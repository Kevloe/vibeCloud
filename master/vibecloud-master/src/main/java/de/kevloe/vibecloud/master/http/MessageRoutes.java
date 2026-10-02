package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.message.MessageDistributor;
import de.kevloe.vibecloud.master.message.MessageService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Sprachen anlegen, uebersetzen und loeschen (PLAN.md Abschnitt 11a).
 *
 * <p>Gearbeitet wird auf den <b>Dateien</b> in {@code messages/}, nicht auf dem geladenen
 * Bundle: Dort haengen auch die Texte der Module mit drin, die aus deren JARs kommen. Wer
 * die mitspeicherte, fror sie in der Datei des Betreibers ein - und ein Modul-Update
 * aenderte seine eigenen Texte nicht mehr.
 *
 * <p>Nach jeder Aenderung wird neu eingelesen und an alle Plugins verteilt, genau wie bei
 * {@code cloud messages reload}. Eine Textkorrektur wirkt damit sofort, ohne Neustart.
 */
final class MessageRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(MessageRoutes.class);
    private static final Gson GSON = new Gson();

    private static final String READ = "cloud.read";
    private static final String WRITE = "cloud.write";

    /** Dasselbe Recht wie {@code cloud messages reload} - kein eigenes Schema. */
    private static final String PERMISSION = "vibecloud.command.cloud.messages";

    /**
     * Erlaubte Schluessel.
     *
     * <p>Die Datei wird flach geschrieben, der Schluessel steht dort unquotiert vor dem
     * Doppelpunkt. Ein Schluessel mit Doppelpunkt, Raute oder Leerzeichen ergaebe YAML,
     * das sich nicht mehr einlesen laesst - und dann stuenden im Spiel ueberall nur noch
     * Schluesselnamen.
     */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

    private final ApiAuth auth;
    private final MessageDistributor messages;
    private final AuditLog audit;

    MessageRoutes(ApiAuth auth, MessageDistributor messages, AuditLog audit) {
        this.auth = auth;
        this.messages = messages;
        this.audit = audit;
    }

    void register(RoutesConfig routes) {
        routes
                .get("/api/v1/messages", this::list)
                .post("/api/v1/messages", this::create)
                .post("/api/v1/messages/reload", this::reload)
                .get("/api/v1/messages/modules/{module}/{locale}", this::moduleEntries)
                .put("/api/v1/messages/modules/{module}/{locale}", this::saveModule)
                // Nach den festen Pfaden: Sonst faengt der Platzhalter "reload" ab.
                .get("/api/v1/messages/{locale}", this::entries)
                .put("/api/v1/messages/{locale}", this::save)
                .delete("/api/v1/messages/{locale}", this::delete);
    }

    /** Welche Sprachen es gibt und wie vollstaendig sie sind. */
    private void list(Context context) {
        if (!auth.require(context, READ, PERMISSION)) {
            return;
        }
        MessageService service = messages.messages();
        try {
            Map<String, String> required = service.entriesOfFile(service.defaultLocale());

            List<Map<String, Object>> locales = new java.util.ArrayList<>();
            for (String locale : service.localeFiles()) {
                Map<String, String> entries = service.entriesOfFile(locale);
                long missing = required.keySet().stream()
                        .filter(key -> !entries.containsKey(key))
                        .count();

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("locale", locale);
                entry.put("default", locale.equals(service.defaultLocale()));
                entry.put("keys", entries.size());
                entry.put("missing", missing);
                locales.add(entry);
            }
            context.json(Map.of(
                    "default", service.defaultLocale(),
                    "locales", locales,
                    "modules", moduleSummaries(service)));
        } catch (IOException exception) {
            LOG.error("Sprachdateien nicht lesbar", exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Sprachdateien nicht lesbar");
        }
    }

    /**
     * Die Module mit eigenen Texten, je Sprache der Cloud.
     *
     * <p>Aufgezaehlt werden die Sprachen der Cloud und nicht die im JAR: Eine Sprache gibt
     * es, wenn es ihre Datei in {@code messages/} gibt - auch fuer ein Modul, das sie
     * selbst nicht mitbringt. Genau die will man dann ja uebersetzen.
     */
    private static List<Map<String, Object>> moduleSummaries(MessageService service)
            throws IOException {
        List<Map<String, Object>> modules = new java.util.ArrayList<>();
        for (String module : service.modulesWithMessages()) {
            Map<String, String> required;
            try {
                required = service.moduleEntries(module, service.defaultLocale());
            } catch (NoSuchElementException exception) {
                // Zwischen Aufzaehlen und Lesen entladen - dann gibt es nichts zu zeigen.
                continue;
            }

            List<Map<String, Object>> locales = new java.util.ArrayList<>();
            for (String locale : service.localeFiles()) {
                if (!MessageService.isValidLocale(locale)) {
                    // Eine Datei wie "de.alt.yml" - als Sprache der Cloud wird sie
                    // geladen, fuer ein Modul gibt es zu ihr aber keinen Pfad.
                    continue;
                }
                Map<String, String> entries = service.moduleEntries(module, locale);

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("locale", locale);
                entry.put("default", locale.equals(service.defaultLocale()));
                entry.put("keys", entries.size());
                entry.put("missing", required.keySet().stream()
                        .filter(key -> !entries.containsKey(key))
                        .count());
                entry.put("overridden", service.moduleOverrides(module, locale).size());
                locales.add(entry);
            }
            modules.add(Map.of("id", module, "locales", locales));
        }
        return modules;
    }

    /**
     * Die Texte eines Moduls in einer Sprache.
     *
     * <p>Drei Saetze nebeneinander: was gilt ({@code entries}), die Standardsprache als
     * Vorlage ({@code defaults}) und was das Modul selbst mitbringt ({@code bundled}).
     * Ohne das Dritte saehe niemand, welcher Text ein eigener ist - und koennte ihn nicht
     * zuruecknehmen.
     */
    private void moduleEntries(Context context) {
        if (!auth.require(context, READ, PERMISSION)) {
            return;
        }
        MessageService service = messages.messages();
        String module = context.pathParam("module");
        String locale = context.pathParam("locale");
        try {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("module", module);
            answer.put("locale", locale);
            answer.put("default", locale.equals(service.defaultLocale()));
            answer.put("entries", service.moduleEntries(module, locale));
            answer.put("defaults", service.moduleEntries(module, service.defaultLocale()));
            answer.put("bundled", service.moduleBundled(module, locale));
            context.json(answer);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (NoSuchElementException exception) {
            fail(context, HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IOException exception) {
            LOG.error("Texte von Modul {} ({}) nicht lesbar", module, locale, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht lesbar");
        }
    }

    /** Die Texte eines Moduls ersetzen - gespeichert wird nur, was vom JAR abweicht. */
    private void saveModule(Context context) {
        if (!auth.require(context, WRITE, PERMISSION)) {
            return;
        }
        String module = context.pathParam("module");
        String locale = context.pathParam("locale");

        Map<String, String> entries = readEntries(context);
        if (entries == null) {
            return;
        }

        int own;
        try {
            own = messages.messages().saveModuleEntries(module, locale, entries);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        } catch (NoSuchElementException exception) {
            fail(context, HttpStatus.NOT_FOUND, exception.getMessage());
            return;
        } catch (IllegalStateException exception) {
            fail(context, HttpStatus.CONFLICT, exception.getMessage());
            return;
        } catch (IOException exception) {
            LOG.error("Texte von Modul {} ({}) nicht schreibbar", module, locale, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht schreibbar");
            return;
        }
        audit.record(actor(context), "messages.module_saved", module,
                Map.of("locale", locale, "own", own));
        context.json(answerAfterChange(locale, own == 0
                ? "Gespeichert - es gelten wieder alle Texte des Moduls."
                : own + (own == 1 ? " eigener Text" : " eigene Texte") + " gespeichert."));
    }

    /**
     * Liest {@code entries} aus dem Rumpf und prueft jeden Eintrag.
     *
     * @return {@code null}, wenn die Antwort schon geschrieben wurde
     */
    private static Map<String, String> readEntries(Context context) {
        Map<?, ?> body;
        try {
            body = GSON.fromJson(context.body(), Map.class);
        } catch (RuntimeException exception) {
            fail(context, HttpStatus.BAD_REQUEST, "Rumpf ist kein JSON");
            return null;
        }
        Object raw = body == null ? null : body.get("entries");

        if (!(raw instanceof Map<?, ?> given)) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'entries' ist Pflicht");
            return null;
        }

        Map<String, String> entries = new TreeMap<>();
        for (Map.Entry<?, ?> entry : given.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!VALID_KEY.matcher(key).matches()) {
                fail(context, HttpStatus.BAD_REQUEST, "Kein gueltiger Schluessel: " + key);
                return null;
            }
            if (!(entry.getValue() instanceof String text)) {
                fail(context, HttpStatus.BAD_REQUEST, "Der Wert von " + key + " ist kein Text");
                return null;
            }
            // Ein echter Zeilenumbruch wuerde die Datei zerreissen. MiniMessage hat dafuer
            // ein Tag, und genau das steht auch in der Standardsprache.
            if (text.contains("\n") || text.contains("\r")) {
                fail(context, HttpStatus.BAD_REQUEST,
                        "Zeilenumbruch in " + key + " - bitte <newline> schreiben");
                return null;
            }
            entries.put(key, text);
        }
        return entries;
    }

    /**
     * Die Eintraege einer Sprache - und daneben die der Standardsprache.
     *
     * <p>Beides zusammen, weil man beim Uebersetzen den Ausgangstext sehen muss. Fehlende
     * Schluessel erkennt die Oberflaeche daran, dass sie links stehen und rechts nicht.
     */
    private void entries(Context context) {
        if (!auth.require(context, READ, PERMISSION)) {
            return;
        }
        MessageService service = messages.messages();
        String locale = context.pathParam("locale");
        try {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("locale", locale);
            answer.put("default", locale.equals(service.defaultLocale()));
            answer.put("entries", service.entriesOfFile(locale));
            answer.put("defaults", service.entriesOfFile(service.defaultLocale()));
            context.json(answer);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (NoSuchElementException exception) {
            fail(context, HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IOException exception) {
            LOG.error("Sprachdatei {} nicht lesbar", locale, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht lesbar");
        }
    }

    /** Neue Sprache - mit den Texten der Standardsprache als Vorlage. */
    private void create(Context context) {
        if (!auth.require(context, WRITE, PERMISSION)) {
            return;
        }
        Map<?, ?> body = GSON.fromJson(context.body(), Map.class);
        Object value = body == null ? null : body.get("locale");
        String locale = value instanceof String text ? text.trim() : null;

        if (locale == null || locale.isEmpty()) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'locale' ist Pflicht");
            return;
        }
        try {
            messages.messages().createLocale(locale);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        } catch (IllegalStateException exception) {
            fail(context, HttpStatus.CONFLICT, exception.getMessage());
            return;
        } catch (IOException exception) {
            LOG.error("Sprache {} konnte nicht angelegt werden", locale, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht schreibbar");
            return;
        }
        audit.record(actor(context), "messages.locale_created", locale, null);
        context.json(answerAfterChange(locale,
                "Sprache " + locale + " angelegt - mit den Texten der Standardsprache."));
    }

    /** Alle Eintraege einer Sprache ersetzen. */
    private void save(Context context) {
        if (!auth.require(context, WRITE, PERMISSION)) {
            return;
        }
        String locale = context.pathParam("locale");
        Map<String, String> entries = readEntries(context);
        if (entries == null) {
            return;
        }

        try {
            messages.messages().saveFile(locale, entries);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        } catch (IOException exception) {
            LOG.error("Sprachdatei {} nicht schreibbar", locale, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht schreibbar");
            return;
        }
        audit.record(actor(context), "messages.saved", locale, Map.of("keys", entries.size()));
        context.json(answerAfterChange(locale, entries.size() + " Texte gespeichert."));
    }

    private void delete(Context context) {
        if (!auth.require(context, WRITE, PERMISSION)) {
            return;
        }
        String locale = context.pathParam("locale");
        try {
            messages.messages().deleteLocale(locale);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        } catch (NoSuchElementException exception) {
            fail(context, HttpStatus.NOT_FOUND, exception.getMessage());
            return;
        } catch (IOException exception) {
            LOG.error("Sprachdatei {} nicht loeschbar", locale, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht loeschbar");
            return;
        }
        audit.record(actor(context), "messages.locale_deleted", locale, null);
        context.json(answerAfterChange(locale, "Sprache " + locale + " geloescht."));
    }

    /** Fuer von Hand geaenderte Dateien - dasselbe wie {@code cloud messages reload}. */
    private void reload(Context context) {
        if (!auth.require(context, WRITE, PERMISSION)) {
            return;
        }
        audit.record(actor(context), "messages.reloaded", null, null);
        context.json(answerAfterChange(null, "Neu eingelesen."));
    }

    /**
     * Nach jeder Aenderung neu einlesen und verteilen.
     *
     * <p>Schlaegt das Einlesen fehl, bleiben die alten Texte aktiv - das sagt die Antwort
     * auch. Die Datei ist dann zwar geschrieben, aber im Spiel gilt noch der alte Stand;
     * alles andere waere ein Netzwerk voller Schluesselnamen.
     */
    private Map<String, Object> answerAfterChange(String locale, String note) {
        MessageDistributor.Result result = messages.reload();

        Map<String, Object> answer = new LinkedHashMap<>();
        if (locale != null) {
            answer.put("locale", locale);
        }
        answer.put("note", result.reloaded()
                ? note + " An " + result.plugins() + " Server verteilt."
                : note + " ACHTUNG: Neu einlesen ist fehlgeschlagen, es gelten die alten "
                  + "Texte. Details stehen im Log des Masters.");
        answer.put("reloaded", result.reloaded());
        answer.put("plugins", result.plugins());
        return answer;
    }

    private static void fail(Context context, HttpStatus status, String message) {
        ApiAuth.fail(context, status, message);
    }

    private static String actor(Context context) {
        return ApiAuth.of(context).map(ApiAuth.Principal::name).orElse("API");
    }
}
