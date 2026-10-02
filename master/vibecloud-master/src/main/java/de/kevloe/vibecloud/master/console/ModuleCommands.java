package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.master.module.ModuleManager;
import de.kevloe.vibecloud.module.ModuleDescriptor;

import java.util.List;
import java.util.Locale;

/** {@code module list|load|unload|reload|info} (PLAN.md Abschnitt 10 und 12). */
public final class ModuleCommands implements CommandRegistry.Command {

    private final ModuleManager modules;
    private final Runnable onChange;

    /**
      * @param onChange wird nach load, unload und reload aufgerufen - ein Modul bringt
      *                 eigene Befehle mit, und die Proxys muessen die neue Liste kennen
      */
    public ModuleCommands(ModuleManager modules, Runnable onChange) {
        this.modules = modules;
        this.onChange = onChange;
    }

    @Override
    public String name() {
        return "module";
    }

    @Override
    public String description() {
        return "Module laden, entladen und ansehen";
    }

    @Override
    public String usage() {
        return "module list | info <id> | load <id> | unload <id> | reload <id>";
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return List.of("list", "info", "load", "unload", "reload");
        }
        return modules.list().stream()
                .map(info -> info.descriptor().id())
                .toList();
    }

    @Override
    public List<String> subCommands() {
        return List.of("list", "info", "load", "unload", "reload");
    }

    /** Nicht im Spiel: Code zur Laufzeit zu laden gehoert an die Konsole. */
    @Override
    public boolean availableInGame() {
        return false;
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
            case "load" -> {
                load(out, args);
                onChange.run();
            }
            case "unload" -> {
                unload(out, args);
                onChange.run();
            }
            case "reload" -> {
                reload(out, args);
                onChange.run();
            }
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private void list(CommandOutput out) {
        List<ModuleManager.ModuleInfo> all = modules.list();
        if (all.isEmpty()) {
            out.warn("Keine Module vorhanden. Jars nach modules/ legen und 'module load <id>'.");
            return;
        }
        out.info(String.format("%-16s %-10s %-9s %-18s %s",
                "ID", "VERSION", "ZUSTAND", "BRAUCHT", "DATEI"));
        for (ModuleManager.ModuleInfo info : all) {
            ModuleDescriptor descriptor = info.descriptor();
            out.info(String.format("%-16s %-10s %-9s %-18s %s",
                    descriptor.id(),
                    descriptor.version(),
                    info.enabled() ? "aktiv" : "inaktiv",
                    descriptor.depends().isEmpty() ? "-"
                            : String.join(",", descriptor.depends()),
                    info.fileName()));
        }
    }

    private void info(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: module info <id>");
            return;
        }
        modules.list().stream()
                .filter(entry -> entry.descriptor().id().equals(args.get(1)))
                .findFirst()
                .ifPresentOrElse(entry -> {
                    ModuleDescriptor descriptor = entry.descriptor();
                    out.info("Modul " + descriptor.id());
                    out.info("  Name          " + descriptor.name());
                    out.info("  Version       " + descriptor.version());
                    out.info("  Zustand       " + (entry.enabled() ? "aktiv" : "inaktiv"));
                    out.info("  Hauptklasse   " + descriptor.main());
                    out.info("  Modul-API     " + descriptor.apiVersion());
                    out.info("  Braucht       " + (descriptor.depends().isEmpty()
                            ? "-" : String.join(", ", descriptor.depends())));
                    out.info("  Optional      " + (descriptor.softDepends().isEmpty()
                            ? "-" : String.join(", ", descriptor.softDepends())));
                    out.info("  Exportiert    " + (descriptor.exports().isEmpty()
                            ? "- (andere Module sehen nichts davon)"
                            : String.join(", ", descriptor.exports())));
                    out.info("  Bundles       " + (descriptor.bundles().isEmpty()
                            ? "-" : descriptor.bundles().keySet()));
                    out.info("  Datei         " + entry.fileName());
                }, () -> out.error("Modul " + args.get(1) + " ist nicht vorhanden."));
    }

    private void load(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: module load <id>");
            return;
        }
        if (modules.load(args.get(1))) {
            out.success("Modul " + args.get(1) + " aktiv.");
        } else {
            out.error("Modul " + args.get(1) + " konnte nicht aktiviert werden - "
                      + "Details stehen im Log.");
        }
    }

    private void unload(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: module unload <id>");
            return;
        }
        if (modules.unload(args.get(1))) {
            out.success("Modul " + args.get(1) + " entladen.");
            out.info("Abhaengige Module wurden mit entladen - siehe Log.");
        } else {
            out.error("Modul " + args.get(1) + " ist nicht aktiv.");
        }
    }

    private void reload(CommandOutput out, List<String> args) {
        if (args.size() < 2) {
            out.error("Syntax: module reload <id>");
            return;
        }
        if (modules.reload(args.get(1))) {
            out.success("Modul " + args.get(1) + " neu geladen.");
        } else {
            out.error("Neuladen von " + args.get(1) + " fehlgeschlagen - Details im Log.");
        }
    }
}
