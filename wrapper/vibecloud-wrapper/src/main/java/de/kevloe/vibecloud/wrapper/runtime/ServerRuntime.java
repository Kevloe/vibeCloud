package de.kevloe.vibecloud.wrapper.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Wie ein Gameserver ausgefuehrt wird (PLAN.md Abschnitt 7, Entscheidung 16.3).
 *
 * <p>Die Abstraktion steht von Tag eins, auch wenn es zunaechst nur
 * {@link ProcessServerRuntime} gibt: Eine Docker-Variante soll spaeter daneben treten
 * koennen, ohne dass der restliche Wrapper davon weiss.
 */
public interface ServerRuntime {

    /**
     * Startet den Server.
     *
     * @param request  was gestartet werden soll
     * @param listener bekommt Ausgabezeilen und das Ende des Prozesses gemeldet
     */
    RunningServer start(StartRequest request, ServerListener listener) throws Exception;

    /** Name der Umsetzung, fuer Logs. */
    String describe();

    /**
     * @param workingDirectory fertig vorbereitetes Verzeichnis
     * @param stopCommand      Zeile, die ein regulaeres Herunterfahren ausloest
     */
    record StartRequest(
            String serverName,
            Path workingDirectory,
            List<String> command,
            Map<String, String> environment,
            int port,
            String stopCommand) {
    }

    /** Ein laufender Server. */
    interface RunningServer {

        String serverName();

        /** Schreibt eine Zeile in die Standardeingabe. */
        void sendLine(String line);

        /** Regulaeres Herunterfahren, danach hartes Beenden nach Ablauf der Gnadenfrist. */
        void stop(int graceSeconds);

        /** Sofort beenden. */
        void kill();

        boolean isAlive();

        /** Pfad der Logdatei, falls die Umsetzung eine schreibt. */
        Path logFile();

        /**
         * Betriebssystem-Kennung des Prozesses, {@code -1} wenn es keinen gibt.
         *
         * <p>Wird im Serververzeichnis vermerkt: Nur damit kann ein neu gestarteter
         * Wrapper einen Prozess wiederfinden, den sein hart beendeter Vorgaenger
         * zurueckgelassen hat.
         */
        default long pid() {
            return -1;
        }
    }

    /** Rueckmeldungen eines laufenden Servers. */
    interface ServerListener {

        void onLine(String serverName, String line, boolean errorStream);

        /** Der Port nimmt Verbindungen an - der Server gilt damit als bereit. */
        void onReady(String serverName);

        void onExit(String serverName, int exitCode);
    }
}
