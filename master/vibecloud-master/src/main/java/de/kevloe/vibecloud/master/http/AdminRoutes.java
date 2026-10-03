package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.module.ModuleManager;
import de.kevloe.vibecloud.master.node.NodeRepository;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.settings.MaintenanceSwitch;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Anlegen und Aendern ueber HTTP (PLAN.md Abschnitt 12).
 *
 * <p>Dieselben Dienste wie Konsole und Befehle, dieselben Rechte-Knoten. Hier wird nichts
 * nachgebaut - ein zweiter Weg mit eigener Logik waere der sichere Weg zu zwei
 * verschiedenen Verhaltensweisen.
 *
 * <p>Getrennt von {@link CloudRoutes}, weil Lesen und Schreiben verschiedene Rechte und
 * verschiedene Sorgfalt brauchen: Ein Tippfehler beim Lesen zeigt nichts an, beim
 * Schreiben loescht er eine Gruppe.
 */
final class AdminRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(AdminRoutes.class);
    private static final Gson GSON = new Gson();

    private static final String READ = "cloud.read";
    private static final String WRITE = "cloud.write";

    private final ApiAuth auth;
    private final ServerGroupRepository groups;
    private final ServerRegistry servers;
    private final NodeRepository nodes;
    private final RankRepository ranks;
    private final PermissionService permissions;
    private final ModuleManager modules;
    private final AuditLog audit;
    private final Path modulesDirectory;
    private final MaintenanceSwitch maintenance;
    private final de.kevloe.vibecloud.master.template.VersionCatalog versions;
    private final de.kevloe.vibecloud.master.template.JarStore jars;
    private final de.kevloe.vibecloud.master.node.NodeRegistry liveNodes;

    AdminRoutes(ApiAuth auth, ServerGroupRepository groups, ServerRegistry servers,
                NodeRepository nodes, RankRepository ranks, PermissionService permissions,
                ModuleManager modules, AuditLog audit, Path modulesDirectory,
                MaintenanceSwitch maintenance,
                de.kevloe.vibecloud.master.template.VersionCatalog versions,
                de.kevloe.vibecloud.master.template.JarStore jars,
                de.kevloe.vibecloud.master.node.NodeRegistry liveNodes) {
        this.maintenance = maintenance;
        this.versions = versions;
        this.jars = jars;
        this.liveNodes = liveNodes;
        this.auth = auth;
        this.groups = groups;
        this.servers = servers;
        this.nodes = nodes;
        this.ranks = ranks;
        this.permissions = permissions;
        this.modules = modules;
        this.audit = audit;
        this.modulesDirectory = modulesDirectory;
    }

    void register(RoutesConfig routes) {
        routes
                // Gruppen
                .get("/api/v1/groups/fields", this::groupFields)
                .get("/api/v1/groups/versions", this::groupVersions)
                .post("/api/v1/groups", this::createGroup)
                .patch("/api/v1/groups/{name}", this::editGroup)
                .delete("/api/v1/groups/{name}", this::deleteGroup)
                // Nodes
                .post("/api/v1/nodes", this::createNode)
                .patch("/api/v1/nodes/{name}", this::editNode)
                .post("/api/v1/nodes/{name}/token", this::rotateNodeToken)
                .delete("/api/v1/nodes/{name}", this::deleteNode)
                // Raenge
                .get("/api/v1/ranks/fields", this::rankFields)
                .post("/api/v1/ranks", this::createRank)
                .patch("/api/v1/ranks/{id}", this::editRank)
                .delete("/api/v1/ranks/{id}", this::deleteRank)
                .post("/api/v1/ranks/{id}/inherit", this::addInheritance)
                .delete("/api/v1/ranks/{id}/inherit/{parent}", this::removeInheritance)
                // Rang eines Spielers
                .put("/api/v1/players/{uuid}/rank", this::setPlayerRank)
                .delete("/api/v1/players/{uuid}/rank", this::resetPlayerRank)
                // Modul-Konfiguration
                .get("/api/v1/modules/{id}/config", this::moduleConfig)
                .put("/api/v1/modules/{id}/config", this::saveModuleConfig);
    }

    // ---------------------------------------------------------------- Gruppen

    /** Welche Felder sich aendern lassen - damit die Oberflaeche das Formular bauen kann. */
    private void groupFields(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.group.edit")) {
            return;
        }
        context.json(ServerGroupRepository.editableFields().stream().map(field -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", field);
            entry.put("values", ServerGroupRepository.valuesFor(field));
            return entry;
        }).toList());
    }

    /**
     * Die waehlbaren Versionen einer Plattform - dasselbe wie {@code group versions}, mit
     * dessen Recht.
     *
     * <p>Die Java der verbundenen Nodes kommt mit: Die Oberflaeche soll schon beim Waehlen
     * sagen, dass 1.16.5 auf keinem Node starten kann, nicht erst beim ersten Start.
     */
    private void groupVersions(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.group.versions")) {
            return;
        }
        ServerPlatformType platform;
        try {
            platform = ServerPlatformType.valueOf(
                    Optional.ofNullable(context.queryParam("platform")).orElse("PAPER")
                            .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST,
                    "platform muss PAPER, VELOCITY oder MINESTOM sein");
            return;
        }
        String jarSource = platform == ServerPlatformType.VELOCITY ? "velocity" : "paper";
        var available = versions.versions(platform);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("platform", platform.name());
        // Bei MINESTOM gibt es nichts zu waehlen - das Jar liegt im Template.
        answer.put("selectable", platform != ServerPlatformType.MINESTOM);
        answer.put("reachable", platform == ServerPlatformType.MINESTOM || !available.isEmpty());
        answer.put("latestJava",
                de.kevloe.vibecloud.master.template.VersionCatalog.MODERN_PLUGIN_JAVA);
        answer.put("versions", available.stream().map(version -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", version.id());
            entry.put("java", version.java());
            entry.put("legacy", version.legacy());
            entry.put("supported", version.supported());
            entry.put("downloaded", jars.isDownloaded(jarSource, version.id()));
            return entry;
        }).toList());
        answer.put("nodes", liveNodes.listAll().stream()
                .filter(node -> node.connected())
                .map(node -> Map.of("name", node.name(),
                        "java", liveNodes.javaVersionsOf(node.name())))
                .toList());
        context.json(answer);
    }

    private void createGroup(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.group.create")) {
            return;
        }
        Map<?, ?> body = body(context);
        String name = text(body, "name");
        String platform = text(body, "platform");

        if (name == null || platform == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Felder 'name' und 'platform' sind Pflicht");
            return;
        }
        if (groups.find(name).isPresent()) {
            fail(context, HttpStatus.CONFLICT, "Die Gruppe " + name + " gibt es schon");
            return;
        }
        ServerPlatformType type;
        try {
            type = ServerPlatformType.valueOf(platform.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST,
                    "Plattform muss PAPER, VELOCITY oder MINESTOM sein");
            return;
        }

        String template = Optional.ofNullable(text(body, "template")).orElse(name);
        ServerGroup group = ServerGroup.defaults(name, type, template);
        String version = text(body, "version");
        if (version != null && !version.isBlank()) {
            group = group.withMcVersion(version.trim());
        }
        try {
            // Prueft auch die Version - dieselbe Stelle wie group create in der Konsole.
            groups.create(group);
        } catch (RuntimeException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        }
        audit.record(actor(context), "group.created", name,
                Map.of("platform", type.name(), "template", template,
                        "version", group.mcVersion()));
        LOG.info("Gruppe {} ueber die Schnittstelle angelegt ({})", name, type);

        context.status(HttpStatus.CREATED).json(Map.of("name", name));
    }

    private void editGroup(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.group.edit")) {
            return;
        }
        String name = context.pathParam("name");
        Map<?, ?> body = body(context);
        String field = text(body, "field");
        String value = text(body, "value");

        if (field == null || value == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Felder 'field' und 'value' sind Pflicht");
            return;
        }
        try {
            // Die Wartung ist mehr als ein Feld: Die Proxys muessen davon erfahren. Ohne
            // diesen Umweg stuende der Wert in der Datenbank, und weiter wuerden Spieler
            // dorthin geschickt.
            boolean found = field.equalsIgnoreCase("maintenance")
                    ? maintenance.setGroup(name, parseBoolean(value), actor(context)) >= 0
                    : groups.updateField(name, field, value);
            if (!found) {
                fail(context, HttpStatus.NOT_FOUND, "Unbekannte Gruppe: " + name);
                return;
            }
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        }
        audit.record(actor(context), "group.edited", name,
                Map.of("field", field, "value", value));
        // Wie in der Konsole: Die Aenderung wirkt erst auf neu gestartete Server - nur die
        // Wartung nicht, die gilt sofort.
        context.json(Map.of("name", name, "field", field, "value", value,
                "note", field.equalsIgnoreCase("maintenance")
                        ? "Wirkt sofort"
                        : "Wirkt auf neu gestartete Server"));
    }

    private void deleteGroup(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.group.delete")) {
            return;
        }
        String name = context.pathParam("name");

        if (!servers.activeOfGroup(name).isEmpty()) {
            // Sonst liefen Server weiter, zu denen es keine Gruppe mehr gibt - der
            // Scheduler wuesste nicht, was er mit ihnen tun soll.
            fail(context, HttpStatus.CONFLICT,
                    "Es laufen noch Server dieser Gruppe - erst stoppen");
            return;
        }
        if (!groups.delete(name)) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekannte Gruppe: " + name);
            return;
        }
        audit.record(actor(context), "group.deleted", name, null);
        context.json(Map.of("deleted", name));
    }

    // ---------------------------------------------------------------- Nodes

    private void createNode(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.node.add")) {
            return;
        }
        Map<?, ?> body = body(context);
        String name = text(body, "name");
        if (name == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'name' ist Pflicht");
            return;
        }
        if (nodes.find(name).isPresent()) {
            fail(context, HttpStatus.CONFLICT, "Den Node " + name + " gibt es schon");
            return;
        }
        long memory = number(body, "maxMemoryMb", 8192);
        List<String> allowedIps = strings(body, "allowedIps");

        String token = nodes.create(name, memory, allowedIps);
        audit.record(actor(context), "node.created", name,
                Map.of("maxMemoryMb", String.valueOf(memory)));
        LOG.info("Node {} ueber die Schnittstelle angelegt", name);

        // Das Token steht nur hier - gespeichert ist allein sein Hash.
        context.status(HttpStatus.CREATED).json(Map.of(
                "name", name,
                "token", token,
                "note", "Das Token wird nur jetzt angezeigt"));
    }

    private void editNode(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.node.enable")) {
            return;
        }
        String name = context.pathParam("name");
        if (nodes.find(name).isEmpty()) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekannter Node: " + name);
            return;
        }
        Map<?, ?> body = body(context);
        Object enabled = body == null ? null : body.get("enabled");

        if (!(enabled instanceof Boolean value)) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'enabled' muss true oder false sein");
            return;
        }
        nodes.setEnabled(name, value);
        audit.record(actor(context), value ? "node.enabled" : "node.disabled", name, null);
        context.json(Map.of("name", name, "enabled", value));
    }

    private void rotateNodeToken(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.node.token")) {
            return;
        }
        String name = context.pathParam("name");
        if (nodes.find(name).isEmpty()) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekannter Node: " + name);
            return;
        }
        String token = nodes.rotateToken(name);
        audit.record(actor(context), "node.token_rotated", name, null);

        context.json(Map.of(
                "name", name,
                "token", token,
                "note", "Das alte Token gilt nicht mehr - wrapper.json anpassen und "
                        + "den Wrapper neu starten"));
    }

    private void deleteNode(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.node.remove")) {
            return;
        }
        String name = context.pathParam("name");

        if (!servers.onNode(name).isEmpty()) {
            fail(context, HttpStatus.CONFLICT,
                    "Auf diesem Node laufen noch Server - erst stoppen");
            return;
        }
        if (!nodes.delete(name)) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekannter Node: " + name);
            return;
        }
        audit.record(actor(context), "node.deleted", name, null);
        context.json(Map.of("deleted", name));
    }

    // ---------------------------------------------------------------- Raenge

    private void rankFields(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.edit")) {
            return;
        }
        context.json(RankRepository.editableFields());
    }

    private void createRank(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.create")) {
            return;
        }
        Map<?, ?> body = body(context);
        String id = text(body, "id");
        if (id == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'id' ist Pflicht");
            return;
        }
        if (ranks.find(id).isPresent()) {
            fail(context, HttpStatus.CONFLICT, "Den Rang " + id + " gibt es schon");
            return;
        }
        int weight = (int) number(body, "weight", 0);
        String displayName = Optional.ofNullable(text(body, "displayName")).orElse(id);

        try {
            ranks.create(new RankRepository.Rank(id, id, displayName, "", "", "<gray>",
                    weight, false, null));
        } catch (RuntimeException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        }
        audit.record(actor(context), "rank.created", id,
                Map.of("weight", String.valueOf(weight)));
        context.status(HttpStatus.CREATED).json(Map.of("id", id));
    }

    private void editRank(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.edit")) {
            return;
        }
        String id = context.pathParam("id");
        Map<?, ?> body = body(context);
        String field = text(body, "field");
        String value = text(body, "value");

        if (field == null || value == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Felder 'field' und 'value' sind Pflicht");
            return;
        }
        try {
            if (!ranks.updateField(id, field, value)) {
                fail(context, HttpStatus.NOT_FOUND, "Unbekannter Rang: " + id);
                return;
            }
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        }
        // Rechte und Anzeige haengen daran - ohne das Verwerfen saehe ein Spieler seinen
        // alten Prefix, bis er sich neu verbindet.
        permissions.invalidateRank(id);

        audit.record(actor(context), "rank.edited", id,
                Map.of("field", field, "value", value));
        context.json(Map.of("id", id, "field", field, "value", value));
    }

    private void deleteRank(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.delete")) {
            return;
        }
        String id = context.pathParam("id");
        try {
            if (!ranks.delete(id)) {
                fail(context, HttpStatus.NOT_FOUND, "Unbekannter Rang: " + id);
                return;
            }
        } catch (RuntimeException exception) {
            // Der Standardrang und Raenge mit Spielern lassen sich nicht loeschen - die
            // Datenbank sagt, warum.
            fail(context, HttpStatus.CONFLICT, exception.getMessage());
            return;
        }
        permissions.invalidateAll();
        audit.record(actor(context), "rank.deleted", id, null);
        context.json(Map.of("deleted", id));
    }

    private void addInheritance(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.inherit")) {
            return;
        }
        String id = context.pathParam("id");
        String parent = text(body(context), "parent");

        if (parent == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'parent' ist Pflicht");
            return;
        }
        try {
            permissions.addInheritance(id, parent, actor(context));
        } catch (RuntimeException exception) {
            // Zyklen werden beim Anlegen abgelehnt, nicht zur Laufzeit entdeckt.
            fail(context, HttpStatus.CONFLICT, exception.getMessage());
            return;
        }
        context.json(Map.of("id", id, "inherits", parent));
    }

    private void removeInheritance(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.inherit")) {
            return;
        }
        String id = context.pathParam("id");
        String parent = context.pathParam("parent");

        if (!permissions.removeInheritance(id, parent, actor(context))) {
            fail(context, HttpStatus.NOT_FOUND, id + " erbt nicht von " + parent);
            return;
        }
        context.json(Map.of("id", id, "removed", parent));
    }

    // ---------------------------------------------------------------- Rang eines Spielers

    /**
     * {@code rank set <spieler> <rang> [dauer]} ueber HTTP.
     *
     * <p>Derselbe Aufruf wie in der Konsole - {@link PermissionService#setRank} schreibt
     * den Verlauf, verwirft die Rechte und loest das Event aus, an dem auch der
     * Dashboard-Zugang haengt. Wer sich hier selbst einen Rang ohne
     * {@code vibecloud.dashboard.login} gibt, ist deshalb danach abgemeldet.
     */
    private void setPlayerRank(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.set")) {
            return;
        }
        Optional<UUID> uuid = uuid(context);
        if (uuid.isEmpty()) {
            return;
        }
        Map<?, ?> body = body(context);
        String rank = text(body, "rank");
        if (rank == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'rank' ist Pflicht");
            return;
        }
        rank = rank.trim().toLowerCase(Locale.ROOT);
        if (ranks.find(rank).isEmpty()) {
            fail(context, HttpStatus.BAD_REQUEST, "Rang " + rank + " existiert nicht");
            return;
        }

        // Dieselbe Schreibweise wie ueberall: 30d, 12h, 90m. Leer heisst dauerhaft.
        String given = text(body, "duration");
        Duration duration = null;
        if (given != null) {
            duration = Times.parseDuration(given.trim());
            if (duration == null) {
                fail(context, HttpStatus.BAD_REQUEST,
                        "Dauer nicht verstanden: " + given + " (erlaubt: 30d, 12h, 90m)");
                return;
            }
        }

        try {
            permissions.setRank(uuid.get(), rank, duration, actor(context), null);
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.NOT_FOUND, exception.getMessage());
            return;
        }
        // Protokolliert wird im Dienst - sonst stuende jede Aenderung zweimal im Verlauf.
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("uuid", uuid.get().toString());
        answer.put("rank", rank);
        answer.put("duration", given == null ? "" : given.trim());
        answer.put("note", "Wirkt sofort - die Plugins laden die Rechte neu, ohne Relog");
        context.json(answer);
    }

    /** {@code rank reset <spieler>} - zurueck auf den Default-Rang. */
    private void resetPlayerRank(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.rank.reset")) {
            return;
        }
        Optional<UUID> uuid = uuid(context);
        if (uuid.isEmpty()) {
            return;
        }
        try {
            permissions.resetRank(uuid.get(), actor(context));
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.NOT_FOUND, exception.getMessage());
            return;
        } catch (IllegalStateException exception) {
            // Kein Default-Rang - dann gibt es nichts, worauf zurueckgesetzt werden koennte.
            fail(context, HttpStatus.CONFLICT, exception.getMessage());
            return;
        }
        context.json(Map.of("uuid", uuid.get().toString(), "reset", true));
    }

    // ---------------------------------------------------------------- Modul-Konfiguration

    /**
     * Die {@code config.json} eines Moduls.
     *
     * <p>Als Text und nicht als geparstes Objekt: Die Oberflaeche zeigt sie in einem
     * Editor, und Kommentare oder Formatierung sollen dabei nicht verloren gehen.
     */
    private void moduleConfig(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.module.info")) {
            return;
        }
        String id = context.pathParam("id");
        if (modules.descriptorOf(id).isEmpty()) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekanntes Modul: " + id);
            return;
        }
        Path file = configFile(id);
        try {
            String content = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    // Noch nie gespeichert: Das Modul legt sie erst an, wenn es seine
                    // Einstellungen zum ersten Mal liest.
                    : "{}";
            context.json(Map.of("id", id, "content", content, "exists", Files.exists(file)));
        } catch (IOException exception) {
            LOG.error("Konfiguration von {} nicht lesbar", id, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht lesbar");
        }
    }

    private void saveModuleConfig(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.module.reload")) {
            return;
        }
        String id = context.pathParam("id");
        if (modules.descriptorOf(id).isEmpty()) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekanntes Modul: " + id);
            return;
        }
        String content = text(body(context), "content");
        if (content == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'content' ist Pflicht");
            return;
        }
        // Erst pruefen, dann schreiben: Eine kaputte Datei faellt sonst erst beim
        // naechsten Start des Moduls auf - und dann startet es nicht mehr.
        try {
            GSON.fromJson(content, Map.class);
        } catch (JsonSyntaxException exception) {
            fail(context, HttpStatus.BAD_REQUEST, "Das ist kein gueltiges JSON");
            return;
        }

        try {
            Path file = configFile(id);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOG.error("Konfiguration von {} nicht schreibbar", id, exception);
            fail(context, HttpStatus.INTERNAL_SERVER_ERROR, "Datei nicht schreibbar");
            return;
        }
        audit.record(actor(context), "module.config_changed", id, null);

        context.json(Map.of("id", id,
                "note", "Gespeichert. Wirksam wird es mit 'module reload " + id + "'"));
    }

    private Path configFile(String id) {
        return modulesDirectory.resolve(id).resolve("config.json");
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static void fail(Context context, HttpStatus status, String message) {
        ApiAuth.fail(context, status, message);
    }

    private static String actor(Context context) {
        return ApiAuth.of(context).map(ApiAuth.Principal::name).orElse("API");
    }

    /** Nur {@code true} und {@code false} - "ja" waere sonst stillschweigend false. */
    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "maintenance muss true oder false sein, nicht: " + value);
        };
    }

    /** Liest die UUID aus dem Pfad und antwortet selbst, wenn sie nicht stimmt. */
    private static Optional<UUID> uuid(Context context) {
        try {
            return Optional.of(UUID.fromString(context.pathParam("uuid")));
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, "Keine gueltige UUID");
            return Optional.empty();
        }
    }

    private static Map<?, ?> body(Context context) {
        try {
            return GSON.fromJson(context.body(), Map.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String text(Map<?, ?> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String found && !found.isBlank() ? found : null;
    }

    private static long number(Map<?, ?> body, String key, long fallback) {
        if (body == null) {
            return fallback;
        }
        Object value = body.get(key);
        return value instanceof Number found ? found.longValue() : fallback;
    }

    private static List<String> strings(Map<?, ?> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(entry -> !entry.isBlank()).toList();
    }
}
