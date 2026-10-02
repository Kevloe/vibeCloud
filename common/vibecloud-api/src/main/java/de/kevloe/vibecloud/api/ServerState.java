package de.kevloe.vibecloud.api;

/**
 * Lebenszyklus eines Gameservers (PLAN.md Abschnitt 7).
 *
 * <p>{@link #WAITING_FOR_NODE} gilt nur fuer statische Server: Ihre Welt liegt auf einem
 * festen Node, deshalb warten sie auf dessen Rueckkehr statt auf einen anderen Node
 * auszuweichen - dort kaemen sie mit leerer Welt hoch.
 */
public enum ServerState {
    PREPARING,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    CRASHED,
    WAITING_FOR_NODE;

    public boolean isActive() {
        return this == PREPARING || this == STARTING || this == RUNNING;
    }
}
