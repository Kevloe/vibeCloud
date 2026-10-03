package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.plugin.CloudConnection;
import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import de.kevloe.vibecloud.api.plugin.PluginModuleChannel;
import de.kevloe.vibecloud.protocol.MovePlayerResponse;
import de.kevloe.vibecloud.protocol.ServerCommand;
import de.kevloe.vibecloud.protocol.ServerEvent;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.ServerRegisterResponse;
import de.kevloe.vibecloud.protocol.ServerStateReport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Das Gameserver-Plugin fuer Paper 1.16 bis 26.1 (PLAN.md Abschnitt 11) - geladen von
 * {@link VibeCloudPaperLegacy}, der erklaert, warum es diesen Umweg gibt.
 *
 * <p>Dasselbe wie {@code VibeCloudPaper}: am Master anmelden, Rang und Rechte jedes Spielers
 * holen, Raenge anzeigen, die Spielerzahl melden. Verbindung, Rechte-Auswertung und
 * Rechte-Bridge sind <b>dieselben Dateien</b> (siehe build.gradle.kts) - hier steht nur,
 * was auf alten Servern anders gehen muss:
 * <ul>
 *   <li>Texte als Strings statt Components ({@link LegacyText}).</li>
 *   <li>Chat ueber {@code AsyncPlayerChatEvent} - {@code AsyncChatEvent} gibt es erst ab
 *       Paper 1.16.5 und mit einer anderen Adventure-Version.</li>
 *   <li>Die OP-Stufe fuer den Spielmodus-Umschalter nur, wenn der Server die Methode hat.</li>
 *   <li>Keine Modul-Bundles - sie sind fuer das aktuelle Paper gebaut. Der Master schickt
 *       sie gar nicht erst.</li>
 * </ul>
 */
public final class LegacyCore implements CloudCore, Listener, CloudConnection.CommandHandler {

    private final JavaPlugin plugin;

    private static final String GAMEMODE_PERMISSION = "minecraft.command.gamemode";
    private static final byte SWITCHER_OP_LEVEL = 2;
    private static final String WILDCARD = "*";
    private static final byte FULL_OP_LEVEL = 4;

    private PluginModuleChannel moduleChannel;
    private CloudConnection connection;
    private CloudPermissions permissions;
    private LegacyRankDisplay display;
    private PermissibleInjector injector;

    /** {@code Player#sendOpLevel(byte)} - Paper-API, nicht auf jedem Server da. */
    private Method sendOpLevel;
    private boolean opLevelSearched;

    public LegacyCore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        Optional<CloudConnection.ConnectionFile> file =
                CloudConnection.readConnectionFile(Path.of("."));

        if (file.isEmpty()) {
            plugin.getLogger().warning("vibeCloud ist inaktiv - dieser Server laeuft nicht unter "
                                + "der Cloud. Der Server funktioniert davon unabhaengig.");
            return;
        }

        plugin.saveDefaultConfig();
        boolean chatEnabled = plugin.getConfig().getBoolean("chat-format", true);

        permissions = new CloudPermissions(file.get().groupName, file.get().serverName);
        display = new LegacyRankDisplay(permissions, plugin.getLogger(), chatEnabled);
        injector = new PermissibleInjector(permissions, plugin.getLogger());

        connection = new CloudConnection(file.get(), ServerPlatform.SERVER_PLATFORM_PAPER,
                Bukkit.getMaxPlayers(), this);
        moduleChannel = new PluginModuleChannel(connection);
        connection.connectAsync();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("vibeCloud (Legacy, " + Bukkit.getBukkitVersion() + ") verbindet "
                         + "sich zum Master als " + file.get().serverName + " ...");
    }

    @Override
    public void disable() {
        if (injector != null) {
            Bukkit.getOnlinePlayers().forEach(injector::restore);
        }
        if (connection != null) {
            connection.close();
        }
    }

    // ---------------------------------------------------------------- Master -> Server

    @Override
    public void onConnected(ServerRegisterResponse response) {
        plugin.getLogger().info("Am Master angemeldet. Sprachen: "
                         + String.join(", ", response.getAvailableLocalesList()));
        Bukkit.getOnlinePlayers().stream().findFirst().ifPresent(sample -> {
            if (injector.isAvailable(sample)) {
                plugin.getLogger().info("Rechte-Bridge aktiv: player.hasPermission(...) fragt "
                                 + "die Cloud");
            }
        });
        Bukkit.getOnlinePlayers().forEach(this::loadPlayer);
        reportState();
    }

    @Override
    public void onCommand(ServerCommand command) {
        switch (command.getCommandCase()) {
            case KICK_PLAYER -> kick(command.getKickPlayer());
            case MODULE_MESSAGE -> moduleChannel.onMessage(command.getModuleMessage());
            case RELOAD_MESSAGES -> {
                connection.loadMessages();
                plugin.getLogger().info("Sprachdateien neu geladen");
            }
            case PLAYER_DATA_CHANGED -> reloadPermissions(command.getPlayerDataChanged());
            default -> plugin.getLogger().fine("Befehl " + command.getCommandCase()
                                        + " ist fuer einen Gameserver nicht relevant");
        }
    }

    @Override
    public void onDisconnected() {
        plugin.getLogger().warning("Verbindung zum Master verloren. Der Server laeuft weiter; "
                            + "Rechte bleiben auf dem letzten bekannten Stand.");
    }

    private void reloadPermissions(de.kevloe.vibecloud.protocol.PlayerDataChanged changed) {
        if (changed.getAllPlayers()) {
            permissions.forgetAll();
            Bukkit.getOnlinePlayers().forEach(this::loadPlayer);
            plugin.getLogger().info("Rechte aller Spieler neu geladen");
            return;
        }
        UUID uuid = Protos.fromProto(changed.getUuid());
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            loadPlayer(player);
            plugin.getLogger().info("Rechte von " + player.getName() + " neu geladen");
        } else {
            permissions.forget(uuid);
        }
    }

    private void loadPlayer(Player player) {
        connection.fetchPlayerData(player.getUniqueId(), player.getName())
                .ifPresent(data -> {
                    permissions.update(data);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        injector.inject(player);
                        display.apply(player);
                        if (player.isOnline()) {
                            // Wie im aktuellen Plugin: Die Befehlsliste des Joins ist mit den
                            // Bukkit-Rechten berechnet, bevor die Cloud geantwortet hat.
                            player.updateCommands();
                            sendOpLevel(player);
                        }
                    });
                });
    }

    /**
     * Wie im aktuellen Plugin - aber {@code sendOpLevel} ist Paper-API und nicht auf jedem
     * dieser Server vorhanden. Fehlt sie, bleibt der Umschalter (F3+F4) aus; der Wechsel per
     * Befehl geht trotzdem, denn den prueft der Server mit dem Recht.
     */
    private void sendOpLevel(Player player) {
        if (player.isOp()) {
            return;
        }
        Method method = opLevelMethod(player);
        if (method == null) {
            return;
        }
        byte level = permissions.has(player.getUniqueId(), WILDCARD)
                ? FULL_OP_LEVEL
                : player.hasPermission(GAMEMODE_PERMISSION) ? SWITCHER_OP_LEVEL : (byte) 0;
        try {
            method.invoke(player, level);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().fine("OP-Stufe fuer " + player.getName() + " nicht gesetzt: "
                             + exception.getMessage());
        }
    }

    private Method opLevelMethod(Player player) {
        if (!opLevelSearched) {
            opLevelSearched = true;
            try {
                sendOpLevel = player.getClass().getMethod("sendOpLevel", byte.class);
            } catch (NoSuchMethodException exception) {
                plugin.getLogger().info("Dieser Server kennt sendOpLevel nicht - der "
                        + "Spielmodus-Umschalter (F3+F4) bleibt fuer Nicht-Operatoren aus.");
            }
        }
        return sendOpLevel;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        resendOpLevel(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        resendOpLevel(event.getPlayer());
    }

    private void resendOpLevel(Player player) {
        if (connection == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                sendOpLevel(player);
            }
        });
    }

    private void kick(de.kevloe.vibecloud.protocol.KickPlayer command) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(Protos.fromProto(command.getUuid()));
            if (player != null) {
                player.kickPlayer(message(player, command.getMessageKey(),
                        command.getPlaceholdersMap()));
            }
        });
    }

    // ---------------------------------------------------------------- Server -> Master

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (connection == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> loadPlayer(event.getPlayer()));
        reportState();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (connection == null) {
            return;
        }
        injector.restore(event.getPlayer());
        display.remove(event.getPlayer());
        permissions.forget(event.getPlayer().getUniqueId());
        // Der Spieler ist beim Quit noch in der Liste.
        reportState(Bukkit.getOnlinePlayers().size() - 1);
    }

    /**
     * Chat mit Rang-Prefix. Gesetzt wird nur das Format - die Nachricht selbst bleibt, wie
     * sie ist, und andere Plugins koennen sie weiter lesen.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncPlayerChatEvent event) {
        if (connection == null) {
            return;
        }
        display.chatFormat(event.getPlayer()).ifPresent(format -> {
            try {
                event.setFormat(format);
            } catch (RuntimeException exception) {
                // Ein kaputtes Format im Rang soll den Chat nicht abschalten.
                plugin.getLogger().warning("Chat-Format nicht verwendbar: "
                                           + exception.getMessage());
            }
        });
    }

    private void reportState() {
        reportState(Bukkit.getOnlinePlayers().size());
    }

    private void reportState(int players) {
        if (connection == null) {
            return;
        }
        connection.publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setStateReport(ServerStateReport.newBuilder()
                        .setIngameState("LOBBY")
                        .setPlayers(Math.max(0, players)))
                .build());
    }

    // ---------------------------------------------------------------- API fuer Plugins

    /** Eine Cloud-Nachricht in der Sprache des Spielers, als Text mit Farbcodes. */
    private String message(Player player, String key, Map<String, String> placeholders) {
        String locale = permissions.localeOf(player.getUniqueId()).orElse(null);
        return connection.messages()
                .map(bundle -> LegacyText.render(bundle.get(locale, key, placeholders)))
                .orElse(key);
    }

    @Override
    public void switchServer(Player player, String target) {
        switchServer(player.getUniqueId(), player.getName(), target);
    }

    @Override
    public void switchServer(UUID uuid, String name, String target) {
        if (connection == null) {
            plugin.getLogger().warning("Kein Cloud-Kontakt - " + name
                                       + " bleibt auf diesem Server.");
            return;
        }
        Thread thread = new Thread(() -> {
            Optional<MovePlayerResponse> answer = connection.movePlayer(uuid, target);
            if (answer.isEmpty()) {
                plugin.getLogger().warning("Wechsel von " + name + " nach " + target
                                           + " fehlgeschlagen: Master nicht erreichbar");
                return;
            }
            if (!answer.get().getAccepted()) {
                plugin.getLogger().warning("Wechsel von " + name + " nach " + target
                                           + " abgelehnt: " + answer.get().getReason());
            }
        }, "vibecloud-switch-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public boolean hasPermission(UUID uuid, String node) {
        return permissions != null && permissions.has(uuid, node);
    }
}
