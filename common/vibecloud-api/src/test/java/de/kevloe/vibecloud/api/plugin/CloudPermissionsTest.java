package de.kevloe.vibecloud.api.plugin;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.protocol.PermissionRule;
import de.kevloe.vibecloud.protocol.PlayerData;
import de.kevloe.vibecloud.protocol.RankData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Rechte-Cache der Plugins und sein Snapshot auf der Platte.
 *
 * <p>Zwei Zusagen werden hier geprueft: dass die Auswertung den Kontext dieses Servers
 * beruecksichtigt, und dass ein unbekannter Spieler <b>keine</b> Rechte hat - ein Ausfall
 * darf nie Rechte erweitern.
 */
class CloudPermissionsTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path directory;

    private final CloudPermissions permissions = new CloudPermissions("lobby", "lobby-1");

    @Test
    void unbekannterSpielerHatKeineRechte() {
        assertThat(permissions.knows(PLAYER)).isFalse();
        assertThat(permissions.has(PLAYER, "irgendwas")).isFalse();
        assertThat(permissions.explain(PLAYER, "irgendwas")).isEmpty();
    }

    @Test
    void uebernimmtRegelnUndRang() {
        permissions.update(data(rule("vibecloud.language", true), rule("vip.kit", true)));

        assertThat(permissions.knows(PLAYER)).isTrue();
        assertThat(permissions.has(PLAYER, "vibecloud.language")).isTrue();
        assertThat(permissions.has(PLAYER, "vip.kit")).isTrue();
        assertThat(permissions.has(PLAYER, "nicht.vorhanden")).isFalse();
        assertThat(permissions.rankOf(PLAYER)).isPresent();
        assertThat(permissions.rankOf(PLAYER).orElseThrow().getId()).isEqualTo("vip");
    }

    /** Eine Regel fuer einen anderen Server darf hier nicht gelten. */
    @Test
    void beachtetDenKontextDiesesServers() {
        PermissionRule fuerDiesenServer = PermissionRule.newBuilder()
                .setNode("hier.erlaubt").setValue(true)
                .setGroup("lobby").setServer("lobby-1")
                .setTier("PLAYER").build();
        PermissionRule fuerAnderenServer = PermissionRule.newBuilder()
                .setNode("dort.erlaubt").setValue(true)
                .setGroup("lobby").setServer("lobby-2")
                .setTier("PLAYER").build();

        permissions.update(data(fuerDiesenServer, fuerAnderenServer));

        assertThat(permissions.has(PLAYER, "hier.erlaubt")).isTrue();
        assertThat(permissions.has(PLAYER, "dort.erlaubt")).isFalse();
    }

    @Test
    void nenntDieQuelleEinerEntscheidung() {
        permissions.update(data(PermissionRule.newBuilder()
                .setNode("test.node").setValue(true)
                .setTier("INHERITED_RANK").setWeight(10).setSource("spieler")
                .build()));

        assertThat(permissions.explain(PLAYER, "test.node"))
                .get()
                .extracting(candidate -> candidate.source())
                .isEqualTo("spieler");
    }

    @Test
    void vergessenEntferntAlles() {
        permissions.update(data(rule("test.node", true)));
        permissions.forget(PLAYER);

        assertThat(permissions.knows(PLAYER)).isFalse();
        assertThat(permissions.has(PLAYER, "test.node")).isFalse();
    }

    // ---------------------------------------------------------------- Snapshot

    @Test
    void snapshotUeberlebtEinenNeustart() throws IOException {
        permissions.update(data(rule("vip.kit", true), rule("-vip.fly", false)));
        Path file = directory.resolve("snapshot.bin");

        permissions.saveSnapshot(file);

        // Neuer Prozess: frische Instanz, nur die Datei als Quelle.
        CloudPermissions nachNeustart = new CloudPermissions("lobby", "lobby-1");
        int loaded = nachNeustart.loadSnapshot(file, Duration.ofMinutes(10));

        assertThat(loaded).isEqualTo(1);
        assertThat(nachNeustart.has(PLAYER, "vip.kit")).isTrue();
        assertThat(nachNeustart.has(PLAYER, "vip.fly")).isFalse();
        assertThat(nachNeustart.rankOf(PLAYER).orElseThrow().getPrefix())
                .isEqualTo("<gold>[VIP] </gold>");
    }

    /** Ein zu alter Snapshot wird verworfen - sonst gaebe es veraltete Rechte. */
    @Test
    void verwirftZuAltenSnapshot() throws IOException {
        permissions.update(data(rule("vip.kit", true)));
        Path file = directory.resolve("snapshot.bin");
        permissions.saveSnapshot(file);

        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                Instant.now().minus(Duration.ofHours(2))));

        CloudPermissions nachNeustart = new CloudPermissions("lobby", "lobby-1");
        int loaded = nachNeustart.loadSnapshot(file, Duration.ofMinutes(10));

        assertThat(loaded).isZero();
        assertThat(nachNeustart.has(PLAYER, "vip.kit")).isFalse();
        // Und die Datei ist weg, damit sie nicht beim naechsten Start wieder auftaucht.
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void fehlenderSnapshotIstKeinFehler() {
        int loaded = permissions.loadSnapshot(directory.resolve("gibtsnicht.bin"),
                Duration.ofMinutes(10));

        assertThat(loaded).isZero();
    }

    /** Eine beschaedigte Datei darf den Proxy nicht am Start hindern. */
    @Test
    void beschaedigterSnapshotWirdIgnoriert() throws IOException {
        Path file = directory.resolve("snapshot.bin");
        Files.writeString(file, "das ist kein Protobuf");

        int loaded = permissions.loadSnapshot(file, Duration.ofMinutes(10));

        assertThat(loaded).isZero();
    }

    @Test
    void snapshotWirdAtomarGeschrieben() throws IOException {
        permissions.update(data(rule("test.node", true)));
        Path file = directory.resolve("snapshot.bin");

        permissions.saveSnapshot(file);

        assertThat(Files.exists(file)).isTrue();
        // Keine Teil-Datei zurueckgelassen.
        assertThat(Files.exists(file.resolveSibling("snapshot.bin.part"))).isFalse();
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static PermissionRule rule(String node, boolean value) {
        String cleaned = node.startsWith("-") ? node.substring(1) : node;
        return PermissionRule.newBuilder()
                .setNode(cleaned)
                .setValue(value)
                .setTier("OWN_RANK")
                .setSource("vip")
                .build();
    }

    private static PlayerData data(PermissionRule... rules) {
        PlayerData.Builder builder = PlayerData.newBuilder()
                .setUuid(Protos.toProto(PLAYER))
                .setName("TestSpieler")
                .setRank(RankData.newBuilder()
                        .setId("vip")
                        .setName("vip")
                        .setDisplayName("VIP")
                        .setPrefix("<gold>[VIP] </gold>")
                        .setWeight(10));
        for (PermissionRule rule : rules) {
            builder.addRules(rule);
        }
        return builder.build();
    }
}
