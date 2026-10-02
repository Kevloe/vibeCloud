package de.kevloe.vibecloud.sftp;

import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;
import java.util.UUID;

/**
 * Trennt eine Datei vom Blob-Cache, bevor ueber SFTP in sie geschrieben wird.
 *
 * <p>Die Dateien aus dem Template sind harte Links auf die Blobs in {@code cache/}
 * (PLAN.md Abschnitt 7). Das spart Platz und Zeit - heisst aber: Wer eine solche Datei an
 * Ort und Stelle aendert, aendert den Blob. Der steckt dann mit dem neuen Inhalt in
 * <b>jedem</b> Server dieses Nodes, der dieselbe Datei aus seinem Template bekommt, und
 * sein Name stimmt nicht mehr mit seinem Inhalt ueberein. Eine {@code server.properties}
 * zu bearbeiten ist genau der Fall, fuer den es SFTP gibt.
 *
 * <p>Deshalb bekommt das Serververzeichnis vor dem ersten Schreiben eine eigene Datei:
 * daneben anlegen, dann umbenennen. Der Blob behaelt seinen Inhalt.
 */
final class UnsharingAccessor implements SftpFileSystemAccessor {

    @Override
    public SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle fileHandle,
                                        Path file, String handle,
                                        Set<? extends OpenOption> options,
                                        FileAttribute<?>... attrs) throws IOException {
        if (options.contains(StandardOpenOption.WRITE)
            || options.contains(StandardOpenOption.APPEND)) {
            Path jail = subsystem.getServerSession().getAttribute(SftpGateway.JAIL);
            if (jail != null) {
                unshare(localPath(jail, file),
                        options.contains(StandardOpenOption.TRUNCATE_EXISTING));
            }
        }
        return SftpFileSystemAccessor.super.openFile(subsystem, fileHandle, file, handle,
                options, attrs);
    }

    /**
     * Der Pfad auf der Platte zu einem Pfad der Sitzung.
     *
     * <p>Die Sitzung sieht ihr Serververzeichnis als {@code /}. Hier wird nichts
     * freigegeben, was der eingesperrte Pfad nicht ohnehin erreicht - faellt das Ergebnis
     * aus dem Verzeichnis heraus, wird es abgelehnt statt aufgeloest.
     */
    static Path localPath(Path jail, Path file) throws IOException {
        String relative = file.toAbsolutePath().normalize().toString().replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        Path local = jail.resolve(relative).normalize();
        if (!local.startsWith(jail)) {
            throw new IOException("Pfad liegt ausserhalb des Serververzeichnisses");
        }
        return local;
    }

    /**
     * Gibt der Datei einen eigenen Inhalt, falls sie ihn mit etwas teilt.
     *
     * @param truncating ob der Inhalt gleich ohnehin verworfen wird - dann muss er nicht
     *                   erst kopiert werden
     */
    static void unshare(Path local, boolean truncating) throws IOException {
        if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) || !isShared(local)) {
            return;
        }
        Path own = local.resolveSibling(
                "." + local.getFileName() + ".vibecloud-" + UUID.randomUUID());
        try {
            if (truncating) {
                Files.createFile(own);
                keepPermissions(local, own);
            } else {
                Files.copy(local, own, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.move(own, local, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(own);
        }
    }

    /** Ein Startskript soll nach dem Hochladen noch ausfuehrbar sein. */
    private static void keepPermissions(Path from, Path to) {
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
        } catch (IOException | UnsupportedOperationException exception) {
            // Kein POSIX-Dateisystem - dann gibt es auch nichts zu uebernehmen.
        }
    }

    /**
     * Ob es zu dieser Datei weitere Namen gibt.
     *
     * <p>Die Anzahl der Links kennt Java nur auf POSIX-Dateisystemen. Wo sie fehlt
     * (Windows), gilt jede Datei als geteilt: einmal zu oft kopiert ist harmlos, einmal zu
     * wenig veraendert den Cache.
     */
    private static boolean isShared(Path local) {
        try {
            Object links = Files.getAttribute(local, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return !(links instanceof Number count) || count.intValue() > 1;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException exception) {
            return true;
        }
    }
}
