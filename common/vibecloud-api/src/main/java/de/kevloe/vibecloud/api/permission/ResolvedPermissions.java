package de.kevloe.vibecloud.api.permission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Die aufgeloesten Rechte eines Spielers (PLAN.md Abschnitt 9).
 *
 * <p>Enthaelt bewusst noch alle Kandidaten und nicht ein fertiges Ja/Nein je Knoten: Welche
 * Regel gilt, haengt vom Kontext ab, und der wechselt mit jedem Serverwechsel. Eine
 * Vorberechnung pro Server waere die gleiche Arbeit, nur oefter.
 *
 * <h2>Welche Regel gewinnt</h2>
 * In dieser Reihenfolge, der erste Unterschied entscheidet:
 * <ol>
 *   <li><b>Knoten-Genauigkeit</b> - ein Volltreffer schlaegt {@code a.b.*} schlaegt {@code a.*}</li>
 *   <li><b>Kontext-Genauigkeit</b> - Server schlaegt Gruppe schlaegt global</li>
 *   <li><b>Ebene</b> - Spieler schlaegt eigenen Rang schlaegt geerbte Raenge (nach weight)</li>
 *   <li><b>Verbot</b> - bei sonst gleichem Stand gewinnt das Verbot</li>
 * </ol>
 *
 * <p><b>Praezisierung gegenueber PLAN.md Abschnitt 9:</b> Dort steht, Negationen gewinnen
 * "immer" und Spieler-Permissions schlagen "immer" den Rang. Beides gleichzeitig ist nicht
 * widerspruchsfrei - bei einem Rang-Verbot und einer Spieler-Erlaubnis muesste beides
 * gewinnen. Aufgeloest wird es ueber die Genauigkeit: Die genauere Regel gewinnt, und erst
 * bei gleicher Genauigkeit gewinnt das Verbot.
 *
 * <p>Damit verhalten sich alle Beispiele des Plans wie beschrieben:
 * {@code -vibecloud.rank.set} auf einem Spieler sticht {@code vibecloud.*} aus dem
 * Admin-Rang (genauerer Knoten), und ein zusaetzliches {@code worldedit.use} auf einem
 * Spieler gilt, obwohl der Rang davon nichts weiss.
 */
public final class ResolvedPermissions {

    /** Ebene einer Regel. Hoeher gewinnt. */
    public enum Tier {
        /** Von einem geerbten Rang. */
        INHERITED_RANK,
        /** Vom eigenen Rang des Spielers. */
        OWN_RANK,
        /** Direkt beim Spieler eingetragen. */
        PLAYER
    }

    /**
     * Eine Regel mit ihrer Herkunft.
     *
     * @param source Name der Quelle, fuer {@code /perm check} - damit nachvollziehbar ist,
     *               warum ein Recht gilt
     * @param weight {@code weight} des Rangs; bei Spieler-Regeln ohne Bedeutung
     */
    public record Candidate(
            PermissionEntry entry,
            Tier tier,
            int weight,
            String source) {
    }

    private final List<Candidate> candidates;

    public ResolvedPermissions(List<Candidate> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    public static ResolvedPermissions empty() {
        return new ResolvedPermissions(List.of());
    }

    /** Hat der Spieler dieses Recht in dieser Umgebung? */
    public boolean has(String node, PermissionContext context) {
        return decide(node, context).map(candidate -> candidate.entry().value()).orElse(false);
    }

    public boolean has(String node) {
        return has(node, PermissionContext.GLOBAL);
    }

    /**
     * Welche Regel entscheidet - samt Herkunft.
     *
     * <p>Das ist die Grundlage von {@code /perm check}: Es soll nicht nur "ja" oder "nein"
     * herauskommen, sondern auch woher das kommt. Ohne das sucht man bei einem falschen
     * Recht in der ganzen Rang-Hierarchie.
     */
    public Optional<Candidate> decide(String node, PermissionContext context) {
        Instant now = Instant.now();

        return candidates.stream()
                .filter(candidate -> !candidate.entry().isExpired(now))
                .filter(candidate -> candidate.entry().matches(node))
                .filter(candidate -> candidate.entry().context().appliesTo(context))
                .max(PRECEDENCE);
    }

    /**
     * Reihenfolge der Vorrangregeln. Bewusst als Comparator, damit die Regel an genau einer
     * Stelle steht und testbar ist.
     */
    private static final Comparator<Candidate> PRECEDENCE =
            Comparator.<Candidate>comparingInt(candidate -> candidate.entry().nodeSpecificity())
                    .thenComparingInt(candidate -> candidate.entry().context().specificity())
                    .thenComparing(Candidate::tier)
                    .thenComparingInt(candidate -> candidate.tier() == Tier.INHERITED_RANK
                            ? candidate.weight() : 0)
                    // Bei sonst gleichem Stand gewinnt das Verbot.
                    .thenComparing(candidate -> !candidate.entry().value());

    /**
     * Alle Rechte, die in dieser Umgebung gelten - fuer die Permission-Bridges der Plugins.
     *
     * <p>Wildcards koennen nicht aufgezaehlt werden, deshalb sind hier nur die genau
     * benannten Knoten enthalten. Fuer {@code hasPermission(...)} fragt das Plugin
     * {@link #has(String, PermissionContext)}, nicht diese Liste.
     */
    public List<String> effectiveNodes(PermissionContext context) {
        Instant now = Instant.now();
        List<String> nodes = new ArrayList<>();

        candidates.stream()
                .filter(candidate -> !candidate.entry().isExpired(now))
                .filter(candidate -> candidate.entry().context().appliesTo(context))
                .map(candidate -> candidate.entry().node())
                .filter(node -> !node.contains("*"))
                .distinct()
                .forEach(node -> {
                    if (has(node, context)) {
                        nodes.add(node);
                    }
                });
        return nodes;
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }
}
