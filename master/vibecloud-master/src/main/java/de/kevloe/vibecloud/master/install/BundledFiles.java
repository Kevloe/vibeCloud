package de.kevloe.vibecloud.master.install;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Packt die Plattform-Plugins aus der Master-Jar ins Arbeitsverzeichnis.
 *
 * <p>Damit braucht ein Root nur {@code CloudMaster.jar}: Ohne {@code vibecloud-paper.jar}
 * in {@code templates/global/server/plugins/} startet zwar jeder Server, ist aber nicht Teil
 * der Cloud. <b>Module kommen nicht mit</b> - die legt der Betreiber selbst nach
 * {@code modules/}.
 *
 * <p>Die Regel je Datei, mit dem zuletzt ausgepackten Stand in {@value #STATE_FILE}:
 * <ul>
 *   <li>fehlt -> auspacken. Ohne Cloud-Plugin ist ein Server nicht Teil der Cloud; ein
 *       geloeschtes Plugin ist eher ein Versehen als eine Entscheidung.</li>
 *   <li>gleich dem, was die Jar mitbringt -> nichts zu tun.</li>
 *   <li>gleich dem zuletzt Ausgepackten -> ersetzen. So kommt ein Plugin-Update mit dem
 *       Update des Masters an, ohne dass jemand Dateien kopiert.</li>
 *   <li>sonst hat sie jemand ersetzt -> liegen lassen und warnen. Eine eigene Fassung
 *       stillschweigend zu ueberschreiben waere schlimmer als eine veraltete.</li>
 * </ul>
 */
public final class BundledFiles {

    private static final Logger LOG = LoggerFactory.getLogger(BundledFiles.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final String STATE_FILE = ".vibecloud-bundled.json";
    /** Praefix in der Jar. Darunter steht derselbe Pfad wie im Arbeitsverzeichnis. */
    static final String RESOURCE_PREFIX = "bundled/";

    /** Muss zu {@code bundledResources} in {@code master/vibecloud-master/build.gradle.kts} passen. */
    static final List<String> TARGETS = List.of(
            "templates/global/server/plugins/vibecloud-paper.jar",
            "templates/global/server-legacy/plugins/vibecloud-paper-legacy.jar",
            "templates/global/proxy/plugins/vibecloud-velocity.jar");

    /** Liefert eine Ressource der Jar oder {@code null}. */
    @FunctionalInterface
    public interface Resources {
        InputStream open(String name) throws IOException;
    }

    /** Was mit einer Datei passiert ist - fuer Log und Tests. */
    public enum Outcome { EXTRACTED, UPDATED, UNCHANGED, KEPT_MODIFIED, NOT_BUNDLED }

    private final Path workingDirectory;
    private final Resources resources;

    public BundledFiles(Path workingDirectory, Resources resources) {
        this.workingDirectory = workingDirectory;
        this.resources = resources;
    }

    /** Aus der Jar, in der diese Klasse steckt. */
    public static BundledFiles fromClasspath(Path workingDirectory) {
        ClassLoader loader = BundledFiles.class.getClassLoader();
        return new BundledFiles(workingDirectory, loader::getResourceAsStream);
    }

    public Map<String, Outcome> install() {
        Map<String, String> state = readState();
        Map<String, Outcome> outcomes = new TreeMap<>();
        boolean anyBundled = false;

        for (String target : TARGETS) {
            Outcome outcome = installOne(target, state);
            outcomes.put(target, outcome);
            anyBundled |= outcome != Outcome.NOT_BUNDLED;
        }
        if (anyBundled) {
            writeState(state);
        } else {
            // Start aus der IDE oder mit 'gradlew run': Dort gibt es keine Fat-Jar, und die
            // Plugins kommen wie bisher mit updateTestEnv.
            LOG.debug("Keine mitgelieferten Plugins in dieser Jar");
        }
        return outcomes;
    }

    private Outcome installOne(String target, Map<String, String> state) {
        byte[] bundled;
        try (InputStream in = resources.open(RESOURCE_PREFIX + target)) {
            if (in == null) {
                return Outcome.NOT_BUNDLED;
            }
            bundled = in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Mitgeliefertes " + target + " nicht lesbar",
                    exception);
        }
        String bundledHash = sha256(bundled);
        Path file = workingDirectory.resolve(target);

        try {
            if (Files.notExists(file)) {
                write(file, bundled);
                state.put(target, bundledHash);
                LOG.info("{} ausgepackt", target);
                return Outcome.EXTRACTED;
            }
            String current = sha256(Files.readAllBytes(file));
            if (current.equals(bundledHash)) {
                state.put(target, bundledHash);
                return Outcome.UNCHANGED;
            }
            if (current.equals(state.get(target))) {
                write(file, bundled);
                state.put(target, bundledHash);
                LOG.info("{} auf die Fassung dieses Masters aktualisiert", target);
                return Outcome.UPDATED;
            }
            LOG.warn("{} weicht von der Fassung dieses Masters ab und wurde nicht ersetzt. "
                     + "Wer die mitgelieferte will: Datei loeschen und den Master neu "
                     + "starten.", target);
            return Outcome.KEPT_MODIFIED;
        } catch (IOException exception) {
            // Unter Windows haelt ein laufender Prozess seine Dateien fest. Der Master
            // laeuft trotzdem - mit der Datei, die schon da ist.
            LOG.warn("{} konnte nicht geschrieben werden: {}", target, exception.toString());
            return Outcome.KEPT_MODIFIED;
        }
    }

    /** Ueber eine Datei daneben: Ein Absturz mitten im Schreiben laesst kein halbes Jar zurueck. */
    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, content);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private Map<String, String> readState() {
        Path file = workingDirectory.resolve(STATE_FILE);
        if (Files.notExists(file)) {
            return new TreeMap<>();
        }
        try {
            Map<String, String> read = GSON.fromJson(Files.readString(file),
                    new TypeToken<TreeMap<String, String>>() { }.getType());
            return read == null ? new TreeMap<>() : read;
        } catch (IOException | RuntimeException exception) {
            // Ohne Stand gilt jede abweichende Datei als eigene - das ist die sichere Seite.
            LOG.warn("{} nicht lesbar ({}) - abweichende Plugins bleiben unangetastet",
                    STATE_FILE, exception.getMessage());
            return new TreeMap<>();
        }
    }

    private void writeState(Map<String, String> state) {
        try {
            Files.writeString(workingDirectory.resolve(STATE_FILE), GSON.toJson(state));
        } catch (IOException exception) {
            LOG.warn("{} konnte nicht geschrieben werden: {}", STATE_FILE,
                    exception.getMessage());
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 fehlt in dieser Java", exception);
        }
    }
}
