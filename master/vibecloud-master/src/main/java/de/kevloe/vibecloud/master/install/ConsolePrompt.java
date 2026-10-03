package de.kevloe.vibecloud.master.install;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.util.Optional;

/**
 * Der Assistent an der echten Konsole.
 *
 * <p>Ein eigenes Terminal, das vor dem der Cloud-Konsole wieder geschlossen wird: Diese
 * hat Tab-Vervollstaendigung fuer Befehle und leitet das Log um - beides waere hier fehl
 * am Platz, und die Befehle gibt es vor der Datenbank noch gar nicht.
 */
final class ConsolePrompt implements SetupWizard.Prompt, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConsolePrompt.class);

    /** Bricht den Assistenten ab - Strg+C oder Strg+D. */
    static final class Aborted extends RuntimeException {
        Aborted() {
            super("Einrichtung abgebrochen", null, false, false);
        }
    }

    private final Terminal terminal;
    private final LineReader reader;

    private ConsolePrompt(Terminal terminal) {
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    /**
     * @return leer, wenn niemand an der Konsole sitzt - als Dienst, mit umgeleiteter
     *         Eingabe. Dann soll nichts auf eine Antwort warten.
     */
    static Optional<ConsolePrompt> open() {
        Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return Optional.empty();
        }
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            if (terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
                terminal.close();
                return Optional.empty();
            }
            return Optional.of(new ConsolePrompt(terminal));
        } catch (IOException | RuntimeException exception) {
            LOG.debug("Kein System-Terminal fuer den Assistenten", exception);
            return Optional.empty();
        }
    }

    @Override
    public String ask(String question, String defaultValue) {
        String suffix = defaultValue == null || defaultValue.isEmpty()
                ? ": " : " [" + defaultValue + "]: ";
        String answer = read(question + suffix, null);
        return answer.isBlank() && defaultValue != null ? defaultValue : answer.strip();
    }

    @Override
    public String askSecret(String question) {
        return read(question + ": ", '*');
    }

    @Override
    public void say(String line) {
        terminal.writer().println(line);
        terminal.writer().flush();
    }

    private String read(String prompt, Character mask) {
        try {
            return reader.readLine(prompt, mask);
        } catch (UserInterruptException | EndOfFileException exception) {
            throw new Aborted();
        }
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }
}
