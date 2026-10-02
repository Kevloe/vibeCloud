package de.kevloe.vibecloud.module;

import java.util.List;
import java.util.Map;

/**
 * Der Inhalt von {@code module.json} (PLAN.md Abschnitt 10).
 *
 * @param id          eindeutige Kennung, auch Praefix fuer Nachrichten-Schluessel und
 *                    Kanal-Schluessel
 * @param main        Klasse, die {@link CloudModule} implementiert
 * @param apiVersion  gegen welche Modul-API gebaut wurde
 * @param depends     Module, die vorher aktiv sein muessen; fehlt eines, startet dieses
 *                    Modul nicht
 * @param softDepends Module, die vorher aktiv sein sollen, wenn vorhanden
 * @param exports     Pakete, die andere Module sehen duerfen - noetig, damit ein Modul
 *                    eigene Events werfen kann, auf die andere reagieren
 *                    (PLAN.md Abschnitt 10a)
 * @param bundles     Plattform -> Pfad im JAR, z. B. {@code paper -> bundles/paper.jar}
 */
public record ModuleDescriptor(
        String id,
        String name,
        String version,
        String main,
        String apiVersion,
        List<String> depends,
        List<String> softDepends,
        List<String> exports,
        Map<String, String> bundles) {

    public ModuleDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("module.json: id fehlt");
        }
        if (main == null || main.isBlank()) {
            throw new IllegalArgumentException("module.json: main fehlt (Hauptklasse)");
        }
        depends = depends == null ? List.of() : List.copyOf(depends);
        softDepends = softDepends == null ? List.of() : List.copyOf(softDepends);
        exports = exports == null ? List.of() : List.copyOf(exports);
        bundles = bundles == null ? Map.of() : Map.copyOf(bundles);
        name = name == null || name.isBlank() ? id : name;
        version = version == null || version.isBlank() ? "0.0.0" : version;
        apiVersion = apiVersion == null || apiVersion.isBlank() ? "1.0" : apiVersion;
    }

    /** Harte und weiche Abhaengigkeiten zusammen - fuer die Reihenfolge-Auflosung. */
    public List<String> allDependencies() {
        return java.util.stream.Stream.concat(depends.stream(), softDepends.stream()).toList();
    }

    /** Ob ein Paket fuer andere Module sichtbar ist. */
    public boolean exportsPackage(String packageName) {
        return exports.stream().anyMatch(exported ->
                packageName.equals(exported) || packageName.startsWith(exported + "."));
    }
}
