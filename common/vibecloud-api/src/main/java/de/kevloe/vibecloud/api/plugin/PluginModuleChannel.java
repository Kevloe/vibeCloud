package de.kevloe.vibecloud.api.plugin;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.protocol.ModuleMessage;
import de.kevloe.vibecloud.protocol.ServerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Die Plugin-Seite des Modul-Kanals (PLAN.md Abschnitt 10).
 *
 * <p>Das Gegenstueck zum {@code ModuleChannelRouter} im Master. Ein Plugin-Teil eines Moduls
 * - etwa der Mute-Filter im Chat - fragt hier den Master und bekommt eine Antwort.
 *
 * <p><b>Warum fragen und nicht selbst wissen?</b> Ein Gameserver haelt keine Strafen und
 * keine Rechte vor. Er fragt, der Master entscheidet. Faellt der Master aus, gibt es keine
 * Antwort - und jeder Aufrufer muss selbst festlegen, was dann gilt
 * (PLAN.md Abschnitt 3 und 13).
 *
 * <p>Laeuft ueber den bestehenden Dauerkanal: keine zweite Verbindung, keine eigenen
 * Zugangsdaten.
 */
public final class PluginModuleChannel {

    private static final Logger LOG = LoggerFactory.getLogger(PluginModuleChannel.class);
    private static final Gson GSON = new Gson();

    private final CloudConnection connection;
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    public PluginModuleChannel(CloudConnection connection) {
        this.connection = connection;
    }

    /**
     * Fragt den Master und wartet auf die Antwort.
     *
     * <p>Der Future schlaegt fehl, wenn der Master nicht antwortet. Was dann gilt,
     * entscheidet der Aufrufer - fuer einen Mute-Filter heisst das: im Zweifel schreiben
     * lassen, fuer ein Login-Gate: im Zweifel ablehnen.
     */
    public <R> CompletableFuture<R> request(String moduleId, String key, Object payload,
                                            Class<R> responseType, Duration timeout) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<String> answer = new CompletableFuture<>();
        pending.put(correlationId, answer);

        if (!publish(ModuleMessage.newBuilder()
                .setModuleId(moduleId)
                .setKey(key)
                .setPayloadJson(GSON.toJson(payload))
                .setCorrelationId(correlationId)
                .setExpectsResponse(true)
                .build())) {
            pending.remove(correlationId);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Keine Verbindung zum Master"));
        }

        return answer
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(correlationId))
                .thenApply(json -> GSON.fromJson(json, responseType));
    }

    /**
     * Fragt den Master und wartet blockierend.
     *
     * <p>Nur aus einem asynchronen Kontext aufrufen - im Haupt-Thread eines Gameservers
     * wuerde das den Tick anhalten.
     *
     * @return leer, wenn der Master nicht rechtzeitig antwortet
     */
    public <R> Optional<R> ask(String moduleId, String key, Object payload,
                               Class<R> responseType, Duration timeout) {
        try {
            return Optional.ofNullable(
                    request(moduleId, key, payload, responseType, timeout).get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof TimeoutException) {
                LOG.warn("{}/{}: keine Antwort vom Master innerhalb von {}",
                        moduleId, key, timeout);
            } else {
                LOG.warn("{}/{} fehlgeschlagen: {}", moduleId, key, cause.getMessage());
            }
            return Optional.empty();
        }
    }

    /** Meldet etwas, ohne auf eine Antwort zu warten. */
    public boolean send(String moduleId, String key, Object payload) {
        return publish(ModuleMessage.newBuilder()
                .setModuleId(moduleId)
                .setKey(key)
                .setPayloadJson(GSON.toJson(payload))
                .setExpectsResponse(false)
                .build());
    }

    /** Beantwortet Anfragen des Masters. */
    public <T> void respond(String moduleId, String key, Class<T> requestType,
                            Function<T, Object> handler) {
        handlers.put(fullKey(moduleId, key), new Handler(
                json -> handler.apply(GSON.fromJson(json, requestType))));
    }

    /** Nimmt Meldungen des Masters entgegen, ohne zu antworten. */
    public <T> void listen(String moduleId, String key, Class<T> requestType,
                           Consumer<T> handler) {
        handlers.put(fullKey(moduleId, key), new Handler(json -> {
            handler.accept(GSON.fromJson(json, requestType));
            return null;
        }));
    }

    /**
     * Eine Nachricht vom Master.
     *
     * <p>Ruft das Plattform-Plugin auf, wenn ein {@code ServerCommand} eine
     * {@link ModuleMessage} enthaelt.
     */
    public void onMessage(ModuleMessage message) {
        // Antwort auf eine eigene Anfrage? Die traegt keinen Schluessel.
        if (!message.getCorrelationId().isBlank() && message.getKey().isBlank()) {
            CompletableFuture<String> waiting = pending.remove(message.getCorrelationId());
            if (waiting != null) {
                waiting.complete(message.getPayloadJson());
            }
            return;
        }

        Handler handler = handlers.get(fullKey(message.getModuleId(), message.getKey()));
        if (handler == null) {
            // Normal: Der Master schickt an alle Server, nicht jeder hat den Teil geladen.
            LOG.debug("Kein Handler fuer {}",
                    fullKey(message.getModuleId(), message.getKey()));
            return;
        }

        Object result;
        try {
            result = handler.invoke(message.getPayloadJson());
        } catch (RuntimeException exception) {
            LOG.error("Handler {} ist fehlgeschlagen",
                    fullKey(message.getModuleId(), message.getKey()), exception);
            return;
        }

        if (message.getExpectsResponse()) {
            publish(ModuleMessage.newBuilder()
                    .setModuleId(message.getModuleId())
                    .setCorrelationId(message.getCorrelationId())
                    .setPayloadJson(result == null ? "null" : GSON.toJson(result))
                    .build());
        }
    }

    private boolean publish(ModuleMessage message) {
        if (!connection.isConnected()) {
            return false;
        }
        connection.publish(ServerEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setModuleMessage(message)
                .build());
        return true;
    }

    private static String fullKey(String moduleId, String key) {
        return moduleId + ":" + key;
    }

    private record Handler(Function<String, Object> invoke) {

        Object invoke(String json) {
            return invoke.apply(json);
        }
    }
}
