package de.kevloe.vibecloud.common.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SHA-256-Fingerprint eines Zertifikats, Format {@code sha256:3f:a1:...}.
 *
 * <p>Liegt absichtlich in {@code common}: Master und Wrapper muessen denselben Wert
 * berechnen. Zwei Implementierungen wuerden irgendwann auseinanderlaufen, und der Fehler
 * waere "Wrapper verbindet sich nicht" ohne erkennbaren Grund.
 */
public final class CertificateFingerprint {

    public static String of(X509Certificate certificate) {
        try {
            return format(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException exception) {
            throw new IllegalStateException("Fingerprint nicht berechenbar", exception);
        }
    }

    /** Fingerprint aus einem PEM-Text (so liest der Master seine eigene Datei). */
    public static String ofPem(String pem) {
        String base64 = pem.lines()
                .filter(line -> !line.startsWith("-----"))
                .reduce("", String::concat);
        try {
            byte[] der = Base64.getMimeDecoder().decode(base64);
            return format(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Fingerprint nicht berechenbar", exception);
        }
    }

    /**
     * Vergleicht zwei Fingerprints. Gross-/Kleinschreibung und Doppelpunkte sind egal,
     * damit ein aus einem Terminal kopierter Wert nicht aus formalen Gruenden scheitert.
     */
    public static boolean matches(String expected, String actual) {
        String left = normalize(expected);
        String right = normalize(actual);
        // Leere Werte gelten nie als Treffer - sonst wuerde eine fehlende Angabe in der
        // wrapper.json jedes beliebige Zertifikat akzeptieren.
        return !left.isEmpty() && left.equals(right);
    }

    private static String normalize(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        // Erst kleinschreiben, DANN das Praefix entfernen: Ein in Grossbuchstaben kopierter
        // Wert ("SHA256:3D:...") wuerde sonst sein Praefix behalten und nie passen.
        String lower = fingerprint.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.replace("sha256", "").replace(":", "").replace(" ", "");
    }

    private static String format(byte[] digest) {
        return "sha256:" + HexFormat.ofDelimiter(":").formatHex(digest);
    }

    private CertificateFingerprint() {
    }
}
