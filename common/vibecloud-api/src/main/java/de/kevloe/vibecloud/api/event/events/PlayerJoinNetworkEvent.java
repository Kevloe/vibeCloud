package de.kevloe.vibecloud.api.event.events;

import java.util.UUID;

/**
 * Ein Spieler ist im Netzwerk angekommen (PLAN.md Abschnitt 10a).
 *
 * <p>Post-Event: Es ist schon passiert, nicht abbrechbar. Wer einen Join <b>verhindern</b>
 * will, haengt sich an {@code PlayerPreLoginEvent}.
 */
public record PlayerJoinNetworkEvent(UUID uuid, String name, String initialServer) {
}
