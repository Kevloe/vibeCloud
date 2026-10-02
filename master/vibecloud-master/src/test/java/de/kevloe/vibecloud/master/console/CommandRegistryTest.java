package de.kevloe.vibecloud.master.console;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Namenssuche fuer Tab-Vervollstaendigung und Vorschlaege. */
class CommandRegistryTest {

    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        // Reihenfolge wie im Betrieb: Module registrieren nach dem Core.
        for (String name : List.of("stop", "server", "node", "rank", "perm", "help",
                "unban", "unmute", "tempban", "ban", "mute")) {
            registry.register(command(name));
        }
    }

    @Test
    @DisplayName("Ein Praefix findet alle Befehle, die so anfangen")
    void praefixFindetAlleBefehle() {
        // Der Fall aus der Praxis: "un" allein ist kein Befehl, soll aber weiterhelfen.
        assertThat(registry.completeNames("un")).containsExactly("unban", "unmute");
        assertThat(registry.completeNames("s")).containsExactly("server", "stop");
    }

    @Test
    @DisplayName("Treffer kommen alphabetisch, nicht in Registrierungsreihenfolge")
    void trefferKommenAlphabetisch() {
        // Module melden sich spaeter an als der Core - unsortiert stuenden sie immer hinten.
        assertThat(registry.completeNames("")).isSorted();
        assertThat(registry.completeNames("t")).containsExactly("tempban");
    }

    @Test
    @DisplayName("Ohne Praefix-Treffer wird im Namen gesucht")
    void ohnePraefixTrefferWirdImNamenGesucht() {
        // "ban" trifft als Praefix nur "ban" selbst - aber gemeint ist oft etwas anderes.
        assertThat(registry.completeNames("ban")).containsExactly("ban");
        assertThat(registry.completeNames("mute")).containsExactly("mute");
        assertThat(registry.completeNames("emp")).containsExactly("tempban");
        // "nba" steckt mitten in "unban" - das ist gewollt, es hilft bei Vertippern.
        assertThat(registry.completeNames("nba")).containsExactly("unban");
        assertThat(registry.completeNames("xyz")).isEmpty();
    }

    @Test
    @DisplayName("Gross- und Kleinschreibung ist gleichgueltig")
    void grossKleinschreibungIstGleichgueltig() {
        assertThat(registry.completeNames("UN")).containsExactly("unban", "unmute");
        assertThat(registry.find("STOP")).isPresent();
    }

    @Test
    @DisplayName("Ohne Eingabe kommen alle Befehle")
    void ohneEingabeKommenAlleBefehle() {
        assertThat(registry.completeNames("")).hasSize(11);
    }

    @Test
    @DisplayName("Ein abgemeldeter Befehl wird nicht mehr vorgeschlagen")
    void abgemeldeterBefehlWirdNichtVorgeschlagen() {
        // Nach dem Entladen eines Moduls: Der Befehl zeigte sonst auf einen
        // geschlossenen ClassLoader.
        assertThat(registry.unregister("unban")).isTrue();

        assertThat(registry.completeNames("un")).containsExactly("unmute");
        assertThat(registry.find("unban")).isEmpty();
    }

    private static CommandRegistry.Command command(String name) {
        return new CommandRegistry.Command() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Test";
            }

            @Override
            public String usage() {
                return name;
            }

            @Override
            public void execute(CommandOutput out, List<String> args) {
            }
        };
    }
}
