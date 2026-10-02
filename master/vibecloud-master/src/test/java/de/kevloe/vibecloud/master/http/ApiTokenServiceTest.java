package de.kevloe.vibecloud.master.http;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokens der REST-Schnittstelle gegen eine echte Datenbank.
 *
 * <p>Geprueft wird vor allem, was bei einem Fehler Zugriff geben wuerde: ein falsches
 * Geheimnis, eine falsche Id, ein abgelaufenes Token, ein fehlendes Recht.
 */
@Testcontainers
class ApiTokenServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("devonly");

    /** Eine Datenbank fuer die ganze Klasse - je Test ein Pool sprengt das Limit. */
    private static Database database;

    private ApiTokenService tokens;

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
            connection.createStatement().execute("TRUNCATE api_tokens");
        }
        tokens = new ApiTokenService(database);
    }

    @Test
    @DisplayName("Ein angelegtes Token wird erkannt")
    void angelegtesTokenWirdErkannt() {
        String token = tokens.create("dashboard", List.of("servers.read"), "CONSOLE", null);

        var verified = tokens.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().name()).isEqualTo("dashboard");
        assertThat(verified.get().allows("servers.read")).isTrue();
        assertThat(verified.get().allows("players.write")).isFalse();
    }

    @Test
    @DisplayName("Das Geheimnis steht nicht in der Datenbank")
    void geheimnisStehtNichtInDerDatenbank() {
        String token = tokens.create("dashboard", List.of("*"), "CONSOLE", null);
        String secret = token.substring(token.indexOf('.') + 1);

        // Nur der Hash wird gespeichert. Wer die Datenbank liest, hat noch kein Token.
        assertThat(tokens.all()).hasSize(1);
        assertThat(tokens.all().getFirst().toString()).doesNotContain(secret);
    }

    @Test
    @DisplayName("Ein falsches Geheimnis wird abgewiesen")
    void falschesGeheimnisWirdAbgewiesen() {
        String token = tokens.create("dashboard", List.of("*"), "CONSOLE", null);
        String id = token.substring(0, token.indexOf('.'));

        assertThat(tokens.verify(id + ".falsch")).isEmpty();
    }

    @Test
    @DisplayName("Eine unbekannte Id wird abgewiesen")
    void unbekannteIdWirdAbgewiesen() {
        assertThat(tokens.verify("gibtsnicht.irgendwas")).isEmpty();
    }

    @Test
    @DisplayName("Unsinn als Token wird abgewiesen, nicht zum Absturz gebracht")
    void unsinnWirdAbgewiesen() {
        // Alles davon kann in einem Authorization-Header stehen.
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify(".")).isEmpty();
        assertThat(tokens.verify("ohnepunkt")).isEmpty();
        assertThat(tokens.verify(".nurgeheimnis")).isEmpty();
        assertThat(tokens.verify("nurid.")).isEmpty();
    }

    @Test
    @DisplayName("Ein abgelaufenes Token wird abgewiesen")
    void abgelaufenesTokenWirdAbgewiesen() {
        String token = tokens.create("alt", List.of("*"), "CONSOLE",
                Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("Ein Token mit Zukunftsdatum gilt")
    void tokenMitZukunftsdatumGilt() {
        String token = tokens.create("befristet", List.of("*"), "CONSOLE",
                Instant.now().plus(Duration.ofDays(30)));

        assertThat(tokens.verify(token)).isPresent();
    }

    @Test
    @DisplayName("Der Stern deckt jedes Recht ab")
    void sternDecktJedesRechtAb() {
        String token = tokens.create("admin", List.of("*"), "CONSOLE", null);

        var verified = tokens.verify(token).orElseThrow();

        assertThat(verified.allows("servers.read")).isTrue();
        assertThat(verified.allows("players.write")).isTrue();
        assertThat(verified.allows("was.auch.immer")).isTrue();
    }

    @Test
    @DisplayName("Ein zurueckgezogenes Token gilt nicht mehr")
    void zurueckgezogenesTokenGiltNichtMehr() {
        String token = tokens.create("weg", List.of("*"), "CONSOLE", null);
        String id = token.substring(0, token.indexOf('.'));

        assertThat(tokens.revoke(id)).isTrue();
        assertThat(tokens.verify(token)).isEmpty();
        assertThat(tokens.revoke(id)).isFalse();
    }

    @Test
    @DisplayName("Die Benutzung wird vermerkt")
    void benutzungWirdVermerkt() {
        String token = tokens.create("dashboard", List.of("*"), "CONSOLE", null);
        assertThat(tokens.all().getFirst().lastUsedAt()).isNull();

        tokens.verify(token);

        // Damit sichtbar ist, welches Token noch gebraucht wird, bevor man es loescht.
        assertThat(tokens.all().getFirst().lastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("Zwei Tokens sind verschieden")
    void zweiTokensSindVerschieden() {
        String erstes = tokens.create("a", List.of("*"), "CONSOLE", null);
        String zweites = tokens.create("b", List.of("*"), "CONSOLE", null);

        assertThat(erstes).isNotEqualTo(zweites);
        assertThat(tokens.verify(erstes).orElseThrow().name()).isEqualTo("a");
        assertThat(tokens.verify(zweites).orElseThrow().name()).isEqualTo("b");
    }
}
