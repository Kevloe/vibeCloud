package de.kevloe.vibecloud.master;

import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.LocalEventBus;
import de.kevloe.vibecloud.api.event.events.CloudReadyEvent;
import de.kevloe.vibecloud.api.event.events.CloudShutdownEvent;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.common.config.JsonConfig;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.config.MasterConfigFile;
import de.kevloe.vibecloud.master.console.CloudCommands;
import de.kevloe.vibecloud.master.console.CloudTerminal;
import de.kevloe.vibecloud.master.console.CommandOutput;
import de.kevloe.vibecloud.master.console.CommandRegistry;
import de.kevloe.vibecloud.master.console.ConsoleBuffer;
import de.kevloe.vibecloud.master.console.GroupCommands;
import de.kevloe.vibecloud.master.console.MaintenanceCommands;
import de.kevloe.vibecloud.master.console.NodeCommands;
import de.kevloe.vibecloud.master.console.PermCommands;
import de.kevloe.vibecloud.master.console.RankCommands;
import de.kevloe.vibecloud.master.console.ScreenCommand;
import de.kevloe.vibecloud.master.console.ServerCommands;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.event.EventCursorStore;
import de.kevloe.vibecloud.master.grpc.AuthInterceptor;
import de.kevloe.vibecloud.master.grpc.NodeServiceImpl;
import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.master.grpc.PluginServiceImpl;
import de.kevloe.vibecloud.master.grpc.ServerSessionStore;
import de.kevloe.vibecloud.master.message.MessageService;
import de.kevloe.vibecloud.master.module.MasterModuleContextImpl;
import de.kevloe.vibecloud.master.module.ModuleChannelRouter;
import de.kevloe.vibecloud.master.module.ModuleManager;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.node.NodeRepository;
import de.kevloe.vibecloud.master.scheduler.PlacementScheduler;
import de.kevloe.vibecloud.master.server.FallbackSelector;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerHistoryRepository;
import de.kevloe.vibecloud.master.server.ServerNameGenerator;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.server.ServerService;
import de.kevloe.vibecloud.master.server.StaticBindingRepository;
import de.kevloe.vibecloud.master.settings.CloudSettings;
import de.kevloe.vibecloud.master.settings.MaintenanceSwitch;
import de.kevloe.vibecloud.master.template.JarStore;
import de.kevloe.vibecloud.master.template.LogArchive;
import de.kevloe.vibecloud.master.template.TemplateStore;
import de.kevloe.vibecloud.master.tls.ForwardingSecret;
import de.kevloe.vibecloud.master.tls.SelfSignedCertificate;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hochfahren und Herunterfahren des Masters (PLAN.md Abschnitt 6).
 *
 * <p>Die Reihenfolge ist Absicht: Datenbank und Einzel-Master-Sperre zuerst, denn ohne die
 * darf nichts anderes starten. Erst danach das Zertifikat, dann der gRPC-Port, zuletzt die
 * Konsole.
 */
public final class VibeCloudMaster implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VibeCloudMaster.class);

    /** Nach so vielen Fehlversuchen wird ein Node gesperrt (PLAN.md Abschnitt 13). */
    private static final int MAX_FAILED_AUTHS = 5;

    private final Path workingDirectory;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ScreenCommand.AttachmentState attachment = new ScreenCommand.AttachmentState();

    private MasterConfig config;
    /** Die config.json zum Aendern im Betrieb - fuer {@code cloud config} und das Dashboard. */
    private MasterConfigFile configFile;
    /** Der globale Wartungsmodus - dieselbe Stelle fuer Konsole und Dashboard. */
    private MaintenanceSwitch maintenanceSwitch;
    /** SFTP-Zugaenge und die Entscheidung ueber Anmeldungen, die ein Wrapper meldet. */
    private de.kevloe.vibecloud.master.sftp.SftpAccountService sftpAccounts;
    /** SFTP-Zugang zu den Templates der Gruppen - laeuft im Master selbst. */
    private de.kevloe.vibecloud.master.sftp.TemplateSftp templateSftp;
    private Database database;
    private AuditLog audit;
    private EventBus events;
    private NodeRegistry nodes;
    private PlacementScheduler scheduler;
    private LogArchive logs;
    private MessageService messages;
    /** Neu einlesen und verteilen - dieselbe Stelle fuer Konsole und Dashboard. */
    private de.kevloe.vibecloud.master.message.MessageDistributor messageDistributor;
    private ServerSessionStore sessions;
    private PluginConnectionRegistry plugins;
    private de.kevloe.vibecloud.master.server.PlayerTransferService transfers;
    private de.kevloe.vibecloud.master.http.ApiTokenService apiTokens;
    private de.kevloe.vibecloud.master.http.AccountService accounts;
    private final de.kevloe.vibecloud.master.player.OnlinePlayers onlinePlayers =
            new de.kevloe.vibecloud.master.player.OnlinePlayers();
    private de.kevloe.vibecloud.master.http.HttpApi httpApi;
    private ScheduledExecutorService housekeeping;
    private PermissionService permissionService;
    private ModuleManager moduleManager;
    private TemplateStore templateStore;
    private CommandRegistry consoleCommands;
    private de.kevloe.vibecloud.master.grpc.PluginServiceImpl pluginService;
    private Server grpcServer;
    private CloudTerminal terminal;

    public VibeCloudMaster(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /** @return {@code false} wenn der Start abgebrochen wurde (Konfiguration oder Sperre) */
    public boolean start() throws Exception {
        LOG.info("vibeCloud Master startet (Protokoll-Version {})", VibeCloud.API_VERSION);

        config = JsonConfig.loadOrCreate(
                workingDirectory.resolve("config.json"), MasterConfig.class, new MasterConfig());
        if (config == null) {
            // Erststart: Die Vorlage wurde geschrieben, jetzt soll der Betreiber draufschauen.
            return false;
        }
        configFile = new MasterConfigFile(workingDirectory.resolve("config.json"), config);

        database = new Database(config.database);
        if (!database.acquireMasterLock()) {
            LOG.error("Auf dieser Datenbank laeuft schon ein Master. Zwei Master wuerden "
                      + "beide Server nachstarten - dieser Prozess beendet sich deshalb.");
            database.close();
            return false;
        }
        database.migrate();

        audit = new AuditLog(database);
        events = new LocalEventBus();

        NodeRepository nodeRepository = new NodeRepository(database);
        nodes = new NodeRegistry(nodeRepository, events,
                Duration.ofSeconds(config.grpc.heartbeatTimeoutSeconds));

        // --- Server-Schicht ---
        ServerGroupRepository groups = new ServerGroupRepository(database);
        ServerRegistry servers = new ServerRegistry();
        ServerNameGenerator names = new ServerNameGenerator(database);
        StaticBindingRepository bindings = new StaticBindingRepository(database);
        ServerHistoryRepository history = new ServerHistoryRepository(database);

        // Das zweite Verzeichnis ist das der Module: Dort liegen die eigenen Texte des
        // Betreibers zu einem Modul, neben dessen config.json.
        messages = new MessageService(workingDirectory.resolve("messages"),
                workingDirectory.resolve("modules"));
        sessions = new ServerSessionStore();
        plugins = new PluginConnectionRegistry();
        messageDistributor =
                new de.kevloe.vibecloud.master.message.MessageDistributor(messages, plugins);
        CloudSettings settings = new CloudSettings(database);
        maintenanceSwitch = new MaintenanceSwitch(settings, groups, plugins, audit);

        // --- Spieler und Rechte ---
        RankRepository rankRepository = new RankRepository(database);
        PlayerRepository playerRepository = new PlayerRepository(database);
        permissionService = new PermissionService(rankRepository, playerRepository, events, audit);
        PlayerService playerService = new PlayerService(playerRepository, rankRepository,
                permissionService, events);

        // SFTP laeuft im Wrapper, entschieden wird hier - mit den Rechten aus dem Spiel.
        sftpAccounts = new de.kevloe.vibecloud.master.sftp.SftpAccountService(
                database, bindings, permissionService::has, audit);
        // Damit perm und der Rechte-Editor es vorschlagen. Die einzelnen Server ergeben
        // sich aus ihren Namen: vibecloud.sftp.<server>.
        permissionService.declareNode(
                de.kevloe.vibecloud.master.sftp.SftpAccountService.PERMISSION_PREFIX + "*",
                "SFTP-Zugang zu den Verzeichnissen aller statischen Server");

        JarStore jars = new JarStore(workingDirectory.resolve("jars"));
        TemplateStore templates = new TemplateStore(workingDirectory.resolve("templates"), jars);
        templateStore = templates;
        logs = new LogArchive(workingDirectory.resolve("logs"), config.servers.logRetentionDays);
        ConsoleBuffer console = new ConsoleBuffer();

        FallbackSelector fallbackSelector = new FallbackSelector(groups, servers);
        ServerService serverService = new ServerService(groups, servers, names, bindings,
                history, nodes, templates, audit, config.servers, sessions);
        scheduler = new PlacementScheduler(groups, servers, serverService,
                config.servers.schedulerIntervalSeconds);

        SelfSignedCertificate certificate = SelfSignedCertificate.loadOrCreate(
                workingDirectory.resolve("tls"), "vibecloud-master");
        serverService.setForwardingSecret(
                ForwardingSecret.loadOrCreate(workingDirectory.resolve("secrets")));

        startGrpc(nodeRepository, certificate, servers, groups, history, templates, console,
                fallbackSelector, settings, playerService, rankRepository);
        startConsole(nodeRepository, certificate, serverService, servers, groups, bindings,
                console, templates, settings, playerService, rankRepository);

        // Templates liegen auf dem Master - also bietet er selbst den SFTP-Zugang dazu an.
        templateSftp = new de.kevloe.vibecloud.master.sftp.TemplateSftp(
                groups, templates, sftpAccounts);
        templateSftp.start(config.sftp,
                workingDirectory.resolve("secrets").resolve("sftp-host.key"));
        permissionService.declareNode(
                de.kevloe.vibecloud.master.sftp.SftpAccountService.TEMPLATE_PERMISSION_PREFIX
                + "*", "SFTP-Zugang zu den Templates aller Gruppen");
        // 'screen' gibt es im Spiel nicht, deshalb schlaegt der Katalog seine Rechte nicht
        // von selbst vor. Das Dashboard braucht sie aber.
        permissionService.declareNode(
                de.kevloe.vibecloud.master.http.HttpApi.CONSOLE_PERMISSION,
                "Die Konsole eines Servers im Dashboard mitlesen");
        permissionService.declareNode(
                de.kevloe.vibecloud.master.http.HttpApi.CONSOLE_SEND_PERMISSION,
                "Befehle in die Konsole eines Servers schreiben - dort gibt es 'op'");

        scheduler.start();
        permissionService.start();

        // Aenderungen an Rechten muessen bei den Plugins ankommen - ueber die bestehenden
        // gRPC-Streams, nicht ueber Redis. Plugins haben bewusst keine Zugangsdaten zu
        // Datenspeichern (PLAN.md Abschnitt 3).
        permissionService.onInvalidation(uuid -> {
            var changed = de.kevloe.vibecloud.protocol.PlayerDataChanged.newBuilder();
            if (uuid == null) {
                changed.setAllPlayers(true);
            } else {
                changed.setUuid(de.kevloe.vibecloud.api.Protos.toProto(uuid));
            }
            plugins.broadcastToAll(de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry
                    .command(builder -> builder.setPlayerDataChanged(changed)));
        });

        startHousekeeping();

        startModules(groups, servers, serverService, playerService, rankRepository,
                playerRepository);

        events.post(new CloudReadyEvent());
        // Nach den Modulen: Sie koennen eigene Routen mitbringen (ab M7).
        startHttp(servers, serverService, groups, nodeRepository, playerService,
                rankRepository, console, bindings);

        printStartupHints(nodeRepository, groups, certificate);
        return true;
    }

    private void startGrpc(NodeRepository nodeRepository, SelfSignedCertificate certificate,
                           ServerRegistry servers, ServerGroupRepository groups,
                           ServerHistoryRepository history, TemplateStore templates,
                           ConsoleBuffer console, FallbackSelector fallbackSelector,
                           CloudSettings settings, PlayerService playerService,
                           RankRepository rankRepository) throws IOException {

        AuthInterceptor auth = new AuthInterceptor(nodeRepository, sessions, audit,
                MAX_FAILED_AUTHS);
        EventCursorStore cursors = new EventCursorStore(database);

        pluginService = new PluginServiceImpl(sessions, plugins, nodes,
                servers, groups, fallbackSelector, messages, settings, events, audit,
                playerService, onlinePlayers, permissionService, rankRepository);

        NodeServiceImpl nodeService = new NodeServiceImpl(nodes, cursors, audit, servers,
                groups, history, templates, console, logs, scheduler,
                config.grpc.heartbeatIntervalSeconds, plugins, pluginService, sessions,
                sftpAccounts);

        grpcServer = NettyServerBuilder
                .forAddress(new InetSocketAddress(config.grpc.bindAddress, config.grpc.port))
                .sslContext(GrpcSslContexts
                        .forServer(certificate.certificateFile().toFile(),
                                certificate.privateKeyFile().toFile())
                        .build())
                .maxInboundMessageSize(config.grpc.maxMessageSizeMb * 1024 * 1024)
                // Virtual Threads: Jeder Wrapper haelt mehrere dauerhaft offene Streams,
                // ein begrenzter Pool wuerde bei vielen Nodes zum Nadeloehr.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .addService(nodeService)
                .addService(pluginService)
                .intercept(auth)
                .build()
                .start();

        LOG.info("gRPC-Server hoert auf {}:{} (TLS)", config.grpc.bindAddress, config.grpc.port);
    }

    private void startConsole(NodeRepository nodeRepository, SelfSignedCertificate certificate,
                              ServerService serverService, ServerRegistry servers,
                              ServerGroupRepository groups, StaticBindingRepository bindings,
                              ConsoleBuffer console, TemplateStore templates,
                              CloudSettings settings, PlayerService playerService,
                              RankRepository rankRepository) throws IOException {

        CommandRegistry commands = new CommandRegistry();
        consoleCommands = commands;
        commands.register(CloudTerminal.helpCommand(commands));
        commands.register(new CloudCommands(groups, servers, scheduler, audit, templates.root(),
                messageDistributor, configFile));
        commands.register(new NodeCommands(nodeRepository, nodes, audit,
                certificate.fingerprint(), config.grpc.port));
        commands.register(new GroupCommands(groups, servers, audit));
        commands.register(new ServerCommands(serverService, servers, groups, bindings));
        commands.register(new ScreenCommand(console, servers, serverService, attachment));
        commands.register(new RankCommands(permissionService, rankRepository, playerService));
        commands.register(new PermCommands(permissionService, rankRepository, playerService,
                commands));
        commands.register(new MaintenanceCommands(maintenanceSwitch, groups));

        transfers = new de.kevloe.vibecloud.master.server.PlayerTransferService(
                plugins, servers, audit);
        commands.register(de.kevloe.vibecloud.master.console.SendCommands.self(
                servers, transfers));
        commands.register(de.kevloe.vibecloud.master.console.SendCommands.other(
                servers, playerService, transfers));
        // Auch Plugins duerfen Spieler verschieben - ueber MovePlayer.
        pluginService.setTransfers(transfers);

        apiTokens = new de.kevloe.vibecloud.master.http.ApiTokenService(database);
        commands.register(new de.kevloe.vibecloud.master.console.ApiCommands(apiTokens, audit));
        commands.register(new de.kevloe.vibecloud.master.console.SftpCommands(
                sftpAccounts, playerService, bindings, nodes, groups, () -> templateSftp));

        accounts = new de.kevloe.vibecloud.master.http.AccountService(
                database, permissionService, audit);
        // Haengt am PlayerPermissionsChangedEvent: Faellt vibecloud.dashboard.login weg,
        // verschwindet der Zugang - egal, ob durch rank set, perm remove, geaenderte
        // Vererbung oder einen abgelaufenen Rang (PLAN.md Abschnitt 12).
        events.register(accounts);
        commands.register(new de.kevloe.vibecloud.master.console.AcpCommands(
                accounts, playerService));
        commands.register(new de.kevloe.vibecloud.master.console.PlayerCommands(
                playerService, servers));
        commands.register(new CommandRegistry.Command() {

            @Override
            public String name() {
                return "stop";
            }

            @Override
            public String description() {
                return "Master beenden";
            }

            @Override
            public String usage() {
                return "stop";
            }

            /**
             * Nicht im Spiel: Ohne Master kommt niemand mehr herein (PLAN.md 17.4).
             * Diesen Schalter soll nur bedienen, wer am Terminal sitzt.
             */
            @Override
            public boolean availableInGame() {
                return false;
            }

            @Override
            public void execute(CommandOutput out, List<String> args) {
                out.warn("Master wird beendet. Die Gameserver laufen weiter - "
                         + "die Wrapper verbinden sich beim naechsten Start von selbst.");
                terminal.stop();
            }
        });

        terminal = new CloudTerminal(commands, attachment);
    }

    /**
     * Laedt die Module (PLAN.md Abschnitt 10).
     *
     * <p>Erst nach dem gRPC-Server und der Konsole: Module duerfen Befehle registrieren und
     * ueber den Kanal mit Plugins sprechen, und beides muss vorher stehen.
     */
    private void startModules(ServerGroupRepository groups, ServerRegistry servers,
                              ServerService serverService, PlayerService playerService,
                              RankRepository rankRepository,
                              PlayerRepository playerRepository) throws IOException {

        ModuleChannelRouter router = new ModuleChannelRouter(plugins, servers,
                consoleCommands, permissionService, messages);
        pluginService.setModuleChannel(router);

        Path modulesDirectory = workingDirectory.resolve("modules");

        moduleManager = new ModuleManager(modulesDirectory,
                (descriptor, classLoader) -> {
                    try {
                        return new MasterModuleContextImpl(descriptor, classLoader, events,
                                database, permissionService, playerService, servers, router,
                                plugins, messages, modulesDirectory);
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                                "Datenverzeichnis fuer Modul " + descriptor.id()
                                + " konnte nicht angelegt werden", exception);
                    }
                },
                events, audit);

        consoleCommands.register(new de.kevloe.vibecloud.master.console.ModuleCommands(
                moduleManager, () -> pluginService.publishCommands()));
        moduleManager.loadAll();

        // Erst nach loadAll: Die Befehle der Module sollen von Anfang an dabei sein.
        pluginService.setPlayerCommands(
                new de.kevloe.vibecloud.master.console.PlayerCommandService(
                        consoleCommands, permissionService::has,
                        (actor, command, args) -> audit.record(actor, "command.ingame",
                                command, java.util.Map.of("args", args))));

        // Erst jetzt, nach dem Laden: Die Bundles der Module gehoeren in jedes
        // Server-Template (PLAN.md Abschnitt 10).
        Path bundleCache = workingDirectory.resolve("jars/bundles");
        templateStore.setBundleProvider(
                platform -> moduleManager.extractBundles(platform, bundleCache));
    }

    /** Taegliches Aufraeumen: alte Logs loeschen (PLAN.md Entscheidung 17.3). */
    private void startHousekeeping() {
        logs.pruneOld();
        housekeeping = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("housekeeping").factory());
        housekeeping.scheduleAtFixedRate(logs::pruneOld, 1, 24, TimeUnit.HOURS);
    }

    /**
     * Startet die REST-Schnittstelle, falls eingeschaltet.
     *
     * <p>Zuletzt im Bootstrap: Sie greift auf Server-Registry und Transfers zu, und beides
     * muss stehen, bevor die erste Anfrage hereinkommt.
     */
    private void startHttp(ServerRegistry servers, ServerService serverService,
                          ServerGroupRepository groups, NodeRepository nodeRepository,
                          PlayerService playerService, RankRepository rankRepository,
                          ConsoleBuffer console, StaticBindingRepository bindings)
            throws IOException {

        var jwt = de.kevloe.vibecloud.master.security.Jwt.load(
                workingDirectory.resolve("secrets"));

        httpApi = new de.kevloe.vibecloud.master.http.HttpApi(
                apiTokens, accounts, permissionService, jwt, config.http.secureCookies,
                servers, serverService, groups, nodeRepository, nodes, playerService,
                rankRepository, transfers, moduleManager, console, onlinePlayers, audit,
                workingDirectory.resolve("modules"), messageDistributor, consoleCommands,
                maintenanceSwitch, configFile, sftpAccounts, bindings, templateSftp);

        // Neben dem Master, damit ein Update des Dashboards nur ein Verzeichnis ersetzt.
        httpApi.start(config.http, workingDirectory.resolve("dashboard"));
    }

    /**
     * Hinweise beim Start. Der Fingerprint ist der Wert, ohne den sich kein Wrapper
     * verbinden kann - er muss sichtbar sein, nicht in einer Datei versteckt.
     */
    private void printStartupHints(NodeRepository nodeRepository, ServerGroupRepository groups,
                                   SelfSignedCertificate certificate) {
        CommandOutput out = terminal.output();
        out.info("");
        out.success("vibeCloud Master ist bereit.");
        out.info("Zertifikat-Fingerprint: " + certificate.fingerprint());

        if (nodeRepository.findAll().isEmpty()) {
            out.info("");
            out.warn("Es ist noch kein Node angelegt. Der erste Schritt auf einem Root ist:");
            out.warn("  node add <name> [maxMemoryMb] [erlaubte IPs]");
            out.warn("Der Befehl gibt eine fertige wrapper.json samt Token und Fingerprint aus.");
        }
        if (groups.findAll().isEmpty()) {
            out.info("");
            out.warn("Es ist noch keine Gruppe angelegt - ohne die startet kein Server:");
            out.warn("  cloud setup     legt 'proxy' und 'lobby' mit brauchbaren Vorgaben an");
        }
        out.info("");
    }

    /**
     * Blockiert, bis der Master beendet werden soll.
     *
     * <p>Gibt es kein Terminal - als systemd-Dienst ist stdin {@code /dev/null} - laeuft der
     * Master <b>ohne</b> Konsole weiter und wartet auf SIGTERM. Ein stdin-Ende darf keinen
     * Master herunterfahren, sonst wuerde der Dienst sofort nach dem Start wieder aufhoeren.
     */
    public void awaitShutdown() {
        terminal.runUntilStopped();

        if (!terminal.wasStopRequested()) {
            LOG.info("Keine interaktive Konsole verfuegbar - der Master laeuft weiter. "
                     + "Beenden mit SIGTERM (systemctl stop) oder Strg+C.");
            try {
                shutdownLatch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Loest {@link #awaitShutdown()} aus, z. B. aus dem Shutdown-Hook. */
    public void requestShutdown() {
        shutdownLatch.countDown();
    }

    @Override
    public void close() {
        // Shutdown-Hook und finally-Block rufen beide close() - der zweite Aufruf soll
        // nichts tun und vor allem nicht nochmal "Master beendet" loggen.
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Master wird beendet");
        shutdownLatch.countDown();

        if (events != null) {
            events.post(new CloudShutdownEvent());
        }
        // Die Gameserver werden absichtlich NICHT gestoppt: Ein Master-Neustart darf
        // niemanden aus dem Spiel werfen (PLAN.md Abschnitt 7).
        if (httpApi != null) {
            httpApi.close();
        }
        closeQuietly(templateSftp);
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (plugins != null) {
            plugins.closeAll();
        }
        // Module zuerst: Sie haengen an Datenbank, Events und Kanal.
        closeQuietly(moduleManager);
        closeQuietly(permissionService);
        closeQuietly(scheduler);
        closeQuietly(nodes);
        closeQuietly(terminal);
        closeQuietly(audit);
        if (housekeeping != null) {
            housekeeping.close();
        }
        if (events instanceof LocalEventBus bus) {
            bus.shutdown();
        }
        closeQuietly(database);
        LOG.info("Master beendet");
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            LOG.debug("Fehler beim Schliessen von {}", closeable.getClass().getSimpleName(),
                    exception);
        }
    }
}
