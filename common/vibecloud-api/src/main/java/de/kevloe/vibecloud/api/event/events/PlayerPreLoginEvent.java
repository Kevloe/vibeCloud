package de.kevloe.vibecloud.api.event.events;

import de.kevloe.vibecloud.api.event.Cancellable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Darf dieser Spieler ins Netzwerk? (PLAN.md Abschnitt 10a)
 *
 * <p><b>Pre-Event:</b> abbrechbar, synchron im Master, mit Timeout. Genau hier haengt sich
 * ab M6 das punishment-Modul ein - der Core kennt keine Bans. Ein spaeteres Whitelist-
 * oder Laendersperr-Modul nutzt dieselbe Stelle, ohne dass am Login-Gate etwas geaendert
 * werden muss.
 *
 * <p>Die Begruendung ist ein Nachrichten-Schluessel, kein fertiger Text - uebersetzt wird
 * erst bei der Anzeige (PLAN.md Abschnitt 11a).
 */
public final class PlayerPreLoginEvent implements Cancellable {

    private final UUID uuid;
    private final String name;
    private final String ip;
    private final String platform;

    private boolean cancelled;
    private String reasonKey;
    private final Map<String, String> placeholders = new LinkedHashMap<>();

    public PlayerPreLoginEvent(UUID uuid, String name, String ip, String platform) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
        this.platform = platform;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /** Die IP im Klartext - gespeichert wird sie nur gehasht (PLAN.md Abschnitt 13). */
    public String ip() {
        return ip;
    }

    public String platform() {
        return platform;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel(String messageKey) {
        this.cancelled = true;
        this.reasonKey = messageKey;
    }

    /** Abbrechen mit Platzhaltern fuer die Meldung, z. B. Grund und Ablaufzeit eines Bans. */
    public void cancel(String messageKey, Map<String, String> values) {
        cancel(messageKey);
        placeholders.putAll(values);
    }

    @Override
    public String cancelReasonKey() {
        return reasonKey;
    }

    public Map<String, String> placeholders() {
        return Map.copyOf(placeholders);
    }
}
