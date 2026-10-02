package de.kevloe.vibecloud.master.node;

import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.events.NodeConnectedEvent;
import de.kevloe.vibecloud.api.event.events.NodeDisconnectedEvent;
import de.kevloe.vibecloud.api.event.events.NodeTimeoutEvent;
import de.kevloe.vibecloud.api.node.NodeInfo;
import de.kevloe.vibecloud.protocol.NodeCommand;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Die aktuell verbundenen Wrapper und ihr Rueckkanal (PLAN.md Abschnitt 6).
 *
 * <p>Der Rueckkanal ist der Kern der Architektur: Der Master oeffnet nie eine Verbindung zu
 * einem Root, sondern schickt Befehle durch den Stream, den der Wrapper aufgebaut hat.
 * Deshalb braucht kein Root einen offenen Port.
 *
 * <p>Ein Wrapper kommt in zwei Schritten an: erst {@code Register} (sagt, was er kann), dann
 * {@code Control} (oeffnet den Rueckkanal). Zwischen beiden ist der Node bekannt, aber noch
 * nicht ansprechbar - deshalb gibt es {@link #attach} und {@link #attachChannel} getrennt.
 */
public final class NodeRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NodeRegistry.class);

    private final Map<String, ConnectedNode> connected = new ConcurrentHashMap<>();
    private final NodeRepository repository;
    private final EventBus events;
    private final Duration heartbeatTimeout;
    private final ScheduledExecutorService watchdog;

    public NodeRegistry(NodeRepository repository, EventBus events, Duration heartbeatTimeout) {
        this.repository = repository;
        this.events = events;
        this.heartbeatTimeout = heartbeatTimeout;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("node-watchdog").factory());

        // Der Watchdog prueft nur und meldet - er macht keine I/O im eigenen Takt.
        watchdog.scheduleAtFixedRate(this::checkTimeouts, 5, 5, TimeUnit.SECONDS);
    }

    /** Schritt 1: Der Wrapper hat sich angemeldet und beschrieben. */
    public void attach(String node, RegisterInfo info) {
        ConnectedNode previous = connected.put(node, new ConnectedNode(node, info));
        if (previous != null) {
            LOG.warn("Node {} meldet sich erneut an - alte Verbindung wird getrennt", node);
            previous.closeQuietly();
        }
        repository.touchLastSeen(node, info.maxMemoryMb());
        LOG.info("Node {} angemeldet ({} MB RAM, {} Kerne, {})",
                node, info.maxMemoryMb(), info.cpuCores(), info.osName());
    }

    /** Schritt 2: Der Rueckkanal steht, ab jetzt ist der Node ansprechbar. */
    public void attachChannel(String node, StreamObserver<NodeCommand> channel) {
        ConnectedNode current = connected.computeIfAbsent(node,
                key -> new ConnectedNode(key, RegisterInfo.unknown()));
        current.setChannel(channel);
        LOG.info("Control-Stream von {} offen", node);
        events.post(new NodeConnectedEvent(infoOf(node)));
    }

    /** Der Wrapper hat die Verbindung beendet oder sie ist abgerissen. */
    public void detach(String node, String reason) {
        ConnectedNode removed = connected.remove(node);
        if (removed == null) {
            return;
        }
        LOG.info("Node {} getrennt: {}", node, reason);
        events.post(new NodeDisconnectedEvent(node, reason));
    }

    public void heartbeat(String node) {
        ConnectedNode current = connected.get(node);
        if (current != null) {
            current.touch();
        }
    }

    public boolean isConnected(String node) {
        ConnectedNode current = connected.get(node);
        return current != null && current.hasChannel();
    }

    public Optional<ConnectedNode> connection(String node) {
        return Optional.ofNullable(connected.get(node));
    }

    /**
     * Adresse, unter der die Gameserver dieses Nodes fuer Proxys erreichbar sind.
     *
     * <p>Faellt auf den Node-Namen zurueck, wenn der Node nicht verbunden ist. Der Name
     * ist nicht auflösbar, macht aber in Logs klar, welcher Node gemeint war.
     */
    public String addressOf(String node) {
        return connection(node)
                .map(ConnectedNode::info)
                .map(RegisterInfo::serverAddress)
                .filter(address -> !address.isBlank())
                .orElse(node);
    }

    /** Schickt einen Befehl durch den Rueckkanal dieses Nodes. */
    public boolean send(String node, NodeCommand command) {
        ConnectedNode target = connected.get(node);
        if (target == null) {
            LOG.debug("Befehl an {} verworfen - nicht verbunden", node);
            return false;
        }
        return target.send(command);
    }

    /** Alle bekannten Nodes, auch die gerade nicht verbundenen. */
    public List<NodeInfo> listAll() {
        return repository.findAll().stream().map(this::toInfo).toList();
    }

    private NodeInfo toInfo(NodeRepository.NodeRecord record) {
        ConnectedNode live = connected.get(record.name());
        boolean online = live != null && live.hasChannel();
        return new NodeInfo(
                record.name(),
                record.enabled(),
                online,
                live != null ? live.lastHeartbeat() : record.lastSeen(),
                record.maxMemoryMb(),
                // Belegter RAM kommt ab M2, wenn es Server gibt.
                0L,
                live != null ? live.info().cpuCores() : 0,
                live != null ? live.info().osName() : "-",
                live != null ? live.cpuLoad() : 0.0);
    }

    private NodeInfo infoOf(String node) {
        return repository.find(node)
                .map(this::toInfo)
                .orElseGet(() -> new NodeInfo(node, true, true, Instant.now(), 0, 0, 0, "-", 0.0));
    }

    /**
     * Erkennt stumme Nodes. Wichtig: Hier wird nur gemeldet, nicht entschieden, was mit den
     * Servern passiert - das macht ab M2 der Scheduler, und fuer statische Server lautet die
     * Antwort ausdruecklich "nichts" (PLAN.md Abschnitt 6).
     */
    private void checkTimeouts() {
        Instant now = Instant.now();
        for (ConnectedNode node : Map.copyOf(connected).values()) {
            Duration silent = Duration.between(node.lastHeartbeat(), now);
            if (silent.compareTo(heartbeatTimeout) > 0) {
                LOG.warn("Node {} seit {} s ohne Heartbeat - Verbindung gilt als tot",
                        node.name(), silent.toSeconds());
                detach(node.name(), "Heartbeat-Timeout");
                node.closeQuietly();
                events.post(new NodeTimeoutEvent(node.name(), silent));
            }
        }
    }

    @Override
    public void close() {
        watchdog.close();
        connected.values().forEach(ConnectedNode::closeQuietly);
        connected.clear();
    }

    /** Was der Wrapper bei der Anmeldung ueber sich gesagt hat. */
    public record RegisterInfo(
            long maxMemoryMb,
            int cpuCores,
            String osName,
            String wrapperVersion,
            long clockSkewMillis,
            String serverAddress) {

        /** Fuer den Fall, dass ein Control-Stream ohne vorheriges Register ankommt. */
        static RegisterInfo unknown() {
            return new RegisterInfo(0, 0, "-", "-", 0, "");
        }
    }

    /** Ein verbundener Wrapper samt Rueckkanal. */
    public static final class ConnectedNode {

        private final String name;
        private final RegisterInfo info;
        private volatile StreamObserver<NodeCommand> channel;
        private volatile Instant lastHeartbeat = Instant.now();
        private volatile double cpuLoad;

        ConnectedNode(String name, RegisterInfo info) {
            this.name = name;
            this.info = info;
        }

        void setChannel(StreamObserver<NodeCommand> channel) {
            this.channel = channel;
            touch();
        }

        boolean hasChannel() {
            return channel != null;
        }

        /**
         * Senden ist synchronisiert, weil ein gRPC-StreamObserver nicht thread-safe ist -
         * und Befehle kommen aus Konsole, Scheduler und REST gleichzeitig.
         */
        synchronized boolean send(NodeCommand command) {
            StreamObserver<NodeCommand> target = channel;
            if (target == null) {
                return false;
            }
            try {
                target.onNext(command);
                return true;
            } catch (RuntimeException exception) {
                LOG.debug("Senden an {} fehlgeschlagen: {}", name, exception.getMessage());
                return false;
            }
        }

        void touch() {
            lastHeartbeat = Instant.now();
        }

        public void reportCpuLoad(double load) {
            this.cpuLoad = load;
            touch();
        }

        synchronized void closeQuietly() {
            StreamObserver<NodeCommand> target = channel;
            if (target == null) {
                return;
            }
            try {
                target.onCompleted();
            } catch (RuntimeException ignored) {
                // Stream war schon zu - nichts zu tun.
            }
            channel = null;
        }

        public String name() {
            return name;
        }

        public RegisterInfo info() {
            return info;
        }

        public Instant lastHeartbeat() {
            return lastHeartbeat;
        }

        public double cpuLoad() {
            return cpuLoad;
        }
    }
}
