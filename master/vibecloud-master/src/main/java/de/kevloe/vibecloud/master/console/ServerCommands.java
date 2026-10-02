package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.server.ServerService;
import de.kevloe.vibecloud.master.server.StaticBindingRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Befehle rund um Server: {@code server list|start|stop|kill|info|cmd}. */
public final class ServerCommands implements CommandRegistry.Command {

    private final ServerService service;
    private final ServerRegistry registry;
    private final ServerGroupRepository groups;
    private final StaticBindingRepository bindings;

    public ServerCommands(ServerService service, ServerRegistry registry,
                          ServerGroupRepository groups, StaticBindingRepository bindings) {
        this.service = service;
        this.registry = registry;
        this.groups = groups;
        this.bindings = bindings;
    }

    @Override
    public String name() {
        return "server";
    }

    @Override
    public String description() {
        return "Server starten, stoppen und ansehen";
    }

    @Override
    public String usage() {
        return "server list [gruppe] | start <gruppe> | stop <server> | kill <server> "
               + "| info <server> | cmd <server> <befehl...>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("list", "start", "stop", "kill", "info", "cmd");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);

        // "list [gruppe]" und "start <gruppe>" wollen Gruppen, nicht Server - das war
        // vorher vertauscht und hat beim Tippen in die Irre gefuehrt.
        if (List.of("list", "start").contains(sub)) {
            return args.size() == 2 ? groupNames() : List.of();
        }
        if (List.of("stop", "kill", "info").contains(sub)) {
            return args.size() == 2 ? serverNames() : List.of();
        }
        // "cmd <server> <befehl...>": nur der Server, danach tippt der Betreiber frei.
        if (sub.equals("cmd")) {
            return args.size() == 2 ? serverNames() : List.of();
        }
        return List.of();
    }

    private List<String> groupNames() {
        return groups.findAll().stream().map(ServerGroup::name).sorted().toList();
    }

    private List<String> serverNames() {
        return registry.all().stream().map(CloudServer::name).sorted().toList();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "list" -> list(out, args);
            case "start" -> start(out, args);
            case "stop" -> stop(out, args);
            case "kill" -> kill(out, args);
            case "info" -> info(out, args);
            case "cmd" -> command(out, args);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private void list(CommandOutput out, List<String> args) {
        List<CloudServer> all = args.size() > 1
                ? registry.ofGroup(args.get(1))
                : registry.all();

        if (all.isEmpty()) {
            out.warn(args.size() > 1
                    ? "Kein Server der Gruppe " + args.get(1) + " laeuft."
                    : "Es laeuft kein Server. Starten mit: server start <gruppe>");
            return;
        }
        out.info(String.format("%-20s %-12s %-16s %-10s %-7s %s",
                "NAME", "GRUPPE", "ZUSTAND", "NODE", "PORT", "LAUFZEIT"));
        for (CloudServer server : all) {
            out.info(String.format("%-20s %-12s %-16s %-10s %-7d %s",
                    server.name(), server.groupName(), server.state(),
                    server.node(), server.port(), uptime(server)));
        }

        // Statische Server, die auf ihren Node warten, sind hier das Wichtigste -
        // sonst fragt man sich, warum nichts startet.
        List<CloudServer> waiting = all.stream()
                .filter(server -> server.state() == ServerState.WAITING_FOR_NODE)
                .toList();
        if (!waiting.isEmpty()) {
            out.warn("");
            waiting.forEach(server -> out.warn(
                    server.name() + " wartet auf Node " + server.node()
                    + " - der Server wird NICHT woanders gestartet, weil seine Welt dort liegt."));
        }
    }

    private void start(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: server start <gruppe>");
            return;
        }
        ServerService.StartResult result = service.start(args.get(1), "CONSOLE");
        if (result.started()) {
            out.success("%s wird auf Node %s gestartet (Port %d)."
                    .formatted(result.serverName(), result.node(), result.port()));
            out.info("Fortschritt mit: screen " + result.serverName());
        } else {
            out.error("Start abgelehnt: " + result.reason());
        }
    }

    private void stop(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: server stop <server>");
            return;
        }
        if (service.stop(args.get(1), "CONSOLE", "ueber die Konsole gestoppt")) {
            out.success(args.get(1) + " wird gestoppt.");
        } else {
            out.error(args.get(1) + " ist nicht bekannt oder sein Node ist nicht erreichbar.");
        }
    }

    private void kill(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: server kill <server>");
            return;
        }
        if (service.kill(args.get(1), "CONSOLE")) {
            out.warn(args.get(1) + " wird hart beendet - Welt und Spielerdaten werden "
                     + "nicht gespeichert.");
        } else {
            out.error(args.get(1) + " ist nicht bekannt.");
        }
    }

    private void info(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: server info <server>");
            return;
        }
        registry.find(args.get(1)).ifPresentOrElse(server -> {
            out.info("Server " + server.name());
            out.info("  Gruppe     " + server.groupName() + " (" + server.platform() + ")");
            out.info("  Zustand    " + server.state());
            out.info("  Node       " + server.node());
            out.info("  Port       " + server.port());
            out.info("  Art        " + (server.staticServer() ? "statisch" : "dynamisch"));
            out.info("  Laufzeit   " + uptime(server));
            out.info("  Spieler    " + server.players() + "/" + server.maxPlayers());

            if (server.staticServer()) {
                bindings.find(server.name()).ifPresent(binding -> out.info(
                        "  Bindung    dauerhaft an Node " + binding.node()
                        + " (Welt liegt dort)"));
            }
        }, () -> out.error(args.get(1) + " ist nicht bekannt."));
    }

    private void command(CommandOutput out, List<String> args) {
        if (args.size() < 3) {
            out.error("Syntax: server cmd <server> <befehl...>");
            return;
        }
        String server = args.get(1);
        String line = String.join(" ", args.subList(2, args.size()));
        if (service.execute(server, line, "CONSOLE")) {
            out.success("An " + server + " gesendet: " + line);
        } else {
            out.error(server + " ist nicht bekannt oder sein Node ist nicht erreichbar.");
        }
    }

    private static String uptime(CloudServer server) {
        if (server.startedAt() == null) {
            return "-";
        }
        Duration duration = Duration.between(server.startedAt(), Instant.now());
        if (duration.toMinutes() < 1) {
            return duration.toSeconds() + " s";
        }
        if (duration.toHours() < 1) {
            return duration.toMinutes() + " min";
        }
        return duration.toHours() + " h " + (duration.toMinutes() % 60) + " min";
    }
}
