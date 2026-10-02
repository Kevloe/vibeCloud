package de.kevloe.vibecloud.master.module;

import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.common.config.JsonConfig;
import de.kevloe.vibecloud.master.db.Database;
import de.kevloe.vibecloud.master.message.MessageService;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.module.ModuleChannel;
import de.kevloe.vibecloud.module.ModuleCommands;
import de.kevloe.vibecloud.module.ModuleConfig;
import de.kevloe.vibecloud.module.ModuleDatabase;
import de.kevloe.vibecloud.module.ModuleDescriptor;
import de.kevloe.vibecloud.module.ModulePermissions;
import de.kevloe.vibecloud.module.ModulePlayers;
import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.protocol.BroadcastMessage;
import de.kevloe.vibecloud.protocol.KickPlayer;
import de.kevloe.vibecloud.protocol.SendMessage;
import de.kevloe.vibecloud.api.Protos;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Was ein Modul im Master tatsaechlich bekommt (PLAN.md Abschnitt 10).
 *
 * <p>Jedes Modul bekommt eine eigene Instanz. Sie merkt sich alle Registrierungen, damit
 * {@link #shutdown()} sie beim Entladen wieder abmelden kann - ohne das leckt jeder Reload
 * Event-Handler und Befehle.
 */
public final class MasterModuleContextImpl implements ModuleManager.MasterModuleContext {

    private static final Logger LOG = LoggerFactory.getLogger(MasterModuleContextImpl.class);

    private final ModuleDescriptor descriptor;
    private final ClassLoader classLoader;
    private final EventBus events;
    private final Database database;
    private final PermissionService permissions;
    private final PlayerService players;
    private final ServerRegistry servers;
    private final ModuleChannelRouter channelRouter;
    private final PluginConnectionRegistry plugins;
    private final MessageService messages;
    private final Path dataDirectory;
    private final Logger logger;

    /** Was dieses Modul angemeldet hat - fuer das Abmelden beim Entladen. */
    private final List<Object> registeredListeners = new ArrayList<>();
    private final List<String> registeredCommands = new ArrayList<>();
    private final List<String> declaredNodes = new ArrayList<>();
    private final ScheduledExecutorService scheduler;

    public MasterModuleContextImpl(ModuleDescriptor descriptor, ClassLoader classLoader,
                                   EventBus events, Database database,
                                   PermissionService permissions, PlayerService players,
                                   ServerRegistry servers, ModuleChannelRouter channelRouter,
                                   PluginConnectionRegistry plugins,
                                   MessageService messages, Path modulesDirectory)
            throws IOException {
        this.descriptor = descriptor;
        this.classLoader = classLoader;
        this.events = events;
        this.database = database;
        this.permissions = permissions;
        this.players = players;
        this.servers = servers;
        this.channelRouter = channelRouter;
        this.plugins = plugins;
        this.messages = messages;
        this.dataDirectory = modulesDirectory.resolve(descriptor.id());
        this.logger = LoggerFactory.getLogger("module." + descriptor.id());
        this.scheduler = Executors.newScheduledThreadPool(1,
                Thread.ofVirtual().name("module-" + descriptor.id() + "-", 0).factory());

        Files.createDirectories(dataDirectory);
    }

    @Override
    public String moduleId() {
        return descriptor.id();
    }

    @Override
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Event-Bus mit Mitschrift.
     *
     * <p>Jedes {@code register} wird gemerkt, damit es beim Entladen abgemeldet werden kann.
     * Ein Modul, das das selbst tun muesste, wuerde es irgendwann vergessen - und dann
     * laufen nach einem Reload zwei Handler.
     */
    @Override
    public EventBus events() {
        return new EventBus() {

            @Override
            public void register(Object listener) {
                events.register(listener);
                registeredListeners.add(listener);
            }

            @Override
            public void unregister(Object listener) {
                events.unregister(listener);
                registeredListeners.remove(listener);
            }

            @Override
            public void post(Object event) {
                events.post(event);
            }

            @Override
            public <E extends de.kevloe.vibecloud.api.event.Cancellable> E postSync(E event) {
                return events.postSync(event);
            }
        };
    }

    @Override
    public ModuleCommands commands() {
        return (name, description, usage, permission, command) -> {
            channelRouter.registerCommand(descriptor.id(), name, description, usage,
                    permission, command);
            registeredCommands.add(name);
        };
    }

    @Override
    public ModuleDatabase database() {
        return new ModuleDatabase() {

            @Override
            public int migrate() {
                // Zwei Dinge sind hier noetig, und beide aus demselben Grund: Der
                // ClassLoader des Moduls sieht AUCH die Ressourcen des Masters.
                //
                // 1. Eigener Pfad "db/migration/<id>" - sonst findet Flyway die Migrations
                //    des Masters mit und bricht mit zwei Dateien der Version 1 ab.
                // 2. Eigene Historie-Tabelle - sonst halten Core- und Modul-Migrations
                //    sich gegenseitig fuer "nicht angewendet".
                var result = Flyway.configure(classLoader)
                        .dataSource(database.dataSource())
                        .locations("classpath:db/migration/" + descriptor.id())
                        .table("flyway_schema_history_" + descriptor.id())
                        // baselineOnMigrate, weil das Schema vom Core schon existiert und
                        // Flyway sonst "non-empty schema without history table" meldet.
                        // baselineVersion 0 ist dabei entscheidend: Mit dem Standardwert 1
                        // wuerde Flyway auf Version 1 baselinen und die V1 des Moduls
                        // ueberspringen - die Tabellen entstuenden nie.
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load()
                        .migrate();

                if (result.migrationsExecuted > 0) {
                    logger.info("{} Migration(en) ausgefuehrt, Schema auf Version {}",
                            result.migrationsExecuted, result.targetSchemaVersion);
                }
                return result.migrationsExecuted;
            }

            @Override
            public Connection connection() throws SQLException {
                return database.connection();
            }

            @Override
            public String tablePrefix() {
                return descriptor.id() + "_";
            }
        };
    }

    @Override
    public ModuleChannel channel() {
        return channelRouter.channelFor(descriptor.id());
    }

    @Override
    public ModulePermissions permissions() {
        return new ModulePermissions() {

            @Override
            public boolean has(UUID uuid, String node) {
                return permissions.has(uuid, node);
            }

            @Override
            public boolean has(UUID uuid, String node, String group, String server) {
                return permissions.has(uuid, node, new PermissionContext(group, server));
            }

            @Override
            public Optional<String> explain(UUID uuid, String node) {
                return permissions.explain(uuid, node, PermissionContext.GLOBAL)
                        .map(candidate -> candidate.entry() + " aus " + candidate.source());
            }

            @Override
            public void declare(String node, String description) {
                // Nur Dokumentation und Vervollstaendigung - auf die Auswertung hat das
                // keinen Einfluss. Beim Entladen des Moduls wird es wieder zurueckgenommen.
                permissions.declareNode(node, description);
                declaredNodes.add(node);
                logger.debug("Recht angemeldet: {} - {}", node, description);
            }
        };
    }

    @Override
    public ModulePlayers players() {
        return new ModulePlayers() {

            @Override
            public Optional<PlayerInfo> find(UUID uuid) {
                return players.find(uuid).map(MasterModuleContextImpl::toInfo);
            }

            @Override
            public Optional<PlayerInfo> findByName(String name) {
                return players.findByName(name).map(MasterModuleContextImpl::toInfo);
            }

            @Override
            public boolean kick(UUID uuid, String messageKey,
                                Map<String, String> placeholders) {
                return plugins.broadcastToProxies(PluginConnectionRegistry.command(builder ->
                        builder.setKickPlayer(KickPlayer.newBuilder()
                                .setUuid(Protos.toProto(uuid))
                                .setMessageKey(messageKey)
                                .putAllPlaceholders(placeholders)))) > 0;
            }

            @Override
            public boolean message(UUID uuid, String messageKey,
                                   Map<String, String> placeholders) {
                return plugins.broadcastToProxies(PluginConnectionRegistry.command(builder ->
                        builder.setSendMessage(SendMessage.newBuilder()
                                .setUuid(Protos.toProto(uuid))
                                .setMessageKey(messageKey)
                                .putAllPlaceholders(placeholders)))) > 0;
            }

            @Override
            public int broadcast(String permission, String messageKey,
                                 Map<String, String> placeholders) {
                return plugins.broadcastToProxies(PluginConnectionRegistry.command(builder ->
                        builder.setBroadcastMessage(BroadcastMessage.newBuilder()
                                .setPermission(permission)
                                .setMessageKey(messageKey)
                                .putAllPlaceholders(placeholders))));
            }

            @Override
            public List<String> suggestNames(String prefix, int limit) {
                return players.suggestNames(prefix, limit);
            }

            @Override
            public Optional<String> lastIpHash(UUID uuid) {
                return players.lastIpHash(uuid);
            }

            @Override
            public Optional<String> currentServer(UUID uuid) {
                // Ab M4 weiss der Master ueber den Proxy, wo ein Spieler ist. Hier liefert
                // die Registry den letzten bekannten Stand.
                return players.find(uuid)
                        .map(record -> record.lastServer())
                        .filter(server -> server != null && !server.isBlank());
            }
        };
    }

    @Override
    public ModuleConfig config() {
        Path file = dataDirectory.resolve("config.json");
        return new ModuleConfig() {

            @Override
            public <T> T load(Class<T> type, T defaults) {
                T loaded = JsonConfig.loadOrCreate(file, type, defaults);
                // JsonConfig gibt null zurueck, wenn es die Datei neu angelegt hat. Fuer ein
                // Modul ist das kein Grund anzuhalten - die Vorgaben passen ja.
                return loaded != null ? loaded : defaults;
            }

            @Override
            public <T> T reload(Class<T> type, T defaults) {
                return load(type, defaults);
            }

            @Override
            public void save(Object value) {
                try {
                    Files.writeString(file, new com.google.gson.GsonBuilder()
                            .setPrettyPrinting().create().toJson(value));
                } catch (IOException exception) {
                    logger.error("Konfiguration konnte nicht gespeichert werden", exception);
                }
            }
        };
    }

    /**
     * Laedt {@code messages/<sprache>.yml} aus dem Modul-JAR.
     *
     * <p>Gelesen wird ueber den ClassLoader des Moduls - so kommen nur dessen eigene
     * Dateien, nicht die des Masters.
     */
    @Override
    public int loadMessages() {
        Map<String, Map<String, String>> byLocale = new java.util.LinkedHashMap<>();

        // Welche Sprachen es gibt, weiss nur das JAR. Statt es zu durchsuchen, werden die
        // Sprachen des Masters probiert - ein Modul soll ohnehin nicht mehr anbieten als
        // die Cloud selbst kennt.
        for (String locale : messages.bundle().availableLocales()) {
            try (java.io.InputStream in = classLoader.getResourceAsStream(
                    "messages/" + locale + ".yml")) {
                if (in != null) {
                    byLocale.put(locale,
                            de.kevloe.vibecloud.common.message.MessageBundle.loadYaml(in));
                }
            } catch (IOException exception) {
                logger.error("messages/{}.yml konnte nicht gelesen werden", locale, exception);
            }
        }

        if (byLocale.isEmpty()) {
            logger.warn("Keine Sprachdateien im Modul-JAR gefunden (erwartet: "
                        + "messages/<sprache>.yml). Spielersichtbare Texte zeigen dann nur "
                        + "ihren Schluessel.");
            return 0;
        }
        messages.registerModuleMessages(descriptor.id(), byLocale);
        return byLocale.size();
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    /**
     * Meldet alles ab, was dieses Modul angemeldet hat.
     *
     * <p>Das ist der Unterschied zwischen einem Reload und einem Speicherleck: Ohne das
     * bleiben Event-Handler und Befehle auf Klassen eines geschlossenen ClassLoaders
     * zeigen.
     */
    @Override
    public void shutdown() {
        List.copyOf(registeredListeners).forEach(listener -> {
            try {
                events.unregister(listener);
            } catch (RuntimeException exception) {
                LOG.debug("Handler von {} nicht abgemeldet", descriptor.id(), exception);
            }
        });
        registeredListeners.clear();

        registeredCommands.forEach(name -> channelRouter.unregisterCommand(descriptor.id(), name));
        registeredCommands.clear();

        // Auch die angemeldeten Rechte: Sonst schlaegt die Vervollstaendigung nach dem
        // Entladen weiter Knoten vor, die niemand mehr prueft.
        declaredNodes.forEach(permissions::forgetNode);
        declaredNodes.clear();

        channelRouter.forgetModule(descriptor.id());
        messages.unregisterModuleMessages(descriptor.id());
        scheduler.shutdownNow();
    }

    private static ModulePlayers.PlayerInfo toInfo(
            de.kevloe.vibecloud.master.player.PlayerRepository.PlayerRecord record) {
        return new ModulePlayers.PlayerInfo(
                record.uuid(),
                record.name(),
                record.platform(),
                record.rankId(),
                record.locale(),
                record.firstLogin(),
                record.lastLogin(),
                record.playtimeSeconds());
    }
}
