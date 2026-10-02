package de.kevloe.vibecloud.api.outbox;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.protocol.NodeEvent;
import de.kevloe.vibecloud.protocol.ReplayFinished;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Outbox ist der Teil, bei dem ein Fehler erst nach einem Master-Ausfall auffaellt -
 * also genau dann, wenn niemand Zeit hat, ihn zu suchen. Deshalb hier die Faelle,
 * die in PLAN.md Abschnitt 15 in der Resilienz-Checkliste stehen.
 */
class FileOutboxTest {

    /** Beliebiges Ereignis mit Inhalt - der Typ ist fuer die Outbox unerheblich. */
    private static NodeEvent event(long marker) {
        return NodeEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setReplayFinished(ReplayFinished.newBuilder().setLastSeq(marker))
                .build();
    }

    @Test
    void vergibtAufsteigendeSeqAbEins() throws IOException {
        try (FileOutbox outbox = new FileOutbox(tempDirectory())) {
            assertThat(outbox.append(event(1)).getSeq()).isEqualTo(1L);
            assertThat(outbox.append(event(2)).getSeq()).isEqualTo(2L);
            assertThat(outbox.append(event(3)).getSeq()).isEqualTo(3L);
            assertThat(outbox.highestSeq()).isEqualTo(3L);
            assertThat(outbox.pendingCount()).isEqualTo(3L);
        }
    }

    @Test
    void replayLiefertNurEreignisseNachDemCursor() throws IOException {
        try (FileOutbox outbox = new FileOutbox(tempDirectory())) {
            for (int i = 1; i <= 5; i++) {
                outbox.append(event(i));
            }

            List<NodeEvent> replayed = outbox.replayFrom(2L, 100);

            assertThat(replayed).extracting(NodeEvent::getSeq).containsExactly(3L, 4L, 5L);
        }
    }

    @Test
    void replayAchtetAufDasLimit() throws IOException {
        try (FileOutbox outbox = new FileOutbox(tempDirectory())) {
            for (int i = 1; i <= 10; i++) {
                outbox.append(event(i));
            }

            assertThat(outbox.replayFrom(0L, 4)).hasSize(4);
        }
    }

    @Test
    void ueberlebtNeustartUndZaehltWeiter() throws IOException {
        Path directory = tempDirectory();

        try (FileOutbox first = new FileOutbox(directory)) {
            first.append(event(1));
            first.append(event(2));
        }

        // Neuer Prozess, gleiches Verzeichnis: Eintraege sind noch da, seq zaehlt weiter.
        try (FileOutbox second = new FileOutbox(directory)) {
            assertThat(second.pendingCount()).isEqualTo(2L);
            assertThat(second.append(event(3)).getSeq()).isEqualTo(3L);
        }
    }

    /**
     * Der subtile Fall: Sind alle Segmente quittiert und geloescht, darf seq NICHT wieder
     * bei 1 anfangen - der Master wuerde die neuen Ereignisse sonst als Duplikate verwerfen,
     * weil sein Cursor schon hoeher steht.
     */
    @Test
    void seqStartetNachVollstaendigerQuittierungNichtNeu() throws IOException {
        Path directory = tempDirectory();
        // Kleine Segmente erzwingen, damit ueberhaupt gerollt und geloescht werden kann.
        try (FileOutbox outbox = new FileOutbox(directory, 64, 10_000_000, Duration.ofDays(7))) {
            for (int i = 1; i <= 20; i++) {
                outbox.append(event(i));
            }
            outbox.ackUpTo(20L);
        }

        try (FileOutbox reopened = new FileOutbox(directory, 64, 10_000_000, Duration.ofDays(7))) {
            assertThat(reopened.append(event(21)).getSeq()).isGreaterThan(20L);
        }
    }

    @Test
    void ackGibtNurVollstaendigQuittierteSegmenteFrei() throws IOException {
        Path directory = tempDirectory();
        try (FileOutbox outbox = new FileOutbox(directory, 64, 10_000_000, Duration.ofDays(7))) {
            for (int i = 1; i <= 20; i++) {
                outbox.append(event(i));
            }
            long pendingBefore = outbox.pendingCount();

            outbox.ackUpTo(5L);

            // Etwas wurde freigegeben, aber nicht alles - die spaeteren Ereignisse
            // muessen erhalten bleiben, sonst gehen sie verloren.
            assertThat(outbox.pendingCount()).isLessThan(pendingBefore);
            assertThat(outbox.replayFrom(5L, 100))
                    .extracting(NodeEvent::getSeq)
                    .contains(20L);
        }
    }

    @Test
    void verwirftAeltesteSegmenteWennDieGrenzeErreichtIst() throws IOException {
        Path directory = tempDirectory();
        // 64 Byte pro Segment, 256 Byte gesamt -> es muss zwangslaeufig verworfen werden.
        try (FileOutbox outbox = new FileOutbox(directory, 64, 256, Duration.ofDays(7))) {
            for (int i = 1; i <= 100; i++) {
                outbox.append(event(i));
            }

            // Die Grenze wird eingehalten ...
            assertThat(outbox.pendingCount()).isLessThan(100L);
            // ... und die zuletzt geschriebenen Ereignisse sind die, die bleiben.
            assertThat(outbox.highestSeq()).isEqualTo(100L);
        }
    }

    private Path tempDirectory() {
        return directory.resolve("outbox");
    }

    @TempDir
    Path directory;
}
