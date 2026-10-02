package de.kevloe.vibecloud.api.outbox;

import de.kevloe.vibecloud.protocol.NodeEvent;

import java.io.Closeable;
import java.util.List;

/**
 * Zwischenspeicher fuer Ereignisse, solange der Master nicht erreichbar ist
 * (PLAN.md Abschnitt 7).
 *
 * <p>Gepuffert werden <b>Fakten</b> mit Zeitstempel: Server-Zustaende, Logins, Strafen.
 * <b>Nicht</b> gepuffert werden Momentaufnahmen wie Heartbeats, CPU-Werte oder Spielerzahlen -
 * sonst spielt man nach 30 Minuten Ausfall tausende nutzlose Eintraege nach.
 */
public interface Outbox extends Closeable {

    /**
     * Haengt ein Ereignis an und vergibt die naechste {@code seq}.
     *
     * @return das Ereignis mit gesetzter {@code seq}
     */
    NodeEvent append(NodeEvent event);

    /** Ereignisse mit {@code seq > fromSeqExclusive}, aufsteigend, hoechstens {@code limit}. */
    List<NodeEvent> replayFrom(long fromSeqExclusive, int limit);

    /**
     * Gibt alles bis einschliesslich {@code seq} frei. Erst nach der Quittung des Masters
     * aufrufen - vorher weiss niemand, ob die Ereignisse angekommen sind.
     */
    void ackUpTo(long seq);

    /** Hoechste je vergebene seq. Ueberlebt Neustarts. */
    long highestSeq();

    /** Noch nicht quittierte Ereignisse. */
    long pendingCount();
}
