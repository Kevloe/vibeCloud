package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Alle Server, die der Master kennt - im Speicher, nicht in der Datenbank.
 *
 * <p>Das ist Absicht: Die Wahrheit darueber, was laeuft, steht auf den Nodes. Nach einem
 * Master-Neustart meldet jeder Wrapper seinen {@code FullState} und der Master uebernimmt
 * daraus (PLAN.md Abschnitt 7). Eine Datenbanktabelle mit "laufenden" Servern wuerde nach
 * einem Absturz lügen, und man müsste sie beim Start ohnehin verwerfen.
 *
 * <p>Dauerhaft gespeichert wird nur, was es wert ist: {@code static_server_bindings}
 * (wo liegt die Welt) und {@code server_history} (was lief wann).
 */
public final class ServerRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ServerRegistry.class);

    private final Map<String, CloudServer> servers = new ConcurrentHashMap<>();

    public void put(CloudServer server) {
        servers.put(server.name(), server);
    }

    public Optional<CloudServer> find(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    public void remove(String name) {
        servers.remove(name);
    }

    /** Setzt den Zustand und gibt den vorherigen zurueck, falls der Server bekannt war. */
    public Optional<ServerState> updateState(String name, ServerState state) {
        CloudServer current = servers.get(name);
        if (current == null) {
            return Optional.empty();
        }
        servers.put(name, current.withState(state));
        return Optional.of(current.state());
    }

    public List<CloudServer> all() {
        return servers.values().stream()
                .sorted(Comparator.comparing(CloudServer::name))
                .toList();
    }

    public List<CloudServer> ofGroup(String group) {
        return servers.values().stream()
                .filter(server -> server.groupName().equals(group))
                .sorted(Comparator.comparing(CloudServer::name))
                .toList();
    }

    public List<CloudServer> onNode(String node) {
        return servers.values().stream()
                .filter(server -> server.node().equals(node))
                .toList();
    }

    /** Server einer Gruppe, die hochfahren oder laufen. */
    public List<CloudServer> activeOfGroup(String group) {
        return ofGroup(group).stream().filter(CloudServer::isActive).toList();
    }

    /** Alle belegten Namen - auch die gerade hochfahrenden, sonst gibt es Kollisionen. */
    public Set<String> takenNames() {
        return Set.copyOf(servers.keySet());
    }

    public Set<Integer> portsOnNode(String node) {
        return servers.values().stream()
                .filter(server -> server.node().equals(node))
                .map(CloudServer::port)
                .collect(Collectors.toSet());
    }

    /** Vom Node belegter Arbeitsspeicher, aus den Gruppen der laufenden Server. */
    public long usedMemoryOnNode(String node, Map<String, ServerGroup> groups) {
        return servers.values().stream()
                .filter(server -> server.node().equals(node))
                .filter(CloudServer::isActive)
                .mapToLong(server -> {
                    ServerGroup group = groups.get(server.groupName());
                    return group == null ? 0L : group.memoryMb();
                })
                .sum();
    }

    /**
     * Uebernimmt die Server, die ein Wrapper beim Reconnect meldet.
     *
     * <p>Alles, was der Master fuer diesen Node kannte und nicht gemeldet wurde, gilt als
     * verschwunden. Der Node ist die Wahrheit - nicht der Speicher des Masters.
     *
     * @return Namen der Server, die der Node nicht mehr hat
     */
    public List<String> reconcileNode(String node, List<CloudServer> reported) {
        Set<String> reportedNames = reported.stream()
                .map(CloudServer::name)
                .collect(Collectors.toSet());

        List<String> vanished = servers.values().stream()
                .filter(server -> server.node().equals(node))
                .map(CloudServer::name)
                .filter(name -> !reportedNames.contains(name))
                .toList();

        vanished.forEach(servers::remove);
        reported.forEach(server -> servers.put(server.name(), server));

        if (!reported.isEmpty() || !vanished.isEmpty()) {
            LOG.info("Node {}: {} Server uebernommen, {} verschwunden",
                    node, reported.size(), vanished.size());
        }
        return vanished;
    }

    /** Entfernt alle Server eines Nodes - nach einem Timeout oder Trennen. */
    public List<CloudServer> dropNode(String node) {
        List<CloudServer> affected = onNode(node);
        affected.forEach(server -> servers.remove(server.name()));
        return affected;
    }

    /** Hilfskonstruktor fuer einen neu angelegten Server. */
    public static CloudServer newServer(String name, ServerGroup group, String node, int port) {
        return new CloudServer(
                name,
                group.name(),
                group.platform(),
                node,
                port,
                ServerState.PREPARING,
                group.staticGroup(),
                Instant.now(),
                0,
                group.maxPlayers());
    }

    /** Fuer Server, die ein Wrapper gemeldet hat und deren Gruppe noch bekannt ist. */
    public static CloudServer adopted(String name, ServerGroup group, ServerPlatformType platform,
                                      String node, int port, ServerState state, Instant startedAt) {
        return new CloudServer(name, group == null ? "?" : group.name(), platform, node, port,
                state, group != null && group.staticGroup(), startedAt, 0,
                group == null ? 0 : group.maxPlayers());
    }
}
