package de.kevloe.vibecloud.master.settings;

import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.protocol.SetMaintenance;

import java.util.Map;

/**
 * Der Wartungsmodus - global und je Gruppe, eine Stelle fuer Konsole und Dashboard.
 *
 * <p>Umschalten sind drei Schritte: speichern, protokollieren, den Proxys mitteilen. Das
 * Letzte vergisst man am leichtesten, und dann stimmt alles ausser dem, was der Spieler
 * sieht: Die Logins lehnt der Master selbst ab, aber die MOTD und die Wahl des Join-Ziels
 * macht der Proxy. Genau so war es einmal - das Feld {@code maintenance} einer Gruppe
 * liess sich im Dashboard setzen, und kein Proxy erfuhr davon.
 *
 * <p>Einen Wartungsmodus fuer einen <b>einzelnen Server</b> gibt es nicht. Die Server einer
 * Gruppe sind austauschbar; einen davon zu sperren hiesse nur, dass der Proxy den naechsten
 * nimmt.
 */
public final class MaintenanceSwitch {

    private final CloudSettings settings;
    private final ServerGroupRepository groups;
    private final PluginConnectionRegistry plugins;
    private final AuditLog audit;

    public MaintenanceSwitch(CloudSettings settings, ServerGroupRepository groups,
                             PluginConnectionRegistry plugins, AuditLog audit) {
        this.settings = settings;
        this.groups = groups;
        this.plugins = plugins;
        this.audit = audit;
    }

    public boolean isActive() {
        return settings.isMaintenanceActive();
    }

    /**
     * Schaltet die Wartung fuer das ganze Netzwerk.
     *
     * @param actor wer umschaltet - fuer das Protokoll
     * @return wie viele Server die Meldung bekommen haben
     */
    public int set(boolean active, String actor) {
        settings.setMaintenance(active);
        audit.record(actor, active ? "maintenance.on" : "maintenance.off", null);
        return announce(actor, true, "", active);
    }

    /**
     * Schaltet die Wartung fuer eine Gruppe.
     *
     * <p>Neue Verbindungen dorthin werden abgelehnt, und sie faellt als Join-Ziel weg. Die
     * laufenden Server bleiben an - damit man mit {@code vibecloud.maintenance.bypass}
     * darauf testen kann.
     *
     * @return wie viele Server die Meldung bekommen haben, oder -1 wenn es die Gruppe
     *         nicht gibt
     */
    public int setGroup(String groupName, boolean active, String actor) {
        if (!groups.updateField(groupName, "maintenance", Boolean.toString(active))) {
            return -1;
        }
        audit.record(actor, active ? "maintenance.group_on" : "maintenance.group_off",
                groupName, Map.of("group", groupName));
        return announce(actor, false, groupName, active);
    }

    /** Schickt den neuen Zustand an alle verbundenen Plugins. */
    private int announce(String actor, boolean global, String groupName, boolean active) {
        int reached = plugins.broadcastToAll(PluginConnectionRegistry.command(builder ->
                builder.setSetMaintenance(SetMaintenance.newBuilder()
                        .setGlobal(global)
                        .setGroupName(groupName)
                        .setActive(active))));
        if (reached == 0) {
            // Kein Plugin verbunden ist kein Fehler - aber der Betreiber soll wissen,
            // dass gerade niemand die Aenderung sieht.
            audit.record(actor, "maintenance.not_announced", groupName.isEmpty() ? null : groupName);
        }
        return reached;
    }
}
