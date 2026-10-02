package de.kevloe.vibecloud.master.settings;

import com.google.gson.Gson;
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
 * Globale Schalter aus {@code cloud_settings} (PLAN.md Abschnitt 11).
 *
 * <p>Der Wartungsmodus hat zwei Ebenen: global hier, pro Gruppe in
 * {@code server_groups.maintenance}.
 */
public final class CloudSettings {

    private static final Logger LOG = LoggerFactory.getLogger(CloudSettings.class);
    private static final Gson GSON = new Gson();

    private static final String MAINTENANCE = "maintenance";

    private final Database database;

    /** Spiegel der Datenbankwerte - diese Schalter werden bei jedem Login gelesen. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CloudSettings(Database database) {
        this.database = database;
        loadAll();
    }

    private void loadAll() {
        // "#>> '{}'" holt den Wert als Text aus dem JSON. Ohne das kaeme "true" samt seinen
        // Anfuehrungszeichen zurueck, und parseBoolean machte daraus false: Der
        // Wartungsmodus war nach jedem Master-Neustart stillschweigend wieder aus.
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT key, value #>> '{}' AS value FROM cloud_settings");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                cache.put(result.getString("key"), result.getString("value"));
            }
        } catch (SQLException exception) {
            LOG.error("Globale Einstellungen nicht lesbar - es gelten die Vorgaben", exception);
        }
    }

    public boolean isMaintenanceActive() {
        return Boolean.parseBoolean(cache.getOrDefault(MAINTENANCE, "false"));
    }

    public void setMaintenance(boolean active) {
        put(MAINTENANCE, Boolean.toString(active));
    }

    private void put(String key, String value) {
        String sql = """
                INSERT INTO cloud_settings (key, value, updated_at)
                VALUES (?, ?::jsonb, now())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, GSON.toJson(value));
            statement.executeUpdate();
            cache.put(key, value);
        } catch (SQLException exception) {
            throw new IllegalStateException("Einstellung " + key + " nicht speicherbar", exception);
        }
    }
}
