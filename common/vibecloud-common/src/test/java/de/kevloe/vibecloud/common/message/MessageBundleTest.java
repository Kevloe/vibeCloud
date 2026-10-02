package de.kevloe.vibecloud.common.message;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Sprachsystem (PLAN.md Abschnitt 11a).
 *
 * <p>Der wichtigste Test ist {@link #fehlenderSchluesselZeigtDenSchluesselNamen()}: Ein
 * fehlender Eintrag muss sichtbar sein, nicht als leerer Bildschirm verschwinden.
 */
class MessageBundleTest {

    private static final String DE = """
            prefix: "<gray>vibeCloud</gray> "
            login:
              maintenance: "<red>Wartung</red>"
              full: "<red>Voll: <aktuell>/<maximal></red>"
            welcome:
              join: "<gray>Willkommen, <spieler>.</gray>"
            """;

    private static final String EN = """
            login:
              maintenance: "<red>Maintenance</red>"
            """;

    @Test
    void flachtVerschachteltesYamlAb() throws IOException {
        Map<String, String> flat = MessageBundle.loadYaml(stream(DE));

        assertThat(flat).containsKeys("prefix", "login.maintenance", "login.full", "welcome.join");
        assertThat(flat.get("login.maintenance")).isEqualTo("<red>Wartung</red>");
    }

    @Test
    void liefertTextDerGewaehltenSprache() throws IOException {
        MessageBundle bundle = bundle();

        assertThat(bundle.raw("en", "login.maintenance")).isEqualTo("<red>Maintenance</red>");
        assertThat(bundle.raw("de", "login.maintenance")).isEqualTo("<red>Wartung</red>");
    }

    /** Fehlt ein Schluessel in der gewaehlten Sprache, greift die Standardsprache. */
    @Test
    void faelltAufDieStandardspracheZurueck() throws IOException {
        MessageBundle bundle = bundle();

        assertThat(bundle.raw("en", "welcome.join")).isEqualTo("<gray>Willkommen, <spieler>.</gray>");
    }

    /**
     * Der Kern: Ohne diesen Rueckfall saehe der Spieler einen leeren Bildschirm, und
     * niemand wuesste, dass ein Text fehlt.
     */
    @Test
    void fehlenderSchluesselZeigtDenSchluesselNamen() throws IOException {
        MessageBundle bundle = bundle();

        assertThat(bundle.raw("de", "gibt.es.nicht")).isEqualTo("gibt.es.nicht");
    }

    @Test
    void setztBenanntePlatzhalterEin() throws IOException {
        MessageBundle bundle = bundle();

        String text = bundle.get("de", "login.full", Map.of("aktuell", "120", "maximal", "500"));

        assertThat(text).isEqualTo("<red>Voll: 120/500</red>");
    }

    /** Nicht uebergebene Platzhalter bleiben stehen - das faellt auf, Leerstellen nicht. */
    @Test
    void unbekanntePlatzhalterBleibenStehen() throws IOException {
        MessageBundle bundle = bundle();

        assertThat(bundle.get("de", "welcome.join", Map.of())).contains("<spieler>");
    }

    @Test
    void loestSprachenMitRegionAuf() throws IOException {
        MessageBundle bundle = bundle();

        assertThat(bundle.resolveLocale("en")).isEqualTo("en");
        // Minecraft liefert "de_DE" - die Sprache ohne Region muss greifen.
        assertThat(bundle.resolveLocale("de_DE")).isEqualTo("de");
        assertThat(bundle.resolveLocale("en_US")).isEqualTo("en");
        // Unbekannt -> Standardsprache, nicht Fehler.
        assertThat(bundle.resolveLocale("fr_FR")).isEqualTo("de");
        assertThat(bundle.resolveLocale(null)).isEqualTo("de");
    }

    @Test
    void meldetLueckenInUebersetzungen() throws IOException {
        MessageBundle bundle = bundle();

        assertThat(bundle.missingKeys("en"))
                .contains("welcome.join", "login.full", "prefix")
                .doesNotContain("login.maintenance");
        assertThat(bundle.missingKeys("de")).isEmpty();
    }

    @Test
    void ohneSpracheGiltDieStandardsprache() throws IOException {
        // Die MOTD gilt niemandem persoenlich und fragt ohne Sprache an. Vorher warf das
        // hier - und der Proxy beantwortete keinen Server-List-Ping mehr.
        MessageBundle bundle = bundle();

        assertThat(bundle.raw(null, "login.maintenance"))
                .isEqualTo(bundle.raw("de", "login.maintenance"));
        assertThat(bundle.get(null, "welcome.join", Map.of("spieler", "Kevin")))
                .isEqualTo(bundle.get("de", "welcome.join", Map.of("spieler", "Kevin")));
    }

    private static MessageBundle bundle() throws IOException {
        return new MessageBundle("de", Map.of(
                "de", MessageBundle.loadYaml(stream(DE)),
                "en", MessageBundle.loadYaml(stream(EN))));
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
