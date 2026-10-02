package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.master.permission.PermissionService;

import java.util.List;

/**
 * Vorschlaege, die mehrere Befehle brauchen.
 *
 * <p>An einer Stelle, damit {@code /rank set}, {@code /perm player add} und
 * {@code /tempban} dieselben Dauern anbieten - und nicht jeder seine eigene Auswahl.
 */
final class Completions {

    /**
     * Gaengige Dauern.
     *
     * <p>Von lang nach kurz, weil die laengeren oefter gebraucht werden. Das Format
     * versteht {@code 30d}, {@code 12h}, {@code 90m}, {@code 45s}.
     */
    static final List<String> DURATIONS =
            List.of("permanent", "30d", "14d", "7d", "3d", "24h", "12h", "2h", "30m");

    /**
     * Kontext-Argumente fuer Rechte.
     *
     * <p>Mit Gleichheitszeichen, weil genau das getippt werden muss:
     * {@code perm player add Kevin vibecloud.fly group=bedwars}.
     */
    static final List<String> CONTEXTS = List.of("group=", "server=");

    private Completions() {
    }

    /**
     * Alle Rechte-Knoten, die es sinnvoll vorzuschlagen gibt.
     *
     * <p>Gebaut wird die Liste in {@link CommandRegistry#permissionNodes(List)} - dieselbe
     * Stelle, aus der auch der Rechte-Editor im Dashboard seine Vorschlaege bezieht.
     */
    static List<String> permissionNodes(PermissionService permissions,
                                        CommandRegistry registry) {
        return registry.permissionNodes(permissions.declaredNodes());
    }
}
