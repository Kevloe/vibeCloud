package de.kevloe.vibecloud.api.event.events;

import java.util.UUID;

/**
 * Die Rechte eines Spielers haben sich geaendert - egal aus welchem Grund
 * (PLAN.md Abschnitt 10a).
 *
 * <p>Feuert bei Rang-Aenderung, entzogener Permission, umgebauter Vererbung und
 * abgelaufenem Rang. Das ACP haengt sich hier ein, um Dashboard-Zugaenge zu entfernen,
 * sobald die Berechtigung fehlt (PLAN.md Abschnitt 12).
 *
 * @param uuid betroffener Spieler, oder {@code null} wenn alle betroffen sind
 */
public record PlayerPermissionsChangedEvent(UUID uuid) {

    public boolean affectsAllPlayers() {
        return uuid == null;
    }
}
