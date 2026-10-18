package com.editora.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Discovers and loads plugins from {@code <configDir>/plugins/<id>/plugin.json}. <b>Shared</b> across all
 * windows (created once, owned by {@code ui.WindowManager}): plugin <em>classes</em> load once here, but a
 * plugin's {@link Plugin} instance + its tool-window content node are built <em>per window</em> via
 * {@code contributeTo} (a JavaFX Node can't live in two scenes).
 *
 * <p><b>The launch set.</b> The first {@link #discover()} — at launch, off the FX thread — is the only one
 * that builds class loaders, and what it found is frozen as {@link #launchSet()}: every window of this
 * session, the first and any opened later, applies exactly that set. Later {@code discover()} calls (Settings
 * → Reload, after an install or a removal) only rescan the manifests for the Settings list. So a plugin
 * installed, updated, enabled or disabled during a session changes nothing that runs until the next launch,
 * as the Settings page says — new windows used to read the live list and run a declined update, a plugin
 * the user had just switched off, or two versions of one plugin side by side.
 *
 * <p>For each enabled Java plugin in the launch set it builds a child {@link URLClassLoader} whose parent is
 * the app module's loader, so plugin code can use Editora's exported API ({@code com.editora.plugin} + the
 * JDK) and it works inside the sealed jlink image. Each plugin is loaded in its own {@code try/catch}: a
 * failure is recorded as {@link PluginDescriptor#loadError()} and never blocks others. A plugin's id must be
 * its folder's name, so one folder can never run under the consent given to another.
 */
public final class PluginManager {

    private static final Logger LOG = Logger.getLogger(PluginManager.class.getName());

    /** Whether the user enabled plugin {@code id} after seeing what it does ({@code digest}, see the store). */
    @FunctionalInterface
    public interface Consent {
        boolean allows(String id, String digest);
    }

    /** {@code --disable-plugins}: this process loads no plugin at all (a way out of a plugin that hangs). */
    private static volatile boolean disabledForSession;

    /** Called by the launcher for {@code --disable-plugins}, before anything is discovered. */
    public static void disableForSession() {
        disabledForSession = true;
    }

    /** Whether this launch was started with {@code --disable-plugins}. */
    public static boolean disabledForSession() {
        return disabledForSession;
    }

    private final Path pluginsDir;
    private final Consent consent;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private volatile List<PluginDescriptor> descriptors = List.of();
    /** What the first discover found: what every window of this session loads. Null until then. */
    private volatile List<PluginDescriptor> launchSet;

    /** Plugin id → the version of an update waiting for the next start ({@code .pending-<id>}). */
    private volatile Map<String, String> pendingUpdates = Map.of();
    /** Plugin ids whose removal waits for the next start ({@code .remove-<id>}). */
    private volatile Set<String> pendingRemovals = Set.of();

    /**
     * Every {@link URLClassLoader} this manager built (only the launch set's): open for the whole session —
     * a running plugin loads classes lazily — and closed by {@link #closeAll()} when the last window closes.
     */
    private final Set<URLClassLoader> live = ConcurrentHashMap.newKeySet();

    /** Plugin id → why it failed to start (or started slowly) in a window this session; see the Settings row. */
    private final Map<String, PluginDescriptor.LoadProblem> runtimeProblems = new ConcurrentHashMap<>();

    /** Plugin id → the chords its code bound with {@code PluginContext.bindKey}, so a keymap rebuild keeps them. */
    private final Map<String, Map<String, String>> codeBindings = new ConcurrentHashMap<>();

    /**
     * @param pluginsDir {@code <configDir>/plugins}
     * @param consent    whether a plugin is enabled with the capabilities it has now (the {@code plugins.json} store)
     */
    public PluginManager(Path pluginsDir, Consent consent) {
        this.pluginsDir = pluginsDir;
        this.consent = consent == null ? (id, digest) -> false : consent;
    }

    /** As above, with a consent that looks at the id alone — for tests and tools. */
    public PluginManager(Path pluginsDir, Predicate<String> isEnabled) {
        this(pluginsDir, isEnabled == null ? null : (Consent) (id, digest) -> isEnabled.test(id));
    }

    /**
     * Scans the plugins dir and parses each manifest. The first call — the launch — first applies the updates and
     * removals a previous session left waiting ({@link PluginInstaller#applyPending}), builds a class loader for
     * every enabled Java plugin, and freezes the result as {@link #launchSet()}. Synchronized: an install, a
     * Reload and a window's startup can ask at once.
     */
    public synchronized void discover() {
        boolean launch = launchSet == null;
        if (launch) {
            PluginInstaller.applyPending(pluginsDir);
        }
        List<PluginDescriptor> found = new ArrayList<>();
        Map<String, String> updates = new LinkedHashMap<>();
        Set<String> removals = new java.util.HashSet<>();
        if (Files.isDirectory(pluginsDir)) {
            List<Path> entries = new ArrayList<>();
            try (Stream<Path> s = Files.list(pluginsDir)) {
                s.sorted().forEach(entries::add);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to list plugins dir " + pluginsDir, e);
            }
            for (Path dir : entries) {
                String folder = dir.getFileName().toString();
                if (folder.startsWith(PluginInstaller.PENDING_PREFIX) && Files.isDirectory(dir)) {
                    updates.put(folder.substring(PluginInstaller.PENDING_PREFIX.length()), pendingVersion(dir));
                    continue;
                }
                if (folder.startsWith(PluginInstaller.REMOVE_PREFIX)) {
                    removals.add(folder.substring(PluginInstaller.REMOVE_PREFIX.length()));
                    continue;
                }
                if (!Files.isDirectory(dir) || folder.startsWith(".") || PluginInstaller.isReservedId(folder)) {
                    continue; // an install being staged, or a built-in tool folder (plugins/lsp, …): not a plugin
                }
                Path manifestFile = dir.resolve("plugin.json");
                if (!Files.isRegularFile(manifestFile)) {
                    continue; // not a plugin folder
                }
                found.add(loadDescriptor(dir, manifestFile, launch));
            }
        }
        descriptors = List.copyOf(found);
        pendingUpdates = Map.copyOf(updates);
        pendingRemovals = Set.copyOf(removals);
        if (launch) {
            launchSet = descriptors;
        }
    }

    /** The version a waiting update declares ("" when unknown). */
    private String pendingVersion(Path dir) {
        try (InputStream in = Files.newInputStream(dir.resolve("plugin.json"))) {
            PluginManifest m = parseManifest(mapper, in);
            return m.version == null ? "" : m.version.strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * The plugins this session runs: what the launch discover found, enabled or not, in folder order. Every
     * window applies this set (its enabled, error-free entries) until Editora restarts. Empty before the first
     * discover.
     */
    public List<PluginDescriptor> launchSet() {
        List<PluginDescriptor> set = launchSet;
        return set == null ? List.of() : set;
    }

    /** The launch-set descriptor of {@code id} that runs this session (enabled, loaded), or null. */
    public PluginDescriptor running(String id) {
        for (PluginDescriptor d : launchSet()) {
            if (d.id().equals(id) && d.enabled() && d.loadError() == null) {
                return d;
            }
        }
        return null;
    }

    /** The version of {@code id}'s update waiting for the next start, or null when there is none. */
    public String pendingUpdate(String id) {
        return pendingUpdates.get(id);
    }

    /** Whether {@code id} will be removed at the next start. */
    public boolean pendingRemoval(String id) {
        return pendingRemovals.contains(id);
    }

    /** Closes every class loader (last window closing / app shutdown). Idempotent. */
    public void closeAll() {
        for (URLClassLoader l : new ArrayList<>(live)) {
            live.remove(l);
            try {
                l.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to close plugin class loader " + l.getName(), e);
            }
        }
    }

    /** How many class loaders this manager currently holds open — for tests + lifecycle assertions. */
    int liveLoaderCount() {
        return live.size();
    }

    /** Records why {@code id} failed (or was slow) to start in a window: shown on its Settings row this session. */
    public void recordRuntimeProblem(String id, PluginDescriptor.LoadProblem problem) {
        if (id != null && problem != null) {
            runtimeProblems.put(id, problem);
        }
    }

    /** Why {@code id} failed or was slow to start this session, or null. */
    public PluginDescriptor.LoadProblem runtimeProblem(String id) {
        return id == null ? null : runtimeProblems.get(id);
    }

    /** Remembers that plugin {@code id}'s code bound {@code chord} to {@code commandId}, for keymap rebuilds. */
    public void recordCodeBinding(String id, String chord, String commandId) {
        codeBindings
                .computeIfAbsent(id, k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(chord, commandId);
    }

    /** The chords plugin {@code id}'s code bound this session (empty when none). */
    public Map<String, String> codeBindings(String id) {
        Map<String, String> m = codeBindings.get(id);
        if (m == null) {
            return Map.of();
        }
        synchronized (m) {
            return new LinkedHashMap<>(m);
        }
    }

    /** A parse error without Jackson's internals ("Source: REDACTED (`StreamReadFeature…`)"), with its line. */
    static String readableError(Exception e) {
        if (e instanceof com.fasterxml.jackson.core.JsonProcessingException j) {
            com.fasterxml.jackson.core.JsonLocation at = j.getLocation();
            return at == null || at.getLineNr() < 1
                    ? j.getOriginalMessage()
                    : j.getOriginalMessage() + " (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")";
        }
        return String.valueOf(e.getMessage());
    }

    /** What went wrong reading {@code plugin.json}, by kind (see {@link PluginDescriptor.LoadProblem}). Pure. */
    static PluginDescriptor.LoadProblem manifestProblem(Exception e) {
        if (e instanceof com.fasterxml.jackson.core.JsonProcessingException j) {
            com.fasterxml.jackson.core.JsonLocation at = j.getLocation();
            boolean placed = at != null && at.getLineNr() >= 1;
            return new PluginDescriptor.LoadProblem(
                    PluginDescriptor.LoadProblem.Kind.MANIFEST_INVALID,
                    List.of(
                            placed ? String.valueOf(at.getLineNr()) : "",
                            placed ? String.valueOf(at.getColumnNr()) : ""),
                    readableError(e));
        }
        return e instanceof IOException
                ? new PluginDescriptor.LoadProblem(
                        PluginDescriptor.LoadProblem.Kind.MANIFEST_UNREADABLE, List.of(), readableError(e))
                : new PluginDescriptor.LoadProblem(
                        PluginDescriptor.LoadProblem.Kind.MANIFEST_INVALID, List.of("", ""), readableError(e));
    }

    private PluginDescriptor loadDescriptor(Path dir, Path manifestFile, boolean buildLoaders) {
        String folder = dir.getFileName().toString();
        PluginManifest manifest;
        try (InputStream in = Files.newInputStream(manifestFile)) {
            manifest = parseManifest(mapper, in);
        } catch (IOException | RuntimeException e) {
            PluginManifest stub = new PluginManifest();
            stub.id = folder;
            stub.name = stub.id;
            return PluginDescriptor.withProblem(stub, dir, false, null, manifestProblem(e));
        }
        List<String> jars = jarNames(dir);
        if (manifest.id == null || manifest.id.isBlank()) {
            manifest.id = folder;
        } else if (!PluginInstaller.isSafeId(manifest.id.strip()) || PluginInstaller.isReservedId(manifest.id)) {
            // The id is used as a key and, historically, as a path under the plugins folder: "..", an absolute
            // path or a built-in folder name must never get that far. The folder is listed (so it can be seen
            // and removed) under its own name, and nothing from it is loaded.
            String declared = manifest.id;
            manifest.id = folder;
            if (manifest.name == null || manifest.name.isBlank()) {
                manifest.name = manifest.id;
            }
            return PluginDescriptor.withProblem(
                    manifest,
                    dir,
                    false,
                    null,
                    new PluginDescriptor.LoadProblem(
                            PluginDescriptor.LoadProblem.Kind.ID_NOT_ALLOWED, List.of(declared), ""),
                    jars);
        } else if (!manifest.id.strip().equals(folder)) {
            // A plugin is its folder: consent, Remove and the command namespace all go by the id, so a second
            // folder declaring an enabled plugin's id ran its code under that plugin's consent, and Remove on
            // one row deleted the other. Listed under the folder's name, nothing loaded.
            String declared = manifest.id.strip();
            manifest.id = folder;
            return PluginDescriptor.withProblem(
                    manifest,
                    dir,
                    false,
                    null,
                    new PluginDescriptor.LoadProblem(
                            PluginDescriptor.LoadProblem.Kind.ID_MISMATCH, List.of(declared, folder), ""),
                    jars);
        }
        manifest.id = manifest.id.strip();
        String digest = PluginDescriptor.digest(manifest, jars);
        boolean enabled = consent.allows(manifest.id, digest);
        ClassLoader loader = null;
        PluginDescriptor.LoadProblem error = null;
        if (buildLoaders && enabled && manifest.main != null && !manifest.main.isBlank()) {
            try {
                loader = buildClassLoader(dir);
            } catch (RuntimeException e) {
                error = new PluginDescriptor.LoadProblem(
                        PluginDescriptor.LoadProblem.Kind.CODE_NOT_LOADED, List.of(), String.valueOf(e.getMessage()));
                LOG.log(Level.WARNING, "Plugin " + manifest.id + " class loader failed", e);
            }
        }
        return PluginDescriptor.withProblem(manifest, dir, enabled, loader, error, jars);
    }

    /** Builds a child loader from the plugin's {@code *.jar} and {@code lib/*.jar}; parent = the app loader. */
    private URLClassLoader buildClassLoader(Path dir) {
        List<URL> urls = new ArrayList<>();
        for (String jar : jarNames(dir)) {
            try {
                urls.add(dir.resolve(jar).toUri().toURL());
            } catch (java.net.MalformedURLException e) {
                LOG.log(Level.WARNING, "Bad jar URL " + jar, e);
            }
        }
        URLClassLoader loader = new URLClassLoader(
                "plugin:" + dir.getFileName(),
                urls.toArray(new URL[0]),
                getClass().getClassLoader());
        live.add(loader); // closed by closeAll() when the last window closes
        return loader;
    }

    /** The jars a plugin's loader reads: {@code *.jar} in its folder, then {@code lib/*.jar}, each sorted. */
    static List<String> jarNames(Path dir) {
        List<String> out = new ArrayList<>();
        collectJars(dir, "", out);
        collectJars(dir.resolve("lib"), "lib/", out);
        return out;
    }

    private static void collectJars(Path dir, String prefix, List<String> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .sorted()
                    .forEach(n -> out.add(prefix + n));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to list jars in " + dir, e);
        }
    }

    /** Parses a {@code plugin.json} stream into a manifest. Pure (no I/O beyond the stream) — unit-tested. */
    static PluginManifest parseManifest(ObjectMapper mapper, InputStream in) throws IOException {
        PluginManifest m = mapper.readValue(in, PluginManifest.class);
        if (m.keymap == null) {
            m.keymap = new LinkedHashMap<>();
        }
        if (m.keymaps == null) {
            m.keymaps = new LinkedHashMap<>();
        }
        m.keymaps.values().removeIf(java.util.Objects::isNull);
        if (m.commands == null) {
            m.commands = new ArrayList<>();
        }
        m.commands.removeIf(java.util.Objects::isNull);
        return m;
    }

    /** All discovered plugins (enabled + disabled + failed), in folder order — the latest scan, for Settings. */
    public List<PluginDescriptor> descriptors() {
        return descriptors;
    }

    /** The plugins root directory ({@code <configDir>/plugins}) — the install target for new plugins. */
    public Path pluginsDir() {
        return pluginsDir;
    }

    /**
     * Instantiates a Java plugin's {@link Plugin} from its class loader (no-arg constructor), under the
     * plugin's thread-context loader (so libraries that use it work). Returns null on any failure, which is
     * logged and recorded ({@link #runtimeProblem}), so one bad plugin never blocks the rest and the Settings row
     * says why it does nothing. The caller invokes {@link Plugin#start} once per window.
     */
    public Plugin instantiate(PluginDescriptor d) {
        if (d == null || d.classLoader() == null || !d.hasJavaEntry()) {
            return null;
        }
        String main = d.manifest().main.strip();
        Thread thread = Thread.currentThread();
        ClassLoader prev = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(d.classLoader());
            Class<?> c = Class.forName(main, true, d.classLoader());
            Object o = c.getDeclaredConstructor().newInstance();
            if (o instanceof Plugin p) {
                return p;
            }
            LOG.warning("Plugin " + d.id() + " main class " + main + " is not a Plugin");
            recordRuntimeProblem(
                    d.id(),
                    new PluginDescriptor.LoadProblem(
                            PluginDescriptor.LoadProblem.Kind.MAIN_NOT_A_PLUGIN, List.of(main), c.getName()));
            return null;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            LOG.log(Level.WARNING, "Plugin " + d.id() + " main class " + main + " not found", e);
            recordRuntimeProblem(
                    d.id(),
                    new PluginDescriptor.LoadProblem(
                            PluginDescriptor.LoadProblem.Kind.MAIN_NOT_FOUND, List.of(main), String.valueOf(e)));
            return null;
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "Failed to instantiate plugin " + d.id() + " (" + main + ")", e);
            recordRuntimeProblem(
                    d.id(),
                    new PluginDescriptor.LoadProblem(
                            PluginDescriptor.LoadProblem.Kind.MAIN_NOT_A_PLUGIN, List.of(main), String.valueOf(e)));
            return null;
        } finally {
            thread.setContextClassLoader(prev);
        }
    }
}
