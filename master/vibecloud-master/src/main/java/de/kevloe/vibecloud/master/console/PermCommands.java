package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionEntry;
import de.kevloe.vibecloud.api.permission.ResolvedPermissions;
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
 * {@code perm ...} (PLAN.md Abschnitt 9).
 *
 * <p>Der wichtigste Unterbefehl ist {@code perm check}: Er sagt nicht nur ja oder nein,
 * sondern <b>woher</b> das Ergebnis kommt. Ohne das sucht man bei einem falschen Recht die
 * ganze Rang-Hierarchie durch.
 */
public final class PermCommands implements CommandRegistry.Command {

    private final PermissionService permissions;
    private final RankRepository ranks;
    private final PlayerService players;
    private final CommandRegistry registry;

    /**
      * @param registry nur fuer die Vervollstaendigung: Daraus ergeben sich die
      *                 Rechte-Knoten der Befehle selbst
      */
    public PermCommands(PermissionService permissions, RankRepository ranks,
                        PlayerService players, CommandRegistry registry) {
        this.permissions = permissions;
        this.ranks = ranks;
        this.players = players;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "perm";
    }

    @Override
    public String description() {
        return "Rechte von Raengen und Spielern";
    }

    @Override
    public String usage() {
        return "perm rank add|remove <rang> <node> [group=<g>|server=<s>] "
               + "| perm player add|remove <spieler> <node> [dauer] [group=<g>|server=<s>] "
               + "| perm check <spieler> <node> [group=<g>|server=<s>] "
               + "| perm list <spieler>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("rank", "player", "check", "list");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);
        String last = args.getLast();

        return switch (sub) {
            // perm rank add|remove <rang> <node> [kontext]
            case "rank" -> switch (args.size()) {
                case 2 -> List.of("add", "remove");
                case 3 -> rankIds();
                case 4 -> permissionNodes();
                case 5 -> Completions.CONTEXTS;
                default -> List.of();
            };

            // perm player add|remove <spieler> <node> [dauer] [kontext]
            case "player" -> switch (args.size()) {
                case 2 -> List.of("add", "remove");
                case 3 -> players.suggestNames(last, 20);
                case 4 -> permissionNodes();
                case 5 -> Completions.DURATIONS;
                case 6 -> Completions.CONTEXTS;
                default -> List.of();
            };

            case "check" -> switch (args.size()) {
                case 2 -> players.suggestNames(last, 20);
                case 3 -> permissionNodes();
                case 4 -> Completions.CONTEXTS;
                default -> List.of();
            };

            case "list" -> args.size() == 2 ? players.suggestNames(last, 20) : List.of();

            default -> List.of();
        };
    }

    private List<String> rankIds() {
        return ranks.findAll().stream().map(RankRepository.Rank::id).sorted().toList();
    }

    private List<String> permissionNodes() {
        return Completions.permissionNodes(permissions, registry);
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "rank" -> rank(out, args);
            case "player" -> player(out, args);
            case "check" -> check(out, args);
            case "list" -> list(out, args);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    // ---------------------------------------------------------------- Rang-Rechte

    private void rank(CommandOutput out, List<String> args) {
        if (args.size() < 4) {
            out.error("Syntax: perm rank add|remove <rang> <node> [group=<g>|server=<s>]");
            return;
        }
        String rankId = args.get(2);
        if (ranks.find(rankId).isEmpty()) {
            out.error("Rang " + rankId + " existiert nicht.");
            return;
        }
        String node = args.get(3);
        PermissionContext context = parseContext(args, 4);

        if (args.get(1).equalsIgnoreCase("add")) {
            PermissionEntry entry = PermissionEntry.parse(node, context, null);
            permissions.addRankPermission(rankId, entry, "CONSOLE");
            out.success(rankId + ": " + entry);
            out.info("Wirkt sofort fuer alle Spieler mit diesem Rang - auch fuer die, "
                     + "die ihn erben.");
        } else {
            String cleaned = node.startsWith("-") ? node.substring(1) : node;
            if (permissions.removeRankPermission(rankId, cleaned, context, "CONSOLE")) {
                out.success(rankId + ": " + cleaned + " entfernt (" + context + ")");
            } else {
                out.error(rankId + " hat kein Recht " + cleaned + " mit Kontext " + context);
            }
        }
    }

    // ---------------------------------------------------------------- Spieler-Rechte

    private void player(CommandOutput out, List<String> args) {
        if (args.size() < 4) {
            out.error("Syntax: perm player add|remove <spieler> <node> [dauer] "
                      + "[group=<g>|server=<s>]");
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = players.findByName(args.get(2));
        if (record.isEmpty()) {
            out.error(args.get(2) + " ist unbekannt - der Spieler muss einmal "
                      + "verbunden gewesen sein.");
            return;
        }
        String node = args.get(3);
        PermissionContext context = parseContext(args, 4);

        if (args.get(1).equalsIgnoreCase("add")) {
            Instant expires = null;
            if (args.size() > 4) {
                Duration duration = RankCommands.parseDuration(args.get(4));
                if (duration != null) {
                    expires = Instant.now().plus(duration);
                }
            }
            PermissionEntry entry = PermissionEntry.parse(node, context, expires);
            permissions.addPlayerPermission(record.get().uuid(), entry, "CONSOLE");
            out.success(record.get().name() + ": " + entry + " ("
                        + RankCommands.formatUntil(expires) + ")");
        } else {
            String cleaned = node.startsWith("-") ? node.substring(1) : node;
            if (permissions.removePlayerPermission(record.get().uuid(), cleaned, context,
                    "CONSOLE")) {
                out.success(record.get().name() + ": " + cleaned + " entfernt");
            } else {
                out.error(record.get().name() + " hat kein eigenes Recht " + cleaned
                          + " mit Kontext " + context);
            }
        }
    }

    // ---------------------------------------------------------------- Pruefen

    /**
     * Sagt, ob ein Recht gilt - und welche Regel entschieden hat.
     *
     * <p>Das ist das wichtigste Werkzeug beim Debuggen: Man sieht, ob das Recht aus dem
     * eigenen Rang kommt, aus einem geerbten oder direkt vom Spieler.
     */
    private void check(CommandOutput out, List<String> args) {
        if (args.size() < 3) {
            out.error("Syntax: perm check <spieler> <node> [group=<g>|server=<s>]");
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = players.findByName(args.get(1));
        if (record.isEmpty()) {
            out.error(args.get(1) + " ist unbekannt.");
            return;
        }
        String node = args.get(2);
        PermissionContext context = parseContext(args, 3);

        var resolved = permissions.resolve(record.get().uuid());
        boolean allowed = resolved.has(node, context);
        Optional<ResolvedPermissions.Candidate> decision = resolved.decide(node, context);

        out.info(record.get().name() + " / " + node + " / " + context);
        if (allowed) {
            out.success("  ERLAUBT");
        } else {
            out.error("  VERBOTEN");
        }

        if (decision.isEmpty()) {
            out.info("  Keine Regel trifft zu - es gilt die Vorgabe (verboten).");
            return;
        }
        ResolvedPermissions.Candidate candidate = decision.get();
        out.info("  Entschieden durch: " + candidate.entry());
        out.info("  Quelle:            " + describe(candidate));

        // Alle anderen zutreffenden Regeln zeigen - oft ist genau da der Denkfehler.
        List<ResolvedPermissions.Candidate> others = resolved.candidates().stream()
                .filter(other -> other.entry().matches(node))
                .filter(other -> other.entry().context().appliesTo(context))
                .filter(other -> other != candidate)
                .toList();

        if (!others.isEmpty()) {
            out.info("  Ueberstimmte Regeln:");
            others.forEach(other -> out.info("    " + other.entry() + "  aus "
                                             + describe(other)));
        }
    }

    private void list(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: perm list <spieler>");
            return;
        }
        Optional<PlayerRepository.PlayerRecord> record = players.findByName(args.get(1));
        if (record.isEmpty()) {
            out.error(args.get(1) + " ist unbekannt.");
            return;
        }
        var resolved = permissions.resolve(record.get().uuid());

        out.info(record.get().name() + " hat den Rang " + record.get().rankId()
                 + (record.get().rankExpiresAt() == null ? ""
                    : " (" + RankCommands.formatUntil(record.get().rankExpiresAt()) + ")"));
        out.info("Alle zutreffenden Regeln (" + resolved.candidates().size() + "):");

        resolved.candidates().forEach(candidate ->
                out.info("  " + candidate.entry() + "  aus " + describe(candidate)));
    }

    private static String describe(ResolvedPermissions.Candidate candidate) {
        return switch (candidate.tier()) {
            case PLAYER -> "direkt beim Spieler";
            case OWN_RANK -> "eigenem Rang " + candidate.source();
            case INHERITED_RANK -> "geerbtem Rang " + candidate.source()
                                   + " (weight " + candidate.weight() + ")";
        };
    }

    /**
     * Liest {@code group=bedwars} oder {@code server=lobby-1} aus den restlichen Argumenten.
     *
     * <p>Alles, was nicht so aussieht, wird ignoriert - so stoert eine Dauerangabe nicht.
     */
    private static PermissionContext parseContext(List<String> args, int from) {
        String group = null;
        String server = null;
        for (int i = from; i < args.size(); i++) {
            String argument = args.get(i);
            if (argument.startsWith("group=")) {
                group = argument.substring("group=".length());
            } else if (argument.startsWith("server=")) {
                server = argument.substring("server=".length());
            }
        }
        return group == null && server == null
                ? PermissionContext.GLOBAL
                : new PermissionContext(group, server);
    }
}
