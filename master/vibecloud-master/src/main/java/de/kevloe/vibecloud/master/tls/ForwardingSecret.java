package de.kevloe.vibecloud.master.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Das Velocity-Forwarding-Secret (PLAN.md Abschnitt 13).
 *
 * <p>Proxy und Gameserver brauchen dasselbe Secret, sonst lehnt der Gameserver jede
 * Verbindung ab. Der Master erzeugt es einmal und verteilt es automatisch - manuelles
 * Kopieren auf jeden Server waere genau die Art Handarbeit, die irgendwann vergessen wird.
 *
 * <p>Das Secret geht nie ueber einen ungesicherten Kanal: Es kommt im TLS-geschuetzten
 * Startbefehl an und landet nur in Verzeichnissen, die dem Server selbst gehoeren.
 */
public final class ForwardingSecret {

    private static final Logger LOG = LoggerFactory.getLogger(ForwardingSecret.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FILE_NAME = "forwarding.secret";

    private ForwardingSecret() {
    }

    /** Laedt das Secret oder erzeugt es beim ersten Start. */
    public static String loadOrCreate(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(FILE_NAME);

        if (Files.exists(file)) {
            String secret = Files.readString(file).trim();
            if (!secret.isEmpty()) {
                return secret;
            }
            LOG.warn("{} war leer - es wird ein neues Secret erzeugt", file);
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getEncoder().withoutPadding().encodeToString(bytes);

        Files.writeString(file, secret);
        restrictToOwner(file);
        LOG.info("Forwarding-Secret erzeugt und in {} abgelegt", file);
        return secret;
    }

    /**
     * Erzeugt ein neues Secret. Wirkt erst nach einem Neustart aller Server - vorher passen
     * Proxy und Gameserver nicht mehr zusammen.
     */
    public static String rotate(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        Files.deleteIfExists(file);
        LOG.warn("Forwarding-Secret wird erneuert. Alle Server muessen neu starten, "
                 + "sonst lehnen sie Verbindungen vom Proxy ab.");
        return loadOrCreate(directory);
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException exception) {
            LOG.debug("Dateirechte fuer {} nicht setzbar (Windows?)", path.getFileName());
        }
    }
}
