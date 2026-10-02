package de.kevloe.vibecloud.master.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Logback-Appender, der seine Zeilen durch das Terminal schreibt.
 *
 * <p>Der Grund: Ein {@code ConsoleAppender} schreibt direkt nach {@code System.out} und
 * ueberschreibt dabei die Eingabezeile. Nach jeder Log-Meldung waren Prompt und halb
 * getippter Befehl weg - sichtbar bleibt nur noch die Meldung. Hier gehen die Zeilen
 * stattdessen an {@link org.jline.reader.LineReader#printAbove}, das ueber der Eingabezeile
 * ausgibt und sie danach neu zeichnet.
 *
 * <p>Eingetragen in {@code logback.xml}. Solange sich kein Terminal angemeldet hat - beim
 * Start, oder wenn der Master als Dienst ohne Konsole laeuft - geht alles wie gewohnt nach
 * {@code System.out}.
 *
 * <p>Das ist die eine Stelle, an der der Master Logback direkt kennt statt nur SLF4J. Eine
 * Appender-Implementierung geht nicht anders; alles andere loggt weiter ueber SLF4J.
 */
public final class TerminalAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /**
     * Wohin die Zeilen gehen.
     *
     * <p>Statisch, weil Logback den Appender selbst erzeugt - das Terminal kann ihn nicht
     * im Konstruktor bekommen. {@code volatile}, weil Logger-Aufrufe aus jedem Thread
     * kommen.
     */
    private static volatile Consumer<String> sink;

    private Encoder<ILoggingEvent> encoder;

    /** Leitet alle weiteren Zeilen an das Terminal. */
    public static void redirectTo(Consumer<String> target) {
        sink = target;
    }

    /** Zurueck nach {@code System.out} - beim Herunterfahren des Terminals. */
    public static void reset() {
        sink = null;
    }

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    public Encoder<ILoggingEvent> getEncoder() {
        return encoder;
    }

    @Override
    public void start() {
        if (encoder == null) {
            addError("Kein <encoder> fuer " + getName() + " konfiguriert");
            return;
        }
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) {
            return;
        }
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        Consumer<String> target = sink;
        if (target == null) {
            System.out.print(line);
            System.out.flush();
            return;
        }
        try {
            // Ohne Zeilenumbruch: Den setzt printAbove selbst. Mit waere nach jeder
            // Meldung eine Leerzeile.
            target.accept(stripNewline(line));
        } catch (RuntimeException exception) {
            // Ein kaputtes Terminal darf das Logging nicht mitnehmen - dann lieber
            // unformatiert weiter als still verlieren.
            System.out.print(line);
            System.out.flush();
        }
    }

    private static String stripNewline(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }
}
