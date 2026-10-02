package de.kevloe.vibecloud.api.permission;

import java.util.Locale;

/**
 * Vergleich von Permission-Knoten (PLAN.md Abschnitt 9).
 *
 * <p>Ein Wildcard {@code vibecloud.*} deckt alles darunter ab. Ein genauerer Knoten
 * gewinnt gegen einen allgemeineren - deshalb kann man mit {@code -vibecloud.server.stop}
 * eine einzelne Ausnahme aus {@code vibecloud.*} herausnehmen.
 */
public final class PermissionNodes {

    /** Punktzahl eines Volltreffers; hoeher als jeder Wildcard. */
    private static final int EXACT_BONUS = 1_000;

    private PermissionNodes() {
    }

    /**
     * Passt die Regel auf den abgefragten Knoten?
     *
     * @param rule    der Knoten der Regel, z. B. {@code vibecloud.*}
     * @param queried der abgefragte Knoten, z. B. {@code vibecloud.server.start}
     */
    public static boolean matches(String rule, String queried) {
        String ruleNode = rule.toLowerCase(Locale.ROOT);
        String queriedNode = queried.toLowerCase(Locale.ROOT);

        if (ruleNode.equals("*")) {
            return true;
        }
        if (ruleNode.equals(queriedNode)) {
            return true;
        }
        if (!ruleNode.endsWith(".*")) {
            return false;
        }
        String prefix = ruleNode.substring(0, ruleNode.length() - 1);
        // "vibecloud." deckt "vibecloud.server.start" ab, aber nicht "vibecloudx.foo".
        return queriedNode.startsWith(prefix);
    }

    /**
     * Wie spezifisch ein Knoten ist.
     *
     * <p>Ein Volltreffer gewinnt immer gegen einen Wildcard, und ein tieferer Wildcard
     * gegen einen flacheren: {@code a.b.*} schlaegt {@code a.*} schlaegt {@code *}.
     */
    public static int specificity(String node) {
        String lower = node.toLowerCase(Locale.ROOT);
        if (lower.equals("*")) {
            return 0;
        }
        if (lower.endsWith(".*")) {
            return countSegments(lower.substring(0, lower.length() - 2));
        }
        return EXACT_BONUS + countSegments(lower);
    }

    private static int countSegments(String node) {
        if (node.isEmpty()) {
            return 0;
        }
        int segments = 1;
        for (int i = 0; i < node.length(); i++) {
            if (node.charAt(i) == '.') {
                segments++;
            }
        }
        return segments;
    }
}
