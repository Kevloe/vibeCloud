package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.config.MasterConfigFile;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.settings.MaintenanceSwitch;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die allgemeinen Einstellungen der Cloud: Wartungsmodus und {@code config.json}.
 *
 * <p>Zwei Dinge mit zwei Rechten, deshalb zwei Paare von Endpunkten statt eines
 * gemeinsamen: Den Wartungsmodus schaltet ein Moderator, die Ports des Netzwerks nicht.
 * Beides haengt an den Rechten der Befehle, die dasselbe tun - {@code maintenance} und
 * {@code cloud config}.
 */
final class SettingsRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsRoutes.class);
    private static final Gson GSON = new Gson();

    private static final String READ = "cloud.read";
    private static final String WRITE = "cloud.write";

    /** Lesen und Schreiben: Es gibt nur {@code cloud config}, keinen lesenden Unterbefehl. */
    private static final String CONFIG = "vibecloud.command.cloud.config";

    /** Das Recht haengt an der Richtung, wie in der Konsole. */
    private static final String ON = "vibecloud.command.maintenance.on";
    private static final String OFF = "vibecloud.command.maintenance.off";

    private final ApiAuth auth;
    private final MaintenanceSwitch maintenance;
    private final MasterConfigFile config;
    private final ServerGroupRepository groups;
    private final AuditLog audit;

    SettingsRoutes(ApiAuth auth, MaintenanceSwitch maintenance, MasterConfigFile config,
                   ServerGroupRepository groups, AuditLog audit) {
        this.auth = auth;
        this.maintenance = maintenance;
        this.config = config;
        this.groups = groups;
        this.audit = audit;
    }

    void register(RoutesConfig routes) {
        routes
                .get("/api/v1/settings/maintenance", this::maintenance)
                .put("/api/v1/settings/maintenance", this::setMaintenance)
                .put("/api/v1/settings/maintenance/groups/{name}", this::setGroupMaintenance)
                .get("/api/v1/settings/config", this::config)
                .patch("/api/v1/settings/config", this::saveConfig);
    }

    // ---------------------------------------------------------------- Wartungsmodus

    /** {@code maintenance list}: global und welche Gruppen. */
    private void maintenance(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.maintenance.list")) {
            return;
        }
        context.json(Map.of(
                "active", maintenance.isActive(),
                "groups", groups.findAll().stream()
                        .filter(ServerGroup::maintenance)
                        .map(ServerGroup::name)
                        .sorted()
                        .toList(),
                // Damit die Oberflaeche keinen Schalter anbietet, der dann abgelehnt wird.
                // Geprueft wird beim Schalten trotzdem noch einmal.
                "mayEnable", auth.allows(context, WRITE, ON),
                "mayDisable", auth.allows(context, WRITE, OFF)));
    }

    /**
     * {@code maintenance on|off <gruppe>}.
     *
     * <p>Ein eigener Endpunkt und nicht das Feld {@code maintenance} im Gruppen-Formular:
     * Dort braucht es {@code group.edit}, hier das Recht fuer die Wartung - und wer ein
     * Minigame kurz sperren darf, soll dafuer nicht die ganze Gruppe umbauen duerfen.
     */
    private void setGroupMaintenance(Context context) {
        Object value = body(context).get("active");
        if (!(value instanceof Boolean active)) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST,
                    "Feld 'active' muss true oder false sein");
            return;
        }
        if (!auth.require(context, WRITE, active ? ON : OFF)) {
            return;
        }
        String name = context.pathParam("name");
        int reached = maintenance.setGroup(name, active, actor(context));
        if (reached < 0) {
            ApiAuth.fail(context, HttpStatus.NOT_FOUND, "Unbekannte Gruppe: " + name);
            return;
        }
        context.json(Map.of(
                "group", name,
                "active", active,
                "plugins", reached,
                "note", active
                        ? "Gruppe " + name + " ist in Wartung. Keine neuen Verbindungen "
                          + "dorthin; laufende Server bleiben an."
                        : "Wartung fuer " + name + " aufgehoben."));
    }

    /**
     * {@code maintenance on|off} fuer das ganze Netzwerk.
     *
     * <p>Das Recht haengt an der Richtung, wie in der Konsole: Wer nur
     * {@code maintenance.off} hat, darf aufheben, aber nicht einschalten.
     */
    private void setMaintenance(Context context) {
        Object value = body(context).get("active");
        if (!(value instanceof Boolean active)) {
            // Vor der Rechtepruefung, weil das Recht vom Wert abhaengt - verraten wird
            // dabei nichts, was nicht in der Beschreibung der Schnittstelle stuende.
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST,
                    "Feld 'active' muss true oder false sein");
            return;
        }
        if (!auth.require(context, WRITE, active ? ON : OFF)) {
            return;
        }
        int reached = maintenance.set(active, actor(context));
        context.json(Map.of(
                "active", active,
                "plugins", reached,
                "note", active
                        ? "Wartungsmodus aktiv. Neue Logins werden abgelehnt; wer online "
                          + "ist, bleibt online."
                        : "Wartungsmodus aufgehoben."));
    }

    // ---------------------------------------------------------------- config.json

    /**
     * Die aenderbaren Felder mit dem Wert aus der Datei und dem, womit der Master laeuft.
     *
     * <p>Beides, weil eine Aenderung erst nach dem Neustart gilt: Ohne den laufenden Wert
     * saehe die Seite nach dem Speichern aus, als waere alles schon wirksam.
     */
    private void config(Context context) {
        if (!auth.require(context, READ, CONFIG)) {
            return;
        }
        MasterConfig saved;
        try {
            saved = config.read();
        } catch (IOException exception) {
            LOG.error("config.json nicht lesbar", exception);
            ApiAuth.fail(context, HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
            return;
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        for (String name : MasterConfigFile.editableFields()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("value", MasterConfigFile.valueOf(saved, name));
            entry.put("running", config.runningValue(name));
            entry.put("range", MasterConfigFile.rangeOf(name));
            fields.add(entry);
        }
        context.json(Map.of("fields", fields, "locked", config.lockedFields()));
    }

    /** Mehrere Felder auf einmal - sie haengen voneinander ab, siehe {@link MasterConfigFile}. */
    private void saveConfig(Context context) {
        if (!auth.require(context, WRITE, CONFIG)) {
            return;
        }
        if (!(body(context).get("values") instanceof Map<?, ?> given) || given.isEmpty()) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Feld 'values' ist Pflicht");
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : given.entrySet()) {
            Object value = entry.getValue();
            // Gson liest jede Zahl als double - "30.0" waere dann keine ganze Zahl mehr.
            values.put(String.valueOf(entry.getKey()),
                    value instanceof Double number && number == Math.rint(number)
                            ? String.valueOf(number.longValue())
                            : String.valueOf(value));
        }

        try {
            config.update(values);
        } catch (IllegalArgumentException exception) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        } catch (IOException exception) {
            LOG.error("config.json nicht schreibbar", exception);
            ApiAuth.fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht schreibbar");
            return;
        }
        audit.record(actor(context), "config.changed", null, values);
        LOG.info("{} hat die config.json geaendert: {}", actor(context), values);

        context.json(Map.of(
                "saved", values.size(),
                "note", "Gespeichert. Wirksam nach einem Neustart des Masters."));
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static Map<?, ?> body(Context context) {
        try {
            Map<?, ?> body = GSON.fromJson(context.body(), Map.class);
            return body == null ? Map.of() : body;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private static String actor(Context context) {
        return ApiAuth.of(context).map(ApiAuth.Principal::name).orElse("API");
    }
}
