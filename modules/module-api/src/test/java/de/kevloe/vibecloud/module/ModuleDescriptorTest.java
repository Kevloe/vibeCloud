package de.kevloe.vibecloud.module;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Das Modul-Manifest (PLAN.md Abschnitt 10).
 *
 * <p>Der wichtigste Teil ist {@link ModuleDescriptor#exportsPackage}: Davon haengt ab, ob
 * ein anderes Modul die Event-Klassen sehen kann. Ohne Export versteckt die
 * child-first-Isolation sie - und der Fehler sieht dann wie ein fehlendes Event aus, nicht
 * wie ein fehlender Export.
 */
class ModuleDescriptorTest {

    @Test
    void fehlendeIdWirdAbgelehnt() {
        assertThatThrownBy(() -> descriptor(null, "Main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void fehlendeHauptklasseWirdAbgelehnt() {
        assertThatThrownBy(() -> descriptor("example", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("main");
    }

    /** Fehlende Felder bekommen brauchbare Vorgaben statt eine Ausnahme zu werfen. */
    @Test
    void optionaleFelderHabenVorgaben() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "example", null, null, "Main", null, null, null, null, null);

        assertThat(descriptor.name()).isEqualTo("example");
        assertThat(descriptor.version()).isEqualTo("0.0.0");
        assertThat(descriptor.apiVersion()).isEqualTo("1.0");
        assertThat(descriptor.depends()).isEmpty();
        assertThat(descriptor.exports()).isEmpty();
        assertThat(descriptor.bundles()).isEmpty();
    }

    @Test
    void harteUndWeicheAbhaengigkeitenZusammen() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "discord", "Discord", "1.0", "Main", "1.0",
                List.of("punishment"), List.of("party"), List.of(), Map.of());

        assertThat(descriptor.allDependencies()).containsExactly("punishment", "party");
    }

    @Test
    void exportiertesPaketIstSichtbar() {
        ModuleDescriptor descriptor = withExports("de.kevloe.vibecloud.punishment.api");

        assertThat(descriptor.exportsPackage("de.kevloe.vibecloud.punishment.api")).isTrue();
    }

    /** Unterpakete eines Exports sind mit exportiert. */
    @Test
    void unterpaketeSindMitExportiert() {
        ModuleDescriptor descriptor = withExports("de.kevloe.vibecloud.punishment.api");

        assertThat(descriptor.exportsPackage("de.kevloe.vibecloud.punishment.api.events"))
                .isTrue();
    }

    /** Nicht exportierte Pakete bleiben verborgen - das ist der Sinn der Isolation. */
    @Test
    void nichtExportiertePaketeBleibenVerborgen() {
        ModuleDescriptor descriptor = withExports("de.kevloe.vibecloud.punishment.api");

        assertThat(descriptor.exportsPackage("de.kevloe.vibecloud.punishment")).isFalse();
        assertThat(descriptor.exportsPackage("de.kevloe.vibecloud.punishment.internal"))
                .isFalse();
    }

    /**
     * Ein Praefix-Treffer ohne Punktgrenze darf nicht zaehlen: Wer
     * {@code ...punishment.api} exportiert, exportiert nicht {@code ...punishment.apifoo}.
     */
    @Test
    void praefixTrefferOhnePunktgrenzeZaehltNicht() {
        ModuleDescriptor descriptor = withExports("de.kevloe.vibecloud.punishment.api");

        assertThat(descriptor.exportsPackage("de.kevloe.vibecloud.punishment.apifoo"))
                .isFalse();
    }

    @Test
    void ohneExportsIstNichtsSichtbar() {
        ModuleDescriptor descriptor = descriptor("example", "Main");

        assertThat(descriptor.exportsPackage("de.kevloe.vibecloud.example.api")).isFalse();
    }

    @Test
    void mehrereExportsWerdenAlleBeruecksichtigt() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "example", "Example", "1.0", "Main", "1.0",
                List.of(), List.of(),
                List.of("de.kevloe.a", "de.kevloe.b"), Map.of());

        assertThat(descriptor.exportsPackage("de.kevloe.a")).isTrue();
        assertThat(descriptor.exportsPackage("de.kevloe.b.sub")).isTrue();
        assertThat(descriptor.exportsPackage("de.kevloe.c")).isFalse();
    }

    private static ModuleDescriptor descriptor(String id, String main) {
        return new ModuleDescriptor(id, "Name", "1.0", main, "1.0",
                List.of(), List.of(), List.of(), Map.of());
    }

    private static ModuleDescriptor withExports(String... exports) {
        return new ModuleDescriptor("punishment", "Punishment", "1.0", "Main", "1.0",
                List.of(), List.of(), List.of(exports), Map.of());
    }
}
