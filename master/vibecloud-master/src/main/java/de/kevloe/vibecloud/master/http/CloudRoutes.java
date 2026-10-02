package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.module.ModuleManager;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.node.NodeRepository;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.OnlinePlayers;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.server.PlayerTransferService;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.server.ServerService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Die Cloud ueber HTTP (PLAN.md Abschnitt 12).
 *
 * <p>Jeder Endpunkt nennt zwei Dinge: den Rechte-Bereich fuer ein API-Token und den
 * Rechte-Knoten fuer einen Menschen. Der Knoten ist derselbe wie beim entsprechenden
 * Befehl - wer {@code server stop} im Spiel nicht darf, darf es hier auch nicht.
 *
 * <p>Geschrieben wird nicht hier: Die Endpunkte rufen dieselben Dienste wie Konsole und
 * Befehle. Doppelte Logik waere der sichere Weg zu zwei verschiedenen Verhaltensweisen.
 */
final class CloudRoutes {

    private static final Gson GSON = new Gson();

    private static final String READ = "cloud.read";
    private static final String WRITE = "cloud.write";

    private static final String CONSOLE_SEND = HttpApi.CONSOLE_SEND_PERMISSION;

    private static final int MAX_COMMAND_LENGTH = 1000;

    private final ApiAuth auth;
    private final ServerRegistry servers;
    private final ServerService serverService;
    private final ServerGroupRepository groups;
    private final NodeRepository nodeRepository;
    private final NodeRegistry nodes;
    private final PlayerService players;
    private final PermissionService permissions;
    private final RankRepository ranks;
    private final PlayerTransferService transfers;
    private final ModuleManager modules;
    private final OnlinePlayers online;

    CloudRoutes(ApiAuth auth, ServerRegistry servers, ServerService serverService,
                ServerGroupRepository groups, NodeRepository nodeRepository,
                NodeRegistry nodes, PlayerService players, PermissionService permissions,
                RankRepository ranks, PlayerTransferService transfers,
                ModuleManager modules, OnlinePlayers online) {
        this.auth = auth;
        this.servers = servers;
        this.serverService = serverService;
        this.groups = groups;
        this.nodeRepository = nodeRepository;
        this.nodes = nodes;
        this.players = players;
        this.permissions = permissions;
        this.ranks = ranks;
        this.transfers = transfers;
        this.modules = modules;
        this.online = online;
    }

    void register(RoutesConfig routes) {
        routes.get("/api/v1/servers", this::listServers)
                .post("/api/v1/servers/{name}/stop", this::stopServer)
                .post("/api/v1/servers/{name}/kill", this::killServer)
                .post("/api/v1/servers/{name}/command", this::sendCommand)
                .get("/api/v1/groups", this::listGroups)
                .post("/api/v1/groups/{name}/start", this::startServer)
                .get("/api/v1/nodes", this::listNodes)
                .get("/api/v1/overview", this::overview)
                .get("/api/v1/players", this::searchPlayers)
                .get("/api/v1/players/online", this::onlinePlayers)
                .get("/api/v1/players/{uuid}", this::player)
                .post("/api/v1/players/{uuid}/server", this::movePlayer)
                .get("/api/v1/ranks", this::listRanks)
                .get("/api/v1/modules", this::listModules);
    }

    // ---------------------------------------------------------------- Server

    private void listServers(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.server.list")) {
            return;
        }
        context.json(servers.all().stream().map(CloudRoutes::describe).toList());
    }

    private void stopServer(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.server.stop")) {
            return;
        }
        String name = context.pathParam("name");
        boolean stopped = serverService.stop(name, actor(context), "REST");

        if (!stopped) {
            ApiAuth.fail(context, HttpStatus.NOT_FOUND, "Unbekannter Server: " + name);
            return;
        }
        context.json(Map.of("stopping", name));
    }

    private void killServer(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.server.kill")) {
            return;
        }
        String name = context.pathParam("name");
        if (!serverService.kill(name, actor(context))) {
            ApiAuth.fail(context, HttpStatus.NOT_FOUND, "Unbekannter Server: " + name);
            return;
        }
        context.json(Map.of("killed", name));
    }

    /**
     * Schreibt eine Zeile in die Konsole eines Servers - der schreibende Teil von
     * {@code screen}.
     *
     * <p>Derselbe Weg wie in der Master-Konsole ({@link ServerService#execute}), aber ein
     * <b>eigenes Recht</b>: {@value HttpApi#CONSOLE_SEND_PERMISSION}. Das Log mitzulesen und Befehle
     * abzusetzen ist nicht dasselbe - in der Konsole eines Gameservers gibt es {@code op},
     * und wer das tippen darf, darf auf diesem Server alles. In der Master-Konsole faellt
     * der Unterschied nicht auf, weil dort ohnehin jeder alles darf.
     *
     * <p>Genau eine Zeile: Ein Zeilenumbruch waere ein zweiter Befehl, der im Protokoll
     * nicht als solcher auftaucht.
     */
    private void sendCommand(Context context) {
        if (!auth.require(context, WRITE, CONSOLE_SEND)) {
            return;
        }
        String name = context.pathParam("name");
        Map<?, ?> body;
        try {
            body = GSON.fromJson(context.body(), Map.class);
        } catch (RuntimeException exception) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Rumpf ist kein JSON");
            return;
        }
        Object given = body == null ? null : body.get("command");
        if (!(given instanceof String line) || line.isBlank()) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Feld 'command' fehlt");
            return;
        }
        if (line.contains("\n") || line.contains("\r")) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Nur eine Zeile je Aufruf");
            return;
        }
        if (line.length() > MAX_COMMAND_LENGTH) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST,
                    "Hoechstens " + MAX_COMMAND_LENGTH + " Zeichen");
            return;
        }
        if (servers.find(name).isEmpty()) {
            ApiAuth.fail(context, HttpStatus.NOT_FOUND, "Unbekannter Server: " + name);
            return;
        }
        // Der Dienst schreibt den Befehl samt Absender ins Protokoll.
        if (!serverService.execute(name, line.strip(), actor(context))) {
            ApiAuth.fail(context, HttpStatus.SERVICE_UNAVAILABLE,
                    "Der Node von " + name + " ist nicht erreichbar");
            return;
        }
        // "angenommen", nicht "ausgefuehrt": Was der Server daraus macht, steht in
        // seinem Log - eine Antwort auf einen Befehl gibt es in dieser Richtung nicht.
        context.json(Map.of("accepted", true, "server", name));
    }

    private void startServer(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.server.start")) {
            return;
        }
        String group = context.pathParam("name");
        ServerService.StartResult result = serverService.start(group, actor(context));

        if (!result.started()) {
            // Kein Node frei, Gruppe unbekannt, Grenze erreicht - der Dienst sagt, warum.
            ApiAuth.fail(context, HttpStatus.CONFLICT, result.reason());
            return;
        }
        context.json(Map.of(
                "server", result.serverName(),
                "node", result.node(),
                "port", result.port()));
    }

    // ---------------------------------------------------------------- Gruppen und Nodes

    /**
     * Alle Gruppen - mit den aenderbaren Feldern und ihren aktuellen Werten.
     *
     * <p>Die Werte stehen bewusst in derselben Antwort und nicht hinter einem zweiten
     * Aufruf je Gruppe: Das Formular zum Bearbeiten soll zeigen koennen, was drinsteht,
     * ohne vorher nachzuladen.
     */
    private void listGroups(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.group.list")) {
            return;
        }
        context.json(groups.findAll().stream().map(group -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", group.name());
            entry.put("platform", group.platform().name());
            entry.put("static", group.staticGroup());
            entry.put("minOnline", group.minOnline());
            entry.put("maxOnline", group.maxOnline());
            entry.put("memoryMb", group.memoryMb());
            entry.put("online", servers.activeOfGroup(group.name()).size());

            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : ServerGroupRepository.editableFields()) {
                fields.put(field, ServerGroupRepository.valueOf(group, field));
            }
            entry.put("fields", fields);
            return entry;
        }).toList());
    }

    private void listNodes(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.node.list")) {
            return;
        }
        context.json(nodeRepository.findAll().stream().map(node -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", node.name());
            entry.put("connected", nodes.isConnected(node.name()));
            entry.put("enabled", node.enabled());
            entry.put("maxMemoryMb", node.maxMemoryMb());
            entry.put("servers", servers.onNode(node.name()).size());
            entry.put("lastSeen", Times.format(node.lastSeen()));
            return entry;
        }).toList());
    }

    // ---------------------------------------------------------------- Spieler

    private void player(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.player.info")) {
            return;
        }
        Optional<UUID> uuid = uuid(context);
        if (uuid.isEmpty()) {
            return;
        }
        Optional<PlayerRepository.PlayerRecord> found = players.find(uuid.get());
        if (found.isEmpty()) {
            ApiAuth.fail(context, HttpStatus.NOT_FOUND, "Unbekannter Spieler");
            return;
        }
        PlayerRepository.PlayerRecord record = found.get();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("uuid", record.uuid().toString());
        answer.put("name", record.name());
        answer.put("platform", record.platform());
        answer.put("rank", record.rankId());
        answer.put("rankExpiresAt", Times.format(record.rankExpiresAt()));
        answer.put("locale", record.locale() == null ? "" : record.locale());
        answer.put("firstLogin", Times.format(record.firstLogin()));
        answer.put("lastLogin", Times.format(record.lastLogin()));
        answer.put("lastServer", record.lastServer() == null ? "" : record.lastServer());
        answer.put("online", online.isOnline(record.uuid()));
        answer.put("playtimeSeconds", record.playtimeSeconds());
        context.json(answer);
    }

    private void movePlayer(Context context) {
        if (!auth.require(context, WRITE, "vibecloud.command.send")) {
            return;
        }
        Optional<UUID> uuid = uuid(context);
        if (uuid.isEmpty()) {
            return;
        }
        Map<?, ?> body;
        try {
            body = GSON.fromJson(context.body(), Map.class);
        } catch (RuntimeException exception) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Rumpf ist kein JSON");
            return;
        }
        Object target = body == null ? null : body.get("server");
        if (!(target instanceof String server) || server.isBlank()) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Feld 'server' fehlt");
            return;
        }

        Optional<String> rejected = transfers.rejectTarget(server);
        if (rejected.isPresent()) {
            ApiAuth.fail(context, HttpStatus.NOT_FOUND, rejected.get());
            return;
        }
        int reached = transfers.transfer(uuid.get(), server, actor(context));
        if (reached == 0) {
            ApiAuth.fail(context, HttpStatus.SERVICE_UNAVAILABLE, "Kein Proxy erreichbar");
            return;
        }
        // "angenommen", nicht "erledigt": Ob der Spieler online war, weiss nur der Proxy.
        context.json(Map.of("accepted", true, "server", server, "proxies", reached));
    }

    /**
     * Spielersuche nach Namensanfang.
     *
     * <p>{@code GET /api/v1/players?q=kev}. Ohne {@code q} kommen die zuletzt gesehenen -
     * das ist die Liste "alle Spieler", sinnvoll sortiert.
     */
    private void searchPlayers(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.player.find")) {
            return;
        }
        String query = Optional.ofNullable(context.queryParam("q")).orElse("");
        int limit = Math.clamp(parseInt(context.queryParam("limit"), 50), 1, 200);

        context.json(players.suggestNames(query, limit).stream()
                .map(players::findByName)
                .flatMap(Optional::stream)
                .map(this::shortPlayer)
                .toList());
    }

    /** Wer gerade verbunden ist - aus der Meldung der Proxys, nicht aus der Datenbank. */
    private void onlinePlayers(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.player.find")) {
            return;
        }
        context.json(online.all().stream().map(player -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uuid", player.uuid().toString());
            entry.put("name", player.name());
            entry.put("server", player.server());
            entry.put("proxy", player.proxy());
            entry.put("since", Times.format(player.since()));
            return entry;
        }).toList());
    }

    /**
     * Alles auf einen Blick fuer die Startseite.
     *
     * <p>In einem Aufruf statt in fuenf: Die Uebersicht laedt sonst ein halbes Dutzend
     * Endpunkte, und die Zahlen passten nicht zueinander, weil sie aus verschiedenen
     * Momenten stammen.
     */
    private void overview(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.cloud.status")) {
            return;
        }
        List<CloudServer> all = servers.all();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("servers", all.size());
        answer.put("serversRunning", all.stream()
                .filter(server -> server.state() == de.kevloe.vibecloud.api.ServerState.RUNNING)
                .count());
        answer.put("groups", groups.findAll().size());
        answer.put("nodes", nodeRepository.findAll().size());
        answer.put("nodesConnected", nodeRepository.findAll().stream()
                .filter(node -> nodes.isConnected(node.name()))
                .count());
        answer.put("modules", modules.list().size());
        answer.put("playersOnline", online.count());
        answer.put("playersKnown", players.countAll());
        context.json(answer);
    }

    private Map<String, Object> shortPlayer(PlayerRepository.PlayerRecord record) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("uuid", record.uuid().toString());
        entry.put("name", record.name());
        entry.put("rank", record.rankId());
        entry.put("lastLogin", Times.format(record.lastLogin()));
        entry.put("lastServer", record.lastServer() == null ? "" : record.lastServer());
        entry.put("online", online.isOnline(record.uuid()));
        return entry;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- Raenge und Module

    private void listRanks(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.rank.list")) {
            return;
        }
        Map<String, List<String>> inheritance = ranks.inheritance();

        context.json(ranks.findAll().stream().map(rank -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rank.id());
            entry.put("displayName", rank.displayName());
            entry.put("prefix", rank.prefix());
            entry.put("weight", rank.weight());
            entry.put("default", rank.isDefault());
            entry.put("inherits", inheritance.getOrDefault(rank.id(), List.of()));

            // Wie bei den Gruppen: die aenderbaren Felder samt aktuellem Wert.
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : RankRepository.editableFields()) {
                fields.put(field, RankRepository.valueOf(rank, field));
            }
            entry.put("fields", fields);
            return entry;
        }).toList());
    }

    /**
     * Welche Module laufen.
     *
     * <p>Das Dashboard blendet damit Seiten aus, zu denen kein Modul geladen ist - die
     * Ban-Verwaltung verschwindet ohne {@code punishment} (PLAN.md Abschnitt 12).
     */
    private void listModules(Context context) {
        if (!auth.require(context, READ, "vibecloud.command.module.list")) {
            return;
        }
        context.json(modules.list().stream().map(module -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", module.descriptor().id());
            entry.put("name", module.descriptor().name());
            entry.put("version", module.descriptor().version());
            entry.put("enabled", module.enabled());
            return entry;
        }).toList());
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static Map<String, Object> describe(CloudServer server) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", server.name());
        entry.put("group", server.groupName());
        entry.put("platform", server.platform().name());
        entry.put("node", server.node());
        entry.put("port", server.port());
        entry.put("state", server.state().name());
        entry.put("static", server.staticServer());
        entry.put("players", server.players());
        entry.put("maxPlayers", server.maxPlayers());
        entry.put("startedAt", Times.format(server.startedAt()));
        return entry;
    }

    /** Liest die UUID aus dem Pfad und antwortet selbst, wenn sie nicht stimmt. */
    private static Optional<UUID> uuid(Context context) {
        try {
            return Optional.of(UUID.fromString(context.pathParam("uuid")));
        } catch (IllegalArgumentException exception) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Keine gueltige UUID");
            return Optional.empty();
        }
    }

    /** Wer die Aktion veranlasst hat - fuer das Protokoll. */
    private static String actor(Context context) {
        return ApiAuth.of(context).map(ApiAuth.Principal::name).orElse("API");
    }
}
