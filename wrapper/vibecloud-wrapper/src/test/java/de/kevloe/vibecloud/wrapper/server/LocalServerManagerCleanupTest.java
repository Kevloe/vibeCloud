package de.kevloe.vibecloud.wrapper.server;

import com.google.gson.Gson;
import de.kevloe.vibecloud.wrapper.config.WrapperConfig;
import de.kevloe.vibecloud.wrapper.runtime.ServerRuntime;
import de.kevloe.vibecloud.wrapper.template.TemplateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aufraeumen nach einem harten Abbruch des Wrappers.
 *
 * <p>Der gefaehrliche Teil daran ist nicht das Loeschen, sondern das Nicht-Loeschen: Die
 * Welt eines statischen Servers ist nicht wiederherstellbar.
 */
class LocalServerManagerCleanupTest {

    private static final Gson GSON = new Gson();

    @TempDir
    private Path root;

    private LocalServerManager manager;

    @BeforeEach
    void setUp() throws IOException {
        manager = new LocalServerManager(root, new WrapperConfig(),
                new TemplateCache(root.resolve("cache")), new NoRuntime());
    }

    @Test
    @DisplayName("Ein dynamischer Rest wird geloescht")
    void dynamischerRestWirdGeloescht() throws IOException {
        Path directory = leftover("lobby-1", false);

        assertThat(manager.cleanupLeftovers()).isEqualTo(1);
        assertThat(directory).doesNotExist();
    }

    @Test
    @DisplayName("Ein statischer Server behaelt seine Welt")
    void statischerServerBehaeltSeineWelt() throws IOException {
        Path directory = leftover("survival-1", true);

        assertThat(manager.cleanupLeftovers()).isZero();
        assertThat(directory).exists();
        assertThat(directory.resolve("world/level.dat")).exists();
    }

    @Test
    @DisplayName("Ohne Spurdatei wird nichts angefasst")
    void ohneSpurdateiWirdNichtsAngefasst() throws IOException {
        // Koennte die Welt eines statischen Servers aus einer aelteren Version sein.
        // Loeschen waere unwiederbringlich, also lieber stehen lassen und warnen.
        Path directory = root.resolve("servers/unbekannt");
        Files.createDirectories(directory.resolve("world"));
        Files.writeString(directory.resolve("world/level.dat"), "welt");

        assertThat(manager.cleanupLeftovers()).isZero();
        assertThat(directory).exists();
    }

    @Test
    @DisplayName("Eine unlesbare Spurdatei wird wie eine fehlende behandelt")
    void unlesbareSpurdateiWirdWieFehlendeBehandelt() throws IOException {
        Path directory = root.resolve("servers/kaputt");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(".vibecloud-node.json"), "{kein json");

        assertThat(manager.cleanupLeftovers()).isZero();
        assertThat(directory).exists();
    }

    @Test
    @DisplayName("Mehrere Reste werden in einem Durchgang erledigt")
    void mehrereResteWerdenErledigt() throws IOException {
        Path ersterDynamisch = leftover("lobby-1", false);
        Path zweiterDynamisch = leftover("lobby-2", false);
        Path statisch = leftover("survival-1", true);

        assertThat(manager.cleanupLeftovers()).isEqualTo(2);
        assertThat(ersterDynamisch).doesNotExist();
        assertThat(zweiterDynamisch).doesNotExist();
        assertThat(statisch).exists();
    }

    @Test
    @DisplayName("Ohne servers-Verzeichnis gibt es nichts zu tun")
    void ohneServersVerzeichnisGibtEsNichtsZuTun() {
        // Erster Start auf einem frischen Node.
        assertThat(manager.cleanupLeftovers()).isZero();
    }

    @Test
    @DisplayName("Eine fremde Prozesskennung wird nicht beendet")
    void fremdeProzesskennungWirdNichtBeendet() throws IOException {
        // Die Kennung des Testlaufs selbst, aber mit falscher Startzeit: So sieht es aus,
        // wenn das Betriebssystem eine Kennung neu vergeben hat. Daran darf das
        // Aufraeumen nicht ruehren - sonst beendet es einen fremden Prozess.
        long eigene = ProcessHandle.current().pid();
        Path directory = root.resolve("servers/lobby-9");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(".vibecloud-node.json"), GSON.toJson(
                new LocalServerManager.ServerMarker("lobby-9", "lobby", false, 30009,
                        eigene, 1L)));

        assertThat(manager.cleanupLeftovers()).isEqualTo(1);
        // Der Testlauf lebt noch - sonst waere dieser Satz nie erreicht.
        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }

    // ---------------------------------------------------------------- SFTP

    @Test
    @DisplayName("SFTP gibt es nur in das Verzeichnis eines statischen Servers")
    void sftpNurFuerStatischeServer() throws IOException {
        Path survival = leftover("survival-1", true);
        leftover("lobby-1", false);
        Files.createDirectories(root.resolve("servers/ohne-spur"));

        assertThat(manager.staticDirectory("survival-1")).contains(survival);
        // Das Verzeichnis eines dynamischen Servers ist nach dem Stopp weg - und alles
        // Hochgeladene mit ihm.
        assertThat(manager.staticDirectory("lobby-1")).isEmpty();
        assertThat(manager.staticDirectory("ohne-spur")).isEmpty();
        assertThat(manager.staticDirectory("gibtsnicht")).isEmpty();
    }

    @Test
    @DisplayName("Ein Servername ist kein Pfad")
    void servernameIstKeinPfad() throws IOException {
        leftover("survival-1", true);

        // Der Name kommt aus dem SFTP-Benutzernamen, also von aussen.
        for (String boese : java.util.List.of("..", "../servers/survival-1", "survival-1/..",
                "survival-1/world", "", ".", "SURVIVAL-1")) {
            assertThat(manager.staticDirectory(boese)).as("Name %s", boese).isEmpty();
        }
        assertThat(manager.staticDirectory(null)).isEmpty();
    }

    /** Ein Serververzeichnis, wie es ein abgebrochener Lauf hinterlaesst. */
    private Path leftover(String name, boolean staticServer) throws IOException {
        Path directory = root.resolve("servers").resolve(name);
        Files.createDirectories(directory.resolve("world"));
        Files.writeString(directory.resolve("world/level.dat"), "welt");
        Files.writeString(directory.resolve(".vibecloud-node.json"), GSON.toJson(
                new LocalServerManager.ServerMarker(name, "gruppe", staticServer, 30000,
                        -1, -1)));
        return directory;
    }

    /** Eine Laufzeit, die nichts startet - hier wird nur aufgeraeumt. */
    private static final class NoRuntime implements ServerRuntime {

        @Override
        public String describe() {
            return "Test";
        }

        @Override
        public RunningServer start(StartRequest request, ServerListener listener) {
            throw new UnsupportedOperationException("im Test nicht gebraucht");
        }
    }
}
