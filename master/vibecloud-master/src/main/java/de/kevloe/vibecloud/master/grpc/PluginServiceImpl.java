package de.kevloe.vibecloud.master.grpc;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.events.PlayerJoinNetworkEvent;
import de.kevloe.vibecloud.api.event.events.PlayerQuitNetworkEvent;
import de.kevloe.vibecloud.api.event.events.PlayerSwitchServerEvent;
import de.kevloe.vibecloud.api.identity.CallIdentity;
import de.kevloe.vibecloud.api.identity.ServerIdentity;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.common.message.MessageBundle;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.message.MessageService;
import de.kevloe.vibecloud.master.module.ModuleChannelRouter;
import de.kevloe.vibecloud.master.console.CommandRegistry;
import de.kevloe.vibecloud.master.console.PlayerCommandService;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.OnlinePlayers;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.server.FallbackSelector;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.settings.CloudSettings;
import de.kevloe.vibecloud.protocol.BackendServer;
import de.kevloe.vibecloud.protocol.CloudCommand;
import de.kevloe.vibecloud.protocol.CommandLevel;
import de.kevloe.vibecloud.protocol.CommandLine;
import de.kevloe.vibecloud.protocol.CommandRunRequest;
import de.kevloe.vibecloud.protocol.CommandRunResponse;
import de.kevloe.vibecloud.protocol.CommandSuggestRequest;
import de.kevloe.vibecloud.protocol.CommandSuggestResponse;
import de.kevloe.vibecloud.protocol.SetCommands;
import de.kevloe.vibecloud.protocol.LoginRequest;
import de.kevloe.vibecloud.protocol.LoginResponse;
import de.kevloe.vibecloud.master.server.PlayerTransferService;
import de.kevloe.vibecloud.protocol.MessageMap;
import de.kevloe.vibecloud.protocol.MovePlayerRequest;
import de.kevloe.vibecloud.protocol.MovePlayerResponse;
import de.kevloe.vibecloud.protocol.MessagesRequest;
import de.kevloe.vibecloud.protocol.PermissionRule;
import de.kevloe.vibecloud.protocol.PlayerData;
import de.kevloe.vibecloud.protocol.PlayerDataRequest;
import de.kevloe.vibecloud.protocol.PluginServiceGrpc;
import de.kevloe.vibecloud.protocol.RankData;
import de.kevloe.vibecloud.protocol.SetLocaleRequest;
import de.kevloe.vibecloud.protocol.SetLocaleResponse;
import de.kevloe.vibecloud.protocol.ServerCommand;
import de.kevloe.vibecloud.protocol.ServerEvent;
import de.kevloe.vibecloud.protocol.ServerRegisterRequest;
import de.kevloe.vibecloud.protocol.ServerRegisterResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Master-Seite der Plugin-Verbindung (PLAN.md Abschnitt 11).
 *
 * <p>Befugnis: Ein Plugin ist {@code SERVER:<name>} und darf <b>nur ueber sich selbst</b>
 * sprechen. Netzwerkweite Spieler-Ereignisse darf ausschliesslich ein Proxy melden - ein
 * kompromittierter Gameserver koennte sonst Logins fuer beliebige UUIDs erfinden
 * (PLAN.md Abschnitt 13).
 */
public final class PluginServiceImpl extends PluginServiceGrpc.PluginServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(PluginServiceImpl.class);

    private final ServerSessionStore sessions;
    private final PluginConnectionRegistry plugins;
    private final NodeRegistry nodes;
    private final ServerRegistry servers;
    private final ServerGroupRepository groups;
    private final FallbackSelector fallback;
    private final MessageService messages;
    private final CloudSettings settings;
    private final EventBus events;
    private final AuditLog audit;
    private final PlayerService playerService;
    private final OnlinePlayers online;
    /** Erst nach dem Laden der Module gesetzt - sie kommen nach dem gRPC-Server. */
    private volatile ModuleChannelRouter moduleChannel;
    private final PermissionService permissions;

    /**
     * Befehle aus dem Spiel. Erst nach den Modulen gesetzt, darum nicht final -
     * Module bringen eigene Befehle mit.
     */
    private volatile PlayerCommandService playerCommands;

    /** Serverwechsel. Wie der Befehlsdienst erst nach dem Bootstrap gesetzt. */
    private volatile PlayerTransferService transfers;
    private final RankRepository ranks;

    public PluginServiceImpl(ServerSessionStore sessions, PluginConnectionRegistry plugins,
                             NodeRegistry nodes, ServerRegistry servers,
                             ServerGroupRepository groups,
                             FallbackSelector fallback, MessageService messages,
                             CloudSettings settings, EventBus events, AuditLog audit,
                             PlayerService playerService, OnlinePlayers online, PermissionService permissions,
                             RankRepository ranks) {
        this.sessions = sessions;
        this.plugins = plugins;
        this.nodes = nodes;
        this.servers = servers;
        this.groups = groups;
        this.fallback = fallback;
        this.messages = messages;
        this.settings = settings;
        this.events = events;
        this.audit = audit;
        this.playerService = playerService;
        this.online = online;
        this.permissions = permissions;
        this.ranks = ranks;
    }

    public void setTransfers(PlayerTransferService transfers) {
        this.transfers = transfers;
    }

    /** Setzt den Befehlsdienst und verteilt die Liste an die schon verbundenen Proxys. */
    public void setPlayerCommands(PlayerCommandService service) {
        this.playerCommands = service;
        publishCommands();
    }

    /**
     * Schickt die aktuelle Befehlsliste an alle Proxys.
     *
     * <p>Nach dem Laden oder Entladen eines Moduls aufzurufen: Sonst zeigt der Proxy einen
     * Befehl an, den es nicht mehr gibt - oder umgekehrt.
     */
    public void publishCommands() {
        PlayerCommandService service = playerCommands;
        if (service == null) {
            return;
        }
        SetCommands.Builder update = SetCommands.newBuilder();
        service.inGameCommands().forEach(command -> update.addCommands(describe(command)));

        int delivered = plugins.broadcastToProxies(PluginConnectionRegistry.command(
                builder -> builder.setSetCommands(update)));
        if (delivered > 0) {
            LOG.debug("{} Befehle an {} Proxy(s) verteilt",
                    update.getCommandsCount(), delivered);
        }
    }

    private static CloudCommand describe(CommandRegistry.Command command) {
        return CloudCommand.newBuilder()
                .setName(command.name())
                .setDescription(command.description())
                .setUsage(command.usage())
                .setPermission(command.permission())
                .addAllSubCommands(command.subCommands())
                .build();
    }

    /** Wird vom Bootstrap nachgereicht, sobald die Module stehen. */
    public void setModuleChannel(ModuleChannelRouter router) {
        this.moduleChannel = router;
    }

    // ---------------------------------------------------------------- Anmeldung

    @Override
    public void registerServer(ServerRegisterRequest request,
                               StreamObserver<ServerRegisterResponse> responseObserver) {
        String claimed = identity().serverName();

        if (!claimed.equals(request.getServerName())) {
            // Der Name in den Metadaten und der in der Nachricht muessen uebereinstimmen.
            audit.record("SERVER:" + claimed, "authz.rejected", request.getServerName(),
                    Map.of("reason", "Servername weicht von der Anmeldung ab"));
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Servername passt nicht zur Anmeldung")
                    .asRuntimeException());
            return;
        }

        if (request.getApiVersion() < VibeCloud.MIN_SUPPORTED_API_VERSION
            || request.getApiVersion() > VibeCloud.API_VERSION) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Plugin-Protokoll %d passt nicht zum Master (%d)"
                            .formatted(request.getApiVersion(), VibeCloud.API_VERSION))
                    .asRuntimeException());
            return;
        }

        String secret = AuthInterceptor.CLAIMED_SECRET.get();
        Optional<String> token = secret == null
                ? Optional.empty()
                : sessions.redeemSecret(claimed, secret);

        if (token.isEmpty()) {
            audit.record("SERVER:" + claimed, "server.auth.failed", claimed,
                    Map.of("reason", "Secret ungueltig, verbraucht oder abgelaufen"));
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Secret ungueltig, verbraucht oder abgelaufen")
                    .asRuntimeException());
            return;
        }

        ServerPlatformType platform = fromProto(request.getPlatform());
        MessageBundle bundle = messages.bundle();

        ServerRegisterResponse.Builder response = ServerRegisterResponse.newBuilder()
                .setApiVersion(VibeCloud.API_VERSION)
                .setSessionToken(token.get())
                .setDefaultLocale(bundle.defaultLocale())
                .addAllAvailableLocales(bundle.availableLocales())
                .setMaintenance(settings.isMaintenanceActive());

        // Ein Proxy braucht sofort alle laufenden Backend-Server, sonst kann er niemanden
        // verbinden, bis der naechste Server startet.
        if (platform == ServerPlatformType.VELOCITY) {
            backendServers().forEach(response::addServers);

            // Die Befehle gleich mit: Sonst muesste der Proxy nachfragen, und bis dahin
            // waere /ban fuer die Spieler nicht vorhanden.
            PlayerCommandService service = playerCommands;
            if (service != null) {
                service.inGameCommands().forEach(
                        command -> response.addCommands(describe(command)));
            }
        }

        audit.record("SERVER:" + claimed, "server.registered", claimed,
                Map.of("platform", platform.name(), "pluginVersion", request.getPluginVersion()));
        LOG.info("Plugin von {} angemeldet ({}, {} Slots)",
                claimed, platform, request.getMaxPlayers());

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------- Befehle im Spiel

    /**
     * Ein Spieler hat einen Cloud-Befehl eingegeben.
     *
     * <p>Nur von einem Proxy: Ein Gameserver koennte sonst Befehle mit der UUID eines
     * Administrators ausloesen. Das Recht prueft der Master selbst neu - mitgeliefert wird
     * nur, wer gefragt hat (PLAN.md Abschnitt 13).
     */
    @Override
    public void runCommand(CommandRunRequest request,
                           StreamObserver<CommandRunResponse> responseObserver) {

        ServerIdentity identity = identity();
        if (!requireProxy(identity, platformOf(identity), "RunCommand")) {
            responseObserver.onNext(CommandRunResponse.newBuilder().setAllowed(false).build());
            responseObserver.onCompleted();
            return;
        }
        PlayerCommandService service = playerCommands;
        if (service == null) {
            responseObserver.onNext(CommandRunResponse.newBuilder().setAllowed(false).build());
            responseObserver.onCompleted();
            return;
        }

        PlayerCommandService.Result result = service.run(
                Protos.fromProto(request.getUuid()), request.getName(),
                request.getCommand(), request.getArgsList());

        CommandRunResponse.Builder response = CommandRunResponse.newBuilder()
                .setAllowed(result.allowed());
        for (PlayerCommandService.Line line : result.lines()) {
            response.addLines(CommandLine.newBuilder()
                    .setLevel(levelOf(line.level()))
                    .setText(line.text()));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    /** Vorschlaege fuer die naechste Stelle der Eingabe. */
    @Override
    public void suggestCommand(CommandSuggestRequest request,
                               StreamObserver<CommandSuggestResponse> responseObserver) {

        ServerIdentity identity = identity();
        PlayerCommandService service = playerCommands;

        CommandSuggestResponse.Builder response = CommandSuggestResponse.newBuilder();
        if (service != null && requireProxy(identity, platformOf(identity), "SuggestCommand")) {
            response.addAllCandidates(service.suggest(Protos.fromProto(request.getUuid()),
                    request.getCommand(), request.getArgsList()));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    /**
      * Die Plattform der aufrufenden Verbindung.
      *
      * <p>Aus der Registry, nicht aus der Anfrage: Was ein Plugin ueber sich selbst
      * behauptet, darf nicht entscheiden, ob es als Proxy gilt.
      */
    private ServerPlatformType platformOf(ServerIdentity identity) {
        return servers.find(identity.serverName())
                .map(CloudServer::platform)
                .orElse(ServerPlatformType.PAPER);
    }

    /**
     * Ein Plugin schickt einen Spieler auf einen anderen Server.
     *
     * <p>Ein Gameserver darf nur Spieler verschieben, die auf ihm sind - sonst koennte ein
     * einzelner kompromittierter Server beliebige Spieler im Netzwerk herumschieben
     * (PLAN.md Abschnitt 13). Ein Proxy darf jeden: Er verwaltet sie alle.
     *
     * <p>Grundlage ist der letzte gemeldete Aufenthaltsort. Der kann eine Sekunde alt sein;
     * im schlechtesten Fall wird ein Wechsel abgelehnt, der gerade noch erlaubt gewesen
     * waere. Die andere Richtung - einen fremden Spieler verschieben zu koennen - waere
     * schlimmer.
     */
    @Override
    public void movePlayer(MovePlayerRequest request,
                           StreamObserver<MovePlayerResponse> responseObserver) {

        ServerIdentity identity = identity();
        PlayerTransferService service = transfers;
        UUID uuid = Protos.fromProto(request.getUuid());

        if (service == null) {
            responseObserver.onNext(refuse("Der Master ist noch nicht bereit"));
            responseObserver.onCompleted();
            return;
        }
        if (!mayMove(identity, uuid)) {
            audit.record("SERVER:" + identity.serverName(), "authz.rejected",
                    uuid.toString(), Map.of("reason", "Spieler ist nicht auf diesem Server"));
            responseObserver.onNext(refuse("Dieser Spieler ist nicht auf deinem Server"));
            responseObserver.onCompleted();
            return;
        }

        Optional<String> rejected = service.rejectTarget(request.getTargetServer());
        if (rejected.isPresent()) {
            responseObserver.onNext(refuse(rejected.get()));
            responseObserver.onCompleted();
            return;
        }

        int reached = service.transfer(uuid, request.getTargetServer(),
                "SERVER:" + identity.serverName());

        responseObserver.onNext(reached > 0
                ? MovePlayerResponse.newBuilder().setAccepted(true).build()
                : refuse("Kein Proxy erreichbar"));
        responseObserver.onCompleted();
    }

    /** Proxys duerfen jeden verschieben, ein Gameserver nur seine eigenen Spieler. */
    private boolean mayMove(ServerIdentity identity, UUID uuid) {
        if (platformOf(identity) == ServerPlatformType.VELOCITY) {
            return true;
        }
        return playerService.find(uuid)
                .map(record -> identity.serverName().equals(record.lastServer()))
                .orElse(false);
    }

    private static MovePlayerResponse refuse(String reason) {
        return MovePlayerResponse.newBuilder().setAccepted(false).setReason(reason).build();
    }

    private static CommandLevel levelOf(PlayerCommandService.Level level) {
        return switch (level) {
            case INFO -> CommandLevel.COMMAND_LEVEL_INFO;
            case SUCCESS -> CommandLevel.COMMAND_LEVEL_SUCCESS;
            case WARN -> CommandLevel.COMMAND_LEVEL_WARN;
            case ERROR -> CommandLevel.COMMAND_LEVEL_ERROR;
        };
    }

    // ---------------------------------------------------------------- Dauerkanal

    @Override
    public StreamObserver<ServerEvent> serverControl(StreamObserver<ServerCommand> toPlugin) {
        ServerIdentity identity = identity();
        ServerPlatformType platform = servers.find(identity.serverName())
                .map(CloudServer::platform)
                .orElse(ServerPlatformType.PAPER);

        plugins.attach(identity.serverName(), platform, toPlugin);

        return new StreamObserver<>() {

            @Override
            public void onNext(ServerEvent event) {
                try {
                    handle(identity, platform, event);
                } catch (RuntimeException exception) {
                    LOG.error("Ereignis von {} konnte nicht verarbeitet werden",
                            identity.serverName(), exception);
                }
            }

            @Override
            public void onError(Throwable error) {
                plugins.detach(identity.serverName());
                online.proxyGone(identity.serverName());
            }

            @Override
            public void onCompleted() {
                plugins.detach(identity.serverName());
                online.proxyGone(identity.serverName());
                toPlugin.onCompleted();
            }
        };
    }

    private void handle(ServerIdentity identity, ServerPlatformType platform, ServerEvent event) {
        switch (event.getEventCase()) {
            case PLAYER_COUNT -> servers.find(identity.serverName()).ifPresent(server ->
                    servers.put(server.withPlayers(event.getPlayerCount().getPlayers())));

            case STATE_REPORT -> servers.find(identity.serverName()).ifPresent(server ->
                    servers.put(server.withPlayers(event.getStateReport().getPlayers())));

            // Netzwerkweite Spieler-Ereignisse darf nur ein Proxy melden.
            case PLAYER_JOINED -> {
                if (requireProxy(identity, platform, "PlayerJoinedNetwork")) {
                    var joined = event.getPlayerJoined();
                    online.joined(Protos.fromProto(joined.getPlayer().getUuid()),
                            joined.getPlayer().getName(), identity.serverName(),
                            joined.getInitialServer());
                    events.post(new PlayerJoinNetworkEvent(
                            Protos.fromProto(joined.getPlayer().getUuid()),
                            joined.getPlayer().getName(),
                            joined.getInitialServer()));
                    LOG.info("{} hat das Netzwerk betreten (Ziel: {})",
                            joined.getPlayer().getName(), joined.getInitialServer());
                }
            }
            case PLAYER_LEFT -> {
                if (requireProxy(identity, platform, "PlayerLeftNetwork")) {
                    var left = event.getPlayerLeft();
                    java.util.UUID leaving = Protos.fromProto(left.getPlayer().getUuid());
                    online.left(leaving);
                    // Spielzeit mitschreiben, bevor das Event rausgeht - ein Modul koennte
                    // sie im Handler schon lesen wollen.
                    playerService.logout(leaving, identity.serverName(),
                            left.getSessionSeconds());
                    events.post(new PlayerQuitNetworkEvent(leaving,
                            left.getPlayer().getName(),
                            java.time.Duration.ofSeconds(left.getSessionSeconds())));
                }
            }
            case PLAYER_SWITCHED -> {
                if (requireProxy(identity, platform, "PlayerSwitchedServer")) {
                    var switched = event.getPlayerSwitched();
                    online.switched(Protos.fromProto(switched.getPlayer().getUuid()),
                            switched.getToServer());
                    events.post(new PlayerSwitchServerEvent(
                            Protos.fromProto(switched.getPlayer().getUuid()),
                            switched.getPlayer().getName(),
                            switched.getFromServer(),
                            switched.getToServer()));
                }
            }
            case MODULE_MESSAGE -> {
                ModuleChannelRouter router = moduleChannel;
                if (router == null) {
                    LOG.warn("Modul-Nachricht von {} verworfen - die Module sind "
                             + "noch nicht geladen", identity.serverName());
                } else {
                    router.onPluginMessage(identity.serverName(),
                            event.getModuleMessage());
                }
            }
            case EVENT_NOT_SET -> LOG.warn("Leeres Ereignis von {} verworfen",
                    identity.serverName());
        }
    }

    /**
     * Stellt sicher, dass nur ein Proxy netzwerkweite Spieler-Ereignisse meldet.
     *
     * <p>Ohne diese Pruefung koennte ein kompromittierter Gameserver Logins fuer beliebige
     * UUIDs erfinden - zum Beispiel fuer einen Administrator.
     */
    private boolean requireProxy(ServerIdentity identity, ServerPlatformType platform,
                                 String what) {
        if (platform == ServerPlatformType.VELOCITY) {
            return true;
        }
        audit.record("SERVER:" + identity.serverName(), "authz.rejected",
                identity.serverName(), Map.of("reason", what + " darf nur ein Proxy melden"));
        LOG.warn("{} ({}) wollte {} melden - verworfen, das darf nur ein Proxy",
                identity.serverName(), platform, what);
        return false;
    }

    // ---------------------------------------------------------------- Login-Gate

    @Override
    public void checkLogin(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        identity();
        UUID uuid = Protos.fromProto(request.getUuid());

        MessageBundle bundle = messages.bundle();

        // 1. Spieler anlegen/aktualisieren und das abbrechbare Pre-Event ausloesen.
        //    Hier haengt sich ab M6 das punishment-Modul ein - der Core kennt keine Bans
        //    (PLAN.md Abschnitt 10a).
        PlayerService.LoginResult login = playerService.login(uuid, request.getName(),
                request.getIp(), platformName(request.getPlatform()), "");

        // Gespeicherte Wahl hat Vorrang vor der Client-Sprache.
        String locale = login.locale() != null && !login.locale().isBlank()
                ? login.locale()
                : bundle.resolveLocale(request.getClientLocale());

        LoginResponse.Builder response = LoginResponse.newBuilder().setLocale(locale);

        if (!login.allowed()) {
            responseObserver.onNext(response
                    .setAllowed(false)
                    .setDenyMessageKey(login.reasonKey())
                    .putAllPlaceholders(login.placeholders())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // 2. Wartungsmodus - mit Bypass. Der Master loest die Permission selbst auf; das
        //    Plugin uebertraegt nie ein "darf das"-Flag (PLAN.md Abschnitt 13).
        if (settings.isMaintenanceActive()
            && !permissions.has(uuid, "vibecloud.maintenance.bypass")) {
            LOG.debug("{} abgewiesen: Wartungsmodus", request.getName());
            responseObserver.onNext(response
                    .setAllowed(false)
                    .setDenyMessageKey("login.maintenance")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // 3. Ziel bestimmen. Gibt es keins, ist ein Join sinnlos - dann lieber eine klare
        //    Meldung als ein Timeout beim Verbinden.
        Optional<String> target = fallback.choose();
        if (target.isEmpty()) {
            responseObserver.onNext(response
                    .setAllowed(false)
                    .setDenyMessageKey("login.no_fallback")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        LOG.debug("{} ({}) darf joinen, Ziel {}", request.getName(), uuid, target.get());
        responseObserver.onNext(response
                .setAllowed(true)
                .setTargetServer(target.get())
                .build());
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------- Sprachen

    @Override
    public void getMessages(MessagesRequest request,
                            StreamObserver<de.kevloe.vibecloud.protocol.MessageBundle> observer) {
        identity();
        MessageBundle bundle = messages.bundle();

        var response = de.kevloe.vibecloud.protocol.MessageBundle.newBuilder()
                .setDefaultLocale(bundle.defaultLocale());

        for (String locale : bundle.availableLocales()) {
            if (!request.getLocale().isBlank() && !request.getLocale().equals(locale)) {
                continue;
            }
            response.putLocales(locale, MessageMap.newBuilder()
                    .putAllEntries(bundle.entriesOf(locale))
                    .build());
        }
        observer.onNext(response.build());
        observer.onCompleted();
    }

    // ---------------------------------------------------------------- Spielerdaten

    /**
     * Rang und Rechte eines Spielers.
     *
     * <p>Das Plugin fragt beim Join und nach jeder Aenderungsmeldung. Uebertragen werden
     * die <b>Regeln</b>, nicht fertige Ja/Nein-Antworten: Welche Regel gilt, haengt vom
     * Server ab, auf dem der Spieler gerade ist.
     */
    @Override
    public void getPlayerData(PlayerDataRequest request,
                              StreamObserver<PlayerData> responseObserver) {
        identity();
        UUID uuid = Protos.fromProto(request.getUuid());

        Optional<PlayerRepository.PlayerRecord> record = playerService.find(uuid);
        if (record.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Spieler " + uuid + " ist dem Master nicht bekannt")
                    .asRuntimeException());
            return;
        }

        PlayerRepository.PlayerRecord player = record.get();
        PlayerData.Builder data = PlayerData.newBuilder()
                .setUuid(request.getUuid())
                .setName(player.name())
                .setPlatform("BEDROCK".equals(player.platform())
                        ? de.kevloe.vibecloud.protocol.Platform.PLATFORM_BEDROCK
                        : de.kevloe.vibecloud.protocol.Platform.PLATFORM_JAVA)
                .setLocale(player.locale() == null ? "" : player.locale());

        ranks.find(player.rankId()).ifPresent(rank -> data.setRank(RankData.newBuilder()
                .setId(rank.id())
                .setName(rank.name())
                .setDisplayName(rank.displayName())
                .setPrefix(rank.prefix())
                .setSuffix(rank.suffix())
                .setColor(rank.color())
                .setWeight(rank.weight())
                .setChatFormat(rank.chatFormat() == null ? "" : rank.chatFormat())));

        for (var candidate : permissions.resolve(uuid).candidates()) {
            var entry = candidate.entry();
            data.addRules(PermissionRule.newBuilder()
                    .setNode(entry.node())
                    .setValue(entry.value())
                    .setGroup(entry.context().group() == null ? "" : entry.context().group())
                    .setServer(entry.context().server() == null ? "" : entry.context().server())
                    .setExpiresEpochMillis(entry.expires() == null
                            ? 0L : entry.expires().toEpochMilli())
                    .setTier(candidate.tier().name())
                    .setWeight(candidate.weight())
                    .setSource(candidate.source()));
        }

        responseObserver.onNext(data.build());
        responseObserver.onCompleted();
    }

    /** Speichert die Sprachwahl eines Spielers ({@code /language}). */
    @Override
    public void setLocale(SetLocaleRequest request,
                          StreamObserver<SetLocaleResponse> responseObserver) {
        identity();
        UUID uuid = Protos.fromProto(request.getUuid());
        MessageBundle bundle = messages.bundle();

        boolean accepted = playerService.setLocale(uuid, request.getLocale(),
                bundle.availableLocales());

        responseObserver.onNext(SetLocaleResponse.newBuilder()
                .setAccepted(accepted)
                .setLocale(accepted ? request.getLocale() : bundle.defaultLocale())
                .addAllAvailable(bundle.availableLocales())
                .build());
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static String platformName(de.kevloe.vibecloud.protocol.Platform platform) {
        return platform == de.kevloe.vibecloud.protocol.Platform.PLATFORM_BEDROCK
                ? "BEDROCK" : "JAVA";
    }


    /** Alle laufenden Gameserver als Backend-Eintraege fuer die Proxys. */
    public Iterable<BackendServer> backendServers() {
        return servers.all().stream()
                .filter(server -> server.state() == ServerState.RUNNING)
                .filter(server -> server.platform() != ServerPlatformType.VELOCITY)
                .map(this::toBackend)
                .toList();
    }

    public BackendServer toBackend(CloudServer server) {
        Optional<ServerGroup> group = groups.find(server.groupName());
        return BackendServer.newBuilder()
                .setName(server.name())
                .setGroupName(server.groupName())
                // Die erreichbare Adresse des Nodes, nicht sein Name: Ein Proxy kann
                // "node-a" nicht auflösen.
                .setHost(nodes.addressOf(server.node()))
                .setPort(server.port())
                .setFallback(group.map(ServerGroup::fallback).orElse(false))
                .setJoinPriority(group.map(ServerGroup::joinPriority).orElse(100))
                .build();
    }

    private ServerIdentity identity() {
        CallIdentity identity = AuthInterceptor.currentIdentity();
        if (identity instanceof ServerIdentity serverIdentity) {
            return serverIdentity;
        }
        audit.record(identity.describe(), "authz.rejected", "PluginService",
                Map.of("reason", "keine Server-Identitaet"));
        throw Status.PERMISSION_DENIED
                .withDescription("Dieser Dienst ist nur fuer Server-Plugins")
                .asRuntimeException();
    }

    private static ServerPlatformType fromProto(de.kevloe.vibecloud.protocol.ServerPlatform platform) {
        return switch (platform) {
            case SERVER_PLATFORM_VELOCITY -> ServerPlatformType.VELOCITY;
            case SERVER_PLATFORM_MINESTOM -> ServerPlatformType.MINESTOM;
            default -> ServerPlatformType.PAPER;
        };
    }
}
