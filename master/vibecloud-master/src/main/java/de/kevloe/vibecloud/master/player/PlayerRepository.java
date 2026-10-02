package de.kevloe.vibecloud.master.player;

import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.permission.RankRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Lesen und Schreiben von {@code players} und den zugehoerigen Tabellen. */
public final class PlayerRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerRepository.class);

    private static final String COLUMNS = """
            uuid, name, name_lower, platform, xuid, first_login, last_login, last_server,
            playtime_seconds, locale, rank_id, rank_expires_at, rank_fallback_id
            """;

    private final Database database;

    public PlayerRepository(Database database) {
        this.database = database;
    }

    // ---------------------------------------------------------------- Lesen

    public Optional<PlayerRecord> find(UUID uuid) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM players WHERE uuid = ?")) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.error("Spieler {} konnte nicht geladen werden", uuid, exception);
            return Optional.empty();
        }
    }

    /** Suche ohne Beachtung der Gross-/Kleinschreibung. */
    public Optional<PlayerRecord> findByName(String name) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM players WHERE name_lower = ?")) {
            statement.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.error("Spieler {} konnte nicht gesucht werden", name, exception);
            return Optional.empty();
        }
    }

    public List<PermissionEntry> permissionsOf(UUID uuid) {
        String sql = """
                SELECT permission, value, context, expires_at
                FROM player_permissions
                WHERE uuid = ? AND (expires_at IS NULL OR expires_at > now())
                """;
        List<PermissionEntry> entries = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(RankRepository.readEntry(result));
                }
            }
        } catch (SQLException exception) {
            LOG.error("Rechte von {} konnten nicht geladen werden", uuid, exception);
        }
        return entries;
    }

    /** Spieler mit abgelaufenem Rang - fuer den Minuten-Scheduler. */
    public List<PlayerRecord> findWithExpiredRank() {
        String sql = "SELECT " + COLUMNS + " FROM players "
                     + "WHERE rank_expires_at IS NOT NULL AND rank_expires_at <= now()";
        List<PlayerRecord> players = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                players.add(read(result));
            }
        } catch (SQLException exception) {
            LOG.error("Abgelaufene Raenge konnten nicht ermittelt werden", exception);
        }
        return players;
    }

    /** Spieler, die einen bestimmten Rang tragen - fuer gezielte Cache-Invalidierung. */
    public List<UUID> findByRank(String rankId) {
        List<UUID> uuids = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT uuid FROM players WHERE rank_id = ?")) {
            statement.setString(1, rankId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    uuids.add(result.getObject("uuid", UUID.class));
                }
            }
        } catch (SQLException exception) {
            LOG.error("Spieler mit Rang {} nicht ermittelbar", rankId, exception);
        }
        return uuids;
    }

    // ---------------------------------------------------------------- Schreiben

    /**
     * Legt einen Spieler an oder aktualisiert ihn beim Login.
     *
     * <p>Ein Namenswechsel wird in {@code player_names} mitgeschrieben - bei einer
     * Strafen-Recherche will man wissen, wie jemand frueher hiess.
     *
     * @param defaultRank Rang fuer neue Spieler
     * @return der Datensatz nach dem Login
     */
    public PlayerRecord recordLogin(UUID uuid, String name, String platform, String xuid,
                                    String ip, String defaultRank) {
        Optional<PlayerRecord> existing = find(uuid);

        if (existing.isEmpty()) {
            insert(uuid, name, platform, xuid, defaultRank);
            recordName(uuid, name);
        } else {
            if (!existing.get().name().equals(name)) {
                LOG.info("{} heisst jetzt {} (war {})", uuid, name, existing.get().name());
                recordName(uuid, name);
            }
            touchLogin(uuid, name);
        }
        recordConnection(uuid, ip);
        return find(uuid).orElseThrow();
    }

    private void insert(UUID uuid, String name, String platform, String xuid,
                        String defaultRank) {
        String sql = """
                INSERT INTO players (uuid, name, name_lower, platform, xuid, rank_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, name);
            statement.setString(3, name.toLowerCase(Locale.ROOT));
            statement.setString(4, platform);
            statement.setString(5, xuid);
            statement.setString(6, defaultRank);
            statement.executeUpdate();
            LOG.info("Neuer Spieler: {} ({}, Rang {})", name, platform, defaultRank);
        } catch (SQLException exception) {
            throw new IllegalStateException("Spieler konnte nicht angelegt werden", exception);
        }
    }

    private void touchLogin(UUID uuid, String name) {
        String sql = "UPDATE players SET name = ?, name_lower = ?, last_login = now() "
                     + "WHERE uuid = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, name.toLowerCase(Locale.ROOT));
            statement.setObject(3, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Login von {} nicht gespeichert", name, exception);
        }
    }

    private void recordName(UUID uuid, String name) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO player_names (uuid, name) VALUES (?, ?)")) {
            statement.setObject(1, uuid);
            statement.setString(2, name);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.debug("Name-Historie fuer {} nicht geschrieben", uuid, exception);
        }
    }

    /** IPs werden nur gehasht gespeichert (PLAN.md Abschnitt 13). */
    private void recordConnection(UUID uuid, String ip) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO player_connections (uuid, ip_hash) VALUES (?, ?)")) {
            statement.setObject(1, uuid);
            statement.setString(2, hashIp(ip));
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.debug("Verbindung von {} nicht protokolliert", uuid, exception);
        }
    }

    public void recordLogout(UUID uuid, String lastServer, long sessionSeconds) {
        String sql = """
                UPDATE players
                SET playtime_seconds = playtime_seconds + ?, last_server = ?
                WHERE uuid = ?
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Math.max(0, sessionSeconds));
            statement.setString(2, lastServer);
            statement.setObject(3, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Logout von {} nicht gespeichert", uuid, exception);
        }
        closeOpenConnection(uuid);
    }

    private void closeOpenConnection(UUID uuid) {
        String sql = """
                UPDATE player_connections SET disconnected_at = now()
                WHERE id = (SELECT id FROM player_connections
                            WHERE uuid = ? AND disconnected_at IS NULL
                            ORDER BY connected_at DESC LIMIT 1)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.debug("Verbindungsende fuer {} nicht gesetzt", uuid, exception);
        }
    }

    /**
     * Setzt den Rang. Genau einer pro Spieler (PLAN.md Abschnitt 9).
     *
     * @param expires  wann er ablaeuft, {@code null} = permanent
     * @param fallback Rang nach Ablauf, {@code null} = Default-Rang
     */
    public void setRank(UUID uuid, String rankId, Instant expires, String fallback,
                        String actor, String reason) {
        Optional<PlayerRecord> before = find(uuid);

        String sql = """
                UPDATE players
                SET rank_id = ?, rank_expires_at = ?, rank_fallback_id = ?,
                    rank_granted_by = ?, rank_granted_at = now()
                WHERE uuid = ?
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rankId);
            statement.setTimestamp(2, expires == null ? null : Timestamp.from(expires));
            statement.setString(3, fallback);
            statement.setString(4, actor);
            statement.setObject(5, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Rang konnte nicht gesetzt werden: "
                                            + exception.getMessage(), exception);
        }

        // Bei einem Einzelrang ueberschreibt jede Aenderung den Vorgaenger - ohne Historie
        // waere nicht mehr nachvollziehbar, wer wann was gesetzt hat.
        recordRankChange(uuid, before.map(PlayerRecord::rankId).orElse(null), rankId,
                actor, reason, expires);
    }

    private void recordRankChange(UUID uuid, String oldRank, String newRank, String actor,
                                  String reason, Instant expires) {
        String sql = """
                INSERT INTO player_rank_history (uuid, old_rank, new_rank, actor, reason,
                                                 expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, oldRank);
            statement.setString(3, newRank);
            statement.setString(4, actor);
            statement.setString(5, reason);
            statement.setTimestamp(6, expires == null ? null : Timestamp.from(expires));
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Rang-Historie fuer {} nicht geschrieben", uuid, exception);
        }
    }

    public List<RankChange> rankHistory(UUID uuid, int limit) {
        String sql = """
                SELECT old_rank, new_rank, actor, reason, created_at
                FROM player_rank_history WHERE uuid = ?
                ORDER BY created_at DESC LIMIT ?
                """;
        List<RankChange> history = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    history.add(new RankChange(
                            result.getString("old_rank"),
                            result.getString("new_rank"),
                            result.getString("actor"),
                            result.getString("reason"),
                            result.getTimestamp("created_at").toInstant()));
                }
            }
        } catch (SQLException exception) {
            LOG.error("Rang-Historie von {} nicht lesbar", uuid, exception);
        }
        return history;
    }

    public void setLocale(UUID uuid, String locale) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE players SET locale = ? WHERE uuid = ?")) {
            statement.setString(1, locale);
            statement.setObject(2, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Sprache von {} nicht gespeichert", uuid, exception);
        }
    }

    public void addPermission(UUID uuid, PermissionEntry entry, String actor) {
        String sql = """
                INSERT INTO player_permissions (uuid, permission, value, context,
                                                expires_at, granted_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (uuid, permission, context) DO UPDATE
                    SET value = EXCLUDED.value, expires_at = EXCLUDED.expires_at,
                        granted_by = EXCLUDED.granted_by, granted_at = now()
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, entry.node());
            statement.setBoolean(3, entry.value());
            statement.setString(4, RankRepository.Contexts.toJson(entry.context()));
            statement.setTimestamp(5, entry.expires() == null
                    ? null : Timestamp.from(entry.expires()));
            statement.setString(6, actor);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Recht konnte nicht gespeichert werden: "
                                            + exception.getMessage(), exception);
        }
    }

    public boolean removePermission(UUID uuid, String node, PermissionContext context) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM player_permissions WHERE uuid = ? AND permission = ? "
                     + "AND context = ?::jsonb")) {
            statement.setObject(1, uuid);
            statement.setString(2, node.toLowerCase(Locale.ROOT));
            statement.setString(3, RankRepository.Contexts.toJson(context));
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Recht konnte nicht entfernt werden", exception);
        }
    }

    // ---------------------------------------------------------------- Abbildung

    private static PlayerRecord read(ResultSet result) throws SQLException {
        Timestamp expires = result.getTimestamp("rank_expires_at");
        return new PlayerRecord(
                result.getObject("uuid", UUID.class),
                result.getString("name"),
                result.getString("platform"),
                result.getString("xuid"),
                result.getTimestamp("first_login").toInstant(),
                result.getTimestamp("last_login").toInstant(),
                result.getString("last_server"),
                result.getLong("playtime_seconds"),
                result.getString("locale"),
                result.getString("rank_id"),
                expires == null ? null : expires.toInstant(),
                result.getString("rank_fallback_id"));
    }

    /** Wie viele Spieler die Cloud ueberhaupt kennt. */
    public int countAll() {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM players");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException exception) {
            LOG.error("Spielerzahl nicht lesbar", exception);
            return 0;
        }
    }

    /**
     * Namen, die mit {@code prefix} anfangen - zuletzt gesehene zuerst.
     *
     * <p>Fuer die Vervollstaendigung von {@code /ban}, {@code /rank set} und Aehnlichem.
     * Absichtlich aus der Datenbank und nicht aus der Liste der Online-Spieler: Gebannt
     * wird meist jemand, der gerade nicht da ist.
     */
    public List<String> suggestNames(String prefix, int limit) {
        // LIKE-Sonderzeichen entschaerfen, sonst waere "%" eine Suche nach allem.
        // Escape-Zeichen ist "!" und nicht der Backslash: Der muesste in Java, im
        // Text-Block und in SQL je eigen verdoppelt werden - drei Ebenen, drei Fehler.
        String pattern = prefix.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";

        String sql = """
                SELECT name FROM players WHERE name_lower LIKE ? ESCAPE '!'
                ORDER BY last_login DESC LIMIT ?
                """;
        List<String> names = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        } catch (SQLException exception) {
            // Eine fehlende Vervollstaendigung ist aergerlich, aber kein Grund,
            // den Befehl scheitern zu lassen.
            LOG.debug("Namensvorschlaege fuer '{}' nicht lesbar", prefix, exception);
        }
        return names;
    }

    /**
     * Der Hash der zuletzt benutzten IP.
     *
     * <p>Fuer IP-Bans: Ein Modul bekommt nur den Hash, nie die Adresse selbst - damit kann
     * es spaetere Logins wiedererkennen, aber niemanden lokalisieren (PLAN.md Abschnitt 13).
     */
    public Optional<String> lastIpHash(UUID uuid) {
        String sql = """
                SELECT ip_hash FROM player_connections
                WHERE uuid = ? AND ip_hash IS NOT NULL
                ORDER BY connected_at DESC LIMIT 1
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.error("Letzte IP von {} nicht lesbar", uuid, exception);
            return Optional.empty();
        }
    }

    /**
     * IP-Hash aus {@link de.kevloe.vibecloud.api.Hashing}.
     *
     * <p>Bewusst keine eigene Umsetzung: Ein Ban-Modul muss denselben Wert berechnen, um
     * IP-Bans pruefen zu koennen.
     */
    static String hashIp(String ip) {
        return de.kevloe.vibecloud.api.Hashing.ipHash(ip);
    }

    /** Ein Spieler. */
    public record PlayerRecord(
            UUID uuid,
            String name,
            String platform,
            String xuid,
            Instant firstLogin,
            Instant lastLogin,
            String lastServer,
            long playtimeSeconds,
            String locale,
            String rankId,
            Instant rankExpiresAt,
            String rankFallbackId) {

        public boolean hasExpiredRank() {
            return rankExpiresAt != null && Instant.now().isAfter(rankExpiresAt);
        }
    }

    public record RankChange(String oldRank, String newRank, String actor, String reason,
                             Instant at) {
    }
}
