package de.kevloe.vibecloud.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Chat-Format fuer {@code AsyncPlayerChatEvent}.
 *
 * <p>Bukkit setzt das Format mit {@code String.format(format, name, nachricht)} zusammen.
 * Ein falsches Format wirft dort - und dann schreibt im ganzen Server niemand mehr.
 */
class LegacyTextTest {

    @Test
    @DisplayName("Name und Nachricht werden zu %1$s und %2$s")
    void platzhalter() {
        String format = LegacyText.chatFormat("<prefix><name><suffix>: <message>",
                "[Admin] ", "");

        assertThat(String.format(format, "Steve", "hallo")).isEqualTo("[Admin] Steve: hallo");
    }

    @Test
    @DisplayName("Ein Prozentzeichen im Prefix bricht String.format nicht")
    void prozent() {
        String format = LegacyText.chatFormat("<prefix><name>: <message>", "100% ", "");

        assertThat(String.format(format, "Steve", "hallo")).isEqualTo("100% Steve: hallo");
    }

    @Test
    @DisplayName("Farben werden zu Farbcodes, nicht zu Text")
    void farben() {
        String format = LegacyText.chatFormat("<prefix><name>: <message>", "<red>[A]</red> ", "");

        assertThat(format).doesNotContain("<red>").contains("[A]");
        assertThat(String.format(format, "Steve", "hallo")).endsWith("Steve: hallo");
    }

    @Test
    @DisplayName("Ein Spielername wird nicht als MiniMessage gelesen")
    void nameUnverarbeitet() {
        assertThat(LegacyText.nameLine("", "<red>Steve", "")).contains("<red>Steve");
    }
}
