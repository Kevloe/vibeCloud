package de.kevloe.vibecloud.api.plugin;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.common.message.MessageBundle;
import de.kevloe.vibecloud.common.tls.FingerprintTrustManager;
import de.kevloe.vibecloud.protocol.LoginRequest;
import de.kevloe.vibecloud.protocol.LoginResponse;
import de.kevloe.vibecloud.protocol.MessagesRequest;
import de.kevloe.vibecloud.protocol.PlayerCountChanged;
import de.kevloe.vibecloud.protocol.PlayerData;
import de.kevloe.vibecloud.protocol.PlayerDataRequest;
import de.kevloe.vibecloud.protocol.PluginServiceGrpc;
import de.kevloe.vibecloud.protocol.ServerCommand;
import de.kevloe.vibecloud.protocol.CommandRunRequest;
import de.kevloe.vibecloud.protocol.CommandRunResponse;
import de.kevloe.vibecloud.protocol.CommandSuggestRequest;
import de.kevloe.vibecloud.protocol.MovePlayerRequest;
import de.kevloe.vibecloud.protocol.MovePlayerResponse;
import de.kevloe.vibecloud.protocol.ServerEvent;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.ServerRegisterRequest;
import de.kevloe.vibecloud.protocol.ServerRegisterResponse;
import de.kevloe.vibecloud.protocol.SetLocaleRequest;
import de.kevloe.vibecloud.protocol.SetLocaleResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verbindung eines Plugins zum Master (PLAN.md Abschnitt 11).
 *
 * <p>Derselbe Kern fuer Velocity, Paper und Minestom - nur die Plattform-Adapter
 * unterscheiden sich. Drei eigene Verbindungsimplementierungen wuerden dreimal dieselben
 * Fehler machen.
 *
 * <p>Anmeldung in zwei Schritten: Das Einmal-Secret aus
 * {@code vibecloud-connection.json} wird gegen ein Sitzungs-Token eingeloest. Danach gilt
 * nur noch das Token - ein verbrauchtes Secret ist wertlos.
 *
 * <p><b>Muss mit Java 17 kompilieren.</b> Das Legacy-Plugin fuer Paper 1.16 bis 26.1
 * baut diese Datei mit {@code --release 17} mit (siehe
 * {@code platform/vibecloud-paper-legacy}). Keine virtuellen Threads, keine Sequenced
 * Collections, kein Pattern-Matching-switch - der Build des Legacy-Plugins faellt sonst
 * sofort um.
 */
public final class CloudConnection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CloudConnection.class);
    private static final Gson GSON = new Gson();

    private static final long BACKOFF_START_MILLIS = 2_000;
    private static final long BACKOFF_MAX_MILLIS = 30_000;

    private final ConnectionFile file;
    private final ServerPlatform platform;
    private final int maxPlayers;
    private final CommandHandler handler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ManagedChannel channel;
    private PluginServiceGrpc.PluginServiceBlockingStub blocking;
    private StreamObserver<ServerEvent> toMaster;
    private volatile boolean connected;
    private volatile MessageBundle messages;
    private volatile String sessionToken;

    public CloudConnection(ConnectionFile file, ServerPlatform platform, int maxPlayers,
                           CommandHandler handler) {
        this.file = file;
        this.platform = platform;
        this.maxPlayers = maxPlayers;
        this.handler = handler;
    }

    /** Liest {@code vibecloud-connection.json} aus dem Arbeitsverzeichnis des Servers. */
    public static Optional<ConnectionFile> readConnectionFile(Path directory) {
        Path path = directory.resolve("vibecloud-connection.json");
        if (Files.notExists(path)) {
            LOG.warn("""
                    {} fehlt - dieser Server laeuft offenbar nicht unter vibeCloud.
                    Das Plugin bleibt inaktiv; der Server funktioniert davon unabhaengig.""",
                    path);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(GSON.fromJson(Files.readString(path), ConnectionFile.class));
        } catch (IOException | RuntimeException exception) {
            LOG.error("{} ist nicht lesbar", path, exception);
            return Optional.empty();
        }
    }

    /** Verbindet im Hintergrund und haelt die Verbindung. Blockiert nicht. */
    public void connectAsync() {
        // Ein gewoehnlicher Thread, kein virtueller: Es ist genau einer, er lebt so lange
        // wie das Plugin, und virtuelle Threads gibt es erst ab Java 21.
        Thread thread = new Thread(this::runForever, "cloud-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void runForever() {
        long backoff = BACKOFF_START_MILLIS;
        while (running.get()) {
            try {
                connectOnce();
                backoff = BACKOFF_START_MILLIS;
                // connectOnce kehrt erst zurueck, wenn der Stream endet.
            } catch (Exception exception) {
                LOG.warn("Keine Verbindung zum Master ({}). Neuer Versuch in {} s",
                        exception.getMessage(), backoff / 1000);
            }
            if (!running.get()) {
                return;
            }
            sleep(backoff);
            backoff = Math.min(BACKOFF_MAX_MILLIS, backoff * 2);
        }
    }

    private void connectOnce() throws IOException, InterruptedException {
        // Den Kanal des letzten Versuchs schliessen. Sonst bleibt bei jedem Wiederverbinden
        // einer offen liegen, bis der Garbage Collector ihn findet - und gRPC meldet das
        // dann als ERROR ("was garbage collected without being shut down").
        if (channel != null) {
            channel.shutdownNow();
        }
        channel = NettyChannelBuilder
                .forAddress(file.masterHost, file.masterPort)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(new FingerprintTrustManager(file.masterFingerprint))
                        .build())
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();

        // Erst mit dem Einmal-Secret anmelden ...
        Metadata registerMetadata = new Metadata();
        registerMetadata.put(Keys.SERVER, file.serverName);
        registerMetadata.put(Keys.SECRET, file.secret);

        ServerRegisterResponse response = PluginServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(registerMetadata))
                .registerServer(ServerRegisterRequest.newBuilder()
                        .setApiVersion(VibeCloud.API_VERSION)
                        .setServerName(file.serverName)
                        .setGroupName(file.groupName)
                        .setPlatform(platform)
                        .setPort(file.port)
                        .setMaxPlayers(maxPlayers)
                        .setPluginVersion(VibeCloud.API_VERSION + ".0")
                        .build());

        sessionToken = response.getSessionToken();
        LOG.info("Am Master angemeldet als {} (Protokoll {}, Sprachen: {})",
                file.serverName, response.getApiVersion(),
                String.join(", ", response.getAvailableLocalesList()));

        // ... danach gilt nur noch das Sitzungs-Token.
        Metadata sessionMetadata = new Metadata();
        sessionMetadata.put(Keys.SERVER, file.serverName);
        sessionMetadata.put(Keys.SESSION, sessionToken);

        blocking = PluginServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(sessionMetadata));
        var async = PluginServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(sessionMetadata));

        loadMessages();
        handler.onConnected(response);

        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        toMaster = async.serverControl(new StreamObserver<>() {

            @Override
            public void onNext(ServerCommand command) {
                try {
                    handler.onCommand(command);
                } catch (RuntimeException exception) {
                    // Ein fehlerhafter Befehl darf die Verbindung nicht abreissen lassen.
                    LOG.error("Befehl vom Master fehlgeschlagen", exception);
                }
            }

            @Override
            public void onError(Throwable error) {
                LOG.debug("Verbindung zum Master beendet: {}", error.getMessage());
                connected = false;
                handler.onDisconnected();
                finished.countDown();
            }

            @Override
            public void onCompleted() {
                connected = false;
                handler.onDisconnected();
                finished.countDown();
            }
        });
        connected = true;
        finished.await();
    }

    /** Holt die Sprachdateien und haelt sie lokal. */
    public void loadMessages() {
        try {
            var bundle = blocking.getMessages(MessagesRequest.newBuilder().build());
            Map<String, Map<String, String>> locales = new LinkedHashMap<>();
            bundle.getLocalesMap().forEach((locale, map) ->
                    locales.put(locale, new HashMap<>(map.getEntriesMap())));
            messages = new MessageBundle(bundle.getDefaultLocale(), locales);
            LOG.debug("{} Sprachen geladen", locales.size());
        } catch (RuntimeException exception) {
            LOG.error("Sprachdateien konnten nicht geladen werden", exception);
        }
    }

    /**
     * Fragt den Master, ob ein Spieler joinen darf.
     *
     * <p>Ist der Master nicht erreichbar, wird <b>abgelehnt</b>: Ohne Master gibt es keine
     * verlaessliche Ban-Pruefung und keine Rang-Auflosung, und wer in dieser Luecke
     * reinkommt, hat unklare Rechte (PLAN.md Abschnitt 7).
     */
    public LoginResponse checkLogin(java.util.UUID uuid, String name, String ip,
                                    de.kevloe.vibecloud.protocol.Platform playerPlatform,
                                    String clientLocale) {
        if (!connected || blocking == null) {
            return LoginResponse.newBuilder()
                    .setAllowed(false)
                    .setDenyMessageKey("login.master_offline")
                    .build();
        }
        try {
            return blocking.withDeadlineAfter(3, TimeUnit.SECONDS)
                    .checkLogin(LoginRequest.newBuilder()
                            .setUuid(Protos.toProto(uuid))
                            .setName(name)
                            .setIp(ip)
                            .setPlatform(playerPlatform)
                            .setClientLocale(clientLocale == null ? "" : clientLocale)
                            .build());
        } catch (RuntimeException exception) {
            LOG.warn("Login-Pruefung fuer {} fehlgeschlagen: {}", name, exception.getMessage());
            return LoginResponse.newBuilder()
                    .setAllowed(false)
                    .setDenyMessageKey("login.master_offline")
                    .build();
        }
    }

    public void publish(ServerEvent event) {
        StreamObserver<ServerEvent> target = toMaster;
        if (target == null || !connected) {
            return;
        }
        try {
            synchronized (this) {
                target.onNext(event);
            }
        } catch (RuntimeException exception) {
            LOG.debug("Ereignis nicht gesendet: {}", exception.getMessage());
            connected = false;
        }
    }

    /**
     * Holt Rang und Rechte eines Spielers.
     *
     * <p>Faellt der Aufruf aus, bleibt der Spieler ohne Rechte - das ist die sichere
     * Richtung. Eine Rechteerweiterung durch einen Ausfall waere das Gegenteil.
     */
    public Optional<PlayerData> fetchPlayerData(java.util.UUID uuid, String name) {
        if (!connected || blocking == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(blocking.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getPlayerData(PlayerDataRequest.newBuilder()
                            .setUuid(Protos.toProto(uuid))
                            .setName(name == null ? "" : name)
                            .build()));
        } catch (RuntimeException exception) {
            LOG.warn("Spielerdaten fuer {} konnten nicht geladen werden: {}",
                    name, exception.getMessage());
            return Optional.empty();
        }
    }

    /** Speichert die Sprachwahl eines Spielers ({@code /language}). */
    public Optional<SetLocaleResponse> setLocale(java.util.UUID uuid, String locale) {
        if (!connected || blocking == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(blocking.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .setLocale(SetLocaleRequest.newBuilder()
                            .setUuid(Protos.toProto(uuid))
                            .setLocale(locale)
                            .build()));
        } catch (RuntimeException exception) {
            LOG.warn("Sprachwahl konnte nicht gespeichert werden: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fuehrt einen Cloud-Befehl im Namen eines Spielers aus.
     *
     * <p>Blockiert - nur aus einem Hintergrund-Thread aufrufen. Der Master prueft das
     * Recht selbst; hier wird nur weitergegeben, wer gefragt hat.
     *
     * <p>Leer heisst "keine Antwort": Dann hat der Spieler nichts falsch gemacht, sondern
     * der Master ist nicht erreichbar.
     */
    public Optional<CommandRunResponse> runCommand(java.util.UUID uuid, String name,
                                                   String command, List<String> args) {
        if (!connected || blocking == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(blocking.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .runCommand(CommandRunRequest.newBuilder()
                            .setUuid(Protos.toProto(uuid))
                            .setName(name)
                            .setCommand(command)
                            .addAllArgs(args)
                            .build()));
        } catch (RuntimeException exception) {
            LOG.warn("Befehl '{}' konnte nicht ausgefuehrt werden: {}",
                    command, exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Vorschlaege fuer die naechste Stelle der Eingabe.
     *
     * <p>Kurze Frist: Das haengt an einem Tastendruck. Kommt keine Antwort, gibt es eben
     * keine Vorschlaege - das ist besser, als den Spieler warten zu lassen.
     */
    public List<String> suggestCommand(java.util.UUID uuid, String command,
                                       List<String> args) {
        if (!connected || blocking == null) {
            return List.of();
        }
        try {
            return blocking.withDeadlineAfter(1500, TimeUnit.MILLISECONDS)
                    .suggestCommand(CommandSuggestRequest.newBuilder()
                            .setUuid(Protos.toProto(uuid))
                            .setCommand(command)
                            .addAllArgs(args)
                            .build())
                    .getCandidatesList();
        } catch (RuntimeException exception) {
            LOG.debug("Keine Vorschlaege fuer '{}': {}", command, exception.getMessage());
            return List.of();
        }
    }

    /**
     * Schickt einen Spieler auf einen anderen Server.
     *
     * <p>Fuer die Plugin-Teile: Ein Minigame verteilt nach der Runde zurueck in die Lobby,
     * ein Hub schickt jemanden weiter. Ausgefuehrt wird es vom Proxy - ein Gameserver kann
     * einen Spieler nicht selbst weiterschicken.
     *
     * <p>Der Master laesst das nur fuer Spieler zu, die auf dem aufrufenden Server sind.
     * Ein Proxy darf jeden verschieben.
     *
     * <p>Blockiert kurz - nicht aus dem Haupt-Thread eines Gameservers aufrufen.
     *
     * @return leer, wenn der Master nicht erreichbar ist; sonst die Antwort mit Begruendung
     */
    public Optional<MovePlayerResponse> movePlayer(java.util.UUID uuid, String targetServer) {
        if (!connected || blocking == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(blocking.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .movePlayer(MovePlayerRequest.newBuilder()
                            .setUuid(Protos.toProto(uuid))
                            .setTargetServer(targetServer)
                            .build()));
        } catch (RuntimeException exception) {
            LOG.warn("Wechsel nach {} fehlgeschlagen: {}",
                    targetServer, exception.getMessage());
            return Optional.empty();
        }
    }

    public void reportPlayerCount(int players) {
        publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setPlayerCount(PlayerCountChanged.newBuilder().setPlayers(players))
                .build());
    }

    public boolean isConnected() {
        return connected;
    }

    public Optional<MessageBundle> messages() {
        return Optional.ofNullable(messages);
    }

    public ConnectionFile connectionFile() {
        return file;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        StreamObserver<ServerEvent> target = toMaster;
        if (target != null) {
            try {
                target.onCompleted();
            } catch (RuntimeException ignored) {
                // Stream war schon zu.
            }
        }
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    /** Was der Wrapper in {@code vibecloud-connection.json} geschrieben hat. */
    public static final class ConnectionFile {
        public String masterHost;
        public int masterPort;
        public String masterFingerprint;
        public String serverName;
        public String groupName;
        public String node;
        public String secret;
        public int port;
    }

    /** Was das Plugin mit den Nachrichten des Masters macht. */
    public interface CommandHandler {

        void onConnected(ServerRegisterResponse response);

        void onCommand(ServerCommand command);

        void onDisconnected();
    }

    private static final class Keys {
        static final Metadata.Key<String> SERVER =
                Metadata.Key.of("vibecloud-server", Metadata.ASCII_STRING_MARSHALLER);
        static final Metadata.Key<String> SECRET =
                Metadata.Key.of("vibecloud-secret", Metadata.ASCII_STRING_MARSHALLER);
        static final Metadata.Key<String> SESSION =
                Metadata.Key.of("vibecloud-session", Metadata.ASCII_STRING_MARSHALLER);
    }
}
