package de.kevloe.vibecloud.api.event.events;

import java.util.UUID;

/** Ein Spieler hat den Server gewechselt. */
public record PlayerSwitchServerEvent(UUID uuid, String name, String fromServer, String toServer) {
}
