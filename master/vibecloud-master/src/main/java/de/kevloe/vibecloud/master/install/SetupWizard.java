package de.kevloe.vibecloud.master.install;

import de.kevloe.vibecloud.master.config.MasterConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Erste Einrichtung in der Konsole, wenn es noch keine {@code config.json} gibt.
 *
 * <p>Vorher schrieb der Master eine Vorlage und beendete sich; man musste die Datei
 * finden, das Passwort der Datenbank hineinschreiben und wieder starten - und erfuhr erst
 * dann, ob es stimmte. Hier wird die Verbindung sofort geprueft, und die Datei entsteht
 * erst, wenn sie funktioniert.
 *
 * <p>Gefragt wird nur, was ohne Antwort nicht geht oder nach aussen wirkt: Datenbank,
 * gRPC-Port, Dashboard an oder aus. Alles andere hat brauchbare Vorgaben und steht danach
 * in der Datei und auf der Seite "Einstellungen".
 *
 * <p>Ohne interaktive Konsole (Dienst) laeuft das nicht - dort bleibt es beim Alten:
 * Vorlage schreiben, beenden.
 */
public final class SetupWizard {

    /** Ein- und Ausgabe - in Betrieb die Konsole, im Test eine Liste von Antworten. */
    public interface Prompt {
        /** @return die Eingabe, oder {@code defaultValue} bei einer leeren Zeile */
        String ask(String question, String defaultValue);

        /** Ohne Echo - fuer das Passwort. */
        String askSecret(String question);

        void say(String line);
    }

    /** Prueft die Zugangsdaten; leer heisst: Verbindung steht. */
    @FunctionalInterface
    public interface DatabaseCheck {
        Optional<String> problemWith(MasterConfig.Database database);
    }

    /**
     * @param createDefaultGroups ob {@code cloud setup} nach dem Start laufen soll -
     *                            ausgefuehrt wird es erst, wenn die Datenbank steht
     */
    public record Result(MasterConfig config, boolean createDefaultGroups) {

        /** Abgebrochen - es wird nichts geschrieben und der Master beendet sich. */
        static final Result ABORTED = new Result(null, false);

        public boolean aborted() {
            return config == null;
        }
    }

    /**
     * Fragt an der echten Konsole, falls dort jemand sitzt.
     *
     * @return leer ohne interaktive Konsole - dann gilt das alte Verhalten (Vorlage
     *         schreiben, beenden)
     */
    public static Optional<Result> atConsole() {
        Optional<ConsolePrompt> console = ConsolePrompt.open();
        if (console.isEmpty()) {
            return Optional.empty();
        }
        ConsolePrompt prompt = console.get();
        try {
            Result result = new SetupWizard(SetupWizard::connect).run(prompt);
            return Optional.of(result == null ? Result.ABORTED : result);
        } catch (ConsolePrompt.Aborted aborted) {
            prompt.say("");
            prompt.say("Abgebrochen - es wurde nichts gespeichert.");
            return Optional.of(Result.ABORTED);
        } finally {
            // Die Antworten sind da; ein Fehler beim Schliessen des Terminals aendert
            // daran nichts.
            try {
                prompt.close();
            } catch (java.io.IOException ignored) {
                // siehe oben
            }
        }
    }

    private final DatabaseCheck databaseCheck;

    public SetupWizard(DatabaseCheck databaseCheck) {
        this.databaseCheck = databaseCheck;
    }

    /** @return {@code null}, wenn abgebrochen wurde - dann wird nichts geschrieben */
    public Result run(Prompt prompt) {
        MasterConfig config = new MasterConfig();
        prompt.say("");
        prompt.say("Willkommen bei vibeCloud - es gibt noch keine config.json.");
        prompt.say("Ein paar Fragen, dann laeuft der Master. [Vorgabe] mit Enter uebernehmen.");
        prompt.say("");
        prompt.say("PostgreSQL (die Datenbank muss es schon geben):");

        MasterConfig.Database database = config.database;
        while (true) {
            database.host = prompt.ask("  Host", database.host);
            database.port = askPort(prompt, "  Port", database.port);
            database.database = prompt.ask("  Datenbank", database.database);
            database.user = prompt.ask("  Benutzer", database.user);
            database.password = askPassword(prompt);

            prompt.say("  Verbinde ...");
            Optional<String> problem = databaseCheck.problemWith(database);
            if (problem.isEmpty()) {
                prompt.say("  Verbindung steht.");
                break;
            }
            prompt.say("  Keine Verbindung: " + problem.get());
            if (!askYesNo(prompt, "  Noch einmal eingeben?", true)) {
                prompt.say("Abgebrochen - es wurde nichts gespeichert.");
                return null;
            }
        }

        prompt.say("");
        config.grpc.port = askPort(prompt,
                "Port fuer die Wrapper (gRPC, muss von den Roots erreichbar sein)",
                config.grpc.port);

        config.http.enabled = askYesNo(prompt, "Dashboard und REST-Schnittstelle einschalten?",
                true);
        if (config.http.enabled) {
            config.http.port = askPort(prompt, "  Port des Dashboards", config.http.port);
        }

        boolean groups = askYesNo(prompt,
                "Standard-Gruppen 'proxy' und 'lobby' anlegen, falls es noch keine gibt?",
                true);

        prompt.say("");
        prompt.say("Gespeichert wird in config.json. Weitere Einstellungen stehen dort "
                   + "und im Dashboard unter \"Einstellungen\".");
        return new Result(config, groups);
    }

    /** Ein leeres Passwort waere ein Datenbank-Zugang, den jeder im Netz erraet. */
    private static String askPassword(Prompt prompt) {
        while (true) {
            String password = prompt.askSecret("  Passwort");
            if (password != null && !password.isEmpty()) {
                return password;
            }
            prompt.say("  Das Passwort darf nicht leer sein.");
        }
    }

    private static int askPort(Prompt prompt, String question, int defaultValue) {
        while (true) {
            String answer = prompt.ask(question, Integer.toString(defaultValue));
            try {
                int port = Integer.parseInt(answer.strip());
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // Unten erklaert.
            }
            prompt.say("  Ein Port ist eine Zahl von 1 bis 65535.");
        }
    }

    private static boolean askYesNo(Prompt prompt, String question, boolean defaultValue) {
        while (true) {
            String answer = prompt.ask(question + (defaultValue ? " (J/n)" : " (j/N)"),
                    defaultValue ? "j" : "n").strip().toLowerCase(java.util.Locale.ROOT);
            switch (answer) {
                case "j", "ja", "y", "yes" -> {
                    return true;
                }
                case "n", "nein", "no" -> {
                    return false;
                }
                default -> prompt.say("  Bitte j oder n.");
            }
        }
    }

    /** Die echte Pruefung: eine Verbindung auf, gleich wieder zu. */
    public static Optional<String> connect(MasterConfig.Database database) {
        String url = "jdbc:postgresql://%s:%d/%s".formatted(
                database.host, database.port, database.database);
        DriverManager.setLoginTimeout(5);
        try (Connection _ = DriverManager.getConnection(url, database.user,
                database.password)) {
            return Optional.empty();
        } catch (SQLException exception) {
            return Optional.of(exception.getMessage());
        }
    }
}
