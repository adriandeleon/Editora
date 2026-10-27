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

import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.MarkdownRenderer;
import com.editora.lsp.InlayHintFilter;
import com.editora.lsp.JdtlsGenerate;
import com.editora.lsp.JdtlsRefactor;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspServerRegistry;
import com.editora.text.LspDiagnostic;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

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
        void openAndGoto(@Nullable Path file, int line0, int col0);

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
        @Nullable
        EditorBuffer bufferForPath(@Nullable Path file);

        /** Every live buffer at {@code path}, or below it for a directory, across application windows. */
        default java.util.List<EditorBuffer> buffersAtOrUnder(Path path) {
            EditorBuffer exact = bufferForPath(path);
            return exact == null ? java.util.List.of() : java.util.List.of(exact);
        }

        /** Opens {@code file} in a background (non-selected) tab and returns its buffer — how a multi-file
         *  quick fix touches a file with no open tab (#670). Null when it can't be opened. */
        @Nullable
        EditorBuffer openBackgroundBuffer(Path file);

        default void openBackgroundBufferAsync(Path file, java.util.function.Consumer<@Nullable EditorBuffer> done) {
            done.accept(openBackgroundBuffer(file));
        }

        /** Saves {@code buffer} to its file now; false when it could not be saved. */
        default boolean saveBuffer(EditorBuffer buffer) {
            return false;
        }

        /** Closes the tab of {@code buffer}, opened in the background by an edit, when it has nothing unsaved. */
        default void closeBackgroundBuffer(EditorBuffer buffer) {}

        /** A workspace edit renamed a file on disk (#676) — remap the open buffer/tab + per-file session
         *  state (the project-tree rename hook). */
        void fileRenamed(@Nullable Path from, Path to);

        /** A workspace edit created/deleted a path on disk; refresh or close matching UI state. */
        void fileCreated(Path file);

        void fileDeleted(@Nullable Path file);

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
        void setStatusBarLsp(@Nullable String label);

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
        @Nullable
        StructurePanel structurePanel();

        /**
         * The window's configuration, or null in a test that has none. Read for the trusted-folder list
         * (project-supplied server commands run only in a trusted folder) and the config directory.
         */
        default com.editora.config.@Nullable ConfigManager config() {
            return null;
        }

        /** Base dir for per-project jdtls Eclipse workspaces ({@code <configDir>/jdtls-workspaces}). */
        default @Nullable Path jdtlsWorkspaceBase() {
            com.editora.config.ConfigManager config = config();
            return config == null ? null : config.getConfigDir().resolve("jdtls-workspaces");
        }

        /** The active project's root for LSP root resolution (null when Projects is off / no project). */
        @Nullable
        Path lspProjectRoot();

        /** Shows the Python interpreter status-bar item ({@code text == null} hides it). */
        default void setStatusBarPython(@Nullable String text, @Nullable String tooltip) {}

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
        @Nullable
        Path canonicalize(@Nullable Path file);
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

        @Nullable
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
    private @Nullable ProblemsPanel problemsPanel;

    private @Nullable ReferencesPanel referencesPanel;
    private @Nullable HierarchyPanel hierarchyPanel;
    private final ProblemsPanel.Actions problemsActions;
    private final HierarchyPanel.Loader hierarchyLoader;
    /** The Problems tree's active file, remembered for a panel not built yet. */
    private @Nullable Path problemsActiveFile;

    /** The buffer path a hierarchy was started from — routes child expansions to the right session. */
    private @Nullable Path hierarchyAnchor;
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
        "groovy",
        "scala",
        "clojure",
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
    private @Nullable Popup hoverPopup;

    /** The currently-showing signature-help popup (#674), or null. */
    private @Nullable Popup signaturePopup;

    private long signatureGeneration;
    private long hoverGeneration;
    private @Nullable Path pendingSignaturePath;
    private final com.editora.lsp.SignatureSelection signatureSelection = new com.editora.lsp.SignatureSelection();
    private @Nullable CodeArea signatureArea;
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
            public @Nullable Path projectRoot() {
                return ops.lspProjectRoot();
            }

            @Override
            public com.editora.config.@Nullable ConfigManager config() {
                return ops.config();
            }

            @Override
            public void setStatusItem(@Nullable String text, @Nullable String tooltip) {
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
            public @Nullable EditorBuffer activeBuffer() {
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
            public @Nullable Path venvOf(@Nullable Path file) {
                return pythonInterpreters.resolve(file).venv();
            }

            @Override
            public void setStatus(String message) {
                host.setStatus(message);
            }

            @Override
            public void setError(@Nullable String message) {
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

            @Override
            public void openLibrary(String uri, int line) {
                Path anchor = hierarchyAnchor;
                if (anchor != null) {
                    openLibraryFrame(anchor, uri, line);
                }
            }
        };
        lspManager.setOnSessionCrashed(this::onSessionCrashed);
        lspManager.setOnSharedServerEnded(this::sharedServerEnded);
        lspManager.setOnInstallHold(this::installHold);
        lspManager.setWindowFocused(() -> {
            javafx.stage.Window w = host.window();
            return w != null && w.isFocused();
        });
        lspManager.setOnServerStatus(new LspManager.ServerStatusListener() {
            @Override
            public void status(String serverId, String type, String message) {
                onServerStatus(serverId, null, type, message);
            }

            @Override
            public void status(String serverId, @Nullable Path root, String type, String message) {
                onServerStatus(serverId, root, type, message);
            }
        });
        lspManager.setPythonInterpreterResolver(pythonInterpreters::pythonPathForServerRoot); // review B1
        // A server root the Project panel's watcher does not cover gets a watcher of its own (review B7).
        lspManager.setWatchSessionRoots(root -> {
            Path project = watchedProjectRoot;
            return project == null || !root.startsWith(project.toAbsolutePath().normalize());
        });
        lspManager.setFolderTrust(this::folderTrusted);
        lspManager.setOnStartWithheld(this::onStartWithheld);
        lspManager.setOnSecondServer((serverId, root) -> host.setStatus(tr(
                "status.lsp.secondServer",
                serverLabel(serverId),
                root.getFileName() == null ? root : root.getFileName())));
        lspManager.setOnStartFailed(this::onStartFailed);
        lspManager.setOnStartRefused(this::onStartRefused);
        lspManager.setOnRequestError(this::onRequestError);
        lspManager.setApplyEditHandler(this::applyWorkspaceEditsConfirmed); // server quick-fix edits land here (#670)
        lspManager.setOnEditBlocked(this::editBlocked);
        lspManager.setOnRefreshRequested(this::refreshRequested);
        lspManager.setOpenDocumentDiagnosticsOnly(!projectWideProblems); // the Problems window's default scope
        lspManager.setOnDiagnosticsUnchanged(this::diagnosticsUnchanged);
        lspManager.setClientUi(new ServerPrompts()); // a server's questions (Metals' "Import build?") get asked
        xml = new XmlLspActions(
                host, lspManager, ops::openAndGoto, this::activeLspBuffer, this::lspPath, () -> findReferences(true));
    }

    /** The XML server's commands and client commands (XML review B15, D6). */
    private final XmlLspActions xml;

    XmlLspActions xml() {
        return xml;
    }

    /**
     * {@code file}'s diagnostics are known to be what they were before the last edit — the server was sent
     * nothing (the text ended up identical) or answered a pull with "unchanged". Every edit clears the
     * buffer's overlay, scrollbar stripe and minimap marks on the assumption that a publish follows; when
     * none will, the last published list is put back, so the editor does not show a clean file while the
     * Problems window still lists its errors.
     */
    private void diagnosticsUnchanged(@Nullable Path file) {
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
                refreshCapabilityGates(buffer, path, buffer == active); // covers every other kind for this buffer
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

    private boolean servedByAny(Path path, java.util.@Nullable Set<Object> origins) {
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
            Path path = lspPath(b);
            if (path != null && lspManager.isManaged(path)) {
                refreshCapabilityGates(b, path, b == active);
            }
        });
        requestStructureSymbols(active);
        ops.onServerCapabilitiesReady();
    }

    /** Folder-without-build-file source roots jdtls has been told about (C6): asked once each. */
    private final java.util.Set<Path> correctedSourceRoots = new java.util.HashSet<>();

    /** The build files that make a folder a project jdtls imports properly (no invisible-project guess). */
    private static final List<String> JAVA_BUILD_FILES =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * A Java file in a folder with no build file: jdtls guessed its source folder from where the file is, and
     * the declared package says otherwise, so every file was red (Java review C6). Runs when the file is opened
     * (the requests wait for the server) and again when the server's capabilities arrive or it says the import is
     * done; {@link com.editora.dap.JavaSourceRootFix} does the telling, the same correction a Debug launch makes.
     */
    private void correctPlainFolderSourceRoot(EditorBuffer buffer, Path path) {
        if (!LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))
                || path.getFileName() == null
                || !path.getFileName().toString().endsWith(".java")
                || !com.editora.process.ExecTargets.runsHere(path)) {
            return;
        }
        String pkg = com.editora.dap.JavaSourceRootFix.declaredPackage(buffer.getContent());
        Path root = com.editora.dap.JavaSourceRootFix.sourceRoot(path, pkg);
        Path projectRoot = lspRootFor(buffer, LspServerRegistry.JAVA_SERVER_ID);
        if (root == null || correctedSourceRoots.contains(root) || projectRoot == null) {
            return;
        }
        for (String build : JAVA_BUILD_FILES) {
            if (java.nio.file.Files.exists(projectRoot.resolve(build))) {
                return; // a Maven or Gradle project: jdtls reads its source folders from the build
            }
        }
        com.editora.dap.JavaSourceRootFix.correct(lspManager, path, pkg, () -> !buffer.isDisposed(), fixed -> {
            if (fixed) {
                correctedSourceRoots.add(root);
                // jdtls keeps a file it has open bound to the source folder it was opened under: reopened, each
                // one in the corrected folder is read there, instead of staying red until its tab is reopened
                // (re-review C6).
                host.forEachBuffer(open -> {
                    Path p = lspPath(open);
                    if (p != null && p.startsWith(root) && lspManager.isManaged(p) && !open.isDisposed()) {
                        lspManager.closeDocument(p);
                        clearDiagnostics(p);
                        syncBuffer(open);
                    }
                });
            }
        });
    }

    /**
     * Re-applies one managed buffer's gates after initialize or a dynamic capability change, and re-requests
     * its server data: diagnostics and folding ranges for any tab (both are kept per buffer), semantic
     * tokens and inlay hints only for the tab on screen.
     */
    private void refreshCapabilityGates(EditorBuffer b, Path path, boolean active) {
        correctPlainFolderSourceRoot(b, path);
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
     * (serverId + root) keys that are not started again by opening or showing a tab: the server crash-looped,
     * refused initialize, or has no TypeScript it may run. Before this, every tab open forked (and lost) the
     * server again (Node review E5). Cleared by Restart Language Servers, a settings apply (an install or a
     * trust decision goes through one), and a remote host reconnecting.
     */
    private final java.util.Set<String> startBlocked = new java.util.HashSet<>();

    /** Whether opening a tab may start {@code serverId} on {@code root}. Package-visible for tests. */
    boolean startAllowed(String serverId, @Nullable Path root) {
        return !startBlocked.contains(crashKey(serverId, root));
    }

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
        startBlocked.removeIf(key -> key.contains(onHost));
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
        // What it said before it ever answered (a native build on too old a glibc), shown rather than left in the
        // Debug Log, and kept for Doctor and the install path while it is given up on (XML review A4).
        String reason = lspManager.crashReason(serverId);
        if (!restart) {
            startBlocked.add(crashKey(serverId, root)); // E5: given up — opening another tab must not fork it again
            if (reason != null) {
                crashedServers.put(serverId, reason);
            }
        }
        host.setStatus(
                restart
                        ? tr("status.lsp.crashed", serverLabel(serverId))
                        : reason == null
                                ? tr("status.lsp.crashLoop", serverLabel(serverId))
                                : tr("status.lsp.crashLoopReason", serverLabel(serverId), reason));
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

    /**
     * Another window restarted the Java server this window shared (or saw it crash first): this window's
     * documents on it reopen on the new one, with no crash notice — this window did nothing wrong.
     */
    private void sharedServerEnded(String serverId, Path root) {
        if (LspManager.isHeldForInstall(serverId)) {
            return; // an install stopped it: the files stay paused, and reopen when it is released (fifth review CF1)
        }
        // the ended server's project problems go too: another window restarted it, and its "The supplied phased
        // action failed" stayed here after the project imported (seventh review CR3)
        clearProjectProblems(root);
        forEachCrashedBuffer(serverId, root, b -> {
            b.setLspActive(false);
            clearDiagnostics(lspPath(b));
            // At once, shown or not: deferred, a background tab's problems left the Problems window until it was
            // next shown (third review CT9). The server is already starting for the other window.
            deferredLsp.remove(b);
            syncBuffer(b);
        });
        updateStatusBar();
    }

    /**
     * An install stopped {@code serverId} in every window while its folder is swapped ({@code held}), or is done.
     * Held, this window's documents on it go inactive and the status says why; released, they reopen on a fresh
     * server. The window that ran the install re-applies its tool support first, so this runs after it, and a
     * document that is already open again is left alone (fourth review CF1).
     */
    private void installHold(String serverId, boolean held) {
        if (!ops.lspFeatureEnabled() || !serverEnabled(serverId)) {
            return;
        }
        if (held) {
            host.forEachBuffer(b -> {
                if (serverId.equals(serverIdForBuffer(b))) {
                    b.setLspActive(false);
                    clearDiagnostics(lspPath(b));
                }
            });
            host.setStatus(tr("status.lsp.heldForInstall", serverProgram(serverId)));
            updateStatusBar();
            return;
        }
        Platform.runLater(() -> {
            host.forEachBuffer(b -> {
                Path p = lspPath(b);
                if (p != null && serverId.equals(serverIdForBuffer(b)) && !lspManager.isManaged(p)) {
                    deferredLsp.remove(b);
                    syncBuffer(b);
                }
            });
            updateStatusBar();
        });
    }

    /** The crash-history key of one server on one root; a remote root by its URI, which names its host. */
    private static String crashKey(String serverId, @Nullable Path root) {
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
     * A server whose command exists is not running, and starting it again would not help: it refused
     * initialize (its own words are shown), or Editora did not start the TypeScript server because there is no
     * TypeScript it may run. Its tabs go inactive, the reason is an error (so it is not lost when the status line
     * moves on), and opening more tabs does not retry it until something changes (Node review B3, E5).
     */
    private void onStartRefused(String serverId, Path root, com.editora.lsp.StartRefusal refusal) {
        if (!ops.lspFeatureEnabled() || !serverEnabled(serverId)) {
            return;
        }
        startBlocked.add(crashKey(serverId, root));
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p != null
                    && serverId.equals(serverIdForBuffer(b))
                    && sameLspRoot(lspRootFor(b, serverId), root)
                    && !lspManager.isManaged(p)) {
                b.setLspActive(false);
                clearDiagnostics(p);
            }
        });
        ops.setLspLoading(false);
        // the server's own name ("jdtls needs Java 25"), not the language's ("Java needs Java 25"); said again on the
        // next pulse too, as the file's own "Opened …" status landed over it (seventh review DR3)
        String said = startRefusedMessage(
                refusal.kind() == com.editora.lsp.StartRefusal.Kind.NO_JAVA_FOR_SERVER
                        ? serverProgram(serverId)
                        : serverLabel(serverId),
                refusal);
        host.setError(said);
        Platform.runLater(() -> host.setError(said));
        updateStatusBar();
    }

    /** The status-line words for a {@link com.editora.lsp.StartRefusal}. */
    static String startRefusedMessage(String server, com.editora.lsp.StartRefusal refusal) {
        return switch (refusal.kind()) {
            case SERVER_REFUSED -> tr("status.lsp.startRefused", server, refusal.detail());
            case NO_TYPESCRIPT ->
                refusal.detail().isBlank()
                        ? tr("status.lsp.tsNoSdk")
                        : tr("status.lsp.tsSdkWithoutTsserver", refusal.detail());
            case UNTRUSTED_TYPESCRIPT -> tr("status.lsp.tsSdkUntrusted", tr("command.lsp.trustProjectSettings"));
            case NO_JAVA_FOR_SERVER -> tr("status.lsp.javaTooOld", server, refusal.detail());
        };
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

    /** Servers given up on after failing their requests, by id → why (for the Doctor row); see {@link #onRequestError}. */
    private final Map<String, String> failingServers = new java.util.HashMap<>();

    /**
     * A server answered a request with a fault inside itself (Maven review C2). The first fault of each method
     * is said in the echo area — "lemminx-maven: internal error on hover — see the log" — so a server that
     * fails everything no longer reads as one with nothing to say. The third fault in a row gives the server
     * up the way a crash loop does: it is stopped, not restarted until the settings change or Restart Language
     * Servers, its buffers go unserved, and Doctor's row says why.
     */
    void onRequestError(String serverId, Path root, String method, String summary, boolean announce, boolean failing) {
        if (!ops.lspFeatureEnabled()) {
            return;
        }
        if (failing) {
            failingServers.put(serverId, summary);
            startBlocked.add(crashKey(serverId, root));
            lspManager.shutdownServer(serverId);
            forEachCrashedBuffer(serverId, root, b -> {
                b.setLspActive(false);
                clearDiagnostics(lspPath(b));
            });
            onServerStatus(serverId, "Error", summary); // the narrator's failed state, as a failed start leaves it
            host.setStatus(tr("status.lsp.requestErrors", serverLabel(serverId)));
            updateStatusBar();
            return;
        }
        if (announce) {
            host.setStatus(tr("status.lsp.requestError", serverLabel(serverId), methodLabel(method)));
        }
    }

    /** Why {@code serverId} was given up on after failing its requests, or null while it is not. Doctor-screen read. */
    @Nullable
    String serverFailure(String serverId) {
        return failingServers.get(serverId);
    }

    /** Servers given up on after crashing at every start, by id → what they said first (see {@link #sessionCrashed}). */
    private final Map<String, String> crashedServers = new java.util.HashMap<>();

    /**
     * Why {@code serverId} keeps exiting before it is ready ("version `GLIBC_2.34' not found"), while it is given
     * up on; else null. Doctor and Settings show it, and Install treats such a server as not installed.
     */
    @Nullable
    String serverCrash(String serverId) {
        return crashedServers.get(serverId);
    }

    /** {@link #serverFailure} or {@link #serverCrash}, whichever says why {@code serverId} is given up on. */
    @Nullable
    ServerTrouble serverTrouble(String serverId) {
        String crash = crashedServers.get(serverId);
        if (crash != null) {
            return new ServerTrouble(crash, true);
        }
        String failing = failingServers.get(serverId);
        return failing == null ? null : new ServerTrouble(failing, false);
    }

    /** Why a server is given up on: what it said, and whether it crashed at start rather than failed requests. */
    record ServerTrouble(String detail, boolean crashed) {}

    /** The user-facing name of an LSP request, for the echo line ("internal error on hover"). */
    static String methodLabel(String method) {
        String key = "lsp.method." + method;
        String label = tr(key);
        return label.equals(key) ? method : label;
    }

    /** Whether two roots identify the same LSP session boundary. Kept pure for crash-routing coverage. */
    static boolean sameLspRoot(@Nullable Path bufferRoot, Path crashedRoot) {
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
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        Path path = origin.anchor();
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
        syncForRequest(origin); // sync latest text before the request
        java.util.function.Consumer<List<com.editora.lsp.LspManager.HierarchyNode>> onRoots = roots -> {
            if (!navigationRequestCurrent(b, origin.ownPath(), version, generation)) {
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
            lspManager.prepareCallHierarchyAt(
                    path, origin.libraryUri(), area.getCurrentParagraph(), area.getCaretColumn(), onRoots);
        } else {
            lspManager.prepareTypeHierarchyAt(
                    path, origin.libraryUri(), area.getCurrentParagraph(), area.getCaretColumn(), onRoots);
        }
    }

    /** Live diagnostics map for the MCP bridge's {@code getDiagnostics} (read on the FX thread). */
    Map<Path, List<LspDiagnostic>> problems() {
        return problems;
    }

    /** Sorts the Problems tree so the active file's group is on top (pass the canonical path; on tab switch). */
    void setProblemsActiveFile(@Nullable Path canonicalActive) {
        problemsActiveFile = canonicalActive;
        if (problemsPanel != null) {
            problemsPanel.setActiveFile(canonicalActive);
        }
    }

    /**
     * The open Java files jdtls calls "a non-project file" though they are in a source folder of a project with a
     * build file, and its message, by project: a pom.xml that does not parse when jdtls first imports it is skipped
     * with a ProjectStatus of OK, and every file is then checked for syntax only (tenth review CO2).
     */
    private final Map<String, Map<Path, String>> nonProjectFiles = new java.util.HashMap<>();

    private void noteNonProjectFile(Path file, List<LspDiagnostic> diagnostics) {
        Path root = file.toString().endsWith(".java") ? lspManager.javaSessionRoot(file) : null;
        if (root == null || !hasJavaBuildFile(root) || !inSourceFolder(root, file)) {
            return; // a loose file in a project's other folders is a non-project file by rights
        }
        String key = importKey(LspServerRegistry.JAVA_SERVER_ID, root);
        String message = diagnostics.stream()
                .map(LspDiagnostic::message)
                .filter(m -> m.contains("is a non-project file"))
                .findFirst()
                .orElse(null);
        Map<Path, String> files = nonProjectFiles.computeIfAbsent(key, k -> new java.util.HashMap<>());
        if (message == null) {
            files.remove(file);
        } else {
            files.put(file, message);
            if (saidReady.contains(statusSource(LspServerRegistry.JAVA_SERVER_ID, root))) {
                nonProjectImport(root, message);
            }
        }
    }

    /** Whether {@code file} is under a Maven or Gradle source folder of {@code root}. */
    private static boolean inSourceFolder(Path root, Path file) {
        String rel = "/" + root.relativize(file).toString().replace('\\', '/');
        return rel.contains("/src/main/java/") || rel.contains("/src/test/java/");
    }

    /** Marks {@code root}'s import failed for a non-project file's {@code message}, said once as the reason. */
    private void nonProjectImport(Path root, String message) {
        if (importFailed.add(importKey(LspServerRegistry.JAVA_SERVER_ID, root))) {
            host.setError(tr(
                    "status.lsp.importProblemWhy",
                    statusServerName(LspServerRegistry.JAVA_SERVER_ID, root),
                    message.replaceFirst("[.\\s]+$", "")));
            refreshImportNotes();
        }
    }

    /** Project-level problems already said in the status line, by folder and message. */
    private final java.util.Set<String> announcedProjectProblems = new java.util.HashSet<>();

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
            noteNonProjectFile(file, diagnostics);
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
        // Problems of a project rather than a file arrive on its folder (jdtls: the expected JDK is missing, the
        // compliance level does not match). Listed in either scope, and said once (Java review C19).
        boolean projectLevel = buffer == null && LspManager.projectLevel(file);
        if (projectLevel) {
            for (LspDiagnostic d : listed) {
                if (d.severity().ordinal() <= LspDiagnostic.Severity.WARNING.ordinal()
                        && announcedProjectProblems.add(key + "\u0000" + d.message())) {
                    // the project's folder name, not "pom.xml"; a warning is not said as an error (CT8)
                    Path project = java.nio.file.Files.isDirectory(file) ? file : file.getParent();
                    String text = tr(
                            "status.lsp.projectProblem",
                            project == null ? PathDisplay.fileName(file) : PathDisplay.fileName(project),
                            d.message());
                    // in a window that joins later the replayed diagnostic comes before the replayed import line:
                    // the server's logged error says the import failed already (tenth review CP3)
                    boolean failedImport = project != null
                            && (importFailed.contains(importKey(LspServerRegistry.JAVA_SERVER_ID, project))
                                    || lspManager.lastServerError(LspServerRegistry.JAVA_SERVER_ID, project) != null);
                    if (d.severity() == LspDiagnostic.Severity.ERROR && !failedImport) {
                        host.setError(text);
                    } else {
                        host.setStatus(text); // the import's own error already said it (ninth review CP3)
                    }
                    break;
                }
            }
        }
        boolean keep = projectWideProblems || projectLevel ? !listed.isEmpty() : buffer != null && !listed.isEmpty();
        if (keep) {
            problems.put(key, listed);
        } else {
            problems.remove(key);
        }
        if (projectLevel) {
            // The project's error comes after jdtls said the import failed: say the reason again in its words
            // ("Could not find method …", not the Gradle wrapper's "Could not fetch model") (third review CT7).
            Path project = java.nio.file.Files.isDirectory(file) ? file : file.getParent();
            String reason = project == null ? null : projectReason(project);
            String failedKey = importKey(LspServerRegistry.JAVA_SERVER_ID, project);
            if (reason != null
                    && importFailed.contains(failedKey)
                    && !reason.equals(saidImportReason.put(failedKey, reason))) { // once, not per publish
                host.setError(tr(
                        "status.lsp.importProblemWhy",
                        statusServerName(LspServerRegistry.JAVA_SERVER_ID, project), // named with its folder (CV4)
                        reason.replaceFirst("[.\\s]+$", "")));
            }
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
        if (path == null || !lspManager.hasJavaSession(path)) {
            host.setStatus(tr("status.lsp.unavailable"));
            return;
        }
        setProjectWideProblems(true);
        ops.setLspLoading(true);
        host.setStatus(tr("status.lsp.buildingWorkspace"));
        lspManager.refreshProjectDiagnostics(path, outcome -> {
            ops.setLspLoading(false);
            // "Project built" over a build with errors read as success (Java review C15).
            switch (outcome) {
                case SUCCEEDED -> host.setStatus(tr("status.lsp.buildWorkspaceDone"));
                case WITH_ERRORS ->
                    host.setError(
                            problems.isEmpty() // a project that did not import builds "with errors" and lists none
                                    ? tr("status.lsp.buildWorkspaceNotImported")
                                    : tr("status.lsp.buildWorkspaceErrors"));
                case CANCELLED -> host.setStatus(tr("status.lsp.buildWorkspaceCancelled"));
                case FAILED, UNAVAILABLE -> host.setError(tr("status.lsp.buildWorkspaceFailed"));
            }
        });
    }

    /** Drops {@code file}'s diagnostics (a tab closed / its LSP session ended) + refreshes the panel. */
    void clearDiagnostics(@Nullable Path file) {
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
    boolean isManaged(java.nio.file.@Nullable Path path) {
        return path != null && lspManager.isManaged(path);
    }

    /**
     * Reconciles the LSP feature with its setting (mirrors {@link MermaidCoordinator}). Configures the
     * manager + Problems window, then (when enabled) detects each server and gates per-buffer LSP. Runs at
     * init and on every settings apply.
     */
    void applySupport() {
        startBlocked.clear(); // a settings change (an install, a trust decision) may be what the server lacked
        failingServers.clear();
        crashedServers.clear();
        boolean on = ops.lspFeatureEnabled(); // effective: off in Simple UI mode
        // Give jdtls a per-project Eclipse workspace under the config dir (it otherwise shares one default
        // workspace and deadlocks on its .lock — the server then never finishes initialize / completion).
        lspManager.setJdtlsWorkspaceBase(ops.jdtlsWorkspaceBase());
        com.editora.config.ConfigManager config = ops.config();
        lspManager.setLombokDir(
                config == null
                        ? null
                        : config.getConfigDir().resolve(com.editora.install.InstallCatalog.LOMBOK_SUBPATH));
        lspManager.setOnLombokMissing(this::offerLombok);
        lspManager.setJavaCleanups(host.settings().getJavaCleanups());
        lspManager.setJavaFilteredTypes(host.settings().getJavaExcludedTypes());
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
            commands.put(serverId, launchCommand(serverId));
        }
        return commands;
    }

    /** Pushes {@code Settings.lspOnTypeFormatting} to the manager, which jdtls needs to register the provider,
     *  the Default JDK (jdtls's default runtime), and the Maven server's Central-index choice (which restarts
     *  that server when it changes). */
    void applyOnTypeFormatting() {
        lspManager.setJavaOnTypeFormatting(host.settings().isLspOnTypeFormatting());
        if (lspManager.setJavaDefaultJdk(host.settings().getMavenJdkHome())) {
            host.setStatus(tr("status.lsp.javaJdkChanged")); // a running jdtls keeps its JDK (re-review CN7)
        }
        lspManager.setMavenCentralIndex(host.settings().isMavenPomCentralIndex());
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
    void ensureManaged(java.nio.file.@Nullable Path file) {
        if (file == null || lspManager.isManaged(file)) {
            return;
        }
        java.nio.file.Path key = ops.canonicalize(file);
        host.forEachBuffer(b -> {
            Path path = lspPath(b);
            if (path != null && key != null && key.equals(ops.canonicalize(path)) && deferredLsp.remove(b)) {
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
            case "groovy" -> s.isGroovyLspEnabled();
            case "scala" -> s.isScalaLspEnabled();
            case "clojure" -> s.isClojureLspEnabled();
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

    /** This window's project scope: its .editora/settings.json, its trust, and the effective values. */
    private final ProjectScope projectScope = new ProjectScope(this::scopeRoot, this::scopeConfig);

    private @Nullable Path scopeRoot() {
        return ops.lspProjectRoot();
    }

    private com.editora.config.@Nullable ConfigManager scopeConfig() {
        return ops.config();
    }

    /** This window's project scope, which every reader of a project-scopable setting asks. */
    ProjectScope projectScope() {
        return projectScope;
    }

    /**
     * The command {@code serverId} runs when the user chose one other than the default (a path, another server),
     * else null — so a missing custom server is not "fixed" by installing the default one (review B8).
     */
    @Nullable
    String customServerCommand(@Nullable String serverId) {
        if (serverId == null) {
            return null;
        }
        String command = serverCommand(serverId);
        if (command == null || command.isBlank()) {
            return null;
        }
        if ("xml".equals(serverId) && com.editora.install.XmlServerInstall.editorasOwn(command, configDir())) {
            return null; // what an earlier install wrote: Install replaces it, it is not the user's own (review A6)
        }
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(command));
        String fallback = com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId);
        return normalized.equals(String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(fallback)))
                ? null
                : command.strip();
    }

    /** The configured command for a server id (blank ⇒ the server's default). */
    private String serverCommand(String serverId) {
        return projectSettings().commandFor(serverId, serverCommand(host.settings(), serverId), projectTrusted());
    }

    /** The command a server is started with: {@link #serverCommand(String)}, a blank one filled in with
     *  Editora's own copy where it keeps one outside PATH (lemminx, review A9). */
    private String launchCommand(String serverId) {
        return withOwnCopy(serverId, serverCommand(serverId), configDir());
    }

    /** {@code command} for {@code serverId}, Editora's own copy standing in for a blank XML command. Pure but
     *  for file lookups. */
    static String withOwnCopy(String serverId, @Nullable String command, @Nullable Path configDir) {
        String raw = command == null ? "" : command;
        return "xml".equals(serverId) ? com.editora.install.XmlServerInstall.effectiveCommand(raw, configDir) : raw;
    }

    /** {@link #commandsForAllServers} as they are launched: {@link #withOwnCopy} applied to each. */
    static Map<String, String> launchCommandsForAllServers(com.editora.config.Settings s, @Nullable Path configDir) {
        Map<String, String> commands = commandsForAllServers(s);
        commands.replaceAll((id, command) -> withOwnCopy(id, command, configDir));
        return commands;
    }

    private @Nullable Path configDir() {
        com.editora.config.ConfigManager config = ops.config();
        return config == null ? null : config.getConfigDir();
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
        return projectScope.trusted();
    }

    /**
     * The line Settings → LSP shows on {@code serverId}'s card when this window's project file
     * ({@code .editora/settings.json}) overrides it — the global command shown there is then not the one that
     * runs — or null when the project changes nothing for it.
     */
    @Nullable
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

    /**
     * The name of a project value as Settings shows it: {@code editor.tabSize} → "Tab size",
     * {@code build.maven} → "Maven command", {@code debug.python} → "Python debugger".
     */
    static String scopeLabel(String key) {
        int dot = key.indexOf('.');
        String section = dot < 0 ? key : key.substring(0, dot);
        String name = dot < 0 ? key : key.substring(dot + 1);
        return switch (section) {
            case "build" -> tr("settings.scope.buildCommand", buildToolName(name));
            case "debug" ->
                tr(
                        "settings.scope.debugTool",
                        switch (name) {
                            case "java" -> "Java";
                            case "python" -> "Python";
                            case "javascript" -> "JavaScript";
                            default -> name;
                        });
            default -> tr("settings.scope." + name);
        };
    }

    private static String buildToolName(String id) {
        for (com.editora.build.BuildTool tool : com.editora.build.BuildTool.values()) {
            if (ProjectScope.toolId(tool).equals(id)) {
                return tool.displayName();
            }
        }
        return id;
    }

    /** What the project file asks for that needs trust (see {@code ProjectSettings.trustRequests}). */
    private List<String> projectTrustRequests() {
        List<String> requests = new java.util.ArrayList<>(
                projectSettings().trustRequests(id -> serverCommand(host.settings(), id), this::globalServerEnabled));
        requests.addAll(
                com.editora.lsp.BashIdeConfig.trustRequests(projectSettings().getBashIde()));
        projectSettings().valueOverrides().forEach((key, value) -> requests.add(scopeLabel(key) + ": " + value));
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
    static com.editora.lsp.BashIdeConfig userBashIde(com.editora.config.Settings s, @Nullable Path configDir) {
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
        applyTypeScriptConfig();
    }

    /**
     * What the TypeScript server is started with (type acquisition, the Node.js that runs it), its inlay-hint and
     * code-lens settings, and the shared root of loose JavaScript files.
     */
    private void applyTypeScriptConfig() {
        var s = host.settings();
        lspManager.setTypeScriptStart(s.isTypescriptTypeAcquisition(), s.getNodePath());
        lspManager.setTypeScriptSettings(s.isInlayHints(), s.isCodeLens());
        com.editora.config.ConfigManager config = ops.config();
        lspManager.setLooseRoot(config == null ? null : config.getConfigDir().resolve(LOOSE_ROOT));
    }

    /** The shared root of loose JavaScript/TypeScript (and XML) files, under the config folder. */
    static final String LOOSE_ROOT = "lsp/loose-files";

    /** The project root whose withheld overrides were last announced, so a gating pass does not repeat it. */
    private @Nullable Path withheldNoticeRoot;

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
        List<String> sdkRequests = sdkTrustRequests(root);
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
        if (sdkRequests.stream().anyMatch(r -> r.startsWith(ASTRO_SERVER_ID + ": "))) {
            lspManager.shutdownServer(ASTRO_SERVER_ID); // restart it on the SDK the folder may now supply
        }
        if (sdkRequests.stream().anyMatch(r -> r.startsWith(com.editora.lsp.TypeScriptServer.SERVER_ID + ": "))) {
            lspManager.shutdownServer(com.editora.lsp.TypeScriptServer.SERVER_ID); // now on the folder's own TypeScript
        }
        projectScope.reload(); // now trusted: the editor, build and debug values apply too
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
    private List<String> sdkTrustRequests(Path root) {
        List<String> out = new java.util.ArrayList<>(typeScriptSdkTrustRequests(root));
        if (!serverEnabled(ASTRO_SERVER_ID)) {
            return List.copyOf(out);
        }
        Path top = root.toAbsolutePath().normalize();
        java.util.Set<String> found = new java.util.TreeSet<>(out);
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
     * What trusting {@code root} hands the TypeScript / JavaScript server: the folder's own TypeScript, whose
     * {@code tsserver.js} the server runs. Untrusted, it runs the TypeScript installed beside itself instead
     * (Node review B2). One {@code "typescript: <tsserver.js>"} line per SDK at the project root or at the server
     * root of an open JS/TS file, never above the project root.
     */
    private List<String> typeScriptSdkTrustRequests(Path root) {
        String id = com.editora.lsp.TypeScriptServer.SERVER_ID;
        if (!serverEnabled(id)) {
            return List.of();
        }
        Path top = root.toAbsolutePath().normalize();
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.function.Consumer<Path> look = start -> {
            if (start != null && start.toAbsolutePath().normalize().startsWith(top)) {
                Path sdk = com.editora.lsp.TypeScriptServer.findTsserver(start, top);
                if (sdk != null) {
                    found.add(id + ": " + sdk);
                }
            }
        };
        look.accept(top);
        host.forEachBuffer(b -> {
            if (lspPath(b) != null && id.equals(serverIdForBuffer(b))) {
                look.accept(lspRootFor(b, id));
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
        return projectScope.settings();
    }

    /** Whether {@code file} is this window's project {@code .editora/settings.json}. */
    private boolean isProjectSettingsFile(@Nullable Path file) {
        Path root = ops.lspProjectRoot();
        return file != null
                && root != null
                && com.editora.vfs.Vfs.isLocal(file)
                && com.editora.config.PathKeys.sameNormalized(file, com.editora.config.ProjectSettings.fileFor(root));
    }

    /** The overrides in force when {@link #applySupport} last configured the manager. */
    private String appliedProjectOverrides = "";

    /** The project root those overrides were resolved for. */
    private @Nullable Path appliedProjectRoot;

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
        projectScope.reload();
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
            // A Java lemminx (the pom.xml server's, where the native build cannot run) runs on the Maven JDK too.
            case "xml" -> com.editora.lsp.JvmServerCommand.withJdk(s.getXmlLspCommand(), s.getMavenJdkHome());
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
            case "groovy" -> com.editora.lsp.JvmServerCommand.withJdk(s.getGroovyLspCommand(), s.getMavenJdkHome());
            case "scala" -> com.editora.lsp.JvmServerCommand.withJdk(s.getScalaLspCommand(), s.getMavenJdkHome());
            case "clojure" -> s.getClojureLspCommand();
            case "lua" -> s.getLuaLspCommand();
            case "dockerfile" -> s.getDockerfileLspCommand();
            case "sql" -> s.getSqlLspCommand();
            case "terraform" -> s.getTerraformLspCommand();
            case "toml" -> s.getTomlLspCommand();
            case "csharp" -> s.getCsharpLspCommand();
            case "typst" -> s.getTypstLspCommand();
            case "astro" -> s.getAstroLspCommand();
            // The stored command starts with a bare `java`: run it on the Maven JDK like groovy/scala (review C4).
            case "maven-pom" ->
                com.editora.lsp.JvmServerCommand.withJdk(s.getMavenPomLspCommand(), s.getMavenJdkHome());
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
        String raw = launchCommand(serverId);
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
            case "groovy" -> s.setGroovyLspEnabled(on);
            case "scala" -> s.setScalaLspEnabled(on);
            case "clojure" -> s.setClojureLspEnabled(on);
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
            case "groovy" -> s.setGroovyLspCommand(command);
            case "scala" -> s.setScalaLspCommand(command);
            case "clojure" -> s.setClojureLspCommand(command);
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
            out.add(new EditorBuffer.InlayHint(s.line(), s.col(), s.paddedLabel()));
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
        lspManager.setTypeScriptSettings(
                host.settings().isInlayHints(), host.settings().isCodeLens());
        boolean on = lineAnnotationsOn();
        host.forEachBuffer(b -> {
            Path path = lspPath(b);
            b.setInlayHintsActive(on && path != null && lspManager.isManaged(path));
            requestInlayHints(b); // the gate inside clears buffers when toggled off
        });
    }

    /**
     * Requests semantic tokens for {@code buffer}'s visible region (padded by {@link #SEMANTIC_WINDOW_PAD})
     * and pushes them into the buffer when the response lands — but only if it's still the active buffer
     * (a background tab's tokens would overlay nothing useful and waste an apply).
     */
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

    private @Nullable EditorBuffer inlayScrollTarget;

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
            Path path = lspPath(b);
            if (path == null) {
                return;
            }
            boolean on = want && lspManager.isManaged(path) && lspManager.supportsSemanticTokens(path);
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
    void requestStructureSymbols(@Nullable EditorBuffer buffer) {
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
    private void requestStructureSymbols(@Nullable EditorBuffer buffer, boolean evenIfHidden) {
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

    private void setStructureSymbols(EditorBuffer buffer, @Nullable List<com.editora.lsp.SymbolNode> symbols) {
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
                // An encrypted file's text reaches a language server only when Settings allow it (ADR 0017);
                // a locked one has no text to serve yet.
                && (!buffer.encryption().isEncrypted()
                        || (host.settings().isEncryptionLanguageServers()
                                && !buffer.encryption().isLocked()))
                && serverId != null
                && serverEnabled(serverId)
                && (remote
                        ? remoteServerAvailable(buffer, path, serverId)
                        : Boolean.TRUE.equals(serverAvailable.get(serverId)));
        // eligible implies path and serverId are set; restated below where they are used, for the null checker
        if (eligible
                && path != null
                && serverId != null
                && !startBlocked.isEmpty()
                && !serverId.equals(lspManager.managedServerId(path))
                && !startAllowed(serverId, lspRootFor(buffer, serverId))) {
            eligible = false; // crash-looped or refused: not started again by a tab (E5)
        }
        buffer.setHeavyFileLspAllowed(buffer.isHeavyFile() && eligible); // B10: within the language's limit
        watchedProjectRoot = ops.lspProjectRoot();
        if (eligible && "python".equals(serverId)) {
            pythonInterpreters.captureSnapshot(); // what the server's start resolves its interpreter from
        }
        if (eligible
                && path != null
                && serverId != null
                && libraryOrigin(path, serverId) != null
                && libraryViewModeApplied.add(path.toAbsolutePath().normalize())
                && !buffer.isDirty()) {
            buffer.setViewMode(true); // a library or stub file is for reading; Enable Editing is one click away
        }
        if (eligible && path != null && serverId != null) {
            // Open when not yet managed, or re-open when the desired server changed — a pom.xml moving from the
            // plain XML server to lemminx-maven (once installed/enabled), or back. isManaged() alone can't tell:
            // it's keyed by URI, not server, so it stays true across a switch (which would otherwise never happen).
            String managing = lspManager.managedServerId(path);
            if (!serverId.equals(managing)) {
                if (managing != null) {
                    lspManager.closeDocument(path); // drop the old server's document + diagnostics before switching
                    clearDiagnostics(path);
                }
                Path root = lspRootFor(buffer, serverId);
                if (root == null || !lspManager.hasSession(serverId, root)) {
                    host.setStatus(tr("status.lsp.starting", serverLabel(serverId)));
                    ops.setLspLoading(true); // show the loading bar until the server reports ready
                }
                lspManager.setDocumentIndent(path, formattingIndent(buffer)); // quick fixes keep its indent (B8)
                lspManager.openDocument(path, root, routeLanguageId(buffer, serverId), buffer.text());
                correctPlainFolderSourceRoot(buffer, path); // queued until the server is up (C6)
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
            if (com.editora.lsp.TypeScriptServer.SERVER_ID.equals(serverId) && !remote) {
                noticeMissingNodeTypes(buffer, path);
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

    /** The folders the "Node.js APIs have no types" hint was shown for (once each per window). */
    private final java.util.Set<Path> nodeTypesHinted = new java.util.HashSet<>();

    /**
     * Says once per folder that Node.js APIs have no types for this JavaScript file, and how to add them: without
     * {@code @types/node} (and with type acquisition off) hover says {@code any} and {@code fs.} completes only
     * words from the file (Node review B9). TypeScript files get the compiler's own error for this instead.
     */
    private void noticeMissingNodeTypes(EditorBuffer buffer, Path path) {
        String lang = buffer.getLanguage();
        if (host.settings().isTypescriptTypeAcquisition()
                || !("javascript".equals(lang) || "javascriptreact".equals(lang))
                || path.getParent() == null
                || nodeTypesHinted.contains(path.getParent())
                || !com.editora.lsp.NodeTypesHint.usesNodeApis(buffer.text())
                || com.editora.lsp.NodeTypesHint.hasNodeTypes(path)) {
            return;
        }
        nodeTypesHinted.add(path.getParent());
        host.setStatus(tr("status.lsp.nodeTypesHint"));
    }

    /** Workspace root for a buffer under {@code serverId}: active project (if Projects on), else nearest build
     *  file (that server's markers), else the file's dir. */
    private @Nullable Path lspRootFor(EditorBuffer buffer, String serverId) {
        Path file = lspPath(buffer);
        if (file == null) {
            return null; // every caller has checked lspPath already
        }
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
        Path loose = lspManager.looseRoot();
        if (loose != null && com.editora.lsp.XmlServer.isXml(serverId)) {
            return com.editora.lsp.XmlServer.sharedRoot(ops.lspProjectRoot(), file, loose); // one lemminx (A7)
        }
        if (loose != null
                && com.editora.lsp.TypeScriptServer.SERVER_ID.equals(serverId)
                && com.editora.lsp.RootResolver.isLoose(
                        ops.lspProjectRoot(), file, com.editora.lsp.LspServerRegistry.rootMarkersForServer(serverId))) {
            return loose; // one server for every loose script, wherever it is (B7)
        }
        Path resolved = com.editora.lsp.RootResolver.resolveTiered(
                ops.lspProjectRoot(), file, com.editora.lsp.LspServerRegistry.rootMarkerTiersForServer(serverId));
        if (resolved != null && com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)) {
            // A module pom's root is its reactor's: one lemminx-maven JVM per reactor, not per module (C11).
            return com.editora.lsp.RootResolver.outermostContiguous(resolved, "pom.xml", ops.lspProjectRoot());
        }
        if (com.editora.lsp.LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                && com.editora.lsp.RootResolver.isLoose(
                        ops.lspProjectRoot(), file, com.editora.lsp.LspServerRegistry.rootMarkersForServer(serverId))) {
            // A loose `package demo;` file in …/src/demo is rooted where its package says, not in its own folder,
            // where jdtls expected the default package and marked every declaration (re-review C6).
            Path implied = com.editora.dap.JavaSourceRootFix.sourceRoot(
                    file, com.editora.dap.JavaSourceRootFix.declaredPackage(packageSource(buffer.getContent(), file)));
            if (implied != null && !com.editora.lsp.RootResolver.isBroadRoot(implied)) {
                return implied;
            }
        }
        if (resolved != null && com.editora.lsp.LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
            // A module's root is its build's: one jdtls per reactor or Gradle build, as a project gets (C10).
            return com.editora.lsp.RootResolver.javaBuildRoot(resolved, ops.lspProjectRoot());
        }
        return resolved;
    }

    /**
     * The text to read a file's {@code package} line from: the buffer's, or the start of the file on disk while
     * the buffer is still empty. A file's server is chosen as it opens, before its text has loaded, so the loose
     * root fix only ever saw an empty buffer outside its own test (third review CT4).
     */
    static String packageSource(String content, Path file) {
        if (!content.isBlank()) {
            return content;
        }
        try (java.io.Reader in = java.nio.file.Files.newBufferedReader(file)) {
            char[] head = new char[16 * 1024]; // a package line comes before any code, after the licence header
            int n = in.read(head);
            return n <= 0 ? "" : new String(head, 0, n);
        } catch (java.io.IOException | RuntimeException e) {
            return content;
        }
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
    void noteLibraryTarget(@Nullable Path anchor, Path target) {
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
    static boolean isLibraryTarget(Path target, Path serverRoot, @Nullable Path projectRoot) {
        Path t = target.toAbsolutePath().normalize();
        if (serverRoot != null && t.startsWith(serverRoot.toAbsolutePath().normalize())) {
            return false;
        }
        return projectRoot == null || !t.startsWith(projectRoot.toAbsolutePath().normalize());
    }

    /** The origin recorded for {@code file} when the buffer is about to be served by {@code serverId}. */
    private @Nullable LibraryOrigin libraryOrigin(@Nullable Path file, @Nullable String serverId) {
        if (file == null) {
            return null;
        }
        LibraryOrigin o = libraryOrigins.get(file.toAbsolutePath().normalize());
        return o != null && o.serverId().equals(serverId) ? o : null;
    }

    /** Servers that cannot run on a remote host yet (astro-ls needs a trusted TypeScript SDK found there). */
    private static boolean remoteUnsupported(@Nullable String serverId) {
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
    @Nullable
    String serverIdForBuffer(EditorBuffer b) {
        Path p = b == null ? null : lspPath(b);
        String base = b == null ? null : com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage());
        String pomServer = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        String fileName =
                p == null || p.getFileName() == null ? null : p.getFileName().toString();
        if (fileName != null && com.editora.lsp.LspServerRegistry.isPomFile(fileName) && serverEnabled(pomServer)) {
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
        if ("bash".equals(base) && fileName != null && com.editora.text.ShellFiles.isZshStartupFile(fileName)) {
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

    /**
     * On the Maven server, drops the {@code ${…}} property items that flood a GAV completion unless the typed
     * prefix starts with {@code $} (Maven review C19); every other server's results pass through untouched.
     */
    private java.util.function.Consumer<com.editora.completion.CompletionResult> pomCompletionFilter(
            EditorBuffer buffer,
            int line,
            int column,
            java.util.function.Consumer<com.editora.completion.CompletionResult> cb) {
        Path p = lspPath(buffer);
        if (p == null || !com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(lspManager.managedServerId(p))) {
            return cb;
        }
        String lineText = line >= 0 && line < buffer.getArea().getParagraphs().size()
                ? buffer.getArea().getParagraph(line).getText()
                : "";
        String prefix = com.editora.lsp.MavenPomServer.elementPrefix(lineText, column);
        return result -> {
            var items = com.editora.lsp.MavenPomServer.filterCompletion(
                    prefix, result.items(), com.editora.completion.Completion::label);
            cb.accept(
                    items == result.items()
                            ? result
                            : new com.editora.completion.CompletionResult(items, result.incomplete()));
        };
    }

    /** The accept hook for a completion item: resolve it + apply any additional edits (a TypeScript
     *  auto-import's {@code import} line). Returns null when the item can't carry extra edits. */
    private @Nullable Runnable autoImportAccept(EditorBuffer buffer, org.eclipse.lsp4j.CompletionItem item) {
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
    private volatile @Nullable Path watchedProjectRoot;

    /**
     * Untitled buffers served by a language server → the made-up file path their document has there (review
     * B14): an untitled Python buffer got no completion, errors or hover at all, because every request is keyed
     * by a file. The path names nothing on disk; it lives in a per-window folder under the temporary folder.
     */
    private final Map<EditorBuffer, Path> untitledLspPaths = new java.util.WeakHashMap<>();

    private int untitledLspCounter;

    /** The folder an untitled buffer's made-up document path lives in (per window, created on first use). */
    private @Nullable Path untitledLspFolder;

    /** The path {@code buffer}'s document has on its language server: its file, or an untitled buffer's made-up path. */
    @Nullable
    Path lspPath(EditorBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        Path real = buffer.getPath();
        return real != null ? real : untitledLspPaths.get(buffer);
    }

    /** Whether {@code path} is an untitled buffer's made-up document path. */
    boolean isUntitledLspPath(@Nullable Path path) {
        return path != null && untitledLspPaths.containsValue(path);
    }

    /** The buffer a server's path means: an untitled buffer's made-up path, else the open file. */
    private @Nullable EditorBuffer bufferForLspPath(@Nullable Path path) {
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
    private @Nullable Path untitledLspPath(EditorBuffer buffer) {
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
    private @Nullable EditorBuffer pythonStatusBuffer;

    private @Nullable String pythonStatusLanguage;

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
    private @Nullable Path doctorPythonFile() {
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
    private void updatePythonStatus(@Nullable EditorBuffer b) {
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
        Path path = b == null ? null : lspPath(b);
        String serverId = path == null ? null : lspManager.managedServerId(path);
        ops.setStatusBarLsp(serverId != null ? serverProgram(serverId) : null);
        updateProblemsAvailability(); // the Problems window tracks the same active-file LSP-managed condition
    }

    /**
     * The Problems tool-window stripe button is shown only when the active file is served by a language
     * server (LSP on + managed) — the same condition as the status-bar {@code LSP:} segment. So it's hidden
     * on a Welcome/Markdown/plain tab even if another open file has diagnostics.
     */
    private void updateProblemsAvailability() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        boolean available = ops.lspFeatureEnabled() && path != null && lspManager.isManaged(path);
        ops.setProblemsAvailable(available);
    }

    /**
     * The name a status message gives a server: its language as Doctor and Settings name it ("TypeScript /
     * JavaScript"), not its command ("typescript-language-server language server keeps crashing", Node review
     * E8); the program's name when no such label exists.
     */
    private String serverLabel(String serverId) {
        String key = "install.lang." + serverId;
        String label = tr(key);
        return label.equals(key) ? serverProgram(serverId) : label;
    }

    /** The program a server runs ("typescript-language-server"), as the status bar's LSP segment shows it. */
    private String serverProgram(String serverId) {
        // Servers whose program name says the wrong thing — `java` for the Maven server, the platform-suffixed
        // lemminx download for XML — have a display name of their own (Maven review C14).
        String named = com.editora.lsp.LspServerRegistry.displayNameFor(serverId);
        if (named != null) {
            return named;
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
    void onServerStatus(@Nullable String serverId, String type, String message) {
        onServerStatus(serverId, null, type, message);
    }

    /** As above, from the session on {@code root} (null when unknown). */
    void onServerStatus(@Nullable String serverId, @Nullable Path root, String type, String message) {
        if (!ops.lspFeatureEnabled()) {
            return;
        }
        String source = statusSource(serverId, root);
        if (serverId != null && root != null) {
            if ("Launching".equalsIgnoreCase(type)) {
                saidReady.remove(source); // a new process: its ready line is owed again
                // and what its files had is not known again until it has checked them (sixth review AS1)
                Path launched = root;
                host.forEachBuffer(b -> {
                    Path p = lspPath(b);
                    if (p != null
                            && serverId.equals(serverIdForBuffer(b))
                            && launched.equals(lspManager.javaSessionRoot(p))) {
                        b.problemCounter().reset();
                    }
                });
            }
            if ("ProjectStatus".equalsIgnoreCase(type) && "OK".equalsIgnoreCase(message)) {
                // that folder imported: its earlier failure no longer stands (third review CT3)
                String key = importKey(serverId, root);
                boolean was = importFailed.remove(key) | importWarned.remove(key) | warnedAtReady.remove(key);
                if ((was && saidReady.contains(source)) || reloadOwed.remove(source)) {
                    // a fixed build file re-imported cleanly: say so, or "Updating…" was the last word (CF4)
                    host.setStatus(tr("status.lsp.ready", statusServerName(serverId, root)));
                }
            }
        }
        if (com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)) {
            // lemminx-maven answers initialize long before it can answer a request: its ready line waits for
            // the "Maven initialization done" progress end (Maven review C18).
            for (var status : mavenReadyGate.onStatus(type, message)) {
                narrateServerStatus(source, status.type(), status.message());
            }
            return;
        }
        narrateServerStatus(source, type, message);
        refreshImportNotes(); // an OK import, or a cleared failure
        if ("ProjectStatus".equalsIgnoreCase(type)
                && "OK".equalsIgnoreCase(message)
                && (serverId == null || LspServerRegistry.JAVA_SERVER_ID.equals(serverId))) {
            importBuild.playFromStart(); // one per import: each project of a reactor reports its own
        }
    }

    /**
     * After jdtls imports a project, one quiet incremental build of each Java root with an open file, so the
     * problems that belong to the project rather than a file — a JDK it expects that is not installed, a
     * compliance level the JDK in use does not match — are reported then, not after a Build Project most users
     * never run (Java review C19). With autobuild off nothing else would build it.
     */
    private final javafx.animation.PauseTransition importBuild = importBuildTimer();

    private final java.util.Set<Path> builtAfterImport = new java.util.HashSet<>();

    private javafx.animation.PauseTransition importBuildTimer() {
        var t = new javafx.animation.PauseTransition(javafx.util.Duration.millis(800));
        t.setOnFinished(e -> buildAfterImport());
        return t;
    }

    void buildAfterImport() {
        host.forEachBuffer(b -> {
            Path path = lspPath(b);
            if (path == null
                    || !LspServerRegistry.JAVA_SERVER_ID.equals(serverIdForBuffer(b))
                    || !lspManager.hasJavaSession(path)) {
                return;
            }
            Path root = lspRootFor(b, LspServerRegistry.JAVA_SERVER_ID);
            // Maven only: jdtls compiles a Gradle project into Buildship's bin/, which then shows in `git status`;
            // that is for Build Project, run on purpose, to do (re-review CN3, owner decision).
            if (root != null
                    && hasJavaBuildFile(root)
                    && java.nio.file.Files.isRegularFile(root.resolve("pom.xml"))
                    && builtAfterImport.add(root)) {
                lspManager.buildJavaWorkspace(path, false, outcome -> {});
            }
        });
    }

    /**
     * Whether {@code root} is a Maven or Gradle project — where a JDK or compliance level is declared, the
     * problems the post-import build is for. A loose file's invisible project is left unbuilt: Debug compiles
     * such a file into a folder of its own because jdtls never builds it, and must keep doing so.
     */
    private static boolean hasJavaBuildFile(Path root) {
        if (com.editora.vfs.Vfs.isRemote(root)) {
            return false;
        }
        for (String name :
                List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            if (java.nio.file.Files.isRegularFile(root.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    /** Holds the Maven server's ready status until its own initialization progress ends (C18). */
    private final com.editora.lsp.MavenPomServer.ReadyGate mavenReadyGate =
            new com.editora.lsp.MavenPomServer.ReadyGate();

    private void narrateServerStatus(@Nullable String serverId, String type, @Nullable String message) {
        showStatusLine(statusNarrator.onStatus(serverId, type, message, System.currentTimeMillis()));
        if (statusNarrator.pending()) {
            statusSettle.playFromStart(); // every status restarts the quiet period
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

    /** Turns raw server statuses into conclusive echo lines — above all, one that says startup is done. */
    private final com.editora.lsp.ServerStatusNarrator statusNarrator = new com.editora.lsp.ServerStatusNarrator();

    /** Fires once the servers have been quiet long enough for the narrator to call one ready (or show work). */
    private final javafx.animation.PauseTransition statusSettle = statusSettle();

    private javafx.animation.PauseTransition statusSettle() {
        var settle = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(com.editora.lsp.ServerStatusNarrator.QUIET_MILLIS));
        settle.setOnFinished(e -> {
            if (!ops.lspFeatureEnabled()) {
                return;
            }
            statusNarrator.tick(System.currentTimeMillis()).forEach(this::showStatusLine);
            if (statusNarrator.pending()) {
                settle.playFromStart();
            }
        });
        return settle;
    }

    /**
     * Projects (server and folder) that did not import since the last restart, reload or clean: never "ready" over
     * that. Keyed by folder: in a window of several projects one failed import marked every other ready line.
     */
    private final java.util.Set<String> importFailed = new java.util.HashSet<>();

    /** Projects that imported with build problems (a missing dependency): ready, but said so. */
    private final java.util.Set<String> importWarned = new java.util.HashSet<>();

    /** Of those, the ones whose ready line said so: a later clean import of the folder says ready again (CF4). */
    private final java.util.Set<String> warnedAtReady = new java.util.HashSet<>();

    /** The project reason last said for each failed import, so a re-published error is not said again. */
    private final Map<String, String> saidImportReason = new java.util.HashMap<>();

    /**
     * The server and folder behind each narrated source. Each folder of a server is narrated on its own: in a window
     * of two Java projects their progress interleaved in one stream, and the failed one's verdict gave way to the
     * other's "Loaded and ready" (fourth review CF2).
     */
    private record StatusSource(String serverId, Path root) {}

    private final Map<String, StatusSource> statusSources = new java.util.HashMap<>();

    /** The sources a Reload Project Configuration owes a ready line once their project reports OK (CV3). */
    private final java.util.Set<String> reloadOwed = new java.util.HashSet<>();

    /** The sources whose ready line was said since their server last launched. */
    private final java.util.Set<String> saidReady = new java.util.HashSet<>();

    /** The narrator's id for {@code serverId}'s statuses from {@code root}: the server id alone when no folder. */
    private @Nullable String statusSource(@Nullable String serverId, @Nullable Path root) {
        if (serverId == null || root == null) {
            return serverId;
        }
        Path r = root.toAbsolutePath().normalize();
        String key = serverId + '\u0000' + r;
        statusSources.putIfAbsent(key, new StatusSource(serverId, r));
        return key;
    }

    /** The server's program name, and the project's folder when the window narrates more than one of its folders. */
    private String statusServerName(String serverId, @Nullable Path root) {
        String name = serverProgram(serverId);
        long folders = statusSources.values().stream()
                .filter(src -> src.serverId().equals(serverId) && lspManager.hasLiveSession(serverId, src.root()))
                .count(); // live servers only (CV4), starting ones too: their first lines were unnamed (sixth CS3)
        return root == null || folders < 2 || root.getFileName() == null
                ? name
                : tr("status.lsp.serverInFolder", name, root.getFileName().toString());
    }

    /**
     * Forgets how the Java project at {@code root} imported (and what no known folder said), as its import runs
     * again; true when it had failed. Other projects' outcomes stand (sixth review CS1).
     */
    private boolean forgetImport(@Nullable Path root) {
        boolean failed = false;
        for (String key : root == null
                ? java.util.List.of(importKey(LspServerRegistry.JAVA_SERVER_ID, null))
                : java.util.List.of(
                        importKey(LspServerRegistry.JAVA_SERVER_ID, root),
                        importKey(LspServerRegistry.JAVA_SERVER_ID, null))) {
            failed |= importFailed.remove(key);
            importWarned.remove(key);
            warnedAtReady.remove(key);
            nonProjectFiles.remove(key); // the next import's publishes say again (CO2)
        }
        return failed;
    }

    private static String importKey(@Nullable String serverId, @Nullable Path root) {
        return serverId + "\u0000" + (root == null ? "" : root.toAbsolutePath().normalize());
    }

    /**
     * Why {@code root}'s import failed, in the project's own words: the last line of the error jdtls put on the
     * project (a Gradle build's "Could not find method …"), which beats the generic wrapper its log says first
     * (third review CT7). Null when there is none yet.
     */
    private @Nullable String projectReason(@Nullable Path root) {
        if (root == null) {
            return null;
        }
        Path r = root.toAbsolutePath().normalize();
        for (Map.Entry<Path, List<LspDiagnostic>> e : problems.entrySet()) {
            Path p = e.getKey().toAbsolutePath().normalize();
            if (!LspManager.projectLevel(p) || !(p.equals(r) || r.equals(p.getParent()))) {
                continue;
            }
            for (LspDiagnostic d : e.getValue()) {
                if (d.severity() == LspDiagnostic.Severity.ERROR) {
                    List<String> lines = d.message()
                            .lines()
                            .map(String::strip)
                            .filter(l -> !l.isEmpty())
                            .toList();
                    if (!lines.isEmpty()) {
                        return lines.get(lines.size() - 1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Each Java file's problem counter says when its project did not import, instead of counting what an unimported
     * project's server reports for it (fifth review CV1).
     */
    private void refreshImportNotes() {
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p == null || !LspServerRegistry.JAVA_SERVER_ID.equals(serverIdForBuffer(b))) {
                return;
            }
            Path root = lspManager.javaSessionRoot(p);
            boolean failed = importFailed.contains(importKey(LspServerRegistry.JAVA_SERVER_ID, root))
                    || importFailed.contains(importKey(LspServerRegistry.JAVA_SERVER_ID, null));
            b.problemCounter().setNote(failed ? tr("editor.problemCounter.notImported") : null);
        });
    }

    private void showStatusLine(com.editora.lsp.ServerStatusNarrator.@Nullable Line line) {
        showStatusLineOnly(line);
        refreshImportNotes();
    }

    /**
     * Whether {@code b}'s Java server has said its project is loaded: its first empty publish, before the import and
     * build, read as "no problems" (sixth review AS1). Other servers check a file as they open it.
     */
    private boolean serverSettled(EditorBuffer b) {
        Path p = lspPath(b);
        if (p == null || !LspServerRegistry.JAVA_SERVER_ID.equals(serverIdForBuffer(b))) {
            return true;
        }
        Path root = lspManager.javaSessionRoot(p);
        return root == null || saidReady.contains(statusSource(LspServerRegistry.JAVA_SERVER_ID, root));
    }

    private void showStatusLineOnly(com.editora.lsp.ServerStatusNarrator.@Nullable Line line) {
        if (line != null && line.ready() && line.serverId() != null && !saidReady.contains(line.serverId())) {
            saidReady.add(line.serverId());
            host.forEachBuffer(b -> b.problemCounter().refresh()); // its files' counts are known now (AS1)
        }
        if (line != null) {
            StatusSource source = line.serverId() == null ? null : statusSources.get(line.serverId());
            String serverId = source != null ? source.serverId() : line.serverId();
            Path root = source != null ? source.root() : null;
            String server = serverId == null ? "LSP" : statusServerName(serverId, root);
            String key = importKey(serverId, root);
            if (line.ready() && line.serverId() != null) {
                saidReady.add(line.serverId());
            }
            if (line.ready() && root != null && LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
                nonProjectFiles.getOrDefault(key, Map.of()).values().stream()
                        .findFirst()
                        .ifPresent(message -> nonProjectImport(root, message)); // CO2
            }
            if (line.problem()) {
                // jdtls says WARNING both for an import that threw (a Gradle build that does not run: it logs the
                // exception) and for one that worked with build problems (a missing Maven artifact: nothing
                // logged). Only the first is "did not import" (re-review CN2, third review CT2).
                String logged = serverId == null ? null : lspManager.lastServerError(serverId, root);
                if (logged == null) {
                    importWarned.add(key);
                    if (saidReady.contains(line.serverId())) {
                        host.setStatus(tr("status.lsp.importProblem", server)); // a later edit broke the build
                    } // else said once, by the ready line: mid-import it came twice in two wordings (CF4)
                } else {
                    importFailed.add(key);
                    // the project's diagnostic, with the real cause, comes a moment after jdtls's WARNING: wait for
                    // it, and say the failure once ("Could not find method …", not "Could not fetch model …") (CP3)
                    Runnable say = () -> {
                        String reason = projectReason(root);
                        if (reason != null && reason.equals(saidImportReason.get(key))) {
                            return; // the diagnostic said it already
                        }
                        if (reason != null) {
                            saidImportReason.put(key, reason);
                        }
                        host.setError(tr(
                                "status.lsp.importProblemWhy",
                                server,
                                (reason != null ? reason : logged).replaceFirst("[.\\s]+$", "")));
                    };
                    if (projectReason(root) != null) {
                        say.run();
                    } else {
                        var wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
                        wait.setOnFinished(e -> say.run());
                        wait.play();
                    }
                }
            } else if (line.ready() && importFailed.contains(key)) {
                host.setStatus(tr("status.lsp.readyNotImported", server)); // not "Loaded and ready"; said as error
            } else if (line.ready() && importWarned.remove(key)) {
                warnedAtReady.add(key);
                host.setStatus(tr("status.lsp.readyWithProblems", server));
            } else {
                host.setStatus(
                        line.ready() ? tr("status.lsp.ready", server) : tr("status.lsp.server", server, line.text()));
            }
        }
    }

    /**
     * Stops {@code serverId} while its install is replaced (Doctor's Update), so it does not run on — or, on
     * Windows, hold open — the files being swapped out. The settings apply after the install starts it again, on
     * the new copy, as the files it serves are re-gated.
     */
    void stopServer(String serverId) {
        failingServers.remove(serverId); // the new copy gets a fresh chance
        crashedServers.remove(serverId);
        clearDiagnosticsForServer(serverId); // before shutdown: after it, nothing would re-publish an empty list
        lspManager.shutdownServer(serverId);
    }

    /** Restarts every running server, clears diagnostics, then re-gates each buffer ({@code lsp.restartServers}). */
    void restartServers() {
        startBlocked.clear();
        failingServers.clear();
        crashedServers.clear();
        importFailed.clear();
        nonProjectFiles.clear();
        refreshImportNotes();
        importWarned.clear();
        warnedAtReady.clear();
        statusNarrator.forget(null);
        lspManager.restartAll(); // a Java server shared with another window restarts for both
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
        if (buffer != null && buffer.getPath() != null) {
            lspManager.projectStyleChanged(buffer.getPath()); // .editorconfig, a formatter profile, the build (AN5)
        }
        Path path = buffer == null ? null : lspPath(buffer);
        if (path != null
                && path.getFileName() != null
                && JAVA_BUILD_FILES.contains(path.getFileName().toString())) {
            Path root = lspManager.javaSessionRoot(path);
            if (root != null && importFailed.contains(importKey(LspServerRegistry.JAVA_SERVER_ID, root))) {
                // The build file of a project that did not import was saved, presumably fixed: import it again,
                // as a saved pom.xml re-syncs a project that did (fourth review CT1).
                forgetImport(root);
                restartJavaServer(path);
                host.setStatus(tr("status.lsp.projectReimporting"));
            }
        }
        if (buffer != null && path != null && lspManager.isManaged(path)) {
            // Flush the current open-document state first. savedText may be an older snapshot when the user
            // continued typing during I/O; includeText still describes the bytes that actually reached disk.
            lspManager.changeDocument(path, buffer.getContent());
            lspManager.saveDocument(path, savedText);
            lspManager.pullDiagnostics(path); // no-op for push-only servers
            if ((projectWideProblems || lspManager.otherJavaDocumentOpen(path))
                    && LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))) {
                pendingProjectRecheck.add(path); // one build per session, saves in two projects both checked
                projectRecheck.playFromStart();
            }
        } else if (path != null && path.toString().endsWith(".java") && lspManager.hasJavaSession(path)) {
            // A tab jdtls never opened (restored, or open across a restart, and not shown since): without this the
            // server kept its old text, and the next rename skipped the file (Java review 3, BT2).
            lspManager.notifyWatchedFiles(java.util.List.of(
                    new com.editora.lsp.LspManager.WatchedFile(path, com.editora.lsp.LspManager.WatchedKind.CHANGED)));
        }
    }

    /** Buffers being saved again by {@link #afterUserSave}: that save does not start another round. */
    private final java.util.Set<EditorBuffer> savingAfterActions =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Java actions on save (IntelliJ's Actions on Save): after an explicit save of a Java file served by jdtls,
     * Organize Imports and/or Format Document run as the settings ask, and the file is saved again when they
     * changed it. An ambiguous import is skipped, never asked about; typing in the meantime drops the step, as a
     * stale answer would be wrong for the new text. Never on autosave.
     */
    void afterUserSave(EditorBuffer buffer) {
        boolean cleanup = host.settings().isJavaCleanupOnSave();
        boolean organize = host.settings().isJavaOrganizeImportsOnSave();
        boolean format = host.settings().isJavaFormatOnSave();
        Path path = buffer == null ? null : lspPath(buffer);
        if (buffer == null
                || path == null
                || !(cleanup || organize || format)
                || savingAfterActions.remove(buffer)
                || !buffer.isEditable()
                || !LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))
                // while an import question is open no on-save action runs: a format or clean-up changed the file
                // under it as well, and the answer was dropped though the user typed nothing (eighth review AR1)
                || lspManager.questionOpen(path)) {
            return;
        }
        String saved = buffer.getContent();
        Runnable formatThenSave = () -> {
            if (!format || buffer.isDisposed()) {
                saveIfActionsChanged(buffer, saved);
                return;
            }
            String before = buffer.getContent();
            lspManager.changeDocument(path, buffer.text()); // the formatter must see what Organize Imports left
            com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
            lspManager.formatDocument(path, indent.tabSize(), indent.insertSpaces(), edits -> {
                if (!edits.isEmpty() && buffer.getContent().equals(before) && !buffer.isDisposed()) {
                    buffer.applyLspEdits(edits);
                }
                saveIfActionsChanged(buffer, saved);
            });
        };
        Runnable organizeThenRest = () -> {
            // a question opened during the clean-up: the explicit Organize Imports owns the imports (seventh review
            // AR1)
            if (!organize || buffer.isDisposed() || lspManager.questionOpen(path)) {
                formatThenSave.run();
                return;
            }
            lspManager.changeDocument(path, buffer.text()); // after a clean-up, Organize Imports sees its result
            CodeArea area = buffer.getArea();
            int last = Math.max(0, area.getParagraphs().size() - 1);
            lspManager.organizeImportsQuietly(
                    path, last, area.getParagraph(last).length(), outcome -> formatThenSave.run());
        };
        if (!cleanup) {
            organizeThenRest.run();
            return;
        }
        lspManager.cleanUpJava(path, outcome -> organizeThenRest.run());
    }

    /**
     * {@code lsp.cleanUp} (LSP: Clean Up File): runs the clean-ups chosen in Settings on the active Java file now,
     * as clean-up on save does — {@code @Override} added, redundant modifiers dropped and so on.
     */
    void cleanUpFile() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        if (b == null || path == null || !LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))) {
            host.setStatus(tr("status.lsp.unavailable"));
            return;
        }
        if (!ops.activeEditable()) {
            return;
        }
        if (host.settings().getJavaCleanups().isEmpty()) {
            host.setStatus(tr("status.lsp.cleanupNoneChosen"));
            return;
        }
        lspManager.changeDocument(path, b.text());
        lspManager.cleanUpJava(
                path,
                outcome -> host.setStatus(tr(
                        switch (outcome) {
                            case ORGANIZED -> "status.lsp.cleanedUp";
                            case UNCHANGED -> "status.lsp.cleanupNothing";
                            case UNSUPPORTED, FAILED, STALE -> "status.lsp.cleanupFailed";
                        })));
    }

    /** Saves {@code buffer} again when the actions on save changed what was saved, and nothing else did. */
    private void saveIfActionsChanged(EditorBuffer buffer, String saved) {
        if (buffer.isDisposed() || buffer.getContent().equals(saved) || !buffer.isDirty()) {
            return;
        }
        foldIntoOneUndo(buffer, saved);
        savingAfterActions.add(buffer);
        if (!ops.saveBuffer(buffer)) {
            savingAfterActions.remove(buffer);
        }
    }

    /** Clean-up, Organize Imports and Format, each of which may be an undo step of its own. */
    private static final int SAVE_ACTION_STEPS = 3;

    /**
     * Makes what the actions on save did one undo step: undoing it took one Undo per action, and the states in
     * between were never on disk (re-review AN7). Undoes the actions' steps back to {@code saved}, then puts
     * their result back as a single edit. The history is left as it was when those steps cannot be told apart
     * from the user's own (they do not lead back to {@code saved}) or the buffer shows only part of its text.
     */
    static void foldIntoOneUndo(EditorBuffer buffer, String saved) {
        CodeArea area = buffer.getArea();
        String result = area.getText();
        if (!result.equals(buffer.getContent())) {
            return; // narrowed or filtered: the area is not the whole document
        }
        var undo = area.getUndoManager();
        int caret = area.getCaretPosition();
        int undone = 0;
        while (!area.getText().equals(saved) && undo.isUndoAvailable() && undone < SAVE_ACTION_STEPS) {
            undo.undo();
            undone++;
        }
        if (!area.getText().equals(saved)) {
            for (int i = 0; i < undone && undo.isRedoAvailable(); i++) {
                undo.redo();
            }
            return;
        }
        if (undone < 2) {
            if (undone == 1) {
                undo.redo(); // already one step
            }
            return;
        }
        int prefix = 0;
        int max = Math.min(saved.length(), result.length());
        while (prefix < max && saved.charAt(prefix) == result.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && saved.charAt(saved.length() - 1 - suffix) == result.charAt(result.length() - 1 - suffix)) {
            suffix++;
        }
        undo.preventMerge();
        area.replaceText(prefix, saved.length() - suffix, result.substring(prefix, result.length() - suffix));
        undo.preventMerge();
        area.moveTo(Math.min(caret, area.getLength()));
    }

    /** The file whose save asked for {@link #projectRecheck}; the build goes to its project's server. */
    private final java.util.Set<Path> pendingProjectRecheck = new java.util.LinkedHashSet<>();

    /**
     * While Problems shows the whole project, or another file of the project is open, a Java save is followed by a
     * recheck (debounced): with autobuild off nothing else rechecks the other files, so an error fixed in one file
     * stayed listed for the files that use it until Build Project was run by hand (Java review C7), and an open file
     * calling a method just renamed elsewhere showed no error until it was typed in (eighth review AQ1). The whole
     * project needs an incremental build; open files outside a Maven project are only re-sent, since jdtls compiles a
     * Gradle project into Buildship's bin/ (re-review CN3, tenth review CO1). Quiet: no status.
     */
    private final javafx.animation.PauseTransition projectRecheck = projectRecheck();

    private javafx.animation.PauseTransition projectRecheck() {
        var recheck = new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
        recheck.setOnFinished(e -> {
            List<Path> saved = List.copyOf(pendingProjectRecheck);
            pendingProjectRecheck.clear();
            List<Path> built = new java.util.ArrayList<>();
            for (Path path : saved) {
                if (built.stream().noneMatch(b -> lspManager.sameJavaSession(b, path))
                        && (projectWideProblems || lspManager.otherJavaDocumentOpen(path))
                        && ops.lspFeatureEnabled()) {
                    built.add(path);
                    Path root = lspManager.javaSessionRoot(path);
                    if (projectWideProblems
                            || (root != null && java.nio.file.Files.isRegularFile(root.resolve("pom.xml")))) {
                        lspManager.buildJavaWorkspace(path, false, outcome -> {});
                    } else {
                        lspManager.revalidateOtherJavaDocuments(path);
                    }
                }
            }
        });
        return recheck;
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
            Path path = lspPath(buffer);
            if (path != null && !lspManager.changeDocument(path, text)) {
                // A net-zero edit (type + Backspace, edit + undo): the server was sent nothing and will
                // publish nothing, but the edit already cleared the buffer's marks.
                diagnosticsUnchanged(path);
            }
        });
        // Pull-model diagnostics (fired on the same debounce as didChange; no-op for push-only servers).
        buffer.setLspDiagnosticsRequester(() -> {
            Path path = lspPath(buffer);
            if (path != null) {
                lspManager.pullDiagnostics(path);
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
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                return lspManager.completion(
                        path,
                        line,
                        column,
                        kind,
                        trigger,
                        item -> autoImportAccept(buffer, item),
                        pomCompletionFilter(buffer, line, column, cb));
            }
            cb.accept(com.editora.completion.CompletionResult.EMPTY);
            return () -> {};
        });
        // Lazy documentation for the completion doc side-popup: resolve the item's docs on demand.
        buffer.setCompletionDocResolver((token, cb) -> {
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                lspManager.resolveCompletionDoc(path, token, cb);
            } else {
                cb.accept(null);
            }
        });
        buffer.setCompletionDocEnabled(host.settings().isCompletionDoc());
        // Tab re-indents the current line to the server's convention via range formatting (when supported).
        buffer.setLspRangeFormatter((sl, sc, el, ec, cb) -> {
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
                buffer.sendLspChange(); // the didChange debounce may be pending: format the text on screen
                lspManager.rangeFormatting(path, sl, sc, el, ec, indent.tabSize(), indent.insertSpaces(), cb);
            } else {
                cb.accept(java.util.List.of());
            }
        });
        // Paste auto-import (#742): ask jdtls what the pasted code needs; only jdtls advertises the
        // command, so any other server (or language) drops out inside handlePasteEvent's capability gate.
        // The gutter lightbulb (IntelliJ's intention bulb): once the caret rests, what the server offers there — a
        // quick fix lights it as a fix, a quick action as an assist; refactorings and source actions, offered
        // nearly everywhere, do not. Clicking it is Alt+Enter.
        buffer.codeActionBulb().setProbe((line, column, answer) -> {
            Path path = lspPath(buffer);
            if (path == null || !lspManager.isManaged(path) || !lspManager.supportsCodeActions(path)) {
                answer.accept(null);
                return;
            }
            buffer.sendLspChange(); // the server must have the text the caret is in
            lspManager.codeActions(path, line, column, line, column, BULB_KINDS, all -> answer.accept(bulbKind(all)));
        });
        // the counter's own file, whichever editor group is active: its arrows moved another file's caret (AV2)
        buffer.problemCounter()
                .setActions(
                        () -> ops.executeCommand("tool.problems"),
                        () -> gotoProblem(buffer, false),
                        () -> gotoProblem(buffer, true));
        buffer.problemCounter().setSettled(() -> serverSettled(buffer));
        buffer.setLspPasteImportsRequester((sl, sc, el, ec, text, stillValid) -> {
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
                lspManager.handlePasteEvent(
                        path, sl, sc, el, ec, text, indent.tabSize(), indent.insertSpaces(), stillValid, applied -> {});
            }
        });
        // Smart-semicolon detection (#746): where does a ';' typed here belong?
        buffer.setLspSmartSemicolonRequester((line, character, cb) -> {
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                lspManager.smartSemicolonPosition(path, line, character, cb);
            } else {
                cb.accept(null);
            }
        });
        // On-type formatting (#740): re-indent the line after a server-declared trigger character.
        buffer.setLspOnTypeFormatter((line, character, ch, cb) -> {
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                com.editora.lsp.FormattingIndent indent = formattingIndent(buffer);
                buffer.sendLspChange(); // the server must have the character that triggered this
                lspManager.onTypeFormatting(path, line, character, ch, indent.tabSize(), indent.insertSpaces(), cb);
            } else {
                cb.accept(java.util.List.of());
            }
        });
        buffer.setLspCommandRunner(id -> {
            switch (id) {
                case "lsp.peekDefinition" -> peekDefinition();
                case "lsp.refactorThis" -> refactorActions();
                case "lsp.generate" -> generateActions();
                case "lsp.callHierarchy" -> callHierarchy();
                case "lsp.typeHierarchy" -> typeHierarchy();
                case "lsp.gotoSuperMethod" -> gotoSuperMethod();
                default -> {}
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

    /**
     * Asks the user what a language server asks ({@code window/showMessageRequest}, Metals' input box and quick
     * pick) and carries out the editor actions it requests. Every method runs on the FX thread and returns at
     * once; the dialog completes the answer when the user closes it.
     */
    private final class ServerPrompts implements com.editora.lsp.LspClientUi {

        @Override
        public java.util.concurrent.CompletableFuture<String> chooseAction(
                String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions) {
            var answer = new java.util.concurrent.CompletableFuture<String>();
            List<javafx.scene.control.ButtonType> buttons = new java.util.ArrayList<>();
            for (String action : actions) {
                buttons.add(
                        new javafx.scene.control.ButtonType(action, javafx.scene.control.ButtonBar.ButtonData.OTHER));
            }
            buttons.add(javafx.scene.control.ButtonType.CLOSE);
            javafx.scene.control.Alert alert = Dialogs.styled(new javafx.scene.control.Alert(
                    alertType(type), message, buttons.toArray(javafx.scene.control.ButtonType[]::new)));
            alert.initOwner(host.window());
            alert.initModality(javafx.stage.Modality.NONE); // the editor stays usable while it is asked
            alert.setTitle(serverLabel(serverId));
            alert.setHeaderText(null);
            alert.getDialogPane().setMinWidth(480);
            alert.setOnHidden(e -> {
                javafx.scene.control.ButtonType chosen = alert.getResult();
                answer.complete(
                        chosen == null || chosen == javafx.scene.control.ButtonType.CLOSE ? null : chosen.getText());
            });
            alert.show();
            return answer;
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> inputBox(
                String serverId, @Nullable String prompt, @Nullable String value, @Nullable String placeHolder) {
            var answer = new java.util.concurrent.CompletableFuture<String>();
            javafx.scene.control.TextInputDialog dialog =
                    Dialogs.styled(new javafx.scene.control.TextInputDialog(value == null ? "" : value));
            dialog.initOwner(host.window());
            dialog.setTitle(serverLabel(serverId));
            dialog.setHeaderText(prompt == null || prompt.isBlank() ? null : prompt);
            if (placeHolder != null) {
                dialog.getEditor().setPromptText(placeHolder);
            }
            dialog.setOnHidden(e -> answer.complete(dialog.getResult()));
            dialog.show();
            return answer;
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> quickPick(
                String serverId, @Nullable String placeHolder, List<PickItem> items) {
            var answer = new java.util.concurrent.CompletableFuture<String>();
            // The question goes in the title: as the field's placeholder it hid while the field had focus, so a
            // run of import choices asked nothing anyone could see (third review AT4).
            String question = placeHolder == null || placeHolder.isBlank() ? null : placeHolder;
            QuickOpen<PickItem> picker = new QuickOpen<>(
                    question == null ? serverLabel(serverId) : question + "  ·  " + serverLabel(serverId),
                    question == null ? tr("palette.setting.pick") : question,
                    () -> items,
                    PickItem::label,
                    item -> item.description() == null ? "" : item.description(),
                    chosen -> answer.complete(chosen == null ? null : chosen.id()));
            // No preview; the cancel hook is what tells the server the user chose nothing.
            picker.setPreview(item -> {}, () -> answer.complete(null));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
            // Cancelled: the request that asked has ended, so the question goes too (fifth review DV1).
            answer.whenComplete((value, error) -> {
                if (answer.isCancelled()) {
                    Platform.runLater(picker::hide);
                }
            });
            return answer;
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> chooseImport(
                String serverId, String simpleName, int line, List<PickItem> items) {
            return quickPick(serverId, tr("picker.lsp.chooseImport", simpleName, line + 1), items);
        }

        @Override
        public void openLocation(String serverId, Path file, int line, int column) {
            ops.openAndGoto(file, line, column);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Object> clientCommand(
                String serverId, String command, List<Object> arguments) {
            return xml.clientCommand(serverId, command, arguments);
        }

        private javafx.scene.control.Alert.AlertType alertType(org.eclipse.lsp4j.MessageType type) {
            if (type == null) {
                return javafx.scene.control.Alert.AlertType.INFORMATION;
            }
            return switch (type) {
                case Error -> javafx.scene.control.Alert.AlertType.ERROR;
                case Warning -> javafx.scene.control.Alert.AlertType.WARNING;
                default -> javafx.scene.control.Alert.AlertType.INFORMATION;
            };
        }
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
                    com.google.gson.JsonElement status = plan.status(); // none: JsonNull, read as no constructors
                    lspManager.jdtlsGenerateApply(
                            path,
                            kind,
                            params,
                            status != null ? status : com.google.gson.JsonNull.INSTANCE,
                            item.expectedDocuments(),
                            chosen,
                            superChosen,
                            ok -> reportEdit(
                                    ok,
                                    tr("status.lsp.codeActionApplied", bareTitle(item.title())),
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
        applyJdtlsEdit(path, item, method, params, null);
    }

    /** As above; {@code onRename} gets the place of the new name the server suggests renaming. */
    private void applyJdtlsEdit(
            Path path,
            LspManager.CodeActionItem item,
            String method,
            Object params,
            java.util.function.@Nullable Consumer<JdtlsRefactor.RenameAt> onRename) {
        beginReportedEdit();
        lspManager.jdtlsApplyEdit(path, method, params, item.expectedDocuments(), null, onRename, (ok, refused) -> {
            if (!ok && refused != null) {
                host.setError(refused); // the server's own reason beats "could not apply"
                return;
            }
            reportEdit(
                    ok,
                    tr("status.lsp.codeActionApplied", bareTitle(item.title())),
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
            default -> extractRefactor(path, item, request);
        }
        return true;
    }

    /**
     * A refactoring whose edit is one {@code java/getRefactorEdit} away, after at most two questions: which
     * expression, when it was offered at a bare caret ({@code java/inferSelection}), and where an extracted
     * field is initialized. Most refactorings ask neither.
     */
    private void extractRefactor(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        java.util.function.BiConsumer<@Nullable String, JdtlsRefactor.@Nullable Selection> apply =
                (scope, selection) -> applyJdtlsEdit(
                        path,
                        item,
                        "java/getRefactorEdit",
                        refactorEdit(request, JdtlsRefactor.extractArguments(scope, selection)),
                        at -> renameExtracted(path, at));
        if (!JdtlsRefactor.needsInferredSelection(request)) {
            chooseInitializeScope(item, JdtlsRefactor.initializedScopes(request), scope -> apply.accept(scope, null));
            return;
        }
        lspManager.jdtlsRequest(
                path,
                "java/inferSelection",
                JdtlsRefactor.inferSelectionParams(request.name(), request.params()),
                answer -> {
                    List<JdtlsRefactor.Selection> found = JdtlsRefactor.selections(answer);
                    if (found.isEmpty()) {
                        host.setStatus(tr("status.lsp.refactorNoExpression", item.title()));
                        return;
                    }
                    java.util.function.Consumer<JdtlsRefactor.Selection> chosen = selection -> chooseInitializeScope(
                            item, selection.initializedScopes(), scope -> apply.accept(scope, selection));
                    if (found.size() == 1) {
                        chosen.accept(found.get(0));
                    } else {
                        pickOne(
                                item.title(),
                                tr("picker.refactor.expression"),
                                found,
                                JdtlsRefactor.Selection::label,
                                selection -> "",
                                chosen);
                    }
                });
    }

    /**
     * After an extract, Rename on the name the server picked ({@code d}, {@code getTotal}, {@code string}), as
     * IntelliJ and vscode-java start an in-place rename there (Java review B12). Only in the file the
     * refactoring started from, while it is still the active tab; the selection the edit left is cleared.
     */
    private void renameExtracted(Path path, JdtlsRefactor.RenameAt at) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !path.equals(lspPath(b)) || !path.equals(lspManager.pathForServerUri(path, at.uri()))) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        if (at.offset() < 0 || at.offset() + at.length() > area.getLength()) {
            return;
        }
        area.moveTo(at.offset());
        area.requestFollowCaret();
        rename();
    }

    /** Asks where a field is initialized when the server offers more than one place; none or one is not asked. */
    private void chooseInitializeScope(
            LspManager.CodeActionItem item, List<String> scopes, java.util.function.Consumer<@Nullable String> then) {
        if (scopes.size() <= 1) {
            then.accept(scopes.isEmpty() ? null : scopes.get(0));
            return;
        }
        pickOne(
                item.title(),
                tr("picker.refactor.initializeIn"),
                scopes,
                LspCoordinator::initializeScopeLabel, // jdtls's scope names, translated (third review DN5)
                s -> "",
                then::accept);
    }

    /** jdtls's {@code InitializeScope} name in the UI language; the English value is what goes back (DN5). */
    static String initializeScopeLabel(String scope) {
        return switch (scope) {
            case "Field declaration" -> tr("picker.refactor.scope.field");
            case "Current method" -> tr("picker.refactor.scope.method");
            case "Class constructors" -> tr("picker.refactor.scope.constructors");
            default -> scope;
        };
    }

    /**
     * {@code java/move} with {@code params}. When jdtls finds problems it answers with them and a token instead
     * of an edit; the user sees them and the move is applied only if they continue.
     */
    private void applyJdtlsMove(Path path, LspManager.CodeActionItem item, com.google.gson.JsonObject params) {
        applyJdtlsMove(path, item, params, () -> {});
    }

    /** As above; {@code onApplied} runs once the move is applied. */
    private void applyJdtlsMove(
            Path path, LspManager.CodeActionItem item, com.google.gson.JsonObject params, Runnable onApplied) {
        beginReportedEdit();
        lspManager.jdtlsApplyEdit(
                path,
                "java/move",
                params,
                item.expectedDocuments(),
                (problems, proceed) -> {
                    if (moveProblemsConfirmer.test(problems)) {
                        proceed.run();
                    } else {
                        host.setStatus(tr("status.lsp.refactorCancelled", item.title()));
                    }
                },
                null,
                refactorPreview(item.title()),
                (ok, refused) -> {
                    if (!ok && refused != null) {
                        host.setError(refused);
                        return;
                    }
                    if (ok) {
                        onApplied.run();
                    }
                    reportEdit(
                            ok,
                            tr("status.lsp.codeActionApplied", bareTitle(item.title())),
                            tr("status.lsp.codeActionFailed", item.title()));
                });
    }

    /** Asks whether to move despite the problems jdtls found; a field so a test can answer. */
    java.util.function.Predicate<String> moveProblemsConfirmer = this::confirmMoveProblems;

    private boolean confirmMoveProblems(String problems) {
        javafx.scene.control.ButtonType proceed = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.moveProblems.continue"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING,
                problems,
                javafx.scene.control.ButtonType.CANCEL,
                proceed);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.moveProblems.title"));
        confirm.setHeaderText(tr("dialog.lsp.moveProblems.header"));
        confirm.getDialogPane().setMinWidth(520);
        // The move may break code: Enter cancels.
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(proceed)).setDefaultButton(false);
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL))
                .setDefaultButton(true);
        return confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == proceed;
    }

    private com.google.gson.JsonObject refactorEdit(
            JdtlsRefactor.Request request, com.google.gson.@Nullable JsonArray arguments) {
        EditorBuffer buffer = activeLspBuffer();
        com.editora.lsp.FormattingIndent indent = buffer == null
                ? new com.editora.lsp.FormattingIndent(projectScope.tabSize(host.settings()), true)
                : formattingIndent(buffer);
        return JdtlsRefactor.refactorEditParams(
                request.name(), request.params(), indent.tabSize(), indent.insertSpaces(), arguments);
    }

    /** Asks the server where {@code source} can move, lets the user choose, and moves it there. */
    private void moveTo(
            Path path,
            LspManager.CodeActionItem item,
            String moveKind,
            @Nullable String source,
            com.google.gson.@Nullable JsonElement params,
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
                    String sourceRoot =
                            "moveResource".equals(moveKind) ? JdtlsRefactor.currentSourceRoot(answer) : null;
                    List<JdtlsRefactor.Destination> rows = new java.util.ArrayList<>(targets);
                    if (!JdtlsRefactor.inTestRoot(path.toString())) {
                        // main code does not move into src/test: those packages are for tests (B20)
                        rows.removeIf(d -> JdtlsRefactor.inTestRoot(d.detail()));
                    }
                    JdtlsRefactor.Destination newPackage = new JdtlsRefactor.Destination(
                            tr("picker.refactor.newPackage"), "", com.google.gson.JsonNull.INSTANCE);
                    if (sourceRoot != null && !com.editora.vfs.Vfs.isRemote(path)) {
                        rows.add(newPackage); // a package that does not exist yet (Java review B20)
                    }
                    pickDestination(item, prompt, rows, target -> {
                        if (target == newPackage && sourceRoot != null) {
                            moveToNewPackage(path, item, source, Path.of(sourceRoot));
                        } else {
                            applyJdtlsMove(
                                    path, item, JdtlsRefactor.moveParams(moveKind, source, params, target.raw()));
                        }
                    });
                });
    }

    /**
     * Move into a package that does not exist yet, typed as IntelliJ's Move Class takes it: its folder is
     * created under the current source root and announced to the server, which moves the file there by URI.
     * Before, the package's folder had to be made in the Project tree first.
     */
    private void moveToNewPackage(Path path, LspManager.CodeActionItem item, @Nullable String source, Path root) {
        host.promptText(item.title(), tr("prompt.refactor.newPackage"), "", typed -> {
            String name = typed == null ? "" : typed.strip();
            if (!JdtlsRefactor.isPackageName(name)) {
                host.setError(tr("status.lsp.refactorBadPackage", name));
                return;
            }
            if (!java.nio.file.Files.isDirectory(root)) { // never create a folder tree somewhere else (BN2)
                host.setError(tr("status.lsp.refactorPackageNotCreated", name, root.toString()));
                return;
            }
            Path folder = root.resolve(name.replace('.', java.io.File.separatorChar));
            try {
                java.nio.file.Files.createDirectories(folder);
            } catch (java.io.IOException | RuntimeException e) {
                host.setError(tr("status.lsp.refactorPackageNotCreated", name, String.valueOf(e.getMessage())));
                return;
            }
            lspManager.notifyWatchedFiles(List.of(new LspManager.WatchedFile(folder, LspManager.WatchedKind.CREATED)));
            com.google.gson.JsonObject params = JdtlsRefactor.moveParams("moveResource", source, null, null);
            params.addProperty("destination", folder.toUri().toString());
            AppliedEdit before = lastApplied;
            applyJdtlsMove(path, item, params, () -> {
                if (lastApplied != null && lastApplied != before) {
                    newPackageFolders.put(lastApplied, new Path[] {folder, root}); // undo takes the folder back too
                }
            });
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
                            target -> applyJdtlsMove(
                                    path,
                                    item,
                                    JdtlsRefactor.moveParams(
                                            moveKind, request.documentUri(), request.params(), target.raw())));
                });
    }

    /** Move Type: into a file of its own, or into another class — asked only when both are possible. */
    private void moveType(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        Runnable newFile = () -> applyJdtlsMove(
                path,
                item,
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
        pickOne(
                item.title(),
                prompt,
                targets,
                JdtlsRefactor.Destination::label,
                d -> d.detail() == null ? "" : destinationDetail(d.detail()),
                onChoose);
    }

    /** A destination's folder relative to the project when it is a path there (re-review BN8), else as given. */
    private String destinationDetail(String detail) {
        try {
            Path p = Path.of(detail);
            return p.isAbsolute() && java.nio.file.Files.exists(p) ? projectRelative(p) : detail;
        } catch (java.nio.file.InvalidPathException e) {
            return detail;
        }
    }

    /** Extract Interface: which methods, the interface's name, and the package it is created in. */
    private void extractInterface(Path path, LspManager.CodeActionItem item, JdtlsRefactor.Request request) {
        lspManager.jdtlsRequest(path, "java/checkExtractInterfaceStatus", request.params(), status -> {
            List<JdtlsGenerate.Candidate> members = JdtlsRefactor.interfaceMembers(status);
            List<JdtlsRefactor.Destination> offered = JdtlsRefactor.interfacePackages(status);
            List<JdtlsRefactor.Destination> mainOnly = JdtlsRefactor.inTestRoot(path.toString())
                    ? offered
                    : offered.stream()
                            .filter(d -> !JdtlsRefactor.inTestRoot(d.detail()))
                            .toList();
            // main code's interface is not offered src/test/java, as Move already does (third review BT4)
            List<JdtlsRefactor.Destination> packages = mainOnly.isEmpty() ? offered : mainOnly;
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
                beginReportedEdit();
                lspManager.jdtlsApplyEdit(
                        path,
                        "java/getRefactorEdit",
                        refactorEdit(request, arguments),
                        item.expectedDocuments(),
                        null,
                        null,
                        refactorPreview(item.title()),
                        (ok, why) -> {
                            if (!ok && why != null) {
                                host.setError(why);
                                return;
                            }
                            reportEdit(
                                    ok,
                                    tr("status.lsp.codeActionApplied", bareTitle(item.title())),
                                    tr("status.lsp.codeActionFailed", item.title()));
                        });
            });
        });
    }

    /**
     * The preview step of a refactoring that changes files the user cannot see — Change Signature updating
     * every caller, Move rewriting imports — which before applied unseen (Java review B20). The rename
     * preview's card: each file ticked, untick to leave it out; an edit confined to this file applies at once.
     */
    private java.util.function.BiConsumer<
                    com.editora.lsp.WorkspaceEditMapper.Mapped,
                    java.util.function.Consumer<com.editora.lsp.WorkspaceEditMapper.Mapped>>
            refactorPreview(String title) {
        return (mapped, apply) -> withDiskView(resourcePaths(mapped), files -> {
            if (!com.editora.lsp.RenamePreview.worthPreviewing(mapped, workspaceDisk(files))) {
                apply.accept(mapped);
                return;
            }
            java.util.List<MultiSelectPicker.Item<Path>> rows = new java.util.ArrayList<>();
            java.util.Set<Path> listed = new java.util.LinkedHashSet<>();
            for (com.editora.lsp.RenamePreview.FileChange change :
                    com.editora.lsp.RenamePreview.summarise(mapped, workspaceDisk(files))) {
                rows.add(new MultiSelectPicker.Item<>(previewLabel(change), true, change.file()));
                listed.add(change.file());
            }
            MultiSelectPicker.show(
                    host.overlayHost(),
                    tr(
                            "lsp.refactor.previewTitle",
                            title.replaceAll("\\.\\.\\.$|…$", ""),
                            com.editora.lsp.RenamePreview.totalEdits(mapped),
                            rows.size()),
                    rows,
                    tr("picker.hint.apply"),
                    keep -> {
                        var kept = new java.util.LinkedHashSet<>(keep);
                        com.editora.lsp.WorkspaceEditMapper.Mapped filtered = java.util.Objects.requireNonNull(
                                com.editora.lsp.RenamePreview.filter(mapped, kept, listed),
                                "filter is null only for a null edit");
                        List<Path> covered = com.editora.lsp.RenamePreview.excludedButDeleted(filtered, kept, listed);
                        if (!covered.isEmpty()) {
                            editRefused(covered, tr("status.lsp.renameDeleteCoversKept", blockedFileNames(covered)));
                            return;
                        }
                        apply.accept(filtered);
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
        Path path = lspPath(b);
        if (path == null) {
            return;
        }
        if (lspManager.questionOpen(path)) {
            // jdtls answers a second organize with no edit while the first waits on the user: that said "Imports
            // already organized" beside the open question (sixth review AS2)
            host.setStatus(tr("status.lsp.answerImportQuestion"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        int lastLine = Math.max(0, area.getParagraphs().size() - 1);
        int lastChar = area.getParagraph(lastLine).length();
        lspManager.changeDocument(path, b.text()); // the server organizes the text it has
        lspManager.organizeImportsAny(path, lastLine, lastChar, outcome -> {
            if (outcome == LspManager.OrganizeOutcome.FAILED) {
                host.setError(tr("status.lsp.importsFailed"));
                return;
            }
            host.setStatus(tr(
                    switch (outcome) {
                        case ORGANIZED -> "status.lsp.importsOrganized";
                        case UNCHANGED -> "status.lsp.importsUnchanged";
                        case UNSUPPORTED, FAILED -> "status.lsp.importsUnsupported";
                        case STALE -> "status.lsp.importsStale"; // the file changed under the question (AR1)
                    }));
        });
    }

    /** Copies the fully qualified name of the symbol at the caret ({@code java.getFullyQualifiedName}). */
    void copyQualifiedName() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lspPath(b);
        if (path == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(path, b.text());
        lspManager.fullyQualifiedName(path, area.getCurrentParagraph(), area.getCaretColumn(), name -> {
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
        EditorBuffer b = host.activeBuffer(); // a pom.xml tab counts, whichever server (if any) edits it
        Path path = b == null ? null : lspPath(b);
        // The Java project's root, also from a build-file tab no Java server edits: by that tab's own server it was
        // no root at all, so the failure was missed and the project not imported again (fourth review CT1).
        Path root = path == null ? null : lspManager.javaSessionRoot(path);
        // This project's import runs again, its outcome said afresh; another project's failure stands: cleared for
        // the window, it was lost, and its fixed build file no longer re-imported (sixth review CS1)
        boolean failed = forgetImport(root);
        refreshImportNotes();
        if (path == null) {
            host.setStatus(tr("status.lsp.unavailable"));
            return;
        }
        if (failed && lspManager.hasJavaSession(path)) {
            // An import that threw made no Java project, so java/projectConfigurationUpdate updated nothing:
            // Problems emptied and no file was checked again (third review CT1). Start the server over instead.
            restartJavaServer(path);
            host.setStatus(tr("status.lsp.projectReimporting"));
            return;
        }
        if (lspManager.reloadProjectConfiguration(path)) {
            String source = statusSource(LspServerRegistry.JAVA_SERVER_ID, root);
            if (source != null) {
                reloadOwed.add(source); // the update ends on ready, not on "Updating project configurations…"
            }
            host.setStatus(tr("status.lsp.projectReloading"));
        } else {
            host.setStatus(tr("status.lsp.unavailable"));
        }
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

    /**
     * Clean Java Workspace and Restart ({@code lsp.cleanJavaWorkspace}): the active file's Java project starts
     * over with a fresh jdtls workspace (Java review C14), the cure for a stale index or classpath.
     */
    void cleanJavaWorkspace() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        if (path == null || !lspManager.hasJavaSession(path)) {
            host.setStatus(tr("status.lsp.unavailable"));
            return;
        }
        host.setStatus(tr("status.lsp.javaWorkspaceCleaning"));
        // this project's only: Clean in one project emptied another's build error from Problems (seventh review CR4)
        Path cleaned = lspManager.javaSessionRoot(path);
        Path under = cleaned == null ? null : cleaned.toAbsolutePath().normalize();
        correctedSourceRoots.removeIf(
                r -> under == null || r.toAbsolutePath().normalize().startsWith(under));
        builtAfterImport.removeIf(
                r -> under == null || r.toAbsolutePath().normalize().startsWith(under));
        announcedProjectProblems.removeIf(k -> under == null || k.startsWith(under.toString()));
        forgetImport(cleaned); // its fresh import says its own outcome (CT3); others stand
        refreshImportNotes();
        java.util.List<EditorBuffer> java = javaBuffersOf(path);
        clearProjectProblems(cleaned); // the dead server's "project:1" entries would stay for good
        lspManager.cleanJavaWorkspace(path, deleted -> {
            host.setStatus(tr(deleted ? "status.lsp.javaWorkspaceCleaned" : "status.lsp.javaWorkspaceKept"));
            applyGating();
            // Reopen the project's files now, not when each tab is next shown: run from a pom.xml or build.gradle
            // tab, nothing started the server again (third review CT5).
            reopenNow(java);
        });
    }

    /** Restarts {@code path}'s Java server in this window, its open Java files reopened on the new one. */
    private void restartJavaServer(Path path) {
        Path root = lspManager.javaSessionRoot(path);
        java.util.List<EditorBuffer> java = javaBuffersOf(path, root);
        if (root == null) {
            statusNarrator.forget(LspServerRegistry.JAVA_SERVER_ID);
            clearProjectProblems(null);
            stopServer(LspServerRegistry.JAVA_SERVER_ID);
        } else {
            // only the project being re-imported: another project's server keeps running (fifth review CV2)
            String source = statusSource(LspServerRegistry.JAVA_SERVER_ID, root);
            statusNarrator.forget(source == null ? LspServerRegistry.JAVA_SERVER_ID : source);
            clearProjectProblems(root);
            java.forEach(b -> clearDiagnostics(lspPath(b)));
            lspManager.shutdownServerAt(LspServerRegistry.JAVA_SERVER_ID, root);
        }
        applyGating();
        reopenNow(java);
    }

    /**
     * The open Java files of {@code path}'s project: those its Java server has open, else, from a build file,
     * the {@code .java} files under the build file's folder.
     */
    private java.util.List<EditorBuffer> javaBuffersOf(Path path) {
        return javaBuffersOf(path, null);
    }

    /** As above, only the files of the project at {@code root} (null: every Java file). */
    private java.util.List<EditorBuffer> javaBuffersOf(Path path, @Nullable Path root) {
        java.util.List<EditorBuffer> out = new java.util.ArrayList<>();
        Path dir = path.getParent();
        Path only = root == null ? null : root.toAbsolutePath().normalize();
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p == null || (only != null && !p.toAbsolutePath().normalize().startsWith(only))) {
                return;
            }
            boolean served = LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(p));
            boolean underBuildFile = dir != null && p.toString().endsWith(".java") && p.startsWith(dir);
            if (served || underBuildFile) {
                out.add(b);
            }
        });
        return out;
    }

    /** Syncs {@code buffers} at once, shown or not: their server starts (or they join it) now. */
    private void reopenNow(java.util.List<EditorBuffer> buffers) {
        for (EditorBuffer b : buffers) {
            if (!b.isDisposed()) {
                deferredLsp.remove(b);
                syncBuffer(b);
            }
        }
    }

    /** Drops the Problems entries of projects (rather than files): their server is going away. */
    private void clearProjectProblems() {
        clearProjectProblems(null);
    }

    /** As above, those of the project at {@code root} only (null: every project's). */
    private void clearProjectProblems(@Nullable Path root) {
        Path only = root == null ? null : root.toAbsolutePath().normalize();
        // a project nested in this one has its own problems: they are not this one's to clear (seventh review CR4)
        java.util.List<Path> nested = only == null
                ? java.util.List.of()
                : statusSources.values().stream()
                        .map(StatusSource::root)
                        .filter(r -> r.startsWith(only) && !r.equals(only))
                        .toList();
        for (Path p : java.util.List.copyOf(problems.keySet())) {
            Path at = p.toAbsolutePath().normalize();
            if (LspManager.projectLevel(p)
                    && bufferForLspPath(p) == null
                    && (only == null || (at.startsWith(only) && nested.stream().noneMatch(at::startsWith)))) {
                clearDiagnostics(p);
            }
        }
    }

    /** Java roots already offered a Lombok download in this window. */
    private final java.util.Set<Path> lombokOffered = new java.util.HashSet<>();

    /** Asks whether to download Lombok for the project at a root; a field so a test can answer. */
    java.util.function.Function<Path, java.util.concurrent.CompletableFuture<Boolean>> lombokAsker =
            root -> new ServerPrompts()
                    .chooseAction(
                            LspServerRegistry.JAVA_SERVER_ID,
                            org.eclipse.lsp4j.MessageType.Info,
                            tr("dialog.lombok.missing", PathDisplay.fileName(root)),
                            List.of(tr("dialog.lombok.download")))
                    .thenApply(chosen -> tr("dialog.lombok.download").equals(chosen));

    /** Downloads Lombok into a folder and returns the jar; a field so a test need not reach Maven Central. */
    interface LombokInstaller {
        Path install(Path dir) throws Exception;
    }

    LombokInstaller lombokInstaller = dir -> new com.editora.install.InstallService().installLombok(dir);

    /**
     * A project's build declares Lombok but no build has downloaded it yet, so jdtls started without it and every
     * Lombok-generated member is an error: offer the pinned jar (once per root and window), and restart the Java
     * server with it.
     */
    void offerLombok(Path root) {
        com.editora.config.ConfigManager config = ops.config();
        if (config == null || !lombokOffered.add(root)) {
            return;
        }
        Path dir = config.getConfigDir().resolve(com.editora.install.InstallCatalog.LOMBOK_SUBPATH);
        lombokAsker.apply(root).thenAccept(yes -> {
            if (!Boolean.TRUE.equals(yes)) {
                return;
            }
            javafx.application.Platform.runLater(() -> host.setStatus(tr("status.lombok.downloading")));
            Thread.ofVirtual().name("lombok-download").start(() -> {
                try {
                    Path jar = lombokInstaller.install(dir);
                    javafx.application.Platform.runLater(() -> {
                        host.setStatus(tr("status.lombok.installed", jar.getFileName()));
                        lspManager.restartServer(LspServerRegistry.JAVA_SERVER_ID);
                        applyGating(); // the project's files reopen on a server that has the agent
                    });
                } catch (Exception e) {
                    String why = String.valueOf(e.getMessage());
                    javafx.application.Platform.runLater(() -> host.setError(tr("status.lombok.failed", why)));
                }
            });
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
    private @Nullable EditorBuffer activeLspBuffer() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || lspPath(b) == null || !lspManager.isManaged(lspPath(b))) {
            host.setStatus(tr("status.lsp.unavailable"));
            return null;
        }
        return b;
    }

    /**
     * Where a position request from the active tab goes: a workspace file to its own session, or a library
     * ({@code jdt://}) source tab to the session that handed the source out, about its URI. Only Go to
     * Definition did that; hover, references, implementation and the hierarchies said "No language server
     * for this file" inside {@code ArrayList.java} (Java review B7). Null, with that status said, when neither.
     */
    private @Nullable NavOrigin navOrigin() {
        EditorBuffer active = host.activeBuffer();
        LibrarySource lib = active == null ? null : librarySources.get(active);
        if (active != null
                && lib != null
                && lib.anchor() != null
                && lib.uri() != null
                && lspManager.isManaged(lib.anchor())) {
            return new NavOrigin(active, lib.anchor(), lib.uri());
        }
        EditorBuffer b = activeLspBuffer();
        Path path = b == null ? null : lspPath(b);
        return b == null || path == null ? null : new NavOrigin(b, path, null);
    }

    /**
     * A request's origin: the tab, the file whose session answers, and the {@code jdt://} URI asked about when
     * the tab is library source (null for the file itself).
     */
    private record NavOrigin(
            EditorBuffer buffer, Path anchor, @Nullable String libraryUri) {
        /** The path {@link #navigationRequestCurrent} compares: none for a library tab. */
        @Nullable
        Path ownPath() {
            return libraryUri == null ? anchor : null;
        }
    }

    /** Sends the tab's latest text before a request (a library tab is read-only: nothing to send). */
    private void syncForRequest(NavOrigin origin) {
        if (origin.libraryUri() == null) {
            lspManager.changeDocument(origin.anchor(), origin.buffer().text());
        }
    }

    /** Reads a peeked file off the FX thread; peek is user-initiated, so one lazy thread is plenty. */
    private static final java.util.concurrent.ExecutorService PEEK_READ =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "peek-read");
                t.setDaemon(true);
                return t;
            });

    private @Nullable PeekPopup peekPopup;

    /**
     * {@code lsp.peekDefinition}: show the definition here rather than going to it.
     *
     * <p>The same request as {@link #gotoDefinition()}, answered without moving. Most uses of
     * go-to-definition are a question — what is this, what does it take — and the answer does not justify
     * losing your place, your scroll position and usually a tab. Enter in the popup still commits to the
     * real jump, so nothing is taken away.
     */
    void peekDefinition() {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path originPath = origin.ownPath();
        Path anchor = origin.anchor();
        long originVersion = b.docVersion();
        long requestGeneration = ++navigationRequestGeneration;
        syncForRequest(origin);
        lspManager.definitionAt(
                anchor, origin.libraryUri(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
                    if (!navigationRequestCurrent(b, originPath, originVersion, requestGeneration)) {
                        return;
                    }
                    if (targets.isEmpty()) {
                        host.setStatus(tr("status.lsp.noDefinition"));
                        return;
                    }
                    LspManager.Target t = targets.get(0);
                    Path file = t.file();
                    if (file == null) {
                        peekLibrary(
                                anchor,
                                t,
                                () -> navigationRequestCurrent(b, originPath, originVersion, requestGeneration));
                        return;
                    }
                    peekTarget(t, file, b, originPath, originVersion, requestGeneration);
                });
    }

    /**
     * Peeks a {@code jdt://} definition — a JDK or dependency member — in the peek window like any other
     * (Java review B7), its source asked of {@code anchor}'s session; Open there opens it as library source.
     * Before, peek gave up and opened a tab.
     */
    private void peekLibrary(Path anchor, LspManager.Target t, java.util.function.BooleanSupplier current) {
        String uri = t.classFileUri();
        if (uri == null) {
            host.setStatus(tr("status.lsp.noDefinition"));
            return;
        }
        lspManager.classFileContents(anchor, uri, content -> {
            if (!current.getAsBoolean()) {
                return;
            }
            if (content == null) {
                host.setStatus(tr("status.lsp.libraryUnavailable"));
                return;
            }
            int line = com.editora.lsp.JavadocSkip.declarationLine(content, t.line());
            showPeek(
                    LspManager.classFileTitle(uri),
                    content,
                    line,
                    "java",
                    () -> openLibraryDefinition(anchor, t, current));
        });
    }

    private void peekTarget(
            LspManager.Target t,
            Path file,
            EditorBuffer origin,
            @Nullable Path originPath,
            long originVersion,
            long requestGeneration) {
        EditorBuffer open = bufferForLspPath(file);
        if (open != null) {
            // Already open: its text is authoritative (it may hold unsaved edits) and free to read here.
            showPeek(t, file, open.text());
            return;
        }
        PEEK_READ.submit(() -> {
            String text;
            try {
                text = java.nio.file.Files.readString(file);
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
                    showPeek(t, file, text);
                }
            });
        });
    }

    private void showPeek(LspManager.Target t, Path file, String text) {
        String name = PathDisplay.fileName(file);
        showPeek(
                name,
                text,
                t.line(),
                com.editora.text.LanguageRegistry.forFileName(name),
                () -> ops.openAndGoto(file, t.line(), t.character()));
    }

    private void showPeek(String name, String text, int line, String language, Runnable open) {
        if (peekPopup == null) {
            peekPopup = new PeekPopup(host.overlayHost());
        }
        String title = tr("lsp.peek.title", name, line + 1);
        // The editor's own font, so the peeked lines wrap and align the way the file does.
        peekPopup.show(
                PeekPopup.build(title, text, line, language),
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                open);
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
    void gotoDefinition(@Nullable Runnable afterJump) {
        gotoDefinition(afterJump, null);
    }

    /**
     * As {@link #gotoDefinition(Runnable)}; {@code noAnswer} runs when no server serves the file or the server
     * finds nothing — the server-free index then gets its turn — and keeps this method's own status message
     * when it returns false (it could not answer either).
     */
    void gotoDefinition(@Nullable Runnable afterJump, java.util.function.@Nullable BooleanSupplier noAnswer) {
        EditorBuffer active = host.activeBuffer();
        LibrarySource lib = active == null ? null : librarySources.get(active);
        if (active != null && lib != null) {
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
        if (originPath == null) {
            return;
        }
        long originVersion = b.docVersion();
        long requestGeneration = ++navigationRequestGeneration;
        lspManager.changeDocument(originPath, b.text()); // sync latest text before the request
        lspManager.definition(originPath, area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
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

    private record LibrarySource(
            @Nullable String uri, @Nullable Path anchor) {}

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

    /**
     * Opens the source of a JDK/dependency class the server reported under a {@code jdt://} URI: fetched via
     * jdtls's {@code java/classFileContents} into a read-only, Java-highlighted, path-less buffer (#665).
     * Before this, such a definition was silently dropped and {@code M-.} on {@code String}/{@code List}
     * reported "no definition". {@code anchor} is the buffer the navigation started from (it routes the
     * request to the right session).
     */
    private void openLibraryDefinition(Path anchorPath, LspManager.Target t) {
        openLibraryDefinition(anchorPath, t, () -> true);
    }

    private void openLibraryDefinition(
            @Nullable Path anchorPath, LspManager.Target t, java.util.function.BooleanSupplier stillCurrent) {
        openLibraryDefinition(anchorPath, t, stillCurrent, b -> {});
    }

    private void openLibraryDefinition(
            @Nullable Path anchorPath,
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
        if (anchorPath == null) {
            host.setStatus(tr("status.lsp.libraryUnavailable")); // no session to ask: as an empty answer says
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
                // jdtls's class-file range starts at the member's Javadoc: land on the declaration (B16).
                int line = com.editora.lsp.JavadocSkip.declarationLine(content, t.line());
                gotoInBuffer(opened, line, line == t.line() ? t.character() : -1);
                host.setStatus(""); // the "Loading library source…" it said is over
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
        Path anchor = lib.anchor();
        if (anchor == null) {
            host.setStatus(tr("status.lsp.noDefinition")); // no session to ask: as an empty answer says
            return;
        }
        lspManager.definitionAt(anchor, lib.uri(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (!current.getAsBoolean()) {
                return;
            }
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noDefinition"));
                return;
            }
            LspManager.Target t = targets.get(0);
            if (t.file() != null) {
                noteLibraryTarget(anchor, t.file());
                ops.openAndGoto(t.file(), t.line(), t.character());
            } else {
                openLibraryDefinition(anchor, t, current);
            }
        });
    }

    /**
     * Moves {@code buffer}'s caret to a 0-based line/column (clamped) and shows the line; a negative {@code col0}
     * is the line's first non-blank character.
     */
    private static void gotoInBuffer(EditorBuffer buffer, int line0, int col0) {
        CodeArea a = buffer.getFocusedArea();
        int par = Math.max(0, Math.min(line0, a.getParagraphs().size() - 1));
        String text = a.getParagraph(par).getText();
        int col = col0 >= 0
                ? Math.min(col0, text.length())
                : text.length() - text.stripLeading().length();
        a.moveTo(par, col);
        // At the top below the pinned rows, as every jump shows its target; followed only, the line sat under the
        // sticky header and a library tab's View Mode bar (Java review B16). A tab just opened anchors after layout.
        buffer.jumps().reveal(a, par, false);
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
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path path = origin.ownPath();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        String symbol = wordAtCaret(area);
        syncForRequest(origin); // sync latest text before the request
        lspManager.referencesAt(
                origin.anchor(),
                origin.libraryUri(),
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                targets -> {
                    if (!navigationRequestCurrent(b, path, version, generation)) {
                        return;
                    }
                    if (listAlways) {
                        showInReferencesWindow(targets, symbol);
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
                    showInReferencesWindow(targets, symbol);
                },
                why -> {
                    if (navigationRequestCurrent(b, path, version, generation)) {
                        host.setError(
                                "timeout".equals(why)
                                        ? tr("status.lsp.referencesTimedOut")
                                        : tr("status.lsp.referencesFailed", why));
                    }
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
                lspManager::implementationAt,
                (anchor, targets) -> {
                    if (targets.isEmpty()) {
                        host.setStatus(tr("status.lsp.noImplementations"));
                        return;
                    }
                    if (targets.size() == 1) {
                        openTarget(anchor, targets.get(0));
                        return;
                    }
                    EditorBuffer active = host.activeBuffer();
                    String symbol = active == null ? null : wordAtCaret(active.getFocusedArea());
                    if (targets.stream().allMatch(t -> t.file() != null)) {
                        showInReferencesWindow(targets, symbol, true);
                        return;
                    }
                    // The References window is keyed by file and a library target has none: list them all in a
                    // picker instead of opening one unexplained class (re-review BN4).
                    nestedImplementationNames(anchor, targets, names -> {
                        java.nio.file.Path base = ops.lspProjectRoot();
                        List<String> excluded = lspManager.javaFilteredTypes();
                        java.util.function.Function<LspManager.Target, String> label =
                                t -> names.getOrDefault(t, implementationLabel(t));
                        List<LspManager.Target> listed = targets.stream() // no sun.awt.util.IdentityArrayList (BN4)
                                .filter(t -> t.file() != null
                                        || !SymbolRanking.excluded(
                                                libraryPackage(t) + "." + implementationLabel(t), excluded))
                                // by class and line, so a class's own rows sit together in order (fourth review BN4)
                                .sorted(java.util.Comparator.comparing(label).thenComparingInt(LspManager.Target::line))
                                .toList();
                        pickOne(
                                symbol == null
                                        ? tr("picker.implementations.title")
                                        : tr("picker.implementations.of", symbol),
                                tr("picker.implementations.prompt"),
                                listed.isEmpty() ? targets : listed,
                                label,
                                t -> implementationDetail(t, base),
                                chosen -> openTarget(anchor, chosen));
                    });
                });
    }

    /**
     * Each library target's type by its own name, nested ones dotted ({@code AbstractList.SubList}), read from its
     * class's source; a target whose source is not had keeps its file's name (fifth review BN4). One request per
     * class file, at most {@link #NESTED_NAME_FILES}.
     */
    private void nestedImplementationNames(
            Path anchor,
            List<LspManager.Target> targets,
            java.util.function.Consumer<Map<LspManager.Target, String>> cb) {
        Map<String, List<LspManager.Target>> byClass = new java.util.LinkedHashMap<>();
        for (LspManager.Target t : targets) {
            if (t.file() == null && t.classFileUri() != null && byClass.size() < NESTED_NAME_FILES) {
                byClass.computeIfAbsent(t.classFileUri(), k -> new java.util.ArrayList<>())
                        .add(t);
            }
        }
        Map<LspManager.Target, String> names = new java.util.HashMap<>();
        if (byClass.isEmpty()) {
            cb.accept(names);
            return;
        }
        int[] left = {byClass.size()};
        byClass.forEach((uri, rows) -> lspManager.classFileContents(anchor, uri, source -> {
            if (source != null) {
                for (LspManager.Target t : rows) {
                    String name = com.editora.lsp.JavaTypeAtLine.at(source, t.line());
                    if (name != null) {
                        names.put(t, name);
                    }
                }
            }
            if (--left[0] == 0) {
                cb.accept(names);
            }
        }));
    }

    private static final int NESTED_NAME_FILES = 24;

    /** An implementation's picker row: its class (a library's {@code ArrayList}) or its file's name. */
    static String implementationLabel(LspManager.Target t) {
        if (t.file() != null) {
            return t.file().getFileName().toString();
        }
        String title = LspManager.classFileTitle(t.classFileUri());
        return title.replaceFirst("\\.(class|java)$", "");
    }

    /** Where it is: the library class's package, or the file relative to the project, with its line. */
    static String implementationDetail(LspManager.Target t, java.nio.file.@Nullable Path base) {
        if (t.file() != null) {
            Path file = t.file();
            String where = base != null && file.startsWith(base)
                    ? base.relativize(file).toString()
                    : file.toString();
            return where + ":" + (t.line() + 1);
        }
        // the line tells apart the classes one file holds: AbstractList's SubList, its iterators (third review BN4)
        String pkg = libraryPackage(t);
        String line = tr("structure.line", t.line() + 1);
        return pkg.isEmpty() ? line : pkg + "  ·  " + line;
    }

    /** The package of a library target's class file ({@code java.util}), or "". */
    static String libraryPackage(LspManager.Target t) {
        String uri = t.classFileUri() == null ? "" : t.classFileUri();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        int last = path.lastIndexOf('/');
        int before = last > 0 ? path.lastIndexOf('/', last - 1) : -1;
        return before >= 0 ? path.substring(before + 1, last) : "";
    }

    /** Go to Type Definition ({@code lsp.gotoTypeDefinition}, #736) — from a symbol to its type's declaration. */
    void gotoTypeDefinition() {
        withNavRequest(
                "status.lsp.typeDefinitionUnsupported",
                lspManager::supportsTypeDefinition,
                lspManager::typeDefinitionAt,
                (anchor, targets) -> openFirstTarget(anchor, targets, "status.lsp.noTypeDefinition"));
    }

    /**
     * Go to Super Method ({@code lsp.gotoSuperMethod}, IntelliJ's Ctrl+U): what the member at the caret overrides, as
     * IntelliJ finds it. The member is the innermost method, constructor or type whose whole range (its annotations and
     * Javadoc too) holds the caret, an anonymous class's method included, also in a field or an enum constant; in a
     * lambda, in a method or a field, it is the lambda's functional interface method; a constructor goes to the super
     * constructor it calls, through {@code this(...)} too; a type to its supertypes. A static or private method has none.
     * jdtls's {@code findLinks} answers first; the type hierarchy adds the branches it misses (eighth and ninth reviews).
     */
    void gotoSuperMethod() {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        Path path = origin.anchor();
        if (!lspManager.supportsSuperMethod(path)) {
            host.setStatus(tr("status.lsp.superMethodUnsupported"));
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        int line = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        int caret = area.getCaretPosition();
        String text = area.getText();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        syncForRequest(origin);
        SuperAsk ask = new SuperAsk(
                path, origin.libraryUri(), () -> navigationRequestCurrent(b, origin.ownPath(), version, generation));
        lspManager.memberSpans(path, null, origin.libraryUri(), members -> {
            if (ask.current().getAsBoolean()) {
                superAt(ask, text, line, column, caret, members);
            }
        });
    }

    private void superAt(
            SuperAsk ask, String text, int line, int column, int caret, List<com.editora.lsp.MemberSpan> members) {
        List<com.editora.lsp.MemberSpan> chain = com.editora.lsp.JavaMembers.enclosing(members, line);
        int at = chain.size() - 1;
        while (at >= 0 && !chain.get(at).isType() && !chain.get(at).isCallable()) {
            at--;
        }
        if (at < 0) {
            superMethods(ask, line, column, List.of());
            return;
        }
        com.editora.lsp.MemberSpan member = chain.get(at);
        com.editora.lsp.MemberSpan inner = chain.get(chain.size() - 1); // a field or constant around the caret
        com.editora.lsp.MemberSpan scope = member.isCallable() ? member : inner != member ? inner : null;
        int base = scope != null
                ? offsetOf(text, scope.startLine())
                : member.isType() ? offsetOf(text, member.nameLine()) : -1;
        int reference = base < 0 ? -1 : com.editora.lsp.JavaMembers.methodReference(text, caret);
        if (reference >= base && base >= 0) { // String::length: its functional interface's method (tenth review BO3)
            definitionAsSuper(ask, text, reference + 1);
            return;
        }
        int parameters = base < 0 ? -1 : com.editora.lsp.JavaMembers.lambdaParameterArrow(text, base, caret);
        if (parameters >= 0) { // in a lambda's parameters, the lambda's (BO3)
            definitionAsSuper(ask, text, parameters);
            return;
        }
        int arrow = scope == null
                ? -1
                : com.editora.lsp.JavaMembers.lambdaArrow(text, offsetOf(text, scope.startLine()), caret);
        if (arrow >= 0) { // a lambda's super method is its functional interface's method, as in IntelliJ
            definitionAsSuper(ask, text, arrow);
            return;
        }
        if (member.isType() && scope == null) {
            // in no member of the type: a static or instance initializer, which jdtls leaves out of the outline; an
            // anonymous class's method or a lambda there is found in the text (ninth review BQ2)
            int[] anonymous =
                    com.editora.lsp.JavaMembers.anonymousMethod(text, offsetOf(text, member.startLine()), caret);
            int lambda = com.editora.lsp.JavaMembers.lambdaArrow(text, offsetOf(text, member.nameLine()), caret);
            if (anonymous != null && anonymous[1] > lambda) {
                anonymousSuper(ask, text, anonymous);
                return;
            }
            if (lambda >= 0 && caret > offsetOf(text, member.nameLine() + 1)) {
                definitionAsSuper(ask, text, lambda);
                return;
            }
        }
        if (member.isType() && scope != null && !scope.isCallable()) {
            // a field's anonymous class no outline holds, as in a class file (tenth review BP2)
            int[] anonymous =
                    com.editora.lsp.JavaMembers.anonymousMethod(text, offsetOf(text, scope.startLine()), caret);
            if (anonymous != null) {
                anonymousSuper(ask, text, anonymous);
                return;
            }
        }
        if (member.isType() && member.name().equals("{...}") && at > 0) {
            // an enum constant's own body: its class is the enum's, as IntelliJ goes there
            com.editora.lsp.MemberSpan constant = chain.get(at - 1);
            for (int i = at - 1; i >= 0; i--) {
                if (chain.get(i).isType()) {
                    openTarget(
                            ask.path(),
                            new LspManager.Target(
                                    ask.libraryUri() == null ? ask.path() : null,
                                    chain.get(i).nameLine(),
                                    chain.get(i).nameColumn(),
                                    ask.libraryUri()));
                    return;
                }
            }
            superTypes(ask, constant);
            return;
        }
        if (member.isType()) {
            superTypes(ask, member);
            return;
        }
        com.editora.lsp.MemberSpan type = null;
        for (int i = at - 1; i >= 0 && type == null; i--) {
            type = chain.get(i).isType() ? chain.get(i) : null;
        }
        int[] anonymous = ask.libraryUri() == null
                ? null
                : com.editora.lsp.JavaMembers.anonymousMethod(text, offsetOf(text, member.startLine()), caret);
        if (anonymous != null) {
            // a class file's outline has no anonymous classes: the method is found in the text (ninth review BP2)
            anonymousSuper(ask, text, anonymous);
            return;
        }
        if (member.kind().equals("constructor")) {
            superConstructor(ask, text, member, type, chain, new java.util.HashSet<>());
            return;
        }
        java.util.Set<String> modifiers = com.editora.lsp.JavaMembers.modifiers(
                text, offsetOf(text, member.startLine()), offsetOf(text, member.nameLine()) + member.nameColumn());
        if (modifiers.contains("static") || modifiers.contains("private")) {
            host.setStatus(tr("status.lsp.noSuperMethod")); // neither overrides anything (ninth review DP1)
            return;
        }
        superMethodsOf(
                ask,
                text,
                member,
                type,
                com.editora.lsp.JavaMembers.annotated(
                        text,
                        offsetOf(text, member.startLine()),
                        offsetOf(text, member.nameLine()) + member.nameColumn(),
                        "Override"));
    }

    /**
     * A method's super methods: jdtls's {@code findLinks} first, which is exact for a class's method; the type hierarchy
     * then finds the branches it misses (an interface's methods, a second interface, an anonymous class written with
     * {@code <>} or extending a project class). A class's method findLinks finds nothing for overrides nothing, unless
     * it is marked {@code @Override}: findLinks misses an inner class of a generic class.
     */
    private void superMethodsOf(
            SuperAsk ask,
            String text,
            com.editora.lsp.MemberSpan method,
            com.editora.lsp.@Nullable MemberSpan type,
            boolean override) {
        javafx.animation.PauseTransition slow = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
        slow.setOnFinished(e -> {
            if (ask.current().getAsBoolean()) {
                host.setStatus(tr("status.lsp.superMethodSearching")); // a deep hierarchy takes a moment (BP5)
            }
        });
        slow.play();
        lspManager.superMethodAt(
                ask.path(),
                ask.libraryUri(),
                method.nameLine(),
                method.nameColumn(),
                linked -> {
                    if (!ask.current().getAsBoolean()) {
                        slow.stop();
                        return;
                    }
                    // findLinks misses an inner class of a generic class: a method marked @Override has one, so the
                    // hierarchy is walked before "none" is said (tenth review EO1)
                    if (type == null
                            || !(type.kind().equals("interface")
                                    || type.isAnonymous()
                                    || !linked.isEmpty()
                                    || override)) {
                        slow.stop();
                        openOneOrPick(ask, labelled(linked), "picker.superMethod.title", "status.lsp.noSuperMethod");
                        return;
                    }
                    new SuperMethodWalk(lspManager, ask, text, method, found -> {
                                slow.stop();
                                openOneOrPick(
                                        ask,
                                        found.isEmpty() ? labelled(linked) : found,
                                        "picker.superMethod.title",
                                        "status.lsp.noSuperMethod");
                            })
                            .start(type);
                },
                why -> {
                    slow.stop();
                    navigationFailed(ask, why);
                });
    }

    /** One Go to Super Method request: the anchor file, the library tab's URI (or null), and whether it still counts. */
    record SuperAsk(Path path, @Nullable String libraryUri, java.util.function.BooleanSupplier current) {}

    /** A Go to Super Method target and its picker row: the type, or the type and method ({@code Shape.area}). */
    record SuperTarget(LspManager.Target target, String label) {}

    /** The offset of {@code line}'s start in {@code text}. */
    static int offsetOf(String text, int line) {
        int at = 0;
        for (int i = 0; i < line && at >= 0; i++) {
            at = text.indexOf('\n', at);
            at = at < 0 ? -1 : at + 1;
        }
        return Math.max(0, at);
    }

    /** The 0-based line and column of {@code offset} in {@code text}. */
    private static int[] lineColumn(String text, int offset) {
        int line = 0;
        int start = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                start = i + 1;
            }
        }
        return new int[] {line, offset - start};
    }

    /**
     * The super method of an anonymous class's method no outline holds (in an initializer, or a class file), found in
     * {@code text} ({@code anonymous}: the offsets of the instantiated type's name and the method's): jdtls's
     * {@code findLinks}, else the walk from the type it instantiates, as for one the outline holds (tenth review BO2).
     */
    private void anonymousSuper(SuperAsk ask, String text, int[] anonymous) {
        int[] name = lineColumn(text, anonymous[1]);
        int[] type = lineColumn(text, anonymous[0]);
        lspManager.superMethodAt(
                ask.path(),
                ask.libraryUri(),
                name[0],
                name[1],
                linked -> {
                    if (!ask.current().getAsBoolean()) {
                        return;
                    }
                    if (!linked.isEmpty()) {
                        openOneOrPick(ask, labelled(linked), "picker.superMethod.title", "status.lsp.noSuperMethod");
                        return;
                    }
                    var method = new com.editora.lsp.MemberSpan(
                            com.editora.lsp.JavaMembers.signatureAt(text, anonymous[1]),
                            "method",
                            name[0],
                            name[0],
                            name[0],
                            name[1],
                            List.of());
                    var anonymousClass = new com.editora.lsp.MemberSpan(
                            "new {...}", "class", type[0], name[0], type[0], type[1], List.of(method));
                    new SuperMethodWalk(
                                    lspManager,
                                    ask,
                                    text,
                                    method,
                                    found -> openOneOrPick(
                                            ask, found, "picker.superMethod.title", "status.lsp.noSuperMethod"))
                            .start(anonymousClass);
                },
                why -> navigationFailed(ask, why));
    }

    /** jdtls's {@code java/findLinks} at a method's name: its one answer, else {@code otherwise}. */
    private void superMethods(SuperAsk ask, int line, int column, List<SuperTarget> otherwise) {
        lspManager.superMethodAt(
                ask.path(),
                ask.libraryUri(),
                line,
                column,
                targets -> {
                    if (ask.current().getAsBoolean()) {
                        openOneOrPick(
                                ask,
                                targets.isEmpty() ? otherwise : labelled(targets),
                                "picker.superMethod.title",
                                "status.lsp.noSuperMethod");
                    }
                },
                why -> navigationFailed(ask, why));
    }

    private void navigationFailed(SuperAsk ask, String why) {
        if (ask.current().getAsBoolean()) {
            host.setError(
                    "timeout".equals(why)
                            ? tr("status.lsp.navigationTimedOut")
                            : tr("status.lsp.navigationFailed", why));
        }
    }

    private static List<SuperTarget> labelled(List<LspManager.Target> targets) {
        return targets.stream()
                .map(t -> new SuperTarget(t, implementationLabel(t)))
                .toList();
    }

    /** Go to Definition at {@code offset} (a lambda's arrow, a {@code super(...)} call), opened as the super method. */
    private void definitionAsSuper(SuperAsk ask, String text, int offset) {
        int[] at = lineColumn(text, offset);
        lspManager.definitionAt(ask.path(), ask.libraryUri(), at[0], at[1], targets -> {
            if (ask.current().getAsBoolean()) {
                openOneOrPick(ask, labelled(targets), "picker.superMethod.title", "status.lsp.noSuperMethod");
            }
        });
    }

    /**
     * A constructor's super constructor: the one its {@code super(...)} calls; through a {@code this(...)} call, the one
     * the chained constructor reaches (ninth review BP3); else the superclass's no-argument constructor, else the
     * superclass itself, as IntelliJ does.
     */
    private void superConstructor(
            SuperAsk ask,
            String text,
            com.editora.lsp.MemberSpan ctor,
            com.editora.lsp.@Nullable MemberSpan type,
            List<com.editora.lsp.MemberSpan> chain,
            java.util.Set<Integer> seen) {
        int from = offsetOf(text, ctor.startLine());
        int to = offsetOf(text, ctor.endLine() + 1);
        int call = com.editora.lsp.JavaMembers.superCall(text, from, to);
        if (call >= 0) {
            definitionAsSuper(ask, text, call);
            return;
        }
        int chained = com.editora.lsp.JavaMembers.thisCall(text, from, to);
        if (chained >= 0 && type != null && seen.add(ctor.nameLine())) {
            int[] at = lineColumn(text, chained);
            lspManager.definitionAt(ask.path(), ask.libraryUri(), at[0], at[1], targets -> {
                if (!ask.current().getAsBoolean()) {
                    return;
                }
                com.editora.lsp.MemberSpan next = targets.isEmpty()
                        ? null
                        : type.children().stream()
                                .filter(m -> m.kind().equals("constructor")
                                        && m.nameLine() == targets.get(0).line())
                                .findFirst()
                                .orElse(null);
                if (next == null) {
                    implicitSuperConstructor(ask, type);
                } else {
                    superConstructor(ask, text, next, type, chain, seen);
                }
            });
            return;
        }
        if (type == null) {
            host.setStatus(tr("status.lsp.noSuperMethod"));
            return;
        }
        implicitSuperConstructor(ask, type);
    }

    /** The superclass's no-argument constructor, else the superclass itself (none for {@code Object}). */
    private void implicitSuperConstructor(SuperAsk ask, com.editora.lsp.MemberSpan type) {
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), type.nameColumn(), roots -> {
            if (!ask.current().getAsBoolean()) {
                return;
            }
            if (roots.isEmpty()) {
                host.setStatus(tr("status.lsp.noSuperMethod"));
                return;
            }
            lspManager.typeHierarchyChildren(ask.path(), roots.get(0).raw(), true, supers -> {
                LspManager.HierarchyNode superclass = supers.stream()
                        .filter(n -> n.kindName().equals("class") && !n.name().equals("Object"))
                        .findFirst()
                        .orElse(null);
                if (!ask.current().getAsBoolean()) {
                    return;
                }
                if (superclass == null) {
                    host.setStatus(tr("status.lsp.noSuperMethod"));
                    return;
                }
                lspManager.memberSpans(ask.path(), superclass.file(), superclass.libraryUri(), members -> {
                    if (!ask.current().getAsBoolean()) {
                        return;
                    }
                    com.editora.lsp.MemberSpan declared =
                            com.editora.lsp.JavaMembers.typeAt(members, superclass.line(), superclass.name());
                    com.editora.lsp.MemberSpan noArgs = declared == null
                            ? null
                            : declared.children().stream()
                                    .filter(m -> m.kind().equals("constructor")
                                            && com.editora.lsp.JavaMembers.parameters(m.name())
                                                    .isEmpty())
                                    .findFirst()
                                    .orElse(null);
                    int line = noArgs == null ? superclass.line() : noArgs.nameLine();
                    int col = noArgs == null ? superclass.col() : noArgs.nameColumn();
                    openTarget(
                            ask.path(), new LspManager.Target(superclass.file(), line, col, superclass.libraryUri()));
                });
            });
        });
    }

    /** {@code supers} with the superclass first, as IntelliJ lists them; jdtls gives the interfaces first. */
    static List<LspManager.HierarchyNode> superclassFirst(List<LspManager.HierarchyNode> supers) {
        List<LspManager.HierarchyNode> out = new java.util.ArrayList<>(supers);
        out.sort(java.util.Comparator.comparingInt(n -> n.kindName().equals("interface") ? 1 : 0));
        return out;
    }

    /**
     * A type's supertypes, the superclass first and each named by its type (eighth review BQ6). An anonymous class
     * goes to the type it instantiates.
     */
    private void superTypes(SuperAsk ask, com.editora.lsp.MemberSpan type) {
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), type.nameColumn(), roots -> {
            if (!ask.current().getAsBoolean()) {
                return;
            }
            if (roots.isEmpty()) {
                host.setStatus(tr("status.lsp.noSuperType"));
                return;
            }
            if (type.isAnonymous()) { // jdtls answers with the instantiated type itself
                openOneOrPick(
                        ask, typeTargets(roots.subList(0, 1)), "picker.superTypes.title", "status.lsp.noSuperType");
                return;
            }
            lspManager.typeHierarchyChildren(ask.path(), roots.get(0).raw(), true, supers -> {
                if (ask.current().getAsBoolean()) {
                    openOneOrPick(
                            ask,
                            typeTargets(superclassFirst(supers)),
                            "picker.superTypes.title",
                            "status.lsp.noSuperType");
                }
            });
        });
    }

    private static List<SuperTarget> typeTargets(List<LspManager.HierarchyNode> nodes) {
        return nodes.stream()
                .map(n -> new SuperTarget(new LspManager.Target(n.file(), n.line(), n.col(), n.libraryUri()), n.name()))
                .toList();
    }

    /** Opens the one target, or lets the user pick among several (IntelliJ's chooser); says {@code emptyKey} if none. */
    private void openOneOrPick(SuperAsk ask, List<SuperTarget> targets, String titleKey, String emptyKey) {
        if (targets.isEmpty()) {
            host.setStatus(tr(emptyKey));
            return;
        }
        host.setStatus(""); // "Looking for super methods…" is over, picker or not (tenth review DO3)
        if (targets.size() == 1) {
            openTarget(ask.path(), targets.get(0).target());
            return;
        }
        java.nio.file.Path base = ops.lspProjectRoot();
        pickOne(
                tr(titleKey),
                tr("picker.superMethod.prompt"),
                targets,
                SuperTarget::label,
                t -> implementationDetail(t.target(), base),
                chosen -> openTarget(ask.path(), chosen.target()));
    }

    /** Go to Declaration ({@code lsp.gotoDeclaration}, #736). */
    void gotoDeclaration() {
        withNavRequest(
                "status.lsp.declarationUnsupported",
                lspManager::supportsDeclaration,
                lspManager::declarationAt,
                (anchor, targets) -> openFirstTarget(anchor, targets, "status.lsp.noDeclaration"));
    }

    /** A position-to-targets request on {@link LspManager} (implementation / type definition / declaration). */
    @FunctionalInterface
    private interface NavRequest {
        void run(
                Path anchor,
                @Nullable String documentUri,
                int line,
                int character,
                Consumer<List<LspManager.Target>> cb,
                Consumer<String> onError);
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
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        Path path = origin.anchor();
        if (!supported.test(path)) {
            host.setStatus(tr(unsupportedKey));
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        syncForRequest(origin); // sync latest text before the request
        request.run(
                path,
                origin.libraryUri(),
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                targets -> {
                    if (navigationRequestCurrent(b, origin.ownPath(), version, generation)) {
                        onTargets.accept(path, targets);
                    }
                },
                why -> { // a failed request said "No implementations found" (Java review B13)
                    if (navigationRequestCurrent(b, origin.ownPath(), version, generation)) {
                        host.setError(
                                "timeout".equals(why)
                                        ? tr("status.lsp.navigationTimedOut")
                                        : tr("status.lsp.navigationFailed", why));
                    }
                });
    }

    private boolean navigationRequestCurrent(
            EditorBuffer buffer, @Nullable Path path, long version, long requestGeneration) {
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
            // the tab's own path, none for a library tab: comparing the anchor (the project file a library tab is
            // served through) dropped every library target opened from a library tab (eighth review BQ3)
            Path originPath = origin == null ? null : lspPath(origin);
            openLibraryDefinition(
                    anchor,
                    t,
                    () -> origin != null
                            && generation == navigationRequestGeneration
                            && origin == host.activeBuffer()
                            && java.util.Objects.equals(originPath, lspPath(origin))
                            && version == origin.docVersion());
        }
    }

    /** Fills the References tool window with {@code targets} (each must have a real path) and opens it. */
    private void showInReferencesWindow(List<LspManager.Target> targets) {
        showInReferencesWindow(targets, null);
    }

    /** As above, naming the {@code symbol} the list is about in its summary. */
    private void showInReferencesWindow(List<LspManager.Target> targets, @Nullable String symbol) {
        showInReferencesWindow(targets, symbol, false);
    }

    /** As above; {@code implementations} titles the list "2 implementations of area()" (re-review BN9). */
    private void showInReferencesWindow(
            List<LspManager.Target> targets, @Nullable String symbol, boolean implementations) {
        List<String> previews = previewLines(targets, f -> {
            EditorBuffer buffer = bufferForLspPath(f);
            return buffer == null ? null : buffer.getContent();
        });
        List<ReferencesPanel.Reference> refs = new java.util.ArrayList<>(targets.size());
        java.util.Set<Path> unread = new java.util.LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            LspManager.Target t = targets.get(i);
            refs.add(new ReferencesPanel.Reference(
                    t.file(),
                    t.line(),
                    t.character(),
                    previews.get(i),
                    previewRuns(t, previews.get(i)),
                    symbol != null && ReferencesPanel.writes(openLine(t), t.character(), symbol.length())));
            if (previews.get(i).isEmpty() && t.file() != null && bufferForLspPath(t.file()) == null) {
                unread.add(t.file());
            }
        }
        ReferencesPanel panel = referencesPanel();
        panel.setReferences(refs, symbol, ops.lspProjectRoot(), implementations);
        ops.openReferencesWindow();
        if (!unread.isEmpty()) {
            readPreviewsFromDisk(panel, panel.generation(), unread);
        }
    }

    /** A reference's whole line from its file's open tab, or null without one. */
    private @Nullable String openLine(LspManager.Target t) {
        EditorBuffer buffer = t.file() == null ? null : bufferForLspPath(t.file());
        if (buffer == null) {
            return null;
        }
        CodeArea area = buffer.getArea();
        return t.line() >= 0 && t.line() < area.getParagraphs().size()
                ? area.getParagraph(t.line()).getText()
                : null;
    }

    /** The largest file whose lines a References preview reads from disk. */
    private static final long PREVIEW_READ_LIMIT = 4L * 1024 * 1024;

    /**
     * The previews of references into files without a tab, read off the FX thread (the Peek reader) and filled
     * into the list when it is still the one shown. Before, those rows showed only {@code 13:19}, and in a
     * real project most references are in closed files (Java review B6). Remote and huge files stay bare.
     */
    private void readPreviewsFromDisk(ReferencesPanel panel, long generation, java.util.Set<Path> files) {
        PEEK_READ.submit(() -> {
            java.util.Map<Path, String[]> lines = new java.util.HashMap<>();
            for (Path f : files) {
                try {
                    if (!com.editora.vfs.Vfs.isRemote(f) && java.nio.file.Files.size(f) <= PREVIEW_READ_LIMIT) {
                        lines.put(f, java.nio.file.Files.readString(f).split("\n", -1));
                    }
                } catch (java.io.IOException | RuntimeException unreadable) {
                    // left without a preview, as before
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            javafx.application.Platform.runLater(() -> panel.fillPreviews(generation, r -> {
                String[] in = r.file() == null ? null : lines.get(r.file());
                return in != null && r.line() >= 0 && r.line() < in.length ? in[r.line()] : "";
            }));
        });
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
            List<LspManager.Target> targets, java.util.function.Function<Path, @Nullable String> contentOf) {
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
        Path anchor = lspPath(b);
        if (anchor == null) {
            return;
        }
        if (!lspManager.supportsWorkspaceSymbols(anchor)) {
            host.setStatus(tr("status.lsp.workspaceSymbolsUnsupported"));
            return;
        }
        String sel = b.getFocusedArea().getSelectedText();
        String seed = sel != null && !sel.isBlank() && !sel.contains("\n") ? sel.trim() : "";
        WorkspaceSymbolPopup popup = new WorkspaceSymbolPopup(host.overlayHost(), new WorkspaceSymbolPopup.Ops() {
            @Override
            public void query(
                    String text, java.util.function.Consumer<java.util.List<LspManager.WorkspaceSymbolMatch>> cb) {
                lspManager.workspaceSymbols(
                        anchor,
                        text,
                        ms -> cb.accept(SymbolRanking.withoutExcluded(ms, lspManager.javaFilteredTypes())));
            }

            @Override
            public void open(LspManager.WorkspaceSymbolMatch match) {
                openWorkspaceSymbol(anchor, match);
            }
        });
        popup.show(seed);
    }

    /** Opens a workspace-symbol match: its file, or a {@code jdt://} one as library source asked of {@code anchor}. */
    private void openWorkspaceSymbol(Path anchor, LspManager.WorkspaceSymbolMatch match) {
        Path file = match.file();
        if (file != null) {
            ops.openAndGoto(file, match.line(), match.character());
        } else {
            var target = new LspManager.Target(null, match.line(), match.character(), match.libraryUri());
            if (match.line() > 0) {
                openLibraryDefinition(anchor, target);
                return;
            }
            // jdtls places a class-file type at line 0, its package line: find the declaration (BN7).
            openLibraryDefinition(anchor, target, () -> true, opened -> {
                int line = typeDeclarationLine(opened.getContent(), match.name());
                if (line >= 0) {
                    gotoInBuffer(opened, line, -1);
                }
            });
        }
    }

    private static final java.util.regex.Pattern TYPE_KEYWORD =
            java.util.regex.Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+(\\w+)\\b");

    /** The line declaring type {@code name} in Java source, or -1; comment lines are skipped. Pure. */
    static int typeDeclarationLine(String content, String name) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.startsWith("*") || t.startsWith("/")) {
                continue;
            }
            var m = TYPE_KEYWORD.matcher(lines[i]);
            while (m.find()) {
                if (m.group(1).equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The file or project a Java request goes through when no tab names one: the active file, else the project. */
    private @Nullable Path javaAnchor() {
        EditorBuffer b = host.activeBuffer();
        Path own = b == null ? null : lspPath(b);
        return own != null && lspManager.hasJavaSession(own) ? own : ops.lspProjectRoot();
    }

    /** Search Everywhere's JDK and dependency classes from jdtls (Java review E8); empty without a Java server. */
    void libraryTypes(String query, java.util.function.Consumer<List<LspManager.WorkspaceSymbolMatch>> cb) {
        Path anchor = javaAnchor();
        if (anchor == null || query.isBlank()) {
            cb.accept(List.of());
            return;
        }
        lspManager.javaLibraryTypes(
                anchor, query, ms -> cb.accept(SymbolRanking.withoutExcluded(ms, lspManager.javaFilteredTypes())));
    }

    /**
     * {@link #libraryTypes} as Search Everywhere rows: fuzzy-matched like the index's symbols, one point below an
     * equal project match, so the project's own class is listed first.
     */
    void libraryTypeItems(
            String query, java.util.function.Consumer<List<com.editora.search.SearchEverywhere.Item>> cb) {
        libraryTypes(query, matches -> cb.accept(libraryRows(matches, query)));
    }

    /**
     * {@code matches} as Search Everywhere rows, in Ctrl+N's tie order: Search Everywhere keeps the arrival order of
     * rows with an equal score and label, so "Entry" listed {@code Map.Entry} 16th, past its group (fifth review EV3).
     */
    static List<com.editora.search.SearchEverywhere.Item> libraryRows(
            List<LspManager.WorkspaceSymbolMatch> matches, String query) {
        List<LspManager.WorkspaceSymbolMatch> ordered = new java.util.ArrayList<>(matches);
        ordered.sort(SymbolRanking.TIE_BREAK);
        List<com.editora.search.SearchEverywhere.Item> out = new java.util.ArrayList<>();
        for (LspManager.WorkspaceSymbolMatch m : ordered) {
            int score = SymbolRanking.score(m, query);
            if (score != com.editora.search.FuzzyMatch.NO_SCORE) {
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.SYMBOL,
                        m.name(),
                        m.container().isEmpty() ? LspManager.classFileTitle(m.libraryUri()) : m.container(),
                        score - 1,
                        m));
            }
        }
        return out;
    }

    /** Opens a {@link #libraryTypes} match. */
    void openLibraryType(LspManager.WorkspaceSymbolMatch match) {
        Path anchor = javaAnchor();
        if (anchor != null) {
            openWorkspaceSymbol(anchor, match);
        }
    }

    void showHover() {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path path = origin.ownPath();
        syncForRequest(origin); // sync latest text before the request
        long generation = ++hoverGeneration;
        long version = b.docVersion();
        int caret = area.getCaretPosition();
        java.util.function.BooleanSupplier stale = () -> b != host.activeBuffer()
                || b.isDisposed()
                || area != b.getFocusedArea()
                || generation != hoverGeneration
                || version != b.docVersion()
                || caret != area.getCaretPosition()
                || !java.util.Objects.equals(path, lspPath(b));
        lspManager.hoverAt(
                origin.anchor(),
                origin.libraryUri(),
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                text -> {
                    if (stale.getAsBoolean()) return;
                    if (text == null || text.isBlank()) {
                        host.setStatus(tr("status.lsp.noHover"));
                    } else {
                        showHoverPopup(area, text);
                    }
                },
                failure -> {
                    if (stale.getAsBoolean()) return;
                    // The server failed, which is not "nothing here": say so (Maven review C2).
                    host.setStatus(tr("status.lsp.hoverFailed", failure));
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
        Path path = buffer == null ? null : lspPath(buffer);
        if (buffer == null || path == null || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
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
        // A selection formats just its lines (textDocument/rangeFormatting), as IntelliJ's Reformat Code does;
        // before, the whole file was reformatted under it (Java review A13).
        CodeArea area = buffer.getFocusedArea();
        var sel = area.getSelection();
        boolean selectionOnly = sel.getLength() > 0 && lspManager.supportsRangeFormatting(path);
        Consumer<List<com.editora.text.LspTextEdit>> onEdits = edits -> {
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
            host.setStatus(tr(selectionOnly ? "status.lsp.selectionFormatted" : "status.lsp.formatted"));
        };
        if (selectionOnly) {
            int startLine = area.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
            var end = area.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
            // Whole lines; a selection ending at a line's start doesn't take that line.
            int endLine = end.getMinor() == 0 && end.getMajor() > startLine ? end.getMajor() - 1 : end.getMajor();
            lspManager.rangeFormatting(
                    path,
                    startLine,
                    0,
                    endLine,
                    area.getParagraphLength(endLine),
                    indent.tabSize(),
                    indent.insertSpaces(),
                    onEdits);
        } else {
            lspManager.formatDocument(path, indent.tabSize(), indent.insertSpaces(), onEdits);
        }
    }

    /**
     * Code actions / quick fixes at the caret or selection ({@code lsp.codeActions}, #670): asks the server
     * (with the overlapping diagnostics as context), shows a picker — preferred actions first — and applies
     * the pick. An action with a deferred edit is resolved first; a command-style action executes server-side
     * and its edits come back through {@code workspace/applyEdit} (→ {@link #applyWorkspaceEdits}).
     */
    void codeActions() {
        // jdtls lists every generator at every caret; they have their own command (Generate), so the quick-fix
        // list is about the caret (Java review A4). Other servers' source actions stay in it.
        showCodeActions(
                (serverId, item) -> !LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                        || (com.editora.lsp.CodeActionKinds.group(item.kind())
                                        != com.editora.lsp.CodeActionKinds.Group.SOURCE
                                && !com.editora.lsp.CodeActionKinds.isJavaGeneratorAssist(item)),
                "status.lsp.noCodeActions",
                null);
    }

    /**
     * Generate (IntelliJ's Alt+Insert): getters, constructors, toString, overrides. For Java only the generators;
     * Organize Imports and the clean-ups have commands of their own (re-review BN6).
     */
    void generateActions() {
        showCodeActions(
                (serverId, item) -> LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                        ? com.editora.lsp.CodeActionKinds.isJavaGenerator(item.kind())
                        : com.editora.lsp.CodeActionKinds.group(item.kind())
                                == com.editora.lsp.CodeActionKinds.Group.SOURCE,
                "status.lsp.noGenerate",
                null);
    }

    /** Refactor This (IntelliJ's Ctrl+Alt+Shift+T): the refactorings offered here. */
    void refactorActions() {
        showCodeActions(
                (serverId, item) -> com.editora.lsp.CodeActionKinds.group(item.kind())
                        == com.editora.lsp.CodeActionKinds.Group.REFACTOR,
                "status.lsp.noRefactorings",
                null);
    }

    /**
     * One refactoring by its code-action kind ({@code refactor.extract.variable}, {@code refactor.move}, …), for
     * the commands IntelliJ binds to its own chords: run at once when the server offers exactly one action of
     * that kind here, listed when it offers several, and said when it offers none (Java review E3).
     */
    void refactoring(String kind, String commandId) {
        showCodeActions(
                (serverId, item) -> com.editora.lsp.CodeActionKinds.matches(item.kind(), kind)
                        // jdtls files Make Static under refactor.inline too: not what Ctrl+Alt+N asked for (EN6)
                        && !(kind.equals("refactor.inline")
                                && com.editora.lsp.CodeActionKinds.isMakeStatic(item.title())),
                "status.lsp.noRefactoringHere",
                commandId);
    }

    /**
     * The code actions at the caret (or selection) that {@code keep} accepts, arranged by {@link
     * com.editora.lsp.CodeActionKinds}. {@code loneCommand} non-null: a single action runs without a list, and
     * the empty message names that command.
     */
    private void showCodeActions(
            java.util.function.BiPredicate<@Nullable String, LspManager.CodeActionItem> keep,
            String emptyKey,
            @Nullable String loneCommand) {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        Path path = lspPath(b);
        if (path == null) {
            return;
        }
        if (!lspManager.supportsCodeActions(path) || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.noCodeActions"));
            return;
        }
        String serverId = lspManager.managedServerId(path);
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
        lspManager.codeActions(path, start.getMajor(), start.getMinor(), end.getMajor(), end.getMinor(), all -> {
            if (b != host.activeBuffer()) {
                return; // switched tabs while the server was thinking
            }
            List<LspManager.CodeActionItem> items =
                    com.editora.lsp.CodeActionKinds.arrange(all, item -> keep.test(serverId, item));
            if (items.isEmpty()) {
                host.setStatus(
                        loneCommand == null
                                ? tr(emptyKey)
                                : tr(emptyKey, com.editora.editor.MenuTitles.of(tr("command." + loneCommand))));
                return;
            }
            // Inline Method rewrites every call and deletes the method: it is chosen from its one-row list, never
            // run by the chord alone (third review ET1).
            if (loneCommand != null
                    && items.size() == 1
                    && !com.editora.lsp.CodeActionKinds.isInlineMethod(
                            items.get(0).title())) {
                applyChosenAction(path, items, codeActionRow(items.get(0)));
                return;
            }
            // Anchored at the caret rather than a centred overlay card (#767): this acts on the symbol under
            // the cursor, which is where the user is looking. The editor keeps focus, so the caret stays
            // visible at the spot the fix will land while the list is open.
            b.showCodeActions(
                    items.stream().map(LspCoordinator::codeActionRow).toList(),
                    chosen -> applyChosenAction(path, items, chosen));
        });
    }

    /** What the lightbulb asks for: quick fixes and quick assists, not jdtls's generators and refactorings (AV7). */
    static final List<String> BULB_KINDS = List.of("quickfix", "quickassist");

    /**
     * The lightbulb for {@code actions}: {@code fix} with a quick fix for an error among them, {@code assist} with
     * another quick fix (a warning's suppressions, fifth review AV6) or a quick action. jdtls's "Add Javadoc
     * comment", offered at every position inside a type, lights nothing: the bulb was on 27 lines of 30 (AV1).
     */
    static @Nullable String bulbKind(List<LspManager.CodeActionItem> actions) {
        boolean assist = false;
        for (LspManager.CodeActionItem a : actions) {
            var group = com.editora.lsp.CodeActionKinds.group(a.kind());
            if (group == com.editora.lsp.CodeActionKinds.Group.FIX) {
                if (fixesAnError(a)) {
                    return "fix";
                }
                assist = true;
            }
            assist |= group == com.editora.lsp.CodeActionKinds.Group.ASSIST
                    && !com.editora.lsp.CodeActionKinds.isJavaGeneratorAssist(a)
                    && !a.title().startsWith("Add Javadoc comment");
        }
        return assist ? "assist" : null;
    }

    /** Whether a quick fix answers an error: by the diagnostics it names, and when it names none, it counts. */
    private static boolean fixesAnError(LspManager.CodeActionItem a) {
        if (!(a.raw() instanceof org.eclipse.lsp4j.CodeAction action)
                || action.getDiagnostics() == null
                || action.getDiagnostics().isEmpty()) {
            return true;
        }
        return action.getDiagnostics().stream()
                .anyMatch(
                        d -> d.getSeverity() == null || d.getSeverity() == org.eclipse.lsp4j.DiagnosticSeverity.Error);
    }

    /** An action's title without the "…" of a row that asks more: "Applied: Move 'Strings'" (BT7). */
    static String bareTitle(String title) {
        return title.strip().replaceFirst("(\\.\\.\\.|…)$", "");
    }

    /** The popup's row for {@code item}: its title, and a word for its group instead of the raw LSP kind (A17). */
    private static com.editora.editor.lsp.CodeAction codeActionRow(LspManager.CodeActionItem item) {
        String label = item.kind().isBlank()
                ? ""
                : tr("lsp.codeAction.group."
                        + com.editora.lsp.CodeActionKinds.group(item.kind())
                                .name()
                                .toLowerCase(java.util.Locale.ROOT));
        return new com.editora.editor.lsp.CodeAction(item.title(), label, item.preferred(), item.raw());
    }

    // --- problems at the caret (Java review A6) -------------------------------------------------------

    /**
     * Next / Previous Problem (IntelliJ's F2 / Shift+F2): moves the caret to the next error of the file after it
     * (or the previous one before it), wrapping, and says what it is. Warnings and the rest when the file has
     * no errors. Before this a diagnostic could only be read with the mouse or in the Problems window.
     */
    void gotoProblem(boolean forward) {
        gotoProblem(host.activeBuffer(), forward);
    }

    /** As {@link #gotoProblem(boolean)} in {@code b}, which takes the focus first (its counter's arrows). */
    void gotoProblem(@Nullable EditorBuffer b, boolean forward) {
        if (b != null && b != host.activeBuffer()) {
            b.getFocusedArea().requestFocus();
        }
        Path path = b == null ? null : lspPath(b);
        if (b == null || path == null) {
            host.setStatus(tr("status.lsp.noProblems"));
            return;
        }
        List<LspDiagnostic> here = problems.getOrDefault(ops.canonicalize(path), List.of());
        List<LspDiagnostic> errors = here.stream()
                .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                .toList();
        List<LspDiagnostic> candidates = new java.util.ArrayList<>(errors.isEmpty() ? here : errors);
        if (candidates.isEmpty()) {
            host.setStatus(tr("status.lsp.noProblems"));
            return;
        }
        candidates.sort(
                java.util.Comparator.comparingInt(LspDiagnostic::startLine).thenComparingInt(LspDiagnostic::startCol));
        CodeArea area = b.getFocusedArea();
        int line = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        LspDiagnostic target = nextProblem(candidates, line, col, forward);
        int targetLine = Math.min(target.startLine(), area.getParagraphs().size() - 1);
        int targetCol =
                Math.min(target.startCol(), area.getParagraph(targetLine).length());
        area.moveTo(targetLine, targetCol);
        area.requestFollowCaret();
        sayProblem(target);
    }

    /** Pure: the first problem after ({@code forward}) or before the caret, wrapping; {@code sorted} is non-empty. */
    static LspDiagnostic nextProblem(List<LspDiagnostic> sorted, int line, int col, boolean forward) {
        if (forward) {
            for (LspDiagnostic d : sorted) {
                if (d.startLine() > line || (d.startLine() == line && d.startCol() > col)) {
                    return d;
                }
            }
            return sorted.get(0);
        }
        for (int i = sorted.size() - 1; i >= 0; i--) {
            LspDiagnostic d = sorted.get(i);
            if (d.startLine() < line || (d.startLine() == line && d.startCol() < col)) {
                return d;
            }
        }
        return sorted.get(sorted.size() - 1);
    }

    /** Show Problem at Caret (IntelliJ's Ctrl+F1): says the problems the caret is on, or on its line. */
    void problemAtCaret() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        if (b == null || path == null) {
            host.setStatus(tr("status.lsp.noProblemHere"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        int line = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        LspDiagnostic best = null;
        for (LspDiagnostic d : problems.getOrDefault(ops.canonicalize(path), List.of())) {
            boolean onLine = d.startLine() <= line && line <= d.endLine();
            boolean atCaret = onLine
                    && (d.startLine() < line || d.startCol() <= col)
                    && (d.endLine() > line || col <= d.endCol());
            if (onLine
                    && (best == null
                            || atCaret
                            || d.severity().ordinal() < best.severity().ordinal())) {
                best = d;
                if (atCaret) {
                    break;
                }
            }
        }
        if (best == null) {
            host.setStatus(tr("status.lsp.noProblemHere"));
        } else {
            sayProblem(best);
        }
    }

    private void sayProblem(LspDiagnostic d) {
        String text = tr("status.lsp.problem", d.startLine() + 1, d.message());
        if (d.severity() == LspDiagnostic.Severity.ERROR) {
            host.setError(text);
        } else {
            host.setStatus(text);
        }
    }

    /**
     * Applies the action the user picked from the caret popup.
     *
     * <p>The popup deals in the neutral {@link com.editora.editor.lsp.CodeAction} — {@code editor} must not see
     * lsp4j — so the server's own object rides across as an opaque token and is matched back by identity
     * here. The lists are a handful of entries, so a scan is cheaper than building a map.
     */
    private void applyChosenAction(
            Path path, List<LspManager.CodeActionItem> items, com.editora.editor.lsp.CodeAction chosen) {
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
        if (runRefactorCommand(path, item) || xml.runsCodeAction(path, item)) {
            return; // likewise a refactoring that asks the user where to, or what; or lemminx's binding wizard
        }
        LspManager.CodeActionItem applied = item;
        beginReportedEdit();
        lspManager.applyCodeAction(
                path,
                applied,
                ok -> reportEdit(
                        ok,
                        tr("status.lsp.codeActionApplied", bareTitle(applied.title())),
                        lspManager.lastCodeActionFailure() != null // the server's reason when it gave one (BN9)
                                ? tr(
                                        "status.lsp.codeActionFailedWhy",
                                        applied.title(),
                                        lspManager.lastCodeActionFailure())
                                : lspManager.lastCodeActionEmpty() // resolved to no change at all (ET1)
                                        ? tr("status.lsp.codeActionNoChange", applied.title())
                                        : tr("status.lsp.codeActionFailed", applied.title())));
    }

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
        if (path == null) {
            return;
        }
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
            lspManager.previewRename(path, line, col, name, (mapped, why) -> {
                if (mapped == null) { // with the server's reason when it gave one (re-review BN9)
                    reportEdit(
                            false,
                            "",
                            why == null || why.isBlank()
                                    ? tr("status.lsp.renameFailed", name)
                                    : tr("status.lsp.renameFailedWhy", name, why));
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
                tr("picker.hint.apply"),
                keep -> applyPreviewed(mapped, new java.util.LinkedHashSet<>(keep), listed, name));
    }

    /** One preview row: the path, then what happens to it — deletes and replacements spelled out. */
    String previewLabel(com.editora.lsp.RenamePreview.FileChange change) {
        String label = projectRelative(change.file());
        if (change.edits() > 0) {
            label += "  ·  " + tr("lsp.rename.editCount", change.edits());
        }
        if (change.renamedTo() != null) {
            // where it goes, not just its new name: a move keeps the name (re-review BN8)
            Path to = change.renamedTo();
            boolean sameFolder =
                    java.util.Objects.equals(to.getParent(), change.file().getParent());
            label += "  ·  " + tr("lsp.rename.movesTo", sameFolder ? to.getFileName() : projectRelative(to));
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

    /** {@code file} relative to the project, else with {@code ~} for the home folder: rows were absolute (B10). */
    private String projectRelative(Path file) {
        Path root = ops.lspProjectRoot();
        return root != null && file.startsWith(root) && !file.equals(root)
                ? root.relativize(file).toString()
                : ops.homeCollapsed(file.toString());
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
        com.editora.lsp.WorkspaceEditMapper.Mapped filtered = java.util.Objects.requireNonNull(
                com.editora.lsp.RenamePreview.filter(mapped, keep, listed), "filter is null only for a null edit");
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
        java.util.Set<String> errorsBefore = errorsIn(mapped);
        applyWorkspaceEditsAsync(mapped, ok -> {
            reportEdit(ok, tr("status.lsp.renamed", name), tr("status.lsp.renameFailed", name));
            if (ok) {
                checkRenameConflicts(mapped, errorsBefore);
            }
        });
    }

    /** How long after a rename its files' diagnostics are compared with what they were (B17). */
    static final javafx.util.Duration RENAME_CHECK_DELAY = javafx.util.Duration.seconds(2);

    /**
     * jdtls applies a rename onto a name that is already taken (a second local {@code canvas}) without a word: the
     * conflicts JDT finds are not part of LSP's rename. So once its files have been re-checked, an error that
     * was not there before is reported, with the way back (Java review B17).
     */
    private void checkRenameConflicts(com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.Set<String> before) {
        var check = new javafx.animation.PauseTransition(RENAME_CHECK_DELAY);
        check.setOnFinished(e -> {
            List<String> added = new java.util.ArrayList<>(renamedFilesErrors(mapped));
            added.removeAll(before);
            if (!added.isEmpty()) {
                String first = added.get(0);
                host.setError(tr(
                        "status.lsp.renameIntroducedErrors",
                        added.size(),
                        first.substring(first.indexOf('\u0000') + 1),
                        com.editora.editor.MenuTitles.of(tr("command.lsp.undoRefactoring"))));
            }
        });
        check.play();
    }

    private java.util.Set<String> errorsIn(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        return new java.util.HashSet<>(renamedFilesErrors(mapped));
    }

    /** The error messages in {@code mapped}'s files (where its renames put them), as "file\0message". */
    private List<String> renamedFilesErrors(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        for (var edit : mapped.edits()) {
            files.add(ops.canonicalize(edit.file()));
        }
        for (var rename : mapped.renames()) {
            files.add(ops.canonicalize(rename.to()));
        }
        List<String> out = new java.util.ArrayList<>();
        for (Path file : files) {
            for (LspDiagnostic d : problems.getOrDefault(file, List.of())) {
                if (d.severity() == LspDiagnostic.Severity.ERROR) {
                    out.add(file.getFileName() + "\u0000" + d.message());
                }
            }
        }
        return out;
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
                    && onDisk != null
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
            Path path = buffer == null ? null : lspPath(buffer);
            if (buffer != null && path != null) {
                open.put(file.toAbsolutePath().normalize(), new OpenTarget(buffer, path));
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
    void documentPathChanged(EditorBuffer buffer, @Nullable Path oldPath, boolean oldAlreadyClosed) {
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
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> whenDone) {
        openBeforeEdit.put(mapped, openBuffers());
        java.util.function.Consumer<Boolean> done = ok -> {
            openBeforeEdit.remove(mapped);
            whenDone.accept(ok);
        };
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

    /**
     * Applies a workspace edit's per-file batches through undoable buffers (FX thread; the manager's apply-edit
     * handler, {@link #applyWorkspaceEditsConfirmed}, ends here — both a picked action's inline edit and a
     * server-initiated {@code workspace/applyEdit} land here). All-or-nothing: if any touched file can't be opened editable,
     * nothing is applied — half a refactoring corrupts the workspace. A file with no open tab opens in a
     * background tab so its change is visible and undoable.
     *
     * <p>Package-private so {@code LspWorkspaceEditFxTest} can drive it: this is the only LSP path that
     * writes and MOVES files on disk, so its all-or-nothing refusals need direct tests.
     */
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
        java.util.Set<EditorBuffer> openBefore = openBuffers();
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
        Map<EditorBuffer, String> before = textsOf(buffers);
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
        lspManager.notifyWatchedFiles(resourceEvents(staged, creates, deletes));
        if (!orphaned.isEmpty()) {
            tabsNotRemapped(orphaned);
        }
        afterWorkspaceEdit(mapped, buffers, before, openBefore);
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
        Map<EditorBuffer, String> before = textsOf(buffers);
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
            lspManager.notifyWatchedFiles(resourceEvents(renames, creates, deletes));
        });
        if (!orphaned.isEmpty()) {
            tabsNotRemapped(orphaned); // the text is edited and the file moved; never call that "applied"
        }
        afterWorkspaceEdit(mapped, buffers, before, openBeforeEdit.get(mapped));
        done.accept(orphaned.isEmpty());
    }

    /**
     * What the server hears of the files an edit created, moved or deleted on disk. A server reads a file it has
     * not got open from disk only when told it changed, and a folder nobody watches tells it nothing (BN1).
     */
    private static java.util.List<com.editora.lsp.LspManager.WatchedFile> resourceEvents(
            java.util.List<StagedRename> renames,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedDelete> deletes) {
        java.util.List<com.editora.lsp.LspManager.WatchedFile> out = new java.util.ArrayList<>();
        for (StagedRename r : renames) {
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    r.rename().from(), com.editora.lsp.LspManager.WatchedKind.DELETED));
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    r.rename().to(), com.editora.lsp.LspManager.WatchedKind.CREATED));
        }
        for (StagedCreate c : creates) {
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    c.operation().file(), com.editora.lsp.LspManager.WatchedKind.CREATED));
        }
        for (StagedDelete d : deletes) {
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    d.operation().file(), com.editora.lsp.LspManager.WatchedKind.DELETED));
        }
        return out;
    }

    // --- after an edit: save, close what it opened, remember it for Undo (Java review B1, B2, B18) -------------

    /** The buffers open when each asynchronous workspace edit began; the others it opened itself. */
    private final Map<com.editora.lsp.WorkspaceEditMapper.Mapped, java.util.Set<EditorBuffer>> openBeforeEdit =
            new java.util.IdentityHashMap<>();

    private java.util.Set<EditorBuffer> openBuffers() {
        java.util.Set<EditorBuffer> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        host.forEachBuffer(out::add);
        return out;
    }

    private static Map<EditorBuffer, String> textsOf(java.util.List<EditorBuffer> buffers) {
        Map<EditorBuffer, String> out = new java.util.IdentityHashMap<>();
        for (EditorBuffer b : buffers) {
            if (b != null) {
                out.putIfAbsent(b, b.getContent());
            }
        }
        return out;
    }

    /** One file of an applied edit: where it is now, and its text before and after. */
    record EditedFile(Path path, String before, String after) {}

    /** The last applied workspace edit (without deletions), as Undo Last Refactoring reverts it. */
    record AppliedEdit(
            java.util.List<EditedFile> files,
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames,
            java.util.List<Path> created) {}

    private @Nullable AppliedEdit lastApplied;
    /** Set while Undo Refactoring applies its inverse, which is not itself recorded. */
    private boolean undoingEdit;

    /** The edit Undo Refactoring would revert (tests). */
    @Nullable
    AppliedEdit lastAppliedEdit() {
        return lastApplied;
    }

    /**
     * What happens once a workspace edit is in. One that moved, created or deleted files is saved whole — its
     * files were already changed on disk, and their new text left unsaved made a project that did not compile
     * until every tab was saved (B2). A file the edit had to open (it had no tab) is saved and its tab closed,
     * as IntelliJ edits closed files, instead of a dirty tab per file (B18). An edit that spanned files or
     * moved them can be undone as one (B1); every edit is remembered for Undo Last Refactoring.
     */
    private void afterWorkspaceEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            Map<EditorBuffer, String> before,
            java.util.@Nullable Set<EditorBuffer> openBefore) {
        java.util.Set<EditorBuffer> touched =
                java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<EditorBuffer, Boolean>());
        for (EditorBuffer b : buffers) {
            if (b != null && !b.isDisposed()) {
                touched.add(b);
                // An applied edit is not typing: the auto-popup read a rename or inline at the caret as an
                // identifier typed, and its list took the next Enter (fourth review BF2).
                b.cancelCompletion();
            }
        }
        boolean resources = !mapped.renames().isEmpty()
                || !mapped.creates().isEmpty()
                || !mapped.deletes().isEmpty();
        java.util.Set<Path> created = new java.util.HashSet<>();
        mapped.creates().forEach(c -> created.add(c.file().toAbsolutePath().normalize()));
        if (undoingEdit) {
            lastApplied = null;
        } else if (!touched.isEmpty() && mapped.deletes().isEmpty()) {
            java.util.List<EditedFile> files = new java.util.ArrayList<>();
            for (EditorBuffer b : touched) {
                Path path = b.getPath();
                if (path != null && !created.contains(path.toAbsolutePath().normalize())) {
                    files.add(new EditedFile(path, before.getOrDefault(b, ""), b.getContent()));
                }
            }
            lastApplied = new AppliedEdit(
                    java.util.List.copyOf(files),
                    java.util.List.copyOf(mapped.renames()),
                    java.util.List.copyOf(created));
        }
        java.util.List<com.editora.lsp.LspManager.WatchedFile> written = new java.util.ArrayList<>();
        for (EditorBuffer b : touched) {
            boolean opened = openBefore != null && !openBefore.contains(b);
            Path path = b.getPath();
            boolean unmanaged = path != null && !lspManager.isManaged(path); // a background tab is never opened on it
            if (!resources && !opened) {
                if (unmanaged) {
                    // An open file the user can see stays as it is: they save it. But the server must hear its new
                    // text now: one never opened there kept its old text, so a second refactoring before a save
                    // skipped it and left the project broken (fourth review, BT2's remainder).
                    deferredLsp.remove(b);
                    syncBuffer(b);
                }
                continue;
            }
            boolean saved = b.isDirty() && ops.saveBuffer(b);
            if (saved && unmanaged && path != null) {
                written.add(new com.editora.lsp.LspManager.WatchedFile(
                        path, com.editora.lsp.LspManager.WatchedKind.CHANGED));
            }
            if ((saved || !b.isDirty())
                    && opened
                    && (path == null || !created.contains(path.toAbsolutePath().normalize()))) {
                ops.closeBackgroundBuffer(b); // a created file (an extracted interface) stays open to be seen
            }
        }
        // A file the server does not have open keeps its old text there until it hears the file changed: the next
        // rename skipped it and Find References missed it (re-review BN1).
        lspManager.notifyWatchedFiles(written);
    }

    /**
     * The Project tree's rename participant (B15): the edits jdtls wants for a rename go in first — the class
     * renamed with its {@code .java} file, the package lines and imports of a moved folder — then the tree moves
     * the file. Once it has, those edits are finished like any refactoring's: saved, so the project on disk
     * compiles, recorded with the move so Undo takes both back, and told to the server (re-review BN3).
     */
    void willRenameFile(Path from, Path to, java.util.function.Consumer<Boolean> proceed) {
        AppliedEdit previous = lastApplied;
        lastRenameRefused = false;
        renamesInFlight++;
        lspManager.willRenameFile(
                from,
                to,
                ok -> {
                    try {
                        proceed.accept(ok); // the tree moves the file now, and points its tabs at the new path
                        AppliedEdit edit = lastApplied;
                        boolean moved = ok && !java.nio.file.Files.exists(from) && java.nio.file.Files.exists(to);
                        if (moved && edit != null && edit != previous) {
                            treeRenamed(edit, from, to);
                        } else if (moved && moveGroup != null) {
                            // moved with no edit (not Java, or nothing to change): still part of the drag, so
                            // undoing the drag as one refactoring takes it back too (fifth review BV1)
                            moveGroup.add(new AppliedEdit(
                                    java.util.List.of(),
                                    java.util.List.of(
                                            new com.editora.lsp.WorkspaceEditMapper.FileRename(from, to, false)),
                                    java.util.List.of()));
                            moveGroupWhole &= !java.nio.file.Files.isDirectory(to);
                        }
                    } finally {
                        renameDone();
                    }
                },
                this::renameWithoutEdits);
    }

    /**
     * Tree renames whose edit is not recorded yet. Saving a tab the move edited runs a nested event loop, which ran
     * the drag's next move and its end before this move was recorded: the drag ends only once none is in flight
     * (sixth review BS1).
     */
    private int renamesInFlight;

    /** The end of the drag, waiting for a rename still in flight: true for an Undo Move's, null when none waits. */
    private @Nullable Boolean moveEndWaiting;

    /** What the tree said when the drag ended, said again once it is recorded: a save's status landed over it (BF3). */
    private @Nullable String moveEndStatus;

    /** Whether the last refused tree rename was the user's No; false for a failed edit (seventh review BR4). */
    private boolean lastRenameRefused;

    private void renameDone() {
        renamesInFlight = Math.max(0, renamesInFlight - 1);
        Boolean undo = moveEndWaiting;
        if (renamesInFlight == 0 && undo != null) {
            moveEndWaiting = null;
            String said = moveEndStatus;
            // the tree's "Moved 1 to geo", not the save's "Saved /the/whole/path"; but never over the warning that
            // a package line was left as it was (eighth review BQ4)
            if (!endTreeMove(undo) && said != null) {
                host.setStatus(said);
            }
        }
    }

    /**
     * The Project tree's participant: a rename or a drag-move goes through jdtls (B15, BT1), and the moves of one
     * drag, or of its Undo Move, are one change (fourth review BF1).
     */
    ProjectPanel.RenameParticipant treeParticipant() {
        return new ProjectPanel.RenameParticipant() {
            @Override
            public void willRename(Path from, Path to, java.util.function.Consumer<Boolean> proceed) {
                willRenameFile(from, to, proceed);
            }

            @Override
            public void beginMove(int count) {
                moveGroup = new java.util.ArrayList<>();
                moveGroupWhole = true;
                moveCount = count;
                moveWithoutEdits.clear();
                moveWithoutEditsAnswer = null;
            }

            @Override
            public void endMove(boolean undo) {
                endMove(undo, null);
            }

            @Override
            public void endMove(boolean undo, @Nullable String status) {
                moveEndStatus = status;
                if (renamesInFlight > 0) {
                    moveEndWaiting = undo; // a move's edit is still being recorded (sixth review BS1)
                } else {
                    endTreeMove(undo);
                }
            }

            @Override
            public boolean refusedByUser() {
                return lastRenameRefused;
            }
        };
    }

    /** The edits of the drag-move under way, one per moved file; null when none is. */
    private java.util.@Nullable List<AppliedEdit> moveGroup;

    /** False once a folder moved in it: a folder cannot be moved back as one, so Undo Move is the way back. */
    private boolean moveGroupWhole;

    /** How many items the drag under way moves, for the one question asked about them all. */
    private int moveCount;

    /** The {@code .java} files of the drag that moved without their package line edited. */
    private final java.util.List<String> moveWithoutEdits = new java.util.ArrayList<>();

    /** The edit Undo Last Refactoring would revert when it came from a drag-move, so reverting it ends Undo Move. */
    private @Nullable AppliedEdit treeMoveEdit;

    /** Told when Undo Last Refactoring took back a drag-move, which the tree must no longer offer to undo. */
    private Runnable onTreeMoveUndone = () -> {};

    void setOnTreeMoveUndone(Runnable action) {
        onTreeMoveUndone = action == null ? () -> {} : action;
    }

    /** Ends the drag's group; true when it warned that jdtls left a package line as it was. */
    private boolean endTreeMove(boolean undo) {
        java.util.List<AppliedEdit> group = moveGroup;
        moveGroup = null;
        if (group == null) {
            return false;
        }
        // Only a drag in which jdtls edited something is a refactoring: a .txt dragged after a rename took the
        // rename's undo away, and became "Refactoring undone in 0 files" (sixth review BS2).
        boolean edited = group.stream().anyMatch(e -> !e.files().isEmpty());
        if (undo) {
            // Undo Move took the drag back: it is not a refactoring to undo, and the drag's own edit is gone
            if (edited || (lastApplied != null && lastApplied == treeMoveEdit)) {
                lastApplied = null;
            }
            treeMoveEdit = null;
        } else if (edited) {
            lastApplied = moveGroupWhole ? merged(group) : null;
            treeMoveEdit = lastApplied;
        } else {
            // a drag with no edit is the tree's newest move: an older drag's refactoring no longer ends it, nor does
            // its Undo Move take that refactoring's undo away (seventh review BS2)
            treeMoveEdit = null;
        }
        moveEndStatus = null;
        boolean warned = !moveWithoutEdits.isEmpty();
        if (warned) {
            host.setError(tr(
                    "status.lsp.moveWithoutPackageEdit", String.join(", ", moveWithoutEdits), moveWithoutEdits.size()));
            moveWithoutEdits.clear();
        }
        moveWithoutEditsAnswer = null;
        return warned;
    }

    /** One edit made of several, in order: a file edited twice keeps its first text, a later move renames it. */
    static AppliedEdit merged(java.util.List<AppliedEdit> edits) {
        Map<Path, EditedFile> files = new java.util.LinkedHashMap<>();
        java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames = new java.util.ArrayList<>();
        java.util.List<Path> created = new java.util.ArrayList<>();
        for (AppliedEdit e : edits) {
            for (var r : e.renames()) {
                Path f = r.from().toAbsolutePath().normalize();
                Path t = r.to().toAbsolutePath().normalize();
                Map<Path, EditedFile> moved = new java.util.LinkedHashMap<>();
                files.forEach((p, ef) -> {
                    Path n = p.toAbsolutePath().normalize();
                    Path now = n.startsWith(f) ? t.resolve(f.relativize(n)) : p;
                    moved.put(now, new EditedFile(now, ef.before(), ef.after()));
                });
                files = moved;
            }
            for (EditedFile f : e.files()) {
                files.merge(f.path(), f, (old, now) -> new EditedFile(old.path(), old.before(), now.after()));
            }
            renames.addAll(e.renames());
            created.addAll(e.created());
        }
        return new AppliedEdit(
                java.util.List.copyOf(files.values()), java.util.List.copyOf(renames), java.util.List.copyOf(created));
    }

    /**
     * jdtls had no edit for a rename. When it could not say (still importing, or no answer), the file would move
     * with its package line and its users' imports as they were, with no word (fourth review BF5): ask first. When
     * it answered with nothing for a {@code .java} file moved to another folder, move it and say so (DF3).
     */
    private boolean renameWithoutEdits(Path from, Path to, boolean answered) {
        String name = PathDisplay.fileName(from);
        if (answered) {
            if (moveGroup != null) {
                moveWithoutEdits.add(name);
            } else {
                host.setError(tr("status.lsp.moveWithoutPackageEdit", name, 1));
            }
            return true;
        }
        Boolean asked = moveWithoutEditsAnswer;
        if (moveGroup != null && asked != null) {
            lastRenameRefused = !asked;
            return asked; // asked once for the whole drag (fifth review BV3)
        }
        boolean rename = java.util.Objects.equals(from.getParent(), to.getParent());
        // A drag of several asks once, so it names them all, not only the first (sixth review BV3)
        boolean many = moveGroup != null && moveCount > 1;
        boolean answer = confirmRenameWithoutEdits.test(
                many
                        ? tr("dialog.lsp.renameWithoutEdits.manyBody", name, moveCount - 1)
                        : tr(
                                rename
                                        ? "dialog.lsp.renameWithoutEdits.renameBody"
                                        : "dialog.lsp.renameWithoutEdits.body",
                                name));
        if (moveGroup != null) {
            moveWithoutEditsAnswer = answer;
        }
        lastRenameRefused = !answer;
        return answer;
    }

    /** The answer the user gave about moving without edits during the drag under way, or null when not asked. */
    private @Nullable Boolean moveWithoutEditsAnswer;

    /** Asks whether to rename a file jdtls could not edit for; a field so a test can answer. */
    java.util.function.Predicate<String> confirmRenameWithoutEdits = this::askRenameWithoutEdits;

    private boolean askRenameWithoutEdits(String body) {
        javafx.scene.control.Alert confirm = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                body,
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.renameWithoutEdits.title"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                == javafx.scene.control.ButtonType.OK;
    }

    private void treeRenamed(AppliedEdit edit, Path from, Path to) {
        Path f = from.toAbsolutePath().normalize();
        Path t = to.toAbsolutePath().normalize();
        java.util.List<EditedFile> files = new java.util.ArrayList<>();
        for (EditedFile e : edit.files()) {
            Path p = e.path().toAbsolutePath().normalize();
            Path now = p.startsWith(f) ? t.resolve(f.relativize(p)) : e.path();
            files.add(new EditedFile(now, e.before(), e.after()));
        }
        java.util.List<AppliedEdit> group = moveGroup;
        if (java.nio.file.Files.isDirectory(t)) {
            lastApplied = null; // a folder cannot be moved back as one: rename it back, and jdtls edits again
            moveGroupWhole = false;
        } else {
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames =
                    new java.util.ArrayList<>(edit.renames());
            renames.add(new com.editora.lsp.WorkspaceEditMapper.FileRename(from, to, false));
            lastApplied = new AppliedEdit(java.util.List.copyOf(files), java.util.List.copyOf(renames), edit.created());
        }
        lspManager.notifyWatchedFiles(java.util.List.of(
                new com.editora.lsp.LspManager.WatchedFile(from, com.editora.lsp.LspManager.WatchedKind.DELETED),
                new com.editora.lsp.LspManager.WatchedFile(to, com.editora.lsp.LspManager.WatchedKind.CREATED)));
        if (group != null && lastApplied != null) {
            group.add(lastApplied);
        }
        // Saved only now that the move is recorded: a save can run a nested event loop, which ran the drag's next
        // move and its end first (sixth review BS1).
        for (EditedFile e : files) {
            EditorBuffer b = ops.bufferForPath(e.path());
            if (b != null && b.isDirty() && b.getContent().equals(e.after())) {
                ops.saveBuffer(b);
            }
        }
        if (group != null) {
            return; // a drag-move: the tree says what moved, not "Renamed to" (fourth review BF3)
        }
        // the saves above said "Saved /the/whole/path"; the rename is what happened (BT7)
        host.setStatus(tr("status.renamedTo", PathDisplay.fileName(to)));
    }

    /** What the user chose when Undo met a buffer the last refactoring edited. */
    enum RefactoringUndoChoice {
        ALL_FILES,
        THIS_FILE,
        CANCEL
    }

    /** Asks whether Undo should revert the whole refactoring; a field so a test can answer. */
    java.util.function.IntFunction<RefactoringUndoChoice> refactoringUndoChooser = this::askRefactoringUndo;

    private RefactoringUndoChoice askRefactoringUndo(int files) {
        javafx.scene.control.ButtonType all = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.undoRefactoring.all", files), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType one = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.undoRefactoring.thisFile"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.Alert confirm = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr("dialog.lsp.undoRefactoring.body", files),
                all,
                one,
                javafx.scene.control.ButtonType.CANCEL));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.undoRefactoring.title"));
        confirm.setHeaderText(null);
        var chosen = confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        return chosen == all
                ? RefactoringUndoChoice.ALL_FILES
                : chosen == one ? RefactoringUndoChoice.THIS_FILE : RefactoringUndoChoice.CANCEL;
    }

    /**
     * Undo in {@code buffer}: when it still holds what the last refactoring left there (and so do the edit's
     * other files), asks whether to undo the refactoring everywhere. True when this took over the Undo — done
     * for all files, or cancelled; false leaves the ordinary per-file Undo to run.
     */
    boolean offerRefactoringUndo(@Nullable EditorBuffer buffer) {
        AppliedEdit edit = lastApplied;
        if (edit == null || buffer == null) {
            return false;
        }
        Path path = buffer.getPath();
        if (path == null || !holdsAfter(edit, path, buffer.getContent())) {
            return false; // edited since (or never part of it): an ordinary Undo
        }
        if (edit.files().stream().noneMatch(f -> f.path().equals(path)) || !refactoringIntact(edit)) {
            return false;
        }
        int files = edit.files().size() + edit.created().size();
        if (files < 2 && edit.renames().isEmpty()) {
            return false; // one file, nothing moved: the ordinary Undo is the whole of it
        }
        return switch (refactoringUndoChooser.apply(files)) {
            case ALL_FILES -> {
                undoRefactoring();
                yield true;
            }
            case CANCEL -> true;
            case THIS_FILE -> false;
        };
    }

    private static boolean holdsAfter(AppliedEdit edit, Path path, String text) {
        return edit.files().stream()
                .anyMatch(f -> f.path().equals(path) && f.after().equals(text));
    }

    /** Whether every file of {@code edit} still holds what it left — in its tab, or on disk. */
    private boolean refactoringIntact(AppliedEdit edit) {
        for (EditedFile f : edit.files()) {
            EditorBuffer open = ops.bufferForPath(f.path());
            String now;
            if (open != null) {
                now = open.getContent();
            } else {
                try {
                    now = java.nio.file.Files.readString(f.path());
                } catch (java.io.IOException | RuntimeException e) {
                    return false;
                }
            }
            if (!now.equals(f.after())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reverts the last refactoring in every file it changed, moves its renamed files back and deletes the files it
     * created ({@code lsp.undoRefactoring}). The inverse is itself a workspace edit, so it goes through the same
     * checks, journal and tab remapping, and is saved the same way. Refused when any of the files changed since.
     */
    void undoRefactoring() {
        AppliedEdit edit = lastApplied;
        if (edit == null) {
            host.setStatus(tr("status.lsp.noRefactoringToUndo"));
            return;
        }
        if (!refactoringIntact(edit)) {
            host.setError(tr("status.lsp.refactoringChangedSince"));
            return;
        }
        java.util.List<
                        org.eclipse.lsp4j.jsonrpc.messages.Either<
                                org.eclipse.lsp4j.TextDocumentEdit, org.eclipse.lsp4j.ResourceOperation>>
                changes = new java.util.ArrayList<>();
        for (EditedFile f : edit.files()) {
            org.eclipse.lsp4j.Position end = endOf(f.after());
            changes.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new org.eclipse.lsp4j.TextDocumentEdit(
                    new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(
                            f.path().toUri().toString(), null),
                    java.util.List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new org.eclipse.lsp4j.TextEdit(
                            new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0), end), f.before()))))));
        }
        for (int i = edit.renames().size() - 1; i >= 0; i--) {
            var r = edit.renames().get(i);
            changes.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(new org.eclipse.lsp4j.RenameFile(
                    r.to().toUri().toString(), r.from().toUri().toString())));
        }
        for (Path c : edit.created()) {
            changes.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                    new org.eclipse.lsp4j.DeleteFile(c.toUri().toString())));
        }
        var inverse = new org.eclipse.lsp4j.WorkspaceEdit(changes);
        com.editora.lsp.WorkspaceEditMapper.Mapped mapped = com.editora.lsp.WorkspaceEditMapper.map(inverse);
        if (mapped == null) {
            host.setError(tr("status.lsp.refactoringUndoFailed"));
            return;
        }
        int files = edit.files().size() + edit.created().size();
        undoingEdit = true;
        beginReportedEdit();
        applyWorkspaceEditsConfirmed(mapped, ok -> {
            undoingEdit = false;
            if (ok) {
                lastApplied = null;
                if (edit == treeMoveEdit) {
                    treeMoveEdit = null;
                    onTreeMoveUndone.run(); // the drag is taken back: Undo Move has nothing left (BF1)
                }
                Path[] made = newPackageFolders.remove(edit);
                if (made != null) {
                    // Move ▸ New package's folder goes with it (BT4), and jdtls hears it is gone: the next Move
                    // still offered the package (fourth review BF4)
                    lspManager.notifyWatchedFiles(removeEmptyFolders(made[0], made[1]).stream()
                            .map(f -> new com.editora.lsp.LspManager.WatchedFile(
                                    f, com.editora.lsp.LspManager.WatchedKind.DELETED))
                            .toList());
                }
            }
            reportEdit(ok, tr("status.lsp.refactoringUndone", files), tr("status.lsp.refactoringUndoFailed"));
        });
    }

    /** Folders Move ▸ New package created, by the refactoring that filled them, with their source root. */
    private final Map<AppliedEdit, Path[]> newPackageFolders = new java.util.IdentityHashMap<>();

    /**
     * Deletes {@code folder} and its parents up to (not including) {@code root} while they are empty, and returns
     * the folders it deleted.
     */
    static java.util.List<Path> removeEmptyFolders(Path folder, Path root) {
        java.util.List<Path> deleted = new java.util.ArrayList<>();
        @Nullable Path at = folder.toAbsolutePath().normalize();
        Path stop = root.toAbsolutePath().normalize();
        while (at != null && at.startsWith(stop) && !at.equals(stop)) {
            try (var entries = java.nio.file.Files.list(at)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
                java.nio.file.Files.delete(at);
                deleted.add(at);
            } catch (java.io.IOException | RuntimeException e) {
                break;
            }
            at = at.getParent();
        }
        return deleted;
    }

    /** The position just past the end of {@code text}, in LSP's UTF-16 units. */
    static org.eclipse.lsp4j.Position endOf(String text) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new org.eclipse.lsp4j.Position(line, text.length() - lineStart);
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

    private @Nullable Path interruptedEditsCheckedFor;

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
    @Nullable
    Path workspaceEditJournalDir;

    private @Nullable Path journalDir() {
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
            @Nullable Path overwrittenBackup,
            boolean created,
            @Nullable WorkspaceFileIdentity identity) {}

    private record StagedDelete(
            com.editora.lsp.WorkspaceEditMapper.FileDelete operation,
            Path stage,
            @Nullable WorkspaceFileIdentity identity) {}

    private java.util.@Nullable List<StagedCreate> stageCreates(
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

    private java.util.@Nullable List<StagedDelete> stageDeletes(
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

    private void deleteRecursively(@Nullable Path path) {
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
            @Nullable Path overwrittenBackup,
            @Nullable WorkspaceFileIdentity identity) {}

    /**
     * Executes every file move as one recoverable batch before editor text is touched. Sources and overwritten
     * destinations are first moved aside; if any later move fails, every path is restored from those stages.
     */
    private java.util.@Nullable List<StagedRename> stageRenames(
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
            WorkspaceFileIdentity fromIdentity = files.identity(from);
            if (fromIdentity == null) {
                return false;
            }
            WorkspaceFileIdentity toIdentity = files.identity(to);
            if (toIdentity == null) {
                return false;
            }
            Object source = fromIdentity.fileKey();
            Object destination = toIdentity.fileKey();
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

        showAtCaret(popup, area);
    }

    /**
     * Shows {@code popup} under {@code area}'s caret line, or above it when it doesn't fit below (Java review
     * A8: hover and signature help covered the line they describe near the window's bottom). The height is
     * only known once shown, so it is moved after showing.
     */
    private static void showAtCaret(javafx.stage.PopupWindow popup, CodeArea area) {
        var bounds = area.getCaretBounds().orElse(null);
        if (bounds == null) {
            popup.show(area, 0, 0);
            return;
        }
        popup.show(area, bounds.getMinX(), bounds.getMaxY());
        double top = com.editora.editor.completion.CaretPopupPlacement.top(bounds, popup.getHeight());
        if (top < bounds.getMinY()) {
            popup.setAnchorY(top);
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
     * Smart Type completion (IntelliJ's Ctrl+Shift+Space, Java only): the completion list cut down to what fits
     * the type expected at the caret. The type comes from the code ({@link com.editora.lsp.JavaExpectedType}),
     * or for a call's argument from signature help; where none is stated the full list opens, and says so.
     */
    void smartTypeCompletion() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        if (b == null || path == null || !LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))) {
            host.setStatus(tr("status.lsp.smartTypeJavaOnly"));
            if (b != null) {
                b.triggerCompletion();
            }
            return;
        }
        CodeArea area = b.getFocusedArea();
        int caret = area.getCaretPosition();
        long version = b.docVersion();
        var expected = com.editora.lsp.JavaExpectedType.at(area.getText(), caret);
        if (expected == null) {
            host.setStatus(tr("status.lsp.smartTypeUnknown"));
            b.triggerCompletion();
            return;
        }
        String type = expected.type();
        if (type != null) {
            showSmartType(b, List.of(type), expected.afterNew());
            return;
        }
        lspManager.changeDocument(path, b.text());
        lspManager.signatureHelp(path, area.getCurrentParagraph(), area.getCaretColumn(), null, false, null, help -> {
            if (b != host.activeBuffer()
                    || b.isDisposed()
                    || version != b.docVersion()
                    || caret != area.getCaretPosition()) {
                return;
            }
            // Every overload's parameter here, the active one's first: println(count) fits println(int) although
            // jdtls lists println(char[]) first (fourth review AF2). Each binds its type variables from its own
            // return type against what the line declares (AF1).
            String target = com.editora.lsp.JavaExpectedType.declaredTarget(area.getText(), caret);
            java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
            Integer argument = com.editora.lsp.JavaExpectedType.argumentIndex(area.getText(), caret);
            for (var parameter : com.editora.lsp.SignatureFormat.parametersAtCaret(help, argument)) {
                types.addAll(com.editora.lsp.JavaExpectedType.bindTypeVariables(
                        com.editora.lsp.JavaExpectedType.parameterTypes(parameter.label()),
                        target,
                        parameter.returnType()));
            }
            if (types.isEmpty()) {
                host.setStatus(tr("status.lsp.smartTypeUnknown"));
                b.triggerCompletion();
            } else {
                showSmartType(b, List.copyOf(types), expected.afterNew());
            }
        });
    }

    /** {@code types}: what the argument may be, the first named in the status (a varargs element, then its array). */
    private void showSmartType(EditorBuffer b, List<String> types, boolean afterNew) {
        String type = types.get(0);
        long others = types.stream()
                .filter(t -> !t.equals(type) && !t.equals(type + "[]"))
                .count();
        host.setStatus(
                others == 0 ? tr("status.lsp.smartType", type) : tr("status.lsp.smartTypeOverloads", type, others));
        b.triggerCompletion(items -> {
            List<com.editora.completion.Completion> kept =
                    com.editora.lsp.SmartTypeFilter.filter(items, types, afterNew);
            if (kept.isEmpty() && !items.isEmpty()) {
                Platform.runLater(() -> host.setStatus(tr("status.lsp.smartTypeNone", type)));
            }
            return kept;
        });
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
    void signatureHelp(boolean manual, @Nullable Character triggerChar) {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lspPath(b);
        if (b == null || path == null || !lspManager.isManaged(path) || !lspManager.supportsSignatureHelp(path)) {
            if (manual) {
                host.setStatus(tr("status.lsp.noSignatureHelp"));
            }
            return;
        }
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

        showAtCaret(popup, area);
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
