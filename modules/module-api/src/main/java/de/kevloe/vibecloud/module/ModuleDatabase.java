package de.kevloe.vibecloud.module;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Datenbankzugriff eines Moduls (PLAN.md Abschnitt 10).
 *
 * <p>Die Migrations liegen im Modul-JAR unter <b>{@code db/migration/<modul-id>/}</b> -
 * nicht direkt unter {@code db/migration}. Der ClassLoader eines Moduls sieht auch die
 * Ressourcen des Masters, und der hat dort seine eigenen Migrations: Flyway faende dann
 * zwei Dateien mit Version 1 und brechte ab.
 *
 * <p>Dazu gibt es eine <b>eigene Flyway-Historie</b> ({@code flyway_schema_history_<id>}).
 * Ohne die wuerden Modul- und Core-Migrations in derselben Tabelle landen und sich bei
 * jedem Update gegenseitig fuer "nicht angewendet" halten.
 *
 * <p>Tabellennamen sollten mit der Modul-Id beginnen ({@code punishment_bans}), damit zwei
 * Module sich nicht in die Quere kommen - die Datenbank ist gemeinsam.
 */
public interface ModuleDatabase {

    /**
     * Fuehrt die Migrations dieses Moduls aus.
     *
     * <p>Im {@code onEnable} aufrufen, bevor zum ersten Mal auf eigene Tabellen zugegriffen
     * wird. Mehrfaches Aufrufen ist harmlos.
     *
     * @return Anzahl neu angewendeter Migrations
     */
    int migrate();

    /**
     * Eine Verbindung aus dem Pool des Masters.
     *
     * <p>Immer mit try-with-resources benutzen. Eine nicht zurueckgegebene Verbindung
     * blockiert den Pool - und der ist fuer die ganze Cloud da, nicht nur fuer dieses Modul.
     */
    Connection connection() throws SQLException;

    /** Praefix, mit dem Tabellen dieses Moduls beginnen sollten. */
    String tablePrefix();
}
