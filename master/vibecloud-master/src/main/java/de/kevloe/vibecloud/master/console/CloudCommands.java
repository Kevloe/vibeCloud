package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.config.MasterConfigFile;
import de.kevloe.vibecloud.master.scheduler.PlacementScheduler;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code cloud setup} und {@code cloud status} (PLAN.md Abschnitt 9, Entscheidung 17.5).
 *
 * <p>Nach {@code rank set <name> admin} fehlen immer noch Gruppen - ohne die startet kein
 * Server. {@code cloud setup} schliesst diese Luecke, damit eine frische Installation in
 * wenigen Befehlen spielbar ist.
 */
public final class CloudCommands implements CommandRegistry.Command {

    private final ServerGroupRepository groups;
    private final ServerRegistry servers;
    private final PlacementScheduler scheduler;
    private final AuditLog audit;
    private final Path templateRoot;
    private final de.kevloe.vibecloud.master.message.MessageDistributor messages;
    private final MasterConfigFile config;

    public CloudCommands(ServerGroupRepository groups, ServerRegistry servers,
                         PlacementScheduler scheduler, AuditLog audit, Path templateRoot,
                         de.kevloe.vibecloud.master.message.MessageDistributor messages,
                         MasterConfigFile config) {
        this.groups = groups;
        this.servers = servers;
        this.scheduler = scheduler;
        this.audit = audit;
        this.templateRoot = templateRoot;
        this.messages = messages;
        this.config = config;
    }

    @Override
    public String name() {
        return "cloud";
    }

    @Override
    public String description() {
        return "Erst-Einrichtung und Gesamtstatus";
    }

    @Override
    public String usage() {
        return "cloud setup | status | messages reload | config [set <feld> <wert>]";
    }

    @Override
    public List<String> subCommands() {
        return List.of("setup", "status", "messages", "config");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        return switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "messages" -> args.size() == 2 ? List.of("reload") : List.of();
            case "config" -> switch (args.size()) {
                case 2 -> List.of("set");
                case 3 -> MasterConfigFile.editableFields();
                default -> List.of();
            };
            default -> List.of();
        };
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "setup" -> setup(out);
            case "status" -> status(out);
            case "messages" -> messages(out, args);
            case "config" -> config(out, args);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    /**
     * Zeigt und aendert die Felder der {@code config.json}, die sich im Betrieb aendern
     * lassen.
     *
     * <p>Der Befehl ist auch der Grund, warum das Dashboard dafuer kein eigenes Recht
     * braucht: Die Einstellungsseite haengt an {@code vibecloud.command.cloud.config},
     * und geschrieben wird hier wie dort ueber {@link MasterConfigFile}.
     */
    private void config(CommandOutput out, List<String> args) {
        if (args.size() == 1) {
            MasterConfig saved;
            try {
                saved = config.read();
            } catch (IOException exception) {
                out.error(exception.getMessage());
                return;
            }
            for (String field : MasterConfigFile.editableFields()) {
                String value = MasterConfigFile.valueOf(saved, field);
                String running = config.runningValue(field);
                out.info(String.format("  %-34s %-8s%s", field, value,
                        value.equals(running) ? "" : "(laeuft noch mit " + running + ")"));
            }
            config.lockedFields().forEach((field, value) -> out.info(
                    String.format("  %-34s %-8s(nur in der config.json)", field, value)));
            return;
        }
        if (args.size() != 4 || !args.get(1).equalsIgnoreCase("set")) {
            out.error("Syntax: cloud config | cloud config set <feld> <wert>");
            return;
        }
        try {
            config.update(Map.of(args.get(2), args.get(3)));
        } catch (IllegalArgumentException | IOException exception) {
            out.error(exception.getMessage());
            return;
        }
        audit.record("CONSOLE", "config.changed", args.get(2), Map.of("value", args.get(3)));
        out.success(args.get(2) + " steht jetzt auf " + args.get(3) + ".");
        out.warn("Wirksam nach einem Neustart des Masters - bis dahin laeuft er mit "
                 + config.runningValue(args.get(2)) + ".");
    }

    private void setup(CommandOutput out) {
        if (!groups.findAll().isEmpty()) {
            out.error("Es gibt schon Gruppen - setup ist nur fuer eine frische Installation.");
            out.info("Einzelne Gruppen anlegen mit: group create <name> <plattform>");
            return;
        }

        ServerGroup proxy = new ServerGroup(
                "proxy", ServerPlatformType.VELOCITY, true,
                1, 1, 500, 512,
                List.of(), List.of(),
                0, 0,
                "%group%-%id%", "proxy", "latest", "velocity",
                false, 0, false, 100);

        ServerGroup lobby = new ServerGroup(
                "lobby", ServerPlatformType.PAPER, false,
                1, 3, 50, 2048,
                List.of(), List.of(),
                80, 300,
                "%group%-%id%", "lobby", "latest", "paper",
                true, 10, false, 50);

        groups.create(proxy);
        groups.create(lobby);
        audit.record("CONSOLE", "cloud.setup", null,
                Map.of("groups", List.of("proxy", "lobby")));

        createTemplateDirectories(out, "proxy", "lobby");

        out.success("Zwei Gruppen angelegt:");
        out.info("  proxy  VELOCITY, statisch, 1 Instanz, 512 MB");
        out.info("  lobby  PAPER, dynamisch, 1-3 Instanzen, 2048 MB, Join-Ziel");
        out.info("");
        out.info("Der Scheduler startet sie von selbst, sobald ein Node verbunden ist.");
        out.info("velocity.toml und server.properties legt der Wrapper selbst an. Eigene "
                 + "Dateien gehoeren nach templates/proxy/ und templates/lobby/.");
    }

    /** Legt die Template-Verzeichnisse an, damit klar ist, wohin die Dateien gehoeren. */
    private void createTemplateDirectories(CommandOutput out, String... templates) {
        for (String template : templates) {
            Path directory = templateRoot.resolve(template);
            try {
                Files.createDirectories(directory);
            } catch (IOException exception) {
                out.warn("Verzeichnis " + directory + " konnte nicht angelegt werden: "
                         + exception.getMessage());
            }
        }
    }

    /**
     * Liest die Sprachdateien neu und verteilt sie an alle Plugins.
     *
     * <p>Damit wirkt eine Textkorrektur sofort - ohne Neustart eines Servers
     * (PLAN.md Abschnitt 11a).
     */
    private void messages(CommandOutput out, List<String> args) {
        if (args.size() < 2 || !args.get(1).equalsIgnoreCase("reload")) {
            out.error("Syntax: cloud messages reload");
            return;
        }
        var result = messages.reload();
        if (!result.reloaded()) {
            out.error("Neuladen fehlgeschlagen - die alten Texte bleiben aktiv. "
                      + "Details stehen im Log.");
            return;
        }
        audit.record("CONSOLE", "messages.reloaded", null,
                java.util.Map.of("plugins", result.plugins()));
        out.success("Sprachdateien neu geladen ("
                    + messages.messages().bundle().requiredKeys().size()
                    + " Schluessel) und an " + result.plugins() + " Server verteilt.");
    }

    private void status(CommandOutput out) {
        List<ServerGroup> allGroups = groups.findAll();
        List<CloudServer> allServers = servers.all();

        out.info("Gruppen          " + allGroups.size());
        out.info("Server aktiv     " + allServers.stream().filter(CloudServer::isActive).count()
                 + " von " + allServers.size() + " bekannt");
        out.info("Spieler          " + allServers.stream().mapToInt(CloudServer::players).sum());
        out.info("Scheduler        " + (scheduler.isArmed()
                ? "scharf"
                : "noch nicht scharf (wartet auf den Zustandsabgleich eines Wrappers)"));

        if (allGroups.isEmpty()) {
            out.warn("");
            out.warn("Noch keine Gruppe angelegt. Schnellster Weg: cloud setup");
        }
    }
}
