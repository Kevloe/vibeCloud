package de.kevloe.vibecloud.punishment;

import de.kevloe.vibecloud.punishment.api.Punishment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Die reine Logik des Punishment-Moduls - ohne Datenbank und ohne Master. */
class PunishmentModuleTest {

    // ---------------------------------------------------------------- Dauer

    @Test
    @DisplayName("Dauerangaben werden verstanden")
    void dauerAngabenWerdenVerstanden() {
        assertThat(PunishmentModule.parseDuration("30d")).isEqualTo(Duration.ofDays(30));
        assertThat(PunishmentModule.parseDuration("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(PunishmentModule.parseDuration("90m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(PunishmentModule.parseDuration("45s")).isEqualTo(Duration.ofSeconds(45));
        assertThat(PunishmentModule.parseDuration("7D")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("Unsinnige Dauerangaben werden abgelehnt, nicht geraten")
    void unsinnigeDauerAngabenWerdenAbgelehnt() {
        // Lieber eine Fehlermeldung als ein Bann mit einer Dauer, die niemand wollte.
        assertThat(PunishmentModule.parseDuration("30")).isNull();
        assertThat(PunishmentModule.parseDuration("d")).isNull();
        assertThat(PunishmentModule.parseDuration("abc")).isNull();
        assertThat(PunishmentModule.parseDuration("30x")).isNull();
        assertThat(PunishmentModule.parseDuration("0d")).isNull();
        assertThat(PunishmentModule.parseDuration("-5d")).isNull();
        assertThat(PunishmentModule.parseDuration("")).isNull();
        assertThat(PunishmentModule.parseDuration(null)).isNull();
    }

    // ---------------------------------------------------------------- Zustand einer Strafe

    @Test
    @DisplayName("Eine dauerhafte Strafe laeuft nie ab")
    void dauerhafteStrafeLaeuftNieAb() {
        Punishment ban = punishment(Punishment.Type.BAN, null, null);

        assertThat(ban.isPermanent()).isTrue();
        assertThat(ban.isActive()).isTrue();
        assertThat(ban.remaining()).isEmpty();
    }

    @Test
    @DisplayName("Eine abgelaufene Strafe wirkt nicht mehr")
    void abgelaufeneStrafeWirktNichtMehr() {
        Instant vergangen = Instant.now().minus(Duration.ofHours(1));

        assertThat(punishment(Punishment.Type.BAN, vergangen, null).isActive()).isFalse();
    }

    @Test
    @DisplayName("Eine aufgehobene Strafe wirkt nicht mehr, bleibt aber in der Historie")
    void aufgehobeneStrafeWirktNichtMehr() {
        // Dauerhaft und trotzdem nicht aktiv: Das Aufheben loescht nichts, es markiert nur.
        Punishment pardoned = punishment(Punishment.Type.BAN, null, Instant.now());

        assertThat(pardoned.isRevoked()).isTrue();
        assertThat(pardoned.isActive()).isFalse();
        assertThat(pardoned.remaining()).isEmpty();
    }

    @Test
    @DisplayName("Eine befristete Strafe hat eine Restlaufzeit")
    void befristeteStrafeHatRestlaufzeit() {
        Punishment ban = punishment(Punishment.Type.BAN,
                Instant.now().plus(Duration.ofHours(2)), null);

        assertThat(ban.remaining()).isPresent();
        assertThat(ban.remaining().orElseThrow()).isBetween(
                Duration.ofMinutes(119), Duration.ofHours(2));
    }

    @Test
    @DisplayName("Nur Bann und Mute wirken dauerhaft")
    void nurBannUndMuteWirkenDauerhaft() {
        // Ein Kick ist einmalig, eine Verwarnung nur ein Vermerk - gegen beide muss beim
        // Login nichts geprueft werden.
        assertThat(Punishment.Type.BAN.isEnforced()).isTrue();
        assertThat(Punishment.Type.MUTE.isEnforced()).isTrue();
        assertThat(Punishment.Type.KICK.isEnforced()).isFalse();
        assertThat(Punishment.Type.WARN.isEnforced()).isFalse();
    }

    // ---------------------------------------------------------------- Einspruchs-Kennung

    @Test
    @DisplayName("Einspruchs-Kennungen enthalten keine verwechselbaren Zeichen")
    void kennungenEnthaltenKeineVerwechselbarenZeichen() {
        // Ein Spieler tippt die Kennung aus dem Ban-Bildschirm ab. 0/O und 1/I/L dabei
        // auseinanderzuhalten, klappt nicht - deshalb kommen sie gar nicht vor.
        for (int i = 0; i < 500; i++) {
            String id = PunishmentRepository.newAppealId();

            assertThat(id).hasSize(8).doesNotContainAnyWhitespaces();
            assertThat(id).doesNotContain("0").doesNotContain("O")
                    .doesNotContain("1").doesNotContain("I").doesNotContain("L");
            assertThat(id).isUpperCase();
        }
    }

    @Test
    @DisplayName("Einspruchs-Kennungen wiederholen sich nicht erkennbar")
    void kennungenWiederholenSichNicht() {
        var ids = new java.util.HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            ids.add(PunishmentRepository.newAppealId());
        }
        assertThat(ids).hasSize(1000);
    }

    private static Punishment punishment(Punishment.Type type, Instant expires,
                                         Instant revoked) {
        return new Punishment(1, type, java.util.UUID.randomUUID(), "Spieler",
                "Grund", "Konsole", Instant.now().minus(Duration.ofMinutes(5)),
                expires, revoked, revoked == null ? null : "Konsole", "ABCDEFGH");
    }
}
