package de.kevloe.vibecloud.punishment.paper;

import de.kevloe.vibecloud.api.plugin.PluginModuleChannel;
import de.kevloe.vibecloud.paper.VibeCloudPaper;
import de.kevloe.vibecloud.punishment.api.PunishmentAnswer;
import de.kevloe.vibecloud.punishment.api.PunishmentQuery;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Der Paper-Teil des Punishment-Moduls: der Mute-Filter im Chat.
 *
 * <p><b>Dieses Plugin kennt keine Strafen.</b> Es fragt bei jeder Nachricht den Master -
 * der Gameserver hat weder die Tabelle noch Zugangsdaten dafuer (PLAN.md Abschnitt 3).
 * Verteilt wird es als {@code bundles/paper.jar} im Modul-JAR; der Wrapper legt es beim
 * Serverstart in {@code plugins/}.
 */
public final class PunishmentPaperPlugin extends JavaPlugin implements Listener {

    private static final String MODULE_ID = "punishment";

    /**
     * Wie lange auf die Antwort des Masters gewartet wird.
     *
     * <p>Kurz gehalten: Der Spieler wartet auf seine eigene Chatnachricht. Lieber einmal
     * durchlassen als jeden Chat spuerbar verzoegern.
     */
    private static final Duration TIMEOUT = Duration.ofMillis(750);

    private PluginModuleChannel channel;
    private VibeCloudPaper cloud;

    @Override
    public void onEnable() {
        cloud = (VibeCloudPaper) Bukkit.getPluginManager().getPlugin("vibeCloud");
        if (cloud == null) {
            getLogger().severe("vibeCloud fehlt - der Mute-Filter bleibt aus.");
            return;
        }
        channel = cloud.moduleChannel();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Mute-Filter aktiv");
    }

    /**
     * Prueft vor dem Absenden, ob der Spieler stummgeschaltet ist.
     *
     * <p>{@code AsyncChatEvent} laeuft bereits ausserhalb des Haupt-Threads, deshalb darf
     * hier auf die Antwort gewartet werden, ohne den Tick anzuhalten.
     *
     * <p>{@link EventPriority#LOWEST}: vor allen anderen. Eine stummgeschaltete Nachricht
     * soll gar nicht erst in einem Chat-Plugin landen.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (channel == null) {
            return;
        }
        Player player = event.getPlayer();
        Optional<PunishmentAnswer> answer = channel.ask(MODULE_ID, "check",
                new PunishmentQuery(player.getUniqueId(), "MUTE"),
                PunishmentAnswer.class, TIMEOUT);

        // Keine Antwort heisst: schreiben lassen. Ein Master-Ausfall darf nicht den ganzen
        // Chat lahmlegen - anders als beim Login, wo ein Ausfall sperrt.
        if (answer.isEmpty() || !answer.get().active()) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(cloud.message(player, "punishment.mute.blocked", Map.of(
                "grund", answer.get().reason(),
                "ablauf", answer.get().expires(),
                "kennung", answer.get().appealId())));
    }
}
