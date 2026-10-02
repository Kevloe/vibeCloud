package de.kevloe.vibecloud.api.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prozess-lokaler Event-Bus. Clusterweite Verteilung ueber Redis folgt in M4 und setzt
 * auf dieser Klasse auf - sie bleibt dann der lokale Teil der Kette.
 *
 * <p>Post-Events laufen auf Virtual Threads: Es gibt hier absichtlich keinen begrenzten
 * Thread-Pool, in dem ein haengender Handler die anderen aussperren koennte.
 */
public final class LocalEventBus implements EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(LocalEventBus.class);

    /** Pro Event-Typ die Handler, bereits nach Prioritaet sortiert. */
    private final Map<Class<?>, CopyOnWriteArrayList<Handler>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher;

    public LocalEventBus() {
        this.dispatcher = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("event-dispatch-", 0).factory());
    }

    @Override
    public void register(Object listener) {
        for (Method method : listener.getClass().getMethods()) {
            Subscribe annotation = method.getAnnotation(Subscribe.class);
            if (annotation == null) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                throw new IllegalArgumentException(
                        "@Subscribe-Methode " + method.getName() + " in "
                        + listener.getClass().getName() + " muss genau einen Parameter haben");
            }
            Class<?> eventType = method.getParameterTypes()[0];
            method.setAccessible(true);

            CopyOnWriteArrayList<Handler> list =
                    handlers.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>());
            list.add(new Handler(listener, method, annotation.priority(), annotation.onTimeout()));
            list.sort(Comparator.comparing(Handler::priority));

            LOG.debug("Handler angemeldet: {}#{} fuer {}",
                    listener.getClass().getSimpleName(), method.getName(), eventType.getSimpleName());
        }
    }

    @Override
    public void unregister(Object listener) {
        handlers.values().forEach(list -> list.removeIf(handler -> handler.target() == listener));
    }

    @Override
    public void post(Object event) {
        List<Handler> matching = handlersFor(event.getClass());
        if (matching.isEmpty()) {
            return;
        }
        // Reihenfolge innerhalb einer Prioritaet ist nicht garantiert - das ist bei
        // Post-Events in Ordnung, weil keiner den anderen beeinflussen kann.
        for (Handler handler : matching) {
            dispatcher.execute(() -> invokeSafely(handler, event));
        }
    }

    @Override
    public <E extends Cancellable> E postSync(E event) {
        for (Handler handler : handlersFor(event.getClass())) {
            invokeSafely(handler, event);
        }
        return event;
    }

    /** Auch Handler auf Oberklassen und Interfaces des Events beruecksichtigen. */
    private List<Handler> handlersFor(Class<?> eventType) {
        List<Handler> result = new ArrayList<>();
        for (Map.Entry<Class<?>, CopyOnWriteArrayList<Handler>> entry : handlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventType)) {
                result.addAll(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(Handler::priority));
        return result;
    }

    /**
     * Eine Ausnahme in einem Handler wird geloggt und beendet die Verteilung nicht -
     * ein fehlerhaftes Modul darf den Rest der Cloud nicht lahmlegen.
     */
    private void invokeSafely(Handler handler, Object event) {
        try {
            handler.method().invoke(handler.target(), event);
        } catch (InvocationTargetException exception) {
            LOG.error("Handler {}#{} hat eine Ausnahme geworfen bei {}",
                    handler.target().getClass().getSimpleName(), handler.method().getName(),
                    event.getClass().getSimpleName(), exception.getCause());
        } catch (ReflectiveOperationException exception) {
            LOG.error("Handler {}#{} konnte nicht aufgerufen werden",
                    handler.target().getClass().getSimpleName(), handler.method().getName(),
                    exception);
        }
    }

    public void shutdown() {
        dispatcher.close();
        handlers.clear();
    }

    private record Handler(Object target, Method method, Priority priority, TimeoutAction onTimeout) {
    }
}
