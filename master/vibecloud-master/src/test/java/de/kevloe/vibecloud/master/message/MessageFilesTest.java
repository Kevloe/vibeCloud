package de.kevloe.vibecloud.master.message;

import de.kevloe.vibecloud.common.message.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die mitgelieferten Sprachdateien (PLAN.md Abschnitt 15).
 *
 * <p>Zwei Zusagen aus dem Plan werden hier eingelöst:
 * <ul>
 *   <li>Jeder im Code benutzte Schluessel existiert in {@code de.yml}</li>
 *   <li>Jeder Schluessel aus {@code de.yml} existiert in jeder weiteren Sprachdatei</li>
 * </ul>
 *
 * <p>Ohne diese Tests faellt ein fehlender Text erst auf, wenn ein Spieler genau diese
 * Meldung sieht - und dann steht dort der Schluesselname.
 */
class MessageFilesTest {

    /**
     * Schluessel, die der Code benutzt. Bewusst hier gepflegt und nicht aus dem Code
     * gelesen: Eine Reflection-Suche wuerde dynamisch zusammengebaute Schluessel
     * uebersehen und damit falsche Sicherheit geben.
     */
    private static final List<String> USED_BY_CODE = List.of(
            "login.maintenance",
            "login.master_offline",
            "login.no_fallback",
            "login.network_full",
            "server.kicked",
            "server.moved",
            "server.not_found",
            "server.connect_failed",
            "server.stopping",
            "language.current",
            "language.available",
            "language.changed",
            "language.unknown",
            "language.no_permission",
            "maintenance.enabled_global",
            "maintenance.disabled_global",
            "maintenance.enabled_group",
            "maintenance.disabled_group",
            "maintenance.bypass_notice",
            "error.internal",
            "error.no_permission");

    @Test
    void deYmlEnthaeltAlleImCodeBenutztenSchluessel() throws IOException {
        Map<String, String> german = loadFromJar("de");

        assertThat(german.keySet())
                .as("Fehlende Schluessel in messages/de.yml")
                .containsAll(USED_BY_CODE);
    }

    @Test
    void deYmlIstGueltigesYamlUndNichtLeer() throws IOException {
        Map<String, String> german = loadFromJar("de");

        assertThat(german).isNotEmpty();
        assertThat(german.values()).noneMatch(String::isBlank);
    }

    /**
     * Platzhalter muessen benannt sein. Positionsbasierte wie {@code {0}} oder {@code %s}
     * halten bei Uebersetzungen nicht, weil dort die Wortreihenfolge wechselt.
     */
    @Test
    void platzhalterSindBenannt() throws IOException {
        Pattern positional = Pattern.compile("\\{\\d+}|%[sd]");

        for (Map.Entry<String, String> entry : loadFromJar("de").entrySet()) {
            Matcher matcher = positional.matcher(entry.getValue());
            assertThat(matcher.find())
                    .as("Schluessel %s nutzt einen positionsbasierten Platzhalter: %s",
                            entry.getKey(), entry.getValue())
                    .isFalse();
        }
    }

    /** Jede weitere Sprachdatei muss vollstaendig sein. */
    @Test
    void weitereSprachenSindVollstaendig() throws IOException {
        Map<String, String> german = loadFromJar("de");
        Set<String> required = german.keySet();

        for (String locale : List.of("en")) {
            Map<String, String> other = loadFromJarIfPresent(locale);
            if (other == null) {
                continue;
            }
            assertThat(other.keySet())
                    .as("messages/%s.yml ist unvollstaendig", locale)
                    .containsAll(required);
        }
    }

    private static Map<String, String> loadFromJar(String locale) throws IOException {
        Map<String, String> loaded = loadFromJarIfPresent(locale);
        assertThat(loaded).as("messages/%s.yml fehlt", locale).isNotNull();
        return loaded;
    }

    private static Map<String, String> loadFromJarIfPresent(String locale) throws IOException {
        try (InputStream in = MessageFilesTest.class
                .getResourceAsStream("/messages/" + locale + ".yml")) {
            return in == null ? null : MessageBundle.loadYaml(in);
        }
    }
}
