package de.kevloe.vibecloud.wrapper;

import de.kevloe.vibecloud.api.outbox.FileOutbox;
import de.kevloe.vibecloud.api.outbox.Outbox;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.common.config.JsonConfig;
import de.kevloe.vibecloud.wrapper.config.WrapperConfig;
import de.kevloe.vibecloud.wrapper.grpc.MasterConnection;
import de.kevloe.vibecloud.wrapper.runtime.ProcessServerRuntime;
import de.kevloe.vibecloud.wrapper.server.LocalServerManager;
import de.kevloe.vibecloud.sftp.SftpGateway;
import de.kevloe.vibecloud.wrapper.template.TemplateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Einstiegspunkt des Wrappers. */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Path workingDirectory = Path.of(args.length > 0 ? args[0] : ".");

        WrapperConfig config = JsonConfig.loadOrCreate(
                workingDirectory.resolve("wrapper.json"), WrapperConfig.class, new WrapperConfig());
        if (config == null) {
            LOG.warn("Die passenden Werte gibt der Master mit 'node add <name>' aus.");
            return;
        }
        if (config.token.isBlank() || config.masterFingerprint.isBlank()) {
            LOG.error("token und masterFingerprint muessen in der wrapper.json gesetzt sein. "
                      + "Beides gibt der Master mit 'node add <name>' aus.");
            return;
        }

        LOG.info("vibeCloud Wrapper startet als Node {} (Protokoll-Version {})",
                config.node, VibeCloud.API_VERSION);

        ProcessServerRuntime runtime = new ProcessServerRuntime();

        try (Outbox outbox = new FileOutbox(workingDirectory.resolve("outbox"))) {
            TemplateCache cache = new TemplateCache(workingDirectory.resolve("cache"));
            LocalServerManager servers =
                    new LocalServerManager(workingDirectory, config, cache, runtime);

            LOG.info("Server-Laufzeit: {}", runtime.describe());

            // Vor dem Verbinden: Was ein hart beendeter Vorgaenger zurueckgelassen hat,
            // gehoert weg. Sonst belegen Prozesse ohne Aufsicht die Ports, und der Master
            // bekommt Server gemeldet, die niemand mehr steuert.
            int leftovers = servers.cleanupLeftovers();
            if (leftovers > 0) {
                LOG.info("{} Verzeichnis(se) aus einem frueheren Lauf aufgeraeumt", leftovers);
            }

            try (MasterConnection connection =
                         new MasterConnection(config, outbox, servers, cache);
                 SftpGateway _ = startSftp(config, workingDirectory, servers, connection)) {

                // Geht der Wrapper, gehen seine Server mit: Er ist der Besitzer der
                // Prozesse auf diesem Node. Zurueckgelassene Prozesse wuerden ihre Ports
                // halten, und der Master hielte Server fuer laufend, die niemand mehr
                // steuert.
                //
                // Das ist nicht derselbe Fall wie ein Master-Neustart - dort laufen die
                // Server weiter und werden beim Reconnect adoptiert (PLAN.md Abschnitt 7).
                //
                // Reihenfolge: erst die Server, dann die Verbindung. So erreichen die
                // Zustandsmeldungen den Master noch, bevor der Kanal zugeht.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    servers.shutdownAll(config.stopGraceSeconds);
                    closeQuietly(connection);
                }, "wrapper-shutdown"));

                connection.runForever();
            }
        } catch (Exception exception) {
            LOG.error("Wrapper konnte nicht gestartet werden", exception);
            System.exit(1);
        } finally {
            runtime.shutdown();
        }
        LOG.info("Wrapper beendet - die Gameserver dieses Nodes sind mit ihm gegangen");
    }

    /**
     * Startet den SFTP-Zugang, falls eingeschaltet.
     *
     * <p>Vor dem Verbinden zum Master: Der Port geht mit der Anmeldung mit, damit
     * {@code sftp servers} dort die richtige Adresse zeigt. Anmelden kann sich trotzdem
     * erst jemand, wenn die Verbindung steht - bis dahin lautet jede Antwort nein.
     *
     * <p>Laesst sich der Port nicht oeffnen, laeuft der Wrapper ohne SFTP weiter. Die
     * Gameserver sind wichtiger als der Dateizugang zu ihnen.
     *
     * @return {@code null}, wenn kein SFTP laeuft
     */
    private static SftpGateway startSftp(WrapperConfig config, Path workingDirectory,
                                         LocalServerManager servers,
                                         MasterConnection connection) {
        if (!config.sftp.enabled) {
            LOG.info("SFTP ist aus (sftp.enabled in der wrapper.json)");
            return null;
        }
        SftpGateway gateway = new SftpGateway("statische Server", config.sftp.bindAddress, config.sftp.port,
                workingDirectory.resolve("secrets").resolve("sftp-host.key"),
                servers::staticDirectory, connection::authenticateSftp);
        try {
            gateway.start();
            connection.announceSftp(gateway.port(), gateway.fingerprint());
            return gateway;
        } catch (java.io.IOException exception) {
            LOG.error("SFTP konnte auf {}:{} nicht starten ({}) - der Wrapper laeuft ohne "
                      + "weiter", config.sftp.bindAddress, config.sftp.port,
                    exception.getMessage());
            gateway.close();
            return null;
        }
    }

    /**
     * Schliesst die Verbindung beim Herunterfahren.
     *
     * <p>Im Shutdown-Hook darf nichts hochkommen: Eine Ausnahme hier wuerde die restlichen
     * Aufraeumschritte verhindern.
     */
    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception exception) {
            LOG.debug("Verbindung nicht sauber geschlossen", exception);
        }
    }

    private Main() {
    }
}
