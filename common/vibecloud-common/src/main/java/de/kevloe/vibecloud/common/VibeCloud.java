package de.kevloe.vibecloud.common;

/**
 * Feste Werte, die in mehreren Komponenten gebraucht werden.
 *
 * <p>Die Protokoll-Version steuert, welche Wrapper sich an welchen Master haengen duerfen:
 * Der Master akzeptiert die aktuelle und die vorherige Version, deshalb lautet die
 * Update-Reihenfolge immer erst Master, dann Wrapper (PLAN.md Abschnitt 5).
 */
public final class VibeCloud {

    /** Aktuelle Protokoll-Version. Bei jeder nicht rueckwaertskompatiblen Aenderung erhoehen. */
    public static final int API_VERSION = 1;

    /** Aelteste Version, die der Master noch annimmt. */
    public static final int MIN_SUPPORTED_API_VERSION = 1;

    private VibeCloud() {
    }
}
