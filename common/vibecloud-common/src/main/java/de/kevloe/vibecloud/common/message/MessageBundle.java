package de.kevloe.vibecloud.common.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Geladene Sprachdateien (PLAN.md Abschnitt 11a).
 *
 * <p><b>Kein spielersichtbarer Text steht im Code.</b> Alles laeuft ueber Schluessel, die
 * hier aufgeloest werden - Plugins, Master-Konsole und Module gleichermassen.
 *
 * <p>Verschachteltes YAML wird beim Laden zu flachen Schluesseln:
 * {@code login.maintenance} statt verschachtelter Maps. Das macht Lookups billig und
 * Tests einfach.
 */
public final class MessageBundle {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBundle.class);

    private final String defaultLocale;

    /** Sprache -> (flacher Schluessel -> MiniMessage-Text). */
    private final Map<String, Map<String, String>> locales;

    public MessageBundle(String defaultLocale, Map<String, Map<String, String>> locales) {
        this.defaultLocale = defaultLocale;
        this.locales = Map.copyOf(locales);
    }

    /** Laedt eine einzelne YAML-Datei und flacht sie ab. */
    public static Map<String, String> loadYaml(InputStream stream) throws IOException {
        try (stream) {
            Object root = new Yaml().load(stream);
            Map<String, String> flat = new TreeMap<>();
            if (root instanceof Map<?, ?> map) {
                flatten("", map, flat);
            }
            return flat;
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> target) {
        map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(path, nested, target);
            } else if (value != null) {
                target.put(path, String.valueOf(value));
            }
        });
    }

    /**
     * Loest einen Schluessel auf.
     *
     * <p>Auflosungskette: gewaehlte Sprache, dann Standardsprache, dann der Schluesselname
     * selbst. Der letzte Schritt ist wichtig: Ein fehlender Eintrag zeigt
     * {@code login.maintenance} statt eines leeren Bildschirms - so faellt die Luecke auf
     * statt still zu verschwinden.
     */
    public String raw(String locale, String key) {
        // null heisst "keine Sprache bekannt" - etwa bei der MOTD, die niemandem
        // persoenlich gilt. Ohne diese Zeile wirft die unveraenderliche Map beim
        // Nachschlagen, und der Proxy beantwortet keinen einzigen Server-List-Ping mehr.
        Map<String, String> chosen = locale == null ? null : locales.get(locale);
        if (chosen != null) {
            String value = chosen.get(key);
            if (value != null) {
                return value;
            }
        }
        Map<String, String> fallback = locales.get(defaultLocale);
        if (fallback != null) {
            String value = fallback.get(key);
            if (value != null) {
                return value;
            }
        }
        LOG.warn("Nachrichten-Schluessel '{}' fehlt in allen Sprachen", key);
        return key;
    }

    /**
     * Loest auf und setzt benannte Platzhalter ein.
     *
     * @param placeholders z. B. {@code Map.of("spieler", "Kevin")} fuer {@code <spieler>}
     */
    public String get(String locale, String key, Map<String, String> placeholders) {
        String text = raw(locale, key);
        if (placeholders == null || placeholders.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        return result;
    }

    public String get(String locale, String key) {
        return get(locale, key, Map.of());
    }

    /** Welche Sprache tatsaechlich benutzt wird, wenn der Client diese anfragt. */
    public String resolveLocale(String requested) {
        if (requested != null && locales.containsKey(requested)) {
            return requested;
        }
        // Nur die Sprache ohne Region versuchen: "de_DE" -> "de".
        if (requested != null && requested.contains("_")) {
            String language = requested.substring(0, requested.indexOf('_'));
            if (locales.containsKey(language)) {
                return language;
            }
        }
        return defaultLocale;
    }

    public Set<String> availableLocales() {
        return locales.keySet();
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    public Map<String, String> entriesOf(String locale) {
        return locales.getOrDefault(locale, Map.of());
    }

    /** Alle Schluessel der Standardsprache - die Pflichtmenge fuer jede weitere Sprache. */
    public Set<String> requiredKeys() {
        return entriesOf(defaultLocale).keySet();
    }

    /**
     * Prueft, ob eine Sprache vollstaendig ist.
     *
     * @return fehlende Schluessel, leer wenn alles da ist
     */
    public Set<String> missingKeys(String locale) {
        Map<String, String> entries = entriesOf(locale);
        Map<String, String> required = new LinkedHashMap<>(entriesOf(defaultLocale));
        required.keySet().removeAll(entries.keySet());
        return required.keySet();
    }
}
