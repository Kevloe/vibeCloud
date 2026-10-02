package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.api.event.Subscribe;
import de.kevloe.vibecloud.api.event.events.PlayerPermissionsChangedEvent;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.security.Argon2Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Zugaenge zum Dashboard (PLAN.md Abschnitt 12).
 *
 * <p><b>Keine Registrierung.</b> Ein Account entsteht nur in-game ueber {@code /acp create}
 * und haengt an der Minecraft-UUID. Damit gelten im Dashboard dieselben Rechte wie im
 * Spiel - wer in-game keinen Server stoppen darf, kann es dort auch nicht.
 *
 * <p>Zugang hat nur, wer {@code vibecloud.dashboard.login} besitzt. Faellt dieses Recht
 * weg - durch {@code rank set}, {@code perm remove}, geaenderte Vererbung oder einen
 * abgelaufenen Rang -, wird der Account automatisch geloescht. Der Dienst haengt dafuer am
 * {@link PlayerPermissionsChangedEvent}, das bei jeder dieser Ursachen ausloest.
 */
public final class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    /** Das Recht, ohne das es keinen Zugang gibt. */
    public static final String LOGIN_PERMISSION = "vibecloud.dashboard.login";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Zeichen fuer Start-Passwoerter.
     *
     * <p>Ohne 0, O, 1, I und l: Das Passwort wird im Chat angezeigt und abgetippt - diese
     * Zeichen verwechselt man dabei zuverlaessig. Dieselbe Ueberlegung wie bei den
     * Einspruchs-Kennungen im Punishment-Modul.
     */
    private static final String ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 12;

    /** Nach so vielen Fehlversuchen ist der Account kurz gesperrt (PLAN.md Abschnitt 12). */
    private static final int MAX_FAILED_LOGINS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Database database;
    private final PermissionService permissions;
    private final AuditLog audit;

    public AccountService(Database database, PermissionService permissions, AuditLog audit) {
        this.database = database;
        this.permissions = permissions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- Anlegen und aendern

    /**
     * Legt einen Zugang an und gibt das Start-Passwort zurueck.
     *
     * <p>Das Passwort steht nur in der Antwort - gespeichert wird allein der Hash, und
     * geloggt wird es nirgends.
     *
     * @throws IllegalStateException wenn es schon einen Zugang gibt oder das Recht fehlt
     */
    public String create(UUID uuid, String username, String createdBy) {
        if (!permissions.has(uuid, LOGIN_PERMISSION)) {
            throw new IllegalStateException(username + " hat " + LOGIN_PERMISSION
                                            + " nicht - ohne das Recht gibt es keinen Zugang");
        }
        if (find(uuid).isPresent()) {
            throw new IllegalStateException(username + " hat schon einen Zugang. "
                                            + "Neues Passwort: acp changepw " + username);
        }
        String password = newPassword();
        String sql = """
                INSERT INTO dashboard_accounts
                    (uuid, username, username_lower, password_hash, created_by)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, username);
            statement.setString(3, username.toLowerCase(Locale.ROOT));
            statement.setString(4, Argon2Hash.hash(username.toLowerCase(Locale.ROOT), password));
            statement.setString(5, createdBy);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Zugang konnte nicht angelegt werden", exception);
        }

        // Das Passwort gehoert in keinen Audit-Eintrag - nur die Tatsache.
        audit.record(createdBy, "dashboard.account.created", username,
                Map.of("uuid", uuid.toString()));
        LOG.info("Dashboard-Zugang fuer {} angelegt (von {})", username, createdBy);
        return password;
    }

    /**
     * Setzt ein neues Start-Passwort.
     *
     * <p>Alle laufenden Sitzungen werden ungueltig: Wer das Passwort zurueucksetzt, tut das
     * meist, weil es in falsche Haende geraten ist.
     */
    public String resetPassword(UUID uuid, String actor) {
        Account account = find(uuid).orElseThrow(() ->
                new IllegalStateException("Kein Zugang vorhanden - erst 'acp create'"));

        String password = newPassword();
        String sql = """
                UPDATE dashboard_accounts
                SET password_hash = ?, must_change_password = TRUE,
                    session_version = session_version + 1,
                    failed_logins = 0, locked_until = NULL
                WHERE uuid = ?
                """;
        update(sql, statement -> {
            statement.setString(1, Argon2Hash.hash(account.usernameLower(), password));
            statement.setObject(2, uuid);
        });
        audit.record(actor, "dashboard.account.password_reset", account.username(), null);
        LOG.info("Start-Passwort fuer {} neu gesetzt (von {})", account.username(), actor);
        return password;
    }

    /**
     * Setzt das Passwort des Benutzers selbst.
     *
     * <p>Danach ist {@code must_change_password} aus und alle anderen Sitzungen sind
     * ungueltig - auch die, mit der jemand das Passwort erraten hatte.
     */
    public void changePassword(UUID uuid, String newPassword) {
        Account account = find(uuid).orElseThrow(() ->
                new IllegalStateException("Kein Zugang vorhanden"));

        if (newPassword == null || newPassword.length() < 10) {
            throw new IllegalArgumentException("Das Passwort braucht mindestens 10 Zeichen");
        }
        String sql = """
                UPDATE dashboard_accounts
                SET password_hash = ?, must_change_password = FALSE,
                    session_version = session_version + 1
                WHERE uuid = ?
                """;
        update(sql, statement -> {
            statement.setString(1, Argon2Hash.hash(account.usernameLower(), newPassword));
            statement.setObject(2, uuid);
        });
        audit.record(account.username(), "dashboard.account.password_changed",
                account.username(), null);
        LOG.info("{} hat sein Dashboard-Passwort geaendert", account.username());
    }

    /** Sperrt oder entsperrt einen Zugang, ohne ihn zu loeschen. */
    public boolean setDisabled(UUID uuid, boolean disabled, String actor) {
        Optional<Account> account = find(uuid);
        if (account.isEmpty()) {
            return false;
        }
        update("""
                UPDATE dashboard_accounts
                SET disabled = ?, session_version = session_version + 1
                WHERE uuid = ?
                """, statement -> {
            statement.setBoolean(1, disabled);
            statement.setObject(2, uuid);
        });
        audit.record(actor, disabled ? "dashboard.account.disabled"
                : "dashboard.account.enabled", account.get().username(), null);
        return true;
    }

    /** Loescht einen Zugang. */
    public boolean delete(UUID uuid, String actor, String reason) {
        Optional<Account> account = find(uuid);
        if (account.isEmpty()) {
            return false;
        }
        update("DELETE FROM dashboard_accounts WHERE uuid = ?",
                statement -> statement.setObject(1, uuid));

        audit.record(actor, "dashboard.account.removed", account.get().username(),
                Map.of("reason", reason));
        LOG.info("Dashboard-Zugang von {} geloescht ({})", account.get().username(), reason);
        return true;
    }

    // ---------------------------------------------------------------- Anmelden

    /**
     * Prueft Benutzername und Passwort.
     *
     * <p>Jeder Fehlschlag sieht gleich aus - unbekannter Name, falsches Passwort, fehlendes
     * Recht. Ein Unterschied waere eine Auskunft darueber, welche Zugaenge es gibt.
     */
    public LoginResult login(String username, String password) {
        Optional<Account> found = findByName(username);
        if (found.isEmpty()) {
            // Trotzdem rechnen: Ohne das waere an der Antwortzeit zu erkennen, ob es den
            // Namen gibt.
            Argon2Hash.verify("unbekannt", password, Argon2Hash.hash("unbekannt", "x"));
            return LoginResult.failed();
        }
        Account account = found.get();

        if (account.disabled()) {
            return LoginResult.failed();
        }
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now())) {
            LOG.warn("Login von {} abgelehnt - gesperrt bis {}",
                    account.username(), account.lockedUntil());
            return LoginResult.locked(account.lockedUntil());
        }
        if (!Argon2Hash.verify(account.usernameLower(), password, account.passwordHash())) {
            countFailure(account);
            return LoginResult.failed();
        }
        // Das Recht gilt auch beim Login neu geprueft: Zwischen dem Anlegen und jetzt kann
        // es entzogen worden sein, und das Loeschen haengt an einem Event, das ausfallen
        // kann (etwa bei einem Neustart im falschen Moment).
        if (!permissions.has(account.uuid(), LOGIN_PERMISSION)) {
            LOG.warn("{} hat {} nicht mehr - Zugang wird geloescht",
                    account.username(), LOGIN_PERMISSION);
            delete(account.uuid(), "SYSTEM", "Recht fehlt beim Login");
            return LoginResult.failed();
        }

        update("""
                UPDATE dashboard_accounts
                SET failed_logins = 0, locked_until = NULL, last_login_at = now()
                WHERE uuid = ?
                """, statement -> statement.setObject(1, account.uuid()));

        return LoginResult.success(account);
    }

    private void countFailure(Account account) {
        int failed = account.failedLogins() + 1;
        Instant lockedUntil = failed >= MAX_FAILED_LOGINS
                ? Instant.now().plus(LOCK_DURATION) : null;

        update("UPDATE dashboard_accounts SET failed_logins = ?, locked_until = ? WHERE uuid = ?",
                statement -> {
                    statement.setInt(1, failed);
                    statement.setTimestamp(2,
                            lockedUntil == null ? null : Timestamp.from(lockedUntil));
                    statement.setObject(3, account.uuid());
                });

        if (lockedUntil != null) {
            audit.record("SYSTEM", "dashboard.account.locked", account.username(),
                    Map.of("failedLogins", String.valueOf(failed)));
            LOG.warn("{} nach {} Fehlversuchen gesperrt bis {}",
                    account.username(), failed, lockedUntil);
        }
    }

    // ---------------------------------------------------------------- Rechte-Entzug

    /**
     * Loescht Zugaenge, deren Recht weggefallen ist.
     *
     * <p>Haengt am {@link PlayerPermissionsChangedEvent}: Das loest bei jeder Ursache aus -
     * {@code rank set}, {@code perm remove}, geaenderte Vererbung, abgelaufener Rang.
     *
     * <p>Aendert sich ein Rang, sind potenziell viele Spieler betroffen. Geprueft werden
     * aber nur die, die ueberhaupt einen Zugang haben - das sind eine Handvoll.
     */
    @Subscribe
    public void onPermissionsChanged(PlayerPermissionsChangedEvent event) {
        List<Account> betroffen = event.affectsAllPlayers()
                ? all()
                : find(event.uuid()).map(List::of).orElse(List.of());

        for (Account account : betroffen) {
            if (permissions.has(account.uuid(), LOGIN_PERMISSION)) {
                continue;
            }
            delete(account.uuid(), "SYSTEM", "Recht " + LOGIN_PERMISSION + " entzogen");
        }
    }

    // ---------------------------------------------------------------- Lesen

    public Optional<Account> find(UUID uuid) {
        return query("WHERE uuid = ?", statement -> statement.setObject(1, uuid))
                .stream().findFirst();
    }

    public Optional<Account> findByName(String username) {
        return query("WHERE username_lower = ?", statement ->
                statement.setString(1, username.toLowerCase(Locale.ROOT)))
                .stream().findFirst();
    }

    public List<Account> all() {
        return query("ORDER BY username_lower", statement -> { });
    }

    // ---------------------------------------------------------------- Innereien

    private List<Account> query(String where, StatementFiller filler) {
        String sql = """
                SELECT uuid, username, username_lower, password_hash, must_change_password,
                       session_version, failed_logins, locked_until, disabled,
                       created_by, created_at, last_login_at
                FROM dashboard_accounts
                """ + where;

        List<Account> accounts = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            filler.fill(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    accounts.add(read(result));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Zugaenge nicht lesbar", exception);
        }
        return accounts;
    }

    private void update(String sql, StatementFiller filler) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            filler.fill(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Zugang nicht aenderbar", exception);
        }
    }

    private static Account read(ResultSet result) throws SQLException {
        Timestamp locked = result.getTimestamp("locked_until");
        Timestamp lastLogin = result.getTimestamp("last_login_at");

        return new Account(
                result.getObject("uuid", UUID.class),
                result.getString("username"),
                result.getString("username_lower"),
                result.getString("password_hash"),
                result.getBoolean("must_change_password"),
                result.getInt("session_version"),
                result.getInt("failed_logins"),
                locked == null ? null : locked.toInstant(),
                result.getBoolean("disabled"),
                result.getString("created_by"),
                result.getTimestamp("created_at").toInstant(),
                lastLogin == null ? null : lastLogin.toInstant());
    }

    private static String newPassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }

    @FunctionalInterface
    private interface StatementFiller {

        void fill(PreparedStatement statement) throws SQLException;
    }

    /** Ein Dashboard-Zugang. */
    public record Account(
            UUID uuid,
            String username,
            String usernameLower,
            String passwordHash,
            boolean mustChangePassword,
            int sessionVersion,
            int failedLogins,
            Instant lockedUntil,
            boolean disabled,
            String createdBy,
            Instant createdAt,
            Instant lastLoginAt) {
    }

    /** Ergebnis eines Login-Versuchs. */
    public record LoginResult(Account account, Instant lockedUntil) {

        public static LoginResult success(Account account) {
            return new LoginResult(account, null);
        }

        public static LoginResult failed() {
            return new LoginResult(null, null);
        }

        public static LoginResult locked(Instant until) {
            return new LoginResult(null, until);
        }

        public boolean isSuccess() {
            return account != null;
        }

        public boolean isLocked() {
            return lockedUntil != null;
        }
    }
}
