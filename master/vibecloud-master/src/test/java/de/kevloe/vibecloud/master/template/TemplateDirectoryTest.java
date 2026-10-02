package de.kevloe.vibecloud.master.template;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Welches Verzeichnis der SFTP-Zugang zu einem Template oeffnet.
 *
 * <p>Der Name des Templates ist ein freies Feld der Gruppe. Wird er ungeprueft zum Pfad,
 * fuehrt das Recht fuer eine Gruppe in ein beliebiges Verzeichnis des Masters - bis hin zu
 * {@code secrets/} mit dem JWT-Schluessel.
 */
class TemplateDirectoryTest {

    @TempDir
    private Path directory;

    private Path root;
    private TemplateStore templates;

    @BeforeEach
    void setUp() throws IOException {
        root = directory.resolve("templates");
        templates = new TemplateStore(root, new JarStore(directory.resolve("jars")));
    }

    private static ServerGroup group(String template) {
        return ServerGroup.defaults("lobby", ServerPlatformType.PAPER, template);
    }

    @Test
    @DisplayName("Das Template einer Gruppe liegt direkt unter templates/")
    void templateLiegtUnterTemplates() {
        assertThat(templates.editableDirectory(group("lobby"))).contains(root.resolve("lobby"));
        assertThat(templates.editableDirectory(group("bed_wars-2")))
                .contains(root.resolve("bed_wars-2"));
    }

    @Test
    @DisplayName("Das Verzeichnis wird dabei nicht angelegt")
    void nichtsWirdAngelegt() {
        templates.editableDirectory(group("lobby"));

        // Gefragt wird vor der Anmeldung - sonst entstuende fuer jeden Versuch ein Ordner.
        assertThat(root.resolve("lobby")).doesNotExist();
    }

    @Test
    @DisplayName("Ein Template-Name ist kein Pfad")
    void templateNameIstKeinPfad() {
        for (String boese : List.of("..", "../secrets", "lobby/..", "lobby/../../secrets",
                "/etc", "C:\\Windows", ".", "", "lobby/plugins", "a b")) {
            assertThat(templates.editableDirectory(group(boese)))
                    .as("Template %s", boese)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("templates/global ist nicht erreichbar")
    void globalIstNichtErreichbar() {
        // Dort liegen die Dateien aller Gruppen und das Cloud-Plugin selbst - mehr, als
        // das Recht fuer eine Gruppe hergibt.
        assertThat(templates.editableDirectory(group("global"))).isEmpty();
        assertThat(templates.editableDirectory(group("GLOBAL"))).isEmpty();
    }
}
