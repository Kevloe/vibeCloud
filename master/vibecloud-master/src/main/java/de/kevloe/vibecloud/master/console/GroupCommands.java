package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Befehle rund um Servergruppen: {@code group list|create|edit|info|delete}. */
public final class GroupCommands implements CommandRegistry.Command {

    private final ServerGroupRepository groups;
    private final ServerRegistry servers;
    private final AuditLog audit;

    public GroupCommands(ServerGroupRepository groups, ServerRegistry servers, AuditLog audit) {
        this.groups = groups;
        this.servers = servers;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "group";
    }

    @Override
    public String description() {
        return "Servergruppen verwalten";
    }

    @Override
    public String usage() {
        return "group list | create <name> <PAPER|VELOCITY|MINESTOM> [template] "
               + "| edit <name> <feld> <wert> | info <name> | delete <name>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("list", "create", "edit", "info", "delete");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);

        if (sub.equals("create")) {
            // create <name> <plattform> [template] - der Name ist frei.
            return args.size() == 3 ? List.of("PAPER", "VELOCITY", "MINESTOM") : List.of();
        }
        if (sub.equals("edit")) {
            return switch (args.size()) {
                case 2 -> names();
                case 3 -> ServerGroupRepository.editableFields();
                case 4 -> ServerGroupRepository.valuesFor(args.get(2));
                default -> List.of();
            };
        }
        if (args.size() == 2 && List.of("info", "delete").contains(sub)) {
            return names();
        }
        return List.of();
    }

    private List<String> names() {
        return groups.findAll().stream().map(ServerGroup::name).sorted().toList();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "list" -> list(out);
            case "create" -> create(out, args);
            case "edit" -> edit(out, args);
            case "info" -> info(out, args);
            case "delete" -> delete(out, args);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private void list(CommandOutput out) {
        List<ServerGroup> all = groups.findAll();
        if (all.isEmpty()) {
            out.warn("Noch keine Gruppe angelegt. Schnellster Weg: cloud setup");
            return;
        }
        out.info(String.format("%-14s %-9s %-7s %-9s %-7s %-7s %s",
                "NAME", "PLATTFORM", "ART", "ONLINE", "RAM", "PORTS", "TEMPLATE"));
        for (ServerGroup group : all) {
            long active = servers.activeOfGroup(group.name()).size();
            out.info(String.format("%-14s %-9s %-7s %-9s %-7s %-7s %s",
                    group.name(),
                    group.platform(),
                    group.staticGroup() ? "static" : "dyn",
                    active + "/" + group.minOnline() + "-" + group.maxOnline(),
                    group.memoryMb() + "M",
                    group.maxPlayers() + "p",
                    group.template()));
        }
    }

    private void create(CommandOutput out, List<String> args) {
        if (args.size() < 3) {
            out.error("Syntax: group create <name> <PAPER|VELOCITY|MINESTOM> [template]");
            return;
        }
        String name = args.get(1);
        if (groups.find(name).isPresent()) {
            out.error("Gruppe " + name + " existiert bereits.");
            return;
        }
        ServerPlatformType platform;
        try {
            platform = ServerPlatformType.valueOf(args.get(2).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            out.error("Unbekannte Plattform. Moeglich: "
                      + Arrays.toString(ServerPlatformType.values()));
            return;
        }
        String template = args.size() > 3 ? args.get(3) : name;

        ServerGroup group = ServerGroup.defaults(name, platform, template);

        try {
            groups.create(group);
        } catch (IllegalStateException exception) {
            out.error(exception.getMessage());
            return;
        }
        audit.record("CONSOLE", "group.created", name,
                Map.of("platform", platform.name(), "template", template));

        out.success("Gruppe " + name + " angelegt (" + platform + ", "
                    + (group.staticGroup() ? "statisch" : "dynamisch") + ").");
        out.info("Template-Verzeichnis: templates/" + template + "/");
        out.info("Werte anpassen mit: group edit " + name + " <feld> <wert>");
        out.info("Starten mit: server start " + name);
    }

    private void edit(CommandOutput out, List<String> args) {
        if (args.size() < 4) {
            out.error("Syntax: group edit <name> <feld> <wert>");
            out.info("Felder: minOnline maxOnline maxPlayers memory startPercent idleTimeout "
                     + "template mcVersion jarSource namePattern fallback joinPriority "
                     + "maintenance priority");
            return;
        }
        String name = args.get(1);
        if (groups.find(name).isEmpty()) {
            out.error("Gruppe " + name + " existiert nicht.");
            return;
        }
        String field = args.get(2);
        String value = String.join(" ", args.subList(3, args.size()));

        // Namensmuster vorab pruefen: Ein ungueltiges Muster wuerde erst beim Start
        // auffallen, und dann bei jedem Startversuch erneut.
        if (field.toLowerCase(Locale.ROOT).contains("pattern")) {
            String problem = ServerGroup.validateNamePattern(value);
            if (problem != null) {
                out.error("Muster abgelehnt: " + problem);
                return;
            }
        }

        try {
            if (groups.updateField(name, field, value)) {
                audit.record("CONSOLE", "group.edited", name,
                        Map.of("field", field, "value", value));
                out.success(name + ": " + field + " = " + value);
                out.info("Wirkt erst auf neu gestartete Server - laufende bleiben unberuehrt.");
            } else {
                out.error("Gruppe " + name + " existiert nicht.");
            }
        } catch (IllegalArgumentException exception) {
            out.error(exception.getMessage());
        }
    }

    private void info(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: group info <name>");
            return;
        }
        groups.find(args.get(1)).ifPresentOrElse(group -> {
            out.info("Gruppe " + group.name());
            out.info("  Plattform    " + group.platform()
                     + (group.staticGroup() ? " (statisch)" : " (dynamisch)"));
            out.info("  Online       min " + group.minOnline() + ", max " + group.maxOnline()
                     + ", aktuell " + servers.activeOfGroup(group.name()).size());
            out.info("  Spieler      max " + group.maxPlayers()
                     + (group.startPercent() > 0
                        ? ", nachstarten ab " + group.startPercent() + " %" : ""));
            out.info("  Speicher     " + group.memoryMb() + " MB");
            out.info("  JVM-Flags    " + (group.jvmFlags().isEmpty()
                    ? "(keine)" : String.join(" ", group.jvmFlags())));
            out.info("  Nodes        " + (group.allowedNodes().isEmpty()
                    ? "alle erlaubt" : String.join(", ", group.allowedNodes())));
            out.info("  Namensmuster " + group.namePattern());
            out.info("  Template     templates/" + group.template() + "/");
            out.info("  Version      " + group.mcVersion() + " (" + group.jarSource() + ")");
            out.info("  Join-Ziel    " + (group.fallback()
                    ? "ja, Prioritaet " + group.joinPriority() : "nein"));
            if (group.maintenance()) {
                out.warn("  Wartung      aktiv - es wird nichts Neues gestartet");
            }
        }, () -> out.error("Gruppe " + args.get(1) + " existiert nicht."));
    }

    private void delete(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: group delete <name>");
            return;
        }
        String name = args.get(1);
        long running = servers.activeOfGroup(name).size();
        if (running > 0) {
            out.error("Es laufen noch " + running + " Server dieser Gruppe. "
                      + "Erst stoppen, dann loeschen.");
            return;
        }
        if (groups.delete(name)) {
            audit.record("CONSOLE", "group.deleted", name);
            out.success("Gruppe " + name + " geloescht.");
            out.info("Das Template-Verzeichnis bleibt bestehen und wurde nicht angetastet.");
        } else {
            out.error("Gruppe " + name + " existiert nicht.");
        }
    }
}
