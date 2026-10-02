package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.api.plugin.CloudPermissions;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Haengt {@link CloudPermissible} in Bukkit-Spieler ein (PLAN.md Abschnitt 9).
 *
 * <p>Das ist der einzige Weg: Paper hat keine API, um einen Rechte-Anbieter zu registrieren
 * (Velocity hat dafuer den {@code PermissionProvider}). Geprueft an
 * {@code paper-api 26.2.build.129-stable}: {@code CraftHumanEntity} haelt ein
 * {@code protected final PermissibleBase perm}, und {@code hasPermission(...)} delegiert
 * dorthin.
 *
 * <h2>Warum das Feld ueber seinen Typ gesucht wird</h2>
 * Nicht ueber den Namen {@code perm}, sondern ueber den Typ {@link PermissibleBase}. Ein
 * Umbenennen in einer kuenftigen Paper-Version bricht die Suche damit nicht - ein
 * Typwechsel waere ein viel groesserer Umbau und faellt sofort auf.
 *
 * <p><b>Scheitert die Injektion, laeuft der Server normal weiter.</b> Dann greifen die
 * Cloud-Rechte nur ueber {@code VibeCloudPaper.hasPermission(...)}, nicht ueber
 * {@code player.hasPermission(...)}. Die Warnung dazu kommt genau einmal - nicht bei jedem
 * Join, sonst flutet sie das Log.
 */
final class PermissibleInjector {

    private final CloudPermissions permissions;
    private final Logger logger;

    /** Einmal gefunden, immer wieder benutzt - Reflection ist zu teuer fuer jeden Join. */
    private Field permField;
    private boolean searched;
    private boolean warned;

    PermissibleInjector(CloudPermissions permissions, Logger logger) {
        this.permissions = permissions;
        this.logger = logger;
    }

    /**
     * Tauscht das Rechte-Objekt des Spielers aus.
     *
     * @return {@code true} wenn es geklappt hat
     */
    boolean inject(Player player) {
        Optional<Field> field = permissibleField(player.getClass());
        if (field.isEmpty()) {
            warnOnce("Das Rechte-Feld von Paper wurde nicht gefunden");
            return false;
        }
        try {
            Field target = field.get();
            Object current = target.get(player);

            if (current instanceof CloudPermissible) {
                // Schon eingehaengt - z. B. nach einem Reconnect zum Master.
                return true;
            }
            PermissibleBase original = current instanceof PermissibleBase base ? base : null;

            target.set(player, new CloudPermissible(player, player.getUniqueId(),
                    permissions, original));
            return true;

        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Das Rechte-Feld konnte nicht gesetzt werden: " + exception.getMessage());
            return false;
        }
    }

    /** Setzt das Original zurueck - beim Quit und beim Deaktivieren des Plugins. */
    void restore(Player player) {
        Optional<Field> field = permissibleField(player.getClass());
        if (field.isEmpty()) {
            return;
        }
        try {
            Object current = field.get().get(player);
            if (current instanceof CloudPermissible cloud && cloud.original() != null) {
                field.get().set(player, cloud.original());
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.log(Level.FINE, "Rechte-Feld von " + player.getName()
                                   + " nicht zurueckgesetzt", exception);
        }
    }

    /**
     * Sucht das Feld ueber die ganze Klassenhierarchie.
     *
     * <p>{@code CraftPlayer} erbt es von {@code CraftHumanEntity} - deshalb nicht nur die
     * eigene Klasse ansehen.
     */
    private Optional<Field> permissibleField(Class<?> type) {
        if (searched) {
            return Optional.ofNullable(permField);
        }
        searched = true;
        Optional<Field> found = findPermissibleField(type);
        found.ifPresent(field -> {
            permField = field;
            logger.fine("Rechte-Feld gefunden: " + field.getDeclaringClass().getName()
                        + "." + field.getName());
        });
        return found;
    }

    /**
     * Sucht ein Feld vom Typ {@link PermissibleBase} in der ganzen Klassenhierarchie.
     *
     * <p>Getrennt und paketsichtbar, damit die Suche ohne laufenden Server getestet werden
     * kann - das Verhalten gegen eine echte Paper-Klasse laesst sich hier nicht pruefen.
     */
    static Optional<Field> findPermissibleField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field candidate : current.getDeclaredFields()) {
                if (!PermissibleBase.class.isAssignableFrom(candidate.getType())) {
                    continue;
                }
                candidate.setAccessible(true);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void warnOnce(String message) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning("""
                %s.
                Cloud-Rechte greifen deshalb NICHT ueber player.hasPermission(...) und damit
                nicht in Fremd-Plugins. Der Server laeuft normal weiter; eigene Plugins
                koennen VibeCloudPaper.hasPermission(uuid, node) benutzen.
                Das deutet auf eine Paper-Version hin, die das Rechte-Objekt anders haelt -
                bitte melden.""".formatted(message));
    }

    /** Fuer die Startmeldung: ob die Bridge grundsaetzlich arbeiten kann. */
    boolean isAvailable(Player sample) {
        return permissibleField(sample.getClass()).isPresent();
    }
}
