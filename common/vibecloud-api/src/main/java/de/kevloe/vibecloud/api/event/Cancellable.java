package de.kevloe.vibecloud.api.event;

/**
 * Pre-Events: abbrechbar, synchron, mit Begruendung.
 *
 * <p>Die Begruendung ist ein Nachrichten-Schluessel, kein fertiger Text - Uebersetzung
 * passiert erst bei der Anzeige (PLAN.md Abschnitt 11a).
 */
public interface Cancellable {

    boolean isCancelled();

    /**
     * @param messageKey Schluessel aus den Sprachdateien, z. B. {@code punishment.ban.screen}
     */
    void cancel(String messageKey);

    String cancelReasonKey();
}
