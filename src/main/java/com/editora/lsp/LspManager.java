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

import com.editora.lsp.WorkspaceEditDrafts.EditBasis;
import com.editora.process.ProcessRunner;
import com.editora.text.LspDiagnostic;
import com.google.gson.JsonElement;
import org.eclipse.lsp4j.CompletionItem;
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
    static final String JDT_SCHEME = "jdt://";

    final BiConsumer<Path, List<LspDiagnostic>> onDiagnostics;
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

    /** Definition, references, hierarchies and the other "where does this lead" requests. */
    private final LspNavigationRequests navigation = new LspNavigationRequests(this);

    /** Completion, signature help, highlights and hover. */
    private final LspAssists assists = new LspAssists(this);

    /** Code actions, and every server edit on its way to the editor. */
    final LspCodeActions codeActions = new LspCodeActions(this);

    /** Document, range and on-type formatting. */
    private final LspFormatting formatting = new LspFormatting(this);

    /** Outlines, folding and selection ranges, colours and symbol search. */
    private final LspDocumentSymbols outline = new LspDocumentSymbols(this);

    /** Code lenses and inlay hints. */
    private final LspInlineHints inlineHints = new LspInlineHints(this);

    /** Semantic tokens and the per-document state their deltas need. */
    final SemanticTokenRequests semanticTokens = new SemanticTokenRequests(this);

    /** Symbol renames, and the edits a file or folder rename takes along. */
    private final LspRename renames = new LspRename(this);

    /** jdtls's own requests: generate, refactorings, paste imports and the rest. */
    private final JdtlsCommands jdtls = new JdtlsCommands(this);

    /** Java project builds through jdtls. */
    final JavaBuilds builds = new JavaBuilds(this);

    /** Organize Imports, per server. */
    private final LspOrganizeImports imports = new LspOrganizeImports(this);

    /** Published and pulled diagnostics, and the raw ones code actions send back. */
    private final LspDiagnostics diagnostics = new LspDiagnostics(this);

    /** Root watchers and remote scanners, and the file changes they forward to the servers. */
    private final LspFileWatching watching = new LspFileWatching(this);

    /** One unanswered request per document and kind; see {@link LatestRequests}. */
    final LatestRequests latest = new LatestRequests();

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

    final Map<String, LanguageServerSession> sessionsByRoot = new ConcurrentHashMap<>();
    final Map<String, LanguageServerSession> sessionByDocUri = new ConcurrentHashMap<>();

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

    /** Whether an install holds {@code serverId} stopped in every window right now. */
    public static boolean isHeldForInstall(String serverId) {
        return HELD_FOR_INSTALL.contains(serverId);
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

    /** Every manager not closed yet, so a test run can close the ones its tests left open. */
    static java.util.List<LspManager> openManagersForTest() {
        synchronized (MANAGERS) {
            return java.util.List.copyOf(MANAGERS);
        }
    }

    private volatile Map<String, LanguageServerSession> sharedServers = SHARED_SERVERS;

    /** This window's client on each session it uses: the primary one on a session it started. */
    private final Map<LanguageServerSession, LanguageServerSession.Client> clientOf = new ConcurrentHashMap<>();

    /** Sessions this window stopped, so the drop that follows is not taken for another window's doing. */
    private final java.util.Set<LanguageServerSession> endedHere = ConcurrentHashMap.newKeySet();

    /** Whether this window has the focus: a question a shared server asks goes to the focused window. */
    volatile java.util.function.BooleanSupplier windowFocused = () -> false;

    public void setWindowFocused(java.util.function.BooleanSupplier focused) {
        windowFocused = focused == null ? () -> false : focused;
    }

    /** Told (on the FX thread) when another window restarted a server this window shared: its documents reopen. */
    private volatile java.util.function.BiConsumer<String, Path> onSharedServerEnded = (serverId, root) -> {};

    public void setOnSharedServerEnded(java.util.function.BiConsumer<String, Path> handler) {
        onSharedServerEnded = handler == null ? (serverId, root) -> {} : handler;
    }

    /**
     * Told (on the FX thread) of the files this manager's own watchers saw change outside Editora, after the servers
     * were: a pom fixed by a git pull re-imports a failed project as a save does (fourteenth check CJ8).
     */
    volatile java.util.function.Consumer<List<WatchedFile>> onWatchedFiles = changes -> {};

    public void setOnWatchedFiles(java.util.function.Consumer<List<WatchedFile>> handler) {
        onWatchedFiles = handler == null ? changes -> {} : handler;
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
    /** Root → bash-language-server's {@code bashIde} settings there (null = its defaults until the coordinator sets them). */
    private volatile java.util.function.@Nullable Function<Path, BashIdeConfig> bashIde;

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
     * Every XML file outside the open project shares it too, markers or not: lemminx keeps no project model
     * (XML review A7, {@link XmlServer#sharedRoot}). Null until the coordinator sets it.
     */
    private volatile @Nullable Path looseRoot;

    /** Sets what the TypeScript server is started with; a change restarts the running ones. */
    public void setTypeScriptStart(boolean typeAcquisition, String nodePath) {
        String node = nodePath == null ? "" : nodePath.strip();
        boolean changed = typeAcquisition != typeScriptTypeAcquisition || !node.equals(nodePathSetting);
        typeScriptTypeAcquisition = typeAcquisition;
        nodePathSetting = node;
        if (changed) {
            availableCache.clear(); // an npm server's program may be in (or gone with) that Node.js's folder (A203)
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

    /** The indentation recorded for {@code file} (see {@link #setDocumentIndent}), or null when none is. */
    public @Nullable FormattingIndent documentIndent(Path file) {
        return documentIndents.get(uri(file));
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

    /** The config dir whose own TypeScript (TypeScriptServerInstall) the TypeScript and Astro servers prefer. */
    private volatile @Nullable Path ownTypeScriptConfigDir;

    /** Sets the config dir Editora's own TypeScript is installed under (JS review B101); null: none. */
    public void setOwnTypeScriptConfigDir(@Nullable Path configDir) {
        ownTypeScriptConfigDir = configDir;
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
            semanticTokens.wholeDocumentTokens.remove(uri);
        }
    }

    private static final List<String> REFRESHABLE_KINDS =
            List.of("diagnostic", "documentSymbol", "foldingRange", "inlayHint", "semanticTokens");

    /**
     * As {@link #invalidateRequests(Path)}, for one {@code workspace/…/refresh} kind only ({@code diagnostics},
     * {@code semanticTokens}, {@code inlayHints}, {@code foldingRanges}; {@code codeLenses} has nothing cached). Only
     * that kind is asked again, so cancelling the others dropped their answers until the next edit (review 3, B21).
     */
    public void invalidateRequests(Path file, String refreshKind) {
        String kind = switch (refreshKind) {
            case "diagnostics" -> "diagnostic";
            case "semanticTokens" -> "semanticTokens";
            case "inlayHints" -> "inlayHint";
            case "foldingRanges" -> "foldingRange";
            default -> null;
        };
        if (kind != null && file != null && servable(file)) {
            String uri = uri(file);
            latest.cancel(uri, kind);
            if (kind.equals("semanticTokens")) {
                semanticTokens.wholeDocumentTokens.remove(uri);
            }
        }
    }

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
            watching.closeRootWatchers(); // no server to tell: a folder watcher left running held an inotify instance
            pythonLibraries.closeAll();
            shutdownAll();
            com.editora.perf.AfterFirstPaint.run(ProcessRunner::warmAugmentedPathAsync);
        }
    }

    /**
     * The JDK jdtls makes its default ({@link JavaRuntimes#preferredHomeHere}): {@code setting} (the Default JDK),
     * else PATH's java, else {@code JAVA_HOME}, each only when it is a JDK; null for {@code setting} null (a remote
     * or non-Java server). Scans PATH: off the FX thread.
     */
    static @Nullable String javaPreferredHome(@Nullable String setting) {
        return setting == null ? null : JavaRuntimes.preferredHomeHere(setting);
    }

    /**
     * The Java version jdtls builds {@code file}'s folder for when it has no build file: its default runtime's
     * ({@code JavaSE-25} → 25); null when no local Java server serves it or none is known.
     */
    public @Nullable Integer javaDefaultMajor(Path file) {
        LanguageServerSession s = file == null ? null : javaSessionFor(file);
        return s == null ? null : JavaRuntimes.defaultMajor(s.javaRuntimes());
    }

    /**
     * The JDK the user chose in Settings (Build Tools ▸ Default JDK, "" for none). A Java server marks it as its
     * default runtime (else PATH's java, {@link #javaPreferredHome}) and runs Gradle on it; one started after this
     * also runs on it itself when the launcher's own Java is older than jdtls needs. A running local server is
     * sent the new default at once (Java debug review 2, B102), so what it builds and the JDK sources it shows
     * follow the program's JDK; only the JDK it runs on itself waits for a restart.
     */
    public void setJavaDefaultJdk(@Nullable String home) {
        String next = home == null ? "" : home;
        boolean changed = !next.equals(javaDefaultJdk);
        javaDefaultJdk = next;
        if (changed) {
            String prefix = sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID);
            List<LanguageServerSession> running = sessionsByRoot.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .filter(session -> com.editora.process.ExecTargets.runsHere(session.root()))
                    .toList();
            if (!running.isEmpty()) {
                startExec.execute(() -> {
                    List<Map<String, Object>> runtimes =
                            JavaRuntimes.runtimes(JavaRuntimes.discover(), javaPreferredHome(next), true);
                    running.forEach(session -> session.setJavaRuntimes(runtimes));
                });
            }
        }
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
     * Forgets the last error the session on {@code root} logged: what an import that starts again or succeeded says
     * next is its own (eleventh review CM1).
     */
    public void forgetServerError(@Nullable String serverId, @Nullable Path root) {
        LanguageServerSession s = root == null ? null : sessionsByRoot.get(sessionKey(String.valueOf(serverId), root));
        if (s != null) {
            s.forgetLastErrorLog();
        }
    }

    /**
     * What jdtls's import on {@code root} threw before it said it is ready ("artifactId can neither be null…"): its
     * files then have no project, under a ready line (thirteenth review CK2). Null when it threw nothing.
     */
    public @Nullable String startupError(@Nullable String serverId, @Nullable Path root) {
        LanguageServerSession s = root == null ? null : sessionsByRoot.get(sessionKey(String.valueOf(serverId), root));
        return s == null ? null : s.startupError();
    }

    /** As {@link #lastServerError(String)}, from the session on {@code root} only. */
    public @Nullable String lastServerError(@Nullable String serverId, @Nullable Path root) {
        if (root == null) {
            return lastServerError(serverId);
        }
        LanguageServerSession s = sessionsByRoot.get(sessionKey(String.valueOf(serverId), root));
        return s == null ? null : s.lastErrorLog();
    }

    /** How many errors {@code file}'s server has logged so far: a mark for {@link #errorLoggedSince}. */
    public long loggedErrorCount(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? 0 : s.loggedErrorCount();
    }

    /**
     * The first line of the last error {@code file}'s server logged after {@code mark} ({@link #loggedErrorCount}),
     * or null when it logged none since: why a request it answered with nothing failed (Bash review B3).
     */
    public @Nullable String errorLoggedSince(Path file, long mark) {
        LanguageServerSession s = sessionFor(file);
        return s == null || s.loggedErrorCount() <= mark ? null : s.lastLoggedError();
    }

    /**
     * The last error {@code serverId}'s servers logged (its first line), or null: what a failed import was really
     * about, which jdtls says only in its log (re-review CN2).
     */
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
        EditBasis expected = WorkspaceEditDrafts.editBasis(s);
        s.rawRequest("java/cleanup", new org.eclipse.lsp4j.TextDocumentIdentifier(serverUri(file)))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : JdtlsCommands.asWorkspaceEdit(r);
                    Platform.runLater(() -> {
                        if (e != null) {
                            cb.accept(OrganizeOutcome.FAILED);
                        } else if (edit == null || !LspOrganizeImports.changesText(edit)) {
                            cb.accept(OrganizeOutcome.UNCHANGED);
                        } else {
                            codeActions.applyWorkspaceEdit(
                                    edit,
                                    expected,
                                    ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.FAILED));
                        }
                    });
                });
    }

    /** Whether jdtls downloads every dependency's sources when it imports a project (Settings → LSP). */
    private volatile boolean javaDownloadSources;

    /** Told (on the FX thread) of the libraries whose sources jdtls attached after their class files were read. */
    private volatile java.util.function.Consumer<java.util.Set<String>> onSourceInvalidated = roots -> {};

    public void setOnSourceInvalidated(java.util.function.Consumer<java.util.Set<String>> handler) {
        onSourceInvalidated = handler == null ? roots -> {} : handler;
    }

    /**
     * Tells jdtls whether to download every dependency's sources at import ({@code java.maven.downloadSources},
     * {@code java.eclipse.downloadSources}): sent at {@code initialize} and pushed to running Java sessions, which
     * apply it at their next import or project update.
     */
    public void setJavaDownloadSources(boolean enabled) {
        javaDownloadSources = enabled;
        String prefix = sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID);
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix)) {
                session.setJavaDownloadSources(enabled);
            }
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

    /**
     * Whether lemminx-maven may search Maven Central (search.maven.org) when completing pom.xml coordinates (Settings →
     * LSP; off = the local repository only). Each completion sends what was typed; nothing is downloaded up front.
     */
    private volatile boolean mavenCentralIndex;

    /**
     * Pushes the Settings → LSP "Search Maven Central" choice (Maven review C10, review 2 D13). It is sent at {@code
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

    /** See {@link LspDiagnostics#setHtmlMarkupChecks}. */
    public void setHtmlMarkupChecks(java.util.function.BooleanSupplier on) {
        diagnostics.setHtmlMarkupChecks(on);
    }

    /** The XML servers' settings (Settings → LSP → XML); each session gets them for its own root. */
    private volatile XmlServerSettings xmlServerSettings = XmlServerSettings.defaults();

    /**
     * Sets the XML servers' (lemminx's) settings and hands every running XML and pom server its {@code xml} object,
     * which it gets pushed when that changed (XML review D3). Called on every settings apply and project reload, so
     * a folder trusted since is read again here: with "trusted" schema downloads, trusting a folder turns them on.
     */
    public void setXmlSettings(XmlServerSettings settings) {
        xmlServerSettings = settings == null ? XmlServerSettings.defaults() : settings;
        sessionsByRoot.forEach((key, session) -> {
            for (String id : List.of(XmlServerSettings.SERVER_ID, LspServerRegistry.MAVEN_POM_SERVER_ID)) {
                if (key.startsWith(sessionKeyPrefix(id))) {
                    session.setXmlSettings(xmlSectionFor(id, session.root()));
                }
            }
        });
    }

    /**
     * The {@code xml} object for {@code serverId} on {@code root}: the user's XML settings with that folder's trust,
     * and for the pom server lemminx-maven's own {@code maven} object, which a push must carry too (the extension
     * reads the pushed object whole).
     */
    Map<String, Object> xmlSectionFor(String serverId, Path root) {
        boolean local = com.editora.process.ExecTargets.runsHere(root);
        // The loose-files root is a folder Editora made, not the user's: a relative grammar or catalog has nothing to
        // be relative to there, and resolved under it named that internal folder in a false error (XML review 2 B4).
        Path base = root.equals(looseRoot) ? null : root;
        Map<String, Object> xml =
                new java.util.LinkedHashMap<>(xmlServerSettings.section(base, folderTrust.test(root), local));
        if (LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)) {
            xml.put(
                    "maven",
                    ServerInitOptions.mavenSection(
                            mavenCentralIndex, local ? ServerInitOptions.mavenRepoLocal() : null));
            // lemminx-maven says only in its log that a coordinate completion failed on its workspace (E21)
            xml.put("logs", Map.of("client", true));
        }
        return Map.copyOf(xml);
    }

    /** Root → the HTML server's settings there (Settings → LSP → HTML, a project's {@code html} object). */
    private volatile java.util.function.Function<Path, HtmlServerSettings> htmlSettings =
            root -> HtmlServerSettings.defaults();

    /**
     * Sets the HTML servers' settings, each server given those for its root; a running one gets them pushed when they
     * changed (HTML review B2, A4, D3). Called on every settings apply and project reload.
     */
    public void setHtmlSettings(java.util.function.@Nullable Function<Path, HtmlServerSettings> forRoot) {
        htmlSettings = forRoot == null ? root -> HtmlServerSettings.defaults() : forRoot;
        String prefix = sessionKeyPrefix(HtmlServerSettings.SERVER_ID);
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix)) {
                session.setHtmlSettings(htmlSettings.apply(session.root()));
            }
        });
    }

    /**
     * As {@link #setHtmlSettings(java.util.function.Function)}: the user's settings {@code user}, with the project's
     * {@code html} object ({@code projectHtml}) for servers on {@code projectRoot} (null = no project).
     */
    public void setHtmlSettings(
            com.editora.config.Settings user,
            java.util.@Nullable Map<String, Object> projectHtml,
            @Nullable Path projectRoot) {
        HtmlServerSettings own = HtmlServerSettings.of(user);
        setHtmlSettings(HtmlServerSettings.perRoot(own.withProject(projectHtml), own, projectRoot));
    }

    /**
     * Sets bash-language-server's {@code bashIde} configuration: answered when a Bash server asks for it, and
     * pushed to the running ones when it changed (they re-read it and re-lint).
     */
    public void setBashIde(BashIdeConfig config) {
        setBashIde(root -> config);
    }

    /** As {@link #setBashIde(BashIdeConfig)}, each Bash server given the configuration for its root (A1). */
    public void setBashIde(java.util.function.Function<Path, BashIdeConfig> forRoot) {
        bashIde = forRoot;
        String prefix = sessionKeyPrefix("bash");
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix)) {
                session.setBashIde(bashIdeFor(session.root()));
            }
        });
    }

    /**
     * The {@code bashIde} settings a Bash server at {@code root} is sent (null = never set). The shared root of
     * loose scripts reads nothing in the background: its scripts are wherever the user keeps them (Bash review A9).
     */
    public @Nullable BashIdeConfig bashIdeFor(Path root) {
        var forRoot = bashIde;
        BashIdeConfig config = forRoot == null ? null : forRoot.apply(root);
        if (!root.toAbsolutePath().normalize().equals(looseRoot)) {
            return config;
        }
        return (config == null ? BashIdeConfig.defaults() : config).withBackgroundAnalysisMaxFiles(0);
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

    /** The library folders of each local Python session's environment, watched for installs (review 2, B102). */
    private final PythonLibraryWatchers pythonLibraries = new PythonLibraryWatchers();

    /** How many library folders are watched for {@code session} (tests). */
    int pythonLibraryWatchesForTest(LanguageServerSession session) {
        return pythonLibraries.watchedFor(session);
    }

    /** Runs on the FX thread when an environment was created or deleted under a server's root (review 2, B103). */
    private volatile Runnable onPythonEnvironmentChanged = () -> {};

    /**
     * Installs what runs (FX thread) when a watched change creates or removes a Python environment: the window
     * re-resolves its interpreter, re-tells its servers ({@link #refreshPythonInterpreters}) and its status item.
     */
    public void setOnPythonEnvironmentChanged(Runnable handler) {
        onPythonEnvironmentChanged = handler == null ? () -> {} : handler;
    }

    /**
     * An environment folder appeared or went: told now and twice more, since {@code python -m venv} writes the
     * folder first and its {@code pyvenv.cfg} and interpreter a moment later, inside a folder no watcher reports.
     */
    void pythonEnvironmentChanged() {
        Platform.runLater(() -> onPythonEnvironmentChanged.run());
        Thread.ofVirtual().name("lsp-python-environment").start(() -> {
            for (long wait : new long[] {2_000, 4_000}) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
                if (closed) {
                    return;
                }
                Platform.runLater(() -> {
                    if (!closed) {
                        onPythonEnvironmentChanged.run();
                    }
                });
            }
        });
    }

    /**
     * Re-resolves every running Python session's interpreter off the FX thread — the project's pick changed, or an
     * environment was created or deleted ({@link #setOnPythonEnvironmentChanged}); a session whose answer changed is
     * pushed the new {@code python.pythonPath} and has its library folders watched again.
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
                    pythonLibraries.update(s);
                }
            }
        });
    }

    /** Re-reads shell files changed on disk for the Bash server, which has no watched-files support (B7). */
    final BashReanalysis bashReanalysis = new BashReanalysis(Platform::runLater);

    /** See {@link LspFileWatching#scannedRemoteRoots}. */
    java.util.Set<Path> scannedRemoteRoots() {
        return watching.scannedRemoteRoots();
    }

    /** See {@link LspFileWatching#watchesModelFile}. */
    public boolean watchesModelFile(Path file) {
        return watching.watchesModelFile(file);
    }

    /** See {@link LspFileWatching#reportsChangesTo}. */
    public boolean reportsChangesTo(Path file) {
        return watching.reportsChangesTo(file);
    }

    /** See {@link LspFileWatching#watchedRoots}. */
    java.util.Set<Path> watchedRoots() {
        return watching.watchedRoots();
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
        detectionGeneration.incrementAndGet();
    }

    /** Counts {@link #invalidateDetection} calls (an install ends with one): a server may now be fixed (B112). */
    private final java.util.concurrent.atomic.AtomicLong detectionGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /** See {@link #detectionGeneration}. */
    public long detectionGeneration() {
        return detectionGeneration.get();
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
    volatile @Nullable LspClientUi clientUi;

    /**
     * Installs the window's answers to server questions ({@code window/showMessageRequest}, Metals' input box,
     * quick pick and client commands). Each call reaches {@code ui} on the FX thread.
     */
    public void setClientUi(LspClientUi ui) {
        this.clientUi = ui;
    }

    /** The client UI sessions are given, which answers on the FX thread (tests). */
    LspClientUi clientUiForTest() {
        return fxClientUi;
    }

    /** {@link #clientUi} reached from a session's reader thread: every call is posted to the FX thread. */
    private final LspClientUi fxClientUi = new FxClientUi(this);

    /** Whether {@code file}'s server is waiting on the user's answer to an import question (sixth review AS2). */
    public boolean questionOpen(Path file) {
        LanguageServerSession s = file == null ? null : sessionFor(file);
        return s != null && s.questionOpen();
    }

    /**
     * The Maven pom server's session for {@code file}: the one it is open on, else the one whose root holds it (a pom
     * that is not open — left out, or changed on disk), the innermost; null when there is none.
     */
    private @Nullable LanguageServerSession mavenSession(@Nullable Path file) {
        LanguageServerSession s = file == null ? null : sessionFor(file);
        if (s != null || file == null) {
            return s != null && LspServerRegistry.MAVEN_POM_SERVER_ID.equals(s.serverId()) ? s : null;
        }
        Path at = file.toAbsolutePath().normalize();
        String prefix = sessionKeyPrefix(LspServerRegistry.MAVEN_POM_SERVER_ID);
        LanguageServerSession best = null;
        for (var e : sessionsByRoot.entrySet()) {
            Path root = e.getValue().root().toAbsolutePath().normalize();
            if (e.getKey().startsWith(prefix)
                    && at.startsWith(root)
                    && (best == null
                            || root.getNameCount()
                                    > best.root().toAbsolutePath().normalize().getNameCount())) {
                best = e.getValue();
            }
        }
        return best;
    }

    /**
     * Takes {@code poms} out of the workspace of the Maven pom server serving {@code file} (review 2, E3: one
     * malformed pom in its workspace empties every coordinate completion), each by the URI lemminx-maven's walk of
     * the root gave it. Returns the poms newly left out; none when {@code file} is not on the Maven server, or that
     * server is not ready to be told.
     */
    public List<Path> leaveOutOfMavenWorkspace(Path file, List<Path> poms) {
        LanguageServerSession s = mavenSession(file);
        if (s == null) {
            return List.of();
        }
        Map<String, Path> byUri = new java.util.LinkedHashMap<>();
        poms.forEach(p -> byUri.put(s.uris().toWire(uri(p)), p));
        return s.leaveOutOfWorkspace(byUri);
    }

    /** Whether {@code pom} was left out of its Maven pom server's workspace ({@link #leaveOutOfMavenWorkspace}). */
    public boolean leftOutOfMavenWorkspace(Path pom) {
        LanguageServerSession s = mavenSession(pom);
        return s != null && s.leftOutOfWorkspace(s.uris().toWire(uri(pom)));
    }

    /** Every pom left out of the workspace of the Maven pom server serving {@code file} (none when it is not). */
    public List<Path> mavenLeftOut(Path file) {
        LanguageServerSession s = mavenSession(file);
        return s == null ? List.of() : s.leftOutOfWorkspace();
    }

    /** The root of the Maven pom server serving {@code file}, or null when none does. */
    public @Nullable Path mavenRoot(@Nullable Path file) {
        LanguageServerSession s = mavenSession(file);
        return s == null ? null : s.root();
    }

    /**
     * How many coordinate completions the Maven pom server serving {@code file} failed on its workspace's projects
     * so far (review 3, E21): an answer of markup only after this grew is the broken one, not an honest no-match.
     */
    public int mavenCompletionFailures(@Nullable Path file) {
        LanguageServerSession s = mavenSession(file);
        return s == null ? 0 : s.workspaceCompletionFailures();
    }

    /**
     * When the Maven pom server serving {@code file} was launched (epoch millis), or -1 when none serves it: a pom
     * changed on disk after it may be worth a restart, one older is what it already built (review 4, E31).
     */
    public long mavenStartedMillis(@Nullable Path file) {
        LanguageServerSession s = mavenSession(file);
        return s == null ? -1 : s.startedMillis();
    }

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
        String node = nodePathSetting;
        detectExec.submit(() -> {
            boolean ok = available(serverId, com.editora.install.NpmServerBins.resolve(serverId, command, node));
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
        String node = nodePathSetting;
        detectExec.submit(() -> {
            boolean ok = available(serverId, com.editora.install.NpmServerBins.resolve(serverId, argv, node));
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
    static boolean servable(Path file) {
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
        session.didOpen(uri, LspServerRegistry.protocolLanguageId(routeLanguageId, file), text, this);
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
        semanticTokens.semanticTokenState.remove(uri); // the next open starts from a full request (#679)
        semanticTokens.semanticRequestGeneration.remove(uri);
        semanticTokens.wholeDocumentTokens.remove(uri);
        // Removed, not bumped: generations are unique across documents (see nextDiagnosticGeneration), so a
        // reply still on its way finds no entry — or a later one — and is rejected either way.
        diagnostics.diagnosticRequestGeneration.remove(uri);
        latest.cancelAll(uri);
        DocumentUris.forget(file);
        diagnostics.rawDiagnostics.remove(uri); // open-documents-only retention (#670); keyed by this, our own, URI
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

    // --- Documents lent to a running server without an editor (Project Map linked cards) ------------

    /**
     * A document lent to {@code session} for requests no editor tab asked: how many loans hold it, the opener the
     * first of them opened it under, and whether another window's editor held it then (nothing was opened).
     */
    private record Loan(LanguageServerSession session, int count, Object opener, boolean heldElsewhere) {}

    /** The documents lent now, by document key (see {@link #lendDocument}). */
    private final Map<String, Loan> lentDocuments = new ConcurrentHashMap<>();

    /**
     * Lends {@code file}, with {@code text} as its content, to the language server that already runs for it in this
     * window, so a few requests can be asked about a file no editor tab has open — the Project Map's linked cards.
     * Nothing is started and no editor is opened or raised. While lent, the requests that take a file (references,
     * call hierarchy, …) reach that server for it. Returns the handle that ends the loan — the server hears
     * {@code didClose} only once nothing else holds the document — a no-op one when an editor already has the file
     * open on a server, and null when no running server serves the file. An editor opening or closing the file
     * meanwhile keeps its own hold: the session counts who has a document open. FX thread.
     */
    public @Nullable Runnable lendDocument(Path file, String text) {
        if (closed || !enabled || file == null || !servable(file)) {
            return null;
        }
        String key = uri(file);
        if (sessionByDocUri.containsKey(key)) {
            return () -> {};
        }
        LanguageServerSession session = runningSessionFor(file);
        if (session == null) {
            return null;
        }
        cancelEviction(session);
        Loan earlier = lentDocuments.get(key);
        if (earlier != null && earlier.session() == session) {
            // Lent already (an outline loan and a question, two quick questions): this loan shares the first one's
            // hold, and the server hears didClose only when the last of them ends — the first to end used to close
            // it while the other still asked about it (Map review 4, C13).
            lentDocuments.put(key, new Loan(session, earlier.count() + 1, earlier.opener(), earlier.heldElsewhere()));
        } else {
            // Another window's editor may hold the document on this (shared) session already: its text is the one
            // the server knows, unsaved edits and all. Opening it again would send the disk text over it as a
            // change, so the loan only routes requests to the session and leaves the document as that editor has it.
            boolean heldElsewhere = session.isOpen(key);
            Object opener = new Object();
            lentDocuments.put(key, new Loan(session, 1, opener, heldElsewhere));
            if (!heldElsewhere) {
                String name =
                        file.getFileName() == null ? "" : file.getFileName().toString();
                String language = com.editora.text.LanguageRegistry.forFileName(name);
                session.didOpen(
                        key, LspServerRegistry.protocolLanguageId(language, file), text == null ? "" : text, opener);
            }
        }
        java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (ended.compareAndSet(false, true)) {
                Loan held = lentDocuments.get(key);
                if (held != null && held.session() == session) {
                    if (held.count() > 1) {
                        lentDocuments.put(
                                key, new Loan(session, held.count() - 1, held.opener(), held.heldElsewhere()));
                    } else {
                        lentDocuments.remove(key);
                        if (!held.heldElsewhere()) {
                            session.didClose(key, held.opener());
                        }
                    }
                }
                maybeScheduleEviction(session);
            }
        };
    }

    /** Whether a language server that serves {@code file} runs in this window (or has it open); starts nothing. */
    public boolean runsFor(@Nullable Path file) {
        return file != null && servable(file) && (sessionFor(file) != null || runningSessionFor(file) != null);
    }

    /** See {@link LspNavigationRequests#supportsReferences}. */
    public boolean supportsReferences(Path file) {
        return navigation.supportsReferences(file);
    }

    /**
     * The session of this window, started and initialised, that would serve {@code file}: its language's server at
     * the deepest root that holds it. Null when there is none; nothing is started.
     */
    @Nullable
    LanguageServerSession runningSessionFor(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        String serverId = LspServerRegistry.serverIdFor(com.editora.text.LanguageRegistry.forFileName(name));
        if (serverId == null) {
            return null;
        }
        String prefix = sessionKeyPrefix(serverId);
        LanguageServerSession best = null;
        for (Map.Entry<String, LanguageServerSession> entry : sessionsByRoot.entrySet()) {
            LanguageServerSession s = entry.getValue();
            Path sessionRoot = s.root();
            if (entry.getKey().startsWith(prefix)
                    && !s.isDisposed()
                    && s.isInitialized()
                    && com.editora.config.PathKeys.isAtOrUnder(file, sessionRoot)
                    && (best == null || sessionRoot.getNameCount() > best.root().getNameCount())) {
                best = s;
            }
        }
        return best;
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
        String workspace = JdtlsWorkspaces.jdtlsWorkspaceBySession.remove(session);
        CompletableFuture<Void> gone =
                session.exited().whenComplete((done, error) -> JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspace));
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

    /** TEST SEAM — {@code serverId}'s availability as if probed, so a re-detection answers it without a probe. */
    void cacheAvailabilityForTest(String serverId, boolean available) {
        availableCache.put(serverId, available);
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
     * Whether a session for {@code (serverId, root)} exists, started or still starting: a second file opened
     * while jdtls starts said "Starting…" again (Java review C18).
     */
    public boolean hasSession(String serverId, Path root) {
        String key = sessionKey(serverId, root);
        return sessionsByRoot.containsKey(key)
                || (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && sharedServers.containsKey(key));
    }

    /** Whether this window has a live session of {@code serverId} at {@code root}, started or still starting. */
    public boolean hasLiveSession(String serverId, Path root) {
        LanguageServerSession s = sessionsByRoot.get(sessionKey(serverId, root));
        return s != null && !s.isDisposed();
    }

    /**
     * Whether {@code serverId} already runs for {@code root} and has finished its handshake: a file opened into
     * it starts nothing, so there is no "starting" to announce (Java review C18).
     */
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

    /** The TypeScript {@code file}'s server said it runs ({@code $/typescriptVersion}), or "" (B109). */
    public String typeScriptVersion(@Nullable Path file) {
        LanguageServerSession s = file == null || !isManaged(file) ? null : sessionFor(file);
        return s == null ? "" : s.typeScriptVersion();
    }

    /** Whether {@code file}'s server has answered {@code initialize} (its "ready" status line has been said). */
    public boolean isReady(@Nullable Path file) {
        LanguageServerSession s = file == null || !isManaged(file) ? null : sessionFor(file);
        return s != null && s.isInitialized();
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

    /** See {@link LspCodeActions#setOnEditBlocked}. */
    public void setOnEditBlocked(Consumer<List<Path>> handler) {
        codeActions.setOnEditBlocked(handler);
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
            if ("ProjectStatus".equalsIgnoreCase(type) && session.get() != null) {
                session.get().setLastProjectStatus(msg); // what a window joining later is told (sixth review CS2)
            }
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
        client.onApplyEdit =
                (edit, respond) -> codeActions.onServerApplyEdit(session, edit, respond); // server-side fix (#670)
        client.ui = fxClientUi; // showMessageRequest and Metals' prompts, answered on the FX thread
        client.focused = () -> windowFocused.getAsBoolean();
        client.onSourceInvalidated = roots -> Platform.runLater(() -> onSourceInvalidated.accept(roots));
    }

    /**
     * Joins the server another window runs for this project (Java review C12), or null when it can no longer
     * take a window (it stopped meanwhile). The documents this window opens on it are counted with the other
     * window's, and it hears the server's diagnostics and status as the other window does.
     */
    private @Nullable LanguageServerSession join(String key, LanguageServerSession session) {
        LanguageServerSession.Client client = new LanguageServerSession.Client(
                params -> diagnostics.onPublishDiagnostics(session, params),
                statusSink(session.serverId(), () -> session));
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
        watching.ensureRootWatcher(session.root(), session.serverId());
        if (session.isInitialized()) {
            // the project's own problems first, so its verdict can name them (seventh review CR2)
            session.projectDiagnostics().forEach(client.diagnostics::accept);
        }
        if (session.isInitialized() && session.stillImporting()) {
            // jdtls is still importing: this window waits for its own ready, as the first window does; the
            // handshake's replayed ready said "Loaded and ready" over unchecked files (eighth review CQ1)
            client.status.accept("Starting", session.lastStarting());
        } else if (session.isInitialized()) {
            // Ready before this window came: it hears so now — its status bar says "Loaded and ready" instead of
            // a stale progress line, and its documents' gates and data are refreshed (re-review CN5). How the
            // project imported goes first: a failed import was "Loaded and ready" here (sixth review CS2).
            String imported = session.lastProjectStatus();
            if (imported != null) {
                client.status.accept("ProjectStatus", imported);
            }
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
        codeActions.pendingApplyExpected.remove(session);
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
            claimedWorkspace =
                    JdtlsWorkspaces.claimUsableJdtlsWorkspaceName(claimBase, LspServerRegistry.workspaceDirName(root));
        }
        LanguageServerSession[] diagnosticSource = new LanguageServerSession[1];
        LanguageServerSession session = new LanguageServerSession(
                spec,
                root,
                params -> diagnostics.onPublishDiagnostics(diagnosticSource[0], params),
                statusSink(spec.serverId(), () -> diagnosticSource[0]));
        diagnosticSource[0] = session;
        session.setClientThread(Platform::runLater); // a re-sync must not race the FX thread's own document sync
        configureClient(session.primaryClient(), key, session);
        session.setJavaOnTypeFormatting(javaOnTypeFormatting);
        session.setJavaDownloadSources(javaDownloadSources);
        session.setJavaCleanups(javaCleanups);
        session.setJavaFilteredTypes(javaFilteredTypes);
        session.setBashIde(bashIdeFor(root));
        session.setHtmlSettings(htmlSettings.apply(root));
        if (TypeScriptServer.SERVER_ID.equals(serverId)) {
            session.setTypeScriptSettings(typeScriptSettings);
        }
        if (XmlServerSettings.isLemminx(serverId)) {
            session.setXmlSettings(xmlSectionFor(serverId, root)); // trust read here, where the trust list is edited
        }
        if (claimedWorkspace != null) {
            JdtlsWorkspaces.jdtlsWorkspaceBySession.put(session, claimedWorkspace);
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
                pythonLibraries.update(session);
                session.setPythonWorkspaceDiagnostics(pythonWholeProject);
            }
            watching.ensureRootWatcher(root, serverId);
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
        // a remote host's JDKs are its own; the setting names this machine's. PATH's java is found on the start
        // thread below (a PATH scan stays off the FX thread).
        String javaJdkSetting = remote || !LspServerRegistry.JAVA_SERVER_ID.equals(serverId) ? null : javaDefaultJdk;
        boolean centralIndex = mavenCentralIndex;
        boolean downloadSources = javaDownloadSources;
        // Trust is read here, on the caller's thread, where the trust list is also edited.
        Path sdkCeiling =
                ServerInitOptions.ASTRO_SERVER_ID.equals(serverId) || TypeScriptServer.SERVER_ID.equals(serverId)
                        ? NodeServerPaths.trustedCeiling(root, folderTrust)
                        : null;
        // lemminx-maven resolves and loads what a pom names (parents, plugins, extensions, its repositories): never
        // on a folder that is not trusted. The coordinator routes such a pom to plain lemminx; this is the backstop.
        boolean pomUntrusted = LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId) && !folderTrust.test(root);
        // A server that runs the folder's own code to load its build waits for trust (UntrustedBuilds): jdtls starts
        // with its imports off, the others not at all on a folder with their build files.
        boolean folderTrusted = !UntrustedBuilds.gated(serverId) || folderTrust.test(root);
        boolean typeAcquisition = typeScriptTypeAcquisition;
        String nodeSetting = nodePathSetting;
        boolean loose = root.toAbsolutePath().normalize().equals(looseRoot);
        startExec.execute(() -> {
            awaitPriorExit(key); // a restart: let the server being replaced release what it holds
            String javaPreferredHome = javaPreferredHome(javaJdkSetting);
            if (closed || session.isDisposed()) {
                dropSession(key, session);
                return;
            }
            LspServerRegistry.ServerSpec resolvedSpec = unresolvedSpec;
            String workspaceName = null;
            if (workspaceBaseName != null && workspaceBase != null) {
                workspaceName = JdtlsWorkspaces.claimUsableJdtlsWorkspaceName(workspaceBase, workspaceBaseName);
                if (!workspaceName.equals(workspaceBaseName)) {
                    // Another window (or an earlier server still exiting) holds this project's workspace: this
                    // one imports it again on a server of its own. Said, not hidden (Java review C12).
                    Platform.runLater(() -> {
                        if (!closed) {
                            onSecondServer.accept(serverId, root);
                        }
                    });
                }
                JdtlsWorkspaces.jdtlsWorkspaceBySession.put(session, workspaceName);
                // shutdown may race the off-thread claim after the session was put in sessionsByRoot.
                // Do not leave a claim behind or start a process that its window no longer owns.
                if (session.isDisposed()) {
                    JdtlsWorkspaces.jdtlsWorkspaceBySession.remove(session, workspaceName);
                    JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspaceName);
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
                    JdtlsWorkspaces.jdtlsWorkspaceBySession.remove(session);
                    JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspaceName);
                }
            }
            List<JavaRuntimes.Jdk> hostJdks = null;
            java.util.Map<String, String> remoteEnvironment = new java.util.HashMap<>();
            if (remote && LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
                resolvedSpec = JdtlsWorkspaces.withRemoteJdtlsWorkspace(resolvedSpec, root);
                hostJdks = RemoteJavaRuntimes.discover(root);
            }
            List<String> startCommand = remote
                    ? JavaServerEnvironment.configure(
                            serverId,
                            resolvedSpec.command(),
                            remoteEnvironment,
                            exe -> JavaServerEnvironment.isRemoteJdtlsLauncher(root, exe))
                    // an npm -g server in the Settings Node.js's own folder, off PATH (HTML review 3, A203)
                    : com.editora.install.NpmServerBins.resolve(serverId, resolvedSpec.command(), nodeSetting);
            if (!remote && XmlServerSettings.isLemminx(serverId)) {
                // A Java lemminx (the pom server's, or XML's fallback) keeps lemminx-maven's artifact store under
                // user.home: Editora's, not the passwd home the JVM would read (review D14).
                startCommand = JvmServerCommand.withUserHome(startCommand, System.getProperty("user.home", ""));
                // A bundle installed before review 4 still carries Eclipse's jar signatures, which kept lemminx's
                // RELAX NG plugin from loading (A31): unsigned once, here, before a server runs on it.
                com.editora.install.JarSignatures.repairBundleOf(startCommand);
            }
            if (XmlServerSettings.SERVER_ID.equals(serverId)
                    && LemminxMavenGuard.loadsLemminxMaven(startCommand, !remote)) {
                // The plain XML server serves the poms trust keeps off lemminx-maven: with that extension on its
                // classpath it would resolve them and run their plugins anyway (XML/pom review 3, A21). Not a crash.
                LOG.warning("Not starting the XML server on " + root + ": its command loads lemminx-maven");
                endHere(session);
                dropSession(key, session);
                StartRefusal refusal = new StartRefusal(StartRefusal.Kind.XML_LOADS_MAVEN, "");
                Platform.runLater(() -> {
                    if (!closed) {
                        onStartRefused.refused(serverId, root, refusal);
                    }
                });
                return;
            }
            if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && !remote && !startCommand.isEmpty()) {
                // the Java this jdtls's bundles need: 25 for 1.63, though its launcher only checks for 21 (DS1)
                int minJava = JavaRuntimes.serverMinMajor(startCommand.get(0));
                JavaRuntimes.ServerJava serverJava = JavaRuntimes.serverJava(
                        JavaRuntimes.launcherJavaMajor(), JavaRuntimes.discover(), javaPreferredHome, minJava);
                if (serverJava.missing() && JavaServerEnvironment.isJdtlsLauncher(startCommand.get(0))) {
                    // jdtls would fail on an older Java, and the user would only see a crash loop: say what is
                    // missing instead, and do not retry.
                    endHere(session);
                    dropSession(key, session);
                    StartRefusal refusal =
                            new StartRefusal(StartRefusal.Kind.NO_JAVA_FOR_SERVER, String.valueOf(minJava));
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
            if (pomUntrusted || UntrustedBuilds.withholds(serverId, root, folderTrusted)) {
                endHere(session); // deliberate: no crash notice, no restart
                dropSession(key, session);
                Platform.runLater(() -> onStartWithheld.accept(serverId, root));
                return;
            }
            boolean importsOff = UntrustedBuilds.importsOff(serverId, folderTrusted);
            session.setStartTrust(folderTrusted, importsOff, importsOff && UntrustedBuilds.hasBuild(serverId, root));
            if (session.buildNotLoaded()) {
                Platform.runLater(() -> {
                    if (!closed) {
                        onBuildNotLoaded.accept(serverId, root);
                    }
                });
            }
            Path ownTs = ownTypeScriptConfigDir;
            Path astroTsdk = ServerInitOptions.ASTRO_SERVER_ID.equals(serverId) && !remote
                    ? NodeServerPaths.astroTypeScriptSdk(
                            root, sdkCeiling, startCommand, com.editora.install.TypeScriptServerInstall.ownSdk(ownTs))
                    : null;
            if (ServerInitOptions.ASTRO_SERVER_ID.equals(serverId) && astroTsdk == null) {
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
                TypeScriptServer.Choice choice = NodeServerPaths.typeScriptChoice(
                        root,
                        sdkCeiling,
                        startCommand,
                        loose,
                        com.editora.install.TypeScriptServerInstall.ownTsserver(ownTs));
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
                String ignored = TypeScriptServer.ignoredFolderVersion(choice, root, loose);
                Path runningTs = choice.tsserver();
                if (!ignored.isEmpty() && runningTs != null) {
                    String running = TypeScriptServer.sdkVersion(runningTs);
                    Platform.runLater(() -> onFolderTypeScriptIgnored.folderTypeScriptIgnored(root, ignored, running));
                }
            }
            if (!remote
                    && (TypeScriptServer.SERVER_ID.equals(serverId) || NodeServerPaths.nodeScriptServer(serverId))) {
                // the Node.js chosen in Settings runs every npm server's "#!/usr/bin/env node", not only tsserver (A8);
                // a project's .nvmrc picks only tsserver's, since the others are Editora's tools (HTML review 2 A103)
                session.setPathPrepend(
                        NodeServerPaths.nodeBinDir(root, nodeSetting, TypeScriptServer.SERVER_ID.equals(serverId)));
            }
            Path tsserverPath = tsserver;
            List<JavaRuntimes.Jdk> jdks = hostJdks;
            if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
                // what the start's settings carry (javaSettings), kept for every later push of them
                session.setJavaRuntimes(JavaRuntimes.runtimes(
                        jdks == null ? JavaRuntimes.discover() : jdks, javaPreferredHome, jdks == null));
            }
            // A remote jdtls is told about the host's JDKs, and gets the debug bundles as files on the host.
            List<String> startBundles = remote && LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                    ? remoteDebugBundles(root, bundles)
                    : remote ? List.of() : bundles;
            if (PYTHON_SERVER_ID.equals(serverId)) {
                // Before initialize: the first answer counts. A remote server is told the path as its host names it.
                session.setPythonPath(resolvePythonPath(root));
                pythonLibraries.update(session);
                session.setPythonWorkspaceDiagnostics(pythonWholeProject);
            }
            if (!remote) {
                watching.ensureRootWatcher(root, serverId); // file changes nobody else reports reach this server (B7)
            } else {
                watching.ensureRemoteScanner(
                        root); // no watcher here can follow it: listed when the window returns (2, B108)
            }
            session.configureStart(startCommand, remoteEnvironment, () -> {
                Object options = ServerInitOptions.startOptions(
                        serverId,
                        remote,
                        root,
                        session,
                        tsserverPath,
                        typeAcquisition,
                        loose,
                        startBundles,
                        astroTsdk,
                        onTypeFormatting,
                        jdks,
                        centralIndex,
                        javaPreferredHome,
                        downloadSources);
                return importsOff ? UntrustedBuilds.initOptionsWithImportsOff(options) : options;
            });
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

    /** Per server: what its last session that died before answering {@code initialize} wrote to stderr first. */
    private final Map<String, String> crashReasons = new ConcurrentHashMap<>();

    /**
     * Why {@code serverId}'s last session died, when it died before it was ever ready and said something on
     * stderr ("version `GLIBC_2.34' not found"); else null. A crash after it served is not explained here: its
     * stderr is a log by then, not a reason (XML review A4).
     */
    public @Nullable String crashReason(String serverId) {
        return crashReasons.get(serverId);
    }

    /** Sets the session-crashed callback (see {@link #onSessionCrashed}); marshaled to the FX thread. */
    public void setOnSessionCrashed(BiConsumer<String, Path> callback) {
        this.onSessionCrashed = callback == null ? (id, root) -> {} : callback;
    }

    /** Releases the workspace claim held by {@code session}, if any. */
    private void releaseJdtlsWorkspace(LanguageServerSession session) {
        JdtlsWorkspaces.releaseJdtlsWorkspaceName(JdtlsWorkspaces.jdtlsWorkspaceBySession.remove(session));
    }

    /** Removes a session that can no longer serve requests, so the next open starts a fresh one. */
    private void dropSession(String key, LanguageServerSession session) {
        requestErrors.forget(key);
        boolean wasMine = sessionsByRoot.remove(key, session);
        sharedServers.remove(key, session);
        clientOf.remove(session);
        boolean endedByThisWindow = endedHere.remove(session);
        sessionByDocUri.values().removeIf(s -> s == session);
        codeActions.pendingApplyExpected.remove(session);
        String workspaceName = JdtlsWorkspaces.jdtlsWorkspaceBySession.remove(session);
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
            JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspaceName);
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
                JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspaceName);
            }
            String said = session.initializedOnce() ? null : session.firstStderrLine();
            if (said == null) {
                crashReasons.remove(session.serverId());
            } else {
                crashReasons.put(session.serverId(), said);
            }
            Platform.runLater(() -> onSessionCrashed.accept(session.serverId(), session.root()));
        } else {
            JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspaceName);
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
            JdtlsWorkspaces.repairFailedJdtlsWorkspace(jdtlsWorkspaceBase, workspaceName);
            JdtlsWorkspaces.releaseJdtlsWorkspaceName(workspaceName);
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

    /** What {@link #clearJdtlsWorkspaces} did: the caches it deleted, the bytes they held, and the ones it kept
     *  because a server still uses them (or a file in them could not be deleted). */
    public record JdtlsCacheClearResult(int deleted, long bytesFreed, int kept) {}

    /** See {@link JdtlsWorkspaces#clearJdtlsWorkspaces}. */
    public static JdtlsCacheClearResult clearJdtlsWorkspaces(Path base) {
        return JdtlsWorkspaces.clearJdtlsWorkspaces(base);
    }

    @Nullable
    LanguageServerSession sessionFor(@Nullable Path file) {
        if (file == null) {
            return null;
        }
        String key = uri(file);
        LanguageServerSession open = sessionByDocUri.get(key);
        if (open != null || lentDocuments.isEmpty()) {
            return open;
        }
        Loan loan = lentDocuments.get(key);
        return loan == null ? null : loan.session(); // lent for a request with no editor (see lendDocument)
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

    /** See {@link LspAssists#completion}. */
    public void completion(Path file, int line, int character, Consumer<List<CompletionItem>> cb) {
        assists.completion(file, line, character, cb);
    }

    /** See {@link LspAssists#completion}. */
    public Runnable completion(
            Path file,
            int line,
            int character,
            int triggerKind,
            @Nullable String triggerCharacter,
            java.util.function.Function<CompletionItem, @Nullable Runnable> onAccept,
            Consumer<com.editora.completion.CompletionResult> cb) {
        return assists.completion(file, line, character, triggerKind, triggerCharacter, onAccept, cb);
    }

    /** See {@link LspAssists#resolveCompletion}. */
    public void resolveCompletion(Path file, CompletionItem item, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        assists.resolveCompletion(file, item, cb);
    }

    /** See {@link LspAssists#resolveCompletionDoc}. */
    public void resolveCompletionDoc(Path file, Object token, Consumer<String> cb) {
        assists.resolveCompletionDoc(file, token, cb);
    }

    /** See {@link LspFormatting#supportsFormatting}. */
    public boolean supportsFormatting(Path file) {
        return formatting.supportsFormatting(file);
    }

    /** See {@link LspAssists#supportsSignatureHelp}. */
    public boolean supportsSignatureHelp(Path file) {
        return assists.supportsSignatureHelp(file);
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

    /** See {@link LspAssists#signatureTriggerCharacters}. */
    public java.util.Set<Character> signatureTriggerCharacters(Path file) {
        return assists.signatureTriggerCharacters(file);
    }

    /** See {@link LspAssists#signatureHelp}. */
    public void signatureHelp(
            Path file,
            int line,
            int character,
            String triggerChar,
            boolean retrigger,
            Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        assists.signatureHelp(file, line, character, triggerChar, retrigger, cb);
    }

    /** See {@link LspAssists#signatureHelp}. */
    public void signatureHelp(
            Path file,
            int line,
            int character,
            @Nullable String triggerChar,
            boolean retrigger,
            org.eclipse.lsp4j.@Nullable SignatureHelp activeHelp,
            Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        assists.signatureHelp(file, line, character, triggerChar, retrigger, activeHelp, cb);
    }

    /** See {@link LspAssists#supportsDocumentHighlight}. */
    public boolean supportsDocumentHighlight(Path file) {
        return assists.supportsDocumentHighlight(file);
    }

    /** See {@link LspAssists#documentHighlights}. */
    public void documentHighlights(
            Path file, int line, int character, Consumer<List<com.editora.text.OccurrenceSpan>> cb) {
        assists.documentHighlights(file, line, character, cb);
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

    /** See {@link LspFileWatching#notifyWatchedFiles}. */
    public void notifyWatchedFiles(List<WatchedFile> changes) {
        watching.notifyWatchedFiles(changes);
    }

    /** See {@link LspFileWatching#projectStyleChanged}. */
    public void projectStyleChanged(Path file) {
        watching.projectStyleChanged(file);
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

    /** See {@link LspInlineHints#supportsCodeLens}. */
    public boolean supportsCodeLens(Path file) {
        return inlineHints.supportsCodeLens(file);
    }

    /** See {@link LspInlineHints#requestCodeLens}. */
    public void requestCodeLens(Path file, int startLine, int endLine, Consumer<List<CodeLensSpan>> cb) {
        inlineHints.requestCodeLens(file, startLine, endLine, cb);
    }

    /** See {@link LspInlineHints#supportsInlayHints}. */
    public boolean supportsInlayHints(Path file) {
        return inlineHints.supportsInlayHints(file);
    }

    /** See {@link LspInlineHints#requestInlayHints}. */
    public void requestInlayHints(
            Path file,
            int startLine,
            int endLine,
            int lineCount,
            int lastLineLength,
            Consumer<List<InlayHintSpan>> cb) {
        inlineHints.requestInlayHints(file, startLine, endLine, lineCount, lastLineLength, cb);
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

    /** See {@link LspNavigationRequests#supportsCallHierarchy}. */
    public boolean supportsCallHierarchy(Path file) {
        return navigation.supportsCallHierarchy(file);
    }

    /** See {@link LspNavigationRequests#supportsTypeHierarchy}. */
    public boolean supportsTypeHierarchy(Path file) {
        return navigation.supportsTypeHierarchy(file);
    }

    /** See {@link LspNavigationRequests#prepareCallHierarchy}. */
    public void prepareCallHierarchy(Path file, int line, int character, Consumer<List<HierarchyNode>> cb) {
        navigation.prepareCallHierarchy(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#prepareCallHierarchy}. */
    public void prepareCallHierarchy(
            Path file, int line, int character, Consumer<List<HierarchyNode>> cb, @Nullable Consumer<String> onError) {
        navigation.prepareCallHierarchy(file, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#prepareCallHierarchyAt}. */
    public void prepareCallHierarchyAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<HierarchyNode>> cb) {
        navigation.prepareCallHierarchyAt(anchor, documentUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#callHierarchyChildren}. */
    public void callHierarchyChildren(Path anchor, Object raw, boolean incoming, Consumer<List<HierarchyNode>> cb) {
        navigation.callHierarchyChildren(anchor, raw, incoming, cb);
    }

    /** See {@link LspNavigationRequests#callHierarchyChildren}. */
    public void callHierarchyChildren(
            Path anchor,
            Object raw,
            boolean incoming,
            Consumer<List<HierarchyNode>> cb,
            @Nullable Consumer<String> onError) {
        navigation.callHierarchyChildren(anchor, raw, incoming, cb, onError);
    }

    /** See {@link LspNavigationRequests#prepareTypeHierarchy}. */
    public void prepareTypeHierarchy(Path file, int line, int character, Consumer<List<HierarchyNode>> cb) {
        navigation.prepareTypeHierarchy(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#prepareTypeHierarchyAt}. */
    public void prepareTypeHierarchyAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<HierarchyNode>> cb) {
        navigation.prepareTypeHierarchyAt(anchor, documentUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#prepareTypeHierarchyAt}. */
    public void prepareTypeHierarchyAt(
            Path anchor,
            @Nullable Path file,
            @Nullable String libraryUri,
            int line,
            int character,
            Consumer<List<HierarchyNode>> cb) {
        navigation.prepareTypeHierarchyAt(anchor, file, libraryUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#typeHierarchyChildren}. */
    public void typeHierarchyChildren(Path anchor, Object raw, boolean supertypes, Consumer<List<HierarchyNode>> cb) {
        navigation.typeHierarchyChildren(anchor, raw, supertypes, cb);
    }

    /** See {@link LspRename#supportsRename}. */
    public boolean supportsRename(Path file) {
        return renames.supportsRename(file);
    }

    /** See {@link LspRename#supportsPrepareRename}. */
    public boolean supportsPrepareRename(Path file) {
        return renames.supportsPrepareRename(file);
    }

    /** The outcome of {@code prepareRename}: whether renaming here is possible, and the placeholder (the
     *  current name) when the server supplied one — blank means "derive it from the document range". */
    public record RenamePrep(
            boolean allowed, String placeholder, int startLine, int startCol, int endLine, int endCol) {
        static final RenamePrep REFUSED = new RenamePrep(false, "", 0, 0, 0, 0);
    }

    /** See {@link LspRename#prepareRename}. */
    public void prepareRename(Path file, int line, int character, Consumer<RenamePrep> cb) {
        renames.prepareRename(file, line, character, cb);
    }

    /** See {@link LspRename#previewRename}. */
    public void previewRename(
            Path file, int line, int character, String newName, Consumer<WorkspaceEditMapper.Mapped> cb) {
        renames.previewRename(file, line, character, newName, cb);
    }

    /** See {@link LspRename#previewRename}. */
    public void previewRename(
            Path file,
            int line,
            int character,
            String newName,
            java.util.function.BiConsumer<WorkspaceEditMapper.@Nullable Mapped, @Nullable String> cb) {
        renames.previewRename(file, line, character, newName, cb);
    }

    /** See {@link LspRename#rename}. */
    public void rename(Path file, int line, int character, String newName, Consumer<Boolean> cb) {
        renames.rename(file, line, character, newName, cb);
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

    /** See {@link LspFormatting#supportsRangeFormatting}. */
    public boolean supportsRangeFormatting(Path file) {
        return formatting.supportsRangeFormatting(file);
    }

    /** See {@link LspFormatting#onTypeTriggerCharacters}. */
    public java.util.Set<Character> onTypeTriggerCharacters(Path file) {
        return formatting.onTypeTriggerCharacters(file);
    }

    /** See {@link LspFormatting#onTypeFormatting}. */
    public void onTypeFormatting(
            Path file,
            int line,
            int character,
            char ch,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        formatting.onTypeFormatting(file, line, character, ch, tabSize, insertSpaces, cb);
    }

    /** See {@link LspFormatting#rangeFormatting}. */
    public void rangeFormatting(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        formatting.rangeFormatting(file, startLine, startChar, endLine, endChar, tabSize, insertSpaces, cb);
    }

    /** See {@link LspFormatting#rangeFormatting}. */
    public void rangeFormatting(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            FormattingIndent indent,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        formatting.rangeFormatting(file, startLine, startChar, endLine, endChar, indent, cb);
    }

    /** See {@link LspFormatting#formatFailure}. */
    public @Nullable FormatFailure formatFailure(Path file) {
        return formatting.formatFailure(file);
    }

    /** See {@link LspFormatting#formatDocument}. */
    public void formatDocument(
            Path file, int tabSize, boolean insertSpaces, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        formatting.formatDocument(file, tabSize, insertSpaces, cb);
    }

    /** See {@link LspFormatting#formatDocument}. */
    public void formatDocument(Path file, FormattingIndent indent, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        formatting.formatDocument(file, indent, cb);
    }

    /** See {@link LspDocumentSymbols#supportsDocumentSymbols}. */
    public boolean supportsDocumentSymbols(Path file) {
        return outline.supportsDocumentSymbols(file);
    }

    /** See {@link LspDocumentSymbols#documentSymbols}. */
    public void documentSymbols(Path file, Consumer<List<SymbolNode>> cb) {
        outline.documentSymbols(file, cb);
    }

    /** See {@link LspDocumentSymbols#outlinesClosedFile}. */
    public boolean outlinesClosedFile(@Nullable Path file) {
        return outline.outlinesClosedFile(file);
    }

    /** See {@link LspDocumentSymbols#closedFileSymbols}. */
    public void closedFileSymbols(Path file, String text, Consumer<@Nullable List<SymbolNode>> cb) {
        outline.closedFileSymbols(file, text, cb);
    }

    /** See {@link LspDocumentSymbols#memberSpans}. */
    public void memberSpans(
            Path anchor, @Nullable Path file, @Nullable String libraryUri, Consumer<List<MemberSpan>> cb) {
        outline.memberSpans(anchor, file, libraryUri, cb);
    }

    /** See {@link LspDocumentSymbols#jdkClassFile}. */
    public static boolean jdkClassFile(String libraryUri) {
        return LspDocumentSymbols.jdkClassFile(libraryUri);
    }

    /** See {@link LspDocumentSymbols#latestDocumentSymbols}. */
    public void latestDocumentSymbols(Path file, Consumer<List<SymbolNode>> cb) {
        outline.latestDocumentSymbols(file, cb);
    }

    /** What a deduplicated request's answer depends on: equal stamps mean the server is asked the same thing. */
    record RequestStamp(
            @Nullable LanguageServerSession session,
            @Nullable Integer version,
            @Nullable Object arguments) {}

    static RequestStamp stamp(LanguageServerSession session, String uri, @Nullable Object arguments) {
        return new RequestStamp(session, session.documentVersion(uri), arguments);
    }

    // --- folding ranges (#738) / selection ranges (#739) ---------------------------------------------

    /** See {@link LspDocumentSymbols#supportsFoldingRanges}. */
    public boolean supportsFoldingRanges(Path file) {
        return outline.supportsFoldingRanges(file);
    }

    /** See {@link LspDocumentSymbols#supportsSelectionRanges}. */
    public boolean supportsSelectionRanges(Path file) {
        return outline.supportsSelectionRanges(file);
    }

    /** See {@link LspDocumentSymbols#foldingRanges}. */
    public void foldingRanges(Path file, Consumer<List<com.editora.text.FoldRegions.Region>> cb) {
        outline.foldingRanges(file, cb);
    }

    /** See {@link LspDocumentSymbols#supportsDocumentColor}. */
    public boolean supportsDocumentColor(Path file) {
        return outline.supportsDocumentColor(file);
    }

    /** See {@link LspDocumentSymbols#documentColors}. */
    public void documentColors(Path file, Consumer<List<com.editora.text.ColorSwatch>> cb) {
        outline.documentColors(file, cb);
    }

    /** See {@link LspDocumentSymbols#selectionRanges}. */
    public void selectionRanges(Path file, int line, int character, int[] lineStarts, Consumer<List<int[]>> cb) {
        outline.selectionRanges(file, line, character, lineStarts, cb);
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

    /** See {@link LspDocumentSymbols#supportsWorkspaceSymbols}. */
    public boolean supportsWorkspaceSymbols(Path file) {
        return outline.supportsWorkspaceSymbols(file);
    }

    /** See {@link LspDocumentSymbols#workspaceSymbols}. */
    public void workspaceSymbols(Path anchorFile, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        outline.workspaceSymbols(anchorFile, query, cb);
    }

    /** See {@link LspDocumentSymbols#javaLibraryTypes}. */
    public void javaLibraryTypes(@Nullable Path underRoot, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        outline.javaLibraryTypes(underRoot, query, cb);
    }

    /** See {@link SemanticTokenRequests#supportsSemanticTokens}. */
    public boolean supportsSemanticTokens(Path file) {
        return semanticTokens.supportsSemanticTokens(file);
    }

    /** True if an LSP {@code Either<Boolean, ?>} capability is present (right form) or explicitly true. */
    static boolean eitherTrue(org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, ?> e) {
        return e != null && (e.isRight() || Boolean.TRUE.equals(e.getLeft()));
    }

    /** See {@link LspDiagnostics#diagnosticGenerationEntries}. */
    int diagnosticGenerationEntries() {
        return diagnostics.diagnosticGenerationEntries();
    }

    /** See {@link SemanticTokenRequests#wholeDocumentTokensCurrent}. */
    public boolean wholeDocumentTokensCurrent(Path file) {
        return semanticTokens.wholeDocumentTokensCurrent(file);
    }

    /** See {@link SemanticTokenRequests#requestSemanticTokens}. */
    public void requestSemanticTokens(
            Path file,
            int startLine,
            int endLine,
            int lineCount,
            int lastLineLength,
            Consumer<List<com.editora.text.SemanticToken>> cb) {
        semanticTokens.requestSemanticTokens(file, startLine, endLine, lineCount, lastLineLength, cb);
    }

    /** See {@link LspAssists#hover}. */
    public void hover(Path file, int line, int character, Consumer<String> cb) {
        assists.hover(file, line, character, cb);
    }

    /** See {@link LspAssists#hover}. */
    public void hover(Path file, int line, int character, Consumer<String> cb, Consumer<String> onFailure) {
        assists.hover(file, line, character, cb, onFailure);
    }

    /** See {@link LspAssists#hoverAt}. */
    public void hoverAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<String> cb,
            Consumer<String> onFailure) {
        assists.hoverAt(anchor, documentUri, line, character, cb, onFailure);
    }

    /** See {@link LspNavigationRequests#definition}. */
    public void definition(Path file, int line, int character, Consumer<List<Target>> cb) {
        navigation.definition(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#definition}. */
    public void definition(
            Path file, int line, int character, Consumer<List<Target>> cb, @Nullable Consumer<String> onError) {
        navigation.definition(file, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#definitionAt}. */
    public void definitionAt(
            Path anchorFile, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        navigation.definitionAt(anchorFile, documentUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#implementation}. */
    public void implementation(Path file, int line, int character, Consumer<List<Target>> cb) {
        navigation.implementation(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#implementationAt}. */
    public void implementationAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        navigation.implementationAt(anchor, documentUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#implementationAt}. */
    public void implementationAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        navigation.implementationAt(anchor, documentUri, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#typeDefinition}. */
    public void typeDefinition(Path file, int line, int character, Consumer<List<Target>> cb) {
        navigation.typeDefinition(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#typeDefinitionAt}. */
    public void typeDefinitionAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        navigation.typeDefinitionAt(anchor, documentUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#typeDefinitionAt}. */
    public void typeDefinitionAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        navigation.typeDefinitionAt(anchor, documentUri, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#superMethodAt}. */
    public void superMethodAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        navigation.superMethodAt(anchor, documentUri, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#supportsSuperMethod}. */
    public boolean supportsSuperMethod(Path file) {
        return navigation.supportsSuperMethod(file);
    }

    /** See {@link LspNavigationRequests#declaration}. */
    public void declaration(Path file, int line, int character, Consumer<List<Target>> cb) {
        navigation.declaration(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#declarationAt}. */
    public void declarationAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        navigation.declarationAt(anchor, documentUri, line, character, cb);
    }

    /** See {@link LspNavigationRequests#declarationAt}. */
    public void declarationAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        navigation.declarationAt(anchor, documentUri, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#supportsImplementation}. */
    public boolean supportsImplementation(Path file) {
        return navigation.supportsImplementation(file);
    }

    /** See {@link LspNavigationRequests#supportsTypeDefinition}. */
    public boolean supportsTypeDefinition(Path file) {
        return navigation.supportsTypeDefinition(file);
    }

    /** See {@link LspNavigationRequests#supportsDeclaration}. */
    public boolean supportsDeclaration(Path file) {
        return navigation.supportsDeclaration(file);
    }

    /** See {@link LspNavigationRequests#documentLinkTarget}. */
    public void documentLinkTarget(
            Path file, int line, int character, Consumer<com.editora.editops.HtmlLinks.@Nullable Target> cb) {
        navigation.documentLinkTarget(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#lastMissingInclude}. */
    public @Nullable String lastMissingInclude() {
        return navigation.lastMissingInclude();
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

    /** See {@link LspDiagnostics#rawDiagnosticsFor}. */
    List<org.eclipse.lsp4j.Diagnostic> rawDiagnosticsFor(Path file) {
        return diagnostics.rawDiagnosticsFor(file);
    }

    /** See {@link LspCodeActions#codeActions}. */
    public void codeActions(
            Path file, int startLine, int startChar, int endLine, int endChar, Consumer<List<CodeActionItem>> cb) {
        codeActions.codeActions(file, startLine, startChar, endLine, endChar, cb);
    }

    /** See {@link LspCodeActions#codeActions}. */
    public void codeActions(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            @Nullable List<String> only,
            Consumer<List<CodeActionItem>> cb) {
        codeActions.codeActions(file, startLine, startChar, endLine, endChar, only, cb);
    }

    /** See {@link LspCodeActions#codeActionsFor}. */
    public void codeActionsFor(Path file, org.eclipse.lsp4j.Diagnostic diagnostic, Consumer<List<CodeActionItem>> cb) {
        codeActions.codeActionsFor(file, diagnostic, cb);
    }

    /** See {@link LspCodeActions#applyCodeAction}. */
    public void applyCodeAction(Path file, Object raw, Consumer<Boolean> cb) {
        codeActions.applyCodeAction(file, raw, cb);
    }

    /** See {@link LspCodeActions#lastCodeActionFailure}. */
    public @Nullable String lastCodeActionFailure() {
        return codeActions.lastCodeActionFailure();
    }

    /** See {@link LspCodeActions#lastEditUnresolvedUri}. */
    public @Nullable String lastEditUnresolvedUri() {
        return codeActions.lastEditUnresolvedUri();
    }

    /** See {@link LspCodeActions#lastCodeActionEmpty}. */
    public boolean lastCodeActionEmpty() {
        return codeActions.lastCodeActionEmpty();
    }

    /** See {@link LspCodeActions#applyCodeAction}. */
    public void applyCodeAction(Path file, CodeActionItem item, Consumer<Boolean> cb) {
        codeActions.applyCodeAction(file, item, cb);
    }

    /** See {@link LspCodeActions#setApplyEditHandler}. */
    public void setApplyEditHandler(
            java.util.function.BiConsumer<WorkspaceEditMapper.Mapped, Consumer<Boolean>> handler) {
        codeActions.setApplyEditHandler(handler);
    }

    /** Why a request failed, as {@code onError} callbacks hear it: {@code "timeout"}, or the server's message. */
    static String failureReason(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause instanceof java.util.concurrent.TimeoutException ? "timeout" : String.valueOf(cause.getMessage());
    }

    /** See {@link LspNavigationRequests#references}. */
    public void references(Path file, int line, int character, Consumer<List<Target>> cb) {
        navigation.references(file, line, character, cb);
    }

    /** See {@link LspNavigationRequests#references}. */
    public void references(
            Path file, int line, int character, Consumer<List<Target>> cb, @Nullable Consumer<String> onError) {
        navigation.references(file, line, character, cb, onError);
    }

    /** See {@link LspNavigationRequests#referencesAt}. */
    public void referencesAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        navigation.referencesAt(anchor, documentUri, line, character, cb, onError);
    }

    /** See {@link LspAssists#triggerCharacters}. */
    public java.util.Set<Character> triggerCharacters(Path file) {
        return assists.triggerCharacters(file);
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

    /**
     * Told (on the FX thread) when jdtls starts on a folder that is not trusted and has a Maven or Gradle build it
     * therefore did not load (see {@link UntrustedBuilds}).
     */
    private volatile BiConsumer<String, Path> onBuildNotLoaded = (id, root) -> {};

    public void setOnBuildNotLoaded(BiConsumer<String, Path> callback) {
        this.onBuildNotLoaded = callback == null ? (id, root) -> {} : callback;
    }

    /** Whether the server serving {@code file} started without the folder's build because it is not trusted. */
    public boolean buildNotLoaded(@Nullable Path file) {
        LanguageServerSession s = file == null || !servable(file) ? null : sessionByDocUri.get(uri(file));
        return s != null && s.buildNotLoaded();
    }

    /**
     * After a trust decision (here or in another window): stops each server that loads a project's build
     * ({@link UntrustedBuilds#gated}) whose folder's trust no longer matches the one it started with — a jdtls that
     * started without its build on a folder now trusted, and any of them on a folder no longer trusted. True when
     * one stopped; the caller opens their files again, which starts them as the trust now says. A server that
     * started untrusted with no build to load (a loose file's) has nothing to gain and keeps running.
     */
    public boolean restartForTrust() {
        List<Map.Entry<String, Path>> stale = new ArrayList<>();
        sessionsByRoot.forEach((key, session) -> {
            String id = session.serverId();
            if (!UntrustedBuilds.gated(id)) {
                return;
            }
            boolean trusted = folderTrust.test(session.root());
            boolean wasTrusted = session.startedTrusted();
            boolean restart = wasTrusted ? !trusted : trusted && session.buildImportsOff();
            if (restart) {
                stale.add(Map.entry(id, session.root())); // the Path itself: a remote root is not on this disk
            }
        });
        stale.forEach(e -> shutdownServerAt(e.getKey(), e.getValue()));
        return !stale.isEmpty();
    }

    /** Told when a Java server starts on a second workspace for a project another window already serves. */
    private volatile BiConsumer<String, Path> onSecondServer = (id, root) -> {};

    public void setOnSecondServer(BiConsumer<String, Path> callback) {
        this.onSecondServer = callback == null ? (id, root) -> {} : callback;
    }

    /** Told (on the FX thread) that a server was <b>not started</b> because it would need untrusted code. */
    private volatile BiConsumer<String, Path> onStartWithheld = (id, root) -> {};

    /** Notified (FX thread) with the root, the folder's own TypeScript version and the running one (JS review B109). */
    public interface TypeScriptVersionListener {
        void folderTypeScriptIgnored(Path root, String folderVersion, String runningVersion);
    }

    private volatile TypeScriptVersionListener onFolderTypeScriptIgnored = (r, f, v) -> {};

    /** See {@link TypeScriptVersionListener}: an untrusted folder's own TypeScript of another major is not run. */
    public void setOnFolderTypeScriptIgnored(TypeScriptVersionListener callback) {
        this.onFolderTypeScriptIgnored = callback == null ? (r, f, v) -> {} : callback;
    }

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

    /** See {@link NodeServerPaths#typeScriptServerExecutable}. */
    public static @Nullable Path typeScriptServerExecutable(List<String> command) {
        return NodeServerPaths.typeScriptServerExecutable(command);
    }

    /** See {@link NodeServerPaths#findTypeScriptSdk}. */
    public static @Nullable Path findTypeScriptSdk(@Nullable Path start, @Nullable Path ceiling) {
        return NodeServerPaths.findTypeScriptSdk(start, ceiling);
    }

    /** See {@link LspDiagnostics#setOnDiagnosticsUnchanged}. */
    public void setOnDiagnosticsUnchanged(Consumer<Path> handler) {
        diagnostics.setOnDiagnosticsUnchanged(handler);
    }

    /** See {@link LspDiagnostics#pullDiagnostics}. */
    public void pullDiagnostics(Path file) {
        diagnostics.pullDiagnostics(file);
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
        watching.closeRootWatchers();
        pythonLibraries.close();
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
        diagnostics.rawDiagnostics.clear();
        semanticTokens.semanticTokenState.clear();
        semanticTokens.semanticRequestGeneration.clear();
        semanticTokens.wholeDocumentTokens.clear();
        latest.clear();
        diagnostics.diagnosticRequestGeneration.clear();
        codeActions.pendingApplyExpected.clear();
        javaScriptShadows.values().forEach(LspManager::deleteJavaScriptShadow);
        javaScriptShadows.clear();
        sessions.forEach(this::disposeDeliberately);
    }

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
                codeActions.pendingApplyExpected.remove(s);
                disposeDeliberately(e.getKey(), s);
                any = true;
            }
        }
        return any;
    }

    /**
     * Stops {@code serverId}'s sessions on folders that are no longer trusted (a revoke, here or in another window):
     * the pom server must not keep resolving a folder's poms once its trust is gone (XML/pom review 2, E1).
     */
    public void shutdownUntrusted(String serverId) {
        String prefix = sessionKeyPrefix(serverId);
        List<Path> untrusted = new ArrayList<>();
        sessionsByRoot.forEach((key, session) -> {
            if (key.startsWith(prefix) && !folderTrust.test(session.root())) {
                untrusted.add(session.root());
            }
        });
        untrusted.forEach(root -> shutdownServerAt(serverId, root));
    }

    /** Shuts down every session for {@code serverId} (e.g. when that server's per-server toggle goes off). */
    public void shutdownServer(String serverId) {
        shutdownServerAt(serverId, null);
    }

    /**
     * Stops {@code serverId}'s session on {@code root} only (null: on every root). Re-importing one project that did
     * not import restarted every Java server in the window, the healthy projects' too (fifth review CV2).
     */
    public void shutdownServerAt(String serverId, @Nullable Path root) {
        String prefix = sessionKeyPrefix(serverId);
        Path only = root == null ? null : root.toAbsolutePath().normalize();
        var it = sessionsByRoot.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(prefix)
                    && (only == null
                            || only.equals(e.getValue().root().toAbsolutePath().normalize()))) {
                LanguageServerSession s = e.getValue();
                it.remove(); // before it stops: this window's own drop must not read as another's
                sessionByDocUri.values().removeIf(v -> v == s);
                codeActions.pendingApplyExpected.remove(s);
                disposeDeliberately(e.getKey(), s);
            }
        }
    }

    // --- Internals -----------------------------------------------------------------------------

    /** Whether Problems shows the whole project, which Pyright then checks file by file (review 2, B105). */
    private volatile boolean pythonWholeProject;

    /**
     * Says whether the consumer only shows diagnostics for open documents (the Problems window's default
     * scope). It then never sees a publish for any other file. Off by default: everything is delivered.
     */
    public void setOpenDocumentDiagnosticsOnly(boolean openDocumentsOnly) {
        diagnostics.openDocumentDiagnosticsOnly = openDocumentsOnly;
        pythonWholeProject = !openDocumentsOnly;
        for (LanguageServerSession s : sessionsByRoot.values()) {
            if (PYTHON_SERVER_ID.equals(s.serverId())) {
                s.setPythonWorkspaceDiagnostics(!openDocumentsOnly); // Pyright checks the closed files too (B105)
            }
        }
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

    /** See {@link JdtlsCommands#jdtlsGenerateCandidates}. */
    public void jdtlsGenerateCandidates(
            Path file, JdtlsGenerate.Kind kind, Object actionParams, Consumer<JdtlsGenerate.Plan> cb) {
        jdtls.jdtlsGenerateCandidates(file, kind, actionParams, cb);
    }

    /** See {@link JdtlsCommands#jdtlsGenerateApply}. */
    public void jdtlsGenerateApply(
            Path file,
            JdtlsGenerate.Kind kind,
            Object actionParams,
            JsonElement checkResponse,
            Map<Path, String> expectedAtAction,
            List<JdtlsGenerate.Candidate> chosen,
            Consumer<Boolean> cb) {
        jdtls.jdtlsGenerateApply(file, kind, actionParams, checkResponse, expectedAtAction, chosen, cb);
    }

    /** See {@link JdtlsCommands#jdtlsGenerateApply}. */
    public void jdtlsGenerateApply(
            Path file,
            JdtlsGenerate.Kind kind,
            Object actionParams,
            JsonElement checkResponse,
            Map<Path, String> expectedAtAction,
            List<JdtlsGenerate.Candidate> chosen,
            @Nullable List<JdtlsGenerate.Candidate> chosenConstructors,
            Consumer<Boolean> cb) {
        jdtls.jdtlsGenerateApply(
                file, kind, actionParams, checkResponse, expectedAtAction, chosen, chosenConstructors, cb);
    }

    /** See {@link JdtlsCommands#jdtlsRequest}. */
    public void jdtlsRequest(Path file, String method, Object params, Consumer<JsonElement> cb) {
        jdtls.jdtlsRequest(file, method, params, cb);
    }

    /** See {@link JdtlsCommands#jdtlsApplyEdit}. */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtls.jdtlsApplyEdit(file, method, params, expectedAtAction, cb);
    }

    /** See {@link JdtlsCommands#jdtlsApplyEdit}. */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtls.jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, cb);
    }

    /** See {@link JdtlsCommands#jdtlsApplyEdit}. */
    public void jdtlsApplyEdit(
            Path file,
            String method,
            Object params,
            Map<Path, String> expectedAtAction,
            java.util.function.@Nullable BiConsumer<String, Runnable> confirm,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename,
            java.util.function.BiConsumer<Boolean, String> cb) {
        jdtls.jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, onRename, cb);
    }

    /** See {@link JdtlsCommands#jdtlsApplyEdit}. */
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
        jdtls.jdtlsApplyEdit(file, method, params, expectedAtAction, confirm, onRename, preview, cb);
    }

    /** The raw result as gson, or null — the custom {@code java/…} requests answer as untyped JSON. */
    public static com.google.gson.@Nullable JsonElement asJson(@Nullable Object raw) {
        if (raw instanceof com.google.gson.JsonElement json) {
            return json;
        }
        return raw == null ? null : LanguageServerSession.LSP_GSON.toJsonTree(raw);
    }

    /** See {@link JdtlsCommands#handlePasteEvent}. */
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
        jdtls.handlePasteEvent(
                file, startLine, startChar, endLine, endChar, pastedText, tabSize, insertSpaces, stillValid, cb);
    }

    /** See {@link JdtlsCommands#handlePasteEvent}. */
    public void handlePasteEvent(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            String pastedText,
            int tabSize,
            boolean insertSpaces,
            @Nullable Path copiedFrom,
            java.util.function.BooleanSupplier stillValid,
            java.util.function.@Nullable Supplier<String> current,
            boolean retry,
            Consumer<Boolean> cb) {
        jdtls.handlePasteEvent(
                file,
                startLine,
                startChar,
                endLine,
                endChar,
                pastedText,
                tabSize,
                insertSpaces,
                copiedFrom,
                stillValid,
                current,
                retry,
                cb);
    }

    /** See {@link JdtlsCommands#smartSemicolonPosition}. */
    public void smartSemicolonPosition(Path file, int line, int character, Consumer<int[]> cb) {
        jdtls.smartSemicolonPosition(file, line, character, cb);
    }

    /** See {@link JdtlsCommands#isTestFile}. */
    public void isTestFile(Path file, Consumer<Boolean> cb) {
        jdtls.isTestFile(file, cb);
    }

    /** See {@link JdtlsCommands#resolveStackTraceLocation}. */
    public void resolveStackTraceLocation(Path anchorFile, String traceLine, Consumer<String> cb) {
        jdtls.resolveStackTraceLocation(anchorFile, traceLine, cb);
    }

    /** See {@link JavaBuilds#refreshProjectDiagnostics}. */
    public void refreshProjectDiagnostics(Path file, Consumer<BuildOutcome> cb) {
        builds.refreshProjectDiagnostics(file, cb);
    }

    /**
     * The jdtls session serving {@code file}: its own when jdtls manages it, else the one whose root holds it
     * (the deepest) — a pom.xml or build.gradle belongs to the Java project beside it although another server, or
     * none, edits it. Before this, Reload and Build Project run from a pom.xml tab went to lemminx-maven (Java
     * review C2). Null when no jdtls serves the file's folder.
     */
    @Nullable
    LanguageServerSession javaSessionFor(@Nullable Path file) {
        return serverSessionFor(LspServerRegistry.JAVA_SERVER_ID, file);
    }

    /** {@code serverId}'s session serving {@code file}: its own, else the live one whose root holds it most closely. */
    @Nullable
    LanguageServerSession serverSessionFor(String serverId, @Nullable Path file) {
        LanguageServerSession own = sessionFor(file);
        if (own != null && serverId.equals(own.serverId())) {
            return own;
        }
        if (file == null) {
            return null;
        }
        Path at = file.toAbsolutePath().normalize();
        String prefix = sessionKeyPrefix(serverId);
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

    /** See {@link LspRename#willRenameFile}. */
    public void willRenameFile(Path from, Path to, Consumer<Boolean> proceed) {
        renames.willRenameFile(from, to, proceed);
    }

    /**
     * What a rename does when jdtls has no edit for it. {@code answered} false: it could not say (still importing,
     * or the request failed), so the package line and imports would silently stay as they were; true: it answered
     * with nothing for a {@code .java} file moved to another folder. Returns whether to rename the file anyway.
     */
    public interface RenameFallback {
        boolean renameWithoutEdits(Path from, Path to, boolean answered);
    }

    /** See {@link LspRename#mayHoldJava}. */
    public static boolean mayHoldJava(Path dir) {
        return LspRename.mayHoldJava(dir);
    }

    /** See {@link LspRename#willRenameFile}. */
    public void willRenameFile(Path from, Path to, Consumer<Boolean> proceed, RenameFallback fallback) {
        renames.willRenameFile(from, to, proceed, fallback);
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
        String name = s == null ? null : JdtlsWorkspaces.jdtlsWorkspaceBySession.get(s);
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
        codeActions.pendingApplyExpected.remove(s);
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
                if (JdtlsWorkspaces.claimedJdtlsWorkspaces.add(name)) {
                    try {
                        deleted = JdtlsWorkspaces.deleteUnlockedJdtlsWorkspace(workspace);
                    } finally {
                        JdtlsWorkspaces.claimedJdtlsWorkspaces.remove(name);
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

    /**
     * Whether another Java file is open on {@code saved}'s jdtls, in this window or another sharing it: a save there
     * can break it, and nothing else rechecks it (eighth review AQ1, ninth review CP1).
     */
    public boolean otherJavaDocumentOpen(@Nullable Path saved) {
        LanguageServerSession s = javaSessionFor(saved);
        return s != null && saved != null && s.otherJavaDocumentOpen(uri(saved));
    }

    /**
     * Has jdtls recheck the other Java files open on {@code saved}'s server against what was just saved, without a
     * build (tenth review CO1).
     */
    public void revalidateOtherJavaDocuments(@Nullable Path saved) {
        LanguageServerSession s = javaSessionFor(saved);
        if (s != null && saved != null) {
            s.revalidateOtherJavaDocuments(uri(saved));
        }
    }

    /** Whether {@code a} and {@code b} are served by one running jdtls (a save in one can break the other). */
    public boolean sameJavaSession(@Nullable Path a, @Nullable Path b) {
        LanguageServerSession s = javaSessionFor(a);
        return s != null && s == javaSessionFor(b);
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

    /** See {@link JavaBuilds#buildJavaWorkspace}. */
    public void buildJavaWorkspace(Path file, boolean full, Consumer<BuildOutcome> cb) {
        builds.buildJavaWorkspace(file, full, cb);
    }

    /**
     * A build's outcome and the compile errors jdtls published while it ran, by file (only files with errors;
     * every file, open or not — the Problems window's open-files filter does not apply).
     */
    public record BuildResult(BuildOutcome outcome, Map<Path, List<LspDiagnostic>> errors) {}

    /** See {@link JavaBuilds#buildJavaWorkspaceForLaunch}. */
    public void buildJavaWorkspaceForLaunch(Path file, Consumer<BuildResult> cb) {
        builds.buildJavaWorkspaceForLaunch(file, cb);
    }

    /** What an Organize Imports did. */
    public enum OrganizeOutcome {
        ORGANIZED,
        UNCHANGED,
        /** The file's server has no way to organize imports. */
        UNSUPPORTED,
        /** The server failed or gave up (jdtls cancels the whole operation when it cannot ask about a name). */
        FAILED,
        /** The file changed while the server worked (an import question was open): its edit no longer applies. */
        STALE
    }

    /** See {@link LspOrganizeImports#organizeImportsAny}. */
    public void organizeImportsAny(Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        imports.organizeImportsAny(file, lastLine, lastChar, cb);
    }

    /** See {@link LspOrganizeImports#organizeImportsQuietly}. */
    public void organizeImportsQuietly(Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        imports.organizeImportsQuietly(file, lastLine, lastChar, cb);
    }

    /** See {@link LspOrganizeImports#organizeImports}. */
    public void organizeImports(Path file, int lastLine, int lastChar, Consumer<Boolean> cb) {
        imports.organizeImports(file, lastLine, lastChar, cb);
    }

    /** See {@link JdtlsCommands#fullyQualifiedName}. */
    public void fullyQualifiedName(Path file, int line, int character, Consumer<String> cb) {
        jdtls.fullyQualifiedName(file, line, character, cb);
    }

    /** See {@link JdtlsCommands#reloadProjectConfiguration}. */
    public boolean reloadProjectConfiguration(Path file) {
        return jdtls.reloadProjectConfiguration(file);
    }

    /**
     * The text {@code anchorFile}'s server holds for {@code file}, open in any window sharing that server; null when
     * it is not open there. Its outline positions follow this text, not the file on disk (twelfth review BM3).
     */
    public @Nullable String sentText(Path anchorFile, Path file) {
        LanguageServerSession s = sessionFor(anchorFile);
        return s == null ? null : s.documentSnapshot(uri(file));
    }

    /** See {@link JdtlsCommands#classFileContents}. */
    public void classFileContents(Path anchorFile, @Nullable String jdtUri, Consumer<String> cb) {
        jdtls.classFileContents(anchorFile, jdtUri, cb);
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
     * Binds the XML document {@code file} to {@code grammar} with lemminx's {@link XmlServer#ASSOCIATE_GRAMMAR}
     * ({@code bindingType} one of {@link XmlServer#bindingTypes}) and applies the edit it answers — the
     * {@code xsi:noNamespaceSchemaLocation}, DOCTYPE or {@code xml-model} lemminx writes, with a path relative to
     * the document (XML review D6). {@code cb} hears on the FX thread whether the edit landed.
     */
    public void xmlAssociateGrammar(Path file, Path grammar, String bindingType, Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || !XmlServerSettings.isLemminx(s.serverId())) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        List<Object> args =
                List.of(Map.of("uri", s.uris().toWire(uri(file))), s.uris().toWire(uri(grammar)), bindingType);
        s.executeCommand(XmlServer.ASSOCIATE_GRAMMAR, args).whenComplete((result, error) -> {
            org.eclipse.lsp4j.WorkspaceEdit edit = error == null ? XmlServer.associateEdit(result) : null;
            if (error != null) {
                codeActions.lastCodeActionFailure = failureReason(error);
            }
            Platform.runLater(() -> {
                if (edit == null) {
                    cb.accept(false);
                } else {
                    codeActions.applyWorkspaceEdit(edit, WorkspaceEditDrafts.versionedBasis(s, edit), cb);
                }
            });
        });
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
