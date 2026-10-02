package de.kevloe.vibecloud.common;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Zeitangaben fuer die Anzeige.
 *
 * <p>Gespeichert wird alles in UTC (`timestamptz`), umgerechnet wird erst hier - so steht in
 * der Datenbank ein eindeutiger Zeitpunkt, und wer auf die Konsole oder einen Ban-Bildschirm
 * schaut, sieht seine eigene Uhrzeit.
 *
 * <p>Ohne diese Stelle rutscht leicht ein {@code Instant.toString()} in die Ausgabe. Das ist
 * immer UTC und sieht aus wie ein Fehler: Im Sommer in Deutschland sind das zwei Stunden
 * Rueckstand auf die Wanduhr.
 */
public final class Times {

    /** Zone des Rechners - bei einer Cloud ist das die Zone des Betreibers. */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final DateTimeFormatter MINUTES =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZONE);

    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZONE);

    private Times() {
    }

    /** {@code 01.10.2026 15:57} - leer bei {@code null}. */
    public static String format(Instant instant) {
        return instant == null ? "" : MINUTES.format(instant);
    }

    /** Wie {@link #format}, zusaetzlich mit Sekunden. */
    public static String formatWithSeconds(Instant instant) {
        return instant == null ? "" : SECONDS.format(instant);
    }

    /**
     * Eine Dauer in lesbarer Form: {@code 3d 4h}, {@code 12m}, {@code 8s}.
     *
     * <p>Nur die zwei groessten Einheiten - "2d 7h 13m 51s" liest niemand.
     */
    public static String formatDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
        }
        return seconds + "s";
    }

    /**
     * Liest eine Dauer in der Schreibweise, die ueberall in der Cloud gilt:
     * {@code 30d}, {@code 12h}, {@code 90m}, {@code 45s}.
     *
     * <p>Hier und nicht je Befehl, weil es eine Angabe ist, die ein Betreiber tippt:
     * Haette {@code tempban 7d} eine andere Vorstellung von "7d" als
     * {@code perm player add ... 7d}, waere der Unterschied erst nach sieben Tagen zu
     * sehen.
     *
     * @return {@code null}, wenn es keine gueltige Dauer ist - ob das "permanent" heisst
     *         oder ein Fehler ist, entscheidet der Aufrufer
     */
    public static Duration parseDuration(String text) {
        if (text == null || text.length() < 2) {
            return null;
        }
        char unit = text.charAt(text.length() - 1);
        try {
            long amount = Long.parseLong(text.substring(0, text.length() - 1));
            if (amount <= 0) {
                return null;
            }
            return switch (Character.toLowerCase(unit)) {
                case 'd' -> Duration.ofDays(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 's' -> Duration.ofSeconds(amount);
                default -> null;
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
