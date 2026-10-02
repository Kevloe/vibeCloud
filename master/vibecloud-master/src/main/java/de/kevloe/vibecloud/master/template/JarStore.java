package de.kevloe.vibecloud.master.template;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.kevloe.vibecloud.api.server.ServerGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Beschafft Server-Jars und legt sie zentral ab (PLAN.md Abschnitt 6).
 *
 * <p>Der Master laedt <b>einmal</b> herunter; die Wrapper ziehen das Jar wie jede andere
 * Datei ueber seinen Hash. Damit gibt es keinen Download pro Root und keine
 * Versions-Drift zwischen Nodes.
 *
 * <p>Die PaperMC-API liefert zu jedem Build eine SHA-256-Pruefsumme. Die wird geprueft -
 * ein halb heruntergeladenes Jar wuerde sonst erst beim Serverstart auffallen, und dann
 * auf jedem Node gleichzeitig.
 */
public final class JarStore {

    private static final Logger LOG = LoggerFactory.getLogger(JarStore.class);

    private static final String FILL_API = "https://fill.papermc.io/v3/projects/%s/versions/%s/builds/latest";
    private static final Gson GSON = new Gson();

    /** Wie viele Versionen fuer die Aufloesung von "latest" hoechstens geprueft werden. */
    private static final int MAX_LATEST_CANDIDATES = 8;

    private final Path directory;
    private final HttpClient http;

    /** Verhindert, dass zwei Gruppen dasselbe Jar gleichzeitig herunterladen. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * Aufgeloeste "latest"-Versionen je Projekt. Ohne das wuerde jeder Serverstart
     * mehrere API-Abfragen ausloesen - bei einer dynamischen Gruppe also dauernd.
     */
    private final Map<String, String> latestStableCache = new ConcurrentHashMap<>();

    public JarStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Besorgt das Jar einer Gruppe.
     *
     * @return Pfad zum Jar, oder {@code null} wenn die Gruppe ihr Jar im Template mitbringt
     */
    public Path resolve(ServerGroup group) throws IOException, InterruptedException {
        String source = group.jarSource();

        if (source.equals("template")) {
            // Selbst gebaute Jars (Minestom, Forks) liegen im Gruppen-Template.
            return null;
        }
        if (source.startsWith("custom:")) {
            String url = source.substring("custom:".length());
            return downloadIfMissing(fileNameFromUrl(url), url, null);
        }

        String project = switch (source) {
            case "paper" -> "paper";
            case "velocity" -> "velocity";
            default -> throw new IOException("Unbekannte jar_source: " + source);
        };
        return resolveFromPaperApi(project, group.mcVersion());
    }

    private Path resolveFromPaperApi(String project, String version)
            throws IOException, InterruptedException {

        String effectiveVersion = version.equals("latest")
                ? latestStableVersion(project)
                : version;

        String api = FILL_API.formatted(project, effectiveVersion);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(api)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("PaperMC-API antwortete mit %d fuer %s %s"
                    .formatted(response.statusCode(), project, effectiveVersion));
        }

        JsonObject build = GSON.fromJson(response.body(), JsonObject.class);
        JsonObject download = build.getAsJsonObject("downloads").getAsJsonObject("server:default");

        String name = download.get("name").getAsString();
        String url = download.get("url").getAsString();
        String sha256 = download.getAsJsonObject("checksums").get("sha256").getAsString();
        String channel = build.get("channel").getAsString();

        if (!"STABLE".equals(channel)) {
            LOG.warn("{} {} ist als {} markiert, nicht STABLE - fuer den Produktivbetrieb "
                     + "besser eine stabile Version in der Gruppe eintragen",
                    project, effectiveVersion, channel);
        }
        return downloadIfMissing(name, url, sha256);
    }

    /**
     * Loest {@code mc_version: "latest"} auf.
     *
     * <p><b>"latest" heisst: die neueste Version mit stabilen Builds</b> - nicht die
     * neueste Version ueberhaupt. Letzteres klingt gleich, ist es aber nicht: Zum
     * Zeitpunkt dieser Zeilen ist 26.3 die neueste Version, hat aber nur Beta-Builds.
     * Ein Netzwerk wuerde damit unbemerkt auf einer Beta laufen.
     *
     * <p>Wer bewusst eine Beta will, traegt die Version direkt in die Gruppe ein
     * ({@code group edit <name> mcVersion 26.3}).
     */
    private String latestStableVersion(String project) throws IOException, InterruptedException {
        String cached = latestStableCache.get(project);
        if (cached != null) {
            return cached;
        }

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                        "https://fill.papermc.io/v3/projects/" + project)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Versionsliste fuer " + project + " nicht abrufbar");
        }
        JsonObject versions = GSON.fromJson(response.body(), JsonObject.class)
                .getAsJsonObject("versions");

        // Die API liefert Familien ("26.3", "26.2", "1.21") in absteigender Aktualitaet.
        List<String> candidates = new ArrayList<>();
        for (String family : versions.keySet()) {
            versions.getAsJsonArray(family).forEach(entry ->
                    candidates.add(entry.getAsString()));
            // Mehr als die neuesten zwei Familien sind fuer "latest" nie relevant und
            // wuerden nur die API belasten.
            if (candidates.size() >= MAX_LATEST_CANDIDATES) {
                break;
            }
        }

        for (String version : candidates) {
            // Vorabversionen ueberspringen, ohne dafuer die API zu fragen.
            if (version.contains("-pre") || version.contains("-rc")) {
                continue;
            }
            Optional<String> channel = channelOf(project, version);
            if (channel.filter("STABLE"::equals).isPresent()) {
                LOG.info("Neueste stabile {}-Version: {}", project, version);
                latestStableCache.put(project, version);
                return version;
            }
            channel.ifPresent(value -> LOG.debug("{} {} ist {} - wird fuer 'latest' uebersprungen",
                    project, version, value));
        }
        throw new IOException("Keine stabile " + project + "-Version gefunden. Bitte eine "
                             + "Version direkt in der Gruppe eintragen (group edit <name> "
                             + "mcVersion <version>).");
    }

    /** @return Kanal des neuesten Builds dieser Version, oder leer wenn es keinen gibt */
    private Optional<String> channelOf(String project, String version)
            throws IOException, InterruptedException {

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(FILL_API.formatted(project, version)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        JsonObject build = GSON.fromJson(response.body(), JsonObject.class);
        return Optional.ofNullable(build.get("channel")).map(element -> element.getAsString());
    }

    private Path downloadIfMissing(String fileName, String url, String expectedSha256)
            throws IOException, InterruptedException {

        Path target = directory.resolve(fileName);
        Object lock = locks.computeIfAbsent(fileName, key -> new Object());

        synchronized (lock) {
            if (Files.exists(target)) {
                if (expectedSha256 == null || expectedSha256.equals(sha256Of(target))) {
                    return target;
                }
                LOG.warn("{} hat die falsche Pruefsumme und wird neu geladen", fileName);
                Files.delete(target);
            }

            LOG.info("Lade {} ...", fileName);
            Path temporary = directory.resolve(fileName + ".part");
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Download von " + url + " antwortete mit "
                                      + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }

            if (expectedSha256 != null) {
                String actual = sha256Of(temporary);
                if (!expectedSha256.equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(temporary);
                    throw new IOException("""
                            Pruefsumme von %s stimmt nicht - Download verworfen.
                              erwartet: %s
                              erhalten: %s""".formatted(fileName, expectedSha256, actual));
                }
            }
            // Erst umbenennen, wenn die Datei vollstaendig und geprueft ist: Ein Abbruch
            // soll keine halbe Datei zurueck lassen, die beim naechsten Start benutzt wird.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("{} bereit ({} MB)", fileName, Files.size(target) / (1024 * 1024));
            return target;
        }
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

    private static String fileNameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash < 0 ? url : url.substring(slash + 1);
        int query = name.indexOf('?');
        return query < 0 ? name : name.substring(0, query);
    }
}
