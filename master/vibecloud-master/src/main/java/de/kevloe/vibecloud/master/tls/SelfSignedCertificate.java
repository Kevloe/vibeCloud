package de.kevloe.vibecloud.master.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import de.kevloe.vibecloud.common.tls.CertificateFingerprint;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/**
 * Erzeugt beim ersten Start ein selbstsigniertes Zertifikat fuer den gRPC-Port und gibt
 * dessen SHA-256-Fingerprint aus (PLAN.md Abschnitt 13).
 *
 * <p>Der Fingerprint gehoert in die {@code wrapper.json} jedes Roots. Ohne diese Pruefung
 * koennte sich jemand als Master ausgeben und den Wrappern Befehle schicken - die Richtung,
 * an die man zuerst nicht denkt.
 *
 * <p>Ein eigenes Zertifikat kann stattdessen einfach unter denselben Namen abgelegt werden;
 * vorhandene Dateien werden nie ueberschrieben.
 */
public final class SelfSignedCertificate {

    private static final Logger LOG = LoggerFactory.getLogger(SelfSignedCertificate.class);

    private static final String CERT_FILE = "master-cert.pem";
    private static final String KEY_FILE = "master-key.pem";
    private static final Duration VALIDITY = Duration.ofDays(3650);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final Path certificate;
    private final Path privateKey;
    private final String fingerprint;

    private SelfSignedCertificate(Path certificate, Path privateKey, String fingerprint) {
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.fingerprint = fingerprint;
    }

    /** Laedt das vorhandene Zertifikat oder erzeugt eines, falls keines da ist. */
    public static SelfSignedCertificate loadOrCreate(Path directory, String hostName)
            throws IOException, GeneralSecurityException {

        Files.createDirectories(directory);
        Path certPath = directory.resolve(CERT_FILE);
        Path keyPath = directory.resolve(KEY_FILE);

        if (Files.exists(certPath) && Files.exists(keyPath)) {
            return new SelfSignedCertificate(certPath, keyPath, fingerprintOf(certPath));
        }

        LOG.info("Kein Zertifikat gefunden - es wird ein selbstsigniertes erzeugt");
        generate(certPath, keyPath, hostName);
        return new SelfSignedCertificate(certPath, keyPath, fingerprintOf(certPath));
    }

    private static void generate(Path certPath, Path keyPath, String hostName)
            throws IOException, GeneralSecurityException {

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        X500Name subject = new X500Name("CN=vibeCloud Master");
        Instant now = Instant.now();

        try {
            var builder = new JcaX509v3CertificateBuilder(
                    subject,
                    new BigInteger(64, new SecureRandom()),
                    java.util.Date.from(now.minus(Duration.ofHours(1))),
                    java.util.Date.from(now.plus(VALIDITY)),
                    subject,
                    keyPair.getPublic());

            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            // Der Wrapper prueft den Fingerprint, nicht den Hostnamen. Der SAN steht
            // trotzdem drin, damit Standard-Werkzeuge wie openssl nicht meckern.
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, hostName)));

            var signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());

            X509Certificate certificate = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));

            writePem(certPath, "CERTIFICATE", certificate.getEncoded());
            writePem(keyPath, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
            restrictToOwner(keyPath);
        } catch (org.bouncycastle.operator.OperatorCreationException exception) {
            throw new GeneralSecurityException("Zertifikat konnte nicht signiert werden", exception);
        }
    }

    private static void writePem(Path path, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        Files.writeString(path, "-----BEGIN " + type + "-----\n"
                                + body + "\n-----END " + type + "-----\n");
    }

    /** Der private Schluessel darf nur dem Besitzer lesbar sein. Auf Windows ohne Wirkung. */
    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException exception) {
            LOG.debug("Dateirechte fuer {} nicht setzbar (Windows?)", path.getFileName());
        }
    }

    private static String fingerprintOf(Path certPath) throws IOException {
        return CertificateFingerprint.ofPem(Files.readString(certPath));
    }

    /**
     * SHA-256 ueber das Zertifikat, Format {@code sha256:3f:a1:...}.
     * Genau dieser Wert gehoert in die {@code wrapper.json}.
     */
    public String fingerprint() {
        return fingerprint;
    }

    public Path certificateFile() {
        return certificate;
    }

    public Path privateKeyFile() {
        return privateKey;
    }
}
