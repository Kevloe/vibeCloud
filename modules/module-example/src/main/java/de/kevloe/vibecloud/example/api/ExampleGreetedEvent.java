package de.kevloe.vibecloud.example.api;

import java.util.UUID;

/**
 * Ein Event, das dieses Modul wirft.
 *
 * <p>Liegt bewusst in einem eigenen {@code api}-Paket, das in {@code module.json} unter
 * {@code exports} steht. Nur dadurch kann ein anderes Modul darauf reagieren - ohne Export
 * versteckt die child-first-Isolation die Klasse (PLAN.md Abschnitt 10a).
 */
public record ExampleGreetedEvent(UUID uuid, String name, long count) {
}
