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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** Laesst nur 1.16.5 und latest zu und merkt sich, was angenommen wurde. */
    private final List<String> accepted = new CopyOnWriteArrayList<>();

    private void onlyKnownVersions() {
        groups.setVersionCheck(new ServerGroupRepository.VersionCheck() {
            @Override
            public Optional<String> problemWith(ServerGroup group) {
                return List.of("latest", "1.16.5").contains(group.mcVersion())
                        ? Optional.empty()
                        : Optional.of("Die Version " + group.mcVersion() + " gibt es nicht");
            }

            @Override
            public void accepted(ServerGroup group) {
                accepted.add(group.name() + "=" + group.mcVersion());
            }
        });
    }

    @Test
    @DisplayName("Eine unbekannte Version wird abgelehnt und nichts gespeichert")
    void unbekannteVersion() {
        onlyKnownVersions();

        assertThatThrownBy(() -> groups.updateField("lobby", "mc_version", "1.16.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.16.9 gibt es nicht");
        assertThat(groups.find("lobby").orElseThrow().mcVersion()).isEqualTo("latest");
        assertThat(accepted).isEmpty();
    }

    @Test
    @DisplayName("Eine bekannte Version wird gespeichert und danach gemeldet - zum Vorladen")
    void bekannteVersion() {
        onlyKnownVersions();

        assertThat(groups.updateField("lobby", "mc_version", "1.16.5")).isTrue();
        assertThat(groups.find("lobby").orElseThrow().mcVersion()).isEqualTo("1.16.5");
        assertThat(accepted).containsExactly("lobby=1.16.5");
    }

    @Test
    @DisplayName("Andere Felder fragen die Versionspruefung nicht")
    void andereFelder() {
        onlyKnownVersions();

        assertThat(groups.updateField("lobby", "memory", "4096")).isTrue();
        assertThat(accepted).isEmpty();
    }

    @Test
    @DisplayName("Auch das Anlegen geht durch die Versionspruefung")
    void anlegen() {
        onlyKnownVersions();

        assertThatThrownBy(() -> groups.create(ServerGroup.defaults("skywars",
                ServerPlatformType.PAPER, "skywars").withMcVersion("1.8.8")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(groups.find("skywars")).isEmpty();

        groups.create(ServerGroup.defaults("skywars", ServerPlatformType.PAPER, "skywars")
                .withMcVersion("1.16.5"));
        assertThat(accepted).containsExactly("skywars=1.16.5");
    }
}
