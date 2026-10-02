package de.kevloe.vibecloud.common.tls;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Fingerprint-Vergleich entscheidet, ob ein Wrapper dem Master vertraut
 * (PLAN.md Abschnitt 13).
 *
 * <p>Beide Fehlerrichtungen sind schlimm: Zu streng, und ein aus dem Terminal kopierter
 * Wert funktioniert nicht - dann baut jemand die Pruefung aus Frust aus. Zu locker, und
 * die Pruefung ist wertlos.
 */
class CertificateFingerprintTest {

    private static final String A =
            "sha256:3d:ea:cb:4f:12:70:0f:37:d7:e1:93:8d:c4:c7:50:c1"
            + ":f7:d6:3d:9f:67:ba:26:6b:8d:79:82:09:80:66:4e:75";

    @Test
    void gleicherWertPasst() {
        assertThat(CertificateFingerprint.matches(A, A)).isTrue();
    }

    @Test
    void grossschreibungUndDoppelpunkteSindEgal() {
        String ohneDoppelpunkte = A.replace(":", "").replace("sha256", "");
        assertThat(CertificateFingerprint.matches(A, ohneDoppelpunkte)).isTrue();
        assertThat(CertificateFingerprint.matches(A, A.toUpperCase(java.util.Locale.ROOT))).isTrue();
    }

    @Test
    void umgebendeLeerzeichenSindEgal() {
        assertThat(CertificateFingerprint.matches(A, "  " + A + "\n")).isTrue();
    }

    @Test
    void einAnderesZertifikatPasstNicht() {
        String andere = A.substring(0, A.length() - 2) + "00";
        assertThat(CertificateFingerprint.matches(A, andere)).isFalse();
    }

    @Test
    void einZeichenUnterschiedReichtZumAblehnen() {
        String fastGleich = A.replaceFirst("3d", "3e");
        assertThat(CertificateFingerprint.matches(A, fastGleich)).isFalse();
    }

    @Test
    void leereWerteGeltenNichtAlsTreffer() {
        // Sonst wuerde eine leere masterFingerprint-Angabe jedes Zertifikat akzeptieren.
        assertThat(CertificateFingerprint.matches(A, "")).isFalse();
        assertThat(CertificateFingerprint.matches(A, null)).isFalse();
    }
}
