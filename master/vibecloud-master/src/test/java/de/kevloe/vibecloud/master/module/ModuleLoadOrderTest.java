package de.kevloe.vibecloud.master.module;

import de.kevloe.vibecloud.module.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Ladereihenfolge der Module (PLAN.md Abschnitt 10).
 *
 * <p>Ein Fehler hier faellt erst in einer bestimmten Modul-Kombination auf - und dann
 * sieht es aus wie ein Fehler im Modul, nicht in der Reihenfolge. Deshalb jeder Fall
 * einzeln.
 */
class ModuleLoadOrderTest {

    @Test
    void ohneAbhaengigkeitenBleibenAlleDrin() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("a"), module("b"), module("c")));

        assertThat(result.isUsable()).isTrue();
        assertThat(ids(result)).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void abhaengigkeitKommtVorher() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("discord", List.of("punishment")),
                module("punishment")));

        assertThat(ids(result)).containsExactly("punishment", "discord");
    }

    @Test
    void laengereKetteWirdRichtigSortiert() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("d", List.of("c")),
                module("c", List.of("b")),
                module("b", List.of("a")),
                module("a")));

        assertThat(ids(result)).containsExactly("a", "b", "c", "d");
    }

    /** Mehrere Abhaengigkeiten: alle muessen vorher kommen. */
    @Test
    void mehrfacheAbhaengigkeitenKommenAlleVorher() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("party", List.of("friends", "punishment")),
                module("friends"),
                module("punishment")));

        List<String> order = ids(result);
        assertThat(order.indexOf("friends")).isLessThan(order.indexOf("party"));
        assertThat(order.indexOf("punishment")).isLessThan(order.indexOf("party"));
    }

    /** Weiche Abhaengigkeiten bestimmen die Reihenfolge mit, sind aber nicht Pflicht. */
    @Test
    void weicheAbhaengigkeitSortiertMitIstAberNichtPflicht() {
        var vorhanden = ModuleLoadOrder.resolve(List.of(
                soft("discord", List.of("punishment")),
                module("punishment")));
        assertThat(ids(vorhanden)).containsExactly("punishment", "discord");

        // Fehlt sie, laeuft das Modul trotzdem.
        var fehlt = ModuleLoadOrder.resolve(List.of(soft("discord", List.of("punishment"))));
        assertThat(ids(fehlt)).containsExactly("discord");
        assertThat(fehlt.missing()).isEmpty();
    }

    /** Eine fehlende harte Abhaengigkeit schliesst das Modul aus - aber nur dieses. */
    @Test
    void fehlendeHarteAbhaengigkeitSchliesstNurDiesesModulAus() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("discord", List.of("gibtsnicht")),
                module("punishment")));

        assertThat(result.missing()).containsEntry("discord", "gibtsnicht");
        assertThat(ids(result)).containsExactly("punishment");
    }

    @Test
    void erkenntDirektenZyklus() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("a", List.of("b")),
                module("b", List.of("a"))));

        assertThat(result.hasCycle()).isTrue();
        assertThat(result.isUsable()).isFalse();
        assertThat(result.cycle()).contains("a", "b");
    }

    @Test
    void erkenntLangenZyklus() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("a", List.of("b")),
                module("b", List.of("c")),
                module("c", List.of("a"))));

        assertThat(result.hasCycle()).isTrue();
    }

    /** Ein Zyklus ueber eine weiche Abhaengigkeit ist genauso ein Zyklus. */
    @Test
    void erkenntZyklusUeberWeicheAbhaengigkeit() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("a", List.of("b")),
                soft("b", List.of("a"))));

        assertThat(result.hasCycle()).isTrue();
    }

    /** Mehrfachvererbung in Rautenform ist kein Zyklus. */
    @Test
    void rautenFormIstKeinZyklus() {
        var result = ModuleLoadOrder.resolve(List.of(
                module("top", List.of("links", "rechts")),
                module("links", List.of("basis")),
                module("rechts", List.of("basis")),
                module("basis")));

        assertThat(result.hasCycle()).isFalse();
        List<String> order = ids(result);
        assertThat(order.indexOf("basis")).isLessThan(order.indexOf("links"));
        assertThat(order.indexOf("basis")).isLessThan(order.indexOf("rechts"));
        assertThat(order.getLast()).isEqualTo("top");
    }

    @Test
    void meldetDoppelteIds() {
        var result = ModuleLoadOrder.resolve(List.of(module("a"), module("a"), module("b")));

        assertThat(result.duplicates()).containsExactly("a");
        // Das erste gewinnt, nicht beide.
        assertThat(ids(result)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void leereListeIstKeinFehler() {
        var result = ModuleLoadOrder.resolve(List.of());

        assertThat(result.isUsable()).isTrue();
        assertThat(result.order()).isEmpty();
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static List<String> ids(ModuleLoadOrder.Result result) {
        return result.order().stream().map(ModuleDescriptor::id).toList();
    }

    private static ModuleDescriptor module(String id) {
        return module(id, List.of());
    }

    private static ModuleDescriptor module(String id, List<String> depends) {
        return new ModuleDescriptor(id, id, "1.0.0", "Main", "1.0",
                depends, List.of(), List.of(), Map.of());
    }

    private static ModuleDescriptor soft(String id, List<String> softDepends) {
        return new ModuleDescriptor(id, id, "1.0.0", "Main", "1.0",
                List.of(), softDepends, List.of(), Map.of());
    }
}
