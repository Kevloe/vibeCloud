package de.kevloe.vibecloud.master.install;

import de.kevloe.vibecloud.master.install.BundledFiles.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Wann ein mitgeliefertes Plugin ausgepackt, ersetzt oder liegen gelassen wird. */
class BundledFilesTest {

    private static final String PAPER = BundledFiles.TARGETS.getFirst();

    @TempDir
    Path directory;

    /** Was "die Jar" gerade mitbringt - je Test veraenderbar wie ein Master-Update. */
    private final Map<String, String> jar = new HashMap<>();

    private BundledFiles files() {
        return new BundledFiles(directory, name -> {
            String content = jar.get(name.substring(BundledFiles.RESOURCE_PREFIX.length()));
            return content == null ? null
                    : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        });
    }

    private String read(String target) throws Exception {
        return Files.readString(directory.resolve(target));
    }

    @Test
    @DisplayName("Eine fehlende Datei wird ausgepackt, eine gleiche bleibt")
    void auspacken() throws Exception {
        jar.put(PAPER, "v1");

        assertThat(files().install()).containsEntry(PAPER, Outcome.EXTRACTED);
        assertThat(read(PAPER)).isEqualTo("v1");
        assertThat(files().install()).containsEntry(PAPER, Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("Ein unveraendertes Plugin folgt dem Update des Masters")
    void update() throws Exception {
        jar.put(PAPER, "v1");
        files().install();

        jar.put(PAPER, "v2");
        assertThat(files().install()).containsEntry(PAPER, Outcome.UPDATED);
        assertThat(read(PAPER)).isEqualTo("v2");
    }

    @Test
    @DisplayName("Eine eigene Fassung wird nicht ueberschrieben - auch nicht beim Update")
    void eigeneFassung() throws Exception {
        jar.put(PAPER, "v1");
        files().install();
        Files.writeString(directory.resolve(PAPER), "eigene");

        jar.put(PAPER, "v2");
        assertThat(files().install()).containsEntry(PAPER, Outcome.KEPT_MODIFIED);
        assertThat(read(PAPER)).isEqualTo("eigene");
    }

    @Test
    @DisplayName("Eine vorhandene Datei ohne Stand gilt als eigene")
    void ohneStand() throws Exception {
        Path file = directory.resolve(PAPER);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "von Hand");
        jar.put(PAPER, "v1");

        assertThat(files().install()).containsEntry(PAPER, Outcome.KEPT_MODIFIED);
        assertThat(read(PAPER)).isEqualTo("von Hand");
    }

    @Test
    @DisplayName("Ein geloeschtes Plugin kommt zurueck - ohne ist kein Server Teil der Cloud")
    void geloescht() throws Exception {
        jar.put(PAPER, "v1");
        files().install();
        Files.delete(directory.resolve(PAPER));

        assertThat(files().install()).containsEntry(PAPER, Outcome.EXTRACTED);
        assertThat(read(PAPER)).isEqualTo("v1");
    }

    @Test
    @DisplayName("Ohne mitgelieferte Dateien (IDE, gradlew run) wird nichts angelegt")
    void nichtsMitgeliefert() {
        assertThat(files().install()).allSatisfy(
                (target, outcome) -> assertThat(outcome).isEqualTo(Outcome.NOT_BUNDLED));
        assertThat(directory.resolve(BundledFiles.STATE_FILE)).doesNotExist();
        assertThat(directory.resolve("templates")).doesNotExist();
    }
}
