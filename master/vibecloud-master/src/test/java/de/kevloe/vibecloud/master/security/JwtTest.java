package de.kevloe.vibecloud.master.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Zugangstoken des Dashboards.
 *
 * <p>Geprueft wird vor allem, was bei einem Fehler Zugriff geben wuerde: eine veraenderte
 * Nutzlast, eine fremde Signatur, ein Refresh-Token an der Stelle eines Zugangstokens.
 */
class JwtTest {

    private static final UUID SPIELER = UUID.randomUUID();

    @TempDir
    private Path secrets;

    private Jwt jwt;

    @BeforeEach
    void setUp() throws IOException {
        jwt = Jwt.load(secrets);
    }

    @Test
    @DisplayName("Ein eigenes Token wird erkannt")
    void eigenesTokenWirdErkannt() {
        String token = jwt.accessToken(SPIELER, "Kevloe", 3);

        var claims = jwt.verify(token, "access");

        assertThat(claims).isPresent();
        assertThat(claims.get().uuid()).isEqualTo(SPIELER);
        assertThat(claims.get().username()).isEqualTo("Kevloe");
        assertThat(claims.get().sessionVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("Eine veraenderte Nutzlast wird abgewiesen")
    void veraenderteNutzlastWirdAbgewiesen() {
        String token = jwt.accessToken(SPIELER, "Kevloe", 1);
        String[] parts = token.split("\\.");

        // Nutzlast gegen eine mit anderer Sitzungs-Version tauschen - die Signatur passt
        // dann nicht mehr. Ohne diese Pruefung koennte sich jeder jede Version ausstellen.
        String fremd = jwt.accessToken(SPIELER, "Kevloe", 99).split("\\.")[1];
        String gebastelt = parts[0] + "." + fremd + "." + parts[2];

        assertThat(jwt.verify(gebastelt, "access")).isEmpty();
    }

    @Test
    @DisplayName("Ein Token mit fremdem Schluessel wird abgewiesen")
    void fremderSchluesselWirdAbgewiesen() throws IOException {
        Path andere = Files.createTempDirectory("vibecloud-jwt");
        try {
            String fremdesToken = Jwt.load(andere).accessToken(SPIELER, "Kevloe", 1);

            assertThat(jwt.verify(fremdesToken, "access")).isEmpty();
        } finally {
            Files.deleteIfExists(andere.resolve("jwt.key"));
            Files.deleteIfExists(andere);
        }
    }

    @Test
    @DisplayName("Ein Refresh-Token taugt nicht fuer Anfragen")
    void refreshTokenTaugtNichtFuerAnfragen() {
        // Sonst waere das langlebige Token im Cookie so stark wie das kurzlebige.
        String refresh = jwt.refreshToken(SPIELER, "Kevloe", 1);

        assertThat(jwt.verify(refresh, "access")).isEmpty();
        assertThat(jwt.verify(refresh, "refresh")).isPresent();
    }

    @Test
    @DisplayName("Ein Zugangstoken taugt nicht zum Erneuern")
    void zugangstokenTaugtNichtZumErneuern() {
        String access = jwt.accessToken(SPIELER, "Kevloe", 1);

        assertThat(jwt.verify(access, "refresh")).isEmpty();
    }

    @Test
    @DisplayName("Unsinn wird abgewiesen, nicht zum Absturz gebracht")
    void unsinnWirdAbgewiesen() {
        // Alles davon kann in einem Authorization-Header stehen.
        assertThat(jwt.verify(null, "access")).isEmpty();
        assertThat(jwt.verify("", "access")).isEmpty();
        assertThat(jwt.verify("abc", "access")).isEmpty();
        assertThat(jwt.verify("a.b.c", "access")).isEmpty();
        assertThat(jwt.verify("a.b.c.d", "access")).isEmpty();
        assertThat(jwt.verify("....", "access")).isEmpty();
    }

    @Test
    @DisplayName("Der Schluessel ueberlebt einen Neustart")
    void schluesselUeberlebtNeustart() throws IOException {
        String token = jwt.accessToken(SPIELER, "Kevloe", 1);

        // Neue Instanz, dasselbe Verzeichnis: Nach einem Master-Neustart muessen
        // bestehende Sitzungen weitergelten.
        assertThat(Jwt.load(secrets).verify(token, "access")).isPresent();
        assertThat(secrets.resolve("jwt.key")).exists();
    }

    @Test
    @DisplayName("Die Sitzungs-Version steckt im Token")
    void sitzungsVersionSteckImToken() {
        // Der Hebel, mit dem ein Rechte-Entzug laufende Sitzungen beendet: Die Nummer
        // wird in der Datenbank hochgezaehlt, das Token traegt noch die alte.
        assertThat(jwt.verify(jwt.accessToken(SPIELER, "Kevloe", 7), "access")
                .orElseThrow().sessionVersion()).isEqualTo(7);
    }
}
