package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.api.Hashing;
import de.kevloe.vibecloud.master.db.Database;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Tokens fuer die REST-Schnittstelle (PLAN.md Abschnitt 12 und 13).
 *
 * <p>Aufbau eines Tokens: {@code <id>.<geheimnis>}. Die Id steht vorn, damit die Pruefung
 * genau eine Zeile liest statt alle Tokens zu vergleichen.
 *
 * <p>Gespeichert wird nur SHA-256 des Geheimnisses. Fuer Passwoerter waere das falsch, hier
 * ist es richtig: Das Geheimnis sind 32 Byte aus {@link SecureRandom}, da hilft kein
 * Durchprobieren. Ein langsames Verfahren wuerde nur jede Anfrage bremsen.
 */
public final class ApiTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(ApiTokenService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ID_BYTES = 6;
    private static final int SECRET_BYTES = 32;

    /** Erlaubt alles - fuer das eigene Dashboard. */
    public static final String SCOPE_ALL = "*";

    private final Database database;

    public ApiTokenService(Database database) {
        this.database = database;
    }

    /**
     * Legt ein Token an.
     *
     * <p>Das Klartext-Token wird nur hier zurueckgegeben und nirgends gespeichert - wie bei
     * den Node-Tokens. Geht es verloren, wird ein neues angelegt.
     *
     * @param scopes was es darf, z. B. {@code servers.read}; {@code *} erlaubt alles
     * @return das Token im Klartext
     */
    public String create(String name, List<String> scopes, String createdBy,
                         Instant expiresAt) {
        String id = random(ID_BYTES);
        String secret = random(SECRET_BYTES);

        String sql = """
                INSERT INTO api_tokens (id, name, token_hash, scopes, created_by, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, name);
            statement.setString(3, Hashing.sha256(secret));
            statement.setArray(4, connection.createArrayOf("text", scopes.toArray()));
            statement.setString(5, createdBy);
            statement.setTimestamp(6, expiresAt == null ? null : Timestamp.from(expiresAt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Token konnte nicht angelegt werden", exception);
        }
        LOG.info("API-Token '{}' angelegt (Id {}, Rechte {})", name, id, scopes);
        return id + "." + secret;
    }

    /**
     * Prueft ein Token aus einer Anfrage.
     *
     * @return das Token, wenn es gueltig und nicht abgelaufen ist
     */
    public Optional<ApiToken> verify(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        int separator = presented.indexOf('.');
        if (separator <= 0 || separator == presented.length() - 1) {
            return Optional.empty();
        }
        String id = presented.substring(0, separator);
        String secret = presented.substring(separator + 1);

        String sql = """
                SELECT id, name, token_hash, scopes, expires_at
                FROM api_tokens WHERE id = ?
                """;
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                // Zeitkonstanter Vergleich: Ein Vergleich, der beim ersten Unterschied
                // abbricht, verraet ueber die Dauer, wie viel schon stimmt.
                if (!MessageDigest.isEqual(
                        Hashing.sha256(secret).getBytes(StandardCharsets.UTF_8),
                        result.getString("token_hash").getBytes(StandardCharsets.UTF_8))) {
                    return Optional.empty();
                }
                Timestamp expires = result.getTimestamp("expires_at");
                if (expires != null && expires.toInstant().isBefore(Instant.now())) {
                    LOG.debug("Token {} ist abgelaufen", id);
                    return Optional.empty();
                }
                List<String> scopes = toList(result.getArray("scopes"));
                touch(id);
                return Optional.of(new ApiToken(id, result.getString("name"), scopes));
            }
        } catch (SQLException exception) {
            // Kein Zugriff bei einem Datenbankfehler - die sichere Richtung.
            LOG.error("Token {} nicht pruefbar", id, exception);
            return Optional.empty();
        }
    }

    /** Alle Tokens, neueste zuerst. Ohne Hash - der gehoert in keine Ausgabe. */
    public List<ApiTokenInfo> all() {
        String sql = """
                SELECT id, name, scopes, created_by, created_at, expires_at, last_used_at
                FROM api_tokens ORDER BY created_at DESC
                """;
        List<ApiTokenInfo> tokens = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Timestamp expires = result.getTimestamp("expires_at");
                Timestamp used = result.getTimestamp("last_used_at");
                tokens.add(new ApiTokenInfo(
                        result.getString("id"),
                        result.getString("name"),
                        toList(result.getArray("scopes")),
                        result.getString("created_by"),
                        result.getTimestamp("created_at").toInstant(),
                        expires == null ? null : expires.toInstant(),
                        used == null ? null : used.toInstant()));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Tokens nicht lesbar", exception);
        }
        return tokens;
    }

    /** Zieht ein Token zurueck. */
    public boolean revoke(String id) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM api_tokens WHERE id = ?")) {
            statement.setString(1, id);
            boolean removed = statement.executeUpdate() > 0;
            if (removed) {
                LOG.info("API-Token {} zurueckgezogen", id);
            }
            return removed;
        } catch (SQLException exception) {
            throw new IllegalStateException("Token nicht loeschbar", exception);
        }
    }

    /**
     * Merkt die letzte Benutzung.
     *
     * <p>Damit man sieht, welches Token noch gebraucht wird, bevor man es zurueckzieht.
     * Ein Fehler dabei darf die Anfrage nicht scheitern lassen - es ist nur Statistik.
     */
    private void touch(String id) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE api_tokens SET last_used_at = now() WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.debug("last_used_at fuer {} nicht gesetzt", id, exception);
        }
    }

    private static List<String> toList(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** Ein geprueftes Token, wie es in der Anfrage steckt. */
    public record ApiToken(String id, String name, List<String> scopes) {

        /** Ob dieses Token das Recht hat. {@code *} deckt alles ab. */
        public boolean allows(String scope) {
            return scopes.contains(SCOPE_ALL) || scopes.contains(scope);
        }
    }

    /** Ein Token fuer die Anzeige - ohne Geheimnis. */
    public record ApiTokenInfo(
            String id,
            String name,
            List<String> scopes,
            String createdBy,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt) {
    }
}
