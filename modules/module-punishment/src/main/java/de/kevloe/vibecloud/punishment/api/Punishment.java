package de.kevloe.vibecloud.punishment.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Eine Strafe (PLAN.md Abschnitt 10).
 *
 * <p>Liegt im exportierten Paket, damit andere Module damit arbeiten koennen - ein
 * Discord-Modul bekommt sie im {@link PlayerPunishedEvent} mitgeliefert.
 *
 * @param expiresAt {@code null} = dauerhaft
 * @param appealId  kurze Kennung, die ein Spieler abtippen kann
 */
public record Punishment(
        long id,
        Type type,
        UUID uuid,
        String name,
        String reason,
        String actor,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedBy,
        String appealId) {

    /** Art der Strafe. */
    public enum Type {
        /** Sperrt den Zugang zum Netzwerk. */
        BAN,
        /** Verhindert das Schreiben im Chat. */
        MUTE,
        /** Trennt einmalig - laeuft nicht ab. */
        KICK,
        /** Nur ein Vermerk mit Hinweis an den Spieler. */
        WARN;

        /** Ob diese Art ueberhaupt ablaufen kann. */
        public boolean canExpire() {
            return this == BAN || this == MUTE;
        }

        /** Ob diese Art dauerhaft wirkt, also gegen sie geprueft werden muss. */
        public boolean isEnforced() {
            return this == BAN || this == MUTE;
        }
    }

    /** Dauerhaft, laeuft nicht ab. */
    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Wirkt die Strafe gerade?
     *
     * <p>Nicht aufgehoben und entweder dauerhaft oder noch nicht abgelaufen.
     */
    public boolean isActive(Instant now) {
        return !isRevoked() && (isPermanent() || now.isBefore(expiresAt));
    }

    public boolean isActive() {
        return isActive(Instant.now());
    }

    /** Wie lange sie noch laeuft; leer bei dauerhaften Strafen. */
    public java.util.Optional<Duration> remaining() {
        if (isPermanent() || isRevoked()) {
            return java.util.Optional.empty();
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return java.util.Optional.of(remaining.isNegative() ? Duration.ZERO : remaining);
    }
}
