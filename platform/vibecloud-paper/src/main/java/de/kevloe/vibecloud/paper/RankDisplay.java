package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import de.kevloe.vibecloud.protocol.RankData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Zeigt Raenge im Spiel an (PLAN.md Abschnitt 11).
 *
 * <p>Ohne das sieht man von den Raengen nichts - deshalb gehoert es in den Core und nicht
 * in ein spaeteres Modul.
 *
 * <h2>Tab-Liste und Nametags</h2>
 * Beides laeuft ueber Scoreboard-Teams. Der Trick bei der Sortierung: Minecraft sortiert
 * Teams <b>alphabetisch</b> nach ihrem Namen. Damit ein hoeherer Rang oben steht, wird das
 * Gewicht invertiert und nullgepolstert in den Teamnamen geschrieben:
 * weight 100 wird zu {@code 0899_admin}, weight 0 zu {@code 0999_spieler}.
 */
public final class RankDisplay {

    /** Teamnamen sind auf 16 Zeichen begrenzt - deshalb Praefix kurz und Rang gekuerzt. */
    private static final int MAX_TEAM_NAME = 16;

    private static final String DEFAULT_CHAT_FORMAT =
            "<prefix><name><suffix><gray>:</gray> <message>";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final CloudPermissions permissions;
    private final Logger logger;
    private final boolean chatEnabled;

    public RankDisplay(CloudPermissions permissions, Logger logger, boolean chatEnabled) {
        this.permissions = permissions;
        this.logger = logger;
        this.chatEnabled = chatEnabled;
    }

    /**
     * Baut die Chat-Zeile aus dem Format des Rangs.
     *
     * @return gerenderte Zeile, oder leer wenn das Chat-Format abgeschaltet ist
     */
    public Optional<Component> renderChat(Player player, Component message) {
        if (!chatEnabled) {
            return Optional.empty();
        }
        RankData rank = permissions.rankOf(player.getUniqueId()).orElse(null);
        String format = rank == null || rank.getChatFormat().isBlank()
                ? DEFAULT_CHAT_FORMAT
                : rank.getChatFormat();

        return Optional.of(miniMessage.deserialize(format,
                Placeholder.parsed("prefix", rank == null ? "" : rank.getPrefix()),
                Placeholder.parsed("suffix", rank == null ? "" : rank.getSuffix()),
                Placeholder.unparsed("name", player.getName()),
                Placeholder.component("message", message)));
    }

    /** Setzt Prefix, Suffix und Sortierung fuer einen Spieler. */
    public void apply(Player player) {
        RankData rank = permissions.rankOf(player.getUniqueId()).orElse(null);
        if (rank == null) {
            return;
        }
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = teamFor(scoreboard, rank);

            team.prefix(miniMessage.deserialize(rank.getPrefix()));
            team.suffix(miniMessage.deserialize(rank.getSuffix()));
            // Die Farbe des Rangs faerbt auch den Namen ueber dem Kopf.
            team.color(colorOf(rank));

            // Spieler aus anderen Teams entfernen, sonst bleibt der alte Rang stehen.
            scoreboard.getTeams().stream()
                    .filter(other -> !other.equals(team))
                    .filter(other -> other.hasEntry(player.getName()))
                    .forEach(other -> other.removeEntry(player.getName()));

            team.addEntry(player.getName());

            // Der Name in der Tab-Liste selbst: Prefix plus Name.
            player.playerListName(miniMessage.deserialize(
                    rank.getPrefix() + "<name>" + rank.getSuffix(),
                    Placeholder.unparsed("name", player.getName())));

        } catch (RuntimeException exception) {
            // Anzeigefehler duerfen niemanden vom Server werfen.
            logger.warning("Rang-Anzeige fuer " + player.getName() + " fehlgeschlagen: "
                           + exception.getMessage());
        }
    }

    /** Alle sichtbaren Spieler neu einfaerben - nach einer Rang-Aenderung. */
    public void applyAll() {
        Bukkit.getOnlinePlayers().forEach(this::apply);
    }

    public void remove(Player player) {
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            scoreboard.getTeams().stream()
                    .filter(team -> team.hasEntry(player.getName()))
                    .forEach(team -> team.removeEntry(player.getName()));
        } catch (RuntimeException exception) {
            logger.fine("Team-Eintrag von " + player.getName() + " nicht entfernt");
        }
    }

    private Team teamFor(Scoreboard scoreboard, RankData rank) {
        String name = teamName(rank);
        Team team = scoreboard.getTeam(name);
        return team != null ? team : scoreboard.registerNewTeam(name);
    }

    /**
     * Teamname mit invertiertem Gewicht als Praefix.
     *
     * <p>Minecraft sortiert Teams alphabetisch. {@code 999 - weight} dreht die Reihenfolge,
     * sodass ein hoeherer Rang weiter oben in der Tab-Liste steht.
     */
    static String teamName(RankData rank) {
        int inverted = Math.max(0, Math.min(999, 999 - rank.getWeight()));
        String prefix = String.format("%04d_", inverted);
        String id = rank.getId();
        int room = MAX_TEAM_NAME - prefix.length();
        return prefix + (id.length() > room ? id.substring(0, room) : id);
    }

    /**
     * Uebersetzt die Rang-Farbe in eine Bukkit-Farbe.
     *
     * <p>Die Farbe steht als MiniMessage in der Datenbank ({@code <red>}), Teams brauchen
     * aber ein {@code NamedTextColor}. Deshalb wird ein Beispieltext gerendert und dessen
     * Farbe uebernommen - so bleibt die Datenbank bei einem Format.
     */
    private net.kyori.adventure.text.format.NamedTextColor colorOf(RankData rank) {
        try {
            Component sample = miniMessage.deserialize(rank.getColor() + "x");
            var color = sample.color();
            if (color != null) {
                return net.kyori.adventure.text.format.NamedTextColor.nearestTo(color);
            }
        } catch (RuntimeException exception) {
            logger.fine("Farbe '" + rank.getColor() + "' nicht lesbar");
        }
        return net.kyori.adventure.text.format.NamedTextColor.GRAY;
    }

    /** Nur fuer Logs: der Rang als lesbarer Text. */
    String describe(UUID uuid) {
        return permissions.rankOf(uuid)
                .map(rank -> PlainTextComponentSerializer.plainText()
                        .serialize(miniMessage.deserialize(rank.getDisplayName())))
                .orElse("?");
    }
}
