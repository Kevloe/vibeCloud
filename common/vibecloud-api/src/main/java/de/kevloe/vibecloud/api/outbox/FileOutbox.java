package de.kevloe.vibecloud.api.outbox;

import com.google.protobuf.InvalidProtocolBufferException;
import de.kevloe.vibecloud.protocol.NodeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Outbox auf der Platte: Segmentdateien mit laengenpraefigierten Protobuf-Eintraegen.
 *
 * <p>Format pro Eintrag: varint-Laenge, dann die serialisierte {@link NodeEvent}. Also dasselbe
 * Format wie auf der Leitung - kein zweiter Serializer, keine zweite Fehlerquelle.
 *
 * <p><b>Warum Segmente statt einer Datei:</b> Freigeben heisst dann "ganze Datei loeschen"
 * statt "Datei umschreiben". Umschreiben waere der eine Moment, in dem ein Absturz die
 * komplette Outbox zerstoeren koennte.
 *
 * <p>Nicht thread-safe fuer parallele {@link #append}; der Wrapper schreibt aus einem Thread.
 */
public final class FileOutbox implements Outbox {

    private static final Logger LOG = LoggerFactory.getLogger(FileOutbox.class);

    private static final String SEGMENT_PREFIX = "outbox-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final String SEQ_FILE = "seq.state";

    private final Path directory;
    private final long maxSegmentBytes;
    private final long maxTotalBytes;
    private final Duration maxAge;

    private long nextSeq;
    private int currentSegment;
    private OutputStream currentOut;
    private long currentBytes;

    public FileOutbox(Path directory) throws IOException {
        // 8 MB pro Segment, 50 MB / 7 Tage gesamt (PLAN.md Abschnitt 7).
        this(directory, 8L * 1024 * 1024, 50L * 1024 * 1024, Duration.ofDays(7));
    }

    public FileOutbox(Path directory, long maxSegmentBytes, long maxTotalBytes, Duration maxAge)
            throws IOException {
        this.directory = directory;
        this.maxSegmentBytes = maxSegmentBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxAge = maxAge;

        Files.createDirectories(directory);
        this.nextSeq = readSeqState() + 1;
        this.currentSegment = highestExistingSegment();
        openSegment();

        long pending = pendingCount();
        if (pending > 0) {
            LOG.info("Outbox: {} Ereignisse aus einem frueheren Lauf uebernommen (bis seq {})",
                    pending, nextSeq - 1);
        }
    }

    @Override
    public NodeEvent append(NodeEvent event) {
        NodeEvent stamped = event.toBuilder().setSeq(nextSeq).build();
        try {
            byte[] payload = stamped.toByteArray();
            if (currentBytes > 0 && currentBytes + payload.length > maxSegmentBytes) {
                rollSegment();
            }
            writeVarInt(currentOut, payload.length);
            currentOut.write(payload);
            currentOut.flush();
            currentBytes += payload.length;

            nextSeq++;
            writeSeqState(stamped.getSeq());
            enforceLimits();
            return stamped;
        } catch (IOException exception) {
            throw new UncheckedIOException("Outbox-Eintrag konnte nicht geschrieben werden", exception);
        }
    }

    @Override
    public List<NodeEvent> replayFrom(long fromSeqExclusive, int limit) {
        List<NodeEvent> result = new ArrayList<>();
        for (Path segment : segments()) {
            if (result.size() >= limit) {
                break;
            }
            try (InputStream in = Files.newInputStream(segment)) {
                while (result.size() < limit) {
                    NodeEvent event = readEntry(in);
                    if (event == null) {
                        break;
                    }
                    if (event.getSeq() > fromSeqExclusive) {
                        result.add(event);
                    }
                }
            } catch (IOException exception) {
                // Ein angerissenes Segment (Absturz mitten im Schreiben) macht den Rest
                // nicht unbrauchbar - der letzte Eintrag fehlt, alles davor bleibt gueltig.
                LOG.warn("Outbox-Segment {} nicht vollstaendig lesbar: {}",
                        segment.getFileName(), exception.getMessage());
            }
        }
        result.sort(Comparator.comparingLong(NodeEvent::getSeq));
        return result;
    }

    @Override
    public void ackUpTo(long seq) {
        for (Path segment : segments()) {
            if (segment.equals(currentSegmentPath())) {
                continue;
            }
            long highest = highestSeqIn(segment);
            if (highest >= 0 && highest <= seq) {
                deleteQuietly(segment);
            }
        }
    }

    @Override
    public long highestSeq() {
        return nextSeq - 1;
    }

    @Override
    public long pendingCount() {
        long count = 0;
        for (Path segment : segments()) {
            try (InputStream in = Files.newInputStream(segment)) {
                while (readEntry(in) != null) {
                    count++;
                }
            } catch (IOException exception) {
                LOG.debug("Segment {} beim Zaehlen uebersprungen", segment.getFileName());
            }
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        if (currentOut != null) {
            currentOut.flush();
            currentOut.close();
        }
    }

    // ------------------------------------------------------------------ Segmente

    private void openSegment() throws IOException {
        Path path = currentSegmentPath();
        currentBytes = Files.exists(path) ? Files.size(path) : 0L;
        currentOut = new BufferedOutputStream(Files.newOutputStream(
                path, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    }

    private void rollSegment() throws IOException {
        currentOut.flush();
        currentOut.close();
        currentSegment++;
        openSegment();
    }

    private Path currentSegmentPath() {
        return directory.resolve(SEGMENT_PREFIX + String.format("%06d", currentSegment) + SEGMENT_SUFFIX);
    }

    private List<Path> segments() {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith(SEGMENT_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(SEGMENT_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            LOG.warn("Outbox-Verzeichnis nicht lesbar: {}", exception.getMessage());
            return List.of();
        }
    }

    private int highestExistingSegment() {
        List<Path> segments = segments();
        if (segments.isEmpty()) {
            return 0;
        }
        String name = segments.getLast().getFileName().toString();
        String digits = name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length());
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Haelt die Grenzen ein. Laeuft die Outbox voll, werden die aeltesten Segmente verworfen
     * und es gibt eine Warnung - lieber Datenverlust mit Hinweis als eine volle Platte.
     */
    private void enforceLimits() {
        List<Path> segments = segments();
        long total = 0;
        for (Path segment : segments) {
            total += sizeQuietly(segment);
        }
        Instant cutoff = Instant.now().minus(maxAge);

        for (Path segment : segments) {
            if (segment.equals(currentSegmentPath())) {
                continue;
            }
            boolean tooBig = total > maxTotalBytes;
            boolean tooOld = isOlderThan(segment, cutoff);
            if (!tooBig && !tooOld) {
                continue;
            }
            long size = sizeQuietly(segment);
            LOG.warn("Outbox-Grenze erreicht ({}): Segment {} wird verworfen, "
                     + "diese Ereignisse erreichen den Master nicht mehr",
                    tooBig ? "Groesse" : "Alter", segment.getFileName());
            deleteQuietly(segment);
            total -= size;
        }
    }

    private boolean isOlderThan(Path segment, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(segment).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            return false;
        }
    }

    private long sizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOG.warn("Outbox-Segment {} konnte nicht geloescht werden", path.getFileName());
        }
    }

    private long highestSeqIn(Path segment) {
        long highest = -1;
        try (InputStream in = Files.newInputStream(segment)) {
            NodeEvent event;
            while ((event = readEntry(in)) != null) {
                highest = Math.max(highest, event.getSeq());
            }
        } catch (IOException exception) {
            return highest;
        }
        return highest;
    }

    // ------------------------------------------------------------------ seq-Zustand

    /**
     * Der Zaehler liegt in einer eigenen Datei, damit er auch dann stimmt, wenn alle
     * Segmente quittiert und geloescht wurden. Sonst wuerde seq nach dem Aufraeumen
     * wieder bei 1 anfangen und der Master wuerde neue Ereignisse als Duplikate verwerfen.
     */
    private long readSeqState() {
        Path path = directory.resolve(SEQ_FILE);
        if (!Files.exists(path)) {
            return 0L;
        }
        try {
            String text = Files.readString(path).trim();
            return text.isEmpty() ? 0L : Long.parseLong(text);
        } catch (IOException | NumberFormatException exception) {
            LOG.warn("seq.state unlesbar, es wird bei den Segmenten nachgesehen");
            long highest = 0;
            for (Path segment : segments()) {
                highest = Math.max(highest, highestSeqIn(segment));
            }
            return Math.max(0L, highest);
        }
    }

    private void writeSeqState(long seq) throws IOException {
        Files.writeString(directory.resolve(SEQ_FILE), Long.toString(seq));
    }

    // ------------------------------------------------------------------ Rahmenformat

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    /** @return naechster Eintrag oder {@code null} am Ende der Datei */
    private static NodeEvent readEntry(InputStream in) throws IOException {
        int length;
        try {
            length = readVarInt(in);
        } catch (EOFException exception) {
            return null;
        }
        if (length < 0) {
            return null;
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            // Abgeschnittener Eintrag: Der Prozess wurde mitten im Schreiben beendet.
            return null;
        }
        try {
            return NodeEvent.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IOException("Outbox-Eintrag beschaedigt", exception);
        }
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (shift < 32) {
            int read = in.read();
            if (read == -1) {
                if (shift == 0) {
                    throw new EOFException();
                }
                return -1;
            }
            result |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("varint zu lang");
    }
}
