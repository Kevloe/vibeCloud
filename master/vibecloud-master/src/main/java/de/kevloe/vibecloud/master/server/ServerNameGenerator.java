package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * Vergibt Servernamen nach dem {@code name_pattern} der Gruppe (PLAN.md Abschnitt 6).
 *
 * <p>Bei dynamischen Gruppen werden freie Nummern wiederverwendet, damit die Zahlen nicht
 * endlos wachsen: Nach einem Tag mit viel Auf und Ab waere man sonst bei {@code lobby-4000},
 * und der Name sagt nichts mehr aus.
 */
public final class ServerNameGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ServerNameGenerator.class);

    /** Sicherheitsnetz gegen eine Endlosschleife, falls die Belegung inkonsistent ist. */
    private static final int MAX_ID = 10_000;

    private final Database database;

    public ServerNameGenerator(Database database) {
        this.database = database;
    }

    /**
     * Naechster freier Name der Gruppe.
     *
     * @param takenNames Namen, die aktuell belegt sind (laufende Server und Bindungen)
     */
    public synchronized String nextName(ServerGroup group, String node, Set<String> takenNames) {
        for (int id = 1; id <= MAX_ID; id++) {
            String candidate = format(group, node, id);
            if (!takenNames.contains(candidate)) {
                rememberHighest(group.name(), id);
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Kein freier Servername in Gruppe " + group.name() + " unter " + MAX_ID);
    }

    /** Setzt das Muster ein. {@code %id%} ist garantiert vorhanden (von {@link ServerGroup} geprueft). */
    public static String format(ServerGroup group, String node, int id) {
        String name = group.namePattern()
                .replace("%group%", group.name())
                .replace("%id%", Integer.toString(id))
                .replace("%node%", node == null ? "" : node);
        if (name.length() > ServerGroup.MAX_SERVER_NAME_LENGTH) {
            // Velocity lehnt laengere Namen ab. Beim Anlegen der Gruppe wird das geprueft,
            // hier faellt nur noch ein zu langer Node-Name auf.
            throw new IllegalStateException("Servername zu lang: " + name);
        }
        return name;
    }

    /**
     * Haelt {@code server_counter} nach. Der Wert wird fuer die Namensvergabe nicht mehr
     * gebraucht, ist aber die ehrliche Antwort auf "wie viele Server hatte diese Gruppe
     * schon maximal gleichzeitig".
     */
    private void rememberHighest(String group, int id) {
        String sql = """
                INSERT INTO server_counter (group_name, next_id) VALUES (?, ?)
                ON CONFLICT (group_name) DO UPDATE
                    SET next_id = GREATEST(server_counter.next_id, EXCLUDED.next_id)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, group);
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.debug("server_counter fuer {} nicht aktualisiert", group, exception);
        }
    }

    public int highestUsedId(String group) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT next_id FROM server_counter WHERE group_name = ?")) {
            statement.setString(1, group);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            return 0;
        }
    }
}
