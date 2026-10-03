package de.kevloe.vibecloud.wrapper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Java-Installationen, mit denen dieser Node Gameserver starten kann.
 *
 * <p>Jede Minecraft-Version hat ihre Java: Paper 1.16 lief mit 8 bis 16, 1.18 braucht 17,
 * 1.20.5 braucht 21, 26.x braucht 25. Der Master sagt pro Start, welche mindestens noetig
 * ist ({@code StartServer.java_version}); hier wird die <b>kleinste passende</b> gewaehlt.
 * Die aelteste, die reicht, ist die, fuer die die Version gebaut wurde - eine neuere bricht
 * alte Server eher, als dass sie ihnen hilft.
 *
 * <p>Die Version einer Installation wird erfragt, nicht aus dem Pfad geraten: Ein Verzeichnis
 * {@code jdk-17} kann alles enthalten.
 */
public final class JavaRuntimes {

    private static final Logger LOG = LoggerFactory.getLogger(JavaRuntimes.class);

    /** Hauptversion -> Programm. Sortiert, damit die kleinste passende vorn steht. */
    private final TreeMap<Integer, String> executables;

    JavaRuntimes(Map<Integer, String> executables) {
        this.executables = new TreeMap<>(executables);
    }

    /**
     * Erkennt die eingetragenen Installationen und nimmt die eigene Java dazu.
     *
     * <p>Eine Installation, die sich nicht starten laesst, fuehrt nicht zum Abbruch: Der
     * Wrapper laeuft weiter, die Warnung nennt den Pfad, und der Master startet dort nur,
     * was ohne sie geht.
     */
    public static JavaRuntimes detect(List<String> configured) {
        Map<Integer, String> found = new TreeMap<>();
        found.put(Runtime.version().feature(), ownExecutable());

        for (String entry : configured) {
            Optional<Path> executable = executableOf(entry);
            if (executable.isEmpty()) {
                LOG.warn("javaRuntimes: {} ist kein java-Programm und kein Java-Verzeichnis - "
                         + "wird ignoriert", entry);
                continue;
            }
            OptionalInt version = probe(executable.get());
            if (version.isEmpty()) {
                LOG.warn("javaRuntimes: {} meldet keine Version - wird ignoriert",
                        executable.get());
                continue;
            }
            // Die erste Angabe einer Version gewinnt; die eigene Java steht schon drin.
            String previous = found.putIfAbsent(version.getAsInt(), executable.get().toString());
            if (previous != null && !previous.equals(executable.get().toString())) {
                LOG.info("javaRuntimes: {} ist ebenfalls Java {} - es bleibt bei {}",
                        executable.get(), version.getAsInt(), previous);
            }
        }
        JavaRuntimes runtimes = new JavaRuntimes(found);
        LOG.info("Java fuer Gameserver: {}", runtimes.describe());
        return runtimes;
    }

    /** Die vorhandenen Hauptversionen, aufsteigend - fuer die Anmeldung beim Master. */
    public List<Integer> versions() {
        return List.copyOf(executables.keySet());
    }

    /**
     * Die kleinste Installation mit mindestens dieser Version.
     *
     * @param required 0 = die eigene Java des Wrappers, wie vor der Versionswahl
     */
    public Optional<Selected> select(int required) {
        if (required <= 0) {
            int own = Runtime.version().feature();
            return Optional.of(new Selected(own, executables.get(own)));
        }
        Map.Entry<Integer, String> entry = executables.ceilingEntry(required);
        return entry == null
                ? Optional.empty()
                : Optional.of(new Selected(entry.getKey(), entry.getValue()));
    }

    public record Selected(int version, String executable) {
    }

    String describe() {
        StringBuilder text = new StringBuilder();
        executables.forEach((version, path) -> text.append(text.isEmpty() ? "" : ", ")
                .append(version).append(" (").append(path).append(')'));
        return text.toString();
    }

    // ---------------------------------------------------------------- Erkennung

    /** Dieselbe JVM wie der Wrapper - so laeuft nicht versehentlich eine aeltere Java. */
    private static String ownExecutable() {
        Path java = Path.of(System.getProperty("java.home"), "bin", executableName());
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    /** Ein Eintrag darf das Programm selbst oder das Java-Verzeichnis sein. */
    static Optional<Path> executableOf(String entry) {
        if (entry == null || entry.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(entry.trim());
        if (Files.isDirectory(path)) {
            path = path.resolve("bin").resolve(executableName());
        }
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
    }

    /**
     * Fragt eine Installation nach ihrer Version.
     *
     * <p>{@code -XshowSettings:properties -version} kennt jede Java seit 8. Die Ausgabe geht
     * auf stderr; gelesen wird {@code java.specification.version}, nicht der Text von
     * {@code -version} - der sieht je nach Hersteller anders aus.
     */
    private static OptionalInt probe(Path executable) {
        try {
            Process process = new ProcessBuilder(executable.toString(),
                    "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return OptionalInt.empty();
            }
            return parseSpecificationVersion(output);
        } catch (IOException exception) {
            return OptionalInt.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        }
    }

    private static final Pattern SPECIFICATION =
            Pattern.compile("java\\.specification\\.version\\s*=\\s*(\\S+)");

    /** "1.8" heisst Java 8, ab 9 steht die Hauptversion allein da. */
    static OptionalInt parseSpecificationVersion(String output) {
        Matcher matcher = SPECIFICATION.matcher(output);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        String value = matcher.group(1);
        try {
            return OptionalInt.of(value.startsWith("1.")
                    ? Integer.parseInt(value.substring(2))
                    : Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }
}
