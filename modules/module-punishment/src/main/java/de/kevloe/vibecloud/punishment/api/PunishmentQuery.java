package de.kevloe.vibecloud.punishment.api;

import java.util.UUID;

/**
 * Nutzlast fuer den Modul-Kanal: Hat dieser Spieler eine aktive Strafe dieser Art?
 *
 * <p>Wird vom Paper-Bundle an den Master geschickt, wenn jemand schreibt. Die Entscheidung
 * faellt im Master - der Gameserver hat die Strafen nicht und soll sie auch nicht haben
 * (PLAN.md Abschnitt 13).
 */
public record PunishmentQuery(UUID uuid, String type) {
}
