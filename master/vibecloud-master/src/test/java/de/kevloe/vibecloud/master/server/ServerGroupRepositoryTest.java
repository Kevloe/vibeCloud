package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.db.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Felder einer Gruppe aendern - gegen eine echte Datenbank.
 *
 * <p>Der Anlass: {@code group edit lobby memory 64} wurde gespeichert, obwohl eine Gruppe
 * mindestens 128 MB braucht. Danach warf jedes Lesen der Gruppen, und der Scheduler startete
 * fuer keine einzige mehr einen Server - ein Tippfehler in einem Feld legte die Cloud still.
 */
@Testcontainers
class ServerGroupRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("devonly");

    private static Database database;

    private ServerGroupRepository groups;

    @BeforeAll
    static void connect() {
        MasterConfig.Database config = new MasterConfig.Database();
        config.host = POSTGRES.getHost();
        config.port = POSTGRES.getFirstMappedPort();
        config.database = "vibecloud";
        config.user = "vibecloud";
        config.password = "devonly";

        database = new Database(config);
        database.migrate();
    }

    @AfterAll
    static void disconnect() {
        database.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = database.connection()) {
            connection.createStatement().execute("TRUNCATE server_groups CASCADE");
        }
        groups = new ServerGroupRepository(database);
        groups.create(ServerGroup.defaults("lobby", ServerPlatformType.PAPER, "lobby"));
        groups.create(ServerGroup.defaults("bedwars", ServerPlatformType.PAPER, "bedwars"));
    }

    @Test
    @DisplayName("Ein gueltiger Wert wird gespeichert")
    void gueltigerWert() {
        assertThat(groups.updateField("lobby", "memory", "4096")).isTrue();

        assertThat(groups.find("lobby").orElseThrow().memoryMb()).isEqualTo(4096);
    }

    @Test
    @DisplayName("Ein Wert, mit dem die Gruppe ungueltig waere, wird nicht gespeichert")
    void ungueltigerWertWirdNichtGespeichert() {
        ServerGroup vorher = groups.find("lobby").orElseThrow();

        for (Map.Entry<String, String> boese : List.of(
                Map.entry("memory", "64"),
                Map.entry("name_pattern", "immer-gleich"),
                // Die Vorgabe ist max_online 3 - ein Minimum darueber gibt es nicht.
                Map.entry("min_online", "50"),
                Map.entry("max_online", "0"))) {
            assertThatThrownBy(() -> groups.updateField("lobby", boese.getKey(), boese.getValue()))
                    .as("%s = %s", boese.getKey(), boese.getValue())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Nichts davon steht in der Datenbank.
        assertThat(groups.find("lobby").orElseThrow()).isEqualTo(vorher);
    }

    @Test
    @DisplayName("Ein abgelehnter Wert laesst alle Gruppen lesbar")
    void abgelehnterWertLaesstAllesLesbar() {
        assertThatThrownBy(() -> groups.updateField("lobby", "memory", "64"))
                .isInstanceOf(IllegalArgumentException.class);

        // Genau hier warf es vorher - und damit bei jedem Abgleich des Schedulers.
        assertThat(groups.findAll()).extracting(ServerGroup::name)
                .containsExactlyInAnyOrder("lobby", "bedwars");
    }

    @Test
    @DisplayName("Unbekannte Gruppe, unbekanntes Feld, keine Zahl")
    void sonstigeFehler() {
        assertThat(groups.updateField("gibtsnicht", "memory", "1024")).isFalse();
        assertThatThrownBy(() -> groups.updateField("lobby", "gibtsnicht", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> groups.updateField("lobby", "memory", "viel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(groups.find("lobby").orElseThrow().memoryMb()).isEqualTo(2048);
    }
}
