package de.kevloe.vibecloud.module;

import java.util.UUID;

/**
 * Rechte pruefen (PLAN.md Abschnitt 9 und 13).
 *
 * <p><b>Der Master entscheidet</b>, das Modul fragt nur. Ein Modul bekommt niemals ein
 * "darf das"-Flag von aussen uebergeben - sonst koennte ein kompromittierter Gameserver
 * sich Rechte erfinden.
 */
public interface ModulePermissions {

    boolean has(UUID uuid, String node);

    /** Mit Kontext, z. B. nur auf einem Server. */
    boolean has(UUID uuid, String node, String group, String server);

    /**
     * Woher die Entscheidung kommt - fuer Debug-Ausgaben eines Moduls.
     *
     * @return lesbare Beschreibung, oder leer wenn keine Regel zutrifft
     */
    java.util.Optional<String> explain(UUID uuid, String node);

    /**
     * Meldet ein Recht an, das dieses Modul benutzt.
     *
     * <p>Nur fuer die Dokumentation: So kann {@code perm list} zeigen, welche Rechte es
     * ueberhaupt gibt, statt dass man sie im Code suchen muss.
     */
    void declare(String node, String description);
}
