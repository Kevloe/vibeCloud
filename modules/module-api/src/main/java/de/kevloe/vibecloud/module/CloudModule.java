package de.kevloe.vibecloud.module;

/**
 * Ein Cloud-Modul (PLAN.md Abschnitt 10).
 *
 * <p>Ein Modul ist ein JAR mit {@code module.json} im Wurzelverzeichnis. Es wird in einem
 * eigenen child-first ClassLoader geladen und sieht <b>nur</b> {@code module-api} und
 * {@code vibecloud-api} - niemals Master-Interna.
 *
 * <h2>Lebenszyklus</h2>
 * <pre>
 * onLoad()                 Felder vorbereiten, nichts registrieren
 * onEnable(ModuleContext)  Commands, Events, Routen registrieren
 * onDisable()              alles freigeben
 * </pre>
 *
 * <p>Zwischen {@code onLoad} und {@code onEnable} liegt die Reihenfolge-Auflosung: Erst
 * wenn alle Module geladen sind, werden sie in Abhaengigkeitsreihenfolge aktiviert. Ein
 * Modul darf in {@code onEnable} also davon ausgehen, dass seine {@code depends} schon
 * aktiv sind.
 */
public interface CloudModule {

    /**
     * Vor dem Aktivieren. Hier noch nichts registrieren - andere Module sind
     * moeglicherweise noch nicht aktiv.
     */
    default void onLoad(ModuleContext context) {
    }

    /** Hier werden Commands, Events, Kanal-Handler und Routen registriert. */
    void onEnable(ModuleContext context);

    /**
     * Muss alles freigeben, was {@link #onEnable} belegt hat.
     *
     * <p>Events und Commands meldet der ModuleManager automatisch ab. Eigene Threads,
     * offene Dateien und Timer muss das Modul selbst schliessen - sonst leckt jeder Reload.
     */
    default void onDisable() {
    }
}
