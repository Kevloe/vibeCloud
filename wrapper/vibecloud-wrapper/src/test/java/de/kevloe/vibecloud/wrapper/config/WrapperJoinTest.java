package de.kevloe.vibecloud.wrapper.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code join}: die wrapper.json aus der Zeile von {@code node add}. */
class WrapperJoinTest {

    private static final String FINGERPRINT = "sha256:3f:a1:00";

    @Test
    @DisplayName("Adresse mit Port, Node, Token und Fingerprint landen in der Konfiguration")
    void mitPort() {
        WrapperConfig config = WrapperJoin.parse(
                List.of("10.0.0.5:5001", "node-b", "-abc_DEF", FINGERPRINT));

        assertThat(config.masterHost).isEqualTo("10.0.0.5");
        assertThat(config.masterPort).isEqualTo(5001);
        assertThat(config.node).isEqualTo("node-b");
        // Base64url darf mit einem Minus beginnen - das ist kein Schalter.
        assertThat(config.token).isEqualTo("-abc_DEF");
        assertThat(config.masterFingerprint).isEqualTo(FINGERPRINT);
    }

    @Test
    @DisplayName("Ohne Port gilt die Vorgabe, IPv6 in Klammern wird zerlegt")
    void ohnePortUndIpv6() {
        assertThat(WrapperJoin.parse(List.of("master.example", "n", "t", FINGERPRINT)))
                .satisfies(config -> {
                    assertThat(config.masterHost).isEqualTo("master.example");
                    assertThat(config.masterPort).isEqualTo(new WrapperConfig().masterPort);
                });
        assertThat(WrapperJoin.parse(List.of("[fd00::1]:5002", "n", "t", FINGERPRINT)))
                .satisfies(config -> {
                    assertThat(config.masterHost).isEqualTo("fd00::1");
                    assertThat(config.masterPort).isEqualTo(5002);
                });
    }

    @Test
    @DisplayName("Vertauschte Reihenfolge und falsche Anzahl werden abgelehnt")
    void falscheEingaben() {
        assertThatThrownBy(() -> WrapperJoin.parse(List.of("m", "n", FINGERPRINT, "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reihenfolge");
        assertThatThrownBy(() -> WrapperJoin.parse(List.of("m", "n", "t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WrapperJoin.parse(List.of("m:99999", "n", "t", FINGERPRINT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Schreibt eine lesbare wrapper.json und ueberschreibt nie eine vorhandene")
    void schreiben(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("wrapper.json");
        WrapperJoin.write(file, WrapperJoin.parse(List.of("m:5000", "n", "geheim", FINGERPRINT)));

        WrapperConfig read = new Gson().fromJson(Files.readString(file), WrapperConfig.class);
        assertThat(read.token).isEqualTo("geheim");
        // Die Vorlage daneben enthaelt kein Token.
        assertThat(Files.readString(directory.resolve("wrapper.example.json")))
                .doesNotContain("geheim");

        assertThatThrownBy(() -> WrapperJoin.write(file,
                WrapperJoin.parse(List.of("x", "y", "anders", FINGERPRINT))))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).contains("geheim");
    }
}
