package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.template.JarStore;
import de.kevloe.vibecloud.master.template.VersionCatalog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Befehle rund um Servergruppen: {@code group list|versions|create|edit|info|delete}. */
public final class GroupCommands implements CommandRegistry.Command {

    private final ServerGroupRepository groups;
    private final ServerRegistry servers;
    private final AuditLog audit;
    private final VersionCatalog versions;
    private final JarStore jars;
    private final NodeRegistry nodes;

    public GroupCommands(ServerGroupRepository groups, ServerRegistry servers, AuditLog audit,
                         VersionCatalog versions, JarStore jars, NodeRegistry nodes) {
        this.groups = groups;
        this.servers = servers;
        this.audit = audit;
        this.versions = versions;
        this.jars = jars;
        this.nodes = nodes;
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
        return "group list | versions [PAPER|VELOCITY] "
               + "| create <name> <PAPER|VELOCITY|MINESTOM> [version] [template] "
               + "| edit <name> <feld> <wert> | info <name> | delete <name>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("list", "versions", "create", "edit", "info", "delete");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        String sub = args.getFirst().toLowerCase(Locale.ROOT);

        if (sub.equals("create")) {
            // create <name> <plattform> [version] [template] - der Name ist frei.
            return switch (args.size()) {
                case 3 -> List.of("PAPER", "VELOCITY", "MINESTOM");
                case 4 -> platformOf(args.get(2)).map(this::versionSuggestions).orElse(List.of());
                default -> List.of();
            };
        }
        if (sub.equals("versions")) {
            return args.size() == 2 ? List.of("PAPER", "VELOCITY") : List.of();
        }
        if (sub.equals("edit")) {
            return switch (args.size()) {
                case 2 -> names();
                case 3 -> ServerGroupRepository.editableFields();
                case 4 -> isVersionField(args.get(2))
                        ? groups.find(args.get(1)).map(group ->
                                versionSuggestions(group.platform())).orElse(List.of())
                        : ServerGroupRepository.valuesFor(args.get(2));
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

    /**
     * Nur aus dem Zwischenspeicher: Ein Tab darf nicht auf die PaperMC-API warten. Beim
     * allerersten Tab ist die Liste deshalb leer und beim zweiten da.
     */
    private List<String> versionSuggestions(ServerPlatformType platform) {
        return Stream.concat(Stream.of("latest"),
                versions.cachedVersions(platform).stream().map(VersionCatalog.Version::id))
                .toList();
    }

    private static boolean isVersionField(String field) {
        return field.equalsIgnoreCase("mc_version") || field.equalsIgnoreCase("mcversion");
    }

    private static Optional<ServerPlatformType> platformOf(String text) {
        try {
            return Optional.of(ServerPlatformType.valueOf(text.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "list" -> list(out);
            case "versions" -> versions(out, args);
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
            out.error("Syntax: group create <name> <PAPER|VELOCITY|MINESTOM> [version] [template]");
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
        String template = args.size() > 4 ? args.get(4) : name;

        ServerGroup group = ServerGroup.defaults(name, platform, template);
        if (args.size() > 3) {
            group = group.withMcVersion(args.get(3));
        }

        try {
            groups.create(group);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            out.error(exception.getMessage());
            if (args.size() == 4 && !args.get(3).contains(".") && !args.get(3).equals("latest")) {
                // Bis zu dieser Version stand an dieser Stelle das Template.
                out.info("Die Version steht vor dem Template: group create " + name + " "
                         + platform + " <version> " + args.get(3));
            }
            return;
        }
        audit.record("CONSOLE", "group.created", name,
                Map.of("platform", platform.name(), "template", template,
                        "version", group.mcVersion()));

        out.success("Gruppe " + name + " angelegt (" + platform + ", "
                    + (group.staticGroup() ? "statisch" : "dynamisch") + ", Version "
                    + group.mcVersion() + ").");
        out.info("Template-Verzeichnis: templates/" + template + "/");
        describeRequirements(out, group);
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
                if (isVersionField(field) || field.toLowerCase(Locale.ROOT).contains("jar")) {
                    groups.find(name).ifPresent(group -> describeRequirements(out, group));
                }
            } else {
                out.error("Gruppe " + name + " existiert nicht.");
            }
        } catch (IllegalArgumentException exception) {
            out.error(exception.getMessage());
        }
    }

    /**
     * {@code group versions [PAPER|VELOCITY]} - was sich als {@code mc_version} waehlen laesst.
     *
     * <p>Mit der Java, die ein Server der Version braucht, und welche Nodes sie haben: Ohne
     * das merkt man erst beim Start, dass auf keinem Root eine Java 17 liegt.
     */
    private void versions(CommandOutput out, List<String> args) {
        ServerPlatformType platform = args.size() > 1
                ? platformOf(args.get(1)).orElse(null)
                : ServerPlatformType.PAPER;
        if (platform == null || platform == ServerPlatformType.MINESTOM) {
            out.error("Syntax: group versions [PAPER|VELOCITY] - Minestom bringt sein Jar "
                      + "im Template mit");
            return;
        }
        List<VersionCatalog.Version> available = versions.versions(platform);
        if (available.isEmpty()) {
            out.error("Die Versionsliste ist nicht abrufbar (fill.papermc.io). Eine Version "
                      + "laesst sich trotzdem eintragen - geprueft wird sie dann beim Download.");
            return;
        }
        String jarSource = platform == ServerPlatformType.PAPER ? "paper" : "velocity";
        out.info(String.format("%-10s %-6s %-8s %-11s %s", "VERSION", "JAVA", "PLUGIN", "JAR", ""));
        for (VersionCatalog.Version version : available) {
            out.info(String.format("%-10s %-6s %-8s %-11s %s",
                    version.id(),
                    version.java() + "+",
                    version.legacy() ? "legacy" : "aktuell",
                    jars.isDownloaded(jarSource, version.id()) ? "geladen" : "-",
                    version.supported() ? "" : "(von PaperMC nicht mehr gepflegt)"));
        }
        out.info("");
        out.info("Java auf den Nodes: " + javaOnNodes());
        out.info("Waehlen mit: group create <name> " + platform + " <version>  oder  "
                 + "group edit <name> mc_version <version>");
    }

    /**
     * Was eine Gruppe braucht, und ob ein Node es hat. Nach dem Anlegen und nach einem
     * Versionswechsel - der Fehler soll jetzt auffallen, nicht beim ersten Start.
     */
    private void describeRequirements(CommandOutput out, ServerGroup group) {
        requirementsOf(group).ifPresent(out::info);
        int java = requiredJava(group);
        if (java > 0 && nodes.listAll().stream()
                .filter(node -> node.connected())
                .noneMatch(node -> nodes.hasJava(node.name(), java))) {
            out.warn("Kein verbundener Node hat Java " + java + " oder neuer. Auf einem Root "
                     + "installieren und in dessen wrapper.json unter javaRuntimes eintragen.");
        }
        if (group.jarSource().equals("paper") || group.jarSource().equals("velocity")) {
            out.info("Das Jar wird im Hintergrund geladen, falls es noch fehlt.");
        }
    }

    /** "Braucht Java 17+, Legacy-Plugin, Jar geladen" - leer, wenn nichts bekannt ist. */
    private Optional<String> requirementsOf(ServerGroup group) {
        if (!group.jarSource().equals("paper") && !group.jarSource().equals("velocity")) {
            return Optional.empty();
        }
        if (group.mcVersion().equals("latest")) {
            return Optional.of("Braucht Java " + VersionCatalog.MODERN_PLUGIN_JAVA
                               + "+ (latest = neueste stabile Version)");
        }
        int java = requiredJava(group);
        boolean legacy = VersionCatalog.isLegacy(group.platform(), group.mcVersion());
        return Optional.of("Braucht Java " + java + "+"
                           + (legacy ? ", bekommt das Legacy-Plugin" : "")
                           + ", Jar " + (jars.isDownloaded(group.jarSource(), group.mcVersion())
                                         ? "geladen" : "noch nicht geladen"));
    }

    private int requiredJava(ServerGroup group) {
        if (group.mcVersion().equals("latest")) {
            return VersionCatalog.MODERN_PLUGIN_JAVA;
        }
        int javaMinimum = versions.cachedVersions(group.platform()).stream()
                .filter(version -> version.id().equals(group.mcVersion()))
                .mapToInt(VersionCatalog.Version::javaMinimum)
                .findFirst().orElse(0);
        return VersionCatalog.requiredJava(group.platform(), group.mcVersion(), javaMinimum);
    }

    private String javaOnNodes() {
        List<String> parts = nodes.listAll().stream()
                .filter(node -> node.connected())
                .map(node -> node.name() + " " + nodes.javaVersionsOf(node.name()))
                .toList();
        return parts.isEmpty() ? "(kein Node verbunden)" : String.join(", ", parts);
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
            requirementsOf(group).ifPresent(line -> out.info("               " + line));
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
