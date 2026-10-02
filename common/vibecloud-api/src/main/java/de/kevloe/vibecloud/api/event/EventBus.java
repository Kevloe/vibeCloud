package de.kevloe.vibecloud.api.event;

/**
 * Derselbe Bus fuer Master, Module und Plattform-Plugins (PLAN.md Abschnitt 10a).
 *
 * <p>Wer ein Event abonniert, braucht keine Ahnung davon, wo es entsteht.
 */
public interface EventBus {

    /** Alle mit {@link Subscribe} markierten Methoden des Objekts anmelden. */
    void register(Object listener);

    /** Alle Handler dieses Objekts abmelden. Passiert beim Entladen eines Moduls automatisch. */
    void unregister(Object listener);

    /**
     * Post-Event: Es ist schon passiert. Laeuft asynchron, ein langsamer Handler
     * blockiert keinen anderen. Eine Ausnahme in einem Handler beendet die Verteilung nicht.
     */
    void post(Object event);

    /**
     * Pre-Event: synchron und in Prioritaetsreihenfolge, damit ein Handler abbrechen kann.
     * Gibt dasselbe Event zurueck, damit der Aufrufer es direkt pruefen kann.
     */
    <E extends Cancellable> E postSync(E event);
}
