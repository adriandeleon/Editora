package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

    /** Makes every store write a "my changes only" write (see {@link StoreSync}). */
    private final StoreSync sync;

    /**
     * Stores written on the caller's thread whose last write failed, each with the save that writes it again.
     * Such a store is written only when it changes, so without this a note, bookmark, breakpoint, macro, site
     * or dictionary word added while the config folder was briefly unwritable lived in memory until quit and
     * was gone after it. Retried after the next store write that succeeds, by {@link #flushWrites} (every
     * durable save, including the one at quit) and by {@link #shutdown}.
     */
    private final Map<Path, Runnable> unsavedStores = new LinkedHashMap<>();

    /** True while {@link #retryUnsavedStores} runs, so the saves it calls do not start another round. */
    private boolean retryingUnsavedStores;

    /**
     * The body hashes of revisions that are in the Local History index on disk but not in this process's
     * memory — another process recorded them. Replaced by every index write that looked at the disk (see
     * {@link #saveHistory}); their bodies must not be collected.
     */
    private volatile Set<String> foreignHistoryHashes = Set.of();

    /** The body hashes listed by the backups kept beside the index ({@link HistoryIndexGuard#backupHashes}). */
    private volatile Set<String> backupHistoryHashes = Set.of();

    /** The backups {@link #backupHistoryHashes} was read from, to notice one that appears mid-session. */
    private volatile Set<String> knownHistoryBackups = Set.of();

    /**
     * When the newest backup that is not a whole index was made, or {@code Long.MIN_VALUE}: nothing is
     * collected until its grace period is over (see {@link HistoryIndexGuard.BackupHashes}).
     */
    private volatile long incompleteHistoryBackupSince = Long.MIN_VALUE;

    private final HistoryService historyService;
    /** This process's claim on the config dir ({@code null} until {@link #claimInstance()}). */
    private volatile InstanceLock instanceLock;

    private final DocumentWriteSequencer documentWrites = new DocumentWriteSequencer();
    /** Orders blob collection against index publications: a collection is queued while this is held. */
    private final Object historyPublicationLock = new Object();

    private Settings settings = new Settings();
    /** Global bookmarks (all files/projects), stored in {@code bookmarks.json} — see {@link BookmarkStore}. */
    private BookmarkStore bookmarkStore = new BookmarkStore();
    /** Personal Notes (all files/projects), stored in {@code notes.json} — see {@link NoteStore}. */
    private NoteStore noteStore = new NoteStore();
    /** Breakpoints (all files/projects), stored in {@code breakpoints.json} — see {@link BreakpointStore}. */
    private BreakpointStore breakpointStore = new BreakpointStore();
    /** Local File History index (all files/projects), in {@code history/index.json} — see {@link HistoryStore}. */
    private volatile HistoryStore historyStore = new HistoryStore();
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
    /** The dictionary as this process last read or wrote it: what its own additions and removals are relative to. */
    private final java.util.Set<String> userDictionaryBase = new java.util.LinkedHashSet<>();
    /** The shared projects index ({@code projects.json}) — one source of truth across all windows. */
    private final ProjectManager projects;

    /** The histories every window adds to; created on first use (see {@link #recentFiles()}). */
    private RecentFiles recentFiles;

    private SearchHistory searchHistory;
    private AgentSessionHistory agentSessions;

    /**
     * Whether the Local History index in memory can be collected against — false when it was lost, lists
     * nothing beside stored bodies, or failed to load without a legible copy. Blob GC is refused when false (see
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
        this.sync = new StoreSync(configDir, json);
        this.projects = new ProjectManager(configDir);
        this.projects.setOnWriteError((file, failure) -> {
            writer.reportWriteError(file, failure);
            markUnsaved(file, this.projects::save);
        });
        this.projects.setOnSaved(file -> storeSaved(file));
        this.projects.loadProblems().forEach(this::onLoadProblem);
        this.historyService =
                new HistoryService(new HistoryBlobStore(getHistoryBlobsDir()), this::mayCollectHistoryBlobs);
        this.historyService.setOnAcknowledged(this::historyLimitsAcknowledged);
    }

    // --- more than one process on this config dir ---

    /**
     * Claims the config dir for this process and reports whether it is the <em>primary</em> instance — the
     * first Editora process using this directory. Call once at startup, before any window is built;
     * idempotent. The claim is an OS file lock (see {@link InstanceLock}) held until {@link #shutdown()} or
     * process exit, so a crashed primary never leaves a stale claim behind.
     *
     * <p>A second process on the same directory is a possible state (a {@code --new-instance} launch, or one
     * that could not be forwarded to the running editor). Each process writes only its own changes to the
     * stores ({@link StoreSync}) but does not see the other's until it restarts. So a secondary is told
     * apart, tells the user once, and never garbage-collects shared data.
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
     * <p>Nor while the index cannot be trusted ({@link HistoryIndexGuard}): it was lost, lists nothing beside
     * stored bodies, or failed to load with no legible copy kept. A copy that <em>was</em> kept
     * ({@code index.json…bak}) does not stop collection by itself — the bodies it refers to are protected by
     * hash — except, for a grace period, when it is not a whole index and so cannot say what it once listed.
     */
    boolean mayCollectHistoryBlobs() {
        if (!historyIndexIntact) {
            return false;
        }
        long incompleteSince = incompleteHistoryBackupSince;
        if (incompleteSince != Long.MIN_VALUE
                && System.currentTimeMillis() - incompleteSince <= HistoryIndexGuard.INCOMPLETE_BACKUP_GRACE_MILLIS) {
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
        sync.loaded(getSettingsFile(), settingsBytes());
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
     * <p>The legacy file is renamed to {@code settings.toml.migrated} — not deleted — and only after the
     * complete JSON replacement has been written atomically: the JSON is produced from the model, so the
     * comments of a hand-maintained file and any key the model does not carry exist nowhere else. If the
     * write fails, this launch still uses the TOML values and the next launch can retry; an existing JSON
     * file always wins so a stale legacy copy can never roll settings back.
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
            retireLegacyFile(legacy);
            return migrated;
        }
        return read(file, new Settings(), ConfigSchema.SETTINGS);
    }

    /** The suffix a converted legacy TOML file is kept under. */
    static final String MIGRATED_SUFFIX = ".migrated";

    /**
     * Renames a legacy file that has been converted to {@code <name>.migrated} (numbered when that is taken).
     * Best effort: when it cannot be renamed it stays where it is, which is harmless — JSON wins from now on.
     */
    static void retireLegacyFile(Path legacy) {
        Path kept = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX);
        for (int n = 2; Files.exists(kept) && n <= 20; n++) { // an earlier one is somebody's copy too
            kept = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX + "." + n);
        }
        try {
            Files.move(legacy, kept);
        } catch (IOException | RuntimeException ignored) {
            // left in place; never deleted
        }
    }

    /** {@link ConfigMigrations#readVersioned} with this instance's mapper, collecting what could not be read. */
    private <T> T read(Path file, T defaults, ConfigSchema schema) {
        T store = ConfigMigrations.readVersioned(file, json, defaults, schema, this::onLoadProblem);
        if (schema != ConfigSchema.SETTINGS) { // the preferences are registered by load(), after a TOML conversion
            storeLoaded(file, store);
        }
        return store;
    }

    /** Tells {@link StoreSync} what {@code store}, just read from {@code file}, serializes to. */
    private void storeLoaded(Path file, Object store) {
        try {
            sync.loaded(file, json.writeValueAsBytes(store));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + file.getFileName(), e);
        }
    }

    /** As {@link #storeLoaded(Path, Object)}, for a session file read by a window's {@link ConfigManager}. */
    void storeLoaded(Path file, byte[] serialized) {
        sync.loaded(file, serialized);
    }

    /**
     * Queues {@code mine} — a store serialized on the calling thread — for {@code file}. The write happens on
     * the writer thread through {@link StoreSync}: skipped when this process changed nothing, merged with
     * what is on disk when another process wrote the file in the meantime.
     */
    void enqueueStore(Path file, byte[] mine, ConfigSchema schema) {
        writer.enqueue(file, () -> sync.write(file, mine, schema, null) ? null : ConfigWriter.UNCHANGED);
    }

    /** The sink a history list writes through: queued, and merged like every other store. */
    private ConfigWriter.Sink sinkFor(ConfigSchema schema) {
        return new ConfigWriter.Sink() {
            @Override
            public void write(Path file, ConfigWriter.BytesSupplier bytes) {
                if (!writeProtected.contains(file)) {
                    writer.enqueue(
                            file, () -> sync.write(file, bytes.get(), schema, null) ? null : ConfigWriter.UNCHANGED);
                }
            }

            @Override
            public void loaded(Path file, ConfigWriter.BytesSupplier bytes) {
                try {
                    sync.loaded(file, bytes.get());
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to serialize " + file.getFileName(), e);
                }
            }
        };
    }

    /** Records {@code problem} for {@link #takeLoadProblems} and write-protects its file when it must be. */
    void onLoadProblem(ConfigLoadProblem problem) {
        if (problem.kind() == ConfigLoadProblem.Kind.NEWER_COPY_KEPT && alreadyReported(problem.backup())) {
            return; // said once, when the newer build first came back; the copy is the user's to deal with
        }
        if (!loadProblems.contains(problem)) { // a session file is read at bootstrap and again by its window
            loadProblems.add(problem);
        }
        if (problem.mustNotOverwrite()) {
            writeProtected.add(problem.file());
        }
    }

    /** Names the set-aside copies the user has been told about, one path per line. */
    static final String REPORTED_COPIES_FILE_NAME = "reported-backups.txt";

    /**
     * Whether {@code copy} — a set-aside file that was not restored — has been reported in an earlier launch;
     * records it when it has not. Without this the same notice would come back on every start for as long as
     * the copy exists. When the record cannot be read or written the answer is "not yet": repeating a notice
     * is better than never giving it.
     */
    private boolean alreadyReported(Path copy) {
        if (copy == null) {
            return false;
        }
        Path ledger = configDir.resolve(REPORTED_COPIES_FILE_NAME);
        String line = copy.toAbsolutePath().normalize().toString();
        try {
            if (Files.isReadable(ledger) && Files.readAllLines(ledger).contains(line)) {
                return true;
            }
            Files.createDirectories(configDir);
            Files.writeString(
                    ledger,
                    line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // not recorded: it is reported again next launch
        }
        return false;
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
        return flushWrites();
    }

    /** The shared off-thread writer (settings + session state route through it; see {@link ConfigWriter}). */
    /** Drops any queued write for {@code file} (it's about to be deleted — see {@link ConfigWriter#cancel}). */
    public void cancelPendingWrite(Path file) {
        writer.cancel(file);
        sync.forget(file);
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
            enqueueStore(getSettingsFile(), bytes, ConfigSchema.SETTINGS);
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
        boolean storesSaved = retryUnsavedStores();
        return writer.flush() && storesSaved;
    }

    /** Stops app-wide background services after the last window has closed. */
    public boolean shutdown() {
        // First: the history worker finishes the records still in flight (the save made from the quit prompt)
        // and hands them over, which queues the index — so the writer has to be running still.
        historyService.shutdown();
        boolean storesSaved = retryUnsavedStores(); // the last chance for a store whose write failed earlier
        boolean durable = writer.shutdown() && storesSaved;
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
     * <p>The write goes through {@link StoreSync}: nothing is written when this process changed nothing, and
     * when another process wrote the file in the meantime its entries are kept and only this process's own
     * additions, edits and removals are applied.
     *
     * <p>Never throws. These writes run inside FX event handlers (toggle a bookmark or breakpoint, edit a
     * note, save a macro, trust a folder) and, for the one-time {@code bookmarks.json} creation, inside
     * {@link #load()}: an exception from a full disk or a read-only config dir used to abort the handler with
     * nothing shown, or stop the app from starting. A failure is logged and reported through
     * {@link #setOnWriteError} like a queued write; the change stays in memory and {@code retry} is run again
     * later (see {@link #unsavedStores}).
     *
     * @param retry the public save that leads back here for this store
     * @return whether the store in memory is now the one on disk
     */
    private boolean writeStore(Path file, Object store, ConfigSchema schema, Runnable retry) {
        if (writeProtected.contains(file)) {
            return false;
        }
        boolean written;
        try {
            Files.createDirectories(configDir);
            written = sync.write(file, json.writeValueAsBytes(store), schema, null);
        } catch (IOException e) {
            writer.reportWriteError(file, e);
            markUnsaved(file, retry);
            return false;
        }
        if (written) { // an unchanged store that was skipped says nothing about whether the disk works
            storeSaved(file);
        }
        return true;
    }

    private void markUnsaved(Path file, Runnable retry) {
        synchronized (unsavedStores) {
            unsavedStores.put(file, retry);
        }
    }

    /** {@code file} is on disk as it is in memory; the disk takes writes, so retry what failed before. */
    private void storeSaved(Path file) {
        synchronized (unsavedStores) {
            unsavedStores.remove(file);
        }
        retryUnsavedStores();
    }

    /**
     * Saves again every store whose last write failed. A store that fails again reports it again and stays
     * listed.
     *
     * @return whether nothing is left unsaved
     */
    private boolean retryUnsavedStores() {
        List<Runnable> retries;
        synchronized (unsavedStores) {
            if (retryingUnsavedStores) {
                return true;
            }
            if (unsavedStores.isEmpty()) {
                return true;
            }
            retryingUnsavedStores = true;
            retries = new ArrayList<>(unsavedStores.values());
        }
        try {
            retries.forEach(Runnable::run);
        } finally {
            synchronized (unsavedStores) {
                retryingUnsavedStores = false;
            }
        }
        synchronized (unsavedStores) {
            return unsavedStores.isEmpty();
        }
    }

    /** Whether a store's last write failed and is still waiting to be retried. */
    boolean hasUnsavedStores() {
        synchronized (unsavedStores) {
            return !unsavedStores.isEmpty();
        }
    }

    // --- recent files, search history, agent sessions (one instance each, shared by every window) ---

    /**
     * The recent-files list. One instance for the whole app: each window used to load its own copy and
     * rewrite the whole file on every change, so the last window to write discarded the others' entries.
     */
    public RecentFiles recentFiles() {
        if (recentFiles == null) {
            recentFiles = new RecentFiles(configDir, sinkFor(ConfigSchema.RECENT), this::onLoadProblem);
        }
        return recentFiles;
    }

    /** The Find-in-Files query history, shared like {@link #recentFiles()}. */
    public SearchHistory searchHistory() {
        if (searchHistory == null) {
            searchHistory = new SearchHistory(configDir, sinkFor(ConfigSchema.SEARCH_HISTORY), this::onLoadProblem);
        }
        return searchHistory;
    }

    /** The AI Agent chat-session history, shared like {@link #recentFiles()}. */
    public AgentSessionHistory agentSessions() {
        if (agentSessions == null) {
            agentSessions =
                    new AgentSessionHistory(configDir, sinkFor(ConfigSchema.AGENT_SESSIONS), this::onLoadProblem);
        }
        return agentSessions;
    }

    // --- plugins (enable-state) ---

    public PluginStore getPluginStore() {
        return pluginStore;
    }

    private void loadPlugins() {
        // Read even when absent: a copy an older build set aside may be waiting to be restored.
        pluginStore = read(getPluginsFile(), new PluginStore(), ConfigSchema.PLUGINS);
    }

    public void savePlugins() {
        writeStore(getPluginsFile(), pluginStore, ConfigSchema.PLUGINS, this::savePlugins);
    }

    // --- workspace trust (folders allowed to run their own build wrapper) ---

    public Path getTrustFile() {
        return configDir.resolve(ConfigManager.TRUST_FILE_NAME);
    }

    public TrustStore getTrustStore() {
        return trustStore;
    }

    private void loadTrust() {
        // Read even when absent: a copy an older build set aside may be waiting to be restored.
        trustStore = read(getTrustFile(), new TrustStore(), ConfigSchema.TRUST);
    }

    public void saveTrust() {
        writeStore(getTrustFile(), trustStore, ConfigSchema.TRUST, this::saveTrust);
    }

    // --- keyboard macros (app-global) ---

    public MacroStore getMacroStore() {
        return macroStore;
    }

    private void loadMacros() {
        // Read even when absent: a copy an older build set aside may be waiting to be restored.
        macroStore = read(getMacrosFile(), new MacroStore(), ConfigSchema.MACROS);
        if (macroStore == null) {
            macroStore = new MacroStore();
        }
        macroStore.sanitize(); // a hand-edited file may hold a null list or null entries
    }

    public void saveMacros() {
        writeStore(getMacrosFile(), macroStore, ConfigSchema.MACROS, this::saveMacros);
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
                // Stripped: Settings stores the field as typed, and a stray space would never match.
                m.put(a.getAbbreviation().strip().toLowerCase(java.util.Locale.ROOT), a.getExpansion());
            }
        }
        return m;
    }

    private void loadAbbreviations() {
        // Read even when absent: a copy an older build set aside may be waiting to be restored.
        abbrevStore = read(getAbbreviationsFile(), new AbbrevStore(), ConfigSchema.ABBREVIATIONS);
    }

    /**
     * Re-reads {@code abbreviations.json} after something other than this process's own save replaced it
     * (settings sync), and tells the windows. The buffers hold their own copy of the map; the caller
     * re-applies the settings to refresh those.
     */
    public void reloadAbbreviations() {
        loadAbbreviations();
        onStoreChanged.run();
    }

    public void saveAbbreviations() {
        writeStore(getAbbreviationsFile(), abbrevStore, ConfigSchema.ABBREVIATIONS, this::rewriteAbbreviations);
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
        writeStore(getConnectionsFile(), connectionStore, ConfigSchema.CONNECTIONS, this::rewriteConnections);
        onStoreChanged.run();
    }

    /** The retry of a failed {@link #saveAbbreviations}: the list did not change again, so nobody is told. */
    private void rewriteAbbreviations() {
        writeStore(getAbbreviationsFile(), abbrevStore, ConfigSchema.ABBREVIATIONS, this::rewriteAbbreviations);
    }

    /** The retry of a failed {@link #saveConnections}. */
    private void rewriteConnections() {
        writeStore(getConnectionsFile(), connectionStore, ConfigSchema.CONNECTIONS, this::rewriteConnections);
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
        String w = dictionaryForm(word);
        if (w.isEmpty() || !userDictionary.add(w)) {
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
            userDictionaryBase.add(w);
        } catch (IOException e) {
            // The word still applies for this session; say that it is not saved, and try again later.
            writer.reportWriteError(getUserDictionaryFile(), e);
            markUnsaved(getUserDictionaryFile(), this::rewriteUserDictionary);
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
        String w = dictionaryForm(word);
        if (userDictionary.remove(w)) {
            rewriteUserDictionary();
        }
    }

    /**
     * The form a word is kept under: lower case, with the typographic apostrophes editors substitute written
     * as the ASCII one the spell checker looks words up by. A word stored as typed ({@code zzq’abc}) was
     * written to the file and never matched.
     */
    public static String dictionaryForm(String word) {
        return word.strip().replace('’', '\'').replace('‘', '\'').toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Re-reads {@code dictionary.txt} into the shared set, returning whether the set changed. For a file
     * edited by hand (the Settings link opens it in the editor): the words used to be read once at startup,
     * so an added line was not accepted, and a removed one stayed accepted, until the next launch.
     */
    public boolean reloadUserDictionary() {
        java.util.Set<String> onDisk;
        try {
            onDisk = readUserDictionary(getUserDictionaryFile());
        } catch (IOException e) {
            return false; // unreadable right now: keep what is in memory
        }
        // What this process added or removed and has not managed to write yet still applies on top.
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(onDisk);
        for (String known : userDictionaryBase) {
            if (!userDictionary.contains(known)) {
                merged.remove(known);
            }
        }
        for (String word : userDictionary) {
            if (!userDictionaryBase.contains(word)) {
                merged.add(word);
            }
        }
        userDictionaryBase.clear();
        userDictionaryBase.addAll(onDisk);
        if (merged.equals(userDictionary)) {
            return false;
        }
        // In place: every buffer's checker holds this very set.
        userDictionary.retainAll(merged);
        userDictionary.addAll(merged);
        return true;
    }

    /**
     * Brings {@code dictionary.txt} in line with this process's additions and removals, keeping every word
     * another process (or a hand edit) added to the file in the meantime — the file used to be rewritten from
     * this process's set alone, which dropped them. Those words join the in-memory set as well.
     *
     * <p>Written via temp + atomic move (the project convention): a plain {@code writeString} truncates
     * first, so a crash / disk-full between truncate and write left the whole personal dictionary empty.
     * A failure is reported and the rewrite is retried later; the in-memory set applies meanwhile.
     */
    private void rewriteUserDictionary() {
        Path file = getUserDictionaryFile();
        try {
            Files.createDirectories(configDir);
            java.util.Set<String> merged = readUserDictionary(file);
            for (String removedHere : userDictionaryBase) {
                if (!userDictionary.contains(removedHere)) {
                    merged.remove(removedHere);
                }
            }
            for (String word : userDictionary) {
                if (!userDictionaryBase.contains(word)) {
                    merged.add(word); // added here (possibly by an append that failed)
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String w : merged) {
                sb.append(w).append(System.lineSeparator());
            }
            com.editora.io.AtomicFileWrite.write(file, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            userDictionary.addAll(merged);
            userDictionary.retainAll(merged);
            userDictionaryBase.clear();
            userDictionaryBase.addAll(merged);
        } catch (IOException e) {
            writer.reportWriteError(file, e);
            markUnsaved(file, this::rewriteUserDictionary);
            return;
        }
        storeSaved(file);
    }

    private void loadUserDictionary() {
        userDictionary.clear();
        userDictionaryBase.clear();
        try {
            userDictionary.addAll(readUserDictionary(getUserDictionaryFile()));
        } catch (IOException ignored) {
            // missing/unreadable dictionary just means no user words
        }
        userDictionaryBase.addAll(userDictionary);
    }

    /** The words in {@code file}, lower-cased, in file order; empty when the file is absent. */
    private static java.util.Set<String> readUserDictionary(Path file) throws IOException {
        java.util.Set<String> words = new java.util.LinkedHashSet<>();
        if (!Files.isReadable(file)) {
            return words;
        }
        // Lenient decode: Files.readAllLines REPORTs malformed bytes and throws, which silently discarded
        // the ENTIRE personal dictionary over one bad byte — and a later remove would then rewrite the
        // file from that empty set (permanent loss). new String(bytes, UTF_8) replaces instead of throwing.
        String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        for (String line : text.split("\r?\n")) {
            String w = dictionaryForm(stripBom(line));
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return words;
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
        // Before deciding this is a first run: an older build may have set the real file aside.
        ConfigMigrations.restoreSetAsideCopy(
                getBookmarksFile(), json, new BookmarkStore(), ConfigSchema.BOOKMARKS, this::onLoadProblem);
        if (Files.exists(getBookmarksFile())) {
            bookmarkStore = read(getBookmarksFile(), new BookmarkStore(), ConfigSchema.BOOKMARKS);
            return;
        }
        bookmarkStore = new BookmarkStore();
        migrateLegacyBookmarks();
        saveBookmarks(); // create bookmarks.json so migration is one-time (even if empty)
    }

    private void loadConnections() {
        // Read even when absent: a copy an older build set aside may be waiting to be restored.
        connectionStore = read(getConnectionsFile(), new ConnectionStore(), ConfigSchema.CONNECTIONS);
    }

    private void loadBreakpoints() {
        // Read even when absent: a copy an older build set aside may be waiting to be restored.
        breakpointStore = read(getBreakpointsFile(), new BreakpointStore(), ConfigSchema.BREAKPOINTS);
    }

    private void loadHistory() {
        Path index = getHistoryFile();
        // Reported, but never write-protected: the index publication protocol below must keep running.
        List<ConfigLoadProblem> problems = new ArrayList<>();
        // First, so that "lost" is judged on the index an older build may have set aside.
        ConfigMigrations.restoreSetAsideCopy(index, json, new HistoryStore(), ConfigSchema.HISTORY, problems::add);
        boolean lost = HistoryIndexGuard.lostIndex(index, getHistoryBlobsDir());
        HistoryStore loaded;
        if (lost && Files.exists(index)) {
            // Zero-length beside stored revision bodies: a write the OS never flushed, not "no history yet".
            problems.add(ConfigMigrations.unreadable(index));
            loaded = new HistoryStore();
        } else {
            loaded = ConfigMigrations.readVersioned(
                    index, json, new HistoryStore(), ConfigSchema.HISTORY, problems::add);
        }
        // A list merged from two writers is not newest-first on disk (StoreMerge keeps this process's rows
        // first); everything that reads one takes row 0 for the newest. Null rows and lists — legal JSON, a
        // hand edit or damage — were dropped as the store took the lists in.
        loaded.sortNewestFirst();
        historyStore = loaded;
        // What the file holds, as this build writes it: a list that had to be re-ordered is a change this
        // load made, and the next save writes it.
        try {
            sync.loaded(index, json.writeValueAsBytes(loaded.snapshot()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + index.getFileName(), e);
        }
        for (ConfigLoadProblem problem : problems) {
            if (problem.kind() == ConfigLoadProblem.Kind.NEWER_COPY_KEPT && alreadyReported(problem.backup())) {
                continue;
            }
            if (!loadProblems.contains(problem)) {
                loadProblems.add(problem);
            }
        }
        // Decided after the read, which is what leaves a backup behind.
        //
        // An index that did not load as written (torn, from a newer build, a value of the wrong type) is
        // missing revisions whose bodies are on disk. Its bytes were kept beside it, and the bodies that copy
        // refers to are protected by hash for as long as the copy exists (backupHistoryHashes) — in this
        // session and every later one. Only when a copy could not be kept, or cannot be read for its hashes,
        // is collection refused outright.
        // An index that was read with a non-UTF-8 byte replaced still lists every revision, so it does not
        // count; nor does one that was just restored, or a set-aside copy (the backup hashes cover that one).
        boolean everyLossHasACopy = problems.stream()
                .allMatch(p -> p.kind() == ConfigLoadProblem.Kind.NOT_UTF8
                        || p.kind() == ConfigLoadProblem.Kind.NEWER_COPY_RESTORED
                        || p.kind() == ConfigLoadProblem.Kind.NEWER_COPY_KEPT
                        || p.backup() != null);
        boolean backupsReadable = readHistoryBackups(index);
        // An index that lists no revision at all while bodies are stored is not trusted either, however it
        // came to be that way ("{}", an index rewritten from defaults, the empty one a failed load leaves):
        // collecting against it deletes every body. Bodies left by a deliberate purge are collected once the
        // index lists a revision again.
        boolean emptyBesideBodies = loaded.ledger().listsNothing() && HistoryIndexGuard.hasBlobs(getHistoryBlobsDir());
        historyIndexIntact = !lost && everyLossHasACopy && backupsReadable && !emptyBesideBodies;
        foreignHistoryHashes = Set.of();
        HistoryStore.Limits limits = loaded.getAcknowledgedLimits();
        historyService.restoreAcknowledged(
                limits == null
                        ? null
                        : new HistoryRetention.RetentionPolicy(
                                limits.maxPerFile(), limits.maxAgeMillis(), limits.maxTotalBytesPerProject()));
    }

    /**
     * Reads what the backups beside the index protect ({@link HistoryIndexGuard#backupHashes}). Returns false
     * when a backup cannot be read at all — then nothing may be collected.
     */
    private boolean readHistoryBackups(Path index) {
        Set<String> names = HistoryIndexGuard.backupNames(index);
        HistoryIndexGuard.BackupHashes backups = HistoryIndexGuard.backupHashes(index);
        knownHistoryBackups = names == null ? Set.of() : Set.copyOf(names);
        backupHistoryHashes = backups == null ? Set.of() : Set.copyOf(backups.hashes());
        incompleteHistoryBackupSince = backups == null ? Long.MIN_VALUE : backups.incompleteSince();
        return backups != null;
    }

    /** A backup appeared or went since the hashes were read (a merge that kept the bytes it could not read). */
    private void refreshHistoryBackups() {
        Path index = getHistoryFile();
        Set<String> names = HistoryIndexGuard.backupNames(index);
        if (names != null && names.equals(knownHistoryBackups)) {
            return;
        }
        if (!readHistoryBackups(index)) {
            historyIndexIntact = false;
        }
    }

    /** The limits in force changed: keep them with the index, for the next start. */
    private void historyLimitsAcknowledged(HistoryRetention.RetentionPolicy policy) {
        HistoryStore.Limits limits =
                new HistoryStore.Limits(policy.maxPerFile(), policy.maxAgeMillis(), policy.maxTotalBytesPerProject());
        HistoryStore store = historyStore;
        if (!limits.equals(store.getAcknowledgedLimits())) {
            store.setAcknowledgedLimits(limits);
            // With no history at all there is nothing a stricter limit could delete, and no reason to create
            // an index for the sake of this note: it is written with the first revision.
            if (!store.ledger().listsNothing() || Files.exists(getHistoryFile())) {
                saveHistory();
            }
        }
    }

    /** Writes the Local File History index to {@code history/index.json}, independently of a session save. */
    public void saveHistory() {
        saveHistory(null);
    }

    /**
     * Queues the index and reports whether this exact snapshot became durable.
     *
     * <p>Costs what changed since the last call, not the size of the history: the snapshot shares every
     * project that did not change ({@link HistoryStore#snapshot}), the hashes it refers to are already
     * counted ({@link HistoryHashLedger}), and serializing happens on the writer thread.
     */
    public void saveHistory(Consumer<Boolean> completion) {
        HistoryStore store = historyStore;
        HistoryStore.Snapshot snapshot = store.snapshot();
        long publication = store.ledger().publish();
        // Whether this publication's write looked at the file on disk: only then is what this process knows
        // of the other writers' revisions current. Written and read on the writer thread.
        boolean[] sawDisk = new boolean[1];
        writer.enqueue(
                getHistoryFile(),
                () -> {
                    // Another process's revisions are kept in the index on disk but are not in this process's
                    // memory, so their bodies are protected from this process's collection by hash.
                    Set<String>[] foreign = newHashSetHolder();
                    boolean written = sync.write(
                            getHistoryFile(),
                            json.writeValueAsBytes(snapshot),
                            ConfigSchema.HISTORY,
                            merged -> foreign[0] = foreignHashes(merged, snapshot));
                    if (written) {
                        // Not merged: the file now holds this process's rows and nothing else.
                        foreignHistoryHashes = foreign[0] == null ? Set.of() : foreign[0];
                        refreshHistoryBackups();
                        sawDisk[0] = true;
                    } else {
                        // Nothing to persist, so the file was not read. What this process knows of it is
                        // still current only if nobody else has written it since.
                        sawDisk[0] = sync.unchangedOnDisk(getHistoryFile());
                    }
                    return written ? null : ConfigWriter.UNCHANGED;
                },
                outcome -> finishHistoryPublication(store, publication, outcome, sawDisk, completion));
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] newHashSetHolder() {
        return (Set<String>[]) new Set<?>[1];
    }

    private void finishHistoryPublication(
            HistoryStore store,
            long publication,
            ConfigWriter.WriteOutcome outcome,
            boolean[] sawDisk,
            Consumer<Boolean> completion) {
        boolean durable = outcome == ConfigWriter.WriteOutcome.WRITTEN;
        synchronized (historyPublicationLock) {
            if (durable) {
                store.ledger().durable(publication);
            }
            // Collect only behind a publication that looked at the disk. One that was superseded before its
            // turn or failed — or had nothing to write while the file had changed underneath — has not seen
            // what another process added to the index since — collecting on its word deleted that process's
            // revision bodies while the index went on listing them. The request stays pending
            // (HistoryService.requestGc is only cleared by a collection that is queued) and the next
            // publication that does write collects.
            //
            // Queue GC while this live-set snapshot is still current. A durable waiter can start the
            // next publication; running its callback first would let this older GC delete its new blob.
            if (durable && sawDisk[0] && store == historyStore) {
                historyService.gcIfDue(() -> protectedHistoryHashes(store));
            }
        }
        if (completion != null) {
            completion.accept(durable);
        }
    }

    /** Every body hash a collection must keep right now. */
    private Set<String> protectedHistoryHashes(HistoryStore store) {
        Set<String> hashes = store.ledger().protectedHashes();
        hashes.addAll(foreignHistoryHashes);
        hashes.addAll(backupHistoryHashes);
        return hashes;
    }

    /**
     * The body hashes of the rows in {@code merged} — the index as it was just written — that are not this
     * process's own: {@code merged} is this process's rows plus what only the file on disk had, so a hash
     * that occurs more often there than in {@code mine} belongs to at least one row of another writer.
     * (Taking every hash of the merged tree, as before, also pinned this process's own bodies: a purge then
     * left them on disk until a later session.)
     */
    static Set<String> foreignHashes(JsonNode merged, HistoryStore.Snapshot mine) {
        Map<String, Integer> rows = new java.util.HashMap<>();
        for (JsonNode project : merged.path("byProject")) {
            for (JsonNode file : project) {
                for (JsonNode revision : file) {
                    String hash = revision.path("sha256").asText("");
                    if (!hash.isEmpty()) {
                        rows.merge(hash, 1, Integer::sum);
                    }
                }
            }
        }
        for (Map<String, List<HistoryRevision>> project : mine.byProject().values()) {
            for (List<HistoryRevision> file : project.values()) {
                for (HistoryRevision revision : file) {
                    rows.computeIfPresent(revision.sha256(), (hash, count) -> count > 1 ? count - 1 : null);
                }
            }
        }
        return Set.copyOf(rows.keySet());
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
        writeStore(getBookmarksFile(), bookmarkStore, ConfigSchema.BOOKMARKS, this::saveBookmarks);
    }

    /** Writes the breakpoints to {@code breakpoints.json}, independently of a session save. */
    public void saveBreakpoints() {
        writeStore(getBreakpointsFile(), breakpointStore, ConfigSchema.BREAKPOINTS, this::saveBreakpoints);
    }

    /** Loads {@code notes.json} (versioned, per-project buckets). Missing/malformed ⇒ an empty store. */
    private void loadNotes() {
        noteStore = read(getNotesFile(), new NoteStore(), ConfigSchema.NOTES);
    }

    /** Writes the global Personal Notes to {@code notes.json}, independently of a session save. */
    public void saveNotes() {
        writeStore(getNotesFile(), noteStore, ConfigSchema.NOTES, this::saveNotes);
    }
}
