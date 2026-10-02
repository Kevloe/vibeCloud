package de.kevloe.vibecloud.master.console;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Live-Konsole der laufenden Server (PLAN.md Abschnitt 7 und 12).
 *
 * <p>Nur ein Ringpuffer der letzten Zeilen je Server, absichtlich klein. Konsolenzeilen sind
 * Momentaufnahmen und keine Fakten: Sie werden nicht gepuffert, nicht nachgereicht und nicht
 * in der Datenbank gespeichert. Wer den vollen Verlauf will, nimmt den hochgeladenen Log.
 *
 * <p>{@code screen <server>} haengt sich hier ein und bekommt zuerst den Puffer, danach
 * die neuen Zeilen.
 */
public final class ConsoleBuffer {

    private static final int LINES_PER_SERVER = 500;

    private final Map<String, Deque<String>> buffers = new ConcurrentHashMap<>();
    private final Map<String, Set<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    public void append(String serverName, String line) {
        Deque<String> buffer = buffers.computeIfAbsent(serverName, key -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(line);
            while (buffer.size() > LINES_PER_SERVER) {
                buffer.removeFirst();
            }
        }
        Set<Consumer<String>> targets = listeners.get(serverName);
        if (targets != null) {
            // Kopie, damit ein Abmelden waehrend der Verteilung nichts kaputt macht.
            Set.copyOf(targets).forEach(target -> target.accept(line));
        }
    }

    public List<String> history(String serverName) {
        Deque<String> buffer = buffers.get(serverName);
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
    }

    /** @return Abmelde-Funktion */
    public Runnable attach(String serverName, Consumer<String> listener) {
        listeners.computeIfAbsent(serverName, key -> ConcurrentHashMap.newKeySet()).add(listener);
        return () -> {
            Set<Consumer<String>> targets = listeners.get(serverName);
            if (targets != null) {
                targets.remove(listener);
            }
        };
    }

    /** Beim Entfernen eines Servers aufraeumen, damit der Puffer nicht ewig waechst. */
    public void forget(String serverName) {
        buffers.remove(serverName);
        listeners.remove(serverName);
    }
}
