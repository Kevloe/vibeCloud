package de.kevloe.vibecloud.master.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wer gerade im Netzwerk ist.
 *
 * <p>Der Master hatte das bisher nicht: Er kannte nur den letzten gemeldeten Server aus
 * der Datenbank, und der steht auch noch da, wenn jemand laengst offline ist. Fuer die
 * Uebersicht im Dashboard braucht es den echten Stand.
 *
 * <p><b>Nur im Speicher, und das ist richtig so.</b> Wer online ist, ist kein dauerhafter
 * Zustand - nach einem Master-Neustart melden die Proxys ihre Spieler ohnehin neu. Eine
 * Tabelle dafuer waere eine zweite Wahrheit, die nach jedem Absturz falsch waere.
 *
 * <p>Die Quelle ist immer der Proxy: Er meldet Betreten, Verlassen und jeden
 * Serverwechsel. Verliert ein Proxy die Verbindung, verschwinden seine Spieler - sie
 * sind dann fuer den Master nicht mehr nachweisbar.
 */
public final class OnlinePlayers {

    private static final Logger LOG = LoggerFactory.getLogger(OnlinePlayers.class);

    private final Map<UUID, OnlinePlayer> players = new ConcurrentHashMap<>();

    /** Jemand hat das Netzwerk betreten. */
    public void joined(UUID uuid, String name, String proxy, String server) {
        players.put(uuid, new OnlinePlayer(uuid, name, proxy, server, Instant.now()));
    }

    /** Jemand hat das Netzwerk verlassen. */
    public void left(UUID uuid) {
        players.remove(uuid);
    }

    /** Jemand hat den Server gewechselt. */
    public void switched(UUID uuid, String server) {
        players.computeIfPresent(uuid, (key, player) -> player.onServer(server));
    }

    /**
     * Ein Proxy ist weg - seine Spieler sind es damit auch.
     *
     * <p>Ohne das blieben sie fuer immer in der Liste stehen: Das Verlassen meldet der
     * Proxy, und der ist ja gerade nicht mehr da.
     */
    public void proxyGone(String proxy) {
        int before = players.size();
        players.values().removeIf(player -> player.proxy().equals(proxy));

        int removed = before - players.size();
        if (removed > 0) {
            LOG.info("{} Spieler von {} aus der Liste genommen - der Proxy ist weg",
                    removed, proxy);
        }
    }

    public Optional<OnlinePlayer> find(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public boolean isOnline(UUID uuid) {
        return players.containsKey(uuid);
    }

    public int count() {
        return players.size();
    }

    /** Alle, alphabetisch. */
    public List<OnlinePlayer> all() {
        return players.values().stream()
                .sorted(Comparator.comparing(player -> player.name().toLowerCase()))
                .toList();
    }

    /** Ein Spieler, der gerade verbunden ist. */
    public record OnlinePlayer(
            UUID uuid,
            String name,
            String proxy,
            String server,
            Instant since) {

        OnlinePlayer onServer(String newServer) {
            return new OnlinePlayer(uuid, name, proxy, newServer, since);
        }
    }
}
