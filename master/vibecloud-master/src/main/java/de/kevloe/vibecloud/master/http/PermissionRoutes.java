package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.api.permission.PermissionResolver;
import de.kevloe.vibecloud.api.permission.ResolvedPermissions;
import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.console.CommandRegistry;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Einzelne Rechte von Raengen und Spielern ueber HTTP (PLAN.md Abschnitt 9 und 12).
 *
 * <p>Derselbe Weg wie {@code perm} in der Konsole: Diese Klasse ruft
 * {@link PermissionService} und baut keine eigene Auswertung. Die Rechte-Auswertung ist die
 * Stelle, an der zwei Umsetzungen derselben Regeln am teuersten waeren - ein Unterschied
 * faellt nicht als Fehler auf, sondern als ein Spieler, der etwas darf.
 *
 * <p><b>Eigene Klasse</b> und nicht in {@link AdminRoutes}: Dort geht es um Felder einer
 * Gruppe oder eines Rangs, hier um Regeln mit Knoten, Kontext und Ablauf. Beides in einer
 * Klasse waere schnell unleserlich - genau der Grund, aus dem {@code AdminRoutes} und
 * {@code CloudRoutes} getrennt sind.
 *
 * <h2>Welche Rechte ein Aufruf braucht</h2>
 * Dieselben Knoten wie die Unterbefehle von {@code perm}: {@code perm.rank},
 * {@code perm.player}, {@code perm.check}, {@code perm.list}. Auch das <b>Lesen</b> der
 * Rang-Regeln haengt an {@code perm.rank}, obwohl es nur liest: Es gibt keinen lesenden
 * Unterbefehl dafuer, und einen hier zu erfinden hiesse, einen Rechte-Knoten zu haben, den
 * die Konsole nicht kennt.
 */
final class PermissionRoutes {

    private static final Gson GSON = new Gson();

    private static final String READ = "cloud.read";
    private static final String WRITE = "cloud.write";

    private static final String PERM_RANK = "vibecloud.command.perm.rank";
    private static final String PERM_PLAYER = "vibecloud.command.perm.player";
    private static final String PERM_CHECK = "vibecloud.command.perm.check";
    private static final String PERM_LIST = "vibecloud.command.perm.list";

    /**
     * Ein Knoten: Segmente aus Kleinbuchstaben, Ziffern, Unterstrich und Bindestrich, durch
     * Punkte getrennt. Ein Stern darf allein stehen oder als letztes Segment.
     *
     * <p>Geprueft wird das, weil ein Knoten, den
     * {@link de.kevloe.vibecloud.api.permission.PermissionNodes} nie trifft, nicht als
     * Fehler auffaellt: Die Regel steht in der Datenbank, wird bei jeder Abfrage geladen
     * und trifft nie zu. {@code vibecloud.*.stop} sieht wie ein Wildcard aus, ist aber
     * keiner - ein Stern mitten im Knoten wird als gewoehnliches Zeichen verglichen und
     * passt damit auf nichts.
     */
    private static final Pattern VALID_NODE =
            Pattern.compile("\\*|[a-z0-9_-]+(\\.[a-z0-9_-]+)*(\\.\\*)?");

    private final ApiAuth auth;
    private final PermissionService permissions;
    private final RankRepository ranks;
    private final PlayerService players;
    private final CommandRegistry commands;

    PermissionRoutes(ApiAuth auth, PermissionService permissions, RankRepository ranks,
                     PlayerService players, CommandRegistry commands) {
        this.auth = auth;
        this.permissions = permissions;
        this.ranks = ranks;
        this.players = players;
        this.commands = commands;
    }

    void register(RoutesConfig routes) {
        routes
                .get("/api/v1/permissions/nodes", this::nodeCatalog)
                // Raenge
                .get("/api/v1/ranks/{id}/permissions", this::rankPermissions)
                .post("/api/v1/ranks/{id}/permissions", this::addRankPermission)
                .delete("/api/v1/ranks/{id}/permissions", this::removeRankPermission)
                // Spieler
                .get("/api/v1/players/{uuid}/permissions", this::playerPermissions)
                .post("/api/v1/players/{uuid}/permissions", this::addPlayerPermission)
                .delete("/api/v1/players/{uuid}/permissions", this::removePlayerPermission)
                .get("/api/v1/players/{uuid}/permissions/check", this::check);
    }

    // ---------------------------------------------------------------- Katalog

    /**
     * Welche Rechte-Knoten es gibt - fuer die Vorschlagsliste im Dashboard.
     *
     * <p>Dieselbe Liste wie die Tab-Vervollstaendigung von {@code perm}
     * ({@link CommandRegistry#permissionNodes(List)}): Was in der Konsole vorgeschlagen
     * wird, soll im Dashboard auch vorgeschlagen werden.
     *
     * <p>Es ist ein <b>Vorschlag</b>, keine Auswahl. Ein Knoten, der hier fehlt, wirkt
     * genauso - Plugins bringen eigene mit, ohne sie anzumelden. Als Auswahlliste waere das
     * Dashboard enger als die Konsole.
     */
    private void nodeCatalog(Context context) {
        if (!auth.require(context, READ, PERM_LIST)) {
            return;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String node : commands.permissionNodes(permissions.declaredNodes())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("node", node);
            entry.put("description", permissions.describeNode(node).orElse(""));
            nodes.add(entry);
        }
        context.json(nodes);
    }

    // ---------------------------------------------------------------- Raenge

    /**
     * Die Regeln eines Rangs - eigene und geerbte getrennt.
     *
     * <p>Die geerbten stehen mit dabei, weil ohne sie niemand erklaeren kann, warum ein
     * Rang etwas darf, das nicht in seiner eigenen Liste steht. Gebaut werden sie von
     * {@link PermissionResolver}, also von derselben Stelle, die auch beim Login
     * entscheidet - nicht von einem zweiten Durchlauf durch die Vererbung.
     */
    private void rankPermissions(Context context) {
        if (!auth.require(context, READ, PERM_RANK)) {
            return;
        }
        String id = context.pathParam("id");
        if (ranks.find(id).isEmpty()) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekannter Rang: " + id);
            return;
        }

        // Ohne Spieler-Regeln: Hier geht es um den Rang, nicht um eine Person. Damit
        // bleiben als Ebenen nur OWN_RANK und INHERITED_RANK uebrig.
        ResolvedPermissions resolved = PermissionResolver.resolve(id, ranks.rankInfos(),
                ranks.inheritance(), ranks.permissions(), List.of());

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("rank", id);
        answer.put("own", resolved.candidates().stream()
                .filter(candidate -> candidate.tier() == ResolvedPermissions.Tier.OWN_RANK)
                .map(PermissionRoutes::describe)
                .toList());
        answer.put("inherited", resolved.candidates().stream()
                .filter(candidate ->
                        candidate.tier() == ResolvedPermissions.Tier.INHERITED_RANK)
                .map(PermissionRoutes::describe)
                .toList());
        context.json(answer);
    }

    private void addRankPermission(Context context) {
        if (!auth.require(context, WRITE, PERM_RANK)) {
            return;
        }
        String id = context.pathParam("id");
        if (ranks.find(id).isEmpty()) {
            fail(context, HttpStatus.NOT_FOUND, "Unbekannter Rang: " + id);
            return;
        }
        Optional<PermissionEntry> entry = readEntry(context, false);
        if (entry.isEmpty()) {
            return;
        }
        permissions.addRankPermission(id, entry.get(), actor(context));

        context.json(Map.of(
                "rank", id,
                "rule", entry.get().toString(),
                "note", "Wirkt sofort fuer alle Spieler mit diesem Rang - auch fuer die, "
                        + "die ihn erben"));
    }

    private void removeRankPermission(Context context) {
        if (!auth.require(context, WRITE, PERM_RANK)) {
            return;
        }
        String id = context.pathParam("id");
        Optional<String> node = queryNode(context);
        if (node.isEmpty()) {
            return;
        }
        PermissionContext scope = contextOf(context.queryParam("group"),
                context.queryParam("server"));

        if (!permissions.removeRankPermission(id, node.get(), scope, actor(context))) {
            // Der Kontext gehoert in die Meldung: Dieselbe Regel kann global und je Gruppe
            // existieren, und entfernt wird genau eine davon.
            fail(context, HttpStatus.NOT_FOUND,
                    id + " hat kein Recht " + node.get() + " mit Kontext " + scope);
            return;
        }
        context.json(Map.of("rank", id, "removed", node.get(),
                "context", scope.toString()));
    }

    // ---------------------------------------------------------------- Spieler

    /**
     * Die Regeln eines Spielers: seine eigenen und alles, was sonst noch zutrifft.
     *
     * <p>{@code effective} ist dieselbe Kandidatenliste, die {@code perm list} ausgibt -
     * jede Regel mit ihrer Herkunft. Was am Ende gilt, haengt vom Kontext ab und ist
     * deshalb kein Feld hier, sondern die Antwort von {@code /permissions/check}.
     */
    private void playerPermissions(Context context) {
        if (!auth.require(context, READ, PERM_LIST)) {
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = record(context);
        if (record.isEmpty()) {
            return;
        }
        PlayerRepository.PlayerRecord player = record.get();
        ResolvedPermissions resolved = permissions.resolve(player.uuid());

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("uuid", player.uuid().toString());
        answer.put("name", player.name());
        answer.put("rank", player.rankId());
        answer.put("own", players.repository().permissionsOf(player.uuid()).stream()
                .map(PermissionRoutes::describe)
                .toList());
        answer.put("effective", resolved.candidates().stream()
                .map(PermissionRoutes::describe)
                .toList());
        context.json(answer);
    }

    private void addPlayerPermission(Context context) {
        if (!auth.require(context, WRITE, PERM_PLAYER)) {
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = record(context);
        if (record.isEmpty()) {
            return;
        }
        Optional<PermissionEntry> entry = readEntry(context, true);
        if (entry.isEmpty()) {
            return;
        }
        permissions.addPlayerPermission(record.get().uuid(), entry.get(), actor(context));

        context.json(Map.of(
                "player", record.get().name(),
                "rule", entry.get().toString(),
                "expiresAt", Times.format(entry.get().expires())));
    }

    private void removePlayerPermission(Context context) {
        if (!auth.require(context, WRITE, PERM_PLAYER)) {
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = record(context);
        if (record.isEmpty()) {
            return;
        }
        Optional<String> node = queryNode(context);
        if (node.isEmpty()) {
            return;
        }
        PermissionContext scope = contextOf(context.queryParam("group"),
                context.queryParam("server"));

        if (!permissions.removePlayerPermission(record.get().uuid(), node.get(), scope,
                actor(context))) {
            fail(context, HttpStatus.NOT_FOUND, record.get().name()
                    + " hat kein eigenes Recht " + node.get() + " mit Kontext " + scope);
            return;
        }
        context.json(Map.of("player", record.get().name(), "removed", node.get(),
                "context", scope.toString()));
    }

    /**
     * {@code perm check} als Antwort: nicht nur ja oder nein, sondern <b>warum</b>.
     *
     * <p>Das ist beim Suchen eines falschen Rechts das Wichtigste. Ohne die entscheidende
     * Regel und die ueberstimmten sucht man die ganze Rang-Hierarchie durch.
     */
    private void check(Context context) {
        if (!auth.require(context, READ, PERM_CHECK)) {
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = record(context);
        if (record.isEmpty()) {
            return;
        }
        Optional<String> node = queryNode(context);
        if (node.isEmpty()) {
            return;
        }
        PermissionContext scope = contextOf(context.queryParam("group"),
                context.queryParam("server"));

        ResolvedPermissions resolved = permissions.resolve(record.get().uuid());
        Optional<ResolvedPermissions.Candidate> decision = resolved.decide(node.get(), scope);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("player", record.get().name());
        answer.put("node", node.get());
        answer.put("context", scope.toString());
        answer.put("allowed", resolved.has(node.get(), scope));
        answer.put("decidedBy", decision.map(PermissionRoutes::describe).orElse(null));

        // Die ueberstimmten Regeln mitzuliefern ist der halbe Zweck: Oft steht genau da der
        // Denkfehler - ein Verbot im geerbten Rang, das niemand vermutet hat.
        answer.put("overridden", decision.map(winner -> resolved.candidates().stream()
                        .filter(other -> other.entry().matches(node.get()))
                        .filter(other -> other.entry().context().appliesTo(scope))
                        .filter(other -> other != winner)
                        .map(PermissionRoutes::describe)
                        .toList())
                .orElse(List.of()));
        context.json(answer);
    }

    // ---------------------------------------------------------------- Abbildung

    /** Eine Regel mit Herkunft - fuer Listen, in denen Ebenen gemischt vorkommen. */
    private static Map<String, Object> describe(ResolvedPermissions.Candidate candidate) {
        Map<String, Object> entry = describe(candidate.entry());
        entry.put("tier", candidate.tier().name());
        entry.put("source", candidate.source());
        entry.put("weight", candidate.weight());
        return entry;
    }

    /**
     * Eine Regel ohne Herkunft.
     *
     * <p>{@code group} und {@code server} stehen einzeln daneben, nicht nur als Text: Die
     * Oberflaeche muss sie beim Entfernen wieder genauso mitschicken, und aus
     * {@code "group=lobby"} muesste sie sie erst wieder zerlegen.
     */
    private static Map<String, Object> describe(PermissionEntry entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node", entry.node());
        result.put("value", entry.value());
        result.put("context", entry.context().toString());
        result.put("group", entry.context().group() == null ? "" : entry.context().group());
        result.put("server", entry.context().server() == null ? "" : entry.context().server());
        result.put("expiresAt", Times.format(entry.expires()));
        return result;
    }

    // ---------------------------------------------------------------- Eingaben

    /**
     * Liest eine Regel aus dem Rumpf und antwortet selbst, wenn etwas nicht stimmt.
     *
     * <p>{@code value} ist ein eigenes Feld und nicht ein Minus am Knoten: In einer
     * Oberflaeche ist "erlaubt oder verboten" eine Auswahl, kein Zeichen, das man vergessen
     * kann. Ein fuehrendes Minus wird trotzdem verstanden - so laesst sich eine Zeile aus
     * der Konsole eins zu eins einfuegen.
     *
     * @param allowDuration ob {@code duration} erlaubt ist. Nur bei Spielern: Eine
     *                      Rang-Regel, die um drei Uhr nachts ablaeuft, aendert still die
     *                      Rechte aller Spieler mit diesem Rang - dafuer gibt es auch in
     *                      der Konsole keinen Weg.
     */
    private Optional<PermissionEntry> readEntry(Context context, boolean allowDuration) {
        Map<?, ?> body;
        try {
            body = GSON.fromJson(context.body(), Map.class);
        } catch (RuntimeException exception) {
            fail(context, HttpStatus.BAD_REQUEST, "Rumpf ist kein JSON");
            return Optional.empty();
        }
        String raw = text(body, "node");
        if (raw == null) {
            fail(context, HttpStatus.BAD_REQUEST, "Feld 'node' ist Pflicht");
            return Optional.empty();
        }

        // Ein Minus am Knoten ist die Schreibweise der Konsole. Steht zusaetzlich
        // "value": false daneben, bedeutet beides dasselbe - es ist keine doppelte
        // Negation.
        boolean negated = raw.trim().startsWith("-");
        String node = normalize(negated ? raw.trim().substring(1) : raw);

        Optional<String> rejected = rejectNode(node);
        if (rejected.isPresent()) {
            fail(context, HttpStatus.BAD_REQUEST, rejected.get());
            return Optional.empty();
        }

        boolean value = !negated
                        && !(body.get("value") instanceof Boolean allowed && !allowed);

        Instant expires = null;
        String duration = text(body, "duration");
        if (duration != null && !duration.equalsIgnoreCase("permanent")) {
            if (!allowDuration) {
                fail(context, HttpStatus.BAD_REQUEST,
                        "Eine Rang-Regel kann nicht ablaufen - nur Spieler-Rechte");
                return Optional.empty();
            }
            Duration parsed = Times.parseDuration(duration);
            if (parsed == null) {
                fail(context, HttpStatus.BAD_REQUEST,
                        "Dauer nicht verstanden: " + duration + " (etwa 30d, 12h, 90m, 45s)");
                return Optional.empty();
            }
            expires = Instant.now().plus(parsed);
        }

        PermissionContext scope = contextOf(text(body, "group"), text(body, "server"));
        return Optional.of(new PermissionEntry(node, value, scope, expires));
    }

    /** Der Knoten aus der Abfrage - beim Entfernen steht er nicht im Rumpf. */
    private Optional<String> queryNode(Context context) {
        String raw = context.queryParam("node");
        if (raw == null || raw.isBlank()) {
            fail(context, HttpStatus.BAD_REQUEST, "Abfrageparameter 'node' fehlt");
            return Optional.empty();
        }
        String trimmed = raw.trim();
        // Ein Minus davor bedeutet beim Entfernen nichts: Entfernt wird die Regel zu diesem
        // Knoten, ob sie erlaubt oder verbietet.
        String node = normalize(trimmed.startsWith("-") ? trimmed.substring(1) : trimmed);

        Optional<String> rejected = rejectNode(node);
        if (rejected.isPresent()) {
            fail(context, HttpStatus.BAD_REQUEST, rejected.get());
            return Optional.empty();
        }
        return Optional.of(node);
    }

    /** Kleinschreibung wie beim Speichern - sonst findet das Entfernen seine Regel nicht. */
    static String normalize(String node) {
        return node.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Prueft einen Knoten.
     *
     * @return der Grund der Ablehnung, oder leer wenn er in Ordnung ist
     */
    static Optional<String> rejectNode(String node) {
        if (node.isBlank()) {
            return Optional.of("Der Knoten darf nicht leer sein");
        }
        if (!VALID_NODE.matcher(node).matches()) {
            return Optional.of("Kein gueltiger Knoten: " + node
                               + " - erlaubt sind Kleinbuchstaben, Ziffern, Unterstrich, "
                               + "Bindestrich und Punkte; ein Stern nur am Ende "
                               + "(vibecloud.command.*)");
        }
        return Optional.empty();
    }

    /**
     * Baut den Kontext aus zwei Angaben.
     *
     * <p>Leer heisst global. Ein Server ohne Gruppe ist erlaubt - eine Regel fuer
     * {@code lobby-1} braucht nicht zu wissen, in welcher Gruppe der Server liegt.
     */
    static PermissionContext contextOf(String group, String server) {
        String cleanGroup = blankToNull(group);
        String cleanServer = blankToNull(server);
        return cleanGroup == null && cleanServer == null
                ? PermissionContext.GLOBAL
                : new PermissionContext(cleanGroup, cleanServer);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Liest den Spieler aus dem Pfad und antwortet selbst, wenn es ihn nicht gibt. */
    private Optional<PlayerRepository.PlayerRecord> record(Context context) {
        UUID uuid;
        try {
            uuid = UUID.fromString(context.pathParam("uuid"));
        } catch (IllegalArgumentException exception) {
            fail(context, HttpStatus.BAD_REQUEST, "Keine gueltige UUID");
            return Optional.empty();
        }
        Optional<PlayerRepository.PlayerRecord> record = players.find(uuid);
        if (record.isEmpty()) {
            // Wie in der Konsole: Rechte gibt es nur fuer einen Spieler, der schon einmal
            // verbunden war - vorher ist seine UUID nicht bekannt.
            fail(context, HttpStatus.NOT_FOUND,
                    "Unbekannter Spieler - er muss einmal verbunden gewesen sein");
        }
        return record;
    }

    private static String text(Map<?, ?> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String found && !found.isBlank() ? found : null;
    }

    private static void fail(Context context, HttpStatus status, String message) {
        ApiAuth.fail(context, status, message);
    }

    private static String actor(Context context) {
        return ApiAuth.of(context).map(ApiAuth.Principal::name).orElse("API");
    }
}
