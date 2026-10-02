package de.kevloe.vibecloud.module;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spielerdaten lesen (PLAN.md Abschnitt 10). */
public interface ModulePlayers {

    Optional<PlayerInfo> find(UUID uuid);

    /** Suche ohne Beachtung der Gross-/Kleinschreibung. */
    Optional<PlayerInfo> findByName(String name);

    /** Auf welchem Server ist der Spieler gerade; leer wenn offline. */
    Optional<String> currentServer(UUID uuid);

    /**
     * Hash der zuletzt benutzten IP, falls bekannt.
     *
     * <p>Bewusst nur der Hash: Ein Modul kann damit denselben Anschluss wiedererkennen -
     * genau das braucht ein IP-Bann - aber die Adresse nicht auslesen (PLAN.md Abschnitt 13).
     * Denselben Wert bildet {@code Hashing.ipHash} aus einer IP beim Login.
     */
    Optional<String> lastIpHash(UUID uuid);

    /**
     * Spielernamen, die mit {@code prefix} anfangen - zuletzt gesehene zuerst.
     *
     * <p>Fuer die Vervollstaendigung eigener Befehle. Mit Praefix und Grenze, weil das an
     * einem Tastendruck haengt: Die ganze Spielerliste zu laden waere bei jedem Buchstaben
     * eine Abfrage ohne Grenze.
     */
    java.util.List<String> suggestNames(String prefix, int limit);

    /**
     * Trennt einen Spieler, falls er online ist.
     *
     * <p>Nur der Schluessel, nie ein fertiger Text: Den Satz baut das Plugin in der
     * Sprache des Spielers (PLAN.md Abschnitt 11a).
     *
     * @return ob ein Proxy erreicht wurde - nicht, ob der Spieler online war
     */
    boolean kick(UUID uuid, String messageKey, java.util.Map<String, String> placeholders);

    /** Schickt einem Spieler eine Nachricht, falls er online ist. */
    boolean message(UUID uuid, String messageKey, java.util.Map<String, String> placeholders);

    /**
     * Schickt allen mit dieser Berechtigung eine Nachricht.
     *
     * <p>Wer sie bekommt, entscheidet die Rechteauswertung des Masters - das Plugin fragt
     * nur ab, was ihm geliefert wurde (PLAN.md Abschnitt 13).
     *
     * @return Anzahl erreichter Proxys
     */
    int broadcast(String permission, String messageKey,
                  java.util.Map<String, String> placeholders);

    /**
     * Was ein Modul ueber einen Spieler wissen darf.
     *
     * <p>Bewusst ohne IP: Die wird nur gehasht gespeichert, und ein Modul hat keinen Grund,
     * sie zu sehen (PLAN.md Abschnitt 13).
     */
    record PlayerInfo(
            UUID uuid,
            String name,
            String platform,
            String rankId,
            String locale,
            Instant firstLogin,
            Instant lastLogin,
            long playtimeSeconds) {

        public boolean isBedrock() {
            return "BEDROCK".equals(platform);
        }
    }
}
