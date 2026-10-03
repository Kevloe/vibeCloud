package de.kevloe.vibecloud.master.template;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.common.MinecraftVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Welche Server-Versionen eine Gruppe haben kann, und was jede davon braucht.
 *
 * <p>Die Liste kommt aus der PaperMC-API ({@code /v3/projects/<projekt>/versions}) - ein
 * einziger Aufruf liefert alle Versionen samt Mindest-Java. Eine eigene Liste im Code
 * waere mit der naechsten Paper-Version veraltet.
 *
 * <h2>Die drei Grenzen</h2>
 * <ul>
 *   <li><b>Paper ab 1.16</b>: Darunter gibt es kein Cloud-Plugin. Ein Server ohne Plugin
 *       meldet weder Spieler noch Rechte und waere nur scheinbar Teil der Cloud.</li>
 *   <li><b>Unter 26.2 das Legacy-Plugin</b>: {@code vibecloud-paper.jar} ist fuer Java 25 und
 *       {@code api-version: 26.2} gebaut - schon 26.1 lehnt es ab. Dort kommt
 *       {@code vibecloud-paper-legacy.jar} hin, gebaut fuer Java 17.</li>
 *   <li><b>Velocity ab 4.2</b>: Gegen diese API ist {@code vibecloud-velocity} gebaut.</li>
 * </ul>
 *
 * <h2>Java</h2>
 * Gebraucht wird das Hoehere aus dem, was die API fuer die Version nennt, und dem, wofuer
 * das Plugin gebaut ist. Paper 1.16 nennt Java 8, das Legacy-Plugin braucht 17 - also 17.
 * Paper 1.16 und 1.17 weigern sich ueber Java 16 zu starten ("Only up to Java 16 is
 * supported"); mit {@code -DPaper.IgnoreJavaVersion=true} laufen sie. Geprueft mit
 * 1.16.1 und 1.16.5 unter Temurin 17.
 */
public final class VersionCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(VersionCatalog.class);
    private static final Gson GSON = new Gson();

    /** Darunter gibt es kein Cloud-Plugin. */
    public static final MinecraftVersion PAPER_MINIMUM = MinecraftVersion.of("1.16");

    /** Ab hier laedt {@code vibecloud-paper.jar} - das ist dessen {@code api-version}. */
    public static final MinecraftVersion MODERN_PLUGIN_MINIMUM = MinecraftVersion.of("26.2");

    /** Gegen diese Velocity-API ist {@code vibecloud-velocity} gebaut (libs.versions.toml). */
    public static final MinecraftVersion VELOCITY_MINIMUM = MinecraftVersion.of("4.2");

    /** Fuer diese Java-Versionen sind die Plugins gebaut. */
    public static final int MODERN_PLUGIN_JAVA = 25;
    public static final int LEGACY_PLUGIN_JAVA = 17;

    /** Paper bis 1.17 startet sonst nicht unter Java 17. */
    static final String IGNORE_JAVA_VERSION_FLAG = "-DPaper.IgnoreJavaVersion=true";

    /** So lange gilt eine abgerufene Liste. Neue Paper-Versionen kommen selten. */
    private static final Duration MAX_AGE = Duration.ofHours(1);

    /** Nach einem Fehlschlag nicht bei jedem Tastendruck erneut fragen. */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(2);

    private final Source source;
    private final Clock clock;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    public VersionCatalog() {
        this(httpSource(), Clock.systemUTC());
    }

    VersionCatalog(Source source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /** Woher die Rohdaten kommen - in Tests eine feste Antwort. */
    @FunctionalInterface
    interface Source {
        /** @return JSON von {@code /v3/projects/<project>/versions} */
        String fetch(String project) throws IOException, InterruptedException;
    }

    /**
     * Eine waehlbare Version.
     *
     * @param javaMinimum was die PaperMC-API als Mindest-Java nennt
     * @param java        was der Server wirklich braucht (inklusive Plugin)
     * @param legacy      ob das Legacy-Plugin hineinkommt
     * @param supported   ob PaperMC die Version noch pflegt
     */
    public record Version(String id, int javaMinimum, int java, boolean legacy,
                          boolean supported) {
    }

    private record Snapshot(List<Version> versions, Instant fetchedAt) {
    }

    // ---------------------------------------------------------------- Abfragen

    /**
     * Die waehlbaren Versionen, neueste zuerst. Fragt die API, wenn die Liste fehlt oder
     * alt ist.
     *
     * @return leer bei MINESTOM - oder wenn die API nicht erreichbar ist und es noch keine
     *         Liste gibt
     */
    public List<Version> versions(ServerPlatformType platform) {
        Optional<String> project = projectOf(platform);
        if (project.isEmpty()) {
            return List.of();
        }
        Snapshot snapshot = cache.get(project.get());
        if (snapshot != null && snapshot.fetchedAt().plus(MAX_AGE).isAfter(clock.instant())) {
            return snapshot.versions();
        }
        Instant failed = lastFailure.get(project.get());
        if (failed != null && failed.plus(RETRY_AFTER_FAILURE).isAfter(clock.instant())) {
            return snapshot == null ? List.of() : snapshot.versions();
        }
        try {
            List<Version> fresh = parse(platform, source.fetch(project.get()));
            cache.put(project.get(), new Snapshot(fresh, clock.instant()));
            lastFailure.remove(project.get());
            return fresh;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            lastFailure.put(project.get(), clock.instant());
            LOG.warn("Versionsliste fuer {} nicht abrufbar: {}", project.get(),
                    exception.getMessage());
        }
        // Lieber eine alte Liste als keine: Die Versionen verschwinden ja nicht.
        return snapshot == null ? List.of() : snapshot.versions();
    }

    /**
     * Nur was schon da ist, ohne Netz - fuer die Tab-Vervollstaendigung. Ein Tastendruck
     * darf nicht auf die PaperMC-API warten. Fehlt die Liste, wird sie im Hintergrund
     * geholt und ist beim naechsten Tab da.
     */
    public List<Version> cachedVersions(ServerPlatformType platform) {
        Optional<String> project = projectOf(platform);
        if (project.isEmpty()) {
            return List.of();
        }
        Snapshot snapshot = cache.get(project.get());
        if (snapshot == null || snapshot.fetchedAt().plus(MAX_AGE).isBefore(clock.instant())) {
            Thread.ofVirtual().name("version-catalog").start(() -> versions(platform));
        }
        return snapshot == null ? List.of() : snapshot.versions();
    }

    public Optional<Version> find(ServerPlatformType platform, String id) {
        return versions(platform).stream().filter(version -> version.id().equals(id)).findFirst();
    }

    // ---------------------------------------------------------------- Regeln

    /**
     * Prueft die Version einer Gruppe, bevor sie gespeichert wird.
     *
     * <p>Nur bei {@code jar_source} {@code paper} und {@code velocity} - ein eigenes Jar
     * ({@code template}, {@code custom:}) kennt die Cloud nicht und redet nicht hinein.
     *
     * @return Fehlermeldung, oder leer wenn die Version passt
     */
    public Optional<String> problemWith(ServerGroup group) {
        String version = group.mcVersion();
        String jarSource = group.jarSource();
        if (!jarSource.equals("paper") && !jarSource.equals("velocity")) {
            return Optional.empty();
        }
        if (jarSource.equals("paper") && group.platform() != ServerPlatformType.PAPER) {
            return Optional.of("jar_source paper passt nur zu einer PAPER-Gruppe");
        }
        if (jarSource.equals("velocity") && group.platform() != ServerPlatformType.VELOCITY) {
            return Optional.of("jar_source velocity passt nur zu einer VELOCITY-Gruppe");
        }
        if (version.equals("latest")) {
            return Optional.empty();
        }
        Optional<MinecraftVersion> parsed = MinecraftVersion.parse(version);
        if (parsed.isEmpty()) {
            return Optional.of("'" + version + "' ist keine Release-Version (Vorabversionen "
                               + "wie -rc, -pre und SNAPSHOT sind nicht waehlbar)");
        }
        MinecraftVersion minimum = group.platform() == ServerPlatformType.VELOCITY
                ? VELOCITY_MINIMUM : PAPER_MINIMUM;
        if (parsed.get().compareTo(minimum) < 0) {
            return Optional.of(version + " ist zu alt - das Cloud-Plugin gibt es ab "
                               + minimum);
        }

        List<Version> known = versions(group.platform());
        if (known.isEmpty()) {
            // Ohne Liste laesst sich nur die Form pruefen. Ablehnen hiesse, dass ohne
            // Internet keine Gruppe mehr anzulegen ist - der Fehler kaeme beim Download
            // ohnehin, mit derselben Meldung der API.
            LOG.warn("Versionsliste nicht verfuegbar - {} wird ungeprueft uebernommen", version);
            return Optional.empty();
        }
        if (known.stream().noneMatch(entry -> entry.id().equals(version))) {
            return Optional.of("Die Version " + version + " gibt es nicht. Neueste: "
                               + String.join(", ", known.stream().limit(6)
                                       .map(Version::id).toList())
                               + " (alle: group versions " + group.platform() + ")");
        }
        return Optional.empty();
    }

    /** Ob ein Paper-Server dieser Version das Legacy-Plugin bekommt. */
    public static boolean isLegacy(ServerPlatformType platform, String version) {
        return platform == ServerPlatformType.PAPER
               && MinecraftVersion.parse(version)
                       .map(parsed -> parsed.compareTo(MODERN_PLUGIN_MINIMUM) < 0)
                       .orElse(false);
    }

    /**
     * Welche Java-Version der Server braucht: das Hoehere aus API und Plugin.
     *
     * @param javaMinimum Mindest-Java laut API, 0 wenn unbekannt
     */
    public static int requiredJava(ServerPlatformType platform, String version, int javaMinimum) {
        int plugin = switch (platform) {
            case PAPER -> isLegacy(platform, version) ? LEGACY_PLUGIN_JAVA : MODERN_PLUGIN_JAVA;
            case VELOCITY -> MODERN_PLUGIN_JAVA;
            case MINESTOM -> 0;
        };
        return Math.max(plugin, javaMinimum);
    }

    /**
     * JVM-Flags, die die Version zusaetzlich braucht.
     *
     * <p>Paper bis 1.17 prueft beim Start die Java-Version und bricht ueber 16 ab. Das
     * Legacy-Plugin braucht aber 17 - also wird die Pruefung abgeschaltet. An der Version
     * festgemacht und nicht an der Mindest-Java der API, damit es auch bei einem eigenen
     * Jar greift, zu dem die Gruppe eine Version nennt. 1.18 ist die erste mit Java 17.
     */
    public static List<String> extraJvmFlags(ServerPlatformType platform, String version) {
        boolean beforeJava17 = MinecraftVersion.parse(version)
                .map(parsed -> parsed.compareTo(FIRST_WITH_JAVA_17) < 0)
                .orElse(false);
        return isLegacy(platform, version) && beforeJava17
                ? List.of(IGNORE_JAVA_VERSION_FLAG)
                : List.of();
    }

    private static final MinecraftVersion FIRST_WITH_JAVA_17 = MinecraftVersion.of("1.18");

    // ---------------------------------------------------------------- intern

    private static Optional<String> projectOf(ServerPlatformType platform) {
        return switch (platform) {
            case PAPER -> Optional.of("paper");
            case VELOCITY -> Optional.of("velocity");
            case MINESTOM -> Optional.empty();
        };
    }

    /**
     * Liest die API-Antwort und behaelt nur, was waehlbar ist: Releases ab der Grenze der
     * Plattform. Die Reihenfolge der API (neueste zuerst) bleibt.
     */
    static List<Version> parse(ServerPlatformType platform, String json) {
        MinecraftVersion minimum = platform == ServerPlatformType.VELOCITY
                ? VELOCITY_MINIMUM : PAPER_MINIMUM;
        List<Version> result = new ArrayList<>();

        for (JsonElement element : GSON.fromJson(json, JsonObject.class)
                .getAsJsonArray("versions")) {
            JsonObject version = element.getAsJsonObject().getAsJsonObject("version");
            String id = version.get("id").getAsString();

            Optional<MinecraftVersion> parsed = MinecraftVersion.parse(id);
            if (parsed.isEmpty() || parsed.get().compareTo(minimum) < 0) {
                continue;
            }
            int javaMinimum = javaMinimumOf(version);
            boolean supported = version.has("support")
                    && "SUPPORTED".equals(version.getAsJsonObject("support")
                            .get("status").getAsString());
            result.add(new Version(id, javaMinimum,
                    requiredJava(platform, id, javaMinimum),
                    isLegacy(platform, id), supported));
        }
        return List.copyOf(result);
    }

    private static int javaMinimumOf(JsonObject version) {
        try {
            return version.getAsJsonObject("java").getAsJsonObject("version")
                    .get("minimum").getAsInt();
        } catch (RuntimeException exception) {
            // Fehlt die Angabe, entscheidet das Plugin allein.
            return 0;
        }
    }

    private static Source httpSource() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return project -> {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(
                                    "https://fill.papermc.io/v3/projects/" + project + "/versions"))
                            .timeout(Duration.ofSeconds(15))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("PaperMC-API antwortete mit " + response.statusCode());
            }
            return response.body();
        };
    }
}
