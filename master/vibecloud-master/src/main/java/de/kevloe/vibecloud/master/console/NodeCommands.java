package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.node.NodeInfo;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.node.NodeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Befehle rund um die Wrapper: {@code node list|add|remove|token|enable|disable}. */
public final class NodeCommands implements CommandRegistry.Command {

    private final NodeRepository repository;
    private final NodeRegistry registry;
    private final AuditLog audit;
    private final String masterFingerprint;
    private final int grpcPort;

    public NodeCommands(NodeRepository repository, NodeRegistry registry, AuditLog audit,
                        String masterFingerprint, int grpcPort) {
        this.repository = repository;
        this.registry = registry;
        this.audit = audit;
        this.masterFingerprint = masterFingerprint;
        this.grpcPort = grpcPort;
    }

    @Override
    public String name() {
        return "node";
    }

    @Override
    public String description() {
        return "Wrapper verwalten";
    }

    @Override
    public String usage() {
        return "node list | add <name> [maxMemoryMb] [ip,ip] | remove <name> "
               + "| token <name> | enable <name> | disable <name>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("list", "add", "remove", "token", "enable", "disable");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);

        // "add" legt einen neuen Node an - der Name ist frei, danach kommt der Speicher.
        if (sub.equals("add")) {
            return args.size() == 3 ? List.of("4096", "8192", "16384", "32768") : List.of();
        }
        if (args.size() == 2 && List.of("remove", "token", "enable", "disable").contains(sub)) {
            return names();
        }
        return List.of();
    }

    private List<String> names() {
        return repository.findAll().stream()
                .map(NodeRepository.NodeRecord::name).sorted().toList();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "list" -> list(out);
            case "add" -> add(out, args);
            case "remove" -> remove(out, args);
            case "token" -> rotate(out, args);
            case "enable" -> setEnabled(out, args, true);
            case "disable" -> setEnabled(out, args, false);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private void list(CommandOutput out) {
        List<NodeInfo> nodes = registry.listAll();
        if (nodes.isEmpty()) {
            out.warn("Noch kein Node angelegt. Anlegen mit: node add <name> [maxMemoryMb]");
            return;
        }
        out.info(String.format("%-16s %-10s %-10s %-7s %-7s %s",
                "NAME", "STATUS", "RAM (MB)", "KERNE", "CPU", "LETZTER HEARTBEAT"));
        for (NodeInfo node : nodes) {
            String status = !node.enabled() ? "gesperrt" : node.connected() ? "online" : "offline";
            String cpu = node.connected()
                    ? String.format(Locale.ROOT, "%.0f%%", node.cpuLoad() * 100)
                    : "-";
            out.info(String.format("%-16s %-10s %-10d %-7d %-7s %s",
                    node.name(), status, node.maxMemoryMb(), node.cpuCores(), cpu,
                    formatAge(node.lastSeen())));
        }
    }

    private void add(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: node add <name> [maxMemoryMb] [erlaubte IPs, mit Komma getrennt]");
            return;
        }
        String name = args.get(1);
        if (repository.find(name).isPresent()) {
            out.error("Node " + name + " existiert bereits. Neues Token: node token " + name);
            return;
        }
        long memory = args.size() > 2 ? parseLong(out, args.get(2)) : 8192L;
        if (memory < 0) {
            return;
        }
        List<String> allowedIps = args.size() > 3 ? List.of(args.get(3).split(",")) : List.of();

        String token = repository.create(name, memory, allowedIps);
        audit.record("CONSOLE", "node.created", name,
                Map.of("maxMemoryMb", memory, "allowedIps", allowedIps));

        out.success("Node " + name + " angelegt.");
        if (allowedIps.isEmpty()) {
            out.warn("Ohne allowed_ips darf sich dieser Node von jeder Adresse anmelden. "
                     + "Fuer den Produktivbetrieb den Node mit IP-Liste neu anlegen.");
        }
        out.info("");
        out.info("Auf dem Root neben der CloudWrapper.jar ausfuehren:");
        out.info("  java -jar CloudWrapper.jar join <adresse-des-masters>:%d %s %s %s"
                .formatted(grpcPort, name, token, masterFingerprint));
        out.info("");
        out.info("Oder von Hand in die wrapper.json eintragen:");
        out.info("""
                {
                  "masterHost": "<adresse-des-masters>",
                  "masterPort": %d,
                  "masterFingerprint": "%s",
                  "node": "%s",
                  "token": "%s"
                }""".formatted(grpcPort, masterFingerprint, name, token));
        out.info("");
        out.warn("Das Token wird nur jetzt angezeigt und ist danach nicht mehr abrufbar.");
    }

    private void remove(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: node remove <name>");
            return;
        }
        String name = args.get(1);
        if (repository.delete(name)) {
            registry.detach(name, "Node entfernt");
            audit.record("CONSOLE", "node.removed", name);
            out.success("Node " + name + " entfernt.");
        } else {
            out.error("Node " + name + " existiert nicht.");
        }
    }

    private void rotate(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: node token <name>");
            return;
        }
        String name = args.get(1);
        try {
            String token = repository.rotateToken(name);
            audit.record("CONSOLE", "node.token_rotated", name);
            out.success("Neues Token fuer " + name + ":");
            out.info(token);
            out.warn("Das alte Token gilt nicht mehr. Der Wrapper verbindet sich erst wieder, "
                     + "wenn die wrapper.json angepasst und der Dienst neu gestartet ist.");
        } catch (IllegalArgumentException exception) {
            out.error(exception.getMessage());
        }
    }

    private void setEnabled(CommandOutput out, List<String> args, boolean enabled) {
        if (args.size() < 2) {
            out.error("Syntax: node " + (enabled ? "enable" : "disable") + " <name>");
            return;
        }
        String name = args.get(1);
        if (repository.find(name).isEmpty()) {
            out.error("Node " + name + " existiert nicht.");
            return;
        }
        repository.setEnabled(name, enabled);
        audit.record("CONSOLE", enabled ? "node.enabled" : "node.disabled", name);
        if (!enabled) {
            registry.detach(name, "Node gesperrt");
        }
        out.success("Node " + name + (enabled ? " freigegeben." : " gesperrt."));
    }

    private long parseLong(CommandOutput out, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            out.error(value + " ist keine Zahl.");
            return -1;
        }
    }

    private static String formatAge(Instant instant) {
        if (instant == null) {
            return "nie";
        }
        Duration age = Duration.between(instant, Instant.now());
        if (age.toSeconds() < 60) {
            return "vor " + age.toSeconds() + " s";
        }
        if (age.toMinutes() < 60) {
            return "vor " + age.toMinutes() + " min";
        }
        if (age.toHours() < 48) {
            return "vor " + age.toHours() + " h";
        }
        return "vor " + age.toDays() + " Tagen";
    }
}
