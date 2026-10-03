package de.kevloe.vibecloud.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftVersionTest {

    private static MinecraftVersion v(String text) {
        return MinecraftVersion.of(text);
    }

    @Test
    @DisplayName("Verglichen wird als Zahl, nicht als Text")
    void zahlNichtText() {
        // Als Text waere 1.9 groesser als 1.16 - genau verkehrt.
        assertThat(v("1.9.4")).isLessThan(v("1.16.5"));
        assertThat(v("1.16.5")).isLessThan(v("1.21.11"));
    }

    @Test
    @DisplayName("Das neue Schema (26.x) liegt hinter dem alten (1.x)")
    void neuesSchema() {
        assertThat(v("1.21.11")).isLessThan(v("26.1"));
        assertThat(v("26.1.2")).isLessThan(v("26.2"));
    }

    @Test
    @DisplayName("Ein fehlender Teil zaehlt als 0")
    void fehlenderTeil() {
        assertThat(v("1.21").compareTo(v("1.21.0"))).isZero();
        assertThat(v("1.21")).isLessThan(v("1.21.1"));
    }

    @Test
    @DisplayName("Vorabversionen und Namen sind keine Release-Versionen")
    void keineVorabversionen() {
        assertThat(MinecraftVersion.parse("1.21.11-rc3")).isEmpty();
        assertThat(MinecraftVersion.parse("1.13-pre7")).isEmpty();
        assertThat(MinecraftVersion.parse("4.2.1-SNAPSHOT")).isEmpty();
        assertThat(MinecraftVersion.parse("latest")).isEmpty();
        assertThat(MinecraftVersion.parse("")).isEmpty();
        assertThat(MinecraftVersion.parse("1..2")).isEmpty();
        assertThatThrownBy(() -> MinecraftVersion.of("latest"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isAtLeast ist bei Unlesbarem falsch")
    void isAtLeast() {
        assertThat(MinecraftVersion.isAtLeast("1.16.1", v("1.16"))).isTrue();
        assertThat(MinecraftVersion.isAtLeast("1.15.2", v("1.16"))).isFalse();
        assertThat(MinecraftVersion.isAtLeast("latest", v("1.16"))).isFalse();
    }
}
