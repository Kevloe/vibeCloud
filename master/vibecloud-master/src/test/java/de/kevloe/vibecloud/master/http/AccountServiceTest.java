package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.api.event.LocalEventBus;
import de.kevloe.vibecloud.api.event.events.PlayerPermissionsChangedEvent;
import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dashboard-Zugaenge gegen eine echte Datenbank.
 *
 * <p>Der wichtigste Teil steht am Ende: Faellt {@code vibecloud.dashboard.login} weg, muss
 * der Zugang verschwinden. Das ist die Zusage aus PLAN.md Abschnitt 12 - ohne sie behielte
 * jemand nach einer Degradierung weiter Zugriff auf das Dashboard.
 */
@Testcontainers
class AccountServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("devonly");

    private static final UUID KEVIN = UUID.randomUUID();

    /**
     * Eine Datenbank fuer die ganze Klasse.
     *
     * <p>Je Test eine eigene waere ein Verbindungspool je Test - PostgreSQL laesst
     * standardmaessig 100 Verbindungen zu, und bei siebzehn Tests waere Schluss
     * ("too many clients already"). Zurueckgesetzt wird stattdessen die Tabelle.
     */
    private static Database database;

    private PermissionService permissions;
    private AccountService accounts;
    private PlayerRepository players;
    private LocalEventBus events;

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
            connection.createStatement().execute(
                    "TRUNCATE dashboard_accounts, player_permissions, players CASCADE");
        }

        events = new LocalEventBus();
        AuditLog audit = new AuditLog(database);
        RankRepository ranks = new RankRepository(database);
        players = new PlayerRepository(database);
        permissions = new PermissionService(ranks, players, events, audit);
        accounts = new AccountService(database, permissions, audit);

        players.recordLogin(KEVIN, "Kevloe", "JAVA", null, "127.0.0.1", "spieler");
        allowLogin();
    }

    @AfterEach
    void tearDown() {
        // Der Dienst haelt einen Scheduler fuer ablaufende Raenge - ohne Schliessen
        // bleibt je Test ein Thread zurueck.
        permissions.close();
    }

    // ---------------------------------------------------------------- Anlegen

    @Test
    @DisplayName("Ein Zugang entsteht mit einem Start-Passwort")
    void zugangEntstehtMitStartPasswort() {
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");

        assertThat(password).hasSize(12);
        // Keine verwechselbaren Zeichen - das Passwort wird im Chat abgetippt.
        assertThat(password).doesNotContain("0").doesNotContain("O")
                .doesNotContain("1").doesNotContain("l");

        var account = accounts.findByName("kevloe").orElseThrow();
        assertThat(account.mustChangePassword()).isTrue();
        assertThat(account.passwordHash()).doesNotContain(password);
    }

    @Test
    @DisplayName("Ohne das Recht gibt es keinen Zugang")
    void ohneRechtKeinZugang() {
        permissions.removePlayerPermission(KEVIN, AccountService.LOGIN_PERMISSION,
                PermissionContext.GLOBAL, "TEST");

        assertThatThrownBy(() -> accounts.create(KEVIN, "Kevloe", "CONSOLE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AccountService.LOGIN_PERMISSION);
    }

    @Test
    @DisplayName("Ein zweiter Zugang fuer denselben Spieler wird abgelehnt")
    void zweiterZugangWirdAbgelehnt() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");

        assertThatThrownBy(() -> accounts.create(KEVIN, "Kevloe", "CONSOLE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changepw");
    }

    // ---------------------------------------------------------------- Anmelden

    @Test
    @DisplayName("Mit dem Start-Passwort kommt man herein")
    void startPasswortFunktioniert() {
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");

        var result = accounts.login("Kevloe", password);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.account().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Gross- und Kleinschreibung im Namen ist gleichgueltig")
    void grossKleinschreibungIstGleichgueltig() {
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");

        assertThat(accounts.login("KEVLOE", password).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Ein falsches Passwort kommt nicht herein")
    void falschesPasswortKommtNichtHerein() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");

        assertThat(accounts.login("Kevloe", "falsch").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Ein unbekannter Name kommt nicht herein")
    void unbekannterNameKommtNichtHerein() {
        assertThat(accounts.login("GibtEsNicht", "irgendwas").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Nach fuenf Fehlversuchen ist gesperrt")
    void nachFuenfFehlversuchenGesperrt() {
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");

        for (int i = 0; i < 5; i++) {
            assertThat(accounts.login("Kevloe", "falsch").isSuccess()).isFalse();
        }
        // Auch das richtige Passwort nicht mehr - sonst waere die Sperre wirkungslos.
        var result = accounts.login("Kevloe", password);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isLocked()).isTrue();
    }

    @Test
    @DisplayName("Ein erfolgreicher Login setzt die Fehlversuche zurueck")
    void erfolgSetztFehlversucheZurueck() {
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");

        accounts.login("Kevloe", "falsch");
        accounts.login("Kevloe", "falsch");
        assertThat(accounts.login("Kevloe", password).isSuccess()).isTrue();

        assertThat(accounts.find(KEVIN).orElseThrow().failedLogins()).isZero();
    }

    @Test
    @DisplayName("Ein gesperrter Zugang kommt nicht herein")
    void gesperrterZugangKommtNichtHerein() {
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");
        accounts.setDisabled(KEVIN, true, "CONSOLE");

        assertThat(accounts.login("Kevloe", password).isSuccess()).isFalse();

        accounts.setDisabled(KEVIN, false, "CONSOLE");
        assertThat(accounts.login("Kevloe", password).isSuccess()).isTrue();
    }

    // ---------------------------------------------------------------- Passwort aendern

    @Test
    @DisplayName("Ein eigenes Passwort beendet den Zwangswechsel")
    void eigenesPasswortBeendetZwangswechsel() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");

        accounts.changePassword(KEVIN, "meinLangesPasswort");

        var account = accounts.find(KEVIN).orElseThrow();
        assertThat(account.mustChangePassword()).isFalse();
        assertThat(accounts.login("Kevloe", "meinLangesPasswort").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Ein zu kurzes Passwort wird abgelehnt")
    void zuKurzesPasswortWirdAbgelehnt() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");

        assertThatThrownBy(() -> accounts.changePassword(KEVIN, "kurz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Jeder Passwortwechsel beendet laufende Sitzungen")
    void passwortwechselBeendetSitzungen() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");
        int vorher = accounts.find(KEVIN).orElseThrow().sessionVersion();

        accounts.changePassword(KEVIN, "meinLangesPasswort");

        // Die Sitzungs-Version steckt in jedem Token - eine neue macht alle alten
        // ungueltig, auch die von jemandem, der das Passwort erraten hatte.
        assertThat(accounts.find(KEVIN).orElseThrow().sessionVersion()).isGreaterThan(vorher);
    }

    @Test
    @DisplayName("Ein neues Start-Passwort erzwingt wieder den Wechsel")
    void neuesStartPasswortErzwingtWechsel() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");
        accounts.changePassword(KEVIN, "meinLangesPasswort");

        String neu = accounts.resetPassword(KEVIN, "CONSOLE");

        assertThat(accounts.find(KEVIN).orElseThrow().mustChangePassword()).isTrue();
        assertThat(accounts.login("Kevloe", neu).isSuccess()).isTrue();
        assertThat(accounts.login("Kevloe", "meinLangesPasswort").isSuccess()).isFalse();
    }

    // ---------------------------------------------------------------- Rechte-Entzug

    @Test
    @DisplayName("Wird das Recht entzogen, verschwindet der Zugang")
    void rechteEntzugLoeschtZugang() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");

        permissions.removePlayerPermission(KEVIN, AccountService.LOGIN_PERMISSION,
                PermissionContext.GLOBAL, "TEST");

        // Direkt der Handler: Ueber den Bus laeuft er in einem eigenen Thread, und der
        // Test wuerde auf eine Zufallsreihenfolge warten. Dass er am Bus haengt, macht
        // der Bootstrap (VibeCloudMaster) - geprueft im laufenden System.
        accounts.onPermissionsChanged(new PlayerPermissionsChangedEvent(KEVIN));

        assertThat(accounts.find(KEVIN)).isEmpty();
    }

    @Test
    @DisplayName("Mit dem Recht bleibt der Zugang bestehen")
    void mitRechtBleibtZugangBestehen() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");

        accounts.onPermissionsChanged(new PlayerPermissionsChangedEvent(KEVIN));

        assertThat(accounts.find(KEVIN)).isPresent();
    }

    @Test
    @DisplayName("Eine Rang-Aenderung prueft alle Zugaenge")
    void rangAenderungPrueftAlleZugaenge() {
        accounts.create(KEVIN, "Kevloe", "CONSOLE");
        permissions.removePlayerPermission(KEVIN, AccountService.LOGIN_PERMISSION,
                PermissionContext.GLOBAL, "TEST");

        // uuid = null heisst "betrifft alle" - etwa nach 'rank edit' oder geaenderter
        // Vererbung. Geprueft werden nur die, die einen Zugang haben.
        accounts.onPermissionsChanged(new PlayerPermissionsChangedEvent(null));

        assertThat(accounts.find(KEVIN)).isEmpty();
    }

    @Test
    @DisplayName("Fehlt das Recht beim Login, wird der Zugang geloescht")
    void fehlendesRechtBeimLoginLoeschtZugang() {
        // Zweiter Riegel: Das Event kann ausgefallen sein, etwa bei einem Neustart im
        // falschen Moment. Dann faengt es der Login ab.
        String password = accounts.create(KEVIN, "Kevloe", "CONSOLE");
        permissions.removePlayerPermission(KEVIN, AccountService.LOGIN_PERMISSION,
                PermissionContext.GLOBAL, "TEST");

        assertThat(accounts.login("Kevloe", password).isSuccess()).isFalse();
        assertThat(accounts.find(KEVIN)).isEmpty();
    }

    private void allowLogin() {
        permissions.addPlayerPermission(KEVIN,
                new PermissionEntry(AccountService.LOGIN_PERMISSION, true,
                        PermissionContext.GLOBAL, null),
                "TEST");
    }
}
