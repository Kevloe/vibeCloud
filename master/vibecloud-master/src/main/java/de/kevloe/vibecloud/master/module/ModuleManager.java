package de.kevloe.vibecloud.master.module;

import com.google.gson.Gson;
import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.events.ModuleDisableEvent;
import de.kevloe.vibecloud.api.event.events.ModuleEnableEvent;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.module.CloudModule;
import de.kevloe.vibecloud.module.ModuleContext;
import de.kevloe.vibecloud.module.ModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Laedt und entlaedt Module zur Laufzeit (PLAN.md Abschnitt 10).
 *
 * <p>Ablauf beim Start:
 * <pre>
 * modules/ *.jar scannen  ->  module.json lesen
 *   ->  Reihenfolge aufloesen (topologisch, Zyklus abweisen)
 *   ->  je Modul einen ClassLoader
 *   ->  onLoad fuer alle
 *   ->  onEnable in Abhaengigkeitsreihenfolge
 * </pre>
 *
 * <p>Ein fehlerhaftes Modul darf die Cloud nicht verhindern: Scheitert es beim Laden oder
 * Aktivieren, wird es uebersprungen und der Rest laeuft weiter.
 */
public final class ModuleManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleManager.class);
    private static final Gson GSON = new Gson();
    private static final String MANIFEST = "module.json";

    private final Path directory;
    private final ModuleEnvironment environment;
    private final EventBus events;
    private final AuditLog audit;

    /** Geladene Module in Aktivierungsreihenfolge - wichtig fuers Entladen (umgekehrt). */
    private final Map<String, LoadedModule> loaded = new LinkedHashMap<>();

    public ModuleManager(Path directory, ModuleEnvironment environment, EventBus events,
                         AuditLog audit) throws IOException {
        this.directory = directory;
        this.environment = environment;
        this.events = events;
        this.audit = audit;
        Files.createDirectories(directory);
    }

    // ---------------------------------------------------------------- Laden

    /**
     * Scannt das Verzeichnis und aktiviert alles, was geht.
     *
     * @return Anzahl aktivierter Module
     */
    public int loadAll() {
        List<Candidate> candidates = scan();
        if (candidates.isEmpty()) {
            LOG.info("Keine Module in {} gefunden", directory.toAbsolutePath());
            return 0;
        }

        ModuleLoadOrder.Result order = ModuleLoadOrder.resolve(
                candidates.stream().map(Candidate::descriptor).toList());

        if (order.hasCycle()) {
            // Bewusst gar kein Modul laden: Bei einem Zyklus ist unklar, welches Modul den
            // Fehler hat, und halb geladene Module wuerden den Fehler verschleiern.
            LOG.error("""
                    Die Module haben einen Abhaengigkeits-Zyklus: {}
                    Es wird KEIN Modul geladen, bis das aufgeloest ist - sonst waere nicht
                    nachvollziehbar, welches Modul fehlt.""",
                    String.join(" -> ", order.cycle()));
            return 0;
        }

        order.duplicates().forEach(id ->
                LOG.error("Die Modul-Id '{}' kommt mehrfach vor. Nur das erste Jar wird "
                          + "benutzt - bitte das doppelte entfernen.", id));

        order.missing().forEach((id, missing) ->
                LOG.error("Modul '{}' braucht '{}', das aber nicht vorhanden ist - "
                          + "wird uebersprungen.", id, missing));

        Map<String, Candidate> byId = new LinkedHashMap<>();
        candidates.forEach(candidate -> byId.putIfAbsent(candidate.descriptor().id(), candidate));

        int enabled = 0;
        for (ModuleDescriptor descriptor : order.order()) {
            Candidate candidate = byId.get(descriptor.id());
            if (candidate != null && load(candidate)) {
                enabled++;
            }
        }
        LOG.info("{} von {} Modulen aktiv", enabled, candidates.size());
        return enabled;
    }

    /** Laedt ein einzelnes Modul nachtraeglich - fuer {@code module load <id>}. */
    public boolean load(String id) {
        if (loaded.containsKey(id)) {
            LOG.warn("Modul {} ist schon aktiv", id);
            return false;
        }
        Optional<Candidate> candidate = scan().stream()
                .filter(entry -> entry.descriptor().id().equals(id))
                .findFirst();

        if (candidate.isEmpty()) {
            LOG.error("Kein Jar mit der Modul-Id '{}' in {} gefunden", id, directory);
            return false;
        }

        // Harte Abhaengigkeiten muessen aktiv sein - sonst scheitert das Modul spaeter
        // mitten im onEnable, und dann ist der Zustand unklar.
        for (String dependency : candidate.get().descriptor().depends()) {
            if (!loaded.containsKey(dependency)) {
                LOG.error("Modul {} braucht {}, das nicht aktiv ist. Erst 'module load {}'.",
                        id, dependency, dependency);
                return false;
            }
        }
        return load(candidate.get());
    }

    private boolean load(Candidate candidate) {
        ModuleDescriptor descriptor = candidate.descriptor();
        ModuleClassLoader classLoader = null;

        try {
            classLoader = new ModuleClassLoader(candidate.jar(), descriptor,
                    getClass().getClassLoader(), this::loaderOf);

            Class<?> mainClass = classLoader.loadClass(descriptor.main());
            if (!CloudModule.class.isAssignableFrom(mainClass)) {
                throw new IllegalStateException(descriptor.main() + " implementiert "
                                                + "CloudModule nicht");
            }
            CloudModule module = (CloudModule) mainClass.getDeclaredConstructor().newInstance();

            MasterModuleContext context = environment.createContext(descriptor, classLoader);
            module.onLoad(context);
            module.onEnable(context);

            loaded.put(descriptor.id(),
                    new LoadedModule(descriptor, module, classLoader, context, candidate.jar()));

            audit.record("SYSTEM", "module.enabled", descriptor.id(),
                    Map.of("version", descriptor.version()));
            events.post(new ModuleEnableEvent(descriptor.id(), descriptor.version()));

            LOG.info("Modul {} {} aktiv", descriptor.id(), descriptor.version());
            return true;

        } catch (Exception exception) {
            // Ein fehlerhaftes Modul darf die Cloud nicht aufhalten.
            LOG.error("Modul {} konnte nicht aktiviert werden - wird uebersprungen",
                    descriptor.id(), exception);
            closeQuietly(classLoader);
            return false;
        }
    }

    // ---------------------------------------------------------------- Entladen

    /**
     * Entlaedt ein Modul.
     *
     * <p>Module, die davon abhaengen, werden <b>zuerst</b> entladen - sonst laufen sie
     * gegen Klassen, die es nicht mehr gibt.
     */
    public boolean unload(String id) {
        LoadedModule module = loaded.get(id);
        if (module == null) {
            return false;
        }

        List<String> dependents = loaded.values().stream()
                .filter(other -> other.descriptor().allDependencies().contains(id))
                .map(other -> other.descriptor().id())
                .toList();

        if (!dependents.isEmpty()) {
            LOG.info("Erst werden die abhaengigen Module entladen: {}", dependents);
            dependents.forEach(this::unload);
        }

        try {
            module.instance().onDisable();
        } catch (RuntimeException exception) {
            // Trotzdem aufraeumen: Ein haengendes Modul soll nicht dauerhaft Speicher und
            // Handler belegen.
            LOG.error("onDisable von {} ist fehlgeschlagen - es wird trotzdem entfernt",
                    id, exception);
        }

        // Events, Commands und Scheduler-Aufgaben abmelden, damit kein Reload leckt.
        module.context().shutdown();
        loaded.remove(id);
        closeQuietly(module.classLoader());

        audit.record("SYSTEM", "module.disabled", id);
        events.post(new ModuleDisableEvent(id));
        LOG.info("Modul {} entladen", id);
        return true;
    }

    /** Entlaedt und laedt neu - fuer {@code module reload <id>}. */
    public boolean reload(String id) {
        if (!loaded.containsKey(id)) {
            return load(id);
        }
        return unload(id) && load(id);
    }

    public void unloadAll() {
        // Umgekehrte Aktivierungsreihenfolge: Abhaengige zuerst.
        List<String> order = new ArrayList<>(loaded.keySet());
        java.util.Collections.reverse(order);
        order.forEach(this::unload);
    }

    // ---------------------------------------------------------------- Abfragen

    public List<ModuleInfo> list() {
        List<ModuleInfo> infos = new ArrayList<>();

        // Erst die aktiven ...
        loaded.values().forEach(module -> infos.add(new ModuleInfo(
                module.descriptor(), true, module.jar().getFileName().toString())));

        // ... dann die vorhandenen, aber nicht aktiven.
        scan().stream()
                .filter(candidate -> !loaded.containsKey(candidate.descriptor().id()))
                .forEach(candidate -> infos.add(new ModuleInfo(
                        candidate.descriptor(), false, candidate.jar().getFileName().toString())));

        return infos;
    }

    public Optional<ModuleDescriptor> descriptorOf(String id) {
        return Optional.ofNullable(loaded.get(id)).map(LoadedModule::descriptor);
    }

    public boolean isLoaded(String id) {
        return loaded.containsKey(id);
    }

    /**
     * Packt die Plattform-Bundles aller aktiven Module aus (PLAN.md Abschnitt 10).
     *
     * <p>Ein Modul-JAR enthaelt sein Plugin-Bundle als Datei <i>in</i> sich. Fuer den
     * Template-Sync muss es als eigene Datei vorliegen, damit es gehasht und an die Wrapper
     * verteilt werden kann.
     *
     * <p>Ausgepackt wird nur, wenn sich das Modul-JAR geaendert hat - sonst wuerde bei
     * jedem Serverstart dieselbe Datei neu geschrieben und der inhaltsadressierte Cache
     * der Wrapper bekaeme bei jedem Mal einen neuen Hash.
     *
     * @param platform {@code paper}, {@code velocity} oder {@code minestom}
     * @return Zielpfad im Server-Verzeichnis -> Datei auf der Platte
     */
    public Map<String, Path> extractBundles(String platform, Path targetDirectory) {
        Map<String, Path> bundles = new LinkedHashMap<>();

        for (LoadedModule module : loaded.values()) {
            String insideJar = module.descriptor().bundles().get(platform);
            if (insideJar == null || insideJar.isBlank()) {
                continue;
            }
            String id = module.descriptor().id();
            Path target = targetDirectory.resolve(id + "-" + platform + ".jar");

            try {
                Files.createDirectories(targetDirectory);

                boolean upToDate = Files.exists(target)
                                   && Files.getLastModifiedTime(target).toMillis()
                                      >= Files.getLastModifiedTime(module.jar()).toMillis();

                if (!upToDate) {
                    try (JarFile jarFile = new JarFile(module.jar().toFile())) {
                        var entry = jarFile.getEntry(insideJar);
                        if (entry == null) {
                            LOG.error("Modul {} nennt das Bundle '{}' fuer {}, aber im JAR "
                                      + "ist es nicht enthalten", id, insideJar, platform);
                            continue;
                        }
                        try (InputStream in = jarFile.getInputStream(entry)) {
                            Files.copy(in, target,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    LOG.info("Bundle {} von Modul {} ausgepackt", platform, id);
                }
                bundles.put("plugins/" + target.getFileName(), target);

            } catch (IOException exception) {
                // Ein fehlendes Bundle darf keinen Serverstart verhindern - das Modul
                // laeuft im Master weiter, nur ohne seinen Plugin-Teil.
                LOG.error("Bundle {} von Modul {} konnte nicht ausgepackt werden",
                        platform, id, exception);
            }
        }
        return bundles;
    }

    private Optional<ModuleClassLoader> loaderOf(String id) {
        return Optional.ofNullable(loaded.get(id)).map(LoadedModule::classLoader);
    }

    // ---------------------------------------------------------------- Scannen

    /** Liest alle {@code module.json} im Verzeichnis. */
    private List<Candidate> scan() {
        List<Candidate> candidates = new ArrayList<>();

        try (Stream<Path> files = Files.list(directory)) {
            for (Path jar : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                readDescriptor(jar).ifPresent(descriptor ->
                        candidates.add(new Candidate(jar, descriptor)));
            }
        } catch (IOException exception) {
            LOG.error("Modul-Verzeichnis {} nicht lesbar", directory, exception);
        }
        return candidates;
    }

    private Optional<ModuleDescriptor> readDescriptor(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entry = jarFile.getEntry(MANIFEST);
            if (entry == null) {
                LOG.warn("{} hat kein {} - wird ignoriert. Ist das ueberhaupt ein Modul?",
                        jar.getFileName(), MANIFEST);
                return Optional.empty();
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return Optional.of(GSON.fromJson(json, ModuleDescriptor.class));
            }
        } catch (IOException | RuntimeException exception) {
            LOG.error("{} konnte nicht gelesen werden: {}",
                    jar.getFileName(), exception.getMessage());
            return Optional.empty();
        }
    }

    private static void closeQuietly(ModuleClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException exception) {
            LOG.debug("ClassLoader nicht geschlossen", exception);
        }
    }

    @Override
    public void close() {
        unloadAll();
    }

    private record Candidate(Path jar, ModuleDescriptor descriptor) {
    }

    private record LoadedModule(
            ModuleDescriptor descriptor,
            CloudModule instance,
            ModuleClassLoader classLoader,
            MasterModuleContext context,
            Path jar) {
    }

    /** Ein Modul fuer die Anzeige. */
    public record ModuleInfo(ModuleDescriptor descriptor, boolean enabled, String fileName) {
    }

    /** Was der Manager braucht, um einen Context zu bauen - vom Bootstrap geliefert. */
    public interface ModuleEnvironment {

        MasterModuleContext createContext(ModuleDescriptor descriptor, ClassLoader classLoader);
    }

    /** Context mit Aufraeumen - der Manager braucht mehr als die Modul-API zeigt. */
    public interface MasterModuleContext extends ModuleContext {

        /** Meldet alle Registrierungen dieses Moduls ab. */
        void shutdown();
    }
}
