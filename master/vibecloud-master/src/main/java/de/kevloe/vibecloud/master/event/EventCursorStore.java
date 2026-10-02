package de.kevloe.vibecloud.master.event;

import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplizierung des Outbox-Replays (PLAN.md Abschnitt 7).
 *
 * <p>Je Herkunft wird nur die hoechste verarbeitete {@code seq} gespeichert - ein
 * Hochwassermarke statt einer Liste aller gesehenen Ereignisse. Das genuegt, weil der
 * Wrapper in seq-Reihenfolge nachspielt.
 *
 * <p>Warum das noetig ist: Eine abgerissene Verbindung sagt nie sauber, was noch angekommen
 * ist. Der Wrapper muss also notfalls doppelt senden duerfen, und ein doppelter Replay
 * muss harmlos bleiben.
 */
public final class EventCursorStore {

    private static final Logger LOG = LoggerFactory.getLogger(EventCursorStore.class);

    private final Database database;

    /** Spiegel der Datenbankwerte, damit nicht jedes Ereignis eine Abfrage kostet. */
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    public EventCursorStore(Database database) {
        this.database = database;
    }

    /** Hoechste bereits verarbeitete seq dieser Herkunft, 0 wenn unbekannt. */
    public long lastSeq(String origin) {
        return cache.computeIfAbsent(origin, this::loadFromDatabase);
    }

    /**
     * Setzt den Cursor nach vorn. Rueckschritte werden ignoriert: Ein spaeter
     * eintreffendes aelteres Ereignis darf die Marke nicht senken.
     *
     * @return {@code true} wenn der Cursor tatsaechlich bewegt wurde
     */
    public boolean advanceTo(String origin, long seq) {
        long current = lastSeq(origin);
        if (seq <= current) {
            return false;
        }
        String sql = """
                INSERT INTO event_cursors (origin, last_seq, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (origin) DO UPDATE
                    SET last_seq = GREATEST(event_cursors.last_seq, EXCLUDED.last_seq),
                        updated_at = now()
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, origin);
            statement.setLong(2, seq);
            statement.executeUpdate();
            cache.put(origin, seq);
            return true;
        } catch (SQLException exception) {
            LOG.error("Cursor fuer {} konnte nicht auf {} gesetzt werden", origin, seq, exception);
            return false;
        }
    }

    /**
     * Prueft, ob ein Ereignis schon verarbeitet wurde.
     *
     * @return {@code true} wenn es uebersprungen werden soll
     */
    public boolean isDuplicate(String origin, long seq) {
        return seq > 0 && seq <= lastSeq(origin);
    }

    private long loadFromDatabase(String origin) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_seq FROM event_cursors WHERE origin = ?")) {
            statement.setString(1, origin);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException exception) {
            LOG.error("Cursor fuer {} nicht lesbar - es wird von 0 ausgegangen", origin, exception);
            return 0L;
        }
    }
}
