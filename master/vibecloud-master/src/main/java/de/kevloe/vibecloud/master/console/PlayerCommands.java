package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.server.ServerRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Spielerdaten ansehen (PLAN.md Abschnitt 12).
 *
 * <p>Nur Lesen. Geaendert wird ueber {@code rank} und {@code perm} - dort gehoert es hin,
 * und dort haengen die Rechte dafuer.
 */
public final class PlayerCommands implements CommandRegistry.Command {

    private final PlayerService players;
    private final ServerRegistry servers;

    public PlayerCommands(PlayerService players, ServerRegistry servers) {
        this.players = players;
        this.servers = servers;
    }

    @Override
    public String name() {
        return "player";
    }

    @Override
    public String description() {
        return "Spielerdaten ansehen";
    }

    @Override
    public String usage() {
        return "player info <spieler> | find <text>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("info", "find");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        // Bei "find" ist der Text absichtlich frei - er soll ja suchen.
        return args.size() == 2 && args.getFirst().equalsIgnoreCase("info")
                ? players.suggestNames(args.getLast(), 20) : List.of();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "info" -> info(out, args.get(1));
            case "find" -> find(out, args.get(1));
            default -> out.error("Syntax: " + usage());
        }
    }

    private void info(CommandOutput out, String name) {
        Optional<PlayerRepository.PlayerRecord> found = players.findByName(name);
        if (found.isEmpty()) {
            out.error("Unbekannter Spieler: " + name
                      + " (er muss einmal verbunden gewesen sein)");
            return;
        }
        PlayerRepository.PlayerRecord player = found.get();

        out.info("Spieler " + player.name());
        out.info("  UUID        " + player.uuid());
        out.info("  Plattform   " + player.platform());
        out.info("  Rang        " + player.rankId()
                 + (player.rankExpiresAt() == null ? ""
                        : " (bis " + Times.format(player.rankExpiresAt()) + ")"));
        out.info("  Sprache     " + (player.locale() == null ? "-" : player.locale()));
        out.info("  Erster Join " + Times.format(player.firstLogin()));
        out.info("  Letzter     " + Times.format(player.lastLogin()));
        out.info("  Spielzeit   " + Times.formatDuration(
                Duration.ofSeconds(player.playtimeSeconds())));

        // Der letzte bekannte Server - ob er noch laeuft, sagt die Registry dazu.
        String lastServer = player.lastServer();
        if (lastServer == null || lastServer.isBlank()) {
            out.info("  Server      -");
        } else {
            out.info("  Server      " + lastServer
                     + (servers.find(lastServer).isPresent() ? "" : " (nicht mehr aktiv)"));
        }
    }

    private void find(CommandOutput out, String text) {
        List<String> names = players.suggestNames(text, 25);
        if (names.isEmpty()) {
            out.info("Niemand gefunden, dessen Name mit '" + text + "' anfaengt.");
            return;
        }
        out.info(names.size() + " Treffer, zuletzt gesehene zuerst:");
        names.forEach(name -> out.info("  " + name));
    }
}
