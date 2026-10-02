package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.protocol.TransferPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Spieler von einem Server auf einen anderen schicken (PLAN.md Abschnitt 11).
 *
 * <p>Die eine Stelle dafuer - benutzt von der Konsole, von Befehlen im Spiel, von Plugins
 * ueber gRPC und von der REST-Schnittstelle. Ohne sie waere derselbe Ablauf viermal
 * geschrieben und dreimal halb richtig.
 *
 * <p><b>Verschoben wird nicht hier.</b> Der Master schickt den Befehl an alle Proxys;
 * derjenige, der den Spieler gerade hat, fuehrt ihn aus. Nur der Proxy weiss, wer wo ist -
 * der Master kennt den letzten gemeldeten Stand, und der kann eine Sekunde alt sein.
 */
public final class PlayerTransferService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerTransferService.class);

    private final PluginConnectionRegistry plugins;
    private final ServerRegistry servers;
    private final AuditLog audit;

    public PlayerTransferService(PluginConnectionRegistry plugins, ServerRegistry servers,
                                 AuditLog audit) {
        this.plugins = plugins;
        this.servers = servers;
        this.audit = audit;
    }

    /**
     * Schickt einen Spieler auf einen Server.
     *
     * @param actor wer das veranlasst hat - fuer das Protokoll
     * @return Anzahl erreichter Proxys; {@code 0} heisst, dass niemand es ausfuehren kann
     */
    public int transfer(UUID uuid, String targetServer, String actor) {
        int delivered = plugins.broadcastToProxies(PluginConnectionRegistry.command(
                builder -> builder.setTransferPlayer(TransferPlayer.newBuilder()
                        .setUuid(Protos.toProto(uuid))
                        .setTargetServer(targetServer))));

        audit.record(actor, "player.transfer", uuid.toString(),
                Map.of("server", targetServer, "proxies", String.valueOf(delivered)));

        if (delivered == 0) {
            LOG.warn("Transfer von {} nach {} nicht moeglich - kein Proxy verbunden",
                    uuid, targetServer);
        } else {
            LOG.info("{} wird nach {} geschickt (von {})", uuid, targetServer, actor);
        }
        return delivered;
    }

    /**
     * Prueft, ob ein Server als Ziel taugt.
     *
     * @return Begruendung, falls nicht - sonst leer
     */
    public Optional<String> rejectTarget(String targetServer) {
        Optional<CloudServer> server = servers.find(targetServer);
        if (server.isEmpty()) {
            return Optional.of("Unbekannter Server: " + targetServer);
        }
        ServerState state = server.get().state();
        if (!state.isActive()) {
            // Ein Spieler auf einem stoppenden oder abgestuerzten Server landet im Nichts.
            return Optional.of(targetServer + " ist nicht bereit (" + state + ")");
        }
        if (server.get().platform() == ServerPlatformType.VELOCITY) {
            // Der Proxy ist kein Ziel, sondern der Weg dorthin - jeder Spieler ist schon
            // ueber ihn verbunden.
            return Optional.of(targetServer + " ist ein Proxy, kein Ziel");
        }
        return Optional.empty();
    }
}
