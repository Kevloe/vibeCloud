package de.kevloe.vibecloud.master.module;

import de.kevloe.vibecloud.module.ModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * ClassLoader eines Moduls (PLAN.md Abschnitt 10).
 *
 * <p><b>Child-first</b>, aber mit drei Ausnahmen - und die Reihenfolge ist der ganze Trick:
 *
 * <ol>
 *   <li><b>Geteilte Pakete kommen immer vom Parent.</b> {@code vibecloud-api},
 *       {@code module-api}, {@code protocol}, SLF4J. Ohne das haette jedes Modul eigene
 *       Kopien von {@code CloudModule} und {@code EventBus} - und der Master koennte ein
 *       Modul-Objekt nicht als {@code CloudModule} ansprechen. Ein
 *       {@code ClassCastException} mit identischen Klassennamen ist der Fehler, den
 *       niemand versteht.</li>
 *   <li><b>Exportierte Pakete anderer Module</b> kommen von deren Loader. Erst dadurch kann
 *       ein Discord-Modul auf {@code PlayerBanEvent} von {@code punishment} reagieren
 *       (PLAN.md Abschnitt 10a).</li>
 *   <li><b>Alles andere</b> sucht erst im eigenen JAR, dann beim Parent. So kann ein Modul
 *       eigene Bibliotheken mitbringen, ohne mit dem Master zu kollidieren.</li>
 * </ol>
 *
 * <p>Nur <b>deklarierte</b> {@code depends} werden befragt. Die sind topologisch sortiert
 * und zyklenfrei, deshalb kann beim Laden keine Endlosschleife zwischen zwei Loadern
 * entstehen.
 */
public final class ModuleClassLoader extends URLClassLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleClassLoader.class);

    /**
     * Pakete, die immer vom Parent kommen.
     *
     * <p>{@code de.kevloe.vibecloud.master} fehlt hier absichtlich: Module sollen Interna
     * des Masters nicht sehen. Versucht ein Modul es doch, scheitert es mit
     * {@code ClassNotFoundException} - und das ist die richtige Antwort.
     */
    private static final List<String> SHARED_PACKAGES = List.of(
            "de.kevloe.vibecloud.api.",
            "de.kevloe.vibecloud.common.",
            "de.kevloe.vibecloud.protocol.",
            "de.kevloe.vibecloud.module.",
            "org.slf4j.",
            "com.google.protobuf.");

    private final ModuleDescriptor descriptor;
    private final Function<String, Optional<ModuleClassLoader>> loaderLookup;

    /**
     * @param loaderLookup liefert den Loader eines anderen Moduls - als Funktion, damit der
     *                     Loader nicht den ganzen ModuleManager kennen muss
     */
    public ModuleClassLoader(Path jar, ModuleDescriptor descriptor, ClassLoader parent,
                             Function<String, Optional<ModuleClassLoader>> loaderLookup)
            throws IOException {

        super("module-" + descriptor.id(), new URL[]{jar.toUri().toURL()}, parent);
        this.descriptor = descriptor;
        this.loaderLookup = loaderLookup;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return resolved(loaded, resolve);
            }

            // 1. Geteilte Pakete: immer der Parent, damit die Typen identisch sind.
            if (isShared(name)) {
                return resolved(super.loadClass(name, false), resolve);
            }

            // 2. Von einem Modul exportiert, von dem dieses Modul abhaengt.
            Optional<Class<?>> fromDependency = loadFromDependencies(name);
            if (fromDependency.isPresent()) {
                return resolved(fromDependency.get(), resolve);
            }

            // 3. Eigenes JAR vor dem Parent (child-first).
            try {
                return resolved(findClass(name), resolve);
            } catch (ClassNotFoundException ignored) {
                // Nicht im eigenen JAR - dann der normale Weg nach oben.
                return resolved(super.loadClass(name, false), resolve);
            }
        }
    }

    /**
     * Ressourcen kommen zuerst aus dem eigenen JAR (child-first).
     *
     * <p>{@link URLClassLoader} fragt sonst den Parent zuerst - und der ist der Master, der
     * selbst {@code messages/de.yml} mitbringt. Ein Modul bekaeme dann die Texte des
     * Masters statt seiner eigenen, ohne dass irgendwo ein Fehler auftaucht.
     *
     * <p>Fuer Klassen gilt dasselbe schon in {@link #loadClass(String, boolean)}; die
     * geteilten Pakete sind hier kein Thema, weil Ressourcen keine Typidentitaet haben.
     */
    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    /** Eigene Treffer zuerst, danach die des Parents - ohne Doppelte. */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>(Collections.list(findResources(name)));
        for (URL fromParent : Collections.list(getParent().getResources(name))) {
            if (!all.contains(fromParent)) {
                all.add(fromParent);
            }
        }
        return Collections.enumeration(all);
    }

    private Optional<Class<?>> loadFromDependencies(String className) {
        String packageName = packageOf(className);
        if (packageName.isEmpty()) {
            return Optional.empty();
        }

        for (String dependency : descriptor.allDependencies()) {
            Optional<ModuleClassLoader> other = loaderLookup.apply(dependency);
            if (other.isEmpty() || !other.get().descriptor.exportsPackage(packageName)) {
                continue;
            }
            try {
                // findClass, nicht loadClass: Sonst wuerde der andere Loader seinerseits
                // bei seinen Abhaengigkeiten anfragen und die Suche koennte im Kreis laufen.
                return Optional.of(other.get().findClass(className));
            } catch (ClassNotFoundException exception) {
                LOG.debug("{} exportiert {}, hat aber {} nicht",
                        dependency, packageName, className);
            }
        }
        return Optional.empty();
    }

    private static boolean isShared(String className) {
        return SHARED_PACKAGES.stream().anyMatch(className::startsWith)
               || className.startsWith("java.")
               || className.startsWith("javax.");
    }

    private static String packageOf(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
    }

    private Class<?> resolved(Class<?> type, boolean resolve) {
        if (resolve) {
            resolveClass(type);
        }
        return type;
    }

    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    /** Macht {@code findClass} fuer andere Modul-Loader erreichbar. */
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return super.findClass(name);
    }
}
