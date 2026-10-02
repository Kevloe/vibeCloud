package de.kevloe.vibecloud.master.module;

import de.kevloe.vibecloud.module.ModuleDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Isolation des {@link ModuleClassLoader} - fuer Ressourcen, nicht fuer Klassen.
 *
 * <p>Anlass ist ein stiller Fehler aus M6: Ein Modul bekam {@code messages/de.yml} des
 * Masters statt seiner eigenen Datei, weil {@code URLClassLoader} Ressourcen beim Parent
 * zuerst sucht. Im Log sah alles richtig aus - erst im Spiel waere statt des Ban-Textes
 * der Schluessel erschienen.
 */
class ModuleClassLoaderTest {

    @TempDir
    private Path directory;

    /**
     * Der Parent muss wieder geschlossen werden.
     *
     * <p>Unter Windows haelt ein offener {@code URLClassLoader} sein JAR gesperrt, und das
     * Aufraeumen des Temp-Verzeichnisses schlaegt fehl - der Test waere rot, obwohl er
     * geprueft hat, was er pruefen soll.
     */
    private java.net.URLClassLoader master;

    @org.junit.jupiter.api.AfterEach
    void closeParent() throws IOException {
        if (master != null) {
            master.close();
        }
    }

    @Test
    @DisplayName("Eine eigene Ressource gewinnt gegen die gleichnamige des Masters")
    void eigeneRessourceGewinnt() throws IOException {
        ClassLoader master = loaderWith("messages/de.yml", "vom-master");

        try (ModuleClassLoader loader = loaderFor(
                jar("messages/de.yml", "vom-modul"), master)) {

            assertThat(read(loader.getResourceAsStream("messages/de.yml")))
                    .isEqualTo("vom-modul");
        }
    }

    @Test
    @DisplayName("Was das Modul nicht hat, kommt weiterhin vom Master")
    void fehlendeRessourceKommtVomParent() throws IOException {
        ClassLoader master = loaderWith("messages/de.yml", "vom-master");

        try (ModuleClassLoader loader = loaderFor(jar("module.json", "{}"), master)) {

            assertThat(read(loader.getResourceAsStream("messages/de.yml")))
                    .isEqualTo("vom-master");
        }
    }

    @Test
    @DisplayName("getResources liefert beide Treffer, eigene zuerst")
    void getResourcesLiefertBeideTreffer() throws IOException {
        // Wichtig fuer Flyway: Es scannt ueber getResources. Kaemen die Dateien des
        // Masters zuerst, haette ein Modul wieder fremde Migrations im Zugriff.
        ClassLoader master = loaderWith("messages/de.yml", "vom-master");
        Path moduleJar = jar("messages/de.yml", "vom-modul");

        try (ModuleClassLoader loader = loaderFor(moduleJar, master)) {

            List<URL> found = Collections.list(loader.getResources("messages/de.yml"));

            // Ueber die URLs statt ueber den Inhalt: Ein geoeffneter JAR-Stream bleibt im
            // URL-Cache haengen und sperrt die Datei unter Windows.
            assertThat(found).hasSize(2);
            assertThat(found.getFirst().toString())
                    .contains(moduleJar.getFileName().toString());
            assertThat(found.get(1).toString())
                    .doesNotContain(moduleJar.getFileName().toString());
        }
    }

    @Test
    @DisplayName("Eine unbekannte Ressource bleibt unbekannt")
    void unbekannteRessourceBleibtUnbekannt() throws IOException {
        try (ModuleClassLoader loader = loaderFor(jar("module.json", "{}"),
                loaderWith("messages/de.yml", "vom-master"))) {

            assertThat(loader.getResource("gibt/es/nicht.yml")).isNull();
        }
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private ModuleClassLoader loaderFor(Path jar, ClassLoader parent) throws IOException {
        return new ModuleClassLoader(jar, descriptor(), parent, id -> Optional.empty());
    }

    /** Ein Parent-Loader, der genau eine Ressource anbietet - wie der Master. */
    private ClassLoader loaderWith(String name, String content) throws IOException {
        Path jar = jar(name, content);
        master = new java.net.URLClassLoader("test-master",
                new URL[]{jar.toUri().toURL()}, null);
        return master;
    }

    private Path jar(String name, String content) throws IOException {
        Path jar = Files.createTempFile(directory, "test-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ModuleDescriptor descriptor() {
        return new ModuleDescriptor("test", "Test", "1.0.0", "de.kevloe.Test", "1.0",
                List.of(), List.of(), List.of(), Map.of());
    }
}
