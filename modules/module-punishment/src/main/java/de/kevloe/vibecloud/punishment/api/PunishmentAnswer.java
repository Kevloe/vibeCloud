package de.kevloe.vibecloud.punishment.api;

/**
 * Antwort auf eine {@link PunishmentQuery}.
 *
 * @param reason  Grund im Klartext, zum Einsetzen in die Meldung
 * @param expires lesbarer Ablaufzeitpunkt oder "nie"
 */
public record PunishmentAnswer(boolean active, String reason, String expires, String appealId) {

    public static PunishmentAnswer none() {
        return new PunishmentAnswer(false, "", "", "");
    }
}
