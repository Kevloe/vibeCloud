package de.kevloe.vibecloud.wrapper.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Startet Gameserver als eigene Prozesse (PLAN.md Abschnitt 7).
 *
 * <p>Jeder Server bekommt einen eigenen Thread fuer stdout und stderr. Das ist Pflicht und
 * kein Komfort: Laest niemand die Ausgabe, laeuft der Pipe-Puffer des Betriebssystems voll
 * und der Server bleibt stehen - mitten im Spiel, ohne Fehlermeldung.
 *
 * <p>Alle Threads sind Virtual Threads. Bei 20 Servern sind das 40 blockierende Leser,
 * die in einem begrenzten Pool nicht aufgehen wuerden.
 */
public final class ProcessServerRuntime implements ServerRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessServerRuntime.class);

    /** Wie oft geprueft wird, ob der Port schon Verbindungen annimmt. */
    private static final Duration READY_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);

    private final ExecutorService pumps = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("server-io-", 0).factory());

    @Override
    public String describe() {
        return "Prozess (ProcessBuilder)";
    }

    @Override
    public RunningServer start(StartRequest request, ServerListener listener) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(request.command())
                .directory(request.workingDirectory().toFile());
        builder.environment().putAll(request.environment());
        // stderr getrennt lassen, damit Fehler im Log als solche erkennbar bleiben.
        builder.redirectErrorStream(false);

        Process process = builder.start();
        Path logFile = request.workingDirectory().resolve("logs/wrapper-console.log");
        Files.createDirectories(logFile.getParent());

        LOG.info("{} gestartet (PID {}): {}",
                request.serverName(), process.pid(), String.join(" ", request.command()));

        ProcessRunningServer running = new ProcessRunningServer(
                request.serverName(), process, logFile, request.stopCommand());

        pumps.execute(() -> pump(process.getInputStream(), request.serverName(), false,
                listener, logFile));
        pumps.execute(() -> pump(process.getErrorStream(), request.serverName(), true,
                listener, logFile));
        pumps.execute(() -> awaitExit(process, request.serverName(), listener));
        pumps.execute(() -> awaitPort(request, process, listener));

        return running;
    }

    /** Liest einen Strom zeilenweise, meldet ihn weiter und schreibt ihn mit. */
    private void pump(InputStream stream, String serverName, boolean errorStream,
                      ServerListener listener, Path logFile) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
             Writer log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            String line;
            while ((line = reader.readLine()) != null) {
                listener.onLine(serverName, line, errorStream);
                log.write(line);
                log.write(System.lineSeparator());
                // Ungepuffert mitschreiben: Nach einem Absturz will man genau die letzten
                // Zeilen sehen, und die waeren sonst noch im Puffer.
                log.flush();
            }
        } catch (IOException exception) {
            LOG.debug("Ausgabestrom von {} beendet: {}", serverName, exception.getMessage());
        }
    }

    private void awaitExit(Process process, String serverName, ServerListener listener) {
        try {
            int exitCode = process.waitFor();
            LOG.info("{} beendet mit Exit-Code {}", serverName, exitCode);
            listener.onExit(serverName, exitCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Meldet den Server als bereit, sobald sein Port Verbindungen annimmt.
     *
     * <p>Absichtlich nicht ueber einen Text in der Konsole: "Done (12.3s)!" gibt es nur bei
     * Paper, nicht bei Velocity oder Minestom, und es aendert sich zwischen Versionen.
     * Ein offener Port gilt ueberall.
     */
    private void awaitPort(StartRequest request, Process process, ServerListener listener) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();

        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", request.port()), 1000);
                LOG.info("{} nimmt Verbindungen auf Port {} an", request.serverName(),
                        request.port());
                listener.onReady(request.serverName());
                return;
            } catch (IOException exception) {
                sleep(READY_POLL_INTERVAL);
            }
        }
        if (process.isAlive()) {
            LOG.warn("{} hoert nach {} Minuten noch nicht auf Port {} - laeuft der Server?",
                    request.serverName(), READY_TIMEOUT.toMinutes(), request.port());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        pumps.close();
    }

    /** Ein laufender Prozess. */
    private static final class ProcessRunningServer implements RunningServer {

        private final String serverName;
        private final Process process;
        private final Path logFile;
        private final String stopCommand;
        private final Writer stdin;

        ProcessRunningServer(String serverName, Process process, Path logFile,
                             String stopCommand) {
            this.serverName = serverName;
            this.process = process;
            this.logFile = logFile;
            this.stopCommand = stopCommand;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        @Override
        public String serverName() {
            return serverName;
        }

        @Override
        public synchronized void sendLine(String line) {
            if (!process.isAlive()) {
                return;
            }
            try {
                stdin.write(line);
                stdin.write("\n");
                stdin.flush();
            } catch (IOException exception) {
                LOG.debug("Eingabe an {} fehlgeschlagen: {}", serverName, exception.getMessage());
            }
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public void stop(int graceSeconds) {
            sendLine(stopCommand);
            try {
                if (process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                    return;
                }
                LOG.warn("{} hat nach {} s nicht reagiert - wird beendet",
                        serverName, graceSeconds);
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    LOG.warn("{} reagiert auch darauf nicht - hartes Beenden", serverName);
                    kill();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                kill();
            }
        }

        @Override
        public void kill() {
            // Auch die Kindprozesse mitnehmen: Manche Server-Starter legen einen
            // Wrapper-Prozess davor, der sonst als Waise weiterlaeuft.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public Path logFile() {
            return logFile;
        }
    }
}
