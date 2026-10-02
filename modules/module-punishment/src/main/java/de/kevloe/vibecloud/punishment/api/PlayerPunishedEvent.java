package de.kevloe.vibecloud.punishment.api;

/**
 * Ein Spieler wurde bestraft (PLAN.md Abschnitt 10a).
 *
 * <p>Das ist der eigentliche Gewinn des Modul-Systems: Ein spaeteres Discord-Modul
 * reagiert darauf, <b>ohne dass dieses Modul davon weiss</b>:
 *
 * <pre>{@code
 * @Subscribe
 * public void onPunished(PlayerPunishedEvent event) { postToChannel(event); }
 * }</pre>
 *
 * <p>Damit das geht, steht {@code de.kevloe.vibecloud.punishment.api} in {@code exports}
 * der {@code module.json} - ohne Export versteckt die child-first-Isolation diese Klasse.
 */
public record PlayerPunishedEvent(Punishment punishment) {

    public Punishment.Type type() {
        return punishment.type();
    }
}
