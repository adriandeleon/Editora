package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;
import com.editora.text.LspDiagnostic;
import com.editora.text.OccurrenceSpan;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

/**
 * UI-facing facade over the LSP layer (mirrors {@code MermaidService}): owns one
 * {@link LanguageServerSession} per workspace root, routes document open/change/save/close from the
 * editor, runs requests (completion/hover/definition/references), and pushes mapped diagnostics back on
 * the JavaFX thread. The controller supplies the workspace root (it knows the active project) and a
 * diagnostics callback. All public methods are safe to call from the FX thread; server I/O happens on
 * LSP4J's threads and results are marshaled via {@link Platform#runLater}.
 */
public final class LspManager {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(LspManager.class.getName());

    /**
     * A resolved navigation target (definition/reference): a file + 0-based line/character. For a definition
     * inside a JDK/dependency class — which jdtls reports under a {@code jdt://contents/...} URI, not a
     * {@code file:} one — {@code file} is null and {@code classFileUri} carries the URI; the coordinator
     * fetches its source via {@link #classFileContents} instead of opening a path (#665).
     */
    public record Target(
            @Nullable Path file,
            int line,
            int character,
            @Nullable String classFileUri) {
        public Target(Path file, int line, int character) {
            this(file, line, character, null);
        }
    }

    /** The scheme jdtls uses for class-file (JDK/dependency) contents — a URI no filesystem can open. */
    private static final String JDT_SCHEME = "jdt://";

    private final BiConsumer<Path, List<LspDiagnostic>> onDiagnostics;
    /** Server status: {@code accept(type, message)} (e.g. "ServiceReady"/"Ready"), posted to the FX thread. */
    private final BiConsumer<String, String> onStatus;
    /** Fired (on the FX thread) when a session died on its own — crash or failed handshake, never a
     *  deliberate dispose/shutdown — so the coordinator can clear its stale diagnostics and auto-restart
     *  it for the affected open buffers (#666). {@code accept(serverId, root)}. */
    private volatile BiConsumer<String, Path> onSessionCrashed = (id, root) -> {};

    /**
     * Told (FX thread) that a server wants data re-requested. {@code origin} identifies the asking session:
     * only its documents are affected, and {@link #servedBy} says which those are.
     */
    public interface RefreshHandler {
        void refreshRequested(String kind, Object origin);
    }

    private volatile RefreshHandler onRefreshRequested = (kind, origin) -> {};

    /** One unanswered request per document and kind; see {@link LatestRequests}. */
    private final LatestRequests latest = new LatestRequests();

    private final ExecutorService detectExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsp-detect");
        t.setDaemon(true);
        return t;
    });

    // Server processes are forked here, off the FX thread (#407): a JVM/Node fork+exec + PATH scan + ledger write
    // would otherwise stall the UI on the first open of a language. A (small) daemon pool so several servers can
    // start concurrently rather than one blocking the next.
    private final ExecutorService startExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-start");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, LanguageServerSession> sessionsByRoot = new ConcurrentHashMap<>();
    private final Map<String, LanguageServerSession> sessionByDocUri = new ConcurrentHashMap<>();

    /**
     * The Java servers of every window in this process, by session key (Java review C12). A second window on a
     * project joins the server already running for it instead of starting another jdtls on a second workspace,
     * which cost another 1.5 GB and a cold import. Tests get a registry of their own ({@link
     * #setSessionStarterForTest}), so no test joins another's server unless it asks to.
     */
    private static final Map<String, LanguageServerSession> SHARED_SERVERS = new ConcurrentHashMap<>();

    /** Every window's manager, so an install can stop a server in all of them (third review CT6). */
    private static final java.util.Set<LspManager> MANAGERS =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** Servers whose install is being replaced: no window starts one until it is done. */
    private static final java.util.Set<String> HELD_FOR_INSTALL = ConcurrentHashMap.newKeySet();

    /** Stops {@code serverId} in every window and holds new starts until {@link #releaseAfterInstall}. FX thread. */
    public static void holdForInstall(String serverId) {
        HELD_FOR_INSTALL.add(serverId);
        java.util.List<LspManager> all;
        synchronized (MANAGERS) {
            all = java.util.List.copyOf(MANAGERS);
        }
        all.forEach(m -> {
            m.shutdownServer(serverId);
            m.onInstallHold.accept(serverId, true);
        });
    }

    /**
     * Lets {@code serverId} start again after its install, and tells every window, whose documents on it were left
     * with no server: only the window that ran the install re-applied its tool support (fourth review CF1).
     */
    public static void releaseAfterInstall(String serverId) {
        HELD_FOR_INSTALL.remove(serverId);
        java.util.List<LspManager> all;
        synchronized (MANAGERS) {
            all = java.util.List.copyOf(MANAGERS);
        }
        all.forEach(m -> m.onInstallHold.accept(serverId, false));
    }

    private volatile Map<String, LanguageServerSession> sharedServers = SHARED_SERVERS;

    /** This window's client on each session it uses: the primary one on a session it started. */
    private final Map<LanguageServerSession, LanguageServerSession.Client> clientOf = new ConcurrentHashMap<>();

    /** Sessions this window stopped, so the drop that follows is not taken for another window's doing. */
    private final java.util.Set<LanguageServerSession> endedHere = ConcurrentHashMap.newKeySet();

    /** Whether this window has the focus: a question a shared server asks goes to the focused window. */
    private volatile java.util.function.BooleanSupplier windowFocused = () -> false;

    public void setWindowFocused(java.util.function.BooleanSupplier focused) {
        windowFocused = focused == null ? () -> false : focused;
    }

    /** Told (on the FX thread) when another window restarted a server this window shared: its documents reopen. */
    private volatile java.util.function.BiConsumer<String, Path> onSharedServerEnded = (serverId, root) -> {};

    public void setOnSharedServerEnded(java.util.function.BiConsumer<String, Path> handler) {
        onSharedServerEnded = handler == null ? (serverId, root) -> {} : handler;
    }

    /** Told (on the FX thread) when an install stops a server in every window ({@code true}) and when it is done. */
    private volatile java.util.function.BiConsumer<String, Boolean> onInstallHold = (serverId, held) -> {};

    public void setOnInstallHold(java.util.function.BiConsumer<String, Boolean> handler) {
        onInstallHold = handler == null ? (serverId, held) -> {} : handler;
    }

    /** Lets {@code other} share this manager's servers, as two windows do (tests only). */
    void shareServersForTest(LspManager other) {
        other.sharedServers = sharedServers;
    }

    private volatile boolean enabled;
    /** Set by {@link #close()}: the owning window is gone, so nothing may start a server here again. */
    private volatile boolean closed;
    /** Mirrors {@code Settings.lspOnTypeFormatting} into jdtls's {@code java.format.onType.enabled}. */
    private volatile boolean javaOnTypeFormatting;
    /** Settings' Default JDK ("" = none): jdtls's default runtime and the JDK it may itself run on. */
    private volatile String javaDefaultJdk = "";
    /** bash-language-server's {@code bashIde} settings (null = its defaults until the coordinator sets them). */
    private volatile @Nullable BashIdeConfig bashIde;

    /** Whether the TypeScript server may fetch type definitions from npm (Settings; off by default). */
    private volatile boolean typeScriptTypeAcquisition;

    /** The user's Node.js setting ("" = automatic), whose bin folder goes first on the TypeScript server's PATH. */
    private volatile String nodePathSetting = "";

    /** The TypeScript server's javascript/typescript settings ({@link TypeScriptServer#settings}). */
    private volatile Map<String, Object> typeScriptSettings = TypeScriptServer.settings(false, false);

    /** Open documents' indentation by wire URI, answered to the TypeScript server's formattingOptions question. */
    private final Map<String, FormattingIndent> documentIndents = new ConcurrentHashMap<>();

    /**
     * The one root every loose JavaScript/TypeScript file (no project, no tsconfig/jsconfig/package.json/.git
     * above it) shares, so ten scripts in ten folders run one server instead of ten (Node review B7). A folder
     * of Editora's own that holds none of them: the server then gives each file an inferred project of its own.
     * Null until the coordinator sets it.
     */
    private volatile @Nullable Path looseRoot;

    /** Sets what the TypeScript server is started with; a change restarts the running ones. */
    public void setTypeScriptStart(boolean typeAcquisition, String nodePath) {
        String node = nodePath == null ? "" : nodePath.strip();
        boolean changed = typeAcquisition != typeScriptTypeAcquisition || !node.equals(nodePathSetting);
        typeScriptTypeAcquisition = typeAcquisition;
        nodePathSetting = node;
        if (changed) {
            shutdownServer(TypeScriptServer.SERVER_ID);
        }
    }

    /** Sets the TypeScript server's inlay-hint and code-lens settings, pushed to the running ones. */
    public void setTypeScriptSettings(boolean inlayHints, boolean codeLens) {
        Map<String, Object> settings = TypeScriptServer.settings(inlayHints, codeLens);
        typeScriptSettings = settings;
        String prefix = sessionKeyPrefix(TypeScriptServer.SERVER_ID);
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix)) {
                session.setTypeScriptSettings(settings);
            }
        });
    }

    /** Records {@code file}'s indentation, for the server's formatting of quick fixes (call before opening it). */
    public void setDocumentIndent(Path file, FormattingIndent indent) {
        if (file != null && indent != null) {
            documentIndents.put(uri(file), indent);
        }
    }

    /** Sets the shared root of loose JavaScript/TypeScript files (see {@link #looseRoot}). */
    public void setLooseRoot(@Nullable Path root) {
        Path next = root == null ? null : root.toAbsolutePath().normalize();
        if (next != null && !next.equals(looseRoot)) {
            // Made now, not at the first start: a session key names a folder with a trailing slash only once it
            // exists, so a root created after its first key was taken would get a second session.
            try {
                Files.createDirectories(next);
            } catch (java.io.IOException e) {
                LOG.log(java.util.logging.Level.FINE, "Could not create the loose-files root " + next, e);
                next = null;
            }
        }
        looseRoot = next;
    }

    /** The shared root of loose JavaScript/TypeScript files, or null. */
    public @Nullable Path looseRoot() {
        return looseRoot;
    }
    /** jdtls {@code initializationOptions.bundles} — the java-debug plugin jar(s) that enable debugging.
     *  Applied only to the java server, at {@code initialize}; empty = no debug bundle. */
    private volatile List<String> debugBundles = List.of();
    /** serverId → resolved command (blank-substituted to the default). */
    private final Map<String, String> commands = new ConcurrentHashMap<>();
    /** serverId → last availability probe (invalidated when that server's command changes). */
    private final Map<String, Boolean> availableCache = new ConcurrentHashMap<>();
    /** Base dir for jdtls's per-project {@code -data} workspaces (see {@link #setJdtlsWorkspaceBase}). */
    private volatile @Nullable Path jdtlsWorkspaceBase;

    public LspManager(BiConsumer<Path, List<LspDiagnostic>> onDiagnostics, BiConsumer<String, String> onStatus) {
        MANAGERS.add(this);
        this.onDiagnostics = onDiagnostics == null ? (p, d) -> {} : onDiagnostics;
        this.onStatus = onStatus == null ? (t, m) -> {} : onStatus;
        // The augmented PATH (a login shell, to recover nvm/fnm/asdf dirs) is warmed by configure(): at once
        // when LSP is on, since server detection and the FX-thread session start both read it; otherwise
        // after the first painted frame, for Run/Build/Debug, so a launch with LSP off spawns no shell
        // while the user's file is still loading.
    }

    public void setOnRefreshRequested(RefreshHandler handler) {
        onRefreshRequested = handler == null ? (kind, origin) -> {} : handler;
    }

    /** Whether {@code file} is open on the session a {@link RefreshHandler} was given as {@code origin}. */
    public boolean servedBy(Path file, Object origin) {
        return origin != null && file != null && servable(file) && sessionFor(file) == origin;
    }

    /** Pure: whether a server status announces that the server is ready (jdtls: {@code ServiceReady}). */
    static boolean announcesReady(String statusType) {
        return statusType != null
                && statusType.toLowerCase(java.util.Locale.ROOT).contains("ready");
    }

    /**
     * Drops what is known about {@code file}'s unanswered requests and cached results, so the next request
     * of each kind is really sent. For when the server said its answers changed without the document
     * changing (a refresh request, a capability change, the end of indexing): an answer already on its way
     * may predate that.
     */
    public void invalidateRequests(Path file) {
        if (file != null && servable(file)) {
            String uri = uri(file);
            // Only what a server can ask to have refreshed. A signature-help or highlight request answers
            // the caret, not the server's changed state, and its caller is still waiting for it.
            for (String kind : REFRESHABLE_KINDS) {
                latest.cancel(uri, kind);
            }
            wholeDocumentTokens.remove(uri);
        }
    }

    private static final List<String> REFRESHABLE_KINDS =
            List.of("diagnostic", "documentSymbol", "foldingRange", "inlayHint", "semanticTokens");

    /** Unanswered deduplicated requests — package-private for the bounded-traffic test. */
    int latestRequestsInFlight() {
        return latest.inFlight();
    }

    /** Updates the feature flag + each server's command ({@code serverId → command}); a changed command
     *  invalidates that server's cached probe. */
    public void configure(boolean enabled, Map<String, String> serverCommands) {
        if (closed) {
            return; // a late settings apply for a window that has closed must not re-enable its servers
        }
        if (serverCommands != null) {
            serverCommands.forEach(this::putCommand);
        }
        this.enabled = enabled;
        if (enabled) {
            ProcessRunner.warmAugmentedPathAsync(); // before the first session start reads it on FX
        } else {
            closeRootWatchers(); // no server to tell: a folder watcher left running held an inotify instance
            shutdownAll();
            com.editora.perf.AfterFirstPaint.run(ProcessRunner::warmAugmentedPathAsync);
        }
    }

    /**
     * The JDK the user chose in Settings (Build Tools ▸ Default JDK, "" for none). A Java server started after
     * this marks it as its default runtime and runs Gradle on it, and runs on it itself when the launcher's own
     * Java is older than jdtls needs. A running server keeps what it started with; true when one does and the
     * value changed.
     */
    public boolean setJavaDefaultJdk(@Nullable String home) {
        String next = home == null ? "" : home;
        boolean changed = !next.equals(javaDefaultJdk);
        javaDefaultJdk = next;
        String prefix = sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID);
        // true: a running Java server keeps the old one until it restarts (re-review CN7)
        return changed && sessionsByRoot.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    /** The clean-ups jdtls's {@code java/cleanup} runs ({@code java.cleanup.actions}); every Java session gets them. */
    private volatile List<String> javaCleanups = List.of();

    /** The types jdtls never offers or imports (Settings → Java); every Java session gets them. */
    private volatile List<String> javaFilteredTypes = LspServerSettings.FILTERED_TYPES;

    public void setJavaFilteredTypes(List<String> types) {
        javaFilteredTypes = types == null ? List.of() : List.copyOf(types);
        for (LanguageServerSession session : sessionsByRoot.values()) {
            if (LspServerRegistry.JAVA_SERVER_ID.equals(session.serverId())) {
                session.setJavaFilteredTypes(javaFilteredTypes);
            }
        }
    }

    /**
     * The last error {@code serverId}'s servers logged (its first line), or null: what a failed import was really
     * about, which jdtls says only in its log (re-review CN2).
     */
    /** As {@link #lastServerError(String)}, from the session on {@code root} only. */
    public @Nullable String lastServerError(@Nullable String serverId, @Nullable Path root) {
        if (root == null) {
            return lastServerError(serverId);
        }
        LanguageServerSession s = sessionsByRoot.get(sessionKey(String.valueOf(serverId), root));
        return s == null ? null : s.lastErrorLog();
    }

    public @Nullable String lastServerError(@Nullable String serverId) {
        for (LanguageServerSession s : sessionsByRoot.values()) {
            if (s.serverId().equals(serverId) && s.lastErrorLog() != null) {
                return s.lastErrorLog();
            }
        }
        return null;
    }

    /** {@link #setJavaFilteredTypes}'s list, which library results of symbol searches honour too (EN2). */
    public List<String> javaFilteredTypes() {
        return javaFilteredTypes;
    }

    public void setJavaCleanups(List<String> cleanups) {
        javaCleanups = JavaCleanups.known(cleanups);
        for (LanguageServerSession session : sessionsByRoot.values()) {
            if (LspServerRegistry.JAVA_SERVER_ID.equals(session.serverId())) {
                session.setJavaCleanups(javaCleanups);
            }
        }
    }

    /**
     * Runs the chosen clean-ups on {@code file} ({@code java/cleanup}) and applies the edit; {@code cb} on the FX
     * thread with whether the text changed. Nothing to run, or no Java server, is {@link OrganizeOutcome#UNCHANGED}
     * / {@link OrganizeOutcome#FAILED}.
     */
    public void cleanUpJava(Path file, Consumer<OrganizeOutcome> cb) {
        LanguageServerSession s = javaSessionFor(file);
        if (s == null || file == null || sessionFor(file) != s) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.FAILED));
            return;
        }
        if (javaCleanups.isEmpty()) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.UNCHANGED));
            return;
        }
        EditBasis expected = editBasis(s);
        s.rawRequest("java/cleanup", new org.eclipse.lsp4j.TextDocumentIdentifier(serverUri(file)))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : asWorkspaceEdit(r);
                    Platform.runLater(() -> {
                        if (e != null) {
                            cb.accept(OrganizeOutcome.FAILED);
                        } else if (edit == null || !changesText(edit)) {
                            cb.accept(OrganizeOutcome.UNCHANGED);
                        } else {
                            applyWorkspaceEdit(
                                    edit,
                                    expected,
                                    ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.FAILED));
                        }
                    });
                });
    }

    /**
     * Tells jdtls whether to format as you type. jdtls registers its on-type formatting provider only while
     * {@code java.format.onType.enabled} is set, so this is sent at {@code initialize}, pushed after it, and
     * re-pushed to running Java sessions when the setting flips.
     */
    public void setJavaOnTypeFormatting(boolean enabled) {
        javaOnTypeFormatting = enabled;
        String prefix = sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID);
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix)) {
                session.setJavaOnTypeFormatting(enabled);
            }
        });
    }

    /** Whether lemminx-maven may use the Maven Central index (Settings → LSP; off = local repository only). */
    private volatile boolean mavenCentralIndex;

    /**
     * Pushes the Settings → LSP "Maven Central index" choice (Maven review C10). It is sent at {@code
     * initialize}, so a running Maven server is shut down when the choice changes; the coordinator's gating
     * reopens the pom buffers on a server started with the new options.
     */
    public void setMavenCentralIndex(boolean enabled) {
        boolean changed = mavenCentralIndex != enabled;
        mavenCentralIndex = enabled;
        if (changed) {
            shutdownServer(LspServerRegistry.MAVEN_POM_SERVER_ID);
        }
    }

    /**
     * Sets bash-language-server's {@code bashIde} configuration: answered when a Bash server asks for it, and
     * pushed to the running ones when it changed (they re-read it and re-lint).
     */
    public void setBashIde(BashIdeConfig config) {
        bashIde = config;
        String prefix = sessionKeyPrefix("bash");
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix)) {
                session.setBashIde(config);
            }
        });
    }

    /** The Python server's id, whose sessions are told which interpreter to analyse with. */
    static final String PYTHON_SERVER_ID = "python";

    /**
     * Root → the Python interpreter a Pyright session there should analyse with (an absolute executable, or
     * null to leave it to the server). Called off the FX thread (it looks at the file system and PATH).
     */
    private volatile java.util.function.Function<Path, @Nullable String> pythonInterpreterResolver = root -> null;

    /** Installs how a Python session's interpreter is found (review B1); {@code null} = the server's own. */
    public void setPythonInterpreterResolver(java.util.function.Function<Path, @Nullable String> resolver) {
        pythonInterpreterResolver = resolver == null ? root -> null : resolver;
    }

    private @Nullable String resolvePythonPath(Path root) {
        try {
            return pythonInterpreterResolver.apply(root);
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.FINE, "Python interpreter resolution failed for " + root, e);
            return null;
        }
    }

    /**
     * Re-resolves every running Python session's interpreter (the project's pick changed, a venv was
     * created) off the FX thread; a session whose answer changed is pushed the new {@code python.pythonPath}.
     */
    public void refreshPythonInterpreters() {
        List<LanguageServerSession> python = new ArrayList<>();
        for (LanguageServerSession s : sessionsByRoot.values()) {
            if (PYTHON_SERVER_ID.equals(s.serverId())) {
                python.add(s);
            }
        }
        if (python.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("lsp-python-interpreter").start(() -> {
            for (LanguageServerSession s : python) {
                if (!s.isDisposed()) {
                    s.setPythonPath(resolvePythonPath(s.root()));
                }
            }
        });
    }

    /**
     * Which local server roots get a {@link RootWatcher} of their own: those nothing else reports file changes
     * for (no project, or a root outside it). Read off the FX thread when a session starts.
     */
    private volatile java.util.function.Predicate<Path> watchRoot = root -> false;

    /** Root → its watcher; at most {@link #MAX_ROOT_WATCHERS}, closed with the manager. */
    private final Map<Path, RootWatcher> rootWatchers = new ConcurrentHashMap<>();

    static final int MAX_ROOT_WATCHERS = 8;

    /** Installs which server roots this manager watches itself (review B7). */
    public void setWatchSessionRoots(java.util.function.Predicate<Path> watch) {
        watchRoot = watch == null ? root -> false : watch;
    }

    /** Starts watching {@code root} when it should be and is not yet (any thread). */
    private void ensureRootWatcher(Path root) {
        if (closed
                || root == null
                || !com.editora.process.ExecTargets.runsHere(root)
                || RootResolver.isBroadRoot(root)) {
            return;
        }
        Path key = root.toAbsolutePath().normalize();
        if (rootWatchers.containsKey(key) || rootWatchers.size() >= MAX_ROOT_WATCHERS || !watchRoot.test(key)) {
            return;
        }
        RootWatcher w = RootWatcher.start(key, batch -> Platform.runLater(() -> notifyWatchedFiles(batch)));
        if (w != null && rootWatchers.putIfAbsent(key, w) != null) {
            w.close(); // another start won
        }
        if (closed) {
            closeRootWatchers();
        }
    }

    private void closeRootWatchers() {
        rootWatchers.values().forEach(RootWatcher::close);
        rootWatchers.clear();
    }

    /** The roots watched by this manager itself (tests). */
    java.util.Set<Path> watchedRoots() {
        return java.util.Set.copyOf(rootWatchers.keySet());
    }

    /** The workspace root of the session serving {@code file}, or null when none does. */
    public @Nullable Path sessionRoot(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? null : s.root();
    }

    /**
     * The workspace root of the jdtls session {@code file} belongs to: its own, else the deepest whose root holds
     * it, as for a {@code build.gradle} no Java server edits. Null when no jdtls serves its folder.
     */
    public @Nullable Path javaSessionRoot(@Nullable Path file) {
        LanguageServerSession s = javaSessionFor(file);
        return s == null ? null : s.root();
    }

    /** The interpreter a local Python session was given ({@code null} = none, or no such session). */
    public @Nullable String pythonPathFor(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? null : s.pythonPath();
    }

    /** The {@code bashIde} configuration last set (null = never). */
    public @Nullable BashIdeConfig bashIde() {
        return bashIde;
    }

    private void putCommand(String serverId, String command) {
        String def = LspServerRegistry.defaultCommandFor(serverId);
        String cmd = command == null || command.isBlank() ? def : command;
        String prev = commands.put(serverId, cmd);
        if (!cmd.equals(prev)) {
            availableCache.remove(serverId); // command changed → re-probe on next detect
            if (prev != null) {
                // …and the session running the OLD command must go. The session key is (serverId, root) — the
                // command isn't part of it — so without this the stale session is handed straight back:
                // isManaged() stays true so the buffer is never re-opened, the new command never runs, and the
                // old process leaks. Meanwhile the Settings row re-probes the new command and turns green, so
                // it looks like it applied.
                shutdownServer(serverId);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Clears the cached per-server availability so the next {@link #detect} re-probes — used after an
     *  in-app install drops a server onto PATH / into the config dir without changing its command. */
    public void invalidateDetection() {
        availableCache.clear();
    }

    /**
     * Base directory under which jdtls gets a dedicated per-project workspace (via {@code -data}). Without
     * this, jdtls falls back to a single shared default workspace that deadlocks on its {@code .lock} when
     * two project roots — or a leaked previous run — contend for it, so the server never finishes
     * {@code initialize} (loading bar spins forever, no completion). Injected once at startup.
     */
    public void setJdtlsWorkspaceBase(@Nullable Path base) {
        this.jdtlsWorkspaceBase = base;
    }

    /** Where Editora keeps a Lombok it downloaded ({@code <config>/plugins/lsp/lombok}); null: none is offered. */
    private volatile @Nullable Path lombokDir;

    /** Told, on the FX thread, of a Java root whose build declares Lombok when no Lombok jar can be found. */
    private volatile Consumer<Path> onLombokMissing = root -> {};

    public void setLombokDir(@Nullable Path dir) {
        this.lombokDir = dir;
    }

    public void setOnLombokMissing(Consumer<Path> handler) {
        this.onLombokMissing = handler == null ? root -> {} : handler;
    }

    /** Sets the java-debug plugin bundle jar(s) injected into the java server's {@code initialize} so
     *  jdtls can debug. Takes effect on the next java session start — call {@link #restartServer} after
     *  changing it to reload a running jdtls. */
    public void setDebugBundles(List<String> jars) {
        this.debugBundles = jars == null ? List.of() : List.copyOf(jars);
    }

    /**
     * Runs a server-side command ({@code workspace/executeCommand}) on {@code file}'s session — used by the
     * DAP layer to drive jdtls's debug commands. The result (or error) is delivered on the FX thread; a
     * 30 s timeout guards against a server that never answers.
     */
    public void executeCommand(
            @Nullable Path file, String command, List<Object> args, BiConsumer<Object, Throwable> cb) {
        executeCommand(file, command, args, java.time.Duration.ofSeconds(30), cb);
    }

    /**
     * As {@link #executeCommand(Path, String, List, BiConsumer)}, waiting up to {@code timeout}: a command that
     * compiles first (Metals' {@code debug-adapter-start} builds the target it debugs) outlasts the usual 30 s.
     */
    public void executeCommand(
            @Nullable Path file,
            String command,
            List<Object> args,
            java.time.Duration timeout,
            BiConsumer<Object, Throwable> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null, new IllegalStateException("no language server for file")));
            return;
        }
        s.executeCommand(command, args)
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> Platform.runLater(() -> cb.accept(r, e)));
    }

    /**
     * Whether {@code file}'s server is up and lists {@code command} among its {@code executeCommandProvider}
     * commands — Metals' {@code debug-adapter-start}, say.
     */
    public boolean supportsCommand(Path file, String command) {
        LanguageServerSession s = sessionFor(file);
        var caps = s == null ? null : s.capabilities();
        var provider = caps == null ? null : caps.getExecuteCommandProvider();
        return provider != null
                && provider.getCommands() != null
                && provider.getCommands().contains(command);
    }

    /** Where servers' questions are asked (FX thread); null: unanswered, as before a window installs one. */
    private volatile @Nullable LspClientUi clientUi;

    /**
     * Installs the window's answers to server questions ({@code window/showMessageRequest}, Metals' input box,
     * quick pick and client commands). Each call reaches {@code ui} on the FX thread.
     */
    public void setClientUi(LspClientUi ui) {
        this.clientUi = ui;
    }

    /** {@link #clientUi} reached from a session's reader thread: every call is posted to the FX thread. */
    private final LspClientUi fxClientUi = new LspClientUi() {
        @Override
        public java.util.concurrent.CompletableFuture<String> chooseAction(
                String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions) {
            return onFx(ui -> ui.chooseAction(serverId, type, message, actions));
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> inputBox(
                String serverId, @Nullable String prompt, @Nullable String value, @Nullable String placeHolder) {
            return onFx(ui -> ui.inputBox(serverId, prompt, value, placeHolder));
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> quickPick(
                String serverId, @Nullable String placeHolder, List<PickItem> items) {
            return onFx(ui -> ui.quickPick(serverId, placeHolder, items));
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> chooseImport(
                String serverId, String simpleName, int line, List<PickItem> items) {
            return onFx(ui -> ui.chooseImport(serverId, simpleName, line, items));
        }

        @Override
        public void openLocation(String serverId, Path file, int line, int column) {
            Platform.runLater(() -> {
                LspClientUi ui = clientUi;
                if (ui != null && !closed) {
                    ui.openLocation(serverId, file, line, column);
                }
            });
        }

        private java.util.concurrent.CompletableFuture<String> onFx(
                java.util.function.Function<LspClientUi, java.util.concurrent.CompletableFuture<String>> ask) {
            var answer = new java.util.concurrent.CompletableFuture<String>();
            Platform.runLater(() -> {
                LspClientUi ui = clientUi;
                if (ui == null || closed) {
                    answer.complete(null);
                    return;
                }
                try {
                    ask.apply(ui).whenComplete((v, e) -> answer.complete(e == null ? v : null));
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.FINE, "server question not shown", e);
                    answer.complete(null);
                }
            });
            return answer;
        }
    };

    /** Restarts {@code serverId} (dispose + drop its cached sessions) so the next {@code openDocument}
     *  re-initializes it — e.g. to reload jdtls with the java-debug bundle. The controller re-opens the
     *  affected buffers afterwards. */
    public void restartServer(String serverId) {
        shutdownServer(serverId);
    }

    /** Probes whether {@code serverId}'s configured command resolves to an executable; cached, off-thread. */
    public void detect(String serverId, Consumer<Boolean> onResult) {
        if (closed) {
            return; // the window is gone; a probe result would only try to open documents on it
        }
        Boolean hit = availableCache.get(serverId);
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        List<String> command = LspServerRegistry.commandFor(serverId, commands);
        detectExec.submit(() -> {
            boolean ok = available(serverId, command);
            availableCache.put(serverId, ok);
            Platform.runLater(() -> {
                if (!closed) {
                    onResult.accept(ok);
                }
            });
        });
    }

    /**
     * Probes whether {@code command} (blank = {@code serverId}'s default) resolves to an executable, off-thread
     * and <b>without touching this manager's configuration</b>. For a status label that describes a command
     * other than the one in force — the Settings page shows the global command while a trusted project may
     * override it; configuring the manager to probe it replaced the override and stopped the running server.
     */
    public void detectCommand(String serverId, String command, Consumer<Boolean> onResult) {
        if (closed) {
            return;
        }
        Map<String, String> one = new java.util.HashMap<>();
        one.put(serverId, command);
        List<String> argv = LspServerRegistry.commandFor(serverId, one);
        detectExec.submit(() -> {
            boolean ok = available(serverId, argv);
            Platform.runLater(() -> {
                if (!closed) {
                    onResult.accept(ok);
                }
            });
        });
    }

    /**
     * Whether {@code file} can be served: its tools run here, or where they run (a remote host, a container,
     * WSL) can run commands now — its server runs there.
     */
    private static boolean servable(Path file) {
        return com.editora.process.ExecTargets.forPath(file).isAvailable();
    }

    /**
     * The command {@code serverId} is started with on the host of {@code root}: what is configured for that
     * host ({@code lsp.<id>} in its tools), else the global setting when it is a bare command name (a path
     * names a file on this machine), else the server's default.
     */
    List<String> remoteCommand(String serverId, Path root) {
        return LspServerRegistry.remoteCommandFor(
                serverId, commands.get(serverId), com.editora.process.ExecTargets.remoteTool(root, "lsp." + serverId));
    }

    /** Per host and server: whether the server's command is on that host. */
    private final Map<String, Boolean> remoteAvailable = new ConcurrentHashMap<>();

    /** Forgets what {@link #detectRemote} found on every host: a server's command there may have changed. */
    public void forgetRemoteDetections() {
        remoteAvailable.clear();
    }

    /** What {@link #detectRemote} last found for {@code serverId} on {@code anchor}'s host, or null. */
    public @Nullable Boolean remoteAvailability(Path anchor, String serverId) {
        return remoteAvailable.get(
                com.editora.process.ExecTargets.forPath(anchor).id() + SESSION_KEY_SEP + serverId);
    }

    /**
     * Asks the host of remote {@code anchor} whether {@code serverId}'s command is there ({@code command -v}),
     * off the FX thread, and posts the answer on it. Cached per host and server; a host that could not be
     * asked is not remembered.
     */
    public void detectRemote(Path anchor, String serverId, Consumer<Boolean> onResult) {
        var target = com.editora.process.ExecTargets.forPath(anchor);
        String key = target.id() + SESSION_KEY_SEP + serverId;
        Boolean hit = remoteAvailable.get(key);
        if (closed || hit != null || !target.isAvailable()) {
            boolean answer = hit != null && hit;
            Platform.runLater(() -> onResult.accept(answer));
            return;
        }
        List<String> command = remoteCommand(serverId, anchor);
        detectExec.submit(() -> {
            boolean ok = false;
            if (!command.isEmpty()) {
                ProcessRunner.Result r = ProcessRunner.run(
                        anchor.toAbsolutePath().getRoot(),
                        java.time.Duration.ofSeconds(10),
                        List.of("sh", "-c", "command -v \"$1\" >/dev/null", "sh", command.get(0)));
                ok = r.ok();
                if (r.exit() >= 0) {
                    remoteAvailable.put(key, ok);
                }
            }
            boolean answer = ok;
            Platform.runLater(() -> {
                if (!closed) {
                    onResult.accept(answer);
                }
            });
        });
    }

    /**
     * Whether {@code serverId} can be started by {@code command}: its executable resolves, and — for the Maven
     * pom.xml server, whose executable is just {@code java} — the install folder the command names holds every
     * jar the server needs ({@link com.editora.install.MavenServerInstall}). An emptied folder, or the upstream
     * bundle without the jars it leaves out, used to read "found" while the server could not answer a request
     * (Maven review C1/C3); now such a pom.xml falls back to the XML server and Settings/Doctor say "incomplete".
     */
    static boolean available(String serverId, List<String> command) {
        if (!available(command)) {
            return false;
        }
        if (LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)) {
            return com.editora.install.MavenServerInstall.missingJars(command).isEmpty();
        }
        return true;
    }

    /** True if the command's executable resolves (an absolute path that exists, or found on the PATH). */
    static boolean available(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String exe = command.get(0);
        if (exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0) {
            return Files.isExecutable(Path.of(exe));
        }
        // resolveExecutable rewrites a bare name to an absolute path when found on the augmented PATH.
        return !ProcessRunner.resolveExecutable(command).get(0).equals(exe);
    }

    // --- Document lifecycle (called from EditorBuffer hooks via the controller) -----------------

    /**
     * Opens {@code file} on the server that serves {@code routeLanguageId}, starting that server if needed.
     * The {@code routeLanguageId} selects the server + session (e.g. {@code maven-pom} routes a pom.xml to the
     * Maven server); the id actually sent to the server in {@code didOpen} is
     * {@link LspServerRegistry#protocolLanguageId} (so a {@code maven-pom} route opens as {@code xml}).
     */
    public void openDocument(Path file, @Nullable Path root, String routeLanguageId, String text) {
        if (closed || !enabled || file == null || root == null || !LspServerRegistry.isSupported(routeLanguageId)) {
            return;
        }
        LanguageServerSession session = sessionForRoot(root, routeLanguageId);
        if (session == null) {
            return;
        }
        cancelEviction(session); // a reopened document keeps an idle session alive (#669)
        String uri = uri(file);
        sessionByDocUri.put(uri, session);
        if (session.isDisposed()) {
            // The start executor withheld or failed this session's start while this thread was between
            // sessionForRoot and the put above: dropSession ran already, so its removal missed this entry.
            sessionByDocUri.remove(uri, session);
            return;
        }
        if (JavaScriptShadow.wanted(file, routeLanguageId) && com.editora.process.ExecTargets.runsHere(file)) {
            Path shadow = javaScriptShadow(uri, file, text);
            if (shadow != null) {
                session.alias(uri, DocumentUris.of(shadow), JavaScriptShadow::wireText);
            }
        }
        session.didOpen(uri, LspServerRegistry.protocolLanguageId(routeLanguageId), text, this);
    }

    /** Where the stand-in {@code .java} files for Java scripts live (see {@link JavaScriptShadow}); lazily made. */
    private @Nullable Path javaScriptShadowRoot;

    /** Each open Java script's stand-in file, by document key. */
    private final Map<String, Path> javaScriptShadows = new ConcurrentHashMap<>();

    /**
     * Writes the stand-in jdtls is shown for the Java script {@code file} — outside the project, so nothing
     * appears beside the script — or null when it can't be written, and the script is then shown as itself.
     * jdtls reads the text from the open document; the file only has to exist and be named {@code .java}.
     */
    private @Nullable Path javaScriptShadow(String uri, Path file, String text) {
        try {
            Path shadow = javaScriptShadows.get(uri);
            if (shadow == null) {
                synchronized (javaScriptShadows) {
                    if (javaScriptShadowRoot == null || !Files.isDirectory(javaScriptShadowRoot)) {
                        // Its real path: what jdtls and java-debug report back (macOS's /var is /private/var).
                        javaScriptShadowRoot = Files.createTempDirectory("editora-java-scripts-")
                                .toRealPath();
                    }
                    // A folder each: two scripts of one name, from different folders, are two files here.
                    shadow = Files.createTempDirectory(javaScriptShadowRoot, "s")
                            .resolve(JavaScriptShadow.fileName(file));
                }
            }
            Files.writeString(shadow, JavaScriptShadow.wireText(text == null ? "" : text));
            javaScriptShadows.put(uri, shadow);
            return shadow;
        } catch (java.io.IOException | RuntimeException unwritable) {
            LOG.fine(() -> "no stand-in for " + file + ": " + unwritable);
            return null;
        }
    }

    /**
     * The Java script whose stand-in {@code file} is ({@link JavaScriptShadow}), or null when it is none. A
     * debugger's stack frame names the stand-in: java-debug finds the source of a class through jdtls.
     */
    public @Nullable Path scriptForStandIn(@Nullable Path file) {
        if (file == null || javaScriptShadows.isEmpty()) {
            return null;
        }
        for (var entry : javaScriptShadows.entrySet()) {
            if (file.normalize().equals(entry.getValue())) {
                return DocumentUris.toPath(entry.getKey());
            }
        }
        return null;
    }

    /** Deletes a stand-in made by {@link #javaScriptShadow} (best effort). */
    private static void deleteJavaScriptShadow(@Nullable Path shadow) {
        if (shadow == null) {
            return;
        }
        DocumentUris.forget(shadow);
        try {
            Files.deleteIfExists(shadow);
            Files.deleteIfExists(shadow.getParent());
        } catch (java.io.IOException | RuntimeException ignored) {
            // a temporary file: the next one gets a folder of its own anyway
        }
    }

    /**
     * Syncs {@code text} to the server. Returns false when the server already holds exactly this text and so
     * was sent nothing: it then has no reason to publish diagnostics again, and the caller must not wait for
     * a publish that will not come.
     */
    public boolean changeDocument(Path file, String text) {
        LanguageServerSession s = sessionFor(file);
        return s == null || s.didChange(uri(file), text);
    }

    public void saveDocument(Path file, @Nullable String savedText) {
        LanguageServerSession s = sessionFor(file);
        if (s != null) {
            String uri = uri(file);
            latest.cancel(uri, "diagnostic"); // a server may judge the saved file differently: pull afresh
            s.didSave(uri, savedText);
        }
    }

    public void saveDocument(Path file) {
        saveDocument(file, null);
    }

    public void closeDocument(Path file) {
        if (file == null) {
            return;
        }
        String uri = uri(file);
        LanguageServerSession s = sessionByDocUri.remove(uri);
        documentIndents.remove(uri);
        semanticTokenState.remove(uri); // the next open starts from a full request (#679)
        semanticRequestGeneration.remove(uri);
        wholeDocumentTokens.remove(uri);
        // Removed, not bumped: generations are unique across documents (see nextDiagnosticGeneration), so a
        // reply still on its way finds no entry — or a later one — and is rejected either way.
        diagnosticRequestGeneration.remove(uri);
        latest.cancelAll(uri);
        DocumentUris.forget(file);
        rawDiagnostics.remove(uri); // open-documents-only retention (#670); keyed by this, our own, URI
        deleteJavaScriptShadow(javaScriptShadows.remove(uri));
        if (s != null) {
            s.didClose(uri, this);
            maybeScheduleEviction(s);
        }
    }

    // --- Idle-session eviction (#669) -----------------------------------------------------------

    /**
     * How long a session with no open documents is kept alive before being disposed. Closing the last file
     * of a root used to leave its server running until window close — browsing Java files across N unrelated
     * roots accumulated N live jdtls JVMs (hundreds of MB each plus Eclipse indexing). The grace period means
     * tab churn within one project doesn't cold-restart the server (and jdtls's persisted {@code -data} index
     * makes an eventual cold start cheap-ish).
     */
    static final java.time.Duration IDLE_EVICTION_GRACE = java.time.Duration.ofMinutes(3);

    /** The grace actually used; {@link #IDLE_EVICTION_GRACE} in production, shortened by tests (3 minutes of
     *  real waiting is not a test). */
    private volatile java.time.Duration idleEvictionGrace = IDLE_EVICTION_GRACE;

    /** TEST SEAM — shortens the idle-eviction grace so {@link #maybeScheduleEviction} can be observed. */
    void setIdleEvictionGraceForTest(java.time.Duration grace) {
        this.idleEvictionGrace = grace;
    }

    /** Shared timer for idle-session eviction (one daemon thread app-wide; the decision runs on FX). */
    private static final java.util.concurrent.ScheduledExecutorService evictExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lsp-evict");
                t.setDaemon(true);
                return t;
            });

    /** Session key → its pending idle-eviction timer (cancelled when a document reopens on that session). */
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> pendingEvictions = new ConcurrentHashMap<>();

    /** The {@code sessionsByRoot} key currently holding {@code session}, or null (it was dropped). */
    private @Nullable String keyOf(LanguageServerSession session) {
        for (var e : sessionsByRoot.entrySet()) {
            if (e.getValue() == session) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Sessions something other than an open document depends on, with a use count; see {@link #retain}. */
    private final Map<LanguageServerSession, Integer> retained = new ConcurrentHashMap<>();

    /**
     * Keeps the session serving {@code file} alive until the returned handle is run, even with no document
     * open on it. Open documents are not the only client a server has: a Java debug session runs
     * <em>inside</em> the jdtls process (the adapter is started there by {@code vscode.java.startDebugSession}
     * and the debuggee is its child), so evicting the "idle" server three minutes after the last Java tab
     * closed killed the adapter and the program being debugged. The handle is idempotent; releasing it
     * re-arms the idle timer. A no-op handle is returned when {@code file} has no session.
     */
    public Runnable retain(@Nullable Path file) {
        LanguageServerSession session = sessionFor(file);
        if (session == null) {
            return () -> {};
        }
        retained.merge(session, 1, Integer::sum);
        cancelEviction(session);
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                retained.computeIfPresent(session, (k, n) -> n > 1 ? n - 1 : null);
                maybeScheduleEviction(session);
            }
        };
    }

    /** Starts the idle-eviction timer for {@code session} if it no longer serves any open document. */
    private void maybeScheduleEviction(LanguageServerSession session) {
        if (sessionByDocUri.containsValue(session) || retained.containsKey(session)) {
            return; // still serving other open documents, or pinned by a debug session
        }
        String key = keyOf(session);
        if (key == null) {
            return; // already dropped
        }
        var prev = pendingEvictions.put(
                key,
                evictExec.schedule(
                        // The decision runs on the FX thread — openDocument/closeDocument are FX-thread calls,
                        // so re-checking there means no eviction can race a concurrent re-open.
                        () -> Platform.runLater(() -> evictIfIdle(key, session)),
                        idleEvictionGrace.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** Cancels a pending idle eviction for {@code session} (a document just [re]opened on it). */
    private void cancelEviction(LanguageServerSession session) {
        String key = keyOf(session);
        if (key != null) {
            var f = pendingEvictions.remove(key);
            if (f != null) {
                f.cancel(false);
            }
        }
    }

    /** FX thread: disposes {@code session} if it is still cached under {@code key} and still document-less. */
    private void evictIfIdle(String key, LanguageServerSession session) {
        pendingEvictions.remove(key);
        if (sessionsByRoot.get(key) != session
                || sessionByDocUri.containsValue(session)
                || retained.containsKey(session)) {
            return; // replaced, already dropped, pinned, or a document reopened during the grace period
        }
        sessionsByRoot.remove(key, session);
        if (!leaveShared(key, session)) {
            disposeDeliberately(key, session);
        }
    }

    /** How long a restarted session waits for the one it replaces to finish exiting. */
    private static final long PRIOR_EXIT_WAIT_MILLIS = 20_000;

    /**
     * Session key → the exit of a session that was deliberately disposed and has not gone yet, or a Clean Java
     * Workspace still deleting its workspace. Per process: another window sharing the server restarts on the
     * same key, and must wait for both (re-review CN1).
     */
    private static final Map<String, CompletableFuture<Void>> exiting = new ConcurrentHashMap<>();

    /**
     * Disposes a session on purpose (restart, disable, idle eviction, window close) and keeps what it held
     * until its process has really gone.
     *
     * <p>{@code dispose()} gives the server a moment to exit by itself, and until it has, a jdtls still
     * holds its Eclipse workspace lock. So the workspace claim is released only when the process exits, and
     * the exit is remembered under the session key: a replacement for the same (server, root) waits for it
     * before starting ({@link #awaitPriorExit}), instead of launching into a locked workspace.
     */
    private void disposeDeliberately(String key, LanguageServerSession session) {
        requestErrors.forget(key);
        sharedServers.remove(key, session);
        clientOf.remove(session);
        endHere(session);
        if (session.wasShared()) {
            session.reportEndedForAll(); // the other windows reopen their documents on a new server
        }
        String workspace = jdtlsWorkspaceBySession.remove(session);
        CompletableFuture<Void> gone =
                session.exited().whenComplete((done, error) -> releaseJdtlsWorkspaceName(workspace));
        if (!gone.isDone()) {
            exiting.put(key, gone);
            gone.whenComplete((done, error) -> exiting.remove(key, gone));
        }
    }

    /** TEST SEAM — completes once every deliberately disposed session's process has gone and released
     *  what it held. */
    CompletableFuture<Void> pendingExitsForTest() {
        return CompletableFuture.allOf(exiting.values().toArray(CompletableFuture[]::new));
    }

    /** Start thread: waits (bounded) for the session this one replaces to finish exiting. */
    private void awaitPriorExit(String key) {
        CompletableFuture<Void> prior = exiting.get(key);
        if (prior == null) {
            return;
        }
        try {
            prior.get(PRIOR_EXIT_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOG.log(java.util.logging.Level.FINE, "the previous language server had not exited yet", e);
        }
    }

    /** TEST SEAM — what a start on {@code serverId}/{@code root} waits for first, or null (CN1). */
    static @Nullable CompletableFuture<Void> pendingExitForTest(String serverId, Path root) {
        return exiting.get(sessionKey(serverId, root));
    }

    /** TEST SEAM — the session serving {@code file}, so a test can play the server's side of it. */
    @Nullable
    LanguageServerSession sessionForTest(Path file) {
        return sessionFor(file);
    }

    /** TEST SEAM — the command currently configured for {@code serverId} (what the next launch runs). */
    @Nullable
    String configuredCommandForTest(String serverId) {
        return commands.get(serverId);
    }

    /** TEST SEAM — whether a session is currently cached for {@code (serverId, root)}. Lets a test observe
     *  idle eviction (#669) and crash-drop (#666) without reflecting into the session maps. */
    boolean hasSessionForTest(String serverId, Path root) {
        return sessionsByRoot.containsKey(sessionKey(serverId, root));
    }

    /**
     * Whether {@code serverId} already runs for {@code root} and has finished its handshake: a file opened into
     * it starts nothing, so there is no "starting" to announce (Java review C18).
     */
    /**
     * Whether a session for {@code (serverId, root)} exists, started or still starting: a second file opened
     * while jdtls starts said "Starting…" again (Java review C18).
     */
    public boolean hasSession(String serverId, Path root) {
        String key = sessionKey(serverId, root);
        return sessionsByRoot.containsKey(key)
                || (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && sharedServers.containsKey(key));
    }

    public boolean isRunning(String serverId, Path root) {
        String key = sessionKey(serverId, root);
        LanguageServerSession s = sessionsByRoot.get(key);
        if (s == null && LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
            s = sharedServers.get(key); // another window's, which opening a document here joins (C12)
        }
        return s != null && !s.isDisposed() && s.isInitialized();
    }

    public boolean isManaged(@Nullable Path file) {
        // A remote (SFTP) file is never LSP-managed; bail before uri(), whose toUri() throws for such paths.
        return file != null && servable(file) && sessionByDocUri.containsKey(uri(file));
    }

    /** Current protocol version for an open document, or null when it is not managed. */
    public @Nullable Integer documentVersion(Path file) {
        if (file == null) {
            return null;
        }
        LanguageServerSession s = sessionFor(file);
        if (s != null) {
            return s.documentVersion(uri(file));
        }
        Path wanted = com.editora.config.PathKeys.canonical(file);
        for (var entry : sessionByDocUri.entrySet()) {
            Path open = DocumentUris.toPath(entry.getKey());
            if (open != null && wanted.equals(com.editora.config.PathKeys.canonical(open))) {
                return entry.getValue().documentVersion(entry.getKey());
            }
        }
        return null;
    }

    /**
     * What a response is validated against: the text of every document the session held when the request
     * was sent (keyed by local path, as opened and canonical), and the wall-clock instant it was sent
     * ({@code 0} when no request time is known, e.g. an edit handed in without the request that produced it).
     */
    record EditBasis(Map<Path, String> documents, long sentAtMillis, WireUris uris) {
        /** Nothing known, for an edit no session stands behind. One that came from a session uses {@link #unknown}. */
        static final EditBasis UNKNOWN = unknown(WireUris.LOCAL);

        /** Nothing known about the documents, but the edit's URIs are still {@code uris}'s server's. */
        static EditBasis unknown(WireUris uris) {
            return new EditBasis(Map.of(), 0, uris);
        }

        /** The same documents, re-stamped with the moment a follow-up request is sent. */
        EditBasis sentNow() {
            return new EditBasis(documents, System.currentTimeMillis(), uris);
        }
    }

    /** A workspace edit ready to apply, or the files that prevented it ({@code mapped} null). */
    public record EditPlan(WorkspaceEditMapper.@Nullable Mapped mapped, List<Path> blocked) {
        static final EditPlan REFUSED = new EditPlan(null, List.of());
    }

    /** Request-time text for every open document in a session, stamped with the current time. */
    private static EditBasis editBasis(LanguageServerSession session) {
        return new EditBasis(expectedDocuments(session), System.currentTimeMillis(), session.uris());
    }

    /** Request-time text for every open document in a session, keyed by local path. */
    private static Map<Path, String> expectedDocuments(LanguageServerSession session) {
        Map<Path, String> out = new java.util.LinkedHashMap<>();
        session.documentSnapshots().forEach((uri, text) -> {
            Path path = DocumentUris.toPath(uri);
            if (path != null) {
                out.put(path, text);
                out.put(com.editora.config.PathKeys.canonical(path), text);
            }
        });
        return Map.copyOf(out);
    }

    /** The no-I/O half of planning: the mapped edit with every server-open target's request-time text
     *  attached, plus the targets the server did not have open (still to be checked against the disk). */
    private record EditDraft(WorkspaceEditMapper.Mapped mapped, List<Path> closedTargets) {}

    private static @Nullable EditDraft draftWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, EditBasis basis) {
        WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(edit, basis.uris()::toPath);
        if (mapped == null) {
            return null;
        }
        Map<Path, String> documents = basis == null ? Map.of() : basis.documents();
        java.util.Set<String> created = mapped.creates().stream()
                .map(create -> com.editora.config.PathKeys.key(create.file()))
                .collect(java.util.stream.Collectors.toSet());
        List<WorkspaceEditMapper.FileEdit> edits =
                new ArrayList<>(mapped.edits().size());
        List<Path> closedTargets = new ArrayList<>();
        for (WorkspaceEditMapper.FileEdit fileEdit : mapped.edits()) {
            String expected = documents.get(fileEdit.file());
            if (expected == null) {
                expected = documents.get(com.editora.config.PathKeys.canonical(fileEdit.file()));
            }
            if (expected == null && created.contains(com.editora.config.PathKeys.key(fileEdit.file()))) {
                expected = "";
            }
            if (expected != null) {
                edits.add(new WorkspaceEditMapper.FileEdit(
                        fileEdit.file(), fileEdit.edits(), fileEdit.version(), expected, null));
            } else {
                edits.add(fileEdit);
                closedTargets.add(fileEdit.file());
            }
        }
        return new EditDraft(
                new WorkspaceEditMapper.Mapped(
                        List.copyOf(edits), mapped.renames(), mapped.creates(), mapped.deletes()),
                List.copyOf(closedTargets));
    }

    /**
     * Completes a draft by establishing a preimage for each target the server did not have open.
     *
     * <p>The server computed those edits from the file on disk. Reading the file once the response is in
     * would bless whatever is there now, so that alone proves nothing; what does is the file's modification
     * time predating the moment the request was sent — then the content on disk is the content the edit was
     * computed from. Such a target is marked with that instant, and the applier re-checks it against a clean
     * buffer. A target that changed since the request, is missing, or belongs to an edit whose request time
     * is unknown is reported as blocking, and nothing is applied.
     *
     * <p>A version the server attached to a document it never had open means nothing to this client (no
     * version was ever sent for it), so it is dropped rather than compared against a version that cannot
     * exist. Pure given {@code lastModified} (millis, negative for a missing file).
     */
    static EditPlan completeWorkspaceEdit(
            WorkspaceEditMapper.Mapped mapped,
            List<Path> closedTargets,
            EditBasis basis,
            java.util.function.ToLongFunction<Path> lastModified) {
        if (closedTargets.isEmpty()) {
            return new EditPlan(mapped, List.of());
        }
        long sentAt = basis == null ? 0 : basis.sentAtMillis();
        java.util.Set<Path> closed = new java.util.HashSet<>(closedTargets);
        List<Path> blocked = new ArrayList<>();
        List<WorkspaceEditMapper.FileEdit> edits =
                new ArrayList<>(mapped.edits().size());
        for (WorkspaceEditMapper.FileEdit fileEdit : mapped.edits()) {
            if (!closed.contains(fileEdit.file())) {
                edits.add(fileEdit);
            } else if (WorkspaceEditMapper.unchangedSince(
                    fileEdit.file(), lastModified.applyAsLong(fileEdit.file()), sentAt)) {
                edits.add(new WorkspaceEditMapper.FileEdit(fileEdit.file(), fileEdit.edits(), null, null, sentAt));
            } else {
                blocked.add(fileEdit.file());
            }
        }
        if (!blocked.isEmpty()) {
            return new EditPlan(null, List.copyOf(blocked));
        }
        return new EditPlan(
                new WorkspaceEditMapper.Mapped(
                        List.copyOf(edits), mapped.renames(), mapped.creates(), mapped.deletes()),
                List.of());
    }

    /** A file's modification time in millis (by its own machine's clock), or -1 when it cannot be read. */
    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Maps and validates a returned edit in one step. Stats closed targets: call it off the FX thread. */
    private static EditPlan planWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, EditBasis basis) {
        EditDraft draft = draftWorkspaceEdit(edit, basis);
        return draft == null
                ? EditPlan.REFUSED
                : completeWorkspaceEdit(draft.mapped(), draft.closedTargets(), basis, LspManager::lastModifiedMillis);
    }

    /** Told (on the FX thread) which files prevented a workspace edit, just before its caller hears failure. */
    private volatile Consumer<List<Path>> onEditBlocked = files -> {};

    public void setOnEditBlocked(Consumer<List<Path>> handler) {
        this.onEditBlocked = handler == null ? files -> {} : handler;
    }

    /** The server id currently managing {@code file}, or null if it is not open on any server. Lets the
     *  coordinator detect a server change (e.g. a pom.xml moving to lemminx-maven) and close+reopen. */
    public @Nullable String managedServerId(Path file) {
        if (file == null || !servable(file)) {
            return null;
        }
        LanguageServerSession s = sessionByDocUri.get(uri(file));
        return s == null ? null : s.serverId();
    }

    /**
     * Separator between the two halves of a session key. It must be a character that cannot occur in a server
     * id or a URI, so the {@code startsWith(serverId + SEP)} scan in {@link #shutdownServer} can't match the
     * wrong session.
     *
     * <p>Written as an escape, not as a raw NUL byte in the source: a literal control character made this
     * whole file <b>binary</b> to grep/rg (they skip it silently), which is exactly how the key here and the
     * prefix in {@code shutdownServer} came to disagree — one used the NUL, the other a space, so
     * {@code shutdownServer} never matched a single session and quietly did nothing at all.
     */
    private static final String SESSION_KEY_SEP = "\u0000";

    /** The {@code sessionsByRoot} key: one session per (server, root). The command is deliberately not part
     *  of it — {@link #putCommand} disposes the session when the command changes. */
    static String sessionKey(String serverId, Path root) {
        return serverId + SESSION_KEY_SEP + DocumentUris.ofDirectory(root);
    }

    /** The prefix every {@link #sessionKey} for {@code serverId} starts with — how {@link #shutdownServer}
     *  finds that server's sessions. Must agree with {@link #sessionKey}; unit-tested, because it silently
     *  did not. */
    static String sessionKeyPrefix(String serverId) {
        return serverId + SESSION_KEY_SEP;
    }

    /** Where a session's status reaches this window: its status bar, and a refresh once the server is ready. */
    private java.util.function.BiConsumer<String, String> statusSink(
            String serverId, java.util.function.Supplier<LanguageServerSession> session) {
        return (type, msg) -> Platform.runLater(() -> {
            if (closed) {
                return;
            }
            ServerStatusListener named = onServerStatus;
            if (named != null) {
                LanguageServerSession said = session.get();
                named.status(serverId, said == null ? null : said.root(), type, msg);
            } else {
                onStatus.accept(type, msg);
            }
            if (announcesReady(type)) {
                // Capabilities are known now (and jdtls says this again when it has finished
                // importing): the gates and data of this session's documents are due a refresh.
                onRefreshRequested.refreshRequested("capabilities", session.get());
            }
        });
    }

    /** Points this window's {@code client} of {@code session} at this manager. */
    private void configureClient(LanguageServerSession.Client client, String key, LanguageServerSession session) {
        client.onRequestOutcome = (method, error) -> noteRequestOutcome(session, method, error);
        client.indentForUri = documentIndents::get; // TypeScript's formattingOptions, jdtls's java.format.*
        // Drop the session the moment it can no longer serve requests — the process died on its own, or the
        // handshake failed/timed out. Otherwise it stays cached looking alive: isManaged() keeps returning true
        // (so the re-open guard never restarts it), every request fails into an empty result, and LSP is silently
        // dead for the rest of the session while the status bar still names the server.
        client.onDead = () -> dropSession(key, session);
        client.onRefresh = kind -> Platform.runLater(() -> onRefreshRequested.refreshRequested(kind, session));
        client.onApplyEdit = (edit, respond) -> onServerApplyEdit(session, edit, respond); // server-side fix (#670)
        client.ui = fxClientUi; // showMessageRequest and Metals' prompts, answered on the FX thread
        client.focused = () -> windowFocused.getAsBoolean();
    }

    /**
     * Joins the server another window runs for this project (Java review C12), or null when it can no longer
     * take a window (it stopped meanwhile). The documents this window opens on it are counted with the other
     * window's, and it hears the server's diagnostics and status as the other window does.
     */
    private @Nullable LanguageServerSession join(String key, LanguageServerSession session) {
        LanguageServerSession.Client client = new LanguageServerSession.Client(
                params -> onPublishDiagnostics(session, params), statusSink(session.serverId(), () -> session));
        configureClient(client, key, session);
        if (!session.attach(client)) {
            sharedServers.remove(key, session);
            return null;
        }
        LanguageServerSession prev = sessionsByRoot.putIfAbsent(key, session);
        if (prev != null && prev != session) {
            session.detach(client);
            return prev;
        }
        clientOf.put(session, client);
        ensureRootWatcher(session.root());
        if (session.isInitialized()) {
            // Ready before this window came: it hears so now — its status bar says "Loaded and ready" instead of
            // a stale progress line, and its documents' gates and data are refreshed (re-review CN5).
            client.status.accept("ServiceReady", null);
        }
        return session;
    }

    /**
     * Leaves a session another window still uses: this window's documents on it are closed (the server keeps
     * them while the other window has them open) and its client detached. False when this window is its last
     * user, and the caller stops it.
     */
    private boolean leaveShared(String key, LanguageServerSession session) {
        LanguageServerSession.Client client = clientOf.get(session);
        if (client == null || session.clientCount() <= 1) {
            return false;
        }
        sessionByDocUri.forEach((uri, s) -> {
            if (s == session) {
                session.didClose(uri, this);
            }
        });
        sessionByDocUri.values().removeIf(s -> s == session);
        pendingApplyExpected.remove(session);
        requestErrors.forget(key);
        session.detach(client);
        clientOf.remove(session);
        return true; // its workspace claim stays with the session, for the window that stops it to release
    }

    /** Disposes {@code session} as this window's own doing. */
    private void endHere(LanguageServerSession session) {
        endedHere.add(session);
        session.dispose();
    }

    private @Nullable LanguageServerSession sessionForRoot(Path root, String languageId) {
        String serverId = LspServerRegistry.serverIdFor(languageId);
        if (serverId == null) {
            return null;
        }
        // Key by (serverId, root) — not languageId — so js/ts/jsx/tsx in one root share one tsserver.
        String key = sessionKey(serverId, root);
        LanguageServerSession cached = sessionsByRoot.get(key);
        if (cached != null) {
            return cached;
        }
        if (HELD_FOR_INSTALL.contains(serverId)) {
            return null; // its folder is being replaced: started again once the install is done (CT6)
        }
        LspServerRegistry.ServerSpec spec = LspServerRegistry.specFor(languageId, commands);
        if (spec == null) {
            return null;
        }
        boolean shareable =
                LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && com.editora.process.ExecTargets.runsHere(root);
        if (shareable) {
            LanguageServerSession running = sharedServers.get(key);
            LanguageServerSession joined = running == null ? null : join(key, running);
            if (joined != null) {
                return joined;
            }
        }
        // jdtls: give each project root its own Eclipse workspace (-data) so it never deadlocks on the shared
        // default workspace's .lock. Created on demand; jdtls reuses it across sessions (its index persists).
        // The claim registry (static, spanning windows) suffixes the dir when another window's live session
        // already holds it — see claimJdtlsWorkspaceName (#668).
        Path claimBase = jdtlsWorkspaceBase;
        boolean needsJdtlsWorkspace = LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && claimBase != null;
        String claimedWorkspace = null;
        if (needsJdtlsWorkspace && sessionStarterForTest != null && claimBase != null) {
            claimedWorkspace = claimUsableJdtlsWorkspaceName(claimBase, LspServerRegistry.workspaceDirName(root));
        }
        LanguageServerSession[] diagnosticSource = new LanguageServerSession[1];
        LanguageServerSession session = new LanguageServerSession(
                spec,
                root,
                params -> onPublishDiagnostics(diagnosticSource[0], params),
                statusSink(spec.serverId(), () -> diagnosticSource[0]));
        diagnosticSource[0] = session;
        configureClient(session.primaryClient(), key, session);
        session.setJavaOnTypeFormatting(javaOnTypeFormatting);
        session.setJavaCleanups(javaCleanups);
        session.setJavaFilteredTypes(javaFilteredTypes);
        session.setBashIde(bashIde);
        if (TypeScriptServer.SERVER_ID.equals(serverId)) {
            session.setTypeScriptSettings(typeScriptSettings);
        }
        if (claimedWorkspace != null) {
            jdtlsWorkspaceBySession.put(session, claimedWorkspace);
        }
        if (shareable) {
            LanguageServerSession rival = sharedServers.putIfAbsent(key, session);
            if (rival != null) {
                // Another window started one in the same moment: join it, and drop this un-started one.
                LanguageServerSession joined = join(key, rival);
                if (joined != null) {
                    releaseJdtlsWorkspace(session);
                    return joined;
                }
                sharedServers.replace(key, rival, session); // it died in between: this one takes its place
            }
        }
        LanguageServerSession prev = sessionsByRoot.putIfAbsent(key, session);
        if (prev != null) {
            sharedServers.remove(key, session);
            releaseJdtlsWorkspace(session); // this un-started session's claim must not leak
            return prev; // another open created it first (rare) — use that one; this un-started session is dropped
        }
        clientOf.put(session, session.primaryClient());
        if (sessionStarterForTest != null) {
            // The production path below creates the workspace on startExec. The fake starter is deliberately
            // synchronous, so preserve that test seam's observable contract without moving production I/O back
            // onto the FX thread.
            if (claimedWorkspace != null && jdtlsWorkspaceBase != null) {
                try {
                    Files.createDirectories(jdtlsWorkspaceBase.resolve(claimedWorkspace));
                } catch (java.io.IOException e) {
                    dropSession(key, session);
                    throw new IllegalStateException("Could not create test jdtls workspace", e);
                }
            }
            if (PYTHON_SERVER_ID.equals(serverId)) {
                session.setPythonPath(resolvePythonPath(root)); // as the production start does, before initialize
            }
            ensureRootWatcher(root);
            sessionStarterForTest.accept(session); // TEST SEAM (see the field): attach a fake, never fork
            return session;
        }
        // Fork + connect + initialize OFF the FX thread (#407): the process fork + PATH scan + ledger write would
        // otherwise freeze the UI for the first open of a language. The session is already cached, so the caller's
        // didOpen queues (LanguageServerSession.whenReady) until the async initialize completes; a failed start
        // uncaches it so a later open retries.
        boolean remote = !com.editora.process.ExecTargets.runsHere(root);
        // Where the folder's tools run elsewhere (a remote host, a container, WSL): the server there (its own
        // command for it, else the default), and none of the local workspace and SDK discovery below, which
        // reads this machine's disk and names this machine's paths.
        LspServerRegistry.ServerSpec unresolvedSpec = remote
                ? new LspServerRegistry.ServerSpec(serverId, remoteCommand(serverId, root), spec.rootMarkers())
                : spec;
        Path workspaceBase = jdtlsWorkspaceBase;
        String workspaceBaseName = needsJdtlsWorkspace && !remote ? LspServerRegistry.workspaceDirName(root) : null;
        List<String> bundles = List.copyOf(debugBundles);
        boolean onTypeFormatting = javaOnTypeFormatting;
        String javaPreferredHome = remote || !LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                ? null // a remote host's JDKs are its own; the setting names this machine's
                : JavaRuntimes.preferredHome(javaDefaultJdk, System.getenv("JAVA_HOME"));
        boolean centralIndex = mavenCentralIndex;
        // Trust is read here, on the caller's thread, where the trust list is also edited.
        Path sdkCeiling = ASTRO_SERVER_ID.equals(serverId) || TypeScriptServer.SERVER_ID.equals(serverId)
                ? trustedCeiling(root, folderTrust)
                : null;
        boolean typeAcquisition = typeScriptTypeAcquisition;
        String nodeSetting = nodePathSetting;
        boolean loose = root.toAbsolutePath().normalize().equals(looseRoot);
        startExec.execute(() -> {
            awaitPriorExit(key); // a restart: let the server being replaced release what it holds
            if (closed || session.isDisposed()) {
                dropSession(key, session);
                return;
            }
            LspServerRegistry.ServerSpec resolvedSpec = unresolvedSpec;
            String workspaceName = null;
            if (workspaceBaseName != null && workspaceBase != null) {
                workspaceName = claimUsableJdtlsWorkspaceName(workspaceBase, workspaceBaseName);
                if (!workspaceName.equals(workspaceBaseName)) {
                    // Another window (or an earlier server still exiting) holds this project's workspace: this
                    // one imports it again on a server of its own. Said, not hidden (Java review C12).
                    Platform.runLater(() -> {
                        if (!closed) {
                            onSecondServer.accept(serverId, root);
                        }
                    });
                }
                jdtlsWorkspaceBySession.put(session, workspaceName);
                // shutdown may race the off-thread claim after the session was put in sessionsByRoot.
                // Do not leave a claim behind or start a process that its window no longer owns.
                if (session.isDisposed()) {
                    jdtlsWorkspaceBySession.remove(session, workspaceName);
                    releaseJdtlsWorkspaceName(workspaceName);
                    return;
                }
            }
            if (workspaceName != null && workspaceBase != null) {
                Path ws = workspaceBase.resolve(workspaceName);
                try {
                    Files.createDirectories(ws);
                    resolvedSpec = LspServerRegistry.withDataDir(resolvedSpec, ws.toString());
                } catch (java.io.IOException e) {
                    // Couldn't create the workspace dir — fall back to the default (better than not starting).
                    jdtlsWorkspaceBySession.remove(session);
                    releaseJdtlsWorkspaceName(workspaceName);
                }
            }
            List<JavaRuntimes.Jdk> hostJdks = null;
            java.util.Map<String, String> remoteEnvironment = new java.util.HashMap<>();
            if (remote && LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
                resolvedSpec = withRemoteJdtlsWorkspace(resolvedSpec, root);
                hostJdks = RemoteJavaRuntimes.discover(root);
            }
            List<String> startCommand = remote
                    ? JavaServerEnvironment.configure(
                            serverId,
                            resolvedSpec.command(),
                            remoteEnvironment,
                            exe -> JavaServerEnvironment.isRemoteJdtlsLauncher(root, exe))
                    : resolvedSpec.command();
            if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && !remote && !startCommand.isEmpty()) {
                JavaRuntimes.ServerJava serverJava = JavaRuntimes.serverJava(
                        JavaRuntimes.launcherJavaMajor(), JavaRuntimes.discover(), javaPreferredHome);
                if (serverJava.missing() && JavaServerEnvironment.isJdtlsLauncher(startCommand.get(0))) {
                    // The launcher would refuse with "jdtls requires at least Java 21" on its stderr, and the
                    // user would only see a crash loop: say what is missing instead, and do not retry.
                    endHere(session);
                    dropSession(key, session);
                    StartRefusal refusal = new StartRefusal(
                            StartRefusal.Kind.NO_JAVA_FOR_SERVER, String.valueOf(JavaRuntimes.SERVER_MIN_MAJOR));
                    Platform.runLater(() -> {
                        if (!closed) {
                            onStartRefused.refused(serverId, root, refusal);
                        }
                    });
                    return;
                }
                startCommand = JavaServerEnvironment.withJavaExecutable(
                        startCommand, serverJava.executable(), JavaServerEnvironment::isJdtlsLauncher);
                Path lombok = JavaLombok.agentFor(root, Path.of(System.getProperty("user.home")), lombokDir);
                startCommand = JavaLombok.withAgent(startCommand, lombok, JavaServerEnvironment::isJdtlsLauncher);
                if (lombok == null && lombokDir != null && JavaLombok.declaresLombok(root)) {
                    // Declared, but no build has downloaded it yet: the window offers to fetch it.
                    Platform.runLater(() -> onLombokMissing.accept(root));
                }
            }
            Path astroTsdk = ASTRO_SERVER_ID.equals(serverId) && !remote
                    ? astroTypeScriptSdk(root, sdkCeiling, startCommand)
                    : null;
            if (ASTRO_SERVER_ID.equals(serverId) && astroTsdk == null) {
                // astro-ls cannot initialize without a TypeScript SDK, and the only one on offer belongs
                // to a folder that is not trusted to choose code that runs. Disposed first, so the drop
                // reads as deliberate (no crash notice, no auto-restart).
                endHere(session);
                dropSession(key, session);
                Platform.runLater(() -> onStartWithheld.accept(serverId, root));
                return;
            }
            Path tsserver = null;
            if (TypeScriptServer.SERVER_ID.equals(serverId) && !remote) {
                TypeScriptServer.Choice choice = typeScriptChoice(root, sdkCeiling, startCommand, loose);
                if (!choice.starts()) {
                    // Not a crash and not worth retrying: the same start would only fail (or run a folder's code).
                    endHere(session);
                    dropSession(key, session);
                    StartRefusal refusal = choice.outcome() == TypeScriptServer.Outcome.WITHHELD_UNTRUSTED
                            ? new StartRefusal(StartRefusal.Kind.UNTRUSTED_TYPESCRIPT, root.toString())
                            : new StartRefusal(StartRefusal.Kind.NO_TYPESCRIPT, choice.installedVersion());
                    Platform.runLater(() -> {
                        if (!closed) {
                            onStartRefused.refused(serverId, root, refusal);
                        }
                    });
                    return;
                }
                tsserver = choice.tsserver();
                session.setPathPrepend(nodeBinDir(root, nodeSetting));
            }
            Path tsserverPath = tsserver;
            List<JavaRuntimes.Jdk> jdks = hostJdks;
            // A remote jdtls is told about the host's JDKs, and gets the debug bundles as files on the host.
            List<String> startBundles = remote && LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                    ? remoteDebugBundles(root, bundles)
                    : remote ? List.of() : bundles;
            if (PYTHON_SERVER_ID.equals(serverId)) {
                // Before initialize: the first answer counts. A remote server is told the path as its host names it.
                session.setPythonPath(resolvePythonPath(root));
            }
            if (!remote) {
                ensureRootWatcher(root); // file changes nobody else reports reach this server (B7)
            }
            session.configureStart(
                    startCommand,
                    remoteEnvironment,
                    () -> TypeScriptServer.SERVER_ID.equals(serverId) && !remote
                            ? TypeScriptServer.initializationOptions(tsserverPath, typeAcquisition, loose)
                            : TypeScriptServer.SERVER_ID.equals(serverId)
                                    ? TypeScriptServer.initializationOptions(null, typeAcquisition, false)
                                    : withProjectSettings(
                                            serverId,
                                            initOptionsFor(
                                                    serverId,
                                                    startBundles,
                                                    astroTsdk,
                                                    onTypeFormatting,
                                                    jdks,
                                                    centralIndex,
                                                    javaPreferredHome),
                                            remote ? null : root));
            if (!session.start()) {
                dropSession(key, session);
                if (session.startFailed()) {
                    // Disposed by start() itself, so dropSession raised no crash: say so separately, or the
                    // buffers it was meant to serve keep looking served by a server that never came up.
                    Platform.runLater(() -> {
                        if (!closed) {
                            onStartFailed.accept(serverId, root);
                        }
                    });
                }
            }
        });
        return session;
    }

    /**
     * The java-debug bundle(s) for a jdtls on the host of remote {@code root}, as paths there: the host's
     * {@code dap.java} tool when set, else this machine's jars copied into the host's cache
     * ({@code <cache>/java-debug/<jar name>}, sent again only when the size differs — the name carries the
     * version). Empty when there is nothing to offer; debugging then reports the plugin missing.
     */
    static List<String> remoteDebugBundles(Path root, List<String> localJars) {
        String configured = com.editora.process.ExecTargets.remoteTool(root, "dap.java");
        if (configured != null && !configured.isBlank()) {
            return List.of(configured.strip());
        }
        if (localJars == null || localJars.isEmpty()) {
            return List.of();
        }
        try {
            com.editora.process.TargetCache cache =
                    com.editora.process.ExecTargets.forPath(root).cache();
            if (cache == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String jar : localJars) {
                Path source = Path.of(jar);
                out.add(cache.putFile("java-debug/" + source.getFileName(), source));
            }
            return List.copyOf(out);
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * A remote jdtls's {@code -data}: {@code jdtls-workspaces/<root hash>} in the host's cache folder, so its
     * index outlives the session as a local one's does. Not claimed across windows like a local workspace (a
     * remote project has one window); a folder that cannot be made leaves jdtls its own default.
     */
    private static LspServerRegistry.ServerSpec withRemoteJdtlsWorkspace(LspServerRegistry.ServerSpec spec, Path root) {
        try {
            com.editora.process.TargetCache cache =
                    com.editora.process.ExecTargets.forPath(root).cache();
            if (cache == null) {
                return spec;
            }
            return LspServerRegistry.withDataDir(
                    spec, cache.makeDirectories("jdtls-workspaces/" + LspServerRegistry.workspaceDirName(root)));
        } catch (java.io.IOException | RuntimeException e) {
            return spec;
        }
    }

    /**
     * TEST SEAM — when set, a newly created session is handed here <b>instead of being forked</b>, so the
     * routing/lifecycle layer (open→managed→close, idle eviction, crash-vs-dispose, per-server shutdown,
     * diagnostics retention) is reachable without a subprocess. Mirrors
     * {@code LanguageServerSession.attachForTest}; null in production, so the fork path is untouched.
     *
     * <p>This layer sat at 17% coverage while holding the map bookkeeping that several fixes turn on — #669
     * (evict an idle session), #666 (a crashed session must be dropped, a disposed one must not re-fork),
     * #670 (retain raw diagnostics for open documents only). All of that was verifiable only by hand.
     */
    private volatile @Nullable Consumer<LanguageServerSession> sessionStarterForTest;

    void setSessionStarterForTest(Consumer<LanguageServerSession> starter) {
        this.sessionStarterForTest = starter;
        sharedServers = new ConcurrentHashMap<>(); // no joining another test's server (see shareServersForTest)
    }

    /** Sets the session-crashed callback (see {@link #onSessionCrashed}); marshaled to the FX thread. */
    /** A server's status, with the id of the server it came from. */
    @FunctionalInterface
    public interface ServerStatusListener {
        void status(String serverId, String type, String message);

        /** As above, with the project folder of the session that said it (null when unknown). */
        default void status(String serverId, @Nullable Path root, String type, String message) {
            status(serverId, type, message);
        }
    }

    /** When set, receives every server status instead of the constructor's callback, naming the server. */
    private volatile @Nullable ServerStatusListener onServerStatus;

    /**
     * Routes server statuses to {@code listener} with the server's id, so a message can say which server it
     * is from — the constructor's callback has no way to (every one was shown as "Java LSP: …").
     */
    public void setOnServerStatus(ServerStatusListener listener) {
        this.onServerStatus = listener;
    }

    public void setOnSessionCrashed(BiConsumer<String, Path> callback) {
        this.onSessionCrashed = callback == null ? (id, root) -> {} : callback;
    }

    /**
     * jdtls {@code -data} dirs currently claimed by a live session in <b>any</b> window. {@code LspManager} is
     * per-window, so two windows opening files under the same Java root would otherwise fork two jdtls against
     * <b>one</b> Eclipse workspace — whose {@code .metadata/.lock} admits a single process, wedging the second
     * at {@code initialize} until its 60 s timeout, and every retry the same way (#668). Static so the claim
     * spans windows; released when the owning session drops. In-process only — a second Editora <i>process</i>
     * can still collide (rare, pre-existing).
     */
    private static final java.util.Set<String> claimedJdtlsWorkspaces = ConcurrentHashMap.newKeySet();

    /**
     * Session → the jdtls workspace dir name claimed for it, released when it is dropped or shut down. Kept per
     * process, not per window: a server two windows share (C12) holds its claim until the window that stops it
     * releases it, whichever window started it.
     */
    private static final Map<LanguageServerSession, String> jdtlsWorkspaceBySession = new ConcurrentHashMap<>();

    /** Claims the first free workspace dir name for {@code baseName}: the canonical hash dir when free (so
     *  the persisted index is reused), else {@code <base>-2}, {@code -3}, … A suffixed dir costs a second
     *  jdtls index but unwedges the second window. Package-visible for tests. */
    static String claimJdtlsWorkspaceName(String baseName) {
        for (int i = 1; i <= 20; i++) {
            String candidate = workspaceCandidate(baseName, i);
            if (claimedJdtlsWorkspaces.add(candidate)) {
                return candidate;
            }
        }
        // 20 live same-root claims cannot happen in practice; hand out a unique last resort anyway.
        String unique = baseName + "-x" + Long.toHexString(System.nanoTime());
        claimedJdtlsWorkspaces.add(unique);
        return unique;
    }

    /**
     * Off-thread production claim. Sidecar markers identify caches whose previous session never completed
     * initialize and could not be safely removed (usually another process still held the Eclipse lock).
     *
     * <p>A marker is re-tested here rather than obeyed forever: once the lock is free the rebuildable cache
     * and its marker are removed and the name is reused. Nothing else ever deleted a marker, so one timed-out
     * handshake orphaned the canonical index for good and every later start burned another suffix.
     */
    static String claimUsableJdtlsWorkspaceName(Path base, String baseName) {
        for (int i = 1; i <= 20; i++) {
            String candidate = workspaceCandidate(baseName, i);
            if (!claimedJdtlsWorkspaces.add(candidate)) {
                continue;
            }
            Path marker = failedWorkspaceMarker(base, candidate);
            if (!Files.exists(marker)) {
                return candidate;
            }
            if (deleteUnlockedJdtlsWorkspace(base.resolve(candidate))) {
                try {
                    Files.deleteIfExists(marker);
                    return candidate;
                } catch (java.io.IOException e) {
                    LOG.log(java.util.logging.Level.FINE, "Could not clear " + marker, e);
                }
            }
            claimedJdtlsWorkspaces.remove(candidate); // still locked by someone else: leave it alone
        }
        String unique = baseName + "-x" + Long.toHexString(System.nanoTime());
        claimedJdtlsWorkspaces.add(unique);
        return unique;
    }

    private static Path failedWorkspaceMarker(Path base, @Nullable String name) {
        return base.resolve("." + name + ".initialize-failed");
    }

    /** The {@code attempt}-th candidate dir name for {@code baseName} (1 = the canonical name). Pure. */
    static String workspaceCandidate(String baseName, int attempt) {
        return attempt <= 1 ? baseName : baseName + "-" + attempt;
    }

    /** Releases a claim taken by {@link #claimJdtlsWorkspaceName}. Package-visible for tests. */
    static void releaseJdtlsWorkspaceName(@Nullable String name) {
        if (name != null) {
            claimedJdtlsWorkspaces.remove(name);
        }
    }

    /** Releases the workspace claim held by {@code session}, if any. */
    private void releaseJdtlsWorkspace(LanguageServerSession session) {
        releaseJdtlsWorkspaceName(jdtlsWorkspaceBySession.remove(session));
    }

    /** Removes a session that can no longer serve requests, so the next open starts a fresh one. */
    private void dropSession(String key, LanguageServerSession session) {
        requestErrors.forget(key);
        boolean wasMine = sessionsByRoot.remove(key, session);
        sharedServers.remove(key, session);
        clientOf.remove(session);
        boolean endedByThisWindow = endedHere.remove(session);
        sessionByDocUri.values().removeIf(s -> s == session);
        pendingApplyExpected.remove(session);
        String workspaceName = jdtlsWorkspaceBySession.remove(session);
        // Distinguish a crash from a deliberate teardown: dispose() sets its flag BEFORE killing the
        // process, so a session that is dead but never disposed died on its own (process crash, failed/
        // timed-out handshake). Only that case notifies the coordinator to auto-restart — a shutdownServer/
        // shutdownAll/putCommand teardown, or a start() whose fork failed (dispose()d in its catch), must
        // not re-fork in a loop (#666). A failed start is reported on its own, through onStartFailed.
        String refused = session.initializeRefusal();
        if (!session.isDisposed() && refused != null && !LspServerRegistry.JAVA_SERVER_ID.equals(session.serverId())) {
            // The server answered initialize with an error and exited: the same start would only be refused
            // again, so say why instead of restarting it into the crash-loop cap (Node review B3).
            endHere(session);
            endedHere.remove(session);
            releaseJdtlsWorkspaceName(workspaceName);
            StartRefusal refusal = new StartRefusal(StartRefusal.Kind.SERVER_REFUSED, refused);
            Platform.runLater(() -> {
                if (!closed) {
                    onStartRefused.refused(session.serverId(), session.root(), refusal);
                }
            });
            return;
        }
        if (!session.isDisposed()) {
            boolean failedJdtlsInitialization = workspaceName != null
                    && LspServerRegistry.JAVA_SERVER_ID.equals(session.serverId())
                    && !session.initializedOnce();
            endHere(session); // hygiene: stop its executor + untrack the dead process
            endedHere.remove(session);
            if (failedJdtlsInitialization) {
                repairAfterExit(key, session.exited(), workspaceName);
            } else {
                releaseJdtlsWorkspaceName(workspaceName);
            }
            Platform.runLater(() -> onSessionCrashed.accept(session.serverId(), session.root()));
        } else {
            releaseJdtlsWorkspaceName(workspaceName);
            if (wasMine && session.wasShared() && !endedByThisWindow) {
                // Another window restarted the server this one shared, or saw it crash first: reopen quietly.
                Platform.runLater(() -> {
                    if (!closed) {
                        onSharedServerEnded.accept(session.serverId(), session.root());
                    }
                });
            }
        }
    }

    /**
     * Repairs a failed jdtls workspace once its process has really gone, then releases the claim.
     *
     * <p>{@code dispose()} only signals the process (the kill is non-blocking), and a JVM that has not exited
     * yet still holds the Eclipse lock. Probing the lock in the same breath therefore always found the
     * session's <em>own</em> dying server, kept the cache and marked it failed. The wait is bounded, and is
     * remembered under the session key so the auto-restart waits for it ({@link #awaitPriorExit}) and gets the
     * canonical name back instead of a fresh suffix.
     */
    void repairAfterExit(String key, CompletableFuture<Void> exited, @Nullable String workspaceName) {
        Runnable repair = () -> {
            repairFailedJdtlsWorkspace(workspaceName);
            releaseJdtlsWorkspaceName(workspaceName);
        };
        if (exited.isDone()) {
            repair.run();
            return;
        }
        CompletableFuture<Void> repaired = exited.copy()
                .orTimeout(PRIOR_EXIT_WAIT_MILLIS / 2, java.util.concurrent.TimeUnit.MILLISECONDS)
                .handleAsync((done, error) -> {
                    repair.run();
                    return null;
                });
        exiting.put(key, repaired);
        repaired.whenComplete((done, error) -> exiting.remove(key, repaired));
    }

    /** A jdtls data directory contains only rebuildable Eclipse indexes. After a failed handshake, remove it
     *  if its OS lock is free; otherwise mark it so this and future Editora processes choose a fresh suffix. */
    private void repairFailedJdtlsWorkspace(@Nullable String workspaceName) {
        Path base = jdtlsWorkspaceBase;
        if (base == null) {
            return;
        }
        Path workspace = base.resolve(workspaceName);
        Path marker = failedWorkspaceMarker(base, workspaceName);
        if (deleteUnlockedJdtlsWorkspace(workspace)) {
            try {
                Files.deleteIfExists(marker);
            } catch (java.io.IOException ignored) {
                // A stale marker only costs a fresh suffix on the next launch.
            }
            return;
        }
        try {
            Files.createDirectories(base);
            Files.writeString(marker, "initialize failed " + java.time.Instant.now());
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.FINE, "Could not mark failed jdtls workspace " + workspace, e);
        }
    }

    private static boolean deleteUnlockedJdtlsWorkspace(Path workspace) {
        if (!Files.exists(workspace)) {
            return true;
        }
        Path lockFile = workspace.resolve(".metadata/.lock");
        if (Files.exists(lockFile)) {
            try (var channel = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                if (lock == null) {
                    return false;
                }
            } catch (java.nio.channels.OverlappingFileLockException | java.io.IOException e) {
                return false;
            }
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return !Files.exists(workspace);
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /** What {@link #clearJdtlsWorkspaces} did: the caches it deleted, the bytes they held, and the ones it kept
     *  because a server still uses them (or a file in them could not be deleted). */
    public record JdtlsCacheClearResult(int deleted, long bytesFreed, int kept) {}

    /**
     * Deletes every jdtls workspace under {@code base} that no server is using ({@code lsp.clearJavaIndexCaches}).
     * Nothing else ever removes one: there is a workspace per Java folder ever opened, each ~40 MB before the
     * project's own classes (jdtls indexes the JDK into every one), so the folder only grows. jdtls rebuilds a
     * deleted one on its next start for that project.
     *
     * <p>A workspace is kept when a session in this process has claimed it, or another process (a second
     * Editora) holds its Eclipse lock. The claim is taken here for the duration of the delete, so no session can
     * start on a half-deleted cache. Failure markers go with their workspace. Blocking I/O: call it off the FX
     * thread.
     */
    public static JdtlsCacheClearResult clearJdtlsWorkspaces(Path base) {
        if (base == null || !Files.isDirectory(base)) {
            return new JdtlsCacheClearResult(0, 0, 0);
        }
        List<Path> entries;
        try (var list = Files.list(base)) {
            entries = list.sorted().toList();
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.FINE, "Could not list " + base, e);
            return new JdtlsCacheClearResult(0, 0, 0);
        }
        int deleted = 0;
        int kept = 0;
        long freed = 0;
        for (Path dir : entries) {
            if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String name = dir.getFileName().toString();
            if (!claimedJdtlsWorkspaces.add(name)) {
                kept++; // a live session in some window of this process
                continue;
            }
            try {
                long size = treeSize(dir);
                if (deleteUnlockedJdtlsWorkspace(dir)) {
                    deleted++;
                    freed += size;
                } else {
                    kept++;
                }
            } finally {
                claimedJdtlsWorkspaces.remove(name);
            }
        }
        deleteOrphanedFailureMarkers(base);
        return new JdtlsCacheClearResult(deleted, freed, kept);
    }

    /** Deletes the {@code .<name>.initialize-failed} markers whose workspace no longer exists. */
    private static void deleteOrphanedFailureMarkers(Path base) {
        String suffix = ".initialize-failed";
        try (var list = Files.list(base)) {
            for (Path marker : list.toList()) {
                String file = marker.getFileName().toString();
                if (file.startsWith(".") && file.endsWith(suffix) && file.length() > suffix.length() + 1) {
                    String name = file.substring(1, file.length() - suffix.length());
                    if (!Files.exists(base.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(marker);
                    }
                }
            }
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.FINE, "Could not clear failure markers in " + base, e);
        }
    }

    /** Total size of the regular files under {@code dir}, without following links; 0 when unreadable. */
    private static long treeSize(Path dir) {
        try (var paths = Files.walk(dir)) {
            return paths.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (java.io.IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    private @Nullable LanguageServerSession sessionFor(@Nullable Path file) {
        return file == null ? null : sessionByDocUri.get(uri(file));
    }

    // --- Request errors (a broken server must not look like a quiet one; Maven review C2) ---------

    /** Told, on the FX thread, when a server answers a request with a fault inside itself. */
    @FunctionalInterface
    public interface RequestErrorHandler {
        /**
         * {@code method} on {@code serverId} (session root {@code root}) failed with {@code summary} (the server's
         * message and the first line of its data). {@code failing}: this was the {@value RequestErrorTracker#CONSECUTIVE_LIMIT}th
         * fault in a row with no successful answer in between — the server should be given up on.
         * {@code announce}: the first fault of this method on this server since it started.
         */
        void onRequestError(
                String serverId, Path root, String method, String summary, boolean announce, boolean failing);
    }

    private final RequestErrorTracker requestErrors = new RequestErrorTracker();

    private volatile RequestErrorHandler onRequestError = (id, root, method, summary, announce, failing) -> {};

    public void setOnRequestError(RequestErrorHandler handler) {
        this.onRequestError = handler == null ? (id, root, method, summary, announce, failing) -> {} : handler;
    }

    /**
     * Records how {@code method} on {@code s} ended. A server fault ({@link RequestErrorTracker#serverFault}) is
     * logged at WARNING with the server's {@code data} — lemminx's is the stack trace that names the missing
     * class — and reported to {@link #setOnRequestError}; any other error (a cancellation, a transport failure,
     * a refusal) is the caller's business as before. A success ends the server's run of faults.
     */
    void noteRequestOutcome(LanguageServerSession s, String method, Throwable error) {
        if (s == null) {
            return;
        }
        String key = sessionKey(s.serverId(), s.root());
        if (error == null) {
            requestErrors.onSuccess(key);
            return;
        }
        var response = RequestErrorTracker.responseError(error);
        if (response == null || !RequestErrorTracker.serverFault(response.getCode())) {
            return;
        }
        String summary = RequestErrorTracker.describe(response);
        LOG.warning(() -> s.serverId() + ": " + method + " failed inside the server with " + response.getCode() + " "
                + summary + (response.getData() == null ? "" : "\n" + response.getData()));
        RequestErrorTracker.Verdict verdict = requestErrors.onError(key, method);
        if (verdict.announce() || verdict.markFailing()) {
            String serverId = s.serverId();
            Path root = s.root();
            Platform.runLater(() -> {
                if (!closed) {
                    onRequestError.onRequestError(
                            serverId, root, method, summary, verdict.announce(), verdict.markFailing());
                }
            });
        }
    }

    // --- Requests (results marshaled to the FX thread) -----------------------------------------

    public void completion(Path file, int line, int character, Consumer<List<CompletionItem>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.completion(uri(file), new Position(line, character)).whenComplete((result, error) -> {
            List<CompletionItem> items = error == null ? CompletionMapper.itemsOf(result) : List.of();
            Platform.runLater(() -> cb.accept(items));
        });
    }

    /** Maps and ranks off FX; cancellation is wired to the original JSON-RPC future. */
    public Runnable completion(
            Path file,
            int line,
            int character,
            int triggerKind,
            @Nullable String triggerCharacter,
            java.util.function.Function<CompletionItem, @Nullable Runnable> onAccept,
            Consumer<com.editora.completion.CompletionResult> cb) {
        LanguageServerSession session = sessionFor(file);
        if (session == null) {
            cb.accept(com.editora.completion.CompletionResult.EMPTY);
            return () -> {};
        }
        var context =
                new org.eclipse.lsp4j.CompletionContext(org.eclipse.lsp4j.CompletionTriggerKind.forValue(triggerKind));
        context.setTriggerCharacter(triggerCharacter);
        long sent = com.editora.completion.CompletionTrace.now();
        var request = session.completion(uri(file), new Position(line, character), context);
        if (sent != 0) request.whenComplete((r, e) -> com.editora.completion.CompletionTrace.elapsed("server", sent));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        request.whenCompleteAsync((result, error) -> {
            if (cancelled.get()) return;
            long mapping = com.editora.completion.CompletionTrace.now();
            var items = error == null ? CompletionMapper.itemsOf(result) : List.<CompletionItem>of();
            var options = session.capabilities() == null
                    ? null
                    : session.capabilities().getCompletionProvider();
            if (options != null && options.getAllCommitCharacters() != null) {
                for (var item : items) {
                    if (item != null && item.getCommitCharacters() == null)
                        item.setCommitCharacters(options.getAllCommitCharacters());
                }
            }
            var mapped =
                    com.editora.completion.CompletionEngine.sortLspByRelevance(CompletionMapper.map(items, onAccept));
            boolean incomplete = error == null
                    && result != null
                    && result.isRight()
                    && result.getRight() != null
                    && result.getRight().isIncomplete();
            var batch = new com.editora.completion.CompletionResult(mapped, incomplete);
            com.editora.completion.CompletionTrace.elapsed("map-and-rank", mapping);
            Platform.runLater(() -> {
                if (!cancelled.get() && sessionFor(file) == session) cb.accept(batch);
            });
        });
        return () -> {
            cancelled.set(true);
            request.cancel(true);
        };
    }

    /**
     * Resolves {@code item} (for {@code file}) and delivers its {@code additionalTextEdits} — the extra
     * edits a completion implies, e.g. a TypeScript auto-import's {@code import} line — to {@code cb} on
     * the FX thread (empty list if none or unresolved).
     */
    public void resolveCompletion(Path file, CompletionItem item, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || item == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.resolveCompletion(item).whenComplete((resolved, error) -> {
            List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
            CompletionItem use = error == null && resolved != null ? resolved : item;
            if (use.getAdditionalTextEdits() != null) {
                for (org.eclipse.lsp4j.TextEdit e : use.getAdditionalTextEdits()) {
                    if (e != null && e.getRange() != null) {
                        edits.add(new com.editora.text.LspTextEdit(
                                e.getRange().getStart().getLine(),
                                e.getRange().getStart().getCharacter(),
                                e.getRange().getEnd().getLine(),
                                e.getRange().getEnd().getCharacter(),
                                e.getNewText() == null ? "" : e.getNewText()));
                    }
                }
            }
            Platform.runLater(() -> cb.accept(edits));
        });
    }

    /**
     * Resolves the documentation for a completion item (the opaque {@code token} is the LSP
     * {@code CompletionItem} from {@link CompletionMapper}) and delivers its markdown/plain-text body to
     * {@code cb} on the FX thread — {@code null} when there is none. Short-circuits when the item already
     * carries its documentation (many servers send it eagerly), else issues {@code completionItem/resolve}.
     * Drives the IntelliJ-style documentation side-popup.
     */
    public void resolveCompletionDoc(Path file, Object token, Consumer<String> cb) {
        if (!(token instanceof CompletionItem item)) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String existing = documentationOf(item);
        LanguageServerSession s = sessionFor(file);
        if (existing != null || s == null) {
            Platform.runLater(() -> cb.accept(existing));
            return;
        }
        s.resolveCompletion(item).whenComplete((resolved, error) -> {
            String doc = (error == null && resolved != null) ? documentationOf(resolved) : null;
            Platform.runLater(() -> cb.accept(doc));
        });
    }

    /** The item's documentation as a string (markdown or plain text), or null/blank → null. */
    private static @Nullable String documentationOf(CompletionItem item) {
        var doc = item.getDocumentation();
        if (doc == null) {
            return null;
        }
        String s = doc.isLeft()
                ? doc.getLeft()
                : (doc.getRight() == null ? null : doc.getRight().getValue());
        return (s == null || s.isBlank()) ? null : s;
    }

    /** True if {@code file}'s server is ready and advertises whole-document formatting. */
    public boolean supportsFormatting(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && formattingProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises signature help (#674). */
    public boolean supportsSignatureHelp(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && signatureHelpProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code signatureHelpProvider} (null-safe). */
    static boolean signatureHelpProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && caps.getSignatureHelpProvider() != null;
    }

    /**
     * Whether <b>any</b> running Java server advertises the java-debug commands — true when jdtls already has
     * the plugin (some distributions, e.g. Homebrew's jdtls, ship and auto-load it), so debugging works
     * without Editora injecting a bundle jar of its own. See {@link #providesJavaDebug}.
     */
    public boolean javaDebugCommandsAvailable() {
        String prefix = sessionKeyPrefix("java");
        for (Map.Entry<String, LanguageServerSession> e : sessionsByRoot.entrySet()) {
            if (e.getKey().startsWith(prefix) && providesJavaDebug(e.getValue().capabilities())) {
                return true;
            }
        }
        return false;
    }

    /** The jdtls command that starts a debug session; its presence means the java-debug plugin is loaded. */
    public static final String JAVA_DEBUG_COMMAND = "vscode.java.startDebugSession";

    /** Pure: whether {@code caps} advertises the java-debug {@code startDebugSession} command (null-safe). */
    static boolean providesJavaDebug(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getExecuteCommandProvider() == null) {
            return false;
        }
        java.util.List<String> commands = caps.getExecuteCommandProvider().getCommands();
        return commands != null && commands.contains(JAVA_DEBUG_COMMAND);
    }

    /** The signature-help trigger characters {@code file}'s server advertised (usually {@code (} and
     *  {@code ,}), retrigger characters included; empty when not ready / none. */
    public java.util.Set<Character> signatureTriggerCharacters(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? java.util.Set.of() : signatureTriggerCharsOf(s.capabilities());
    }

    /** Pure extraction of signature-help trigger + retrigger characters (null-safe). */
    static java.util.Set<Character> signatureTriggerCharsOf(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getSignatureHelpProvider() == null) {
            return java.util.Set.of();
        }
        java.util.Set<Character> out = new java.util.HashSet<>();
        addFirstChars(out, caps.getSignatureHelpProvider().getTriggerCharacters());
        addFirstChars(out, caps.getSignatureHelpProvider().getRetriggerCharacters());
        return out;
    }

    private static void addFirstChars(java.util.Set<Character> out, List<String> tokens) {
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isEmpty()) {
                    out.add(t.charAt(0));
                }
            }
        }
    }

    /**
     * Requests signature help at a 0-based position; the raw lsp4j result (or null) arrives on the FX thread
     * — resolved for display via {@link SignatureFormat#resolve}. {@code triggerChar} is the character that
     * fired it ({@code null} = the explicit command), {@code retrigger} that a popup is already open (#725).
     */
    public void signatureHelp(
            Path file,
            int line,
            int character,
            String triggerChar,
            boolean retrigger,
            Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        signatureHelp(file, line, character, triggerChar, retrigger, null, cb);
    }

    public void signatureHelp(
            Path file,
            int line,
            int character,
            @Nullable String triggerChar,
            boolean retrigger,
            org.eclipse.lsp4j.@Nullable SignatureHelp activeHelp,
            Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String uri = uri(file);
        // Never joined (the context differs call to call), but a newer request still cancels the older one:
        // the popup only ever takes the latest answer.
        latest.issue(
                uri,
                "signatureHelp",
                new Object(),
                true,
                () -> s.signatureHelp(uri, new Position(line, character), triggerChar, retrigger, activeHelp),
                (r, e) -> Platform.runLater(() -> cb.accept(e == null ? r : null)));
    }

    /** True if {@code file}'s server is ready and advertises document highlight (occurrences — #675). */
    public boolean supportsDocumentHighlight(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && documentHighlightProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentHighlightProvider} (null-safe). */
    static boolean documentHighlightProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentHighlightProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Occurrences of the symbol at a 0-based position, mapped to neutral {@link OccurrenceSpan}s
     *  (Write kind flagged) and delivered on the FX thread — empty when unavailable (#675). */
    public void documentHighlights(
            Path file, int line, int character, Consumer<List<com.editora.text.OccurrenceSpan>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = uri(file);
        Position at = new Position(line, character);
        latest.issue(
                uri,
                "documentHighlight",
                stamp(s, uri, at),
                true,
                () -> s.documentHighlight(uri, at),
                (result, error) -> {
                    List<com.editora.text.OccurrenceSpan> spans = new ArrayList<>();
                    if (error == null && result != null) {
                        for (var h : result) {
                            if (h == null || h.getRange() == null) {
                                continue;
                            }
                            var r = h.getRange();
                            spans.add(new com.editora.text.OccurrenceSpan(
                                    r.getStart().getLine(),
                                    r.getStart().getCharacter(),
                                    r.getEnd().getLine(),
                                    r.getEnd().getCharacter(),
                                    h.getKind() == org.eclipse.lsp4j.DocumentHighlightKind.Write));
                        }
                    }
                    Platform.runLater(() -> cb.accept(spans));
                });
    }

    // --- Watched files (#677) ------------------------------------------------------------------

    /** An external file change to forward to the language servers. */
    public enum WatchedKind {
        CREATED,
        CHANGED,
        DELETED
    }

    /** One changed file + what happened to it. */
    public record WatchedFile(Path file, WatchedKind kind) {}

    /**
     * Folds two events for one path inside a coalescing window into what the server should hear, or null for
     * nothing at all. Pure. A file written fresh is created then modified: still {@code CREATED}. Created then
     * deleted never existed for the server. Deleted then created (an atomic save) is a change.
     */
    public static @Nullable WatchedKind mergeWatched(@Nullable WatchedKind earlier, WatchedKind later) {
        if (earlier == null || later == null) {
            return later == null ? earlier : later;
        }
        return switch (earlier) {
            case CREATED -> later == WatchedKind.DELETED ? null : WatchedKind.CREATED;
            case DELETED -> later == WatchedKind.DELETED ? WatchedKind.DELETED : WatchedKind.CHANGED;
            case CHANGED -> later == WatchedKind.DELETED ? WatchedKind.DELETED : WatchedKind.CHANGED;
        };
    }

    /**
     * Forwards external file changes to every live session whose root contains them
     * ({@code workspace/didChangeWatchedFiles}) — how a git checkout / CLI build / external editor reaches
     * the servers' project models instead of leaving them stale until restart (#677). Callers coalesce
     * bursts (a branch switch touches hundreds of files); one notification per session per batch.
     */
    public void notifyWatchedFiles(List<WatchedFile> changes) {
        if (!enabled || changes == null || changes.isEmpty()) {
            return;
        }
        for (LanguageServerSession s : sessionsByRoot.values()) {
            List<org.eclipse.lsp4j.FileEvent> events = eventsUnderRoot(changes, s.root(), s.uris());
            if (!events.isEmpty()) {
                s.didChangeWatchedFiles(events);
            }
        }
        changes.stream().map(WatchedFile::file).filter(f -> f != null).forEach(this::projectStyleChanged);
    }

    /**
     * {@code file} changed (saved here, or seen by a watcher): when it is one the project's Java style is read
     * from, the Java servers whose project holds it get the style again (re-review AN5).
     */
    public void projectStyleChanged(Path file) {
        if (file == null || !JavaProjectSettings.isStyleSource(file)) {
            return;
        }
        Path at = file.toAbsolutePath().normalize();
        for (Map.Entry<String, LanguageServerSession> e : sessionsByRoot.entrySet()) {
            LanguageServerSession s = e.getValue();
            if (e.getKey().startsWith(sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID))
                    && at.startsWith(s.root().toAbsolutePath().normalize())) {
                s.refreshProjectSettings();
            }
        }
    }

    /** Pure: the {@code FileEvent}s for the changes under {@code root} (path-component containment). */
    static List<org.eclipse.lsp4j.FileEvent> eventsUnderRoot(List<WatchedFile> changes, Path root) {
        return eventsUnderRoot(changes, root, WireUris.LOCAL);
    }

    /** As above, with each file named the way {@code uris}'s server names it. */
    static List<org.eclipse.lsp4j.FileEvent> eventsUnderRoot(List<WatchedFile> changes, Path root, WireUris uris) {
        Path rootNorm = root.toAbsolutePath().normalize();
        List<org.eclipse.lsp4j.FileEvent> out = new ArrayList<>();
        for (WatchedFile c : changes) {
            if (c == null || c.file() == null) {
                continue;
            }
            Path p = c.file().toAbsolutePath().normalize();
            if (!p.startsWith(rootNorm)) {
                continue;
            }
            var type = switch (c.kind()) {
                case CREATED -> org.eclipse.lsp4j.FileChangeType.Created;
                case CHANGED -> org.eclipse.lsp4j.FileChangeType.Changed;
                case DELETED -> org.eclipse.lsp4j.FileChangeType.Deleted;
            };
            out.add(new org.eclipse.lsp4j.FileEvent(uris.toWire(DocumentUris.of(p)), type));
        }
        return out;
    }

    /**
     * A single inlay hint: 0-based position + the rendered label ({@code x:} for a parameter name) +
     * whether it is a <b>parameter</b> hint rather than a type hint.
     *
     * <p>The kind is carried because filtering (#823) applies only to parameter hints: suppressing an
     * uninformative name, or one labelling a non-literal argument, is about how an <em>argument</em> is
     * named — whereas a {@code : String} type hint is information the source does not otherwise carry and
     * must survive every mode.
     */
    public record InlayHintSpan(
            int line, int col, String label, boolean parameter, boolean paddingLeft, boolean paddingRight) {

        /** A parameter hint — the dominant kind, and what a server omitting the kind is assumed to mean. */
        public InlayHintSpan(int line, int col, String label) {
            this(line, col, label, true);
        }

        public InlayHintSpan(int line, int col, String label, boolean parameter) {
            this(line, col, label, parameter, false, false);
        }

        /**
         * The label as drawn, with the space the server asked for on either side: jdtls's lambda type hint
         * ({@code paddingRight}) read "Strings -> …" without it (re-review DN2).
         */
        public String paddedLabel() {
            return (paddingLeft ? " " : "") + label + (paddingRight ? " " : "");
        }
    }

    /** What a code lens counts — which decides what a click on it opens. */
    public enum CodeLensKind {
        REFERENCES,
        IMPLEMENTATIONS
    }

    /** One code lens: where its declaration's name starts, what it says, and what it counts. */
    public record CodeLensSpan(int line, int col, String title, CodeLensKind kind) {}

    /** True if {@code file}'s server is ready and advertises code lenses. */
    public boolean supportsCodeLens(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && s.capabilities() != null && s.capabilities().getCodeLensProvider() != null;
    }

    /**
     * Pure: what a lens counts, read off its command id ({@code java.show.references},
     * {@code java.show.implementations}, {@code editor.action.showReferences}, …) — or null for a lens that
     * does something else (a "Run | Debug" lens), which is not shown: its command is the server's own
     * editor integration and nothing here could carry it out.
     */
    static @Nullable CodeLensKind codeLensKind(org.eclipse.lsp4j.Command command) {
        String id = command == null || command.getCommand() == null
                ? ""
                : command.getCommand().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("implementation")) {
            return CodeLensKind.IMPLEMENTATIONS;
        }
        return id.contains("reference") ? CodeLensKind.REFERENCES : null;
    }

    /** Pure: the lenses worth showing, as neutral spans in line order. */
    static List<CodeLensSpan> codeLensSpans(List<org.eclipse.lsp4j.CodeLens> lenses) {
        List<CodeLensSpan> out = new ArrayList<>();
        if (lenses == null) {
            return out;
        }
        for (org.eclipse.lsp4j.CodeLens lens : lenses) {
            if (lens == null || lens.getRange() == null || lens.getCommand() == null) {
                continue;
            }
            CodeLensKind kind = codeLensKind(lens.getCommand());
            String title = lens.getCommand().getTitle();
            if (kind == CodeLensKind.IMPLEMENTATIONS
                    && title != null
                    && title.strip().startsWith("0 ")) {
                continue; // jdtls answers for every type and method; "0 implementations" on each is noise
            }
            if (kind != null && title != null && !title.isBlank()) {
                Position start = lens.getRange().getStart();
                out.add(new CodeLensSpan(start.getLine(), start.getCharacter(), title.strip(), kind));
            }
        }
        out.sort(java.util.Comparator.comparingInt(CodeLensSpan::line).thenComparing(CodeLensSpan::kind));
        return out;
    }

    /**
     * Requests the code lenses on lines {@code [startLine..endLine]}, delivered as neutral spans on the FX
     * thread — empty when unsupported or failed.
     */
    public void requestCodeLens(Path file, int startLine, int endLine, Consumer<List<CodeLensSpan>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = uri(file);
        latest.issue(
                uri,
                "codeLens",
                stamp(s, uri, List.of(startLine, endLine)),
                true,
                () -> s.codeLens(uri, startLine, endLine),
                (lenses, error) -> {
                    List<CodeLensSpan> out = error == null ? codeLensSpans(lenses) : List.of();
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /** True if {@code file}'s server is ready and advertises inlay hints (#681). */
    public boolean supportsInlayHints(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && inlayHintProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code inlayHintProvider} (null-safe, either form). */
    static boolean inlayHintProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getInlayHintProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * The LSP range covering lines {@code [startLine..endLine]} <b>inclusive</b> of a document with
     * {@code lineCount} lines whose last line is {@code lastLineLength} characters long. Pure; unit-tested.
     *
     * <p>Covering line {@code endLine} means an exclusive end of {@code Position(endLine + 1, 0)} — but for
     * the <em>last</em> line that names a line the document does not have, and <b>jdtls answers an
     * out-of-range range with an empty list rather than an error</b>. That is the whole of #715: inlay hints
     * silently never appeared because the padded window always reached past the end of the file, and the
     * empty response is indistinguishable from "this file has no hints". Measured on jdtls 1.60 with a
     * 27-line file (last line index 26): end {@code Position(27,0)} → 0 hints, {@code Position(26,0)} → 6.
     * Unchanged in 1.62 ({@code JdtlsUpgradeProbeTest}: 0 hints past the end, 5 clamped).
     *
     * <p>So when {@code endLine + 1} would leave the document, the end becomes the true document end —
     * {@code Position(lastLine, lastLineLength)} — which still covers the last line in full.
     */
    static org.eclipse.lsp4j.Range inclusiveLineRange(int startLine, int endLine, int lineCount, int lastLineLength) {
        int lastLine = Math.max(0, lineCount - 1);
        int start = Math.min(Math.max(0, startLine), lastLine);
        int endExclusive = Math.max(start, Math.max(0, endLine)) + 1;
        Position end = endExclusive <= lastLine
                ? new Position(endExclusive, 0)
                : new Position(lastLine, Math.max(0, lastLineLength));
        return new org.eclipse.lsp4j.Range(new Position(start, 0), end);
    }

    /**
     * Requests inlay hints over the line window {@code [startLine..endLine]} (inclusive), delivered as
     * neutral spans on the FX thread — empty when unsupported/failed (#681). {@code lineCount} and
     * {@code lastLineLength} describe the document so the range can be clamped to it: a range reaching past
     * the last line makes jdtls return nothing at all (see {@link #inclusiveLineRange} — #715).
     */
    public void requestInlayHints(
            Path file,
            int startLine,
            int endLine,
            int lineCount,
            int lastLineLength,
            Consumer<List<InlayHintSpan>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var range = inclusiveLineRange(startLine, endLine, lineCount, lastLineLength);
        String uri = uri(file);
        latest.issue(uri, "inlayHint", stamp(s, uri, range), true, () -> s.inlayHint(uri, range), (hints, error) -> {
            List<InlayHintSpan> out = new ArrayList<>();
            if (error == null && hints != null) {
                for (var h : hints) {
                    if (h == null || h.getPosition() == null) {
                        continue;
                    }
                    String label = inlayLabel(h);
                    if (!label.isBlank()) {
                        out.add(new InlayHintSpan(
                                h.getPosition().getLine(),
                                h.getPosition().getCharacter(),
                                label,
                                isParameterHint(h.getKind(), label),
                                Boolean.TRUE.equals(h.getPaddingLeft()),
                                Boolean.TRUE.equals(h.getPaddingRight())));
                    }
                }
            }
            Platform.runLater(() -> cb.accept(out));
        });
    }

    /**
     * Pure: whether a hint names an argument (parameter) rather than an inferred type, used to decide
     * what filtering may touch (#823).
     *
     * <p>{@code kind} is <b>optional</b> in the protocol, and a server that omits it would otherwise have
     * every hint treated as a parameter — so an unkinded {@code : String} would become filterable. The
     * fallback reads the label instead: a parameter name ends with {@code :} or {@code =} ({@code count:},
     * {@code ...args:}); anything else is a type — jdtls, which sends no kind, labels a {@code var} with
     * {@code : int} and a lambda parameter with a bare {@code String}.
     */
    static boolean isParameterHint(org.eclipse.lsp4j.InlayHintKind kind, String label) {
        if (kind == org.eclipse.lsp4j.InlayHintKind.Parameter) {
            return true;
        }
        if (kind == org.eclipse.lsp4j.InlayHintKind.Type) {
            return false;
        }
        String l = label == null ? "" : label.strip();
        return !l.startsWith(":") && (l.endsWith(":") || l.endsWith("="));
    }

    /** Pure: an inlay hint's display label — the plain string, or its label parts joined. */
    static String inlayLabel(org.eclipse.lsp4j.InlayHint h) {
        if (h.getLabel() == null) {
            return "";
        }
        if (h.getLabel().isLeft()) {
            return h.getLabel().getLeft() == null ? "" : h.getLabel().getLeft().strip();
        }
        StringBuilder sb = new StringBuilder();
        if (h.getLabel().getRight() != null) {
            for (var part : h.getLabel().getRight()) {
                if (part != null && part.getValue() != null) {
                    sb.append(part.getValue());
                }
            }
        }
        return sb.toString().strip();
    }

    // --- Call / type hierarchy (#682) ----------------------------------------------------------

    /** One hierarchy row: display fields + a navigable position ({@code file} null for a non-file URI,
     *  e.g. a JDK class) + the opaque lsp4j item ({@code raw}) used to expand its children; {@code calls} is how
     *  many call sites the row stands for (a callee called twice is one row, Java review B19). */
    public record HierarchyNode(
            String name,
            String detail,
            String kindName,
            @Nullable Path file,
            int line,
            int col,
            Object raw,
            int calls) {

        public HierarchyNode(
                String name, String detail, String kindName, @Nullable Path file, int line, int col, Object raw) {
            this(name, detail, kindName, file, line, col, raw, 1);
        }

        HierarchyNode withCalls(int calls) {
            return new HierarchyNode(name, detail, kindName, file, line, col, raw, calls);
        }

        /** The {@code jdt://} class-file URI of a JDK or library node (no file), opened as library source. */
        public @Nullable String libraryUri() {
            String uri = raw instanceof org.eclipse.lsp4j.CallHierarchyItem c
                    ? c.getUri()
                    : raw instanceof org.eclipse.lsp4j.TypeHierarchyItem t ? t.getUri() : null;
            return file == null && uri != null && uri.startsWith("jdt:") ? uri : null;
        }
    }

    /** True if {@code file}'s server is ready and advertises call hierarchy (#682). */
    public boolean supportsCallHierarchy(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && callHierarchyProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises type hierarchy (#682). */
    public boolean supportsTypeHierarchy(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && typeHierarchyProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code callHierarchyProvider} (null-safe). */
    static boolean callHierarchyProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getCallHierarchyProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Pure: whether a server's capabilities include {@code typeHierarchyProvider} (null-safe). */
    static boolean typeHierarchyProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getTypeHierarchyProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** The call-hierarchy anchor item(s) at a 0-based position, delivered on the FX thread (#682). */
    public void prepareCallHierarchy(Path file, int line, int character, Consumer<List<HierarchyNode>> cb) {
        prepareCallHierarchyAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void prepareCallHierarchyAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<HierarchyNode>> cb) {
        LanguageServerSession s = sessionFor(anchor);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.prepareCallHierarchy(documentUri != null ? documentUri : uri(anchor), new Position(line, character))
                .whenComplete((items, e) -> {
                    List<HierarchyNode> out = new ArrayList<>();
                    if (e == null && items != null) {
                        for (var i : items) {
                            if (i != null) {
                                out.add(callNode(i, s.uris()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /** The callers ({@code incoming=true}) or callees of a call-hierarchy node's {@code raw} item. */
    public void callHierarchyChildren(Path anchor, Object raw, boolean incoming, Consumer<List<HierarchyNode>> cb) {
        LanguageServerSession s = sessionFor(anchor);
        if (s == null || !(raw instanceof org.eclipse.lsp4j.CallHierarchyItem item)) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        if (incoming) {
            s.incomingCalls(item).whenComplete((calls, e) -> {
                List<HierarchyNode> out = new ArrayList<>();
                if (e == null && calls != null) {
                    for (var c : calls) {
                        if (c != null && c.getFrom() != null) {
                            out.add(callNode(c.getFrom(), s.uris()).withCalls(callSites(c.getFromRanges())));
                        }
                    }
                }
                List<HierarchyNode> merged = mergeCalls(out);
                Platform.runLater(() -> cb.accept(merged));
            });
        } else {
            s.outgoingCalls(item).whenComplete((calls, e) -> {
                List<HierarchyNode> out = new ArrayList<>();
                if (e == null && calls != null) {
                    for (var c : calls) {
                        if (c != null && c.getTo() != null) {
                            out.add(callNode(c.getTo(), s.uris()).withCalls(callSites(c.getFromRanges())));
                        }
                    }
                }
                List<HierarchyNode> merged = mergeCalls(out);
                Platform.runLater(() -> cb.accept(merged));
            });
        }
    }

    private static int callSites(@Nullable List<?> ranges) {
        return ranges == null || ranges.isEmpty() ? 1 : ranges.size();
    }

    /**
     * One row per called (or calling) member, its call sites added up, in first-seen order. jdtls lists a member
     * once per call, so {@code append(String)} called twice was two rows that went to the same place. Pure.
     */
    static List<HierarchyNode> mergeCalls(List<HierarchyNode> nodes) {
        Map<String, HierarchyNode> byTarget = new java.util.LinkedHashMap<>();
        for (HierarchyNode n : nodes) {
            String key = n.name() + "\u0000" + n.detail() + "\u0000" + n.file() + "\u0000" + n.libraryUri() + "\u0000"
                    + n.line() + ":" + n.col();
            HierarchyNode seen = byTarget.get(key);
            byTarget.put(key, seen == null ? n : seen.withCalls(seen.calls() + n.calls()));
        }
        return List.copyOf(byTarget.values());
    }

    /** The type-hierarchy anchor item(s) at a 0-based position, delivered on the FX thread (#682). */
    public void prepareTypeHierarchy(Path file, int line, int character, Consumer<List<HierarchyNode>> cb) {
        prepareTypeHierarchyAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void prepareTypeHierarchyAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<HierarchyNode>> cb) {
        LanguageServerSession s = sessionFor(anchor);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.prepareTypeHierarchy(documentUri != null ? documentUri : uri(anchor), new Position(line, character))
                .whenComplete((items, e) -> {
                    List<HierarchyNode> out = new ArrayList<>();
                    if (e == null && items != null) {
                        for (var i : items) {
                            if (i != null) {
                                out.add(typeNode(i, s.uris()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /** The supertypes ({@code supertypes=true}) or subtypes of a type-hierarchy node's {@code raw} item. */
    public void typeHierarchyChildren(Path anchor, Object raw, boolean supertypes, Consumer<List<HierarchyNode>> cb) {
        LanguageServerSession s = sessionFor(anchor);
        if (s == null || !(raw instanceof org.eclipse.lsp4j.TypeHierarchyItem item)) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var fut = supertypes ? s.supertypes(item) : s.subtypes(item);
        fut.whenComplete((items, e) -> {
            List<HierarchyNode> out = new ArrayList<>();
            if (e == null && items != null) {
                for (var i : items) {
                    if (i != null) {
                        out.add(typeNode(i, s.uris()));
                    }
                }
            }
            Platform.runLater(() -> cb.accept(out));
        });
    }

    private static HierarchyNode callNode(org.eclipse.lsp4j.CallHierarchyItem i, WireUris uris) {
        var pos = i.getSelectionRange() != null
                ? i.getSelectionRange().getStart()
                : i.getRange() != null ? i.getRange().getStart() : new Position(0, 0);
        return new HierarchyNode(
                i.getName() == null ? "" : i.getName(),
                i.getDetail() == null ? "" : i.getDetail(),
                i.getKind() == null ? "" : i.getKind().toString().toLowerCase(java.util.Locale.ROOT),
                uris.toPath(i.getUri()),
                pos.getLine(),
                pos.getCharacter(),
                i);
    }

    private static HierarchyNode typeNode(org.eclipse.lsp4j.TypeHierarchyItem i, WireUris uris) {
        var pos = i.getSelectionRange() != null
                ? i.getSelectionRange().getStart()
                : i.getRange() != null ? i.getRange().getStart() : new Position(0, 0);
        return new HierarchyNode(
                i.getName() == null ? "" : i.getName(),
                i.getDetail() == null ? "" : i.getDetail(),
                i.getKind() == null ? "" : i.getKind().toString().toLowerCase(java.util.Locale.ROOT),
                uris.toPath(i.getUri()),
                pos.getLine(),
                pos.getCharacter(),
                i);
    }

    /** True if {@code file}'s server is ready and advertises rename (#676). */
    public boolean supportsRename(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && renameProvider(s.capabilities());
    }

    /** True if the server also advertises {@code prepareRename} (validate + placeholder). */
    public boolean supportsPrepareRename(Path file) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || s.capabilities() == null) {
            return false;
        }
        var p = s.capabilities().getRenameProvider();
        return p != null
                && p.isRight()
                && p.getRight() != null
                && Boolean.TRUE.equals(p.getRight().getPrepareProvider());
    }

    /** Pure: whether a server's capabilities include {@code renameProvider} (null-safe, either form). */
    static boolean renameProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getRenameProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** The outcome of {@code prepareRename}: whether renaming here is possible, and the placeholder (the
     *  current name) when the server supplied one — blank means "derive it from the document range". */
    public record RenamePrep(
            boolean allowed, String placeholder, int startLine, int startCol, int endLine, int endCol) {
        static final RenamePrep REFUSED = new RenamePrep(false, "", 0, 0, 0, 0);
    }

    /** Validates a rename at a 0-based position; the result arrives on the FX thread (#676). */
    public void prepareRename(Path file, int line, int character, Consumer<RenamePrep> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(RenamePrep.REFUSED));
            return;
        }
        s.prepareRename(uri(file), new Position(line, character))
                .whenComplete(
                        (r, e) -> Platform.runLater(() -> cb.accept(e != null ? RenamePrep.REFUSED : mapPrepare(r))));
    }

    /** Pure: maps a {@code prepareRename} response (any of its three shapes; null = refused). */
    static RenamePrep mapPrepare(
            org.eclipse.lsp4j.jsonrpc.messages.Either3<
                            org.eclipse.lsp4j.Range,
                            org.eclipse.lsp4j.PrepareRenameResult,
                            org.eclipse.lsp4j.PrepareRenameDefaultBehavior>
                    r) {
        if (r == null) {
            return RenamePrep.REFUSED; // per spec: null/undefined ⇒ rename not valid at this position
        }
        if (r.isFirst() && r.getFirst() != null) {
            var range = r.getFirst();
            return new RenamePrep(
                    true,
                    "",
                    range.getStart().getLine(),
                    range.getStart().getCharacter(),
                    range.getEnd().getLine(),
                    range.getEnd().getCharacter());
        }
        if (r.isSecond() && r.getSecond() != null) {
            var prep = r.getSecond();
            var range = prep.getRange();
            return new RenamePrep(
                    true,
                    prep.getPlaceholder() == null ? "" : prep.getPlaceholder(),
                    range == null ? 0 : range.getStart().getLine(),
                    range == null ? 0 : range.getStart().getCharacter(),
                    range == null ? 0 : range.getEnd().getLine(),
                    range == null ? 0 : range.getEnd().getCharacter());
        }
        if (r.isThird() && r.getThird() != null) {
            return new RenamePrep(true, "", 0, 0, 0, 0); // default behavior: rename the word at the caret
        }
        return RenamePrep.REFUSED;
    }

    /** Renames the symbol at a 0-based position to {@code newName}; the workspace edit applies through the
     *  registered handler and {@code cb} gets overall success on the FX thread (#676). */
    /**
     * Asks the server what a rename would change, <b>without applying it</b>, so the caller can preview and
     * filter first (#768). Delivers null when the server refuses or the edit contains an operation the mapper
     * will not perform.
     */
    public void previewRename(
            Path file, int line, int character, String newName, Consumer<WorkspaceEditMapper.Mapped> cb) {
        previewRename(file, line, character, newName, (mapped, why) -> cb.accept(mapped));
    }

    /** As above, with the server's reason when it refused the rename ("Rename to x failed" said nothing — BN9). */
    public void previewRename(
            Path file,
            int line,
            int character,
            String newName,
            java.util.function.BiConsumer<WorkspaceEditMapper.@Nullable Mapped, @Nullable String> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null, null));
            return;
        }
        EditBasis basis = editBasis(s);
        s.rename(uri(file), new Position(line, character), newName).whenComplete((edit, e) -> {
            EditPlan plan = e != null || edit == null ? EditPlan.REFUSED : planWorkspaceEdit(edit, basis);
            String why = e == null ? null : failureReason(e);
            Platform.runLater(() -> {
                if (!plan.blocked().isEmpty()) {
                    onEditBlocked.accept(plan.blocked());
                }
                cb.accept(plan.mapped(), why);
            });
        });
    }

    public void rename(Path file, int line, int character, String newName, Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        EditBasis basis = editBasis(s);
        s.rename(uri(file), new Position(line, character), newName)
                .whenComplete((edit, e) -> Platform.runLater(() -> {
                    if (e != null || edit == null) {
                        cb.accept(false);
                    } else {
                        applyWorkspaceEdit(edit, basis, cb);
                    }
                }));
    }

    /** True if {@code file}'s server is ready and advertises code actions (quick fixes — #670). */
    public boolean supportsCodeActions(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && codeActionProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code codeActionProvider} (null-safe). */
    static boolean codeActionProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getCodeActionProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Pure: whether a server's capabilities include {@code documentFormattingProvider} (null-safe). */
    static boolean formattingProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentFormattingProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** True if {@code file}'s server is ready and advertises range formatting (for Tab line re-indent). */
    public boolean supportsRangeFormatting(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && rangeFormattingProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentRangeFormattingProvider} (null-safe). */
    static boolean rangeFormattingProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentRangeFormattingProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * Pure: the characters a server formats on ({@code documentOnTypeFormattingProvider}), collected into one
     * set — the spec splits them into a mandatory {@code firstTriggerCharacter} and an optional
     * {@code moreTriggerCharacter} list, a distinction that matters to the server and not to us (#740).
     * Empty when the server has no provider, which is also how the feature stays inert for those servers.
     */
    static java.util.Set<Character> onTypeTriggersOf(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getDocumentOnTypeFormattingProvider() == null) {
            return java.util.Set.of();
        }
        var provider = caps.getDocumentOnTypeFormattingProvider();
        java.util.Set<Character> out = new java.util.HashSet<>();
        String first = provider.getFirstTriggerCharacter();
        if (first != null && !first.isEmpty()) {
            out.add(first.charAt(0));
        }
        if (provider.getMoreTriggerCharacter() != null) {
            for (String more : provider.getMoreTriggerCharacter()) {
                if (more != null && !more.isEmpty()) {
                    out.add(more.charAt(0));
                }
            }
        }
        return out;
    }

    /** The on-type formatting trigger characters for {@code file}'s server; empty when unsupported (#740). */
    public java.util.Set<Character> onTypeTriggerCharacters(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? java.util.Set.of() : onTypeTriggersOf(s.capabilities());
    }

    /**
     * On-type formatting ({@code textDocument/onTypeFormatting}, #740): the edits the server would apply
     * having just seen {@code ch} typed at the given position. Delivered on the FX thread; empty when the
     * server has no provider or the request fails.
     */
    public void onTypeFormatting(
            Path file,
            int line,
            int character,
            char ch,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || onTypeTriggersOf(s.capabilities()).isEmpty()) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.onTypeFormatting(
                        uri(file),
                        new Position(line, character),
                        String.valueOf(ch),
                        new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces))
                .whenComplete((result, error) -> {
                    List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.text.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(edits));
                });
    }

    /**
     * Requests range formatting ({@code textDocument/rangeFormatting}) over the line range
     * {@code [startLine..endLine]} and delivers the resulting edits to {@code cb} on the FX thread (empty
     * on error / unsupported). Used by Tab to re-indent the current line to the server's convention.
     */
    public void rangeFormatting(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        org.eclipse.lsp4j.Range range =
                new org.eclipse.lsp4j.Range(new Position(startLine, startChar), new Position(endLine, endChar));
        s.rangeFormatting(uri(file), range, new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces))
                .whenComplete((result, error) -> {
                    List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.text.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(edits));
                });
    }

    /**
     * Requests whole-document formatting ({@code textDocument/formatting}) for {@code file} and delivers the
     * resulting edits to {@code cb} on the FX thread (empty list if the server returns nothing or errors).
     * {@code tabSize}/{@code insertSpaces} are formatter hints (many formatters use their own config).
     */
    public void formatDocument(
            Path file, int tabSize, boolean insertSpaces, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.formatting(uri(file), new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces))
                .whenComplete((result, error) -> {
                    List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.text.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(edits));
                });
    }

    /** True if {@code file}'s server is ready and advertises the document-symbol outline (Structure window). */
    public boolean supportsDocumentSymbols(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && documentSymbolProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentSymbolProvider} (null-safe). */
    static boolean documentSymbolProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentSymbolProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * Requests the document-symbol outline ({@code textDocument/documentSymbol}) for {@code file} and delivers
     * the mapped {@link SymbolNode} tree to {@code cb} on the FX thread (empty when unsupported / on error).
     * Powers the Structure tool window for LSP-served files.
     */
    public void documentSymbols(Path file, Consumer<List<SymbolNode>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.documentSymbol(uri(file)).whenComplete((result, error) -> {
            List<SymbolNode> symbols = error == null && result != null ? DocumentSymbolMapper.map(result) : List.of();
            Platform.runLater(() -> cb.accept(symbols));
        });
    }

    /**
     * {@link #documentSymbols} for a caller that only ever wants the latest outline (the Structure window,
     * re-asked on every typing pause): a request for an older document version is cancelled, one already
     * running for this version is not repeated, and a superseded request never calls {@code cb}. A caller
     * that waits for its own answer must use {@link #documentSymbols}.
     */
    public void latestDocumentSymbols(Path file, Consumer<List<SymbolNode>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = uri(file);
        latest.issue(uri, "documentSymbol", stamp(s, uri, null), true, () -> s.documentSymbol(uri), (result, error) -> {
            List<SymbolNode> symbols = error == null && result != null ? DocumentSymbolMapper.map(result) : List.of();
            Platform.runLater(() -> cb.accept(symbols));
        });
    }

    /** What a deduplicated request's answer depends on: equal stamps mean the server is asked the same thing. */
    private record RequestStamp(
            @Nullable LanguageServerSession session,
            @Nullable Integer version,
            @Nullable Object arguments) {}

    private static RequestStamp stamp(LanguageServerSession session, String uri, @Nullable Object arguments) {
        return new RequestStamp(session, session.documentVersion(uri), arguments);
    }

    // --- folding ranges (#738) / selection ranges (#739) ---------------------------------------------

    /** Pure: whether a server's capabilities include {@code foldingRangeProvider} (null-safe). */
    static boolean foldingRangeProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getFoldingRangeProvider());
    }

    /** Pure: whether a server's capabilities include {@code selectionRangeProvider} (null-safe). */
    static boolean selectionRangeProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getSelectionRangeProvider());
    }

    /** True if {@code file}'s server is ready and advertises folding ranges (#738). */
    public boolean supportsFoldingRanges(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && foldingRangeProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises selection ranges (#739). */
    public boolean supportsSelectionRanges(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && selectionRangeProvider(s.capabilities());
    }

    /**
     * Requests the document's foldable regions and delivers them on the FX thread as editor
     * {@link com.editora.text.FoldRegions.Region}s, ordered the way the built-in detector emits them
     * (see {@link LspFolding#toRegions}). Empty when the server has no folding provider — the caller then
     * keeps the heuristic regions rather than un-folding the file.
     */
    public void foldingRanges(Path file, Consumer<List<com.editora.text.FoldRegions.Region>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || !foldingRangeProvider(s.capabilities())) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = uri(file);
        latest.issue(uri, "foldingRange", stamp(s, uri, null), true, () -> s.foldingRange(uri), (ranges, error) -> {
            List<com.editora.text.FoldRegions.Region> regions =
                    error == null ? LspFolding.toRegions(ranges) : List.of();
            Platform.runLater(() -> cb.accept(regions));
        });
    }

    /**
     * Requests the nested selection-range chain at one position and delivers it on the FX thread as
     * {@code [start, end]} offset pairs, innermost first (see {@link LspSelection#toOffsetChain}). Empty when
     * the server has no provider or answers nothing, so the caller falls back to the local ladder.
     *
     * <p>{@code lineLengths} maps the document's lines to their lengths — the response is in LSP
     * line/character coordinates and the caller works in offsets, and doing the conversion here keeps the
     * editor free of {@code lsp4j}.
     */
    public void selectionRanges(Path file, int line, int character, int[] lineStarts, Consumer<List<int[]>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || !selectionRangeProvider(s.capabilities())) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.selectionRange(uri(file), List.of(new Position(line, character))).whenComplete((ranges, error) -> {
            List<int[]> chain = error == null ? LspSelection.toOffsetChain(ranges, lineStarts) : List.of();
            Platform.runLater(() -> cb.accept(chain));
        });
    }

    /**
     * A project-wide symbol match ({@code workspace/symbol}): name + container + lowercased kind + location. A
     * symbol inside a JDK or dependency class (jdtls answers those under {@code jdt://}) has no {@code file};
     * {@code libraryUri} carries its URI and it opens as library source.
     */
    public record WorkspaceSymbolMatch(
            String name,
            String container,
            String kind,
            @Nullable Path file,
            int line,
            int character,
            @Nullable String libraryUri) {

        public WorkspaceSymbolMatch(String name, String container, String kind, Path file, int line, int character) {
            this(name, container, kind, file, line, character, null);
        }

        /** Whether this is a library symbol (no file of its own). */
        public boolean isLibrary() {
            return file == null;
        }

        /**
         * Where it is, for a picker row: the file name, or the JDK module / jar of a library symbol
         * ({@code jdt://contents/java.base/java.util/ArrayList.class} → {@code java.base}).
         */
        public String locationLabel() {
            if (file != null) {
                return String.valueOf(file.getFileName());
            }
            String uri = libraryUri == null ? "" : libraryUri;
            String rest =
                    uri.startsWith(JDT_SCHEME + "contents/") ? uri.substring((JDT_SCHEME + "contents/").length()) : "";
            int slash = rest.indexOf('/');
            String head = slash > 0 ? rest.substring(0, slash) : classFileTitle(uri);
            return java.net.URLDecoder.decode(head, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** True if {@code file}'s server is ready and advertises {@code workspace/symbol} (Go to Symbol). */
    public boolean supportsWorkspaceSymbols(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && workspaceSymbolProvider(s.capabilities());
    }

    /** Pure: whether the server advertises {@code workspace/symbol} (Boolean or options form). Null-safe. */
    static boolean workspaceSymbolProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getWorkspaceSymbolProvider());
    }

    /**
     * Runs {@code workspace/symbol} on {@code anchorFile}'s server for {@code query} and delivers the matches
     * on the FX thread. Empty when the file has no server. Handles both the legacy flat {@code SymbolInformation}
     * and the modern {@code WorkspaceSymbol} response shapes (the latter may carry a range-less URI-only
     * location, mapped to line 0).
     */
    public void workspaceSymbols(Path anchorFile, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        workspaceSymbols(sessionFor(anchorFile), query, cb);
    }

    private static void workspaceSymbols(
            @Nullable LanguageServerSession s, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.workspaceSymbol(query).whenComplete((result, error) -> {
            List<WorkspaceSymbolMatch> out = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    for (org.eclipse.lsp4j.SymbolInformation si : result.getLeft()) {
                        addSymbol(out, si.getName(), si.getContainerName(), si.getKind(), si.getLocation(), s.uris());
                    }
                } else if (result.getRight() != null) {
                    for (org.eclipse.lsp4j.WorkspaceSymbol ws : result.getRight()) {
                        addSymbol(out, ws.getName(), ws.getContainerName(), ws.getKind(), ws.getLocation(), s.uris());
                    }
                }
            }
            Platform.runLater(() -> cb.accept(out));
        });
    }

    /**
     * The JDK and dependency types ({@code jdt://}) matching {@code query}, from the Java server of the project
     * holding {@code underRoot} — the classes the server-free index cannot see (Java review E8). Empty without
     * one. Delivered on the FX thread.
     */
    public void javaLibraryTypes(@Nullable Path underRoot, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        LanguageServerSession s = javaSessionFor(underRoot);
        if (s == null || s.capabilities() == null) { // none, or not initialized yet
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        workspaceSymbols(s, query, all -> {
            List<WorkspaceSymbolMatch> types = new ArrayList<>();
            for (WorkspaceSymbolMatch m : all) {
                if (m.libraryUri() != null && LIBRARY_TYPE_KINDS.contains(m.kind())) {
                    types.add(m);
                }
            }
            cb.accept(types);
        });
    }

    private static final java.util.Set<String> LIBRARY_TYPE_KINDS =
            java.util.Set.of("class", "interface", "enum", "struct");

    /** Adds a {@code SymbolInformation} match (its location always carries a range). */
    private static void addSymbol(
            List<WorkspaceSymbolMatch> out,
            String name,
            String container,
            org.eclipse.lsp4j.SymbolKind kind,
            Location loc,
            WireUris uris) {
        if (loc == null) {
            return;
        }
        Path p = uris.toPath(loc.getUri());
        Position start = loc.getRange() != null ? loc.getRange().getStart() : new Position(0, 0);
        if (p == null) {
            // A JDK or dependency symbol (jdtls's jdt:// class-file URI): kept, opened as library source.
            if (loc.getUri() != null && loc.getUri().startsWith(JDT_SCHEME)) {
                out.add(new WorkspaceSymbolMatch(
                        name, container, kindName(kind), null, start.getLine(), start.getCharacter(), loc.getUri()));
            }
            return;
        }
        out.add(new WorkspaceSymbolMatch(name, container, kindName(kind), p, start.getLine(), start.getCharacter()));
    }

    /** Adds a {@code WorkspaceSymbol} match whose location is {@code Either<Location, URI-only>}. */
    private static void addSymbol(
            List<WorkspaceSymbolMatch> out,
            String name,
            String container,
            org.eclipse.lsp4j.SymbolKind kind,
            org.eclipse.lsp4j.jsonrpc.messages.Either<Location, org.eclipse.lsp4j.WorkspaceSymbolLocation> loc,
            WireUris uris) {
        if (loc == null) {
            return;
        }
        if (loc.isLeft()) {
            addSymbol(out, name, container, kind, loc.getLeft(), uris);
        } else if (loc.getRight() != null) {
            Path p = uris.toPath(loc.getRight().getUri());
            if (p != null) {
                out.add(new WorkspaceSymbolMatch(name, container, kindName(kind), p, 0, 0));
            }
        }
    }

    private static String kindName(org.eclipse.lsp4j.SymbolKind k) {
        return k == null ? "" : k.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** True if {@code file}'s server is ready and advertises range semantic tokens (viewport highlighting). */
    public boolean supportsSemanticTokens(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && semanticTokensProvider(s.capabilities()) != null;
    }

    /**
     * Pure: the server's semantic-tokens registration options if it supports range <em>or</em> full
     * requests (and carries a legend), else {@code null}. Null-safe — gates {@link #requestSemanticTokens},
     * which prefers range (viewport) and falls back to full for range-less servers.
     */
    static org.eclipse.lsp4j.@Nullable SemanticTokensWithRegistrationOptions semanticTokensProvider(
            org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return null;
        }
        var p = caps.getSemanticTokensProvider();
        if (p == null || p.getLegend() == null) {
            return null;
        }
        return (eitherTrue(p.getRange()) || eitherTrue(p.getFull())) ? p : null;
    }

    /** True if an LSP {@code Either<Boolean, ?>} capability is present (right form) or explicitly true. */
    private static boolean eitherTrue(org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, ?> e) {
        return e != null && (e.isRight() || Boolean.TRUE.equals(e.getLeft()));
    }

    /** The cached whole-document token state for delta requests (#679): the server's {@code resultId} +
     *  the full data array it identifies. Keyed by document URI; dropped on close/shutdown/error. */
    private record TokenState(LanguageServerSession session, String resultId, List<Integer> data) {}

    private final Map<String, TokenState> semanticTokenState = new ConcurrentHashMap<>();
    private final Map<String, Long> semanticRequestGeneration = new ConcurrentHashMap<>();
    private final Map<String, Long> diagnosticRequestGeneration = new ConcurrentHashMap<>();
    /** Source of {@link #diagnosticRequestGeneration} values. One counter for every document, so a value is
     *  never reused: an entry can then be removed when its document closes without a late reply for the
     *  closed document ever matching the generation of a reopened one. */
    private final java.util.concurrent.atomic.AtomicLong diagnosticGenerations =
            new java.util.concurrent.atomic.AtomicLong();

    private long nextDiagnosticGeneration(String uri) {
        long generation = diagnosticGenerations.incrementAndGet();
        diagnosticRequestGeneration.put(uri, generation);
        return generation;
    }

    /** Tracked documents — package-private so a test can show closing one releases its entry. */
    int diagnosticGenerationEntries() {
        return diagnosticRequestGeneration.size();
    }

    /** The document version a whole-document token set was last delivered for, per URI. */
    private record TokenMark(
            LanguageServerSession session, @Nullable Integer version) {}

    private final Map<String, TokenMark> wholeDocumentTokens = new ConcurrentHashMap<>();

    /**
     * Whether the server would answer a semantic-tokens request for {@code file} with what it last sent:
     * it has no range requests (so every request covers the whole document) and the document has not
     * changed on the server since that answer. Scrolling such a document shows nothing new, so the
     * scroll-settle re-request — a whole-document transfer and decode per scroll — can be skipped.
     */
    public boolean wholeDocumentTokensCurrent(Path file) {
        LanguageServerSession s = sessionFor(file);
        var prov = s == null ? null : semanticTokensProvider(s.capabilities());
        if (prov == null || eitherTrue(prov.getRange())) {
            return false;
        }
        String uri = uri(file);
        TokenMark mark = wholeDocumentTokens.get(uri);
        return mark != null && mark.session() == s && java.util.Objects.equals(mark.version(), s.documentVersion(uri));
    }

    /**
     * Requests semantic tokens over the line window {@code [startLine..endLine]} (inclusive) and delivers
     * the decoded, CSS-classed {@link com.editora.text.SemanticToken}s to {@code cb} on the FX thread
     * (empty when unsupported / on error). The window bounds cost on large files — the caller passes the
     * visible paragraph range. Decoding uses the server's effective legend, never hardcoded indices.
     *
     * <p>Range-less servers (jdtls advertises {@code range=false}) take the whole-document path, which —
     * when the server supports {@code full.delta} and we hold its previous {@code resultId} — asks for a
     * <b>delta</b> and splices it onto the cached array ({@link SemanticTokensSplice}) instead of
     * re-transferring every token on each 300 ms edit pulse (#679). Any error/stale delta falls back to a
     * plain full request.
     */
    public void requestSemanticTokens(
            Path file,
            int startLine,
            int endLine,
            int lineCount,
            int lastLineLength,
            Consumer<List<com.editora.text.SemanticToken>> cb) {
        LanguageServerSession s = sessionFor(file);
        var prov = s == null ? null : semanticTokensProvider(s.capabilities());
        if (s == null || prov == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var legend = prov.getLegend();
        String uri = uri(file);
        long requestGeneration = semanticRequestGeneration.merge(uri, 1L, Long::sum);
        // Prefer a range (viewport) request to bound cost; fall back to a whole-document request for a
        // server that advertises only `full` (no range).
        if (eitherTrue(prov.getRange())) {
            // Clamped to the document — a range past the last line makes a server return nothing (#715).
            var range = inclusiveLineRange(startLine, endLine, lineCount, lastLineLength);
            latest.issue(
                    uri,
                    SEMANTIC,
                    stamp(s, uri, range),
                    true,
                    () -> s.semanticTokensRange(uri, range),
                    (tokens, error) -> {
                        if (!currentSemanticRequest(file, s, uri, requestGeneration)) {
                            return;
                        }
                        List<com.editora.text.SemanticToken> out = (error != null || tokens == null)
                                ? List.of()
                                : SemanticTokensDecoder.decode(
                                        tokens.getData(), legend.getTokenTypes(), legend.getTokenModifiers());
                        Platform.runLater(() -> cb.accept(out));
                    });
            return;
        }
        boolean deltaSupported = fullDeltaSupported(prov);
        TokenState prev = deltaSupported ? semanticTokenState.get(uri) : null;
        if (prev != null && prev.session() != s) {
            semanticTokenState.remove(uri, prev);
            prev = null;
        }
        if (prev != null) {
            TokenState deltaBase = prev;
            Integer version = s.documentVersion(uri);
            var delta = stamp(s, uri, "delta:" + deltaBase.resultId());
            latest.issue(
                    uri,
                    SEMANTIC,
                    delta,
                    true,
                    () -> s.semanticTokensFullDelta(uri, deltaBase.resultId()),
                    (either, error) -> {
                        if (!currentSemanticRequest(file, s, uri, requestGeneration)) {
                            return;
                        }
                        List<Integer> data = null;
                        String resultId = null;
                        if (error == null && either != null) {
                            if (either.isLeft() && either.getLeft() != null) {
                                data = either.getLeft().getData(); // server chose to answer with a fresh full set
                                resultId = either.getLeft().getResultId();
                            } else if (either.isRight() && either.getRight() != null) {
                                data = SemanticTokensSplice.apply(
                                        deltaBase.data(), either.getRight().getEdits());
                                resultId = either.getRight().getResultId();
                            }
                        }
                        if (data == null) {
                            semanticTokenState.remove(uri); // stale/failed delta — re-request full
                            requestSemanticTokensFull(s, file, legend, requestGeneration, cb);
                            return;
                        }
                        rememberTokenState(s, uri, resultId, data);
                        wholeDocumentTokens.put(uri, new TokenMark(s, version));
                        List<Integer> decoded = data;
                        List<com.editora.text.SemanticToken> out = SemanticTokensDecoder.decode(
                                decoded, legend.getTokenTypes(), legend.getTokenModifiers());
                        Platform.runLater(() -> cb.accept(out));
                    });
            return;
        }
        requestSemanticTokensFull(s, file, legend, requestGeneration, cb);
    }

    /** Plain whole-document request; caches {@code resultId}+data when the server supplies one (#679). */
    private void requestSemanticTokensFull(
            LanguageServerSession s,
            Path file,
            org.eclipse.lsp4j.SemanticTokensLegend legend,
            long requestGeneration,
            Consumer<List<com.editora.text.SemanticToken>> cb) {
        String uri = uri(file);
        Integer version = s.documentVersion(uri);
        latest.issue(uri, SEMANTIC, stamp(s, uri, "full"), true, () -> s.semanticTokensFull(uri), (tokens, error) -> {
            if (!currentSemanticRequest(file, s, uri, requestGeneration)) {
                return;
            }
            if (error != null || tokens == null) {
                wholeDocumentTokens.remove(uri);
                Platform.runLater(() -> cb.accept(List.of()));
                return;
            }
            rememberTokenState(s, uri, tokens.getResultId(), tokens.getData());
            wholeDocumentTokens.put(uri, new TokenMark(s, version));
            List<com.editora.text.SemanticToken> out =
                    SemanticTokensDecoder.decode(tokens.getData(), legend.getTokenTypes(), legend.getTokenModifiers());
            Platform.runLater(() -> cb.accept(out));
        });
    }

    /** The {@link LatestRequests} kind shared by the range, full and delta forms: they answer one question. */
    private static final String SEMANTIC = "semanticTokens";

    private boolean currentSemanticRequest(
            Path file, @Nullable LanguageServerSession session, String uri, long requestGeneration) {
        return sessionFor(file) == session
                && java.util.Objects.equals(semanticRequestGeneration.get(uri), requestGeneration);
    }

    private void rememberTokenState(
            LanguageServerSession session, String uri, @Nullable String resultId, List<Integer> data) {
        if (resultId != null && data != null) {
            semanticTokenState.put(uri, new TokenState(session, resultId, List.copyOf(data)));
        } else {
            semanticTokenState.remove(uri); // no resultId ⇒ the server can't delta from this response
        }
    }

    /** Pure: whether the provider's {@code full} form advertises delta support (null-safe). */
    static boolean fullDeltaSupported(org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions prov) {
        var full = prov == null ? null : prov.getFull();
        return full != null
                && full.isRight()
                && full.getRight() != null
                && Boolean.TRUE.equals(full.getRight().getDelta());
    }

    public void hover(Path file, int line, int character, Consumer<String> cb) {
        hover(file, line, character, cb, failure -> cb.accept(""));
    }

    /**
     * As {@link #hover(Path, int, int, Consumer)}, but a request the server answered with a fault reaches
     * {@code onFailure} (with the server's message) instead of reading as "no documentation" — the one place
     * a user looks when hover does nothing (Maven review C2). Both callbacks run on the FX thread.
     */
    public void hover(Path file, int line, int character, Consumer<String> cb, Consumer<String> onFailure) {
        hoverAt(file, null, line, character, cb, onFailure);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void hoverAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<String> cb,
            Consumer<String> onFailure) {
        LanguageServerSession s = sessionFor(anchor);
        if (s == null) {
            cb.accept("");
            return;
        }
        s.hover(documentUri != null ? documentUri : uri(anchor), new Position(line, character))
                .whenComplete((hover, error) -> {
                    var fault = error == null ? null : RequestErrorTracker.responseError(error);
                    if (fault != null && RequestErrorTracker.serverFault(fault.getCode())) {
                        String why = RequestErrorTracker.describe(fault);
                        Platform.runLater(() -> onFailure.accept(why));
                        return;
                    }
                    String text = error == null ? hoverText(hover) : "";
                    Platform.runLater(() -> cb.accept(text));
                });
    }

    public void definition(Path file, int line, int character, Consumer<List<Target>> cb) {
        requestDefinition(sessionFor(file), file == null ? null : uri(file), line, character, cb);
    }

    /**
     * Definition at an explicit document URI on {@code anchorFile}'s session — how navigation chains
     * <b>inside</b> an opened {@code jdt://} library source (#684): the library tab has no filesystem path,
     * but jdtls resolves positions in the jdt documents it has served. {@code anchorFile} is the original
     * workspace file the library source was reached from.
     */
    public void definitionAt(
            Path anchorFile, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        requestDefinition(
                sessionFor(anchorFile),
                documentUri != null ? documentUri : anchorFile == null ? null : uri(anchorFile),
                line,
                character,
                cb);
    }

    /**
     * Go to Implementation ({@code textDocument/implementation}, #735) — the concrete overrides of the
     * member at the caret. Delivers targets on the FX thread; empty when unsupported / on error.
     */
    public void implementation(Path file, int line, int character, Consumer<List<Target>> cb) {
        implementationAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void implementationAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        implementationAt(anchor, documentUri, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Java review B13). */
    public void implementationAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                sessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : uri(anchor),
                line,
                character,
                LanguageServerSession::implementation,
                cb,
                onError);
    }

    /** Go to Type Definition ({@code textDocument/typeDefinition}, #736) — from a symbol to its type. */
    public void typeDefinition(Path file, int line, int character, Consumer<List<Target>> cb) {
        typeDefinitionAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void typeDefinitionAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        typeDefinitionAt(anchor, documentUri, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Java review B13). */
    public void typeDefinitionAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                sessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : uri(anchor),
                line,
                character,
                LanguageServerSession::typeDefinition,
                cb,
                onError);
    }

    /** Go to Declaration ({@code textDocument/declaration}, #736). */
    public void declaration(Path file, int line, int character, Consumer<List<Target>> cb) {
        declarationAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void declarationAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        declarationAt(anchor, documentUri, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Java review B13). */
    public void declarationAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                sessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : uri(anchor),
                line,
                character,
                LanguageServerSession::declaration,
                cb,
                onError);
    }

    /** True if {@code file}'s server is ready and advertises {@code implementationProvider} (#735). */
    public boolean supportsImplementation(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && implementationProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises {@code typeDefinitionProvider} (#736). */
    public boolean supportsTypeDefinition(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && typeDefinitionProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises {@code declarationProvider} (#736). */
    public boolean supportsDeclaration(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && declarationProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code implementationProvider} (null-safe). */
    static boolean implementationProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getImplementationProvider());
    }

    /** Pure: whether a server's capabilities include {@code typeDefinitionProvider} (null-safe). */
    static boolean typeDefinitionProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getTypeDefinitionProvider());
    }

    /** Pure: whether a server's capabilities include {@code declarationProvider} (null-safe). */
    static boolean declarationProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getDeclarationProvider());
    }

    /**
     * One position-to-locations request. {@code definition}, {@code implementation}, {@code typeDefinition}
     * and {@code declaration} differ only in which session method runs — all four answer in the same two
     * shapes (a {@code Location} list or a {@code LocationLink} list), and all four can name a {@code jdt://}
     * class file, so they share one response walk rather than four copies of it.
     */
    @FunctionalInterface
    private interface LocationRequest {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> run(
                LanguageServerSession session, String uri, Position pos);
    }

    private void requestDefinition(
            @Nullable LanguageServerSession s,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb) {
        requestLocations(s, documentUri, line, character, LanguageServerSession::definition, cb, null);
    }

    private void requestLocations(
            @Nullable LanguageServerSession s,
            @Nullable String documentUri,
            int line,
            int character,
            LocationRequest request,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        if (s == null || documentUri == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        request.run(s, documentUri, new Position(line, character)).whenComplete((result, error) -> {
            if (error != null && onError != null) {
                String why = failureReason(error); // not an empty list, which reads as "none found" (B13)
                Platform.runLater(() -> onError.accept(why));
                return;
            }
            List<Target> targets = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    for (Location l : result.getLeft()) {
                        var range = l.getRange(); // a non-conforming server can omit the (spec-required) range
                        if (range != null) {
                            addDefinitionTarget(targets, s.uris(), l.getUri(), range.getStart());
                        }
                    }
                } else if (result.getRight() != null) {
                    for (LocationLink ll : result.getRight()) {
                        var range = ll.getTargetSelectionRange() != null
                                ? ll.getTargetSelectionRange()
                                : ll.getTargetRange();
                        if (range != null) {
                            addDefinitionTarget(targets, s.uris(), ll.getTargetUri(), range.getStart());
                        }
                    }
                }
            }
            Platform.runLater(() -> cb.accept(targets));
        });
    }

    // --- Code actions / quick fixes (#670) ------------------------------------------------------

    /** A code action offered by the server: display fields + the opaque lsp4j payload ({@code raw}) handed
     *  back to {@link #applyCodeAction}. {@code kind} is the LSP kind ({@code quickfix}, {@code source.…})
     *  or empty; {@code preferred} marks the server's recommended fix (listed first). */
    public record CodeActionItem(
            String title,
            String kind,
            boolean preferred,
            Object raw,
            Map<Path, String> expectedDocuments,
            long requestedAtMillis) {
        public CodeActionItem(String title, String kind, boolean preferred, Object raw) {
            this(title, kind, preferred, raw, Map.of(), 0);
        }

        /** What an edit this action carries (or resolves to) was computed against. */
        EditBasis basis(WireUris uris) {
            return new EditBasis(expectedDocuments, requestedAtMillis, uris);
        }
    }

    /** The command id an opaque code-action payload carries, or null (#741). */
    public static @Nullable String commandIdOf(Object raw) {
        org.eclipse.lsp4j.Command cmd = commandOf(raw);
        return cmd == null ? null : cmd.getCommand();
    }

    /** The single argument a {@code java.action.*Prompt} command carries — the original
     *  {@code CodeActionParams}, which the follow-up {@code java/…} requests are addressed with (#741). */
    public static @Nullable Object commandArgument(Object raw) {
        org.eclipse.lsp4j.Command cmd = commandOf(raw);
        List<Object> args = cmd == null ? null : cmd.getArguments();
        return args == null || args.isEmpty() ? null : args.get(0);
    }

    /** Every argument of the command an opaque code-action payload carries, as JSON; empty without one. */
    public static List<JsonElement> commandArguments(Object raw) {
        org.eclipse.lsp4j.Command cmd = commandOf(raw);
        List<Object> args = cmd == null ? null : cmd.getArguments();
        if (args == null) {
            return List.of();
        }
        List<JsonElement> out = new ArrayList<>();
        for (Object arg : args) {
            out.add(arg == null ? com.google.gson.JsonNull.INSTANCE : asJson(arg));
        }
        return out;
    }

    private static org.eclipse.lsp4j.@Nullable Command commandOf(Object raw) {
        if (raw instanceof org.eclipse.lsp4j.Command cmd) {
            return cmd;
        }
        if (raw instanceof org.eclipse.lsp4j.CodeAction action) {
            return action.getCommand();
        }
        return null;
    }

    /**
     * Last raw published diagnostics per <b>managed document URI</b> — the spelling this client opened the
     * document under, never the one the server reported it under (open documents only — see
     * {@link #onPublishDiagnostics}); the context a code-action request sends back to the server.
     *
     * <p>Keying by the server's string lost them whenever the server spelled the URI differently
     * ({@code file:///c%3A/…} for our {@code file:///C:/…}, or a symlink-resolved path): the lookup below
     * missed, the request went out with empty {@code context.diagnostics}, and every quick fix that keys off
     * a diagnostic silently disappeared.
     */
    private final Map<String, List<org.eclipse.lsp4j.Diagnostic>> rawDiagnostics = new ConcurrentHashMap<>();

    /** The retained raw diagnostics for {@code file}. */
    private List<org.eclipse.lsp4j.Diagnostic> rawDiagnosticsFor(Path file) {
        return rawDiagnostics.getOrDefault(uri(file), List.of());
    }

    /**
     * Requests the code actions available over {@code [startLine:startChar .. endLine:endChar]} (0-based),
     * sending the range-overlapping known diagnostics as context, and delivers them on the FX thread —
     * preferred actions first, disabled ones dropped.
     */
    public void codeActions(
            Path file, int startLine, int startChar, int endLine, int endChar, Consumer<List<CodeActionItem>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var range = new org.eclipse.lsp4j.Range(new Position(startLine, startChar), new Position(endLine, endChar));
        List<org.eclipse.lsp4j.Diagnostic> context = contextDiagnostics(rawDiagnosticsFor(file), range);
        EditBasis basis = editBasis(s);
        Map<Path, String> expected = basis.documents();
        long sentAt = basis.sentAtMillis();
        s.codeAction(uri(file), range, context).whenComplete((result, error) -> {
            List<CodeActionItem> items = new ArrayList<>();
            if (error == null && result != null) {
                for (var either : result) {
                    if (either == null) {
                        continue;
                    }
                    if (either.isLeft() && either.getLeft() != null) {
                        var cmd = either.getLeft();
                        items.add(new CodeActionItem(
                                cmd.getTitle() == null ? "" : cmd.getTitle(), "", false, cmd, expected, sentAt));
                    } else if (either.isRight() && either.getRight() != null) {
                        var action = either.getRight();
                        if (action.getDisabled() != null) {
                            continue; // the server says it doesn't apply here
                        }
                        items.add(new CodeActionItem(
                                action.getTitle() == null ? "" : action.getTitle(),
                                action.getKind() == null ? "" : action.getKind(),
                                Boolean.TRUE.equals(action.getIsPreferred()),
                                action,
                                expected,
                                sentAt));
                    }
                }
                items.sort((a, b) -> Boolean.compare(b.preferred(), a.preferred())); // preferred first (stable)
            }
            Platform.runLater(() -> cb.accept(items));
        });
    }

    /**
     * Pure: what a code-action request at {@code range} carries as its context — the diagnostics overlapping it,
     * or, for a bare caret on no squiggle, those on the caret's line. The quick fixes come from the context's
     * diagnostics, so with the caret at the start of a line, after its {@code ;} or on the next word, there were
     * none (Java review A5).
     */
    static List<org.eclipse.lsp4j.Diagnostic> contextDiagnostics(
            List<org.eclipse.lsp4j.Diagnostic> all, org.eclipse.lsp4j.Range range) {
        List<org.eclipse.lsp4j.Diagnostic> overlapping = diagnosticsOverlapping(all, range);
        if (!overlapping.isEmpty() || !range.getStart().equals(range.getEnd())) {
            return overlapping;
        }
        int line = range.getStart().getLine();
        List<org.eclipse.lsp4j.Diagnostic> out = new ArrayList<>();
        for (var d : all) {
            if (d != null
                    && d.getRange() != null
                    && d.getRange().getStart().getLine() <= line
                    && line <= d.getRange().getEnd().getLine()) {
                out.add(d);
            }
        }
        return out;
    }

    /** Pure: the diagnostics whose range overlaps {@code range} (what a code-action context should carry). */
    static List<org.eclipse.lsp4j.Diagnostic> diagnosticsOverlapping(
            List<org.eclipse.lsp4j.Diagnostic> all, org.eclipse.lsp4j.Range range) {
        List<org.eclipse.lsp4j.Diagnostic> out = new ArrayList<>();
        for (var d : all) {
            if (d != null && d.getRange() != null && rangesOverlap(d.getRange(), range)) {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean rangesOverlap(org.eclipse.lsp4j.Range a, org.eclipse.lsp4j.Range b) {
        return comparePositions(a.getStart(), b.getEnd()) <= 0 && comparePositions(b.getStart(), a.getEnd()) <= 0;
    }

    private static int comparePositions(Position a, Position b) {
        int byLine = Integer.compare(a.getLine(), b.getLine());
        return byLine != 0 ? byLine : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    /**
     * Applies a picked {@link CodeActionItem#raw()}: an inline {@code edit} applies directly; an edit-less
     * action is first resolved ({@code codeAction/resolve}); a {@code command} (bare, or riding the action)
     * runs via {@code workspace/executeCommand} — whose server-side handler typically answers with a
     * {@code workspace/applyEdit} back to us, which the registered apply-edit handler lands in the editor.
     * {@code cb} gets overall success on the FX thread.
     */
    public void applyCodeAction(Path file, Object raw, Consumer<Boolean> cb) {
        lastCodeActionFailure = null;
        lastCodeActionEmpty = false;
        LanguageServerSession s = sessionFor(file);
        // No request time: an edit already inside this action was computed at some earlier, unknown moment,
        // so nothing can show a closed file is still what the server saw.
        EditBasis expected = s == null ? EditBasis.UNKNOWN : new EditBasis(expectedDocuments(s), 0, s.uris());
        if (raw instanceof org.eclipse.lsp4j.Command cmd) {
            executeCommandExpectingEdit(file, cmd, expected, cb);
            return;
        }
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action)) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        if (action.getEdit() != null) {
            Platform.runLater(() -> finishCodeAction(file, action, action.getEdit(), expected, cb));
            return;
        }
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        s.resolveCodeAction(action).whenComplete((resolved, error) -> {
            if (error != null) {
                lastCodeActionFailure = failureReason(error);
            }
            org.eclipse.lsp4j.CodeAction use = error == null && resolved != null ? resolved : action;
            Platform.runLater(() -> finishCodeAction(file, use, use.getEdit(), expected, cb));
        });
    }

    /** Request-time snapshots and eventual apply result for one command-driven edit per session. */
    private record PendingApply(EditBasis expected, java.util.concurrent.atomic.AtomicReference<Boolean> applied) {}

    private final Map<LanguageServerSession, PendingApply> pendingApplyExpected = new ConcurrentHashMap<>();

    private void executeCommandExpectingEdit(
            Path file, org.eclipse.lsp4j.Command command, EditBasis expected, Consumer<Boolean> cb) {
        LanguageServerSession session = sessionFor(file);
        if (session == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        // The server computes this edit while it runs the command, so closed files are judged against now.
        PendingApply pending = new PendingApply(
                (expected == null ? EditBasis.UNKNOWN : expected).sentNow(),
                new java.util.concurrent.atomic.AtomicReference<>());
        if (pendingApplyExpected.putIfAbsent(session, pending) != null) {
            Platform.runLater(() -> cb.accept(false));
            return; // do not let concurrent commands share or overwrite each other's stale-edit guard
        }
        executeCommand(file, command.getCommand(), command.getArguments(), (result, error) -> {
            if (error != null) {
                lastCodeActionFailure = failureReason(error);
            }
            pendingApplyExpected.remove(session, pending);
            Boolean applied = pending.applied().get();
            cb.accept(error == null && !Boolean.FALSE.equals(applied));
        });
    }

    /** Why the last code action failed to apply, when the server said; or null. */
    private volatile @Nullable String lastCodeActionFailure;

    /** The server's reason for the last failed code action, or null ("Could not apply: Inline Method" — BN9). */
    public @Nullable String lastCodeActionFailure() {
        return lastCodeActionFailure;
    }

    /** Whether the last code action resolved to neither an edit nor a command: nothing to apply (ET1). */
    private volatile boolean lastCodeActionEmpty;

    public boolean lastCodeActionEmpty() {
        return lastCodeActionEmpty;
    }

    /** Applies a code action together with the request-time snapshots captured when it was listed. */
    public void applyCodeAction(Path file, CodeActionItem item, Consumer<Boolean> cb) {
        lastCodeActionFailure = null;
        lastCodeActionEmpty = false;
        if (item == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        Object raw = item.raw();
        LanguageServerSession owner = sessionFor(file);
        EditBasis basis = item.basis(owner == null ? WireUris.LOCAL : owner.uris());
        if (raw instanceof org.eclipse.lsp4j.Command cmd) {
            executeCommandExpectingEdit(file, cmd, basis, cb);
            return;
        }
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action)) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        if (action.getEdit() != null) {
            Platform.runLater(() -> finishCodeAction(file, action, action.getEdit(), basis, cb));
            return;
        }
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        s.resolveCodeAction(action).whenComplete((resolved, error) -> {
            if (error != null) {
                lastCodeActionFailure = failureReason(error);
            }
            org.eclipse.lsp4j.CodeAction use = error == null && resolved != null ? resolved : action;
            Platform.runLater(() -> finishCodeAction(file, use, use.getEdit(), basis, cb));
        });
    }

    /** FX thread: applies {@code edit} (if any), then runs the action's trailing {@code command} (if any). */
    private void finishCodeAction(
            Path file,
            org.eclipse.lsp4j.CodeAction action,
            org.eclipse.lsp4j.WorkspaceEdit edit,
            EditBasis expected,
            Consumer<Boolean> cb) {
        if (edit != null) {
            applyWorkspaceEdit(
                    edit, expected, editOk -> finishCodeActionAfterEdit(file, action, expected, cb, true, editOk));
            return;
        }
        finishCodeActionAfterEdit(file, action, expected, cb, false, true);
    }

    private void finishCodeActionAfterEdit(
            Path file,
            org.eclipse.lsp4j.CodeAction action,
            EditBasis expected,
            Consumer<Boolean> cb,
            boolean editPresent,
            boolean editOk) {
        if (editPresent && !editOk) {
            cb.accept(false);
            return;
        }
        if (action.getCommand() != null && action.getCommand().getCommand() != null) {
            boolean editApplied = editOk;
            LanguageServerSession session = sessionFor(file);
            EditBasis commandExpected = editPresent && editOk && session != null ? editBasis(session) : expected;
            executeCommandExpectingEdit(
                    file, action.getCommand(), commandExpected, commandOk -> cb.accept(editApplied && commandOk));
            return;
        }
        // No command and no edit either ⇒ nothing was done — report failure, not a silent success. jdtls answers
        // so when a refactoring's final check fails (Inline Method on a method with several returns).
        lastCodeActionEmpty = !editPresent;
        cb.accept(editPresent && editOk);
    }

    /** UI-side workspace-edit applier (set by the coordinator; runs on the FX thread): applies each file's
     *  batch through an undoable buffer, then the trailing file renames — all-or-nothing. Default: refuse. */
    private volatile java.util.function.BiConsumer<WorkspaceEditMapper.Mapped, Consumer<Boolean>> applyEditHandler =
            (edits, done) -> done.accept(false);

    public void setApplyEditHandler(
            java.util.function.BiConsumer<WorkspaceEditMapper.Mapped, Consumer<Boolean>> handler) {
        this.applyEditHandler = handler == null ? (edits, done) -> done.accept(false) : handler;
    }

    /**
     * FX thread: validates {@code edit} against {@code basis} and hands it to the applier. An edit that only
     * touches documents the server had open is applied in the same call, as before. One that reaches a
     * closed file needs that file's modification time, so the check runs on a virtual thread and the apply
     * returns to the FX thread — no file I/O happens here.
     */
    private void applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, EditBasis basis, Consumer<Boolean> done) {
        applyWorkspaceEdit(edit, basis, null, done);
    }

    /**
     * As above; {@code preview}, when given, sees the planned edit first and hands back what to apply (the files
     * the user kept), or never answers when the user cancels.
     */
    private void applyWorkspaceEdit(
            org.eclipse.lsp4j.WorkspaceEdit edit,
            EditBasis basis,
            java.util.function.@Nullable BiConsumer<WorkspaceEditMapper.Mapped, Consumer<WorkspaceEditMapper.Mapped>>
                    preview,
            Consumer<Boolean> done) {
        EditDraft draft = draftWorkspaceEdit(edit, basis);
        if (draft == null) {
            done.accept(false);
            return;
        }
        Consumer<WorkspaceEditMapper.Mapped> apply = preview == null
                ? mapped -> applyEditHandler.accept(mapped, done)
                : mapped -> preview.accept(mapped, kept -> applyEditHandler.accept(kept, done));
        if (draft.closedTargets().isEmpty()) {
            apply.accept(draft.mapped());
            return;
        }
        Thread.ofVirtual().name("lsp-edit-preimage").start(() -> {
            EditPlan plan =
                    completeWorkspaceEdit(draft.mapped(), draft.closedTargets(), basis, LspManager::lastModifiedMillis);
            Platform.runLater(() -> {
                if (plan.mapped() == null) {
                    onEditBlocked.accept(plan.blocked());
                    done.accept(false);
                } else {
                    apply.accept(plan.mapped());
                }
            });
        });
    }

    /**
     * The basis for a server edit that no tracked command is waiting for (the edit of a completion item's
     * command, one sent after {@code executeCommand} was already answered, an unsolicited one). There is no
     * request to date it by, but a target the server names <em>with the version the session currently
     * holds</em> needs none: that version identifies the text, which is the session's own copy. Such targets
     * get that text as their preimage (and keep the applier's version check); every other target stays
     * unknown and is refused as before.
     */
    static EditBasis versionedBasis(LanguageServerSession session, org.eclipse.lsp4j.WorkspaceEdit edit) {
        if (session == null) {
            return EditBasis.UNKNOWN;
        }
        WireUris uris = session.uris();
        if (edit == null || edit.getDocumentChanges() == null) {
            return EditBasis.unknown(uris);
        }
        Map<String, String> snapshots = null;
        Map<Path, String> documents = new java.util.LinkedHashMap<>();
        for (var change : edit.getDocumentChanges()) {
            var id = change != null && change.isLeft() && change.getLeft() != null
                    ? change.getLeft().getTextDocument()
                    : null;
            if (id == null || id.getUri() == null || id.getVersion() == null) {
                continue;
            }
            Path path = uris.toPath(id.getUri());
            String key = uris.toKey(id.getUri());
            if (path == null || !id.getVersion().equals(session.documentVersion(key))) {
                continue;
            }
            if (snapshots == null) {
                snapshots = session.documentSnapshots();
            }
            String text = snapshots.get(key);
            if (text != null) {
                documents.put(path, text);
                documents.put(com.editora.config.PathKeys.canonical(path), text);
            }
        }
        return documents.isEmpty() ? EditBasis.unknown(uris) : new EditBasis(Map.copyOf(documents), 0, uris);
    }

    /** A server-initiated {@code workspace/applyEdit} (from any session): apply on FX, answer the server. */
    private void onServerApplyEdit(
            LanguageServerSession session, org.eclipse.lsp4j.WorkspaceEdit edit, Consumer<Boolean> respond) {
        PendingApply pending = pendingApplyExpected.get(session);
        EditBasis expected = pending == null ? versionedBasis(session, edit) : pending.expected();
        Platform.runLater(() -> applyWorkspaceEdit(edit, expected, applied -> {
            if (pending != null) {
                pending.applied().set(applied);
            }
            respond.accept(applied);
        }));
    }

    /** Why a request failed, as {@code onError} callbacks hear it: {@code "timeout"}, or the server's message. */
    static String failureReason(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause instanceof java.util.concurrent.TimeoutException ? "timeout" : String.valueOf(cause.getMessage());
    }

    public void references(Path file, int line, int character, Consumer<List<Target>> cb) {
        references(file, line, character, cb, null);
    }

    /**
     * As above; {@code onError} (when given) hears a failed or timed-out request instead of {@code cb} hearing an
     * empty list — which read as "No references found" (Java review B13).
     */
    public void references(
            Path file, int line, int character, Consumer<List<Target>> cb, @Nullable Consumer<String> onError) {
        referencesAt(file, null, line, character, cb, onError);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void referencesAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        LanguageServerSession s = sessionFor(anchor);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.references(documentUri != null ? documentUri : uri(anchor), new Position(line, character))
                .whenComplete((locations, error) -> {
                    if (error != null && onError != null) {
                        String why = failureReason(error);
                        Platform.runLater(() -> onError.accept(why));
                        return;
                    }
                    List<Target> targets = new ArrayList<>();
                    if (error == null && locations != null) {
                        for (Location l : locations) {
                            var range = l.getRange(); // guard against a server omitting the spec-required range
                            if (range != null) {
                                addTarget(targets, s.uris(), l.getUri(), range.getStart());
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(targets));
                });
    }

    /**
     * The completion <em>trigger characters</em> {@code file}'s server advertised (e.g. {@code .} for Java,
     * {@code <} for HTML), or empty if the server isn't ready / advertises none. The editor fires LSP
     * completion when one of these is typed — so HTML/CSS/etc. complete like VS Code, not just on a word
     * prefix (see {@code EditorBuffer.setLspTriggerChars}).
     */
    public java.util.Set<Character> triggerCharacters(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? java.util.Set.of() : triggerCharsOf(s.capabilities());
    }

    /** Pure extraction of completion trigger characters from a server's capabilities (null-safe). */
    static java.util.Set<Character> triggerCharsOf(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getCompletionProvider() == null) {
            return java.util.Set.of();
        }
        List<String> triggers = caps.getCompletionProvider().getTriggerCharacters();
        if (triggers == null) {
            return java.util.Set.of();
        }
        java.util.Set<Character> out = new java.util.HashSet<>();
        for (String t : triggers) {
            if (t != null && !t.isEmpty()) {
                out.add(t.charAt(0));
            }
        }
        return out;
    }

    /**
     * jdtls's {@code initializationOptions}: {@code settings.java.autobuild.enabled=false} always, plus
     * the java-debug plugin {@code bundles} when {@code debugBundles} is non-empty. Editora only needs
     * jdtls for per-file diagnostics/completion/hover/nav — never its on-disk build output — but by
     * default jdtls runs a full incremental Eclipse workspace <em>build</em> on save/change, which m2e
     * points at the project's own configured Maven/Gradle output directory (e.g. {@code target/classes}
     * for a Maven project — the exact same directory {@code mvn}/{@code gradle} CLI builds write to).
     * Racing jdtls's ECJ builder against a CLI build there can leave that directory missing classes (most
     * visibly javac's synthetic enum-switch-map inner classes, since ECJ doesn't regenerate them in
     * lockstep) even right after a clean CLI build. Disabling autobuild stops jdtls from ever touching
     * that directory; live per-file diagnostics still work via reconcile-on-type, which isn't gated by it.
     * Pure + package-private so it's directly unit-tested.
     */
    static Map<String, Object> javaInitOptions(List<String> debugBundles) {
        return javaInitOptions(debugBundles, false);
    }

    static Map<String, Object> javaInitOptions(List<String> debugBundles, boolean onTypeFormatting) {
        return javaInitOptions(debugBundles, onTypeFormatting, null);
    }

    static Map<String, Object> javaInitOptions(
            List<String> debugBundles, boolean onTypeFormatting, @Nullable List<JavaRuntimes.Jdk> jdks) {
        return javaInitOptions(debugBundles, onTypeFormatting, jdks, null);
    }

    static Map<String, Object> javaInitOptions(
            List<String> debugBundles,
            boolean onTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            @Nullable String preferredHome) {
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("settings", Map.of("java", javaSettings(onTypeFormatting, jdks, preferredHome)));
        return finishJavaInitOptions(options, debugBundles);
    }

    /**
     * jdtls's {@code settings.java}: autobuild off, plus the JDKs its machine actually has ({@code jdks}, or
     * this machine's when null).
     *
     * <p>{@code configuration.runtimes} is not optional polish. m2e writes a project's execution environment
     * into its Eclipse {@code .classpath} from the pom, so {@code maven.compiler.release=17} demands
     * {@code JavaSE-17}; jdtls can only bind that to a JDK it has been told about, and telling it nothing
     * leaves it knowing only the JVM it runs on. A project targeting any other release then has an
     * unresolved container, and an unresolved container makes {@code vscode.java.resolveClasspath} return an
     * <b>empty classpath with no error</b> — which Run could only report as "the project hasn't finished
     * importing" for a project that had imported perfectly. Declaring the real runtimes also gives jdtls a
     * sane default to fall back to when a project's exact release isn't installed at all.
     */
    private static Map<String, Object> javaSettings(
            boolean onTypeFormatting, @Nullable List<JavaRuntimes.Jdk> jdks, @Nullable String preferredHome) {
        // The keys pushed after initialized, sent here too so the first import already runs with them.
        Map<String, Object> java = new java.util.HashMap<>(LspServerSettings.javaSettings(onTypeFormatting));
        java.put("autobuild", Map.of("enabled", false));
        List<Map<String, Object>> runtimes =
                JavaRuntimes.runtimes(jdks == null ? JavaRuntimes.discover() : jdks, preferredHome);
        if (!runtimes.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configuration =
                    new java.util.HashMap<>((Map<String, Object>) java.getOrDefault("configuration", Map.of()));
            configuration.put("runtimes", runtimes);
            java.put("configuration", Map.copyOf(configuration));
            String gradleJdk = JavaRuntimes.defaultPath(runtimes);
            if (gradleJdk != null) {
                // Gradle runs on the same JDK as the projects' default, not whatever jdtls itself runs on.
                java.put("import", Map.of("gradle", Map.of("java", Map.of("home", gradleJdk))));
            }
        }
        return Map.copyOf(java);
    }

    private static Map<String, Object> finishJavaInitOptions(Map<String, Object> options, List<String> debugBundles) {
        options.put("extendedClientCapabilities", javaExtendedClientCapabilities());
        if (!debugBundles.isEmpty()) {
            options.put("bundles", debugBundles);
        }
        return Map.copyOf(options);
    }

    /**
     * jdtls's {@code initializationOptions.extendedClientCapabilities} (#741) — a vendor extension, distinct
     * from the LSP {@code ClientCapabilities}, that gates a large part of what jdtls will even offer.
     *
     * <p><b>Measured, not assumed</b> (driving jdtls 1.60 against the same file and position): declaring these
     * takes the code actions at a field from <b>9 to 16</b>. Without them, Generate toString / hashCode+equals
     * / Constructors / Delegate Methods, Override-Implement Methods and Extract Interface are not returned at
     * all — the server simply doesn't offer what it thinks the client cannot drive. Re-measured on 1.62 by
     * {@code JdtlsUpgradeProbeTest}: 11 to 17 at its fixture's field, with the same nine titles depending on them.
     *
     * <p><b>Each prompt flag is a contract, not a hint.</b> Turning one on changes that action's reply from a
     * directly-appliable edit into a {@code java.action.*Prompt} command the client must handle. Enabling one
     * without implementing its prompt doesn't leave the feature as it was — it <em>breaks</em> it: the action
     * still appears and then does nothing. That is why the accessors actions (which work today without any
     * flag) are only listed here alongside a real picker; see {@link JdtlsGenerate}.
     *
     * <p>{@code resolveAdditionalTextEditsSupport} is the odd one out and needs no UI: it tells jdtls to defer
     * an auto-import's edits to {@code completionItem/resolve}, which is exactly what
     * {@code LspCoordinator.autoImportAccept} already does. Without it jdtls computes those edits eagerly for
     * every item in a completion list — pure waste on a per-keystroke path.
     */
    static Map<String, Object> javaExtendedClientCapabilities() {
        Map<String, Object> caps = new java.util.LinkedHashMap<>();
        // Needs no client UI.
        caps.put("classFileContentsSupport", true); // we open jdt:// library source (#665)
        caps.put("resolveAdditionalTextEditsSupport", true); // we resolve on accept (#410/#445)
        caps.put("progressReportProvider", true); // legacy language/progressReport, coalesced on the status bar
        // Each of these is backed by a prompt in JdtlsGenerate — DO NOT enable one without its picker.
        caps.put("generateToStringPromptSupport", true);
        caps.put("hashCodeEqualsPromptSupport", true);
        caps.put("generateConstructorsPromptSupport", true);
        caps.put("overrideMethodsPromptSupport", true);
        // Backed by JdtlsGenerate's accessor and delegate-method prompts.
        caps.put("advancedGenerateAccessorsSupport", true);
        caps.put("generateDelegateMethodsPromptSupport", true);
        // Backed by JdtlsRefactor: java.action.applyRefactoringCommand is carried out by the client.
        // jdtls offers Extract Interface only with both flags. The second one also turns the extract
        // refactorings (variable, constant, method, field) into that same command; each is one
        // java/getRefactorEdit away.
        caps.put("extractInterfaceSupport", true);
        caps.put("advancedExtractRefactoringSupport", true);
        caps.put("moveRefactoringSupport", true);
        // jdtls 1.61+: a move with problems answers a confirmationToken instead of applying anyway; the
        // coordinator shows the problems and resends with the token (review D11). Older servers ignore it.
        caps.put("moveRefactoringConfirmationSupport", true);
        // Extract Method / Variable / Field at a bare caret: the action is offered and the client asks
        // java/inferSelection which expression to extract (review D7). Introduce Parameter becomes the same
        // client-driven command, whose edit is one java/getRefactorEdit away.
        caps.put("inferSelectionSupport", List.of("extractMethod", "extractVariable", "extractField"));
        caps.put("advancedIntroduceParameterRefactoringSupport", true);
        // jdtls asks through workspace/executeClientCommand which class an ambiguous name means (Organize
        // Imports) and waits for the answer; LanguageServerSession.executeClientCommand gives it (review A3).
        caps.put("executeClientCommandSupport", true);
        return Map.copyOf(caps);
    }

    /**
     * The {@code initialize.initializationOptions} for a server, or {@code null} for one that needs none. Pure
     * + package-private so it's unit-tested:
     * <ul>
     *   <li><b>java</b> (jdtls): {@code settings.java.autobuild.enabled=false} always + the java-debug plugin
     *       {@code bundles} when debugging is on (see {@link #javaInitOptions}).</li>
     *   <li><b>go</b> (gopls): {@code semanticTokens=true} — gopls ships semantic tokens disabled and only
     *       advertises {@code semanticTokensProvider} when this flat key is set (confirmed; {@code ui.} doesn't).</li>
     *   <li><b>json/css/html</b> (vscode-*-language-server): {@code provideFormatter=true} — these servers
     *       implement formatting but only advertise {@code documentFormattingProvider} when VS Code's own
     *       {@code provideFormatter} flag is passed; without it, Format Document was silently unavailable on a
     *       {@code .json}/{@code .css}/{@code .html} even though the server would format (#468; verified by
     *       driving the real servers: the flag flips the advertised capability from false to true).</li>
     *   <li><b>astro</b> (astro-ls): {@code typescript.tsdk} points at a <i>trusted</i> folder's own TypeScript
     *       SDK, or the SDK installed beside the language server ({@link #astroTypeScriptSdk}). Astro refuses
     *       initialization without it, so with neither the server is not started.</li>
     *   <li><b>maven-pom</b> (JVM lemminx + lemminx-maven): {@code settings.xml.maven.central.skip=true} —
     *       the lemminx-maven settings live under an {@code xml.maven} object (schema verified against the
     *       extension's {@code XMLMavenSettings}). {@code central.skip=true} disables the heavy Maven Central
     *       <i>index</i> download so completion is served from the local {@code ~/.m2} repository only —
     *       offline-friendly and network-conservative, matching how Git/LSP/update-check are gated — unless
     *       Settings → LSP "Use the Maven Central index" is on ({@link #setMavenCentralIndex}, review C10).
     *       Sending any {@code xml.maven} object is also what activates the extension's POM awareness in the
     *       first place.</li>
     * </ul>
     */
    /**
     * {@code options} (jdtls's) with the preferences {@code root}'s own files state ({@link JavaProjectSettings})
     * merged into {@code settings.java}; anything else unchanged.
     */
    @SuppressWarnings("unchecked")
    static @Nullable Object withProjectSettings(String serverId, @Nullable Object options, @Nullable Path root) {
        if (!LspServerRegistry.JAVA_SERVER_ID.equals(serverId) || !(options instanceof Map<?, ?> map) || root == null) {
            return options;
        }
        Map<String, Object> project = JavaProjectSettings.forRoot(root);
        Object settings = map.get("settings");
        if (project.isEmpty() || !(settings instanceof Map<?, ?> s) || !(s.get("java") instanceof Map<?, ?> java)) {
            return options;
        }
        Map<String, Object> out = new java.util.HashMap<>((Map<String, Object>) map);
        out.put("settings", Map.of("java", JavaProjectSettings.merge((Map<String, Object>) java, project)));
        return Map.copyOf(out);
    }

    static @Nullable Object initOptionsFor(String serverId, List<String> debugBundles) {
        return initOptionsFor(serverId, debugBundles, null, false);
    }

    /** {@code astroTsdk} is the SDK {@link #astroTypeScriptSdk} chose; it is only read for the Astro server. */
    static @Nullable Object initOptionsFor(
            String serverId, List<String> debugBundles, @Nullable Path astroTsdk, boolean javaOnTypeFormatting) {
        return initOptionsFor(serverId, debugBundles, astroTsdk, javaOnTypeFormatting, null);
    }

    /** As above; {@code jdks} are the JDKs jdtls is told about (null: this machine's, discovered now). */
    static @Nullable Object initOptionsFor(
            String serverId,
            List<String> debugBundles,
            @Nullable Path astroTsdk,
            boolean javaOnTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks) {
        return initOptionsFor(serverId, debugBundles, astroTsdk, javaOnTypeFormatting, jdks, false);
    }

    /** As above; {@code mavenCentralIndex} lets lemminx-maven download and use the Maven Central index. */
    static @Nullable Object initOptionsFor(
            String serverId,
            List<String> debugBundles,
            @Nullable Path astroTsdk,
            boolean javaOnTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            boolean mavenCentralIndex) {
        return initOptionsFor(serverId, debugBundles, astroTsdk, javaOnTypeFormatting, jdks, mavenCentralIndex, null);
    }

    /** As above; {@code javaPreferredHome} is the JDK jdtls makes its default ({@link JavaRuntimes#preferredHome}). */
    static @Nullable Object initOptionsFor(
            String serverId,
            List<String> debugBundles,
            @Nullable Path astroTsdk,
            boolean javaOnTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            boolean mavenCentralIndex,
            @Nullable String javaPreferredHome) {
        return switch (serverId == null ? "" : serverId) {
            case "java" -> javaInitOptions(debugBundles, javaOnTypeFormatting, jdks, javaPreferredHome);
            case "go" -> Map.of("semanticTokens", true);
            case "json", "css", "html" -> Map.of("provideFormatter", true);
            // gvy reads a flat map of its VS Code settings. CodeNarc's style rules flag nearly every line of
            // ordinary Groovy at Information level; compiler errors are what an editor needs by default.
            case "groovy" -> Map.of("groovy.codenarc.enabled", false);
            case "scala" -> metalsInitOptions();
            case ASTRO_SERVER_ID ->
                astroTsdk == null ? null : Map.of("typescript", Map.of("tsdk", astroTsdk.toString()));
            case LspServerRegistry.MAVEN_POM_SERVER_ID ->
                Map.of(
                        "settings",
                        Map.of("xml", Map.of("maven", Map.of("central", Map.of("skip", !mavenCentralIndex)))));
            default -> null;
        };
    }

    private static final String ASTRO_SERVER_ID = "astro";

    /**
     * Metals' {@code initializationOptions}: what this client implements of its extensions, and nothing more
     * (an option left out means Metals falls back to plain LSP). Its status items arrive as
     * {@code metals/status}; prompts as {@code metals/inputBox} / {@code metals/quickPick}; editor actions as
     * {@code metals/executeClientCommand}; {@code debuggingProvider} lets Debug start Metals' debug adapter
     * ({@code debug-adapter-start}). {@code isHttpEnabled} off: no local web server for its doctor page.
     */
    static Map<String, Object> metalsInitOptions() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("statusBarProvider", "on");
        options.put("icons", "vscode"); // the syntax MetalsStatusView reads (and strips)
        options.put("isHttpEnabled", false);
        options.put("debuggingProvider", true);
        options.put("executeClientCommandProvider", true);
        options.put("inputBoxProvider", true);
        options.put("quickPickProvider", true);
        options.put("isExitOnShutdown", true);
        return options;
    }

    /**
     * Whether a folder is trusted to supply code a language server loads — today the project-local
     * TypeScript SDK handed to astro-ls. The same per-folder record that gates project LSP commands; nothing
     * is trusted until the coordinator installs the real check.
     */
    private volatile java.util.function.Predicate<Path> folderTrust = folder -> false;

    public void setFolderTrust(java.util.function.Predicate<Path> trust) {
        this.folderTrust = trust == null ? folder -> false : trust;
    }

    /** Told (on the FX thread) that a server was <b>not started</b> because it would need untrusted code. */
    /** Told when a Java server starts on a second workspace for a project another window already serves. */
    private volatile BiConsumer<String, Path> onSecondServer = (id, root) -> {};

    public void setOnSecondServer(BiConsumer<String, Path> callback) {
        this.onSecondServer = callback == null ? (id, root) -> {} : callback;
    }

    private volatile BiConsumer<String, Path> onStartWithheld = (id, root) -> {};

    public void setOnStartWithheld(BiConsumer<String, Path> callback) {
        this.onStartWithheld = callback == null ? (id, root) -> {} : callback;
    }

    /**
     * Told (on the FX thread) that a server's start failed — its command could not be run, or a remote
     * host's connection broke mid-start. Not a crash, so nothing restarts it. {@code accept(serverId, root)}.
     */
    private volatile BiConsumer<String, Path> onStartFailed = (id, root) -> {};

    public void setOnStartFailed(BiConsumer<String, Path> callback) {
        this.onStartFailed = callback == null ? (id, root) -> {} : callback;
    }

    /** Told (on the FX thread) why a server whose command exists is not running; see {@link StartRefusal}. */
    public interface StartRefusedListener {
        void refused(String serverId, Path root, StartRefusal refusal);
    }

    private volatile StartRefusedListener onStartRefused = (id, root, why) -> {};

    public void setOnStartRefused(StartRefusedListener callback) {
        this.onStartRefused = callback == null ? (id, root, why) -> {} : callback;
    }

    /**
     * The tsserver for a local TypeScript-server root: the folder's own only within its trusted part, else the
     * one beside the installed server; never a folder's own for the shared loose-files root. Off the FX thread
     * (PATH and disk).
     */
    static TypeScriptServer.Choice typeScriptChoice(
            Path root, @Nullable Path trustedCeiling, List<String> command, boolean loose) {
        Path trusted = trustedCeiling == null || loose ? null : TypeScriptServer.findTsserver(root, trustedCeiling);
        Path any = loose ? null : TypeScriptServer.findTsserver(root, null);
        Path executable = typeScriptServerExecutable(command);
        return TypeScriptServer.choose(
                trusted,
                any,
                TypeScriptServer.installedTsserver(executable),
                TypeScriptServer.installedPackageVersion(executable),
                loose);
    }

    /**
     * Where typescript-language-server itself is, links followed: the command's word naming it (a custom command
     * may run it through {@code node} or a wrapper), else the command's program. Null when it cannot be found.
     */
    public static @Nullable Path typeScriptServerExecutable(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String word = command.get(0);
        for (String w : command) {
            if (w.contains("typescript-language-server")) {
                word = w;
                break;
            }
        }
        List<String> resolved = ProcessRunner.resolveExecutable(List.of(word));
        if (resolved.isEmpty()) {
            return null;
        }
        try {
            Path exe = Path.of(resolved.get(0)).toAbsolutePath().normalize();
            return Files.exists(exe) ? exe.toRealPath() : null;
        } catch (java.io.IOException | java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** The bin folder of the Node.js a root's Node tools run with (Settings, .nvmrc, PATH), or null. */
    private static @Nullable Path nodeBinDir(Path root, String nodeSetting) {
        try {
            return com.editora.run.NodeRuntimes.resolveLocal(root.resolve("package.json"), null, "", nodeSetting)
                    .binDir()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The topmost ancestor of {@code root} (or {@code root} itself) that is still trusted — how far up a
     * search for project-supplied code may go — or {@code null} when {@code root} is not trusted at all.
     */
    static @Nullable Path trustedCeiling(Path root, java.util.function.Predicate<Path> trusted) {
        if (root == null || trusted == null) {
            return null;
        }
        Path dir = root.toAbsolutePath().normalize();
        if (!trusted.test(dir)) {
            return null;
        }
        for (Path parent = dir.getParent(); parent != null && trusted.test(parent); parent = parent.getParent()) {
            dir = parent;
        }
        return dir;
    }

    /**
     * Finds the TypeScript SDK required by astro-ls, which loads and <b>runs</b> the JavaScript in that
     * directory during {@code initialize}. The folder's own TypeScript is therefore taken only from a
     * trusted folder: {@code trustedCeiling} ({@link #trustedCeiling}) is null for an untrusted root, and
     * otherwise bounds the upward walk (npm/pnpm workspace hoisting) so it never leaves the trusted folder.
     * Next comes the SDK beside the resolved astro-ls installation, which is the user's own. A trusted
     * folder with neither gets its deterministic project-local path, which produces Astro's own clear
     * "can't find TypeScript" error; an untrusted one gets {@code null} — the server is not started.
     */
    static @Nullable Path astroTypeScriptSdk(Path root, @Nullable Path trustedCeiling, List<String> command) {
        if (trustedCeiling != null) {
            Path projectSdk = findTypeScriptSdk(root, trustedCeiling);
            if (projectSdk != null) {
                return projectSdk;
            }
        }
        if (command != null && !command.isEmpty()) {
            List<String> resolved = ProcessRunner.resolveExecutable(command);
            if (!resolved.isEmpty()) {
                try {
                    Path executable = Path.of(resolved.get(0)).toAbsolutePath().normalize();
                    if (Files.exists(executable)) {
                        executable = executable.toRealPath();
                    }
                    Path installedSdk = findTypeScriptSdk(executable.getParent(), null);
                    if (installedSdk != null) {
                        return installedSdk;
                    }
                } catch (java.io.IOException | java.nio.file.InvalidPathException ignored) {
                    // Fall through to the trusted project-local path, or to not starting.
                }
            }
        }
        if (trustedCeiling == null) {
            return null;
        }
        Path base = root == null ? Path.of("") : root;
        return base.toAbsolutePath().normalize().resolve("node_modules/typescript/lib");
    }

    /**
     * The first {@code node_modules/typescript/lib} walking up from {@code start}; {@code ceiling}, when
     * given, is the last directory looked at. Public for the trust prompt, which lists what trusting adds.
     */
    public static @Nullable Path findTypeScriptSdk(@Nullable Path start, @Nullable Path ceiling) {
        Path stop = ceiling == null ? null : ceiling.toAbsolutePath().normalize();
        for (Path dir = start == null ? null : start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (stop != null && !dir.startsWith(stop)) {
                break;
            }
            Path candidate = dir.resolve("node_modules/typescript/lib");
            if (Files.isRegularFile(candidate.resolve("typescript.js"))
                    || Files.isRegularFile(candidate.resolve("tsserverlibrary.js"))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Requests <em>pull</em> diagnostics ({@code textDocument/diagnostic}) for {@code file} and routes the
     * result through the same diagnostics callback as pushed {@code publishDiagnostics}. A no-op unless the
     * file's server advertises a {@code diagnosticProvider} (so push-only servers like jdtls/pyright/tsserver
     * are untouched). An "unchanged" report leaves the current diagnostics in place.
     */
    /** Told (FX thread) that a document's diagnostics are confirmed unchanged by its server. */
    private volatile Consumer<Path> onDiagnosticsUnchanged = file -> {};

    public void setOnDiagnosticsUnchanged(Consumer<Path> handler) {
        onDiagnosticsUnchanged = handler == null ? file -> {} : handler;
    }

    public void pullDiagnostics(Path file) {
        if (file == null) {
            return;
        }
        LanguageServerSession s = sessionFor(file);
        if (s == null || s.capabilities() == null || s.capabilities().getDiagnosticProvider() == null) {
            return;
        }
        String documentUri = uri(file);
        Integer requestedVersion = s.documentVersion(documentUri);
        // The generation is taken when the request is really sent: a call that finds the same pull still
        // unanswered sends nothing and must not outdate the answer it is waiting for along with it.
        long[] sent = new long[1];
        java.util.function.Supplier<CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport>> send = () -> {
            sent[0] = nextDiagnosticGeneration(documentUri);
            return s.diagnostic(documentUri);
        };
        latest.issue(documentUri, "diagnostic", stamp(s, documentUri, null), false, send, (report, error) -> {
            long generation = sent[0];
            if (error != null) {
                return;
            }
            List<LspDiagnostic> mapped = DiagnosticMapper.mapReport(report);
            if (mapped == null) {
                // An "unchanged" report: what was shown before the edit is still right. The editor cleared
                // its marks on the edit, so say so rather than leave it showing a clean file.
                Platform.runLater(() -> {
                    if (sessionFor(file) == s
                            && java.util.Objects.equals(diagnosticRequestGeneration.get(documentUri), generation)) {
                        onDiagnosticsUnchanged.accept(file);
                    }
                });
                return;
            }
            var full = report == null ? null : report.getRelatedFullDocumentDiagnosticReport();
            List<org.eclipse.lsp4j.Diagnostic> raw =
                    full == null || full.getItems() == null ? List.of() : List.copyOf(full.getItems());
            Platform.runLater(() -> {
                if (sessionFor(file) == s
                        && java.util.Objects.equals(diagnosticRequestGeneration.get(documentUri), generation)
                        && java.util.Objects.equals(s.documentVersion(documentUri), requestedVersion)) {
                    // The code-action context is built from these. A pull-only server (vscode-css, ruby-lsp)
                    // never publishes, so without this it was always asked with an empty context and offered
                    // no quick fix for a diagnostic the editor was showing.
                    rawDiagnostics.put(documentUri, raw);
                    onDiagnostics.accept(file, mapped);
                }
            });
        });
    }

    /**
     * Final shutdown for a window that is closing: stops every server and makes the manager terminal.
     *
     * <p>{@link #shutdownAll} alone is not enough for that. It is also the restart/disable primitive, so it
     * leaves the manager usable — and a callback that lands after the window closed (a detection probe, a
     * settings apply, a crash-restart) would then open a document and fork a server nothing owns or will
     * ever stop. After this, {@link #openDocument}, {@link #configure} and {@link #detect} do nothing.
     */
    public void close() {
        MANAGERS.remove(this);
        closed = true;
        enabled = false;
        closeRootWatchers();
        shutdownAll();
        pendingEvictions.values().forEach(timer -> timer.cancel(false));
        pendingEvictions.clear();
        detectExec.shutdown();
        startExec.shutdown();
        synchronized (javaScriptShadows) {
            if (javaScriptShadowRoot != null) {
                try {
                    Files.deleteIfExists(javaScriptShadowRoot);
                } catch (java.io.IOException | RuntimeException ignored) {
                    // a temporary folder
                }
            }
        }
    }

    /** Whether {@link #close()} ran. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops this window's servers (best-effort) and clears all routing state. A Java server another window
     * still uses keeps running for it; this window only leaves it.
     */
    public void shutdownAll() {
        endAll(false);
    }

    /** Restarts every server this window uses: one it shares with another window is stopped for both. */
    public void restartAll() {
        endAll(true);
    }

    private void endAll(boolean everywhere) {
        Map<String, LanguageServerSession> sessions = new java.util.HashMap<>(sessionsByRoot);
        sessionsByRoot.clear(); // before they stop: this window's own drop must not read as another's
        if (!everywhere) {
            sessions.entrySet().removeIf(e -> leaveShared(e.getKey(), e.getValue()));
        }
        sessionByDocUri.clear();
        rawDiagnostics.clear();
        semanticTokenState.clear();
        semanticRequestGeneration.clear();
        wholeDocumentTokens.clear();
        latest.clear();
        diagnosticRequestGeneration.clear();
        pendingApplyExpected.clear();
        javaScriptShadows.values().forEach(LspManager::deleteJavaScriptShadow);
        javaScriptShadows.clear();
        sessions.forEach(this::disposeDeliberately);
    }

    /** Shuts down every session for {@code serverId} (e.g. when that server's per-server toggle goes off). */
    /**
     * Shuts down every started session whose folder's tools now run somewhere else than where it was started:
     * a folder bound to a container, or released from one, or a host whose agreement was withdrawn. Its
     * documents are reopened on the right server by the next sync. Deliberate: no crash notice. Returns
     * whether any was.
     */
    public boolean shutdownSessionsOnAnotherTarget() {
        boolean any = false;
        var it = sessionsByRoot.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            LanguageServerSession s = e.getValue();
            String started = s.targetId();
            if (started != null
                    && !started.equals(
                            com.editora.process.ExecTargets.forPath(s.root()).id())) {
                it.remove(); // before it stops: this window's own drop must not read as another's
                sessionByDocUri.values().removeIf(v -> v == s);
                pendingApplyExpected.remove(s);
                disposeDeliberately(e.getKey(), s);
                any = true;
            }
        }
        return any;
    }

    public void shutdownServer(String serverId) {
        String prefix = sessionKeyPrefix(serverId);
        var it = sessionsByRoot.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(prefix)) {
                LanguageServerSession s = e.getValue();
                it.remove(); // before it stops: this window's own drop must not read as another's
                sessionByDocUri.values().removeIf(v -> v == s);
                pendingApplyExpected.remove(s);
                disposeDeliberately(e.getKey(), s);
            }
        }
    }

    // --- Internals -----------------------------------------------------------------------------

    /** Whether diagnostics published for a file that is not open on the publishing server are dropped. */
    private volatile boolean openDocumentDiagnosticsOnly;

    /**
     * Says whether the consumer only shows diagnostics for open documents (the Problems window's default
     * scope). It then never sees a publish for any other file. Off by default: everything is delivered.
     */
    public void setOpenDocumentDiagnosticsOnly(boolean openDocumentsOnly) {
        openDocumentDiagnosticsOnly = openDocumentsOnly;
    }

    /**
     * Whether diagnostics on {@code file} are the project's rather than one file's: on its folder (jdtls: the
     * expected JDK is missing, the compliance level does not match) or its build file. They are listed whatever
     * Problems shows, so they must not be dropped with the closed files' (re-review C19, CN2).
     */
    public static boolean projectLevel(@Nullable Path file) {
        if (file == null || com.editora.vfs.Vfs.isRemote(file)) {
            return false;
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts")
                || java.nio.file.Files.isDirectory(file);
    }

    private void onPublishDiagnostics(LanguageServerSession source, org.eclipse.lsp4j.PublishDiagnosticsParams params) {
        if (source == null || source.isDisposed()) {
            return;
        }
        String reported = source.uris().toKey(params.getUri());
        Path file = DocumentUris.toPath(reported);
        if (file == null) {
            return;
        }
        String sourceUri = managedDocumentUri(source, file, reported);
        if (params.getVersion() != null
                && (sourceUri == null
                        || !java.util.Objects.equals(params.getVersion(), source.documentVersion(sourceUri)))) {
            return; // ranges from an older server snapshot must never replace current diagnostics
        }
        if (!buildCaptures.isEmpty()) {
            captureBuildErrors(source, file, DiagnosticMapper.map(params.getDiagnostics()));
        }
        if (sourceUri == null && openDocumentDiagnosticsOnly && !projectLevel(file)) {
            // Nobody shows a file that is not open here, and a server publishes for its whole project
            // (jdtls: once per file on import). Dropped on this, the reader, thread: each one used to
            // cost the FX thread a queued task, a search of the tabs and a path resolution to learn that.
            return;
        }
        // Only an open document is tracked: successive publishes for any other file are applied in the
        // order they were queued, and one entry per file a server ever mentioned was never released.
        long generation = sourceUri == null ? 0 : nextDiagnosticGeneration(sourceUri);
        // Retain the RAW lsp4j diagnostics for open documents: a code-action request must send the
        // originals as context (their code/source/data are what a quick fix keys off — the mapped neutral
        // LspDiagnostic loses them). Open-documents-only, so a server's project-wide publishes don't
        // accumulate (#670).
        Integer acceptedVersion = params.getVersion();
        List<org.eclipse.lsp4j.Diagnostic> raw =
                params.getDiagnostics() == null ? List.of() : List.copyOf(params.getDiagnostics());
        List<LspDiagnostic> mapped = MavenPomServer.adjustDiagnostics(
                serverDisplayName(source.serverId()), DiagnosticMapper.map(params.getDiagnostics()));
        Platform.runLater(() -> {
            if (source.isDisposed()
                    || !sessionsByRoot.containsValue(source)
                    || sourceUri != null
                            && !java.util.Objects.equals(diagnosticRequestGeneration.get(sourceUri), generation)
                    || sourceUri != null && sessionByDocUri.get(sourceUri) != source
                    || acceptedVersion != null
                            && !java.util.Objects.equals(acceptedVersion, source.documentVersion(sourceUri))) {
                return;
            }
            if (sourceUri != null) {
                rawDiagnostics.put(sourceUri, raw);
            }
            onDiagnostics.accept(file, mapped);
        });
    }

    /** The name a diagnostic without a {@code source} is attributed to: the server's display name, else its id. */
    private static String serverDisplayName(String serverId) {
        String name = LspServerRegistry.displayNameFor(serverId);
        return name != null ? name : serverId;
    }

    private @Nullable String managedDocumentUri(LanguageServerSession source, Path file, String reportedUri) {
        if (sessionByDocUri.get(reportedUri) == source) {
            return reportedUri;
        }
        Path canonical = null;
        for (var entry : sessionByDocUri.entrySet()) {
            if (entry.getValue() == source) {
                Path openPath = DocumentUris.toPath(entry.getKey());
                if (openPath == null) {
                    continue;
                }
                if (com.editora.config.PathKeys.sameNormalized(openPath, file)) {
                    return entry.getKey();
                }
                // A server may report the symlink-resolved path (/private/tmp for /tmp). Off the FX thread
                // (the reader thread), and the resolution is cached.
                if (canonical == null) {
                    canonical = com.editora.config.PathKeys.canonical(file);
                }
                if (canonical.equals(com.editora.config.PathKeys.canonical(openPath))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static void addTarget(List<Target> out, WireUris uris, String uri, Position start) {
        Path p = uris.toPath(uri);
        if (p != null && start != null) {
            out.add(new Target(p, start.getLine(), start.getCharacter()));
        }
    }

    /**
     * Like {@link #addTarget}, but also keeps a {@code jdt://} class-file target (as a path-less
     * {@link Target} carrying the URI). Definition-only: silently dropping these made {@code M-.} on
     * {@code String}/{@code List}/any dependency symbol report "no definition" — the most common Java
     * navigation (#665). References stay file-only (the References panel is file-based).
     */
    private static void addDefinitionTarget(List<Target> out, WireUris uris, String uri, Position start) {
        if (start == null) {
            return;
        }
        Path p = uris.toPath(uri);
        if (p != null) {
            out.add(new Target(p, start.getLine(), start.getCharacter()));
        } else if (uri != null && uri.startsWith(JDT_SCHEME)) {
            out.add(new Target(null, start.getLine(), start.getCharacter(), uri));
        }
    }

    /**
     * Fetches the source of a {@code jdt://} class-file URI via jdtls's {@code java/classFileContents}
     * request (the attached/decompiled source of a JDK or dependency class) and delivers it on the FX
     * thread — {@code null} when the session is gone, the request fails/times out, or the server returns
     * nothing. {@code anchorFile} is any file managed by the jdtls session that produced the URI.
     */
    /**
     * Step 1 of a jdtls generate prompt (#741): runs the {@code java/check…} request and delivers the
     * choosable candidates on the FX thread. Empty when the server has no session, the request fails, or the
     * response isn't the expected shape — the caller then reports "nothing to generate" rather than opening
     * an empty picker.
     */
    public void jdtlsGenerateCandidates(
            Path file, JdtlsGenerate.Kind kind, Object actionParams, Consumer<JdtlsGenerate.Plan> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || kind == null) {
            Platform.runLater(() -> cb.accept(new JdtlsGenerate.Plan(List.of(), null)));
            return;
        }
        s.rawRequest(kind.checkRequest(), actionParams)
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    JdtlsGenerate.Plan plan =
                            e != null ? new JdtlsGenerate.Plan(List.of(), null) : JdtlsGenerate.plan(kind, asJson(r));
                    Platform.runLater(() -> cb.accept(plan));
                });
    }

    /**
     * Step 2 of a jdtls generate prompt (#741): runs the {@code java/generate…} request with the user's
     * choice and applies the {@code WorkspaceEdit} it answers with. {@code cb} reports whether anything was
     * applied.
     */
    public void jdtlsGenerateApply(
            Path file,
            JdtlsGenerate.Kind kind,
            Object actionParams,
            JsonElement checkResponse,
            Map<Path, String> expectedAtAction,
            List<JdtlsGenerate.Candidate> chosen,
            Consumer<Boolean> cb) {
        jdtlsGenerateApply(file, kind, actionParams, checkResponse, expectedAtAction, chosen, null, cb);
    }

    /** As above, with the super constructors the user chose (constructors only; null = all on offer). */
    public void jdtlsGenerateApply(
            Path file,
            JdtlsGenerate.Kind kind,
            Object actionParams,
            JsonElement checkResponse,
            Map<Path, String> expectedAtAction,
            List<JdtlsGenerate.Candidate> chosen,
            @Nullable List<JdtlsGenerate.Candidate> chosenConstructors,
            Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || kind == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        EditBasis expected = expectedAtAction == null || expectedAtAction.isEmpty()
                ? editBasis(s)
                : new EditBasis(expectedAtAction, System.currentTimeMillis(), s.uris());
        s.rawRequest(
                        kind.generateRequest(),
                        JdtlsGenerate.generateParams(
                                kind, asJson(actionParams), chosen, checkResponse, chosenConstructors))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : asWorkspaceEdit(r);
                    Platform.runLater(() -> {
                        if (edit == null) {
                            cb.accept(false);
                        } else {
                            applyWorkspaceEdit(edit, expected, cb);
                        }
                    });
                });
    }

    /**
     * Sends a custom {@code java/…} request and delivers its untyped JSON answer on the FX thread —
     * {@code null} when there is no session or the request fails. For the look-ups a client-driven
     * refactoring makes before it asks for an edit (destinations, candidates, a method's signature).
     */
    public void jdtlsRequest(Path file, String method, Object params, Consumer<JsonElement> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.rawRequest(method, params).whenComplete((r, e) -> {
            JsonElement json = e != null ? null : asJson(r);
            Platform.runLater(() -> cb.accept(json));
        });
    }

    /**
     * Sends a custom {@code java/…} request whose answer is, or wraps, a {@code WorkspaceEdit}
     * ({@link JdtlsRefactor#editOf}) and applies it. {@code cb} gets whether an edit was applied, and the
     * server's own {@code errorMessage} when it refused the refactoring (else null).
     */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtlsApplyEdit(file, method, params, expectedAtAction, null, cb);
    }

    /**
     * As above, for a request jdtls may answer with problems and a {@code confirmationToken} instead of an edit
     * ({@code java/move} once {@code moveRefactoringConfirmationSupport} is declared). {@code confirm} gets the
     * problems on the FX thread and the action that asks again with the token; without it, or for any other
     * answer, this behaves exactly as above.
     */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, null, cb);
    }

    /**
     * As above; {@code onRename} gets the new name's place ({@link JdtlsRefactor#renameAt}) on the FX thread
     * once an edit that carries one is applied.
     */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, onRename, null, cb);
    }

    /** As above; {@code preview} sees the planned edit before it is applied (see {@link #applyWorkspaceEdit}). */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename,
            java.util.function.@Nullable BiConsumer<WorkspaceEditMapper.Mapped, Consumer<WorkspaceEditMapper.Mapped>>
                    preview,
            java.util.function.BiConsumer<Boolean, String> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false, null));
            return;
        }
        EditBasis expected = expectedAtAction == null || expectedAtAction.isEmpty()
                ? editBasis(s)
                : new EditBasis(expectedAtAction, System.currentTimeMillis(), s.uris());
        s.rawRequest(method, params).whenComplete((r, e) -> {
            JsonElement json = e != null ? null : asJson(r);
            String refused = JdtlsRefactor.errorMessage(json);
            String token = JdtlsRefactor.confirmationToken(json);
            if (token != null && confirm != null && params instanceof JsonObject asked) {
                JsonObject again = JdtlsRefactor.withConfirmationToken(asked, token);
                Platform.runLater(() -> confirm.accept(
                        refused == null ? "" : refused,
                        () -> jdtlsApplyEdit(file, method, again, expectedAtAction, null, onRename, preview, cb)));
                return;
            }
            org.eclipse.lsp4j.WorkspaceEdit edit = asWorkspaceEdit(JdtlsRefactor.editOf(json));
            JdtlsRefactor.RenameAt renameAt = onRename == null ? null : JdtlsRefactor.renameAt(json);
            Platform.runLater(() -> {
                if (edit == null) {
                    cb.accept(false, refused);
                } else {
                    applyWorkspaceEdit(edit, expected, preview, ok -> {
                        cb.accept(ok, refused);
                        if (ok && renameAt != null && onRename != null) {
                            onRename.accept(renameAt);
                        }
                    });
                }
            });
        });
    }

    /** The raw result as gson, or null — the custom {@code java/…} requests answer as untyped JSON. */
    public static com.google.gson.@Nullable JsonElement asJson(@Nullable Object raw) {
        if (raw instanceof com.google.gson.JsonElement json) {
            return json;
        }
        return raw == null ? null : LanguageServerSession.LSP_GSON.toJsonTree(raw);
    }

    /**
     * Auto-import for pasted code (#742): asks jdtls's {@code java.edit.handlePasteEvent} what the text
     * pasted at {@code [start..end]} (post-paste coordinates) needs, and applies the answered
     * {@code additionalEdit} — the import insertion — through the normal workspace-edit path. Gated on
     * the server actually advertising the command ({@link JdtlsPaste#supportsPasteEvent}), so a non-jdtls
     * java setup (or any other language's server) never even sends the request.
     *
     * <p>{@code stillValid} is consulted on the FX thread immediately before applying: the round trip is
     * asynchronous and the edit was computed against the post-paste document, so if the user typed in the
     * meantime the answer is dropped rather than applied against text it wasn't computed for (the
     * {@code docVersion} idiom — the caller supplies the check because only it can see the buffer).
     * {@code cb} reports whether an edit was applied.
     */
    public void handlePasteEvent(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            String pastedText,
            int tabSize,
            boolean insertSpaces,
            java.util.function.BooleanSupplier stillValid,
            Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || !JdtlsPaste.supportsPasteEvent(s.capabilities())) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        // The params go over the wire as a STRINGIFIED json — an object argument deserializes to null
        // server-side (established empirically; see JdtlsPaste).
        String params = JdtlsPaste.paramsJson(
                s.uris().toWire(uri(file)), startLine, startChar, endLine, endChar, pastedText, tabSize, insertSpaces);
        EditBasis expected = editBasis(s);
        s.executeCommand(JdtlsPaste.COMMAND, List.of(params))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null
                            ? null
                            : asWorkspaceEdit(
                                    JdtlsPaste.keepPastedImports(JdtlsPaste.additionalEdit(asJson(r)), pastedText));
                    Platform.runLater(() -> {
                        if (edit == null || !stillValid.getAsBoolean()) {
                            cb.accept(false);
                        } else {
                            applyWorkspaceEdit(edit, expected, cb);
                        }
                    });
                });
    }

    /**
     * Smart-semicolon detection (#746): asks jdtls where a {@code ;} typed at {@code (line, character)}
     * belongs — normally the end of the enclosing statement. Delivers the answered {@code {line, char}} on
     * the FX thread, or {@code null} when the server has nothing to say (already at the statement end, or
     * not a jdtls session). Gated on the command being advertised, so no other language pays a request.
     *
     * <p>The document must be in the state the caret refers to — i.e. <b>before</b> the semicolon is typed —
     * which is why the caller flushes its pending didChange first.
     */
    public void smartSemicolonPosition(Path file, int line, int character, Consumer<int[]> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || !JdtlsSmartSemicolon.supported(s.capabilities())) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String params = JdtlsSmartSemicolon.paramsJson(s.uris().toWire(uri(file)), line, character);
        s.executeCommand(JdtlsSmartSemicolon.COMMAND, List.of(params))
                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    int[] at = e != null ? null : JdtlsSmartSemicolon.answeredPosition(asJson(r));
                    Platform.runLater(() -> cb.accept(at));
                });
    }

    /**
     * Decodes a {@code java/generate…} answer (an untyped {@code WorkspaceEdit}) into the typed form — with
     * LSP4J's own gson, whose adapters are what read the {@code Either}s inside {@code documentChanges}. A
     * plain gson cannot, so an answer in that shape was dropped as "nothing generated".
     */
    private static org.eclipse.lsp4j.@Nullable WorkspaceEdit asWorkspaceEdit(@Nullable Object raw) {
        try {
            com.google.gson.JsonElement json = asJson(raw);
            return json == null || !json.isJsonObject()
                    ? null
                    : LanguageServerSession.LSP_GSON.fromJson(json, org.eclipse.lsp4j.WorkspaceEdit.class);
        } catch (RuntimeException malformed) {
            return null; // a malformed answer degrades to "nothing generated", never an exception
        }
    }

    /**
     * Whether jdtls considers {@code file} a <em>test</em> source ({@code java.project.isTestFile}, #745).
     *
     * <p>Answered from the Eclipse project model — it knows which source folders are test folders — where
     * {@code test/JavaTestScanner} can only guess syntactically from annotations. Delivers {@code null}
     * (not {@code false}) when there's no session or the command fails, so the caller keeps its own answer
     * rather than treating "don't know" as "not a test".
     *
     * <p>Only a {@code .java} file is asked about. Of any other name jdtls has no compilation unit and answers
     * with an error whose stack trace lands in the Debug Log on every tab switch; a Java script is shown to it
     * as a stand-in ({@link JavaScriptShadow}), but one outside every project, where nothing is a test.
     */
    public void isTestFile(Path file, Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || file == null || !isJavaSourceName(file)) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        executeCommand(file, "java.project.isTestFile", List.of(serverUri(file)), (result, error) -> {
            Boolean answer = error != null ? null : asBoolean(result);
            cb.accept(answer);
        });
    }

    /** Pure: whether jdtls can make a compilation unit of {@code file} — it must be named {@code *.java}. */
    static boolean isJavaSourceName(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().endsWith(".java");
    }

    /**
     * Resolves one stack-trace line to the URI it points at
     * ({@code java.project.resolveStackTraceLocation}, #744).
     *
     * <p>jdtls resolves it against the real classpath, so unlike the regex+filesystem walk in
     * {@code MainController.resolveRunLinkFile} it can name a frame inside a dependency or the JDK — which
     * comes back as a {@code jdt://} URI the class-file viewer (#665) already opens. Delivers null when
     * there's no session, the command fails, or the server can't place the frame.
     *
     * @param traceLine the console line verbatim, e.g. {@code "\tat demo.Person.of(Person.java:12)"}
     */
    public void resolveStackTraceLocation(Path anchorFile, String traceLine, Consumer<String> cb) {
        LanguageServerSession s = sessionFor(anchorFile);
        if (s == null || traceLine == null || traceLine.isBlank()) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        // The second argument is the project-name filter; an empty list means "search them all", which is
        // what we want — the console line doesn't say which project it came from.
        executeCommand(
                anchorFile,
                "java.project.resolveStackTraceLocation",
                List.of(traceLine, List.of()),
                (result, error) -> {
                    String uri = error != null ? null : rawStringResult(result);
                    cb.accept(uri == null || uri.isBlank() ? null : uri);
                });
    }

    /** A command result that is (or wraps) a boolean, else null. */
    private static @Nullable Boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof com.google.gson.JsonPrimitive p && p.isBoolean()) {
            return p.getAsBoolean();
        }
        return null;
    }

    /** The budget for a full workspace build — passed to the session too, whose own request bound would
     *  otherwise cancel the build at the ordinary 30 seconds. */
    static final java.time.Duration BUILD_WORKSPACE_TIMEOUT = java.time.Duration.ofMinutes(10);

    /**
     * Rebuilds the project and republishes its diagnostics ({@code java.project.refreshDiagnostics}, #743).
     *
     * <p>Editora runs jdtls with {@code autobuild} disabled (see {@link #javaInitOptions}), so nothing ever
     * recomputes diagnostics for files that aren't open — this is the manual trigger. The results arrive the
     * usual way, as {@code publishDiagnostics} notifications, not as this command's return value.
     *
     * <p>Given a longer timeout than the other commands: a cold full build of a large project genuinely
     * takes minutes, and timing out early would leave the caller reporting failure while the build ran on.
     */
    public void refreshProjectDiagnostics(Path file, Consumer<BuildOutcome> cb) {
        LanguageServerSession s = javaSessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(BuildOutcome.UNAVAILABLE));
            return;
        }
        // (uri, isFullBuild) — a full build, since the point is the files we have NOT opened.
        s.rawRequest("java/buildWorkspace", Boolean.TRUE, BUILD_WORKSPACE_TIMEOUT)
                .orTimeout(BUILD_WORKSPACE_TIMEOUT.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((r, e) -> {
                    BuildOutcome outcome = e != null ? BuildOutcome.UNAVAILABLE : buildOutcome(r);
                    Platform.runLater(() -> cb.accept(outcome));
                });
    }

    /**
     * The jdtls session serving {@code file}: its own when jdtls manages it, else the one whose root holds it
     * (the deepest) — a pom.xml or build.gradle belongs to the Java project beside it although another server, or
     * none, edits it. Before this, Reload and Build Project run from a pom.xml tab went to lemminx-maven (Java
     * review C2). Null when no jdtls serves the file's folder.
     */
    private @Nullable LanguageServerSession javaSessionFor(@Nullable Path file) {
        LanguageServerSession own = sessionFor(file);
        if (own != null && LspServerRegistry.JAVA_SERVER_ID.equals(own.serverId())) {
            return own;
        }
        if (file == null) {
            return null;
        }
        Path at = file.toAbsolutePath().normalize();
        String prefix = sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID);
        LanguageServerSession best = null;
        for (var entry : sessionsByRoot.entrySet()) {
            LanguageServerSession s = entry.getValue();
            if (!entry.getKey().startsWith(prefix) || s.isDisposed()) {
                continue;
            }
            Path root = s.root().toAbsolutePath().normalize();
            if (at.startsWith(root)
                    && (best == null
                            || root.getNameCount()
                                    > best.root().toAbsolutePath().normalize().getNameCount())) {
                best = s;
            }
        }
        return best;
    }

    /**
     * Before {@code from} is renamed to {@code to} on disk (a Project tree rename): asks the Java server of its
     * project for the edits that go with it ({@code workspace/willRenameFiles} — the class renamed with its
     * {@code .java} file, the package declarations and imports of a moved folder) and applies them, then calls
     * {@code proceed} on the FX thread with whether the edits are in. With no Java server, or none it wants
     * made, {@code proceed} runs at once with true (Java review B15).
     */
    public void willRenameFile(Path from, Path to, Consumer<Boolean> proceed) {
        LanguageServerSession s = javaSessionFor(from);
        if (s == null) {
            Platform.runLater(() -> proceed.accept(true));
            return;
        }
        EditBasis expected = editBasis(s);
        s.willRenameFiles(s.uris().toWire(uri(from)), s.uris().toWire(uri(to)))
                .whenComplete((edit, e) -> Platform.runLater(() -> {
                    if (e != null || edit == null || !changesText(edit)) {
                        proceed.accept(true);
                    } else {
                        applyWorkspaceEdit(edit, expected, proceed);
                    }
                }));
    }

    /**
     * Clean Java Workspace (Java review C14): stops the Java server of {@code file}'s project and deletes its
     * jdtls workspace — the index, the source folders and classpath it worked out — so the next open imports the
     * project afresh. Restart Language Servers kept that workspace, and Clear Java Index Caches skips the ones in
     * use, so a wrong source folder or a corrupt index survived both. {@code done} (FX thread) is told whether
     * the workspace was deleted; the caller re-opens the project's files.
     */
    public void cleanJavaWorkspace(Path file, Consumer<Boolean> done) {
        LanguageServerSession s = javaSessionFor(file);
        Path base = jdtlsWorkspaceBase;
        String name = s == null ? null : jdtlsWorkspaceBySession.get(s);
        String key = null;
        for (var e : sessionsByRoot.entrySet()) {
            if (e.getValue() == s) {
                key = e.getKey();
            }
        }
        if (s == null || base == null || name == null || key == null) {
            Platform.runLater(() -> done.accept(false));
            return;
        }
        sessionsByRoot.remove(key);
        sessionByDocUri.values().removeIf(x -> x == s);
        pendingApplyExpected.remove(s);
        disposeDeliberately(key, s);
        String exitedKey = key;
        Path workspace = base.resolve(name);
        // Every start on this key — this window's reopen, and another window's that shared the server — waits
        // for the clean, not only for the process: the other window restarted at once and took the workspace
        // before it could be deleted, so the clean became a plain restart (re-review CN1).
        CompletableFuture<Void> priorExit = exiting.getOrDefault(key, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> cleaned = new CompletableFuture<>();
        exiting.put(key, cleaned);
        Thread.ofVirtual().name("jdtls-clean").start(() -> {
            try {
                priorExit.get(PRIOR_EXIT_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                LOG.log(java.util.logging.Level.FINE, "the Java server had not exited yet", e);
            }
            boolean deleted = false;
            for (int attempt = 0; attempt < 50 && !deleted; attempt++) {
                if (claimedJdtlsWorkspaces.add(name)) {
                    try {
                        deleted = deleteUnlockedJdtlsWorkspace(workspace);
                    } finally {
                        claimedJdtlsWorkspaces.remove(name);
                    }
                }
                if (!deleted) {
                    try {
                        Thread.sleep(100); // the process is still letting go of its lock
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            boolean result = deleted;
            cleaned.complete(null);
            exiting.remove(exitedKey, cleaned);
            Platform.runLater(() -> done.accept(result));
        });
    }

    /** Whether a jdtls serves {@code file}'s project, whichever server edits the file itself. */
    public boolean hasJavaSession(@Nullable Path file) {
        return javaSessionFor(file) != null;
    }

    /** What a jdtls {@code java/buildWorkspace} reported ({@code BuildWorkspaceStatus}), or {@link #UNAVAILABLE}. */
    public enum BuildOutcome {
        FAILED,
        SUCCEEDED,
        WITH_ERRORS,
        CANCELLED,
        /** No Java server for the file, or the request itself failed. */
        UNAVAILABLE
    }

    /**
     * Builds the Java workspace {@code file} belongs to — incrementally unless {@code full} — and reports how it
     * went (callback on the FX thread). With autobuild off this is what turns saved sources into class files
     * jdtls (and java-debug inside it, for hot code replace) can see.
     */
    public void buildJavaWorkspace(Path file, boolean full, Consumer<BuildOutcome> cb) {
        LanguageServerSession s = file == null ? null : javaSessionFor(file);
        if (s == null || !LspServerRegistry.JAVA_SERVER_ID.equals(s.serverId())) {
            Platform.runLater(() -> cb.accept(BuildOutcome.UNAVAILABLE));
            return;
        }
        s.rawRequest("java/buildWorkspace", full, BUILD_WORKSPACE_TIMEOUT)
                .orTimeout(BUILD_WORKSPACE_TIMEOUT.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((r, e) -> {
                    BuildOutcome outcome = e != null ? BuildOutcome.UNAVAILABLE : buildOutcome(r);
                    Platform.runLater(() -> cb.accept(outcome));
                });
    }

    /** Pure: jdtls's {@code BuildWorkspaceStatus} — an ordinal (0 FAILED, 1 SUCCEED, 2 WITH_ERROR, 3 CANCELLED)
     *  or its name, possibly as a gson primitive. */
    static BuildOutcome buildOutcome(Object reply) {
        String text = reply instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : String.valueOf(reply);
        text = text.strip();
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2); // a number gson read as a double
        }
        return switch (text.toUpperCase(java.util.Locale.ROOT)) {
            case "0", "FAILED" -> BuildOutcome.FAILED;
            case "1", "SUCCEED", "SUCCEEDED" -> BuildOutcome.SUCCEEDED;
            case "2", "WITH_ERROR", "WITH_ERRORS" -> BuildOutcome.WITH_ERRORS;
            case "3", "CANCELLED" -> BuildOutcome.CANCELLED;
            default -> BuildOutcome.UNAVAILABLE;
        };
    }

    /**
     * A build's outcome and the compile errors jdtls published while it ran, by file (only files with errors;
     * every file, open or not — the Problems window's open-files filter does not apply).
     */
    public record BuildResult(BuildOutcome outcome, Map<Path, List<LspDiagnostic>> errors) {}

    /** The error diagnostics each building session publishes, captured on the reader thread (see buildWorkspace). */
    private final Map<LanguageServerSession, Map<Path, List<LspDiagnostic>>> buildCaptures =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Records {@code file}'s errors for a build of {@code source} in progress (reader thread). */
    private void captureBuildErrors(LanguageServerSession source, Path file, List<LspDiagnostic> mapped) {
        Map<Path, List<LspDiagnostic>> capture = buildCaptures.get(source);
        if (capture == null) {
            return;
        }
        List<LspDiagnostic> errors = mapped.stream()
                .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                .toList();
        if (errors.isEmpty()) {
            capture.remove(file);
        } else {
            capture.put(file, errors);
        }
    }

    /**
     * Builds {@code file}'s Java project the way a debug launch needs it: Editora runs jdtls with autobuild off,
     * so nothing else writes the class files the program is launched from. An incremental build first; when it
     * reports errors without publishing any (the markers were already there), a full build, which republishes
     * them all. The result is delivered on the FX thread.
     */
    public void buildJavaWorkspaceForLaunch(Path file, Consumer<BuildResult> cb) {
        LanguageServerSession s = file == null ? null : sessionFor(file);
        if (s == null || !LspServerRegistry.JAVA_SERVER_ID.equals(s.serverId())) {
            Platform.runLater(() -> cb.accept(new BuildResult(BuildOutcome.UNAVAILABLE, Map.of())));
            return;
        }
        build(s, false, first -> {
            if (first.outcome() == BuildOutcome.WITH_ERRORS && first.errors().isEmpty()) {
                build(s, true, cb);
            } else {
                cb.accept(first);
            }
        });
    }

    private void build(LanguageServerSession s, boolean full, Consumer<BuildResult> cb) {
        Map<Path, List<LspDiagnostic>> capture = new java.util.concurrent.ConcurrentHashMap<>();
        buildCaptures.put(s, capture);
        s.rawRequest("java/buildWorkspace", full, BUILD_WORKSPACE_TIMEOUT)
                .orTimeout(BUILD_WORKSPACE_TIMEOUT.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((r, e) -> {
                    buildCaptures.remove(s, capture);
                    BuildOutcome outcome = e != null ? BuildOutcome.UNAVAILABLE : buildOutcome(r);
                    Map<Path, List<LspDiagnostic>> errors = new java.util.TreeMap<>(capture);
                    Platform.runLater(
                            () -> cb.accept(new BuildResult(outcome, java.util.Collections.unmodifiableMap(errors))));
                });
    }

    /**
     * Organize imports for a whole file ({@code java/organizeImports}, #746) and apply the resulting edit.
     *
     * <p>The same operation is also reachable as the {@code source.organizeImports} code action, but only by
     * opening the code-action picker and choosing it. This is the direct, bindable form.
     *
     * <p>Signature read off the jdtls jar: it takes {@code CodeActionParams} — the whole file's range, not a
     * position — and answers a {@code WorkspaceEdit}.
     */
    /** What an Organize Imports did. */
    public enum OrganizeOutcome {
        ORGANIZED,
        UNCHANGED,
        /** The file's server has no way to organize imports. */
        UNSUPPORTED,
        /** The server failed or gave up (jdtls cancels the whole operation when it cannot ask about a name). */
        FAILED
    }

    /**
     * Organizes {@code file}'s imports the way its server does it: jdtls's {@code java/organizeImports},
     * Pyright's {@code pyright.organizeimports} command (basedpyright's own name for it), else a
     * {@code source.organizeImports} code action. Before this every server was sent the jdtls request, so on a
     * Python file it failed and the status bar said "Imports already organized" (review B5).
     */
    public void organizeImportsAny(Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.UNSUPPORTED));
            return;
        }
        String serverId = s.serverId();
        if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
            organizeJavaImports(s, file, lastLine, lastChar, cb);
            return;
        }
        if (PYTHON_SERVER_ID.equals(serverId)) {
            EditBasis expected = editBasis(s);
            String command = pyrightOrganizeCommand(s.command());
            s.executeCommand(command, List.of(serverUri(file)))
                    .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((r, e) -> {
                        org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : asWorkspaceEdit(r);
                        Platform.runLater(() -> {
                            if (e != null) {
                                cb.accept(OrganizeOutcome.UNSUPPORTED); // pylsp, ruff, jedi: no such command
                            } else if (edit == null || !changesText(edit)) {
                                cb.accept(OrganizeOutcome.UNCHANGED);
                            } else {
                                applyWorkspaceEdit(
                                        edit,
                                        expected,
                                        ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.UNCHANGED));
                            }
                        });
                    });
            return;
        }
        if (TypeScriptServer.SERVER_ID.equals(serverId)) {
            organizeTypeScriptImports(s, file, cb);
            return;
        }
        codeActions(file, 0, 0, lastLine, lastChar, items -> {
            CodeActionItem organize = items.stream()
                    .filter(i -> i.kind().startsWith("source.organizeImports"))
                    .findFirst()
                    .orElse(null);
            if (organize == null) {
                cb.accept(OrganizeOutcome.UNSUPPORTED);
            } else {
                applyCodeAction(
                        file, organize, ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.UNCHANGED));
            }
        });
    }

    /**
     * typescript-language-server's own Organize Imports ({@code _typescript.organizeImports}): it sorts, combines
     * and removes unused imports, and sends the edit back as {@code workspace/applyEdit}. Its
     * {@code source.organizeImports} code action only sorts, and was never offered at all without {@code only}
     * (Node review B5). No edit coming back means the imports were already organized.
     */
    private void organizeTypeScriptImports(LanguageServerSession s, Path file, Consumer<OrganizeOutcome> cb) {
        PendingApply pending =
                new PendingApply(editBasis(s).sentNow(), new java.util.concurrent.atomic.AtomicReference<>());
        if (pendingApplyExpected.putIfAbsent(s, pending) != null) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.UNCHANGED));
            return;
        }
        String serverPath;
        try {
            serverPath = Path.of(java.net.URI.create(serverUri(file))).toString();
        } catch (RuntimeException e) {
            serverPath = java.net.URI.create(serverUri(file)).getPath();
        }
        executeCommand(file, "_typescript.organizeImports", List.of(serverPath), (result, error) -> {
            pendingApplyExpected.remove(s, pending);
            Boolean applied = pending.applied().get();
            cb.accept(
                    error != null
                            ? OrganizeOutcome.UNSUPPORTED
                            : Boolean.TRUE.equals(applied) ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.UNCHANGED);
        });
    }

    /** Pyright's organize-imports command, under basedpyright's name when that is the server. Pure. */
    static String pyrightOrganizeCommand(List<String> serverCommand) {
        String exe = serverCommand == null || serverCommand.isEmpty() ? "" : serverCommand.get(0);
        return exe.contains("basedpyright") ? "basedpyright.organizeimports" : "pyright.organizeimports";
    }

    /** Whether {@code edit} carries any text change (Pyright answers an already-sorted file with none). */
    private static boolean changesText(org.eclipse.lsp4j.WorkspaceEdit edit) {
        if (edit.getChanges() != null && edit.getChanges().values().stream().anyMatch(l -> l != null && !l.isEmpty())) {
            return true;
        }
        return edit.getDocumentChanges() != null && !edit.getDocumentChanges().isEmpty();
    }

    /**
     * Organize Imports as a save runs it: no question for an ambiguous name (it is left unimported), and the
     * outcome reported. {@code cb} on the FX thread.
     */
    public void organizeImportsQuietly(Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        LanguageServerSession s = javaSessionFor(file);
        if (s == null || file == null || sessionFor(file) != s) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.FAILED));
            return;
        }
        String quiet = s.uris().toWire(uri(file));
        s.quietImportChoices.add(quiet);
        organizeJavaImports(s, file, lastLine, lastChar, outcome -> {
            s.quietImportChoices.remove(quiet);
            cb.accept(outcome);
        });
    }

    public void organizeImports(Path file, int lastLine, int lastChar, Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        organizeJavaImports(s, file, lastLine, lastChar, outcome -> cb.accept(outcome == OrganizeOutcome.ORGANIZED));
    }

    /**
     * jdtls's {@code java/organizeImports}. For a name with several candidates jdtls asks the client
     * ({@code java.action.organizeImports.chooseImports}, answered by {@link LanguageServerSession}). Unanswered,
     * it cancelled the whole operation and answered null, which reads exactly like "nothing to change" — so the
     * question is what had to be answered (Java review A3); a request that fails is {@link OrganizeOutcome#FAILED}.
     */
    private void organizeJavaImports(
            LanguageServerSession s, Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        var range = new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(lastLine, lastChar));
        var params = new org.eclipse.lsp4j.CodeActionParams(
                new org.eclipse.lsp4j.TextDocumentIdentifier(serverUri(file)),
                range,
                new org.eclipse.lsp4j.CodeActionContext(List.of()));
        EditBasis expected = editBasis(s);
        s.rawRequest("java/organizeImports", params)
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : asWorkspaceEdit(r);
                    Platform.runLater(() -> {
                        if (e != null) {
                            cb.accept(OrganizeOutcome.FAILED);
                        } else if (edit == null || !changesText(edit)) {
                            cb.accept(OrganizeOutcome.UNCHANGED);
                        } else {
                            applyWorkspaceEdit(
                                    edit,
                                    expected,
                                    ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.FAILED));
                        }
                    });
                });
    }

    /**
     * The fully qualified name of the symbol at a position ({@code java.getFullyQualifiedName}, #746) —
     * what "Copy Reference" copies in an IDE. Null when there's no session or nothing resolves there.
     */
    public void fullyQualifiedName(Path file, int line, int character, Consumer<String> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        var params = new org.eclipse.lsp4j.TextDocumentPositionParams(
                new org.eclipse.lsp4j.TextDocumentIdentifier(serverUri(file)), new Position(line, character));
        executeCommand(file, "java.getFullyQualifiedName", List.of(params), (result, error) -> {
            String name = error != null ? null : rawStringResult(result);
            cb.accept(name == null || name.isBlank() ? null : name);
        });
    }

    /**
     * Re-reads the project's build configuration ({@code java/projectConfigurationUpdate}, #746) — the
     * manual escape hatch when a {@code pom.xml} / {@code build.gradle} change hasn't been picked up.
     *
     * <p>A <b>notification</b>, not a request (jdtls declares it {@code void}), so there is nothing to
     * report back and nothing to wait for; the result shows up as fresh diagnostics.
     */
    /** Asks the Java server of {@code file}'s project to reload its build configuration; false when there is none. */
    public boolean reloadProjectConfiguration(Path file) {
        LanguageServerSession s = javaSessionFor(file);
        if (s != null && file != null) {
            // jdtls takes the build file itself as well as any source file of the project.
            s.rawNotify(
                    "java/projectConfigurationUpdate", new org.eclipse.lsp4j.TextDocumentIdentifier(serverUri(file)));
            return true;
        }
        return false;
    }

    public void classFileContents(Path anchorFile, @Nullable String jdtUri, Consumer<String> cb) {
        LanguageServerSession s = sessionFor(anchorFile);
        if (s == null || jdtUri == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.rawRequest("java/classFileContents", new org.eclipse.lsp4j.TextDocumentIdentifier(jdtUri))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    String text = e != null ? null : rawStringResult(r);
                    Platform.runLater(() -> cb.accept(text == null || text.isBlank() ? null : text));
                });
    }

    /** The string payload of a raw (untyped) JSON-RPC response — lsp4j decodes an unknown method's result
     *  as a gson element, so accept both. Null for anything else. */
    static @Nullable String rawStringResult(Object r) {
        if (r instanceof String s) {
            return s;
        }
        if (r instanceof com.google.gson.JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        return null;
    }

    /**
     * A display title for a {@code jdt://contents/...} URI — its last path segment ({@code String.class}),
     * query dropped. Falls back to the whole URI when it has no path segment.
     */
    public static String classFileTitle(@Nullable String jdtUri) {
        if (jdtUri == null) {
            return "";
        }
        String noQuery = jdtUri;
        int q = noQuery.indexOf('?');
        if (q >= 0) {
            noQuery = noQuery.substring(0, q);
        }
        int slash = noQuery.lastIndexOf('/');
        String name = slash >= 0 && slash < noQuery.length() - 1 ? noQuery.substring(slash + 1) : noQuery;
        return name.isBlank() ? jdtUri : name;
    }

    static String hoverText(Hover hover) {
        return HoverMarkdown.of(hover);
    }

    /** The document URI for {@code file}; remembered, because computing one stats the file. */
    static String uri(Path file) {
        return DocumentUris.of(file);
    }

    /**
     * {@code file}'s URI as its language server knows it — what a server command takes as an argument. The
     * same string as the document's key for a server on this machine.
     */
    public String serverUri(Path file) {
        LanguageServerSession s = sessionFor(file);
        return (s == null ? WireUris.LOCAL : s.uris()).toWire(uri(file));
    }

    /**
     * The file a URI answered by {@code anchorFile}'s server names, or {@code null} when it is not a file
     * (a {@code jdt:} class file, say).
     */
    public @Nullable Path pathForServerUri(Path anchorFile, String uri) {
        LanguageServerSession s = sessionFor(anchorFile);
        return (s == null ? WireUris.LOCAL : s.uris()).toPath(uri);
    }
}
