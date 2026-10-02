package de.kevloe.vibecloud.master.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.kevloe.vibecloud.master.config.MasterConfig;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Datenbankzugriff des Masters. <b>Nur der Master</b> spricht mit der Datenbank -
 * Wrapper und Plugins haben keine Zugangsdaten (PLAN.md Abschnitt 3).
 *
 * <p>Backups sind ausdruecklich nicht Aufgabe der Cloud; das loest PostgreSQL-Replikation
 * ausserhalb des Projekts (PLAN.md Abschnitt 17.2).
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /**
     * Beliebige, aber feste Zahl: Alle Master-Instanzen bewerben sich auf dieselbe Sperre.
     * Zwei Master auf einer Datenbank waeren kein offensichtlicher Fehler, sondern ein
     * stiller - beide Scheduler wuerden Server nachstarten (PLAN.md Abschnitt 6).
     */
    private static final long MASTER_LOCK_ID = 7_310_912_001L;

    private final HikariDataSource dataSource;
    private Connection lockConnection;

    public Database(MasterConfig.Database config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:postgresql://%s:%d/%s".formatted(
                config.host, config.port, config.database));
        hikari.setUsername(config.user);
        hikari.setPassword(config.password);
        hikari.setMaximumPoolSize(config.maxPoolSize);
        hikari.setPoolName("vibecloud-db");
        // Alle Zeitangaben in UTC speichern, Umrechnung erst bei der Anzeige
        // (PLAN.md Abschnitt 8).
        hikari.addDataSourceProperty("ApplicationName", "vibeCloud Master");
        hikari.setConnectionInitSql("SET TIME ZONE 'UTC'");

        this.dataSource = new HikariDataSource(hikari);
        LOG.info("Datenbank verbunden: {}:{}/{}", config.host, config.port, config.database);
    }

    /**
     * Versucht die Einzel-Master-Sperre zu belegen.
     *
     * <p>Die Sperre haengt an einer eigenen Verbindung und loest sich bei einem Absturz von
     * allein - ein Neustart ist deshalb nie blockiert.
     *
     * @return {@code true} wenn dieser Prozess der einzige Master ist
     */
    public boolean acquireMasterLock() {
        try {
            lockConnection = dataSource.getConnection();
            try (PreparedStatement statement =
                         lockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, MASTER_LOCK_ID);
                try (ResultSet result = statement.executeQuery()) {
                    boolean acquired = result.next() && result.getBoolean(1);
                    if (!acquired) {
                        lockConnection.close();
                        lockConnection = null;
                    }
                    return acquired;
                }
            }
        } catch (SQLException exception) {
            LOG.error("Einzel-Master-Sperre konnte nicht geprueft werden", exception);
            return false;
        }
    }

    /** Fuehrt die Flyway-Migrationen aus. Module bringen spaeter eigene mit (Abschnitt 10). */
    public void migrate() {
        var result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        if (result.migrationsExecuted == 0) {
            LOG.info("Datenbankschema aktuell (Version {})", result.targetSchemaVersion);
        } else {
            LOG.info("{} Migration(en) ausgefuehrt, Schema jetzt auf Version {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        if (lockConnection != null) {
            try {
                lockConnection.close();
            } catch (SQLException exception) {
                LOG.debug("Sperr-Verbindung konnte nicht geschlossen werden", exception);
            }
        }
        dataSource.close();
    }
}
