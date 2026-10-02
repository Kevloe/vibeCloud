package de.kevloe.vibecloud.api.permission;

/**
 * Wo eine Permission gilt (PLAN.md Abschnitt 9).
 *
 * <p>Drei Stufen: ueberall, nur in einer Servergruppe, nur auf einem Server. Die
 * spezifischere gewinnt - eine Regel fuer {@code lobby-1} schlaegt eine fuer die Gruppe
 * {@code lobby}, und die schlaegt eine globale.
 *
 * @param group  Servergruppe oder {@code null}
 * @param server Servername oder {@code null}
 */
public record PermissionContext(String group, String server) {

    /** Gilt ueberall. */
    public static final PermissionContext GLOBAL = new PermissionContext(null, null);

    public static PermissionContext ofGroup(String group) {
        return new PermissionContext(group, null);
    }

    public static PermissionContext ofServer(String group, String server) {
        return new PermissionContext(group, server);
    }

    /**
     * Wie spezifisch diese Angabe ist: 0 global, 1 Gruppe, 2 Server.
     *
     * <p>Entscheidet bei konkurrierenden Regeln, welche gewinnt.
     */
    public int specificity() {
        if (server != null && !server.isBlank()) {
            return 2;
        }
        return group != null && !group.isBlank() ? 1 : 0;
    }

    /**
     * Gilt diese Regel in der abgefragten Umgebung?
     *
     * <p>Eine globale Regel gilt ueberall. Eine Gruppen-Regel gilt nur in dieser Gruppe,
     * eine Server-Regel nur auf diesem Server.
     */
    public boolean appliesTo(PermissionContext query) {
        if (group != null && !group.isBlank() && !group.equals(query.group())) {
            return false;
        }
        return server == null || server.isBlank() || server.equals(query.server());
    }

    public boolean isGlobal() {
        return specificity() == 0;
    }

    @Override
    public String toString() {
        if (server != null && !server.isBlank()) {
            return "server=" + server;
        }
        if (group != null && !group.isBlank()) {
            return "group=" + group;
        }
        return "global";
    }
}
