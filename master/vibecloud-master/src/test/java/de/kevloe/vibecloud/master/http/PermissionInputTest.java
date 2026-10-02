package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.api.permission.PermissionContext;
import de.kevloe.vibecloud.api.permission.PermissionNodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Was der Rechte-Editor annimmt und was er ablehnt.
 *
 * <p>Der Grund fuer die Pruefung: Ein Knoten, der nie trifft, faellt nicht als Fehler auf.
 * Die Regel steht in der Datenbank, wird bei jeder Abfrage mitgeladen und entscheidet nie
 * etwas - im Dashboard sieht sie aus, als waere das Recht vergeben. Genau das soll die
 * Schnittstelle verhindern, statt es dem Betreiber zu ueberlassen, es nach zwei Wochen
 * selbst zu merken.
 */
class PermissionInputTest {

    @Test
    void gewoehnlicheKnotenGehenDurch() {
        assertThat(PermissionRoutes.rejectNode("vibecloud.command.server.stop")).isEmpty();
        assertThat(PermissionRoutes.rejectNode("vibecloud.punishment.ban")).isEmpty();
        assertThat(PermissionRoutes.rejectNode("worldedit")).isEmpty();
        assertThat(PermissionRoutes.rejectNode("my-plugin.use_it")).isEmpty();
    }

    @Test
    void wildcardsNurAmEnde() {
        assertThat(PermissionRoutes.rejectNode("*")).isEmpty();
        assertThat(PermissionRoutes.rejectNode("vibecloud.*")).isEmpty();
        assertThat(PermissionRoutes.rejectNode("vibecloud.command.server.*")).isEmpty();
    }

    /**
     * {@code vibecloud.*.stop} sieht aus wie ein Wildcard, ist aber keiner - der Stern wird
     * als gewoehnliches Zeichen verglichen. Dieser Test haelt beides zusammen: die Ablehnung
     * und den Grund dafuer.
     */
    @Test
    void sternInDerMitteWirdAbgelehntUndWuerdeNichtsTreffen() {
        assertThat(PermissionNodes.matches("vibecloud.*.stop",
                "vibecloud.command.stop")).isFalse();
        assertThat(PermissionRoutes.rejectNode("vibecloud.*.stop")).isPresent();
    }

    @Test
    void unsinnWirdAbgelehnt() {
        assertThat(PermissionRoutes.rejectNode("")).isPresent();
        assertThat(PermissionRoutes.rejectNode("   ")).isPresent();
        assertThat(PermissionRoutes.rejectNode("mit leerzeichen")).isPresent();
        assertThat(PermissionRoutes.rejectNode("vibecloud..command")).isPresent();
        assertThat(PermissionRoutes.rejectNode(".vibecloud")).isPresent();
        assertThat(PermissionRoutes.rejectNode("vibecloud.")).isPresent();
        assertThat(PermissionRoutes.rejectNode("vibecloud:stop")).isPresent();
    }

    /**
     * Grossbuchstaben sind erlaubt, werden aber vorher kleingeschrieben - so wie beim
     * Speichern. Ohne das fuende das Entfernen seine eigene Regel nicht wieder.
     */
    @Test
    void grossbuchstabenWerdenKleingeschrieben() {
        assertThat(PermissionRoutes.normalize("  VibeCloud.Command.Stop "))
                .isEqualTo("vibecloud.command.stop");
        assertThat(PermissionRoutes.rejectNode(PermissionRoutes.normalize("VIBECLOUD.*")))
                .isEmpty();
        assertThat(PermissionRoutes.rejectNode("VIBECLOUD.stop")).isPresent();
    }

    @Test
    void leererKontextIstGlobal() {
        assertThat(PermissionRoutes.contextOf(null, null)).isEqualTo(PermissionContext.GLOBAL);
        assertThat(PermissionRoutes.contextOf("", "  ")).isEqualTo(PermissionContext.GLOBAL);
    }

    @Test
    void gruppeUndServerWerdenUebernommen() {
        assertThat(PermissionRoutes.contextOf("bedwars", null).group()).isEqualTo("bedwars");
        assertThat(PermissionRoutes.contextOf(" lobby ", null).group()).isEqualTo("lobby");

        // Ein Server ohne Gruppe ist erlaubt: Eine Regel fuer lobby-1 braucht nicht zu
        // wissen, in welcher Gruppe der Server liegt.
        PermissionContext onlyServer = PermissionRoutes.contextOf(null, "lobby-1");
        assertThat(onlyServer.server()).isEqualTo("lobby-1");
        assertThat(onlyServer.specificity()).isEqualTo(2);
    }
}
