package de.kevloe.vibecloud.master.template;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.protocol.ManifestEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Welches Cloud-Plugin ein Server bekommt.
 *
 * <p>Paper unter 26.2 lehnt {@code vibecloud-paper.jar} ab. Bekaeme so ein Server es
 * trotzdem, liefe er - aber ohne Verbindung zur Cloud, und das faellt erst auf, wenn
 * niemand Rechte hat.
 *
 * <p>Ohne Netz: Die Gruppen bringen ihr Jar im Template mit ({@code jar_source template}),
 * die Version steht trotzdem in der Gruppe.
 */
class LegacyPluginTemplateTest {

    @TempDir
    private Path directory;

    private TemplateStore templates;

    @BeforeEach
    void setUp() throws IOException {
        Path root = directory.resolve("templates");
        templates = new TemplateStore(root, new JarStore(directory.resolve("jars"),
                new VersionCatalog()));

        write(root.resolve("global/server/plugins/vibecloud-paper.jar"), "aktuell");
        write(root.resolve("global/server/server.properties"), "motd=global");
        write(root.resolve("global/server-legacy/plugins/vibecloud-paper-legacy.jar"), "legacy");
        write(root.resolve("bedwars/server.jar"), "paper");
        templates.setBundleProvider(platform ->
                Map.of("plugins/punishment.jar", directory.resolve("bundle.jar")));
        write(directory.resolve("bundle.jar"), "bundle");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ServerGroup group(String version) {
        ServerGroup defaults = ServerGroup.defaults("bedwars", ServerPlatformType.PAPER,
                "bedwars");
        return new ServerGroup(defaults.name(), defaults.platform(), defaults.staticGroup(),
                defaults.minOnline(), defaults.maxOnline(), defaults.maxPlayers(),
                defaults.memoryMb(), List.of("-XX:+UseG1GC"), defaults.allowedNodes(),
                defaults.startPercent(), defaults.idleTimeout(), defaults.namePattern(),
                defaults.template(), version, "template", defaults.fallback(),
                defaults.joinPriority(), defaults.maintenance(), defaults.priority());
    }

    private List<String> paths(TemplateStore.PreparedStart prepared) {
        return prepared.manifest().getEntriesList().stream().map(ManifestEntry::getPath).toList();
    }

    @Test
    @DisplayName("1.16.5 bekommt das Legacy-Plugin statt des aktuellen, aber den Rest von global")
    void legacy() throws Exception {
        TemplateStore.PreparedStart prepared = templates.buildManifest(group("1.16.5"));

        assertThat(paths(prepared))
                .contains("plugins/vibecloud-paper-legacy.jar", "server.properties", "server.jar")
                .doesNotContain("plugins/vibecloud-paper.jar");
        assertThat(prepared.legacyPlugin()).isTrue();
        assertThat(prepared.mcVersion()).isEqualTo("1.16.5");
        assertThat(prepared.javaVersion()).isEqualTo(17);
        // Die Flags der Gruppe bleiben, der Schalter kommt dazu.
        assertThat(prepared.jvmFlags())
                .containsExactly("-XX:+UseG1GC", "-DPaper.IgnoreJavaVersion=true");
    }

    @Test
    @DisplayName("Modul-Bundles bekommt ein Legacy-Server nicht - sie sind fuer Java 25 gebaut")
    void keineBundles() throws Exception {
        assertThat(paths(templates.buildManifest(group("1.20.4"))))
                .doesNotContain("plugins/punishment.jar");
        assertThat(paths(templates.buildManifest(group("26.2"))))
                .contains("plugins/punishment.jar");
    }

    @Test
    @DisplayName("26.2 bleibt beim aktuellen Plugin")
    void aktuell() throws Exception {
        TemplateStore.PreparedStart prepared = templates.buildManifest(group("26.2"));

        assertThat(paths(prepared))
                .contains("plugins/vibecloud-paper.jar")
                .doesNotContain("plugins/vibecloud-paper-legacy.jar");
        assertThat(prepared.legacyPlugin()).isFalse();
        assertThat(prepared.javaVersion()).isEqualTo(25);
        assertThat(prepared.jvmFlags()).containsExactly("-XX:+UseG1GC");
    }

    @Test
    @DisplayName("Ohne Version bei einem eigenen Jar: wie bisher, aktuelles Plugin und Java 25")
    void ohneVersion() throws Exception {
        TemplateStore.PreparedStart prepared = templates.buildManifest(group("latest"));

        assertThat(paths(prepared)).contains("plugins/vibecloud-paper.jar");
        assertThat(prepared.mcVersion()).isEmpty();
        assertThat(prepared.javaVersion()).isEqualTo(25);
    }
}
