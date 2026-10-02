package de.kevloe.vibecloud.api.permission;

import java.time.Instant;

/**
 * Eine einzelne Permission-Regel.
 *
 * @param node    der Knoten, ohne fuehrendes Minus - die Negation steht in {@code value}
 * @param value   {@code true} erlaubt, {@code false} verbietet
 * @param context wo die Regel gilt
 * @param expires wann sie verfaellt, {@code null} = nie
 */
public record PermissionEntry(
        String node,
        boolean value,
        PermissionContext context,
        Instant expires) {

    /**
     * Liest die uebliche Schreibweise: ein fuehrendes {@code -} bedeutet Verbot.
     *
     * <p>So kann man {@code -vibecloud.server.stop} eintippen statt zwei Felder zu pflegen.
     */
    public static PermissionEntry parse(String raw, PermissionContext context, Instant expires) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("-")) {
            return new PermissionEntry(trimmed.substring(1).toLowerCase(java.util.Locale.ROOT),
                    false, context, expires);
        }
        return new PermissionEntry(trimmed.toLowerCase(java.util.Locale.ROOT),
                true, context, expires);
    }

    public static PermissionEntry of(String node, boolean value) {
        return new PermissionEntry(node.toLowerCase(java.util.Locale.ROOT), value,
                PermissionContext.GLOBAL, null);
    }

    public boolean isExpired(Instant now) {
        return expires != null && now.isAfter(expires);
    }

    /** Wie spezifisch der Knoten ist - ein Wildcard ist unspezifischer als ein Volltreffer. */
    public int nodeSpecificity() {
        return PermissionNodes.specificity(node);
    }

    public boolean matches(String queried) {
        return PermissionNodes.matches(node, queried);
    }

    @Override
    public String toString() {
        return (value ? "" : "-") + node + " (" + context + ")";
    }
}
