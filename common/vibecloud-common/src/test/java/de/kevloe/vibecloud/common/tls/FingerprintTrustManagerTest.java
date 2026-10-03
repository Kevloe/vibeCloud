package de.kevloe.vibecloud.common.tls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Die Pruefung des Master-Zertifikats, so wie TLS sie aufruft.
 *
 * <p>Das Zertifikat lautet wie das des Masters auf {@code vibecloud-master}, verbunden wird
 * mit einer IP. Unter Java 17 und 21 lehnte die Engine das ab, solange
 * {@link FingerprintTrustManager} ein einfacher {@code X509TrustManager} war - der
 * Fingerprint stimmte, der Hostname nicht.
 */
class FingerprintTrustManagerTest {

    private static final String PEM = """
            -----BEGIN CERTIFICATE-----
            MIIC+DCCAeCgAwIBAgIIcRdT+oDfilYwDQYJKoZIhvcNAQEMBQAwGzEZMBcGA1UE
            AxMQdmliZUNsb3VkIE1hc3RlcjAgFw0yNjEwMDMwMDM2MDhaGA8yMTI2MDkwOTAw
            MzYwOFowGzEZMBcGA1UEAxMQdmliZUNsb3VkIE1hc3RlcjCCASIwDQYJKoZIhvcN
            AQEBBQADggEPADCCAQoCggEBALWg0wgyt9WuvADEhHvig3GBxLJ7zilRlokZqfjL
            Vyf4q5nUMEqeI7V+YUiZ0cx9QjvGiuqmRED9dMktFp94HkSTWrkrhbYyyt6u/5Ob
            tptXaI6fcFG96A57CGTtwGvqfiSW+k7+LH67XhgDy7yxiuD+YK9ToWyu9Wt2HYgo
            7U/rFGamHHh/N3esi/9h0w4TBMwNzevp5L2/tGlrftNzyQfFzUIH77ZsNRfA4Edz
            5Hptko3PaFh5/DXAjd7Ak32nUPBGqMbhZD92oPes0agx6BNcprg/Bl1Wdk/4PaOX
            M/PIgDuZwfdHCU3wDWsx3iILGTZSE9Ee3k2oIqtKOV4Psr0CAwEAAaM+MDwwHQYD
            VR0OBBYEFMCdgO2Lz6cIz+brkPNyTTFF4yacMBsGA1UdEQQUMBKCEHZpYmVjbG91
            ZC1tYXN0ZXIwDQYJKoZIhvcNAQEMBQADggEBAE1+FeDP4q6f5ojkbCsBsZZT2fys
            MSYyQgOzmdEAaSdq1hvG2m7tGFFxvuB4p4vRRN6Mmc07gcn2mW8YhSKaKPIGdafA
            RfAafeK1bLmm8Ps0qeixCUwX0cx3usiKOrpaNdBIhXHBJoNfIiOmx51poilbF2fX
            I8dlBErc9iL9F6Yq3urx5MtsAhAyhLfCqx+ejipRrHac86TrC4kRtpw7csPEs3k7
            71ePhhzNtL4wkeeQSU7S+Ei3oX/RvwRr1ifKkSR9SH3G4zHwtWW1lnCjTraxFR+i
            k/Ph6Wmv66iQ4HL4hMKu7I8LttEa8d7U45ezK/+WX2cit8Zcx54ciTXROK0=
            -----END CERTIFICATE-----
            """;

    private static X509Certificate certificate() throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(PEM.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Eine Engine wie die von gRPC: Ziel ist eine IP, Hostnamen-Pruefung eingeschaltet. */
    private static SSLEngine engineForIp() throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine("127.0.0.1", 5000);
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(parameters);
        return engine;
    }

    @Test
    @DisplayName("Ist ein X509ExtendedTrustManager - sonst packt die Engine ihn ein und prueft den Hostnamen")
    void erweitert() {
        assertThat(new FingerprintTrustManager("sha256:00"))
                .isInstanceOf(X509ExtendedTrustManager.class);
    }

    @Test
    @DisplayName("Der richtige Fingerprint gilt, auch wenn der Name nicht zur IP passt")
    void richtigerFingerprint() throws Exception {
        X509Certificate certificate = certificate();
        var trust = new FingerprintTrustManager(CertificateFingerprint.of(certificate));

        assertThatCode(() -> trust.checkServerTrusted(
                new X509Certificate[] {certificate}, "RSA", engineForIp()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Ein falscher Fingerprint wird auf jedem Weg abgelehnt")
    void falscherFingerprint() throws Exception {
        X509Certificate certificate = certificate();
        String wrong = "sha256:" + "00:".repeat(31) + "00";
        var trust = new FingerprintTrustManager(wrong);
        X509Certificate[] chain = {certificate};

        assertThatThrownBy(() -> trust.checkServerTrusted(chain, "RSA", engineForIp()))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> trust.checkServerTrusted(chain, "RSA", (java.net.Socket) null))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> trust.checkServerTrusted(chain, "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    @DisplayName("Als Server nimmt er nichts an")
    void keinServer() throws Exception {
        var trust = new FingerprintTrustManager("sha256:00");
        assertThatThrownBy(() -> trust.checkClientTrusted(
                new X509Certificate[] {certificate()}, "RSA", engineForIp()))
                .isInstanceOf(CertificateException.class);
    }
}
