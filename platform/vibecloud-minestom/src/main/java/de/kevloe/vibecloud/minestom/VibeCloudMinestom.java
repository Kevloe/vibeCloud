package de.kevloe.vibecloud.minestom;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.plugin.CloudConnection;
import de.kevloe.vibecloud.common.message.MessageBundle;
import de.kevloe.vibecloud.protocol.ServerCommand;
import de.kevloe.vibecloud.protocol.ServerEvent;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.ServerRegisterResponse;
import de.kevloe.vibecloud.protocol.ServerStateReport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * Minestom-Anbindung als <b>Bibliothek</b>, nicht als Plugin (PLAN.md Abschnitt 11).
 *
 * <p>Minestom-Server haben einen eigenen {@code main()} und kein Plugin-System. Deshalb
 * bindet man das hier selbst ein:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     MinecraftServer server = MinecraftServer.init();
 *     VelocityProxy.enable(System.getenv("VIBECLOUD_FORWARDING_SECRET"));
 *     // ... Welt und Events einrichten ...
 *
 *     VibeCloudMinestom cloud = VibeCloudMinestom.start(
 *             () -> MinecraftServer.getConnectionManager().getOnlinePlayers().size(),
 *             (uuid, message) -> kickPlayer(uuid, message));
 *
 *     server.start("0.0.0.0", cloud.port());
 * }
 * }</pre>
 *
 * <p>Das Forwarding-Secret kommt als Umgebungsvariable {@code VIBECLOUD_FORWARDING_SECRET},
 * der Port als {@code --port} oder {@code VIBECLOUD_PORT} - der Wrapper setzt beides.
 *
 * <p><b>Keine Minestom-Abhaengigkeit in dieser Klasse.</b> Sie kennt nur Lambdas, die der
 * aufrufende Server mitbringt. So kann die Cloud mit jeder Minestom-Version arbeiten, ohne
 * bei jedem Minestom-Update nachzuziehen.
 */
public final class VibeCloudMinestom implements CloudConnection.CommandHandler {

    private static final Logger LOG = LoggerFactory.getLogger(VibeCloudMinestom.class);

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final IntSupplier playerCount;
    private final BiConsumer<UUID, Component> kickHandler;

    private CloudConnection connection;

    private VibeCloudMinestom(IntSupplier playerCount, BiConsumer<UUID, Component> kickHandler) {
        this.playerCount = playerCount;
        this.kickHandler = kickHandler;
    }

    /**
     * Verbindet sich zum Master, falls der Server unter der Cloud laeuft.
     *
     * @param playerCount liefert die aktuelle Spielerzahl
     * @param kickHandler trennt einen Spieler mit der uebergebenen Meldung
     * @return die Anbindung, auch wenn keine Cloud vorhanden ist (dann inaktiv)
     */
    public static VibeCloudMinestom start(IntSupplier playerCount,
                                          BiConsumer<UUID, Component> kickHandler) {
        VibeCloudMinestom cloud = new VibeCloudMinestom(playerCount, kickHandler);

        Optional<CloudConnection.ConnectionFile> file =
                CloudConnection.readConnectionFile(Path.of("."));
        if (file.isEmpty()) {
            LOG.warn("vibeCloud ist inaktiv - dieser Server laeuft nicht unter der Cloud.");
            return cloud;
        }

        cloud.connection = new CloudConnection(file.get(),
                ServerPlatform.SERVER_PLATFORM_MINESTOM, 100, cloud);
        cloud.connection.connectAsync();
        LOG.info("vibeCloud verbindet sich zum Master als {} ...", file.get().serverName);
        return cloud;
    }

    /**
     * Port, auf dem dieser Server lauschen soll.
     *
     * <p>Reihenfolge: Verbindungsdatei, dann {@code VIBECLOUD_PORT}, dann 25565 als
     * Rueckfall fuer den Betrieb ohne Cloud.
     */
    public int port() {
        if (connection != null) {
            return connection.connectionFile().port;
        }
        String fromEnvironment = System.getenv("VIBECLOUD_PORT");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            try {
                return Integer.parseInt(fromEnvironment);
            } catch (NumberFormatException exception) {
                LOG.warn("VIBECLOUD_PORT ist keine Zahl: {}", fromEnvironment);
            }
        }
        return 25565;
    }

    /** Forwarding-Secret fuer {@code VelocityProxy.enable(...)}. */
    public static String forwardingSecret() {
        String secret = System.getenv("VIBECLOUD_FORWARDING_SECRET");
        if (secret == null || secret.isBlank()) {
            LOG.warn("VIBECLOUD_FORWARDING_SECRET ist nicht gesetzt - laeuft dieser Server "
                     + "ohne Cloud, oder wurde er von Hand gestartet?");
            return "";
        }
        return secret;
    }

    /** Nach Join und Quit aufrufen, damit der Master die Spielerzahl kennt. */
    public void reportPlayers() {
        if (connection == null) {
            return;
        }
        connection.publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setStateReport(ServerStateReport.newBuilder()
                        .setIngameState("LOBBY")
                        .setPlayers(playerCount.getAsInt()))
                .build());
    }

    /** Nachricht in der Sprache des Spielers; Texte kommen immer vom Master. */
    public Component message(String locale, String key, Map<String, String> placeholders) {
        return connection == null
                ? Component.text(key)
                : connection.messages()
                        .map(bundle -> miniMessage.deserialize(
                                bundle.get(locale, key, placeholders)))
                        .orElseGet(() -> Component.text(key));
    }

    public Optional<MessageBundle> messages() {
        return connection == null ? Optional.empty() : connection.messages();
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    public void shutdown() {
        if (connection != null) {
            connection.close();
        }
    }

    // ---------------------------------------------------------------- Master -> Server

    @Override
    public void onConnected(ServerRegisterResponse response) {
        LOG.info("Am Master angemeldet. Sprachen: {}",
                String.join(", ", response.getAvailableLocalesList()));
        reportPlayers();
    }

    @Override
    public void onCommand(ServerCommand command) {
        switch (command.getCommandCase()) {
            case KICK_PLAYER -> {
                var kick = command.getKickPlayer();
                kickHandler.accept(Protos.fromProto(kick.getUuid()),
                        message(null, kick.getMessageKey(), kick.getPlaceholdersMap()));
            }
            case RELOAD_MESSAGES -> {
                connection.loadMessages();
                LOG.info("Sprachdateien neu geladen");
            }
            default -> LOG.debug("Befehl {} ist fuer einen Minestom-Server nicht relevant",
                    command.getCommandCase());
        }
    }

    /**
     * Schickt einen Spieler auf einen anderen Server der Cloud.
     *
     * <p>Dasselbe wie im Paper-Teil: Ausgefuehrt wird es vom Proxy, ein Gameserver kann
     * niemanden selbst weiterreichen. Der Master laesst es nur fuer Spieler zu, die auf
     * diesem Server sind.
     *
     * <p>Laeuft im Hintergrund - der Aufruf kehrt sofort zurueck.
     */
    public void switchServer(java.util.UUID uuid, String name, String target) {
        if (connection == null) {
            LOG.warn("Kein Cloud-Kontakt - {} bleibt auf diesem Server", name);
            return;
        }
        Thread.ofVirtual().name("vibecloud-switch-" + name).start(() -> {
            var answer = connection.movePlayer(uuid, target);
            if (answer.isEmpty()) {
                LOG.warn("Wechsel von {} nach {} fehlgeschlagen: Master nicht erreichbar",
                        name, target);
                return;
            }
            if (!answer.get().getAccepted()) {
                LOG.warn("Wechsel von {} nach {} abgelehnt: {}",
                        name, target, answer.get().getReason());
            }
        });
    }

    @Override
    public void onDisconnected() {
        LOG.warn("Verbindung zum Master verloren. Der Server laeuft weiter.");
    }
}
