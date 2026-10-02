package de.kevloe.vibecloud.api.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Das Namensmuster wird beim Anlegen einer Gruppe geprueft, nicht erst beim Serverstart
 * (PLAN.md Entscheidung 16.2).
 *
 * <p>Der Unterschied ist wichtig: Ein ungueltiges Muster faellt sonst erst auf, wenn der
 * Scheduler alle drei Sekunden erfolglos einen Server zu starten versucht.
 */
class ServerGroupTest {

    @Test
    void musterOhneIdWirdAbgelehnt() {
        assertThat(ServerGroup.validateNamePattern("%group%"))
                .isNotNull()
                .contains("%id%");
    }

    @Test
    void gueltigeMusterWerdenAkzeptiert() {
        assertThat(ServerGroup.validateNamePattern("%group%-%id%")).isNull();
        assertThat(ServerGroup.validateNamePattern("%group%#%id%")).isNull();
        assertThat(ServerGroup.validateNamePattern("%group%_%id%")).isNull();
        assertThat(ServerGroup.validateNamePattern("%node%-%group%-%id%")).isNull();
    }

    @Test
    void unerlaubteZeichenWerdenAbgelehnt() {
        // Ein Leerzeichen im Servernamen bricht sowohl Velocity als auch Verzeichnisnamen.
        assertThat(ServerGroup.validateNamePattern("%group% %id%")).isNotNull();
        assertThat(ServerGroup.validateNamePattern("lobby/%id%")).isNotNull();
    }

    /** Velocity lehnt Servernamen ueber 32 Zeichen ab - das muss hier auffallen. */
    @Test
    void zuLangeMusterWerdenAbgelehnt() {
        String tooLong = "ein-sehr-langer-gruppenpraefix-%group%-%id%";

        assertThat(ServerGroup.validateNamePattern(tooLong))
                .isNotNull()
                .contains("32");
    }

    @Test
    void leeresMusterWirdAbgelehnt() {
        assertThat(ServerGroup.validateNamePattern(null)).isNotNull();
        assertThat(ServerGroup.validateNamePattern("  ")).isNotNull();
    }

    @Test
    void konstruktorLehntMusterOhneIdAb() {
        assertThatThrownBy(() -> group("%group%"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%id%");
    }

    @Test
    void konstruktorLehntUnmoeglicheOnlineGrenzenAb() {
        assertThatThrownBy(() -> new ServerGroup("lobby", ServerPlatformType.PAPER, false,
                5, 2, 100, 1024, List.of(), List.of(), 0, 300,
                "%group%-%id%", "lobby", "26.2", "paper", false, 100, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min_online");
    }

    @Test
    void leereNodeListeErlaubtJedenNode() {
        ServerGroup group = group("%group%-%id%");

        assertThat(group.allowsNode("node-a")).isTrue();
        assertThat(group.allowsNode("irgendwas")).isTrue();
    }

    @Test
    void gesetzteNodeListeSchliesstAndereAus() {
        ServerGroup group = new ServerGroup("bedwars", ServerPlatformType.PAPER, false,
                1, 2, 100, 1024, List.of(), List.of("node-b"), 0, 300,
                "%group%-%id%", "bedwars", "26.2", "paper", false, 100, false, 0);

        assertThat(group.allowsNode("node-b")).isTrue();
        assertThat(group.allowsNode("node-a")).isFalse();
    }

    private static ServerGroup group(String namePattern) {
        return new ServerGroup("lobby", ServerPlatformType.PAPER, false,
                1, 3, 50, 2048, List.of(), List.of(), 0, 300,
                namePattern, "lobby", "26.2", "paper", true, 10, false, 0);
    }
}
