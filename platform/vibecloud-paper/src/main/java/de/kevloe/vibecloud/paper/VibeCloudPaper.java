package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.plugin.CloudConnection;
import de.kevloe.vibecloud.api.plugin.PluginModuleChannel;
import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import de.kevloe.vibecloud.protocol.ServerCommand;
import de.kevloe.vibecloud.protocol.ServerEvent;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.ServerRegisterResponse;
import de.kevloe.vibecloud.protocol.ServerStateReport;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gameserver-Plugin (PLAN.md Abschnitt 11).
 *
 * <p>Meldet sich am Master an, holt Rang und Rechte jedes Spielers, zeigt die Raenge an
 * und meldet die Spielerzahl zurueck.
 *
 * <p><b>Nur Paper-API.</b> Kein Velocity, kein Minestom (PLAN.md Abschnitt 4).
 */
public final class VibeCloudPaper extends JavaPlugin
        implements Listener, CloudConnection.CommandHandler {

    /** Das Recht, an dem Paper den Wechsel ueber den Umschalter festmacht. */
    private static final String GAMEMODE_PERMISSION = "minecraft.command.gamemode";

    /** Ab Stufe 2 oeffnet der Client den Umschalter. */
    private static final byte SWITCHER_OP_LEVEL = 2;

    /** Das volle Wildcard - nur wer das hat, bekommt die hoechste Stufe gemeldet. */
    private static final String WILDCARD = "*";

    private static final byte FULL_OP_LEVEL = 4;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private PluginModuleChannel moduleChannel;

    private CloudConnection connection;
    private CloudPermissions permissions;
    private RankDisplay display;
    private PermissibleInjector injector;

    @Override
    public void onEnable() {
        // Die Verbindungsdatei liegt im Arbeitsverzeichnis des Servers - der Wrapper legt
        // sie dort ab, nicht im Plugin-Ordner.
        Optional<CloudConnection.ConnectionFile> file =
                CloudConnection.readConnectionFile(Path.of("."));

        if (file.isEmpty()) {
            getLogger().warning("vibeCloud ist inaktiv - dieser Server laeuft nicht unter "
                                + "der Cloud. Der Server funktioniert davon unabhaengig.");
            return;
        }

        saveDefaultConfig();
        boolean chatEnabled = getConfig().getBoolean("chat-format", true);

        permissions = new CloudPermissions(file.get().groupName, file.get().serverName);
        display = new RankDisplay(permissions, getLogger(), chatEnabled);
        injector = new PermissibleInjector(permissions, getLogger());

        connection = new CloudConnection(file.get(), ServerPlatform.SERVER_PLATFORM_PAPER,
                Bukkit.getMaxPlayers(), this);
        // Vor connectAsync: Ein Bundle koennte sich sonst anmelden wollen, bevor es
        // den Kanal gibt.
        moduleChannel = new PluginModuleChannel(connection);
        connection.connectAsync();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("vibeCloud verbindet sich zum Master als "
                         + file.get().serverName + " ...");
    }

    @Override
    public void onDisable() {
        // Original-Rechteobjekte zuruecksetzen, bevor das Plugin geht - sonst zeigen die
        // Spieler nach einem Plugin-Reload auf eine Klasse, die es nicht mehr gibt.
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
        getLogger().info("Am Master angemeldet. Sprachen: "
                         + String.join(", ", response.getAvailableLocalesList()));

        // Einmal melden, ob player.hasPermission(...) die Cloud befragt - das ist der
        // Unterschied zwischen "Fremd-Plugins funktionieren" und "nur eigene".
        Bukkit.getOnlinePlayers().stream().findFirst().ifPresent(sample -> {
            if (injector.isAvailable(sample)) {
                getLogger().info("Rechte-Bridge aktiv: player.hasPermission(...) fragt "
                                 + "die Cloud");
            }
        });

        // Spieler, die schon online sind, nachladen - bei einem Reconnect waeren sie
        // sonst ohne Rechte.
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
                getLogger().info("Sprachdateien neu geladen");
            }
            case PLAYER_DATA_CHANGED -> reloadPermissions(command.getPlayerDataChanged());
            // Backend-Registrierung und Transfers sind Proxy-Sache. Ein Gameserver, der
            // diese Befehle bekommt, soll nichts tun statt etwas zu erfinden.
            default -> getLogger().fine("Befehl " + command.getCommandCase()
                                        + " ist fuer einen Gameserver nicht relevant");
        }
    }

    @Override
    public void onDisconnected() {
        getLogger().warning("Verbindung zum Master verloren. Der Server laeuft weiter; "
                            + "Rechte bleiben auf dem letzten bekannten Stand.");
    }

    /**
     * Laedt die Rechte neu, nachdem der Master eine Aenderung gemeldet hat.
     *
     * <p>Das ist der Grund, warum eine Rang-Aenderung ohne Relog wirkt: Der Master schickt
     * nur ein kurzes Signal, das Plugin holt die neuen Regeln und faerbt die Anzeige neu.
     */
    private void reloadPermissions(de.kevloe.vibecloud.protocol.PlayerDataChanged changed) {
        if (changed.getAllPlayers()) {
            permissions.forgetAll();
            Bukkit.getOnlinePlayers().forEach(this::loadPlayer);
            getLogger().info("Rechte aller Spieler neu geladen");
            return;
        }
        UUID uuid = Protos.fromProto(changed.getUuid());
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            loadPlayer(player);
            getLogger().info("Rechte von " + player.getName() + " neu geladen");
        } else {
            permissions.forget(uuid);
        }
    }

    /** Holt Rang und Rechte eines Spielers und aktualisiert die Anzeige. */
    private void loadPlayer(Player player) {
        connection.fetchPlayerData(player.getUniqueId(), player.getName())
                .ifPresent(data -> {
                    permissions.update(data);
                    // Bukkit-Aufrufe muessen auf dem Haupt-Thread laufen.
                    Bukkit.getScheduler().runTask(this, () -> {
                        // Erst die Bridge, dann die Anzeige: Ein Plugin, das im
                        // Join-Event Rechte prueft, soll schon die Cloud befragen.
                        injector.inject(player);
                        display.apply(player);
                        // Die Befehlsliste hat der Client schon beim Join bekommen -
                        // berechnet mit den Bukkit-Rechten, bevor die Cloud geantwortet
                        // hat. Ohne das neue Senden gehen /gamemode und Co. zwar, werden
                        // aber weder vorgeschlagen noch als bekannt angezeigt.
                        if (player.isOnline()) {
                            player.updateCommands();
                            sendOpLevel(player);
                        }
                    });
                });
    }

    /**
     * Sagt dem Client, ob er den Spielmodus-Umschalter (F3+F4, F3+N) oeffnen darf.
     *
     * <p>Der Client entscheidet das selbst, an der OP-Stufe, die ihm der Server gemeldet
     * hat - von Rechten weiss er nichts. Der Server dagegen laesst den Wechsel schon zu,
     * wenn {@code minecraft.command.gamemode} gesetzt ist. Ohne diese Meldung darf ein
     * Spieler also wechseln, bekommt den Umschalter aber nie zu sehen.
     *
     * <p>Das ist nur eine Auskunft an den Client und macht niemanden zum Operator:
     * Geprueft wird weiter auf dem Server, bei jedem Wechsel. Echte Operatoren bleiben
     * unberuehrt - ihre Stufe meldet der Server selbst.
     */
    private void sendOpLevel(Player player) {
        if (player.isOp()) {
            return;
        }
        player.sendOpLevel(opLevelFor(player));
    }

    /**
     * Welche Stufe der Client gemeldet bekommt.
     *
     * <p>4 nur beim vollen Wildcard: Wer allein den Spielmodus wechseln darf, soll dem
     * Client nicht als Voll-Operator erscheinen. Gefragt wird die Cloud direkt und nicht
     * {@code player.hasPermission("*")} - auf die Abfrage {@code *} passt nur die Regel
     * {@code *} selbst, und ein unbekannter Spieler bekommt ein klares Nein statt der
     * Bukkit-Vorgabe.
     *
     * <p>Ein einzelnes Verbot neben dem Wildcard aendert die Stufe nicht. Sie ist nur
     * Anzeige; das Verbot setzt der Server durch.
     */
    private byte opLevelFor(Player player) {
        if (permissions.has(player.getUniqueId(), WILDCARD)) {
            return FULL_OP_LEVEL;
        }
        return player.hasPermission(GAMEMODE_PERMISSION) ? SWITCHER_OP_LEVEL : (byte) 0;
    }

    /**
     * Nach Weltwechsel und Respawn meldet der Server die OP-Stufe von sich aus neu - und
     * damit wieder 0. Einen Tick spaeter, damit die eigene Meldung die letzte ist.
     */
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
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                sendOpLevel(player);
            }
        });
    }

    private void kick(de.kevloe.vibecloud.protocol.KickPlayer command) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(Protos.fromProto(command.getUuid()));
            if (player != null) {
                player.kick(render(player, command.getMessageKey(),
                        command.getPlaceholdersMap()));
            }
        });
    }

    // ---------------------------------------------------------------- Server -> Master

    /**
     * Beim Join: Rechte holen und Anzeige setzen.
     *
     * <p>{@link EventPriority#MONITOR}: Es wird nur beobachtet, nichts veraendert - und zu
     * diesem Zeitpunkt ist der Spieler sicher in der Liste.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (connection == null) {
            return;
        }
        // Nicht blockierend: Der Join soll nicht auf den Master warten.
        Bukkit.getScheduler().runTaskAsynchronously(this,
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
        // Der Spieler ist beim Quit noch in der Liste - deshalb einen abziehen,
        // sonst meldet der Server dauerhaft einen Spieler zu viel.
        reportState(Bukkit.getOnlinePlayers().size() - 1);
    }

    /**
     * Chat mit Rang-Prefix.
     *
     * <p>Gerendert ueber einen {@code ChatRenderer}: So bleibt die Nachricht selbst
     * unangetastet und andere Plugins koennen weiterhin mitlesen.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        if (connection == null) {
            return;
        }
        event.renderer((source, sourceDisplayName, message, viewer) ->
                display.renderChat(source, message)
                        .orElseGet(() -> Component.text("")
                                .append(sourceDisplayName)
                                .append(Component.text(": "))
                                .append(message)));
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

    // ---------------------------------------------------------------- Rechte-Bridge

    // ---------------------------------------------------------------- API fuer Bundles

    /**
     * Der Modul-Kanal zum Master.
     *
     * <p>Fuer die Plugin-Teile von Modulen (Bundles): Sie fragen darueber ihren
     * Master-Teil, statt eine eigene Verbindung oder gar Datenbank-Zugangsdaten zu
     * brauchen (PLAN.md Abschnitt 10).
     */
    public PluginModuleChannel moduleChannel() {
        return moduleChannel;
    }

    /**
     * Baut eine Cloud-Nachricht in der Sprache des Spielers.
     *
     * <p>Auch das ist fuer Bundles gedacht: Ein Modul liefert seine Texte an den Master,
     * und hier kommen sie fertig uebersetzt zurueck - der Schluessel traegt die Modul-Id
     * als Praefix (PLAN.md Abschnitt 11a).
     */
    public Component message(Player player, String key, Map<String, String> placeholders) {
        return render(player, key, placeholders);
    }

    /**
     * Schickt einen Spieler auf einen anderen Server der Cloud.
     *
     * <p>Fuer Server-Plugins: ein Minigame, das nach der Runde in die Lobby verteilt. Der
     * Wechsel laeuft ueber den Proxy - ein Paper-Server kann niemanden selbst weiterreichen.
     *
     * <p>Laeuft im Hintergrund, der Aufruf kehrt sofort zurueck. Scheitert es, steht die
     * Begruendung im Log des Servers; der Spieler bleibt, wo er ist.
     *
     * @param target Name eines Servers, nicht einer Gruppe
     */
    public void switchServer(Player player, String target) {
        switchServer(player.getUniqueId(), player.getName(), target);
    }

    /** Wie {@link #switchServer(Player, String)}, fuer einen Spieler ohne Objekt. */
    public void switchServer(UUID uuid, String name, String target) {
        if (connection == null) {
            getLogger().warning("Kein Cloud-Kontakt - " + name + " bleibt auf diesem Server.");
            return;
        }
        // Eigener Thread: Der Aufruf geht ueber das Netz, und der Haupt-Thread darf
        // darauf nicht warten - ein Tick haelt sonst den ganzen Server auf.
        Thread.ofVirtual().name("vibecloud-switch-" + name).start(() -> {
            var answer = connection.movePlayer(uuid, target);
            if (answer.isEmpty()) {
                getLogger().warning("Wechsel von " + name + " nach " + target
                                    + " fehlgeschlagen: Master nicht erreichbar");
                return;
            }
            if (!answer.get().getAccepted()) {
                getLogger().warning("Wechsel von " + name + " nach " + target
                                    + " abgelehnt: " + answer.get().getReason());
            }
        });
    }

    /**
     * Prueft ein Recht gegen die Cloud.
     *
     * <p>Fuer eigene Server-Plugins gedacht. {@code player.hasPermission(...)} fragt
     * ueber die {@link PermissibleInjector}-Bridge ebenfalls die Cloud, sodass auch
     * Fremd-Plugins ohne Anpassung funktionieren.
     */
    public boolean hasPermission(UUID uuid, String node) {
        return permissions != null && permissions.has(uuid, node);
    }

    public Optional<String> explainPermission(UUID uuid, String node) {
        return permissions == null
                ? Optional.empty()
                : permissions.explain(uuid, node).map(candidate ->
                        candidate.entry() + " aus " + candidate.source());
    }

    private Component render(Player player, String key, Map<String, String> placeholders) {
        String locale = permissions.localeOf(player.getUniqueId()).orElse(null);
        return connection.messages()
                .map(bundle -> miniMessage.deserialize(bundle.get(locale, key, placeholders)))
                .orElseGet(() -> Component.text(key));
    }
}
