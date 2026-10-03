package de.kevloe.vibecloud.wrapper.server;

import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.StartServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wohin das Velocity-Secret geschrieben wird.
 *
 * <p>Landet es in der falschen Datei, liest Paper es nicht, und jeder Spieler vom Proxy wird
 * abgewiesen - ohne einen Hinweis auf die Ursache.
 */
class ServerConfiguratorTest {

    @TempDir
    private Path directory;

    private void apply(String version) throws IOException {
        ServerConfigurator.apply(StartServer.newBuilder()
                .setServerName("bedwars-1")
                .setPlatform(ServerPlatform.SERVER_PLATFORM_PAPER)
                .setPort(30001)
                .setForwardingSecret("geheim")
                .setMcVersion(version)
                .build(), directory);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> block(String file, String parent, String child)
            throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(directory.resolve(file)));
        return (Map<String, Object>) ((Map<String, Object>) root.get(parent)).get(child);
    }

    @Test
    @DisplayName("Bis 1.18: settings.velocity-support in paper.yml")
    void paperYml() throws IOException {
        Files.writeString(directory.resolve("paper.yml"),
                "settings:\n  max-joins-per-tick: 3\nconfig-version: 20\n");

        apply("1.16.5");

        assertThat(block("paper.yml", "settings", "velocity-support"))
                .containsEntry("enabled", true)
                .containsEntry("online-mode", true)
                .containsEntry("secret", "geheim");
        // Was im Template steht, bleibt.
        assertThat(Files.readString(directory.resolve("paper.yml")))
                .contains("max-joins-per-tick: 3").contains("config-version: 20");
        assertThat(directory.resolve("config/paper-global.yml")).doesNotExist();
    }

    @Test
    @DisplayName("Ab 1.19: proxies.velocity in config/paper-global.yml")
    void paperGlobal() throws IOException {
        apply("1.19");

        assertThat(block("config/paper-global.yml", "proxies", "velocity"))
                .containsEntry("secret", "geheim");
        assertThat(directory.resolve("paper.yml")).doesNotExist();
    }

    @Test
    @DisplayName("Ohne Version (eigenes Jar, alter Master): das aktuelle Format")
    void ohneVersion() throws IOException {
        apply("");

        assertThat(directory.resolve("config/paper-global.yml")).exists();
    }

    @Test
    @DisplayName("Die Grenze liegt genau zwischen 1.18.2 und 1.19")
    void grenze() {
        assertThat(ServerConfigurator.usesPaperYml("1.18.2")).isTrue();
        assertThat(ServerConfigurator.usesPaperYml("1.19")).isFalse();
        assertThat(ServerConfigurator.usesPaperYml("26.2")).isFalse();
        assertThat(ServerConfigurator.usesPaperYml("latest")).isFalse();
    }
}
