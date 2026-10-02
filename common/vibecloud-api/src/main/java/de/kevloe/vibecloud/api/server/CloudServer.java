package de.kevloe.vibecloud.api.server;

import de.kevloe.vibecloud.api.ServerState;

import java.time.Instant;

/**
 * Ein Server, wie der Master ihn sieht.
 *
 * @param node      Node, auf dem er laeuft
 * @param players   aktuelle Spielerzahl (ab M3 echte Werte)
 */
public record CloudServer(
        String name,
        String groupName,
        ServerPlatformType platform,
        String node,
        int port,
        ServerState state,
        boolean staticServer,
        Instant startedAt,
        int players,
        int maxPlayers) {

    public CloudServer withState(ServerState newState) {
        return new CloudServer(name, groupName, platform, node, port, newState,
                staticServer, startedAt, players, maxPlayers);
    }

    public CloudServer withPlayers(int newPlayers) {
        return new CloudServer(name, groupName, platform, node, port, state,
                staticServer, startedAt, newPlayers, maxPlayers);
    }

    public boolean isActive() {
        return state.isActive();
    }

    /** Auslastung in Prozent; 0 wenn kein Limit gesetzt ist. */
    public int loadPercent() {
        return maxPlayers <= 0 ? 0 : (players * 100) / maxPlayers;
    }
}
