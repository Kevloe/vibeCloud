package de.kevloe.vibecloud.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/** Zeitangaben fuer die Anzeige. */
class TimesTest {

    @Test
    @DisplayName("Angezeigt wird die lokale Zeit, nicht UTC")
    void angezeigtWirdDieLokaleZeit() {
        Instant jetzt = Instant.now();

        // Der eigentliche Punkt: Instant.toString() waere UTC. In Deutschland sind das im
        // Sommer zwei Stunden weniger als die Wanduhr - und es sieht nach einem Fehler aus.
        String erwartet = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .format(LocalDateTime.ofInstant(jetzt, ZoneId.systemDefault()));

        assertThat(Times.format(jetzt)).isEqualTo(erwartet);
    }

    @Test
    @DisplayName("Ein fehlender Zeitpunkt gibt keinen Text")
    void fehlenderZeitpunktGibtKeinenText() {
        // Statt "null" in der Ausgabe stehen zu lassen.
        assertThat(Times.format(null)).isEmpty();
        assertThat(Times.formatWithSeconds(null)).isEmpty();
    }

    @Test
    @DisplayName("Mit Sekunden kommt dasselbe Datum, nur genauer")
    void mitSekundenKommtDasselbeDatum() {
        Instant jetzt = Instant.now();

        assertThat(Times.formatWithSeconds(jetzt)).startsWith(Times.format(jetzt));
        assertThat(Times.formatWithSeconds(jetzt)).hasSize(Times.format(jetzt).length() + 3);
    }

    @Test
    @DisplayName("Dauern zeigen nur die zwei groessten Einheiten")
    void dauernZeigenNurZweiEinheiten() {
        assertThat(Times.formatDuration(Duration.ofDays(3).plusHours(4))).isEqualTo("3d 4h");
        assertThat(Times.formatDuration(Duration.ofHours(5).plusMinutes(7))).isEqualTo("5h 7m");
        assertThat(Times.formatDuration(Duration.ofMinutes(12).plusSeconds(9)))
                .isEqualTo("12m 9s");
        assertThat(Times.formatDuration(Duration.ofSeconds(8))).isEqualTo("8s");

        // Glatte Werte ohne leeren Zusatz - nicht "3d 0h".
        assertThat(Times.formatDuration(Duration.ofDays(3))).isEqualTo("3d");
        assertThat(Times.formatDuration(Duration.ofHours(5))).isEqualTo("5h");

        // 2d 7h 13m 51s liest niemand - Minuten und Sekunden fallen bei Tagen weg.
        assertThat(Times.formatDuration(
                Duration.ofDays(2).plusHours(7).plusMinutes(13).plusSeconds(51)))
                .isEqualTo("2d 7h");
    }

    @Test
    @DisplayName("Keine Dauer ist 0s, auch rueckwaerts")
    void keineDauerIstNull() {
        assertThat(Times.formatDuration(null)).isEqualTo("0s");
        assertThat(Times.formatDuration(Duration.ZERO)).isEqualTo("0s");
        // Eine abgelaufene Strafe darf nicht "-3m" anzeigen.
        assertThat(Times.formatDuration(Duration.ofMinutes(-3))).isEqualTo("0s");
    }

    @Test
    @DisplayName("Dauern werden gelesen, wie sie getippt werden")
    void dauernWerdenGelesen() {
        assertThat(Times.parseDuration("30d")).isEqualTo(Duration.ofDays(30));
        assertThat(Times.parseDuration("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(Times.parseDuration("90m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(Times.parseDuration("45s")).isEqualTo(Duration.ofSeconds(45));

        // Gross oder klein ist gleich - niemand soll an der Einheit scheitern.
        assertThat(Times.parseDuration("7D")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("Was keine Dauer ist, wird keine")
    void unsinnIstKeineDauer() {
        // null heisst "keine gueltige Dauer". Was der Aufrufer daraus macht - permanent
        // oder ein Fehler - entscheidet er selbst; ein stiller Standardwert hier waere bei
        // einem Tippfehler eine Strafe mit falscher Laenge.
        assertThat(Times.parseDuration(null)).isNull();
        assertThat(Times.parseDuration("")).isNull();
        assertThat(Times.parseDuration("30")).isNull();
        assertThat(Times.parseDuration("d")).isNull();
        assertThat(Times.parseDuration("abc")).isNull();
        assertThat(Times.parseDuration("30x")).isNull();
        assertThat(Times.parseDuration("0d")).isNull();
        assertThat(Times.parseDuration("-5d")).isNull();
    }
}
