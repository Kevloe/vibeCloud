package de.kevloe.vibecloud.api.permission;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loest den Rang eines Spielers samt Vererbung zu einer Rechteliste auf
 * (PLAN.md Abschnitt 9).
 *
 * <p>Bewusst ohne Datenbank, Cache und Uhr als Abhaengigkeit: Diese Logik ist der Teil, der
 * bei einem Fehler still falsche Rechte vergibt. Als reine Funktion laesst sie sich
 * vollstaendig durchtesten, und genau das passiert in {@code PermissionResolverTest}.
 */
public final class PermissionResolver {

    /** Schutz gegen entartete Hierarchien; ein Zyklus wird separat erkannt. */
    private static final int MAX_DEPTH = 32;

    private PermissionResolver() {
    }

    /**
     * Baut die Kandidatenliste.
     *
     * @param ownRank           Rang des Spielers
     * @param ranksById         alle bekannten Raenge
     * @param inheritance       Rang-Id -> direkte Elternraenge
     * @param rankPermissions   Rang-Id -> Regeln dieses Rangs
     * @param playerPermissions direkt beim Spieler eingetragene Regeln
     */
    public static ResolvedPermissions resolve(
            String ownRank,
            Map<String, RankInfo> ranksById,
            Map<String, List<String>> inheritance,
            Map<String, List<PermissionEntry>> rankPermissions,
            List<PermissionEntry> playerPermissions) {

        List<ResolvedPermissions.Candidate> candidates = new ArrayList<>();

        // Geerbte Raenge nach weight aufsteigend: Ein hoeheres weight gewinnt, steht also
        // spaeter und damit hoeher in der Rangfolge.
        List<String> inherited = inheritedRanks(ownRank, inheritance).stream()
                .filter(id -> !id.equals(ownRank))
                .sorted(Comparator.comparingInt(id -> weightOf(id, ranksById)))
                .toList();

        for (String rankId : inherited) {
            addRankEntries(candidates, rankId, ranksById, rankPermissions,
                    ResolvedPermissions.Tier.INHERITED_RANK);
        }

        // Der eigene Rang zuletzt - er gewinnt gegen geerbte, unabhaengig von deren weight.
        addRankEntries(candidates, ownRank, ranksById, rankPermissions,
                ResolvedPermissions.Tier.OWN_RANK);

        for (PermissionEntry entry : playerPermissions) {
            candidates.add(new ResolvedPermissions.Candidate(
                    entry, ResolvedPermissions.Tier.PLAYER, 0, "Spieler"));
        }

        return new ResolvedPermissions(candidates);
    }

    private static void addRankEntries(
            List<ResolvedPermissions.Candidate> candidates,
            String rankId,
            Map<String, RankInfo> ranksById,
            Map<String, List<PermissionEntry>> rankPermissions,
            ResolvedPermissions.Tier tier) {

        int weight = weightOf(rankId, ranksById);
        String label = Optional.ofNullable(ranksById.get(rankId))
                .map(RankInfo::name)
                .orElse(rankId);

        for (PermissionEntry entry : rankPermissions.getOrDefault(rankId, List.of())) {
            candidates.add(new ResolvedPermissions.Candidate(entry, tier, weight, label));
        }
    }

    /**
     * Alle Raenge, von denen {@code rankId} direkt oder indirekt erbt - Breitensuche
     * ueber den Graphen, inklusive des Startrangs.
     *
     * <p>Schon besuchte Knoten werden uebersprungen: Bei Mehrfachvererbung erreicht man
     * denselben Rang ueber mehrere Wege, soll ihn aber nur einmal anwenden.
     */
    public static Set<String> inheritedRanks(String rankId, Map<String, List<String>> inheritance) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rankId);

        int depth = 0;
        while (!queue.isEmpty() && depth++ < MAX_DEPTH * MAX_DEPTH) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            inheritance.getOrDefault(current, List.of()).forEach(queue::add);
        }
        return seen;
    }

    /**
     * Sucht einen Zyklus in der Vererbung.
     *
     * <p>Wird <b>vor</b> dem Speichern einer Vererbung geprueft. Ein Zyklus zur Laufzeit
     * zu bemerken waere zu spaet: Die Auflosung wuerde entweder haengen oder stillschweigend
     * unvollstaendige Rechte liefern.
     *
     * @return der gefundene Pfad, oder leer wenn der Graph in Ordnung ist
     */
    public static Optional<List<String>> findCycle(Map<String, List<String>> inheritance) {
        Set<String> finished = new LinkedHashSet<>();

        for (String start : inheritance.keySet()) {
            List<String> path = new ArrayList<>();
            Optional<List<String>> cycle = walk(start, inheritance, new LinkedHashSet<>(),
                    finished, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> walk(
            String current,
            Map<String, List<String>> inheritance,
            Set<String> onPath,
            Set<String> finished,
            List<String> path) {

        if (finished.contains(current)) {
            return Optional.empty();
        }
        if (!onPath.add(current)) {
            // Zurueck auf einem Knoten, der im aktuellen Pfad liegt: das ist der Zyklus.
            List<String> cycle = new ArrayList<>(path);
            cycle.add(current);
            return Optional.of(cycle);
        }
        path.add(current);

        for (String parent : inheritance.getOrDefault(current, List.of())) {
            Optional<List<String>> cycle = walk(parent, inheritance, onPath, finished, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }

        // Nicht removeLast(): Das gibt es erst ab Java 21, und diese Datei baut auch das
        // Legacy-Plugin mit --release 17.
        path.remove(path.size() - 1);
        onPath.remove(current);
        finished.add(current);
        return Optional.empty();
    }

    /**
     * Pruefen, ob eine neue Vererbung einen Zyklus erzeugen wuerde.
     *
     * @return {@code true} wenn {@code child erbt von parent} erlaubt ist
     */
    public static boolean canInherit(String child, String parent,
                                     Map<String, List<String>> inheritance) {
        if (child.equals(parent)) {
            return false;
        }
        // Erbt der geplante Elternrang schon (indirekt) vom Kind, entsteht ein Zyklus.
        return !inheritedRanks(parent, inheritance).contains(child);
    }

    private static int weightOf(String rankId, Map<String, RankInfo> ranksById) {
        return Optional.ofNullable(ranksById.get(rankId)).map(RankInfo::weight).orElse(0);
    }

    /** Das, was der Resolver von einem Rang wissen muss. */
    public record RankInfo(String id, String name, int weight) {
    }
}
