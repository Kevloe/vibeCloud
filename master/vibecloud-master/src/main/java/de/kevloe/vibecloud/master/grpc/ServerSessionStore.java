package de.kevloe.vibecloud.master.grpc;

import de.kevloe.vibecloud.api.identity.ServerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Einmal-Secrets und Sitzungs-Token der Gameserver-Plugins (PLAN.md Abschnitt 13).
 *
 * <p>Zwei Stufen, und die Trennung ist der Punkt:
 * <ul>
 *   <li><b>Einmal-Secret</b> - wird beim Serverstart erzeugt, liegt in
 *       {@code vibecloud-connection.json} und gilt nur fuer {@code RegisterServer}.
 *       Einmal verwendbar, verfaellt nach kurzer Zeit. Wer die Datei spaeter findet,
 *       kann damit nichts mehr anfangen.</li>
 *   <li><b>Sitzungs-Token</b> - gilt fuer alle weiteren Aufrufe, solange der Server lebt.</li>
 * </ul>
 *
 * <p>Ein Einmal-Secret als Dauer-Zugangsdaten waere ein Widerspruch: gRPC schickt die
 * Metadaten bei <i>jedem</i> Aufruf mit, und beim zweiten waere das Secret schon verbraucht.
 */
public final class ServerSessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(ServerSessionStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Wie lange ein Secret nach dem Serverstart gueltig bleibt. */
    private static final Duration SECRET_TTL = Duration.ofSeconds(120);

    private final Map<String, PendingSecret> pending = new ConcurrentHashMap<>();
    private final Map<String, ServerIdentity> sessions = new ConcurrentHashMap<>();

    /** Hinterlegt das Secret, das der Wrapper in die Verbindungsdatei des Servers schreibt. */
    public void registerSecret(String serverName, String node, String secret) {
        pending.put(serverName, new PendingSecret(secret, node, Instant.now().plus(SECRET_TTL)));
        purgeExpired();
    }

    /**
     * Loest ein Einmal-Secret gegen ein Sitzungs-Token ein.
     *
     * @return Token, oder leer wenn das Secret falsch, verbraucht oder abgelaufen ist
     */
    public Optional<String> redeemSecret(String serverName, String secret) {
        PendingSecret entry = pending.get(serverName);
        if (entry == null) {
            LOG.warn("{} wollte sich anmelden, aber dafuer liegt kein Secret vor - "
                     + "bereits benutzt oder der Server wurde nicht von dieser Cloud gestartet",
                    serverName);
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            pending.remove(serverName);
            LOG.warn("Secret von {} ist abgelaufen ({} s Gueltigkeit). Startet der Server so "
                     + "langsam, oder wurde die Verbindungsdatei kopiert?",
                    serverName, SECRET_TTL.toSeconds());
            return Optional.empty();
        }
        // Zeitkonstanter Vergleich, damit aus der Antwortzeit nichts ableitbar ist.
        if (!MessageDigest.isEqual(entry.secret().getBytes(), secret.getBytes())) {
            LOG.warn("Falsches Secret fuer {}", serverName);
            return Optional.empty();
        }

        // Verbraucht: Ab jetzt gilt nur noch das Sitzungs-Token.
        pending.remove(serverName);

        String token = newToken();
        sessions.put(token, new ServerIdentity(serverName, entry.node()));
        return Optional.of(token);
    }

    /** Identitaet zu einem Sitzungs-Token. */
    public Optional<ServerIdentity> resolveSession(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    /** Beim Stopp eines Servers aufraeumen, damit Token nicht unbegrenzt gueltig bleiben. */
    public void forget(String serverName) {
        pending.remove(serverName);
        sessions.entrySet().removeIf(entry -> entry.getValue().serverName().equals(serverName));
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    private static String newToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public int pendingCount() {
        return pending.size();
    }

    public int sessionCount() {
        return sessions.size();
    }

    private record PendingSecret(String secret, String node, Instant expiresAt) {
    }
}
