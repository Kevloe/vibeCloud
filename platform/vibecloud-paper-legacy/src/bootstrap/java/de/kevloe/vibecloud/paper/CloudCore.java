package de.kevloe.vibecloud.paper;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Was der Lader ({@link VibeCloudPaperLegacy}) vom eigentlichen Plugin sieht.
 *
 * <p>Steht im Lader, damit beide Seiten dieselbe Klasse meinen: Der Kern laedt sie ueber
 * seinen Eltern-Klassenlader. Nur Typen von Java und Bukkit in den Signaturen - eine Klasse
 * des Kerns hier wuerde der Bukkit-Klassenlader ein zweites Mal laden, und der Cast
 * scheiterte an zwei gleichnamigen Klassen.
 */
public interface CloudCore {

    void enable();

    void disable();

    boolean hasPermission(UUID uuid, String node);

    void switchServer(Player player, String target);

    void switchServer(UUID uuid, String name, String target);
}
