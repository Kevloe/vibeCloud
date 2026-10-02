package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Was wann lief. Einzige dauerhafte Spur eines dynamischen Servers, dessen Verzeichnis
 * nach dem Stopp geloescht wird.
 */
public final class ServerHistoryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ServerHistoryRepository.class);

    private final Database database;

    public ServerHistoryRepository(Database database) {
        this.database = database;
    }

    public void recordStart(String serverName, String groupName, String node) {
        String sql = "INSERT INTO server_history (server_name, group_name, node, started_at) "
                     + "VALUES (?, ?, ?, now())";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverName);
            statement.setString(2, groupName);
            statement.setString(3, node);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Start von {} nicht protokolliert", serverName, exception);
        }
    }

    /**
     * Schliesst den offenen Eintrag ab. Trifft bewusst nur den letzten offenen Lauf:
     * Ein Servername kann nach einem Neustart erneut vorkommen.
     */
    public void recordStop(String serverName, int exitCode, String detail) {
        String sql = """
                UPDATE server_history SET stopped_at = now(), exit_code = ?, detail = ?
                WHERE id = (SELECT id FROM server_history
                            WHERE server_name = ? AND stopped_at IS NULL
                            ORDER BY started_at DESC LIMIT 1)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, exitCode);
            statement.setString(2, detail);
            statement.setString(3, serverName);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Stopp von {} nicht protokolliert", serverName, exception);
        }
    }

    public List<Entry> recent(String groupName, int limit) {
        String sql = """
                SELECT server_name, group_name, node, started_at, stopped_at, exit_code, detail
                FROM server_history
                WHERE (? IS NULL OR group_name = ?)
                ORDER BY started_at DESC LIMIT ?
                """;
        List<Entry> entries = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupName);
            statement.setString(2, groupName);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new Entry(
                            result.getString("server_name"),
                            result.getString("group_name"),
                            result.getString("node"),
                            result.getTimestamp("started_at").toInstant(),
                            result.getTimestamp("stopped_at") == null
                                    ? null : result.getTimestamp("stopped_at").toInstant(),
                            result.getObject("exit_code") == null
                                    ? null : result.getInt("exit_code"),
                            result.getString("detail")));
                }
            }
        } catch (SQLException exception) {
            LOG.error("Historie nicht lesbar", exception);
        }
        return entries;
    }

    public record Entry(String serverName, String groupName, String node, Instant startedAt,
                        Instant stoppedAt, Integer exitCode, String detail) {
    }
}
