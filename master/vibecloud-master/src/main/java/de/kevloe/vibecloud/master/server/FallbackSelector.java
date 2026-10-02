package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Wohin Spieler beim Join kommen (PLAN.md Abschnitt 11, Entscheidung 17.6).
 *
 * <p>Die Auswahl:
 * <ol>
 *   <li>Gruppen mit {@code fallback = true}, nicht in Wartung, mit laufenden Servern</li>
 *   <li>nach {@code join_priority} aufsteigend</li>
 *   <li>innerhalb der Gruppe: der Server mit den <b>meisten</b> Spielern, der noch nicht
 *       voll ist</li>
 * </ol>
 *
 * <p>Das Auffuellen statt Verteilen ist Absicht: Bei leeren Lobbys wirkt ein Netzwerk
 * schnell tot, und der {@code idle_timeout} kann leere Server nicht abraeumen, wenn
 * ueberall einzelne Spieler sitzen.
 */
public final class FallbackSelector {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackSelector.class);

    private final ServerGroupRepository groups;
    private final ServerRegistry servers;

    public FallbackSelector(ServerGroupRepository groups, ServerRegistry servers) {
        this.groups = groups;
        this.servers = servers;
    }

    /**
     * Waehlt ein Einstiegsziel.
     *
     * @param exclude Server, der nicht in Frage kommt (z. B. der gerade abgestuerzte)
     * @return Servername, oder leer wenn kein Ziel verfuegbar ist
     */
    public Optional<String> choose(String exclude) {
        List<ServerGroup> candidates = groups.findAll().stream()
                .filter(ServerGroup::fallback)
                .filter(group -> !group.maintenance())
                .sorted(Comparator.comparingInt(ServerGroup::joinPriority))
                .toList();

        for (ServerGroup group : candidates) {
            Optional<CloudServer> target = servers.ofGroup(group.name()).stream()
                    .filter(server -> server.state() == ServerState.RUNNING)
                    .filter(server -> !server.name().equals(exclude))
                    .filter(server -> hasRoom(server, group))
                    // Auffuellen: der vollste Server, der noch Platz hat.
                    .max(Comparator.comparingInt(CloudServer::players));

            if (target.isPresent()) {
                return target.map(CloudServer::name);
            }
        }

        if (candidates.isEmpty()) {
            LOG.warn("Keine Gruppe ist als Join-Ziel markiert - Spieler koennen nicht joinen. "
                     + "Abhilfe: group edit <gruppe> fallback true");
        }
        return Optional.empty();
    }

    public Optional<String> choose() {
        return choose(null);
    }

    private static boolean hasRoom(CloudServer server, ServerGroup group) {
        return group.maxPlayers() <= 0 || server.players() < group.maxPlayers();
    }

    /** Gesamtzahl der Spieler im Netzwerk, aus Sicht des Masters. */
    public int networkPlayers() {
        return servers.all().stream().mapToInt(CloudServer::players).sum();
    }

    /** Summe der Platzkapazitaet aller laufenden Fallback-Server. */
    public int networkCapacity() {
        int capacity = 0;
        for (ServerGroup group : groups.findAll()) {
            long running = servers.ofGroup(group.name()).stream()
                    .filter(server -> server.state() == ServerState.RUNNING)
                    .count();
            capacity += (int) running * group.maxPlayers();
        }
        return capacity;
    }
}
