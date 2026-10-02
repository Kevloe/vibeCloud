package de.kevloe.vibecloud.master.grpc;

import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.protocol.ServerCommand;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die verbundenen Plugins und ihr Rueckkanal (PLAN.md Abschnitt 11).
 *
 * <p>Wichtig fuer die Proxys: Startet irgendwo ein Gameserver, muss er in <b>jedem</b> Proxy
 * registriert werden - sonst kann niemand dorthin verbunden werden. Das erledigt
 * {@link #broadcastToProxies}.
 */
public final class PluginConnectionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(PluginConnectionRegistry.class);

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public void attach(String serverName, ServerPlatformType platform,
                       StreamObserver<ServerCommand> channel) {
        Connection previous = connections.put(serverName,
                new Connection(serverName, platform, channel));
        if (previous != null) {
            LOG.warn("Plugin von {} hat sich erneut verbunden - alte Verbindung wird getrennt",
                    serverName);
            previous.closeQuietly();
        }
        LOG.info("Plugin von {} verbunden ({})", serverName, platform);
    }

    public void detach(String serverName) {
        Connection removed = connections.remove(serverName);
        if (removed != null) {
            LOG.info("Plugin von {} getrennt", serverName);
        }
    }

    public boolean isConnected(String serverName) {
        return connections.containsKey(serverName);
    }

    public Optional<Connection> find(String serverName) {
        return Optional.ofNullable(connections.get(serverName));
    }

    public List<Connection> proxies() {
        return connections.values().stream()
                .filter(connection -> connection.platform() == ServerPlatformType.VELOCITY)
                .toList();
    }

    public boolean send(String serverName, ServerCommand command) {
        Connection target = connections.get(serverName);
        return target != null && target.send(command);
    }

    /**
     * Schickt einen Befehl an alle Proxys.
     *
     * @return Anzahl der Proxys, die ihn bekommen haben
     */
    public int broadcastToProxies(ServerCommand command) {
        int delivered = 0;
        for (Connection proxy : proxies()) {
            if (proxy.send(command)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** Schickt einen Befehl an alle verbundenen Plugins, Proxys eingeschlossen. */
    public int broadcastToAll(ServerCommand command) {
        int delivered = 0;
        for (Connection connection : connections.values()) {
            if (connection.send(command)) {
                delivered++;
            }
        }
        return delivered;
    }

    public static ServerCommand command(java.util.function.Consumer<ServerCommand.Builder> filler) {
        ServerCommand.Builder builder = ServerCommand.newBuilder()
                .setCommandId(UUID.randomUUID().toString());
        filler.accept(builder);
        return builder.build();
    }

    public void closeAll() {
        connections.values().forEach(Connection::closeQuietly);
        connections.clear();
    }

    /** Ein verbundenes Plugin. */
    public static final class Connection {

        private final String serverName;
        private final ServerPlatformType platform;
        private final StreamObserver<ServerCommand> channel;

        Connection(String serverName, ServerPlatformType platform,
                   StreamObserver<ServerCommand> channel) {
            this.serverName = serverName;
            this.platform = platform;
            this.channel = channel;
        }

        /** Synchronisiert, weil ein StreamObserver nicht thread-safe ist. */
        synchronized boolean send(ServerCommand command) {
            try {
                channel.onNext(command);
                return true;
            } catch (RuntimeException exception) {
                LOG.debug("Senden an {} fehlgeschlagen: {}", serverName, exception.getMessage());
                return false;
            }
        }

        synchronized void closeQuietly() {
            try {
                channel.onCompleted();
            } catch (RuntimeException ignored) {
                // Stream war schon zu.
            }
        }

        public String serverName() {
            return serverName;
        }

        public ServerPlatformType platform() {
            return platform;
        }
    }
}
