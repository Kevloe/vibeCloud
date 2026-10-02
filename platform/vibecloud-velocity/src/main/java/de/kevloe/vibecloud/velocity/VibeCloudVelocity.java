package de.kevloe.vibecloud.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.plugin.CloudConnection;
import de.kevloe.vibecloud.api.plugin.PluginModuleChannel;
import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import de.kevloe.vibecloud.protocol.BackendServer;
import de.kevloe.vibecloud.protocol.LoginResponse;
import de.kevloe.vibecloud.protocol.Platform;
import de.kevloe.vibecloud.protocol.PlayerJoinedNetwork;
import de.kevloe.vibecloud.protocol.PlayerLeftNetwork;
import de.kevloe.vibecloud.protocol.PlayerRef;
import de.kevloe.vibecloud.protocol.PlayerSwitchedServer;
import de.kevloe.vibecloud.protocol.ServerCommand;
import de.kevloe.vibecloud.protocol.ServerEvent;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.ServerRegisterResponse;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-Plugin (PLAN.md Abschnitt 11).
 *
 * <p>Aufgaben: Backend-Server zur Laufzeit registrieren, Login-Gate beim Master abfragen,
 * Spieler zum Einstiegsziel schicken und Spieler-Ereignisse melden.
 *
 * <p><b>Nur Velocity-API.</b> Kein Bukkit, kein Minestom (PLAN.md Abschnitt 4).
 */
@Plugin(
        id = "vibecloud",
        name = "vibeCloud",
        version = "0.1.0",
        description = "Proxy-Anbindung an die vibeCloud",
        authors = {"kevloe"})
public final class VibeCloudVelocity implements CloudConnection.CommandHandler {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private CloudConnection connection;
    private PluginModuleChannel moduleChannel;
    private CloudCommands cloudCommands;
    private CloudPermissions permissions;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** Was der Master je Spieler beim Login entschieden hat (Ziel und Sprache). */
    private final Map<UUID, LoginResponse> loginDecisions = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> sessionStart = new ConcurrentHashMap<>();

    /** Vom Master gemeldeter Wartungsmodus - nur fuer die Anzeige im MOTD.
     *  Ueber den Login entscheidet der Master beim CheckLogin, nicht dieses Flag. */
    private volatile boolean maintenance;

    /**
     * Logins erlauben, wenn der Master nicht erreichbar ist?
     *
     * <p>Standard ist {@code false} - so steht es in PLAN.md Entscheidung 17.4: Ohne
     * Master gibt es keine verlaessliche Ban-Pruefung, und wer in dieser Luecke
     * reinkommt, hat unklare Rechte.
     *
     * <p>Mit {@code true} entscheidet der Proxy aus dem Snapshot - die 🔧-Alternative
     * aus Abschnitt 7. Erst dieser Schalter gibt dem Snapshot seinen eigentlichen Zweck.
     */
    private boolean offlineLogins;

    private java.util.concurrent.ScheduledExecutorService snapshotWriter;

    @Inject
    public VibeCloudVelocity(ProxyServer proxy, Logger logger,
                             @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        // Die Verbindungsdatei liegt im Arbeitsverzeichnis des Servers - und das ist bei
        // Velocity das Server-Root selbst. Kein Herumklettern ueber den Plugin-Ordner.
        Optional<CloudConnection.ConnectionFile> file =
                CloudConnection.readConnectionFile(Path.of("."));

        if (file.isEmpty()) {
            logger.warn("vibeCloud ist inaktiv - dieser Proxy laeuft nicht unter der Cloud.");
            return;
        }

        permissions = new CloudPermissions(file.get().groupName, file.get().serverName);
        offlineLogins = readOfflineLoginsSetting();

        // Warmer Start: Rang und Rechte sind sofort da, statt erst nach der ersten
        // Master-Antwort. Aelter als zehn Minuten wird verworfen, damit keine
        // veralteten Rechte gelten.
        permissions.loadSnapshot(snapshotFile(), java.time.Duration.ofMinutes(10));
        startSnapshotWriter();

        connection = new CloudConnection(file.get(), ServerPlatform.SERVER_PLATFORM_VELOCITY,
                proxy.getConfiguration().getShowMaxPlayers(), this);
        moduleChannel = new PluginModuleChannel(connection);
        cloudCommands = new CloudCommands(proxy, this, logger, connection);
        cloudCommands.registerHelp();
        connection.connectAsync();
        registerLanguageCommand();
        logger.info("vibeCloud verbindet sich zum Master ...");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        writeSnapshot();
        if (snapshotWriter != null) {
            snapshotWriter.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    // ---------------------------------------------------------------- Snapshot

    private Path snapshotFile() {
        return dataDirectory.resolve("snapshot.bin");
    }

    /**
     * Schreibt den Snapshot regelmaessig mit.
     *
     * <p>Eine Minute Takt ist bewusst grob: Der Snapshot ist ein warmer Start, keine
     * Datenbank. Haeufiger zu schreiben wuerde nur Platten-I/O fuer nichts erzeugen.
     */
    private void startSnapshotWriter() {
        snapshotWriter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("vibecloud-snapshot").factory());
        snapshotWriter.scheduleAtFixedRate(this::writeSnapshot, 1, 1,
                java.util.concurrent.TimeUnit.MINUTES);
    }

    private void writeSnapshot() {
        if (permissions == null) {
            return;
        }
        try {
            permissions.saveSnapshot(snapshotFile());
        } catch (java.io.IOException exception) {
            logger.warn("Snapshot konnte nicht geschrieben werden: {}",
                    exception.getMessage());
        }
    }

    /**
     * Liest {@code offline-logins} aus {@code config.properties}.
     *
     * <p>Absichtlich eine einzelne Datei mit einem Schluessel statt eines
     * Konfigurations-Frameworks - es gibt genau diese eine Einstellung.
     */
    private boolean readOfflineLoginsSetting() {
        Path file = dataDirectory.resolve("config.properties");
        try {
            if (java.nio.file.Files.notExists(file)) {
                java.nio.file.Files.createDirectories(dataDirectory);
                java.nio.file.Files.writeString(file, """
                        # Logins erlauben, wenn der Master nicht erreichbar ist?
                        #
                        # false (Standard, PLAN.md 17.4): Logins werden abgelehnt. Ohne
                        #   Master gibt es keine verlaessliche Ban-Pruefung.
                        # true: Der Proxy entscheidet aus dem letzten Snapshot. Spieler
                        #   koennen weiter joinen, aber Bans und Raenge sind nur so
                        #   aktuell wie der Snapshot.
                        offline-logins=false
                        """);
                return false;
            }
            var properties = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(file)) {
                properties.load(in);
            }
            boolean value = Boolean.parseBoolean(
                    properties.getProperty("offline-logins", "false"));
            if (value) {
                logger.warn("offline-logins ist aktiv: Bei einem Master-Ausfall duerfen "
                            + "Spieler aus dem Snapshot joinen. Bans sind dann nur so "
                            + "aktuell wie der Snapshot.");
            }
            return value;
        } catch (java.io.IOException exception) {
            logger.warn("config.properties nicht lesbar, es gilt offline-logins=false");
            return false;
        }
    }

    // ---------------------------------------------------------------- Master -> Proxy

    @Override
    public void onConnected(ServerRegisterResponse response) {
        // Die beim Start schon laufenden Server registrieren. Ohne das kennt der Proxy
        // keinen Backend-Server und kann niemanden verbinden.
        response.getServersList().forEach(this::registerBackend);
        cloudCommands.replaceAll(response.getCommandsList());
        maintenance = response.getMaintenance();
        logger.info("{} Backend-Server vom Master uebernommen", response.getServersCount());
    }

    @Override
    public void onCommand(ServerCommand command) {
        switch (command.getCommandCase()) {
            case ADD_SERVER -> registerBackend(command.getAddServer().getServer());
            case REMOVE_SERVER -> unregisterBackend(command.getRemoveServer().getName());
            case KICK_PLAYER -> kick(command.getKickPlayer());
            case MODULE_MESSAGE -> moduleChannel.onMessage(command.getModuleMessage());
            case SET_COMMANDS ->
                    cloudCommands.replaceAll(command.getSetCommands().getCommandsList());
            case SEND_MESSAGE -> sendTo(command.getSendMessage());
            case BROADCAST_MESSAGE -> broadcast(command.getBroadcastMessage());
            case TRANSFER_PLAYER -> transfer(command.getTransferPlayer());
            case RELOAD_MESSAGES -> {
                connection.loadMessages();
                logger.info("Sprachdateien neu geladen");
            }
            case SET_MAINTENANCE -> {
                var change = command.getSetMaintenance();
                if (change.getGlobal()) {
                    maintenance = change.getActive();
                    logger.info("Wartungsmodus {}", change.getActive()
                            ? "aktiv - neue Logins werden abgelehnt" : "aufgehoben");
                } else {
                    logger.info("Gruppe {} ist {} in Wartung", change.getGroupName(),
                            change.getActive() ? "jetzt" : "nicht mehr");
                }
            }
            case PLAYER_DATA_CHANGED -> reloadPermissions(command.getPlayerDataChanged());
            case COMMAND_NOT_SET -> logger.warn("Leerer Befehl vom Master");
        }
    }

    @Override
    public void onDisconnected() {
        logger.warn("Verbindung zum Master verloren. Spieler bleiben online, aber neue "
                    + "Logins werden abgelehnt, bis der Master zurueck ist.");
    }

    private void registerBackend(BackendServer server) {
        ServerInfo info = new ServerInfo(server.getName(),
                new InetSocketAddress(server.getHost(), server.getPort()));

        // Doppelte Registrierung vermeiden: Velocity wirft dabei keine Ausnahme, aber der
        // alte Eintrag wuerde mit veralteter Adresse bestehen bleiben.
        proxy.getServer(server.getName())
                .ifPresent(existing -> proxy.unregisterServer(existing.getServerInfo()));

        proxy.registerServer(info);
        logger.info("Server {} registriert ({}:{})",
                server.getName(), server.getHost(), server.getPort());
    }

    private void unregisterBackend(String name) {
        proxy.getServer(name).ifPresent(server -> {
            proxy.unregisterServer(server.getServerInfo());
            logger.info("Server {} abgemeldet", name);

            // Spieler, die noch dort sind, auf ein anderes Ziel schieben statt sie
            // im Nichts haengen zu lassen.
            server.getPlayersConnected().forEach(this::moveToFallback);
        });
    }

    private void moveToFallback(Player player) {
        LoginResponse decision = loginDecisions.get(player.getUniqueId());
        String target = decision == null ? "" : decision.getTargetServer();
        Optional<RegisteredServer> fallback = target.isBlank()
                ? proxy.getAllServers().stream().findFirst()
                : proxy.getServer(target);

        if (fallback.isPresent()) {
            player.createConnectionRequest(fallback.get()).fireAndForget();
            player.sendMessage(message(player, "server.stopping", Map.of()));
        } else {
            player.disconnect(message(player, "login.no_fallback", Map.of()));
        }
    }

    private void kick(de.kevloe.vibecloud.protocol.KickPlayer command) {
        proxy.getPlayer(Protos.fromProto(command.getUuid())).ifPresent(player ->
                player.disconnect(message(player, command.getMessageKey(),
                        command.getPlaceholdersMap())));
    }

    /**
     * Schickt einen Spieler auf einen anderen Server.
     *
     * <p>Fuer Proxy-Plugins. Hier geschieht es direkt - der Proxy verwaltet die
     * Verbindungen selbst und muss den Master nicht fragen. Das Ergebnis ist dasselbe wie
     * bei einem Wechsel aus einem Gameserver oder ueber die Konsole.
     *
     * @return false, wenn es den Server nicht gibt
     */
    public boolean switchServer(Player player, String target) {
        var server = proxy.getServer(target);
        if (server.isEmpty()) {
            logger.warn("{} sollte nach {} wechseln - diesen Server gibt es hier nicht",
                    player.getUsername(), target);
            player.sendMessage(message(player, "server.not_found", Map.of("server", target)));
            return false;
        }
        player.createConnectionRequest(server.get()).fireAndForget();
        return true;
    }

    /** Modul-Kanal zum Master - fuer die Proxy-Teile von Modulen. */
    public PluginModuleChannel moduleChannel() {
        return moduleChannel;
    }

    private void sendTo(de.kevloe.vibecloud.protocol.SendMessage command) {
        // Kein Treffer ist normal: Der Befehl geht an alle Proxys, der Spieler ist auf einem.
        proxy.getPlayer(Protos.fromProto(command.getUuid())).ifPresent(player ->
                player.sendMessage(message(player, command.getMessageKey(),
                        command.getPlaceholdersMap())));
    }

    /**
     * Nachricht an alle mit einer Berechtigung.
     *
     * <p>{@code hasPermission} greift auf die vom Master gelieferte Auswertung zu - der
     * Proxy entscheidet nicht selbst, wer etwas darf (PLAN.md Abschnitt 13).
     */
    private void broadcast(de.kevloe.vibecloud.protocol.BroadcastMessage command) {
        for (Player player : proxy.getAllPlayers()) {
            if (player.hasPermission(command.getPermission())) {
                player.sendMessage(message(player, command.getMessageKey(),
                        command.getPlaceholdersMap()));
            }
        }
    }

    private void transfer(de.kevloe.vibecloud.protocol.TransferPlayer command) {
        proxy.getPlayer(Protos.fromProto(command.getUuid())).ifPresent(player ->
                proxy.getServer(command.getTargetServer()).ifPresentOrElse(
                        target -> player.createConnectionRequest(target).fireAndForget(),
                        () -> player.sendMessage(message(player, "server.not_found",
                                Map.of("server", command.getTargetServer())))));
    }

    /**
     * MOTD in der Serverliste.
     *
     * <p>Zeigt den Wartungsmodus an, damit Spieler es sehen, bevor sie sich
     * verbinden - statt erst beim Login eine Absage zu bekommen.
     */
    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (connection == null) {
            return;
        }
        String key = maintenance ? "motd.maintenance" : "motd.line1";
        event.setPing(event.getPing().asBuilder()
                .description(render(null, key, Map.of()))
                .build());
    }

    // ---------------------------------------------------------------- Rechte

    /**
     * Haengt die Cloud-Rechte in Velocity ein.
     *
     * <p>Damit funktioniert {@code source.hasPermission(...)} und damit auch jede
     * Rechtepruefung fremder Plugins - ohne dass sie von der Cloud wissen muessen.
     *
     * <p>{@link Tristate#UNDEFINED} statt {@code FALSE} bei unbekannten Knoten: So kann ein
     * anderes Plugin noch entscheiden. Nur was die Cloud ausdruecklich verbietet, wird
     * {@code FALSE}.
     */
    @Subscribe
    public void onPermissionsSetup(PermissionsSetupEvent event) {
        if (permissions == null || !(event.getSubject() instanceof Player player)) {
            return;
        }
        event.setProvider(subject -> (PermissionFunction) node -> {
            if (!permissions.knows(player.getUniqueId())) {
                return Tristate.UNDEFINED;
            }
            return permissions.explain(player.getUniqueId(), node)
                    .map(candidate -> candidate.entry().value() ? Tristate.TRUE : Tristate.FALSE)
                    .orElse(Tristate.UNDEFINED);
        });
    }

    /** Laedt Rang und Rechte eines Spielers beim Master. */
    private void loadPermissions(Player player) {
        connection.fetchPlayerData(player.getUniqueId(), player.getUsername())
                .ifPresent(data -> {
                    permissions.update(data);
                    // Rang oder Sprache koennen sich geaendert haben: Kopf und Fuss neu
                    // rendern, damit die Sprache sofort stimmt.
                    applyTab(player, proxy.getPlayerCount());
                });
    }

    private void reloadPermissions(de.kevloe.vibecloud.protocol.PlayerDataChanged changed) {
        if (changed.getAllPlayers()) {
            permissions.forgetAll();
            proxy.getAllPlayers().forEach(this::loadPermissions);
            logger.info("Rechte aller Spieler neu geladen");
            return;
        }
        UUID uuid = Protos.fromProto(changed.getUuid());
        proxy.getPlayer(uuid).ifPresentOrElse(
                this::loadPermissions,
                () -> permissions.forget(uuid));
    }

    /**
     * Kopf und Fuss der Tab-Liste.
     *
     * <p>Zwei Dinge, die vorher falsch waren: Es hing am Rang des Spielers - obwohl Kopf
     * und Fuss fuer alle gleich sind -, und es wurde im {@code PostLoginEvent} gesendet.
     * Dort ist der Client noch nicht im Spiel-Zustand und verwirft die Pakete still.
     * Deshalb erschien die Anzeige erst nach einer Rang-Aenderung: Die kommt spaeter, wenn
     * der Spieler wirklich im Spiel ist.
     *
     * <p>Die Texte stehen in {@code messages/de.yml} unter {@code tab.*} und sind damit
     * ohne Rebuild aenderbar.
     *
     * @param online wie viele Spieler zu zaehlen sind - beim Verlassen einer weniger, weil
     *               Velocity den Spieler da noch in der Liste fuehrt
     */
    private void applyTab(Player player, int online) {
        try {
            Map<String, String> values = Map.of("online", String.valueOf(Math.max(0, online)));
            player.sendPlayerListHeaderAndFooter(
                    message(player, "tab.header", values),
                    message(player, "tab.footer", values));
        } catch (RuntimeException exception) {
            logger.debug("Tab-Anzeige fuer {} nicht gesetzt", player.getUsername(), exception);
        }
    }

    /**
     * Setzt die Tab-Anzeige bei allen neu.
     *
     * <p>Noetig bei jedem Join und Quit: Im Fuss steht die Spielerzahl, und die aendert
     * sich fuer alle - nicht nur fuer den, der gerade kommt oder geht.
     */
    private void updateTabForAll(int online) {
        proxy.getAllPlayers().forEach(player -> applyTab(player, online));
    }

    // ---------------------------------------------------------------- /language

    /**
     * {@code /language [kuerzel]} (PLAN.md Abschnitt 11a).
     *
     * <p>Ohne Argument zeigt es die verfuegbaren Sprachen, mit Argument wechselt es und
     * speichert die Wahl beim Master - sie gilt danach netzwerkweit.
     */
    private void registerLanguageCommand() {
        var meta = proxy.getCommandManager().metaBuilder("language")
                .aliases("lang", "sprache")
                .plugin(this)
                .build();

        proxy.getCommandManager().register(meta,
                (com.velocitypowered.api.command.SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text(
                        "Dieser Befehl ist fuer Spieler."));
                return;
            }
            if (!player.hasPermission("vibecloud.language")) {
                player.sendMessage(message(player, "language.no_permission", Map.of()));
                return;
            }

            var available = connection.messages()
                    .map(bundle -> String.join(", ", bundle.availableLocales()))
                    .orElse("-");

            if (invocation.arguments().length == 0) {
                String current = permissions.localeOf(player.getUniqueId())
                        .orElseGet(() -> connection.messages()
                                .map(bundle -> bundle.defaultLocale()).orElse("de"));
                player.sendMessage(message(player, "language.current",
                        Map.of("sprache", current)));
                player.sendMessage(message(player, "language.available",
                        Map.of("liste", available)));
                return;
            }

            String wanted = invocation.arguments()[0].toLowerCase(java.util.Locale.ROOT);
            var result = connection.setLocale(player.getUniqueId(), wanted);

            if (result.isEmpty() || !result.get().getAccepted()) {
                player.sendMessage(message(player, "language.unknown",
                        Map.of("eingabe", wanted, "liste", available)));
                return;
            }
            // Neu laden, damit die Sprache sofort fuer weitere Meldungen gilt.
            loadPermissions(player);
            player.sendMessage(message(player, "language.changed", Map.of("sprache", wanted)));
        });
    }

    // ---------------------------------------------------------------- Login-Gate

    /**
     * Fragt beim Master, ob der Spieler rein darf.
     *
     * <p>Hier haengt sich ab M6 das punishment-Modul ein - der Proxy weiss nichts von Bans,
     * er fragt nur (PLAN.md Abschnitt 10a).
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (connection == null) {
            return;
        }
        String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
        UUID uuid = event.getUniqueId() != null
                ? event.getUniqueId()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + event.getUsername())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        LoginResponse response = connection.checkLogin(uuid, event.getUsername(), ip,
                Platform.PLATFORM_JAVA, null);

        // Master nicht erreichbar: Standardmaessig ablehnen (PLAN.md 17.4). Nur mit
        // offline-logins=true wird aus dem Snapshot entschieden.
        if (!response.getAllowed() && "login.master_offline".equals(
                response.getDenyMessageKey()) && offlineLogins) {
            if (permissions.knows(uuid)) {
                logger.info("{} kommt aus dem Snapshot rein - der Master ist nicht "
                            + "erreichbar", event.getUsername());
                return;
            }
            logger.info("{} wird abgelehnt: Master offline und kein Snapshot-Eintrag",
                    event.getUsername());
        }

        if (!response.getAllowed()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    render(response.getLocale(), response.getDenyMessageKey(),
                            response.getPlaceholdersMap())));
            return;
        }
        loginDecisions.put(uuid, response);
    }

    /** Schickt den Spieler auf das Ziel, das der Master beim Login bestimmt hat. */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        LoginResponse decision = loginDecisions.get(event.getPlayer().getUniqueId());
        if (decision == null || decision.getTargetServer().isBlank()) {
            return;
        }
        proxy.getServer(decision.getTargetServer()).ifPresentOrElse(
                event::setInitialServer,
                () -> logger.warn("Der Master nannte {} als Ziel, aber dieser Server ist "
                                  + "hier nicht registriert", decision.getTargetServer()));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        sessionStart.put(player.getUniqueId(), Instant.now());
        reportPlayerCount();
        // Rechte holen, damit hasPermission sofort stimmt.
        loadPermissions(player);

        LoginResponse decision = loginDecisions.get(player.getUniqueId());
        connection.publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setPlayerJoined(PlayerJoinedNetwork.newBuilder()
                        .setPlayer(playerRef(player))
                        .setIp(player.getRemoteAddress().getAddress().getHostAddress())
                        .setInitialServer(decision == null ? "" : decision.getTargetServer())
                        .setClientLocale(player.getEffectiveLocale() == null
                                ? "" : player.getEffectiveLocale().toString()))
                .build());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        Instant started = sessionStart.remove(player.getUniqueId());
        loginDecisions.remove(player.getUniqueId());
        permissions.forget(player.getUniqueId());
        reportPlayerCount();
        // Velocity fuehrt den Spieler hier noch in der Liste - deshalb einer weniger,
        // sonst stuende im Fuss dauerhaft eine Zahl zu viel.
        updateTabForAll(proxy.getPlayerCount() - 1);

        long seconds = started == null ? 0 : Duration.between(started, Instant.now()).toSeconds();
        connection.publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setPlayerLeft(PlayerLeftNetwork.newBuilder()
                        .setPlayer(playerRef(player))
                        .setSessionSeconds(seconds))
                .build());
    }

    /**
     * Tab-Anzeige, sobald der Spieler wirklich auf einem Server ist.
     *
     * <p>{@code ServerPostConnectEvent} und nicht {@code PostLoginEvent}: Dort steckt der
     * Client noch im Login-Zustand und verwirft Kopf und Fuss still. Und nicht
     * {@code ServerConnectedEvent} - das feuert, bevor der Wechsel beim Client angekommen
     * ist.
     */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() == null) {
            // Erster Join: Die Spielerzahl hat sich fuer alle geaendert.
            updateTabForAll(proxy.getPlayerCount());
        } else {
            // Nur ein Serverwechsel - die Zahl bleibt, aber der Client hat die Anzeige
            // beim Wechsel verloren und braucht sie erneut.
            applyTab(event.getPlayer(), proxy.getPlayerCount());
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        connection.publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setPlayerSwitched(PlayerSwitchedServer.newBuilder()
                        .setPlayer(playerRef(event.getPlayer()))
                        .setFromServer(event.getPreviousServer()
                                .map(server -> server.getServerInfo().getName()).orElse(""))
                        .setToServer(event.getServer().getServerInfo().getName()))
                .build());
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private void reportPlayerCount() {
        if (connection != null) {
            connection.reportPlayerCount(proxy.getPlayerCount());
        }
    }

    private static PlayerRef playerRef(Player player) {
        return PlayerRef.newBuilder()
                .setUuid(Protos.toProto(player.getUniqueId()))
                .setName(player.getUsername())
                .setPlatform(Platform.PLATFORM_JAVA)
                .build();
    }

    /** Nachricht in der Sprache des Spielers. Texte kommen immer vom Master. */
    private Component message(Player player, String key, Map<String, String> placeholders) {
        // Gespeicherte Wahl zuerst, dann die beim Login ermittelte Sprache.
        String locale = permissions.localeOf(player.getUniqueId()).orElseGet(() -> {
            LoginResponse decision = loginDecisions.get(player.getUniqueId());
            return decision == null ? null : decision.getLocale();
        });
        return render(locale, key, placeholders);
    }

    private Component render(String locale, String key, Map<String, String> placeholders) {
        return connection.messages()
                .map(bundle -> miniMessage.deserialize(
                        bundle.get(locale, key, placeholders)))
                // Faellt das Bundle aus, wird der Schluessel angezeigt - unschoen, aber
                // nachvollziehbar. Ein leerer Bildschirm waere schlimmer.
                .orElseGet(() -> Component.text(key));
    }
}
