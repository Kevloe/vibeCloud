package de.kevloe.vibecloud.punishment;

import de.kevloe.vibecloud.api.Hashing;
import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.LocalEventBus;
import de.kevloe.vibecloud.api.event.events.PlayerPreLoginEvent;
import de.kevloe.vibecloud.module.ModuleChannel;
import de.kevloe.vibecloud.module.ModuleCommands;
import de.kevloe.vibecloud.module.ModuleConfig;
import de.kevloe.vibecloud.module.ModuleContext;
import de.kevloe.vibecloud.module.ModuleDatabase;
import de.kevloe.vibecloud.module.ModuleDescriptor;
import de.kevloe.vibecloud.module.ModulePermissions;
import de.kevloe.vibecloud.module.ModulePlayers;
import de.kevloe.vibecloud.punishment.api.Punishment;
import de.kevloe.vibecloud.punishment.api.PunishmentAnswer;
import de.kevloe.vibecloud.punishment.api.PunishmentQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Abnahmekriterium aus PLAN.md: <i>Ban/Mute greifen beim Login und im Chat.</i>
 *
 * <p>Gegen eine echte PostgreSQL, mit der echten Migration des Moduls und dem echten
 * Event-Bus. Geprueft wird damit auch der Kern der Modul-Idee: Der Core kennt keine Bans -
 * es ist allein dieser Handler am {@link PlayerPreLoginEvent}, der den Login abbricht.
 */
@Testcontainers
class PunishmentLoginGateTest {

    private static final Logger LOG = LoggerFactory.getLogger(PunishmentLoginGateTest.class);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("devonly");

    @TempDir
    private Path dataDirectory;

    private EventBus events;
    private PunishmentModule module;
    private FakeContext context;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = connect()) {
            connection.createStatement().execute("DROP SCHEMA public CASCADE");
            connection.createStatement().execute("CREATE SCHEMA public");
        }
        events = new LocalEventBus();
        context = new FakeContext(events, dataDirectory);
        module = new PunishmentModule();
        module.onEnable(context);
    }

    @AfterEach
    void tearDown() {
        module.onDisable();
    }

    // ---------------------------------------------------------------- Login

    @Test
    @DisplayName("Die Migration des Moduls legt ihre Tabelle an")
    void migrationLegtTabelleAn() throws SQLException {
        // Das ist in M5 zweimal schiefgegangen: einmal durch doppelte V1, einmal durch
        // baselineVersion=1. Beide Male meldete das Log einen Erfolg.
        try (Connection connection = connect();
             var result = connection.createStatement().executeQuery(
                     "SELECT count(*) FROM punishment_entries")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("Ohne Strafe kommt der Spieler herein")
    void ohneStrafeKommtDerSpielerHerein() {
        PlayerPreLoginEvent event = login(UUID.randomUUID(), "Unbelastet", "198.51.100.7");

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Ein Bann bricht den Login ab - mit Grund, Ablauf und Kennung")
    void bannBrichtLoginAb() throws SQLException {
        UUID spieler = UUID.randomUUID();
        Punishment ban = repository().create(Punishment.Type.BAN, spieler, "Gebannt", null,
                "Unerlaubte Modifikationen", "Konsole", null);

        PlayerPreLoginEvent event = login(spieler, "Gebannt", "198.51.100.7");

        assertThat(event.isCancelled()).isTrue();
        assertThat(event.cancelReasonKey()).isEqualTo("punishment.ban.screen");
        assertThat(event.placeholders())
                .containsEntry("grund", "Unerlaubte Modifikationen")
                .containsEntry("ablauf", "nie")
                .containsEntry("kennung", ban.appealId());
    }

    @Test
    @DisplayName("Ein abgelaufener Bann laesst wieder herein")
    void abgelaufenerBannLaesstWiederHerein() throws SQLException {
        UUID spieler = UUID.randomUUID();
        repository().create(Punishment.Type.BAN, spieler, "Abgesessen", null, "Spam",
                "Konsole", Instant.now().minus(Duration.ofMinutes(1)));

        // Niemand muss den Ablauf aufraeumen - die Abfrage filtert ihn heraus. Ein
        // vergessener Aufraeum-Job koennte sonst jemanden dauerhaft aussperren.
        assertThat(login(spieler, "Abgesessen", "198.51.100.7").isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Ein aufgehobener Bann laesst wieder herein, bleibt aber in der Historie")
    void aufgehobenerBannLaesstWiederHerein() throws SQLException {
        UUID spieler = UUID.randomUUID();
        repository().create(Punishment.Type.BAN, spieler, "Entbannt", null, "Irrtum",
                "Konsole", null);

        Optional<Punishment> revoked = repository().revoke(spieler, Punishment.Type.BAN,
                "Konsole", "war ein Irrtum");

        assertThat(revoked).isPresent();
        assertThat(login(spieler, "Entbannt", "198.51.100.7").isCancelled()).isFalse();
        assertThat(repository().history(spieler, 10)).hasSize(1);
    }

    @Test
    @DisplayName("Ein Mute haelt niemanden vom Login ab")
    void muteHaeltNiemandenVomLoginAb() throws SQLException {
        UUID spieler = UUID.randomUUID();
        repository().create(Punishment.Type.MUTE, spieler, "Stumm", null, "Spam",
                "Konsole", null);

        assertThat(login(spieler, "Stumm", "198.51.100.7").isCancelled()).isFalse();
    }

    // ---------------------------------------------------------------- IP-Bann

    @Test
    @DisplayName("Ein IP-Bann trifft auch ein neues Konto von derselben Adresse")
    void ipBannTrifftAuchNeuesKonto() throws SQLException {
        String ip = "203.0.113.9";
        repository().create(Punishment.Type.BAN, UUID.randomUUID(), "Erstkonto",
                Hashing.ipHash(ip), "Unerlaubte Modifikationen", "Konsole", null);

        // Anderes Konto, dieselbe Leitung - genau der Fall, fuer den es IP-Bans gibt.
        PlayerPreLoginEvent event = login(UUID.randomUUID(), "Zweitkonto", ip);

        assertThat(event.isCancelled()).isTrue();
        assertThat(event.placeholders()).containsEntry("grund", "Unerlaubte Modifikationen");
    }

    @Test
    @DisplayName("Ein Bann ohne IP trifft nur das Konto")
    void bannOhneIpTrifftNurDasKonto() throws SQLException {
        // "ban" speichert keine IP, nur "banip" tut das. Sonst traefe jeder Bann die
        // Mitbewohner des Gebannten mit.
        repository().create(Punishment.Type.BAN, UUID.randomUUID(), "Erstkonto", null,
                "Spam", "Konsole", null);

        assertThat(login(UUID.randomUUID(), "Zweitkonto", "203.0.113.9").isCancelled())
                .isFalse();
    }

    @Test
    @DisplayName("Eine andere Adresse bleibt unbehelligt")
    void andereAdresseBleibtUnbehelligt() throws SQLException {
        repository().create(Punishment.Type.BAN, UUID.randomUUID(), "Erstkonto",
                Hashing.ipHash("203.0.113.9"), "Spam", "Konsole", null);

        assertThat(login(UUID.randomUUID(), "Fremd", "198.51.100.7").isCancelled()).isFalse();
    }

    // ---------------------------------------------------------------- Chat

    @Test
    @DisplayName("Der Chat-Filter bekommt den Mute samt Grund")
    void chatFilterBekommtDenMute() throws SQLException {
        UUID spieler = UUID.randomUUID();
        Punishment mute = repository().create(Punishment.Type.MUTE, spieler, "Stumm", null,
                "Spam im Chat", "Konsole", Instant.now().plus(Duration.ofHours(2)));

        PunishmentAnswer answer = context.ask("check",
                new PunishmentQuery(spieler, "MUTE"), PunishmentAnswer.class);

        assertThat(answer.active()).isTrue();
        assertThat(answer.reason()).isEqualTo("Spam im Chat");
        assertThat(answer.appealId()).isEqualTo(mute.appealId());
        assertThat(answer.expires()).isNotEqualTo("nie");
    }

    @Test
    @DisplayName("Ohne Mute darf geschrieben werden")
    void ohneMuteDarfGeschriebenWerden() {
        PunishmentAnswer answer = context.ask("check",
                new PunishmentQuery(UUID.randomUUID(), "MUTE"), PunishmentAnswer.class);

        assertThat(answer.active()).isFalse();
    }

    @Test
    @DisplayName("Ein Bann macht niemanden stumm")
    void bannMachtNiemandenStumm() throws SQLException {
        UUID spieler = UUID.randomUUID();
        repository().create(Punishment.Type.BAN, spieler, "Gebannt", null, "Spam",
                "Konsole", null);

        assertThat(context.ask("check", new PunishmentQuery(spieler, "MUTE"),
                PunishmentAnswer.class).active()).isFalse();
    }

    // ---------------------------------------------------------------- Befehle und Texte

    @Test
    @DisplayName("Alle Strafbefehle sind angemeldet")
    void alleStrafbefehleSindAngemeldet() {
        assertThat(context.commandNames).contains("ban", "tempban", "banip", "unban",
                "mute", "tempmute", "unmute", "kick", "warn", "punishments", "appeal");
    }

    @Test
    @DisplayName("Die Texte des Moduls werden geladen")
    void texteWerdenGeladen() {
        assertThat(context.loadedMessages).isEqualTo(1);
    }

    // ---------------------------------------------------------------- Hilfsmittel

    /** Loest das Login-Gate aus - denselben Weg, den der Master beim Login nimmt. */
    private PlayerPreLoginEvent login(UUID uuid, String name, String ip) {
        return events.postSync(new PlayerPreLoginEvent(uuid, name, ip, "JAVA"));
    }

    private PunishmentRepository repository() {
        return new PunishmentRepository(context.database());
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * Ein {@link ModuleContext} ohne Master.
     *
     * <p>Datenbank und Events sind echt - nur was hier nicht geprueft wird (Spielerdaten,
     * Rechte, Konsole), ist nachgebildet.
     */
    private static final class FakeContext implements ModuleContext {

        private final EventBus events;
        private final Path dataDirectory;
        private final List<String> commandNames = new ArrayList<>();
        private final Map<String, Function<String, Object>> responders = new HashMap<>();
        private final com.google.gson.Gson gson = new com.google.gson.Gson();
        private int loadedMessages;

        private FakeContext(EventBus events, Path dataDirectory) {
            this.events = events;
            this.dataDirectory = dataDirectory;
        }

        /** Stellt die Frage, die sonst ein Gameserver ueber den Kanal stellt. */
        <R> R ask(String key, Object payload, Class<R> responseType) {
            Function<String, Object> responder = responders.get(key);
            assertThat(responder).as("Antwort-Handler fuer " + key).isNotNull();
            Object answer = responder.apply(gson.toJson(payload));
            // Umweg ueber JSON wie im echten Kanal - sonst faellt ein Typfehler erst
            // im Netzwerkfall auf.
            return gson.fromJson(gson.toJson(answer), responseType);
        }

        @Override
        public String moduleId() {
            return "punishment";
        }

        @Override
        public ModuleDescriptor descriptor() {
            return new ModuleDescriptor("punishment", "Punishment", "1.0.0",
                    PunishmentModule.class.getName(), "1.0",
                    List.of(), List.of(), List.of(), Map.of());
        }

        @Override
        public EventBus events() {
            return events;
        }

        @Override
        public ModuleCommands commands() {
            return (name, description, usage, permission, command) -> commandNames.add(name);
        }

        @Override
        public ModuleDatabase database() {
            return new ModuleDatabase() {

                @Override
                public int migrate() {
                    // Dieselbe Konfiguration wie im Master - inklusive baselineVersion("0"),
                    // ohne das ueberspringt Flyway die V1 des Moduls.
                    return Flyway.configure(getClass().getClassLoader())
                            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                                    POSTGRES.getPassword())
                            .locations("classpath:db/migration/punishment")
                            .table("flyway_schema_history_punishment")
                            .baselineOnMigrate(true)
                            .baselineVersion("0")
                            .load()
                            .migrate()
                            .migrationsExecuted;
                }

                @Override
                public Connection connection() throws SQLException {
                    return connect();
                }

                @Override
                public String tablePrefix() {
                    return "punishment_";
                }
            };
        }

        @Override
        public ModuleChannel channel() {
            return new ModuleChannel() {

                @Override
                public <R> java.util.concurrent.CompletableFuture<R> request(
                        Target target, String key, Object payload, Class<R> responseType) {
                    return java.util.concurrent.CompletableFuture.failedFuture(
                            new UnsupportedOperationException("im Test nicht gebraucht"));
                }

                @Override
                public int send(Target target, String key, Object payload) {
                    return 0;
                }

                @Override
                public <T> void respond(String key, Class<T> requestType,
                                        Function<T, Object> handler) {
                    responders.put(key, json ->
                            handler.apply(gson.fromJson(json, requestType)));
                }

                @Override
                public <T> void listen(String key, Class<T> requestType,
                                       Consumer<T> handler) {
                    responders.put(key, json -> {
                        handler.accept(gson.fromJson(json, requestType));
                        return null;
                    });
                }
            };
        }

        @Override
        public ModulePermissions permissions() {
            return new ModulePermissions() {

                @Override
                public boolean has(UUID uuid, String node) {
                    return false;
                }

                @Override
                public boolean has(UUID uuid, String node, String group, String server) {
                    return false;
                }

                @Override
                public Optional<String> explain(UUID uuid, String node) {
                    return Optional.empty();
                }

                @Override
                public void declare(String node, String description) {
                }
            };
        }

        @Override
        public ModulePlayers players() {
            return new ModulePlayers() {

                @Override
                public Optional<PlayerInfo> find(UUID uuid) {
                    return Optional.empty();
                }

                @Override
                public Optional<PlayerInfo> findByName(String name) {
                    return Optional.empty();
                }

                @Override
                public Optional<String> currentServer(UUID uuid) {
                    return Optional.empty();
                }

                @Override
                public List<String> suggestNames(String prefix, int limit) {
                    return List.of();
                }

                @Override
                public Optional<String> lastIpHash(UUID uuid) {
                    return Optional.empty();
                }

                @Override
                public boolean kick(UUID uuid, String messageKey,
                                    Map<String, String> placeholders) {
                    return false;
                }

                @Override
                public boolean message(UUID uuid, String messageKey,
                                       Map<String, String> placeholders) {
                    return false;
                }

                @Override
                public int broadcast(String permission, String messageKey,
                                     Map<String, String> placeholders) {
                    return 0;
                }
            };
        }

        @Override
        public ModuleConfig config() {
            return new ModuleConfig() {

                @Override
                public <T> T load(Class<T> type, T defaults) {
                    return defaults;
                }

                @Override
                public <T> T reload(Class<T> type, T defaults) {
                    return defaults;
                }

                @Override
                public void save(Object value) {
                }
            };
        }

        @Override
        public int loadMessages() {
            loadedMessages = 1;
            return 1;
        }

        @Override
        public Path dataDirectory() {
            return dataDirectory;
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return Executors.newSingleThreadScheduledExecutor();
        }

        @Override
        public Logger logger() {
            return LOG;
        }
    }
}
