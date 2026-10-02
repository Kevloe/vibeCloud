package de.kevloe.vibecloud.api.identity;

/**
 * Wer am anderen Ende einer Verbindung sitzt (PLAN.md Abschnitt 13).
 *
 * <p>Eine erfolgreiche Anmeldung macht niemanden zum Administrator: Jede eingehende Nachricht
 * wird gegen diese Identitaet geprueft. Ein Node darf nur ueber seine eigenen Server sprechen,
 * ein Gameserver nur ueber sich selbst.
 */
public sealed interface CallIdentity permits NodeIdentity, ServerIdentity {

    /** Fuer Logs und Audit-Eintraege, z. B. {@code NODE:node-a}. */
    String describe();
}
