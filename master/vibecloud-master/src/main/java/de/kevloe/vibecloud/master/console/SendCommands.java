package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.server.PlayerTransferService;
import de.kevloe.vibecloud.master.server.ServerRegistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spieler zwischen Servern verschieben (PLAN.md Abschnitt 11 und 12).
 *
 * <p>Zwei Befehle, weil es zwei verschiedene Rechte sind: sich selbst umsetzen darf
 * typischerweise jeder Spieler, andere umsetzen nur das Team.
 *
 * <ul>
 *   <li>{@code switch <server>} - der Aufrufer selbst</li>
 *   <li>{@code send <spieler> <server>} - jemand anderes, auch aus der Konsole</li>
 * </ul>
 *
 * <p>Verschoben wird nicht hier: Der Master schickt den Transfer-Befehl an die Proxys, und
 * derjenige, der den Spieler hat, fuehrt ihn aus. Nur der Proxy weiss, wer gerade wo ist.
 */
public final class SendCommands {

    private SendCommands() {
    }

    /** {@code switch <server>} - nur fuer Spieler. */
    public static CommandRegistry.Command self(ServerRegistry servers,
                                               PlayerTransferService transfers) {
        return new CommandRegistry.Command() {

            @Override
            public String name() {
                return "switch";
            }

            @Override
            public String description() {
                return "Auf einen anderen Server wechseln";
            }

            @Override
            public String usage() {
                return "switch <server>";
            }

            @Override
            public List<String> complete(List<String> args) {
                return args.size() <= 1 ? runningServers(servers) : List.of();
            }

            @Override
            public void execute(CommandOutput out, List<String> args) {
                // Ohne Aufrufer nicht sinnvoll - der Fall wird unten abgefangen.
                out.error("Dieser Befehl ist fuer Spieler. In der Konsole: "
                          + "send <spieler> <server>");
            }

            @Override
            public void execute(CommandRegistry.CommandActor actor, CommandOutput out,
                                List<String> args) {
                if (actor.isConsole()) {
                    execute(out, args);
                    return;
                }
                if (args.isEmpty()) {
                    out.error("Syntax: " + usage());
                    return;
                }
                transfer(out, transfers, servers, actor.uuid(), actor.name(),
                        args.getFirst(), actor.name());
            }
        };
    }

    /** {@code send <spieler> <server>} - jemand anderes. */
    public static CommandRegistry.Command other(ServerRegistry servers, PlayerService players,
                                                PlayerTransferService transfers) {
        return new CommandRegistry.Command() {

            @Override
            public String name() {
                return "send";
            }

            @Override
            public String description() {
                return "Einen Spieler auf einen anderen Server schicken";
            }

            @Override
            public String usage() {
                return "send <spieler> <server>";
            }

            @Override
            public List<String> complete(List<String> args) {
                return switch (args.size()) {
                    case 0, 1 -> players.suggestNames(args.isEmpty() ? "" : args.getLast(), 20);
                    case 2 -> runningServers(servers);
                    default -> List.of();
                };
            }

            @Override
            public void execute(CommandOutput out, List<String> args) {
                execute(CommandRegistry.CommandActor.console(), out, args);
            }

            @Override
            public void execute(CommandRegistry.CommandActor actor, CommandOutput out,
                                List<String> args) {
                if (args.size() < 2) {
                    out.error("Syntax: " + usage());
                    return;
                }
                Optional<?> found = players.findByName(args.getFirst());
                if (found.isEmpty()) {
                    out.error("Unbekannter Spieler: " + args.getFirst());
                    return;
                }
                var record = (de.kevloe.vibecloud.master.player.PlayerRepository.PlayerRecord)
                        found.get();

                transfer(out, transfers, servers, record.uuid(), record.name(),
                        args.get(1), actor.name());
            }
        };
    }

    /**
     * Prueft das Ziel und schickt den Transfer los.
     *
     * <p>Das Ziel wird gegen die Registry geprueft, nicht nur weitergegeben: Ein Tippfehler
     * wuerde sonst still nichts tun, und der Spieler bliebe ohne Erklaerung stehen.
     */
    private static void transfer(CommandOutput out, PlayerTransferService transfers,
                                 ServerRegistry servers, UUID uuid, String playerName,
                                 String target, String actorName) {

        Optional<String> rejected = transfers.rejectTarget(target);
        if (rejected.isPresent()) {
            out.error(rejected.get());
            out.info("Verfuegbar: " + String.join(", ", runningServers(servers)));
            return;
        }

        int reached = transfers.transfer(uuid, target, actorName);
        if (reached == 0) {
            out.error("Kein Proxy erreichbar - niemand kann den Wechsel ausfuehren.");
            return;
        }
        // Ob der Spieler online ist, weiss nur der Proxy. Deshalb hier keine
        // Erfolgsmeldung, die mehr behauptet als bekannt ist.
        out.success(playerName + " wird nach " + target + " geschickt.");
    }

    /** Moegliche Ziele - ohne Proxys, dorthin kann niemand geschickt werden. */
    private static List<String> runningServers(ServerRegistry servers) {
        return servers.all().stream()
                .filter(server -> server.state().isActive())
                .filter(server -> server.platform()
                                  != de.kevloe.vibecloud.api.server.ServerPlatformType.VELOCITY)
                .map(CloudServer::name)
                .sorted()
                .toList();
    }

}
