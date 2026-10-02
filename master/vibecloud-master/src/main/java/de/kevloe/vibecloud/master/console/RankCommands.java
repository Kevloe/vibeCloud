package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code rank ...} (PLAN.md Abschnitt 9).
 *
 * <p>Ein Spieler hat <b>genau einen</b> Rang - deshalb {@code rank set} und
 * {@code rank reset}, kein {@code add}/{@code remove}. Rechte-Sets kombiniert man ueber
 * Vererbung ({@code rank inherit}).
 */
public final class RankCommands implements CommandRegistry.Command {

    private final PermissionService permissions;
    private final RankRepository ranks;
    private final PlayerService playerService;

    public RankCommands(PermissionService permissions, RankRepository ranks,
                        PlayerService playerService) {
        this.permissions = permissions;
        this.ranks = ranks;
        this.playerService = playerService;
    }

    @Override
    public String name() {
        return "rank";
    }

    @Override
    public String description() {
        return "Raenge verwalten und Spielern zuweisen";
    }

    @Override
    public String usage() {
        return "rank list | info <rang> | create <id> [weight] | delete <id> "
               + "| edit <rang> <feld> <wert> | inherit add|remove <rang> <parent> "
               + "| set <spieler> <rang> [dauer] | reset <spieler> | history <spieler>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("list", "info", "create", "delete", "edit", "inherit",
                "set", "reset", "history");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);
        String last = args.getLast();

        return switch (sub) {
            case "info", "delete" -> args.size() == 2 ? rankIds() : List.of();

            case "create" -> args.size() == 3 ? List.of("0", "10", "50", "100") : List.of();

            case "edit" -> switch (args.size()) {
                case 2 -> rankIds();
                case 3 -> RankRepository.editableFields();
                default -> List.of();
            };

            // inherit add|remove <rang> <parent>: beide Stellen sind Raenge.
            case "inherit" -> switch (args.size()) {
                case 2 -> List.of("add", "remove");
                case 3, 4 -> rankIds();
                default -> List.of();
            };

            // set <spieler> <rang> [dauer]
            case "set" -> switch (args.size()) {
                case 2 -> playerNames(last);
                case 3 -> rankIds();
                case 4 -> Completions.DURATIONS;
                default -> List.of();
            };

            case "reset", "history" -> args.size() == 2 ? playerNames(last) : List.of();

            default -> List.of();
        };
    }

    private List<String> rankIds() {
        return ranks.findAll().stream().map(RankRepository.Rank::id).sorted().toList();
    }

    /**
     * Spielernamen zum angefangenen Text.
     *
     * <p>Mit Praefix gesucht und nicht die ganze Tabelle geladen: Bei vielen Spielern
     * waere das eine Abfrage ohne Grenze bei jedem Tastendruck.
     */
    private List<String> playerNames(String prefix) {
        return playerService.suggestNames(prefix, 20);
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "list" -> list(out);
            case "info" -> info(out, args);
            case "create" -> create(out, args);
            case "delete" -> delete(out, args);
            case "edit" -> edit(out, args);
            case "inherit" -> inherit(out, args);
            case "set" -> set(out, args);
            case "reset" -> reset(out, args);
            case "history" -> history(out, args);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private void list(CommandOutput out) {
        List<RankRepository.Rank> all = ranks.findAll();
        var inheritance = ranks.inheritance();

        out.info(String.format("%-14s %-8s %-8s %-18s %s",
                "ID", "WEIGHT", "DEFAULT", "ERBT VON", "PREFIX"));
        for (RankRepository.Rank rank : all) {
            List<String> parents = inheritance.getOrDefault(rank.id(), List.of());
            out.info(String.format("%-14s %-8d %-8s %-18s %s",
                    rank.id(), rank.weight(), rank.isDefault() ? "ja" : "",
                    parents.isEmpty() ? "-" : String.join(",", parents),
                    rank.prefix().isBlank() ? "-" : rank.prefix()));
        }
    }

    private void info(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: rank info <rang>");
            return;
        }
        ranks.find(args.get(1)).ifPresentOrElse(rank -> {
            out.info("Rang " + rank.id());
            out.info("  Anzeigename  " + rank.displayName());
            out.info("  Weight       " + rank.weight()
                     + (rank.isDefault() ? "  (Default-Rang)" : ""));
            out.info("  Prefix       " + (rank.prefix().isBlank() ? "-" : rank.prefix()));
            out.info("  Suffix       " + (rank.suffix().isBlank() ? "-" : rank.suffix()));
            out.info("  Farbe        " + rank.color());
            out.info("  Chat-Format  " + (rank.chatFormat() == null
                    ? "(globale Vorgabe)" : rank.chatFormat()));

            List<String> parents = ranks.inheritance().getOrDefault(rank.id(), List.of());
            out.info("  Erbt von     " + (parents.isEmpty() ? "-" : String.join(", ", parents)));

            var own = ranks.permissionsOf(rank.id());
            out.info("  Eigene Rechte (" + own.size() + "):");
            own.forEach(entry -> out.info("    " + entry));
        }, () -> out.error("Rang " + args.get(1) + " existiert nicht."));
    }

    private void create(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: rank create <id> [weight]");
            return;
        }
        String id = args.get(1).toLowerCase(Locale.ROOT);
        if (ranks.find(id).isPresent()) {
            out.error("Rang " + id + " existiert bereits.");
            return;
        }
        int weight = 0;
        if (args.size() > 2) {
            try {
                weight = Integer.parseInt(args.get(2));
            } catch (NumberFormatException exception) {
                out.error(args.get(2) + " ist keine Zahl.");
                return;
            }
        }
        ranks.create(RankRepository.Rank.simple(id, weight));
        out.success("Rang " + id + " angelegt (weight " + weight + ").");
        out.info("Rechte geben:  perm rank add " + id + " <node>");
        out.info("Vererbung:     rank inherit add " + id + " <parent>");
        out.info("Prefix setzen: rank edit " + id + " prefix <MiniMessage>");
    }

    private void delete(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: rank delete <rang>");
            return;
        }
        try {
            if (ranks.delete(args.get(1))) {
                permissions.invalidateAll();
                out.success("Rang " + args.get(1) + " geloescht.");
            } else {
                out.error("Rang " + args.get(1) + " existiert nicht.");
            }
        } catch (IllegalStateException exception) {
            // Typischer Fall: Es tragen noch Spieler diesen Rang, oder es ist der Default.
            out.error(exception.getMessage());
            out.info("Erst die Spieler umsetzen: rank set <spieler> <anderer-rang>");
        }
    }

    private void edit(CommandOutput out, List<String> args) {
        if (args.size() < 4) {
            out.error("Syntax: rank edit <rang> <feld> <wert>");
            out.info("Felder: displayName prefix suffix color weight chatFormat");
            return;
        }
        String value = String.join(" ", args.subList(3, args.size()));
        try {
            if (ranks.updateField(args.get(1), args.get(2), value)) {
                permissions.invalidateRank(args.get(1));
                out.success(args.get(1) + ": " + args.get(2) + " = " + value);
            } else {
                out.error("Rang " + args.get(1) + " existiert nicht.");
            }
        } catch (IllegalArgumentException exception) {
            out.error(exception.getMessage());
        }
    }

    private void inherit(CommandOutput out, List<String> args) {
        if (args.size() < 4) {
            out.error("Syntax: rank inherit add|remove <rang> <parent>");
            return;
        }
        String child = args.get(2);
        String parent = args.get(3);

        if (ranks.find(child).isEmpty()) {
            out.error("Rang " + child + " existiert nicht.");
            return;
        }
        if (ranks.find(parent).isEmpty()) {
            out.error("Rang " + parent + " existiert nicht.");
            return;
        }

        if (args.get(1).equalsIgnoreCase("add")) {
            try {
                permissions.addInheritance(child, parent, "CONSOLE");
                out.success(child + " erbt jetzt von " + parent + ".");
            } catch (IllegalArgumentException exception) {
                out.error(exception.getMessage());
            }
        } else if (permissions.removeInheritance(child, parent, "CONSOLE")) {
            out.success(child + " erbt nicht mehr von " + parent + ".");
        } else {
            out.error(child + " erbt gar nicht von " + parent + ".");
        }
    }

    private void set(CommandOutput out, List<String> args) {
        if (args.size() < 3) {
            out.error("Syntax: rank set <spieler> <rang> [dauer, z. B. 30d oder 12h]");
            return;
        }
        Optional<PlayerRepository.PlayerRecord> player = playerService.findByName(args.get(1));
        if (player.isEmpty()) {
            out.error(args.get(1) + " ist unbekannt - der Spieler muss einmal "
                      + "verbunden gewesen sein.");
            return;
        }

        Duration duration = null;
        if (args.size() > 3) {
            duration = parseDuration(args.get(3));
            if (duration == null) {
                out.error("Dauer nicht verstanden: " + args.get(3)
                          + " (erlaubt: 30d, 12h, 90m)");
                return;
            }
        }

        try {
            permissions.setRank(player.get().uuid(), args.get(2).toLowerCase(Locale.ROOT),
                    duration, "CONSOLE", null);
            out.success(player.get().name() + " hat jetzt den Rang " + args.get(2)
                        + (duration == null ? "." : " fuer " + args.get(3) + "."));
            out.info("Wirkt sofort - die Plugins laden die Rechte neu, ohne Relog.");
        } catch (IllegalArgumentException exception) {
            out.error(exception.getMessage());
        }
    }

    private void reset(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: rank reset <spieler>");
            return;
        }
        playerService.findByName(args.get(1)).ifPresentOrElse(player -> {
            permissions.resetRank(player.uuid(), "CONSOLE");
            out.success(player.name() + " hat wieder den Default-Rang.");
        }, () -> out.error(args.get(1) + " ist unbekannt."));
    }

    private void history(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: rank history <spieler>");
            return;
        }
        playerService.findByName(args.get(1)).ifPresentOrElse(player -> {
            var history = playerService.rankHistory(player.uuid(), 20);
            if (history.isEmpty()) {
                out.info("Keine Rang-Aenderungen fuer " + player.name() + ".");
                return;
            }
            out.info("Rang-Verlauf von " + player.name() + ":");
            history.forEach(change -> out.info(String.format("  %s  %-12s -> %-12s  von %s%s",
                    Times.formatWithSeconds(change.at()),
                    change.oldRank() == null ? "-" : change.oldRank(),
                    change.newRank(), change.actor(),
                    change.reason() == null ? "" : " (" + change.reason() + ")")));
        }, () -> out.error(args.get(1) + " ist unbekannt."));
    }

    /**
     * Versteht {@code 30d}, {@code 12h}, {@code 90m}, {@code 45s}.
     *
     * <p>Die Schreibweise steht in {@link Times} - dieselbe Angabe tippt ein Betreiber
     * auch bei {@code perm player add} und im Dashboard, und sie muss ueberall dasselbe
     * bedeuten.
     */
    static Duration parseDuration(String text) {
        return Times.parseDuration(text);
    }

    /** Nur fuer die Anzeige - macht aus einem Zeitpunkt etwas Lesbares. */
    static String formatUntil(Instant instant) {
        return instant == null ? "permanent" : "bis " + Times.format(instant);
    }
}
