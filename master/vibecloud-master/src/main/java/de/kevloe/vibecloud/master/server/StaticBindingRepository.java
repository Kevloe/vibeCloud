package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Node-Bindung statischer Server (PLAN.md Abschnitt 6, Entscheidung 17.1).
 *
 * <p>Ein statischer Server wird beim ersten Start einmalig auf einen freien Node gelegt und
 * bleibt dort. Welt, Plugin-Daten und Logs liegen auf diesem Root.
 *
 * <p><b>Diese Bindung wird nie automatisch geaendert.</b> Faellt der Node aus, bleibt der
 * Server aus - auf einem anderen Node kaeme er mit leerer Welt hoch, und Spieler landen auf
 * einem frischen Survival, waehrend die echte Welt auf dem ausgefallenen Root liegt.
 */
public final class StaticBindingRepository {

    private static final Logger LOG = LoggerFactory.getLogger(StaticBindingRepository.class);

    private final Database database;

    public StaticBindingRepository(Database database) {
        this.database = database;
    }

    public void bind(String serverName, String groupName, String node, int port) {
        String sql = """
                INSERT INTO static_server_bindings (server_name, group_name, node, port)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (server_name) DO NOTHING
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverName);
            statement.setString(2, groupName);
            statement.setString(3, node);
            statement.setInt(4, port);
            if (statement.executeUpdate() > 0) {
                LOG.info("Statischer Server {} ist jetzt dauerhaft an Node {} gebunden "
                         + "(Port {}). Die Welt liegt ab jetzt dort.", serverName, node, port);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Bindung fuer " + serverName
                                            + " konnte nicht gespeichert werden", exception);
        }
    }

    public Optional<Binding> find(String serverName) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT server_name, group_name, node, port FROM static_server_bindings "
                     + "WHERE server_name = ?")) {
            statement.setString(1, serverName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.error("Bindung fuer {} nicht lesbar", serverName, exception);
            return Optional.empty();
        }
    }

    public List<Binding> ofGroup(String groupName) {
        List<Binding> bindings = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT server_name, group_name, node, port FROM static_server_bindings "
                     + "WHERE group_name = ? ORDER BY server_name")) {
            statement.setString(1, groupName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    bindings.add(read(result));
                }
            }
        } catch (SQLException exception) {
            LOG.error("Bindungen der Gruppe {} nicht lesbar", groupName, exception);
        }
        return bindings;
    }

    public List<Binding> all() {
        List<Binding> bindings = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT server_name, group_name, node, port FROM static_server_bindings "
                     + "ORDER BY group_name, server_name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                bindings.add(read(result));
            }
        } catch (SQLException exception) {
            LOG.error("Bindungen nicht lesbar", exception);
        }
        return bindings;
    }

    /**
     * Schreibt die Bindung auf einen anderen Node um. Nur fuer einen bewussten Umzug mit
     * angehaltenem Server - nie automatisch bei einem Ausfall.
     */
    public boolean rebind(String serverName, String node) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE static_server_bindings SET node = ?, bound_at = now() "
                     + "WHERE server_name = ?")) {
            statement.setString(1, node);
            statement.setString(2, serverName);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Bindung konnte nicht geaendert werden", exception);
        }
    }

    public boolean unbind(String serverName) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM static_server_bindings WHERE server_name = ?")) {
            statement.setString(1, serverName);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Bindung konnte nicht entfernt werden", exception);
        }
    }

    private static Binding read(ResultSet result) throws SQLException {
        return new Binding(
                result.getString("server_name"),
                result.getString("group_name"),
                result.getString("node"),
                result.getInt("port"));
    }

    public record Binding(String serverName, String groupName, String node, int port) {
    }
}
