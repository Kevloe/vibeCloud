package de.kevloe.vibecloud.common.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Vertraut genau einem Zertifikat - dem mit dem erwarteten Fingerprint
 * (PLAN.md Abschnitt 13).
 *
 * <p>Bewusst kein Vertrauen in die System-CAs: Der Master nutzt ein selbstsigniertes
 * Zertifikat, und "irgendein von einer CA signiertes Zertifikat" wuerde hier nichts
 * beweisen. Geprueft wird die Identitaet des Masters, nicht seine Beglaubigung.
 *
 * <p>Damit scheitert ein vorgeschobener Master sofort, statt Befehle verteilen zu koennen.
 *
 * <p>Liegt in {@code common}, weil Wrapper <b>und</b> Plugins denselben Master pruefen -
 * zwei Implementierungen wuerden irgendwann auseinanderlaufen.
 */
public final class FingerprintTrustManager implements X509TrustManager {

    private static final Logger LOG = LoggerFactory.getLogger(FingerprintTrustManager.class);

    private final String expectedFingerprint;

    public FingerprintTrustManager(String expectedFingerprint) {
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "masterFingerprint fehlt in der wrapper.json. Der Master gibt ihn beim Start "
                    + "und bei 'node add' aus.");
        }
        this.expectedFingerprint = expectedFingerprint;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {

        if (chain == null || chain.length == 0) {
            throw new CertificateException("Der Master hat kein Zertifikat vorgelegt");
        }
        String actual = CertificateFingerprint.of(chain[0]);
        if (!CertificateFingerprint.matches(expectedFingerprint, actual)) {
            LOG.error("""
                    Der Master hat ein unerwartetes Zertifikat vorgelegt - Verbindung abgebrochen.
                      erwartet: {}
                      erhalten: {}
                    Entweder wurde das Master-Zertifikat neu erzeugt (dann den neuen Fingerprint
                    in die wrapper.json uebernehmen) oder es antwortet nicht der echte Master.""",
                    expectedFingerprint, actual);
            throw new CertificateException("Fingerprint des Masters stimmt nicht");
        }
        LOG.debug("Master-Zertifikat bestaetigt ({})", actual);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        // Der Wrapper ist nie Server. Client-Zertifikate kommen mit mTLS in M8.
        throw new CertificateException("Der Wrapper nimmt keine Verbindungen an");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
