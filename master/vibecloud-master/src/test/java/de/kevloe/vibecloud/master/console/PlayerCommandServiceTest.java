package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.permission.PermissionNodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cloud-Befehle aus dem Spiel: Rechte und Vorschlaege.
 *
 * <p>Der Kern dessen, was hier schiefgehen kann: Ein Spieler mit dem Recht zu schauen darf
 * nicht eingreifen koennen, und was er nicht darf, soll ihm beim Tippen nicht angeboten
 * werden.
 */
class PlayerCommandServiceTest {

    private static final UUID SPIELER = UUID.randomUUID();

    private CommandRegistry registry;
    private Set<String> rechte;
    private List<String> protokoll;
    private PlayerCommandService service;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        rechte = new HashSet<>();
        protokoll = new ArrayList<>();

        registry.register(new TestCommand("server",
                List.of("list", "start", "stop", "kill"), true));
        registry.register(new TestCommand("ban", List.of(), true) {

            @Override
            public String permission() {
                // Wie ein Modul: eigenes Recht statt des abgeleiteten Schemas.
                return "vibecloud.punishment.ban";
            }
        });
        registry.register(new TestCommand("stop", List.of(), false));

        service = new PlayerCommandService(registry,
                // Derselbe Vergleich wie im Betrieb, damit "vibecloud.*" und
                // "vibecloud.command.server.*" hier genauso wirken wie echt.
                (uuid, node) -> rechte.stream()
                        .anyMatch(rule -> PermissionNodes.matches(rule, node)),
                (actor, command, args) -> protokoll.add(actor + ": " + command + " " + args));
    }

    // ---------------------------------------------------------------- Rechte

    @Test
    @DisplayName("Das Recht gilt pro Unterbefehl")
    void rechtGiltProUnterbefehl() {
        rechte.add("vibecloud.command.server.list");

        assertThat(run("server", "list").allowed()).isTrue();
        assertThat(run("server", "kill", "lobby-1").allowed()).isFalse();
    }

    @Test
    @DisplayName("Ein Wildcard deckt alle Unterbefehle ab")
    void wildcardDecktAllesAb() {
        rechte.add("vibecloud.command.server.*");

        assertThat(run("server", "list").allowed()).isTrue();
        assertThat(run("server", "kill", "lobby-1").allowed()).isTrue();
    }

    @Test
    @DisplayName("Der Befehlsknoten allein erlaubt noch keinen Unterbefehl")
    void befehlsknotenAlleinErlaubtKeinenUnterbefehl() {
        // So ist die Regel in M4 festgelegt: "a.b" deckt "a.b.c" nicht ab, dafuer gibt es
        // "a.b.*". Wer alles darf, bekommt vibecloud.* - das ist der Rang admin.
        rechte.add("vibecloud.command.server");

        assertThat(run("server", "kill", "lobby-1").allowed()).isFalse();
        assertThat(run("server").allowed()).isTrue();
    }

    @Test
    @DisplayName("vibecloud.* deckt auch die Befehle ab")
    void sternDecktAllesAb() {
        rechte.add("vibecloud.*");

        assertThat(run("server", "kill", "lobby-1").allowed()).isTrue();
        assertThat(run("ban", "Kevin", "hacking").allowed()).isTrue();
        // Nur die Konsolen-Befehle bleiben aussen vor.
        assertThat(run("stop").allowed()).isFalse();
    }

    @Test
    @DisplayName("Ohne jedes Recht geht nichts")
    void ohneRechtGehtNichts() {
        assertThat(run("server", "list").allowed()).isFalse();
        assertThat(run("server").allowed()).isFalse();
        assertThat(protokoll).isEmpty();
    }

    @Test
    @DisplayName("Ein Modul-Befehl haengt an seinem eigenen Recht")
    void modulBefehlHaengtAnSeinemRecht() {
        // Nicht vibecloud.command.ban - das Modul hat vibecloud.punishment.ban angemeldet.
        rechte.add("vibecloud.command.ban");
        assertThat(run("ban", "Kevin", "hacking").allowed()).isFalse();

        rechte.add("vibecloud.punishment.ban");
        assertThat(run("ban", "Kevin", "hacking").allowed()).isTrue();
    }

    @Test
    @DisplayName("Wer einen Unterbefehl darf, sieht die Syntax")
    void werEinenUnterbefehlDarfSiehtDieSyntax() {
        // "/server" ohne Argumente zeigt nur, wie es geht - das soll kein eigenes
        // Recht brauchen.
        rechte.add("vibecloud.command.server.list");

        assertThat(run("server").allowed()).isTrue();
    }

    @Test
    @DisplayName("Befehle nur fuer die Konsole sind im Spiel nicht erreichbar")
    void konsolenBefehleSindImSpielNichtErreichbar() {
        // Selbst mit dem Recht: 'stop' faehrt den Master herunter und sperrt damit alle
        // Logins aus (PLAN.md 17.4).
        rechte.add("vibecloud.command.stop");

        assertThat(run("stop").allowed()).isFalse();
        assertThat(service.inGameCommands()).extracting(CommandRegistry.Command::name)
                .containsExactly("server", "ban")
                .doesNotContain("stop");
    }

    @Test
    @DisplayName("Ein unbekannter Befehl wird wie ein fehlendes Recht behandelt")
    void unbekannterBefehlWirdWieFehlendesRechtBehandelt() {
        // Sonst waere die Fehlermeldung eine Auskunft darueber, welche Befehle es gibt.
        assertThat(run("gibtesnicht").allowed()).isFalse();
    }

    // ---------------------------------------------------------------- Protokoll

    @Test
    @DisplayName("Ein Eingriff aus dem Spiel wird protokolliert")
    void eingriffWirdProtokolliert() {
        rechte.add("vibecloud.command.server.*");

        run("server", "stop", "lobby-1");

        assertThat(protokoll).containsExactly("Kevin: server stop lobby-1");
    }

    // ---------------------------------------------------------------- Vorschlaege

    @Test
    @DisplayName("Vorgeschlagen wird nur, was der Spieler darf")
    void vorgeschlagenWirdNurWasErlaubtIst() {
        rechte.add("vibecloud.command.server.list");
        rechte.add("vibecloud.command.server.start");

        assertThat(service.suggest(SPIELER, "server", List.of("")))
                .containsExactly("list", "start");
    }

    @Test
    @DisplayName("Vorschlaege beachten den angefangenen Text")
    void vorschlaegeBeachtenDenAngefangenenText() {
        rechte.add("vibecloud.command.server.*");

        assertThat(service.suggest(SPIELER, "server", List.of("st")))
                .containsExactly("start", "stop");
    }

    @Test
    @DisplayName("Ohne Recht gibt es keine Vorschlaege")
    void ohneRechtGibtEsKeineVorschlaege() {
        // Sonst waere die Vervollstaendigung eine Aufzaehlung aller Befehle der Cloud.
        assertThat(service.suggest(SPIELER, "server", List.of(""))).isEmpty();
    }

    @Test
    @DisplayName("Fuer Konsolen-Befehle gibt es keine Vorschlaege")
    void fuerKonsolenBefehleGibtEsKeineVorschlaege() {
        rechte.add("vibecloud.command.stop");

        assertThat(service.suggest(SPIELER, "stop", List.of(""))).isEmpty();
    }

    @Test
    @DisplayName("Vorschlaege hinter dem Unterbefehl werden nicht gefiltert")
    void vorschlaegeHinterDemUnterbefehlWerdenNichtGefiltert() {
        // An der zweiten Stelle stehen Servernamen, keine Unterbefehle - die duerfen
        // nicht gegen Rechte geprueft werden.
        rechte.add("vibecloud.command.server.*");

        assertThat(service.suggest(SPIELER, "server", List.of("stop", "")))
                .containsExactly("lobby-1", "lobby-2");
    }

    private PlayerCommandService.Result run(String command, String... args) {
        return service.run(SPIELER, "Kevin", command, List.of(args));
    }

    /** Ein Befehl, der nur mitschreibt, was mit ihm geschieht. */
    private static class TestCommand implements CommandRegistry.Command {

        private final String name;
        private final List<String> subs;
        private final boolean inGame;

        TestCommand(String name, List<String> subs, boolean inGame) {
            this.name = name;
            this.subs = subs;
            this.inGame = inGame;
        }

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
        public List<String> subCommands() {
            return subs;
        }

        @Override
        public boolean availableInGame() {
            return inGame;
        }

        @Override
        public List<String> complete(List<String> args) {
            // Erste Stelle: Unterbefehle. Zweite: etwas, das keine Unterbefehle sind.
            return args.size() <= 1 ? subs : List.of("lobby-1", "lobby-2");
        }

        @Override
        public void execute(CommandOutput out, List<String> args) {
            out.info("ausgefuehrt");
        }
    }
}
