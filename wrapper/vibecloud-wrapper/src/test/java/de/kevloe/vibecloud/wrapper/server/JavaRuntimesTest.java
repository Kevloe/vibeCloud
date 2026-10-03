package de.kevloe.vibecloud.wrapper.server;

import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.StartServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Mit welcher Java ein Server startet. */
class JavaRuntimesTest {

    private static final JavaRuntimes NODE = new JavaRuntimes(Map.of(
            17, "/jvm/17/bin/java",
            21, "/jvm/21/bin/java",
            25, "/jvm/25/bin/java"));

    @Test
    @DisplayName("Die kleinste passende Java gewinnt, nicht die neueste")
    void kleinstePassende() {
        // 1.20.4 braucht 17. Mit 25 liefe sie schlechter oder gar nicht.
        assertThat(NODE.select(17)).get().extracting(JavaRuntimes.Selected::version).isEqualTo(17);
        assertThat(NODE.select(18)).get().extracting(JavaRuntimes.Selected::version).isEqualTo(21);
        assertThat(NODE.select(25)).get().extracting(JavaRuntimes.Selected::executable)
                .isEqualTo("/jvm/25/bin/java");
    }

    @Test
    @DisplayName("Ohne passende Java gibt es keine - dann startet der Server nicht")
    void keinePassende() {
        assertThat(new JavaRuntimes(Map.of(17, "/jvm/17/bin/java")).select(21)).isEmpty();
    }

    @Test
    @DisplayName("Die Versionen gehen aufsteigend an den Master")
    void versionen() {
        assertThat(NODE.versions()).containsExactly(17, 21, 25);
    }

    @Test
    @DisplayName("java.specification.version: 1.8 ist Java 8, danach die Zahl selbst")
    void spezifikation() {
        assertThat(JavaRuntimes.parseSpecificationVersion("""
                Property settings:
                    java.specification.version = 1.8
                    java.vendor = Temurin""")).hasValue(8);
        assertThat(JavaRuntimes.parseSpecificationVersion(
                "    java.specification.version = 17\n")).hasValue(17);
        assertThat(JavaRuntimes.parseSpecificationVersion("Error: could not create the JVM"))
                .isEmpty();
    }

    @Test
    @DisplayName("Ein Eintrag, der weder Programm noch Verzeichnis ist, zaehlt nicht")
    void ungueltigerEintrag() {
        assertThat(JavaRuntimes.executableOf("/gibt/es/nicht")).isEmpty();
        assertThat(JavaRuntimes.executableOf(" ")).isEmpty();
    }

    @Test
    @DisplayName("Die eigene Java ist immer dabei, auch ohne Eintrag")
    void eigeneJava() {
        JavaRuntimes detected = JavaRuntimes.detect(List.of("/gibt/es/nicht"));
        assertThat(detected.versions()).containsExactly(Runtime.version().feature());
        assertThat(detected.select(0)).get().extracting(JavaRuntimes.Selected::version)
                .isEqualTo(Runtime.version().feature());
    }

    @Test
    @DisplayName("--enable-native-access nur ab Java 22 - Java 17 und aelter kennen es nicht")
    void nativeAccess() {
        StartServer request = StartServer.newBuilder()
                .setPlatform(ServerPlatform.SERVER_PLATFORM_PAPER)
                .setMemoryMb(1024)
                .setPort(30001)
                .addJvmFlags("-DPaper.IgnoreJavaVersion=true")
                .build();

        List<String> old = LocalServerManager.buildCommand(request,
                new JavaRuntimes.Selected(17, "/jvm/17/bin/java"));
        assertThat(old).startsWith("/jvm/17/bin/java")
                .contains("-DPaper.IgnoreJavaVersion=true")
                .doesNotContain("--enable-native-access=ALL-UNNAMED");

        List<String> current = LocalServerManager.buildCommand(request,
                new JavaRuntimes.Selected(25, "/jvm/25/bin/java"));
        assertThat(current).contains("--enable-native-access=ALL-UNNAMED");
    }
}
