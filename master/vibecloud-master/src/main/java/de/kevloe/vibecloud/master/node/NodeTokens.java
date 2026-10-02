package de.kevloe.vibecloud.master.node;


import de.kevloe.vibecloud.master.security.Argon2Hash;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Erzeugung und Pruefung der Node-Tokens (PLAN.md Abschnitt 13).
 *
 * <p>Gespeichert wird nur ein Argon2id-Hash. Das Token ist an genau einen Node-Namen
 * gebunden: Der Name geht als zusaetzlicher Eingabewert in den Hash ein, deshalb laesst
 * sich das Token von {@code node-a} nicht fuer {@code node-b} verwenden - selbst dann
 * nicht, wenn jemand die Datenbankzeile kopiert.
 */
public final class NodeTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;


    /** Neues Token im Klartext. Wird einmalig angezeigt und nie gespeichert. */
    public static String generateToken() {
        byte[] token = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * @return {@code argon2id$<salt-b64>$<hash-b64>}
     * @see de.kevloe.vibecloud.master.security.Argon2Hash
     */
    public static String hash(String nodeName, String token) {
        // Node-Name als zusaetzliche Daten: bindet den Hash an diesen einen Node.
        return Argon2Hash.hash(nodeName, token);
    }

    /** Prueft ein Token gegen den gespeicherten Hash. */
    public static boolean verify(String nodeName, String token, String stored) {
        return Argon2Hash.verify(nodeName, token, stored);
    }

    private NodeTokens() {
    }
}
