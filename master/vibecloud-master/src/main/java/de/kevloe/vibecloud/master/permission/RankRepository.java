package de.kevloe.vibecloud.master.permission;

import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.api.permission.PermissionResolver;
import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lesen und Schreiben von {@code ranks}, {@code rank_inheritance}, {@code rank_permissions}. */
public final class RankRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RankRepository.class);

    private final Database database;

    public RankRepository(Database database) {
        this.database = database;
    }

    // ---------------------------------------------------------------- Lesen

    public List<Rank> findAll() {
        String sql = """
                SELECT id, name, display_name, prefix, suffix, color, weight, is_default,
                       chat_format
                FROM ranks ORDER BY weight DESC, name
                """;
        List<Rank> ranks = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ranks.add(read(result));
            }
        } catch (SQLException exception) {
            LOG.error("Raenge konnten nicht geladen werden", exception);
        }
        return ranks;
    }

    public Optional<Rank> find(String id) {
        return findAll().stream().filter(rank -> rank.id().equals(id)).findFirst();
    }

    public Optional<Rank> findDefault() {
        return findAll().stream().filter(Rank::isDefault).findFirst();
    }

    /** Rang-Id -> direkte Elternraenge. */
    public Map<String, List<String>> inheritance() {
        Map<String, List<String>> inheritance = new LinkedHashMap<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT child_id, parent_id FROM rank_inheritance ORDER BY child_id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                inheritance.computeIfAbsent(result.getString("child_id"),
                        key -> new ArrayList<>()).add(result.getString("parent_id"));
            }
        } catch (SQLException exception) {
            LOG.error("Rang-Vererbung konnte nicht geladen werden", exception);
        }
        return inheritance;
    }

    /** Rang-Id -> Regeln. Abgelaufene werden gar nicht geladen. */
    public Map<String, List<PermissionEntry>> permissions() {
        String sql = """
                SELECT rank_id, permission, value, context, expires_at
                FROM rank_permissions
                WHERE expires_at IS NULL OR expires_at > now()
                ORDER BY rank_id
                """;
        Map<String, List<PermissionEntry>> permissions = new HashMap<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                permissions.computeIfAbsent(result.getString("rank_id"),
                        key -> new ArrayList<>()).add(readEntry(result));
            }
        } catch (SQLException exception) {
            LOG.error("Rang-Rechte konnten nicht geladen werden", exception);
        }
        return permissions;
    }

    /** Was der Resolver braucht - Id, Name, weight. */
    public Map<String, PermissionResolver.RankInfo> rankInfos() {
        Map<String, PermissionResolver.RankInfo> infos = new LinkedHashMap<>();
        findAll().forEach(rank -> infos.put(rank.id(),
                new PermissionResolver.RankInfo(rank.id(), rank.name(), rank.weight())));
        return infos;
    }

    // ---------------------------------------------------------------- Schreiben

    public void create(Rank rank) {
        String sql = """
                INSERT INTO ranks (id, name, display_name, prefix, suffix, color, weight,
                                   is_default, chat_format)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rank.id());
            statement.setString(2, rank.name());
            statement.setString(3, rank.displayName());
            statement.setString(4, rank.prefix());
            statement.setString(5, rank.suffix());
            statement.setString(6, rank.color());
            statement.setInt(7, rank.weight());
            statement.setBoolean(8, rank.isDefault());
            statement.setString(9, rank.chatFormat());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Rang " + rank.id() + " konnte nicht angelegt "
                                            + "werden: " + exception.getMessage(), exception);
        }
    }

    public boolean delete(String id) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM ranks WHERE id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            // Der Default-Rang und Raenge, die noch Spieler haben, haengen an
            // Fremdschluesseln - das ist genau die Absicherung, die hier greift.
            throw new IllegalStateException("Rang " + id + " kann nicht geloescht werden: "
                                            + exception.getMessage(), exception);
        }
    }

    /**
     * Die Felder, die {@link #updateField} annimmt - eine Quelle fuer Pruefung und
     * Vervollstaendigung.
     */
    public static java.util.List<String> editableFields() {
        return java.util.List.of("display_name", "prefix", "suffix", "color", "weight",
                "chat_format");
    }

    /**
     * Der aktuelle Wert eines Feldes, als Text - genau so, wie {@link #updateField} ihn
     * wieder annimmt. Nie {@code null}: Ein leeres Feld ist ein leerer Text, sonst
     * stuende in einem Formular "null".
     */
    public static String valueOf(Rank rank, String field) {
        String value = switch (field.toLowerCase(java.util.Locale.ROOT)) {
            case "displayname", "display_name" -> rank.displayName();
            case "prefix" -> rank.prefix();
            case "suffix" -> rank.suffix();
            case "color" -> rank.color();
            case "weight" -> String.valueOf(rank.weight());
            case "chatformat", "chat_format" -> rank.chatFormat();
            default -> throw new IllegalArgumentException("Unbekanntes Feld: " + field);
        };
        return value == null ? "" : value;
    }

    /** Setzt ein einzelnes Feld. Whitelist statt freiem Spaltennamen. */
    public boolean updateField(String id, String field, String value) {
        String column = switch (field.toLowerCase(java.util.Locale.ROOT)) {
            case "displayname", "display_name" -> "display_name";
            case "prefix" -> "prefix";
            case "suffix" -> "suffix";
            case "color" -> "color";
            case "weight" -> "weight";
            case "chatformat", "chat_format" -> "chat_format";
            default -> null;
        };
        if (column == null) {
            throw new IllegalArgumentException("Unbekanntes Feld: " + field);
        }
        String sql = "UPDATE ranks SET " + column + " = ?"
                     + ("weight".equals(column) ? "::integer" : "") + " WHERE id = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Wert '" + value + "' passt nicht zu " + column
                                               + ": " + exception.getMessage());
        }
    }

    /**
     * Legt eine Vererbung an.
     *
     * <p>Der Zyklus-Check passiert <b>vorher</b> im aufrufenden Service: Ein Zyklus zur
     * Laufzeit zu bemerken waere zu spaet (PLAN.md Abschnitt 9).
     */
    public void addInheritance(String childId, String parentId) {
        String sql = "INSERT INTO rank_inheritance (child_id, parent_id) VALUES (?, ?) "
                     + "ON CONFLICT DO NOTHING";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, childId);
            statement.setString(2, parentId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Vererbung konnte nicht gespeichert werden: "
                                            + exception.getMessage(), exception);
        }
    }

    public boolean removeInheritance(String childId, String parentId) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM rank_inheritance WHERE child_id = ? AND parent_id = ?")) {
            statement.setString(1, childId);
            statement.setString(2, parentId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Vererbung konnte nicht entfernt werden", exception);
        }
    }

    public void addPermission(String rankId, PermissionEntry entry, String actor) {
        String sql = """
                INSERT INTO rank_permissions (rank_id, permission, value, context,
                                              expires_at, granted_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (rank_id, permission, context) DO UPDATE
                    SET value = EXCLUDED.value, expires_at = EXCLUDED.expires_at,
                        granted_by = EXCLUDED.granted_by, granted_at = now()
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rankId);
            statement.setString(2, entry.node());
            statement.setBoolean(3, entry.value());
            statement.setString(4, Contexts.toJson(entry.context()));
            statement.setTimestamp(5, entry.expires() == null
                    ? null : Timestamp.from(entry.expires()));
            statement.setString(6, actor);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Recht konnte nicht gespeichert werden: "
                                            + exception.getMessage(), exception);
        }
    }

    public boolean removePermission(String rankId, String node, PermissionContext context) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM rank_permissions WHERE rank_id = ? AND permission = ? "
                     + "AND context = ?::jsonb")) {
            statement.setString(1, rankId);
            statement.setString(2, node.toLowerCase(java.util.Locale.ROOT));
            statement.setString(3, Contexts.toJson(context));
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Recht konnte nicht entfernt werden", exception);
        }
    }

    public List<PermissionEntry> permissionsOf(String rankId) {
        return permissions().getOrDefault(rankId, List.of());
    }

    // ---------------------------------------------------------------- Abbildung

    private static Rank read(ResultSet result) throws SQLException {
        return new Rank(
                result.getString("id"),
                result.getString("name"),
                result.getString("display_name"),
                result.getString("prefix"),
                result.getString("suffix"),
                result.getString("color"),
                result.getInt("weight"),
                result.getBoolean("is_default"),
                result.getString("chat_format"));
    }

    /** Liest eine Regel aus einem ResultSet - gleiches Format in Rang- und Spieler-Tabelle. */
    public static PermissionEntry readEntry(ResultSet result) throws SQLException {
        Timestamp expires = result.getTimestamp("expires_at");
        return new PermissionEntry(
                result.getString("permission"),
                result.getBoolean("value"),
                Contexts.fromJson(result.getString("context")),
                expires == null ? null : expires.toInstant());
    }

    /** Ein Rang. */
    public record Rank(
            String id,
            String name,
            String displayName,
            String prefix,
            String suffix,
            String color,
            int weight,
            boolean isDefault,
            String chatFormat) {

        public static Rank simple(String id, int weight) {
            return new Rank(id, id, id, "", "", "<gray>", weight, false, null);
        }
    }

    /** JSON-Darstellung eines Kontexts. Bewusst klein gehalten statt Gson-Abhaengigkeit. */
    public static final class Contexts {

        public static String toJson(PermissionContext context) {
            if (context.isGlobal()) {
                return "{}";
            }
            StringBuilder json = new StringBuilder("{");
            if (context.group() != null && !context.group().isBlank()) {
                json.append("\"group\":\"").append(context.group()).append('"');
            }
            if (context.server() != null && !context.server().isBlank()) {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append("\"server\":\"").append(context.server()).append('"');
            }
            return json.append('}').toString();
        }

        public static PermissionContext fromJson(String json) {
            if (json == null || json.isBlank() || json.equals("{}")) {
                return PermissionContext.GLOBAL;
            }
            return new PermissionContext(value(json, "group"), value(json, "server"));
        }

        private static String value(String json, String key) {
            String needle = "\"" + key + "\"";
            int start = json.indexOf(needle);
            if (start < 0) {
                return null;
            }
            int colon = json.indexOf(':', start + needle.length());
            int open = json.indexOf('"', colon + 1);
            int close = json.indexOf('"', open + 1);
            return open < 0 || close < 0 ? null : json.substring(open + 1, close);
        }

        private Contexts() {
        }
    }
}
