package de.kevloe.vibecloud.master.console;

import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Interaktive Konsole des Masters (PLAN.md Abschnitt 12).
 *
 * <p>Ausgaben laufen ueber ANSI-Farben und Log-Level, nicht ueber Minecraft-Farbcodes -
 * das war eine der Altlasten des Vorgaengerprojekts.
 */
public final class CloudTerminal implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CloudTerminal.class);

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String GREY = "\u001B[90m";

    private final CommandRegistry commands;
    private final ScreenCommand.AttachmentState attachment;
    private final Terminal terminal;
    private final LineReader reader;
    private final CommandOutput output;

    private volatile boolean running = true;
    private volatile boolean stopRequested;

    public CloudTerminal(CommandRegistry commands, ScreenCommand.AttachmentState attachment)
            throws IOException {
        this.commands = commands;
        this.attachment = attachment;
        this.terminal = openTerminal();
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer((lineReader, parsedLine, candidates) -> complete(parsedLine.words(),
                        parsedLine.wordIndex(), candidates))
                .build();
        this.output = new TerminalOutput();

        // Ab jetzt gehen auch Log-Meldungen durch den LineReader - sonst ueberschreibt
        // die erste Meldung Prompt und halb getippten Befehl.
        TerminalAppender.redirectTo(this::writeAbovePrompt);
    }

    /**
     * Oeffnet das Terminal des Systems, faellt notfalls auf ein einfaches zurueck.
     *
     * <p>Das System-Terminal kann den Cursor bewegen - erst damit kann der LineReader die
     * Eingabezeile neu zeichnen, nachdem eine Log-Meldung darueber ausgegeben wurde, und
     * erst damit funktioniert die Tab-Vervollstaendigung.
     *
     * <p>Ohne Konsole - als Dienst, oder mit umgeleiteter Eingabe - gibt es kein
     * System-Terminal. Dann ist ein einfaches Terminal richtig: Es kann weniger, laeuft
     * aber ueberall.
     */
    private static Terminal openTerminal() throws IOException {
        try {
            return TerminalBuilder.builder().system(true).build();
        } catch (IOException | RuntimeException exception) {
            LOG.debug("Kein System-Terminal verfuegbar, es gilt ein einfaches", exception);
            return TerminalBuilder.builder().dumb(true).build();
        }
    }

    /**
     * Gibt eine Zeile ueber der Eingabezeile aus.
     *
     * <p>{@code synchronized}, weil Log-Meldungen aus beliebigen Threads kommen und sich
     * sonst mit der Ausgabe eines Befehls verschraenken koennten.
     */
    private synchronized void writeAbovePrompt(String message) {
        if (reader.isReading()) {
            reader.printAbove(message);
        } else {
            terminal.writer().println(message);
            terminal.writer().flush();
        }
    }

    /** Blockiert bis {@code stop} eingegeben wird oder der Eingabestrom endet. */
    public void runUntilStopped() {
        while (running) {
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException exception) {
                // Strg+C: nicht sofort beenden, sondern den klaren Weg nennen.
                output.info("Zum Beenden 'stop' eingeben.");
                continue;
            } catch (EndOfFileException exception) {
                // Strg+D oder kein Terminal (z. B. als systemd-Dienst ohne tty).
                // Kein Grund zum Beenden - der Aufrufer wartet dann auf SIGTERM.
                return;
            }
            if (line == null || line.isBlank()) {
                continue;
            }
            dispatch(line.trim());
        }
    }

    /** Zeigt an, wenn die Konsole an einem Server haengt. */
    private String prompt() {
        return attachment.isAttached() ? attachment.attachedServer() + "> " : "vibecloud> ";
    }

    public void dispatch(String line) {
        // Solange die Konsole an einem Server haengt, gehen Eingaben dorthin.
        // '/detach' loest wieder - sonst waere man an einem haengenden Server gefangen.
        if (attachment.isAttached()) {
            if (line.equals("/detach")) {
                String server = attachment.attachedServer();
                attachment.detach();
                output.success("Von " + server + " geloest.");
                return;
            }
            if (!attachment.forward(line)) {
                output.error("Weiterleiten fehlgeschlagen - Server oder Node nicht erreichbar. "
                             + "Loesen mit /detach");
            }
            return;
        }

        List<String> parts = new ArrayList<>(Arrays.asList(line.split("\\s+")));
        String name = parts.removeFirst();

        Optional<CommandRegistry.Command> command = commands.find(name);
        if (command.isEmpty()) {
            suggest(name);
            return;
        }
        try {
            command.get().execute(CommandRegistry.CommandActor.console(), output, parts);
        } catch (RuntimeException exception) {
            // Ein fehlerhafter Befehl darf die Konsole nicht beenden.
            output.error("Befehl fehlgeschlagen: " + exception.getMessage());
            LOG.debug("Befehl '{}' fehlgeschlagen", line, exception);
        }
    }

    /**
     * Zeigt, was gemeint sein koennte.
     *
     * <p>"un" allein ist kein Befehl, aber es gibt nur zwei, die so anfangen. Die
     * hinzuschreiben ist nuetzlicher als auf 'help' mit zwanzig Zeilen zu verweisen.
     *
     * <p>Bewusst wird <b>nicht</b> der einzige Treffer einfach ausgefuehrt: Unter den
     * Befehlen stehen 'ban' und 'stop' - eine Abkuerzung, die der Betreiber nicht genau so
     * gemeint hat, waere hier teuer.
     */
    private void suggest(String name) {
        List<String> matches = commands.completeNames(name);

        if (matches.isEmpty()) {
            output.error("Unbekannter Befehl: " + name + " - 'help' zeigt alle Befehle.");
            return;
        }
        output.error("Unbekannter Befehl: " + name);
        output.info(matches.size() == 1 ? "Gemeint ist vermutlich:" : "Das passt dazu:");

        for (String match : matches) {
            commands.find(match).ifPresent(found -> output.info(
                    String.format("  %-12s %s", found.name(), found.description())));
        }
    }

    public void stop() {
        stopRequested = true;
        running = false;
    }

    /** Unterscheidet 'stop eingegeben' von 'stdin zu Ende'. */
    public boolean wasStopRequested() {
        return stopRequested;
    }

    public CommandOutput output() {
        return output;
    }

    private void complete(List<String> words, int index, List<Candidate> candidates) {
        if (index == 0) {
            String prefix = words.isEmpty() ? "" : words.getFirst();
            commands.completeNames(prefix).forEach(name -> candidates.add(new Candidate(name)));
            return;
        }
        commands.find(words.getFirst()).ifPresent(command -> {
            List<String> args = words.subList(1, words.size());
            command.complete(args).forEach(value -> candidates.add(new Candidate(value)));
        });
    }

    @Override
    public void close() throws IOException {
        running = false;
        // Erst die Umleitung loesen: Danach darf kein Logger mehr in ein geschlossenes
        // Terminal schreiben.
        TerminalAppender.reset();
        terminal.close();
    }

    /** Schreibt direkt ins Terminal, damit die Ausgabe nicht mit Log-Zeilen verschachtelt. */
    private final class TerminalOutput implements CommandOutput {

        @Override
        public void info(String message) {
            write(message);
        }

        @Override
        public void success(String message) {
            write(GREEN + message + RESET);
        }

        @Override
        public void warn(String message) {
            write(YELLOW + message + RESET);
        }

        @Override
        public void error(String message) {
            write(RED + message + RESET);
        }

        private void write(String message) {
            writeAbovePrompt(message);
        }
    }

    /** Eingebauter {@code help}-Befehl. */
    public static CommandRegistry.Command helpCommand(CommandRegistry registry) {
        return new CommandRegistry.Command() {

            @Override
            public String name() {
                return "help";
            }

            @Override
            public String description() {
                return "Zeigt alle Befehle";
            }

            @Override
            public String usage() {
                return "help";
            }

            /**
             * Nicht im Spiel: Dort wuerde es das {@code /help} des Gameservers
             * verdecken. Im Spiel listet {@code /cloudhelp} am Proxy, was der Spieler
             * darf - der Proxy kennt dessen Rechte und kann die Liste filtern.
             */
            @Override
            public boolean availableInGame() {
                return false;
            }

            @Override
            public void execute(CommandOutput out, List<String> args) {
                out.info("Verfuegbare Befehle:");
                for (CommandRegistry.Command command : registry.all()) {
                    out.info(String.format("  %-10s %s", command.name(), command.description()));
                    out.info(GREY + "             " + command.usage() + RESET);
                }
            }
        };
    }
}
