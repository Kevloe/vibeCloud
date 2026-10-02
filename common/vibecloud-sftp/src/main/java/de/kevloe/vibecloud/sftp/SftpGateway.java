package de.kevloe.vibecloud.sftp;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * SFTP-Zugang zu Verzeichnissen der Cloud - dort, wo sie liegen.
 *
 * <p>Zwei Stellen bieten ihn an, mit demselben Code: Der <b>Wrapper</b> fuer die
 * Verzeichnisse der statischen Server seines Nodes, der <b>Master</b> fuer die Templates
 * der Gruppen. Die Dateien liegen an verschiedenen Orten, und ein Umweg ueber den jeweils
 * anderen hiesse, jede Welt durch den gRPC-Kanal zu schieben.
 *
 * <p><b>Ein Lauscher, nicht einer je Verzeichnis.</b> Der Benutzername sagt, wohin es
 * geht: {@code <zugang>.<ziel>}, etwa {@code Kevloe.survival-1} am Wrapper oder
 * {@code Kevloe.lobby} am Master. Jede Sitzung ist in genau dieses Verzeichnis
 * eingesperrt. Ein Port je Ziel waere ein Portbereich mehr, den jemand in der Firewall
 * pflegen muesste.
 *
 * <p><b>Hier wird nichts entschieden.</b> Geprueft wird nur, ob es das Ziel an dieser
 * Stelle gibt; ob jemand hinein darf, sagt die {@link SftpAuthority} - am Ende immer der
 * Master. Ist er nicht erreichbar, kommt niemand herein: Ein Ausfall darf nie Rechte
 * erweitern.
 *
 * <p><b>Nur SFTP.</b> Keine Shell, keine Befehle, keine Port-Weiterleitung - der Dienst
 * spricht SSH nur, weil SFTP darauf aufsetzt.
 */
public final class SftpGateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SftpGateway.class);

    /** Wohin die Sitzung eingesperrt ist - gesetzt bei der Anmeldung. */
    static final AttributeRepository.AttributeKey<Path> JAIL =
            new AttributeRepository.AttributeKey<>();

    /** Nach so vielen Fehlversuchen von einer Adresse ist fuer {@link #BLOCK} Ruhe. */
    private static final int MAX_FAILURES = 5;
    private static final Duration BLOCK = Duration.ofMinutes(10);

    private final SshServer server;
    private final Path hostKeyFile;
    private final SftpAuthority authority;
    private final String purpose;
    private final Function<String, Optional<Path>> directories;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private volatile String fingerprint = "";

    /**
     * @param purpose     wofuer dieser Zugang da ist, fuer das Log: "statische Server",
     *                    "Templates"
     * @param hostKeyFile wo der Host-Schluessel liegt; fehlt er, wird er erzeugt
     * @param directories Ziel -> Verzeichnis, leer wenn es das Ziel hier nicht gibt. Wird
     *                    <b>vor</b> der Anmeldung gefragt und darf deshalb nichts anlegen;
     *                    ein fehlendes Verzeichnis entsteht erst nach einer gelungenen
     * @param authority   die Entscheidung des Masters
     */
    public SftpGateway(String purpose, String bindAddress, int port, Path hostKeyFile,
                       Function<String, Optional<Path>> directories,
                       SftpAuthority authority) {
        this.purpose = purpose;
        this.authority = authority;
        this.directories = directories;
        this.hostKeyFile = hostKeyFile;

        server = SshServer.setUpDefaultServer();
        server.setHost(bindAddress);
        server.setPort(port);
        // Der Schluessel bleibt ueber Neustarts derselbe. Ein neuer bei jedem Start
        // saehe fuer jeden Client aus wie ein Angriff auf die Verbindung.
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));

        // Genau ein Weg herein: Passwort, geprueft vom Master.
        server.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        server.setPasswordAuthenticator(this::authenticate);

        // Nichts ausser SFTP.
        server.setShellFactory(null);
        server.setCommandFactory(null);
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder()
                .withFileSystemAccessor(new UnsharingAccessor())
                .build()));
        server.setFileSystemFactory(new JailFactory());

        CoreModuleProperties.MAX_AUTH_REQUESTS.set(server, 3);
        CoreModuleProperties.AUTH_TIMEOUT.set(server, Duration.ofSeconds(30));
        CoreModuleProperties.IDLE_TIMEOUT.set(server, Duration.ofMinutes(15));
        // Sonst nennt die Begruessung Bibliothek und Version - ein Geschenk an jeden, der
        // nach einer bestimmten Luecke sucht.
        CoreModuleProperties.SERVER_IDENTIFICATION.set(server, "vibeCloud");
    }

    public void start() throws IOException {
        if (hostKeyFile.getParent() != null) {
            Files.createDirectories(hostKeyFile.getParent());
        }
        server.start();
        LOG.info("SFTP fuer {} lauscht auf {}:{} - Benutzername ist <zugang>.<ziel>, "
                 + "Zugaenge gibt es mit 'sftp create' oder im Dashboard",
                purpose, server.getHost(), server.getPort());
        // Beim ersten Verbinden fragt der Client, ob dieser Schluessel stimmt. Ohne die
        // Zeile hier koennte das niemand beantworten.
        try {
            for (KeyPair key : server.getKeyPairProvider().loadKeys(null)) {
                fingerprint = KeyUtils.getFingerPrint(key.getPublic());
                LOG.info("SFTP-Host-Schluessel: {}", fingerprint);
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Host-Schluessel nicht lesbar", exception);
        }
        try {
            // Der private Schluessel geht nur diesen Dienst etwas an. Wer ihn lesen kann,
            // kann sich gegenueber jedem Client als er ausgeben.
            Files.setPosixFilePermissions(hostKeyFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException exception) {
            // Kein POSIX-Dateisystem (Windows) - dort regeln das die Rechte des Ordners.
        }
    }

    /**
     * Fingerprint des Host-Schluessels ({@code SHA256:...}), wie ein Client ihn beim ersten
     * Verbinden anzeigt. Leer vor {@link #start()}.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /** Der Port, auf dem tatsaechlich gelauscht wird - bei Port 0 waehlt das System. */
    public int port() {
        return server.getPort();
    }

    // ---------------------------------------------------------------- Anmeldung

    private boolean authenticate(String username, String password, ServerSession session) {
        String ip = session.getClientAddress() instanceof InetSocketAddress address
                ? address.getAddress().getHostAddress()
                : "unbekannt";

        if (isBlocked(ip)) {
            LOG.debug("SFTP-Anmeldung von {} abgelehnt - zu viele Fehlversuche", ip);
            return false;
        }

        // <zugang>.<ziel>, getrennt am letzten Punkt: Ein Server- oder Gruppenname hat
        // keinen, ein Zugang kann einen haben (Bedrock-Spieler heissen ".Name").
        int separator = username.lastIndexOf('.');
        if (separator <= 0 || separator == username.length() - 1) {
            return reject(ip, username, "Benutzername ist nicht <zugang>.<ziel>");
        }
        String account = username.substring(0, separator);
        String target = username.substring(separator + 1);

        // Was sich ohne Passwort pruefen laesst, wird zuerst geprueft. So erreicht der
        // Dauerbeschuss mit "root" und "admin", den jeder offene SSH-Port abbekommt, die
        // Pruefung der Zugaenge gar nicht.
        Optional<Path> directory;
        try {
            directory = directories.apply(target);
        } catch (RuntimeException exception) {
            return reject(ip, username, "Ziel nicht pruefbar: " + exception.getMessage());
        }
        if (directory.isEmpty()) {
            return reject(ip, username, "dieses Ziel gibt es hier nicht");
        }

        SftpAuthority.Decision decision;
        try {
            decision = authority.check(account, target, password, ip);
        } catch (RuntimeException exception) {
            return reject(ip, username, "Pruefung fehlgeschlagen: " + exception.getMessage());
        }
        if (!decision.allowed()) {
            return reject(ip, username, decision.detail());
        }

        try {
            // Erst jetzt anlegen: Vor der Anmeldung entstuende sonst fuer jeden geratenen
            // Namen ein Verzeichnis.
            Files.createDirectories(directory.get());
            session.setAttribute(JAIL, directory.get().toRealPath());
        } catch (IOException exception) {
            return reject(ip, username, "Verzeichnis nicht lesbar: " + exception.getMessage());
        }
        failures.remove(ip);
        LOG.info("SFTP: {} von {} angemeldet", username, ip);
        return true;
    }

    private boolean reject(String ip, String username, String reason) {
        if (failures.size() > 10_000) {
            // Ein offener SSH-Port sieht im Lauf der Zeit sehr viele Adressen - die
            // abgelaufenen sollen den Speicher nicht behalten.
            failures.values().removeIf(Failures::isExpired);
        }
        failures.compute(ip, (key, current) ->
                current == null || current.isExpired() ? new Failures() : current.another());
        LOG.warn("SFTP: Anmeldung von {} als '{}' abgelehnt ({})", ip, username, reason);
        return false;
    }

    private boolean isBlocked(String ip) {
        Failures current = failures.get(ip);
        if (current == null) {
            return false;
        }
        if (current.isExpired()) {
            failures.remove(ip, current);
            return false;
        }
        return current.count() >= MAX_FAILURES;
    }

    /** Fehlversuche einer Adresse. Nur im Speicher - nach einem Neustart zaehlt es neu. */
    private record Failures(int count, Instant since) {

        Failures() {
            this(1, Instant.now());
        }

        Failures another() {
            return new Failures(count + 1, since);
        }

        boolean isExpired() {
            return Duration.between(since, Instant.now()).compareTo(BLOCK) > 0;
        }
    }

    // ---------------------------------------------------------------- Dateisystem

    /**
     * Sperrt jede Sitzung in das Verzeichnis ihres Servers.
     *
     * <p>Das Verzeichnis haengt an der Sitzung und nicht am Benutzernamen: Zwei
     * Anmeldungen desselben Namens duerfen sich nichts teilen, was eine von beiden
     * ueberschreiben koennte.
     */
    private static final class JailFactory implements FileSystemFactory {

        @Override
        public Path getUserHomeDir(SessionContext session) throws IOException {
            Path jail = session.getAttribute(JAIL);
            if (jail == null) {
                throw new IOException("Sitzung ohne Verzeichnis");
            }
            return jail;
        }

        @Override
        public FileSystem createFileSystem(SessionContext session) throws IOException {
            return new RootedFileSystemProvider().newFileSystem(getUserHomeDir(session), Map.of());
        }
    }

    @Override
    public void close() {
        try {
            server.stop(true);
        } catch (IOException exception) {
            LOG.debug("SFTP nicht sauber beendet", exception);
        }
    }
}
