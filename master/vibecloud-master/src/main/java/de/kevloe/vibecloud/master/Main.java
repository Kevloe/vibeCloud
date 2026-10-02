package de.kevloe.vibecloud.master;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Einstiegspunkt des Masters. */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Path workingDirectory = Path.of(args.length > 0 ? args[0] : ".");

        VibeCloudMaster master = new VibeCloudMaster(workingDirectory);

        // Auch bei SIGTERM (systemd stop) sauber herunterfahren, damit die Einzel-Master-Sperre
        // freigegeben wird und die Wrapper ein regulaeres Stream-Ende sehen.
        Runtime.getRuntime().addShutdownHook(new Thread(master::close, "master-shutdown"));

        try {
            if (!master.start()) {
                // Kein Fehler: Erststart mit neu geschriebener Konfiguration, oder es laeuft
                // bereits ein Master. Beides wurde schon erklaert.
                return;
            }
            master.awaitShutdown();
        } catch (Exception exception) {
            LOG.error("Master konnte nicht gestartet werden", exception);
            System.exit(1);
        } finally {
            master.close();
        }
    }

    private Main() {
    }
}
