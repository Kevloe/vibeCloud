package de.kevloe.vibecloud.api.event;

/**
 * Reihenfolge der Handler. {@link #LAST} ist der Platz fuer reines Mitschreiben,
 * wenn alle Aenderungen schon durch sind.
 */
public enum Priority {
    FIRST,
    EARLY,
    NORMAL,
    LATE,
    LAST
}
