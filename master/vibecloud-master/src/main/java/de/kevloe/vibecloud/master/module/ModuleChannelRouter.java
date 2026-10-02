package de.kevloe.vibecloud.master.module;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.console.CommandOutput;
import de.kevloe.vibecloud.master.console.CommandRegistry;
import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.master.message.MessageService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.module.ModuleChannel;
import de.kevloe.vibecloud.module.ModuleCommands;
import de.kevloe.vibecloud.protocol.ModuleMessage;
import de.kevloe.vibecloud.protocol.ServerCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Vermittelt Modul-Nachrichten und registriert Modul-Befehle (PLAN.md Abschnitt 10).
 *
 * <p>Ein Schluessel wird immer unter der Modul-Id gefuehrt ({@code punishment:is_muted}),
 * damit zwei Module sich nicht in die Quere kommen koennen.
 *
 * <p><b>Server zu Server geht nicht direkt.</b> Alles laeuft hier durch. Damit bleibt das
 * Firewall-Modell aus Abschnitt 13 unangetastet, und es gibt genau eine Stelle, an der
 * Befugnisse geprueft werden.
 */
public final class ModuleChannelRouter {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleChannelRouter.class);
    private static final Gson GSON = new Gson();
    private static final long REQUEST_TIMEOUT_SECONDS = 10;

    private final PluginConnectionRegistry plugins;
    private final ServerRegistry servers;
    private final CommandRegistry consoleCommands;
    private final PermissionService permissions;
    private final MessageService messages;

    /** Schluessel ({@code modul:key}) -> Handler des Master-Teils. */
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    /** Offene Anfragen an Plugins, nach Korrelations-Id. */
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    /** Modul-Id -> registrierte Konsolen-Befehlsnamen. */
    private final Map<String, List<String>> commandsByModule = new ConcurrentHashMap<>();

    public ModuleChannelRouter(PluginConnectionRegistry plugins, ServerRegistry servers,
                               CommandRegistry consoleCommands, PermissionService permissions,
                               MessageService messages) {
        this.plugins = plugins;
        this.servers = servers;
        this.consoleCommands = consoleCommands;
        this.permissions = permissions;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- Kanal

    /** Der Kanal, den ein Modul bekommt. */
    public ModuleChannel channelFor(String moduleId) {
        return new ModuleChannel() {

            @Override
            public <R> CompletableFuture<R> request(Target target, String key, Object payload,
                                                    Class<R> responseType) {
                return sendRequest(moduleId, target, key, payload, responseType);
            }

            @Override
            public int send(Target target, String key, Object payload) {
                return sendFireAndForget(moduleId, target, key, payload);
            }

            @Override
            public <T> void respond(String key, Class<T> requestType,
                                    Function<T, Object> handler) {
                handlers.put(fullKey(moduleId, key), new Handler(requestType,
                        json -> handler.apply(GSON.fromJson(json, requestType))));
            }

            @Override
            public <T> void listen(String key, Class<T> requestType, Consumer<T> handler) {
                handlers.put(fullKey(moduleId, key), new Handler(requestType, json -> {
                    handler.accept(GSON.fromJson(json, requestType));
                    return null;
                }));
            }
        };
    }

    private <R> CompletableFuture<R> sendRequest(String moduleId, ModuleChannel.Target target,
                                                 String key, Object payload,
                                                 Class<R> responseType) {
        // An den Master selbst: direkt im Prozess beantworten, ohne Netzwerk.
        if (target instanceof ModuleChannel.Target.Master) {
            return CompletableFuture.completedFuture(
                    handleLocally(moduleId, key, GSON.toJson(payload), responseType));
        }

        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<String> answer = new CompletableFuture<>();
        pending.put(correlationId, answer);

        int delivered = dispatch(target, ModuleMessage.newBuilder()
                .setModuleId(moduleId)
                .setKey(key)
                .setPayloadJson(GSON.toJson(payload))
                .setCorrelationId(correlationId)
                .setExpectsResponse(true)
                .build());

        if (delivered == 0) {
            pending.remove(correlationId);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Kein Empfaenger fuer " + fullKey(moduleId, key)));
        }

        return answer
                // Ohne Timeout wuerde ein Modul auf eine Antwort warten, die nie kommt -
                // etwa weil der Server zwischendurch gestoppt wurde.
                .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, error) -> pending.remove(correlationId))
                .thenApply(json -> GSON.fromJson(json, responseType));
    }

    private int sendFireAndForget(String moduleId, ModuleChannel.Target target, String key,
                                  Object payload) {
        if (target instanceof ModuleChannel.Target.Master) {
            handleLocally(moduleId, key, GSON.toJson(payload), Object.class);
            return 1;
        }
        return dispatch(target, ModuleMessage.newBuilder()
                .setModuleId(moduleId)
                .setKey(key)
                .setPayloadJson(GSON.toJson(payload))
                .setExpectsResponse(false)
                .build());
    }

    private int dispatch(ModuleChannel.Target target, ModuleMessage message) {
        ServerCommand command = PluginConnectionRegistry.command(builder ->
                builder.setModuleMessage(message));

        return switch (target) {
            case ModuleChannel.Target.Server server ->
                    plugins.send(server.name(), command) ? 1 : 0;
            case ModuleChannel.Target.AllProxies ignored ->
                    plugins.broadcastToProxies(command);
            case ModuleChannel.Target.AllServers ignored -> {
                int delivered = 0;
                for (CloudServer server : servers.all()) {
                    if (server.platform() != ServerPlatformType.VELOCITY
                        && plugins.send(server.name(), command)) {
                        delivered++;
                    }
                }
                yield delivered;
            }
            case ModuleChannel.Target.Master ignored -> 0;
        };
    }

    @SuppressWarnings("unchecked")
    private <R> R handleLocally(String moduleId, String key, String payloadJson,
                                Class<R> responseType) {
        Handler handler = handlers.get(fullKey(moduleId, key));
        if (handler == null) {
            LOG.warn("Kein Handler fuer {} im Master", fullKey(moduleId, key));
            return null;
        }
        Object result = handler.invoke(payloadJson);
        if (result == null) {
            return null;
        }
        // Umweg ueber JSON, damit lokale und entfernte Aufrufe sich gleich verhalten -
        // sonst faellt ein Typfehler nur im Netzwerkfall auf.
        return (R) GSON.fromJson(GSON.toJson(result), responseType);
    }

    /** Eine Nachricht von einem Plugin. */
    public void onPluginMessage(String serverName, ModuleMessage message) {
        // Antwort auf eine eigene Anfrage?
        if (!message.getCorrelationId().isBlank() && message.getKey().isBlank()) {
            CompletableFuture<String> waiting = pending.remove(message.getCorrelationId());
            if (waiting != null) {
                waiting.complete(message.getPayloadJson());
            }
            return;
        }

        Handler handler = handlers.get(fullKey(message.getModuleId(), message.getKey()));
        if (handler == null) {
            LOG.debug("Kein Handler fuer {} (von {})",
                    fullKey(message.getModuleId(), message.getKey()), serverName);
            return;
        }

        Object result;
        try {
            result = handler.invoke(message.getPayloadJson());
        } catch (RuntimeException exception) {
            LOG.error("Handler {} ist fehlgeschlagen", fullKey(message.getModuleId(),
                    message.getKey()), exception);
            return;
        }

        if (message.getExpectsResponse()) {
            plugins.send(serverName, PluginConnectionRegistry.command(builder ->
                    builder.setModuleMessage(ModuleMessage.newBuilder()
                            .setModuleId(message.getModuleId())
                            .setCorrelationId(message.getCorrelationId())
                            .setPayloadJson(result == null ? "null" : GSON.toJson(result)))));
        }
    }

    public void forgetModule(String moduleId) {
        handlers.keySet().removeIf(key -> key.startsWith(moduleId + ":"));
    }

    // ---------------------------------------------------------------- Befehle

    /**
     * Macht einen Modul-Befehl in der Master-Konsole verfuegbar.
     *
     * <p>Derselbe Befehl soll laut Plan auch im Spiel und ueber REST gehen. Die
     * Konsolen-Seite ist hier umgesetzt; der Weg ueber den Proxy kommt mit den
     * In-Game-Commands, die REST-Seite in M7. Die Logik liegt schon jetzt nur einmal vor.
     */
    public void registerCommand(String moduleId, String name, String description, String usage,
                                String permission, ModuleCommands.ModuleCommand command) {

        consoleCommands.register(new CommandRegistry.Command() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description + "  (" + moduleId + ")";
            }

            @Override
            public String usage() {
                return usage;
            }

            @Override
            public List<String> complete(List<String> args) {
                return command.complete(consoleSender(null), args);
            }

            /**
             * Das Recht, das das Modul angegeben hat.
             *
             * <p>Nicht das abgeleitete {@code vibecloud.command.<name>}: Ein Modul bringt
             * seine Rechte selbst mit, und {@code /ban} haengt an
             * {@code vibecloud.punishment.ban}. Ohne das waere das Recht im Spiel ein
             * anderes als das, was das Modul angemeldet hat.
             */
            @Override
            public String permission() {
                return permission == null || permission.isBlank()
                        ? "vibecloud.command." + name : permission;
            }

            @Override
            public void execute(CommandOutput out, List<String> args) {
                command.execute(consoleSender(out), args);
            }
        });

        commandsByModule.computeIfAbsent(moduleId, key -> new java.util.ArrayList<>()).add(name);
        LOG.debug("Modul {} hat den Befehl '{}' registriert", moduleId, name);
    }

    public void unregisterCommand(String moduleId, String name) {
        consoleCommands.unregister(name);
        Optional.ofNullable(commandsByModule.get(moduleId))
                .ifPresent(names -> names.remove(name));
    }

    /**
     * Die Konsole als Befehlssender.
     *
     * <p>Sie hat implizit alle Rechte - wer am Terminal sitzt, hat ohnehin Zugriff auf
     * Config und Datenbank (PLAN.md Abschnitt 9).
     */
    private ModuleCommands.CommandSender consoleSender(CommandOutput out) {
        return new ModuleCommands.CommandSender() {

            @Override
            public String name() {
                return "CONSOLE";
            }

            @Override
            public UUID uuid() {
                return null;
            }

            @Override
            public boolean isConsole() {
                return true;
            }

            @Override
            public boolean hasPermission(String node) {
                return true;
            }

            @Override
            public void reply(String messageKey, Map<String, String> placeholders) {
                // Denselben Text wie im Spiel, nur ohne Formatierung: Das Terminal kann
                // mit MiniMessage-Tags nichts anfangen, und "punishment.ban.already
                // {spieler=Kevin}" ist fuer einen Betreiber keine Antwort.
                // Ausdruecklich die Standardsprache: Mit null wirft die unveraenderliche
                // Map der Sprachen beim Nachschlagen.
                var bundle = messages.bundle();
                replyRaw(MiniMessage.miniMessage().stripTags(
                        bundle.get(bundle.defaultLocale(), messageKey, placeholders)));
            }

            @Override
            public void replyRaw(String text) {
                if (out != null) {
                    out.info(text);
                }
            }
        };
    }

    private static String fullKey(String moduleId, String key) {
        return moduleId + ":" + key;
    }

    /** Ein registrierter Handler samt erwartetem Nutzlast-Typ. */
    private record Handler(Class<?> requestType, Function<String, Object> invoke) {

        Object invoke(String payloadJson) {
            return invoke.apply(payloadJson);
        }
    }
}
