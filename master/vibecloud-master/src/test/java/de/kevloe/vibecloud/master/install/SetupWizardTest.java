package de.kevloe.vibecloud.master.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Fragen der ersten Einrichtung, ohne Konsole und ohne Datenbank. */
class SetupWizardTest {

    /** Antworten der Reihe nach; eine leere Zeile nimmt die Vorgabe. */
    private static final class Answers implements SetupWizard.Prompt {
        private final Deque<String> answers;
        final List<String> said = new ArrayList<>();

        Answers(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public String ask(String question, String defaultValue) {
            String answer = answers.pop();
            return answer.isEmpty() ? defaultValue : answer;
        }

        @Override
        public String askSecret(String question) {
            return answers.pop();
        }

        @Override
        public void say(String line) {
            said.add(line);
        }
    }

    @Test
    @DisplayName("Vorgaben mit Enter, Passwort Pflicht, Ergebnis wie eingegeben")
    void vorgaben() {
        Answers prompt = new Answers(
                "db.local", "", "", "", "", "geheim",  // Host, Port, DB, Benutzer, 2x Passwort
                "",                                    // gRPC-Port
                "", "9090",                            // Dashboard an, Port
                "n");                                  // keine Standard-Gruppen

        SetupWizard.Result result = new SetupWizard(database -> Optional.empty()).run(prompt);

        assertThat(result.aborted()).isFalse();
        assertThat(result.config().database.host).isEqualTo("db.local");
        assertThat(result.config().database.port).isEqualTo(5432);
        assertThat(result.config().database.password).isEqualTo("geheim");
        assertThat(result.config().grpc.port).isEqualTo(5000);
        assertThat(result.config().http.enabled).isTrue();
        assertThat(result.config().http.port).isEqualTo(9090);
        assertThat(result.createDefaultGroups()).isFalse();
        assertThat(prompt.said).contains("  Das Passwort darf nicht leer sein.");
    }

    @Test
    @DisplayName("Keine Verbindung: erneut fragen, und ein Nein bricht ohne Ergebnis ab")
    void keineVerbindung() {
        AtomicInteger attempts = new AtomicInteger();
        SetupWizard wizard = new SetupWizard(database -> attempts.incrementAndGet() == 1
                ? Optional.of("password authentication failed")
                : Optional.empty());

        SetupWizard.Result result = wizard.run(new Answers(
                "", "", "", "", "falsch", "",          // erster Versuch, dann "nochmal"
                "", "", "", "", "richtig",             // zweiter Versuch klappt
                "abc", "5001",                         // ungueltiger Port, dann gueltig
                "nein",                                // kein Dashboard
                ""));                                  // Standard-Gruppen: ja
        assertThat(result.config().database.password).isEqualTo("richtig");
        assertThat(result.config().grpc.port).isEqualTo(5001);
        assertThat(result.config().http.enabled).isFalse();
        assertThat(result.createDefaultGroups()).isTrue();

        SetupWizard failing = new SetupWizard(database -> Optional.of("Verbindung abgelehnt"));
        assertThat(failing.run(new Answers("", "", "", "", "x", "n"))).isNull();
    }
}
