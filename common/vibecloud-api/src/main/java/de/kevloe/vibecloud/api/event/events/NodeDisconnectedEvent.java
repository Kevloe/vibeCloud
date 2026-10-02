package de.kevloe.vibecloud.api.event.events;

/** Ein Wrapper hat die Verbindung regulaer beendet. */
public record NodeDisconnectedEvent(String node, String reason) {
}
