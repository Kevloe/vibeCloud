package de.kevloe.vibecloud.master.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Argon2id fuer Geheimnisse, die ein Mensch tippen kann.
 *
 * <p>Benutzt von Node-Tokens und von Dashboard-Passwoertern. An einer Stelle, weil der
 * Aufbau des gespeicherten Werts ({@code argon2id$salt$hash}) und die Parameter zusammen
 * gehoeren - zwei Umsetzungen wuerden auseinanderlaufen, und ein Fehler darin faellt erst
 * auf, wenn sich niemand mehr anmelden kann.
 *
 * <p><b>Nicht</b> fuer API-Tokens: Die sind 32 Byte aus einem Zufallsgenerator, da genuegt
 * SHA-256. Argon2 wuerde dort nur jede Anfrage bremsen (siehe {@code ApiTokenService}).
 *
 * <p>Die zusaetzlichen Daten ({@code associatedData}) binden einen Hash an seinen Besitzer -
 * Node-Name oder Benutzername. Derselbe Hash in einer anderen Zeile passt damit nicht mehr.
 */
public final class Argon2Hash {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    /**
     * Bewusst moderat.
     *
     * <p>Gepruefft wird beim Verbindungsaufbau eines Wrappers und beim Login im Dashboard -
     * beides selten. 64 MB und drei Durchgaenge kosten rund 50 ms; das bremst einen
     * Angreifer deutlich und einen Menschen nicht spuerbar.
     */
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65_536;
    private static final int PARALLELISM = 1;

    private static final String PREFIX = "argon2id";

    private Argon2Hash() {
    }

    /** @return {@code argon2id$<salt-b64>$<hash-b64>} */
    public static String hash(String associatedData, String secret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(associatedData, secret, salt);

        return PREFIX + "$"
               + Base64.getEncoder().encodeToString(salt) + "$"
               + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Prueft ein Geheimnis gegen einen gespeicherten Wert.
     *
     * <p>Gibt bei jedem Formfehler {@code false} zurueck statt einer Ausnahme: Ein
     * beschaedigter Eintrag darf den Login ablehnen, aber nicht den Dienst stoeren.
     */
    public static boolean verify(String associatedData, String secret, String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[1]);
            expected = Base64.getDecoder().decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        // Zeitkonstant: Ein Vergleich, der beim ersten Unterschied abbricht, verraet
        // ueber die Dauer, wie viel schon stimmt.
        return MessageDigest.isEqual(expected, derive(associatedData, secret, salt));
    }

    private static byte[] derive(String associatedData, String secret, byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KB)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .withAdditional(associatedData.getBytes(StandardCharsets.UTF_8))
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] result = new byte[HASH_BYTES];
        generator.generateBytes(secret.getBytes(StandardCharsets.UTF_8), result);
        return result;
    }
}
