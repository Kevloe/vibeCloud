package de.kevloe.vibecloud.wrapper.server;

import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.StartServer;
import de.kevloe.vibecloud.wrapper.config.WrapperConfig;
import de.kevloe.vibecloud.wrapper.runtime.ServerRuntime;
import de.kevloe.vibecloud.wrapper.template.TemplateCache;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Herunterfahren des Wrappers.
 *
 * <p>Ohne Signale geprueft, denn genau darauf darf es nicht ankommen: Unter systemd
 * bekommt nur der Wrapper das Signal, nicht die Gameserver. Er muss sie also selbst
 * beenden - sonst laufen sie ohne Aufsicht weiter und halten ihre Ports.
 */
class LocalServerManagerShutdownTest {

    @TempDir
    private Path root;

    private LocalServerManager manager;
    private FakeRuntime runtime;

    @BeforeEach
    void setUp() throws IOException {
        runtime = new FakeRuntime();
        manager = new LocalServerManager(root, new WrapperConfig(),
                new TemplateCache(root.resolve("cache")), runtime);
    }

    @Test
    @DisplayName("Alle Server werden mit der Gnadenfrist beendet")
    void alleServerWerdenBeendet() throws Exception {
        start("lobby-1", false);
        start("lobby-2", false);

        manager.shutdownAll(30);

        assertThat(runtime.stopped).containsOnlyKeys("lobby-1", "lobby-2");
        assertThat(runtime.stopped.values()).containsOnly(30);
        assertThat(manager.all()).isEmpty();
    }

    @Test
    @DisplayName("Dynamische Verzeichnisse verschwinden, statische bleiben")
    void dynamischeVerzeichnisseVerschwinden() throws Exception {
        Path dynamisch = start("lobby-1", false);
        Path statisch = start("survival-1", true);

        manager.shutdownAll(5);

        assertThat(dynamisch).doesNotExist();
        // Die Welt eines statischen Servers bleibt auf seinem Node liegen.
        assertThat(statisch).exists();
    }

    @Test
    @DisplayName("Ein haengender Server wird hart beendet")
    void haengenderServerWirdHartBeendet() throws Exception {
        start("lobby-1", false);
        runtime.hang("lobby-1");

        manager.shutdownAll(1);

        // stopQuietly faengt den Fehler und greift zum harten Mittel - sonst bliebe der
        // Prozess zurueck, und der naechste Start faende seinen Port belegt.
        assertThat(runtime.killed).contains("lobby-1");
        assertThat(manager.all()).isEmpty();
    }

    @Test
    @DisplayName("Ohne laufende Server passiert nichts")
    void ohneServerPassiertNichts() {
        assertThat(manager.shutdownAll(30)).isEmpty();
        assertThat(runtime.stopped).isEmpty();
    }

    @Test
    @DisplayName("Logs dynamischer Server werden fuer den naechsten Start gesichert")
    void logsWerdenGesichert() throws Exception {
        start("lobby-1", false);

        var archived = manager.shutdownAll(5);

        // Hochladen kann sie erst der naechste Lauf - der Master ist beim Herunterfahren
        // schon nicht mehr erreichbar.
        assertThat(archived).hasSize(1);
        assertThat(archived.getFirst().serverName()).isEqualTo("lobby-1");
        assertThat(archived.getFirst().file()).exists();
    }

    /** Startet einen Server ueber den Manager und gibt sein Verzeichnis zurueck. */
    private Path start(String name, boolean staticServer) throws Exception {
        StartServer request = StartServer.newBuilder()
                .setServerName(name)
                .setGroupName(staticServer ? "survival" : "lobby")
                .setPlatform(ServerPlatform.SERVER_PLATFORM_PAPER)
                .setPort(30000)
                .setMemoryMb(1024)
                .setStaticServer(staticServer)
                .build();

        manager.start(request, TemplateManifest.getDefaultInstance(), new ServerRuntime.ServerListener() {

            @Override
            public void onLine(String serverName, String line, boolean errorStream) {
            }

            @Override
            public void onReady(String serverName) {
            }

            @Override
            public void onExit(String serverName, int exitCode) {
            }
        });
        return root.resolve("servers").resolve(name);
    }

    /** Eine Laufzeit, die sich merkt, was mit ihren Servern geschah. */
    private final class FakeRuntime implements ServerRuntime {

        private final Map<String, Integer> stopped = new ConcurrentHashMap<>();
        private final List<String> killed = new ArrayList<>();
        private final List<String> hanging = new ArrayList<>();

        void hang(String name) {
            hanging.add(name);
        }

        @Override
        public String describe() {
            return "Test";
        }

        @Override
        public RunningServer start(StartRequest request, ServerListener listener) {
            Path log = request.workingDirectory().resolve("logs/latest.log");
            try {
                Files.createDirectories(log.getParent());
                Files.writeString(log, "Testlog von " + request.serverName());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }

            return new RunningServer() {

                private volatile boolean alive = true;

                @Override
                public String serverName() {
                    return request.serverName();
                }

                @Override
                public void sendLine(String line) {
                }

                @Override
                public void stop(int graceSeconds) {
                    stopped.put(request.serverName(), graceSeconds);
                    if (hanging.contains(request.serverName())) {
                        // Wie ein Server, der auf das Stop-Kommando nicht reagiert.
                        throw new IllegalStateException("reagiert nicht");
                    }
                    alive = false;
                }

                @Override
                public void kill() {
                    killed.add(request.serverName());
                    alive = false;
                }

                @Override
                public boolean isAlive() {
                    return alive;
                }

                @Override
                public Path logFile() {
                    return log;
                }
            };
        }
    }
}
