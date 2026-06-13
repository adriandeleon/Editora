package com.editora.plugin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Discovers and loads plugins from {@code <configDir>/plugins/<id>/plugin.json}. <b>Shared</b> across all
 * windows (created once, owned by {@code ui.WindowManager}): plugin <em>classes</em> load once here, but a
 * plugin's {@link Plugin} instance + its tool-window content node are built <em>per window</em> via
 * {@code contributeTo} (a JavaFX Node can't live in two scenes).
 *
 * <p>{@link #discover()} runs off the FX thread. For each enabled Java plugin it builds a child
 * {@link URLClassLoader} whose parent is the app module's loader, so plugin code can use Editora's exported
 * API ({@code com.editora.plugin}/{@code command}/{@code ui}/{@code editor}/{@code config}) + the JDK — and
 * it works inside the sealed jlink image (no runtime module path needed). Each plugin is loaded in its own
 * {@code try/catch}: a failure is recorded as {@link PluginDescriptor#loadError()} and never blocks others.
 */
public final class PluginManager {

    private static final Logger LOG = Logger.getLogger(PluginManager.class.getName());

    private final Path pluginsDir;
    private final Predicate<String> isEnabled;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private volatile List<PluginDescriptor> descriptors = List.of();

    /**
     * @param pluginsDir {@code <configDir>/plugins}
     * @param isEnabled  whether a plugin id is enabled (from the {@code plugins.json} store)
     */
    public PluginManager(Path pluginsDir, Predicate<String> isEnabled) {
        this.pluginsDir = pluginsDir;
        this.isEnabled = isEnabled == null ? id -> false : isEnabled;
    }

    /** Scans the plugins dir, parses each manifest, and builds a class loader for every enabled Java plugin. */
    public void discover() {
        List<PluginDescriptor> found = new ArrayList<>();
        if (Files.isDirectory(pluginsDir)) {
            List<Path> dirs = new ArrayList<>();
            try (Stream<Path> s = Files.list(pluginsDir)) {
                s.filter(Files::isDirectory).sorted().forEach(dirs::add);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to list plugins dir " + pluginsDir, e);
            }
            for (Path dir : dirs) {
                Path manifestFile = dir.resolve("plugin.json");
                if (!Files.isRegularFile(manifestFile)) {
                    continue; // not a plugin folder
                }
                found.add(loadDescriptor(dir, manifestFile));
            }
        }
        descriptors = List.copyOf(found);
    }

    private PluginDescriptor loadDescriptor(Path dir, Path manifestFile) {
        PluginManifest manifest;
        try (InputStream in = Files.newInputStream(manifestFile)) {
            manifest = parseManifest(mapper, in);
        } catch (IOException | RuntimeException e) {
            PluginManifest stub = new PluginManifest();
            stub.id = dir.getFileName().toString();
            stub.name = stub.id;
            return new PluginDescriptor(stub, dir, false, null, "Invalid plugin.json: " + e.getMessage());
        }
        if (manifest.id == null || manifest.id.isBlank()) {
            manifest.id = dir.getFileName().toString();
        }
        boolean enabled = isEnabled.test(manifest.id);
        ClassLoader loader = null;
        String error = null;
        if (enabled && manifest.main != null && !manifest.main.isBlank()) {
            try {
                loader = buildClassLoader(dir);
            } catch (RuntimeException e) {
                error = "Failed to build class loader: " + e.getMessage();
                LOG.log(Level.WARNING, "Plugin " + manifest.id + " class loader failed", e);
            }
        }
        return new PluginDescriptor(manifest, dir, enabled, loader, error);
    }

    /** Builds a child loader from the plugin's {@code *.jar} and {@code lib/*.jar}; parent = the app loader. */
    private ClassLoader buildClassLoader(Path dir) {
        List<URL> urls = new ArrayList<>();
        collectJars(dir, urls);
        collectJars(dir.resolve("lib"), urls);
        return new URLClassLoader(
                "plugin:" + dir.getFileName(),
                urls.toArray(new URL[0]),
                getClass().getClassLoader());
    }

    private static void collectJars(Path dir, List<URL> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(p -> {
                try {
                    out.add(p.toUri().toURL());
                } catch (java.net.MalformedURLException e) {
                    LOG.log(Level.WARNING, "Bad jar URL " + p, e);
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to list jars in " + dir, e);
        }
    }

    /** Parses a {@code plugin.json} stream into a manifest. Pure (no I/O beyond the stream) — unit-tested. */
    static PluginManifest parseManifest(ObjectMapper mapper, InputStream in) throws IOException {
        PluginManifest m = mapper.readValue(in, PluginManifest.class);
        if (m.keymap == null) {
            m.keymap = new java.util.LinkedHashMap<>();
        }
        if (m.commands == null) {
            m.commands = new ArrayList<>();
        }
        return m;
    }

    /** All discovered plugins (enabled + disabled + failed), in folder order. */
    public List<PluginDescriptor> descriptors() {
        return descriptors;
    }

    /** The plugins root directory ({@code <configDir>/plugins}) — the install target for new plugins. */
    public Path pluginsDir() {
        return pluginsDir;
    }

    /**
     * Instantiates a Java plugin's {@link Plugin} from its class loader (no-arg constructor), under the
     * plugin's thread-context loader (so libraries that use it work). Returns null on any failure (logged),
     * so one bad plugin never blocks the rest. The caller invokes {@link Plugin#start} once per window.
     */
    public Plugin instantiate(PluginDescriptor d) {
        if (d == null || d.classLoader() == null || !d.hasJavaEntry()) {
            return null;
        }
        Thread thread = Thread.currentThread();
        ClassLoader prev = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(d.classLoader());
            Class<?> c = Class.forName(d.manifest().main, true, d.classLoader());
            Object o = c.getDeclaredConstructor().newInstance();
            if (o instanceof Plugin p) {
                return p;
            }
            LOG.warning("Plugin " + d.id() + " main class " + d.manifest().main + " is not a Plugin");
            return null;
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "Failed to instantiate plugin " + d.id() + " (" + d.manifest().main + ")", e);
            return null;
        } finally {
            thread.setContextClassLoader(prev);
        }
    }
}
