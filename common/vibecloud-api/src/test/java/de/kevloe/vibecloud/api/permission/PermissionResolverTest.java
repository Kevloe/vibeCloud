package de.kevloe.vibecloud.api.permission;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Permission-Auflosung (PLAN.md Abschnitt 9 und die Testliste aus Abschnitt 15).
 *
 * <p>Das ist der Teil, bei dem ein Fehler <b>still</b> falsche Rechte vergibt: Niemand
 * merkt, dass ein Spieler zu viel darf, bis er es benutzt. Deshalb hier jeder Fall
 * einzeln.
 *
 * <p>Hierarchie in den Tests:
 * <pre>
 * spieler  (weight 0,  default)
 *   +- vip        (weight 10)
 *   |    +- premium (weight 20)
 *   +- builder    (weight 15)
 *        moderator (weight 50, erbt von premium UND builder)
 *          +- admin (weight 100)
 * </pre>
 */
class PermissionResolverTest {

    private static final Map<String, PermissionResolver.RankInfo> RANKS = Map.of(
            "spieler", new PermissionResolver.RankInfo("spieler", "spieler", 0),
            "vip", new PermissionResolver.RankInfo("vip", "vip", 10),
            "builder", new PermissionResolver.RankInfo("builder", "builder", 15),
            "premium", new PermissionResolver.RankInfo("premium", "premium", 20),
            "moderator", new PermissionResolver.RankInfo("moderator", "moderator", 50),
            "admin", new PermissionResolver.RankInfo("admin", "admin", 100));

    private static final Map<String, List<String>> INHERITANCE = Map.of(
            "vip", List.of("spieler"),
            "premium", List.of("vip"),
            "builder", List.of("spieler"),
            "moderator", List.of("premium", "builder"),
            "admin", List.of("moderator"));

    // ---------------------------------------------------------------- Vererbung

    @Test
    void erbtUeberMehrereEbenen() {
        var resolved = resolve("premium", Map.of(
                "spieler", List.of(entry("vibecloud.language")),
                "vip", List.of(entry("vip.kit"))));

        assertThat(resolved.has("vibecloud.language")).isTrue();
        assertThat(resolved.has("vip.kit")).isTrue();
    }

    @Test
    void mehrfachvererbungNimmtBeideZweige() {
        var resolved = resolve("moderator", Map.of(
                "premium", List.of(entry("premium.fly")),
                "builder", List.of(entry("builder.worldedit"))));

        assertThat(resolved.has("premium.fly")).isTrue();
        assertThat(resolved.has("builder.worldedit")).isTrue();
    }

    /** Bei konkurrierenden Regeln gleicher Genauigkeit gewinnt der hoehere weight. */
    @Test
    void hoehererWeightGewinntBeiGeerbtenRaengen() {
        var resolved = resolve("moderator", Map.of(
                // builder (15) erlaubt, premium (20) verbietet -> premium gewinnt
                "builder", List.of(entry("test.node")),
                "premium", List.of(entry("-test.node"))));

        assertThat(resolved.has("test.node")).isFalse();

        var andersrum = resolve("moderator", Map.of(
                "builder", List.of(entry("-test.node")),
                "premium", List.of(entry("test.node"))));

        assertThat(andersrum.has("test.node")).isTrue();
    }

    /** Der eigene Rang gewinnt gegen geerbte, unabhaengig von deren weight. */
    @Test
    void eigenerRangGewinntGegenGeerbte() {
        var resolved = resolve("vip", Map.of(
                "spieler", List.of(entry("test.node")),
                "vip", List.of(entry("-test.node"))));

        assertThat(resolved.has("test.node")).isFalse();
    }

    // ---------------------------------------------------------------- Spieler-Rechte

    @Test
    void spielerRechteGeltenZusaetzlichZumRang() {
        var resolved = resolve("spieler", Map.of(), List.of(entry("worldedit.use")));

        assertThat(resolved.has("worldedit.use")).isTrue();
    }

    /**
     * Der Fall aus dem Plan: Ein Admin hat {@code vibecloud.*}, einem Spieler wird
     * {@code vibecloud.rank.set} gezielt entzogen.
     */
    @Test
    void spielerVerbotStichtRangWildcard() {
        var resolved = resolve("admin",
                Map.of("admin", List.of(entry("vibecloud.*"))),
                List.of(entry("-vibecloud.rank.set")));

        assertThat(resolved.has("vibecloud.server.start")).isTrue();
        assertThat(resolved.has("vibecloud.rank.set")).isFalse();
    }

    @Test
    void spielerErlaubnisStichtRangVerbotGleicherGenauigkeit() {
        var resolved = resolve("spieler",
                Map.of("spieler", List.of(entry("-test.node"))),
                List.of(entry("test.node")));

        assertThat(resolved.has("test.node")).isTrue();
    }

    // ---------------------------------------------------------------- Wildcards

    @Test
    void wildcardDecktAllesDarunterAb() {
        var resolved = resolve("admin", Map.of("admin", List.of(entry("vibecloud.*"))));

        assertThat(resolved.has("vibecloud.server.start")).isTrue();
        assertThat(resolved.has("vibecloud.rank.set")).isTrue();
        // Aber nichts ausserhalb des Praefixes.
        assertThat(resolved.has("worldedit.use")).isFalse();
    }

    @Test
    void wildcardGreiftNichtUeberWortgrenzen() {
        var resolved = resolve("admin", Map.of("admin", List.of(entry("vibecloud.*"))));

        // "vibecloudx" darf nicht von "vibecloud.*" abgedeckt sein.
        assertThat(resolved.has("vibecloudx.etwas")).isFalse();
    }

    @Test
    void genauererWildcardGewinnt() {
        var resolved = resolve("admin", Map.of("admin", List.of(
                entry("vibecloud.*"),
                entry("-vibecloud.server.*"))));

        assertThat(resolved.has("vibecloud.rank.set")).isTrue();
        assertThat(resolved.has("vibecloud.server.stop")).isFalse();
    }

    @Test
    void sternAlleinGibtAlles() {
        var resolved = resolve("admin", Map.of("admin", List.of(entry("*"))));

        assertThat(resolved.has("irgendwas.beliebiges")).isTrue();
    }

    // ---------------------------------------------------------------- Kontexte

    @Test
    void gruppenRegelGiltNurInDerGruppe() {
        var resolved = resolve("spieler", Map.of("spieler", List.of(
                new PermissionEntry("bedwars.vip", true,
                        PermissionContext.ofGroup("bedwars"), null))));

        assertThat(resolved.has("bedwars.vip", PermissionContext.ofGroup("bedwars"))).isTrue();
        assertThat(resolved.has("bedwars.vip", PermissionContext.ofGroup("lobby"))).isFalse();
        assertThat(resolved.has("bedwars.vip", PermissionContext.GLOBAL)).isFalse();
    }

    @Test
    void serverRegelSchlaegtGruppenRegel() {
        var resolved = resolve("spieler", Map.of("spieler", List.of(
                new PermissionEntry("test.node", true,
                        PermissionContext.ofGroup("lobby"), null),
                new PermissionEntry("test.node", false,
                        PermissionContext.ofServer("lobby", "lobby-1"), null))));

        assertThat(resolved.has("test.node", PermissionContext.ofServer("lobby", "lobby-2")))
                .isTrue();
        assertThat(resolved.has("test.node", PermissionContext.ofServer("lobby", "lobby-1")))
                .isFalse();
    }

    @Test
    void globaleRegelGiltUeberall() {
        var resolved = resolve("spieler", Map.of("spieler", List.of(entry("test.node"))));

        assertThat(resolved.has("test.node", PermissionContext.GLOBAL)).isTrue();
        assertThat(resolved.has("test.node", PermissionContext.ofGroup("bedwars"))).isTrue();
        assertThat(resolved.has("test.node", PermissionContext.ofServer("lobby", "lobby-1")))
                .isTrue();
    }

    // ---------------------------------------------------------------- Ablauf

    @Test
    void abgelaufeneRegelGiltNichtMehr() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        var resolved = resolve("spieler", Map.of("spieler", List.of(
                new PermissionEntry("test.node", true, PermissionContext.GLOBAL, past))));

        assertThat(resolved.has("test.node")).isFalse();
    }

    @Test
    void nochGueltigeRegelGilt() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        var resolved = resolve("spieler", Map.of("spieler", List.of(
                new PermissionEntry("test.node", true, PermissionContext.GLOBAL, future))));

        assertThat(resolved.has("test.node")).isTrue();
    }

    // ---------------------------------------------------------------- Zyklen

    @Test
    void erkenntDirektenZyklus() {
        Map<String, List<String>> cyclic = Map.of(
                "a", List.of("b"),
                "b", List.of("a"));

        assertThat(PermissionResolver.findCycle(cyclic)).isPresent();
    }

    @Test
    void erkenntLangenZyklus() {
        Map<String, List<String>> cyclic = Map.of(
                "a", List.of("b"),
                "b", List.of("c"),
                "c", List.of("d"),
                "d", List.of("a"));

        assertThat(PermissionResolver.findCycle(cyclic)).isPresent();
    }

    @Test
    void gesunderGraphHatKeinenZyklus() {
        assertThat(PermissionResolver.findCycle(INHERITANCE)).isEmpty();
    }

    /** Mehrfachvererbung ist kein Zyklus - derselbe Rang ueber zwei Wege ist erlaubt. */
    @Test
    void rautenFormIstKeinZyklus() {
        Map<String, List<String>> diamond = Map.of(
                "moderator", List.of("premium", "builder"),
                "premium", List.of("spieler"),
                "builder", List.of("spieler"));

        assertThat(PermissionResolver.findCycle(diamond)).isEmpty();
    }

    @Test
    void lehntVererbungAbDieEinenZyklusErzeugt() {
        // spieler soll von admin erben - admin erbt aber schon von spieler.
        assertThat(PermissionResolver.canInherit("spieler", "admin", INHERITANCE)).isFalse();
        assertThat(PermissionResolver.canInherit("admin", "admin", INHERITANCE)).isFalse();
        // Umgekehrt ist es in Ordnung.
        assertThat(PermissionResolver.canInherit("vip", "builder", INHERITANCE)).isTrue();
    }

    // ---------------------------------------------------------------- Nachvollziehbarkeit

    /** {@code /perm check} muss sagen, WOHER ein Recht kommt. */
    @Test
    void nenntDieQuelleEinerEntscheidung() {
        var resolved = resolve("moderator", Map.of("builder", List.of(entry("builder.worldedit"))));

        Optional<ResolvedPermissions.Candidate> decision =
                resolved.decide("builder.worldedit", PermissionContext.GLOBAL);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().source()).isEqualTo("builder");
        assertThat(decision.orElseThrow().tier())
                .isEqualTo(ResolvedPermissions.Tier.INHERITED_RANK);
    }

    @Test
    void unbekannterKnotenGiltAlsVerboten() {
        var resolved = resolve("spieler", Map.of());

        assertThat(resolved.has("gibt.es.nicht")).isFalse();
        assertThat(resolved.decide("gibt.es.nicht", PermissionContext.GLOBAL)).isEmpty();
    }

    @Test
    void grossKleinschreibungIstEgal() {
        var resolved = resolve("spieler", Map.of("spieler", List.of(entry("Test.Node"))));

        assertThat(resolved.has("test.node")).isTrue();
        assertThat(resolved.has("TEST.NODE")).isTrue();
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static PermissionEntry entry(String raw) {
        return PermissionEntry.parse(raw, PermissionContext.GLOBAL, null);
    }

    private static ResolvedPermissions resolve(
            String ownRank, Map<String, List<PermissionEntry>> rankPermissions) {
        return resolve(ownRank, rankPermissions, List.of());
    }

    private static ResolvedPermissions resolve(
            String ownRank,
            Map<String, List<PermissionEntry>> rankPermissions,
            List<PermissionEntry> playerPermissions) {
        return PermissionResolver.resolve(ownRank, RANKS, INHERITANCE,
                rankPermissions, playerPermissions);
    }
}
