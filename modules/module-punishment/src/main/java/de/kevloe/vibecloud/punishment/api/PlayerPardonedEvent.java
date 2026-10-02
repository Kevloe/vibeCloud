package de.kevloe.vibecloud.punishment.api;

/** Eine Strafe wurde aufgehoben (PLAN.md Abschnitt 10a). */
public record PlayerPardonedEvent(Punishment punishment, String actor) {

    public Punishment.Type type() {
        return punishment.type();
    }
}
