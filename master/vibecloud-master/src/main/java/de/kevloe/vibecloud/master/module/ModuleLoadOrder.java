package de.kevloe.vibecloud.master.module;

import de.kevloe.vibecloud.module.ModuleDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bestimmt, in welcher Reihenfolge Module aktiviert werden (PLAN.md Abschnitt 10).
 *
 * <p>Topologisch nach {@code depends} und {@code softDepends}: Ein Modul wird erst aktiviert,
 * wenn alles aktiv ist, wovon es abhaengt. Dadurch darf ein Modul in {@code onEnable}
 * davon ausgehen, dass seine Abhaengigkeiten stehen.
 *
 * <p>Reine Funktion ohne Dateisystem und ClassLoader - damit vollstaendig testbar. Die
 * Reihenfolge-Logik ist genau die Art Code, die bei einem Fehler erst in einer bestimmten
 * Modul-Kombination auffaellt.
 */
public final class ModuleLoadOrder {

    private ModuleLoadOrder() {
    }

    /**
     * Sortiert die Module.
     *
     * @return Ergebnis mit Reihenfolge, uebersprungenen Modulen und gefundenem Zyklus
     */
    public static Result resolve(List<ModuleDescriptor> descriptors) {
        Map<String, ModuleDescriptor> byId = new HashMap<>();
        List<String> duplicates = new ArrayList<>();

        for (ModuleDescriptor descriptor : descriptors) {
            if (byId.putIfAbsent(descriptor.id(), descriptor) != null) {
                duplicates.add(descriptor.id());
            }
        }

        // Harte Abhaengigkeiten, die es nicht gibt: Das Modul kann nicht laufen.
        Map<String, String> missing = new HashMap<>();
        for (ModuleDescriptor descriptor : byId.values()) {
            for (String dependency : descriptor.depends()) {
                if (!byId.containsKey(dependency)) {
                    missing.put(descriptor.id(), dependency);
                }
            }
        }

        Optional<List<String>> cycle = findCycle(byId);
        if (cycle.isPresent()) {
            return new Result(List.of(), missing, duplicates, cycle.get());
        }

        Set<String> done = new LinkedHashSet<>();
        for (String id : byId.keySet().stream().sorted().toList()) {
            visit(id, byId, missing.keySet(), done, new LinkedHashSet<>());
        }

        List<ModuleDescriptor> order = done.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .toList();

        return new Result(order, missing, duplicates, List.of());
    }

    private static void visit(String id, Map<String, ModuleDescriptor> byId,
                              Set<String> unusable, Set<String> done, Set<String> onPath) {
        if (done.contains(id) || unusable.contains(id) || !byId.containsKey(id)) {
            return;
        }
        if (!onPath.add(id)) {
            // Zyklus - wird vorher erkannt, hier nur als Sicherheitsnetz.
            return;
        }
        for (String dependency : byId.get(id).allDependencies()) {
            visit(dependency, byId, unusable, done, onPath);
        }
        onPath.remove(id);
        done.add(id);
    }

    /**
     * Sucht einen Zyklus.
     *
     * <p>Beruecksichtigt auch {@code softDepends}: Eine weiche Abhaengigkeit aendert nichts
     * daran, dass zwei Module nicht beide zuerst aktiviert werden koennen.
     */
    static Optional<List<String>> findCycle(Map<String, ModuleDescriptor> byId) {
        Set<String> finished = new LinkedHashSet<>();

        for (String start : byId.keySet().stream().sorted().toList()) {
            Optional<List<String>> cycle = walk(start, byId, new LinkedHashSet<>(), finished,
                    new ArrayList<>());
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> walk(String id, Map<String, ModuleDescriptor> byId,
                                               Set<String> onPath, Set<String> finished,
                                               List<String> path) {
        if (finished.contains(id) || !byId.containsKey(id)) {
            return Optional.empty();
        }
        if (!onPath.add(id)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(id);
            return Optional.of(cycle);
        }
        path.add(id);

        for (String dependency : byId.get(id).allDependencies()) {
            Optional<List<String>> cycle = walk(dependency, byId, onPath, finished, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }

        path.removeLast();
        onPath.remove(id);
        finished.add(id);
        return Optional.empty();
    }

    /**
     * Ergebnis der Auflosung.
     *
     * @param order      Aktivierungsreihenfolge
     * @param missing    Modul-Id -> fehlende harte Abhaengigkeit
     * @param duplicates mehrfach vorhandene Ids
     * @param cycle      gefundener Zyklus, leer wenn keiner
     */
    public record Result(
            List<ModuleDescriptor> order,
            Map<String, String> missing,
            List<String> duplicates,
            List<String> cycle) {

        public boolean hasCycle() {
            return !cycle.isEmpty();
        }

        public boolean isUsable() {
            return !hasCycle();
        }
    }
}
