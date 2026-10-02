package de.kevloe.vibecloud.api.event.events;

/** Ein Modul ist aktiv geworden (PLAN.md Abschnitt 10a). */
public record ModuleEnableEvent(String moduleId, String version) {
}
