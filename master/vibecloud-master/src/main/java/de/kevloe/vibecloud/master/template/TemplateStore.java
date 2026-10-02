package de.kevloe.vibecloud.master.template;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.protocol.ManifestEntry;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Setzt den Dateisatz einer Gruppe zusammen (PLAN.md Abschnitt 6, Entscheidung 16.4).
 *
 * <p>Zwei Ebenen, keine stapelbaren Layer:
 * <pre>
 * templates/global/server/   -> in jeden Paper-/Minestom-Server
 * templates/global/proxy/    -> in jeden Velocity-Proxy
 * templates/&lt;template&gt;/      -> nur in Server dieser Gruppe (darf ueberschreiben)
 * </pre>
 * Dazu {@code server.jar} aus dem {@link JarStore} und ab M5 die Modul-Bundles.
 *
 * <p>Der Wrapper bekommt eine <b>fertige</b> Liste mit Zielpfaden und Hashes und muss die
 * Schichtung nicht kennen. Das haelt die Reihenfolge-Entscheidung an einer Stelle.
 */
public final class TemplateStore {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateStore.class);

    private final Path root;
    private final JarStore jars;

    /**
     * Liefert die Plugin-Bundles der aktiven Module.
     *
     * <p>Als Schnittstelle, nicht als direkte Abhaengigkeit auf den ModuleManager: Der
     * TemplateStore entsteht vor den Modulen, und eine Rueckwaerts-Abhaengigkeit waere ein
     * Zirkel im Bootstrap.
     */
    private volatile BundleProvider bundleProvider = platform -> java.util.Map.of();

    /** manifest_id -> Manifest. Wird bei jeder Zusammenstellung neu gefuellt. */
    private final Map<String, TemplateManifest> manifests = new ConcurrentHashMap<>();

    /** sha256 -> Datei auf der Platte, damit {@code PullBlobs} liefern kann. */
    private final Map<String, Path> blobs = new ConcurrentHashMap<>();

    public TemplateStore(Path root, JarStore jars) throws IOException {
        this.root = root;
        this.jars = jars;
        Files.createDirectories(root.resolve("global/server"));
        Files.createDirectories(root.resolve("global/proxy"));
    }

    /**
     * Stellt den Dateisatz einer Gruppe zusammen und gibt das Manifest zurueck.
     *
     * <p>Wird bei jedem Serverstart aufgerufen. Das ist gewollt: Eine Aenderung im Template
     * wirkt damit auf den naechsten Start, ohne dass irgendwo ein Cache geleert werden muss.
     */
    public TemplateManifest buildManifest(ServerGroup group)
            throws IOException, InterruptedException {

        // LinkedHashMap: Die Einfuegereihenfolge ist die Kopierreihenfolge. Spaetere
        // Eintraege mit demselben Zielpfad ueberschreiben frueher eingefuegte.
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();

        collect(root.resolve(group.platform().globalTemplate()), "", entries);

        Path groupTemplate = root.resolve(group.template());
        if (Files.isDirectory(groupTemplate)) {
            collect(groupTemplate, "", entries);
        } else {
            LOG.warn("Template-Verzeichnis {} fehlt - Gruppe {} startet nur mit global/*",
                    groupTemplate, group.name());
        }

        // Modul-Bundles nach dem Gruppen-Template: Sie duerfen eine gleichnamige Datei
        // aus dem Template ueberschreiben, nicht umgekehrt - sonst koennte eine alte Kopie
        // im Template das aktuelle Bundle verdecken.
        for (var bundle : bundleProvider.bundlesFor(platformName(group)).entrySet()) {
            entries.put(bundle.getKey(), entryFor(bundle.getValue(), bundle.getKey()));
        }

        Path jar = jars.resolve(group);
        if (jar != null) {
            entries.put("server.jar", entryFor(jar, "server.jar"));
        } else if (!entries.containsKey("server.jar")) {
            throw new IOException("Gruppe " + group.name() + " nutzt jar_source=template, "
                                  + "aber in " + groupTemplate + " liegt keine server.jar");
        }

        // Die manifest_id ist der Hash ueber alle Eintraege: Gleicher Inhalt heisst gleiche
        // Id, der Wrapper kann also erkennen, dass sich nichts geaendert hat.
        String manifestId = digestOf(entries);
        TemplateManifest manifest = TemplateManifest.newBuilder()
                .setManifestId(manifestId)
                .setGroupName(group.name())
                .addAllEntries(entries.values())
                .build();

        manifests.put(manifestId, manifest);
        return manifest;
    }

    public void setBundleProvider(BundleProvider provider) {
        this.bundleProvider = provider;
    }

    private static String platformName(ServerGroup group) {
        return switch (group.platform()) {
            case PAPER -> "paper";
            case VELOCITY -> "velocity";
            case MINESTOM -> "minestom";
        };
    }

    /** Woher die Modul-Bundles kommen. */
    public interface BundleProvider {

        /** @return Zielpfad im Server-Verzeichnis -> Datei auf der Platte */
        java.util.Map<String, Path> bundlesFor(String platform);
    }

    public Optional<TemplateManifest> manifest(String manifestId) {
        return Optional.ofNullable(manifests.get(manifestId));
    }

    /** Datei zu einem Hash, damit {@code PullBlobs} sie streamen kann. */
    public Optional<Path> blob(String sha256) {
        return Optional.ofNullable(blobs.get(sha256));
    }

    /** Laeuft ein Verzeichnis ab und traegt alle Dateien mit ihrem Zielpfad ein. */
    private void collect(Path directory, String prefix, Map<String, ManifestEntry> entries)
            throws IOException {

        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                String target = prefix.isEmpty() ? relative : prefix + "/" + relative;
                entries.put(target, entryFor(path, target));
            }
        }
    }

    private ManifestEntry entryFor(Path file, String target) throws IOException {
        String sha256 = JarStore.sha256Of(file);
        blobs.put(sha256, file);
        return ManifestEntry.newBuilder()
                .setPath(target)
                .setSha256(sha256)
                .setSize(Files.size(file))
                .setExecutable(target.endsWith(".sh"))
                .build();
    }

    private static String digestOf(Map<String, ManifestEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            entries.values().forEach(entry -> {
                digest.update(entry.getPath().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getSha256().getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 fehlt in dieser JVM", exception);
        }
    }

    public Path root() {
        return root;
    }
}
