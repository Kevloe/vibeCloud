package de.kevloe.vibecloud.wrapper.server;

import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.StartServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Setzt Port und Forwarding-Secret in die plattformspezifischen Konfigurationsdateien
 * (PLAN.md Abschnitt 13).
 *
 * <p>Das passiert <b>auf dem Wrapper</b>, nach dem Materialisieren des Templates und vor dem
 * Start. Der Grund: Diese Werte sind pro Server unterschiedlich (jeder hat einen anderen
 * Port), gehoeren also nicht ins Template und nicht in den inhaltsadressierten Blob-Cache -
 * dort wuerde jeder Server eine eigene Kopie jeder Datei erzeugen.
 *
 * <p><b>Vorhandene Werte werden ergaenzt, nicht ersetzt.</b> Wer in seinem Template eigene
 * Einstellungen pflegt, soll sie behalten.
 */
public final class ServerConfigurator {

    private static final Logger LOG = LoggerFactory.getLogger(ServerConfigurator.class);

    private ServerConfigurator() {
    }

    public static void apply(StartServer request, Path directory) throws IOException {
        switch (request.getPlatform()) {
            case SERVER_PLATFORM_PAPER -> configurePaper(request, directory);
            case SERVER_PLATFORM_VELOCITY -> configureVelocity(request, directory);
            case SERVER_PLATFORM_MINESTOM -> configureMinestom(request, directory);
            default -> LOG.warn("Unbekannte Plattform fuer {} - keine Konfiguration angepasst",
                    request.getServerName());
        }
    }

    // ---------------------------------------------------------------- Paper

    /**
     * Paper braucht drei Dinge fuer den Betrieb hinter Velocity:
     * {@code online-mode=false}, das Forwarding aktiviert und das gemeinsame Secret.
     *
     * <p>Ohne {@code online-mode=false} lehnt Paper jede weitergeleitete Verbindung ab;
     * ohne Firewall-Schutz auf den Gameserver-Ports ist genau das aber auch die
     * Sicherheitsluecke, die Abschnitt 13 beschreibt.
     */
    private static void configurePaper(StartServer request, Path directory) throws IOException {
        Path properties = directory.resolve("server.properties");
        Properties values = new Properties();
        if (Files.exists(properties)) {
            try (InputStream in = Files.newInputStream(properties)) {
                values.load(in);
            }
        }
        values.setProperty("server-port", Integer.toString(request.getPort()));
        values.setProperty("online-mode", "false");
        values.setProperty("server-ip", "");
        // Der Proxy prueft die Spielerzahl; der Gameserver soll niemanden abweisen,
        // den der Proxy schon durchgelassen hat.
        values.putIfAbsent("max-players", "100");
        values.putIfAbsent("motd", "vibeCloud");

        try (var out = Files.newBufferedWriter(properties, StandardCharsets.UTF_8)) {
            values.store(out, "von vibeCloud verwaltet - Port und online-mode nicht aendern");
        }

        writePaperGlobal(request, directory);
    }

    /**
     * Ergaenzt den {@code proxies.velocity}-Block in {@code config/paper-global.yml}.
     *
     * <p>Bewusst nur dieser Block: Die Datei wird geladen, der Block gesetzt und wieder
     * geschrieben. Kommentare gehen dabei verloren - deshalb gehoeren eigene Einstellungen
     * ins Template und nicht in eine Datei, die die Cloud anfasst.
     */
    @SuppressWarnings("unchecked")
    private static void writePaperGlobal(StartServer request, Path directory) throws IOException {
        Path file = directory.resolve("config/paper-global.yml");
        Files.createDirectories(file.getParent());

        Map<String, Object> root = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> root.put(String.valueOf(key), value));
                }
            } catch (RuntimeException exception) {
                LOG.warn("paper-global.yml von {} war nicht lesbar und wird neu geschrieben: {}",
                        request.getServerName(), exception.getMessage());
            }
        }

        Map<String, Object> proxies = root.get("proxies") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();

        Map<String, Object> velocity = new LinkedHashMap<>();
        velocity.put("enabled", true);
        // online-mode hier bedeutet "der Proxy hat den Spieler bei Mojang geprueft" -
        // das ist etwas anderes als online-mode in der server.properties.
        velocity.put("online-mode", true);
        velocity.put("secret", request.getForwardingSecret());
        proxies.put("velocity", velocity);
        root.put("proxies", proxies);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        Files.writeString(file, new Yaml(options).dump(root), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Velocity

    /**
     * Velocity liest Port und Forwarding-Modus aus seiner {@code velocity.toml}, das Secret
     * aus einer eigenen Datei.
     *
     * <p>TOML wird hier zeilenweise angepasst statt geparst: Die Datei hat Kommentare, die
     * man behalten will, und es geht nur um drei Schluessel. Ein TOML-Parser waere eine
     * zusaetzliche Abhaengigkeit fuer sehr wenig Gewinn.
     */
    private static void configureVelocity(StartServer request, Path directory)
            throws IOException {

        Files.writeString(directory.resolve("forwarding.secret"),
                request.getForwardingSecret(), StandardCharsets.UTF_8);

        Path toml = directory.resolve("velocity.toml");
        if (Files.notExists(toml)) {
            Files.writeString(toml, defaultVelocityToml(request.getPort()),
                    StandardCharsets.UTF_8);
            LOG.info("velocity.toml fuer {} angelegt (Port {})",
                    request.getServerName(), request.getPort());
            return;
        }

        List<String> lines = Files.readAllLines(toml, StandardCharsets.UTF_8);
        boolean bindSet = false;
        boolean modeSet = false;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).stripLeading();
            if (trimmed.startsWith("bind")) {
                lines.set(i, "bind = \"0.0.0.0:" + request.getPort() + "\"");
                bindSet = true;
            } else if (trimmed.startsWith("player-info-forwarding-mode")) {
                lines.set(i, "player-info-forwarding-mode = \"modern\"");
                modeSet = true;
            }
        }
        if (!bindSet) {
            lines.add("bind = \"0.0.0.0:" + request.getPort() + "\"");
        }
        if (!modeSet) {
            lines.add("player-info-forwarding-mode = \"modern\"");
        }
        Files.write(toml, lines, StandardCharsets.UTF_8);
    }

    private static String defaultVelocityToml(int port) {
        // [forced-hosts] MUSS leer dastehen. Fehlt der Abschnitt, nimmt Velocity seine
        // eingebauten Beispiele (lobby.example.com -> "lobby" usw.), findet die Server
        // nicht und verweigert den Start mit "Your configuration is invalid".
        // Die Backend-Server registriert die Cloud zur Laufzeit, nicht ueber diese Datei.
        return """
                # Von vibeCloud angelegt. bind und player-info-forwarding-mode verwaltet die
                # Cloud - Backend-Server registriert sie zur Laufzeit.
                config-version = "2.9"
                bind = "0.0.0.0:%d"
                motd = "vibeCloud"
                show-max-players = 500
                online-mode = true
                player-info-forwarding-mode = "modern"
                forwarding-secret-file = "forwarding.secret"

                [servers]
                try = []

                [forced-hosts]

                [advanced]
                compression-threshold = 256

                [query]
                enabled = false
                """.formatted(port);
    }

    // ---------------------------------------------------------------- Minestom

    /**
     * Minestom-Server bekommen Port und Secret als Umgebungsvariablen und Startargumente -
     * sie bringen ihre Konfiguration als eigener Code mit, es gibt keine Standarddatei,
     * die man anpassen koennte.
     */
    private static void configureMinestom(StartServer request, Path directory) {
        LOG.debug("{}: Minestom bekommt Port {} als Argument und das Secret ueber die Umgebung",
                request.getServerName(), request.getPort());
    }

    /** Umgebungsvariablen, die jeder Server mitbekommt. */
    public static Map<String, String> environmentFor(StartServer request) {
        Map<String, String> environment = new LinkedHashMap<>(request.getEnvironmentMap());
        environment.put("VIBECLOUD_SERVER", request.getServerName());
        environment.put("VIBECLOUD_GROUP", request.getGroupName());
        environment.put("VIBECLOUD_PORT", Integer.toString(request.getPort()));
        environment.put("VIBECLOUD_FORWARDING_SECRET", request.getForwardingSecret());
        return environment;
    }
}
