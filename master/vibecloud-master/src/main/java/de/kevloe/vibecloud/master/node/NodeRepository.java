package de.kevloe.vibecloud.master.node;

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
import java.util.List;
import java.util.Optional;

/** Lesen und Schreiben der Tabelle {@code nodes}. */
public final class NodeRepository {

    private static final Logger LOG = LoggerFactory.getLogger(NodeRepository.class);

    private final Database database;

    public NodeRepository(Database database) {
        this.database = database;
    }

    /**
     * Legt einen Node an und gibt das Token im Klartext zurueck - einmalig,
     * danach existiert nur noch der Hash.
     */
    public String create(String name, long maxMemoryMb, List<String> allowedIps) {
        String token = NodeTokens.generateToken();
        String hash = NodeTokens.hash(name, token);

        String sql = """
                INSERT INTO nodes (name, token_hash, allowed_ips, max_memory_mb)
                VALUES (?, ?, ?::inet[], ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, hash);
            statement.setString(3, toArrayLiteral(allowedIps));
            statement.setLong(4, maxMemoryMb);
            statement.executeUpdate();
            return token;
        } catch (SQLException exception) {
            throw new IllegalStateException("Node " + name + " konnte nicht angelegt werden: "
                                            + exception.getMessage(), exception);
        }
    }

    /** Erzeugt ein neues Token fuer einen bestehenden Node. Das alte gilt danach nicht mehr. */
    public String rotateToken(String name) {
        String token = NodeTokens.generateToken();
        String hash = NodeTokens.hash(name, token);

        String sql = "UPDATE nodes SET token_hash = ?, token_rotated_at = now(), "
                     + "failed_auths = 0 WHERE name = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash);
            statement.setString(2, name);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Node " + name + " existiert nicht");
            }
            return token;
        } catch (SQLException exception) {
            throw new IllegalStateException("Token-Rotation fehlgeschlagen", exception);
        }
    }

    public Optional<NodeRecord> find(String name) {
        String sql = """
                SELECT name, token_hash, allowed_ips, max_memory_mb, enabled,
                       last_seen, failed_auths
                FROM nodes WHERE name = ?
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.error("Node {} konnte nicht geladen werden", name, exception);
            return Optional.empty();
        }
    }

    public List<NodeRecord> findAll() {
        String sql = """
                SELECT name, token_hash, allowed_ips, max_memory_mb, enabled,
                       last_seen, failed_auths
                FROM nodes ORDER BY name
                """;
        List<NodeRecord> nodes = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                nodes.add(read(result));
            }
        } catch (SQLException exception) {
            LOG.error("Node-Liste konnte nicht geladen werden", exception);
        }
        return nodes;
    }

    public boolean delete(String name) {
        try (Connection connection = database.connection();
             PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM nodes WHERE name = ?")) {
            statement.setString(1, name);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Node konnte nicht geloescht werden", exception);
        }
    }

    public void setEnabled(String name, boolean enabled) {
        update("UPDATE nodes SET enabled = ? WHERE name = ?", statement -> {
            statement.setBoolean(1, enabled);
            statement.setString(2, name);
        });
    }

    public void touchLastSeen(String name, long maxMemoryMb) {
        update("UPDATE nodes SET last_seen = now(), max_memory_mb = ?, failed_auths = 0 "
               + "WHERE name = ?", statement -> {
            statement.setLong(1, maxMemoryMb);
            statement.setString(2, name);
        });
    }

    /**
     * Zaehlt einen Fehlversuch und gibt den neuen Stand zurueck.
     * Ab der konfigurierten Grenze wird der Node deaktiviert.
     */
    public int recordFailedAuth(String name) {
        String sql = "UPDATE nodes SET failed_auths = failed_auths + 1 WHERE name = ? "
                     + "RETURNING failed_auths";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            LOG.error("Fehlversuch fuer {} konnte nicht gezaehlt werden", name, exception);
            return 0;
        }
    }

    private void update(String sql, StatementBinder binder) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Aktualisierung fehlgeschlagen: {}", sql, exception);
        }
    }

    private static NodeRecord read(ResultSet result) throws SQLException {
        Timestamp lastSeen = result.getTimestamp("last_seen");
        java.sql.Array array = result.getArray("allowed_ips");
        List<String> allowedIps = new ArrayList<>();
        if (array != null) {
            for (Object entry : (Object[]) array.getArray()) {
                if (entry != null) {
                    allowedIps.add(entry.toString());
                }
            }
        }
        return new NodeRecord(
                result.getString("name"),
                result.getString("token_hash"),
                allowedIps,
                result.getLong("max_memory_mb"),
                result.getBoolean("enabled"),
                lastSeen == null ? null : lastSeen.toInstant(),
                result.getInt("failed_auths"));
    }

    /** PostgreSQL-Array-Literal, z. B. {@code {203.0.113.5,198.51.100.9}}. */
    private static String toArrayLiteral(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    public record NodeRecord(
            String name,
            String tokenHash,
            List<String> allowedIps,
            long maxMemoryMb,
            boolean enabled,
            Instant lastSeen,
            int failedAuths) {
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
