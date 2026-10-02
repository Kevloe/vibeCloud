package de.kevloe.vibecloud.master.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Logs beendeter DYNAMIC-Server (PLAN.md Abschnitt 7, Entscheidung 17.3).
 *
 * <p>Statische Server behalten ihre Logs auf dem Node - dort bleibt das Verzeichnis ohnehin
 * bestehen. Nur bei dynamischen Servern wird das Verzeichnis geloescht, deshalb muss ihr Log
 * vorher hierher.
 *
 * <p>Ablage: {@code logs/<gruppe>/<server>-<zeitstempel>.log.gz}
 */
public final class LogArchive {

    private static final Logger LOG = LoggerFactory.getLogger(LogArchive.class);

    private final Path root;
    private final Duration retention;

    public LogArchive(Path root, int retentionDays) throws IOException {
        this.root = root;
        this.retention = Duration.ofDays(retentionDays);
        Files.createDirectories(root);
    }

    /** Oeffnet eine Zieldatei fuer einen eingehenden Upload. */
    public Upload beginUpload(String serverName, String groupName) throws IOException {
        String group = groupName == null || groupName.isBlank() ? "unbekannt" : groupName;
        Path directory = root.resolve(group);
        Files.createDirectories(directory);

        String fileName = "%s-%d.log.gz".formatted(
                serverName == null || serverName.isBlank() ? "server" : serverName,
                Instant.now().toEpochMilli());
        Path target = directory.resolve(fileName);
        Path temporary = directory.resolve(fileName + ".part");
        return new Upload(target, temporary, Files.newOutputStream(temporary));
    }

    /**
     * Loescht Logs, die aelter sind als die Aufbewahrungsfrist.
     *
     * <p>Laeuft beim Start und danach taeglich: Ohne das wuerde das Verzeichnis bei vielen
     * kurzlebigen Servern unbemerkt die Platte fuellen.
     */
    public int pruneOld() {
        Instant cutoff = Instant.now().minus(retention);
        int removed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException exception) {
            LOG.warn("Alte Logs konnten nicht aufgeraeumt werden: {}", exception.getMessage());
        }
        if (removed > 0) {
            LOG.info("{} Logs aelter als {} Tage geloescht", removed, retention.toDays());
        }
        return removed;
    }

    public List<Path> listFor(String groupName) {
        Path directory = root.resolve(groupName);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Ein laufender Upload.
     *
     * <p>Erst in eine Teil-Datei, am Ende umbenennen: Bricht die Verbindung mitten im Upload
     * ab, liegt im Archiv keine halbe Datei, die man fuer vollstaendig haelt.
     */
    public record Upload(Path target, Path temporary, OutputStream stream) implements AutoCloseable {

        public void write(byte[] data) throws IOException {
            stream.write(data);
        }

        /** Schliesst ab und macht die Datei sichtbar. */
        public Path finish() throws IOException {
            stream.close();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        }

        /** Bricht ab und raeumt die Teil-Datei weg. */
        @Override
        public void close() {
            try {
                stream.close();
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                LOG.debug("Teil-Datei {} nicht aufgeraeumt", temporary.getFileName());
            }
        }
    }
}
