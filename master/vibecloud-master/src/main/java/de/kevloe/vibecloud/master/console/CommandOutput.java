package de.kevloe.vibecloud.master.console;

/**
 * Ausgabekanal eines Befehls.
 *
 * <p>Ein Befehl schreibt nie direkt nach {@code System.out}: Derselbe Befehl soll spaeter
 * aus dem Spiel oder ueber die REST-API aufgerufen werden, und dann muss die Ausgabe woanders
 * landen (PLAN.md Abschnitt 12).
 */
public interface CommandOutput {

    void info(String message);

    void success(String message);

    void warn(String message);

    void error(String message);

    /** Mehrzeilige Ausgabe, z. B. eine Tabelle. */
    default void lines(Iterable<String> lines) {
        lines.forEach(this::info);
    }
}
