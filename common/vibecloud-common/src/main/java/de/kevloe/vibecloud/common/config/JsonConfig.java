package de.kevloe.vibecloud.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Laedt eine JSON-Konfiguration und schreibt beim ersten Start eine Vorlage.
 *
 * <p>Konfigurationsdateien enthalten Zugangsdaten und stehen deshalb in {@code .gitignore}.
 * Daneben wird automatisch eine {@code *.example.json} ohne Geheimnisse abgelegt, damit
 * nachvollziehbar bleibt, welche Felder es gibt.
 */
public final class JsonConfig {

    private static final Logger LOG = LoggerFactory.getLogger(JsonConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * @param path     Pfad der Konfiguration
     * @param type     Zielklasse (ein Record oder eine einfache Klasse)
     * @param template Vorgabe, die geschrieben wird, wenn die Datei fehlt
     * @return geladene Konfiguration, oder {@code null} wenn sie neu erzeugt wurde
     */
    public static <T> T loadOrCreate(Path path, Class<T> type, T template) {
        try {
            if (Files.notExists(path)) {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, GSON.toJson(template));
                writeExample(path, template);
                LOG.warn("{} wurde neu angelegt. Bitte pruefen und die Cloud erneut starten.",
                        path.getFileName());
                return null;
            }
            T loaded = GSON.fromJson(Files.readString(path), type);
            if (loaded == null) {
                throw new IllegalStateException(path + " ist leer");
            }
            writeExample(path, template);
            return loaded;
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException(path + " ist kein gueltiges JSON", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Konfiguration " + path + " nicht lesbar", exception);
        }
    }

    /**
     * Schreibt eine fertige Konfiguration samt {@code *.example.json} daneben.
     *
     * <p>Fuer Werte, die jemand gerade eingegeben hat - Einrichtungs-Assistent des Masters,
     * {@code join} des Wrappers. Eine vorhandene Datei wird <b>nicht</b> ueberschrieben:
     * Darin koennen Zugangsdaten stehen, die es nirgends sonst gibt.
     *
     * @param template Vorgabe fuer die {@code *.example.json} - ohne Geheimnisse
     * @throws java.nio.file.FileAlreadyExistsException wenn die Datei schon existiert
     */
    public static void write(Path path, Object value, Object template) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, GSON.toJson(value), java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
        writeExample(path, template);
    }

    private static void writeExample(Path path, Object template) throws IOException {
        String fileName = path.getFileName().toString();
        String base = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - ".json".length())
                : fileName;
        Path example = path.resolveSibling(base + ".example.json");
        Files.writeString(example, GSON.toJson(template));
    }

    private JsonConfig() {
    }
}
