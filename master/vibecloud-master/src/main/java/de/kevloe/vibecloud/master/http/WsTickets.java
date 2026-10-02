package de.kevloe.vibecloud.master.http;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Einmal-Eintrittskarten fuer WebSocket-Verbindungen.
 *
 * <p><b>Warum nicht einfach das Zugangstoken?</b> Ein Browser kann beim WebSocket-Aufbau
 * keine eigenen Kopfzeilen setzen - das Token muesste in die Adresse. Dort landet es im
 * Verlauf des Browsers und in jedem Zugriffsprotokoll dazwischen, und es gilt 15 Minuten.
 *
 * <p>Deshalb dieser Umweg: Die Seite holt sich mit ihrem Token eine Karte, die
 * <b>30 Sekunden</b> gilt und <b>einmal</b> benutzt werden kann. Steht sie danach in einem
 * Protokoll, ist sie wertlos.
 */
public final class WsTickets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LIFETIME = Duration.ofSeconds(30);

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * Stellt eine Karte fuer diesen Aufrufer aus.
     *
     * @param uuid des Dashboard-Benutzers, {@code null} bei einem API-Token
     */
    public String issue(UUID uuid, String name) {
        cleanUp();

        byte[] value = new byte[24];
        RANDOM.nextBytes(value);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(value);

        tickets.put(ticket, new Ticket(uuid, name, Instant.now().plus(LIFETIME)));
        return ticket;
    }

    /**
     * Loest eine Karte ein. Danach ist sie verbraucht.
     *
     * @return der Aufrufer, falls die Karte gueltig war
     */
    public Optional<Ticket> redeem(String ticket) {
        if (ticket == null) {
            return Optional.empty();
        }
        // remove statt get: Eine Karte gilt genau einmal. Ein zweiter Verbindungsaufbau
        // mit derselben Karte - etwa aus einem Protokoll abgelesen - geht nicht.
        Ticket found = tickets.remove(ticket);

        if (found == null || found.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    public Duration lifetime() {
        return LIFETIME;
    }

    /** Nicht eingeloeste Karten verschwinden - sonst waechst die Map unbegrenzt. */
    private void cleanUp() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    /** Wer eine Karte eingeloest hat. */
    public record Ticket(UUID uuid, String name, Instant expiresAt) {

        public boolean isUser() {
            return uuid != null;
        }
    }
}
