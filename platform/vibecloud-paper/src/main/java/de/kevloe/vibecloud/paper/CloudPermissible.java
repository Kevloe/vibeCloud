package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.ServerOperator;

import java.util.UUID;

/**
 * Leitet Bukkit-Rechteabfragen an die Cloud weiter (PLAN.md Abschnitt 9).
 *
 * <p>Damit funktionieren {@code player.hasPermission(...)} und alle Fremd-Plugins
 * automatisch, ohne dass sie von der Cloud wissen muessen - genau die Zusage aus dem Plan.
 *
 * <h2>Wann die Cloud entscheidet und wann nicht</h2>
 * Nur wenn die Cloud zu einem Knoten eine <b>ausdrueckliche</b> Regel hat, entscheidet sie.
 * Sonst uebernimmt das normale Bukkit-Verhalten: Plugin-Vorgaben
 * ({@code PermissionDefault}), Operator-Status und Attachments bleiben wirksam.
 *
 * <p>Das ist Absicht. Wuerde hier bei jedem unbekannten Knoten {@code false} zurueckkommen,
 * wuerde jedes Plugin mit eigenen Standardrechten aufhoeren zu funktionieren, sobald die
 * Cloud laeuft - und niemand wuesste, warum.
 */
final class CloudPermissible extends PermissibleBase {

    private final UUID uuid;
    private final CloudPermissions permissions;
    private final PermissibleBase original;

    CloudPermissible(ServerOperator operator, UUID uuid, CloudPermissions permissions,
                     PermissibleBase original) {
        super(operator);
        this.uuid = uuid;
        this.permissions = permissions;
        this.original = original;
    }

    @Override
    public boolean hasPermission(String node) {
        Boolean decision = cloudDecision(node);
        if (decision != null) {
            return decision;
        }
        // super statt original, wenn kein Original vorliegt - sonst waere das eine
        // Endlosrekursion auf diese Methode.
        return original != null ? original.hasPermission(node) : super.hasPermission(node);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        Boolean decision = cloudDecision(permission.getName());
        if (decision != null) {
            return decision;
        }
        return original != null
                ? original.hasPermission(permission)
                : super.hasPermission(permission);
    }

    /**
     * Gilt als gesetzt, wenn die Cloud eine Regel dazu hat.
     *
     * <p>Wichtig fuer Plugins, die {@code isPermissionSet} pruefen, um zwischen "nicht
     * gesetzt" und "ausdruecklich verboten" zu unterscheiden.
     */
    @Override
    public boolean isPermissionSet(String node) {
        if (cloudDecision(node) != null) {
            return true;
        }
        return original != null ? original.isPermissionSet(node) : super.isPermissionSet(node);
    }

    @Override
    public boolean isPermissionSet(Permission permission) {
        if (cloudDecision(permission.getName()) != null) {
            return true;
        }
        return original != null
                ? original.isPermissionSet(permission)
                : super.isPermissionSet(permission);
    }

    /**
     * @return {@code true}/{@code false} wenn die Cloud eine Regel hat, sonst {@code null}
     */
    private Boolean cloudDecision(String node) {
        if (!permissions.knows(uuid)) {
            // Spieler noch nicht geladen oder Master war beim Join nicht erreichbar.
            // Dann soll Bukkit entscheiden, nicht ein stilles "nein".
            return null;
        }
        return permissions.explain(uuid, node)
                .map(candidate -> candidate.entry().value())
                .orElse(null);
    }

    PermissibleBase original() {
        return original;
    }
}
