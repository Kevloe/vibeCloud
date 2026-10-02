package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.http.AccountService;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Zugaenge zum Dashboard verwalten (PLAN.md Abschnitt 12).
 *
 * <p>Es gibt keine Registrierung im Dashboard - ein Zugang entsteht nur hier. Das
 * Start-Passwort erscheint genau einmal in der Ausgabe und wird nirgends geloggt.
 *
 * <p>Der Befehl ist auch im Spiel verfuegbar, so wie es der Plan vorsieht: Wer jemandem
 * einen Zugang gibt, steht meist neben ihm. Ein Zugang braucht ohnehin
 * {@code vibecloud.dashboard.login} - das Anlegen allein verschafft niemandem Rechte.
 */
public final class AcpCommands implements CommandRegistry.Command {

    private final AccountService accounts;
    private final PlayerService players;

    public AcpCommands(AccountService accounts, PlayerService players) {
        this.accounts = accounts;
        this.players = players;
    }

    @Override
    public String name() {
        return "acp";
    }

    @Override
    public String description() {
        return "Dashboard-Zugaenge verwalten";
    }

    @Override
    public String usage() {
        return "acp create <spieler> | changepw <spieler> | disable <spieler> "
               + "| enable <spieler> | remove <spieler> | list";
    }

    @Override
    public List<String> subCommands() {
        return List.of("create", "changepw", "disable", "enable", "remove", "list");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        if (args.size() == 2 && !args.getFirst().equalsIgnoreCase("list")) {
            return players.suggestNames(args.getLast(), 20);
        }
        return List.of();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        execute(CommandRegistry.CommandActor.console(), out, args);
    }

    @Override
    public void execute(CommandRegistry.CommandActor actor, CommandOutput out,
                        List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            list(out);
            return;
        }
        if (args.size() < 2) {
            out.error("Syntax: acp " + sub + " <spieler>");
            return;
        }
        Optional<PlayerRepository.PlayerRecord> found = players.findByName(args.get(1));
        if (found.isEmpty()) {
            out.error("Unbekannter Spieler: " + args.get(1)
                      + " (er muss einmal verbunden gewesen sein)");
            return;
        }
        PlayerRepository.PlayerRecord player = found.get();

        try {
            switch (sub) {
                case "create" -> create(out, player, actor.name());
                case "changepw" -> changePassword(out, player, actor.name());
                case "disable" -> setDisabled(out, player, true, actor.name());
                case "enable" -> setDisabled(out, player, false, actor.name());
                case "remove" -> remove(out, player, actor.name());
                default -> out.error("Syntax: " + usage());
            }
        } catch (IllegalStateException exception) {
            // Kein Recht, Zugang existiert schon, kein Zugang vorhanden - der Dienst sagt,
            // was los ist.
            out.error(exception.getMessage());
        }
    }

    private void create(CommandOutput out, PlayerRepository.PlayerRecord player,
                        String actor) {
        String password = accounts.create(player.uuid(), player.name(), actor);

        out.success("Dashboard-Zugang fuer " + player.name() + " angelegt.");
        out.info("");
        out.info("  Benutzername: " + player.name());
        out.info("  Passwort:     " + password);
        out.info("");
        out.warn("Das Passwort wird nur jetzt angezeigt und muss beim ersten Anmelden "
                 + "geaendert werden.");
    }

    private void changePassword(CommandOutput out, PlayerRepository.PlayerRecord player,
                                String actor) {
        String password = accounts.resetPassword(player.uuid(), actor);

        out.success("Neues Start-Passwort fuer " + player.name() + ".");
        out.info("");
        out.info("  Passwort: " + password);
        out.info("");
        out.warn("Alle offenen Sitzungen sind damit beendet.");
    }

    private void setDisabled(CommandOutput out, PlayerRepository.PlayerRecord player,
                             boolean disabled, String actor) {
        if (!accounts.setDisabled(player.uuid(), disabled, actor)) {
            out.error(player.name() + " hat keinen Zugang.");
            return;
        }
        out.success(player.name() + (disabled
                ? " ist gesperrt - der Zugang bleibt, die Anmeldung geht nicht."
                : " kann sich wieder anmelden."));
    }

    private void remove(CommandOutput out, PlayerRepository.PlayerRecord player,
                        String actor) {
        if (!accounts.delete(player.uuid(), actor, "von Hand entfernt")) {
            out.error(player.name() + " hat keinen Zugang.");
            return;
        }
        out.success("Zugang von " + player.name() + " geloescht.");
    }

    private void list(CommandOutput out) {
        List<AccountService.Account> all = accounts.all();
        if (all.isEmpty()) {
            out.info("Keine Zugaenge. Anlegen mit: acp create <spieler>");
            return;
        }
        out.info(String.format("%-18s %-10s %-18s %s",
                "BENUTZER", "ZUSTAND", "ZULETZT", "ANGELEGT VON"));

        for (AccountService.Account account : all) {
            out.info(String.format("%-18s %-10s %-18s %s",
                    account.username(),
                    state(account),
                    account.lastLoginAt() == null ? "nie"
                            : Times.format(account.lastLoginAt()),
                    account.createdBy()));
        }
    }

    private static String state(AccountService.Account account) {
        if (account.disabled()) {
            return "gesperrt";
        }
        if (account.mustChangePassword()) {
            return "neu";
        }
        return "aktiv";
    }
}
