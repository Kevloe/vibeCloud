package de.kevloe.vibecloud.master.security;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Kurzlebige Zugangstoken fuer das Dashboard (PLAN.md Abschnitt 12).
 *
 * <p>HS256 selbst gebaut statt einer Bibliothek: Der Master stellt die Token aus und prueft
 * sie auch selbst - es gibt keinen dritten Beteiligten, mit dem man sich auf ein Format
 * einigen muesste. Gebraucht werden drei Felder und eine Signatur; dafuer eine
 * Abhaengigkeit mit eigenem Versionszyklus aufzunehmen, waere unverhaeltnismaessig.
 *
 * <p><b>Was ein Token traegt:</b> die UUID des Spielers, seinen Namen und die
 * {@code sessionVersion}. Letztere ist der Grund, warum ein Rechte-Entzug binnen Sekunden
 * wirkt: Der Zaehler in der Datenbank wird hochgezaehlt, und jedes Token mit der alten
 * Nummer ist damit ungueltig - ohne Liste gueltiger Token.
 *
 * <p>Der Schluessel liegt in {@code secrets/jwt.key} und wird beim ersten Start erzeugt.
 * Geht er verloren, sind alle Sitzungen ungueltig - mehr passiert nicht.
 */
public final class Jwt {

    private static final Logger LOG = LoggerFactory.getLogger(Jwt.class);
    private static final Gson GSON = new Gson();

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** {"alg":"HS256","typ":"JWT"} - konstant, deshalb vorberechnet. */
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    /**
     * Lebensdauer eines Zugangstokens.
     *
     * <p>Kurz, weil es im Browser liegt: Nach einem Rechte-Entzug greift die
     * {@code sessionVersion} sofort, aber bei einem verlorenen Token zaehlt nur die Zeit.
     * Verlaengert wird ueber das Refresh-Token.
     */
    public static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);

    /** Lebensdauer des Refresh-Tokens - es liegt in einem HttpOnly-Cookie. */
    public static final Duration REFRESH_LIFETIME = Duration.ofDays(7);

    private final byte[] key;

    private Jwt(byte[] key) {
        this.key = key;
    }

    /**
     * Laedt den Schluessel oder erzeugt ihn.
     *
     * <p>Rechte der Datei werden nicht gesetzt - unter Windows waere das anders als unter
     * Linux, und das Verzeichnis {@code secrets/} ist ohnehin der Ort fuer solche Dateien.
     */
    public static Jwt load(Path secretsDirectory) throws IOException {
        Files.createDirectories(secretsDirectory);
        Path file = secretsDirectory.resolve("jwt.key");

        if (Files.exists(file)) {
            byte[] key = DECODER.decode(Files.readString(file).trim());
            return new Jwt(key);
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.writeString(file, ENCODER.encodeToString(key));
        LOG.info("{} angelegt - geht er verloren, muessen sich alle neu anmelden", file);
        return new Jwt(key);
    }

    /** Stellt ein Zugangstoken aus. */
    public String accessToken(UUID uuid, String username, int sessionVersion) {
        return sign(uuid, username, sessionVersion, "access", ACCESS_LIFETIME);
    }

    /** Stellt ein Refresh-Token aus. Es taugt nur zum Erneuern, nicht fuer Anfragen. */
    public String refreshToken(UUID uuid, String username, int sessionVersion) {
        return sign(uuid, username, sessionVersion, "refresh", REFRESH_LIFETIME);
    }

    private String sign(UUID uuid, String username, int sessionVersion, String type,
                        Duration lifetime) {
        JsonObject claims = new JsonObject();
        claims.addProperty("sub", uuid.toString());
        claims.addProperty("name", username);
        claims.addProperty("ver", sessionVersion);
        claims.addProperty("typ", type);
        claims.addProperty("exp", Instant.now().plus(lifetime).getEpochSecond());

        String payload = ENCODER.encodeToString(
                GSON.toJson(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER + "." + payload;

        return signingInput + "." + ENCODER.encodeToString(mac(signingInput));
    }

    /**
     * Prueft ein Token.
     *
     * <p>Reihenfolge: erst die Signatur, dann der Inhalt. Nichts aus einem unsignierten
     * Token darf eine Entscheidung beeinflussen - auch nicht die Ablaufzeit.
     *
     * @param expectedType {@code access} oder {@code refresh}; ein Refresh-Token darf
     *                     keine Anfrage legitimieren
     */
    public Optional<Claims> verify(String token, String expectedType) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];

        byte[] presented;
        try {
            presented = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(presented, mac(signingInput))) {
            return Optional.empty();
        }

        try {
            JsonObject claims = GSON.fromJson(
                    new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                    JsonObject.class);

            if (!expectedType.equals(claims.get("typ").getAsString())) {
                return Optional.empty();
            }
            Instant expires = Instant.ofEpochSecond(claims.get("exp").getAsLong());
            if (expires.isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new Claims(
                    UUID.fromString(claims.get("sub").getAsString()),
                    claims.get("name").getAsString(),
                    claims.get("ver").getAsInt(),
                    expires));

        } catch (RuntimeException exception) {
            // Signiert, aber inhaltlich kaputt - sollte nicht vorkommen, darf aber
            // keine Ausnahme nach oben geben.
            LOG.warn("Token hat eine gueltige Signatur, aber unerwarteten Inhalt");
            return Optional.empty();
        }
    }

    private byte[] mac(String input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC nicht verfuegbar", exception);
        }
    }

    /** Der Inhalt eines geprueften Tokens. */
    public record Claims(UUID uuid, String username, int sessionVersion, Instant expiresAt) {
    }
}
