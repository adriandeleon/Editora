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
import org.jspecify.annotations.Nullable;

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
    private @Nullable TomlMapper legacyToml;
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
     * Where a result worked out on the writer thread is handed back to the FX thread: {@code Platform.runLater},
     * or the calling thread when no toolkit runs (a test, a tool).
     */
    private volatile java.util.concurrent.Executor fxThread = SharedConfig::runOnFx;

    /**
     * The body hashes of revisions that are in the Local History index on disk but not in this process's
     * memory — another process recorded them. Replaced by every index write that looked at the disk (see
     * {@link #saveHistory}); their bodies must not be collected.
     */
    private volatile Set<String> foreignHistoryHashes = Set.of();

    /** {@link #foreignHistoryHashes} as found in {@code index.json} by the last whole-index merge. */
    private volatile Set<String> foreignIndexHashes = Set.of();

    /** {@link #foreignHistoryHashes} as found in the index's delta by the last delta write. */
    private volatile Set<String> foreignDeltaHashes = Set.of();

    /** {@code index.json} and its delta (perf finding F6): see {@link HistoryIndexFiles}. */
    private final HistoryIndexFiles historyFiles;

    /** The app is quitting: every index publication from here writes the index whole, for older builds. */
    private volatile boolean historyQuitting;

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
    private volatile @Nullable InstanceLock instanceLock;

    private final DocumentWriteSequencer documentWrites = new DocumentWriteSequencer();
    /** Orders blob collection against index publications: a collection is queued while this is held. */
    private final Object historyPublicationLock = new Object();

    private Settings settings = new Settings();
    /** Global bookmarks (all files/projects), stored in {@code bookmarks.json} — see {@link BookmarkStore}. */
    private BookmarkStore bookmarkStore = new BookmarkStore();
    /** Personal Notes (all files/projects), stored in {@code notes.json} — see {@link NoteStore}. */
    private NoteStore noteStore = new NoteStore();

    /** Local code-review threads, per repository, stored in {@code reviews.json} — see {@link ReviewStore}. */
    private ReviewStore reviewStore = new ReviewStore();
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
    /**
     * The dictionary as this process last read or wrote it: what its own additions and removals are relative to.
     * Owned by the writer thread after the load.
     */
    private final java.util.Set<String> userDictionaryBase = new java.util.LinkedHashSet<>();
    /** The shared projects index ({@code projects.json}) — one source of truth across all windows. */
    private final ProjectManager projects;

    /** The histories every window adds to; created on first use (see {@link #recentFiles()}). */
    private @Nullable RecentFiles recentFiles;

    private @Nullable SearchHistory searchHistory;
    private volatile @Nullable AgentSessionHistory agentSessions;
    private @Nullable HttpResponseHistory httpHistory;

    /**
     * True when the index was lost at load (missing or zero-length beside stored bodies, or read without a legible
     * copy kept) and no {@linkplain HistoryIndexGuard#LOSS_MARKER loss marker} could be written to give the bodies
     * their grace period: nothing is collected in this session then. Read on the history worker.
     */
    private volatile boolean historyLossUnmarked;

    /**
     * True when the index listed no revision at all at load although bodies are stored: nothing is collected in
     * this session (collecting against it deletes every body). A copy kept beside it protects what it listed
     * from then on; with no copy at all, the loss marker keeps every body for the grace period.
     */
    private volatile boolean historyEmptyAtLoad;

    /** True while a backup beside the index cannot be read for its hashes; looked at again on each publication. */
    private volatile boolean historyBackupsUnreadable;

    /** Tells a superseded index publication what became of the publication that replaced it. */
    private final HistoryPublicationAnswers historyAnswers = new HistoryPublicationAnswers();

    /** What {@link #load()} could not read as written, held until a window shows it (see {@link #takeLoadProblems}). */
    // Synchronized: a restored window's session is read on a thread of its own while the FX thread takes these.
    private final List<ConfigLoadProblem> loadProblems = java.util.Collections.synchronizedList(new ArrayList<>());
    /**
     * Files that must not be written this session: each holds content this build did not load (it is newer
     * than this build, or unreadable) and could not be copied aside, so saving would destroy the only copy.
     */
    private final Set<Path> writeProtected = ConcurrentHashMap.newKeySet();

    /** Bookkeeping fields: they change without any window having something to re-apply. */
    private static final List<String> SETTINGS_BOOKKEEPING = List.of(
            "lastUpdateCheckEpoch",
            "dismissedUpdateVersion",
            "latestReleaseVersion",
            "latestReleaseUrl",
            "lastUpdateCheckOkEpoch");
    /**
     * The serialized preferences every window last applied, or {@code null} until {@link #markSettingsApplied}.
     * Read and written on the writer thread only (see {@link #detectSettingsChange}).
     */
    private volatile @Nullable JsonNode appliedSettings;

    private volatile byte @Nullable [] appliedSettingsBytes;

    /**
     * Picks, at the moment of a save on the FX thread, the window the change it carries came from (see
     * {@link #setSettingsChanger}); the answer travels with the save to {@link #onSettingsChanged}.
     */
    private java.util.function.UnaryOperator<@Nullable ConfigManager> settingsChanger = saver -> saver;

    /**
     * Re-reads of {@code settings.json} asked for on the FX thread and not applied yet. While one is under way the
     * settings in memory are about to be replaced by the file's, so a save of them would write the old values
     * over the hand edit: it is held, and made once the file's values are in (see {@link #reloadSettings}).
     */
    private int settingsReloadsInFlight;

    /** A settings save was held while a re-read was under way (see {@link #settingsReloadsInFlight}). */
    private boolean settingsSaveHeld;
    /** The export in progress, if any (see {@link #exportConfigAsync}). */
    private java.util.concurrent.@Nullable CompletableFuture<Path> runningExport;
    /** Where an export lands; {@code null} means the user's home directory (see {@link #exportDirectory}). */
    private volatile @Nullable Path exportDirectory;

    private Consumer<@Nullable ConfigManager> onSettingsChanged = origin -> {};
    private volatile Runnable onStoreChanged = () -> {};

    public SharedConfig(Path configDir, boolean dev) {
        this.configDir = configDir;
        this.dev = dev;
        this.sync = new StoreSync(configDir, json);
        this.historyFiles = new HistoryIndexFiles(getHistoryFile(), json, sync);
        this.projects = new ProjectManager(configDir);
        this.projects.setOnWriteError(writer::reportWriteError);
        this.projects.useWriter(writer);
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
        if (historyLossUnmarked || historyEmptyAtLoad || historyBackupsUnreadable || !historyFiles.deltaReadable()) {
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

    /**
     * Whether a purge's revision bodies can leave the disk now ({@link #mayCollectHistoryBlobs}), for the
     * purge to say so when they cannot.
     */
    public boolean canCollectHistoryBlobs() {
        return mayCollectHistoryBlobs();
    }

    /**
     * What, if anything, keeps the bodies {@code sha256s} on disk after a purge removed their rows: the first
     * reason that applies, or {@link HistoryPurgeHold#NONE} when the next collection deletes them.
     */
    public HistoryPurgeHold historyPurgeHold(java.util.Collection<String> sha256s) {
        InstanceLock lock = instanceLock;
        if (lock != null && (!lock.primary() || lock.othersPresent())) {
            return HistoryPurgeHold.OTHER_PROCESS;
        }
        long incompleteSince = incompleteHistoryBackupSince;
        boolean inGrace = incompleteSince != Long.MIN_VALUE
                && System.currentTimeMillis() - incompleteSince <= HistoryIndexGuard.INCOMPLETE_BACKUP_GRACE_MILLIS;
        if (historyLossUnmarked
                || historyEmptyAtLoad
                || historyBackupsUnreadable
                || inGrace
                || !historyFiles.deltaReadable()) {
            return HistoryPurgeHold.INDEX_UNTRUSTED;
        }
        if (sha256s == null || sha256s.isEmpty()) {
            return HistoryPurgeHold.NONE;
        }
        Set<String> backup = backupHistoryHashes;
        if (sha256s.stream().anyMatch(backup::contains)) {
            return HistoryPurgeHold.BACKUP;
        }
        Set<String> foreign = foreignHistoryHashes;
        if (sha256s.stream().anyMatch(foreign::contains)) {
            return HistoryPurgeHold.OTHER_PROCESS; // another writer's rows on disk still list them
        }
        return HistoryPurgeHold.NONE;
    }

    /** The Local History index files (tests). */
    HistoryIndexFiles historyIndexFiles() {
        return historyFiles;
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
        // A reload replaces the Settings object; the next window pair re-baselines. On the writer thread, in order
        // with the comparisons it resets.
        writer.run(() -> {
            appliedSettings = null;
            appliedSettingsBytes = null;
        });
        List<String> skipped = new ArrayList<>();
        int before = loadProblems.size();
        settings = loadSettings();
        ApiKeyVault vault = apiKeys;
        boolean keysMoved = false;
        if (vault != null) {
            settings.useApiKeyVault(vault); // a reload replaced the Settings object the keys were attached to
            keysMoved = vault.absorb(settings); // a key the file holds again goes to the vault, not back to the file
        }
        loadProblems.subList(before, loadProblems.size()).forEach(p -> skipped.addAll(p.skipped()));
        byte[] loadedBytes = settingsBytes();
        settingsFileIssues = checkSettingsFile(skipped, loadedBytes);
        sync.loaded(getSettingsFile(), loadedBytes);
        if (keysMoved) {
            enqueueSettings(); // the file still holds the key the vault took: written again without it
        }
        loadBookmarks();
        loadBreakpoints();
        loadHistory();
        loadNotes();
        loadReviews();
        loadConnections();
        loadPlugins();
        loadTrust();
        loadMacros();
        loadAbbreviations();
        loadUserDictionary();
    }

    /** What {@code settings.json} says that the loaded settings do not use; see {@link SettingsFileCheck}. */
    private List<SettingsFileCheck.Issue> settingsFileIssues = List.of();

    public List<SettingsFileCheck.Issue> settingsFileIssues() {
        return settingsFileIssues;
    }

    /** The issues of the settings file as it is now on disk; none when it is unreadable or of another schema. */
    private List<SettingsFileCheck.Issue> checkSettingsFile(List<String> skipped, byte[] loaded) {
        try {
            JsonNode written = json.readTree(getSettingsFile().toFile());
            if (written == null || written.path("schemaVersion").asInt(-1) != Settings.SCHEMA_VERSION) {
                return List.of(); // migrated keys are renamed on purpose, not "unknown"
            }
            return SettingsFileCheck.issues(written, json.readTree(loaded), skipped);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Re-reads {@code settings.json} — saved by hand in the editor — into the live {@link Settings}, which every
     * window holds, and hands {@code done} its issues on the FX thread. A file that cannot be read (half-typed
     * JSON, a newer schema) leaves the settings as they are and hands over null; its problem is reported like one
     * found at startup.
     *
     * <p>The file is read, parsed and checked on the writer thread, after every write queued before it (PERF-01,
     * Pauta 6.2.0); the values are applied on the FX thread, field by field, with no JSON there. Until they are,
     * a save of the settings is held: it would write the old values over the hand edit.
     */
    public void reloadSettings(Consumer<@Nullable List<SettingsFileCheck.Issue>> done) {
        reloadSettings(null, (failure, issues) -> done.accept(issues));
    }

    /** File work for the writer thread, which may fail. */
    @FunctionalInterface
    public interface FileWork<T> {
        T run() throws IOException;
    }

    /**
     * As {@link #reloadSettings(Consumer)}, with {@code first} run on the writer thread just before the read (a
     * backup copied over {@code settings.json}). When it fails nothing is read: {@code done} gets its failure.
     */
    public void reloadSettings(
            @Nullable FileWork<?> first,
            java.util.function.BiConsumer<@Nullable IOException, @Nullable List<SettingsFileCheck.Issue>> done) {
        settingsReloadsInFlight++;
        writer.run(() -> {
            if (first != null) {
                try {
                    first.run();
                } catch (IOException e) {
                    fxThread.execute(() -> applyReloadedSettings(null, issues -> done.accept(e, null)));
                    return;
                }
            }
            Reloaded reloaded;
            try {
                reloaded = readSettingsForReload();
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(SharedConfig.class.getName())
                        .log(java.util.logging.Level.WARNING, "Could not re-read " + getSettingsFile(), e);
                reloaded = null;
            }
            Reloaded result = reloaded;
            fxThread.execute(() -> applyReloadedSettings(result, issues -> done.accept(null, issues)));
        });
        settleUnlessFx(); // a caller that may wait (a test, a tool) has the result when this returns
    }

    /**
     * Runs {@code work} on the writer thread after every config write queued so far, and hands its result (or
     * its failure) to {@code done} on the FX thread. For file work that must see those writes on disk — a copy of
     * {@code settings.json} made right after a save — without the FX thread waiting for them (PERF-01, Pauta
     * 6.2.0).
     */
    public <T> void afterWrites(
            FileWork<T> work, java.util.function.BiConsumer<@Nullable T, @Nullable Exception> done) {
        writer.run(() -> {
            T result;
            try {
                result = work.run();
            } catch (IOException | RuntimeException e) {
                fxThread.execute(() -> done.accept(null, e));
                return;
            }
            fxThread.execute(() -> done.accept(result, null));
        });
        settleUnlessFx();
    }

    /** What a re-read of {@code settings.json} found: the values, their issues, whether a key went to the keychain. */
    private record Reloaded(Settings read, List<SettingsFileCheck.Issue> issues, boolean keysMoved) {}

    /** The writer thread's half of {@link #reloadSettings}: null when the file cannot be applied. */
    private @Nullable Reloaded readSettingsForReload() {
        Path file = getSettingsFile();
        com.editora.perf.BlockingSites.enter("reloadSettings");
        List<ConfigLoadProblem> problems = new ArrayList<>();
        try {
            if (!json.readTree(file.toFile()).isObject()) {
                return null;
            }
        } catch (IOException e) {
            return null; // not JSON (yet): nothing to apply, and the file is the user's to fix
        }
        Settings read =
                ConfigMigrations.readVersioned(file, json, new Settings(), ConfigSchema.SETTINGS, problems::add);
        List<String> skipped = new ArrayList<>();
        for (ConfigLoadProblem p : problems) {
            if (p.kind() != ConfigLoadProblem.Kind.VALUES_SKIPPED) {
                onLoadProblem(p);
                return null;
            }
            skipped.addAll(p.skipped());
        }
        // A key typed into the file moves to the vault here; the vault writes its store on its own thread, and
        // the file lets go of the key when it is saved next (its key fields are never written).
        ApiKeyVault vault = apiKeys;
        boolean keysMoved = vault != null && vault.absorb(read);
        byte[] bytes = serialize(read, file);
        List<SettingsFileCheck.Issue> issues = checkSettingsFile(skipped, bytes);
        sync.loaded(file, bytes); // what is on disk now is this process's own state
        return new Reloaded(read, issues, keysMoved);
    }

    /** The FX thread's half of {@link #reloadSettings}. */
    private void applyReloadedSettings(
            @Nullable Reloaded reloaded, Consumer<@Nullable List<SettingsFileCheck.Issue>> done) {
        settingsReloadsInFlight--;
        if (reloaded != null) {
            Snapshots.assignFields(reloaded.read(), settings);
            settingsFileIssues = reloaded.issues();
        }
        if (settingsReloadsInFlight == 0 && (settingsSaveHeld || (reloaded != null && reloaded.keysMoved()))) {
            settingsSaveHeld = false;
            enqueueSettings(); // what was held, now over the file's values (unchanged ones are not written)
        }
        done.accept(reloaded == null ? null : reloaded.issues());
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
     * Queues the store {@code mine} serializes to for {@code file}. Serializing and the write happen on the writer
     * thread through {@link StoreSync}: skipped when this process changed nothing, merged with what is on disk
     * when another process wrote the file in the meantime. {@code mine} is called on the writer thread, so it
     * must serialize a snapshot nobody changes ({@link Snapshots#copy}), never a live store.
     */
    void enqueueStore(Path file, ConfigWriter.BytesSupplier mine, ConfigSchema schema) {
        writer.enqueue(file, () -> {
            byte[] bytes = mine.get();
            return bytes == null || sync.write(file, bytes, schema, null) ? null : ConfigWriter.UNCHANGED;
        });
    }

    /**
     * {@code value}'s JSON, for {@code file}. On the writer thread or at load, never the FX thread (PERF-01,
     * Pauta 6.2.0); named for {@link com.editora.perf.BlockingSites} so a test can tell.
     */
    byte[] serialize(Object value, Path file) {
        com.editora.perf.BlockingSites.enter("json " + file.getFileName());
        try {
            return json.writeValueAsBytes(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + file.getFileName(), e);
        }
    }

    /**
     * A caller that is not the FX thread (a test, a tool, a background task) waits for what it just queued, as
     * every store save did before the writes left the FX thread; the FX thread and the writer go on. Returns
     * whether everything queued is on disk (true when nobody waited).
     */
    boolean settleUnlessFx() {
        if (javafx.application.Platform.isFxApplicationThread() || writer.onWriterThread()) {
            return true;
        }
        return writer.awaitQueued();
    }

    /** {@code Platform.runLater}, or the calling thread when no JavaFX toolkit is running (a test, a tool). */
    private static void runOnFx(Runnable task) {
        try {
            javafx.application.Platform.runLater(task);
        } catch (IllegalStateException noToolkit) {
            task.run();
        }
    }

    /** TEST SEAM — where results worked out on the writer thread are handed back to the FX thread. */
    void setFxThreadForTest(java.util.concurrent.Executor executor) {
        this.fxThread = executor;
    }

    /** The sink a history list writes through: queued, and merged like every other store. */
    private ConfigWriter.Sink sinkFor(ConfigSchema schema) {
        return new ConfigWriter.Sink() {
            @Override
            public void write(Path file, ConfigWriter.BytesSupplier bytes) {
                if (!writeProtected.contains(file)) {
                    writer.enqueue(file, () -> {
                        byte[] mine = bytes.get();
                        // Null from the list's supplier means what it means to the queue: nothing to write.
                        return mine == null || sync.write(file, mine, schema, null) ? null : ConfigWriter.UNCHANGED;
                    });
                }
            }

            @Override
            public void delete(Path file) {
                cancelPendingWrite(file); // a queued write must not bring it back
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    java.util.logging.Logger.getLogger(SharedConfig.class.getName())
                            .log(java.util.logging.Level.WARNING, "Failed to delete " + file, e);
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
        synchronized (loadProblems) {
            if (!loadProblems.contains(problem)) { // a session file is read at bootstrap and again by its window
                loadProblems.add(problem);
            }
        }
        if (problem.mustNotOverwrite()) {
            writeProtected.add(problem.file());
        }
    }

    /** Write-protects the file of {@code problem} when it must not be overwritten; the caller reports it. */
    void protectIfMustNotOverwrite(ConfigLoadProblem problem) {
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
    private boolean alreadyReported(@Nullable Path copy) {
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
        synchronized (loadProblems) {
            List<ConfigLoadProblem> taken = List.copyOf(loadProblems);
            loadProblems.clear();
            return taken;
        }
    }

    /** True when {@code file} is not written this session because its unread content has no backup. */
    public boolean isWriteProtected(Path file) {
        return writeProtected.contains(file);
    }

    public Settings getSettings() {
        return settings;
    }

    /**
     * Saves the preferences to {@code settings.json}: queued on the FX thread, which goes on at once; a caller on
     * any other thread then waits until it is written (see {@link #settleUnlessFx}).
     */
    public boolean saveSettings() {
        enqueueSettings();
        return settleUnlessFx();
    }

    /** Drops any queued write for {@code file} (it's about to be deleted — see {@link ConfigWriter#cancel}). */
    public void cancelPendingWrite(Path file) {
        writer.cancel(file);
        sync.forget(file);
    }

    /**
     * Runs {@code work} on the config writer, after every write queued before it and before any queued after, and
     * waits for its answer: for file work that must not interleave with a store's save (Settings sync writing the
     * config directory's files). Never on the FX thread (PERF-01, Pauta 6.2.0): it waits.
     */
    public <T> T callWithWrites(java.util.concurrent.Callable<T> work) throws java.io.IOException {
        if (javafx.application.Platform.isFxApplicationThread()) {
            throw new IllegalStateException("callWithWrites waits for the config writer: never on the FX thread");
        }
        java.util.concurrent.CompletableFuture<T> done = new java.util.concurrent.CompletableFuture<>();
        writer.run(() -> {
            try {
                done.complete(work.call());
            } catch (Exception | Error e) {
                done.completeExceptionally(e);
            }
        });
        try {
            return done.get(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.IOException io) {
                throw io;
            }
            throw new java.io.IOException(cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new java.io.IOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException(e);
        }
    }

    /** The shared off-thread writer (settings + session state route through it; see {@link ConfigWriter}). */
    ConfigWriter writer() {
        return writer;
    }

    /**
     * Saves the projects index on the config-writer thread ({@link ProjectManager#saveQueued}): for the launch
     * path, where {@link ProjectManager#save()} would {@code fsync} on the FX thread before the first frame.
     * Flushed with every other queued write at quit.
     */
    public void saveProjectsQueued() {
        projects.saveQueued(writer);
    }

    /** Queues a write of the current preferences to {@code settings.json} (non-blocking, no JSON here). */
    void enqueueSettings() {
        enqueueSettings(null);
    }

    /**
     * As {@link #enqueueSettings()}, naming the window whose save this is. When the preferences differ from
     * what every window last applied, {@link #setOnSettingsChanged} is told, so a change made through one
     * window's palette command or key binding reaches the others without each command having to remember to
     * broadcast it.
     */
    void enqueueSettings(@Nullable ConfigManager origin) {
        if (settingsReloadsInFlight > 0) {
            settingsSaveHeld = true; // the file's values are about to replace these; saved once they have
            return;
        }
        // A copy, serialized on the writer thread (PERF-01, Pauta 6.2.0): the write and the comparison below
        // share the one serialization.
        Settings snapshot = Snapshots.copy(settings);
        Path file = getSettingsFile();
        Serialized bytes = new Serialized(() -> serialize(snapshot, file));
        if (!writeProtected.contains(file)) {
            enqueueStore(file, bytes::get, ConfigSchema.SETTINGS);
        }
        // Who made the change is decided now, while the windows' pending saves still say it; the comparison
        // that tells whether there was one runs after the write, in order with every other save's.
        ConfigManager changer = origin == null ? null : settingsChanger.apply(origin);
        writer.run(() -> detectSettingsChange(bytes.get(), changer));
    }

    /** One serialization, made on first use on the writer thread and then reused by the write and the comparison. */
    private static final class Serialized {
        private final java.util.function.Supplier<byte[]> make;
        private byte @Nullable [] bytes;

        Serialized(java.util.function.Supplier<byte[]> make) {
            this.make = make;
        }

        synchronized byte[] get() {
            byte[] b = bytes;
            if (b == null) {
                b = make.get();
                bytes = b;
            }
            return b;
        }
    }

    /**
     * On the writer thread: tells {@link #onSettingsChanged}, on the FX thread, when {@code bytes} carry
     * preferences that differ from what every window last applied.
     */
    private void detectSettingsChange(byte[] bytes, @Nullable ConfigManager changer) {
        JsonNode applied = appliedSettings;
        if (applied == null || Arrays.equals(bytes, appliedSettingsBytes)) {
            return;
        }
        JsonNode now = applicableSettings(bytes);
        boolean changed = !now.equals(applied);
        appliedSettings = now;
        appliedSettingsBytes = bytes;
        if (changed) {
            fxThread.execute(() -> onSettingsChanged.accept(changer));
        }
    }

    /**
     * Records that every window now reflects the current preferences. Starts change detection: until the
     * first call nothing is reported, which is right while a single window exists. The comparison baseline is
     * serialized on the writer thread, in order with the saves compared against it.
     */
    public void markSettingsApplied() {
        Settings snapshot = Snapshots.copy(settings);
        writer.run(() -> {
            byte[] bytes = serialize(snapshot, getSettingsFile());
            appliedSettingsBytes = bytes;
            appliedSettings = applicableSettings(bytes);
        });
    }

    /**
     * Sets the handler told, on the FX thread, that a save carried preferences no other window has applied
     * yet. Its argument is the window the change came from (see {@link #setSettingsChanger}), or {@code null}
     * when that cannot be told.
     */
    public void setOnSettingsChanged(Consumer<@Nullable ConfigManager> handler) {
        this.onSettingsChanged = handler == null ? origin -> {} : handler;
    }

    /**
     * Sets how a save names the window a change came from: called on the FX thread at the save with the saving
     * window's config, it returns that window, another one, or {@code null} when it cannot be told. The answer
     * is what {@link #setOnSettingsChanged}'s handler is given once the comparison, made on the writer thread,
     * finds a change.
     */
    public void setSettingsChanger(java.util.function.UnaryOperator<@Nullable ConfigManager> changer) {
        this.settingsChanger = changer == null ? saver -> saver : changer;
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

    /** The live preferences' JSON: for the load, off the FX thread. */
    private byte[] settingsBytes() {
        return serialize(settings, getSettingsFile());
    }

    /**
     * Blocks until all queued config writes have landed (an export, an import, a test, or the quit), retrying any
     * that failed before. Not on the FX thread outside the quit ({@link ConfigWriter#flush} says so loudly).
     */
    public boolean flushWrites() {
        return writer.flush();
    }

    /** As {@link #flushWrites()}, by the name a test or a background task waits under. */
    public boolean awaitWrites() {
        return flushWrites();
    }

    /** The app is quitting: from here the FX thread may make the last save and wait for it. */
    public void quitting() {
        writer.quitting();
    }

    /** Stops app-wide background services after the last window has closed. */
    public boolean shutdown() {
        writer.quitting(); // the quit: the waits below are the last save, which the FX thread may make
        // The HTTP Client's saves wait on its own thread before they reach the writer: let them through first, or
        // a response that landed just before quitting (a Clear, an environment change) is never saved.
        awaitHttpHistoryWork();
        // Then: the history worker finishes the records still in flight (the save made from the quit prompt)
        // and hands them over, which queues the index — so the writer has to be running still.
        historyQuitting = true; // read on the writer thread: every publication from here writes the index whole
        historyService.shutdown();
        if (historyFileSeen) {
            // Fold the delta into the index (HistoryIndexFiles): an older build reads only index.json. Not needed
            // for the rows to be safe — the delta is as durable as the index — so it may find nothing to do.
            saveHistory();
        }
        boolean durable = writer.shutdown(); // retries, a last time, every write that failed earlier
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
     * Zips the active config directory into a timestamped {@code .zip} in {@link #exportDirectory()} (the
     * user's home directory) and returns the created file. Backs up whichever config dir is in use.
     */
    public Path exportConfig() throws IOException {
        return exportConfig(exportDirectory());
    }

    /** The folder an export is written to: the user's home directory unless {@link #setExportDirectory} moved it. */
    public Path exportDirectory() {
        Path dir = exportDirectory;
        return dir != null ? dir : Path.of(System.getProperty("user.home"));
    }

    /**
     * Sends later exports to {@code dir} (which must exist) instead of the home directory; {@code null} restores
     * the default. A test window points this at its own temporary folder, so a test that runs the Export
     * Configuration command never drops a zip into the real home directory.
     */
    public void setExportDirectory(Path dir) {
        exportDirectory = dir;
    }

    Path exportConfig(Path destinationDir) throws IOException {
        return exportConfig(destinationDir, false);
    }

    Path exportConfig(Path destinationDir, boolean includeHttpHistory) throws IOException {
        return exportConfig(destinationDir, includeHttpHistory, true);
    }

    /** The AI API keys are never in it: they are in no settings file, and the {@code keys/} folder is left out. */
    Path exportConfig(Path destinationDir, boolean includeHttpHistory, boolean includePluginData) throws IOException {
        if (includeHttpHistory) {
            awaitHttpHistoryWork(); // the response that just landed belongs in the export too
        }
        if (!writer.flush()) {
            throw new IOException("Timed out waiting for pending configuration writes");
        }
        return ConfigExporter.export(
                configDir,
                destinationDir,
                com.editora.AppInfo.VERSION,
                System.getProperty("user.name"),
                java.time.LocalDateTime.now(),
                includeHttpHistory,
                includePluginData);
    }

    /** Whether a plugin keeps data of its own ({@code plugins/<id>/data/}), which an export leaves out unless asked. */
    public boolean hasPluginData() {
        com.editora.perf.BlockingSites.enter("SharedConfig.hasPluginData");
        Path plugins = getPluginsDir();
        try (Stream<Path> dirs = Files.list(plugins)) {
            return dirs.filter(d -> !d.getFileName().toString().startsWith("."))
                    .filter(d -> !com.editora.plugin.PluginInstaller.isReservedId(
                            d.getFileName().toString()))
                    .anyMatch(d -> Files.isDirectory(d.resolve("data")));
        } catch (IOException | RuntimeException none) {
            return false;
        }
    }

    /** The AI keys' vault once {@link #useSecretStore} ran; null holds them in the settings fields, for the session. */
    private volatile @Nullable ApiKeyVault apiKeys;

    /**
     * Keeps the AI providers' API keys in {@code store} (the system keychain) or, when it does not answer, in the
     * encrypted file {@code keys/secrets.enc}, moving any key {@code settings.json} still holds (see
     * {@link ApiKeyVault#open}) and blanking it in the file's older copies beside it. Blocking — the launch calls it
     * off the FX thread. Tests pass a fake keychain, so they never reach a real one.
     */
    public void useSecretStore(com.editora.secrets.SecretStore store) {
        ApiKeyVault vault = ApiKeyVault.open(store, new com.editora.secrets.EncryptedFileStore(configDir), settings);
        apiKeys = vault;
        if (vault.settingsChanged()) {
            saveSettings();
            scrubSettingsCopies(configDir);
        }
    }

    /**
     * Blanks the {@code aiApiKey…} values in the settings file's older copies in {@code configDir}
     * ({@code settings.json.v104.bak}, {@code settings.toml.migrated}, …), which still hold them as the file held
     * them when the copy was made. Textual, as an export's ({@link ConfigExporter#withoutApiKeys}); a copy that
     * cannot be rewritten is said in the log and left.
     */
    static void scrubSettingsCopies(Path configDir) {
        try (Stream<Path> files = Files.list(configDir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (!ConfigExporter.isSettingsCopy(name)
                        || name.equals(ConfigManager.SETTINGS_FILE_NAME)
                        || !Files.isRegularFile(f)) {
                    continue;
                }
                try {
                    String text = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
                    String blank = ConfigExporter.withoutApiKeys(text);
                    if (!blank.equals(text)) {
                        ConfigWriter.writeAtomicChecked(f, blank.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (IOException | UncheckedIOException e) {
                    java.util.logging.Logger.getLogger(SharedConfig.class.getName())
                            .warning("Could not blank the API keys in " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // no config folder to look in
        }
    }

    /** The vault, once {@link #useSecretStore} ran. */
    public @Nullable ApiKeyVault apiKeyVault() {
        return apiKeys;
    }

    /** Waits for the HTTP Client's history saves still queued in the windows (see {@link HttpResponseHistory}). */
    private void awaitHttpHistoryWork() {
        HttpResponseHistory history = httpHistory;
        if (history != null) {
            history.awaitPendingWork();
        }
    }

    /** Whether the HTTP Client has saved responses, which an export leaves out unless the user asks. */
    public boolean hasHttpHistory() {
        com.editora.perf.BlockingSites.enter("SharedConfig.hasHttpHistory");
        Path dir = configDir.resolve(HttpResponseHistory.DIR_NAME);
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".json"));
        } catch (IOException | RuntimeException none) {
            return false;
        }
    }

    /**
     * {@link #exportConfig()} on a background thread: the export waits for pending writes and then reads and
     * compresses the whole config directory, which must not happen on the FX thread. The future completes on
     * that thread — with the zip, or exceptionally with the {@link IOException} — so a caller marshals back
     * itself. A call made while an export is running joins it rather than starting a second one (both would
     * be named for the same second).
     */
    public synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync() {
        return exportConfigAsync(false);
    }

    /** As {@link #exportConfigAsync()}, with the HTTP response history when {@code includeHttpHistory}. */
    public synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync(boolean includeHttpHistory) {
        return exportConfigAsync(exportDirectory(), includeHttpHistory, true);
    }

    /** As above, and the plugins' data folders when {@code includePluginData}. */
    public synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync(
            boolean includeHttpHistory, boolean includePluginData) {
        return exportConfigAsync(exportDirectory(), includeHttpHistory, includePluginData);
    }

    synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync(Path destinationDir) {
        return exportConfigAsync(destinationDir, false, true);
    }

    synchronized java.util.concurrent.CompletableFuture<Path> exportConfigAsync(
            Path destinationDir, boolean includeHttpHistory, boolean includePluginData) {
        if (runningExport != null && !runningExport.isDone()) {
            return runningExport;
        }
        java.util.concurrent.CompletableFuture<Path> export = new java.util.concurrent.CompletableFuture<>();
        runningExport = export;
        Thread thread = new Thread(
                () -> {
                    try {
                        export.complete(exportConfig(destinationDir, includeHttpHistory, includePluginData));
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

    public Path getReviewsFile() {
        return configDir.resolve(ConfigManager.REVIEWS_FILE_NAME);
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
     * Queues a write of one store. A file that is {@link #isWriteProtected write-protected} is left alone: the
     * in-memory store is the defaults loaded in its place, not its content.
     *
     * <p>The store is copied here ({@link Snapshots#copy}); the copy is serialized and written on the writer
     * thread (PERF-01, Pauta 6.2.0), so the FX thread, where these saves are made (toggle a bookmark or a
     * breakpoint, edit a note, save a macro, trust a folder), neither serializes, hashes, locks nor writes. The
     * in-memory store stays the one the windows read. Writes of one file are made in order, and a later save of
     * it replaces one still queued, so an older state is never written over a newer one.
     *
     * <p>The write goes through {@link StoreSync}: nothing is written when this process changed nothing, and
     * when another process wrote the file in the meantime its entries are kept and only this process's own
     * additions, edits and removals are applied. What that merge keeps of the other process's stays on disk; it
     * is not loaded into this process's memory (it shows after a restart), so nothing comes back to the FX
     * thread.
     *
     * <p>Never throws. A failure is logged and reported through {@link #setOnWriteError}; the change stays in
     * memory and the writer writes the same snapshot again after its next successful write, at every flush and
     * at quit (unless a newer save of the store replaced it). A caller that is not the FX thread waits for the
     * write ({@link #settleUnlessFx}).
     */
    private void writeStore(Path file, Object store, ConfigSchema schema) {
        if (writeProtected.contains(file)) {
            return;
        }
        Object snapshot = Snapshots.copy(store);
        enqueueStore(file, () -> serialize(snapshot, file), schema);
        settleUnlessFx();
    }

    /** Whether a store's last write failed and is still waiting to be retried. */
    boolean hasUnsavedStores() {
        return writer.hasFailedWrites();
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

    /** The HTTP Client's per-{@code .http}-file history (environment + last responses), shared by every window. */
    public HttpResponseHistory httpHistory() {
        if (httpHistory == null) {
            // A history is read when its file is opened, long after startup: the window that read it reports
            // what could not be read (Stored.problems), so it is only write-protected here, not queued for the
            // next window to show.
            httpHistory = new HttpResponseHistory(
                    configDir, sinkFor(ConfigSchema.HTTP_HISTORY), this::protectIfMustNotOverwrite);
        }
        return httpHistory;
    }

    /**
     * The AI Agent chat-session history, shared like {@link #recentFiles()}. Reads it on the calling thread the
     * first time: not for the FX thread, which goes through {@link #agentSessions(Consumer)}.
     */
    public synchronized AgentSessionHistory agentSessions() {
        AgentSessionHistory history = agentSessions;
        if (history == null) {
            history = new AgentSessionHistory(configDir, sinkFor(ConfigSchema.AGENT_SESSIONS), this::onLoadProblem);
            agentSessions = history;
        }
        return history;
    }

    /** The agent session history once read, else {@code null} (its read may be under way). */
    public @Nullable AgentSessionHistory agentSessionsIfRead() {
        return agentSessions;
    }

    /** The read of the agent session history started by {@link #readAgentSessionsAhead}; guarded by {@code this}. */
    private java.util.concurrent.@Nullable CompletableFuture<AgentSessionHistory> agentSessionsRead;

    /** What waits on the FX thread for that read, in the order it asked. FX thread only. */
    private final List<Consumer<AgentSessionHistory>> waitingForAgentSessions = new ArrayList<>();

    /** Starts reading the agent session history on a thread of its own, unless it is read or being read. */
    public synchronized java.util.concurrent.CompletableFuture<AgentSessionHistory> readAgentSessionsAhead() {
        java.util.concurrent.CompletableFuture<AgentSessionHistory> read = agentSessionsRead;
        if (read == null) {
            read = agentSessions != null
                    ? java.util.concurrent.CompletableFuture.completedFuture(agentSessions)
                    : java.util.concurrent.CompletableFuture.supplyAsync(
                            this::agentSessions, com.editora.process.BlockingTasks.named("agent-sessions-read"));
            agentSessionsRead = read;
        }
        return read;
    }

    /**
     * Hands the agent session history to {@code then} on the FX thread: at once when it is read, else once its
     * first read, on a thread of its own, is done (PERF-01, Pauta 6.2.0). Calls made meanwhile run in the order
     * they were made.
     */
    public void agentSessions(Consumer<AgentSessionHistory> then) {
        AgentSessionHistory history = agentSessions;
        if (history != null && waitingForAgentSessions.isEmpty()) {
            then.accept(history);
            return;
        }
        waitingForAgentSessions.add(then);
        if (waitingForAgentSessions.size() == 1) {
            readAgentSessionsAhead()
                    .whenComplete((read, failure) -> fxThread.execute(() -> {
                        List<Consumer<AgentSessionHistory>> waiting = new ArrayList<>(waitingForAgentSessions);
                        waitingForAgentSessions.clear();
                        AgentSessionHistory got = read != null ? read : agentSessions();
                        waiting.forEach(w -> w.accept(got));
                    }));
        }
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
        writeStore(getPluginsFile(), pluginStore, ConfigSchema.PLUGINS);
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
        writeStore(getTrustFile(), trustStore, ConfigSchema.TRUST);
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
        writeStore(getMacrosFile(), macroStore, ConfigSchema.MACROS);
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
        reloadAbbreviations(() -> {});
    }

    /**
     * As {@link #reloadAbbreviations()}: the file is read on the config writer's thread, after the writes queued
     * before it (PERF-01, Pauta 6.2.0); the list is replaced, the windows told and {@code then} run on the FX
     * thread. A change made to the list on the FX thread meanwhile is overwritten, as the file's is the newer.
     */
    public void reloadAbbreviations(Runnable then) {
        writer.run(() -> {
            AbbrevStore read = read(getAbbreviationsFile(), new AbbrevStore(), ConfigSchema.ABBREVIATIONS);
            fxThread.execute(() -> {
                abbrevStore = read;
                onStoreChanged.run();
                then.run();
            });
        });
        settleUnlessFx();
    }

    public void saveAbbreviations() {
        writeStore(getAbbreviationsFile(), abbrevStore, ConfigSchema.ABBREVIATIONS);
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

    /** Whether the user has agreed that commands may be run on the host of connection {@code id}. */
    public boolean remoteExecAllowed(String id) {
        return connectionStore.execAllowed(id);
    }

    /** Records that agreement, and persists it. */
    public void allowRemoteExec(String id) {
        connectionStore.allowExec(id);
        saveConnections();
    }

    /** The command configured for {@code tool} on the host of connection {@code id}, or null (its PATH). */
    public @Nullable String remoteTool(String id, String tool) {
        return connectionStore.tool(id, tool);
    }

    /** Grants or withdraws the agreement to run commands on the host of connection {@code id}, and persists it. */
    public void setRemoteExecAllowed(String id, boolean allowed) {
        connectionStore.setExecAllowed(id, allowed);
        saveConnections();
    }

    /** The tool commands configured for the host of connection {@code id} (tool id → command). */
    public Map<String, String> remoteTools(String id) {
        return connectionStore.tools(id);
    }

    /** Replaces the tool commands of the host of connection {@code id}, and persists them. */
    public void setRemoteTools(String id, Map<String, String> tools) {
        connectionStore.setTools(id, tools);
        saveConnections();
    }

    /** Carries a host's execution settings over to the connection's new id (not persisted: the caller saves). */
    public void renameRemoteHost(String from, String to) {
        connectionStore.renameExec(from, to);
    }

    public void saveConnections() {
        writeStore(getConnectionsFile(), connectionStore, ConfigSchema.CONNECTIONS);
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

    /** The review threads of the repository {@code repoKey} (see {@code com.editora.review.ReviewKeys}). */
    public List<ReviewThread> reviewThreads(String repoKey) {
        return reviewStore.threads(repoKey);
    }

    /** Replaces the review threads of {@code repoKey} and writes {@code reviews.json}. */
    public void setReviewThreads(String repoKey, List<ReviewThread> threads) {
        reviewStore.setThreads(repoKey, threads);
        saveReviews();
    }

    /** Every repository's review threads, by repository key. */
    public Map<String, List<ReviewThread>> allReviewThreads() {
        return java.util.Collections.unmodifiableMap(reviewStore.getByRepo());
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

    /** A project's method breakpoints and field watchpoints (live list; persist with {@link #saveBreakpoints}). */
    public List<MemberBreakpoint> memberBreakpoints(String key) {
        return breakpointStore.members(key);
    }

    /** A project's exception breakpoints (live list; persist with {@link #saveBreakpoints}). */
    public List<ExceptionBreakpoint> exceptionBreakpoints(String key) {
        return breakpointStore.exceptions(key);
    }

    /** A project's pinned values by file (live map; persist with {@link #saveBreakpoints}). */
    public Map<String, List<PinnedLine>> pinsBucket(String key) {
        return breakpointStore.pinsBucket(key);
    }

    public void deleteBreakpointsForProject(String projectKey) {
        String key = projectKey == null ? "" : projectKey;
        boolean lines = breakpointStore.getByProject().remove(key) != null;
        lines |= breakpointStore.getPins().remove(key) != null;
        lines |= breakpointStore.getExceptionBreakpoints().remove(key) != null;
        if (breakpointStore.getMemberBreakpoints().remove(key) != null || lines) {
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

    /**
     * Adds a word to the user dictionary (lower-cased). The shared set changes at once, so the word stops being
     * flagged at once; {@code dictionary.txt} is written on the writer thread (PERF-01, Pauta 6.2.0), the word
     * appended to it.
     */
    public void addUserWord(String word) {
        if (word == null || word.isBlank()) {
            return;
        }
        String w = dictionaryForm(word);
        if (w.isEmpty() || !userDictionary.add(w)) {
            return; // already present
        }
        queueDictionaryWrite();
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

    /** Removes a word from the user dictionary; {@code dictionary.txt} is rewritten without it. No-op if absent. */
    public void removeUserWord(String word) {
        if (word == null) {
            return;
        }
        String w = dictionaryForm(word);
        if (userDictionary.remove(w)) {
            queueDictionaryWrite();
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
     * Words the writer took from the file (another process's, a hand edit's) that the shared set may not hold yet:
     * their result has not been applied on the FX thread. Their absence from a later copy of the set is not a
     * removal made here. Owned by the writer thread; emptied of what a result brought once it is applied.
     */
    private final java.util.Set<String> userDictionaryForeign = new java.util.LinkedHashSet<>();

    /**
     * Counts the changes made to the shared word set on the FX thread. A result worked out on the writer thread
     * from the set as it was at change {@code n} is applied only while the count is still {@code n}: a newer
     * change has its own write queued, which brings its own result.
     */
    private long userDictionaryChanges;

    /** Told, on the FX thread, when a write or a re-read changed the shared word set (another process's words). */
    private Runnable onUserDictionaryChanged = () -> {};

    /** Sets what runs on the FX thread when the shared word set changed under the windows (see above). */
    public void setOnUserDictionaryChanged(Runnable handler) {
        this.onUserDictionaryChanged = handler == null ? () -> {} : handler;
    }

    /**
     * Queues the write that brings {@code dictionary.txt} in line with the shared set as it is now. A caller that
     * is not the FX thread waits for it.
     */
    private void queueDictionaryWrite() {
        long change = ++userDictionaryChanges;
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(userDictionary);
        Path file = getUserDictionaryFile();
        writer.enqueue(file, () -> {
            java.util.Set<String> merged = writeUserDictionary(file, wanted);
            if (!merged.equals(wanted)) {
                fxThread.execute(() -> applyUserDictionary(change, merged, null));
            }
            return null; // written here, by append or rewrite
        });
        settleUnlessFx();
    }

    /**
     * Re-reads {@code dictionary.txt} into the shared set, on the writer thread, and hands {@code done} (on the
     * FX thread) whether the set changed. For a file edited by hand (the Settings link opens it in the editor),
     * synced or imported: the words used to be read once at startup, so an added line was not accepted, and a
     * removed one stayed accepted, until the next launch. A caller that is not the FX thread waits for the read.
     */
    public void reloadUserDictionary(Consumer<Boolean> done) {
        long change = userDictionaryChanges;
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(userDictionary);
        Path file = getUserDictionaryFile();
        writer.run(() -> {
            java.util.Set<String> onDisk;
            try {
                com.editora.perf.BlockingSites.enter("dictionary read");
                onDisk = readUserDictionary(file);
            } catch (IOException e) {
                fxThread.execute(() -> done.accept(false)); // unreadable right now: keep what is in memory
                return;
            }
            // What this process added or removed and has not managed to write yet still applies on top.
            java.util.Set<String> merged = new java.util.LinkedHashSet<>(onDisk);
            for (String known : userDictionaryBase) {
                if (!wanted.contains(known) && !userDictionaryForeign.contains(known)) {
                    merged.remove(known);
                }
            }
            for (String word : wanted) {
                if (!userDictionaryBase.contains(word)) {
                    merged.add(word);
                }
            }
            userDictionaryBase.clear();
            userDictionaryBase.addAll(onDisk);
            noteForeign(merged, wanted);
            fxThread.execute(() -> applyUserDictionary(change, merged, done));
        });
        settleUnlessFx();
    }

    /**
     * On the FX thread: makes the shared set {@code merged}, worked out from the set as it was at change
     * {@code change}, unless the set has changed again since (that change's write brings a newer result).
     */
    private void applyUserDictionary(long change, java.util.Set<String> merged, @Nullable Consumer<Boolean> done) {
        boolean current = change == userDictionaryChanges;
        boolean changed = current && !merged.equals(userDictionary);
        if (changed) {
            // In place: every buffer's checker holds this very set.
            userDictionary.retainAll(merged);
            userDictionary.addAll(merged);
        }
        if (current) {
            // The set holds them now: from here on their absence from a copy is a removal made here.
            writer.run(() -> userDictionaryForeign.removeAll(merged));
        }
        if (done != null) {
            done.accept(changed);
        } else if (changed) {
            onUserDictionaryChanged.run();
        }
    }

    /**
     * On the writer thread: brings {@code file} in line with this process's additions and removals since it last
     * read or wrote the file ({@link #userDictionaryBase}), keeping every word another process (or a hand edit)
     * added to the file in the meantime — the file used to be rewritten from this process's set alone, which
     * dropped them. Words only added are appended, which leaves the rest of the file as it is; a removal rewrites
     * it via temp + atomic move (a plain {@code writeString} truncates first, so a crash between truncate and
     * write left the whole personal dictionary empty). Returns the words the file now holds.
     *
     * @throws IOException when it could not be written: the writer reports it and tries again later
     */
    private java.util.Set<String> writeUserDictionary(Path file, java.util.Set<String> wanted) throws IOException {
        com.editora.perf.BlockingSites.enter("dictionary write");
        java.util.Set<String> onDisk = readUserDictionary(file);
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(onDisk);
        boolean removes = false;
        for (String removedHere : userDictionaryBase) {
            if (!wanted.contains(removedHere) && !userDictionaryForeign.contains(removedHere)) {
                removes |= merged.remove(removedHere);
            }
        }
        List<String> append = new ArrayList<>();
        for (String word : wanted) {
            if (!userDictionaryBase.contains(word) && merged.add(word)) {
                append.add(word); // added here (possibly by an earlier write that failed)
            }
        }
        Files.createDirectories(configDir);
        if (removes) {
            StringBuilder sb = new StringBuilder();
            for (String w : merged) {
                sb.append(w).append(System.lineSeparator());
            }
            com.editora.io.AtomicFileWrite.write(file, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else if (!append.isEmpty()) {
            // A hand-edited or synced file may not end in a line break; appending straight after it would
            // glue this word onto the last one ("beta" + "gamma" -> "betagamma", losing both).
            StringBuilder sb = new StringBuilder(endsMidLine(file) ? System.lineSeparator() : "");
            for (String w : append) {
                sb.append(w).append(System.lineSeparator());
            }
            Files.writeString(
                    file,
                    sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        userDictionaryBase.clear();
        userDictionaryBase.addAll(merged);
        noteForeign(merged, wanted);
        return merged;
    }

    /** On the writer thread: the words of {@code merged} that the copy {@code wanted} of the set did not hold. */
    private void noteForeign(java.util.Set<String> merged, java.util.Set<String> wanted) {
        userDictionaryForeign.clear();
        for (String word : merged) {
            if (!wanted.contains(word)) {
                userDictionaryForeign.add(word);
            }
        }
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
        historyFileSeen = Files.exists(index);
        HistoryStore loaded;
        if (lost && historyFileSeen) {
            // Zero-length beside stored revision bodies: a write the OS never flushed, not "no history yet". (Its
            // zero-byte copy lists nothing and starts no grace period; the loss marker below keeps the bodies.)
            problems.add(ConfigMigrations.unreadable(index));
            loaded = new HistoryStore();
        } else {
            loaded = ConfigMigrations.readVersioned(
                    index, json, new HistoryStore(), ConfigSchema.HISTORY, problems::add);
        }
        // Judged on the index file alone (below): a delta lists only what changed since it was written.
        boolean listedNothingOnDisk = loaded.ledger().listsNothing();
        // Then what changed since the index was written whole (HistoryIndexFiles), as it was read: what the files
        // hold together is the common ancestor of the next merge. A delta that cannot be read now stops blob
        // collection until a publication reads it; a damaged one was kept as a copy, which keeps its bodies.
        HistoryIndexFiles.LoadedDelta delta = historyFiles.loaded(loaded.snapshot());
        if (delta.delta() != null) {
            applyHistoryDelta(loaded, delta.delta());
        }
        if (delta.damagedCopy() != null) {
            problems.add(new ConfigLoadProblem(
                    HistoryIndexFiles.deltaFile(index),
                    ConfigLoadProblem.Kind.UNREADABLE,
                    List.of(),
                    delta.damagedCopy()));
        }
        historyFiles.loadedIndex(loaded.snapshot(), delta.delta());
        // A list merged from two writers is not newest-first on disk (StoreMerge keeps this process's rows
        // first); everything that reads one takes row 0 for the newest. Null rows and lists — legal JSON, a
        // hand edit or damage — were dropped as the store took the lists in. What these change is a change
        // this load made, and the next save writes it.
        long now = System.currentTimeMillis();
        loaded.clampFutureTimestamps(now);
        loaded.sortNewestFirst();
        historyStore = loaded;
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
        // session and every later one. When no copy could be kept, the loss is marked instead and every body
        // is kept for the grace period of a torn copy; when a copy cannot be read for its hashes, nothing is
        // collected until it can.
        // An index that was read with a non-UTF-8 byte replaced still lists every revision, so it does not
        // count; nor does one that was just restored, or a set-aside copy (the backup hashes cover that one).
        boolean everyLossHasACopy = problems.stream()
                .allMatch(p -> p.kind() == ConfigLoadProblem.Kind.NOT_UTF8
                        || p.kind() == ConfigLoadProblem.Kind.NEWER_COPY_RESTORED
                        || p.kind() == ConfigLoadProblem.Kind.NEWER_COPY_KEPT
                        || p.backup() != null);
        // An index that lists no revision at all while bodies are stored is not trusted either, however it
        // came to be that way ("{}", an index rewritten from defaults, the empty one a failed load leaves):
        // collecting against it deletes every body.
        boolean emptyBesideBodies = listedNothingOnDisk && HistoryIndexGuard.hasBlobs(getHistoryBlobsDir());
        historyEmptyAtLoad = emptyBesideBodies;
        // A loss with no copy of what was lost — the index missing or zero-length beside bodies, bytes that could
        // not be kept, or an index that lists nothing beside bodies with no problem reported ("{}") — keeps every
        // body for the same grace period as a torn index, from when it was noticed (see
        // HistoryIndexGuard.LOSS_MARKER). Before, those protected the bodies for one session only.
        boolean loss = lost || !everyLossHasACopy || (emptyBesideBodies && problems.isEmpty());
        HistoryIndexGuard.dropExpiredLossMarker(index, now);
        historyLossUnmarked = loss && !HistoryIndexGuard.markLoss(index, now);
        historyBackupsUnreadable = !readHistoryBackups(index);
        foreignHistoryHashes = Set.of();
        foreignIndexHashes = Set.of();
        foreignDeltaHashes = Set.of();
        HistoryStore.Limits limits = loaded.getAcknowledgedLimits();
        historyService.restoreAcknowledged(
                limits == null
                        ? null
                        : new HistoryRetention.RetentionPolicy(
                                limits.maxPerFile(), limits.maxAgeMillis(), limits.maxTotalBytesPerProject()));
    }

    /** Applies a delta read at load to the index just read, through the store's own maps (its books stay right). */
    private static void applyHistoryDelta(HistoryStore store, HistoryDelta delta) {
        java.util.Set<String> projects =
                new java.util.LinkedHashSet<>(delta.removed().keySet());
        projects.addAll(delta.added().keySet());
        for (String project : projects) {
            Map<String, List<HistoryRevision>> plus = delta.added().getOrDefault(project, Map.of());
            Map<String, List<HistoryRevision>> minus = delta.removed().getOrDefault(project, Map.of());
            java.util.Set<String> files = new java.util.LinkedHashSet<>(minus.keySet());
            files.addAll(plus.keySet());
            Map<String, List<HistoryRevision>> bucket = store.bucket(project);
            for (String file : files) {
                List<HistoryRevision> present = bucket.get(file);
                List<HistoryRevision> rows = HistoryDelta.applyToFile(
                        present == null ? List.of() : present,
                        plus.getOrDefault(file, List.of()),
                        minus.getOrDefault(file, List.of()));
                if (rows.isEmpty()) {
                    if (present != null && !present.isEmpty()) {
                        bucket.remove(file);
                    }
                } else if (rows != present) {
                    bucket.put(file, rows);
                }
            }
        }
        if (delta.acknowledgedLimits() != null) {
            store.setAcknowledgedLimits(delta.acknowledgedLimits());
        }
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
        historyBackupsUnreadable = !readHistoryBackups(index);
    }

    /**
     * Whether the Local History index file is known to exist: it did when the history was loaded, or this
     * process has queued a write of it since. Kept so {@link #historyLimitsAcknowledged} asks no disk on the FX
     * thread (a window's set-up reaches it).
     */
    private volatile boolean historyFileSeen;

    /** The limits in force changed: keep them with the index, for the next start. */
    private void historyLimitsAcknowledged(HistoryRetention.RetentionPolicy policy) {
        HistoryStore.Limits limits =
                new HistoryStore.Limits(policy.maxPerFile(), policy.maxAgeMillis(), policy.maxTotalBytesPerProject());
        HistoryStore store = historyStore;
        if (!limits.equals(store.getAcknowledgedLimits())) {
            store.setAcknowledgedLimits(limits);
            // With no history at all there is nothing a stricter limit could delete, and no reason to create
            // an index for the sake of this note: it is written with the first revision.
            if (!store.ledger().listsNothing() || historyFileSeen) {
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
    public void saveHistory(@Nullable Consumer<Boolean> completion) {
        historyFileSeen = true;
        HistoryStore store = historyStore;
        HistoryStore.Snapshot snapshot = store.snapshot();
        long publication = store.ledger().publish();
        // Whether this publication looked at every row on disk (it wrote the index whole): only then is what
        // this process knows of the other writers' revisions current. Written and read on the writer thread.
        boolean[] sawDisk = new boolean[1];
        writer.enqueue(
                getHistoryFile(),
                () -> {
                    // A blob collection runs only behind a publication that read both files, which a whole
                    // write does: one that is due asks for it. So does the quit, for older builds.
                    boolean compact = historyQuitting || historyService.gcDue();
                    HistoryIndexFiles.Published done = historyFiles.publish(snapshot, compact);
                    // Another process's revisions are kept on disk but are not in this process's memory, so
                    // their bodies are protected from this process's collection by hash.
                    if (done.foreignInIndex() != null) {
                        foreignIndexHashes = done.foreignInIndex();
                        foreignDeltaHashes = Set.of(); // that merge covered the delta too
                    } else if (done.written()) {
                        foreignDeltaHashes = done.foreignInDelta();
                    }
                    Set<String> foreign = new java.util.HashSet<>(foreignIndexHashes);
                    foreign.addAll(foreignDeltaHashes);
                    foreignHistoryHashes = Set.copyOf(foreign);
                    adoptForeignRows(store, done.foreignRows());
                    if (done.written()) {
                        refreshHistoryBackups();
                    }
                    sawDisk[0] = done.sawDisk();
                    return done.written() ? null : ConfigWriter.UNCHANGED;
                },
                outcome -> finishHistoryPublication(store, publication, outcome, sawDisk, completion));
    }

    /**
     * Takes the rows another writer added to the index on disk into this process's index, on the FX thread that
     * owns it. Left out, every later save had to read and merge the files again, for the rest of the session:
     * this process's copy never again equalled them. Taken in, the next save writes plainly — and the other
     * writer's revisions are listed here too, which is what the user expects of one shared history. (A row this
     * build cannot read is not among them: it stays on disk, protected by hash.)
     */
    private void adoptForeignRows(HistoryStore store, Map<String, Map<String, List<HistoryRevision>>> foreign) {
        if (!foreign.isEmpty()) {
            fxThread.execute(() -> {
                if (store == historyStore) {
                    store.adopt(foreign);
                }
            });
        }
    }

    private void finishHistoryPublication(
            HistoryStore store,
            long publication,
            ConfigWriter.WriteOutcome outcome,
            boolean[] sawDisk,
            @Nullable Consumer<Boolean> completion) {
        if (outcome == ConfigWriter.WriteOutcome.SUPERSEDED) {
            // Not written by itself: the snapshot that replaced it holds its rows and answers for it.
            if (completion != null) {
                Boolean answered = historyAnswers.superseded(publication, completion);
                if (answered != null) {
                    completion.accept(answered);
                }
            }
            return;
        }
        boolean durable = outcome == ConfigWriter.WriteOutcome.WRITTEN;
        List<Consumer<Boolean>> superseded;
        synchronized (historyPublicationLock) {
            superseded = historyAnswers.finished(publication, durable);
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
        superseded.forEach(answer -> answer.accept(durable));
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
        writeStore(getBookmarksFile(), bookmarkStore, ConfigSchema.BOOKMARKS);
    }

    /** Writes the breakpoints to {@code breakpoints.json}, independently of a session save. */
    public void saveBreakpoints() {
        writeStore(getBreakpointsFile(), breakpointStore, ConfigSchema.BREAKPOINTS);
    }

    /** Loads {@code notes.json} (versioned, per-project buckets). Missing/malformed ⇒ an empty store. */
    private void loadNotes() {
        noteStore = read(getNotesFile(), new NoteStore(), ConfigSchema.NOTES);
    }

    /** Writes the global Personal Notes to {@code notes.json}, independently of a session save. */
    public void saveNotes() {
        writeStore(getNotesFile(), noteStore, ConfigSchema.NOTES);
    }

    /** Loads {@code reviews.json} (versioned, per-repository). Missing/malformed ⇒ an empty store. */
    private void loadReviews() {
        reviewStore = read(getReviewsFile(), new ReviewStore(), ConfigSchema.REVIEWS);
    }

    /** Writes the review threads to {@code reviews.json}. */
    public void saveReviews() {
        writeStore(getReviewsFile(), reviewStore, ConfigSchema.REVIEWS);
    }
}
