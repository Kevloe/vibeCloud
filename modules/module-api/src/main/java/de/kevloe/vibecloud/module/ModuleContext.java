package de.kevloe.vibecloud.module;

import de.kevloe.vibecloud.api.event.EventBus;

import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Was ein Modul von der Cloud bekommt (PLAN.md Abschnitt 10).
 *
 * <p>Absichtlich schmal: Ein Modul sieht nur Schnittstellen, nie Master-Interna. Dadurch
 * kann der Master intern umgebaut werden, ohne dass Module brechen.
 *
 * <p>Die Breite ist bewusst so gewaehlt, dass ein spaeteres Party-Modul ohne
 * Core-Aenderung passt: Zustand ueber die Datenbank, clusterweite Events, und ein Kanal zu
 * den eigenen Plugin-Teilen.
 */
public interface ModuleContext {

    /** Kennung dieses Moduls - auch Praefix fuer Kanal- und Nachrichten-Schluessel. */
    String moduleId();

    ModuleDescriptor descriptor();

    /**
     * Events abonnieren und eigene werfen (PLAN.md Abschnitt 10a).
     *
     * <p>Handler werden beim Entladen automatisch abgemeldet - darum muss sich das Modul
     * nicht kuemmern.
     */
    EventBus events();

    /** Befehle registrieren. Sie stehen danach in Konsole, Spiel und REST zur Verfuegung. */
    ModuleCommands commands();

    /**
     * Datenbankzugriff mit eigenen Migrations.
     *
     * <p>Die Migrations liegen im Modul-JAR unter {@code db/migration} und bekommen eine
     * eigene Flyway-Historie - so stolpern Modul- und Core-Migrations nicht uebereinander.
     */
    ModuleDatabase database();

    /**
     * Kanal zu den eigenen Plugin-Teilen (PLAN.md Abschnitt 10).
     *
     * <p>Fuer Fragen, auf die es eine Antwort braucht. Reine Benachrichtigungen laufen
     * ueber {@link #events()}.
     */
    ModuleChannel channel();

    /** Rechte pruefen. Der Master entscheidet, das Modul fragt nur. */
    ModulePermissions permissions();

    /** Spielerdaten lesen. */
    ModulePlayers players();

    /** Eigene Konfiguration unter {@code modules/<id>/config.json}. */
    ModuleConfig config();

    /**
     * Texte dieses Moduls (PLAN.md Abschnitt 11a).
     *
     * <p>Das Modul bringt {@code messages/<sprache>.yml} im eigenen JAR mit. Die
     * Schluessel bekommen die Modul-Id als Praefix: Aus {@code ban.screen} wird
     * {@code punishment.ban.screen}. So kann ein Modul keine Core-Texte ueberschreiben.
     *
     * <p>Im {@code onEnable} aufrufen, bevor zum ersten Mal ein Schluessel benutzt wird.
     *
     * @return Anzahl geladener Sprachen
     */
    int loadMessages();

    /** Eigenes Verzeichnis fuer Dateien dieses Moduls. */
    Path dataDirectory();

    /**
     * Geteilter Scheduler.
     *
     * <p>Virtual Threads, also darf hier blockiert werden. Beim Entladen des Moduls werden
     * seine Aufgaben abgebrochen.
     */
    ScheduledExecutorService scheduler();

    /** Logger mit dem Modulnamen als Praefix. */
    org.slf4j.Logger logger();
}
