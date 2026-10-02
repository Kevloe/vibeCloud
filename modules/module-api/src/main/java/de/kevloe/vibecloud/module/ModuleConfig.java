package de.kevloe.vibecloud.module;

/**
 * Konfiguration eines Moduls unter {@code modules/<id>/config.json} (PLAN.md Abschnitt 10).
 *
 * <p>Beim ersten Start wird die Vorgabe geschrieben, damit sichtbar ist, welche Werte es
 * gibt - statt dass man sie im Code suchen muss.
 */
public interface ModuleConfig {

    /**
     * Laedt die Konfiguration oder schreibt die Vorgabe, falls die Datei fehlt.
     *
     * @param type     ein Record oder eine einfache Klasse
     * @param defaults wird geschrieben, wenn die Datei fehlt
     */
    <T> T load(Class<T> type, T defaults);

    /** Liest neu ein - fuer {@code module reload} ohne Neustart. */
    <T> T reload(Class<T> type, T defaults);

    void save(Object value);
}
