package de.kevloe.vibecloud.master.message;

import de.kevloe.vibecloud.master.grpc.PluginConnectionRegistry;
import de.kevloe.vibecloud.protocol.ReloadMessages;

/**
 * Neu einlesen und an alle Plugins verteilen.
 *
 * <p>Eine Stelle fuer beide Wege: {@code cloud messages reload} in der Konsole und das
 * Speichern im Dashboard. Zweimal geschrieben waere es zweimal fast richtig - und ein
 * Unterschied faellt erst auf, wenn ein Server nach einer Textaenderung als einziger den
 * alten Satz zeigt.
 */
public final class MessageDistributor {

    private final MessageService messages;
    private final PluginConnectionRegistry plugins;

    public MessageDistributor(MessageService messages, PluginConnectionRegistry plugins) {
        this.messages = messages;
        this.plugins = plugins;
    }

    /**
     * @param reloaded ob das Einlesen geklappt hat - sonst bleiben die alten Texte aktiv
     * @param plugins  wie viele Server die Meldung bekommen haben
     */
    public record Result(boolean reloaded, int plugins) {}

    public Result reload() {
        if (!messages.reload()) {
            return new Result(false, 0);
        }
        int reached = plugins.broadcastToAll(PluginConnectionRegistry.command(
                builder -> builder.setReloadMessages(ReloadMessages.newBuilder())));
        return new Result(true, reached);
    }

    public MessageService messages() {
        return messages;
    }
}
