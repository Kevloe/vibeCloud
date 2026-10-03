package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import de.kevloe.vibecloud.protocol.RankData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Raenge im Spiel anzeigen - wie {@code RankDisplay}, aber mit den String-Methoden von
 * Bukkit (siehe {@link LegacyText}).
 *
 * <p>Die Sortierung der Tab-Liste kommt aus {@link RankTeams}, derselben Datei wie im
 * aktuellen Plugin.
 */
final class LegacyRankDisplay {

    private static final String DEFAULT_CHAT_FORMAT =
            "<prefix><name><suffix><gray>:</gray> <message>";

    private final CloudPermissions permissions;
    private final Logger logger;
    private final boolean chatEnabled;

    LegacyRankDisplay(CloudPermissions permissions, Logger logger, boolean chatEnabled) {
        this.permissions = permissions;
        this.logger = logger;
        this.chatEnabled = chatEnabled;
    }

    /** @return Format fuer {@code AsyncPlayerChatEvent}, leer wenn abgeschaltet */
    Optional<String> chatFormat(Player player) {
        if (!chatEnabled) {
            return Optional.empty();
        }
        RankData rank = permissions.rankOf(player.getUniqueId()).orElse(null);
        String format = rank == null || rank.getChatFormat().isBlank()
                ? DEFAULT_CHAT_FORMAT
                : rank.getChatFormat();
        return Optional.of(LegacyText.chatFormat(format,
                rank == null ? "" : rank.getPrefix(),
                rank == null ? "" : rank.getSuffix()));
    }

    /** Setzt Prefix, Suffix und Sortierung fuer einen Spieler. */
    void apply(Player player) {
        RankData rank = permissions.rankOf(player.getUniqueId()).orElse(null);
        if (rank == null) {
            return;
        }
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            String name = RankTeams.teamName(rank);
            Team existing = scoreboard.getTeam(name);
            Team team = existing != null ? existing : scoreboard.registerNewTeam(name);

            team.setPrefix(LegacyText.render(rank.getPrefix()));
            team.setSuffix(LegacyText.render(rank.getSuffix()));
            team.setColor(colorOf(rank));

            // Spieler aus anderen Teams entfernen, sonst bleibt der alte Rang stehen.
            for (Team other : scoreboard.getTeams()) {
                if (!other.equals(team) && other.hasEntry(player.getName())) {
                    other.removeEntry(player.getName());
                }
            }
            team.addEntry(player.getName());

            player.setPlayerListName(LegacyText.nameLine(rank.getPrefix(), player.getName(),
                    rank.getSuffix()));

        } catch (RuntimeException exception) {
            // Anzeigefehler duerfen niemanden vom Server werfen.
            logger.warning("Rang-Anzeige fuer " + player.getName() + " fehlgeschlagen: "
                           + exception.getMessage());
        }
    }

    void remove(Player player) {
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team team : scoreboard.getTeams()) {
                if (team.hasEntry(player.getName())) {
                    team.removeEntry(player.getName());
                }
            }
        } catch (RuntimeException exception) {
            logger.fine("Team-Eintrag von " + player.getName() + " nicht entfernt");
        }
    }

    /**
     * Die Rang-Farbe als {@link ChatColor}.
     *
     * <p>Wie im aktuellen Plugin: Ein Beispieltext wird gerendert und die naechste der 16
     * benannten Farben genommen. Deren Namen ({@code dark_red}) sind die von ChatColor.
     */
    private ChatColor colorOf(RankData rank) {
        try {
            Component sample = LegacyText.parse(rank.getColor() + "x");
            TextColor color = sample.color();
            if (color != null) {
                return ChatColor.valueOf(NamedTextColor.nearestTo(color).toString()
                        .toUpperCase(Locale.ROOT));
            }
        } catch (RuntimeException exception) {
            logger.fine("Farbe '" + rank.getColor() + "' nicht lesbar");
        }
        return ChatColor.GRAY;
    }
}
