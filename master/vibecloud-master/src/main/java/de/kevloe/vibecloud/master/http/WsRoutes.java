package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.master.console.ConsoleBuffer;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import io.javalin.config.RoutesConfig;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live-Daten fuer das Dashboard (PLAN.md Abschnitt 12).
 *
 * <ul>
 *   <li>{@code /ws/events} - Serverzustaende und Spielerzahlen</li>
 *   <li>{@code /ws/console/<server>} - die Konsole eines Servers, wie {@code screen}</li>
 * </ul>
 *
 * <p><b>Zustaende werden als Aufnahme geschickt, nicht als Einzelmeldung.</b> Alle zwei
 * Sekunden - und nur, wenn sich etwas geaendert hat. Der Grund: Der Master hat kein Event
 * fuer jeden Zustandswechsel, und eines einzufuehren haette die Kernpfade angefasst, auf
 * denen Server gestartet und gestoppt werden. Zwei Sekunden sind fuer eine Oberflaeche
 * nicht von "sofort" zu unterscheiden, und der Scheduler der Cloud laeuft ohnehin im
 * Drei-Sekunden-Takt.
 *
 * <p>Angemeldet wird mit einer Einmal-Karte in der Adresse ({@link WsTickets}), weil ein
 * Browser beim WebSocket-Aufbau keine Kopfzeilen setzen kann.
 */
final class WsRoutes implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WsRoutes.class);
    private static final Gson GSON = new Gson();

    private static final String SERVERS_PERMISSION = "vibecloud.command.server.list";
    private static final String CONSOLE_PERMISSION = HttpApi.CONSOLE_PERMISSION;

    /** Takt der Zustands-Aufnahmen. */
    private static final int SNAPSHOT_SECONDS = 2;

    private final WsTickets tickets;
    private final PermissionService permissions;
    private final ServerRegistry servers;
    private final ConsoleBuffer console;

    /** Offene Zustands-Verbindungen. */
    private final Map<WsContext, String> eventClients = new ConcurrentHashMap<>();

    /** Offene Konsolen-Verbindungen und ihr Abmelde-Haken. */
    private final Map<WsContext, Runnable> consoleClients = new ConcurrentHashMap<>();

    private final ScheduledExecutorService snapshots = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("ws-snapshots").factory());

    /** Die letzte gesendete Aufnahme - nur Aenderungen gehen raus. */
    private volatile String lastSnapshot = "";

    WsRoutes(WsTickets tickets, PermissionService permissions, ServerRegistry servers,
             ConsoleBuffer console) {
        this.tickets = tickets;
        this.permissions = permissions;
        this.servers = servers;
        this.console = console;
    }

    void register(RoutesConfig routes) {
        routes.ws("/ws/events", config -> {
            config.onConnect(this::openEvents);
            config.onClose(context -> eventClients.remove(context));
        });

        routes.ws("/ws/console/{server}", config -> {
            config.onConnect(this::openConsole);
            config.onClose(this::closeConsole);
        });

        snapshots.scheduleAtFixedRate(this::pushSnapshot,
                SNAPSHOT_SECONDS, SNAPSHOT_SECONDS, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- Zustaende

    private void openEvents(WsConnectContext context) {
        Optional<WsTickets.Ticket> ticket = authorize(context, SERVERS_PERMISSION);
        if (ticket.isEmpty()) {
            return;
        }
        eventClients.put(context, ticket.get().name());

        // Sofort eine Aufnahme, damit die Oberflaeche nicht zwei Sekunden leer bleibt.
        String snapshot = snapshot();
        context.send(snapshot);
        // Als letzte vermerken, sonst schickt der Takt gleich danach dieselbe noch einmal.
        lastSnapshot = snapshot;
        LOG.debug("{} hoert auf Zustaende", ticket.get().name());
    }

    /**
     * Schickt die Aufnahme, wenn sie sich geaendert hat.
     *
     * <p>Der Vergleich ueber den fertigen Text ist grob, aber genau richtig: Gleich heisst
     * "fuer die Oberflaeche gibt es nichts Neues", und dann muss auch nichts gesendet
     * werden.
     */
    private void pushSnapshot() {
        if (eventClients.isEmpty()) {
            return;
        }
        String snapshot = snapshot();
        if (snapshot.equals(lastSnapshot)) {
            return;
        }
        lastSnapshot = snapshot;

        eventClients.keySet().forEach(client -> {
            try {
                client.send(snapshot);
            } catch (RuntimeException exception) {
                // Geschlossene Verbindung - onClose raeumt sie auf.
                LOG.debug("Aufnahme nicht gesendet: {}", exception.getMessage());
            }
        });
    }

    private String snapshot() {
        List<Map<String, Object>> entries = servers.all().stream()
                .sorted(java.util.Comparator.comparing(CloudServer::name))
                .map(server -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", server.name());
                    entry.put("group", server.groupName());
                    entry.put("node", server.node());
                    entry.put("state", server.state().name());
                    entry.put("players", server.players());
                    entry.put("maxPlayers", server.maxPlayers());
                    return entry;
                })
                .toList();

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "servers");
        message.put("servers", entries);
        // Nur die Proxys zaehlen: Ein Spieler ist gleichzeitig auf einem Proxy und auf
        // einem Gameserver - zusammengezaehlt waere er zweimal da.
        message.put("players", servers.all().stream()
                .filter(server -> server.platform()
                                  == de.kevloe.vibecloud.api.server.ServerPlatformType.VELOCITY)
                .mapToInt(CloudServer::players)
                .sum());
        return GSON.toJson(message);
    }

    // ---------------------------------------------------------------- Server-Konsole

    private void openConsole(WsConnectContext context) {
        Optional<WsTickets.Ticket> ticket = authorize(context, CONSOLE_PERMISSION);
        if (ticket.isEmpty()) {
            return;
        }
        String server = context.pathParam("server");

        if (servers.find(server).isEmpty()) {
            context.closeSession(4404, "Unbekannter Server: " + server);
            return;
        }

        // Erst die Historie, dann anhaengen - sonst fehlt der Anfang oder eine Zeile
        // kommt doppelt.
        console.history(server).forEach(line -> context.send(line(server, line)));
        Runnable detach = console.attach(server, line -> {
            try {
                context.send(line(server, line));
            } catch (RuntimeException exception) {
                LOG.debug("Konsolenzeile nicht gesendet: {}", exception.getMessage());
            }
        });
        consoleClients.put(context, detach);
        LOG.debug("{} hoert auf die Konsole von {}", ticket.get().name(), server);
    }

    private void closeConsole(WsCloseContext context) {
        Runnable detach = consoleClients.remove(context);
        if (detach != null) {
            // Ohne das Abmelden schreibt der Puffer weiter in eine geschlossene
            // Verbindung - und haelt sie am Leben.
            detach.run();
        }
    }

    private static String line(String server, String text) {
        return GSON.toJson(Map.of("type", "console", "server", server, "line", text));
    }

    // ---------------------------------------------------------------- Anmeldung

    /**
     * Loest die Karte ein und prueft das Recht.
     *
     * <p>Schliesst die Verbindung mit einem eigenen Code, damit die Oberflaeche den Grund
     * unterscheiden kann: 4401 keine gueltige Karte, 4403 Recht fehlt.
     */
    private Optional<WsTickets.Ticket> authorize(WsConnectContext context,
                                                 String permission) {
        Optional<WsTickets.Ticket> ticket = tickets.redeem(context.queryParam("ticket"));

        if (ticket.isEmpty()) {
            context.closeSession(4401, "Keine gueltige Eintrittskarte");
            return Optional.empty();
        }
        // Ein API-Token hat keine Spieler-Rechte - fuer Live-Daten ist das in Ordnung,
        // es hat sich ueber seinen Rechte-Bereich schon ausgewiesen.
        if (ticket.get().isUser() && !permissions.has(ticket.get().uuid(), permission)) {
            context.closeSession(4403, "Dir fehlt das Recht " + permission);
            return Optional.empty();
        }
        return ticket;
    }

    @Override
    public void close() {
        snapshots.shutdownNow();
        consoleClients.values().forEach(Runnable::run);
        consoleClients.clear();
        eventClients.clear();
    }
}
