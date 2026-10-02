package de.kevloe.vibecloud.master.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Die {@code config.json} aus dem Dashboard aendern.
 *
 * <p>Zwei Dinge duerfen dabei nie passieren: dass ein Feld geschrieben wird, das den Master
 * am Starten hindert, und dass beim Schreiben etwas verloren geht, das niemand angefasst
 * hat.
 */
class MasterConfigFileTest {

    @TempDir
    private Path directory;

    private Path file;
    private MasterConfigFile config;

    @BeforeEach
    void anlegen() throws IOException {
        file = directory.resolve("config.json");
        Files.writeString(file, """
                {
                  "grpc": { "bindAddress": "0.0.0.0", "port": 5000,
                            "heartbeatIntervalSeconds": 10, "heartbeatTimeoutSeconds": 45,
                            "maxMessageSizeMb": 4 },
                  "database": { "host": "db.intern", "password": "geheim" },
                  "http": { "enabled": true, "port": 8080 },
                  "servers": { "portRangeStart": 30000, "portRangeEnd": 30999,
                               "proxyPort": 25565, "stopGraceSeconds": 30 },
                  "ausEinerAnderenVersion": { "bleibt": true }
                }
                """);
        config = new MasterConfigFile(file, new MasterConfig());
    }

    private JsonObject datei() throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    @Test
    @DisplayName("Jedes aenderbare Feld hat einen Wert und einen Bereich")
    void jedesFeldHatEinenWert() {
        for (String field : MasterConfigFile.editableFields()) {
            assertThat(MasterConfigFile.valueOf(new MasterConfig(), field))
                    .as("Wert von %s", field).isNotBlank();
            assertThat(MasterConfigFile.rangeOf(field)).as("Bereich von %s", field).contains("-");
        }
    }

    @Test
    @DisplayName("Die Vorgaben bestehen die eigene Pruefung")
    void vorgabenSindGueltig() {
        MasterConfigFile.validate(new MasterConfig());
    }

    @Test
    @DisplayName("Geaendert wird nur das genannte Feld")
    void nurDasGenannteFeld() throws IOException {
        config.update(Map.of("servers.stopGraceSeconds", "60"));

        JsonObject root = datei();
        assertThat(root.getAsJsonObject("servers").get("stopGraceSeconds").getAsInt())
                .isEqualTo(60);
        // Das Passwort und ein Eintrag, den diese Version nicht kennt, sind noch da.
        assertThat(root.getAsJsonObject("database").get("password").getAsString())
                .isEqualTo("geheim");
        assertThat(root.getAsJsonObject("ausEinerAnderenVersion").get("bleibt").getAsBoolean())
                .isTrue();
        // Ein Feld, das in der Datei fehlte, wird nicht nebenbei mit der Vorgabe gefuellt.
        assertThat(root.getAsJsonObject("servers").has("logRetentionDays")).isFalse();
    }

    @Test
    @DisplayName("Der laufende Wert bleibt, bis der Master neu startet")
    void laufenderWertBleibt() throws IOException {
        config.update(Map.of("servers.stopGraceSeconds", "60"));

        assertThat(MasterConfigFile.valueOf(config.read(), "servers.stopGraceSeconds"))
                .isEqualTo("60");
        assertThat(config.runningValue("servers.stopGraceSeconds")).isEqualTo("30");
    }

    @Test
    @DisplayName("Ein Portbereich laesst sich in einem Schritt verschieben")
    void portbereichInEinemSchritt() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("servers.portRangeStart", "40000");
        values.put("servers.portRangeEnd", "40999");

        // Einzeln ginge das nicht: 40000-30999 ist kein Bereich.
        config.update(values);

        assertThat(datei().getAsJsonObject("servers").get("portRangeStart").getAsInt())
                .isEqualTo(40000);
        assertThatThrownBy(() -> config.update(Map.of("servers.portRangeStart", "50000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("portRangeEnd");
    }

    @Test
    @DisplayName("Der Proxy-Port darf nicht im Gameserver-Bereich liegen")
    void proxyPortNichtImBereich() {
        assertThatThrownBy(() -> config.update(Map.of("servers.proxyPort", "30500")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Der Timeout muss laenger sein als der Abstand der Lebenszeichen")
    void timeoutLaengerAlsIntervall() {
        assertThatThrownBy(() -> config.update(Map.of("grpc.heartbeatTimeoutSeconds", "10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Gesperrte und unbekannte Felder werden abgelehnt")
    void gesperrteFelderWerdenAbgelehnt() throws IOException {
        String vorher = Files.readString(file);

        // Womit sich der Master selbst aussperren koennte - und die Zugangsdaten.
        for (String field : java.util.List.of("database.password", "database.host",
                "http.enabled", "http.port", "grpc.port", "grpc.bindAddress", "gibtsnicht")) {
            assertThatThrownBy(() -> config.update(Map.of(field, "1")))
                    .as("Feld %s", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.readString(file)).isEqualTo(vorher);
        assertThat(config.lockedFields()).doesNotContainKey("database.password");
    }

    @Test
    @DisplayName("Ein Wert, der keine Zahl ist, aendert nichts")
    void keineZahlAendertNichts() throws IOException {
        String vorher = Files.readString(file);

        assertThatThrownBy(() -> config.update(Map.of("servers.stopGraceSeconds", "bald")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.update(Map.of("servers.stopGraceSeconds", "0")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.readString(file)).isEqualTo(vorher);
    }
}
