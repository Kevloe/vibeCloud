package de.kevloe.vibecloud.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Der Lader des Legacy-Plugins - gebaut fuer Java 8, alles andere fuer Java 17.
 *
 * <h2>Warum es ihn gibt</h2>
 * Paper 1.16 schreibt jede Klasse eines Plugins beim Laden um ("Commodore") - mit einem ASM,
 * das Java-17-Klassen nicht lesen kann. Die Klasse wird danach trotzdem geladen, aber jede
 * schreibt einen ERROR samt Stacktrace ins Log, beim Start und spaeter bei jeder, die erst
 * im Betrieb gebraucht wird. Unter Java 17 geht es nicht: Die Verbindung und die
 * Rechte-Auswertung teilt sich das Plugin mit dem aktuellen, und die benutzen Records.
 *
 * <p>Umgeschrieben wird nur, was Bukkits Klassenlader laedt. Deshalb liegt der eigentliche
 * Plugin-Code als {@code META-INF/vibecloud/core.jar} in diesem Jar - fuer Bukkit
 * unsichtbar - und wird hier mit einem eigenen Klassenlader geladen. Was dieses ASM lesen
 * kann, sind nur die zwei Klassen hier.
 *
 * <p><b>Nur Java 8 in diesem Quellordner</b>: keine Records, kein {@code var}, keine
 * Textbloecke. Der Build kompiliert ihn mit {@code --release 8}.
 */
public final class VibeCloudPaperLegacy extends JavaPlugin {

    private static final String CORE_JAR = "META-INF/vibecloud/core.jar";
    private static final String CORE_CLASS = "de.kevloe.vibecloud.paper.LegacyCore";

    private URLClassLoader coreLoader;
    private CloudCore core;

    @Override
    public void onEnable() {
        try {
            File jar = extractCore();
            // Eltern-Lader ist der des Plugins: So sieht der Kern Bukkit und CloudCore,
            // und Bukkit sieht vom Kern nichts.
            coreLoader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, getClassLoader());
            Class<?> type = Class.forName(CORE_CLASS, true, coreLoader);
            core = (CloudCore) type.getConstructor(JavaPlugin.class).newInstance(this);
            core.enable();
        } catch (Exception | LinkageError exception) {
            // Ohne Kern laeuft der Server weiter - nur eben nicht unter der Cloud.
            getLogger().log(Level.SEVERE, "vibeCloud konnte nicht geladen werden. Der Server "
                    + "laeuft weiter, ist aber nicht mit der Cloud verbunden.", exception);
            core = null;
        }
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
        }
        if (coreLoader != null) {
            try {
                coreLoader.close();
            } catch (IOException ignored) {
                // Beim Herunterfahren ohne Bedeutung.
            }
        }
    }

    /**
     * Legt den Kern in den Datenordner. Jedes Mal neu: Nach einem Update des Plugins soll
     * nicht der alte Kern weiterlaufen.
     */
    private File extractCore() throws IOException {
        File folder = getDataFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Datenordner " + folder + " laesst sich nicht anlegen");
        }
        File target = new File(folder, "core.jar");
        InputStream in = getResource(CORE_JAR);
        if (in == null) {
            throw new IOException(CORE_JAR + " fehlt im Plugin - falsch gebaut?");
        }
        try {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }
        return target;
    }

    // ---------------------------------------------------------------- API fuer Plugins

    /** Prueft ein Recht gegen die Cloud - wie {@code VibeCloudPaper.hasPermission}. */
    public boolean hasPermission(UUID uuid, String node) {
        return core != null && core.hasPermission(uuid, node);
    }

    /** Schickt einen Spieler auf einen anderen Server der Cloud. */
    public void switchServer(Player player, String target) {
        if (core != null) {
            core.switchServer(player, target);
        }
    }

    public void switchServer(UUID uuid, String name, String target) {
        if (core != null) {
            core.switchServer(uuid, name, target);
        }
    }
}
