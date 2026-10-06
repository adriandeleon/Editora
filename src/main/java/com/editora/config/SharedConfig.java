package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryRetention;
import com.editora.history.HistoryService;
import com.editora.io.DocumentWriteSequencer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * The <em>shared</em>, app-wide half of the configuration: preferences ({@link Settings}) plus the
 * cross-project stores (bookmarks, notes, breakpoints, SFTP connections, the user spell dictionary, the
 * recent-files / search / agent-session histories) and the {@link ProjectManager} index. A single instance
 * is created once at startup and shared <strong>by reference</strong> across every open window's
 * {@link ConfigManager}, so a save from any window can never clobber another window's in-memory copy. Per-window <em>session</em> state
 * ({@link WorkspaceState} + its file) lives in {@link ConfigManager}, not here.
 *
 * <p>The bucketed stores ({@code bookmarks.json}, {@code notes.json}, {@code breakpoints.json}) are
 * keyed by a project key ({@code ""} = the no-project/global session, else the project id); the key is
 * supplied by the caller (a {@link ConfigManager} derives it from its session file).
 */
public class SharedConfig {

    /**
     * Legacy TOML reader used only for the one-time {@code settings.toml} migration — so it is built only
     * when that file is actually there to read, not on every launch.
     */
    private TomlMapper legacyToml;
    /** Pretty JSON for preferences and the bucketed stores. */
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configDir;
    /** Whether this instance was started in dev mode ({@code --dev}); surfaced in the UI (toolbar badge). */
    private final boolean dev;
    /** Performs settings/session writes off the FX thread (async for the frequent path, flush for durable). */
    private final ConfigWriter writer = new ConfigWriter();

    private final HistoryService historyService;
    /** This process's claim on the config dir ({@code null} until {@link #claimInstance()}). */
    private volatile InstanceLock instanceLock;

    private final DocumentWriteSequencer documentWrites = new DocumentWriteSequencer();
    /** Durable and pending history-index references. GC may delete only outside their union. */
    private final Object historyPublicationLock = new Object();

    private long nextHistoryPublication;
    private long durableHistoryPublication;
    private Set<String> durableHistoryHashes = Set.of();
    private Set<String> currentHistoryHashes = Set.of();
    private final Map<Long, Set<String>> pendingHistoryHashes = new LinkedHashMap<>();
    private final Map<Long, Consumer<Boolean>> historyPublicationWaiters = new LinkedHashMap<>();

    private Settings settings = new Settings();
    /** Global bookmarks (all files/projects), stored in {@code bookmarks.json} — see {@link BookmarkStore}. */
    private BookmarkStore bookmarkStore = new BookmarkStore();
    /** Personal Notes (all files/projects), stored in {@code notes.json} — see {@link NoteStore}. */
    private NoteStore noteStore = new NoteStore();
    /** Breakpoints (all files/projects), stored in {@code breakpoints.json} — see {@link BreakpointStore}. */
    private BreakpointStore breakpointStore = new BreakpointStore();
    /** Local File History index (all files/projects), in {@code history/index.json} — see {@link HistoryStore}. */
    private HistoryStore historyStore = new HistoryStore();
    /** Saved SFTP connections (metadata only, no secrets), stored in {@code connections.json}. */
    private ConnectionStore connectionStore = new ConnectionStore();
    /** Plugin enable-state (id → enabled), stored in {@code plugins.json} — see {@link PluginStore}. */
    private PluginStore pluginStore = new PluginStore();
    /** Trusted workspace roots, stored in {@code trusted-folders.json} — see {@link TrustStore}. */
    private TrustStore trustStore = new TrustStore();
    /** Saved keyboard macros (app-global, not per-project), stored in {@code macros.json} — see {@link MacroStore}. */
    private MacroStore macroStore = new MacroStore();
    /** User abbreviation dictionary (app-global), stored in {@code abbreviations.json} — see {@link AbbrevStore}. */
    private AbbrevStore abbrevStore = new AbbrevStore();
    /** User-added spell-check words (one per line in {@code dictionary.txt}); lower-cased, shared globally. */
    private final java.util.Set<String> userDictionary = new java.util.LinkedHashSet<>();
    /** The shared projects index ({@code projects.json}) — one source of truth across all windows. */
    private final ProjectManager projects;

    /** The histories every window adds to; created on first use (see {@link #recentFiles()}). */
    private RecentFiles recentFiles;

    private SearchHistory searchHistory;
    private AgentSessionHistory agentSessions;

    /**
     * Whether the Local History index in memory is the one that was written — false when it failed to load, or
     * while a backup of one that failed is still on disk. Blob GC is refused when false (see
     * {@link #mayCollectHistoryBlobs}); read on the history worker.
     */
    private volatile boolean historyIndexIntact = true;

    /** What {@link #load()} could not read as written, held until a window shows it (see {@link #takeLoadProblems}). */
    private final List<ConfigLoadProblem> loadProblems = new ArrayList<>();
    /**
     * Files that must not be written this session: each holds content this build did not load (it is newer
     * than this build, or unreadable) and could not be copied aside, so saving would destroy the only copy.
     */
    private final Set<Path> writeProtected = ConcurrentHashMap.newKeySet();

    /** Bookkeeping fields: they change without any window having something to re-apply. */
    private static final List<String> SETTINGS_BOOKKEEPING = List.of("lastUpdateCheckEpoch", "dismissedUpdateVersion");
    /** The serialized preferences every window last applied, or {@code null} until {@link #markSettingsApplied}. */
    private JsonNode appliedSettings;

    private byte[] appliedSettingsBytes;
    /** The export in progress, if any (see {@link #exportConfigAsync}). */
    private java.util.concurrent.CompletableFuture<Path> runningExport;

    private Consumer<ConfigManager> onSettingsChanged = origin -> {};
    private volatile Runnable onStoreChanged = () -> {};

    public SharedConfig(Path configDir, boolean dev) {
        this.configDir = configDir;
        this.dev = dev;
        this.projects = new ProjectManager(configDir);
        this.projects.setOnWriteError(writer::reportWriteError);
        this.projects.loadProblems().forEach(this::onLoadProblem);
        this.historyService =
                new HistoryService(new HistoryBlobStore(getHistoryBlobsDir()), this::mayCollectHistoryBlobs);
    }

    // --- more than one process on this config dir ---

    /**
     * Claims the config dir for this process and reports whether it is the <em>primary</em> instance — the
     * first Editora process using this directory. Call once at startup, before any window is built;
     * idempotent. The claim is an OS file lock (see {@link InstanceLock}) held until {@link #shutdown()} or
     * process exit, so a crashed primary never leaves a stale claim behind.
     *
     * <p>A second process on the same directory is an ordinary state (a launch that is not forwarded to the
     * running editor), but the stores here are written whole from each process's memory. So a secondary is
     * told apart, warns the user once, and never garbage-collects shared data.
     */
    public synchronized boolean claimInstance() {
        if (instanceLock == null) {
            instanceLock = InstanceLock.claim(configDir);
        }
        return instanceLock.primary();
    }

    /**
     * Whether this process is the primary instance on its config dir: the single source of truth for both the
     * "another instance is using this configuration" warning and the local-history GC gate. A config that was
     * never {@linkplain #claimInstance() claimed} (a test, an embedding tool) is its own sole user.
     */
    public boolean isPrimaryInstance() {
        InstanceLock lock = instanceLock;
        return lock == null || lock.primary();
    }

    /**
     * Whether local-history blobs may be deleted now: only by the primary, and only while no other process is
     * using this config dir.
     *
     * <p>Blob GC deletes every blob outside <em>this</em> process's index. Another process's revisions are
     * not in that index, so each history save here used to delete the other editor's revision bodies — its
     * History view then listed revisions that opened empty. A secondary therefore never collects, and the
     * primary skips collection while a secondary is alive (its unreferenced blobs are picked up by the first
     * collection after it exits). Evaluated on the history worker immediately before deleting.
     *
     * <p>Nor while the index is not the one that was written ({@link HistoryIndexGuard}): the in-memory index
     * is then missing revisions whose bodies are still on disk, and collecting against it would delete them
     * all — leaving the kept {@code index.json…bak} pointing at nothing.
     */
    boolean mayCollectHistoryBlobs() {
        if (!historyIndexIntact) {
            return false;
        }
        InstanceLock lock = instanceLock;
        return lock == null || (lock.primary() && !lock.othersPresent());
    }

    /** True when started in dev mode ({@code --dev}); the UI shows a "dev mode" badge in this case. */
    public boolean isDev() {
        return dev;
    }

    public Path getConfigDir() {
        return configDir;
    }

    /** The shared projects index, one instance for the whole app. */
    public ProjectManager projects() {
        return projects;
    }

    /** The app-wide local-history worker. Sharing it coordinates blob publication and GC across windows. */
    public HistoryService historyService() {
        return historyService;
    }

    /** Coordinates document writes across every window that shares this configuration. */
    public DocumentWriteSequencer documentWrites() {
        return documentWrites;
    }

    /** The JSON mapper used for the bucketed stores (reused by {@link ConfigManager} for session state). */
    ObjectMapper json() {
        return json;
    }

    /** Reads all shared config files, merging stored values onto defaults. Falls back to defaults on error. */
    public void load() {
        loadProblems.clear();
        writeProtected.clear();
        // The projects index is read once, when this object is built — before load(), which would otherwise
        // discard what it reported.
        projects.loadProblems().forEach(this::onLoadProblem);
        appliedSettings = null; // a reload replaces the Settings object; the next window pair re-baselines
        settings = loadSettings();
        loadBookmarks();
        loadBreakpoints();
        loadHistory();
        loadNotes();
        loadConnections();
        loadPlugins();
        loadTrust();
        loadMacros();
        loadAbbreviations();
        loadUserDictionary();
    }

    /**
     * Loads JSON preferences, converting the legacy TOML file once when JSON does not yet exist.
     *
     * <p>The legacy file is removed only after the complete JSON replacement has been written atomically.
     * If the write fails, this launch still uses the TOML values and the next launch can retry; an existing
     * JSON file always wins so a stale legacy copy can never roll settings back.
     */
    private Settings loadSettings() {
        Path file = getSettingsFile();
        Path legacy = getLegacySettingsFile();
        if (!Files.exists(file) && Files.isReadable(legacy)) {
            if (legacyToml == null) {
                legacyToml = new TomlMapper();
            }
            Settings migrated = ConfigMigrations.readVersioned(
                    legacy, legacyToml, new Settings(), ConfigSchema.SETTINGS, this::onLoadProblem);
            if (writeProtected.contains(legacy)) {
                return migrated; // unread and not backed up: converting would delete the only copy
            }
            try {
                ConfigWriter.writeAtomic(file, json, migrated);
            } catch (IOException ignored) {
                // Keep using the values read from TOML. Leaving it in place makes migration retryable.
                return migrated;
            }
            try {
                Files.deleteIfExists(legacy);
            } catch (IOException ignored) {
                // JSON now wins on every later launch; a stale legacy copy is harmless.
            }
            return migrated;
        }
        return read(file, new Settings(), ConfigSchema.SETTINGS);
    }

    /** {@link ConfigMigrations#readVersioned} with this instance's mapper, collecting what could not be read. */
    private <T> T read(Path file, T defaults, ConfigSchema schema) {
        return ConfigMigrations.readVersioned(file, json, defaults, schema, this::onLoadProblem);
    }

    /** Records {@code problem} for {@link #takeLoadProblems} and write-protects its file when it must be. */
    void onLoadProblem(ConfigLoadProblem problem) {
        if (!loadProblems.contains(problem)) { // a session file is read at bootstrap and again by its window
            loadProblems.add(problem);
        }
        if (problem.mustNotOverwrite()) {
            writeProtected.add(problem.file());
        }
    }

    /**
     * The load problems not yet shown to the user, handed over once: values that kept their default, files
     * that fell back to defaults, and files that will not be saved this session ({@link #isWriteProtected}).
     */
    public List<ConfigLoadProblem> takeLoadProblems() {
        List<ConfigLoadProblem> taken = List.copyOf(loadProblems);
        loadProblems.clear();
        return taken;
    }

    /** True when {@code file} is not written this session because its unread content has no backup. */
    public boolean isWriteProtected(Path file) {
        return writeProtected.contains(file);
    }

    public Settings getSettings() {
        return settings;
    }

    /** Writes preferences to {@code settings.json} synchronously (serialize now, then block until written). */
    public boolean saveSettings() {
        enqueueSettings();
        return writer.flush();
    }

    /** The shared off-thread writer (settings + session state route through it; see {@link ConfigWriter}). */
    /** Drops any queued write for {@code file} (it's about to be deleted — see {@link ConfigWriter#cancel}). */
    public void cancelPendingWrite(Path file) {
        writer.cancel(file);
    }

    ConfigWriter writer() {
        return writer;
    }

    /** Serializes the current preferences and queues a write to {@code settings.json} (non-blocking). */
    void enqueueSettings() {
        enqueueSettings(null);
    }

    /**
     * As {@link #enqueueSettings()}, naming the window whose save this is. When the preferences differ from
     * what every window last applied, {@link #setOnSettingsChanged} is told, so a change made through one
     * window's palette command or key binding reaches the others without each command having to remember to
     * broadcast it.
     */
    void enqueueSettings(ConfigManager origin) {
        byte[] bytes = settingsBytes();
        if (!writeProtected.contains(getSettingsFile())) {
            writer.enqueue(getSettingsFile(), bytes);
        }
        if (appliedSettings == null || Arrays.equals(bytes, appliedSettingsBytes)) {
            return;
        }
        JsonNode now = applicableSettings(bytes);
        boolean changed = !now.equals(appliedSettings);
        appliedSettings = now;
        appliedSettingsBytes = bytes;
        if (changed) {
            onSettingsChanged.accept(origin);
        }
    }

    /**
     * Records that every window now reflects the current preferences. Starts change detection: until the
     * first call nothing is reported, which is right while a single window exists.
     */
    public void markSettingsApplied() {
        appliedSettingsBytes = settingsBytes();
        appliedSettings = applicableSettings(appliedSettingsBytes);
    }

    /**
     * Sets the handler told (on the saving thread, the FX thread) that a save carried preferences no other
     * window has applied yet. Its argument is the saving window's config, or {@code null} when unknown.
     */
    public void setOnSettingsChanged(Consumer<ConfigManager> handler) {
        this.onSettingsChanged = handler == null ? origin -> {} : handler;
    }

    /** The preferences as a tree, without the fields that never need a window to re-apply anything. */
    private JsonNode applicableSettings(byte[] bytes) {
        try {
            JsonNode tree = json.readTree(bytes);
            if (tree instanceof ObjectNode object) {
                object.remove(SETTINGS_BOOKKEEPING);
            }
            return tree;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to re-read serialized settings", e);
        }
    }

    private byte[] settingsBytes() {
        try {
            return json.writeValueAsBytes(settings);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize settings", e);
        }
    }

    /** Blocks until all queued settings/session writes have landed (a durable save, an export, or exit). */
    public boolean flushWrites() {
        return writer.flush();
    }

    /** Stops app-wide background services after the last window has closed. */
    public boolean shutdown() {
        boolean durable = writer.shutdown();
        historyService.shutdown();
        InstanceLock lock = instanceLock;
        if (lock != null) {
            lock.close(); // after the last write: the next launch may now be the primary
        }
        return durable;
    }

    /** Routes config-file write failures to {@code handler} (on the writer thread) so they can be surfaced
     *  instead of silently swallowed (#418). See {@link ConfigWriter#setOnWriteError}. */
    public void setOnWriteError(java.util.function.BiConsumer<Path, java.io.IOException> handler) {
        writer.setOnWriteError(handler);
    }

    /**
     * Zips the active config directory into a timestamped {@code .zip} in the user's home directory
     * and returns the created file. Backs up whichever config dir is in use.
     */
    public Path exportConfig() throws IOException {
        return exportConfig(Path.of(System.getProperty("user.home")));
    }

    Path exportConfig(Path destinationDir) throws IOException {
        if (!writer.flush()) {
            throw new IOException("Timed out waiting for pending configuration writes");
        }
        return ConfigExporter.export(
                configDir,
                destinationDir,
                com.editora.AppInfo.VERSION,
                System.getProperty("user.name"),
                java.time.LocalDateTime.now());
    }

    /**
     * {@link #exportConfig()} on a background thread: the export waits for pending writes and then reads and
     * compresses the whole config directory, which must not happen on the FX thread. The future completes on
     * that thread — with the zip, or exceptionally with the {@link IOException} — so a caller marshals back
     * itself. A call made while an export is running joins it rather than starting a second one (both would
     * be named for the same second).
     */
    public synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync() {
        return exportConfigAsync(Path.of(System.getProperty("user.home")));
    }

    synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync(Path destinationDir) {
        if (runningExport != null && !runningExport.isDone()) {
            return runningExport;
        }
        java.util.concurrent.CompletableFuture<Path> export = new java.util.concurrent.CompletableFuture<>();
        runningExport = export;
        Thread thread = new Thread(
                () -> {
                    try {
                        export.complete(exportConfig(destinationDir));
                    } catch (IOException | RuntimeException e) {
                        export.completeExceptionally(e);
                    }
                },
                "config-export");
        thread.setDaemon(true);
        thread.start();
        return export;
    }

    // --- file locations ---

    public Path getSettingsFile() {
        return configDir.resolve(ConfigManager.SETTINGS_FILE_NAME);
    }

    Path getLegacySettingsFile() {
        return configDir.resolve(ConfigManager.LEGACY_SETTINGS_FILE_NAME);
    }

    public Path getBookmarksFile() {
        return configDir.resolve(ConfigManager.BOOKMARKS_FILE_NAME);
    }

    public Path getNotesFile() {
        return configDir.resolve(ConfigManager.NOTES_FILE_NAME);
    }

    public Path getBreakpointsFile() {
        return configDir.resolve(ConfigManager.BREAKPOINTS_FILE_NAME);
    }

    /** The Local File History index file ({@code history/index.json}). */
    public Path getHistoryFile() {
        return configDir.resolve(ConfigManager.HISTORY_DIR_NAME).resolve(ConfigManager.HISTORY_INDEX_NAME);
    }

    /** The directory holding gzip'd revision bodies ({@code history/blobs/}). */
    public Path getHistoryBlobsDir() {
        return configDir.resolve(ConfigManager.HISTORY_DIR_NAME).resolve(ConfigManager.HISTORY_BLOBS_NAME);
    }

    public Path getConnectionsFile() {
        return configDir.resolve(ConfigManager.CONNECTIONS_FILE_NAME);
    }

    public Path getUserDictionaryFile() {
        return configDir.resolve(ConfigManager.DICTIONARY_FILE_NAME);
    }

    public Path getPluginsFile() {
        return configDir.resolve(ConfigManager.PLUGINS_FILE_NAME);
    }

    public Path getMacrosFile() {
        return configDir.resolve(ConfigManager.MACROS_FILE_NAME);
    }

    public Path getAbbreviationsFile() {
        return configDir.resolve(ConfigManager.ABBREVIATIONS_FILE_NAME);
    }

    /** The plugin install root: {@code <configDir>/plugins} (each plugin lives in its own subdirectory). */
    public Path getPluginsDir() {
        return configDir.resolve(ConfigManager.PLUGINS_DIR_NAME);
    }

    /**
     * Writes one store synchronously and atomically. A file that is {@link #isWriteProtected write-protected}
     * is left alone: the in-memory store is the defaults loaded in its place, not its content.
     *
     * <p>Never throws. These writes run inside FX event handlers (toggle a bookmark or breakpoint, edit a
     * note, save a macro, trust a folder) and, for the one-time {@code bookmarks.json} creation, inside
     * {@link #load()}: an exception from a full disk or a read-only config dir used to abort the handler with
     * nothing shown, or stop the app from starting. A failure is logged and reported through
     * {@link #setOnWriteError} like a queued write; the change stays in memory.
     *
     * @return whether the store in memory is now the one on disk
     */
    private boolean writeStore(Path file, Object store) {
        if (writeProtected.contains(file)) {
            return false;
        }
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(file, json, store);
            return true;
        } catch (IOException e) {
            writer.reportWriteError(file, e);
            return false;
        }
    }

    // --- recent files, search history, agent sessions (one instance each, shared by every window) ---

    /**
     * The recent-files list. One instance for the whole app: each window used to load its own copy and
     * rewrite the whole file on every change, so the last window to write discarded the others' entries.
     */
    public RecentFiles recentFiles() {
        if (recentFiles == null) {
            recentFiles = new RecentFiles(configDir, this::enqueueHistoryFile, this::onLoadProblem);
        }
        return recentFiles;
    }

    /** The Find-in-Files query history, shared like {@link #recentFiles()}. */
    public SearchHistory searchHistory() {
        if (searchHistory == null) {
            searchHistory = new SearchHistory(configDir, this::enqueueHistoryFile, this::onLoadProblem);
        }
        return searchHistory;
    }

    /** The AI Agent chat-session history, shared like {@link #recentFiles()}. */
    public AgentSessionHistory agentSessions() {
        if (agentSessions == null) {
            agentSessions = new AgentSessionHistory(configDir, this::enqueueHistoryFile, this::onLoadProblem);
        }
        return agentSessions;
    }

    /** Queues a history file on the shared writer: off the FX thread, coalesced, and failures are surfaced. */
    private void enqueueHistoryFile(Path file, ConfigWriter.BytesSupplier bytes) {
        if (!writeProtected.contains(file)) {
            writer.enqueue(file, bytes);
        }
    }

    // --- plugins (enable-state) ---

    public PluginStore getPluginStore() {
        return pluginStore;
    }

    private void loadPlugins() {
        if (Files.exists(getPluginsFile())) {
            pluginStore = read(getPluginsFile(), new PluginStore(), ConfigSchema.PLUGINS);
        } else {
            pluginStore = new PluginStore();
        }
    }

    public void savePlugins() {
        writeStore(getPluginsFile(), pluginStore);
    }

    // --- workspace trust (folders allowed to run their own build wrapper) ---

    public Path getTrustFile() {
        return configDir.resolve(ConfigManager.TRUST_FILE_NAME);
    }

    public TrustStore getTrustStore() {
        return trustStore;
    }

    private void loadTrust() {
        if (Files.exists(getTrustFile())) {
            trustStore = read(getTrustFile(), new TrustStore(), ConfigSchema.TRUST);
        } else {
            trustStore = new TrustStore();
        }
    }

    public void saveTrust() {
        writeStore(getTrustFile(), trustStore);
    }

    // --- keyboard macros (app-global) ---

    public MacroStore getMacroStore() {
        return macroStore;
    }

    private void loadMacros() {
        if (Files.exists(getMacrosFile())) {
            macroStore = read(getMacrosFile(), new MacroStore(), ConfigSchema.MACROS);
        } else {
            macroStore = new MacroStore();
        }
    }

    public void saveMacros() {
        writeStore(getMacrosFile(), macroStore);
    }

    // --- abbreviations (app-global) ---

    public java.util.List<Abbreviation> getAbbreviations() {
        return abbrevStore.abbreviations == null ? java.util.List.of() : abbrevStore.abbreviations;
    }

    public void setAbbreviations(java.util.List<Abbreviation> abbreviations) {
        abbrevStore.abbreviations = abbreviations == null ? new java.util.ArrayList<>() : abbreviations;
    }

    /** The abbreviations as a lower-cased-key → expansion map, for {@link com.editora.editops.Abbrev}. */
    public java.util.Map<String, String> abbreviationMap() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (Abbreviation a : getAbbreviations()) {
            if (a.getAbbreviation() != null && !a.getAbbreviation().isBlank()) {
                m.put(a.getAbbreviation().toLowerCase(java.util.Locale.ROOT), a.getExpansion());
            }
        }
        return m;
    }

    private void loadAbbreviations() {
        if (Files.exists(getAbbreviationsFile())) {
            abbrevStore = read(getAbbreviationsFile(), new AbbrevStore(), ConfigSchema.ABBREVIATIONS);
        } else {
            abbrevStore = new AbbrevStore();
        }
    }

    public void saveAbbreviations() {
        writeStore(getAbbreviationsFile(), abbrevStore);
        onStoreChanged.run();
    }

    // --- SFTP connections ---

    public List<com.editora.vfs.RemoteConnection> getConnections() {
        return connectionStore.connections;
    }

    public void putConnection(com.editora.vfs.RemoteConnection conn) {
        connectionStore.put(conn);
        saveConnections();
    }

    public void removeConnection(String id) {
        connectionStore.remove(id);
        saveConnections();
    }

    /** Replaces the whole saved-connection list (preserving order) and persists — backs the Settings editor. */
    public void setConnections(List<com.editora.vfs.RemoteConnection> conns) {
        connectionStore.connections = new java.util.ArrayList<>(conns == null ? List.of() : conns);
        saveConnections();
    }

    public void saveConnections() {
        writeStore(getConnectionsFile(), connectionStore);
        onStoreChanged.run();
    }

    /**
     * Sets what runs (on the saving thread) after the abbreviations or the saved SFTP sites were changed and
     * saved. Both are edited as whole lists by every window's Settings page, which therefore has to re-read
     * them when a command, a finished Connect or another window changes one — a focus change is not a reliable
     * moment for that, since the change can land while Settings already has focus.
     */
    public void setOnStoreChanged(Runnable handler) {
        this.onStoreChanged = handler == null ? () -> {} : handler;
    }

    // --- bucketed stores (keyed by project key) ---

    /** Every project's note buckets ({@code projectKey → (path → notes)}), for the cross-project panel view. */
    public Map<String, Map<String, List<PersonalNote>>> allNotes() {
        return noteStore.getByProject();
    }

    /** Every project's bookmark buckets ({@code projectKey → (path → bookmarks)}), for the cross-project view. */
    public Map<String, Map<String, List<Bookmark>>> allBookmarks() {
        return bookmarkStore.getByProject();
    }

    public Map<String, List<PersonalNote>> notesBucket(String key) {
        return noteStore.bucket(key);
    }

    public void deleteNotesForProject(String projectKey) {
        if (noteStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveNotes();
        }
    }

    public Map<String, List<Bookmark>> bookmarksBucket(String key) {
        return bookmarkStore.bucket(key);
    }

    public void deleteBookmarksForProject(String projectKey) {
        if (bookmarkStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveBookmarks();
        }
    }

    public Map<String, List<Breakpoint>> breakpointsBucket(String key) {
        return breakpointStore.bucket(key);
    }

    public void deleteBreakpointsForProject(String projectKey) {
        if (breakpointStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveBreakpoints();
        }
    }

    public Map<String, List<HistoryRevision>> historyBucket(String key) {
        return historyStore.bucket(key);
    }

    /** The whole per-project history map (every project) — used to compute live blob hashes for GC. */
    public Map<String, Map<String, List<HistoryRevision>>> historyByProject() {
        return historyStore.getByProject();
    }

    public void deleteHistoryForProject(String projectKey) {
        if (historyStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveHistory();
        }
    }

    // --- user dictionary ---

    /** The user's added spell-check words (lower-cased). Mutated in place; persisted by {@link #addUserWord}. */
    public java.util.Set<String> getUserDictionary() {
        return userDictionary;
    }

    /** Adds a word to the user dictionary (lower-cased) and appends it to {@code dictionary.txt}. */
    public void addUserWord(String word) {
        if (word == null || word.isBlank()) {
            return;
        }
        String w = word.strip().toLowerCase(java.util.Locale.ROOT);
        if (!userDictionary.add(w)) {
            return; // already present
        }
        try {
            Files.createDirectories(configDir);
            Path file = getUserDictionaryFile();
            // A hand-edited or synced file may not end in a line break; appending straight after it would
            // glue this word onto the last one ("beta" + "gamma" -> "betagamma", losing both).
            String separator = endsMidLine(file) ? System.lineSeparator() : "";
            Files.writeString(
                    file,
                    separator + w + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // non-fatal: the word still applies for this session, just isn't persisted
        }
    }

    /** True when {@code file} has content whose last byte is not a line feed. */
    private static boolean endsMidLine(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            if (size == 0) {
                return false;
            }
            java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
            return channel.position(size - 1).read(last) == 1 && last.get(0) != '\n';
        }
    }

    /** Removes a word from the user dictionary and rewrites {@code dictionary.txt}; no-op if absent. */
    public void removeUserWord(String word) {
        if (word == null) {
            return;
        }
        String w = word.strip().toLowerCase(java.util.Locale.ROOT);
        if (userDictionary.remove(w)) {
            rewriteUserDictionary();
        }
    }

    /** Rewrites {@code dictionary.txt} from the in-memory set (one word per line); non-fatal on failure.
     *  Written via temp + atomic move (the project convention): a plain {@code writeString} truncates first,
     *  so a crash / disk-full between truncate and write left the whole personal dictionary empty. */
    private void rewriteUserDictionary() {
        try {
            Files.createDirectories(configDir);
            StringBuilder sb = new StringBuilder();
            for (String w : userDictionary) {
                sb.append(w).append(System.lineSeparator());
            }
            com.editora.io.AtomicFileWrite.write(
                    getUserDictionaryFile(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            // non-fatal: the in-memory set still applies for this session
        }
    }

    private void loadUserDictionary() {
        userDictionary.clear();
        Path file = getUserDictionaryFile();
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            // Lenient decode: Files.readAllLines REPORTs malformed bytes and throws, which silently discarded
            // the ENTIRE personal dictionary over one bad byte — and a later remove would then rewrite the
            // file from that empty set (permanent loss). new String(bytes, UTF_8) replaces instead of throwing.
            String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                String w = stripBom(line).strip().toLowerCase(java.util.Locale.ROOT);
                if (!w.isEmpty()) {
                    userDictionary.add(w);
                }
            }
        } catch (IOException ignored) {
            // missing/unreadable dictionary just means no user words
        }
    }

    /** Drops a leading UTF-8 BOM — {@link String#strip()} does not (U+FEFF is {@code Cf}, not whitespace), so
     *  a BOM'd dictionary.txt left its first word permanently unmatchable. */
    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    // --- loading + saving the bucketed stores ---

    /**
     * Loads {@code bookmarks.json}. On first run (no {@code bookmarks.json} yet) it migrates any bookmarks
     * previously stored inside {@code workspace-state.json} (→ the {@code ""} / no-project bucket) and
     * each per-project {@code projects/<id>.json} session file (→ that project's bucket), strips them from
     * those files, and writes {@code bookmarks.json} so the migration runs only once.
     */
    private void loadBookmarks() {
        if (Files.exists(getBookmarksFile())) {
            bookmarkStore = read(getBookmarksFile(), new BookmarkStore(), ConfigSchema.BOOKMARKS);
            return;
        }
        bookmarkStore = new BookmarkStore();
        migrateLegacyBookmarks();
        saveBookmarks(); // create bookmarks.json so migration is one-time (even if empty)
    }

    private void loadConnections() {
        if (Files.exists(getConnectionsFile())) {
            connectionStore = read(getConnectionsFile(), new ConnectionStore(), ConfigSchema.CONNECTIONS);
        } else {
            connectionStore = new ConnectionStore();
        }
    }

    private void loadBreakpoints() {
        if (Files.exists(getBreakpointsFile())) {
            breakpointStore = read(getBreakpointsFile(), new BreakpointStore(), ConfigSchema.BREAKPOINTS);
        } else {
            breakpointStore = new BreakpointStore();
        }
    }

    private void loadHistory() {
        Path index = getHistoryFile();
        int problemsBefore = loadProblems.size();
        boolean lost = HistoryIndexGuard.lostIndex(index, getHistoryBlobsDir());
        if (lost && Files.exists(index)) {
            // Zero-length beside stored revision bodies: a write the OS never flushed, not "no history yet".
            loadProblems.add(ConfigMigrations.unreadable(index));
            historyStore = new HistoryStore();
        } else if (Files.exists(index)) {
            // Reported, but never write-protected: the index publication protocol below must keep running.
            historyStore = ConfigMigrations.readVersioned(
                    index, json, new HistoryStore(), ConfigSchema.HISTORY, loadProblems::add);
        } else {
            historyStore = new HistoryStore();
        }
        // Decided after the read, which is what leaves a backup behind. The backup keeps protecting the
        // bodies in later sessions, when the index this session writes loads cleanly.
        // An index that was read with a non-UTF-8 byte replaced still lists every revision, so it does not count.
        boolean readAsWritten = loadProblems.subList(problemsBefore, loadProblems.size()).stream()
                .allMatch(p -> p.kind() == ConfigLoadProblem.Kind.NOT_UTF8);
        historyIndexIntact = !lost && readAsWritten && !HistoryIndexGuard.backupPresent(index);
        Set<String> loaded = HistoryRetention.liveHashes(historyStore.getByProject());
        synchronized (historyPublicationLock) {
            durableHistoryHashes = loaded;
            currentHistoryHashes = loaded;
            durableHistoryPublication = ++nextHistoryPublication;
        }
    }

    /** Writes the Local File History index to {@code history/index.json}, independently of a session save. */
    public void saveHistory() {
        saveHistory(null);
    }

    /** Queues the index and reports whether this exact snapshot became durable. */
    public void saveHistory(Consumer<Boolean> completion) {
        HistoryStore snapshot = new HistoryStore();
        snapshot.setSchemaVersion(historyStore.getSchemaVersion());
        Map<String, Map<String, List<HistoryRevision>>> projects = new LinkedHashMap<>();
        for (var project : historyStore.getByProject().entrySet()) {
            Map<String, List<HistoryRevision>> files = new LinkedHashMap<>();
            project.getValue().forEach((path, revisions) -> files.put(path, List.copyOf(revisions)));
            projects.put(project.getKey(), files);
        }
        snapshot.setByProject(projects);
        Set<String> hashes = HistoryRetention.liveHashes(projects);
        long publication;
        synchronized (historyPublicationLock) {
            publication = ++nextHistoryPublication;
            currentHistoryHashes = hashes;
            pendingHistoryHashes.put(publication, hashes);
            if (completion != null) {
                historyPublicationWaiters.put(publication, completion);
            }
        }
        writer.enqueue(
                getHistoryFile(),
                () -> json.writeValueAsBytes(snapshot),
                outcome -> finishHistoryPublication(publication, hashes, outcome));
    }

    private void finishHistoryPublication(long publication, Set<String> hashes, ConfigWriter.WriteOutcome outcome) {
        Set<String> protectedHashes;
        Map<Long, Consumer<Boolean>> finished = new LinkedHashMap<>();
        synchronized (historyPublicationLock) {
            pendingHistoryHashes.remove(publication);
            if (outcome == ConfigWriter.WriteOutcome.WRITTEN && publication > durableHistoryPublication) {
                durableHistoryPublication = publication;
                durableHistoryHashes = hashes;
            }
            Consumer<Boolean> waiter = historyPublicationWaiters.remove(publication);
            if (waiter != null) {
                finished.put(publication, waiter);
            }
            protectedHashes = new LinkedHashSet<>(durableHistoryHashes);
            protectedHashes.addAll(currentHistoryHashes);
            pendingHistoryHashes.values().forEach(protectedHashes::addAll);
            // Queue GC while this live-set snapshot is still current. A durable waiter can start the
            // next publication; running its callback first would let this older GC delete its new blob.
            historyService.gcIfDue(protectedHashes);
        }
        boolean durable = outcome == ConfigWriter.WriteOutcome.WRITTEN;
        finished.forEach((ignored, waiter) -> waiter.accept(durable));
    }

    /** Migrates bookmarks out of the legacy session files into their per-project buckets, stripping each. */
    private void migrateLegacyBookmarks() {
        extractAndStripBookmarks(configDir.resolve(ConfigManager.WORKSPACE_FILE_NAME), ""); // no-project bucket
        Path projectsDir = configDir.resolve(ConfigManager.PROJECTS_DIR_NAME);
        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> s = Files.list(projectsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(f -> {
                            String fn = f.getFileName().toString();
                            extractAndStripBookmarks(f, fn.substring(0, fn.length() - ".json".length()));
                        });
            } catch (IOException ignored) {
                // best-effort migration; a missing/unreadable projects dir just means nothing to migrate
            }
        }
    }

    /** Pulls the {@code bookmarks} node out of a legacy session file into {@code projectKey}'s bucket and rewrites it without that node. */
    private void extractAndStripBookmarks(Path file, String projectKey) {
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            JsonNode root = json.readTree(ConfigMigrations.readText(file));
            if (!(root instanceof ObjectNode obj)) {
                return;
            }
            if (!obj.has("bookmarks")) {
                return;
            }
            JsonNode bm = obj.get("bookmarks");
            if (bm != null && bm.isObject() && !bm.isEmpty()) {
                Map<String, List<Bookmark>> legacy =
                        json.convertValue(bm, new TypeReference<LinkedHashMap<String, List<Bookmark>>>() {});
                Map<String, List<Bookmark>> into = bookmarkStore.bucket(projectKey);
                legacy.forEach(into::putIfAbsent);
            }
            obj.remove("bookmarks"); // drop the legacy node (even when empty) so it stops lingering
            ConfigWriter.writeAtomic(file, json, obj);
        } catch (IOException | IllegalArgumentException e) {
            // a malformed legacy file simply contributes no bookmarks
        }
    }

    /** Writes the global bookmarks to {@code bookmarks.json}, independently of a session save. */
    public void saveBookmarks() {
        writeStore(getBookmarksFile(), bookmarkStore);
    }

    /** Writes the breakpoints to {@code breakpoints.json}, independently of a session save. */
    public void saveBreakpoints() {
        writeStore(getBreakpointsFile(), breakpointStore);
    }

    /** Loads {@code notes.json} (versioned, per-project buckets). Missing/malformed ⇒ an empty store. */
    private void loadNotes() {
        noteStore = read(getNotesFile(), new NoteStore(), ConfigSchema.NOTES);
    }

    /** Writes the global Personal Notes to {@code notes.json}, independently of a session save. */
    public void saveNotes() {
        writeStore(getNotesFile(), noteStore);
    }
}
