package de.kevloe.vibecloud.punishment;

import de.kevloe.vibecloud.module.ModuleDatabase;
import de.kevloe.vibecloud.punishment.api.Punishment;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lesen und Schreiben der Tabelle {@code punishment_entries}. */
final class PunishmentRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Zeichen fuer die Einspruchs-Kennung.
     *
     * <p>Ohne 0, O, 1, I und L: Ein Spieler tippt die Kennung aus einem Ban-Bildschirm ab,
     * und diese Zeichen verwechselt man dabei zuverlaessig.
     */
    private static final String APPEAL_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int APPEAL_LENGTH = 8;

    private static final String COLUMNS = """
            id, type, uuid, name, reason, actor, created_at, expires_at,
            revoked_at, revoked_by, appeal_id
            """;

    private final ModuleDatabase database;

    PunishmentRepository(ModuleDatabase database) {
        this.database = database;
    }

    /**
     * Legt eine Strafe an.
     *
     * @param ipHash gehashte IP oder {@code null} - im Klartext wird sie nie gespeichert
     * @return die angelegte Strafe mit Id und Einspruchs-Kennung
     */
    Punishment create(Punishment.Type type, UUID uuid, String name, String ipHash,
                      String reason, String actor, Instant expiresAt) throws SQLException {

        String appealId = newAppealId();
        String sql = """
                INSERT INTO punishment_entries
                    (type, uuid, name, ip_hash, reason, actor, expires_at, appeal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING
                """ + COLUMNS;

        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type.name());
            statement.setObject(2, uuid);
            statement.setString(3, name);
            statement.setString(4, ipHash);
            statement.setString(5, reason);
            statement.setString(6, actor);
            statement.setTimestamp(7, expiresAt == null ? null : Timestamp.from(expiresAt));
            statement.setString(8, appealId);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Strafe wurde nicht angelegt");
                }
                return read(result);
            }
        }
    }

    /**
     * Die aktive Strafe dieser Art, falls es eine gibt.
     *
     * <p>Abgelaufene werden hier schon ausgefiltert - so muss der Aufrufer nicht daran
     * denken, und ein vergessener Ablauf-Check kann niemanden faelschlich sperren.
     */
    Optional<Punishment> findActive(UUID uuid, Punishment.Type type) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
                FROM punishment_entries
                WHERE uuid = ? AND type = ? AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY created_at DESC LIMIT 1
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, type.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    /**
     * Aktiver Bann auf diese IP - auch von einem anderen Konto.
     *
     * <p>Das ist der Zweck eines IP-Banns: Ein neues Konto von derselben Adresse soll
     * ebenfalls nicht hereinkommen.
     */
    Optional<Punishment> findActiveByIp(String ipHash) throws SQLException {
        if (ipHash == null || ipHash.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT " + COLUMNS + """
                FROM punishment_entries
                WHERE ip_hash = ? AND type = 'BAN' AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY created_at DESC LIMIT 1
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ipHash);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    /**
     * Hebt die aktive Strafe auf.
     *
     * <p>Der Eintrag wird nie geloescht, nur als aufgehoben markiert - die Historie soll
     * vollstaendig bleiben, auch wenn jemand entbannt wurde.
     *
     * @return die aufgehobene Strafe, falls es eine gab
     */
    Optional<Punishment> revoke(UUID uuid, Punishment.Type type, String actor, String reason)
            throws SQLException {

        String sql = """
                UPDATE punishment_entries
                SET revoked_at = now(), revoked_by = ?, revoke_reason = ?
                WHERE id = (SELECT id FROM punishment_entries
                            WHERE uuid = ? AND type = ? AND revoked_at IS NULL
                              AND (expires_at IS NULL OR expires_at > now())
                            ORDER BY created_at DESC LIMIT 1)
                RETURNING
                """ + COLUMNS;

        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actor);
            statement.setString(2, reason);
            statement.setObject(3, uuid);
            statement.setString(4, type.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    /** Alle Strafen eines Spielers, neueste zuerst. */
    List<Punishment> history(UUID uuid, int limit) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
                FROM punishment_entries WHERE uuid = ?
                ORDER BY created_at DESC LIMIT ?
                """;
        List<Punishment> history = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    history.add(read(result));
                }
            }
        }
        return history;
    }

    /** Strafe zu einer Einspruchs-Kennung - damit das Team sie nachschlagen kann. */
    Optional<Punishment> findByAppealId(String appealId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM punishment_entries WHERE appeal_id = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, appealId.toUpperCase(java.util.Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    /** Paketsichtbar, damit ein Test die Zeichenauswahl pruefen kann. */
    static String newAppealId() {
        StringBuilder id = new StringBuilder(APPEAL_LENGTH);
        for (int i = 0; i < APPEAL_LENGTH; i++) {
            id.append(APPEAL_ALPHABET.charAt(RANDOM.nextInt(APPEAL_ALPHABET.length())));
        }
        return id.toString();
    }

    private static Punishment read(ResultSet result) throws SQLException {
        Timestamp expires = result.getTimestamp("expires_at");
        Timestamp revoked = result.getTimestamp("revoked_at");

        return new Punishment(
                result.getLong("id"),
                Punishment.Type.valueOf(result.getString("type")),
                result.getObject("uuid", UUID.class),
                result.getString("name"),
                result.getString("reason"),
                result.getString("actor"),
                result.getTimestamp("created_at").toInstant(),
                expires == null ? null : expires.toInstant(),
                revoked == null ? null : revoked.toInstant(),
                result.getString("revoked_by"),
                result.getString("appeal_id"));
    }
}
