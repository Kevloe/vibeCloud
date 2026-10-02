package de.kevloe.vibecloud.master.sftp;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.StaticBindingRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SFTP-Anmeldungen gegen eine echte Datenbank.
 *
 * <p>Geprueft wird, was bei einem Fehler Zugriff auf ein Serververzeichnis gaebe: ein
 * falsches Passwort, ein fehlendes Recht, ein Server auf einem anderen Node, ein
 * entfernter Zugang.
 */
@Testcontainers
class SftpAccountServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("devonly");

    private static final UUID KEVLOE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** Eine Datenbank fuer die ganze Klasse - je Test ein Pool sprengt das Limit. */
    private static Database database;
    private static AuditLog audit;

    private final Set<String> granted = new HashSet<>();
    private SftpAccountService sftp;

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
    static void disconnect() throws Exception {
        audit.close();
        database.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = database.connection()) {
            connection.createStatement().execute(
                    "TRUNCATE sftp_accounts, server_groups, players CASCADE");
            // Falls die Migration keinen Default-Rang mitbringt - ein Spieler braucht einen.
            connection.createStatement().execute(
                    "INSERT INTO ranks (id, name, display_name, is_default) "
                    + "VALUES ('default', 'default', 'Default', TRUE) ON CONFLICT DO NOTHING");
            // Der Zugang haengt an einem Spieler - ohne ihn gibt es keinen.
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO players (uuid, name, name_lower, rank_id) "
                    + "VALUES (?, 'Kevloe', 'kevloe', "
                    + "(SELECT id FROM ranks WHERE is_default LIMIT 1))")) {
                statement.setObject(1, KEVLOE);
                statement.executeUpdate();
            }
        }
        // Eine Bindung haengt an ihrer Gruppe.
        ServerGroupRepository groups = new ServerGroupRepository(database);
        groups.create(ServerGroup.defaults("survival", ServerPlatformType.PAPER, "survival"));
        groups.create(ServerGroup.defaults("citybuild", ServerPlatformType.PAPER, "citybuild"));

        StaticBindingRepository bindings = new StaticBindingRepository(database);
        bindings.bind("survival-1", "survival", "node-a", 30000);
        bindings.bind("citybuild-1", "citybuild", "node-b", 30001);

        granted.clear();
        granted.add("vibecloud.sftp.survival-1");
        sftp = new SftpAccountService(database, bindings,
                (uuid, node) -> uuid.equals(KEVLOE) && granted.contains(node), audit);
    }

    private boolean login(String node, String username, String server, String password) {
        return sftp.authenticate(node, username, server, password, "203.0.113.7").allowed();
    }

    @Test
    @DisplayName("Mit Zugang, Passwort und Recht kommt man herein")
    void anmeldungGelingt() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        assertThat(login("node-a", "Kevloe", "survival-1", password)).isTrue();
        // Der Name wird ohne Ruecksicht auf Gross und Klein gesucht - das Passwort nicht.
        assertThat(login("node-a", "kevloe", "survival-1", password)).isTrue();
        assertThat(sftp.all().getFirst().lastServer()).isEqualTo("survival-1");
    }

    @Test
    @DisplayName("Ein falsches Passwort wird abgewiesen")
    void falschesPasswort() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        assertThat(login("node-a", "Kevloe", "survival-1", password + "x")).isFalse();
        assertThat(login("node-a", "Kevloe", "survival-1", "")).isFalse();
        assertThat(login("node-a", "Kevloe", "survival-1",
                password.toLowerCase(java.util.Locale.ROOT))).isFalse();
    }

    @Test
    @DisplayName("Ohne Zugang hilft auch ein leeres Passwort nicht")
    void ohneZugang() {
        // Fuer einen unbekannten Namen wird gegen den Hash des leeren Passworts
        // verglichen, damit die Antwortzeit nichts verraet - hinein darf man damit nicht.
        assertThat(login("node-a", "Niemand", "survival-1", "")).isFalse();
        assertThat(login("node-a", "Kevloe", "survival-1", "")).isFalse();
    }

    @Test
    @DisplayName("Das Recht gilt je Server")
    void rechtGiltJeServer() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        // Richtiges Passwort, aber kein Recht fuer citybuild-1.
        assertThat(login("node-b", "Kevloe", "citybuild-1", password)).isFalse();

        granted.add("vibecloud.sftp.citybuild-1");
        assertThat(login("node-b", "Kevloe", "citybuild-1", password)).isTrue();
    }

    @Test
    @DisplayName("Ein entzogenes Recht wirkt bei der naechsten Anmeldung")
    void entzogenesRecht() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");
        assertThat(login("node-a", "Kevloe", "survival-1", password)).isTrue();

        granted.clear();

        assertThat(login("node-a", "Kevloe", "survival-1", password)).isFalse();
    }

    @Test
    @DisplayName("Ein Node darf nur ueber seine eigenen Server fragen")
    void nurEigeneServer() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        // survival-1 liegt auf node-a. Fragt node-b danach, ist das entweder ein Fehler
        // oder ein uebernommener Node, der Passwoerter durchprobiert.
        assertThat(login("node-b", "Kevloe", "survival-1", password)).isFalse();
        assertThat(login("node-a", "Kevloe", "survival-1", password)).isTrue();
    }

    @Test
    @DisplayName("Fuer einen Server ohne Bindung gibt es kein SFTP")
    void serverOhneBindung() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");
        granted.add("vibecloud.sftp.lobby-1");

        // Dynamische Server haben keine Bindung - ihr Verzeichnis ist nach dem Stopp weg.
        assertThat(login("node-a", "Kevloe", "lobby-1", password)).isFalse();
    }

    @Test
    @DisplayName("Ein neues Passwort macht das alte ungueltig")
    void neuesPasswort() {
        String altes = sftp.create(KEVLOE, "Kevloe", "CONSOLE");
        String neues = sftp.resetPassword(KEVLOE, "CONSOLE").orElseThrow();

        assertThat(neues).isNotEqualTo(altes);
        assertThat(login("node-a", "Kevloe", "survival-1", altes)).isFalse();
        assertThat(login("node-a", "Kevloe", "survival-1", neues)).isTrue();
    }

    @Test
    @DisplayName("Ein entfernter Zugang gilt nicht mehr")
    void entfernterZugang() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        assertThat(sftp.remove(KEVLOE, "CONSOLE")).isTrue();

        assertThat(login("node-a", "Kevloe", "survival-1", password)).isFalse();
        assertThat(sftp.remove(KEVLOE, "CONSOLE")).isFalse();
        assertThat(sftp.resetPassword(KEVLOE, "CONSOLE")).isEmpty();
    }

    @Test
    @DisplayName("Das Passwort steht nicht in der Datenbank")
    void passwortStehtNichtInDerDatenbank() throws Exception {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        try (Connection connection = database.connection();
             var result = connection.createStatement().executeQuery(
                     "SELECT password_hash FROM sftp_accounts")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNotEqualTo(password).doesNotContain(password);
        }
        assertThat(sftp.all().getFirst().toString()).doesNotContain(password);
    }

    @Test
    @DisplayName("Im Dashboard entsteht der Zugang mit dem ersten Passwort")
    void zugangEntstehtMitDemErstenPasswort() {
        SftpAccountService.NewPassword erstes = sftp.createOrReset(KEVLOE, "Kevloe", "Kevloe");

        assertThat(erstes.created()).isTrue();
        assertThat(login("node-a", "Kevloe", "survival-1", erstes.password())).isTrue();

        // Beim zweiten Mal gibt es den Zugang schon - dann ist es ein neues Passwort, und
        // das alte gilt nicht mehr.
        SftpAccountService.NewPassword zweites = sftp.createOrReset(KEVLOE, "Kevloe", "Kevloe");

        assertThat(zweites.created()).isFalse();
        assertThat(sftp.all()).hasSize(1);
        assertThat(login("node-a", "Kevloe", "survival-1", erstes.password())).isFalse();
        assertThat(login("node-a", "Kevloe", "survival-1", zweites.password())).isTrue();
    }

    @Test
    @DisplayName("Die Liste im Dashboard und die Anmeldung fragen dasselbe Recht")
    void listeUndAnmeldungFragenDasselbeRecht() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        // Was die Seite anzeigt, muss auch aufgehen - und was sie verschweigt, nicht.
        for (String server : java.util.List.of("survival-1", "citybuild-1")) {
            String node = server.equals("survival-1") ? "node-a" : "node-b";
            assertThat(sftp.mayAccess(KEVLOE, server))
                    .as("Server %s", server)
                    .isEqualTo(login(node, "Kevloe", server, password));
        }
        assertThat(sftp.mayAccess(UUID.randomUUID(), "survival-1")).isFalse();
    }

    // ---------------------------------------------------------------- Templates

    private boolean template(String username, String group, String password) {
        return sftp.authenticateTemplate(username, group, password, "203.0.113.7").allowed();
    }

    @Test
    @DisplayName("Ein Template braucht sein eigenes Recht")
    void templateBrauchtEigenesRecht() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        // Das Recht fuer den Server survival-1 ist nicht das fuer das Template der Gruppe:
        // Ein Template wirkt auf jeden Server der Gruppe, auf jedem Node.
        assertThat(template("Kevloe", "survival", password)).isFalse();
        assertThat(sftp.mayEditTemplate(KEVLOE, "survival")).isFalse();

        granted.add("vibecloud.sftp.template.survival");

        assertThat(template("Kevloe", "survival", password)).isTrue();
        assertThat(sftp.mayEditTemplate(KEVLOE, "survival")).isTrue();
        assertThat(template("Kevloe", "citybuild", password)).isFalse();
        assertThat(sftp.all().getFirst().lastServer()).isEqualTo("template:survival");
    }

    @Test
    @DisplayName("Auch am Template zaehlt das Passwort")
    void templateBrauchtDasPasswort() {
        String password = sftp.create(KEVLOE, "Kevloe", "CONSOLE");
        granted.add("vibecloud.sftp.template.survival");

        assertThat(template("Kevloe", "survival", password + "x")).isFalse();
        assertThat(template("Niemand", "survival", "")).isFalse();
    }

    @Test
    @DisplayName("Ein Server heisst nie wie das Recht fuer ein Template")
    void serverUndTemplateVerwechselnSichNicht() {
        // "template.survival" ist kein Servername - ein Servername hat keinen Punkt. Das
        // Recht fuer ein Template kann also nie versehentlich ein Serververzeichnis oeffnen.
        assertThat(SftpAccountService.templatePermissionFor("Survival"))
                .isEqualTo("vibecloud.sftp.template.survival");
        assertThat(SftpAccountService.permissionFor("survival-1"))
                .isEqualTo("vibecloud.sftp.survival-1");
    }

    @Test
    @DisplayName("Je Spieler gibt es einen Zugang")
    void einZugangJeSpieler() {
        sftp.create(KEVLOE, "Kevloe", "CONSOLE");

        assertThatThrownBy(() -> sftp.create(KEVLOE, "Kevloe", "CONSOLE"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sftp.all()).hasSize(1);
    }
}
