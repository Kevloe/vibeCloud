package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Welche Server als Ziel eines Wechsels taugen.
 *
 * <p>Der Teil, der falsch gehen kann: Ein Spieler, der auf einen stoppenden oder
 * abgestuerzten Server geschickt wird, landet im Nichts.
 */
class PlayerTransferServiceTest {

    private ServerRegistry servers;
    private PlayerTransferService transfers;

    @BeforeEach
    void setUp() {
        servers = new ServerRegistry();
        // Null fuer Verbindungen und Protokoll: rejectTarget liest nur die Registry.
        // Wuerde sich das aendern, scheitert dieser Test sofort und sichtbar.
        transfers = new PlayerTransferService(null, servers, null);
    }

    @Test
    @DisplayName("Ein laufender Gameserver ist ein gueltiges Ziel")
    void laufenderGameserverIstGueltig() {
        servers.put(server("lobby-1", ServerPlatformType.PAPER, ServerState.RUNNING));

        assertThat(transfers.rejectTarget("lobby-1")).isEmpty();
    }

    @Test
    @DisplayName("Ein unbekannter Server wird abgelehnt")
    void unbekannterServerWirdAbgelehnt() {
        assertThat(transfers.rejectTarget("gibtsnicht"))
                .contains("Unbekannter Server: gibtsnicht");
    }

    @Test
    @DisplayName("Ein Proxy ist kein Ziel")
    void proxyIstKeinZiel() {
        // Dorthin kann niemand geschickt werden - jeder Spieler ist schon ueber ihn
        // verbunden. Stand vorher trotzdem in der Vorschlagsliste.
        servers.put(server("proxy-1", ServerPlatformType.VELOCITY, ServerState.RUNNING));

        assertThat(transfers.rejectTarget("proxy-1").orElseThrow())
                .contains("ist ein Proxy, kein Ziel");
    }

    @Test
    @DisplayName("Ein Server, der nicht laeuft, wird abgelehnt")
    void serverDerNichtLaeuftWirdAbgelehnt() {
        servers.put(server("stopping", ServerPlatformType.PAPER, ServerState.STOPPING));
        servers.put(server("crashed", ServerPlatformType.PAPER, ServerState.CRASHED));
        servers.put(server("waiting", ServerPlatformType.PAPER,
                ServerState.WAITING_FOR_NODE));

        assertThat(transfers.rejectTarget("stopping").orElseThrow())
                .contains("ist nicht bereit");
        assertThat(transfers.rejectTarget("crashed").orElseThrow())
                .contains("ist nicht bereit");
        assertThat(transfers.rejectTarget("waiting").orElseThrow())
                .contains("ist nicht bereit");
    }

    @Test
    @DisplayName("Ein startender Server ist ein Ziel")
    void startenderServerIstEinZiel() {
        // Bis der Spieler ankommt, ist er meist fertig - und Velocity wartet beim
        // Verbinden ohnehin kurz.
        servers.put(server("lobby-2", ServerPlatformType.PAPER, ServerState.STARTING));

        assertThat(transfers.rejectTarget("lobby-2")).isEmpty();
    }

    @Test
    @DisplayName("Ein Minestom-Server ist ein Ziel")
    void minestomServerIstEinZiel() {
        servers.put(server("lobby-3", ServerPlatformType.MINESTOM, ServerState.RUNNING));

        assertThat(transfers.rejectTarget("lobby-3")).isEmpty();
    }

    private static CloudServer server(String name, ServerPlatformType platform,
                                      ServerState state) {
        return new CloudServer(name, "gruppe", platform, "node-a", 30000, state,
                false, Instant.now(), 0, 100);
    }
}
