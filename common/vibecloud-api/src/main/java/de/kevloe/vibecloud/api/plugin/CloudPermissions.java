package de.kevloe.vibecloud.api.plugin;

import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.api.permission.ResolvedPermissions;
import de.kevloe.vibecloud.protocol.PermissionRule;
import de.kevloe.vibecloud.protocol.PlayerData;
import de.kevloe.vibecloud.protocol.PlayerDataList;
import de.kevloe.vibecloud.protocol.RankData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rechte und Rang der Spieler auf diesem Server (PLAN.md Abschnitt 9).
 *
 * <p>Der Master liefert die <b>Regeln</b>, nicht fertige Antworten - ausgewertet wird hier,
 * mit dem Kontext dieses Servers. Das ist der Grund, warum kein Relog noetig ist: Eine
 * Rang-Aenderung ersetzt die Regeln, die Auswertung bleibt dieselbe.
 *
 * <p>Diese Klasse ist die lokale Cache-Ebene der Kette aus PLAN.md Abschnitt 9:
 * Plugin → Master → Datenbank.
 */
public final class CloudPermissions {

    private static final Logger LOG = LoggerFactory.getLogger(CloudPermissions.class);

    private final PermissionContext serverContext;
    private final Map<UUID, Snapshot> players = new ConcurrentHashMap<>();

    /** Die Rohdaten des Masters - Grundlage fuer den Snapshot auf der Platte. */
    private final Map<UUID, PlayerData> raw = new ConcurrentHashMap<>();

    /**
     * @param groupName  Gruppe dieses Servers
     * @param serverName Name dieses Servers
     */
    public CloudPermissions(String groupName, String serverName) {
        this.serverContext = PermissionContext.ofServer(groupName, serverName);
    }

    /** Uebernimmt die Daten, die der Master geschickt hat. */
    public void update(PlayerData data) {
        UUID uuid = new UUID(data.getUuid().getMostSignificantBits(),
                data.getUuid().getLeastSignificantBits());

        List<ResolvedPermissions.Candidate> candidates = new ArrayList<>();
        for (PermissionRule rule : data.getRulesList()) {
            candidates.add(new ResolvedPermissions.Candidate(
                    new PermissionEntry(
                            rule.getNode(),
                            rule.getValue(),
                            new PermissionContext(
                                    rule.getGroup().isBlank() ? null : rule.getGroup(),
                                    rule.getServer().isBlank() ? null : rule.getServer()),
                            rule.getExpiresEpochMillis() == 0
                                    ? null : Instant.ofEpochMilli(rule.getExpiresEpochMillis())),
                    tierOf(rule.getTier()),
                    rule.getWeight(),
                    rule.getSource()));
        }

        raw.put(uuid, data);
        players.put(uuid, new Snapshot(
                data.getName(),
                data.hasRank() ? data.getRank() : null,
                data.getLocale().isBlank() ? null : data.getLocale(),
                new ResolvedPermissions(candidates)));

        LOG.debug("Rechte von {} aktualisiert ({} Regeln, Rang {})",
                data.getName(), candidates.size(),
                data.hasRank() ? data.getRank().getId() : "?");
    }

    /**
     * Hat der Spieler dieses Recht auf diesem Server?
     *
     * <p>Ist der Spieler unbekannt - etwa weil der Master beim Join nicht erreichbar war -
     * gilt <b>verboten</b>. Alles andere waere eine Rechteerweiterung durch einen Ausfall.
     */
    public boolean has(UUID uuid, String node) {
        Snapshot snapshot = players.get(uuid);
        if (snapshot == null) {
            return false;
        }
        return snapshot.permissions().has(node, serverContext);
    }

    /** Welche Regel entschieden hat - fuer Debug-Ausgaben im Spiel. */
    public Optional<ResolvedPermissions.Candidate> explain(UUID uuid, String node) {
        Snapshot snapshot = players.get(uuid);
        return snapshot == null
                ? Optional.empty()
                : snapshot.permissions().decide(node, serverContext);
    }

    /** Aufzaehlbare Rechte - fuer die Permission-Bridges der Plattformen. */
    public List<String> effectiveNodes(UUID uuid) {
        Snapshot snapshot = players.get(uuid);
        return snapshot == null
                ? List.of()
                : snapshot.permissions().effectiveNodes(serverContext);
    }

    public Optional<RankData> rankOf(UUID uuid) {
        return Optional.ofNullable(players.get(uuid)).map(Snapshot::rank);
    }

    public Optional<String> localeOf(UUID uuid) {
        return Optional.ofNullable(players.get(uuid)).map(Snapshot::locale);
    }

    public boolean knows(UUID uuid) {
        return players.containsKey(uuid);
    }

    public void forget(UUID uuid) {
        players.remove(uuid);
        raw.remove(uuid);
    }

    /** Nach einer Rang-Aenderung: alles verwerfen, damit neu geladen wird. */
    public void forgetAll() {
        players.clear();
        raw.clear();
    }

    public java.util.Set<UUID> knownPlayers() {
        return java.util.Set.copyOf(players.keySet());
    }

    private static ResolvedPermissions.Tier tierOf(String name) {
        try {
            return ResolvedPermissions.Tier.valueOf(name);
        } catch (IllegalArgumentException exception) {
            // Unbekannte Ebene aus einer neueren Master-Version: als geerbt behandeln,
            // das ist die schwaechste Einordnung und damit die sichere.
            return ResolvedPermissions.Tier.INHERITED_RANK;
        }
    }

    private record Snapshot(String name, RankData rank, String locale,
                            ResolvedPermissions permissions) {
    }

    // ---------------------------------------------------------------- Snapshot

    /**
     * Schreibt den aktuellen Stand auf die Platte (PLAN.md Abschnitt 7).
     *
     * <p>Serialisiert werden die Protobuf-Rohdaten, nicht die aufgeloesten Objekte: Dasselbe
     * Format wie auf der Leitung, also kein zweiter Serializer und keine zweite Fehlerquelle.
     *
     * <p>Erst in eine Teil-Datei, dann umbenennen - ein Absturz mitten im Schreiben darf
     * keinen halben Snapshot hinterlassen, der beim Start fuer vollstaendig gehalten wird.
     */
    public void saveSnapshot(java.nio.file.Path file) throws java.io.IOException {
        PlayerDataList.Builder list = PlayerDataList.newBuilder();
        raw.values().forEach(list::addPlayers);

        java.nio.file.Path parent = file.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Path temporary = file.resolveSibling(file.getFileName() + ".part");
        try (var out = java.nio.file.Files.newOutputStream(temporary)) {
            list.build().writeTo(out);
        }
        java.nio.file.Files.move(temporary, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LOG.debug("Snapshot mit {} Spielern geschrieben", list.getPlayersCount());
    }

    /**
     * Laedt einen Snapshot von der Platte.
     *
     * <p>Nur ein <b>warmer Start</b>: Sobald der Master verbunden ist, werden die Daten
     * ohnehin frisch geholt. Der Snapshot verhindert die Luecke zwischen Proxy-Start und
     * erster Master-Antwort, in der niemand einen Rang haette.
     *
     * @param maxAge aelterer Snapshot wird verworfen, damit keine veralteten Rechte gelten
     * @return Anzahl geladener Spieler
     */
    public int loadSnapshot(java.nio.file.Path file, java.time.Duration maxAge) {
        if (java.nio.file.Files.notExists(file)) {
            return 0;
        }
        try {
            java.time.Instant written = java.nio.file.Files.getLastModifiedTime(file).toInstant();
            if (written.isBefore(java.time.Instant.now().minus(maxAge))) {
                LOG.info("Snapshot ist aelter als {} - wird verworfen, damit keine veralteten "
                         + "Rechte gelten", maxAge);
                java.nio.file.Files.deleteIfExists(file);
                return 0;
            }
            try (var in = java.nio.file.Files.newInputStream(file)) {
                PlayerDataList list = PlayerDataList.parseFrom(in);
                list.getPlayersList().forEach(this::update);
                LOG.info("Snapshot geladen: {} Spieler", list.getPlayersCount());
                return list.getPlayersCount();
            }
        } catch (java.io.IOException | RuntimeException exception) {
            // Ein unlesbarer Snapshot ist kein Grund, den Proxy nicht zu starten.
            LOG.warn("Snapshot {} nicht lesbar - wird ignoriert: {}",
                    file.getFileName(), exception.getMessage());
            return 0;
        }
    }
}
