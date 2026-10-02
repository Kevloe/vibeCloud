package de.kevloe.vibecloud.wrapper.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.StartServer;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import de.kevloe.vibecloud.wrapper.config.WrapperConfig;
import de.kevloe.vibecloud.wrapper.runtime.ProcessServerRuntime;
import de.kevloe.vibecloud.wrapper.runtime.ServerRuntime;
import de.kevloe.vibecloud.wrapper.template.TemplateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Verwaltet die Server auf diesem Node (PLAN.md Abschnitt 7).
 *
 * <p>Zustaendig fuer Verzeichnisse, Startargumente, die Verbindungsdatei fuer das Plugin
 * und das Aufraeumen danach.
 */
public final class LocalServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(LocalServerManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path root;
    private final WrapperConfig config;
    private final TemplateCache cache;
    private final ServerRuntime runtime;

    private final Map<String, LocalServer> servers = new ConcurrentHashMap<>();

    /**
     * Name der Spurdatei im Serververzeichnis.
     *
     * <p>Mit Punkt am Anfang, damit sie zwischen den Dateien des Servers nicht auffaellt -
     * sie gehoert dem Wrapper, nicht dem Server.
     */
    private static final String MARKER = ".vibecloud-node.json";

    public LocalServerManager(Path root, WrapperConfig config, TemplateCache cache,
                              ServerRuntime runtime) throws IOException {
        this.root = root;
        this.config = config;
        this.cache = cache;
        this.runtime = runtime;
        Files.createDirectories(root.resolve("servers"));
        Files.createDirectories(root.resolve("logs/pending"));
    }

    /**
     * Bereitet das Verzeichnis vor und startet den Server.
     *
     * <p>Reihenfolge wie in PLAN.md Abschnitt 7: Verzeichnis anlegen, Dateien aus dem Cache
     * materialisieren, Verbindungsdatei schreiben, dann der Prozess.
     */
    public void start(StartServer request, TemplateManifest manifest,
                      ServerRuntime.ServerListener listener) throws Exception {

        String name = request.getServerName();
        Path directory = root.resolve("servers").resolve(name);

        if (servers.containsKey(name)) {
            LOG.warn("{} laeuft hier schon - Startbefehl ignoriert", name);
            return;
        }

        // Statische Server behalten ihr Verzeichnis samt Welt. Nur bei dynamischen wird
        // sauber neu aufgesetzt, damit kein Rest eines frueheren Laufs stoert.
        if (!request.getStaticServer() && Files.exists(directory)) {
            deleteRecursively(directory);
        }
        Files.createDirectories(directory);

        cache.materialize(manifest, directory);
        writeConnectionFile(request, directory);
        acceptEula(request, directory);
        // Port und Forwarding-Secret gehoeren nicht ins Template: Sie sind pro Server
        // verschieden und wuerden den inhaltsadressierten Cache aufblaehen.
        ServerConfigurator.apply(request, directory);

        List<String> command = buildCommand(request);
        ServerRuntime.RunningServer running = runtime.start(
                new ServerRuntime.StartRequest(
                        name,
                        directory,
                        command,
                        ServerConfigurator.environmentFor(request),
                        request.getPort(),
                        stopCommandFor(request.getPlatform())),
                listener);

        servers.put(name, new LocalServer(name, request.getGroupName(), request.getPort(),
                request.getStaticServer(), directory, running, Instant.now()));

        writeMarker(directory, new ServerMarker(name, request.getGroupName(),
                request.getStaticServer(), request.getPort(), running.pid(),
                processStartMillis(running.pid())));
    }

    /**
     * Startargumente. Der Port geht bei Paper und Minestom als Argument mit; Velocity liest
     * ihn aus seiner eigenen Konfiguration.
     */
    private List<String> buildCommand(StartServer request) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xms" + request.getMemoryMb() + "M");
        command.add("-Xmx" + request.getMemoryMb() + "M");
        // Netty laedt native Bibliotheken; ohne das Flag warnt Java 25 in jeder Serverkonsole.
        command.add("--enable-native-access=ALL-UNNAMED");
        command.addAll(request.getJvmFlagsList());
        command.add("-jar");
        command.add("server.jar");

        switch (request.getPlatform()) {
            case SERVER_PLATFORM_PAPER -> {
                command.add("--port");
                command.add(Integer.toString(request.getPort()));
                command.add("--nogui");
            }
            case SERVER_PLATFORM_MINESTOM -> {
                command.add("--port");
                command.add(Integer.toString(request.getPort()));
            }
            default -> {
                // Velocity: Port steht in der velocity.toml des Templates.
            }
        }
        return command;
    }

    /** Dieselbe JVM wie der Wrapper - so laeuft nicht versehentlich eine aeltere Java-Version. */
    private static String javaExecutable() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private static String stopCommandFor(ServerPlatform platform) {
        return platform == ServerPlatform.SERVER_PLATFORM_VELOCITY ? "shutdown" : "stop";
    }

    /**
     * Legt das Einmal-Secret ab, mit dem sich das Plugin dieses Servers am Master anmeldet
     * (PLAN.md Abschnitt 13). Gelesen wird die Datei ab M3.
     */
    private void writeConnectionFile(StartServer request, Path directory) throws IOException {
        Map<String, Object> connection = Map.of(
                "masterHost", config.masterHost,
                "masterPort", config.masterPort,
                "masterFingerprint", config.masterFingerprint,
                "serverName", request.getServerName(),
                "groupName", request.getGroupName(),
                "node", config.node,
                "secret", request.getConnectSecret(),
                "port", request.getPort());
        Files.writeString(directory.resolve("vibecloud-connection.json"), GSON.toJson(connection));
    }

    /**
     * Paper startet nicht ohne angenommene EULA. Die Cloud startet den Server im Auftrag des
     * Betreibers, der die EULA mit dem Betrieb des Netzwerks akzeptiert - ohne das waere
     * jeder dynamische Server beim ersten Start sofort wieder aus.
     */
    private void acceptEula(StartServer request, Path directory) throws IOException {
        if (request.getPlatform() != ServerPlatform.SERVER_PLATFORM_PAPER) {
            return;
        }
        Path eula = directory.resolve("eula.txt");
        if (Files.notExists(eula)) {
            Files.writeString(eula, "eula=true\n");
        }
    }

    public Optional<LocalServer> find(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    public List<LocalServer> all() {
        return servers.values().stream()
                .sorted(Comparator.comparing(LocalServer::name))
                .toList();
    }

    public void stop(String name, int graceSeconds) {
        find(name).ifPresent(server -> server.running().stop(graceSeconds));
    }

    public void kill(String name) {
        find(name).ifPresent(server -> server.running().kill());
    }

    public void sendCommand(String name, String line) {
        find(name).ifPresent(server -> server.running().sendLine(line));
    }

    /**
     * Raeumt nach dem Ende eines Servers auf.
     *
     * <p>Bei dynamischen Servern wird der Log <b>vorher</b> nach {@code logs/pending/}
     * gesichert; erst danach darf das Verzeichnis weg. Ist der Master nicht erreichbar,
     * bleibt die Datei dort liegen und wird beim Reconnect nachgeschickt.
     *
     * @return gesicherter Log, falls einer hochzuladen ist
     */
    public Optional<PendingLog> cleanupAfterExit(String name) {
        LocalServer server = servers.remove(name);
        if (server == null) {
            return Optional.empty();
        }
        if (server.staticServer()) {
            // Statische Server behalten Verzeichnis und Log auf dem Node.
            return Optional.empty();
        }

        Optional<PendingLog> archived = archiveLog(server);
        try {
            deleteRecursively(server.directory());
        } catch (IOException exception) {
            LOG.warn("Verzeichnis von {} konnte nicht geloescht werden: {}",
                    name, exception.getMessage());
        }
        return archived;
    }

    /**
     * Sichert den Log und legt eine kleine Begleitdatei mit Server- und Gruppenname daneben.
     *
     * <p>Die Begleitdatei ist noetig, weil der Master den Gruppennamen zum Zeitpunkt des
     * Uploads nicht mehr nachschlagen kann: Der Server ist dann schon aus seiner Registry
     * entfernt. Ohne sie landen die Logs in einem Sammelordner statt bei ihrer Gruppe.
     */
    private Optional<PendingLog> archiveLog(LocalServer server) {
        Path source = server.running().logFile();
        if (Files.notExists(source)) {
            return Optional.empty();
        }
        String fileName = "%s-%d.log.gz".formatted(server.name(), System.currentTimeMillis());
        Path target = root.resolve("logs/pending").resolve(fileName);
        try (var in = Files.newInputStream(source);
             var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
            Files.writeString(metaFileOf(target),
                    GSON.toJson(Map.of("server", server.name(), "group", server.groupName())));
            return Optional.of(new PendingLog(target, server.name(), server.groupName()));
        } catch (IOException exception) {
            LOG.warn("Log von {} konnte nicht gesichert werden: {}",
                    server.name(), exception.getMessage());
            return Optional.empty();
        }
    }

    /** Logs, die noch nicht beim Master angekommen sind. */
    public List<PendingLog> pendingLogs() {
        try (Stream<Path> stream = Files.list(root.resolve("logs/pending"))) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".log.gz"))
                    .sorted()
                    .map(LocalServerManager::readPendingLog)
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static PendingLog readPendingLog(Path file) {
        // Rueckfall, falls die Begleitdatei fehlt: Servername aus "<name>-<zeitstempel>.log.gz".
        String fileName = file.getFileName().toString();
        int separator = fileName.lastIndexOf('-');
        String fallbackServer = separator > 0 ? fileName.substring(0, separator) : fileName;
        Path meta = metaFileOf(file);
        if (Files.isRegularFile(meta)) {
            try {
                Map<String, Object> values = GSON.fromJson(Files.readString(meta),
                        new com.google.gson.reflect.TypeToken<Map<String, Object>>() { }.getType());
                Object server = values.get("server");
                Object group = values.get("group");
                return new PendingLog(file,
                        server == null ? fallbackServer : String.valueOf(server),
                        group == null ? "" : String.valueOf(group));
            } catch (IOException | RuntimeException exception) {
                LOG.debug("Begleitdatei {} unlesbar", meta.getFileName());
            }
        }
        return new PendingLog(file, fallbackServer, "");
    }

    public static Path metaFileOf(Path logFile) {
        return logFile.resolveSibling(logFile.getFileName() + ".meta");
    }

    /** Ein gesicherter Log, der noch zum Master gehoert. */
    public record PendingLog(Path file, String serverName, String groupName) {
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOG.debug("{} nicht loeschbar: {}", path, exception.getMessage());
                }
            });
        }
    }

    /**
     * Faehrt alle Server dieses Nodes herunter und raeumt auf.
     *
     * <p>Der Wrapper besitzt die Prozesse auf seinem Node: Geht er, gehen sie mit. Sonst
     * bleiben Prozesse ohne Aufsicht zurueck, halten ihre Ports belegt, und der Master
     * haelt Server fuer laufend, die niemand mehr steuert.
     *
     * <p>Nicht zu verwechseln mit einem <b>Master</b>-Neustart: Dabei laufen die Server
     * weiter und werden beim Reconnect adoptiert (PLAN.md Abschnitt 7). Hier geht der
     * Wrapper selbst, und mit ihm der Besitzer der Prozesse.
     *
     * <p>Parallel, weil {@code stop} bis zum Ende des Prozesses wartet - bei fuenf Servern
     * waere das sonst fuenfmal die Gnadenfrist hintereinander.
     *
     * @return gesicherte Logs; hochladen kann sie erst der naechste Start
     */
    public List<PendingLog> shutdownAll(int graceSeconds) {
        List<LocalServer> running = List.copyOf(servers.values());
        if (running.isEmpty()) {
            return List.of();
        }
        LOG.info("Faehre {} Server auf diesem Node herunter ...", running.size());

        List<Thread> stopping = running.stream()
                .map(server -> Thread.ofVirtual().name("stop-" + server.name())
                        .start(() -> stopQuietly(server, graceSeconds)))
                .toList();

        for (Thread thread : stopping) {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        List<PendingLog> archived = new ArrayList<>();
        running.forEach(server -> cleanupAfterExit(server.name()).ifPresent(archived::add));

        LOG.info("{} Server beendet, {} Log(s) fuer den naechsten Start gesichert",
                running.size(), archived.size());
        return archived;
    }

    private void stopQuietly(LocalServer server, int graceSeconds) {
        try {
            server.running().stop(graceSeconds);
        } catch (RuntimeException exception) {
            LOG.warn("{} liess sich nicht regulaer beenden: {}",
                    server.name(), exception.getMessage());
            server.running().kill();
        }
    }

    // ---------------------------------------------------------------- Reste eines Vorlaufs

    /**
     * Raeumt auf, was ein hart beendeter Vorgaenger hinterlassen hat.
     *
     * <p>Beim Start aufzurufen. Bei einem harten Abbruch laeuft kein Shutdown-Hook: Die
     * Gameserver leben weiter, und ihre Verzeichnisse stehen noch da.
     *
     * <p>Erkannt wird das an der Spurdatei im Serververzeichnis. Fehlt sie, wird
     * <b>nichts</b> geloescht - ein Verzeichnis ohne Spur koennte die Welt eines
     * statischen Servers sein, und die ist nicht wiederherstellbar.
     *
     * @return Anzahl aufgeraeumter Verzeichnisse
     */
    public int cleanupLeftovers() {
        Path serverRoot = root.resolve("servers");
        if (Files.notExists(serverRoot)) {
            return 0;
        }
        int cleaned = 0;

        try (Stream<Path> directories = Files.list(serverRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Optional<ServerMarker> marker = readMarker(directory);

                if (marker.isEmpty()) {
                    LOG.warn("{} hat keine Spurdatei - bleibt unangetastet. Falls es ein "
                             + "Rest ist, von Hand loeschen.", directory.getFileName());
                    continue;
                }
                ServerMarker found = marker.get();
                killLeftoverProcess(found);

                if (found.staticServer()) {
                    // Welt und Konfiguration bleiben: Der Master bindet einen statischen
                    // Server wieder an genau diesen Node.
                    LOG.info("{} ist statisch - Verzeichnis bleibt", found.server());
                    continue;
                }
                try {
                    deleteRecursively(directory);
                    cleaned++;
                    LOG.info("Rest von {} aufgeraeumt", found.server());
                } catch (IOException exception) {
                    LOG.warn("Verzeichnis {} nicht loeschbar: {}",
                            directory.getFileName(), exception.getMessage());
                }
            }
        } catch (IOException exception) {
            LOG.warn("servers/ nicht lesbar: {}", exception.getMessage());
        }
        return cleaned;
    }

    /**
     * Beendet einen Prozess, der den Vorlauf ueberlebt hat.
     *
     * <p>Die Prozesskennung allein genuegt nicht: Das Betriebssystem gibt sie wieder aus,
     * und dann traefe es einen fremden Prozess. Deshalb muss auch die Startzeit passen -
     * zwei Prozesse mit gleicher Kennung und gleicher Startzeit gibt es nicht.
     */
    private void killLeftoverProcess(ServerMarker marker) {
        if (marker.pid() <= 0) {
            return;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(marker.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return;
        }
        long startMillis = handle.get().info().startInstant()
                .map(Instant::toEpochMilli)
                .orElse(-1L);

        if (marker.processStartMillis() > 0
            && Math.abs(startMillis - marker.processStartMillis()) > 2000) {
            LOG.warn("Kennung {} ist inzwischen ein anderer Prozess - nicht angefasst",
                    marker.pid());
            return;
        }
        LOG.warn("{} lief noch aus einem frueheren Wrapper-Lauf (Kennung {}) - wird beendet",
                marker.server(), marker.pid());
        handle.get().destroy();
        try {
            handle.get().onExit()
                    .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .toCompletableFuture()
                    .join();
        } catch (RuntimeException exception) {
            LOG.warn("{} reagiert nicht - hartes Beenden", marker.server());
            handle.get().destroyForcibly();
        }
    }

    private void writeMarker(Path directory, ServerMarker marker) {
        try {
            Files.writeString(directory.resolve(MARKER), GSON.toJson(marker));
        } catch (IOException exception) {
            // Ohne Spurdatei laeuft der Betrieb weiter, nur das Aufraeumen nach einem
            // harten Abbruch nicht mehr.
            LOG.warn("Spurdatei fuer {} nicht geschrieben: {}",
                    marker.server(), exception.getMessage());
        }
    }

    private Optional<ServerMarker> readMarker(Path directory) {
        Path file = directory.resolve(MARKER);
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    GSON.fromJson(Files.readString(file), ServerMarker.class));
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Spurdatei {} nicht lesbar: {}", file, exception.getMessage());
            return Optional.empty();
        }
    }

    private static long processStartMillis(long pid) {
        if (pid <= 0) {
            return -1;
        }
        return ProcessHandle.of(pid)
                .flatMap(handle -> handle.info().startInstant())
                .map(Instant::toEpochMilli)
                .orElse(-1L);
    }

    /**
     * Was im Serververzeichnis vermerkt wird.
     *
     * <p>Damit ein neuer Wrapper weiss, was er vor sich hat: ob er das Verzeichnis loeschen
     * darf und welcher Prozess dazu gehoerte.
     */
    public record ServerMarker(
            String server,
            String group,
            boolean staticServer,
            int port,
            long pid,
            long processStartMillis) {
    }

    /** Ein Server auf diesem Node. */
    public record LocalServer(
            String name,
            String groupName,
            int port,
            boolean staticServer,
            Path directory,
            ServerRuntime.RunningServer running,
            Instant startedAt) {

        public ServerState state() {
            return running().isAlive() ? ServerState.RUNNING : ServerState.STOPPED;
        }
    }

    public ServerRuntime runtime() {
        return runtime;
    }

    public void closeRuntime() {
        if (runtime instanceof ProcessServerRuntime process) {
            process.shutdown();
        }
    }
}
