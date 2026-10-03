package de.kevloe.vibecloud.wrapper.config;

import de.kevloe.vibecloud.common.config.JsonConfig;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * {@code java -jar CloudWrapper.jar join <master[:port]> <node> <token> <fingerprint>}.
 *
 * <p>Schreibt die {@code wrapper.json} aus der Zeile, die {@code node add} am Master ausgibt.
 * Damit braucht ein neuer Root nur die Jar und diesen einen Befehl - kein Kopieren von JSON
 * von Hand, bei dem ein Anfuehrungszeichen verloren geht.
 *
 * <p>Eine vorhandene {@code wrapper.json} wird nie ueberschrieben: Darin steht das Token
 * eines Nodes, das sich nicht wieder anzeigen laesst.
 */
public final class WrapperJoin {

    public static final String USAGE =
            "java -jar CloudWrapper.jar join <master[:port]> <node> <token> <fingerprint>";

    /** Die Angaben aus der Befehlszeile ohne das {@code join} davor. */
    public static WrapperConfig parse(List<String> args) {
        if (args.size() != 4) {
            throw new IllegalArgumentException("Syntax: " + USAGE);
        }
        WrapperConfig config = new WrapperConfig();

        String master = args.get(0);
        int colon = master.lastIndexOf(':');
        // Eine IPv6-Adresse ohne Klammern hat mehrere Doppelpunkte - dort gibt es keinen Port.
        if (colon > 0 && master.indexOf(':') == colon) {
            config.masterHost = master.substring(0, colon);
            config.masterPort = parsePort(master.substring(colon + 1));
        } else if (master.startsWith("[") && master.contains("]:")) {
            int end = master.indexOf("]:");
            config.masterHost = master.substring(1, end);
            config.masterPort = parsePort(master.substring(end + 2));
        } else {
            config.masterHost = master;
        }
        if (config.masterHost.isBlank()) {
            throw new IllegalArgumentException("Keine Adresse des Masters angegeben");
        }

        config.node = args.get(1);
        config.token = args.get(2);
        config.masterFingerprint = args.get(3);
        if (!config.masterFingerprint.startsWith("sha256:")) {
            throw new IllegalArgumentException("Der Fingerprint beginnt mit 'sha256:' - "
                    + "die Reihenfolge ist <node> <token> <fingerprint>");
        }
        return config;
    }

    /**
     * Schreibt die Datei. Unter Linux nur fuer den eigenen Benutzer lesbar - darin steht
     * das Token.
     *
     * @throws FileAlreadyExistsException wenn es schon eine {@code wrapper.json} gibt
     */
    public static void write(Path file, WrapperConfig config) throws IOException {
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString());
        }
        JsonConfig.write(file, config, new WrapperConfig());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Ungueltiger Port: " + text);
        }
    }

    private WrapperJoin() {
    }
}
