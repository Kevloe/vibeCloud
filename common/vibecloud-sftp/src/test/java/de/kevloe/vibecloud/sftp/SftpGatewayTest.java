package de.kevloe.vibecloud.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshException;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Der SFTP-Zugang gegen einen echten Client.
 *
 * <p>Geprueft wird vor allem, was bei einem Fehler zu viel erlaubte: ein Ziel, das es hier
 * nicht gibt, ein Pfad aus dem Verzeichnis heraus, eine abgelehnte Anmeldung - und dass ein
 * Schreiben den Blob-Cache nicht veraendert.
 */
class SftpGatewayTest {

    private static final String PASSWORD = "richtig";

    @TempDir
    private Path root;

    private SftpGateway gateway;
    private SshClient client;

    /** Was den "Master" erreicht hat - Zugang und Server je Rueckfrage. */
    private final List<String> asked = new ArrayList<>();
    private final AtomicInteger port = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        // Zwei Ziele: eines mit Verzeichnis, eines, das es noch nicht gibt (ein Template,
        // in das noch nie jemand etwas gelegt hat).
        Map<String, Path> targets = Map.of(
                "survival-1", root.resolve("servers/survival-1"),
                "neu", root.resolve("templates/neu"));
        Files.createDirectories(root.resolve("servers/survival-1"));
        Files.createDirectories(root.resolve("servers/lobby-1"));
        Files.writeString(root.resolve("servers/survival-1/server.properties"), "motd=alt\n");
        Files.writeString(root.resolve("geheim.txt"), "wrapper.json liegt hier");

        gateway = new SftpGateway("den Test", "127.0.0.1", 0,
                root.resolve("secrets/sftp-host.key"),
                name -> Optional.ofNullable(targets.get(name)),
                (account, serverName, password, ip) -> {
                    asked.add(account + "@" + serverName);
                    return new SftpAuthority.Decision(
                            account.equals("Kevloe") && password.equals(PASSWORD), "falsch");
                });
        gateway.start();
        port.set(gateway.port());

        client = SshClient.setUpDefaultClient();
        // Der Test kennt den Host-Schluessel nicht vorab - um den geht es hier nicht.
        client.setServerKeyVerifier((session, address, key) -> true);
        client.start();
    }

    @AfterEach
    void tearDown() {
        client.stop();
        gateway.close();
    }

    private ClientSession login(String username, String password) throws IOException {
        ClientSession session = client.connect(username, "127.0.0.1", port.get())
                .verify(Duration.ofSeconds(10)).getSession();
        session.addPasswordIdentity(password);
        session.auth().verify(Duration.ofSeconds(10));
        return session;
    }

    @Test
    @DisplayName("Hochladen, lesen, aendern und loeschen im Verzeichnis des Servers")
    void dateienImSerververzeichnis() throws IOException {
        try (ClientSession session = login("Kevloe.survival-1", PASSWORD);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {

            sftp.mkdir("/plugins");
            try (OutputStream out = sftp.write("/plugins/config.yml")) {
                out.write("a: 1\n".getBytes(StandardCharsets.UTF_8));
            }
            Path uploaded = root.resolve("servers/survival-1/plugins/config.yml");
            assertThat(uploaded).hasContent("a: 1\n");

            try (OutputStream out = sftp.write("/plugins/config.yml")) {
                out.write("a: 2\n".getBytes(StandardCharsets.UTF_8));
            }
            assertThat(uploaded).hasContent("a: 2\n");

            try (var in = sftp.read("/server.properties")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("motd=alt\n");
            }

            sftp.remove("/plugins/config.yml");
            assertThat(uploaded).doesNotExist();
        }
    }

    @Test
    @DisplayName("Aus dem Serververzeichnis fuehrt kein Pfad hinaus")
    void keinPfadFuehrtHinaus() throws IOException {
        try (ClientSession session = login("Kevloe.survival-1", PASSWORD);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {

            // Zwei Ebenen hoeher liegt das Arbeitsverzeichnis des Wrappers - mit der
            // wrapper.json und dem Node-Token darin.
            for (String path : List.of("/../../geheim.txt", "../../geheim.txt",
                    "/plugins/../../../geheim.txt", "/../survival-1/server.properties",
                    "/../../servers/survival-1/server.properties")) {
                assertThatThrownBy(() -> sftp.read(path).close())
                        .as("Lesen von %s", path)
                        .isInstanceOf(IOException.class);
            }
            // Schreiben mit ".." scheitert nicht - es bleibt nur drinnen: Ueber der Wurzel
            // der Sitzung gibt es nichts, also ist "/../.." wieder die Wurzel.
            try (OutputStream out = sftp.write("/../../eingeschleust.txt")) {
                out.write(1);
            }
            assertThat(root.resolve("servers/survival-1/eingeschleust.txt")).exists();
            assertThat(root.resolve("eingeschleust.txt")).doesNotExist();
            assertThat(root.resolve("servers/eingeschleust.txt")).doesNotExist();
            assertThat(root.resolve("geheim.txt")).hasContent("wrapper.json liegt hier");
        }
    }

    @Test
    @DisplayName("Ein falsches Passwort kommt nicht herein")
    void falschesPasswort() {
        assertThatThrownBy(() -> login("Kevloe.survival-1", "falsch"))
                .isInstanceOf(SshException.class);
    }

    @Test
    @DisplayName("Ein Ziel, das es hier nicht gibt, hat keinen SFTP-Zugang")
    void unbekanntesZiel() {
        // Das Verzeichnis lobby-1 liegt zwar da - aber freigegeben ist es nicht, und die
        // Pruefung der Zugaenge wird gar nicht erst gefragt.
        assertThatThrownBy(() -> login("Kevloe.lobby-1", PASSWORD))
                .isInstanceOf(SshException.class);
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("Ein fehlendes Verzeichnis entsteht erst nach der Anmeldung")
    void verzeichnisEntstehtErstNachDerAnmeldung() throws IOException {
        Path template = root.resolve("templates/neu");

        // Sonst legte jeder geratene Name ein Verzeichnis an.
        assertThatThrownBy(() -> login("Kevloe.neu", "falsch"))
                .isInstanceOf(SshException.class);
        assertThat(template).doesNotExist();

        try (ClientSession session = login("Kevloe.neu", PASSWORD);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream out = sftp.write("/server.properties")) {
                out.write("motd=neu\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        assertThat(template.resolve("server.properties")).hasContent("motd=neu\n");
    }

    @Test
    @DisplayName("Unsinn als Benutzername erreicht den Master nicht")
    void unsinnErreichtDenMasterNicht() {
        for (String username : List.of("root", "admin", "Kevloe.", ".survival-1",
                "Kevloe.gibtsnicht", "Kevloe...", "Kevloe.survival-1/../lobby-1")) {
            assertThatThrownBy(() -> login(username, PASSWORD))
                    .as("Benutzername %s", username)
                    .isInstanceOf(SshException.class);
        }
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("Nach fuenf Fehlversuchen ist fuer die Adresse Schluss")
    void sperreNachFehlversuchen() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> login("Kevloe.survival-1", "falsch"))
                    .isInstanceOf(SshException.class);
        }
        int bisher = asked.size();

        // Jetzt stimmt das Passwort - aber die Adresse ist gesperrt, und der Master wird
        // nicht mehr gefragt.
        assertThatThrownBy(() -> login("Kevloe.survival-1", PASSWORD))
                .isInstanceOf(SshException.class);
        assertThat(asked).hasSize(bisher);
    }

    @Test
    @DisplayName("Ein Schreiben laesst den Blob im Cache unveraendert")
    void schreibenLaesstDenBlobInRuhe() throws IOException {
        // So legt der Wrapper Template-Dateien ab: als harten Link auf den Blob.
        Path blob = root.resolve("cache/blob-der-vorlage");
        Files.createDirectories(blob.getParent());
        Files.writeString(blob, "aus dem template\n");
        Path linked = root.resolve("servers/survival-1/bukkit.yml");
        try {
            Files.createLink(linked, blob);
        } catch (IOException | UnsupportedOperationException exception) {
            // Dateisystem ohne harte Links - dann gibt es auch nichts zu trennen.
            return;
        }

        try (ClientSession session = login("Kevloe.survival-1", PASSWORD);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream out = sftp.write("/bukkit.yml")) {
                out.write("vom betreiber\n".getBytes(StandardCharsets.UTF_8));
            }
            // Auch ohne Abschneiden, wie beim Fortsetzen eines Uploads.
            try (OutputStream out = sftp.write("/bukkit.yml", SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Append)) {
                out.write("zweite zeile\n".getBytes(StandardCharsets.UTF_8));
            }
        }

        assertThat(linked).hasContent("vom betreiber\nzweite zeile\n");
        // Sonst stuende der neue Inhalt in jedem Server, der diese Datei aus seinem
        // Template bekommt.
        assertThat(blob).hasContent("aus dem template\n");
    }

    @Test
    @DisplayName("Der Host-Schluessel bleibt ueber einen Neustart derselbe")
    void hostSchluesselBleibt() throws IOException {
        Path key = root.resolve("secrets/sftp-host.key");
        byte[] vorher = Files.readAllBytes(key);

        gateway.close();
        gateway = new SftpGateway("den Test", "127.0.0.1", 0, key, name -> Optional.empty(),
                (account, serverName, password, ip) -> SftpAuthority.Decision.denied("aus"));
        gateway.start();

        // Ein neuer Schluessel saehe fuer jeden Client aus wie ein Angriff.
        assertThat(Files.readAllBytes(key)).isEqualTo(vorher);
    }
}
