package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Zustandsabgleich beim Reconnect (PLAN.md Abschnitt 7).
 *
 * <p>Die Regel dahinter: <b>Der Node ist die Wahrheit, nicht der Speicher des Masters.</b>
 * Nach einem Master-Neustart meldet der Wrapper, was laeuft - und alles, was der Master
 * fuer diesen Node kannte und nicht gemeldet bekommt, gilt als weg.
 */
class ServerRegistryTest {

    private static final ServerGroup LOBBY = group("lobby", 2048, false);
    private static final ServerGroup SURVIVAL = group("survival", 4096, true);

    @Test
    void uebernimmtGemeldeteServer() {
        ServerRegistry registry = new ServerRegistry();

        List<String> vanished = registry.reconcileNode("node-a",
                List.of(running("lobby-1", LOBBY, "node-a", 30000)));

        assertThat(vanished).isEmpty();
        assertThat(registry.find("lobby-1")).isPresent();
        assertThat(registry.find("lobby-1").orElseThrow().port()).isEqualTo(30000);
    }

    /** Was der Node nicht mehr meldet, ist weg - auch wenn der Master es noch kannte. */
    @Test
    void erkenntVerschwundeneServer() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(running("lobby-1", LOBBY, "node-a", 30000));
        registry.put(running("lobby-2", LOBBY, "node-a", 30001));

        List<String> vanished = registry.reconcileNode("node-a",
                List.of(running("lobby-1", LOBBY, "node-a", 30000)));

        assertThat(vanished).containsExactly("lobby-2");
        assertThat(registry.find("lobby-2")).isEmpty();
        assertThat(registry.find("lobby-1")).isPresent();
    }

    /** Ein Abgleich von node-a darf die Server von node-b nicht anfassen. */
    @Test
    void abgleichBetrifftNurDenEigenenNode() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(running("lobby-1", LOBBY, "node-a", 30000));
        registry.put(running("bedwars-1", LOBBY, "node-b", 30000));

        List<String> vanished = registry.reconcileNode("node-a", List.of());

        assertThat(vanished).containsExactly("lobby-1");
        assertThat(registry.find("bedwars-1")).isPresent();
    }

    @Test
    void dropNodeEntferntAlleServerDesNodes() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(running("lobby-1", LOBBY, "node-a", 30000));
        registry.put(running("lobby-2", LOBBY, "node-a", 30001));
        registry.put(running("lobby-3", LOBBY, "node-b", 30000));

        List<CloudServer> dropped = registry.dropNode("node-a");

        assertThat(dropped).hasSize(2);
        assertThat(registry.all()).extracting(CloudServer::name).containsExactly("lobby-3");
    }

    @Test
    void zaehltBelegtenSpeicherJeNode() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(running("lobby-1", LOBBY, "node-a", 30000));
        registry.put(running("survival-1", SURVIVAL, "node-a", 30001));
        registry.put(running("lobby-9", LOBBY, "node-b", 30000));

        Map<String, ServerGroup> groups = Map.of("lobby", LOBBY, "survival", SURVIVAL);

        assertThat(registry.usedMemoryOnNode("node-a", groups)).isEqualTo(2048 + 4096);
        assertThat(registry.usedMemoryOnNode("node-b", groups)).isEqualTo(2048);
    }

    /** Beendete Server belegen keinen Speicher mehr - sonst wuerde ein Node nie wieder frei. */
    @Test
    void beendeteServerZaehlenNichtMehrMit() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(running("lobby-1", LOBBY, "node-a", 30000));
        registry.updateState("lobby-1", ServerState.STOPPED);

        assertThat(registry.usedMemoryOnNode("node-a", Map.of("lobby", LOBBY))).isZero();
    }

    @Test
    void belegtePortsWerdenGemeldet() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(running("lobby-1", LOBBY, "node-a", 30000));
        registry.put(running("lobby-2", LOBBY, "node-a", 30001));
        registry.put(running("lobby-3", LOBBY, "node-b", 30005));

        assertThat(registry.portsOnNode("node-a")).containsExactlyInAnyOrder(30000, 30001);
        assertThat(registry.portsOnNode("node-b")).containsExactly(30005);
    }

    /** Auch hochfahrende Server gelten als belegt, sonst gibt es Namenskollisionen. */
    @Test
    void auchVorbereiteteServerBelegenIhrenNamen() {
        ServerRegistry registry = new ServerRegistry();
        registry.put(ServerRegistry.newServer("lobby-1", LOBBY, "node-a", 30000));

        assertThat(registry.find("lobby-1").orElseThrow().state())
                .isEqualTo(ServerState.PREPARING);
        assertThat(registry.takenNames()).contains("lobby-1");
        assertThat(registry.activeOfGroup("lobby")).hasSize(1);
    }

    private static CloudServer running(String name, ServerGroup group, String node, int port) {
        return new CloudServer(name, group.name(), group.platform(), node, port,
                ServerState.RUNNING, group.staticGroup(), Instant.now(), 0, group.maxPlayers());
    }

    private static ServerGroup group(String name, int memoryMb, boolean staticGroup) {
        return new ServerGroup(name, ServerPlatformType.PAPER, staticGroup,
                1, 3, 50, memoryMb, List.of(), List.of(), 0, 300,
                "%group%-%id%", name, "26.2", "paper", false, 100, false, 0);
    }
}
