package de.kevloe.vibecloud.master.settings;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Wartungsmodus, global und je Gruppe.
 *
 * <p>Der Anlass: Konsole und Dashboard schalten ueber dieselbe Stelle. Vorher setzte das
 * Dashboard nur das Feld der Gruppe - und die Konsole kannte einen Weg, den es dort nicht
 * gab.
 */
@Testcontainers
class MaintenanceSwitchTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("devonly");

    private static Database database;
    private static AuditLog audit;

    private ServerGroupRepository groups;
    private MaintenanceSwitch maintenance;

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
        audit = new AuditLog(database);
    }

    @AfterAll
    static void disconnect() {
        audit.close();
        database.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = database.connection()) {
            connection.createStatement().execute(
                    "TRUNCATE server_groups, cloud_settings CASCADE");
        }
        groups = new ServerGroupRepository(database);
        groups.create(ServerGroup.defaults("lobby", ServerPlatformType.PAPER, "lobby"));
        groups.create(ServerGroup.defaults("bedwars", ServerPlatformType.PAPER, "bedwars"));

        // Kein Plugin verbunden: Die Meldung erreicht niemanden, gespeichert wird trotzdem.
        maintenance = new MaintenanceSwitch(new CloudSettings(database), groups,
                new PluginConnectionRegistry(), audit);
    }

    @Test
    @DisplayName("Die Wartung einer Gruppe trifft nur diese Gruppe")
    void wartungEinerGruppe() {
        assertThat(maintenance.setGroup("bedwars", true, "Kevloe")).isZero();

        assertThat(groups.find("bedwars").orElseThrow().maintenance()).isTrue();
        assertThat(groups.find("lobby").orElseThrow().maintenance()).isFalse();
        // Global ist etwas anderes - das Netzwerk bleibt offen.
        assertThat(maintenance.isActive()).isFalse();

        maintenance.setGroup("bedwars", false, "Kevloe");

        assertThat(groups.find("bedwars").orElseThrow().maintenance()).isFalse();
    }

    @Test
    @DisplayName("Eine unbekannte Gruppe wird gemeldet, nicht stillschweigend geschluckt")
    void unbekannteGruppe() {
        assertThat(maintenance.setGroup("gibtsnicht", true, "Kevloe")).isEqualTo(-1);
    }

    @Test
    @DisplayName("Die globale Wartung ueberlebt einen Neustart")
    void globaleWartungUeberlebtNeustart() {
        maintenance.set(true, "Kevloe");

        // Ein neuer Master liest den Schalter aus der Datenbank - sonst waere das Netzwerk
        // nach einem Neustart mitten in der Wartung wieder offen.
        MaintenanceSwitch nachNeustart = new MaintenanceSwitch(new CloudSettings(database),
                groups, new PluginConnectionRegistry(), audit);

        assertThat(nachNeustart.isActive()).isTrue();
        // Die Gruppen bleiben davon unberuehrt.
        assertThat(groups.find("lobby").orElseThrow().maintenance()).isFalse();
    }
}
