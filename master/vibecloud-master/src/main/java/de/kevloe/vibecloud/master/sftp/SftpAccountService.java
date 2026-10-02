package de.kevloe.vibecloud.master.sftp;

import de.kevloe.vibecloud.api.Hashing;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.server.StaticBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * SFTP-Zugaenge und die Entscheidung ueber eine Anmeldung.
 *
 * <p>Der SFTP-Server laeuft im Wrapper, weil dort die Dateien liegen. Entschieden wird
 * hier: Nur der Master kennt Zugaenge und Rechte, und der Wrapper hat keinen Zugriff auf
 * die Datenbank.
 *
 * <p><b>Wer in welches Verzeichnis darf, sagen die Rechte aus dem Spiel</b> -
 * {@code vibecloud.sftp.<server>}, mit {@code vibecloud.sftp.*} fuer alle. Kein zweites
 * Rechtesystem: Ein Entzug mit {@code perm} oder ein abgelaufener Rang wirkt hier bei der
 * naechsten Anmeldung.
 *
 * <p><b>Das Recht ist kein kleines.</b> Wer Dateien hochladen darf, kann ein Plugin
 * hochladen, und das laeuft beim naechsten Start als Code auf dem Node - mit den Rechten
 * des Wrappers. SFTP-Zugang zu einem Server ist deshalb Vertrauen in der Groessenordnung
 * eines Administrators, nicht eines Bauhelfers.
 *
 * <p><b>Passwoerter erzeugt der Master</b>, niemand waehlt eines. Deshalb genuegt SHA-256
 * wie bei den API-Tokens: {@value #PASSWORD_LENGTH} zufaellige Zeichen lassen sich nicht
 * durchprobieren, ein langsames Verfahren wuerde nur jede Anmeldung bremsen. Fuer ein
 * selbst gewaehltes Passwort waere das falsch.
 */
public final class SftpAccountService {

    private static final Logger LOG = LoggerFactory.getLogger(SftpAccountService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PASSWORD_LENGTH = 24;

    /**
     * Zeichen eines Passworts - ohne 0, O, 1, I und l.
     *
     * <p>Meist wird es kopiert, manchmal abgetippt. Dann sollen keine zwei Zeichen gleich
     * aussehen.
     */
    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** Unter diesem Recht haengen die Server: {@code vibecloud.sftp.survival-1}. */
    public static final String PERMISSION_PREFIX = "vibecloud.sftp.";

    private final Database database;
    private final StaticBindingRepository bindings;
    private final BiPredicate<UUID, String> permissions;
    private final AuditLog audit;

    /**
     * @param permissions ob ein Spieler ein Recht hat - im Betrieb
     *                    {@code PermissionService::has}
     */
    public SftpAccountService(Database database, StaticBindingRepository bindings,
                              BiPredicate<UUID, String> permissions, AuditLog audit) {
        this.database = database;
        this.bindings = bindings;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** Das Recht fuer das Verzeichnis eines Servers. */
    public static String permissionFor(String serverName) {
        return PERMISSION_PREFIX + serverName.toLowerCase(Locale.ROOT);
    }

    /**
     * Unter diesem Recht haengen die Templates: {@code vibecloud.sftp.template.lobby}.
     *
     * <p>Eine eigene Ebene und nicht {@code vibecloud.sftp.<gruppe>}: Ein Template wirkt
     * auf <b>jeden</b> Server der Gruppe auf jedem Node, ein Serververzeichnis auf einen.
     * Das soll sich einzeln vergeben lassen. {@code vibecloud.sftp.*} deckt beides ab.
     */
    public static final String TEMPLATE_PERMISSION_PREFIX = PERMISSION_PREFIX + "template.";

    /** So steht ein Template in Protokoll und "zuletzt angemeldet auf". */
    private static final String TEMPLATE_TARGET = "template:";

    /** Das Recht fuer das Template einer Gruppe. */
    public static String templatePermissionFor(String groupName) {
        return TEMPLATE_PERMISSION_PREFIX + groupName.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- Zugaenge

    /**
     * Legt einen Zugang an.
     *
     * @return das Passwort im Klartext - es wird nur hier zurueckgegeben
     * @throws IllegalStateException wenn der Spieler schon einen Zugang hat
     */
    public String create(UUID uuid, String username, String createdBy) {
        if (find(uuid).isPresent()) {
            throw new IllegalStateException(username + " hat schon einen SFTP-Zugang");
        }
        String password = randomPassword();
        String sql = """
                INSERT INTO sftp_accounts
                    (uuid, username, username_lower, password_hash, created_by)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, username);
            statement.setString(3, username.toLowerCase(Locale.ROOT));
            statement.setString(4, Hashing.sha256(password));
            statement.setString(5, createdBy);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("SFTP-Zugang konnte nicht angelegt werden", exception);
        }
        audit.record(createdBy, "sftp.account_created", username);
        LOG.info("SFTP-Zugang fuer {} angelegt", username);
        return password;
    }

    /**
     * Setzt ein neues Passwort - das alte gilt sofort nicht mehr.
     *
     * @return das neue Passwort, leer wenn es keinen Zugang gibt
     */
    public Optional<String> resetPassword(UUID uuid, String actor) {
        Optional<SftpAccount> account = find(uuid);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        String password = randomPassword();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE sftp_accounts SET password_hash = ? WHERE uuid = ?")) {
            statement.setString(1, Hashing.sha256(password));
            statement.setObject(2, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Passwort konnte nicht gesetzt werden", exception);
        }
        audit.record(actor, "sftp.password_reset", account.get().username());
        return Optional.of(password);
    }

    /**
     * Legt einen Zugang an oder gibt einem vorhandenen ein neues Passwort.
     *
     * <p>Fuer das Dashboard: Dort erzeugt jemand sein <b>eigenes</b> Passwort und soll nicht
     * wissen muessen, ob es schon einen Zugang gibt. Ein Zugang allein oeffnet nichts -
     * wohin er fuehrt, sagen weiter die Rechte.
     */
    public NewPassword createOrReset(UUID uuid, String username, String actor) {
        Optional<String> reset = resetPassword(uuid, actor);
        return reset.map(password -> new NewPassword(find(uuid).orElseThrow().username(),
                        password, false))
                .orElseGet(() -> new NewPassword(username, create(uuid, username, actor), true));
    }

    /** Ob dieser Spieler das Template dieser Gruppe bearbeiten darf. */
    public boolean mayEditTemplate(UUID uuid, String groupName) {
        return permissions.test(uuid, templatePermissionFor(groupName));
    }

    /** Ob dieser Spieler in das Verzeichnis dieses Servers darf. */
    public boolean mayAccess(UUID uuid, String serverName) {
        return permissions.test(uuid, permissionFor(serverName));
    }

    public boolean remove(UUID uuid, String actor) {
        Optional<SftpAccount> account = find(uuid);
        if (account.isEmpty()) {
            return false;
        }
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM sftp_accounts WHERE uuid = ?")) {
            statement.setObject(1, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("SFTP-Zugang nicht loeschbar", exception);
        }
        audit.record(actor, "sftp.account_removed", account.get().username());
        LOG.info("SFTP-Zugang von {} entfernt", account.get().username());
        return true;
    }

    public Optional<SftpAccount> find(UUID uuid) {
        return query("WHERE uuid = ?", statement -> statement.setObject(1, uuid))
                .stream().findFirst().map(Row::account);
    }

    /** Alle Zugaenge, nach Namen. Ohne Hash - der gehoert in keine Ausgabe. */
    public List<SftpAccount> all() {
        return query("ORDER BY username_lower", statement -> { })
                .stream().map(Row::account).toList();
    }

    // ---------------------------------------------------------------- Anmeldung

    /**
     * Entscheidet ueber eine SFTP-Anmeldung, die ein Wrapper meldet.
     *
     * <p>Vier Dinge muessen stimmen, und sie werden in dieser Reihenfolge geprueft: Der
     * Server ist statisch und an <b>den Node gebunden, der fragt</b>; den Zugang gibt es;
     * das Passwort stimmt; der Spieler hat das Recht fuer genau diesen Server.
     *
     * <p>Das Erste ist die Pruefung gegen die Verbindungsidentitaet: Ein Node darf nur
     * ueber seine eigenen Server sprechen. Ohne sie koennte ein uebernommener Node
     * Passwoerter gegen Server durchprobieren, die woanders liegen.
     *
     * @param node der Node, dessen Wrapper fragt - aus der Verbindung, nicht aus der
     *             Nachricht
     */
    public Decision authenticate(String node, String username, String serverName,
                                 String password, String clientIp) {
        Optional<StaticBindingRepository.Binding> binding = bindings.find(serverName);
        if (binding.isEmpty() || !binding.get().node().equals(node)) {
            audit.record("NODE:" + node, "authz.rejected", serverName,
                    Map.of("reason", "SFTP fuer einen Server, der nicht an diesen Node "
                                     + "gebunden ist", "user", username));
            return deny(username, serverName, clientIp, "Node " + node,
                    "kein statischer Server dieses Nodes");
        }

        return decide(username, serverName, permissionFor(serverName), password, clientIp,
                "Node " + node);
    }

    /**
     * Entscheidet ueber eine SFTP-Anmeldung am Master - in das Template einer Gruppe.
     *
     * <p>Dieselbe Pruefung wie fuer ein Serververzeichnis, nur mit einem anderen Recht:
     * {@code vibecloud.sftp.template.<gruppe>}. Ob es die Gruppe gibt, hat der Aufrufer
     * schon geprueft - ohne sie gaebe es kein Verzeichnis, in das es gehen koennte.
     */
    public Decision authenticateTemplate(String username, String groupName, String password,
                                         String clientIp) {
        return decide(username, TEMPLATE_TARGET + groupName, templatePermissionFor(groupName),
                password, clientIp, "Master");
    }

    /**
     * Zugang, Passwort, Recht - fuer beide Arten von Zielen dieselbe Stelle.
     *
     * @param target wohin es geht, fuer Protokoll und Anzeige: ein Servername oder
     *               {@code template:<gruppe>}
     * @param origin wo die Anmeldung ankam: "Node node-a" oder "Master"
     */
    private Decision decide(String username, String target, String permission,
                            String password, String clientIp, String origin) {
        Optional<Row> row = query("WHERE username_lower = ?", statement ->
                statement.setString(1, username.toLowerCase(Locale.ROOT)))
                .stream().findFirst();

        // Auch ohne Zugang wird verglichen - sonst verriete die Dauer der Antwort, welche
        // Namen es gibt.
        String expected = row.map(Row::passwordHash).orElse(Hashing.sha256(""));
        boolean matches = MessageDigest.isEqual(
                Hashing.sha256(password).getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));

        if (row.isEmpty() || !matches) {
            // Dieselbe Auskunft fuer "kein Zugang" und "falsches Passwort".
            return deny(username, target, clientIp, origin, "Zugang oder Passwort falsch");
        }
        SftpAccount account = row.get().account();

        if (!permissions.test(account.uuid(), permission)) {
            return deny(account.username(), target, clientIp, origin,
                    "es fehlt das Recht " + permission);
        }

        touch(account.uuid(), target);
        audit.record(account.username(), "sftp.login", target,
                Map.of("at", origin, "ip", clientIp));
        LOG.info("SFTP: {} ist auf {} angemeldet ({}, von {})",
                account.username(), target, origin, clientIp);
        return new Decision(true, "");
    }

    private Decision deny(String username, String target, String clientIp, String origin,
                          String reason) {
        audit.record(username, "sftp.denied", target,
                Map.of("at", origin, "ip", clientIp, "reason", reason));
        LOG.warn("SFTP: {} auf {} abgelehnt - {} ({}, von {})",
                username, target, reason, origin, clientIp);
        return new Decision(false, reason);
    }

    /** Merkt die letzte Anmeldung. Ein Fehler dabei darf sie nicht scheitern lassen. */
    private void touch(UUID uuid, String serverName) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE sftp_accounts SET last_login_at = now(), last_server = ? "
                     + "WHERE uuid = ?")) {
            statement.setString(1, serverName);
            statement.setObject(2, uuid);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.debug("last_login_at fuer {} nicht gesetzt", uuid, exception);
        }
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private List<Row> query(String where, Binder binder) {
        String sql = """
                SELECT uuid, username, password_hash, created_by, created_at,
                       last_login_at, last_server
                FROM sftp_accounts\s""" + where;
        List<Row> rows = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Timestamp lastLogin = result.getTimestamp("last_login_at");
                    rows.add(new Row(new SftpAccount(
                            result.getObject("uuid", UUID.class),
                            result.getString("username"),
                            result.getString("created_by"),
                            result.getTimestamp("created_at").toInstant(),
                            lastLogin == null ? null : lastLogin.toInstant(),
                            result.getString("last_server")),
                            result.getString("password_hash")));
                }
            }
        } catch (SQLException exception) {
            // Kein Zugriff bei einem Datenbankfehler - die sichere Richtung.
            LOG.error("SFTP-Zugaenge nicht lesbar", exception);
            return List.of();
        }
        return rows;
    }

    private static String randomPassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int index = 0; index < PASSWORD_LENGTH; index++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    /** Ein Zugang samt Hash - der Hash verlaesst diese Klasse nicht. */
    private record Row(SftpAccount account, String passwordHash) {
    }

    /**
     * @param created ob der Zugang dabei neu entstanden ist - sonst galt vorher ein anderes
     *                Passwort, und das ist jetzt ungueltig
     */
    public record NewPassword(String username, String password, boolean created) {
    }

    /** Ein Zugang fuer die Anzeige. */
    public record SftpAccount(UUID uuid, String username, String createdBy,
                              Instant createdAt, Instant lastLoginAt, String lastServer) {
    }

    /**
     * @param detail Grund einer Ablehnung - fuer das Log des Wrappers, nicht fuer den Client
     */
    public record Decision(boolean allowed, String detail) {
    }
}
