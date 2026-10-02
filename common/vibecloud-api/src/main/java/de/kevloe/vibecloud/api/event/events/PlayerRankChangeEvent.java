package de.kevloe.vibecloud.api.event.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Der Rang eines Spielers wurde gesetzt, zurueckgesetzt oder ist abgelaufen
 * (PLAN.md Abschnitt 10a).
 *
 * @param actor   wer es ausgeloest hat; {@code SYSTEM} bei Ablauf
 * @param expires wann der neue Rang ablaeuft, {@code null} = permanent
 */
public record PlayerRankChangeEvent(
        UUID uuid,
        String name,
        String oldRank,
        String newRank,
        String actor,
        Instant expires) {

    public boolean wasExpiry() {
        return "SYSTEM".equals(actor);
    }
}
