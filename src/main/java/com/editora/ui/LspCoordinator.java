package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import com.editora.editor.MarkdownRenderer;
import com.editora.lsp.InlayHintFilter;
import com.editora.lsp.JdtlsGenerate;
import com.editora.lsp.JdtlsRefactor;
import com.editora.lsp.LspManager;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * The whole Language Server Protocol integration, extracted from {@link MainController} via the
 * {@link CoordinatorHost} pattern. Owns:
 * <ul>
 *   <li>the on-demand <em>navigation/format</em> flows — go-to-definition, find-references, hover, and
 *       document formatting (the {@code lsp.*} commands + editor right-click items) plus the hover popup;
 *   <li>the <em>diagnostics routing</em> — the per-open-file {@code problems} map, the {@link ProblemsPanel},
 *       and the {@code publishDiagnostics} callback ({@link #onDiagnostics});
 *   <li>the <em>configure/detect/gating + per-buffer lifecycle</em> — {@link #applySupport} (mirrors
 *       {@link MermaidCoordinator}), per-server detection ({@code serverAvailable}/{@code SERVER_IDS}), the
 *       per-server enable/command switches, {@link #syncBuffer} (open/activate/close), the status-bar
 *       {@code LSP:} segment ({@link #updateStatusBar}), the Structure outline ({@link #requestStructureSymbols}),
 *       semantic tokens, the server-ready callback ({@link #onServerStatus}), the {@link #wireBuffer} hook set,
 *       and the {@code lsp.toggleServer}/{@code lsp.setServerCommand} pickers.
 * </ul>
 *
 * <p>The {@link LspManager} is <em>not</em> owned here: it stays constructed in {@code MainController} because
 * the Debug (DAP) integration layers on the same jdtls session and the MCP bridge reads its diagnostics, so
 * the manager must remain reachable from both. The coordinator takes it as a constructor argument. The
 * {@code lspManager}'s {@code publishDiagnostics}/{@code status} callbacks route through thin
 * {@code MainController.onLspDiagnostics}/{@code onLspServerStatus} method-ref delegates (to dodge an illegal
 * forward reference at the manager's field initializer). {@code MainController} also keeps the {@code lspEnabled}
 * predicate (used widely by Run/Debug gating), the {@code ifLsp}/{@code toggleLsp} command glue, the Problems
 * {@code ToolWindow} (built with {@link #problemsPanel()}), and {@code canonicalPath}/{@code tabForPath}.
 */
final class LspCoordinator {

    private long navigationRequestGeneration;

    /** Window hooks beyond {@link CoordinatorHost} that the LSP flows need. */
    interface Ops {
        /** Routes popup controls through the same registered commands as the palette/keymap. */
        default void executeCommand(String id) {}

        /** Opens {@code file} (if needed) and moves the caret to a 0-based LSP line/column. */
        void openAndGoto(Path file, int line0, int col0);

        /** A path with the home directory shown as {@code ~}, for preview labels. */
        String homeCollapsed(String absolutePath);

        /** Opens (selected) a read-only in-memory buffer — no path, {@code language} highlighting — used for
         *  a {@code jdt://} class-file's fetched source (#665). Returns the buffer (null if refused). */
        EditorBuffer openReadOnlyDoc(String title, String content, String language);

        /** Re-selects the tab holding {@code buffer}; false when that tab has been closed. */
        boolean selectBufferTab(EditorBuffer buffer);

        /** Whether the active buffer is editable (formatting is a no-op on a read-only/huge buffer). */
        boolean activeEditable();

        /** Whether the LSP feature is effectively on (off in Simple UI mode); diagnostics are dropped when off. */
        boolean lspFeatureEnabled();

        /** Shows/hides the status-bar indeterminate loading bar (a server is starting). */
        void setLspLoading(boolean loading);

        /** The open buffer for {@code file} (canonical-tab match), or {@code null} when no tab holds it. */
        EditorBuffer bufferForPath(Path file);

        /** Every live buffer at {@code path}, or below it for a directory, across application windows. */
        default java.util.List<EditorBuffer> buffersAtOrUnder(Path path) {
            EditorBuffer exact = bufferForPath(path);
            return exact == null ? java.util.List.of() : java.util.List.of(exact);
        }

        /** Opens {@code file} in a background (non-selected) tab and returns its buffer — how a multi-file
         *  quick fix touches a file with no open tab (#670). Null when it can't be opened. */
        EditorBuffer openBackgroundBuffer(Path file);

        default void openBackgroundBufferAsync(Path file, java.util.function.Consumer<EditorBuffer> done) {
            done.accept(openBackgroundBuffer(file));
        }

        /** A workspace edit renamed a file on disk (#676) — remap the open buffer/tab + per-file session
         *  state (the project-tree rename hook). */
        void fileRenamed(Path from, Path to);

        /** A workspace edit created/deleted a path on disk; refresh or close matching UI state. */
        void fileCreated(Path file);

        void fileDeleted(Path file);

        /** Cancels a save captured before an LSP resource mutation can move, replace, or delete its path. */
        default void invalidatePendingWrite(Path file) {}

        /**
         * Keeps {@code file}'s current content in Local History before a workspace edit deletes or replaces
         * it, answering (on the FX thread) whether the edit may go on: false only when history is on and
         * the copy could not be made durable.
         */
        default void captureBeforeDestruction(Path file, java.util.function.Consumer<Boolean> completion) {
            completion.accept(true);
        }

        /** Sets (or clears, when {@code null}) the status-bar {@code LSP: <server>} segment label. */
        void setStatusBarLsp(String label);

        /** Shows/hides the Problems tool-window stripe button (active file is server-managed). */
        void setProblemsAvailable(boolean available);

        /** Enables References and Hierarchy for a first-time user once an enabled server is installed. */
        void enableNavigationWindowsByDefault();

        /** Opens (shows + focuses) the References tool window after a multi-result Find References. */
        void openReferencesWindow();

        /** Opens (shows + focuses) the Hierarchy tool window after a call/type-hierarchy prepare (#682). */
        void openHierarchyWindow();

        /**
         * The Structure tool window — where the server's document-symbol outline goes, and what says
         * whether anything is showing an outline at all. Null in a test that has none.
         */
        StructurePanel structurePanel();

        /**
         * The window's configuration, or null in a test that has none. Read for the trusted-folder list
         * (project-supplied server commands run only in a trusted folder) and the config directory.
         */
        default com.editora.config.ConfigManager config() {
            return null;
        }

        /** Base dir for per-project jdtls Eclipse workspaces ({@code <configDir>/jdtls-workspaces}). */
        default Path jdtlsWorkspaceBase() {
            com.editora.config.ConfigManager config = config();
            return config == null ? null : config.getConfigDir().resolve("jdtls-workspaces");
        }

        /** The active project's root for LSP root resolution (null when Projects is off / no project). */
        Path lspProjectRoot();

        /** Shows the Python interpreter status-bar item ({@code text == null} hides it). */
        default void setStatusBarPython(String text, String tooltip) {}

        /** Detection finished updating — re-evaluate the active buffer's install banner. */
        void onDetectionSettled();

        /** A server finished {@code initialize}: its capabilities are known, so re-check whether jdtls itself
         *  provides the java-debug commands (some distributions bundle the plugin — #711). */
        void onServerCapabilitiesReady();

        /**
         * The canonical (symlink-resolved) form of {@code file}, so every consumer of the diagnostics map keys
         * agrees. A server reports diagnostics under whatever URI it chose (some canonicalize, some echo the
         * sent path), while {@code setProblemsActiveFile} is given the canonical active path — canonicalizing
         * the key here is what lets the active-file-first sort (and tab-close clear) actually match.
         */
        Path canonicalize(Path file);
    }

    /** Filesystem boundary for staging and recovering LSP create/rename/delete transactions. */
    interface WorkspaceFileOperations {

        WorkspaceFileOperations SYSTEM = new WorkspaceFileOperations() {
            @Override
            public boolean exists(Path path) {
                return java.nio.file.Files.exists(path);
            }

            @Override
            public boolean isDirectory(Path path) {
                return java.nio.file.Files.isDirectory(path);
            }

            @Override
            public boolean isRegularFile(Path path) {
                return java.nio.file.Files.isRegularFile(path);
            }

            @Override
            public long size(Path path) throws java.io.IOException {
                return java.nio.file.Files.size(path);
            }

            @Override
            public WorkspaceFileIdentity identity(Path path) throws java.io.IOException {
                var attributes =
                        java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                return new WorkspaceFileIdentity(
                        attributes.fileKey(),
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis());
            }

            @Override
            public void createDirectories(Path path) throws java.io.IOException {
                java.nio.file.Files.createDirectories(path);
            }

            @Override
            public void createFile(Path path) throws java.io.IOException {
                java.nio.file.Files.createFile(path);
            }

            @Override
            public void move(Path from, Path to, java.nio.file.CopyOption... options) throws java.io.IOException {
                java.nio.file.Files.move(from, to, options);
            }

            @Override
            public Path createTempFile(Path directory, String prefix, String suffix) throws java.io.IOException {
                return java.nio.file.Files.createTempFile(directory, prefix, suffix);
            }

            @Override
            public boolean deleteIfExists(Path path) throws java.io.IOException {
                return java.nio.file.Files.deleteIfExists(path);
            }

            @Override
            public List<Path> list(Path path) throws java.io.IOException {
                try (var children = java.nio.file.Files.list(path)) {
                    return children.toList();
                }
            }

            @Override
            public List<Path> walk(Path path) throws java.io.IOException {
                try (var descendants = java.nio.file.Files.walk(path)) {
                    return descendants.toList();
                }
            }
        };

        boolean exists(Path path);

        boolean isDirectory(Path path);

        boolean isRegularFile(Path path);

        long size(Path path) throws java.io.IOException;

        WorkspaceFileIdentity identity(Path path) throws java.io.IOException;

        void createDirectories(Path path) throws java.io.IOException;

        void createFile(Path path) throws java.io.IOException;

        void move(Path from, Path to, java.nio.file.CopyOption... options) throws java.io.IOException;

        Path createTempFile(Path directory, String prefix, String suffix) throws java.io.IOException;

        boolean deleteIfExists(Path path) throws java.io.IOException;

        List<Path> list(Path path) throws java.io.IOException;

        List<Path> walk(Path path) throws java.io.IOException;
    }

    record WorkspaceFileIdentity(Object fileKey, long size, long lastModifiedMillis) {}

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final Ops ops;
    private final WorkspaceFileOperations workspaceFiles;
    private final Executor workspaceExecutor;

    /** Diagnostics by file, <b>scoped to open files only</b> (a server publishes project-wide). */
    private final Map<Path, List<LspDiagnostic>> problems = new LinkedHashMap<>();

    /** The Problems / References / Hierarchy panels, each built on its first use (see the accessors). */
    private ProblemsPanel problemsPanel;

    private ReferencesPanel referencesPanel;
    private HierarchyPanel hierarchyPanel;
    private final ProblemsPanel.Actions problemsActions;
    private final HierarchyPanel.Loader hierarchyLoader;
    /** The Problems tree's active file, remembered for a panel not built yet. */
    private Path problemsActiveFile;

    /** The buffer path a hierarchy was started from — routes child expansions to the right session. */
    private Path hierarchyAnchor;
    /** The mode of the currently shown hierarchy (drives which children request a node expansion makes). */
    private HierarchyPanel.Mode hierarchyMode = HierarchyPanel.Mode.CALLS;

    /** serverId → whether that server's command was found on this machine (per-server availability). */
    private final Map<String, Boolean> serverAvailable = new java.util.HashMap<>();

    /** Known LSP server ids (the configure/detect/gating loops iterate these). */
    private static final String[] SERVER_IDS = {
        "java",
        "typescript",
        "python",
        "xml",
        "json",
        "bash",
        "yaml",
        "go",
        "rust",
        "php",
        "ruby",
        "clangd",
        "html",
        "css",
        "kotlin",
        "lua",
        "dockerfile",
        "sql",
        "terraform",
        "toml",
        "csharp",
        "typst",
        "astro",
        com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID
    };

    /** Lines of over-scan above/below the viewport when requesting semantic tokens (small scrolls stay covered). */
    private static final int SEMANTIC_WINDOW_PAD = 200;

    /** The currently-showing LSP hover popup (dismissable), or null. */
    private Popup hoverPopup;

    /** The currently-showing signature-help popup (#674), or null. */
    private Popup signaturePopup;

    private long signatureGeneration;
    private long hoverGeneration;
    private Path pendingSignaturePath;
    private final com.editora.lsp.SignatureSelection signatureSelection = new com.editora.lsp.SignatureSelection();
    private CodeArea signatureArea;
    private long signatureRequestVersion = -1;
    private int signatureRequestCaret = -1;
    private final javafx.animation.PauseTransition signatureCaretDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));

    LspCoordinator(CoordinatorHost host, LspManager lspManager, Ops ops) {
        this(
                host,
                lspManager,
                ops,
                WorkspaceFileOperations.SYSTEM,
                task -> Thread.ofVirtual().name("lsp-workspace-edit").start(task));
    }

    LspCoordinator(
            CoordinatorHost host,
            LspManager lspManager,
            Ops ops,
            WorkspaceFileOperations workspaceFiles,
            Executor workspaceExecutor) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.pythonInterpreters = new PythonInterpreterCoordinator(new PythonInterpreterCoordinator.Host() {
            @Override
            public Path projectRoot() {
                return ops.lspProjectRoot();
            }

            @Override
            public com.editora.config.ConfigManager config() {
                return ops.config();
            }

            @Override
            public void setStatusItem(String text, String tooltip) {
                ops.setStatusBarPython(text, tooltip);
            }

            @Override
            public void interpreterChanged() {
                pythonInterpreterChanged();
            }

            @Override
            public OverlayHost overlayHost() {
                return host.overlayHost();
            }

            @Override
            public javafx.stage.Window window() {
                return host.window();
            }

            @Override
            public void setStatus(String message) {
                host.setStatus(message);
            }
        });
        this.pythonFormat = new PythonFormatCoordinator(new PythonFormatCoordinator.Host() {
            @Override
            public EditorBuffer activeBuffer() {
                return host.activeBuffer();
            }

            @Override
            public boolean activeEditable() {
                return ops.activeEditable();
            }

            @Override
            public boolean serverFormats(Path file) {
                return ops.lspFeatureEnabled() && lspManager.isManaged(file) && lspManager.supportsFormatting(file);
            }

            @Override
            public Path venvOf(Path file) {
                return pythonInterpreters.resolve(file).venv();
            }

            @Override
            public void setStatus(String message) {
                host.setStatus(message);
            }

            @Override
            public void setError(String message) {
                host.setError(message);
            }
        });
        this.workspaceFiles = java.util.Objects.requireNonNull(workspaceFiles, "workspaceFiles");
        this.workspaceExecutor = java.util.Objects.requireNonNull(workspaceExecutor, "workspaceExecutor");
        this.problemsActions = new ProblemsPanel.Actions() {
            @Override
            public void open(java.nio.file.Path file, int line, int col) {
                ops.openAndGoto(file, line, col);
            }

            @Override
            public void openUrl(String url) {
                host.openExternalUrl(url); // through the window's link choke point
            }

            @Override
            public void setProjectWide(boolean projectWide) {
                setProjectWideProblems(projectWide); // #743
            }
        };
        this.hierarchyLoader = new HierarchyPanel.Loader() {
            @Override
            public void children(
                    com.editora.lsp.LspManager.HierarchyNode node,
                    boolean primary,
                    java.util.function.Consumer<List<com.editora.lsp.LspManager.HierarchyNode>> cb) {
                Path anchor = hierarchyAnchor;
                if (anchor == null) {
                    cb.accept(List.of());
                } else if (hierarchyMode == HierarchyPanel.Mode.CALLS) {
                    lspManager.callHierarchyChildren(anchor, node.raw(), primary, cb);
                } else {
                    lspManager.typeHierarchyChildren(anchor, node.raw(), primary, cb);
                }
            }

            @Override
            public void open(Path file, int line, int col) {
                ops.openAndGoto(file, line, col);
            }
        };
        lspManager.setOnSessionCrashed(this::onSessionCrashed);
        lspManager.setOnServerStatus(this::onServerStatus);
        lspManager.setPythonInterpreterResolver(pythonInterpreters::pythonPathForServerRoot); // review B1
        // A server root the Project panel's watcher does not cover gets a watcher of its own (review B7).
        lspManager.setWatchSessionRoots(root -> {
            Path project = watchedProjectRoot;
            return project == null || !root.startsWith(project.toAbsolutePath().normalize());
        });
        lspManager.setFolderTrust(this::folderTrusted);
        lspManager.setOnStartWithheld(this::onStartWithheld);
        lspManager.setOnStartFailed(this::onStartFailed);
        lspManager.setApplyEditHandler(this::applyWorkspaceEditsConfirmed); // server quick-fix edits land here (#670)
        lspManager.setOnEditBlocked(this::editBlocked);
        lspManager.setOnRefreshRequested(this::refreshRequested);
        lspManager.setOpenDocumentDiagnosticsOnly(!projectWideProblems); // the Problems window's default scope
        lspManager.setOnDiagnosticsUnchanged(this::diagnosticsUnchanged);
    }

    /**
     * {@code file}'s diagnostics are known to be what they were before the last edit — the server was sent
     * nothing (the text ended up identical) or answered a pull with "unchanged". Every edit clears the
     * buffer's overlay, scrollbar stripe and minimap marks on the assumption that a publish follows; when
     * none will, the last published list is put back, so the editor does not show a clean file while the
     * Problems window still lists its errors.
     */
    private void diagnosticsUnchanged(Path file) {
        EditorBuffer buffer = bufferForLspPath(file);
        List<LspDiagnostic> last = problems.get(ops.canonicalize(file));
        if (buffer != null && last != null && !last.isEmpty()) {
            buffer.setLspDiagnostics(last);
        }
    }

    /**
     * What servers asked to have re-requested since the last flush: refresh kind → the sessions that asked
     * (the manager's opaque origins, compared by identity). See {@link #refreshRequested}.
     */
    private final Map<String, java.util.Set<Object>> pendingRefreshes = new java.util.LinkedHashMap<>();

    private final javafx.animation.PauseTransition refreshFlush =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(REFRESH_COALESCE_MILLIS));

    /** Window over which a server's {@code workspace/…/refresh} requests are folded into one re-request. */
    static final int REFRESH_COALESCE_MILLIS = 100;

    /** The refresh kind for "this server's capabilities changed, or it has just become ready". */
    static final String CAPABILITIES = "capabilities";

    /**
     * A server asked for its data to be re-requested. Servers send these in bursts (clangd: two right after
     * an open, one per rebuilt dependent after a header save; jdtls and tinymist: one
     * {@code client/registerCapability} per feature after initialize), so the kinds are collected for a
     * short fixed window — the timer is not restarted by a later request, which would let a chatty server
     * starve it. Capability changes take the same route: applied at once, each registration re-requested
     * diagnostics, folding ranges, semantic tokens and inlay hints for every open tab of every server.
     */
    private void refreshRequested(String kind, Object origin) {
        pendingRefreshes
                .computeIfAbsent(kind, k -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))
                .add(origin);
        if (refreshFlush.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            refreshFlush.setOnFinished(e -> flushRefreshes());
            refreshFlush.playFromStart();
        }
    }

    /** Re-requests what was asked for, for the documents of the sessions that asked. */
    void flushRefreshes() {
        Map<String, java.util.Set<Object>> due = new java.util.LinkedHashMap<>(pendingRefreshes);
        pendingRefreshes.clear();
        java.util.Set<Object> capabilityOrigins = due.remove(CAPABILITIES);
        EditorBuffer active = host.activeBuffer();
        boolean[] activeGated = {false};
        host.forEachBuffer(buffer -> {
            Path path = lspPath(buffer);
            if (path == null || !lspManager.isManaged(path)) {
                return;
            }
            if (servedByAny(path, capabilityOrigins)) {
                refreshCapabilityGates(buffer, buffer == active); // covers every other kind for this buffer
                activeGated[0] |= buffer == active;
                return;
            }
            boolean invalidated = false;
            for (var asked : due.entrySet()) {
                String kind = asked.getKey();
                if (!servedByAny(path, asked.getValue())) {
                    continue;
                }
                if (!invalidated) {
                    // The server's answers changed without the document changing: one already on its way
                    // may predate that, so it must not stand in for the request made here.
                    lspManager.invalidateRequests(path);
                    invalidated = true;
                }
                if (!refreshAppliesTo(kind, buffer == active)) {
                    refreshWhenShown.add(buffer);
                    continue;
                }
                switch (kind) {
                    case "diagnostics" -> lspManager.pullDiagnostics(path);
                    case "semanticTokens" -> requestSemanticTokens(buffer);
                    case "inlayHints" -> requestInlayHints(buffer);
                    case "foldingRanges" -> requestFoldingRanges(buffer);
                    default -> {
                        // Future server refresh kinds are ignored until the corresponding UI feature exists.
                    }
                }
            }
        });
        if (capabilityOrigins != null) {
            if (activeGated[0]) {
                requestStructureSymbols(active);
            }
            ops.onServerCapabilitiesReady();
        }
    }

    private boolean servedByAny(Path path, java.util.Set<Object> origins) {
        if (origins != null) {
            for (Object origin : origins) {
                if (lspManager.servedBy(path, origin)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pure: whether a refresh of {@code kind} re-requests for a buffer. Semantic tokens and inlay hints are
     * only ever applied to the active buffer (their replies are dropped for any other, and a tab re-requests
     * when it is shown — {@link #onBufferShown}), so asking for them for every open tab was pure server load.
     */
    static boolean refreshAppliesTo(String kind, boolean activeBuffer) {
        return activeBuffer || !("semanticTokens".equals(kind) || "inlayHints".equals(kind));
    }

    /**
     * Buffers whose semantic tokens or inlay hints went out of date while their tab was hidden: the request
     * was not made (its reply would have been dropped), so it is owed when the tab is next shown.
     */
    private final java.util.Set<EditorBuffer> refreshWhenShown =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Re-applies every managed buffer's gates now — every server, not coalesced (the typing probe's hook). */
    private void refreshCapabilityGates() {
        EditorBuffer active = host.activeBuffer();
        host.forEachBuffer(b -> {
            if (lspPath(b) != null && lspManager.isManaged(lspPath(b))) {
                refreshCapabilityGates(b, b == active);
            }
        });
        requestStructureSymbols(active);
        ops.onServerCapabilitiesReady();
    }

    /**
     * Re-applies one managed buffer's gates after initialize or a dynamic capability change, and re-requests
     * its server data: diagnostics and folding ranges for any tab (both are kept per buffer), semantic
     * tokens and inlay hints only for the tab on screen.
     */
    private void refreshCapabilityGates(EditorBuffer b, boolean active) {
        Path path = lspPath(b);
        b.setLspTriggerChars(lspManager.triggerCharacters(path));
        b.setLspFormatAvailable(lspManager.supportsFormatting(path) || b.isPython()); // Python: Ruff/Black
        b.setLspRangeFormatAvailable(lspManager.supportsRangeFormatting(path));
        b.setLspOnTypeTriggers(lspManager.onTypeTriggerCharacters(path));
        b.setLspCodeActionsAvailable(lspManager.supportsCodeActions(path));
        b.setLspRenameAvailable(lspManager.supportsRename(path));
        // Both navigation gates used to be pushed only by syncBuffer — which runs before initialize
        // answers, when no capability is known — so a server that registers them dynamically (or
        // simply finishes its handshake) never got its menu entries switched on.
        b.setLspImplementationAvailable(lspManager.supportsImplementation(path));
        b.setLspTypeDefinitionAvailable(lspManager.supportsTypeDefinition(path));
        b.setLspSignatureTriggerChars(lspManager.signatureTriggerCharacters(path));
        lspManager.invalidateRequests(path); // what the server answers may have changed with its capabilities
        lspManager.pullDiagnostics(path);
        requestFoldingRanges(b);
        boolean sem = host.settings().isSemanticHighlight() && lspManager.supportsSemanticTokens(path);
        b.setSemanticActive(sem);
        b.setInlayHintsActive(lineAnnotationsOn());
        if (!active) {
            refreshWhenShown.add(b);
            return;
        }
        if (sem) {
            requestSemanticTokens(b);
        }
        requestInlayHints(b);
    }

    /** How many on-their-own session deaths per (server, root) within {@link #CRASH_WINDOW_NANOS} are
     *  auto-restarted before giving up (a crash-looping server must not be re-forked forever). */
    private static final int MAX_AUTO_RESTARTS = 2;

    /** The sliding window over which crashes are counted toward {@link #MAX_AUTO_RESTARTS} (5 min — wide
     *  enough that even a 60 s initialize-timeout loop is caught, narrow enough that a one-off crash an
     *  hour later restarts again). */
    private static final long CRASH_WINDOW_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);

    /** (serverId + root) → recent crash timestamps (nanoTime), pruned to the window on each crash. */
    private final Map<String, java.util.ArrayDeque<Long>> recentCrashes = new java.util.HashMap<>();

    /**
     * A session died on its own — process crash or failed/timed-out handshake, never a deliberate shutdown
     * (already on the FX thread; see {@code LspManager.setOnSessionCrashed}). Before this hook existed a
     * crashed server stayed dead for the rest of the session: {@code syncBuffer} (which would re-open) only
     * ran from settings-applies or {@code onBufferShown}, and a crashed buffer isn't in the deferred set —
     * so tabbing away and back did nothing, didChange silently no-oped, and stale diagnostics lingered
     * (#666). Now: clear the dead session's diagnostics, then route each affected buffer back through
     * {@link #syncBufferWhenShown} — the active buffer restarts the server immediately, background buffers
     * re-enter the deferred set and restart on first show (the same policy as startup). Capped per
     * (server, root) so a crash-looping server isn't re-forked forever.
     */
    private void onSessionCrashed(String serverId, Path root) {
        if (root != null && com.editora.vfs.Vfs.isRemote(root)) {
            // A server on another host also dies when the connection does, and the connection's own
            // listener may hear about it a moment after the server's channel closed: let it settle first.
            afterRemoteCrashSettles.accept(() -> remoteSessionEnded(serverId, root));
            return;
        }
        sessionCrashed(serverId, root);
    }

    /** Runs a task once a remote server's death has had a moment to show up as a lost connection. */
    java.util.function.Consumer<Runnable> afterRemoteCrashSettles = task -> {
        javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
        settle.setOnFinished(e -> task.run());
        settle.play();
    };

    /**
     * A remote server ended. With its host still reachable that is a crash like any other. With the
     * connection gone it is not: restarting would only fail against a dead connection (and count towards the
     * crash-loop cap), so its buffers just go quiet until {@link #remoteHostConnected} brings them back.
     */
    private void remoteSessionEnded(String serverId, Path root) {
        if (RemoteExec.runsFor(root)) {
            sessionCrashed(serverId, root);
            return;
        }
        forEachCrashedBuffer(serverId, root, b -> {
            b.setLspActive(false);
            clearDiagnostics(lspPath(b));
        });
        updateStatusBar();
    }

    /**
     * The window is connected to {@code authority} again (or for the first time): its tabs now hold paths on
     * the new connection, so each one is offered to its server afresh — the active one at once, the others
     * when shown — and the crashes counted against the lost connection's servers are forgotten.
     */
    void remoteHostConnected(String authority) {
        if (authority == null) {
            return;
        }
        String onHost = "sftp://" + authority + "/";
        recentCrashes.keySet().removeIf(key -> key.contains(onHost));
        resyncRemoteBuffers(authority);
    }

    /**
     * Whether commands may run on remote hosts has changed (the setting, or a host's consent): remote tabs
     * are offered to their servers, or closed on them, accordingly.
     */
    void remoteExecChanged() {
        // A folder's tools may now run elsewhere (a host allowed or withdrawn, a folder bound to a container or
        // released): a server started on the old side is stopped, and every tab re-decides — not only remote
        // ones, since a bound container serves local files.
        lspManager.shutdownSessionsOnAnotherTarget();
        host.forEachBuffer(b -> {
            if (lspPath(b) != null) {
                syncBufferWhenShown(b);
            }
        });
        updateStatusBar();
    }

    /** Re-decides every remote tab on {@code authority} (any host when null): the active one now, others on show. */
    private void resyncRemoteBuffers(String authority) {
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p != null
                    && com.editora.vfs.Vfs.isRemote(p)
                    && (authority == null || authority.equals(com.editora.vfs.Vfs.authorityOf(p)))) {
                syncBufferWhenShown(b);
            }
        });
        updateStatusBar();
    }

    private void sessionCrashed(String serverId, Path root) {
        if (!ops.lspFeatureEnabled() || !serverEnabled(serverId)) {
            return;
        }
        boolean restart = shouldAutoRestart(crashKey(serverId, root), System.nanoTime());
        host.setStatus(tr(restart ? "status.lsp.crashed" : "status.lsp.crashLoop", serverLabel(serverId)));
        forEachCrashedBuffer(serverId, root, b -> {
            Path p = lspPath(b);
            b.setLspActive(false); // drop the dead session's squiggles/stripes immediately
            clearDiagnostics(p); // …and its stale Problems entries (nothing will ever re-publish them)
            if (restart) {
                syncBufferWhenShown(b); // active → re-open (forks a fresh server) now; background → on show
            }
        });
        updateStatusBar();
    }

    /** The crash-history key of one server on one root; a remote root by its URI, which names its host. */
    private static String crashKey(String serverId, Path root) {
        return serverId + " " + (root == null ? "" : com.editora.vfs.Vfs.toStorableString(root));
    }

    /** The open buffers the session of {@code serverId} on {@code root} was serving, now unmanaged. */
    private void forEachCrashedBuffer(String serverId, Path root, java.util.function.Consumer<EditorBuffer> action) {
        if (!ops.lspFeatureEnabled() || !serverEnabled(serverId)) {
            return;
        }
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p == null || !serverId.equals(serverIdForBuffer(b))) {
                return;
            }
            if (!sameLspRoot(lspRootFor(b, serverId), root)) {
                return; // a crash is scoped to one project root; another root's buffer is unrelated
            }
            if (lspManager.isManaged(p)) {
                return;
            }
            action.accept(b);
        });
    }

    /** The per-folder trust record (inherited by subfolders) that also gates project LSP commands. */
    private boolean folderTrusted(Path folder) {
        com.editora.config.ConfigManager config = ops.config();
        return config != null && config.getTrustStore().isTrusted(folder);
    }

    /**
     * A server was deliberately not started because it could only run with code from an untrusted folder
     * (astro-ls and the folder's own TypeScript SDK). Unlike a crash nothing is retried: the buffers go
     * inactive and the status line says why and names the command that trusts the folder.
     */
    private void onStartWithheld(String serverId, Path root) {
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p != null
                    && serverId.equals(serverIdForBuffer(b))
                    && sameLspRoot(lspRootFor(b, serverId), root)
                    && !lspManager.isManaged(p)) {
                b.setLspActive(false);
            }
        });
        host.setStatus(tr("status.lsp.astroSdkUntrusted", tr("command.lsp.trustProjectSettings")));
        updateStatusBar();
    }

    /**
     * A server's start failed: its command could not be run, or a remote host's connection broke while it
     * was being started. The buffers it was opened for go inactive and the loading bar stops; the session's
     * own error status already said why. Not a crash, so nothing is retried here — the same command would
     * only fail again, and a remote tab comes back through {@link #remoteHostConnected}.
     */
    private void onStartFailed(String serverId, Path root) {
        forEachCrashedBuffer(serverId, root, b -> b.setLspActive(false));
        ops.setLspLoading(false);
        updateStatusBar();
    }

    /** Whether two roots identify the same LSP session boundary. Kept pure for crash-routing coverage. */
    static boolean sameLspRoot(Path bufferRoot, Path crashedRoot) {
        return bufferRoot != null
                && crashedRoot != null
                && bufferRoot
                        .toAbsolutePath()
                        .normalize()
                        .equals(crashedRoot.toAbsolutePath().normalize());
    }

    /** Records a crash of {@code key} at {@code nowNanos} and decides whether to auto-restart: true while the
     *  window holds at most {@link #MAX_AUTO_RESTARTS} crashes, false once the server is crash-looping. */
    private boolean shouldAutoRestart(String key, long nowNanos) {
        return recordCrashAndDecide(
                recentCrashes.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()),
                nowNanos,
                CRASH_WINDOW_NANOS,
                MAX_AUTO_RESTARTS);
    }

    /** Pure sliding-window decision behind {@link #shouldAutoRestart}: prunes {@code times} to the window,
     *  records {@code nowNanos}, and allows the restart while the window holds ≤ {@code maxRestarts} crashes. */
    static boolean recordCrashAndDecide(
            java.util.ArrayDeque<Long> times, long nowNanos, long windowNanos, int maxRestarts) {
        while (!times.isEmpty() && nowNanos - times.peekFirst() > windowNanos) {
            times.removeFirst();
        }
        times.addLast(nowNanos);
        return times.size() <= maxRestarts;
    }

    /**
     * The Problems tool-window content (the {@code ToolWindow} itself stays in {@code MainController}), built
     * on the window's first open. Diagnostics that arrived before then are in {@link #problems}; the build
     * shows them.
     */
    ProblemsPanel problemsPanel() {
        if (problemsPanel == null) {
            problemsPanel = new ProblemsPanel(problemsActions);
            problemsPanel.setProjectWide(projectWideProblems);
            problemsPanel.setActiveFile(problemsActiveFile);
            problemsPanel.setProblems(problems);
        }
        return problemsPanel;
    }

    /** The References tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    ReferencesPanel referencesPanel() {
        if (referencesPanel == null) {
            referencesPanel = new ReferencesPanel(ops::openAndGoto);
        }
        return referencesPanel;
    }

    /** The Hierarchy tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    HierarchyPanel hierarchyPanel() {
        if (hierarchyPanel == null) {
            hierarchyPanel = new HierarchyPanel(hierarchyLoader);
        }
        return hierarchyPanel;
    }

    /**
     * Call hierarchy at the caret (#682): who calls this method (Callers, the default) / what it calls
     * (Callees), expanded lazily one server request per node into the Hierarchy tool window.
     */
    void callHierarchy() {
        showHierarchy(HierarchyPanel.Mode.CALLS);
    }

    /** Type hierarchy at the caret (#682): the type's supertypes (default) / subtypes. */
    void typeHierarchy() {
        showHierarchy(HierarchyPanel.Mode.TYPES);
    }

    private void showHierarchy(HierarchyPanel.Mode mode) {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lspPath(b);
        boolean supported = mode == HierarchyPanel.Mode.CALLS
                ? lspManager.supportsCallHierarchy(path)
                : lspManager.supportsTypeHierarchy(path);
        if (!supported) {
            host.setStatus(tr("status.lsp.noHierarchy"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        java.util.function.Consumer<List<com.editora.lsp.LspManager.HierarchyNode>> onRoots = roots -> {
            if (!navigationRequestCurrent(b, path, version, generation)) {
                return;
            }
            if (roots.isEmpty()) {
                host.setStatus(tr("status.lsp.noHierarchy"));
                return;
            }
            hierarchyAnchor = path;
            hierarchyMode = mode;
            hierarchyPanel().setRoots(mode, roots);
            ops.openHierarchyWindow();
        };
        if (mode == HierarchyPanel.Mode.CALLS) {
            lspManager.prepareCallHierarchy(path, area.getCurrentParagraph(), area.getCaretColumn(), onRoots);
        } else {
            lspManager.prepareTypeHierarchy(path, area.getCurrentParagraph(), area.getCaretColumn(), onRoots);
        }
    }

    /** Live diagnostics map for the MCP bridge's {@code getDiagnostics} (read on the FX thread). */
    Map<Path, List<LspDiagnostic>> problems() {
        return problems;
    }

    /** Sorts the Problems tree so the active file's group is on top (pass the canonical path; on tab switch). */
    void setProblemsActiveFile(Path canonicalActive) {
        problemsActiveFile = canonicalActive;
        if (problemsPanel != null) {
            problemsPanel.setActiveFile(canonicalActive);
        }
    }

    /** Diagnostics callback from the manager (already on the FX thread): store + paint + refresh Problems. */
    void onDiagnostics(Path file, List<LspDiagnostic> diagnostics) {
        if (!ops.lspFeatureEnabled()) {
            return;
        }
        ops.setLspLoading(false); // diagnostics flowing ⇒ the server is up; stop the loading bar
        if (file != null && libraryOrigins.containsKey(file.toAbsolutePath().normalize())) {
            // A library file read through the project's server: its "errors" (the stdlib type-checked under the
            // project's rules) are not the user's problems — VS Code shows none for them either (review B4).
            diagnostics = List.of();
        }
        // A language server publishes diagnostics project-wide (jdtls especially), but we only surface
        // problems for files actually OPEN in Editora — otherwise the Problems window fills with whole-
        // workspace noise from a single open file.
        EditorBuffer buffer = bufferForLspPath(file);
        if (buffer != null) {
            buffer.setLspDiagnostics(diagnostics);
        }
        // Key the map by the canonical path so it agrees with setProblemsActiveFile (given the canonical
        // active path) and clearDiagnostics (given the buffer's path) — the server may report a symlink URI.
        Path key = ops.canonicalize(file);
        // Scope (#743): open-files-only by default — a server publishes project-wide, and one open file
        // would otherwise fill the window with whole-workspace noise. Project scope keeps everything, which
        // is the only way a workspace build's results are worth anything: with jdtls autobuild off, an error
        // in a file you never opened is invisible until you open it.
        // Faded/struck tags (unused, deprecated) are drawn, not listed: one file's unused names would otherwise
        // outnumber its real problems (review B3).
        List<LspDiagnostic> listed = diagnostics.stream().anyMatch(LspDiagnostic::tagOnlyHint)
                ? diagnostics.stream().filter(d -> !d.tagOnlyHint()).toList()
                : diagnostics;
        if (isUntitledLspPath(file)) {
            listed = List.of(); // an untitled buffer has no file a Problems row could open
        }
        boolean keep = projectWideProblems ? !listed.isEmpty() : buffer != null && !listed.isEmpty();
        if (keep) {
            problems.put(key, listed);
        } else {
            problems.remove(key);
        }
        refreshProblems();
    }

    /** Whether the Problems window shows the whole project or only open files (#743). Session state: it
     *  follows an explicit action (a workspace build, or the panel's selector), never a tab switch. */
    private boolean projectWideProblems;

    /** Switches the Problems window's scope and re-renders (#743). Narrowing drops the closed files' entries
     *  so the window matches what it says it is showing; a later build repopulates them. */
    void setProjectWideProblems(boolean projectWide) {
        if (projectWideProblems == projectWide) {
            return;
        }
        projectWideProblems = projectWide;
        lspManager.setOpenDocumentDiagnosticsOnly(!projectWide);
        if (!projectWide) {
            problems.keySet().removeIf(p -> bufferForLspPath(p) == null);
        }
        if (problemsPanel != null) {
            problemsPanel.setProjectWide(projectWide);
        }
        refreshProblems();
    }

    boolean isProjectWideProblems() {
        return projectWideProblems;
    }

    /**
     * Rebuilds the Java project and republishes its diagnostics ({@code java.project.refreshDiagnostics},
     * #743) — the manual trigger that exists because Editora runs jdtls with {@code autobuild} disabled, so
     * nothing ever recomputes diagnostics for files that aren't open.
     *
     * <p>Switches the Problems window to project scope first: without that the results are computed and then
     * immediately discarded by the open-files filter, which is the whole reason this issue existed.
     */
    void buildWorkspace() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        if (path == null || !lspManager.isManaged(path)) {
            host.setStatus(tr("status.lsp.unavailable"));
            return;
        }
        setProjectWideProblems(true);
        ops.setLspLoading(true);
        host.setStatus(tr("status.lsp.buildingWorkspace"));
        lspManager.refreshProjectDiagnostics(path, ok -> {
            ops.setLspLoading(false);
            host.setStatus(tr(ok ? "status.lsp.buildWorkspaceDone" : "status.lsp.buildWorkspaceFailed"));
        });
    }

    /** Drops {@code file}'s diagnostics (a tab closed / its LSP session ended) + refreshes the panel. */
    void clearDiagnostics(Path file) {
        problems.remove(ops.canonicalize(file));
        refreshProblems();
    }

    /** Clears every file's diagnostics (LSP disabled / servers restarted) + refreshes the panel. */
    void clearAllDiagnostics() {
        problems.clear();
        refreshProblems();
    }

    /** Shortest time between two Problems rebuilds while diagnostics keep arriving. */
    static final int PROBLEMS_REFRESH_MILLIS = 100;

    /** Paces Problems rebuilds; the action is deferred to the end of the FX queue (see below). */
    private final FxThrottle problemsRefresh = new FxThrottle(PROBLEMS_REFRESH_MILLIS, true, this::showProblems);

    private void showProblems() {
        if (problemsPanel != null) {
            problemsPanel.setProblems(problems); // an unbuilt panel shows the map when it is built
        }
    }

    /** Runs a Problems rebuild that is waiting for its turn now — a test's stand-in for the pacing delay. */
    void flushProblemsRefresh() {
        problemsRefresh.flush();
    }

    /**
     * Queues one Problems rebuild for the current burst of changes.
     *
     * <p>Every {@code publishDiagnostics} used to rebuild the whole tree, and jdtls publishes once per file
     * on a project import — hundreds of full rebuilds back to back on the FX thread. Deferring the rebuild
     * to the end of the queue lets every publish already waiting there land first, so a burst costs one
     * rebuild; the panel then skips it altogether when the content is what it already shows.
     *
     * <p>That alone is one rebuild per publish whenever the FX thread keeps up with the server, which is
     * the usual case: publishes arrive milliseconds apart, each finds the queue empty. So rebuilds are also
     * paced — the first of a burst is immediate, the rest share one every {@link #PROBLEMS_REFRESH_MILLIS}.
     */
    private void refreshProblems() {
        problemsRefresh.request();
    }

    // --- gating + lifecycle (the configure/detect/per-buffer-sync machine) ----------------------------

    /** Whether {@code serverId}'s command was found on this machine (read by the DAP debug gating for java). */
    boolean isServerAvailable(String serverId) {
        return Boolean.TRUE.equals(serverAvailable.get(serverId));
    }

    /**
     * Whether {@code serverId} was <em>probed and found absent</em> — distinct from "not probed yet". The
     * install banner uses this (not {@code !isServerAvailable}) so a server isn't reported missing during the
     * startup detection window, when the map has no entry yet.
     */
    boolean isServerMissing(String serverId) {
        return serverAvailable.containsKey(serverId) && Boolean.FALSE.equals(serverAvailable.get(serverId));
    }

    /** Whether a live LSP session is currently serving {@code path} (⇒ its server is demonstrably present). */
    boolean isManaged(java.nio.file.Path path) {
        return path != null && lspManager.isManaged(path);
    }

    /**
     * Reconciles the LSP feature with its setting (mirrors {@link MermaidCoordinator}). Configures the
     * manager + Problems window, then (when enabled) detects each server and gates per-buffer LSP. Runs at
     * init and on every settings apply.
     */
    void applySupport() {
        boolean on = ops.lspFeatureEnabled(); // effective: off in Simple UI mode
        // Give jdtls a per-project Eclipse workspace under the config dir (it otherwise shares one default
        // workspace and deadlocks on its .lock — the server then never finishes initialize / completion).
        lspManager.setJdtlsWorkspaceBase(ops.jdtlsWorkspaceBase());
        applyOnTypeFormatting();
        // The commands that actually launch: the global ones, with this project's overrides laid over them
        // when (and only when) its folder is trusted. Building this from the global settings alone meant a
        // committed override was shown in the status bar and Doctor but never run.
        lspManager.configure(on, effectiveCommands());
        applyBashIde();
        appliedProjectRoot = ops.lspProjectRoot();
        appliedProjectOverrides = projectOverrideSignature();
        offerInterruptedEdits();
        if (on) {
            noticeWithheldOverrides();
        }
        updateProblemsAvailability();
        if (!on) {
            serverAvailable.clear();
            clearAllDiagnostics();
            host.forEachBuffer(b -> b.setLspActive(false));
            ops.setLspLoading(false);
            updateStatusBar();
            return;
        }
        for (String serverId : SERVER_IDS) {
            // Stop any server whose per-server toggle is off (frees its process); buffers deactivate below.
            if (!serverEnabled(serverId)) {
                // Clear this server's buffers' diagnostics BEFORE shutting it down. After shutdown
                // isManaged() is false, so syncBuffer's else-branch clear (guarded on isManaged) is skipped —
                // and with no server left to re-publish an empty list, the Problems window would strand this
                // server's diagnostics forever (#469).
                clearDiagnosticsForServer(serverId);
                lspManager.shutdownServer(serverId);
            }
            // Probe each known server independently (one may be installed and another not).
            lspManager.detect(serverId, ok -> serverDetected(serverId, ok));
        }
    }

    /**
     * One server's probe result: recorded, then the buffers it can change are re-gated. Not every open
     * buffer — each of the {@link #SERVER_IDS} answers on its own, so a buffer whose server had just failed to
     * start was re-opened, and its start failed again, once for every other server's answer.
     */
    void serverDetected(String serverId, boolean ok) {
        serverAvailable.put(serverId, ok);
        if (ok && serverEnabled(serverId)) {
            // Installation is enough to seed the preference. The active-buffer managed gate still
            // controls current availability, so neither stripe appears on an ineligible tab.
            ops.enableNavigationWindowsByDefault();
        }
        boolean pomServer = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId);
        host.forEachBuffer(b -> {
            // A pom.xml waits for the Maven server's answer before choosing between it and the XML server.
            if (serverId.equals(serverIdForBuffer(b)) || (pomServer && isPomBuffer(b))) {
                syncBufferWhenShown(b);
            }
        });
        updateStatusBar();
        ops.onDetectionSettled(); // re-evaluate the install banner now that detection has updated
    }

    private static boolean isPomBuffer(EditorBuffer b) {
        Path p = b.getPath();
        return p != null
                && p.getFileName() != null
                && com.editora.lsp.LspServerRegistry.isPomFile(p.getFileName().toString());
    }

    /**
     * Every known server's configured command, keyed by server id — what {@link LspManager#configure} needs.
     *
     * <p><b>Derived from {@link #SERVER_IDS}, never hand-listed.</b> This used to be a literal
     * {@code Map.ofEntries(...)} of 22 entries against a 23-entry {@code SERVER_IDS}, and the one it omitted
     * was {@code maven-pom} — whose registry default command is deliberately blank (it is only known after
     * install). So {@code commandFor} returned an empty argv, {@code available()} said false, and the
     * Maven-aware {@code pom.xml} server could <em>never</em> start, while the Settings row, the in-app
     * installer and Doctor (which all read {@code Settings} directly) reported it present and configured
     * (#723). Building the map from the same array the detect/gating loops walk makes that drift
     * unrepresentable; {@code LspCoordinatorServerIdsTest} pins it.
     *
     * <p>A {@code HashMap} rather than {@code Map.ofEntries} on purpose: it tolerates a null command from a
     * config where the field was explicitly nulled, which {@code Map.ofEntries} would turn into an NPE
     * escaping {@code applySupport} — i.e. LSP silently dead for the session.
     */
    static Map<String, String> commandsForAllServers(com.editora.config.Settings s) {
        Map<String, String> commands = new java.util.HashMap<>();
        for (String serverId : SERVER_IDS) {
            commands.put(serverId, serverCommand(s, serverId));
        }
        return commands;
    }

    /** Every server's effective launch command: {@link #commandsForAllServers} with the project overrides
     *  that this window's trust allows ({@link #serverCommand(String)}) laid over it. */
    Map<String, String> effectiveCommands() {
        Map<String, String> commands = commandsForAllServers(host.settings());
        for (String serverId : SERVER_IDS) {
            commands.put(serverId, serverCommand(serverId));
        }
        return commands;
    }

    /** Pushes {@code Settings.lspOnTypeFormatting} to the manager, which jdtls needs to register the provider. */
    void applyOnTypeFormatting() {
        lspManager.setJavaOnTypeFormatting(host.settings().isLspOnTypeFormatting());
    }

    /** Clears the diagnostics (Problems entry + editor overlay) of every open buffer served by {@code serverId}
     *  and closes its document — used when that server is being disabled, before it is shut down. */
    private void clearDiagnosticsForServer(String serverId) {
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p == null) {
                return;
            }
            // Prefer the server actually managing the buffer (correct for a filename-routed pom.xml). But when no
            // live session manages it — the disable path shuts the session down, and diagnostics can exist without
            // a running server — fall back to the buffer's statically-resolved server id, so a disabled server's
            // diagnostics still get cleared (#469, regressed by #564's managedServerId-only match).
            String managing = lspManager.managedServerId(p);
            String resolved = managing != null ? managing : serverIdForBuffer(b);
            if (!serverId.equals(resolved)) {
                return;
            }
            b.setLspActive(false); // drop the editor squiggle overlay/stripes immediately
            if (lspManager.isManaged(p)) {
                lspManager.closeDocument(p);
            }
            clearDiagnostics(p); // remove from the Problems map (canonical key) + refresh the panel
        });
    }

    /**
     * TEST SEAM — records a server's probed availability directly, standing in for the async
     * {@code LspManager.detect} callback. Without it a coordinator test's gating would depend on whatever
     * language servers happen to be installed on the machine running it.
     */
    void setServerAvailableForTest(String serverId, boolean available) {
        serverAvailable.put(serverId, available);
    }

    /** Applies the detection-dependent gate to every open buffer (per the file's own server). */
    void applyGating() {
        host.forEachBuffer(this::syncBufferWhenShown);
        updateStatusBar();
        ops.onDetectionSettled(); // re-evaluate the install banner now that detection has updated
    }

    /**
     * Buffers whose language server hasn't been started because their tab has never been shown. Weakly keyed
     * so a closed buffer drops out on its own.
     */
    private final java.util.Set<EditorBuffer> deferredLsp =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Starts a buffer's language server only once its tab is actually shown.
     *
     * <p>{@link #wireBuffer} runs for <em>every</em> restored tab, so a session of N files used to fork up to
     * N servers during launch — measured on an 8-file session: 4 extra server processes (including jdtls, a
     * second JVM plus its Eclipse indexing), ~2× the CPU Editora itself burns while starting, and ~225 MB,
     * all for files the user hadn't looked at. Deferring costs nothing for the file being opened (it's the
     * active buffer, so it syncs immediately) and moves the rest to the first tab switch.
     *
     * <p>The trade-off is deliberate: a background file's diagnostics appear when you first visit it rather
     * than at startup. Everything else — the disable/close path, capability pushes, gating — is unchanged,
     * because a deferred buffer simply runs the same {@link #syncBuffer} later.
     */
    void syncBufferWhenShown(EditorBuffer buffer) {
        if (buffer == host.activeBuffer()) {
            deferredLsp.remove(buffer);
            syncBuffer(buffer);
        } else {
            deferredLsp.add(buffer);
        }
    }

    /** Called when a tab becomes visible: starts the server we deferred at wire time, if any. */
    void onBufferShown(EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        boolean owed = refreshWhenShown.remove(buffer);
        if (deferredLsp.remove(buffer)) {
            syncBuffer(buffer); // requests everything itself
        } else if (owed) {
            requestSemanticTokens(buffer);
            requestInlayHints(buffer);
        }
    }

    /**
     * Opens {@code file} on its language server <em>now</em> if the show-deferral has been holding it back.
     *
     * <p>Needed because "open in a tab" and "open on a language server" are not the same thing. A Java run or
     * debug launch routes its {@code resolveClasspath} through <b>any</b> open Java file
     * ({@code RunConfigRouting.pick}) — which may well be a background tab, and a background tab is exactly
     * what {@link #syncBufferWhenShown} defers. Worse, {@link #applyGating} re-defers every non-active buffer,
     * and that runs on every LSP settings apply and after {@link #restartServers}: a file that <em>was</em>
     * managed silently stops being so while its tab stays open. The launch then failed with
     * "no language server for file" while jdtls sat there running perfectly.
     *
     * <p>Synchronous for the caller's purposes: {@code openDocument} caches the session before returning, so
     * {@code sessionFor} answers immediately even though {@code initialize} is still in flight (the
     * {@code didOpen} queues behind it).
     */
    void ensureManaged(java.nio.file.Path file) {
        if (file == null || lspManager.isManaged(file)) {
            return;
        }
        java.nio.file.Path key = ops.canonicalize(file);
        host.forEachBuffer(b -> {
            if (lspPath(b) != null && key.equals(ops.canonicalize(lspPath(b))) && deferredLsp.remove(b)) {
                syncBuffer(b);
            }
        });
    }

    /** Whether a server's own enable toggle is on (under the global LSP enable). */
    boolean serverEnabled(String serverId) {
        return projectSettings().enabledFor(serverId, globalServerEnabled(serverId), projectTrusted());
    }

    /** The global (non-project) enable for {@code serverId}. */
    private boolean globalServerEnabled(String serverId) {
        var s = host.settings();
        return switch (serverId) {
            case "typescript" -> s.isTypescriptLspEnabled();
            case "python" -> s.isPythonLspEnabled();
            case "xml" -> s.isXmlLspEnabled();
            case "json" -> s.isJsonLspEnabled();
            case "bash" -> s.isBashLspEnabled();
            case "yaml" -> s.isYamlLspEnabled();
            case "go" -> s.isGoLspEnabled();
            case "rust" -> s.isRustLspEnabled();
            case "php" -> s.isPhpLspEnabled();
            case "ruby" -> s.isRubyLspEnabled();
            case "clangd" -> s.isClangdLspEnabled();
            case "html" -> s.isHtmlLspEnabled();
            case "css" -> s.isCssLspEnabled();
            case "kotlin" -> s.isKotlinLspEnabled();
            case "lua" -> s.isLuaLspEnabled();
            case "dockerfile" -> s.isDockerfileLspEnabled();
            case "sql" -> s.isSqlLspEnabled();
            case "terraform" -> s.isTerraformLspEnabled();
            case "toml" -> s.isTomlLspEnabled();
            case "csharp" -> s.isCsharpLspEnabled();
            case "typst" -> s.isTypstLspEnabled();
            case "astro" -> s.isAstroLspEnabled();
            case "maven-pom" -> s.isMavenPomLspEnabled();
            default -> s.isJavaLspEnabled();
        };
    }

    /** The configured command for a server id (blank ⇒ the server's default). */
    /** Shared "no overrides" instance, so a window with no project allocates nothing per read. */
    private static final com.editora.config.ProjectSettings EMPTY_PROJECT_SETTINGS =
            new com.editora.config.ProjectSettings();

    private Path projectSettingsRoot;
    private com.editora.config.ProjectSettings projectSettingsCache = EMPTY_PROJECT_SETTINGS;

    /**
     * The command {@code serverId} runs when the user chose one other than the default (a path, another server),
     * else null — so a missing custom server is not "fixed" by installing the default one (review B8).
     */
    String customServerCommand(String serverId) {
        if (serverId == null) {
            return null;
        }
        String command = serverCommand(serverId);
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(command));
        String fallback = com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId);
        return normalized.equals(String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(fallback)))
                ? null
                : command.strip();
    }

    private String serverCommand(String serverId) {
        return projectSettings().commandFor(serverId, serverCommand(host.settings(), serverId), projectTrusted());
    }

    /** Whether the user (or a trusted project) set this server's command, rather than leaving the default (Doctor). */
    boolean serverCommandSet(String serverId) {
        String raw = serverCommand(serverId);
        return raw != null && !raw.isBlank();
    }

    /**
     * Whether this window's project folder is trusted, which is what lets its committed
     * {@code .editora/settings.json} choose a program to run. Only consulted when the file overrides
     * something, so a project without one never touches the trust list.
     */
    private boolean projectTrusted() {
        Path root = ops.lspProjectRoot();
        com.editora.config.ConfigManager config = ops.config();
        return root != null
                && config != null
                && !projectSettings().isEmpty()
                && config.getTrustStore().isTrusted(root);
    }

    /**
     * The line Settings → LSP shows on {@code serverId}'s card when this window's project file
     * ({@code .editora/settings.json}) overrides it — the global command shown there is then not the one that
     * runs — or null when the project changes nothing for it.
     */
    String projectOverrideNote(String serverId) {
        com.editora.config.ProjectSettings ps = projectSettings();
        if (ps.isEmpty()) {
            return null;
        }
        List<String> parts = new java.util.ArrayList<>();
        String command = ps.getLspCommands().get(serverId);
        if (command != null && !command.isBlank()) {
            parts.add(tr(
                    projectTrusted() ? "settings.lsp.project.command" : "settings.lsp.project.untrusted",
                    command.strip()));
        }
        boolean global = globalServerEnabled(serverId);
        if (serverEnabled(serverId) != global) {
            parts.add(tr(global ? "settings.lsp.project.off" : "settings.lsp.project.on"));
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** What the project file asks for that needs trust (see {@code ProjectSettings.trustRequests}). */
    private List<String> projectTrustRequests() {
        List<String> requests = new java.util.ArrayList<>(
                projectSettings().trustRequests(id -> serverCommand(host.settings(), id), this::globalServerEnabled));
        requests.addAll(
                com.editora.lsp.BashIdeConfig.trustRequests(projectSettings().getBashIde()));
        return requests;
    }

    /**
     * bash-language-server's settings for this window: the user's, the project's {@code bashIde} overrides laid
     * over them (paths and the explainshell endpoint only in a trusted folder).
     */
    com.editora.lsp.BashIdeConfig bashIdeConfig() {
        com.editora.config.ConfigManager config = ops.config();
        return userBashIde(host.settings(), config == null ? null : config.getConfigDir())
                .withProject(projectSettings().getBashIde(), projectTrusted());
    }

    /**
     * The user's bash-language-server settings with a blank ShellCheck/shfmt path filled in from the copy Editora
     * installed under {@code configDir} (null = none).
     */
    static com.editora.lsp.BashIdeConfig userBashIde(com.editora.config.Settings s, Path configDir) {
        return com.editora.lsp.BashIdeConfig.of(s)
                .withInstalledTools(
                        com.editora.install.InstallCatalog.installedToolIfPresent(configDir, "shellcheck"),
                        com.editora.install.InstallCatalog.installedToolIfPresent(configDir, "shfmt"));
    }

    /** Whether ShellCheck's absence has been pointed out in this window already. */
    private boolean shellcheckNoticeShown;

    /**
     * Says once per window that ShellCheck is missing when a shell script is opened on the Bash server: the
     * server then lints nothing and says so only in its log, so the script just looked clean.
     */
    private void noticeMissingShellcheck() {
        if (!shellcheckNoticeShown && !bashToolFound("shellcheck")) {
            shellcheckNoticeShown = true;
            // After the opening finishes: the "Opened <file>" message is written later in the same pass and
            // replaced this one before anyone could read it.
            javafx.application.Platform.runLater(() -> host.setStatus(tr("status.lsp.bashNoShellcheck")));
        }
    }

    /** Whether the command the Bash server runs for {@code toolId} resolves (a PATH/file lookup, no process). */
    boolean bashToolFound(String toolId) {
        return com.editora.doctor.DoctorProbes.onPath(List.of(bashToolCommand(toolId)));
    }

    /** The command the Bash server runs for {@code toolId} ({@code shellcheck}/{@code shfmt}) in this window. */
    String bashToolCommand(String toolId) {
        com.editora.lsp.BashIdeConfig c = bashIdeConfig();
        return "shfmt".equals(toolId) ? c.effectiveShfmt() : c.effectiveShellcheck();
    }

    /** Hands the current {@link #bashIdeConfig()} to the manager, which pushes it to running Bash servers. */
    void applyBashIde() {
        lspManager.setBashIde(bashIdeConfig());
    }

    /** The project root whose withheld overrides were last announced, so a gating pass does not repeat it. */
    private Path withheldNoticeRoot;

    /** Says once per project that its server overrides are being ignored, and how to allow them. */
    private void noticeWithheldOverrides() {
        Path root = ops.lspProjectRoot();
        if (root == null || projectTrusted() || projectTrustRequests().isEmpty()) {
            withheldNoticeRoot = null;
            return;
        }
        if (!root.equals(withheldNoticeRoot)) {
            withheldNoticeRoot = root;
            host.setStatus(tr("status.lsp.projectSettingsUntrusted", tr("command.lsp.trustProjectSettings")));
        }
    }

    /** Asks before a folder is trusted; a field so a test can answer without a dialog. */
    java.util.function.BiPredicate<Path, List<String>> trustConfirmer = this::confirmTrustProjectSettings;

    private boolean confirmTrustProjectSettings(Path root, List<String> requests) {
        javafx.scene.control.ButtonType trust = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.trust.accept"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING,
                tr("dialog.lsp.trust.body", root.toString(), String.join("\n", requests)),
                javafx.scene.control.ButtonType.CANCEL,
                trust);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.trust.title"));
        Path name = root.getFileName();
        confirm.setHeaderText(tr("dialog.lsp.trust.header", name == null ? root.toString() : name.toString()));
        confirm.getDialogPane().setMinWidth(520);
        return confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == trust;
    }

    /**
     * Trusts this window's project folder so the language-server overrides in its
     * {@code .editora/settings.json} take effect ({@code lsp.trustProjectSettings}). Shows exactly which
     * commands the file would run before recording anything; the trust is the same per-folder record the
     * build-wrapper prompt writes, so it is listed and revocable in Settings under Workspace.
     */
    void trustProjectSettings() {
        Path root = ops.lspProjectRoot();
        com.editora.config.ConfigManager config = ops.config();
        if (root == null || config == null) {
            host.setStatus(tr("status.trust.noProject"));
            return;
        }
        List<String> sdkRequests = astroSdkTrustRequests(root);
        List<String> requests = new java.util.ArrayList<>(projectTrustRequests());
        requests.addAll(sdkRequests);
        if (requests.isEmpty()) {
            host.setStatus(tr("status.lsp.projectSettingsNothing"));
            return;
        }
        if (!config.getTrustStore().isTrusted(root)) {
            if (!trustConfirmer.test(root, requests)) {
                return;
            }
            config.getTrustStore().trust(root);
            config.saveTrust(); // durable: a security decision must survive a crash
        }
        if (!sdkRequests.isEmpty()) {
            lspManager.shutdownServer(ASTRO_SERVER_ID); // restart it on the SDK the folder may now supply
        }
        applySupport();
        host.syncSettingsWindow();
        host.setStatus(tr("status.lsp.projectSettingsTrusted"));
    }

    private static final String ASTRO_SERVER_ID = "astro";

    /**
     * What trusting {@code root} hands the Astro server besides the project file's commands: the folder's own
     * TypeScript SDK, whose JavaScript astro-ls loads and runs. One {@code "astro: <sdk dir>"} line per SDK
     * found at the project root or at the server root of an open Astro file, never above the project root.
     */
    private List<String> astroSdkTrustRequests(Path root) {
        if (!serverEnabled(ASTRO_SERVER_ID)) {
            return List.of();
        }
        Path top = root.toAbsolutePath().normalize();
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.function.Consumer<Path> look = start -> {
            if (start != null && start.toAbsolutePath().normalize().startsWith(top)) {
                Path sdk = LspManager.findTypeScriptSdk(start, top);
                if (sdk != null) {
                    found.add(ASTRO_SERVER_ID + ": " + sdk);
                }
            }
        };
        look.accept(top);
        host.forEachBuffer(b -> {
            if (lspPath(b) != null && ASTRO_SERVER_ID.equals(serverIdForBuffer(b))) {
                look.accept(lspRootFor(b, ASTRO_SERVER_ID));
            }
        });
        return List.copyOf(found);
    }

    /**
     * This window's project overrides, from the committed {@code .editora/settings.json} (#771).
     *
     * <p>Cached per project root because these are read on every gating pass, and a settings apply re-runs
     * gating for every open buffer — re-reading a file each time would be disk I/O on the FX thread for a
     * value that changes only when someone edits it. {@link #reloadProjectSettings()} drops the cache.
     */
    private com.editora.config.ProjectSettings projectSettings() {
        Path root = ops.lspProjectRoot();
        if (root == null) {
            return EMPTY_PROJECT_SETTINGS;
        }
        if (!root.equals(projectSettingsRoot)) {
            projectSettingsRoot = root;
            projectSettingsCache = com.editora.config.ProjectSettings.load(root);
        }
        return projectSettingsCache;
    }

    /** Whether {@code file} is this window's project {@code .editora/settings.json}. */
    private boolean isProjectSettingsFile(Path file) {
        Path root = ops.lspProjectRoot();
        return file != null
                && root != null
                && com.editora.vfs.Vfs.isLocal(file)
                && com.editora.config.PathKeys.sameNormalized(file, com.editora.config.ProjectSettings.fileFor(root));
    }

    /** The overrides in force when {@link #applySupport} last configured the manager. */
    private String appliedProjectOverrides = "";

    /** The project root those overrides were resolved for. */
    private Path appliedProjectRoot;

    /**
     * Re-runs {@link #applySupport} when this window's project has changed since it last ran and the new
     * project resolves to different server commands or enables; true when it did.
     *
     * <p>A window learns its project after {@code init} has already configured the manager, so without
     * this the first buffer would launch the previous project's (or the global) command.
     */
    private boolean overridesReapplied() {
        offerInterruptedEdits(); // a window learns its project late; asks once per root
        Path root = ops.lspProjectRoot();
        if (java.util.Objects.equals(root, appliedProjectRoot)) {
            return false;
        }
        appliedProjectRoot = root;
        if (projectOverrideSignature().equals(appliedProjectOverrides)) {
            return false;
        }
        applySupport(); // re-detects and re-gates every buffer, this one included
        return true;
    }

    /** Everything a re-configure depends on: the project's effective commands and enables, as one string. */
    private String projectOverrideSignature() {
        StringBuilder signature = new StringBuilder();
        for (String serverId : SERVER_IDS) {
            signature
                    .append(serverId)
                    .append('=')
                    .append(serverEnabled(serverId))
                    .append(':')
                    .append(serverCommand(serverId))
                    .append('\n');
        }
        return signature.append(bashIdeConfig()).toString();
    }

    /**
     * Forgets the cached project overrides so the next read picks up an edited file — and, when what they
     * resolve to has changed (an edited file, another project, a trust decision), re-runs
     * {@link #applySupport} so the change reaches the running servers instead of only the labels.
     */
    void reloadProjectSettings() {
        projectSettingsRoot = null;
        projectSettingsCache = EMPTY_PROJECT_SETTINGS;
        applyOnTypeFormatting();
        applyBashIde();
        appliedProjectRoot = ops.lspProjectRoot();
        offerInterruptedEdits();
        if (ops.lspFeatureEnabled() && !projectOverrideSignature().equals(appliedProjectOverrides)) {
            applySupport();
        }
    }

    /** Pure: {@code serverId}'s configured command from {@code s} — the id→Settings-field mapping, kept
     *  static so {@link #commandsForAllServers} (and its guard test) need no window/host (#723). */
    static String serverCommand(com.editora.config.Settings s, String serverId) {
        return switch (serverId) {
            case "typescript" -> s.getTypescriptLspCommand();
            case "python" -> s.getPythonLspCommand();
            case "xml" -> s.getXmlLspCommand();
            case "json" -> s.getJsonLspCommand();
            case "bash" -> s.getBashLspCommand();
            case "yaml" -> s.getYamlLspCommand();
            case "go" -> s.getGoLspCommand();
            case "rust" -> s.getRustLspCommand();
            case "php" -> s.getPhpLspCommand();
            case "ruby" -> s.getRubyLspCommand();
            case "clangd" -> s.getClangdLspCommand();
            case "html" -> s.getHtmlLspCommand();
            case "css" -> s.getCssLspCommand();
            case "kotlin" -> s.getKotlinLspCommand();
            case "lua" -> s.getLuaLspCommand();
            case "dockerfile" -> s.getDockerfileLspCommand();
            case "sql" -> s.getSqlLspCommand();
            case "terraform" -> s.getTerraformLspCommand();
            case "toml" -> s.getTomlLspCommand();
            case "csharp" -> s.getCsharpLspCommand();
            case "typst" -> s.getTypstLspCommand();
            case "astro" -> s.getAstroLspCommand();
            case "maven-pom" -> s.getMavenPomLspCommand();
            default -> s.getJavaLspCommand();
        };
    }

    /** Known server ids in Settings-page order (read by the Doctor screen's check catalog). */
    static java.util.List<String> serverIds() {
        return java.util.List.of(SERVER_IDS);
    }

    /**
     * The tokenized launch argv for {@code serverId} — the configured command, blank ⇒ the registry
     * default; empty for a server with neither (an uninstalled {@code maven-pom}). Doctor-screen read.
     */
    java.util.List<String> serverArgv(String serverId) {
        String raw = serverCommand(serverId);
        return com.editora.lsp.LspServerRegistry.commandFor(
                serverId, raw == null ? java.util.Map.of() : java.util.Map.of(serverId, raw));
    }

    /** Sets a server's per-server enable toggle (mirrors {@link #serverEnabled}). */
    private void setServerEnabled(String serverId, boolean on) {
        var s = host.settings();
        switch (serverId) {
            case "typescript" -> s.setTypescriptLspEnabled(on);
            case "python" -> s.setPythonLspEnabled(on);
            case "xml" -> s.setXmlLspEnabled(on);
            case "json" -> s.setJsonLspEnabled(on);
            case "bash" -> s.setBashLspEnabled(on);
            case "yaml" -> s.setYamlLspEnabled(on);
            case "go" -> s.setGoLspEnabled(on);
            case "rust" -> s.setRustLspEnabled(on);
            case "php" -> s.setPhpLspEnabled(on);
            case "ruby" -> s.setRubyLspEnabled(on);
            case "clangd" -> s.setClangdLspEnabled(on);
            case "html" -> s.setHtmlLspEnabled(on);
            case "css" -> s.setCssLspEnabled(on);
            case "kotlin" -> s.setKotlinLspEnabled(on);
            case "lua" -> s.setLuaLspEnabled(on);
            case "dockerfile" -> s.setDockerfileLspEnabled(on);
            case "sql" -> s.setSqlLspEnabled(on);
            case "terraform" -> s.setTerraformLspEnabled(on);
            case "toml" -> s.setTomlLspEnabled(on);
            case "csharp" -> s.setCsharpLspEnabled(on);
            case "typst" -> s.setTypstLspEnabled(on);
            case "astro" -> s.setAstroLspEnabled(on);
            case "maven-pom" -> s.setMavenPomLspEnabled(on);
            default -> s.setJavaLspEnabled(on);
        }
    }

    /** Sets a server's configured command (blank ⇒ the server's default); mirrors {@link #serverCommand}. */
    private void setServerCommand(String serverId, String command) {
        var s = host.settings();
        switch (serverId) {
            case "typescript" -> s.setTypescriptLspCommand(command);
            case "python" -> s.setPythonLspCommand(command);
            case "xml" -> s.setXmlLspCommand(command);
            case "json" -> s.setJsonLspCommand(command);
            case "bash" -> s.setBashLspCommand(command);
            case "yaml" -> s.setYamlLspCommand(command);
            case "go" -> s.setGoLspCommand(command);
            case "rust" -> s.setRustLspCommand(command);
            case "php" -> s.setPhpLspCommand(command);
            case "ruby" -> s.setRubyLspCommand(command);
            case "clangd" -> s.setClangdLspCommand(command);
            case "html" -> s.setHtmlLspCommand(command);
            case "css" -> s.setCssLspCommand(command);
            case "kotlin" -> s.setKotlinLspCommand(command);
            case "lua" -> s.setLuaLspCommand(command);
            case "dockerfile" -> s.setDockerfileLspCommand(command);
            case "sql" -> s.setSqlLspCommand(command);
            case "terraform" -> s.setTerraformLspCommand(command);
            case "toml" -> s.setTomlLspCommand(command);
            case "csharp" -> s.setCsharpLspCommand(command);
            case "typst" -> s.setTypstLspCommand(command);
            case "astro" -> s.setAstroLspCommand(command);
            case "maven-pom" -> s.setMavenPomLspCommand(command);
            default -> s.setJavaLspCommand(command);
        }
    }

    /**
     * Requests semantic tokens for {@code buffer}'s visible region (padded by {@link #SEMANTIC_WINDOW_PAD})
     * and pushes them into the buffer when the response lands — but only if it's still the active buffer
     * (a background tab's tokens would overlay nothing useful and waste an apply).
     */
    /**
     * Inlay hints (#681): requests the server's parameter-name/type hints over the visible window and
     * pushes the per-line aggregate into the buffer's end-of-line overlay. Gated on the (default-off)
     * setting + the server's capability; clears when the gate fails so a toggle-off empties the overlay.
     * Rides the same cadence as semantic tokens (didChange debounce, scroll-settle, ready, syncBuffer).
     */
    void requestInlayHints(EditorBuffer buffer) {
        scheduleCodeLens(buffer); // same cadence, its own (longer) settle and gate
        Path path = lspPath(buffer);
        if (path == null
                || !host.settings().isInlayHints()
                || !lspManager.isManaged(path)
                || !lspManager.supportsInlayHints(path)) {
            buffer.setInlayHints(null);
            return;
        }
        int[] window = paddedWindow(buffer.visibleLineWindow(), SEMANTIC_WINDOW_PAD, buffer.lineCount());
        long version = buffer.docVersion();
        var mode = InlayHintFilter.Mode.of(host.settings().getInlayHintMode());
        lspManager.requestInlayHints(path, window[0], window[1], buffer.lineCount(), buffer.lastLineLength(), spans -> {
            if (buffer == host.activeBuffer() && buffer.docVersion() == version) {
                // Filter first, then position (#823 + #824). Line text is read here — FX thread, same
                // docVersion — so the argument classified at each hint's column is the one the server saw.
                buffer.setInlayHints(toInlayHints(InlayHintFilter.filter(spans, mode, buffer::lineText)));
            }
        });
    }

    /**
     * Pure: the visible window grown by {@code pad} on each side and clamped to the document — {@code [0,
     * lineCount-1]}.
     *
     * <p>The clamp is the point (#724). The raw {@code visible[1] + 200} names a line that does not exist for
     * any file shorter than the viewport plus the pad — {@code LspManager} then adds another {@code +1} for
     * the exclusive range end, so a 27-line file was asking for {@code Position(227, 0)}. jdtls tolerates it,
     * but every response path ends in {@code .exceptionally(t -> List.of())}, so a stricter server would fail
     * <em>silently and indistinguishably from "no results"</em>. Clamped, the end becomes exactly
     * {@code Position(lineCount, 0)} — the document end, which is what a well-formed whole-file request
     * looks like.
     */
    static int[] paddedWindow(int[] visible, int pad, int lineCount) {
        int last = Math.max(0, lineCount - 1);
        int start = Math.max(0, visible[0] - pad);
        int end = Math.min(last, Math.max(visible[1], visible[0]) + pad);
        return new int[] {Math.min(start, end), end};
    }

    /**
     * Pure: the server's spans as positioned editor hints, in (line, col) order.
     *
     * <p>This replaced an end-of-line aggregate that joined a line's labels into one string (#824). Hints
     * now carry their own column all the way to the renderer, so the order matters only for determinism —
     * placement no longer depends on it.
     */
    static List<EditorBuffer.InlayHint> toInlayHints(List<com.editora.lsp.LspManager.InlayHintSpan> spans) {
        List<com.editora.lsp.LspManager.InlayHintSpan> sorted = new java.util.ArrayList<>(spans);
        sorted.sort(java.util.Comparator.comparingInt(com.editora.lsp.LspManager.InlayHintSpan::line)
                .thenComparingInt(com.editora.lsp.LspManager.InlayHintSpan::col));
        List<EditorBuffer.InlayHint> out = new java.util.ArrayList<>(sorted.size());
        for (var s : sorted) {
            out.add(new EditorBuffer.InlayHint(s.line(), s.col(), s.label()));
        }
        return out;
    }

    /** Whether either setting that annotates lines from the server is on: inlay hints or code lenses. */
    private boolean lineAnnotationsOn() {
        return host.settings().isInlayHints() || host.settings().isCodeLens();
    }

    /** Lines requested around the viewport: a lens just off screen is there when the view scrolls to it. */
    private static final int CODE_LENS_WINDOW_PAD = 30;

    /** Longer than the hint cadence: resolving a lens is a reference search on the server. */
    private final javafx.animation.PauseTransition codeLensSettle = codeLensSettle();

    private java.lang.ref.WeakReference<EditorBuffer> codeLensTarget = new java.lang.ref.WeakReference<>(null);

    private javafx.animation.PauseTransition codeLensSettle() {
        var settle = new javafx.animation.PauseTransition(javafx.util.Duration.millis(450));
        settle.setOnFinished(e -> {
            EditorBuffer target = codeLensTarget.get();
            if (target != null && target == host.activeBuffer()) {
                requestCodeLens(target);
            }
        });
        return settle;
    }

    /** Asks for {@code buffer}'s code lenses once editing and scrolling have settled; clears them when off. */
    private void scheduleCodeLens(EditorBuffer buffer) {
        Path path = lspPath(buffer);
        if (path == null
                || !host.settings().isCodeLens()
                || !lspManager.isManaged(path)
                || !lspManager.supportsCodeLens(path)) {
            buffer.setCodeLenses(null);
            return;
        }
        codeLensTarget = new java.lang.ref.WeakReference<>(buffer);
        codeLensSettle.playFromStart();
    }

    /**
     * Code lenses: the server's reference and implementation counts for the declarations in view, drawn
     * after their lines. A click opens what the lens counts.
     */
    void requestCodeLens(EditorBuffer buffer) {
        Path path = lspPath(buffer);
        if (path == null
                || !host.settings().isCodeLens()
                || !lspManager.isManaged(path)
                || !lspManager.supportsCodeLens(path)) {
            buffer.setCodeLenses(null);
            return;
        }
        int[] window = paddedWindow(buffer.visibleLineWindow(), CODE_LENS_WINDOW_PAD, buffer.lineCount());
        long version = buffer.docVersion();
        lspManager.changeDocument(path, buffer.text()); // the counts are for the text on screen
        lspManager.requestCodeLens(path, window[0], window[1], spans -> {
            if (buffer != host.activeBuffer() || buffer.docVersion() != version) {
                return;
            }
            List<EditorBuffer.CodeLens> lenses = new java.util.ArrayList<>(spans.size());
            for (LspManager.CodeLensSpan span : spans) {
                lenses.add(new EditorBuffer.CodeLens(span.line(), span.title(), span));
            }
            buffer.setCodeLensHandler((line, clicked) -> openCodeLens(buffer, line, clicked));
            buffer.setCodeLenses(lenses);
        });
    }

    /** A lens was clicked: with several on the line the user says which one. */
    private void openCodeLens(EditorBuffer buffer, int line, List<EditorBuffer.CodeLens> clicked) {
        if (clicked.size() == 1) {
            openCodeLens(buffer, line, clicked.get(0));
        } else if (!clicked.isEmpty()) {
            pickOne(
                    tr("settings.codeLens"),
                    tr("palette.setting.pick"),
                    clicked,
                    EditorBuffer.CodeLens::label,
                    l -> "",
                    chosen -> openCodeLens(buffer, line, chosen));
        }
    }

    /** Puts the caret on the declaration's name and runs the navigation the lens stands for. */
    private void openCodeLens(EditorBuffer buffer, int line, EditorBuffer.CodeLens lens) {
        if (buffer != host.activeBuffer() || !(lens.token() instanceof LspManager.CodeLensSpan span)) {
            return;
        }
        gotoInBuffer(buffer, line, span.col());
        if (span.kind() == LspManager.CodeLensKind.IMPLEMENTATIONS) {
            gotoImplementation();
        } else {
            findReferences(true);
        }
    }

    /** Re-applies the inlay-hints gate to every open buffer (the palette/Settings toggle's apply). */
    void applyInlayHints() {
        boolean on = lineAnnotationsOn();
        host.forEachBuffer(b -> {
            b.setInlayHintsActive(on && lspPath(b) != null && lspManager.isManaged(lspPath(b)));
            requestInlayHints(b); // the gate inside clears buffers when toggled off
        });
    }

    void requestSemanticTokens(EditorBuffer buffer) {
        Path path = lspPath(buffer);
        if (path == null || !buffer.isSemanticActive() || !lspManager.isManaged(path)) {
            return;
        }
        int[] window = paddedWindow(buffer.visibleLineWindow(), SEMANTIC_WINDOW_PAD, buffer.lineCount());
        long gen = buffer.semanticGen(); // capture now; the reply is dropped if the doc changes before it lands
        lspManager.requestSemanticTokens(
                path, window[0], window[1], buffer.lineCount(), buffer.lastLineLength(), tokens -> {
                    if (buffer == host.activeBuffer()) {
                        if (gen == buffer.semanticGen()) {
                            semanticShownGen.put(buffer, gen); // the buffer accepts it: see semanticTokensCurrent
                        }
                        buffer.setSemanticTokens(tokens, gen);
                    }
                });
    }

    /** The {@link EditorBuffer#semanticGen()} each buffer's displayed semantic tokens were applied at. */
    private final Map<EditorBuffer, Long> semanticShownGen = new java.util.WeakHashMap<>();

    /**
     * Whether {@code buffer} already shows the tokens a new request would return: its server only answers
     * for the whole document, that answer was computed for the text the server still holds
     * ({@code LspManager.wholeDocumentTokensCurrent}), and it was applied to the buffer as it stands now.
     * Both halves are needed — an edit that nets to nothing leaves the server's text alone but still
     * marks the buffer's tokens stale, and they then have to be fetched again.
     */
    private boolean semanticTokensCurrent(EditorBuffer buffer) {
        Path path = lspPath(buffer);
        Long shown = semanticShownGen.get(buffer);
        return path != null
                && shown != null
                && shown == buffer.semanticGen()
                && buffer.isSemanticActive()
                && lspManager.isManaged(path)
                && lspManager.wholeDocumentTokensCurrent(path);
    }

    /** Scroll-settle for inlay hints while semantic highlighting is off; see {@link #wireBuffer}. */
    private final javafx.animation.PauseTransition inlayScrollSettle = inlayScrollSettle();

    private EditorBuffer inlayScrollTarget;

    private javafx.animation.PauseTransition inlayScrollSettle() {
        var settle = new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
        settle.setOnFinished(e -> {
            EditorBuffer target = inlayScrollTarget;
            inlayScrollTarget = null;
            if (target != null && target == host.activeBuffer() && !target.isDisposed()) {
                requestInlayHints(target);
            }
        });
        return settle;
    }

    /**
     * Re-gates LSP semantic highlighting against {@code Settings.semanticHighlight} for every open buffer
     * (the palette toggle's apply). Doesn't disturb the sessions — just flips each managed buffer's overlay
     * on/off and fetches tokens when turning on.
     */
    void applySemanticHighlight() {
        boolean want = host.settings().isSemanticHighlight();
        host.forEachBuffer(b -> {
            if (lspPath(b) == null) {
                return;
            }
            boolean on = want && lspManager.isManaged(lspPath(b)) && lspManager.supportsSemanticTokens(lspPath(b));
            b.setSemanticActive(on);
            if (on) {
                requestSemanticTokens(b);
            }
        });
    }

    /**
     * Refreshes the Structure tool window's outline for {@code buffer} from the language server
     * ({@code textDocument/documentSymbol}) when supported; otherwise clears the LSP outline so the panel
     * falls back to the TextMate/fold heuristic. Only acts for the active buffer; an empty result also
     * falls back to the heuristic.
     */
    void requestStructureSymbols(EditorBuffer buffer) {
        requestStructureSymbols(buffer, false);
    }

    /** How the Structure panel asks for the outline it let go stale while nothing was showing it. */
    private final java.util.function.Consumer<EditorBuffer> structureSymbolsWanted =
            buffer -> requestStructureSymbols(buffer, true);

    /**
     * @param evenIfHidden ask the server although the Structure window is closed — the panel needs the
     *     outline now (it is being shown, or the Jump to Structure picker is reading it). Otherwise a closed
     *     window is told its outline is stale instead: the request used to go out on every typing pause for
     *     an outline nobody was looking at.
     */
    private void requestStructureSymbols(EditorBuffer buffer, boolean evenIfHidden) {
        if (buffer == null || buffer != host.activeBuffer()) {
            return;
        }
        StructurePanel panel = ops.structurePanel();
        if (panel != null) {
            panel.setLspSymbolsRequester(structureSymbolsWanted);
        }
        Path path = lspPath(buffer);
        if (path != null && lspManager.isManaged(path) && lspManager.supportsDocumentSymbols(path)) {
            if (!evenIfHidden && panel != null && !panel.showsOutline()) {
                panel.lspSymbolsStale(buffer);
                return;
            }
            long version = buffer.docVersion();
            lspManager.latestDocumentSymbols(path, syms -> {
                if (buffer == host.activeBuffer()
                        && java.util.Objects.equals(path, lspPath(buffer))
                        && version == buffer.docVersion()) {
                    setStructureSymbols(buffer, syms.isEmpty() ? null : syms);
                }
            });
        } else {
            setStructureSymbols(buffer, null);
        }
    }

    private void setStructureSymbols(EditorBuffer buffer, List<com.editora.lsp.SymbolNode> symbols) {
        StructurePanel panel = ops.structurePanel();
        if (panel != null) {
            panel.setLspSymbols(buffer, symbols);
        }
    }

    /** Opens+activates an eligible buffer on its language's server, or deactivates+closes it otherwise. */
    void syncBuffer(EditorBuffer buffer) {
        if (ops.lspFeatureEnabled() && overridesReapplied()) {
            return;
        }
        // An untitled Python buffer is served under a made-up path; saved, its file takes over (review B14).
        Path path = ops.lspFeatureEnabled() ? untitledLspPath(buffer) : lspPath(buffer);
        String serverId = serverIdForBuffer(buffer); // pom.xml → maven-pom (when available), else the language's server
        // Where the file's tools run elsewhere (a remote host, a container, WSL) its server runs there too, when
        // that may run commands; astro-ls does not yet.
        boolean remote = path != null && !com.editora.process.ExecTargets.runsHere(path);
        boolean eligible = ops.lspFeatureEnabled()
                && path != null
                && (!remote || (RemoteExec.runsFor(path) && !remoteUnsupported(serverId)))
                && !buffer.isLargeFile() // 5+ MB files skip LSP (like highlighting/minimap/git) — see setLspActive
                // The intermediate large-source tier skips LSP (keeps highlighting) — up to a per-language limit:
                // Python keeps Pyright to 50,000 lines (review B10).
                && com.editora.lsp.LspServerRegistry.lspAllowedForSize(
                        buffer.getLanguage(),
                        buffer.isHeavyFile(),
                        buffer.getArea().getParagraphs().size())
                // While narrowed the area holds only the region, so every position the server sends or
                // receives is offset — a formatting edit or code action would land in the wrong place and
                // corrupt the file. Suspend the document until the buffer is widened again.
                && !buffer.isNarrowed()
                // An encrypted file's text reaches a language server only when Settings allow it (ADR 0015);
                // a locked one has no text to serve yet.
                && (!buffer.encryption().isEncrypted()
                        || (host.settings().isEncryptionLanguageServers()
                                && !buffer.encryption().isLocked()))
                && serverId != null
                && serverEnabled(serverId)
                && (remote
                        ? remoteServerAvailable(buffer, path, serverId)
                        : Boolean.TRUE.equals(serverAvailable.get(serverId)));
        buffer.setHeavyFileLspAllowed(buffer.isHeavyFile() && eligible); // B10: within the language's limit
        watchedProjectRoot = ops.lspProjectRoot();
        if (eligible && "python".equals(serverId)) {
            pythonInterpreters.captureSnapshot(); // what the server's start resolves its interpreter from
        }
        if (eligible
                && libraryOrigin(path, serverId) != null
                && libraryViewModeApplied.add(path.toAbsolutePath().normalize())
                && !buffer.isDirty()) {
            buffer.setViewMode(true); // a library or stub file is for reading; Enable Editing is one click away
        }
        if (eligible) {
            // Open when not yet managed, or re-open when the desired server changed — a pom.xml moving from the
            // plain XML server to lemminx-maven (once installed/enabled), or back. isManaged() alone can't tell:
            // it's keyed by URI, not server, so it stays true across a switch (which would otherwise never happen).
            String managing = lspManager.managedServerId(path);
            if (!serverId.equals(managing)) {
                if (managing != null) {
                    lspManager.closeDocument(path); // drop the old server's document + diagnostics before switching
                    clearDiagnostics(path);
                }
                host.setStatus(tr("status.lsp.starting", serverLabel(serverId)));
                ops.setLspLoading(true); // show the loading bar until the server reports ready
                lspManager.openDocument(
                        path, lspRootFor(buffer, serverId), routeLanguageId(buffer, serverId), buffer.text());
            }
            buffer.setLspActive(true);
            // Push the server's completion trigger characters + request initial pull diagnostics. Both are
            // no-ops until the server's initialize completes (then onServerStatus "ready" refreshes them),
            // and effective immediately when the server for this root is already running (a 2nd file).
            buffer.setLspTriggerChars(lspManager.triggerCharacters(path));
            buffer.setLspFormatAvailable(lspManager.supportsFormatting(path) || buffer.isPython()); // Ruff/Black
            buffer.setLspRangeFormatAvailable(lspManager.supportsRangeFormatting(path));
            buffer.setLspOnTypeTriggers(lspManager.onTypeTriggerCharacters(path)); // #740
            buffer.setLspCodeActionsAvailable(lspManager.supportsCodeActions(path));
            buffer.setLspRenameAvailable(lspManager.supportsRename(path));
            buffer.setLspImplementationAvailable(lspManager.supportsImplementation(path)); // #735
            buffer.setLspTypeDefinitionAvailable(lspManager.supportsTypeDefinition(path)); // #736
            buffer.setLspSignatureTriggerChars(lspManager.signatureTriggerCharacters(path));
            lspManager.pullDiagnostics(path);
            // Semantic highlighting: gate on the setting + the server's capability; request the initial
            // viewport (a no-op until the server reports ready, then onServerStatus refreshes it).
            boolean semantic = host.settings().isSemanticHighlight() && lspManager.supportsSemanticTokens(path);
            buffer.setSemanticActive(semantic);
            if (semantic) {
                requestSemanticTokens(buffer);
            }
            buffer.setInlayHintsActive(lineAnnotationsOn()); // decoupled from semantic (#681)
            requestInlayHints(buffer); // gated internally on the setting + capability (#681)
            requestFoldingRanges(buffer); // #738 — a no-op until the server reports ready, then refreshed
            if ("bash".equals(serverId) && !remote) {
                noticeMissingShellcheck();
            }
        } else {
            buffer.setLspActive(false);
            buffer.setLspTriggerChars(java.util.Set.of());
            buffer.setLspFormatAvailable(false);
            buffer.setLspRangeFormatAvailable(false);
            buffer.setLspOnTypeTriggers(java.util.Set.of()); // #740
            buffer.setLspCodeActionsAvailable(false);
            buffer.setLspRenameAvailable(false);
            buffer.setLspImplementationAvailable(false);
            buffer.setLspTypeDefinitionAvailable(false);
            buffer.setLspFoldingRegions(java.util.List.of()); // back to the heuristic (#738)
            buffer.setLspSignatureTriggerChars(java.util.Set.of());
            buffer.clearOccurrenceSpans();
            buffer.setInlayHintsActive(false);
            buffer.setInlayHints(null);
            buffer.setCodeLenses(null);
            buffer.setSemanticActive(false);
            if (path != null && lspManager.isManaged(path)) {
                lspManager.closeDocument(path);
                clearDiagnostics(path);
            }
        }
        if (buffer == host.activeBuffer()) {
            // The "LSP: <server>" segment and the Problems stripe follow whether the active file is managed,
            // which only changes here. Every other refresh (a tab switch, a server detected) runs before a
            // newly opened file reaches this point, so they stayed hidden until the user switched tabs.
            updateStatusBar();
        }
    }

    /** Workspace root for a buffer under {@code serverId}: active project (if Projects on), else nearest build
     *  file (that server's markers), else the file's dir. */
    private Path lspRootFor(EditorBuffer buffer, String serverId) {
        Path file = lspPath(buffer);
        if (isUntitledLspPath(file)) {
            Path project = ops.lspProjectRoot(); // an untitled buffer in a project imports from it
            return project != null ? project : file.getParent();
        }
        if (com.editora.vfs.Vfs.isRemote(file)) {
            // No marker probing over the network on the FX thread. A file on the project's host belongs to the
            // project's server even outside its folder: a definition in the host's site-packages, module cache
            // or registry is read where the project's server already runs, instead of starting a second server
            // that would index that whole library folder and hold another of the connection's channels.
            Path project = ops.lspProjectRoot();
            boolean sameHost = project != null && project.getFileSystem() == file.getFileSystem();
            return sameHost ? project : file.getParent();
        }
        LibraryOrigin origin = libraryOrigin(file, serverId);
        if (origin != null) {
            return origin.root(); // a library file reached from the project: read in the project's server (B4)
        }
        return com.editora.lsp.RootResolver.resolveTiered(
                ops.lspProjectRoot(),
                lspPath(buffer),
                com.editora.lsp.LspServerRegistry.rootMarkerTiersForServer(serverId));
    }

    /** Library files already put in View mode once (Enable Editing then sticks). */
    private final java.util.Set<Path> libraryViewModeApplied = new java.util.HashSet<>();

    /** The server (and its root) a library file was reached from by navigation. */
    record LibraryOrigin(String serverId, Path root) {}

    /** Library files reached by Go to Definition and friends → the server that sent us there (review B4). */
    private final Map<Path, LibraryOrigin> libraryOrigins = new java.util.HashMap<>();

    /**
     * Remembers that navigation from {@code anchor} led to {@code target} outside both the anchor's server root
     * and the project: the stdlib, site-packages, a server's bundled stubs. Opening it then joins that server
     * (which treats it as a library and reports nothing about it) instead of starting a second one on the
     * library folder — which indexed all 612 files of {@code /usr/lib/python3.13}, or nvm's whole tree for
     * Pyright's own {@code builtins.pyi}, and painted the stdlib red. It also opens in View mode.
     */
    void noteLibraryTarget(Path anchor, Path target) {
        if (anchor == null || target == null || com.editora.vfs.Vfs.isRemote(target)) {
            return;
        }
        String serverId = lspManager.managedServerId(anchor);
        Path root = lspManager.sessionRoot(anchor);
        if (serverId == null || root == null) {
            return;
        }
        if (isLibraryTarget(target, root, ops.lspProjectRoot())) {
            libraryOrigins.put(target.toAbsolutePath().normalize(), new LibraryOrigin(serverId, root));
        }
    }

    /** Whether {@code target} lies outside the server root and the project. Pure. */
    static boolean isLibraryTarget(Path target, Path serverRoot, Path projectRoot) {
        Path t = target.toAbsolutePath().normalize();
        if (serverRoot != null && t.startsWith(serverRoot.toAbsolutePath().normalize())) {
            return false;
        }
        return projectRoot == null || !t.startsWith(projectRoot.toAbsolutePath().normalize());
    }

    /** The origin recorded for {@code file} when the buffer is about to be served by {@code serverId}. */
    private LibraryOrigin libraryOrigin(Path file, String serverId) {
        if (file == null) {
            return null;
        }
        LibraryOrigin o = libraryOrigins.get(file.toAbsolutePath().normalize());
        return o != null && o.serverId().equals(serverId) ? o : null;
    }

    /** Servers that cannot run on a remote host yet (astro-ls needs a trusted TypeScript SDK found there). */
    private static boolean remoteUnsupported(String serverId) {
        return "astro".equals(serverId);
    }

    /**
     * Whether {@code serverId} is on the host of remote {@code path}, as last asked. Unknown: false for now,
     * and the host is asked; a yes opens the buffer then.
     */
    private boolean remoteServerAvailable(EditorBuffer buffer, Path path, String serverId) {
        Boolean known = lspManager.remoteAvailability(path, serverId);
        if (known == null) {
            lspManager.detectRemote(path, serverId, ok -> {
                if (ok && !buffer.isDisposed() && path.equals(lspPath(buffer))) {
                    syncBuffer(buffer);
                }
            });
            return false;
        }
        return known;
    }

    /**
     * The server id that should serve {@code b}, honoring the {@code pom.xml} → Maven-server routing while
     * leaving every other file on its language's normal server. A {@code pom.xml} routes to the
     * {@code maven-pom} server when that server is <b>enabled</b>; while its availability is still unknown
     * (not yet probed) this returns null so the buffer is <i>not</i> opened on the plain XML server first
     * (which {@link #syncBuffer} would then never switch away from). Once probed: available → {@code maven-pom};
     * absent → fall back to the language's normal server, so a {@code pom.xml} still gets base-XML LSP when
     * lemminx-maven isn't installed. Disabling {@code maven-pom} skips the routing entirely (pure native XML).
     */
    String serverIdForBuffer(EditorBuffer b) {
        Path p = b == null ? null : lspPath(b);
        String base = b == null ? null : com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage());
        String pomServer = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        String fileName =
                p == null || p.getFileName() == null ? null : p.getFileName().toString();
        if (com.editora.lsp.LspServerRegistry.isPomFile(fileName) && serverEnabled(pomServer)) {
            Boolean mavenOk = serverAvailable.get(pomServer);
            if (mavenOk == null) {
                return null; // not yet probed — don't open on the plain XML server first; wait for the probe
            }
            if (mavenOk) {
                return pomServer;
            }
            // probed and absent → fall through to the plain XML server (base-XML LSP still works)
        }
        // zsh startup files (.zshrc, /etc/zsh/zshenv, …) say nothing about being zsh, so bash-language-server would
        // lint them as bash and mark valid zsh as parse errors. A *.zsh script it recognizes and skips itself.
        if ("bash".equals(base) && com.editora.editor.ShellFiles.isZshStartupFile(fileName)) {
            return null;
        }
        return base;
    }

    /** The LSP <i>routing</i> language id to pass to {@link com.editora.lsp.LspManager#openDocument} for a
     *  buffer resolved to {@code serverId}: the Maven pseudo id for the pom server, else the buffer's language. */
    private static String routeLanguageId(EditorBuffer buffer, String serverId) {
        return com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)
                ? com.editora.lsp.LspServerRegistry.MAVEN_POM_LANGUAGE_ID
                : buffer.getLanguage();
    }

    /** The accept hook for a completion item: resolve it + apply any additional edits (a TypeScript
     *  auto-import's {@code import} line). Returns null when the item can't carry extra edits. */
    private Runnable autoImportAccept(EditorBuffer buffer, org.eclipse.lsp4j.CompletionItem item) {
        if (!com.editora.lsp.CompletionMapper.mayHaveAdditionalEdits(item) && item.getCommand() == null) return null;
        return () -> {
            Path path = lspPath(buffer);
            if (path == null) return;
            Runnable command = () -> {
                var c = item.getCommand();
                if (c != null && c.getCommand() != null && path.equals(lspPath(buffer)) && !buffer.isDisposed()) {
                    buffer.sendLspChange();
                    // Tracked, so the workspace/applyEdit the command answers with has a basis to be
                    // validated against (an untracked one is refused unless it names current versions).
                    lspManager.applyCodeAction(path, (Object) c, applied -> {});
                }
            };
            var eager = com.editora.lsp.CompletionMapper.additionalEdits(item);
            if (!eager.isEmpty()) {
                buffer.applyCompletionAdditionalEdits(eager);
                command.run();
            } else if (item.getData() != null) {
                var apply = buffer.trackCompletionAdditionalEdits();
                lspManager.resolveCompletion(path, item, edits -> {
                    apply.accept(path.equals(lspPath(buffer)) ? edits : List.of());
                    command.run();
                });
            } else command.run();
        };
    }

    /** The project root whose files the Project panel watches (null = none), for the server-start thread. */
    private volatile Path watchedProjectRoot;

    /**
     * Untitled buffers served by a language server → the made-up file path their document has there (review
     * B14): an untitled Python buffer got no completion, errors or hover at all, because every request is keyed
     * by a file. The path names nothing on disk; it lives in a per-window folder under the temporary folder.
     */
    private final Map<EditorBuffer, Path> untitledLspPaths = new java.util.WeakHashMap<>();

    private int untitledLspCounter;

    /** The folder an untitled buffer's made-up document path lives in (per window, created on first use). */
    private Path untitledLspFolder;

    /** The path {@code buffer}'s document has on its language server: its file, or an untitled buffer's made-up path. */
    Path lspPath(EditorBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        Path real = buffer.getPath();
        return real != null ? real : untitledLspPaths.get(buffer);
    }

    /** Whether {@code path} is an untitled buffer's made-up document path. */
    boolean isUntitledLspPath(Path path) {
        return path != null && untitledLspPaths.containsValue(path);
    }

    /** The buffer a server's path means: an untitled buffer's made-up path, else the open file. */
    private EditorBuffer bufferForLspPath(Path path) {
        if (path != null && !untitledLspPaths.isEmpty()) {
            for (var e : untitledLspPaths.entrySet()) {
                if (e.getValue().equals(path)) {
                    return e.getKey();
                }
            }
        }
        return ops.bufferForPath(path);
    }

    /**
     * Gives an untitled Python buffer a made-up document path ({@code Untitled-N.py}), or
     * drops it once the buffer has a file of its own. Returns the path to serve it under (null = none).
     */
    private Path untitledLspPath(EditorBuffer buffer) {
        if (buffer.getPath() != null) {
            Path old = untitledLspPaths.remove(buffer);
            if (old != null && lspManager.isManaged(old)) {
                lspManager.closeDocument(old); // saved: the real file takes over
                clearDiagnostics(old);
            }
            return buffer.getPath();
        }
        // Python only: Pyright reads an unsaved document from didOpen alone, where jdtls and others want a
        // file in a project on disk.
        if (!buffer.isPython()) {
            return untitledLspPaths.get(buffer);
        }
        return untitledLspPaths.computeIfAbsent(buffer, b -> {
            try {
                if (untitledLspFolder == null) {
                    untitledLspFolder = java.nio.file.Files.createTempDirectory("editora-untitled-");
                    untitledLspFolder.toFile().deleteOnExit();
                }
            } catch (java.io.IOException e) {
                return null;
            }
            return untitledLspFolder.resolve("Untitled-" + (++untitledLspCounter) + ".py");
        });
    }

    /** The window's Python interpreter: status-bar item, picker, and what Pyright is told (review B1). */
    final PythonInterpreterCoordinator pythonInterpreters;

    /** The buffer + language the Python interpreter item was last computed for (re-resolved only on a change). */
    private EditorBuffer pythonStatusBuffer;

    private String pythonStatusLanguage;

    /** Ruff/Black Format Document for Python files no language server formats (adl's decision 4). */
    final PythonFormatCoordinator pythonFormat;

    /**
     * {@code lsp.formatDocument} for a Python file: Ruff or Black when no running server formats it. True when
     * this took the command — then it works with the language server off too.
     */
    boolean formatPythonExternally() {
        pythonInterpreters.captureSnapshot();
        return pythonFormat.formatActive();
    }

    /** For Doctor (FX thread): resolves, off the FX thread, the interpreter of the active Python file or project. */
    java.util.function.Supplier<com.editora.run.PythonInterpreters.PythonInterpreter> pythonInterpreterForDoctor() {
        pythonInterpreters.captureSnapshot();
        Path file = doctorPythonFile();
        return () -> pythonInterpreters.resolve(file);
    }

    /** For Doctor (FX thread): whether that interpreter runs on another machine. */
    boolean pythonInterpreterElsewhereForDoctor() {
        return pythonInterpreters.elsewhere(doctorPythonFile());
    }

    /** The active Python file Doctor's interpreter row is about (null = the project as a whole). */
    private Path doctorPythonFile() {
        EditorBuffer b = host.activeBuffer();
        return b != null && b.isPython() ? b.getPath() : null;
    }

    /** {@code python.selectInterpreter}: the interpreter picker for the active file's project. */
    void selectPythonInterpreter() {
        pythonInterpreters.selectInterpreter(host.activeBuffer());
    }

    /** The project's interpreter pick changed: servers re-resolve it, the item is recomputed. */
    void pythonInterpreterChanged() {
        lspManager.refreshPythonInterpreters();
        pythonStatusBuffer = null;
        updateStatusBar();
    }

    /** Recomputes the Python interpreter item when the active buffer (or its language) changed. */
    private void updatePythonStatus(EditorBuffer b) {
        String language = b == null ? null : b.getLanguage();
        if (b == pythonStatusBuffer && java.util.Objects.equals(language, pythonStatusLanguage)) {
            return;
        }
        pythonStatusBuffer = b;
        pythonStatusLanguage = language;
        pythonInterpreters.refreshStatus(b);
    }

    /** Updates the status-bar LSP segment: "LSP: &lt;server&gt;" when the active file is managed, else hidden. */
    void updateStatusBar() {
        EditorBuffer b = host.activeBuffer();
        updatePythonStatus(b);
        // The actually-managing server (so a pom.xml on lemminx-maven reads "maven-pom", not the language's "xml").
        String serverId = b == null || lspPath(b) == null ? null : lspManager.managedServerId(lspPath(b));
        ops.setStatusBarLsp(serverId != null ? serverLabel(serverId) : null);
        updateProblemsAvailability(); // the Problems window tracks the same active-file LSP-managed condition
    }

    /**
     * The Problems tool-window stripe button is shown only when the active file is served by a language
     * server (LSP on + managed) — the same condition as the status-bar {@code LSP:} segment. So it's hidden
     * on a Welcome/Markdown/plain tab even if another open file has diagnostics.
     */
    private void updateProblemsAvailability() {
        EditorBuffer b = host.activeBuffer();
        boolean available =
                ops.lspFeatureEnabled() && b != null && lspPath(b) != null && lspManager.isManaged(lspPath(b));
        ops.setProblemsAvailable(available);
    }

    /** The short server name shown in the status bar — the configured command's basename for {@code serverId}. */
    private String serverLabel(String serverId) {
        // The Maven server launches via `java -cp ...`, whose basename ("java") is meaningless — name it directly.
        if (com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)) {
            return "lemminx-maven";
        }
        String configured = serverCommand(serverId);
        String cmd = configured == null || configured.isBlank()
                ? com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId)
                : configured;
        List<String> toks = com.editora.run.ProgramArgs.commandLine(cmd);
        String exe = toks.isEmpty() ? serverId : toks.get(0);
        try {
            return Path.of(exe).getFileName().toString();
        } catch (RuntimeException e) {
            return exe;
        }
    }

    /**
     * Shows a language server's status/log message in the echo area and drives the status-bar loading
     * bar: a "ServiceReady"/"Ready" (or "Error") status stops it. {@code type} is the JDT LS
     * {@code language/status} type (or "Message"/"Error").
     */
    void onServerStatus(String type, String message) {
        onServerStatus(null, type, message);
    }

    /**
     * As {@link #onServerStatus(String, String)}, from the server {@code serverId} (null = unknown): the
     * message is prefixed with that server's name — it used to read "Java LSP:" whichever server sent it.
     */
    void onServerStatus(String serverId, String type, String message) {
        if (!ops.lspFeatureEnabled()) {
            return;
        }
        if (message != null && !message.isBlank()) {
            host.setStatus(tr("status.lsp.server", serverId == null ? "LSP" : serverLabel(serverId), message));
        }
        if (type != null) {
            String t = type.toLowerCase(Locale.ROOT);
            // $/progress (#683): a Begin spins the loading bar for long server work (jdtls indexing, a
            // gradle sync) — not just startup; its End stops it. The bar is a plain boolean, so an overlap
            // with the startup lifecycle is benign (worst case it stops a beat early and ServiceReady or
            // the next Begin corrects it).
            if (t.equals("progress")) {
                ops.setLspLoading(true);
            } else if (t.equals("progressend")) {
                ops.setLspLoading(false);
            }
            if (t.contains("ready") || t.contains("error")) {
                ops.setLspLoading(false); // server finished starting (or failed)
            }
            // A "ready" status also means the server's capabilities are now known. The manager turns it
            // into a capabilities refresh for that server's documents (see refreshRequested), which pushes
            // the completion trigger characters and pulls initial diagnostics (the pull-model servers
            // don't publish until asked).
        }
    }

    /** Restarts every running server, clears diagnostics, then re-gates each buffer ({@code lsp.restartServers}). */
    void restartServers() {
        lspManager.shutdownAll();
        clearAllDiagnostics();
        applyGating();
        host.setStatus(tr("status.lsp.restarted"));
    }

    /** Notifies the server of a save (didSave) for a managed file + refreshes pull-model diagnostics. */
    void notifyDocumentSaved(EditorBuffer buffer, String savedText) {
        if (buffer != null && buffer.getPath() != null && untitledLspPaths.containsKey(buffer)) {
            syncBuffer(buffer); // an untitled buffer saved: its file replaces the made-up document (B14)
            return;
        }
        if (buffer != null && isProjectSettingsFile(lspPath(buffer))) {
            reloadProjectSettings(); // the overrides were just edited: apply them to the running servers
        }
        if (buffer != null && lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
            // Flush the current open-document state first. savedText may be an older snapshot when the user
            // continued typing during I/O; includeText still describes the bytes that actually reached disk.
            lspManager.changeDocument(lspPath(buffer), buffer.getContent());
            lspManager.saveDocument(lspPath(buffer), savedText);
            lspManager.pullDiagnostics(lspPath(buffer)); // no-op for push-only servers
        }
    }

    /** Wires every LSP hook onto a freshly-added buffer (didChange/diagnostics/completion/format/nav), then
     *  opens+activates it if eligible. Called from {@code MainController.addBuffer}. */
    void wireBuffer(EditorBuffer buffer) {
        // Debounced didChange sink + keep the Structure outline live as the document changes.
        buffer.getNode().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && buffer == host.activeBuffer()) {
                hideSignaturePopup();
                hideHoverPopup();
            }
        });
        buffer.setSignatureHelpRequester(ch -> {
            if (buffer == host.activeBuffer()) signatureHelp(false, ch);
        }); // '(' or ',' typed (#674, #725)
        buffer.setOccurrenceRequester(() -> requestOccurrences(buffer)); // caret at rest (#675)
        buffer.setLspChangeListener(text -> {
            if (lspPath(buffer) != null && !lspManager.changeDocument(lspPath(buffer), text)) {
                // A net-zero edit (type + Backspace, edit + undo): the server was sent nothing and will
                // publish nothing, but the edit already cleared the buffer's marks.
                diagnosticsUnchanged(lspPath(buffer));
            }
        });
        // Pull-model diagnostics (fired on the same debounce as didChange; no-op for push-only servers).
        buffer.setLspDiagnosticsRequester(() -> {
            if (lspPath(buffer) != null) {
                lspManager.pullDiagnostics(lspPath(buffer));
                requestStructureSymbols(buffer);
                if (buffer == host.activeBuffer()) refreshSignatureHelpIfShowing();
                requestFoldingRanges(buffer); // #738 — rides the same debounce, no extra pulse
            }
        });
        // Semantic tokens re-request (fired on the same debounce as didChange + on scroll-settle).
        buffer.setSemanticTokensRequester(() -> {
            // This also runs when scrolling settles. A server without range requests answers with the
            // whole document every time, so when the buffer already shows its answer for this text there
            // is nothing a scroll could change — only a transfer and a decode of every token to save.
            if (!semanticTokensCurrent(buffer)) {
                requestSemanticTokens(buffer);
            }
            requestInlayHints(buffer); // same cadence: didChange debounce + scroll-settle (#681)
        });
        // The buffer's own scroll-settle trigger only runs while semantic highlighting is on, so with it
        // off (the setting, or a server without semantic tokens) scrolling never fetched the hints for the
        // lines scrolled to. Cover exactly that case here.
        buffer.getArea().estimatedScrollYProperty().addListener((obs, was, now) -> {
            if (host.settings().isInlayHints() && !buffer.isSemanticActive() && buffer == host.activeBuffer()) {
                inlayScrollTarget = buffer;
                inlayScrollSettle.playFromStart();
            }
        });
        buffer.setLspCompletionSource((line, column, kind, trigger, cb) -> {
            if (lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
                return lspManager.completion(
                        lspPath(buffer), line, column, kind, trigger, item -> autoImportAccept(buffer, item), cb);
            }
            cb.accept(com.editora.completion.CompletionResult.EMPTY);
            return () -> {};
        });
        // Lazy documentation for the completion doc side-popup: resolve the item's docs on demand.
        buffer.setCompletionDocResolver((token, cb) -> {
            if (lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
                lspManager.resolveCompletionDoc(lspPath(buffer), token, cb);
            } else {
                cb.accept(null);
            }
        });
        buffer.setCompletionDocEnabled(host.settings().isCompletionDoc());
        // Tab re-indents the current line to the server's convention via range formatting (when supported).
        buffer.setLspRangeFormatter((sl, sc, el, ec, cb) -> {
            if (lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
                com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
                buffer.sendLspChange(); // the didChange debounce may be pending: format the text on screen
                lspManager.rangeFormatting(
                        lspPath(buffer), sl, sc, el, ec, indent.tabSize(), indent.insertSpaces(), cb);
            } else {
                cb.accept(java.util.List.of());
            }
        });
        // Paste auto-import (#742): ask jdtls what the pasted code needs; only jdtls advertises the
        // command, so any other server (or language) drops out inside handlePasteEvent's capability gate.
        buffer.setLspPasteImportsRequester((sl, sc, el, ec, text, stillValid) -> {
            if (lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
                com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
                lspManager.handlePasteEvent(
                        lspPath(buffer),
                        sl,
                        sc,
                        el,
                        ec,
                        text,
                        indent.tabSize(),
                        indent.insertSpaces(),
                        stillValid,
                        applied -> {});
            }
        });
        // Smart-semicolon detection (#746): where does a ';' typed here belong?
        buffer.setLspSmartSemicolonRequester((line, character, cb) -> {
            if (lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
                lspManager.smartSemicolonPosition(lspPath(buffer), line, character, cb);
            } else {
                cb.accept(null);
            }
        });
        // On-type formatting (#740): re-indent the line after a server-declared trigger character.
        buffer.setLspOnTypeFormatter((line, character, ch, cb) -> {
            if (lspPath(buffer) != null && lspManager.isManaged(lspPath(buffer))) {
                com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
                buffer.sendLspChange(); // the server must have the character that triggered this
                lspManager.onTypeFormatting(
                        lspPath(buffer), line, character, ch, indent.tabSize(), indent.insertSpaces(), cb);
            } else {
                cb.accept(java.util.List.of());
            }
        });
        buffer.setLspNavActions(
                this::gotoDefinition,
                this::findReferences,
                this::showHover,
                () -> {
                    if (!formatPythonExternally()) {
                        formatDocument();
                    }
                },
                this::codeActions,
                this::rename,
                this::gotoImplementation,
                this::gotoTypeDefinition);
        // A language that changes later — picked in the status bar, or a shebang typed into an extension-less
        // file — moves the document to that language's server (or off any), as opening the file would.
        buffer.addLanguageListener(() -> syncBufferWhenShown(buffer));
        // Only the visible buffer starts its server now; a restored background tab waits for its first show.
        syncBufferWhenShown(buffer);
    }

    /** Palette picker over the LSP servers: toggles the chosen server's per-server enable ({@code lsp.toggleServer}). */
    void chooseServerToggle() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.lsp.toggleServer"),
                tr("palette.setting.pick"),
                () -> List.of(SERVER_IDS),
                id -> id + "  —  " + tr(serverEnabled(id) ? "common.on" : "common.off"),
                this::serverLabel,
                id -> {
                    if (id == null) {
                        return;
                    }
                    boolean next = !serverEnabled(id);
                    setServerEnabled(id, next);
                    host.requestSave();
                    applySupport();
                    host.syncSettingsWindow();
                    host.setStatus(tr("status.settingToggled", id, tr(next ? "common.on" : "common.off")));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Palette picker over the LSP servers, then prompts for the chosen server's command ({@code lsp.setServerCommand}). */
    void chooseServerCommand() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.lsp.setServerCommand"),
                tr("palette.setting.pick"),
                () -> List.of(SERVER_IDS),
                id -> id,
                this::serverLabel,
                id -> {
                    if (id == null) {
                        return;
                    }
                    // Prefill with the GLOBAL value: this prompt writes the persistent global setting, so
                    // showing the project's override here would copy a repository's command into it.
                    host.promptText(id, tr("palette.setting.value"), serverCommand(host.settings(), id), v -> {
                        String value = v.trim();
                        setServerCommand(id, value);
                        host.requestSave();
                        applySupport();
                        host.syncSettingsWindow();
                        host.setStatus(tr("status.settingChanged", id, value));
                    });
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    // --- folding ranges (#738) / selection ranges (#739) ---------------------------------------------

    /**
     * Asks the server for this buffer's foldable regions and installs them over the brace/indent heuristic
     * (#738). No-op — leaving the heuristic in place — when the buffer has no path, isn't managed, or its
     * server has no folding provider.
     */
    void requestFoldingRanges(EditorBuffer buffer) {
        Path path = buffer == null ? null : lspPath(buffer);
        if (path == null || !lspManager.isManaged(path) || !lspManager.supportsFoldingRanges(path)) {
            return;
        }
        long version = buffer.docVersion();
        lspManager.foldingRanges(path, regions -> {
            // The document may have moved while the request was out — the regions are line numbers measured
            // against the text we sent, so applying them to a changed document folds the wrong lines. The
            // next debounce pulse re-requests, so dropping this one costs nothing.
            if (buffer.docVersion() == version) {
                buffer.setLspFoldingRegions(regions);
            }
        });
    }

    /** The cached selection-range chain for expand-selection, valid only for the buffer + document version
     *  it was fetched against (#739). Held weakly: the slot only answers "is this the same buffer", and a
     *  strong field kept the last expanded-in buffer alive after its tab closed. */
    private java.lang.ref.WeakReference<EditorBuffer> selectionChainBuffer = new java.lang.ref.WeakReference<>(null);

    private long selectionChainVersion = -1;
    private int selectionChainRequest;
    private List<int[]> selectionChain = List.of();

    /**
     * The server's selection-range chain for {@code buffer}, or empty when none has been fetched for the
     * document as it stands now. Expand-selection prefers it over the local {@code SmartSelect} ladder.
     */
    List<int[]> selectionChain(EditorBuffer buffer) {
        boolean valid =
                buffer != null && buffer == selectionChainBuffer.get() && buffer.docVersion() == selectionChainVersion;
        return valid ? selectionChain : List.of();
    }

    /**
     * Fetches the selection-range chain anchored at a caret position, for the <em>next</em> expand press
     * (#739).
     *
     * <p>Deliberately not awaited: expand-selection has to act on the keystroke that triggered it, and a
     * server round-trip on that path is exactly what makes an editor feel laggy — a cold jdtls can take
     * seconds. So the first press of a ladder uses the local ladder while this request is in flight, and
     * every press after it uses the grammar-accurate chain. The cache is anchored at the ladder's origin
     * rather than the live caret, because {@code selectRange} moves the caret to the range end on each
     * press — re-anchoring per press would re-request per press.
     */
    void requestSelectionChain(EditorBuffer buffer, int line, int character) {
        Path path = buffer == null ? null : lspPath(buffer);
        // The cache is anchored at one ladder's origin: drop it now, so presses made before this request
        // answers fall back to the local ladder instead of walking the previous ladder's chain.
        selectionChainBuffer.clear();
        selectionChain = List.of();
        selectionChainVersion = -1;
        int request = ++selectionChainRequest;
        if (path == null || !lspManager.isManaged(path) || !lspManager.supportsSelectionRanges(path)) {
            return;
        }
        long version = buffer.docVersion();
        lspManager.selectionRanges(path, line, character, buffer.lineStartOffsets(), chain -> {
            if (buffer.docVersion() != version || request != selectionChainRequest) {
                return; // stale: the text has since changed, or a newer ladder has asked
            }
            selectionChainBuffer = new java.lang.ref.WeakReference<>(buffer);
            selectionChainVersion = version;
            selectionChain = chain;
        });
    }

    /**
     * Runs a jdtls source-generation prompt (#741) if {@code item} is one, and reports whether it took over.
     *
     * <p>These actions must <b>not</b> reach {@code applyCodeAction}: their command is a client-side
     * {@code java.action.*Prompt}, so sending it as {@code workspace/executeCommand} fails and the user sees
     * "code action failed". Instead we run the server's {@code java/check…} request, show the member picker,
     * and run {@code java/generate…} with what was chosen.
     */
    private boolean runGeneratePrompt(Path path, LspManager.CodeActionItem item) {
        Object params = LspManager.commandArgument(item.raw());
        JdtlsGenerate.Kind kind = JdtlsGenerate.forCommand(LspManager.commandIdOf(item.raw()));
        if (kind == null || params == null) {
            return false;
        }
        if (kind == JdtlsGenerate.Kind.DELEGATE_METHODS) {
            runDelegatePrompt(path, item, params);
            return true;
        }
        lspManager.jdtlsGenerateCandidates(path, kind, params, plan -> {
            List<JdtlsGenerate.Candidate> candidates = plan.candidates();
            List<JdtlsGenerate.Candidate> constructors = kind == JdtlsGenerate.Kind.CONSTRUCTORS
                    ? JdtlsGenerate.constructorCandidates(plan.status())
                    : List.of();
            if (candidates.isEmpty() && constructors.isEmpty()) {
                host.setStatus(tr("status.lsp.generateNothing", item.title()));
                return;
            }
            // Constructors are a two-step choice: which super constructors to call (each one becomes a
            // generated constructor), then which fields to initialise. A class with no fields — the usual
            // exception subclass — still has constructors to generate.
            java.util.function.Consumer<List<JdtlsGenerate.Candidate>> withConstructors = superChosen -> {
                java.util.function.Consumer<List<JdtlsGenerate.Candidate>> apply = chosen -> {
                    beginReportedEdit();
                    lspManager.jdtlsGenerateApply(
                            path,
                            kind,
                            params,
                            plan.status(),
                            item.expectedDocuments(),
                            chosen,
                            superChosen,
                            ok -> reportEdit(
                                    ok,
                                    tr("status.lsp.codeActionApplied", item.title()),
                                    tr("status.lsp.codeActionFailed", item.title())));
                };
                if (candidates.isEmpty()) {
                    apply.accept(List.of());
                } else {
                    MultiSelectPicker.show(host.overlayHost(), item.title(), pickerRows(candidates), apply);
                }
            };
            if (constructors.size() > 1) {
                MultiSelectPicker.show(
                        host.overlayHost(),
                        tr("picker.generate.superConstructors", item.title()),
                        pickerRows(constructors),
                        withConstructors);
            } else {
                withConstructors.accept(null);
            }
        });
        return true;
    }

    /**
     * Generate Delegate Methods: a field first (only asked when several can be delegated to), then the
     * methods of that field to forward.
     */
    private void runDelegatePrompt(Path path, LspManager.CodeActionItem item, Object params) {
        JdtlsGenerate.Kind kind = JdtlsGenerate.Kind.DELEGATE_METHODS;
        lspManager.jdtlsRequest(path, kind.checkRequest(), params, status -> {
            List<JdtlsGenerate.DelegateField> fields = JdtlsGenerate.delegateFields(status);
            if (fields.isEmpty()) {
                host.setStatus(tr("status.lsp.generateNothing", item.title()));
                return;
            }
            java.util.function.Consumer<JdtlsGenerate.DelegateField> withField = field -> MultiSelectPicker.show(
                    host.overlayHost(),
                    item.title() + " — " + field.label(),
                    pickerRows(field.methods()),
                    chosen -> applyJdtlsEdit(
                            path,
                            item,
                            kind.generateRequest(),
                            JdtlsGenerate.delegateParams(LspManager.asJson(params), field, chosen)));
            if (fields.size() == 1) {
                withField.accept(fields.get(0));
            } else {
                pickOne(item.title(), tr("picker.generate.delegateField"), fields, f -> f.label(), f -> "", withField);
            }
        });
    }

    /** Sends a {@code java/…} request that answers with an edit, applies it, and reports the outcome. */
    private void applyJdtlsEdit(Path path, LspManager.CodeActionItem item, String method, Object params) {
        beginReportedEdit();
        lspManager.jdtlsApplyEdit(path, method, params, item.expectedDocuments(), (ok, refused) -> {
            if (!ok && refused != null) {
                host.setError(refused); // the server's own reason beats "could not apply"
                return;
            }
            reportEdit(
                    ok,
                    tr("status.lsp.codeActionApplied", item.title()),
                    tr("status.lsp.codeActionFailed", item.title()));
        });
    }

    /** A single-choice picker over {@code items}; {@code onChoose} is not called when it is dismissed. */
    private <T> void pickOne(
            String title,
            String prompt,
            List<T> items,
            java.util.function.Function<T, String> label,
            java.util.function.Function<T, String> detail,
            java.util.function.Consumer<T> onChoose) {
        QuickOpen<T> picker = new QuickOpen<>(title, prompt, () -> items, label, detail, chosen -> {
            if (chosen != null) {
                onChoose.accept(chosen);
            }
        });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /**
     * Carries out a jdtls refactoring the client has to drive — Move, Extract Interface, Change Signature
     * ({@link JdtlsRefactor}) — if {@code item} is one, and reports whether it took over.
     *
     * <p>Like the generate prompts these must not reach {@code applyCodeAction}: their command,
     * {@code java.action.applyRefactoringCommand}, is not one the server executes.
     */
    private boolean runRefactorCommand(Path path, LspManager.CodeActionItem item) {
        JdtlsRefactor.Request request =
                JdtlsRefactor.parse(LspManager.commandIdOf(item.raw()), LspManager.commandArguments(item.raw()));
        if (request == null) {
            return false;
        }
        String uri = request.documentUri();
        switch (request.name()) {
            case JdtlsRefactor.MOVE_FILE -> {
                String source = request.info("uri") == null ? uri : request.info("uri");
                moveTo(
                        path,
                        item,
                        "moveResource",
                        source,
                        null,
                        tr("picker.refactor.package"),
                        r -> JdtlsRefactor.packages(r, false));
            }
            case JdtlsRefactor.MOVE_INSTANCE_METHOD ->
                moveTo(
                        path,
                        item,
                        "moveInstanceMethod",
                        uri,
                        request.params(),
                        tr("picker.refactor.moveTarget"),
                        JdtlsRefactor::instanceTargets);
            case JdtlsRefactor.MOVE_STATIC_MEMBER -> moveToType(path, item, "moveStaticMember", request);
            case JdtlsRefactor.MOVE_TYPE -> moveType(path, item, request);
            case JdtlsRefactor.EXTRACT_INTERFACE -> extractInterface(path, item, request);
            case JdtlsRefactor.CHANGE_SIGNATURE -> changeSignature(path, item, request);
            default ->
                // A refactoring that needs no choice from the user: the edit is one request away.
                applyJdtlsEdit(path, item, "java/getRefactorEdit", refactorEdit(request, null));
        }
        return true;
    }

    private com.google.gson.JsonObject refactorEdit(
            JdtlsRefactor.Request request, com.google.gson.JsonArray arguments) {
        EditorBuffer buffer = activeLspBuffer();
        com.editora.lsp.FormattingIndent indent = buffer == null
                ? new com.editora.lsp.FormattingIndent(host.settings().getTabSize(), true)
                : formattingIndent(buffer);
        return JdtlsRefactor.refactorEditParams(
                request.name(), request.params(), indent.tabSize(), indent.insertSpaces(), arguments);
    }

    /** Asks the server where {@code source} can move, lets the user choose, and moves it there. */
    private void moveTo(
            Path path,
            LspManager.CodeActionItem item,
            String moveKind,
            String source,
            com.google.gson.JsonElement params,
            String prompt,
            java.util.function.Function<com.google.gson.JsonElement, List<JdtlsRefactor.Destination>> read) {
        lspManager.jdtlsRequest(
                path, "java/getMoveDestinations", JdtlsRefactor.moveParams(moveKind, source, params, null), answer -> {
                    String refused = JdtlsRefactor.errorMessage(answer);
                    List<JdtlsRefactor.Destination> targets = read.apply(answer);
                    if (refused != null || targets.isEmpty()) {
                        host.setError(refused != null ? refused : tr("status.lsp.refactorNoTarget", item.title()));
                        return;
                    }
                    pickDestination(
                            item,
                            prompt,
                            targets,
                            target -> applyJdtlsEdit(
                                    path,
                                    item,
                                    "java/move",
                                    JdtlsRefactor.moveParams(moveKind, source, params, target.raw())));
                });
    }

    /** Moves a static member or a nested type into another type of the same project. */
    private void moveToType(Path path, LspManager.CodeActionItem item, String moveKind, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(
                path, "java/searchSymbols", JdtlsRefactor.searchTypesParams(request.info("projectName")), answer -> {
                    List<JdtlsRefactor.Destination> types =
                            JdtlsRefactor.types(answer, request.info("enclosingTypeName"));
                    if (types.isEmpty()) {
                        host.setError(tr("status.lsp.refactorNoTarget", item.title()));
                        return;
                    }
                    pickDestination(
                            item,
                            tr("picker.refactor.class"),
                            types,
                            target -> applyJdtlsEdit(
                                    path,
                                    item,
                                    "java/move",
                                    JdtlsRefactor.moveParams(
                                            moveKind, request.documentUri(), request.params(), target.raw())));
                });
    }

    /** Move Type: into a file of its own, or into another class — asked only when both are possible. */
    private void moveType(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        Runnable newFile = () -> applyJdtlsEdit(
                path,
                item,
                "java/move",
                JdtlsRefactor.moveParams("moveTypeToNewFile", request.documentUri(), request.params(), null));
        Runnable otherClass = () -> moveToType(path, item, "moveTypeToClass", request);
        boolean toFile = request.supportsDestination("newFile");
        boolean toClass = request.supportsDestination("class");
        if (toFile && toClass) {
            String fileLabel = tr("picker.refactor.moveType.newFile");
            pickOne(
                    item.title(),
                    tr("picker.refactor.moveTarget"),
                    List.of(fileLabel, tr("picker.refactor.moveType.class")),
                    s -> s,
                    s -> "",
                    chosen -> (chosen.equals(fileLabel) ? newFile : otherClass).run());
        } else if (toClass) {
            otherClass.run();
        } else {
            newFile.run();
        }
    }

    private void pickDestination(
            LspManager.CodeActionItem item,
            String prompt,
            List<JdtlsRefactor.Destination> targets,
            java.util.function.Consumer<JdtlsRefactor.Destination> onChoose) {
        pickOne(item.title(), prompt, targets, JdtlsRefactor.Destination::label, d -> d.detail(), onChoose);
    }

    /** Extract Interface: which methods, the interface's name, and the package it is created in. */
    private void extractInterface(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(path, "java/checkExtractInterfaceStatus", request.params(), status -> {
            List<JdtlsGenerate.Candidate> members = JdtlsRefactor.interfaceMembers(status);
            List<JdtlsRefactor.Destination> packages = JdtlsRefactor.interfacePackages(status);
            if (members.isEmpty() || packages.isEmpty()) {
                host.setStatus(tr("status.lsp.generateNothing", item.title()));
                return;
            }
            String subType = JdtlsRefactor.subTypeName(status);
            MultiSelectPicker.show(
                    host.overlayHost(),
                    item.title(),
                    pickerRows(members),
                    chosen -> host.promptText(
                            item.title(),
                            tr("prompt.refactor.interfaceName"),
                            subType == null ? "" : subType + "Interface",
                            typed -> {
                                String name = typed.strip();
                                if (!JdtlsRefactor.isTypeName(name)) {
                                    host.setError(tr("status.lsp.refactorBadName", name));
                                    return;
                                }
                                java.util.function.Consumer<JdtlsRefactor.Destination> create = pkg -> applyJdtlsEdit(
                                        path,
                                        item,
                                        "java/getRefactorEdit",
                                        refactorEdit(
                                                request, JdtlsRefactor.extractInterfaceArguments(chosen, name, pkg)));
                                if (packages.size() == 1) {
                                    create.accept(packages.get(0));
                                } else {
                                    pickDestination(item, tr("picker.refactor.package"), packages, create);
                                }
                            }));
        });
    }

    /** Change Signature: the method's signature is edited as one line of text. */
    private void changeSignature(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(path, "java/getChangeSignatureInfo", request.params(), info -> {
            String refused = JdtlsRefactor.errorMessage(info);
            String signature = JdtlsRefactor.signatureText(info);
            if (refused != null || signature.isEmpty()) {
                host.setError(refused != null ? refused : tr("status.lsp.codeActionFailed", item.title()));
                return;
            }
            host.promptText(item.title(), tr("prompt.refactor.signature"), signature, typed -> {
                if (typed.strip().equals(signature)) {
                    return; // nothing changed
                }
                com.google.gson.JsonArray arguments = JdtlsRefactor.changeSignatureArguments(info, typed);
                if (arguments == null) {
                    host.setError(tr("status.lsp.refactorBadSignature"));
                    return;
                }
                applyJdtlsEdit(path, item, "java/getRefactorEdit", refactorEdit(request, arguments));
            });
        });
    }

    private static List<MultiSelectPicker.Item<JdtlsGenerate.Candidate>> pickerRows(
            List<JdtlsGenerate.Candidate> candidates) {
        List<MultiSelectPicker.Item<JdtlsGenerate.Candidate>> rows = new java.util.ArrayList<>();
        for (JdtlsGenerate.Candidate c : candidates) {
            rows.add(new MultiSelectPicker.Item<>(c.label(), c.preselected(), c));
        }
        return rows;
    }

    // --- jdtls project + editing commands (#746) -----------------------------------------------------

    /** Organize the active file's imports, the way its server does it ({@link LspManager#organizeImportsAny}). */
    void organizeImports() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        if (!ops.activeEditable()) {
            host.setStatus(tr("status.lsp.readOnly"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        int lastLine = Math.max(0, area.getParagraphs().size() - 1);
        int lastChar = area.getParagraph(lastLine).length();
        lspManager.changeDocument(lspPath(b), b.text()); // the server organizes the text it has
        lspManager.organizeImportsAny(
                lspPath(b),
                lastLine,
                lastChar,
                outcome -> host.setStatus(tr(
                        switch (outcome) {
                            case ORGANIZED -> "status.lsp.importsOrganized";
                            case UNCHANGED -> "status.lsp.importsUnchanged";
                            case UNSUPPORTED -> "status.lsp.importsUnsupported";
                        })));
    }

    /** Copies the fully qualified name of the symbol at the caret ({@code java.getFullyQualifiedName}). */
    void copyQualifiedName() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(lspPath(b), b.text());
        lspManager.fullyQualifiedName(lspPath(b), area.getCurrentParagraph(), area.getCaretColumn(), name -> {
            if (name == null) {
                host.setStatus(tr("status.lsp.noQualifiedName"));
                return;
            }
            var clip = new javafx.scene.input.ClipboardContent();
            clip.putString(name);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
            host.setStatus(tr("status.lsp.qualifiedNameCopied", name));
        });
    }

    /**
     * Re-reads the project's build configuration ({@code java/projectConfigurationUpdate}).
     *
     * <p>{@code workspace/didChangeWatchedFiles} already tells the server when a {@code pom.xml} changes, so
     * this is the escape hatch for when that hasn't taken — not the normal path. Fire-and-forget: it's a
     * notification, so the only observable result is fresh diagnostics arriving.
     */
    void reloadProject() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        lspManager.reloadProjectConfiguration(lspPath(b));
        host.setStatus(tr("status.lsp.projectReloading"));
    }

    /**
     * Deletes the Java server's saved project indexes that no running server uses
     * ({@code lsp.clearJavaIndexCaches}), off the FX thread. Works with LSP off too: the caches outlive it.
     */
    void clearJavaIndexCaches() {
        Path base = ops.jdtlsWorkspaceBase();
        if (base == null) {
            return;
        }
        host.setStatus(tr("status.lsp.javaCachesClearing"));
        workspaceExecutor.execute(() -> {
            LspManager.JdtlsCacheClearResult result = LspManager.clearJdtlsWorkspaces(base);
            Platform.runLater(() -> host.setStatus(javaCachesClearedStatus(result)));
        });
    }

    /** The status line for a finished {@link #clearJavaIndexCaches}. */
    static String javaCachesClearedStatus(LspManager.JdtlsCacheClearResult result) {
        String kept = result.kept() == 0 ? null : tr("status.lsp.javaCachesKept", result.kept());
        if (result.deleted() == 0) {
            return kept == null ? tr("status.lsp.javaCachesNone") : kept;
        }
        String cleared =
                tr("status.lsp.javaCachesCleared", result.deleted(), StatusBar.formatSize(result.bytesFreed()));
        return kept == null ? cleared : cleared + " " + kept;
    }

    /** The active buffer if it is LSP-managed, reporting + returning null otherwise. */
    private EditorBuffer activeLspBuffer() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || lspPath(b) == null || !lspManager.isManaged(lspPath(b))) {
            host.setStatus(tr("status.lsp.unavailable"));
            return null;
        }
        return b;
    }

    /** Reads a peeked file off the FX thread; peek is user-initiated, so one lazy thread is plenty. */
    private static final java.util.concurrent.ExecutorService PEEK_READ =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "peek-read");
                t.setDaemon(true);
                return t;
            });

    private PeekPopup peekPopup;

    /**
     * {@code lsp.peekDefinition}: show the definition here rather than going to it.
     *
     * <p>The same request as {@link #gotoDefinition()}, answered without moving. Most uses of
     * go-to-definition are a question — what is this, what does it take — and the answer does not justify
     * losing your place, your scroll position and usually a tab. Enter in the popup still commits to the
     * real jump, so nothing is taken away.
     */
    void peekDefinition() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        Path originPath = lspPath(b);
        long originVersion = b.docVersion();
        long requestGeneration = ++navigationRequestGeneration;
        lspManager.changeDocument(originPath, b.text());
        lspManager.definition(originPath, area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (!navigationRequestCurrent(b, originPath, originVersion, requestGeneration)) {
                return;
            }
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noDefinition"));
                return;
            }
            LspManager.Target t = targets.get(0);
            if (t.file() == null) {
                // A jdt:// class-file target has no file to read a snippet out of, so peek degrades to
                // the thing it is a lighter version of rather than reporting a failure.
                openLibraryDefinition(
                        lspPath(b), t, () -> navigationRequestCurrent(b, originPath, originVersion, requestGeneration));
                return;
            }
            peekTarget(t, b, originPath, originVersion, requestGeneration);
        });
    }

    private void peekTarget(
            LspManager.Target t, EditorBuffer origin, Path originPath, long originVersion, long requestGeneration) {
        EditorBuffer open = bufferForLspPath(t.file());
        if (open != null) {
            // Already open: its text is authoritative (it may hold unsaved edits) and free to read here.
            showPeek(t, open.text());
            return;
        }
        PEEK_READ.submit(() -> {
            String text;
            try {
                text = java.nio.file.Files.readString(t.file());
            } catch (java.io.IOException | RuntimeException ex) {
                javafx.application.Platform.runLater(() -> {
                    if (navigationRequestCurrent(origin, originPath, originVersion, requestGeneration)) {
                        host.setStatus(tr("status.lsp.peekUnreadable"));
                    }
                });
                return;
            }
            javafx.application.Platform.runLater(() -> {
                if (navigationRequestCurrent(origin, originPath, originVersion, requestGeneration)) {
                    showPeek(t, text);
                }
            });
        });
    }

    private void showPeek(LspManager.Target t, String text) {
        if (peekPopup == null) {
            peekPopup = new PeekPopup(host.overlayHost());
        }
        String name = t.file().getFileName().toString();
        String language = com.editora.editor.LanguageRegistry.forFileName(name);
        String title = tr("lsp.peek.title", name, t.line() + 1);
        // The editor's own font, so the peeked lines wrap and align the way the file does.
        peekPopup.show(
                PeekPopup.build(title, text, t.line(), language),
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                () -> ops.openAndGoto(t.file(), t.line(), t.character()));
    }

    void gotoDefinition() {
        gotoDefinition(null);
    }

    /**
     * As {@link #gotoDefinition()}, running {@code afterJump} once the caret has landed.
     *
     * <p>The hook exists because the destination arrives from the server: a caller that wants to do
     * something with the file the jump opened cannot simply run afterwards, since "afterwards" is before
     * the answer has come back. It runs only on a jump that actually happened.
     */
    void gotoDefinition(Runnable afterJump) {
        gotoDefinition(afterJump, null);
    }

    /**
     * As {@link #gotoDefinition(Runnable)}; {@code noAnswer} runs when no server serves the file or the server
     * finds nothing — the server-free index then gets its turn — and keeps this method's own status message
     * when it returns false (it could not answer either).
     */
    void gotoDefinition(Runnable afterJump, java.util.function.BooleanSupplier noAnswer) {
        EditorBuffer active = host.activeBuffer();
        LibrarySource lib = active == null ? null : librarySources.get(active);
        if (lib != null) {
            libraryGotoDefinition(active, lib); // navigating INSIDE an opened jdt:// source (#684)
            return;
        }
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            if (noAnswer != null) {
                noAnswer.getAsBoolean();
            }
            return;
        }
        CodeArea area = b.getFocusedArea();
        Path originPath = lspPath(b);
        long originVersion = b.docVersion();
        long requestGeneration = ++navigationRequestGeneration;
        lspManager.changeDocument(lspPath(b), b.text()); // sync latest text before the request
        lspManager.definition(lspPath(b), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (!navigationRequestCurrent(b, originPath, originVersion, requestGeneration)) {
                return;
            }
            if (targets.isEmpty()) {
                if (noAnswer == null || !noAnswer.getAsBoolean()) {
                    host.setStatus(tr("status.lsp.noDefinition"));
                }
            } else {
                LspManager.Target t = targets.get(0);
                if (t.file() != null) {
                    noteLibraryTarget(originPath, t.file());
                    ops.openAndGoto(t.file(), t.line(), t.character());
                    if (afterJump != null) {
                        // openAndGoto finishes its work in a runLater of its own, so the hook has to queue
                        // behind it or it would act on the tab we are leaving.
                        javafx.application.Platform.runLater(afterJump);
                    }
                } else {
                    openLibraryDefinition(
                            lspPath(b),
                            t,
                            () -> navigationRequestCurrent(b, originPath, originVersion, requestGeneration));
                }
            }
        });
    }

    /**
     * Class-file source tabs already opened from a {@code jdt://} definition, keyed by URI so a repeated
     * {@code M-.} on the same class re-selects its tab instead of spawning another. Weak values: a closed
     * tab's buffer is disposed and must be collectable; a stale entry just falls through to a re-fetch.
     */
    private final Map<String, java.lang.ref.WeakReference<EditorBuffer>> libraryBuffers = new java.util.HashMap<>();

    /** What a library-source (jdt://) tab was opened from: its own URI + the workspace file whose session
     *  produced it — lets navigation chain from inside library code (#684). Weak keys: dies with the tab. */
    private final Map<EditorBuffer, LibrarySource> librarySources = new java.util.WeakHashMap<>();

    private record LibrarySource(String uri, Path anchor) {}

    /**
     * Opens the source of a JDK/dependency class the server reported under a {@code jdt://} URI: fetched via
     * jdtls's {@code java/classFileContents} into a read-only, Java-highlighted, path-less buffer (#665).
     * Before this, such a definition was silently dropped and {@code M-.} on {@code String}/{@code List}
     * reported "no definition". {@code anchor} is the buffer the navigation started from (it routes the
     * request to the right session).
     */
    /**
     * Opens a {@code jdt://} class-file URI at a line — how a stack-trace frame inside a dependency or the
     * JDK becomes clickable (#744). Reuses the same read-only library-source tab as go-to-definition (#665),
     * so a repeat click re-selects the existing tab rather than opening another.
     */
    void openLibraryFrame(Path anchorPath, String jdtUri, int line0) {
        openLibraryFrame(anchorPath, jdtUri, line0, b -> {});
    }

    /** As above, handing the library-source tab to {@code opened} once it shows (a debugger frame paints it). */
    void openLibraryFrame(Path anchorPath, String jdtUri, int line0, java.util.function.Consumer<EditorBuffer> opened) {
        openLibraryDefinition(
                anchorPath, new LspManager.Target(null, Math.max(0, line0), 0, jdtUri), () -> true, opened);
    }

    private void openLibraryDefinition(Path anchorPath, LspManager.Target t) {
        openLibraryDefinition(anchorPath, t, () -> true);
    }

    private void openLibraryDefinition(
            Path anchorPath, LspManager.Target t, java.util.function.BooleanSupplier stillCurrent) {
        openLibraryDefinition(anchorPath, t, stillCurrent, b -> {});
    }

    private void openLibraryDefinition(
            Path anchorPath,
            LspManager.Target t,
            java.util.function.BooleanSupplier stillCurrent,
            java.util.function.Consumer<EditorBuffer> onOpened) {
        if (!stillCurrent.getAsBoolean()) {
            return;
        }
        String uri = t.classFileUri();
        var ref = libraryBuffers.get(uri);
        EditorBuffer existing = ref == null ? null : ref.get();
        if (existing != null && ops.selectBufferTab(existing)) {
            gotoInBuffer(existing, t.line(), t.character());
            onOpened.accept(existing);
            return;
        }
        host.setStatus(tr("status.lsp.libraryLoading"));
        lspManager.classFileContents(anchorPath, uri, content -> {
            if (!stillCurrent.getAsBoolean()) {
                return;
            }
            if (content == null) {
                host.setStatus(tr("status.lsp.libraryUnavailable"));
                return;
            }
            EditorBuffer opened = ops.openReadOnlyDoc(LspManager.classFileTitle(uri), content, "java");
            if (opened != null) {
                libraryBuffers.put(uri, new java.lang.ref.WeakReference<>(opened));
                librarySources.put(opened, new LibrarySource(uri, anchorPath)); // chain point (#684)
                gotoInBuffer(opened, t.line(), t.character());
                onOpened.accept(opened);
            }
        });
    }

    /**
     * Go-to-definition from INSIDE an opened {@code jdt://} library source (#684): the tab has no
     * filesystem path, so the request goes out with the library document's own jdt URI on the anchor
     * file's session — jdtls resolves positions in the jdt documents it has served. Results chain: a
     * file target opens normally, another library target opens (or re-selects) its own read-only tab.
     */
    private void libraryGotoDefinition(EditorBuffer buffer, LibrarySource lib) {
        CodeArea area = buffer.getFocusedArea();
        long version = buffer.docVersion();
        long generation = ++navigationRequestGeneration;
        java.util.function.BooleanSupplier current = () -> generation == navigationRequestGeneration
                && buffer == host.activeBuffer()
                && version == buffer.docVersion();
        lspManager.definitionAt(lib.anchor(), lib.uri(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (!current.getAsBoolean()) {
                return;
            }
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noDefinition"));
                return;
            }
            LspManager.Target t = targets.get(0);
            if (t.file() != null) {
                noteLibraryTarget(lib.anchor(), t.file());
                ops.openAndGoto(t.file(), t.line(), t.character());
            } else {
                openLibraryDefinition(lib.anchor(), t, current);
            }
        });
    }

    /** Moves {@code buffer}'s caret to a 0-based line/column (clamped) and scrolls it into view. */
    private static void gotoInBuffer(EditorBuffer buffer, int line0, int col0) {
        CodeArea a = buffer.getFocusedArea();
        int par = Math.max(0, Math.min(line0, a.getParagraphs().size() - 1));
        int col = Math.max(0, Math.min(col0, a.getParagraph(par).length()));
        a.moveTo(par, col);
        a.requestFollowCaret();
        a.requestFocus();
    }

    void findReferences() {
        findReferences(false);
    }

    /**
     * Find References at the caret. The command jumps straight to a lone reference and only reports that
     * there are none; a code lens ({@code listAlways}) is a click on a count, so it always opens the
     * References tool window on what was counted — one row, or the empty list behind "0 references".
     */
    private void findReferences(boolean listAlways) {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        Path path = lspPath(b);
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        lspManager.references(path, area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (!navigationRequestCurrent(b, path, version, generation)) {
                return;
            }
            if (listAlways) {
                showInReferencesWindow(targets);
                return;
            }
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noReferences"));
                return;
            }
            if (targets.size() == 1) {
                LspManager.Target t = targets.get(0); // a lone reference: jump straight there (IDE behavior)
                ops.openAndGoto(t.file(), t.line(), t.character());
                return;
            }
            showInReferencesWindow(targets);
        });
    }

    /**
     * Go to Implementation ({@code lsp.gotoImplementation}, #735) — the concrete overrides of the member at
     * the caret. Result handling mirrors {@link #findReferences}: a lone implementation jumps straight there,
     * several fill the References tool window. Unlike references, a target can be a {@code jdt://} class file
     * (an interface implemented inside a dependency), so those route through the library-source path.
     */
    void gotoImplementation() {
        withNavRequest(
                "status.lsp.implementationUnsupported",
                lspManager::supportsImplementation,
                lspManager::implementation,
                (anchor, targets) -> {
                    if (targets.isEmpty()) {
                        host.setStatus(tr("status.lsp.noImplementations"));
                        return;
                    }
                    if (targets.size() == 1) {
                        openTarget(anchor, targets.get(0));
                        return;
                    }
                    // The References window is keyed by file; a library target has no path, so it can only be
                    // opened directly. Show the file-backed ones, and fall back to opening a library target
                    // when every implementation lives inside a dependency.
                    List<LspManager.Target> inFiles = new java.util.ArrayList<>(targets.size());
                    for (LspManager.Target t : targets) {
                        if (t.file() != null) {
                            inFiles.add(t);
                        }
                    }
                    if (inFiles.isEmpty()) {
                        openTarget(anchor, targets.get(0));
                    } else {
                        showInReferencesWindow(inFiles);
                    }
                });
    }

    /** Go to Type Definition ({@code lsp.gotoTypeDefinition}, #736) — from a symbol to its type's declaration. */
    void gotoTypeDefinition() {
        withNavRequest(
                "status.lsp.typeDefinitionUnsupported",
                lspManager::supportsTypeDefinition,
                lspManager::typeDefinition,
                (anchor, targets) -> openFirstTarget(anchor, targets, "status.lsp.noTypeDefinition"));
    }

    /** Go to Declaration ({@code lsp.gotoDeclaration}, #736). */
    void gotoDeclaration() {
        withNavRequest(
                "status.lsp.declarationUnsupported",
                lspManager::supportsDeclaration,
                lspManager::declaration,
                (anchor, targets) -> openFirstTarget(anchor, targets, "status.lsp.noDeclaration"));
    }

    /** A position-to-targets request on {@link LspManager} (implementation / type definition / declaration). */
    @FunctionalInterface
    private interface NavRequest {
        void run(Path file, int line, int character, Consumer<List<LspManager.Target>> cb);
    }

    /**
     * Shared preamble for the navigation commands: resolve the managed buffer, refuse (with a precise status)
     * when the server doesn't advertise the provider — rather than letting an empty result read as "nothing
     * found" — sync the latest text, then run the request at the caret.
     */
    private void withNavRequest(
            String unsupportedKey,
            java.util.function.Predicate<Path> supported,
            NavRequest request,
            java.util.function.BiConsumer<Path, List<LspManager.Target>> onTargets) {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lspPath(b);
        if (!supported.test(path)) {
            host.setStatus(tr(unsupportedKey));
            return;
        }
        CodeArea area = b.getFocusedArea();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        request.run(path, area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (navigationRequestCurrent(b, path, version, generation)) {
                onTargets.accept(path, targets);
            }
        });
    }

    private boolean navigationRequestCurrent(EditorBuffer buffer, Path path, long version, long requestGeneration) {
        return requestGeneration == navigationRequestGeneration
                && buffer == host.activeBuffer()
                && java.util.Objects.equals(path, lspPath(buffer))
                && version == buffer.docVersion();
    }

    /** Opens the first target, reporting {@code emptyKey} when there is none. */
    private void openFirstTarget(Path anchor, List<LspManager.Target> targets, String emptyKey) {
        if (targets.isEmpty()) {
            host.setStatus(tr(emptyKey));
            return;
        }
        openTarget(anchor, targets.get(0));
    }

    /** Opens a resolved target: a workspace file directly, a {@code jdt://} class file as read-only source. */
    private void openTarget(Path anchor, LspManager.Target t) {
        if (t.file() != null) {
            noteLibraryTarget(anchor, t.file());
            ops.openAndGoto(t.file(), t.line(), t.character());
        } else {
            EditorBuffer origin = host.activeBuffer();
            long version = origin == null ? -1 : origin.docVersion();
            long generation = navigationRequestGeneration;
            openLibraryDefinition(
                    anchor,
                    t,
                    () -> origin != null
                            && generation == navigationRequestGeneration
                            && origin == host.activeBuffer()
                            && java.util.Objects.equals(anchor, lspPath(origin))
                            && version == origin.docVersion());
        }
    }

    /** Fills the References tool window with {@code targets} (each must have a real path) and opens it. */
    private void showInReferencesWindow(List<LspManager.Target> targets) {
        List<String> previews = previewLines(targets, f -> {
            EditorBuffer buffer = bufferForLspPath(f);
            return buffer == null ? null : buffer.getContent();
        });
        List<ReferencesPanel.Reference> refs = new java.util.ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            LspManager.Target t = targets.get(i);
            refs.add(new ReferencesPanel.Reference(
                    t.file(), t.line(), t.character(), previews.get(i), previewRuns(t, previews.get(i))));
        }
        referencesPanel().setReferences(refs);
        ops.openReferencesWindow();
    }

    /**
     * The syntax-styled runs of a reference's preview, read from the open buffer's own highlighting so the
     * row is colored as the editor colors that line. Empty (a plain preview) when the file has no open tab or
     * the view's line is not the document's line (a narrowed buffer, a filtered log).
     */
    private List<ReferencesPanel.Run> previewRuns(LspManager.Target t, String preview) {
        EditorBuffer buffer = preview.isEmpty() ? null : bufferForLspPath(t.file());
        if (buffer == null) {
            return List.of();
        }
        try {
            CodeArea area = buffer.getArea();
            if (t.line() >= area.getParagraphs().size()) {
                return List.of();
            }
            String line = area.getParagraph(t.line()).getText();
            return line.strip().equals(preview)
                    ? ReferencesPanel.previewRuns(line, area.getStyleSpans(t.line()))
                    : List.of();
        } catch (RuntimeException e) {
            return List.of(); // the document changed under us; a plain preview is safe
        }
    }

    private static final String[] NO_LINES = new String[0];

    /**
     * One-line previews for each reference target, splitting <b>each file's content at most once</b> —
     * previously the per-target {@code previewLine} re-split the whole document for <em>every</em> target, so
     * 500 references into one open file did 500 full {@code getText()} walks + splits in a single FX pulse
     * (#471). {@code contentOf} maps a file to its open-buffer content (cheap, FX-safe, reflects unsaved
     * edits), or {@code null} for a file with no open tab (closed files show just the line number — no disk I/O
     * on the FX thread). Pure; unit-tested.
     */
    static List<String> previewLines(
            List<LspManager.Target> targets, java.util.function.Function<Path, String> contentOf) {
        java.util.Map<Path, String[]> byFile = new java.util.HashMap<>();
        List<String> out = new java.util.ArrayList<>(targets.size());
        for (LspManager.Target t : targets) {
            String[] lines = byFile.computeIfAbsent(t.file(), f -> {
                String content = contentOf.apply(f);
                return content == null ? NO_LINES : content.split("\n", -1);
            });
            out.add(t.line() >= 0 && t.line() < lines.length ? lines[t.line()].strip() : "");
        }
        return out;
    }

    /**
     * Opens the "Go to Symbol in Workspace" popup ({@code workspace/symbol}) — an incremental picker that
     * re-queries the active file's language server as you type, jumping to the chosen symbol across files.
     * Seeds the query from a single-line selection when present.
     */
    void gotoSymbolInWorkspace() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        if (!lspManager.supportsWorkspaceSymbols(lspPath(b))) {
            host.setStatus(tr("status.lsp.workspaceSymbolsUnsupported"));
            return;
        }
        Path anchor = lspPath(b);
        String sel = b.getFocusedArea().getSelectedText();
        String seed = sel != null && !sel.isBlank() && !sel.contains("\n") ? sel.trim() : "";
        WorkspaceSymbolPopup popup = new WorkspaceSymbolPopup(host.overlayHost(), new WorkspaceSymbolPopup.Ops() {
            @Override
            public void query(
                    String text, java.util.function.Consumer<java.util.List<LspManager.WorkspaceSymbolMatch>> cb) {
                lspManager.workspaceSymbols(anchor, text, cb);
            }

            @Override
            public void open(Path file, int line, int character) {
                ops.openAndGoto(file, line, character);
            }
        });
        popup.show(seed);
    }

    void showHover() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(lspPath(b), b.text()); // sync latest text before the request
        long generation = ++hoverGeneration;
        long version = b.docVersion();
        int caret = area.getCaretPosition();
        Path path = lspPath(b);
        lspManager.hover(path, area.getCurrentParagraph(), area.getCaretColumn(), text -> {
            if (b != host.activeBuffer()
                    || b.isDisposed()
                    || area != b.getFocusedArea()
                    || generation != hoverGeneration
                    || version != b.docVersion()
                    || caret != area.getCaretPosition()
                    || !path.equals(lspPath(b))) return;
            if (text == null || text.isBlank()) {
                host.setStatus(tr("status.lsp.noHover"));
            } else {
                showHoverPopup(area, text);
            }
        });
    }

    /** The indentation a formatting request describes for {@code buffer} (see {@link com.editora.lsp.FormattingIndent}). */
    private static com.editora.lsp.FormattingIndent formattingIndent(EditorBuffer buffer) {
        return com.editora.lsp.FormattingIndent.of(buffer.indentUnit(), buffer.getTabSize());
    }

    /** Reformats the whole active file via its language server ({@code textDocument/formatting}), if the
     *  server is running and advertises formatting. Edits apply through the undoable buffer. */
    void formatDocument() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || lspPath(buffer) == null || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        Path path = lspPath(buffer);
        if (!lspManager.isManaged(path) || !lspManager.supportsFormatting(path)) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        // The document's own indentation, not the global tab size: a formatter given the global width
        // re-indents every line of a file indented differently (shfmt -i=4 over a two-space script).
        com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
        host.setStatus(tr("status.lsp.formatting"));
        // Sync the latest text first (like gotoDefinition/findReferences/showHover): the didChange debounce
        // can leave the server's copy ~300 ms behind, and formatting the STALE server text yields edits whose
        // offsets don't line up with the document on screen (#667). Redundant when already current, but a
        // full-text didChange is cheap next to the formatting round-trip itself.
        lspManager.changeDocument(path, buffer.text());
        // The server computes whole-document edits (line/col based) against the text as it is NOW. If the
        // user edits during the async round-trip, those offsets no longer line up — and applyLspEdits only
        // clamps + swallows, so a stale format silently corrupts the file (every line mis-formatted/shifted).
        // Snapshot the text and drop the reply if it changed, mirroring tryLspReindentLine's line guard.
        String snapshot = buffer.getContent();
        lspManager.formatDocument(path, indent.tabSize(), indent.insertSpaces(), edits -> {
            if (buffer != host.activeBuffer()) {
                return; // user switched tabs before the server replied
            }
            if (!buffer.getContent().equals(snapshot)) {
                host.setStatus(tr("status.lsp.formatStale")); // edited mid-format — a re-run formats cleanly
                return;
            }
            if (edits.isEmpty()) {
                // bash-language-server answers "no edits" when shfmt is missing, too: say which it is.
                host.setStatus(
                        "bash".equals(lspManager.managedServerId(path)) && !bashToolFound("shfmt")
                                ? tr("status.lsp.bashNoShfmt")
                                : tr("status.lsp.formatNoChange"));
                return;
            }
            if (!NoUndoGuard.allow(buffer, tr("noUndo.op.lsp"))) {
                return;
            }
            buffer.applyLspEdits(edits);
            host.setStatus(tr("status.lsp.formatted"));
        });
    }

    /**
     * Code actions / quick fixes at the caret or selection ({@code lsp.codeActions}, #670): asks the server
     * (with the overlapping diagnostics as context), shows a picker — preferred actions first — and applies
     * the pick. An action with a deferred edit is resolved first; a command-style action executes server-side
     * and its edits come back through {@code workspace/applyEdit} (→ {@link #applyWorkspaceEdits}).
     */
    void codeActions() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lspPath(b);
        if (!lspManager.supportsCodeActions(path) || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.noCodeActions"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        var sel = area.getSelection();
        boolean hasSelection = sel.getLength() > 0;
        var start = area.offsetToPosition(
                hasSelection ? sel.getStart() : area.getCaretPosition(),
                org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        var end = hasSelection
                ? area.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                : start;
        lspManager.codeActions(path, start.getMajor(), start.getMinor(), end.getMajor(), end.getMinor(), items -> {
            if (b != host.activeBuffer()) {
                return; // switched tabs while the server was thinking
            }
            if (items.isEmpty()) {
                host.setStatus(tr("status.lsp.noCodeActions"));
                return;
            }
            // Anchored at the caret rather than a centred overlay card (#767): this acts on the symbol under
            // the cursor, which is where the user is looking. The editor keeps focus, so the caret stays
            // visible at the spot the fix will land while the list is open.
            b.showCodeActions(
                    items.stream()
                            .map(i -> new com.editora.editor.CodeAction(i.title(), i.kind(), i.preferred(), i.raw()))
                            .toList(),
                    chosen -> applyChosenAction(path, items, chosen));
        });
    }

    /**
     * Applies the action the user picked from the caret popup.
     *
     * <p>The popup deals in the neutral {@link com.editora.editor.CodeAction} — {@code editor} must not see
     * lsp4j — so the server's own object rides across as an opaque token and is matched back by identity
     * here. The lists are a handful of entries, so a scan is cheaper than building a map.
     */
    private void applyChosenAction(
            Path path, List<LspManager.CodeActionItem> items, com.editora.editor.CodeAction chosen) {
        if (chosen == null) {
            return;
        }
        LspManager.CodeActionItem item = null;
        for (LspManager.CodeActionItem candidate : items) {
            if (candidate.raw() == chosen.token()) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            return;
        }
        if (runGeneratePrompt(path, item)) {
            return; // a jdtls generate prompt: we drive it, not the server (#741)
        }
        if (runRefactorCommand(path, item)) {
            return; // likewise a refactoring that asks the user where to, or what
        }
        LspManager.CodeActionItem applied = item;
        beginReportedEdit();
        lspManager.applyCodeAction(
                path,
                applied,
                ok -> reportEdit(
                        ok,
                        tr("status.lsp.codeActionApplied", applied.title()),
                        tr("status.lsp.codeActionFailed", applied.title())));
    }

    /**
     * Applies a workspace edit's per-file batches through undoable buffers (FX thread; registered as the
     * manager's apply-edit handler — both a picked action's inline edit and a server-initiated
     * {@code workspace/applyEdit} land here). All-or-nothing: if any touched file can't be opened editable,
     * nothing is applied — half a refactoring corrupts the workspace. A file with no open tab opens in a
     * background tab so its change is visible and undoable.
     */
    /**
     * Rename the symbol under the caret (#676): validate via {@code prepareRename} when the server supports
     * it (jdtls does — it also supplies the placeholder), prompt pre-filled with the current name, then
     * {@code textDocument/rename} — whose workspace edit (including a class rename's {@code RenameFile})
     * applies through {@link #applyWorkspaceEdits}.
     */
    void rename() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lspPath(b);
        if (!lspManager.supportsRename(path) || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.noRename"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        int line = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        if (lspManager.supportsPrepareRename(path)) {
            lspManager.prepareRename(path, line, col, prep -> {
                if (!prep.allowed()) {
                    host.setStatus(tr("status.lsp.cannotRename"));
                    return;
                }
                String placeholder = !prep.placeholder().isBlank()
                        ? prep.placeholder()
                        : textInRange(b, prep.startLine(), prep.startCol(), prep.endLine(), prep.endCol());
                promptAndRename(b, path, line, col, placeholder.isBlank() ? wordAtCaret(area) : placeholder);
            });
        } else {
            promptAndRename(b, path, line, col, wordAtCaret(area));
        }
    }

    private void promptAndRename(EditorBuffer b, Path path, int line, int col, String placeholder) {
        host.promptText(tr("dialog.lsp.rename.title"), tr("dialog.lsp.rename.label"), placeholder, newName -> {
            String name = newName == null ? "" : newName.trim();
            if (name.isEmpty() || name.equals(placeholder)) {
                return; // nothing to do
            }
            host.setStatus(tr("status.lsp.renaming"));
            beginReportedEdit();
            lspManager.previewRename(path, line, col, name, mapped -> {
                if (mapped == null) {
                    reportEdit(false, "", tr("status.lsp.renameFailed", name));
                    return;
                }
                withDiskView(resourcePaths(mapped), files -> {
                    if (!com.editora.lsp.RenamePreview.worthPreviewing(mapped, workspaceDisk(files))) {
                        // Confined to this file: visible on screen and one undo away, so a confirmation step
                        // here would be friction with nothing to confirm.
                        applyRename(mapped, name);
                        return;
                    }
                    previewThenApply(mapped, name, files);
                });
            });
        });
    }

    /**
     * Shows which files a rename would change and applies only the ones left ticked.
     *
     * <p>A rename edits files the user cannot see, and every desktop IDE treats showing them first as what
     * makes refactoring trustworthy. Reuses {@link MultiSelectPicker} — the same checkbox card the jdtls
     * generators use — rather than a bespoke preview pane; a per-file choice is what the filtering can
     * honour safely, and a per-hunk one would need the diff viewer and a much larger change.
     */
    private void previewThenApply(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, String name, WorkspaceFileOperations files) {
        java.util.List<MultiSelectPicker.Item<java.nio.file.Path>> rows = new java.util.ArrayList<>();
        java.util.Set<Path> listed = new java.util.LinkedHashSet<>();
        for (com.editora.lsp.RenamePreview.FileChange change :
                com.editora.lsp.RenamePreview.summarise(mapped, workspaceDisk(files))) {
            rows.add(new MultiSelectPicker.Item<>(previewLabel(change), true, change.file()));
            listed.add(change.file());
        }
        MultiSelectPicker.show(
                host.overlayHost(),
                tr("lsp.rename.previewTitle", name, com.editora.lsp.RenamePreview.totalEdits(mapped), rows.size()),
                rows,
                keep -> applyPreviewed(mapped, new java.util.LinkedHashSet<>(keep), listed, name));
    }

    /** One preview row: the path, then what happens to it — deletes and replacements spelled out. */
    String previewLabel(com.editora.lsp.RenamePreview.FileChange change) {
        String label = ops.homeCollapsed(change.file().toString());
        if (change.edits() > 0) {
            label += "  ·  " + tr("lsp.rename.editCount", change.edits());
        }
        if (change.renamedTo() != null) {
            label += "  ·  " + tr("lsp.rename.movesTo", change.renamedTo().getFileName());
        }
        if (change.overwrites()) {
            label += "  ·  " + tr("lsp.rename.overwrites");
        }
        if (change.deletion() != null) {
            label += "  ·  "
                    + tr(
                            change.deletion() == com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY
                                    ? "lsp.rename.deletesFolder"
                                    : "lsp.rename.deletesFile");
        }
        return label;
    }

    /**
     * Applies what the user left ticked. Refused when a delete that is still ticked would remove a path
     * the user unticked — a file kept in place inside a folder the edit deletes.
     */
    void applyPreviewed(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.Set<Path> keep,
            java.util.Set<Path> listed,
            String name) {
        com.editora.lsp.WorkspaceEditMapper.Mapped filtered =
                com.editora.lsp.RenamePreview.filter(mapped, keep, listed);
        List<Path> covered = com.editora.lsp.RenamePreview.excludedButDeleted(filtered, keep, listed);
        if (!covered.isEmpty()) {
            beginReportedEdit();
            editRefused(covered, tr("status.lsp.renameDeleteCoversKept", blockedFileNames(covered)));
            reportEdit(false, "", tr("status.lsp.renameFailed", name));
            return;
        }
        applyRename(filtered, name);
    }

    /** The filesystem as the workspace-edit transaction sees it, for classifying destructive operations. */
    private com.editora.lsp.WorkspaceEditHazards.Disk workspaceDisk() {
        return workspaceDisk(workspaceFiles);
    }

    private static com.editora.lsp.WorkspaceEditHazards.Disk workspaceDisk(WorkspaceFileOperations files) {
        return new com.editora.lsp.WorkspaceEditHazards.Disk() {
            @Override
            public boolean exists(Path path) {
                return files.exists(path);
            }

            @Override
            public boolean isDirectory(Path path) {
                return files.isDirectory(path);
            }
        };
    }

    /**
     * Runs {@code next} on the FX thread with a view of the disk for {@code paths}: the live disk when they are
     * all local (a stat there is cheap, and the check stays in this FX turn), else a {@link WorkspaceFileSnapshot}
     * taken on the workspace worker first — on a remote host every stat is a round trip, and a rename touching
     * a few dozen files froze the window for the length of them.
     */
    private void withDiskView(
            java.util.Collection<Path> paths, java.util.function.Consumer<WorkspaceFileOperations> next) {
        if (!WorkspaceFileSnapshot.anyRemote(paths)) {
            next.accept(workspaceFiles);
            return;
        }
        workspaceExecutor.execute(() -> {
            WorkspaceFileSnapshot snapshot = WorkspaceFileSnapshot.of(workspaceFiles, paths);
            Platform.runLater(() -> next.accept(snapshot));
        });
    }

    /** Every path {@code mapped}'s create, rename and delete operations name. */
    private static List<Path> resourcePaths(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        List<Path> out = new java.util.ArrayList<>();
        mapped.creates().forEach(c -> out.add(c.file()));
        mapped.renames().forEach(r -> {
            out.add(r.from());
            out.add(r.to());
        });
        mapped.deletes().forEach(d -> out.add(d.file()));
        return out;
    }

    /** Where {@link #staleDiskTargets} will look: each disk-preimage target, at the place a staged rename put it. */
    private static List<Path> preimagePaths(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, Map<Path, Path> movedTo) {
        List<Path> out = new java.util.ArrayList<>();
        for (var edit : mapped.edits()) {
            if (edit.diskPreimageAt() != null) {
                out.add(movedTo.getOrDefault(edit.file().toAbsolutePath().normalize(), edit.file()));
            }
        }
        return out;
    }

    /** Applies a (possibly filtered) rename edit and reports the outcome. */
    private void applyRename(com.editora.lsp.WorkspaceEditMapper.Mapped mapped, String name) {
        beginReportedEdit();
        applyWorkspaceEditsAsync(
                mapped, ok -> reportEdit(ok, tr("status.lsp.renamed", name), tr("status.lsp.renameFailed", name)));
    }

    /** The files that made the workspace edit in progress unsafe to apply; consumed by {@link #reportEdit}. */
    private List<Path> blockedTargets = List.of();

    /**
     * Starts an operation whose outcome {@link #reportEdit} will announce. The blocked-files note belongs to
     * one operation: a server-initiated edit that was blocked earlier has no {@code reportEdit} of its own
     * to consume it, and left standing it silenced the failure message of the next, unrelated operation.
     */
    void beginReportedEdit() {
        blockedTargets = List.of();
    }

    /**
     * A workspace edit was refused because of specific files — changed since the request, unsaved, or not
     * loaded — rather than for a reason nobody can act on. Names them, so "Rename failed" is not all the
     * user gets when the fix is to save or reload one file.
     */
    void editBlocked(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        blockedTargets = List.copyOf(files);
        host.setError(tr("status.lsp.editBlocked", blockedFileNames(files)));
    }

    /** As {@link #editBlocked}, for a refusal with a reason of its own to give. */
    private void editRefused(List<Path> files, String message) {
        blockedTargets = files == null || files.isEmpty() ? List.of(Path.of("")) : List.copyOf(files);
        host.setError(message);
    }

    /** Pure: up to three file names, then a count of the rest. */
    static String blockedFileNames(List<Path> files) {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(3, files.size());
        for (int i = 0; i < shown; i++) {
            Path name = files.get(i).getFileName();
            names.append(i == 0 ? "" : ", ").append(name == null ? files.get(i) : name);
        }
        if (files.size() > shown) {
            names.append(" +").append(files.size() - shown);
        }
        return names.toString();
    }

    /** Reports an edit's outcome. A failure whose blocking files were already named keeps that message. */
    void reportEdit(boolean ok, String success, String failure) {
        boolean named = !blockedTargets.isEmpty();
        blockedTargets = List.of();
        if (ok) {
            host.setStatus(success);
        } else if (!named) {
            host.setStatus(failure);
        }
    }

    /**
     * Whether an edit to a file the server did not have open may be applied to {@code buffer}. The server
     * computed it from the file on disk, so three things must hold: no window has unsaved changes to the
     * file, the buffer mirrors the file as it is on disk now (its snapshot was taken by a load or a save and
     * still matches), and the file has not been modified since the request was sent. Anything that cannot
     * be shown refuses the edit.
     */
    private boolean diskPreimageHolds(
            com.editora.lsp.WorkspaceEditMapper.FileEdit edit,
            EditorBuffer buffer,
            Path onDiskAt,
            WorkspaceFileOperations files) {
        Long since = edit.diskPreimageAt();
        if (since == null) {
            return true;
        }
        if (buffer.isDirty() || ops.buffersAtOrUnder(edit.file()).stream().anyMatch(EditorBuffer::isDirty)) {
            return false;
        }
        try {
            WorkspaceFileIdentity onDisk =
                    files.identity(onDiskAt.toAbsolutePath().normalize());
            EditorBuffer.DiskSnapshot loaded = buffer.diskSnapshot();
            // The content fingerprint is recorded only by a load or a save — the two moments the buffer's
            // text is known to be the file's. Answering "changed on disk" with Keep re-baselines the time
            // and size without either, leaving a clean buffer that holds different text from the file; the
            // snapshot then matched the disk and the server's edit landed at the wrong offsets.
            return loaded.modifiedMillis() >= 0
                    && loaded.fingerprint() != null
                    && !loaded.differsFrom(onDisk.lastModifiedMillis(), onDisk.size())
                    && com.editora.lsp.WorkspaceEditMapper.unchangedSince(onDiskAt, onDisk.lastModifiedMillis(), since);
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * The disk-preimage targets of {@code mapped} that no longer hold, for {@link #editBlocked}.
     *
     * <p>{@code movedTo} is where the staged renames have <b>already put</b> each rename source (normalized
     * source → destination; empty before staging). A target the same edit also moves is no longer at its
     * old path, so it is examined where it now is: a move keeps size and modification time, which is all
     * the comparison reads. Looking at the old path instead refused every class rename requested from a
     * usage site, because the declaring file had just been moved by this very edit.
     *
     * <p>{@code skipUnloaded} leaves out targets with no buffer yet — the files this edit creates, which are
     * loaded only after staging and are checked by the pass that follows it.
     */
    private List<Path> staleDiskTargets(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            Map<Path, Path> movedTo,
            boolean skipUnloaded,
            WorkspaceFileOperations files) {
        List<Path> stale = new java.util.ArrayList<>();
        for (int i = 0; i < mapped.edits().size() && i < buffers.size(); i++) {
            var edit = mapped.edits().get(i);
            EditorBuffer buffer = buffers.get(i);
            if (edit.diskPreimageAt() == null || (buffer == null && skipUnloaded)) {
                continue;
            }
            Path onDiskAt = movedTo.getOrDefault(edit.file().toAbsolutePath().normalize(), edit.file());
            if (buffer == null || !diskPreimageHolds(edit, buffer, onDiskAt, files)) {
                stale.add(edit.file());
            }
        }
        return stale;
    }

    /** Normalized rename source → destination for the renames that are staged on disk. */
    private static Map<Path, Path> movedTo(java.util.List<StagedRename> renames) {
        Map<Path, Path> moved = new java.util.HashMap<>();
        for (StagedRename item : renames) {
            moved.put(
                    item.rename().from().toAbsolutePath().normalize(),
                    item.rename().to().toAbsolutePath().normalize());
        }
        return moved;
    }

    /**
     * The open buffer for a path a workspace edit is about to move or delete, with the buffer's <b>own</b>
     * path — resolved while the file still exists. Afterwards the server's spelling of the old path can no
     * longer be canonicalised, so under a symlinked project (jdtls answers with the resolved path) looking the
     * tab up again by that spelling finds nothing and the tab is left behind on a file that is gone.
     */
    private record OpenTarget(EditorBuffer buffer, Path path) {}

    private Map<Path, OpenTarget> openTargets(java.util.stream.Stream<Path> files) {
        Map<Path, OpenTarget> open = new java.util.HashMap<>();
        files.forEach(file -> {
            EditorBuffer buffer = bufferForLspPath(file);
            if (buffer != null && lspPath(buffer) != null) {
                open.put(file.toAbsolutePath().normalize(), new OpenTarget(buffer, lspPath(buffer)));
            }
        });
        return open;
    }

    private Map<Path, OpenTarget> openResourceTargets(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        return openTargets(java.util.stream.Stream.concat(
                mapped.renames().stream().map(com.editora.lsp.WorkspaceEditMapper.FileRename::from),
                mapped.deletes().stream().map(deletion -> deletion.file())));
    }

    /**
     * Pure: {@code serverTo} written the way the tab spells its path. {@code tabFrom} and {@code serverFrom}
     * name the same file; the trailing names they share are stripped to find where the two spellings part
     * ({@code ~/dev/app} against {@code /mnt/data/dev/app}), and a destination under the server's side is
     * re-rooted on the tab's, so the renamed tab stays under the project and LSP root it was opened in.
     */
    static Path inSpellingOf(Path tabFrom, Path serverFrom, Path serverTo) {
        Path tab = tabFrom.toAbsolutePath().normalize();
        Path server = serverFrom.toAbsolutePath().normalize();
        Path to = serverTo.toAbsolutePath().normalize();
        if (tab.equals(server)) {
            return serverTo;
        }
        while (tab.getParent() != null
                && server.getParent() != null
                && java.util.Objects.equals(tab.getFileName(), server.getFileName())) {
            tab = tab.getParent();
            server = server.getParent();
        }
        return to.startsWith(server) ? tab.resolve(server.relativize(to).toString()) : serverTo;
    }

    /** Whether every moved-or-deleted file that had a tab still has that same tab, at the same path. */
    private boolean openTargetsCurrent(Map<Path, OpenTarget> targets) {
        for (OpenTarget target : targets.values()) {
            EditorBuffer buffer = target.buffer();
            if (buffer.isDisposed()
                    || !target.path().equals(lspPath(buffer))
                    || bufferForLspPath(target.path()) != buffer) {
                return false;
            }
        }
        return true;
    }

    /**
     * Moves each renamed file's open tab to the new path, by buffer identity, and retires the old LSP
     * document under the path it was opened with. Returns the tabs that did <b>not</b> follow their file —
     * the caller must not report success for those.
     */
    private List<Path> remapRenamedBuffers(java.util.List<StagedRename> renames, Map<Path, OpenTarget> targets) {
        List<Path> orphaned = new java.util.ArrayList<>();
        for (StagedRename item : renames) {
            var rename = item.rename();
            OpenTarget target = targets.get(rename.from().toAbsolutePath().normalize());
            EditorBuffer open = target == null ? null : target.buffer();
            Path from = target == null ? rename.from() : target.path();
            Path to = target == null ? rename.to() : inSpellingOf(from, rename.from(), rename.to());
            if (lspManager.isManaged(from)) {
                lspManager.closeDocument(from); // didClose the OLD uri before the buffer re-opens as new
            }
            clearDiagnostics(from);
            clearDiagnostics(rename.from());
            ops.fileRenamed(from, to); // remaps the open buffer's path + tab + session state
            if (open != null) {
                if (from.equals(lspPath(open))) {
                    orphaned.add(from);
                } else {
                    syncBufferWhenShown(open); // re-open the document under its NEW uri
                }
            }
        }
        return orphaned;
    }

    /** Closes the LSP document and the tab of each deleted file, under the path the tab was opened with. */
    private void retireDeleted(java.util.List<StagedDelete> deletes, Map<Path, OpenTarget> targets) {
        for (StagedDelete item : deletes) {
            Path file = item.operation().file();
            OpenTarget target = targets.get(file.toAbsolutePath().normalize());
            Path opened = target == null ? file : target.path();
            if (lspManager.isManaged(opened)) {
                lspManager.closeDocument(opened);
            }
            clearDiagnostics(opened);
            clearDiagnostics(file);
            ops.fileDeleted(opened);
        }
    }

    /** A file was moved but its open tab could not follow: say which, instead of reporting success. */
    private void tabsNotRemapped(List<Path> files) {
        blockedTargets = List.copyOf(files);
        host.setError(tr("status.lsp.editTabNotRemapped", blockedFileNames(files)));
    }

    /** The document text inside a 0-based LSP range (single-line expected), or "" when out of bounds. */
    private static String textInRange(EditorBuffer b, int sl, int sc, int el, int ec) {
        try {
            CodeArea a = b.getFocusedArea();
            int from = a.getAbsolutePosition(sl, sc);
            int to = a.getAbsolutePosition(el, ec);
            return to > from ? a.getText(from, to) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The identifier run around the caret (the no-prepare fallback placeholder), or "". */
    static String wordAt(String line, int col) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int c = Math.max(0, Math.min(col, line.length()));
        int start = c;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        int end = c;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
            end++;
        }
        return end > start ? line.substring(start, end) : "";
    }

    private static String wordAtCaret(CodeArea area) {
        return wordAt(area.getParagraph(area.getCurrentParagraph()).getText(), area.getCaretColumn());
    }

    /** Atomically retires an old URI and registers the buffer under its current path. */
    void documentPathChanged(EditorBuffer buffer, Path oldPath, boolean oldAlreadyClosed) {
        if (oldPath != null) {
            if (!oldAlreadyClosed && lspManager.isManaged(oldPath)) {
                lspManager.closeDocument(oldPath);
            }
            clearDiagnostics(oldPath);
        }
        if (buffer != null && !buffer.isDisposed() && lspPath(buffer) != null) {
            syncBufferWhenShown(buffer);
        }
    }

    /** Asks before an unpreviewed edit destroys something on disk; a field so a test can answer. */
    java.util.function.Predicate<List<com.editora.lsp.WorkspaceEditHazards.Hazard>> destructiveEditConfirmer =
            this::confirmDestructiveEdit;

    private boolean confirmDestructiveEdit(List<com.editora.lsp.WorkspaceEditHazards.Hazard> hazards) {
        StringBuilder lines = new StringBuilder();
        for (var hazard : hazards) {
            lines.append(lines.isEmpty() ? "" : "\n")
                    .append(tr(
                            hazard.kind() == com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY
                                    ? "dialog.lsp.destructiveEdit.deleteFolder"
                                    : "dialog.lsp.destructiveEdit.overwrite",
                            ops.homeCollapsed(hazard.path().toString())));
        }
        javafx.scene.control.ButtonType apply = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.destructiveEdit.apply"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING,
                lines.toString(),
                javafx.scene.control.ButtonType.CANCEL,
                apply);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.destructiveEdit.title"));
        confirm.setHeaderText(tr("dialog.lsp.destructiveEdit.header"));
        confirm.getDialogPane().setMinWidth(520);
        // Enter must not confirm a deletion: Cancel is the default button.
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(apply)).setDefaultButton(false);
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL))
                .setDefaultButton(true);
        return confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == apply;
    }

    /**
     * The applier for edits nobody previewed — a code action, a refactoring command, a server's
     * {@code workspace/applyEdit}. One that deletes a folder with its contents or replaces an existing
     * file is applied only after the user has seen the paths and agreed (a rename shows them as preview
     * rows instead).
     */
    void applyWorkspaceEditsConfirmed(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> done) {
        withDiskView(resourcePaths(mapped), files -> {
            List<com.editora.lsp.WorkspaceEditHazards.Hazard> hazards =
                    com.editora.lsp.WorkspaceEditHazards.needingConfirmation(mapped, workspaceDisk(files));
            if (!hazards.isEmpty() && !destructiveEditConfirmer.test(hazards)) {
                editRefused(
                        hazards.stream()
                                .map(com.editora.lsp.WorkspaceEditHazards.Hazard::path)
                                .toList(),
                        tr("status.lsp.destructiveEditDeclined"));
                done.accept(false);
                return;
            }
            applyWorkspaceEditsAsync(mapped, done);
        });
    }

    /** Most files of one deleted folder that are copied to Local History; a larger folder is not walked further. */
    static final int MAX_CAPTURED_PER_EDIT = 200;

    /**
     * Copies what the edit is about to delete or replace into Local History, then continues on the FX
     * thread with whether every copy was made. Runs before staging, while each file is still at the path
     * its history belongs to. A folder is listed off the FX thread; an edit with nothing to lose continues
     * at once.
     */
    private void captureBeforeDestruction(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> done) {
        withDiskView(resourcePaths(mapped), disk -> captureBeforeDestruction(mapped, disk, done));
    }

    private void captureBeforeDestruction(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            WorkspaceFileOperations disk,
            java.util.function.Consumer<Boolean> done) {
        List<com.editora.lsp.WorkspaceEditHazards.Hazard> hazards =
                com.editora.lsp.WorkspaceEditHazards.of(mapped, workspaceDisk(disk));
        if (hazards.isEmpty()) {
            done.accept(true);
            return;
        }
        boolean folders =
                hazards.stream().anyMatch(h -> h.kind() == com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY);
        if (!folders) {
            captureEach(
                    hazards.stream()
                            .map(com.editora.lsp.WorkspaceEditHazards.Hazard::path)
                            .toList(),
                    0,
                    done);
            return;
        }
        workspaceExecutor.execute(() -> {
            List<Path> files = new java.util.ArrayList<>();
            for (var hazard : hazards) {
                if (hazard.kind() != com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY) {
                    files.add(hazard.path());
                    continue;
                }
                try {
                    for (Path path : workspaceFiles.walk(hazard.path())) {
                        if (files.size() >= MAX_CAPTURED_PER_EDIT) {
                            break;
                        }
                        if (workspaceFiles.isRegularFile(path)) {
                            files.add(path);
                        }
                    }
                } catch (java.io.IOException | RuntimeException unreadable) {
                    // Staging will fail on the same folder and refuse the edit; nothing to copy from here.
                }
            }
            Platform.runLater(() -> captureEach(files, 0, done));
        });
    }

    private void captureEach(List<Path> files, int index, java.util.function.Consumer<Boolean> done) {
        if (index >= files.size()) {
            done.accept(true);
            return;
        }
        Path file = files.get(index);
        ops.captureBeforeDestruction(file, kept -> {
            if (!kept) {
                editRefused(List.of(file), tr("status.lsp.editHistoryFailed", blockedFileNames(List.of(file))));
                done.accept(false);
                return;
            }
            captureEach(files, index + 1, done);
        });
    }

    /**
     * Production workspace-edit path. Unopened files are decoded through the host's background loader and
     * create/move/delete operations run on a virtual thread; only buffer validation, RichTextFX edits and UI
     * bookkeeping return to the FX thread.
     */
    void applyWorkspaceEditsAsync(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> done) {
        if (!resourceTargetsSafe(mapped)) {
            resourceTargetsBlocked(mapped);
            done.accept(false);
            return;
        }
        invalidateResourceWrites(mapped);
        java.util.List<EditorBuffer> buffers =
                new java.util.ArrayList<>(mapped.edits().size());
        java.util.Set<Path> createdPaths = mapped.creates().stream()
                .map(c -> c.file().toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        collectWorkspaceBuffers(
                mapped,
                createdPaths,
                0,
                buffers,
                () -> withDiskView(preimagePaths(mapped, Map.of()), files -> {
                    // Before anything is staged: a target the server did not have open must still be the file
                    // it read. Afterwards the same question is asked again where a staged rename moved it.
                    List<Path> stale = staleDiskTargets(mapped, buffers, Map.of(), true, files);
                    if (!stale.isEmpty()) {
                        editBlocked(stale);
                        done.accept(false);
                        return;
                    }
                    Map<Path, OpenTarget> targets = openResourceTargets(mapped); // while the files still exist
                    captureBeforeDestruction(mapped, captured -> {
                        if (captured) {
                            stageWorkspaceEdit(mapped, buffers, targets, done);
                        } else {
                            done.accept(false);
                        }
                    });
                }));
    }

    /** Every path {@link #finishWorkspaceEdit}'s checks read. */
    private List<Path> finishPaths(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes) {
        List<Path> out = new java.util.ArrayList<>();
        creates.forEach(item -> out.add(item.operation().file()));
        renames.forEach(item -> {
            out.add(item.rename().from());
            out.add(item.rename().to());
        });
        deletes.forEach(item -> {
            out.add(item.operation().file());
            out.add(item.stage());
        });
        out.addAll(preimagePaths(mapped, movedTo(renames)));
        return out;
    }

    /** Stages the edit's filesystem operations off the FX thread, then finishes (or rolls back) on it. */
    private void stageWorkspaceEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            Map<Path, OpenTarget> targets,
            java.util.function.Consumer<Boolean> done) {
        workspaceExecutor.execute(() -> {
            WorkspaceTransactionStatus transaction = beginTransaction(mapped);
            java.util.List<StagedCreate> creates = stageCreates(mapped.creates(), transaction);
            java.util.List<StagedRename> renames = creates == null ? null : stageRenames(mapped.renames(), transaction);
            java.util.List<StagedDelete> deletes =
                    creates == null || renames == null ? null : stageDeletes(mapped.deletes(), transaction);
            if (creates == null || renames == null || deletes == null) {
                if (renames != null) {
                    rollbackRenames(renames, transaction);
                }
                if (creates != null) {
                    rollbackCreates(creates, transaction);
                }
                endTransaction(transaction);
                Platform.runLater(() -> {
                    reportIncompleteRollback(transaction);
                    done.accept(false);
                });
                return;
            }
            // The finish step checks the staged files and the preimages where the renames put them, on the FX
            // thread: for remote files those stats are taken here, on the worker (see withDiskView).
            List<Path> checked = finishPaths(mapped, creates, renames, deletes);
            WorkspaceFileOperations files = WorkspaceFileSnapshot.anyRemote(checked)
                    ? WorkspaceFileSnapshot.of(workspaceFiles, checked)
                    : workspaceFiles;
            Platform.runLater(() -> collectCreatedWorkspaceBuffers(
                    mapped,
                    buffers,
                    0,
                    () -> finishWorkspaceEdit(
                            mapped, buffers, creates, renames, deletes, targets, transaction, files, done)));
        });
    }

    private void invalidateResourceWrites(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        java.util.Set<Path> paths = new java.util.LinkedHashSet<>();
        mapped.creates().forEach(operation -> paths.add(operation.file()));
        mapped.renames().forEach(operation -> {
            paths.add(operation.from());
            paths.add(operation.to());
        });
        mapped.deletes().forEach(operation -> paths.add(operation.file()));
        paths.forEach(ops::invalidatePendingWrite);
    }

    private void collectWorkspaceBuffers(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.Set<Path> createdPaths,
            int index,
            java.util.List<EditorBuffer> buffers,
            Runnable done) {
        if (index >= mapped.edits().size()) {
            done.run();
            return;
        }
        var edit = mapped.edits().get(index);
        EditorBuffer open = bufferForLspPath(edit.file());
        if (open != null || createdPaths.contains(edit.file().toAbsolutePath().normalize())) {
            buffers.add(open);
            collectWorkspaceBuffers(mapped, createdPaths, index + 1, buffers, done);
            return;
        }
        ops.openBackgroundBufferAsync(edit.file(), buffer -> {
            buffers.add(buffer);
            collectWorkspaceBuffers(mapped, createdPaths, index + 1, buffers, done);
        });
    }

    private void collectCreatedWorkspaceBuffers(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            int index,
            Runnable done) {
        if (index >= buffers.size()) {
            done.run();
            return;
        }
        if (buffers.get(index) != null) {
            collectCreatedWorkspaceBuffers(mapped, buffers, index + 1, done);
            return;
        }
        int slot = index;
        ops.openBackgroundBufferAsync(mapped.edits().get(index).file(), buffer -> {
            buffers.set(slot, buffer);
            collectCreatedWorkspaceBuffers(mapped, buffers, slot + 1, done);
        });
    }

    /** Package-private so {@code LspWorkspaceEditFxTest} can drive it: this is the only LSP path that
     *  writes and MOVES files on disk, so its all-or-nothing refusals need direct tests. */
    boolean applyWorkspaceEdits(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        if (!resourceTargetsSafe(mapped)) {
            resourceTargetsBlocked(mapped);
            return false;
        }
        invalidateResourceWrites(mapped);
        WorkspaceTransactionStatus transaction = beginTransaction(mapped);
        boolean applied = applyWorkspaceEdits(mapped, transaction);
        endTransaction(transaction);
        return applied;
    }

    private boolean applyWorkspaceEdits(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, WorkspaceTransactionStatus transaction) {
        var files = mapped.edits();
        java.util.List<StagedCreate> creates = stageCreates(mapped.creates(), transaction);
        if (creates == null) {
            reportIncompleteRollback(transaction);
            return false;
        }
        java.util.List<EditorBuffer> buffers = new java.util.ArrayList<>(files.size());
        for (var fe : files) {
            EditorBuffer buf = bufferForLspPath(fe.file());
            if (buf == null) {
                buf = ops.openBackgroundBuffer(fe.file());
            }
            if (buf == null || !buf.isEditable() || buf.isNarrowed()) {
                rollbackCreates(creates, transaction);
                reportIncompleteRollback(transaction);
                return false;
            }
            // A versioned edit is valid only for the exact server document it names. JDT LS commonly sends
            // a null version, so request-time text is retained as the equivalent guard for those edits.
            if (fe.version() != null
                    && !java.util.Objects.equals(fe.version(), lspManager.documentVersion(fe.file()))) {
                rollbackCreates(creates, transaction);
                reportIncompleteRollback(transaction);
                return false;
            }
            if (fe.expectedText() != null && !fe.expectedText().equals(buf.getContent())) {
                rollbackCreates(creates, transaction);
                reportIncompleteRollback(transaction);
                return false;
            }
            buffers.add(buf);
        }
        List<Path> stale = staleDiskTargets(mapped, buffers, Map.of(), false, workspaceFiles);
        if (!stale.isEmpty()) {
            editBlocked(stale);
            rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        Map<Path, OpenTarget> targets = openResourceTargets(mapped); // while the files still exist
        java.util.List<StagedRename> staged = stageRenames(mapped.renames(), transaction);
        if (staged == null) {
            rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        java.util.List<StagedDelete> deletes = stageDeletes(mapped.deletes(), transaction);
        if (deletes == null) {
            rollbackRenames(staged, transaction);
            rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        boolean safe = resourceTargetsSafe(mapped);
        List<Path> unplaceable = safe ? unplaceableTargets(mapped, buffers) : List.of();
        if (!resourceStateCurrent(workspaceFiles, creates, staged, deletes) || !safe || !unplaceable.isEmpty()) {
            if (!safe) {
                resourceTargetsBlocked(mapped);
            }
            editUnplaceable(unplaceable);
            rollbackDeletes(deletes, transaction);
            rollbackRenames(staged, transaction);
            rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        if (!applyWorkspaceTextEdits(mapped, buffers, transaction)) {
            rollbackDeletes(deletes, transaction);
            rollbackRenames(staged, transaction);
            rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        // The filesystem transaction completed before any text changed. Now remap open buffers/session state
        // in protocol order; this part is in-memory and cannot leave a failed disk move behind.
        transaction.journal.committed();
        List<Path> orphaned = remapRenamedBuffers(staged, targets);
        for (StagedCreate created : creates) {
            ops.fileCreated(created.operation().file());
        }
        retireDeleted(deletes, targets);
        commitRenames(staged);
        commitCreates(creates);
        commitDeletes(deletes);
        if (!orphaned.isEmpty()) {
            tabsNotRemapped(orphaned);
        }
        return orphaned.isEmpty();
    }

    private void finishWorkspaceEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes,
            Map<Path, OpenTarget> targets,
            WorkspaceTransactionStatus transaction,
            WorkspaceFileOperations files,
            java.util.function.Consumer<Boolean> done) {
        // Supersede saves started while the resource transaction was staging, before UI identity changes.
        invalidateResourceWrites(mapped);
        if (!resourceStateCurrent(files, creates, renames, deletes) || !openTargetsCurrent(targets)) {
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        // The preflight ran before files were loaded and staged, several FX turns ago: a keystroke since
        // then — in any window — may have dirtied a buffer this edit is about to close with its file.
        // Asked again here, in the same FX turn that retires the tabs, nothing can slip in between.
        if (!resourceTargetsSafe(mapped)) {
            resourceTargetsBlocked(mapped);
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        Map<Path, Path> movedTo = movedTo(renames);
        List<Path> stale = staleDiskTargets(mapped, buffers, movedTo, false, files);
        if (!stale.isEmpty()) {
            editBlocked(stale);
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        for (int i = 0; i < mapped.edits().size(); i++) {
            var edit = mapped.edits().get(i);
            EditorBuffer buffer = buffers.get(i);
            // A file this edit has just moved is gone from its old path, where the server's spelling can
            // no longer be resolved to the tab's: ask by the tab's own path, pinned before the move.
            OpenTarget moved = targets.get(edit.file().toAbsolutePath().normalize());
            boolean relocated = movedTo.containsKey(edit.file().toAbsolutePath().normalize());
            if (buffer == null
                    || buffer.isDisposed()
                    || (relocated ? moved == null || moved.buffer() != buffer : bufferForLspPath(edit.file()) != buffer)
                    || !buffer.isEditable()
                    || buffer.isNarrowed()
                    || (edit.version() != null
                            && !java.util.Objects.equals(edit.version(), lspManager.documentVersion(edit.file())))
                    || (edit.expectedText() != null && !edit.expectedText().equals(buffer.getContent()))) {
                rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
                return;
            }
        }
        List<Path> unplaceable = unplaceableTargets(mapped, buffers);
        if (!unplaceable.isEmpty()) {
            editUnplaceable(unplaceable); // before any buffer changes: all of the edit lands, or none of it
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        // Buffers without undo (large-file mode) keep a Local History copy first; one refusal stops the edit.
        for (EditorBuffer buffer : new java.util.LinkedHashSet<>(buffers)) {
            if (!NoUndoGuard.allow(buffer, tr("noUndo.op.lsp"))) {
                rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
                return;
            }
        }
        if (!applyWorkspaceTextEdits(mapped, buffers, transaction)) {
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        transaction.journal.committed();
        List<Path> orphaned = remapRenamedBuffers(renames, targets);
        creates.forEach(created -> ops.fileCreated(created.operation().file()));
        retireDeleted(deletes, targets);
        workspaceExecutor.execute(() -> {
            commitRenames(renames);
            commitCreates(creates);
            commitDeletes(deletes);
            transaction.journal.close();
        });
        if (!orphaned.isEmpty()) {
            tabsNotRemapped(orphaned); // the text is edited and the file moved; never call that "applied"
        }
        done.accept(orphaned.isEmpty());
    }

    /** Protects dirty delete victims and every live owner of an overwritten destination. */
    private boolean resourceTargetsSafe(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        for (var deletion : mapped.deletes()) {
            if (ops.buffersAtOrUnder(deletion.file()).stream().anyMatch(EditorBuffer::isDirty)) {
                return false;
            }
        }
        for (var creation : mapped.creates()) {
            if (creation.overwrite() && !ops.buffersAtOrUnder(creation.file()).isEmpty()) {
                return false;
            }
        }
        java.util.Set<String> sources = mapped.renames().stream()
                .map(rename -> com.editora.config.PathKeys.key(rename.from()))
                .collect(java.util.stream.Collectors.toSet());
        for (var rename : mapped.renames()) {
            if (com.editora.config.PathKeys.sameNormalized(rename.from(), rename.to())) {
                continue;
            }
            if (!sources.contains(com.editora.config.PathKeys.key(rename.to()))
                    && !ops.buffersAtOrUnder(rename.to()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Names the unsaved buffers that made {@link #resourceTargetsSafe} refuse, in whichever window they are. */
    private void resourceTargetsBlocked(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        java.util.Set<Path> unsaved = new java.util.LinkedHashSet<>();
        java.util.List<Path> targets = new java.util.ArrayList<>();
        mapped.deletes().forEach(deletion -> targets.add(deletion.file()));
        mapped.creates().stream().filter(c -> c.overwrite()).forEach(creation -> targets.add(creation.file()));
        mapped.renames().forEach(rename -> targets.add(rename.to()));
        for (Path target : targets) {
            for (EditorBuffer buffer : ops.buffersAtOrUnder(target)) {
                if (buffer.isDirty() && lspPath(buffer) != null) {
                    unsaved.add(lspPath(buffer));
                }
            }
        }
        if (!unsaved.isEmpty()) {
            List<Path> files = List.copyOf(unsaved);
            editRefused(files, tr("status.lsp.editTargetsUnsaved", blockedFileNames(files)));
        }
    }

    /** The files whose buffer cannot take every one of its edits — see {@code applyLspEditsAtomically}. */
    private static List<Path> unplaceableTargets(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.List<EditorBuffer> buffers) {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < mapped.edits().size(); i++) {
            EditorBuffer buffer = buffers.get(i);
            if (buffer != null && !buffer.canPlaceLspEdits(mapped.edits().get(i).edits())) {
                files.add(mapped.edits().get(i).file());
            }
        }
        return files;
    }

    /** An edit the server sent does not fit the file it names: nothing was applied; say which file. */
    private void editUnplaceable(List<Path> files) {
        if (!files.isEmpty()) {
            editRefused(files, tr("status.lsp.editUnplaceable", blockedFileNames(files)));
        }
    }

    private boolean resourceStateCurrent(
            WorkspaceFileOperations files,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes) {
        try {
            for (StagedCreate item : creates) {
                Path file = item.operation().file().toAbsolutePath().normalize();
                if (item.created()
                        && (!files.exists(file) || !java.util.Objects.equals(item.identity(), files.identity(file)))) {
                    return false;
                }
            }
            for (StagedRename item : renames) {
                Path from = item.rename().from().toAbsolutePath().normalize();
                Path to = item.rename().to().toAbsolutePath().normalize();
                // After a case-only rename on a case-insensitive volume the old spelling still "exists".
                if ((files.exists(from) && !sameFileInAnotherCase(files, from, to))
                        || !files.exists(to)
                        || !java.util.Objects.equals(item.identity(), files.identity(to))) {
                    return false;
                }
            }
            for (StagedDelete item : deletes) {
                Path file = item.operation().file().toAbsolutePath().normalize();
                if (files.exists(file)
                        || !files.exists(item.stage())
                        || !java.util.Objects.equals(item.identity(), files.identity(item.stage()))) {
                    return false;
                }
            }
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        }
    }

    private void rollbackWorkspaceAsync(
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes,
            WorkspaceTransactionStatus transaction,
            java.util.function.Consumer<Boolean> done) {
        workspaceExecutor.execute(() -> {
            rollbackDeletes(deletes, transaction);
            rollbackRenames(renames, transaction);
            rollbackCreates(creates, transaction);
            endTransaction(transaction);
            Platform.runLater(() -> {
                reportIncompleteRollback(transaction);
                done.accept(false);
            });
        });
    }

    private boolean applyWorkspaceTextEdits(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            WorkspaceTransactionStatus transaction) {
        java.util.List<AppliedWorkspaceText> applied = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < mapped.edits().size(); i++) {
                var edit = mapped.edits().get(i);
                EditorBuffer buffer = buffers.get(i);
                String original = buffer.getContent();
                if (!buffer.applyLspEditsAtomically(edit.edits())) {
                    // Checked for every buffer before the first was touched; reaching this means the
                    // document changed underneath. The catch below restores the buffers already edited.
                    editUnplaceable(List.of(edit.file()));
                    throw new IllegalStateException("workspace edit does not fit " + edit.file());
                }
                if (!original.equals(buffer.getContent())) {
                    applied.add(new AppliedWorkspaceText(buffer, edit.file(), original));
                }
                if (lspManager.isManaged(edit.file())) {
                    // Keep the server document synchronized before a trailing CodeAction command runs.
                    lspManager.changeDocument(edit.file(), buffer.getContent());
                }
            }
            return true;
        } catch (RuntimeException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                try {
                    var snapshot = applied.get(i);
                    snapshot.buffer().replaceWholeDocument(snapshot.original());
                    if (lspManager.isManaged(snapshot.file())) {
                        lspManager.changeDocument(snapshot.file(), snapshot.original());
                    }
                } catch (RuntimeException rollbackFailure) {
                    transaction.rollbackFailed = true;
                }
            }
            return false;
        }
    }

    private void reportIncompleteRollback(WorkspaceTransactionStatus transaction) {
        if (transaction.rollbackFailed) {
            host.setError(tr("status.lsp.workspaceEditRollbackFailed"));
        }
    }

    private static final class WorkspaceTransactionStatus {
        private boolean rollbackFailed;
        private com.editora.lsp.WorkspaceEditJournal journal = com.editora.lsp.WorkspaceEditJournal.begin(null);
    }

    /** What the user chose to do about an interrupted transaction. */
    enum InterruptedEditChoice {
        /** Put the staged files back (an undecided edit). */
        RESTORE,
        /** Remove the old copies (a decided edit that did not get to clean up). */
        REMOVE,
        /** Leave every file where it is and stop asking. */
        KEEP,
        /** Ask again at the next project open. */
        LATER
    }

    /** Asks what to do about an interrupted transaction; a field so a test can answer without a dialog. */
    java.util.function.Function<com.editora.lsp.WorkspaceEditJournal.Interrupted, InterruptedEditChoice>
            interruptedEditPrompt = this::promptInterruptedEdit;

    private Path interruptedEditsCheckedFor;

    /**
     * Once per opened project: looks for workspace-edit transactions that were interrupted between staging
     * and commit — the process died with files moved aside under hidden {@code .editora-lsp-*} names — and
     * offers to put them back. Nothing is moved or removed without the user's answer.
     */
    void offerInterruptedEdits() {
        Path root = ops.lspProjectRoot();
        Path directory = journalDir();
        if (root == null || directory == null || root.equals(interruptedEditsCheckedFor)) {
            return;
        }
        interruptedEditsCheckedFor = root;
        workspaceExecutor.execute(() -> {
            List<com.editora.lsp.WorkspaceEditJournal.Interrupted> found = new java.util.ArrayList<>();
            for (var interrupted : com.editora.lsp.WorkspaceEditJournal.pending(directory)) {
                if (interrupted.touches(root)) {
                    found.add(interrupted);
                } else {
                    com.editora.lsp.WorkspaceEditJournal.release(interrupted); // another project's
                }
            }
            if (!found.isEmpty()) {
                Platform.runLater(() -> found.forEach(this::resolveInterruptedEdit));
            }
        });
    }

    private void resolveInterruptedEdit(com.editora.lsp.WorkspaceEditJournal.Interrupted interrupted) {
        InterruptedEditChoice choice = interruptedEditPrompt.apply(interrupted);
        switch (choice == null ? InterruptedEditChoice.LATER : choice) {
            case RESTORE ->
                workspaceExecutor.execute(() -> {
                    var outcome = interrupted.restore();
                    Platform.runLater(() -> interruptedEditResolved(
                            outcome.restored(),
                            outcome.leftInPlace(),
                            tr(
                                    "status.lsp.interruptedEdit.restored",
                                    outcome.restored().size())));
                });
            case REMOVE ->
                workspaceExecutor.execute(() -> {
                    List<Path> left = interrupted.discardLeftovers();
                    Platform.runLater(
                            () -> interruptedEditResolved(List.of(), left, tr("status.lsp.interruptedEdit.removed")));
                });
            case KEEP -> {
                List<Path> left = interrupted.leftovers();
                interrupted.dismiss();
                host.setStatus(tr("status.lsp.interruptedEdit.kept", blockedFileNames(left)));
            }
            case LATER -> {
                // The journal stays; this run does not ask again.
            }
        }
    }

    private void interruptedEditResolved(List<Path> restored, List<Path> left, String success) {
        if (!restored.isEmpty()) {
            ops.fileCreated(restored.get(0)); // refreshes the Project tree
        }
        if (left.isEmpty()) {
            host.setStatus(success);
        } else {
            host.setError(tr("status.lsp.interruptedEdit.left", blockedFileNames(left)));
        }
    }

    private InterruptedEditChoice promptInterruptedEdit(com.editora.lsp.WorkspaceEditJournal.Interrupted interrupted) {
        List<Path> originals = interrupted.originals();
        StringBuilder lines = new StringBuilder();
        int shown = Math.min(10, originals.size());
        for (int i = 0; i < shown; i++) {
            lines.append(i == 0 ? "" : "\n")
                    .append(ops.homeCollapsed(originals.get(i).toString()));
        }
        if (originals.size() > shown) {
            lines.append("\n+").append(originals.size() - shown);
        }
        javafx.scene.control.ButtonType act = new javafx.scene.control.ButtonType(
                tr(
                        interrupted.committed()
                                ? "dialog.lsp.interruptedEdit.remove"
                                : "dialog.lsp.interruptedEdit.restore"),
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType keep = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.interruptedEdit.keep"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.ButtonType later = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.interruptedEdit.later"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        javafx.scene.control.Alert ask = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING, lines.toString(), later, keep, act);
        ask.initOwner(host.window());
        ask.setTitle(tr("dialog.lsp.interruptedEdit.title"));
        ask.setHeaderText(tr(
                interrupted.committed()
                        ? "dialog.lsp.interruptedEdit.headerCommitted"
                        : "dialog.lsp.interruptedEdit.header"));
        ask.getDialogPane().setMinWidth(560);
        javafx.scene.control.ButtonType answer = ask.showAndWait().orElse(later);
        if (answer == act) {
            return interrupted.committed() ? InterruptedEditChoice.REMOVE : InterruptedEditChoice.RESTORE;
        }
        return answer == keep ? InterruptedEditChoice.KEEP : InterruptedEditChoice.LATER;
    }

    /** Where interrupted-transaction journals are kept; a field so a test can point it at a temp directory. */
    Path workspaceEditJournalDir;

    private Path journalDir() {
        if (workspaceEditJournalDir != null) {
            return workspaceEditJournalDir;
        }
        com.editora.config.ConfigManager config = ops.config();
        return config == null ? null : config.getConfigDir().resolve("lsp-edit-journal");
    }

    /**
     * Starts a transaction. One that moves files keeps a journal on disk until it has committed or rolled
     * back, so a crash in between leaves a record of what is staged where (see
     * {@link com.editora.lsp.WorkspaceEditJournal}).
     */
    private WorkspaceTransactionStatus beginTransaction(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        WorkspaceTransactionStatus transaction = new WorkspaceTransactionStatus();
        if (!mapped.creates().isEmpty()
                || !mapped.renames().isEmpty()
                || !mapped.deletes().isEmpty()) {
            transaction.journal = com.editora.lsp.WorkspaceEditJournal.begin(journalDir());
        }
        return transaction;
    }

    /**
     * Ends a transaction whose staged files have all been committed or put back. After an incomplete
     * rollback the journal stays, so the files it could not restore are offered at the next project open.
     */
    private void endTransaction(WorkspaceTransactionStatus transaction) {
        if (transaction.rollbackFailed) {
            transaction.journal.abandon();
        } else {
            transaction.journal.close();
        }
    }

    private record AppliedWorkspaceText(EditorBuffer buffer, Path file, String original) {}

    private record StagedCreate(
            com.editora.lsp.WorkspaceEditMapper.FileCreate operation,
            Path overwrittenBackup,
            boolean created,
            WorkspaceFileIdentity identity) {}

    private record StagedDelete(
            com.editora.lsp.WorkspaceEditMapper.FileDelete operation, Path stage, WorkspaceFileIdentity identity) {}

    private java.util.List<StagedCreate> stageCreates(
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileCreate> operations,
            WorkspaceTransactionStatus transaction) {
        java.util.List<StagedCreate> staged = new java.util.ArrayList<>();
        try {
            for (var operation : operations) {
                Path file = operation.file().toAbsolutePath().normalize();
                if (workspaceFiles.exists(file)) {
                    if (operation.ignoreIfExists()) {
                        staged.add(new StagedCreate(operation, null, false, null));
                        continue;
                    }
                    if (!operation.overwrite() || workspaceFiles.isDirectory(file)) {
                        rollbackCreates(staged, transaction);
                        return null;
                    }
                    Path backup = temporarySibling(file, ".created-overwrite");
                    transaction.journal.creating(file, backup);
                    workspaceFiles.move(file, backup);
                    staged.add(new StagedCreate(operation, backup, false, null));
                    workspaceFiles.createFile(file);
                    staged.set(staged.size() - 1, new StagedCreate(operation, backup, true, null));
                    staged.set(
                            staged.size() - 1,
                            new StagedCreate(operation, backup, true, workspaceFiles.identity(file)));
                } else {
                    Path parent = file.getParent();
                    if (parent != null) {
                        workspaceFiles.createDirectories(parent);
                    }
                    transaction.journal.creating(file, null);
                    workspaceFiles.createFile(file);
                    staged.add(new StagedCreate(operation, null, true, null));
                    staged.set(
                            staged.size() - 1, new StagedCreate(operation, null, true, workspaceFiles.identity(file)));
                }
            }
            return staged;
        } catch (java.io.IOException | RuntimeException failure) {
            rollbackCreates(staged, transaction);
            return null;
        }
    }

    private void rollbackCreates(java.util.List<StagedCreate> staged, WorkspaceTransactionStatus transaction) {
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedCreate item = staged.get(i);
            Path file = item.operation().file().toAbsolutePath().normalize();
            try {
                if (item.created() && workspaceFiles.exists(file)) {
                    if (workspaceFiles.size(file) == 0) {
                        workspaceFiles.deleteIfExists(file);
                    } else {
                        transaction.rollbackFailed = true;
                    }
                }
                if (item.overwrittenBackup() != null && workspaceFiles.exists(item.overwrittenBackup())) {
                    if (workspaceFiles.exists(file)) {
                        transaction.rollbackFailed = true;
                    } else {
                        workspaceFiles.move(item.overwrittenBackup(), file);
                    }
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Continue restoring independent paths.
            }
        }
    }

    private void commitCreates(java.util.List<StagedCreate> staged) {
        for (StagedCreate item : staged) {
            deleteRecursively(item.overwrittenBackup());
        }
    }

    private java.util.List<StagedDelete> stageDeletes(
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileDelete> operations,
            WorkspaceTransactionStatus transaction) {
        java.util.List<StagedDelete> staged = new java.util.ArrayList<>();
        try {
            for (var operation : operations) {
                Path file = operation.file().toAbsolutePath().normalize();
                if (!workspaceFiles.exists(file)) {
                    if (operation.ignoreIfNotExists()) {
                        continue;
                    }
                    rollbackDeletes(staged, transaction);
                    return null;
                }
                if (workspaceFiles.isDirectory(file)
                        && !operation.recursive()
                        && !workspaceFiles.list(file).isEmpty()) {
                    rollbackDeletes(staged, transaction);
                    return null;
                }
                Path stage = temporarySibling(file, ".deleted");
                transaction.journal.deleting(file, stage);
                workspaceFiles.move(file, stage);
                staged.add(new StagedDelete(operation, stage, null));
                staged.set(staged.size() - 1, new StagedDelete(operation, stage, workspaceFiles.identity(stage)));
            }
            return staged;
        } catch (java.io.IOException | RuntimeException failure) {
            rollbackDeletes(staged, transaction);
            return null;
        }
    }

    private void rollbackDeletes(java.util.List<StagedDelete> staged, WorkspaceTransactionStatus transaction) {
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedDelete item = staged.get(i);
            try {
                Path original = item.operation().file().toAbsolutePath().normalize();
                if (workspaceFiles.exists(original)) {
                    transaction.rollbackFailed = true;
                } else {
                    workspaceFiles.move(item.stage(), original);
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Continue restoring independent paths.
            }
        }
    }

    private void commitDeletes(java.util.List<StagedDelete> staged) {
        for (StagedDelete item : staged) {
            deleteRecursively(item.stage());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !workspaceFiles.exists(path)) {
            return;
        }
        try {
            workspaceFiles.walk(path).stream()
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            workspaceFiles.deleteIfExists(p);
                        } catch (java.io.IOException ignored) {
                            // A committed edit should not be rolled back because cleanup of a hidden stage failed.
                        }
                    });
        } catch (java.io.IOException ignored) {
            // Best effort cleanup.
        }
    }

    /** One completed filesystem rename plus the temporary paths used to make the batch transactional. */
    private record StagedRename(
            com.editora.lsp.WorkspaceEditMapper.FileRename rename,
            Path stage,
            Path overwrittenBackup,
            WorkspaceFileIdentity identity) {}

    /**
     * Executes every file move as one recoverable batch before editor text is touched. Sources and overwritten
     * destinations are first moved aside; if any later move fails, every path is restored from those stages.
     */
    private java.util.List<StagedRename> stageRenames(
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames,
            WorkspaceTransactionStatus transaction) {
        java.util.List<StagedRename> staged = new java.util.ArrayList<>();
        java.util.Set<Path> sources = new java.util.HashSet<>();
        java.util.Set<Path> destinations = new java.util.HashSet<>();
        try {
            for (var r : renames) {
                Path from = r.from().toAbsolutePath().normalize();
                Path to = r.to().toAbsolutePath().normalize();
                if (samePathSpelling(from, to)) {
                    continue;
                }
                if (!sources.add(from) || !destinations.add(to) || !workspaceFiles.isRegularFile(from)) {
                    return null;
                }
            }
            // Validate collisions only after every source is known. A destination may legitimately be
            // another source in the same batch (A→B, B→C), because all sources are staged first.
            for (var r : renames) {
                Path from = r.from().toAbsolutePath().normalize();
                Path to = r.to().toAbsolutePath().normalize();
                if (!samePathSpelling(from, to)
                        && !r.overwrite()
                        && workspaceFiles.exists(to)
                        && !sources.contains(to)
                        && !sameFileInAnotherCase(from, to)) {
                    return null;
                }
            }
            for (var r : renames) {
                Path from = r.from().toAbsolutePath().normalize();
                Path to = r.to().toAbsolutePath().normalize();
                if (samePathSpelling(from, to)) {
                    continue;
                }
                Path stage = temporarySibling(from, ".source");
                transaction.journal.renaming(
                        from, stage, r.to().toAbsolutePath().normalize());
                workspaceFiles.move(from, stage);
                staged.add(new StagedRename(r, stage, null, null));
                staged.set(staged.size() - 1, new StagedRename(r, stage, null, workspaceFiles.identity(stage)));
            }
            for (int i = 0; i < staged.size(); i++) {
                StagedRename item = staged.get(i);
                Path to = item.rename().to().toAbsolutePath().normalize();
                Path parent = to.getParent();
                if (parent != null) {
                    workspaceFiles.createDirectories(parent);
                }
                Path backup = null;
                if (workspaceFiles.exists(to)) {
                    backup = temporarySibling(to, ".destination");
                    transaction.journal.replacing(to, backup);
                    workspaceFiles.move(to, backup);
                    item = new StagedRename(item.rename(), item.stage(), backup, item.identity());
                    staged.set(i, item);
                }
                workspaceFiles.move(item.stage(), to);
            }
            return List.copyOf(staged);
        } catch (java.io.IOException | RuntimeException failure) {
            rollbackRenames(staged, transaction);
            return null;
        }
    }

    /**
     * Whether a rename is a no-op. Compared as text, not with {@code Path.equals}: on Windows that ignores
     * case, so a case-only rename ({@code Httpclient.java} → {@code HttpClient.java}) was dropped from the
     * batch while its text edits were applied.
     */
    private static boolean samePathSpelling(Path from, Path to) {
        return from.toString().equals(to.toString());
    }

    /**
     * Whether {@code to} "exists" only because it is {@code from} itself on a case-insensitive volume — a
     * case-only rename, not a collision with someone else's file. Staging then performs the two-step move
     * such a rename needs.
     */
    private boolean sameFileInAnotherCase(Path from, Path to) {
        return sameFileInAnotherCase(workspaceFiles, from, to);
    }

    private static boolean sameFileInAnotherCase(WorkspaceFileOperations files, Path from, Path to) {
        if (!from.toString().equalsIgnoreCase(to.toString())) {
            return false;
        }
        try {
            Object source = files.identity(from).fileKey();
            Object destination = files.identity(to).fileKey();
            // No file keys (Windows): the names differ only in case and both resolve, which is the same file
            // on every volume but a case-sensitive directory.
            return source == null || destination == null || source.equals(destination);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private void commitRenames(java.util.List<StagedRename> staged) {
        for (StagedRename item : staged) {
            deleteRecursively(item.overwrittenBackup());
        }
    }

    private Path temporarySibling(Path file, String suffix) throws java.io.IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new java.io.IOException("file has no parent: " + file);
        }
        Path temp = workspaceFiles.createTempFile(parent, ".editora-lsp-", suffix);
        workspaceFiles.deleteIfExists(temp);
        return temp;
    }

    private void rollbackRenames(java.util.List<StagedRename> staged, WorkspaceTransactionStatus transaction) {
        // Put completed destinations back into their per-source stages first. This also handles chains such
        // as A→B and B→C without one restored source overwriting another staged source.
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedRename item = staged.get(i);
            Path to = item.rename().to().toAbsolutePath().normalize();
            try {
                if (!workspaceFiles.exists(item.stage()) && workspaceFiles.exists(to)) {
                    workspaceFiles.move(to, item.stage());
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Continue restoring the other independently staged paths.
            }
        }
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedRename item = staged.get(i);
            Path from = item.rename().from().toAbsolutePath().normalize();
            Path to = item.rename().to().toAbsolutePath().normalize();
            try {
                if (workspaceFiles.exists(item.stage())) {
                    if (workspaceFiles.exists(from)) {
                        transaction.rollbackFailed = true;
                    } else {
                        workspaceFiles.move(item.stage(), from);
                    }
                }
                if (item.overwrittenBackup() != null
                        && workspaceFiles.exists(item.overwrittenBackup())
                        && !workspaceFiles.exists(to)) {
                    workspaceFiles.move(item.overwrittenBackup(), to);
                } else if (item.overwrittenBackup() != null && workspaceFiles.exists(item.overwrittenBackup())) {
                    // The completed destination could not be moved back to its source stage. Replacing it
                    // here would destroy that source's only surviving copy, so retain both paths for recovery.
                    transaction.rollbackFailed = true;
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Best effort after the original failure; all recoverable stages are attempted.
            }
        }
    }

    /**
     * Shows LSP hover markdown in a dismissable popup at the caret (rendered via the Markdown renderer).
     * Closes on Escape, a click elsewhere (auto-hide), caret movement, scrolling, or another hover.
     */
    private void showHoverPopup(CodeArea area, String markdown) {
        hideHoverPopup();
        Node content;
        try {
            // Links in a hover (documentation URLs) open through the window's link choke point.
            content = MarkdownRenderer.renderDocument(
                    MarkdownRenderer.parseForRender(markdown),
                    null,
                    host::openExternalUrl,
                    MarkdownRenderer.ImagePolicy.DATA_ONLY);
        } catch (RuntimeException e) {
            Label label = new Label(markdown);
            label.setWrapText(true);
            content = label;
        }
        // In a scroll pane no taller than part of the window: a command's hover from bash-language-server is
        // its whole man page (8 KB for `ls`), which made a popup thousands of pixels tall with most of it
        // off screen and out of reach.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.getStyleClass().add("lsp-hover-scroll");
        scroll.setFitToWidth(true); // prose wraps at the popup's width; code blocks scroll sideways themselves
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setFocusTraversable(false);
        scroll.setMaxWidth(HOVER_MAX_WIDTH);
        scroll.setMaxHeight(hoverMaxHeight(area));
        // Wide enough for the signature: the popup sized itself to the docstring's prose, so Pyright's
        // `def helper(` block scrolled sideways under its own language tag (Python review B12).
        scroll.setMinWidth(hoverCodeWidth(markdown));
        VBox box = new VBox(scroll);
        box.getStyleClass().add("lsp-hover-popup");
        box.setMaxWidth(HOVER_MAX_WIDTH);
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());

        Popup popup = new Popup();
        popup.setAutoHide(true); // click outside / focus loss dismisses it
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        hoverPopup = popup;

        // Dismiss on Escape, caret movement, or scroll — all detached again when the popup hides.
        EventHandler<KeyEvent> esc = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideHoverPopup();
                ev.consume();
            }
        };
        ChangeListener<Object> dismiss = (o, a, b) -> hideHoverPopup();
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(dismiss);
        area.estimatedScrollYProperty().addListener(dismiss);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            area.caretPositionProperty().removeListener(dismiss);
            area.estimatedScrollYProperty().removeListener(dismiss);
            if (hoverPopup == popup) {
                hoverPopup = null;
            }
        });

        var bounds = area.getCaretBounds().orElse(null);
        if (bounds != null) {
            popup.show(area, bounds.getMinX(), bounds.getMaxY());
        } else {
            popup.show(area, 0, 0);
        }
    }

    /** Average advance of the hover's code font, per character (a slight over-estimate is harmless). */
    static final double HOVER_CODE_CHAR_WIDTH = 8.4;

    /** Code-block chrome inside the hover: block padding, the scroll pane's inner padding, the scroll bar. */
    static final double HOVER_CODE_CHROME = 72;

    /**
     * Pure: the text-column width a hover needs to show its fenced code without a sideways scroll — the
     * longest code line in characters — clamped to {@link #HOVER_MAX_WIDTH}; 0 without a code block.
     */
    static double hoverCodeWidth(String markdown) {
        if (markdown == null) {
            return 0;
        }
        int longest = 0;
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            String t = line.strip();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                longest = Math.max(longest, line.length());
            }
        }
        return longest == 0 ? 0 : Math.min(HOVER_MAX_WIDTH, longest * HOVER_CODE_CHAR_WIDTH + HOVER_CODE_CHROME);
    }

    /** Widest a hover popup's text column gets. */
    static final double HOVER_MAX_WIDTH = 560;

    /** The tallest a hover popup gets in a window {@code windowHeight} tall: part of it, within fixed bounds. */
    static double hoverMaxHeight(double windowHeight) {
        double share = windowHeight > 0 ? windowHeight * 0.45 : 400;
        return Math.max(160, Math.min(480, share));
    }

    private static double hoverMaxHeight(CodeArea area) {
        javafx.stage.Window w = area.getScene() == null ? null : area.getScene().getWindow();
        return hoverMaxHeight(w == null ? 0 : w.getHeight());
    }

    /** Hides the LSP hover popup if one is showing. */
    private void hideHoverPopup() {
        hoverGeneration++;
        if (hoverPopup != null) {
            hoverPopup.hide();
            hoverPopup = null;
        }
    }

    /**
     * Signature help (#674): the overloads + active parameter at the caret. {@code manual} is the
     * {@code lsp.signatureHelp} command (reports when unavailable); auto-trigger — a typed {@code (} or
     * {@code ,}, and the typing-pause refresh while the popup is up — stays silent. A response with no
     * signatures hides the popup, which is also how it closes once the caret leaves the call.
     */
    void signatureHelp(boolean manual) {
        signatureHelp(manual, null);
    }

    /**
     * As {@link #signatureHelp(boolean)}, with the {@code triggerChar} that fired it ({@code null} = the
     * explicit command or a typing-pause refresh). Passing it through is what lets the server see
     * {@code triggerKind=TriggerCharacter} instead of a blanket {@code Invoked} (#725); {@code isRetrigger}
     * is derived from whether a popup is already open, which servers use to keep the active overload stable
     * while arguments are typed.
     */
    void signatureHelp(boolean manual, Character triggerChar) {
        EditorBuffer b = host.activeBuffer();
        if (b == null
                || lspPath(b) == null
                || !lspManager.isManaged(lspPath(b))
                || !lspManager.supportsSignatureHelp(lspPath(b))) {
            if (manual) {
                host.setStatus(tr("status.lsp.noSignatureHelp"));
            }
            return;
        }
        Path path = lspPath(b);
        CodeArea area = b.getFocusedArea();
        boolean retrigger = signaturePopup != null; // the popup is already up for this call
        if (!manual
                && triggerChar == null
                && signatureArea == area
                && signatureRequestVersion == b.docVersion()
                && signatureRequestCaret == area.getCaretPosition()) return;
        if (signatureArea != null && signatureArea != area) hideSignaturePopup();
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        long generation = ++signatureGeneration;
        long version = b.docVersion();
        int caret = area.getCaretPosition();
        signatureArea = area;
        signatureRequestVersion = version;
        signatureRequestCaret = caret;
        pendingSignaturePath = path;
        lspManager.signatureHelp(
                path,
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                triggerChar == null ? null : String.valueOf(triggerChar),
                retrigger,
                signatureSelection.context(),
                help -> {
                    if (b != host.activeBuffer()
                            || b.isDisposed()
                            || area != b.getFocusedArea()
                            || generation != signatureGeneration
                            || version != b.docVersion()
                            || caret != area.getCaretPosition()
                            || !path.equals(lspPath(b))) return;
                    pendingSignaturePath = null;
                    signatureSelection.update(help);
                    var active = signatureSelection.active();
                    if (active == null) {
                        hideSignaturePopup(); // outside a call now — the natural close
                        if (manual) {
                            host.setStatus(tr("status.lsp.noSignatureHelp"));
                        }
                        return;
                    }
                    showSignaturePopup(area, active);
                });
    }

    void moveSignature(int delta) {
        if (signaturePopup == null || signatureArea == null) return;
        signatureSelection.move(delta);
        var active = signatureSelection.active();
        if (active != null) showSignaturePopup(signatureArea, active);
    }

    // --- Watched files (#677) ------------------------------------------------------------------

    /** Pending external file changes (latest kind wins per path), flushed coalesced to the servers. */
    private final Map<Path, com.editora.lsp.LspManager.WatchedKind> pendingWatched = new LinkedHashMap<>();

    /** Coalesces watcher bursts (a branch switch touches hundreds of files) into one flush. */
    private final javafx.animation.PauseTransition watchedFlush =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));

    /**
     * Queues external file changes for the language servers ({@code workspace/didChangeWatchedFiles}) —
     * fed by the Project tree's filesystem watcher and the external-change/branch-switch reload paths.
     * Without this a git checkout or a CLI build left every server's project model stale until restart
     * (#677). FX thread; bursts coalesce (300 ms) into one notification per session.
     */
    void watchedFilesChanged(List<ProjectPanel.FsChange> changes) {
        if (!ops.lspFeatureEnabled() || changes == null || changes.isEmpty()) {
            return;
        }
        for (var c : changes) {
            if (c == null || c.path() == null) {
                continue;
            }
            com.editora.lsp.LspManager.WatchedKind kind = switch (c.kind()) {
                case CREATED -> com.editora.lsp.LspManager.WatchedKind.CREATED;
                case DELETED -> com.editora.lsp.LspManager.WatchedKind.DELETED;
                case CHANGED -> com.editora.lsp.LspManager.WatchedKind.CHANGED;
            };
            // Merged, not overwritten: writing a new file is a create then a modify, and a server told only
            // "changed" about a file it never knew ignores it — a new module stayed unresolved (review B6).
            com.editora.lsp.LspManager.WatchedKind merged =
                    com.editora.lsp.LspManager.mergeWatched(pendingWatched.get(c.path()), kind);
            if (merged == null) {
                pendingWatched.remove(c.path());
            } else {
                pendingWatched.put(c.path(), merged);
            }
        }
        watchedFlush.setOnFinished(e -> flushWatchedFiles());
        watchedFlush.playFromStart();
    }

    /** Convenience for the reload paths: a batch of files that changed on disk (kind CHANGED). */
    void watchedFilesReloaded(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        watchedFilesChanged(files.stream()
                .map(f -> new ProjectPanel.FsChange(f, ProjectPanel.FsKind.CHANGED))
                .toList());
    }

    private void flushWatchedFiles() {
        if (pendingWatched.isEmpty()) {
            return;
        }
        List<com.editora.lsp.LspManager.WatchedFile> batch = new java.util.ArrayList<>(pendingWatched.size());
        pendingWatched.forEach((path, kind) -> batch.add(new com.editora.lsp.LspManager.WatchedFile(path, kind)));
        pendingWatched.clear();
        lspManager.notifyWatchedFiles(batch);
    }

    /** Refreshes on typing pause, including a first response invalidated by continued typing before
     *  the popup opened. Escape clears the pending context and must never schedule this retry. */
    private void refreshSignatureHelpIfShowing() {
        EditorBuffer active = host.activeBuffer();
        if (signaturePopup != null
                || (pendingSignaturePath != null
                        && active != null
                        && !active.isDisposed()
                        && pendingSignaturePath.equals(lspPath(active)))) {
            signatureHelp(false);
        }
    }

    /**
     * Document highlight (#675): asks the server for the occurrences of the symbol under the resting caret
     * and pushes them into the buffer's occurrence overlay. Fired by the buffer's 300 ms caret-idle timer;
     * silent (an empty result just leaves the wash cleared). The {@code docVersion} guard drops a response
     * computed against a document that moved while the request was in flight — its offsets are meaningless.
     */
    private void requestOccurrences(EditorBuffer buffer) {
        Path path = lspPath(buffer);
        if (buffer != host.activeBuffer()
                || path == null
                || !lspManager.isManaged(path)
                || !lspManager.supportsDocumentHighlight(path)) {
            buffer.clearOccurrenceSpans();
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        long version = buffer.docVersion();
        lspManager.documentHighlights(path, area.getCurrentParagraph(), area.getCaretColumn(), spans -> {
            if (buffer == host.activeBuffer() && buffer.docVersion() == version) {
                buffer.setOccurrenceSpans(spans);
            }
        });
    }

    /**
     * Shows (replacing any previous) the signature popup above/below the caret. Unlike the hover popup it
     * survives multiline argument edits until the server reports no call; caret navigation is debounced.
     */
    private void showSignaturePopup(CodeArea area, com.editora.lsp.SignatureFormat.Active active) {
        String label = active.label();
        javafx.scene.text.Text pre = new javafx.scene.text.Text(label.substring(0, active.paramStart()));
        javafx.scene.text.Text param =
                new javafx.scene.text.Text(label.substring(active.paramStart(), active.paramEnd()));
        param.setStyle("-fx-font-weight: bold; -fx-underline: true");
        javafx.scene.text.Text post = new javafx.scene.text.Text(label.substring(active.paramEnd()));
        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow(pre, param, post);
        flow.setMaxWidth(560);
        VBox box = new VBox(4, flow);
        if (active.total() > 1) {
            Label count = new Label((active.index() + 1) + "/" + active.total());
            count.getStyleClass().add("lsp-signature-count");
            javafx.scene.control.Button previous = new javafx.scene.control.Button("‹");
            javafx.scene.control.Button next = new javafx.scene.control.Button("›");
            previous.setFocusTraversable(false);
            next.setFocusTraversable(false);
            previous.setAccessibleText(tr("command.lsp.previousSignature"));
            next.setAccessibleText(tr("command.lsp.nextSignature"));
            previous.setOnAction(e -> ops.executeCommand("lsp.previousSignature"));
            next.setOnAction(e -> ops.executeCommand("lsp.nextSignature"));
            box.getChildren().add(0, new javafx.scene.layout.HBox(6, previous, count, next));
        }
        if (!active.documentation().isBlank()) {
            try {
                Node doc = MarkdownRenderer.renderDocument(
                        MarkdownRenderer.parseForRender(active.documentation()),
                        null,
                        host::openExternalUrl,
                        MarkdownRenderer.ImagePolicy.DATA_ONLY);
                box.getChildren().add(doc);
            } catch (RuntimeException e) {
                Label docLabel = new Label(active.documentation());
                docLabel.setWrapText(true);
                box.getChildren().add(docLabel);
            }
        }
        box.getStyleClass().add("lsp-hover-popup"); // same card styling as hover
        box.setMaxWidth(560);
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());

        if (signaturePopup != null) {
            signaturePopup.getContent().setAll(box);
            var bounds = area.getCaretBounds().orElse(null);
            if (bounds != null) {
                signaturePopup.setAnchorX(bounds.getMinX());
                signaturePopup.setAnchorY(bounds.getMaxY());
            }
            return;
        }
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        signaturePopup = popup;

        EventHandler<KeyEvent> esc = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideSignaturePopup();
                ev.consume();
            }
        };
        ChangeListener<Object> caret = (o, a, bNew) -> {
            signatureCaretDebounce.setOnFinished(event -> refreshSignatureHelpIfShowing());
            signatureCaretDebounce.playFromStart();
        };
        javafx.event.EventHandler<javafx.scene.input.ScrollEvent> scroll = event -> hideSignaturePopup();
        ChangeListener<Boolean> focus = (o, was, now) -> {
            if (!now) hideSignaturePopup();
        };
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(caret);
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, scroll);
        area.focusedProperty().addListener(focus);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            area.caretPositionProperty().removeListener(caret);
            area.removeEventFilter(javafx.scene.input.ScrollEvent.SCROLL, scroll);
            area.focusedProperty().removeListener(focus);
            if (signaturePopup == popup) {
                signaturePopup = null;
                hideSignaturePopup();
            }
        });

        var bounds = area.getCaretBounds().orElse(null);
        if (bounds != null) {
            popup.show(area, bounds.getMinX(), bounds.getMaxY());
        } else {
            popup.show(area, 0, 0);
        }
    }

    /** Hides the signature-help popup if one is showing. */
    private void hideSignaturePopup() {
        signatureGeneration++;
        pendingSignaturePath = null;
        signatureSelection.clear();
        signatureArea = null;
        signatureRequestVersion = -1;
        signatureCaretDebounce.stop();
        if (signaturePopup != null) {
            signaturePopup.hide();
            signaturePopup = null;
        }
    }
}
