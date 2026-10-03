package de.kevloe.vibecloud.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Texte fuer Server, die nur Strings kennen.
 *
 * <p>Rang-Prefixe und Cloud-Nachrichten stehen als MiniMessage in der Datenbank. Paper 1.16.1
 * hat kein Adventure, 1.16.5 ein altes; die Bukkit-Methoden mit {@code String} gibt es
 * dagegen ueberall. Deshalb wird hier mit dem eingepackten Adventure gerendert und als
 * Legacy-Text weitergegeben - <b>nie</b> mit einer Bukkit-Methode, die ein {@code Component}
 * nimmt: Das eingepackte Adventure ist verlagert, und so ein Aufruf liefe auf einen
 * {@code NoSuchMethodError}.
 *
 * <p>Die Farbcodes erzeugt der Serializer, sie stehen nirgends im Code. Hex-Farben gehen ab
 * 1.16, also auf jedem Server, auf dem dieses Plugin laeuft.
 */
final class LegacyText {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /**
     * Platzhalter fuer {@code String.format} im Chat. Zeichen aus dem privaten Bereich von
     * Unicode: In einem Rang-Prefix kommen sie nicht vor, und MiniMessage laesst sie stehen.
     */
    private static final String NAME_TOKEN = "";
    private static final String MESSAGE_TOKEN = "";

    private LegacyText() {
    }

    static String render(String miniMessage) {
        return LEGACY.serialize(MINI_MESSAGE.deserialize(miniMessage));
    }

    static Component parse(String miniMessage) {
        return MINI_MESSAGE.deserialize(miniMessage);
    }

    /** Prefix, Name, Suffix - fuer die Tab-Liste. */
    static String nameLine(String prefix, String name, String suffix) {
        return LEGACY.serialize(MINI_MESSAGE.deserialize(prefix + "<name>" + suffix,
                Placeholder.unparsed("name", name)));
    }

    /**
     * Baut aus dem Chat-Format des Rangs ein Format fuer {@code AsyncPlayerChatEvent}.
     *
     * <p>Bukkit erwartet {@code %1$s} fuer den Namen und {@code %2$s} fuer die Nachricht und
     * setzt beides selbst ein - so bleibt die Nachricht unangetastet, und andere Plugins
     * koennen sie weiter lesen und aendern. Ein {@code %} aus dem Prefix wird verdoppelt,
     * sonst hielte {@code String.format} es fuer einen Platzhalter und wuerfe.
     */
    static String chatFormat(String format, String prefix, String suffix) {
        Component rendered = MINI_MESSAGE.deserialize(format,
                Placeholder.parsed("prefix", prefix),
                Placeholder.parsed("suffix", suffix),
                Placeholder.unparsed("name", NAME_TOKEN),
                Placeholder.unparsed("message", MESSAGE_TOKEN));
        return LEGACY.serialize(rendered)
                .replace("%", "%%")
                .replace(NAME_TOKEN, "%1$s")
                .replace(MESSAGE_TOKEN, "%2$s");
    }
}
