package de.kevloe.vibecloud.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Gemeinsame Hash-Verfahren (PLAN.md Abschnitt 13).
 *
 * <p>Liegt in {@code api}, weil Master <b>und</b> Module denselben Wert berechnen muessen:
 * Der Master speichert beim Login einen IP-Hash, ein Ban-Modul vergleicht dagegen. Zwei
 * Implementierungen wuerden irgendwann auseinanderlaufen, und der Fehler waere "IP-Ban
 * greift nicht" ohne erkennbaren Grund.
 */
public final class Hashing {

    private Hashing() {
    }

    /**
     * SHA-256 einer IP-Adresse.
     *
     * <p>Nicht umkehrbar, aber vergleichbar - genug fuer IP-Bans und
     * Mehrfachkonten-Erkennung, ohne die Adresse selbst zu speichern.
     */
    public static String ipHash(String ip) {
        return sha256(ip == null ? "" : ip);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 fehlt in dieser JVM", exception);
        }
    }
}
