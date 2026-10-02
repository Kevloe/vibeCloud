package de.kevloe.vibecloud.api.event.events;

/**
 * Ein Modul wurde entladen (PLAN.md Abschnitt 10a).
 *
 * <p>Handler sollten hier nur noch aufraeumen - die Klassen des Moduls sind moeglicherweise
 * schon nicht mehr ladbar.
 */
public record ModuleDisableEvent(String moduleId) {
}
