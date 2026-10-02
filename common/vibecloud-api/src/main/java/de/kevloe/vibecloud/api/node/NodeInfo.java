package de.kevloe.vibecloud.api.node;

import java.time.Instant;

/**
 * Ein bekannter Wrapper aus Sicht des Masters.
 *
 * @param connected      ob gerade ein Control-Stream offen ist
 * @param lastSeen       letzter Heartbeat, UTC
 * @param memoryUsedMb   von Gameservern belegt (ab M2 echte Werte)
 */
public record NodeInfo(
        String name,
        boolean enabled,
        boolean connected,
        Instant lastSeen,
        long maxMemoryMb,
        long memoryUsedMb,
        int cpuCores,
        String osName,
        double cpuLoad) {

    public long freeMemoryMb() {
        return Math.max(0L, maxMemoryMb - memoryUsedMb);
    }
}
