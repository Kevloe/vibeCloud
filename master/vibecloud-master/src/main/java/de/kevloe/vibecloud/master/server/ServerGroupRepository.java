package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Lesen und Schreiben der Tabelle {@code server_groups}. */
public final class ServerGroupRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ServerGroupRepository.class);

    private static final String COLUMNS = """
            name, platform, static_group, min_online, max_online, max_players, memory_mb,
            jvm_flags, allowed_nodes, start_percent, idle_timeout, name_pattern, template,
            mc_version, jar_source, fallback, join_priority, maintenance, priority
            """;

    private final Database database;

    /**
     * Prueft Version und Jar-Quelle einer Gruppe, bevor sie gespeichert wird.
     *
     * <p>Hier und nicht in Konsole und Schnittstelle: Beide Wege laufen durch
     * {@link #create} und {@link #updateField}. Zwei Pruefungen waeren zwei Meinungen
     * darueber, welche Versionen es gibt.
     */
    public interface VersionCheck {

        /** @return Fehlermeldung, oder leer wenn die Gruppe so gespeichert werden darf */
        Optional<String> problemWith(ServerGroup group);

        /** Nach dem Speichern - etwa um das Jar schon herunterzuladen. */
        void accepted(ServerGroup group);
    }

    private volatile VersionCheck versionCheck = new VersionCheck() {
        @Override
        public Optional<String> problemWith(ServerGroup group) {
            return Optional.empty();
        }

        @Override
        public void accepted(ServerGroup group) {
        }
    };

    public ServerGroupRepository(Database database) {
        this.database = database;
    }

    public void setVersionCheck(VersionCheck check) {
        this.versionCheck = check;
    }

    /**
     * @throws IllegalArgumentException wenn die Version nicht passt - dann ist nichts
     *                                  angelegt
     * @throws IllegalStateException    bei einem Datenbankfehler
     */
    public void create(ServerGroup group) {
        Optional<String> problem = versionCheck.problemWith(group);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(problem.get());
        }
        String sql = """
                INSERT INTO server_groups (
                    name, platform, static_group, min_online, max_online, max_players,
                    memory_mb, jvm_flags, allowed_nodes, start_percent, idle_timeout,
                    name_pattern, template, mc_version, jar_source, fallback,
                    join_priority, maintenance, priority)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, group.name());
            statement.setString(i++, group.platform().name());
            statement.setBoolean(i++, group.staticGroup());
            statement.setInt(i++, group.minOnline());
            statement.setInt(i++, group.maxOnline());
            statement.setInt(i++, group.maxPlayers());
            statement.setInt(i++, group.memoryMb());
            statement.setString(i++, toArrayLiteral(group.jvmFlags()));
            statement.setString(i++, toArrayLiteral(group.allowedNodes()));
            statement.setInt(i++, group.startPercent());
            statement.setInt(i++, group.idleTimeout());
            statement.setString(i++, group.namePattern());
            statement.setString(i++, group.template());
            statement.setString(i++, group.mcVersion());
            statement.setString(i++, group.jarSource());
            statement.setBoolean(i++, group.fallback());
            statement.setInt(i++, group.joinPriority());
            statement.setBoolean(i++, group.maintenance());
            statement.setInt(i, group.priority());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Gruppe " + group.name() + " konnte nicht angelegt werden: "
                    + exception.getMessage(), exception);
        }
        versionCheck.accepted(group);
    }

    public Optional<ServerGroup> find(String name) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM server_groups WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.error("Gruppe {} konnte nicht geladen werden", name, exception);
            return Optional.empty();
        }
    }

    public List<ServerGroup> findAll() {
        List<ServerGroup> groups = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM server_groups ORDER BY priority DESC, name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                groups.add(read(result));
            }
        } catch (SQLException exception) {
            LOG.error("Gruppenliste konnte nicht geladen werden", exception);
        }
        return groups;
    }

    public boolean delete(String name) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM server_groups WHERE name = ?")) {
            statement.setString(1, name);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Gruppe konnte nicht geloescht werden", exception);
        }
    }

    /**
     * Die Felder, die {@link #updateField} annimmt.
     *
     * <p>Hier statt in der Vervollstaendigung, damit beides nicht auseinanderlaeuft: Was
     * vorgeschlagen wird, muss auch gesetzt werden koennen.
     */
    public static List<String> editableFields() {
        return List.of("min_online", "max_online", "max_players", "memory", "start_percent",
                "idle_timeout", "join_priority", "priority", "mc_version", "jar_source",
                "template", "name_pattern", "fallback", "maintenance");
    }

    /** Werte, die zu einem Feld passen - leer, wenn es freier Text ist. */
    public static List<String> valuesFor(String field) {
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "fallback", "maintenance" -> List.of("true", "false");
            // Die echte Liste haengt an der Plattform und kommt aus dem VersionCatalog -
            // das hier ist nur, was immer geht.
            case "mcversion", "mc_version" -> List.of("latest");
            case "jarsource", "jar_source" -> List.of("paper", "velocity", "template");
            case "memory", "memory_mb" -> List.of("512", "1024", "2048", "4096");
            case "min_online", "minonline", "max_online", "maxonline" ->
                    List.of("0", "1", "2", "3");
            default -> List.of();
        };
    }

    /**
     * Der aktuelle Wert eines Feldes, als Text - genau so, wie {@link #updateField} ihn
     * wieder annimmt.
     *
     * <p>Steht absichtlich direkt neben {@code updateField}: Wer ein Feld anzeigen kann,
     * muss es auch setzen koennen. Stuende das Lesen woanders, zeigte ein Formular
     * irgendwann ein Feld, das beim Speichern abgelehnt wird.
     */
    public static String valueOf(ServerGroup group, String field) {
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "minonline", "min_online" -> String.valueOf(group.minOnline());
            case "maxonline", "max_online" -> String.valueOf(group.maxOnline());
            case "maxplayers", "max_players" -> String.valueOf(group.maxPlayers());
            case "memory", "memory_mb" -> String.valueOf(group.memoryMb());
            case "startpercent", "start_percent" -> String.valueOf(group.startPercent());
            case "idletimeout", "idle_timeout" -> String.valueOf(group.idleTimeout());
            case "joinpriority", "join_priority" -> String.valueOf(group.joinPriority());
            case "priority" -> String.valueOf(group.priority());
            case "mcversion", "mc_version" -> group.mcVersion();
            case "jarsource", "jar_source" -> group.jarSource();
            case "template" -> group.template();
            case "namepattern", "name_pattern" -> group.namePattern();
            case "fallback" -> String.valueOf(group.fallback());
            case "maintenance" -> String.valueOf(group.maintenance());
            default -> throw new IllegalArgumentException("Unbekanntes Feld: " + field);
        };
    }

    /**
     * Setzt ein einzelnes Feld. Absichtlich mit Whitelist statt freiem Spaltennamen -
     * sonst waere das eine SQL-Injection ueber den Konsolen-Befehl.
     *
     * <p><b>Gespeichert wird nur, was sich danach noch lesen laesst.</b> Die Regeln einer
     * Gruppe stehen in {@link ServerGroup} (mindestens 128 MB, {@code max_online} nicht
     * unter {@code min_online}, {@code %id%} im Namensmuster) - und die Datenbank kennt sie
     * nicht. Ohne die Gegenprobe hier nahm sie {@code memory 64} an, danach warf jedes
     * {@code findAll()}, und der Scheduler startete fuer <b>keine</b> Gruppe mehr einen
     * Server. Ein Tippfehler in einem Feld legte die ganze Cloud still.
     *
     * @throws IllegalArgumentException bei einem unbekannten Feld oder einem Wert, mit dem
     *                                  die Gruppe nicht mehr gueltig waere - dann ist
     *                                  nichts geaendert
     */
    public boolean updateField(String group, String field, String value) {
        String column = switch (field.toLowerCase(Locale.ROOT)) {
            case "minonline", "min_online" -> "min_online";
            case "maxonline", "max_online" -> "max_online";
            case "maxplayers", "max_players" -> "max_players";
            case "memory", "memory_mb" -> "memory_mb";
            case "startpercent", "start_percent" -> "start_percent";
            case "idletimeout", "idle_timeout" -> "idle_timeout";
            case "joinpriority", "join_priority" -> "join_priority";
            case "priority" -> "priority";
            case "mcversion", "mc_version" -> "mc_version";
            case "jarsource", "jar_source" -> "jar_source";
            case "template" -> "template";
            case "namepattern", "name_pattern" -> "name_pattern";
            case "fallback" -> "fallback";
            case "maintenance" -> "maintenance";
            default -> null;
        };
        if (column == null) {
            throw new IllegalArgumentException("Unbekanntes Feld: " + field);
        }

        boolean numeric = List.of("min_online", "max_online", "max_players", "memory_mb",
                "start_percent", "idle_timeout", "join_priority", "priority").contains(column);
        boolean bool = List.of("fallback", "maintenance").contains(column);
        boolean version = List.of("mc_version", "jar_source").contains(column);

        String sql = "UPDATE server_groups SET " + column + " = ?"
                     + (numeric ? "::integer" : bool ? "::boolean" : "") + " WHERE name = ?";
        try (Connection connection = database.connection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                statement.setString(2, group);
                if (statement.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }
                // Die Gegenprobe: dieselbe Zeile so lesen, wie es danach jeder tut. Wirft
                // der Konstruktor, wird die Aenderung zurueckgenommen.
                ServerGroup changed = requireReadable(connection, group);
                // Die Version wird an der fertigen Gruppe geprueft: Ob 1.16.5 geht, haengt
                // an Plattform und jar_source, die in anderen Feldern stehen.
                if (version && changed != null) {
                    Optional<String> problem = versionCheck.problemWith(changed);
                    if (problem.isPresent()) {
                        throw new IllegalArgumentException(problem.get());
                    }
                }
                connection.commit();
                if (version && changed != null) {
                    versionCheck.accepted(changed);
                }
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalArgumentException(
                    "Wert '" + value + "' passt nicht zu " + column + ": " + exception.getMessage());
        }
    }

    private static ServerGroup requireReadable(Connection connection, String group)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM server_groups WHERE name = ?")) {
            statement.setString(1, group);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private static ServerGroup read(ResultSet result) throws SQLException {
        return new ServerGroup(
                result.getString("name"),
                ServerPlatformType.valueOf(result.getString("platform")),
                result.getBoolean("static_group"),
                result.getInt("min_online"),
                result.getInt("max_online"),
                result.getInt("max_players"),
                result.getInt("memory_mb"),
                readArray(result, "jvm_flags"),
                readArray(result, "allowed_nodes"),
                result.getInt("start_percent"),
                result.getInt("idle_timeout"),
                result.getString("name_pattern"),
                result.getString("template"),
                result.getString("mc_version"),
                result.getString("jar_source"),
                result.getBoolean("fallback"),
                result.getInt("join_priority"),
                result.getBoolean("maintenance"),
                result.getInt("priority"));
    }

    private static List<String> readArray(ResultSet result, String column) throws SQLException {
        java.sql.Array array = result.getArray(column);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object entry : (Object[]) array.getArray()) {
            if (entry != null) {
                values.add(entry.toString());
            }
        }
        return values;
    }

    private static String toArrayLiteral(List<String> values) {
        if (values.isEmpty()) {
            return "{}";
        }
        // Werte in Anfuehrungszeichen, damit Leerzeichen in JVM-Flags erhalten bleiben.
        List<String> quoted = values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList();
        return "{" + String.join(",", quoted) + "}";
    }
}
