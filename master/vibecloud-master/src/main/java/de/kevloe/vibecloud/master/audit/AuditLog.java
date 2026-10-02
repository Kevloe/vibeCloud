package de.kevloe.vibecloud.master.audit;

import com.google.gson.Gson;
import de.kevloe.vibecloud.master.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jede administrative Aktion mit Akteur (PLAN.md Abschnitt 13).
 *
 * <p>Auch abgelehnte Anmeldungen und Verstoesse gegen die Befugnis-Pruefung landen hier -
 * im Log allein wuerde man sie zu leicht uebersehen.
 *
 * <p>Schreibt asynchron: Ein Audit-Eintrag darf einen Verbindungsaufbau nie verzoegern,
 * und eine kurzzeitig nicht erreichbare Datenbank darf ihn nicht scheitern lassen.
 */
public final class AuditLog implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
    private static final Gson GSON = new Gson();

    private final Database database;
    private final ExecutorService writer;

    public AuditLog(Database database) {
        this.database = database;
        this.writer = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("audit-writer").factory());
    }

    public void record(String actor, String action, String target, Map<String, ?> data) {
        String json = data == null || data.isEmpty() ? null : GSON.toJson(data);
        writer.execute(() -> write(actor, action, target, json));
    }

    public void record(String actor, String action, String target) {
        record(actor, action, target, null);
    }

    private void write(String actor, String action, String target, String json) {
        String sql = "INSERT INTO cloud_audit (actor, action, target, data) "
                     + "VALUES (?, ?, ?, ?::jsonb)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actor);
            statement.setString(2, action);
            statement.setString(3, target);
            statement.setString(4, json);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.error("Audit-Eintrag '{}' von '{}' konnte nicht geschrieben werden",
                    action, actor, exception);
        }
    }

    @Override
    public void close() {
        writer.close();
    }
}
