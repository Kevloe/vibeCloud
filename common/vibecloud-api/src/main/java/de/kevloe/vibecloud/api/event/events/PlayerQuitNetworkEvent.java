package de.kevloe.vibecloud.api.event.events;

import java.time.Duration;
import java.util.UUID;

/** Ein Spieler hat das Netzwerk verlassen, mit Dauer der Sitzung. */
public record PlayerQuitNetworkEvent(UUID uuid, String name, Duration sessionDuration) {
}
