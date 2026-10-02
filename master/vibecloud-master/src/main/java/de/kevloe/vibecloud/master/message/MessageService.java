package de.kevloe.vibecloud.master.message;

import de.kevloe.vibecloud.common.message.MessageBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Der Master ist die einzige Quelle fuer Sprachdateien (PLAN.md Abschnitt 11a).
 *
 * <p>Beim ersten Start wird {@code messages/de.yml} aus dem Jar herausgeschrieben, damit
 * Texte ohne Rebuild aenderbar sind. Plugins holen sich eine Kopie beim Verbinden;
 * {@code cloud messages reload} liest neu ein und verteilt sofort - fuer eine Textkorrektur
 * muss kein Server neu starten.
 */
public final class MessageService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);

    private static final String DEFAULT_LOCALE = "de";
    private static final String SUFFIX = ".yml";

    private final Path directory;
    private volatile MessageBundle bundle;

    /**
     * Texte der Module, je Sprache.
     *
     * <p>Getrennt vom Kern gehalten: Beim Entladen eines Moduls muessen genau seine Texte
     * wieder verschwinden, ohne die Datei des Betreibers anzufassen.
     */
    private final Map<String, Map<String, Map<String, String>>> moduleMessages =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MessageService(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        extractDefaultIfMissing();
        appendMissingDefaults();
        this.bundle = load();
    }

    /**
     * Legt die mitgelieferte Standardsprache ab, falls sie fehlt.
     *
     * <p>Absichtlich nicht ueberschreiben: Sonst wuerden eigene Textaenderungen bei jedem
     * Cloud-Update verloren gehen.
     */
    private void extractDefaultIfMissing() throws IOException {
        Path target = directory.resolve(DEFAULT_LOCALE + SUFFIX);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = getClass().getResourceAsStream(
                "/messages/" + DEFAULT_LOCALE + SUFFIX)) {
            if (in == null) {
                throw new IOException("messages/de.yml fehlt im Jar");
            }
            Files.copy(in, target);
            LOG.info("{} angelegt - Texte koennen dort ohne Rebuild geaendert werden", target);
        }
    }

    /**
     * Ergaenzt Schluessel, die es im Jar gibt, in der Datei aber noch nicht.
     *
     * <p>Die Datei wird absichtlich nie ueberschrieben - eigene Texte sollen ein Update
     * ueberleben. Dadurch fehlten bisher aber alle Schluessel, die nach dem ersten Start
     * dazukamen: Im Spiel stand dann der Schluesselname. Deshalb werden nur die fehlenden
     * unten angehaengt, als flache Zeilen mit Punkt-Schreibweise - die liest der Lader
     * genauso wie die verschachtelte Form.
     */
    private void appendMissingDefaults() throws IOException {
        Path target = directory.resolve(DEFAULT_LOCALE + SUFFIX);

        Map<String, String> bundled;
        try (InputStream in = getClass().getResourceAsStream(
                "/messages/" + DEFAULT_LOCALE + SUFFIX)) {
            if (in == null) {
                return;
            }
            bundled = MessageBundle.loadYaml(in);
        }

        Map<String, String> existing;
        try (InputStream in = Files.newInputStream(target)) {
            existing = MessageBundle.loadYaml(in);
        }

        List<String> missing = bundled.keySet().stream()
                .filter(key -> !existing.containsKey(key))
                .sorted()
                .toList();

        if (missing.isEmpty()) {
            return;
        }

        StringBuilder addition = new StringBuilder("\n# Von vibeCloud ergaenzt - "
                + "neue Schluessel dieser Version:\n");
        for (String key : missing) {
            // Einfache Anfuehrungszeichen: Darin ist nur das Anfuehrungszeichen selbst
            // besonders, und das wird verdoppelt. MiniMessage-Tags bleiben unangetastet.
            addition.append(key).append(": '")
                    .append(bundled.get(key).replace("'", "''"))
                    .append("'\n");
        }
        Files.writeString(target, addition.toString(), java.nio.file.StandardOpenOption.APPEND);

        LOG.info("{} neue Schluessel in {} ergaenzt: {}", missing.size(), target.getFileName(),
                missing.size() > 5 ? missing.subList(0, 5) + " ..." : missing);
    }

    /** Liest alle {@code *.yml} im Verzeichnis ein. */
    public MessageBundle load() throws IOException {
        Map<String, Map<String, String>> locales = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(SUFFIX)).sorted().toList()) {
                String name = file.getFileName().toString();
                String locale = name.substring(0, name.length() - SUFFIX.length());
                try (InputStream in = Files.newInputStream(file)) {
                    locales.put(locale, MessageBundle.loadYaml(in));
                } catch (IOException exception) {
                    LOG.error("Sprachdatei {} konnte nicht gelesen werden - wird uebersprungen",
                            name, exception);
                }
            }
        }
        if (!locales.containsKey(DEFAULT_LOCALE)) {
            throw new IOException("Die Standardsprache " + DEFAULT_LOCALE + SUFFIX + " fehlt");
        }

        // Modul-Texte unter ihrer Modul-Id einhaengen: "punishment.ban.screen".
        // So kann ein Modul keine Core-Texte ueberschreiben (PLAN.md Abschnitt 11a).
        moduleMessages.forEach((moduleId, byLocale) ->
                byLocale.forEach((locale, entries) -> {
                    Map<String, String> target = locales.computeIfAbsent(locale,
                            key -> new LinkedHashMap<>());
                    entries.forEach((key, value) -> target.put(moduleId + "." + key, value));
                }));

        MessageBundle loaded = new MessageBundle(DEFAULT_LOCALE, locales);
        warnAboutGaps(loaded);
        LOG.info("Sprachen geladen: {} ({} Schluessel in {})",
                String.join(", ", loaded.availableLocales()),
                loaded.requiredKeys().size(), DEFAULT_LOCALE);
        return loaded;
    }

    /**
     * Meldet unvollstaendige Uebersetzungen beim Start.
     *
     * <p>Fehlende Schluessel fallen sonst erst auf, wenn ein Spieler mit dieser Sprache
     * genau diese Meldung sieht - und dann steht dort der Schluesselname.
     */
    private void warnAboutGaps(MessageBundle loaded) {
        for (String locale : loaded.availableLocales()) {
            if (locale.equals(DEFAULT_LOCALE)) {
                continue;
            }
            Set<String> missing = loaded.missingKeys(locale);
            if (!missing.isEmpty()) {
                LOG.warn("Sprache {} fehlen {} Schluessel (es greift {}): {}",
                        locale, missing.size(), DEFAULT_LOCALE,
                        missing.stream().limit(5).toList());
            }
        }
    }

    /**
     * Haengt die Texte eines Moduls ein.
     *
     * <p>Das Modul liefert sie beim Aktivieren aus seinem JAR. Die Schluessel bekommen die
     * Modul-Id als Praefix, damit ein Modul keine Core-Texte ueberschreiben kann.
     *
     * @param byLocale Sprache -> (Schluessel ohne Praefix -> Text)
     */
    public void registerModuleMessages(String moduleId,
                                       Map<String, Map<String, String>> byLocale) {
        moduleMessages.put(moduleId, Map.copyOf(byLocale));
        reload();
        LOG.info("Texte von Modul {} eingehaengt ({} Sprachen)", moduleId, byLocale.size());
    }

    /** Entfernt die Texte eines Moduls - beim Entladen. */
    public void unregisterModuleMessages(String moduleId) {
        if (moduleMessages.remove(moduleId) != null) {
            reload();
        }
    }

    // ---------------------------------------------------------------- Dateien bearbeiten

    /**
     * Erlaubte Sprachkennungen.
     *
     * <p>Die Kennung wird zu einem Dateinamen - ohne diese Pruefung waere
     * {@code ../../config} eine gueltige "Sprache". Deshalb keine Punkte, keine Schraegstriche,
     * nur das, was eine Sprache wirklich ist: {@code de}, {@code en}, {@code pt_BR}.
     */
    private static final java.util.regex.Pattern VALID_LOCALE =
            java.util.regex.Pattern.compile("[a-z]{2,3}(_[A-Za-z]{2})?");

    public static boolean isValidLocale(String locale) {
        return locale != null && VALID_LOCALE.matcher(locale).matches();
    }

    public String defaultLocale() {
        return DEFAULT_LOCALE;
    }

    /** Welche Sprachdateien es gibt - nach dem Verzeichnis, nicht nach dem geladenen Bundle. */
    public List<String> localeFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Die Eintraege einer Sprachdatei.
     *
     * <p>Bewusst aus der <b>Datei</b> und nicht aus dem geladenen Bundle: Dort haengen auch
     * die Texte der Module mit drin ({@code punishment.ban.screen}). Die stehen im JAR des
     * Moduls, und wer sie beim Speichern mit in die Datei des Betreibers schriebe, fror sie
     * dort fuer immer ein.
     */
    public Map<String, String> entriesOfFile(String locale) throws IOException {
        requireValid(locale);
        Path file = fileOf(locale);
        if (!Files.exists(file)) {
            throw new java.util.NoSuchElementException("Sprache " + locale + " gibt es nicht");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return MessageBundle.loadYaml(in);
        }
    }

    /**
     * Schreibt eine Sprachdatei neu.
     *
     * <p>Flach, in Punkt-Schreibweise und alphabetisch - der Lader versteht beide Formen.
     * Das heisst aber auch: <b>Kommentare und Verschachtelung der Datei gehen dabei
     * verloren.</b> Wer das nicht will, bearbeitet die Datei weiter von Hand; die
     * Oberflaeche sagt es vorher.
     */
    public void saveFile(String locale, Map<String, String> entries) throws IOException {
        requireValid(locale);
        StringBuilder text = new StringBuilder(
                "# Von vibeCloud geschrieben (Dashboard). Flache Schluessel in Punkt-Schreibweise;\n"
                + "# Format der Texte ist MiniMessage, Platzhalter sind benannt: <spieler>.\n\n");

        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> text.append(entry.getKey()).append(": '")
                        // In einfachen Anfuehrungszeichen ist nur das Anfuehrungszeichen
                        // selbst besonders - MiniMessage-Tags bleiben unangetastet.
                        .append(entry.getValue().replace("'", "''"))
                        .append("'\n"));

        // Erst daneben schreiben, dann umbenennen: Faellt der Master mitten im Schreiben
        // aus, ist die alte Datei noch heil statt halb ueberschrieben.
        Path file = fileOf(locale);
        Path temporary = directory.resolve(locale + SUFFIX + ".tmp");
        Files.writeString(temporary, text.toString());
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Legt eine neue Sprache an - mit den Texten der Standardsprache als Vorlage.
     *
     * <p>Leer anzulegen waere unbrauchbar: Man saehe nur Schluesselnamen und wuesste nicht,
     * was zu uebersetzen ist. So steht ueberall der deutsche Text, und man ersetzt ihn
     * Zeile fuer Zeile - bis dahin greift ohnehin die Standardsprache.
     */
    public void createLocale(String locale) throws IOException {
        requireValid(locale);
        if (locale.equals(DEFAULT_LOCALE)) {
            throw new IllegalArgumentException("Die Standardsprache gibt es schon");
        }
        if (Files.exists(fileOf(locale))) {
            throw new IllegalStateException("Die Sprache " + locale + " gibt es schon");
        }
        saveFile(locale, entriesOfFile(DEFAULT_LOCALE));
    }

    /** Loescht eine Sprachdatei. Die Standardsprache nie - ohne sie laedt gar nichts mehr. */
    public void deleteLocale(String locale) throws IOException {
        requireValid(locale);
        if (locale.equals(DEFAULT_LOCALE)) {
            throw new IllegalArgumentException(
                    "Die Standardsprache " + DEFAULT_LOCALE + " laesst sich nicht loeschen");
        }
        if (!Files.deleteIfExists(fileOf(locale))) {
            throw new java.util.NoSuchElementException("Sprache " + locale + " gibt es nicht");
        }
    }

    private Path fileOf(String locale) {
        return directory.resolve(locale + SUFFIX);
    }

    private static void requireValid(String locale) {
        if (!isValidLocale(locale)) {
            throw new IllegalArgumentException(
                    "Keine gueltige Sprachkennung: " + locale + " (erwartet: de, en, pt_BR)");
        }
    }

    /** Liest neu ein. Gibt zurueck, ob es geklappt hat - bei einem Fehler bleibt das Alte gueltig. */
    public boolean reload() {
        try {
            bundle = load();
            return true;
        } catch (IOException exception) {
            // Bewusst das alte Bundle behalten: Eine kaputte Datei darf nicht dazu fuehren,
            // dass im Spiel ueberall nur noch Schluesselnamen stehen.
            LOG.error("Sprachdateien konnten nicht neu geladen werden - die alten bleiben aktiv",
                    exception);
            return false;
        }
    }

    public MessageBundle bundle() {
        return bundle;
    }
}
