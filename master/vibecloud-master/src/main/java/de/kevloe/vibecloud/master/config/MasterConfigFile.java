package de.kevloe.vibecloud.master.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * Die {@code config.json} zum Aendern im Betrieb - fuer {@code cloud config} und das
 * Dashboard.
 *
 * <p><b>Nicht jedes Feld ist aenderbar.</b> Draussen bleibt alles, was entscheidet, ob
 * Master und Dashboard ueberhaupt hochkommen: die Datenbank, Adresse und Port des
 * gRPC-Servers, die Schnittstelle selbst. Ein Tippfehler dort liesse sich ueber genau den
 * Weg nicht mehr zuruecknehmen, ueber den er hineinkam - und die Zugangsdaten der Datenbank
 * gehoeren ohnehin nicht in einen Browser.
 *
 * <p><b>Geschrieben wird die Datei, nicht der laufende Master.</b> Er liest seine
 * Konfiguration beim Start; ein Teil der Werte steckt danach in Diensten, die sich nicht
 * umstellen lassen (Takt des Schedulers, Heartbeat). Die Haelfte sofort und die andere
 * nach dem Neustart waere eine Regel, die sich niemand merkt - also gilt fuer alle
 * dasselbe: wirksam nach einem Neustart. Bis dahin zeigt {@link #runningValue}, womit der
 * Master gerade laeuft.
 */
public final class MasterConfigFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Ein aenderbares Feld. Lesen und Schreiben stehen in einem Eintrag - eine Liste zum
     * Anzeigen und eine zweite zum Setzen liefen beim naechsten neuen Feld auseinander.
     *
     * @param name Pfad in der Datei, {@code abschnitt.feld}
     */
    private record Field(String name, int min, int max, ToIntFunction<MasterConfig> read,
                         ObjIntConsumer<MasterConfig> write) {}

    private static final List<Field> FIELDS = List.of(
            new Field("servers.portRangeStart", 1024, 65535,
                    config -> config.servers.portRangeStart,
                    (config, value) -> config.servers.portRangeStart = value),
            new Field("servers.portRangeEnd", 1024, 65535,
                    config -> config.servers.portRangeEnd,
                    (config, value) -> config.servers.portRangeEnd = value),
            new Field("servers.proxyPort", 1, 65535,
                    config -> config.servers.proxyPort,
                    (config, value) -> config.servers.proxyPort = value),
            new Field("servers.stopGraceSeconds", 1, 3600,
                    config -> config.servers.stopGraceSeconds,
                    (config, value) -> config.servers.stopGraceSeconds = value),
            new Field("servers.schedulerIntervalSeconds", 1, 300,
                    config -> config.servers.schedulerIntervalSeconds,
                    (config, value) -> config.servers.schedulerIntervalSeconds = value),
            new Field("servers.logRetentionDays", 1, 3650,
                    config -> config.servers.logRetentionDays,
                    (config, value) -> config.servers.logRetentionDays = value),
            new Field("grpc.heartbeatIntervalSeconds", 1, 300,
                    config -> config.grpc.heartbeatIntervalSeconds,
                    (config, value) -> config.grpc.heartbeatIntervalSeconds = value),
            new Field("grpc.heartbeatTimeoutSeconds", 2, 3600,
                    config -> config.grpc.heartbeatTimeoutSeconds,
                    (config, value) -> config.grpc.heartbeatTimeoutSeconds = value),
            new Field("grpc.maxMessageSizeMb", 1, 256,
                    config -> config.grpc.maxMessageSizeMb,
                    (config, value) -> config.grpc.maxMessageSizeMb = value));

    /**
     * Felder, die nur gezeigt werden. Die Datenbank fehlt auch hier - Host und Benutzer
     * sind keine Zugangsdaten, aber die halbe Strecke dorthin.
     */
    private static final Map<String, Function<MasterConfig, String>> LOCKED = locked();

    private static Map<String, Function<MasterConfig, String>> locked() {
        Map<String, Function<MasterConfig, String>> fields = new LinkedHashMap<>();
        fields.put("grpc.bindAddress", config -> config.grpc.bindAddress);
        fields.put("grpc.port", config -> String.valueOf(config.grpc.port));
        fields.put("http.port", config -> String.valueOf(config.http.port));
        fields.put("http.secureCookies", config -> String.valueOf(config.http.secureCookies));
        fields.put("sftp.enabled", config -> String.valueOf(config.sftp.enabled));
        fields.put("sftp.port", config -> String.valueOf(config.sftp.port));
        return fields;
    }

    private final Path file;
    private final MasterConfig running;

    /**
     * @param running die Konfiguration, mit der dieser Master gestartet ist
     */
    public MasterConfigFile(Path file, MasterConfig running) {
        this.file = file;
        this.running = running;
    }

    public static List<String> editableFields() {
        return FIELDS.stream().map(Field::name).toList();
    }

    /** Der Wert eines Feldes als Text - genau so, wie {@link #update} ihn annimmt. */
    public static String valueOf(MasterConfig config, String field) {
        return String.valueOf(find(field).read().applyAsInt(config));
    }

    /** Der zulaessige Bereich eines Feldes, zum Anzeigen: {@code 1-3600}. */
    public static String rangeOf(String field) {
        Field found = find(field);
        return found.min() + "-" + found.max();
    }

    /** Womit der Master gerade laeuft - weicht nach einer Aenderung von der Datei ab. */
    public String runningValue(String field) {
        return valueOf(running, field);
    }

    /** Die gesperrten Felder mit dem Wert, mit dem der Master laeuft. */
    public Map<String, String> lockedFields() {
        Map<String, String> values = new LinkedHashMap<>();
        LOCKED.forEach((name, read) -> values.put(name, read.apply(running)));
        return values;
    }

    /** Der Stand der Datei - nicht der des laufenden Masters. */
    public MasterConfig read() throws IOException {
        try {
            MasterConfig loaded = GSON.fromJson(Files.readString(file), MasterConfig.class);
            if (loaded == null) {
                throw new IOException(file.getFileName() + " ist leer");
            }
            return loaded;
        } catch (JsonParseException exception) {
            throw new IOException(file.getFileName() + " ist kein gueltiges JSON", exception);
        }
    }

    /**
     * Setzt mehrere Felder auf einmal.
     *
     * <p>Zusammen und nicht einzeln, weil die Felder voneinander abhaengen: Wer den
     * Portbereich von 30000-30999 auf 40000-40999 verschiebt, haette mit einem Feld je
     * Aufruf zwischendurch einen Bereich, der bei 40000 anfaengt und bei 30999 aufhoert -
     * und die Pruefung lehnte den ersten Schritt ab.
     *
     * <p>Geaendert werden in der Datei nur die genannten Felder. Alles andere bleibt, wie
     * es dort steht - auch Eintraege, die diese Version nicht kennt.
     *
     * @throws IllegalArgumentException bei einem unbekannten oder gesperrten Feld, einem
     *                                  Wert, der keine Zahl ist, oder einer Kombination,
     *                                  mit der der Master nicht laufen koennte
     */
    public synchronized void update(Map<String, String> values) throws IOException {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Es ist kein Feld angegeben");
        }
        MasterConfig candidate = read();
        Map<Field, Integer> parsed = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            Field field = find(entry.getKey());
            int value;
            try {
                value = Integer.parseInt(entry.getValue().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        field.name() + " erwartet eine ganze Zahl, nicht: " + entry.getValue());
            }
            field.write().accept(candidate, value);
            parsed.put(field, value);
        }
        validate(candidate);

        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw new IOException(file.getFileName() + " ist kein gueltiges JSON", exception);
        }
        parsed.forEach((field, value) -> {
            int dot = field.name().indexOf('.');
            String section = field.name().substring(0, dot);
            JsonElement existing = root.get(section);
            JsonObject target;
            if (existing != null && existing.isJsonObject()) {
                target = existing.getAsJsonObject();
            } else {
                // Eine Datei aus einer aelteren Version kennt den Abschnitt noch nicht.
                target = new JsonObject();
                root.add(section, target);
            }
            target.addProperty(field.name().substring(dot + 1), value);
        });

        // Erst daneben schreiben, dann umbenennen: Eine halb geschriebene config.json
        // waere ein Master, der nicht mehr startet.
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(root));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Prueft, ob der Master mit dieser Konfiguration laufen koennte.
     *
     * <p>Geprueft wird das Ganze, nicht nur das geaenderte Feld: Die Regeln hier sind
     * genau die, bei denen ein Wert fuer sich stimmt und erst neben einem anderen falsch
     * ist.
     */
    static void validate(MasterConfig config) {
        for (Field field : FIELDS) {
            int value = field.read().applyAsInt(config);
            if (value < field.min() || value > field.max()) {
                throw new IllegalArgumentException(field.name() + " muss zwischen "
                        + field.min() + " und " + field.max() + " liegen, nicht " + value);
            }
        }
        MasterConfig.Servers servers = config.servers;
        if (servers.portRangeStart > servers.portRangeEnd) {
            throw new IllegalArgumentException("servers.portRangeStart (" + servers.portRangeStart
                    + ") liegt hinter servers.portRangeEnd (" + servers.portRangeEnd + ")");
        }
        if (servers.proxyPort >= servers.portRangeStart
            && servers.proxyPort <= servers.portRangeEnd) {
            // Der Gameserver-Bereich ist per Firewall auf die Proxy-IPs begrenzt - ein
            // Proxy darin waere fuer Spieler unerreichbar.
            throw new IllegalArgumentException("servers.proxyPort (" + servers.proxyPort
                    + ") darf nicht im Gameserver-Bereich " + servers.portRangeStart + "-"
                    + servers.portRangeEnd + " liegen");
        }
        if (config.grpc.heartbeatTimeoutSeconds <= config.grpc.heartbeatIntervalSeconds) {
            // Sonst gaelte jeder Node zwischen zwei Lebenszeichen als ausgefallen.
            throw new IllegalArgumentException("grpc.heartbeatTimeoutSeconds muss groesser sein "
                    + "als grpc.heartbeatIntervalSeconds");
        }
    }

    private static Field find(String name) {
        return FIELDS.stream()
                .filter(field -> field.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(LOCKED.containsKey(name)
                        ? name + " laesst sich nur in der config.json aendern"
                        : "Unbekanntes Feld: " + name + " (moeglich: "
                          + String.join(", ", editableFields()) + ")"));
    }
}
