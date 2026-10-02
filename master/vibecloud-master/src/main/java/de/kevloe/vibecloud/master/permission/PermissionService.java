package de.kevloe.vibecloud.master.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.events.PlayerPermissionsChangedEvent;
import de.kevloe.vibecloud.api.event.events.PlayerRankChangeEvent;
import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.api.permission.PermissionResolver;
import de.kevloe.vibecloud.api.permission.ResolvedPermissions;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Loest Rechte auf und haelt sie im Cache (PLAN.md Abschnitt 9).
 *
 * <p><b>Der Master prueft jede Berechtigung selbst</b> - Plugins fragen hier nach und
 * uebertragen nie ein "darf das"-Flag (PLAN.md Abschnitt 13).
 *
 * <h2>Cache und Invalidierung</h2>
 * Der Master haelt die aufgeloesten Rechte in einem Caffeine-Cache. Aendert sich etwas,
 * wird invalidiert und den Plugins mitgeteilt, dass sie neu laden sollen.
 *
 * <p><b>Abweichung von PLAN.md Abschnitt 9:</b> Der Plan verteilt die Invalidierung ueber
 * Redis Pub/Sub. Hier laeuft sie ueber die bestehenden gRPC-Streams zu den Plugins. Grund:
 * Ueber Redis braeuchte jedes Plugin Redis-Zugangsdaten, und Abschnitt 3 legt fest, dass
 * Plugins keinen Zugang zu Datenspeichern haben. Redis bleibt damit fuer M4 ohne Aufgabe -
 * es wird interessant, sobald es einen zweiten Master gibt oder Module gemeinsamen Zustand
 * brauchen.
 */
public final class PermissionService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionService.class);

    private final RankRepository ranks;
    private final PlayerRepository players;
    private final EventBus events;
    private final AuditLog audit;

    /**
     * Aufgeloeste Rechte je Spieler. Kurze Lebensdauer als Sicherheitsnetz: Sollte eine
     * Invalidierung je ausbleiben, korrigiert sich der Zustand nach zehn Minuten selbst.
     */
    private final Cache<UUID, ResolvedPermissions> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private final ScheduledExecutorService expiryWatcher;

    /** Wird gerufen, wenn Plugins neu laden sollen. Gesetzt vom Bootstrap. */
    private volatile Consumer<UUID> invalidationListener = uuid -> { };

    public PermissionService(RankRepository ranks, PlayerRepository players, EventBus events,
                             AuditLog audit) {
        this.ranks = ranks;
        this.players = players;
        this.events = events;
        this.audit = audit;
        this.expiryWatcher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("rank-expiry").factory());
    }

    /**
     * Rechte, die ein Modul angemeldet hat - Knoten auf Beschreibung.
     *
     * <p>Nur zur Dokumentation und fuer die Vervollstaendigung: Wer
     * {@code perm player add Kevin vibecloud.punishment.} tippt, soll sehen, was es
     * ueberhaupt gibt. Auf die Auswertung hat das keinen Einfluss - ein nicht angemeldeter
     * Knoten wirkt genauso.
     */
    private final Map<String, String> declaredNodes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Meldet ein Recht an. */
    public void declareNode(String node, String description) {
        declaredNodes.put(node, description == null ? "" : description);
    }

    /** Nimmt ein Recht zurueck - beim Entladen eines Moduls. */
    public void forgetNode(String node) {
        declaredNodes.remove(node);
    }

    /** Alle angemeldeten Rechte, alphabetisch. */
    public List<String> declaredNodes() {
        return declaredNodes.keySet().stream().sorted().toList();
    }

    /** Beschreibung eines angemeldeten Rechts. */
    public Optional<String> describeNode(String node) {
        return Optional.ofNullable(declaredNodes.get(node));
    }

    /**
     * Startet die Ablaufpruefung.
     *
     * <p>Laeuft minuetlich und zusaetzlich beim Login, falls der Master zwischendurch aus
     * war (PLAN.md Abschnitt 9).
     */
    public void start() {
        expiryWatcher.scheduleAtFixedRate(this::expireRanks, 1, 1, TimeUnit.MINUTES);
        expireRanks();
    }

    public void onInvalidation(Consumer<UUID> listener) {
        this.invalidationListener = listener;
    }

    // ---------------------------------------------------------------- Auflosung

    /** Die aufgeloesten Rechte eines Spielers. */
    public ResolvedPermissions resolve(UUID uuid) {
        return cache.get(uuid, this::load);
    }

    /** Prueft ein Recht - die Antwort auf jede Plugin-Anfrage. */
    public boolean has(UUID uuid, String node, PermissionContext context) {
        return resolve(uuid).has(node, context);
    }

    public boolean has(UUID uuid, String node) {
        return has(uuid, node, PermissionContext.GLOBAL);
    }

    /** Welche Regel entschieden hat - fuer {@code /perm check}. */
    public Optional<ResolvedPermissions.Candidate> explain(UUID uuid, String node,
                                                           PermissionContext context) {
        return resolve(uuid).decide(node, context);
    }

    private ResolvedPermissions load(UUID uuid) {
        Optional<PlayerRepository.PlayerRecord> player = players.find(uuid);
        if (player.isEmpty()) {
            return ResolvedPermissions.empty();
        }

        // Abgelaufenen Rang hier gleich korrigieren: Der Spieler koennte joinen, bevor der
        // Minuten-Scheduler das naechste Mal laeuft.
        PlayerRepository.PlayerRecord record = player.get();
        if (record.hasExpiredRank()) {
            expireRank(record);
            record = players.find(uuid).orElse(record);
        }

        Map<String, List<String>> inheritance = ranks.inheritance();

        // Ein Zyklus wuerde die Auflosung unvollstaendig machen. Beim Anlegen wird er
        // verhindert; hier ist es die letzte Pruefung, falls die Daten von Hand geaendert
        // wurden.
        PermissionResolver.findCycle(inheritance).ifPresent(cycle ->
                LOG.error("Die Rang-Vererbung enthaelt einen Zyklus: {}. Die Rechte sind damit "
                          + "unvollstaendig. Bitte mit 'rank inherit remove' aufloesen.",
                        String.join(" -> ", cycle)));

        return PermissionResolver.resolve(
                record.rankId(),
                ranks.rankInfos(),
                inheritance,
                ranks.permissions(),
                players.permissionsOf(uuid));
    }

    // ---------------------------------------------------------------- Aenderungen

    /**
     * Setzt den Rang eines Spielers.
     *
     * @param duration wie lange, {@code null} = permanent
     */
    public void setRank(UUID uuid, String rankId, Duration duration, String actor,
                        String reason) {
        PlayerRepository.PlayerRecord before = players.find(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Spieler ist unbekannt"));

        ranks.find(rankId).orElseThrow(() ->
                new IllegalArgumentException("Rang " + rankId + " existiert nicht"));

        Instant expires = duration == null ? null : Instant.now().plus(duration);
        // Nach Ablauf zurueck auf den vorherigen Rang - nicht auf den Default. Wer von
        // premium auf vip hochgesetzt wird, soll danach wieder premium sein.
        String fallback = expires == null ? null : before.rankId();

        players.setRank(uuid, rankId, expires, fallback, actor, reason);
        audit.record(actor, "player.rank_set", before.name(),
                Map.of("from", before.rankId(), "to", rankId,
                        "expires", expires == null ? "nie" : expires.toString()));

        invalidate(uuid);
        events.post(new PlayerRankChangeEvent(uuid, before.name(), before.rankId(), rankId,
                actor, expires));
        LOG.info("{} hat {} den Rang {} gegeben{}", actor, before.name(), rankId,
                expires == null ? "" : " (bis " + expires + ")");
    }

    /** Setzt auf den Default-Rang zurueck. */
    public void resetRank(UUID uuid, String actor) {
        String defaultRank = ranks.findDefault()
                .map(RankRepository.Rank::id)
                .orElseThrow(() -> new IllegalStateException("Es gibt keinen Default-Rang"));
        setRank(uuid, defaultRank, null, actor, "zurueckgesetzt");
    }

    public void addPlayerPermission(UUID uuid, PermissionEntry entry, String actor) {
        players.addPermission(uuid, entry, actor);
        audit.record(actor, "player.permission_added", uuid.toString(),
                Map.of("node", entry.toString()));
        invalidate(uuid);
    }

    public boolean removePlayerPermission(UUID uuid, String node, PermissionContext context,
                                          String actor) {
        boolean removed = players.removePermission(uuid, node, context);
        if (removed) {
            audit.record(actor, "player.permission_removed", uuid.toString(),
                    Map.of("node", node));
            invalidate(uuid);
        }
        return removed;
    }

    public void addRankPermission(String rankId, PermissionEntry entry, String actor) {
        ranks.addPermission(rankId, entry, actor);
        audit.record(actor, "rank.permission_added", rankId, Map.of("node", entry.toString()));
        invalidateRank(rankId);
    }

    public boolean removeRankPermission(String rankId, String node, PermissionContext context,
                                        String actor) {
        boolean removed = ranks.removePermission(rankId, node, context);
        if (removed) {
            audit.record(actor, "rank.permission_removed", rankId, Map.of("node", node));
            invalidateRank(rankId);
        }
        return removed;
    }

    /**
     * Legt eine Vererbung an - aber nur, wenn dadurch kein Zyklus entsteht.
     *
     * @throws IllegalArgumentException wenn es einen Zyklus gaebe
     */
    public void addInheritance(String childId, String parentId, String actor) {
        if (!PermissionResolver.canInherit(childId, parentId, ranks.inheritance())) {
            throw new IllegalArgumentException(
                    "Das wuerde einen Zyklus erzeugen: " + parentId + " erbt schon (indirekt) "
                    + "von " + childId);
        }
        ranks.addInheritance(childId, parentId);
        audit.record(actor, "rank.inheritance_added", childId, Map.of("parent", parentId));
        invalidateAll();
    }

    public boolean removeInheritance(String childId, String parentId, String actor) {
        boolean removed = ranks.removeInheritance(childId, parentId);
        if (removed) {
            audit.record(actor, "rank.inheritance_removed", childId, Map.of("parent", parentId));
            invalidateAll();
        }
        return removed;
    }

    // ---------------------------------------------------------------- Invalidierung

    /** Ein Spieler hat neue Rechte. */
    public void invalidate(UUID uuid) {
        cache.invalidate(uuid);
        events.post(new PlayerPermissionsChangedEvent(uuid));
        invalidationListener.accept(uuid);
    }

    /**
     * Ein Rang hat sich geaendert - alle Spieler mit diesem Rang sind betroffen.
     *
     * <p>Gezielt statt pauschal: Bei einer Aenderung an {@code vip} muessen nicht alle
     * Spieler neu geladen werden. Erbt allerdings ein anderer Rang von diesem, greift
     * {@link #invalidateAll()} - die Vererbung macht die Abhaengigkeit unuebersichtlich.
     */
    public void invalidateRank(String rankId) {
        boolean inheritedByOthers = ranks.inheritance().values().stream()
                .anyMatch(parents -> parents.contains(rankId));

        if (inheritedByOthers) {
            invalidateAll();
            return;
        }
        List<UUID> affected = players.findByRank(rankId);
        affected.forEach(this::invalidate);
        LOG.debug("Rang {} geaendert - {} Spieler neu zu laden", rankId, affected.size());
    }

    /** Alles neu laden. Nach Vererbungs-Aenderungen der sichere Weg. */
    public void invalidateAll() {
        cache.invalidateAll();
        invalidationListener.accept(null);
        LOG.debug("Alle Rechte-Caches geleert");
    }

    // ---------------------------------------------------------------- Ablauf

    /**
     * Setzt abgelaufene Raenge zurueck.
     *
     * <p>Laeuft minuetlich. Der Rueckfall geht auf {@code rank_fallback_id}, sonst auf den
     * Default-Rang (PLAN.md Abschnitt 9).
     */
    public void expireRanks() {
        try {
            List<PlayerRepository.PlayerRecord> expired = players.findWithExpiredRank();
            if (!expired.isEmpty()) {
                LOG.info("{} abgelaufene Raenge werden zurueckgesetzt", expired.size());
            }
            expired.forEach(this::expireRank);
        } catch (RuntimeException exception) {
            // Ein Fehler hier darf den Scheduler nicht dauerhaft anhalten.
            LOG.error("Ablaufpruefung der Raenge fehlgeschlagen", exception);
        }
    }

    private void expireRank(PlayerRepository.PlayerRecord record) {
        String fallback = record.rankFallbackId() != null
                ? record.rankFallbackId()
                : ranks.findDefault().map(RankRepository.Rank::id).orElse(null);

        if (fallback == null) {
            LOG.error("Rang von {} ist abgelaufen, aber es gibt keinen Rueckfall und keinen "
                      + "Default-Rang - der Rang bleibt bestehen", record.name());
            return;
        }

        players.setRank(record.uuid(), fallback, null, null, "SYSTEM", "Rang abgelaufen");
        audit.record("SYSTEM", "player.rank_expired", record.name(),
                Map.of("from", record.rankId(), "to", fallback));
        cache.invalidate(record.uuid());
        invalidationListener.accept(record.uuid());
        events.post(new PlayerRankChangeEvent(record.uuid(), record.name(), record.rankId(),
                fallback, "SYSTEM", null));

        LOG.info("Rang von {} ist abgelaufen: {} -> {}",
                record.name(), record.rankId(), fallback);
    }

    public RankRepository ranks() {
        return ranks;
    }

    @Override
    public void close() {
        expiryWatcher.close();
    }
}
