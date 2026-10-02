package de.kevloe.vibecloud.master.grpc;

import de.kevloe.vibecloud.api.identity.CallIdentity;
import de.kevloe.vibecloud.api.identity.NodeIdentity;
import de.kevloe.vibecloud.api.identity.ServerIdentity;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.node.NodeRepository;
import de.kevloe.vibecloud.master.node.NodeTokens;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Der <b>einzige</b> Pruefpunkt fuer eingehende Verbindungen (PLAN.md Abschnitt 13).
 *
 * <p>Absichtlich ein Interceptor und nicht Code in jedem Service: Eine zentrale Stelle kann
 * man pruefen, verteilte Pruefungen vergisst man irgendwann an einer Stelle.
 *
 * <p>Geprueft wird in dieser Reihenfolge:
 * <ol>
 *   <li>Node-Name und Token vorhanden</li>
 *   <li>Node existiert und ist aktiv</li>
 *   <li>Quell-IP steht in {@code allowed_ips} (falls gesetzt)</li>
 *   <li>Token passt zum Hash <i>dieses</i> Node-Namens</li>
 * </ol>
 *
 * <p>Erst danach wird die {@link CallIdentity} in den gRPC-Context gelegt. Die
 * Befugnis-Pruefung pro Nachricht baut darauf auf und sitzt im jeweiligen Service.
 */
public final class AuthInterceptor implements ServerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(AuthInterceptor.class);

    public static final Metadata.Key<String> NODE_KEY =
            Metadata.Key.of("vibecloud-node", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of("vibecloud-token", Metadata.ASCII_STRING_MARSHALLER);

    /** Gameserver- und Proxy-Plugins melden sich mit diesen Schluesseln. */
    public static final Metadata.Key<String> SERVER_KEY =
            Metadata.Key.of("vibecloud-server", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> SECRET_KEY =
            Metadata.Key.of("vibecloud-secret", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> SESSION_KEY =
            Metadata.Key.of("vibecloud-session", Metadata.ASCII_STRING_MARSHALLER);

    /** Identitaet der aktuellen Verbindung. Services lesen sie ueber {@link #currentIdentity()}. */
    public static final Context.Key<CallIdentity> IDENTITY = Context.key("vibecloud-identity");

    /** Nur waehrend {@code RegisterServer} gesetzt: das vorgelegte Einmal-Secret. */
    public static final Context.Key<String> CLAIMED_SECRET = Context.key("vibecloud-claimed-secret");

    /**
     * Quell-IP der Verbindung, wie der Master sie sieht.
     *
     * <p>Dient als Adresse eines Nodes, wenn der Wrapper keine eigene angibt - das ist
     * die zuverlaessigste Antwort auf die Frage, wo dieser Node erreichbar ist.
     */
    public static final Context.Key<String> REMOTE_IP = Context.key("vibecloud-remote-ip");

    private final NodeRepository nodes;
    private final ServerSessionStore sessions;
    private final AuditLog audit;
    private final int maxFailedAuths;

    /** Drosselung je Quell-IP, damit Token nicht durchprobiert werden koennen. */
    private final Map<String, Throttle> throttles = new ConcurrentHashMap<>();

    public AuthInterceptor(NodeRepository nodes, ServerSessionStore sessions, AuditLog audit,
                           int maxFailedAuths) {
        this.nodes = nodes;
        this.sessions = sessions;
        this.audit = audit;
        this.maxFailedAuths = maxFailedAuths;
    }

    /** @return Identitaet des laufenden Aufrufs, nie {@code null} innerhalb eines Service */
    public static CallIdentity currentIdentity() {
        CallIdentity identity = IDENTITY.get();
        if (identity == null) {
            throw new IllegalStateException(
                    "Kein authentifizierter Aufruf - laeuft der AuthInterceptor?");
        }
        return identity;
    }

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {

        String remoteIp = remoteIp(call);

        // Plugins melden sich mit Servername plus Secret oder Sitzungs-Token.
        String server = headers.get(SERVER_KEY);
        if (server != null && !server.isBlank()) {
            return interceptServerCall(call, headers, next, server, remoteIp);
        }

        String node = headers.get(NODE_KEY);
        String token = headers.get(TOKEN_KEY);

        if (node == null || node.isBlank() || token == null || token.isBlank()) {
            return deny(call, "Node-Name oder Token fehlt", remoteIp, node);
        }

        Throttle throttle = throttles.computeIfAbsent(remoteIp, key -> new Throttle());
        if (throttle.isBlocked()) {
            return deny(call, "zu viele Fehlversuche, bitte spaeter erneut", remoteIp, node);
        }

        Optional<NodeRepository.NodeRecord> record = nodes.find(node);
        if (record.isEmpty()) {
            // Gleiche Meldung wie bei falschem Token: Von aussen soll nicht erkennbar sein,
            // welche Node-Namen existieren.
            throttle.fail();
            return deny(call, "Anmeldung abgelehnt", remoteIp, node);
        }

        NodeRepository.NodeRecord stored = record.get();
        if (!stored.enabled()) {
            return deny(call, "Node ist deaktiviert", remoteIp, node);
        }

        if (!isIpAllowed(stored, remoteIp)) {
            audit.record("SYSTEM", "node.auth.ip_rejected", node,
                    Map.of("remoteIp", remoteIp, "allowed", stored.allowedIps()));
            LOG.warn("Node {} wollte sich von {} anmelden - IP steht nicht in allowed_ips",
                    node, remoteIp);
            throttle.fail();
            return deny(call, "Anmeldung abgelehnt", remoteIp, node);
        }

        if (!NodeTokens.verify(node, token, stored.tokenHash())) {
            throttle.fail();
            int failed = nodes.recordFailedAuth(node);
            audit.record("SYSTEM", "node.auth.failed", node,
                    Map.of("remoteIp", remoteIp, "failedAuths", failed));

            if (failed >= maxFailedAuths) {
                nodes.setEnabled(node, false);
                LOG.error("Node {} nach {} Fehlversuchen deaktiviert - entweder ein falsch "
                          + "konfigurierter Wrapper oder ein Angriffsversuch von {}",
                        node, failed, remoteIp);
                audit.record("SYSTEM", "node.disabled", node, Map.of("reason", "failed_auths"));
            } else {
                LOG.warn("Falsches Token fuer Node {} von {} ({}/{})",
                        node, remoteIp, failed, maxFailedAuths);
            }
            return deny(call, "Anmeldung abgelehnt", remoteIp, node);
        }

        throttle.reset();
        Context context = Context.current()
                .withValue(IDENTITY, new NodeIdentity(node))
                .withValue(REMOTE_IP, remoteIp);
        return Contexts.interceptCall(context, call, headers, next);
    }

    /**
     * Anmeldung eines Gameserver- oder Proxy-Plugins.
     *
     * <p>Das Einmal-Secret gilt nur fuer {@code RegisterServer}; alles andere braucht das
     * Sitzungs-Token. Die Identitaet kommt aus dem Store, <b>nicht</b> aus den Metadaten -
     * ein Plugin kann sich also nicht als ein anderer Server ausgeben.
     */
    private <R, S> ServerCall.Listener<R> interceptServerCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next,
            String server, String remoteIp) {

        String session = headers.get(SESSION_KEY);
        if (session != null && !session.isBlank()) {
            Optional<ServerIdentity> identity = sessions.resolveSession(session);
            if (identity.isEmpty()) {
                return deny(call, "Sitzung unbekannt oder abgelaufen", remoteIp, server);
            }
            if (!identity.get().serverName().equals(server)) {
                // Token gehoert zu einem anderen Server: Versuch, eine fremde Identitaet
                // zu benutzen. Das ist kein Konfigurationsfehler.
                audit.record("SERVER:" + server, "authz.rejected", server,
                        Map.of("reason", "Sitzung gehoert zu " + identity.get().serverName(),
                                "remoteIp", remoteIp));
                LOG.error("Plugin von {} benutzt die Sitzung von {} - abgelehnt",
                        server, identity.get().serverName());
                return deny(call, "Anmeldung abgelehnt", remoteIp, server);
            }
            Context context = Context.current().withValue(IDENTITY, identity.get());
            return Contexts.interceptCall(context, call, headers, next);
        }

        // Kein Token: Nur die Anmeldung selbst darf mit dem Einmal-Secret arbeiten.
        String secret = headers.get(SECRET_KEY);
        if (secret == null || secret.isBlank()) {
            return deny(call, "Secret oder Sitzungs-Token fehlt", remoteIp, server);
        }
        if (!call.getMethodDescriptor().getFullMethodName().endsWith("RegisterServer")) {
            return deny(call, "Das Einmal-Secret gilt nur fuer die Anmeldung", remoteIp, server);
        }
        // Die Pruefung selbst passiert im Service, weil er das Token zurueckgeben muss.
        // Hier wird nur eine vorlaeufige Identitaet gesetzt, damit der Service weiss,
        // welcher Servername behauptet wird.
        Context context = Context.current()
                .withValue(IDENTITY, new ServerIdentity(server, "?"))
                .withValue(CLAIMED_SECRET, secret);
        return Contexts.interceptCall(context, call, headers, next);
    }

    /** Leere {@code allowed_ips} heisst: keine Einschraenkung konfiguriert. */
    private boolean isIpAllowed(NodeRepository.NodeRecord stored, String remoteIp) {
        if (stored.allowedIps().isEmpty()) {
            return true;
        }
        return stored.allowedIps().stream().anyMatch(allowed -> matches(allowed, remoteIp));
    }

    private boolean matches(String allowed, String remoteIp) {
        // Einzeladressen deckt der Vergleich ab. CIDR-Bereiche kommen mit der
        // Firewall-Verwaltung in M3 dazu, dann an einer Stelle fuer beide Seiten.
        return allowed.equals(remoteIp) || allowed.equals(remoteIp + "/32");
    }

    private <R, S> ServerCall.Listener<R> deny(
            ServerCall<R, S> call, String reason, String remoteIp, String node) {
        LOG.debug("Verbindung von {} abgelehnt (Node {}): {}", remoteIp, node, reason);
        call.close(Status.UNAUTHENTICATED.withDescription(reason), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }

    private static String remoteIp(ServerCall<?, ?> call) {
        SocketAddress address = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return "unbekannt";
    }

    /** Exponentielle Drosselung: Nach jedem Fehlversuch wird die Wartezeit verdoppelt. */
    private static final class Throttle {

        private static final long BASE_DELAY_MILLIS = 500;
        private static final long MAX_DELAY_MILLIS = 60_000;

        private int failures;
        private long blockedUntil;

        synchronized boolean isBlocked() {
            return System.currentTimeMillis() < blockedUntil;
        }

        synchronized void fail() {
            failures++;
            long delay = Math.min(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS * (1L << Math.min(failures, 7)));
            blockedUntil = System.currentTimeMillis() + delay;
        }

        synchronized void reset() {
            failures = 0;
            blockedUntil = 0;
        }
    }
}
