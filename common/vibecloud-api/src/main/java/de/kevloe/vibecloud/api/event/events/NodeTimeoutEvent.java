package de.kevloe.vibecloud.api.event.events;

import java.time.Duration;

/**
 * Ein Wrapper hat zu lange keinen Heartbeat geschickt.
 *
 * <p>Verlaesst den Master nie - fuer andere Server ist das uninteressant.
 */
public record NodeTimeoutEvent(String node, Duration silentFor) {
}
