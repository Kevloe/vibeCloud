package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.settings.MaintenanceSwitch;

import java.util.List;
import java.util.Locale;

/**
 * {@code maintenance ...} (PLAN.md Abschnitt 11, Entscheidung 17.8).
 *
 * <p>Zwei Ebenen: global und pro Gruppe. Wer {@code vibecloud.maintenance.bypass} hat,
 * kommt trotzdem rein - das ist der eigentliche Zweck der Gruppen-Wartung: ein Minigame
 * testen, waehrend der Rest des Netzwerks normal laeuft.
 */
public final class MaintenanceCommands implements CommandRegistry.Command {

    private final MaintenanceSwitch maintenance;
    private final ServerGroupRepository groups;

    public MaintenanceCommands(MaintenanceSwitch maintenance, ServerGroupRepository groups) {
        this.maintenance = maintenance;
        this.groups = groups;
    }

    @Override
    public String name() {
        return "maintenance";
    }

    @Override
    public String description() {
        return "Wartungsmodus global oder je Gruppe";
    }

    @Override
    public String usage() {
        return "maintenance on|off [gruppe] | maintenance list";
    }

    @Override
    public List<String> subCommands() {
        return List.of("on", "off", "list");
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        // "maintenance on" ohne Gruppe gilt global, mit Gruppe nur fuer diese.
        if (args.size() == 2 && List.of("on", "off").contains(
                args.getFirst().toLowerCase(Locale.ROOT))) {
            return groups.findAll().stream()
                    .map(de.kevloe.vibecloud.api.server.ServerGroup::name).sorted().toList();
        }
        return List.of();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "on" -> set(out, args, true);
            case "off" -> set(out, args, false);
            case "list" -> list(out);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private void set(CommandOutput out, List<String> args, boolean active) {
        if (args.size() < 2) {
            // Speichern, protokollieren und den Proxys mitteilen - dieselbe Stelle wie
            // der Schalter im Dashboard.
            maintenance.set(active, "CONSOLE");
            if (active) {
                out.warn("Wartungsmodus fuer das ganze Netzwerk aktiv. Neue Logins werden "
                         + "abgelehnt; wer vibecloud.maintenance.bypass hat, kommt rein.");
                out.info("Spieler, die bereits online sind, bleiben online.");
            } else {
                out.success("Wartungsmodus aufgehoben.");
            }
            return;
        }

        String groupName = args.get(1);
        if (maintenance.setGroup(groupName, active, "CONSOLE") < 0) {
            out.error("Gruppe " + groupName + " existiert nicht.");
            return;
        }

        if (active) {
            out.warn("Gruppe " + groupName + " ist in Wartung. Keine neuen Verbindungen "
                     + "dorthin, und sie fallt als Join-Ziel weg.");
            out.info("Laufende Server bleiben an - damit du mit Bypass testen kannst.");
        } else {
            out.success("Wartung fuer " + groupName + " aufgehoben.");
        }
    }

    private void list(CommandOutput out) {
        boolean global = maintenance.isActive();
        var inMaintenance = groups.findAll().stream()
                .filter(de.kevloe.vibecloud.api.server.ServerGroup::maintenance)
                .map(de.kevloe.vibecloud.api.server.ServerGroup::name)
                .toList();

        if (global) {
            out.warn("Global: Wartungsmodus AKTIV");
        } else {
            out.info("Global: normaler Betrieb");
        }
        if (inMaintenance.isEmpty()) {
            out.info("Gruppen: keine in Wartung");
        } else {
            out.warn("Gruppen in Wartung: " + String.join(", ", inMaintenance));
        }
    }
}
