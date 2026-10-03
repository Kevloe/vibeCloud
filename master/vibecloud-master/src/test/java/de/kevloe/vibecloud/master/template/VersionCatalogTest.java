package de.kevloe.vibecloud.master.template;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * Welche Versionen waehlbar sind und was jede braucht.
 *
 * <p>Die Antwort ist ein Ausschnitt der echten von {@code fill.papermc.io/v3/projects/paper/
 * versions} (abgerufen am 2026-10-03), gekuerzt um die Flags.
 */
class VersionCatalogTest {

    private static final String PAPER = """
            {"versions":[
              {"version":{"id":"26.3","support":{"status":"SUPPORTED"},"java":{"version":{"minimum":25}}}},
              {"version":{"id":"26.3-rc-3","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":25}}}},
              {"version":{"id":"26.2","support":{"status":"SUPPORTED"},"java":{"version":{"minimum":25}}}},
              {"version":{"id":"26.1.2","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":25}}}},
              {"version":{"id":"1.21.11","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":21}}}},
              {"version":{"id":"1.21.11-rc3","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":21}}}},
              {"version":{"id":"1.20.4","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":17}}}},
              {"version":{"id":"1.17.1","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":16}}}},
              {"version":{"id":"1.16.5","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":8}}}},
              {"version":{"id":"1.16.1","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":8}}}},
              {"version":{"id":"1.15.2","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":8}}}},
              {"version":{"id":"1.8.8","support":{"status":"UNSUPPORTED"},"java":{"version":{"minimum":8}}}}
            ]}""";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),
            ZoneOffset.UTC);

    private static VersionCatalog catalog() {
        return new VersionCatalog(project -> PAPER, CLOCK);
    }

    private static List<String> ids(List<VersionCatalog.Version> versions) {
        return versions.stream().map(VersionCatalog.Version::id).toList();
    }

    private static ServerGroup paper(String version) {
        return ServerGroup.defaults("bedwars", ServerPlatformType.PAPER, "bedwars")
                .withMcVersion(version);
    }

    @Test
    @DisplayName("Waehlbar sind Releases ab 1.16, neueste zuerst")
    void waehlbar() {
        assertThat(ids(catalog().versions(ServerPlatformType.PAPER)))
                .containsExactly("26.3", "26.2", "26.1.2", "1.21.11", "1.20.4", "1.17.1",
                        "1.16.5", "1.16.1");
    }

    @Test
    @DisplayName("Java ist das Hoehere aus API und Plugin")
    void java() {
        var versions = catalog().versions(ServerPlatformType.PAPER);
        assertThat(versions).filteredOn(version -> version.id().equals("1.16.5"))
                .singleElement()
                .satisfies(version -> {
                    // Die API sagt 8, das Legacy-Plugin braucht 17.
                    assertThat(version.javaMinimum()).isEqualTo(8);
                    assertThat(version.java()).isEqualTo(17);
                    assertThat(version.legacy()).isTrue();
                });
        assertThat(versions).filteredOn(version -> version.id().equals("1.21.11"))
                .singleElement()
                .satisfies(version -> assertThat(version.java()).isEqualTo(21));
        assertThat(versions).filteredOn(version -> version.id().equals("26.2"))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.java()).isEqualTo(25);
                    assertThat(version.legacy()).isFalse();
                    assertThat(version.supported()).isTrue();
                });
    }

    @Test
    @DisplayName("Unter 26.2 das Legacy-Plugin - auch 26.1, das lehnt api-version 26.2 ab")
    void legacyGrenze() {
        assertThat(VersionCatalog.isLegacy(ServerPlatformType.PAPER, "26.1.2")).isTrue();
        assertThat(VersionCatalog.isLegacy(ServerPlatformType.PAPER, "26.2")).isFalse();
        assertThat(VersionCatalog.isLegacy(ServerPlatformType.PAPER, "26.3")).isFalse();
        // Unbekannt (eigenes Jar ohne Angabe) heisst: das aktuelle Plugin, wie bisher.
        assertThat(VersionCatalog.isLegacy(ServerPlatformType.PAPER, "")).isFalse();
        assertThat(VersionCatalog.isLegacy(ServerPlatformType.VELOCITY, "1.16.5")).isFalse();
    }

    @Test
    @DisplayName("Paper vor 1.18 bekommt den Schalter gegen die eigene Java-Pruefung")
    void ignoreJavaVersion() {
        assertThat(VersionCatalog.extraJvmFlags(ServerPlatformType.PAPER, "1.16.5"))
                .containsExactly("-DPaper.IgnoreJavaVersion=true");
        assertThat(VersionCatalog.extraJvmFlags(ServerPlatformType.PAPER, "1.17.1"))
                .containsExactly("-DPaper.IgnoreJavaVersion=true");
        assertThat(VersionCatalog.extraJvmFlags(ServerPlatformType.PAPER, "1.18")).isEmpty();
        assertThat(VersionCatalog.extraJvmFlags(ServerPlatformType.PAPER, "26.2")).isEmpty();
    }

    @Test
    @DisplayName("Eine Version, die es gibt, geht durch")
    void gueltig() {
        assertThat(catalog().problemWith(paper("1.16.5"))).isEmpty();
        assertThat(catalog().problemWith(paper("latest"))).isEmpty();
    }

    @Test
    @DisplayName("Zu alt, unbekannt oder Vorabversion wird abgelehnt - mit Grund")
    void abgelehnt() {
        assertThat(catalog().problemWith(paper("1.15.2"))).get(STRING).contains("zu alt");
        assertThat(catalog().problemWith(paper("1.16.9"))).get(STRING)
                .contains("gibt es nicht").contains("26.3");
        assertThat(catalog().problemWith(paper("1.21.11-rc3"))).get(STRING)
                .contains("keine Release-Version");
    }

    @Test
    @DisplayName("Jar-Quelle und Plattform muessen zusammenpassen")
    void quelleUndPlattform() {
        ServerGroup velocity = ServerGroup.defaults("proxy", ServerPlatformType.VELOCITY, "proxy");
        ServerGroup wrong = new ServerGroup(velocity.name(), velocity.platform(),
                velocity.staticGroup(), velocity.minOnline(), velocity.maxOnline(),
                velocity.maxPlayers(), velocity.memoryMb(), velocity.jvmFlags(),
                velocity.allowedNodes(), velocity.startPercent(), velocity.idleTimeout(),
                velocity.namePattern(), velocity.template(), "latest", "paper",
                velocity.fallback(), velocity.joinPriority(), velocity.maintenance(),
                velocity.priority());
        assertThat(catalog().problemWith(wrong)).isPresent();
    }

    @Test
    @DisplayName("Ein eigenes Jar wird nicht geprueft - die Cloud kennt es nicht")
    void eigenesJar() {
        ServerGroup group = paper("1.12.2");
        ServerGroup custom = new ServerGroup(group.name(), group.platform(), group.staticGroup(),
                group.minOnline(), group.maxOnline(), group.maxPlayers(), group.memoryMb(),
                group.jvmFlags(), group.allowedNodes(), group.startPercent(),
                group.idleTimeout(), group.namePattern(), group.template(), "1.12.2",
                "template", group.fallback(), group.joinPriority(), group.maintenance(),
                group.priority());
        assertThat(catalog().problemWith(custom)).isEmpty();
    }

    @Test
    @DisplayName("Ohne Internet wird nur die Form geprueft, die Gruppe aber angenommen")
    void ohneInternet() {
        VersionCatalog offline = new VersionCatalog(project -> {
            throw new IOException("keine Verbindung");
        }, CLOCK);
        assertThat(offline.problemWith(paper("1.16.9"))).isEmpty();
        // Die Form bleibt Pflicht: zu alt ist auch ohne Liste zu alt.
        assertThat(offline.problemWith(paper("1.12.2"))).isPresent();
    }

    @Test
    @DisplayName("Die Liste wird zwischengespeichert, nicht bei jeder Frage geholt")
    void zwischenspeicher() {
        AtomicInteger calls = new AtomicInteger();
        VersionCatalog counting = new VersionCatalog(project -> {
            calls.incrementAndGet();
            return PAPER;
        }, CLOCK);
        counting.versions(ServerPlatformType.PAPER);
        counting.versions(ServerPlatformType.PAPER);
        counting.problemWith(paper("1.16.5"));
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("Minestom hat keine Versionsliste")
    void minestom() {
        assertThat(catalog().versions(ServerPlatformType.MINESTOM)).isEmpty();
        assertThat(VersionCatalog.requiredJava(ServerPlatformType.MINESTOM, "", 0)).isZero();
    }
}
