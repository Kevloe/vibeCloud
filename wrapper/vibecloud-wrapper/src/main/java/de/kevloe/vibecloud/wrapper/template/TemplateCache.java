package de.kevloe.vibecloud.wrapper.template;

import de.kevloe.vibecloud.protocol.ManifestEntry;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inhaltsadressierter Dateicache des Wrappers (PLAN.md Abschnitt 7).
 *
 * <p>Dateien liegen unter ihrem SHA-256 in {@code cache/blobs/}, nicht unter ihrem Namen.
 * Dadurch wird jede Datei genau einmal uebertragen, auch wenn sie in mehreren Gruppen
 * vorkommt - dasselbe Plugin in fuenf Templates kostet eine Uebertragung, nicht fuenf.
 *
 * <p>Beim Einrichten eines Serververzeichnisses wird bevorzugt <b>hart verlinkt</b> statt
 * kopiert. Bei einem 64-MB-Server-Jar und zwanzig Servern ist das der Unterschied zwischen
 * 64 MB und 1,3 GB belegtem Speicher.
 */
public final class TemplateCache {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateCache.class);

    private final Path blobDirectory;

    /** Hashes, die nachweislich vorliegen - erspart das erneute Pruefen der Platte. */
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    public TemplateCache(Path cacheDirectory) throws IOException {
        this.blobDirectory = cacheDirectory.resolve("blobs");
        Files.createDirectories(blobDirectory);
        reindex();
    }

    private void reindex() throws IOException {
        try (var stream = Files.list(blobDirectory)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> known.add(path.getFileName().toString()));
        }
        if (!known.isEmpty()) {
            LOG.info("Dateicache: {} Blobs vorhanden", known.size());
        }
    }

    /** Welche Hashes des Manifests fehlen und beim Master geholt werden muessen. */
    public List<String> missingBlobs(TemplateManifest manifest) {
        List<String> missing = new ArrayList<>();
        for (ManifestEntry entry : manifest.getEntriesList()) {
            if (!has(entry.getSha256())) {
                missing.add(entry.getSha256());
            }
        }
        return missing.stream().distinct().toList();
    }

    public boolean has(String sha256) {
        if (known.contains(sha256)) {
            return true;
        }
        if (Files.isRegularFile(blobDirectory.resolve(sha256))) {
            known.add(sha256);
            return true;
        }
        return false;
    }

    /**
     * Legt einen empfangenen Blob ab.
     *
     * <p>Erst in eine Teil-Datei schreiben, dann den Hash pruefen, dann umbenennen: Ein
     * Abbruch mitten in der Uebertragung darf keinen beschaedigten Blob im Cache lassen,
     * sonst startet jeder Server mit einer kaputten Datei.
     */
    public void store(String sha256, Path temporaryFile) throws IOException {
        String actual = sha256Of(temporaryFile);
        if (!actual.equals(sha256)) {
            Files.deleteIfExists(temporaryFile);
            throw new IOException("Blob beschaedigt: erwartet " + sha256 + ", erhalten " + actual);
        }
        Path target = blobDirectory.resolve(sha256);
        Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
        known.add(sha256);
    }

    public Path temporaryFile(String sha256) {
        return blobDirectory.resolve(sha256 + ".part");
    }

    /**
     * Baut ein Serververzeichnis aus dem Manifest auf.
     *
     * <p>Die Reihenfolge der Eintraege ist die Kopierreihenfolge des Masters
     * (global/* vor Gruppen-Template). Hier wird nicht mehr entschieden, nur ausgefuehrt.
     */
    public void materialize(TemplateManifest manifest, Path serverDirectory) throws IOException {
        Files.createDirectories(serverDirectory);
        int linked = 0;
        int copied = 0;

        for (ManifestEntry entry : manifest.getEntriesList()) {
            Path source = blobDirectory.resolve(entry.getSha256());
            if (!Files.isRegularFile(source)) {
                throw new IOException("Blob " + entry.getSha256() + " fuer " + entry.getPath()
                                      + " fehlt im Cache");
            }
            Path target = serverDirectory.resolve(entry.getPath());
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(target);

            try {
                Files.createLink(target, source);
                linked++;
            } catch (IOException | UnsupportedOperationException exception) {
                // Harte Links gehen nicht ueber Dateisystemgrenzen und nicht auf jedem
                // Windows-Volume. Kopieren ist dann der richtige Rueckfall, nicht ein Fehler.
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            if (entry.getExecutable()) {
                target.toFile().setExecutable(true);
            }
        }
        LOG.debug("{}: {} Dateien verlinkt, {} kopiert",
                serverDirectory.getFileName(), linked, copied);
    }

    /** Entfernt Blobs, die in keinem der uebergebenen Manifeste mehr vorkommen. */
    public int pruneUnreferenced(Set<String> stillNeeded) throws IOException {
        int removed = 0;
        try (var stream = Files.list(blobDirectory)) {
            for (Path blob : stream.filter(Files::isRegularFile).toList()) {
                String name = blob.getFileName().toString();
                if (name.endsWith(".part") || stillNeeded.contains(name)) {
                    continue;
                }
                Files.delete(blob);
                known.remove(name);
                removed++;
            }
        }
        return removed;
    }

    static String sha256Of(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 fehlt in dieser JVM", exception);
        }
    }

    /** Oeffnet einen Ausgabestrom fuer einen eingehenden Blob. */
    public OutputStream openTemporary(String sha256) throws IOException {
        return Files.newOutputStream(temporaryFile(sha256));
    }
}
