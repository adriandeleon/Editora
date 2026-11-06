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
 *       {@link MermaidCoordinator}), per-server detection ({@code serverAvailable}/{@code LspServerCommands.SERVER_IDS}), the
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
    /** Folder trust for the lemminx servers: which pom may reach lemminx-maven, and Trust Folder (E1, C6). */
    private final XmlFolderTrust xmlTrust;

    final HtmlLspAids html; // HTML links, the markup check and colour swatches (HTML review B6, D5, D9)
    final EslintCoordinator eslint; // a JS/TS project's own ESLint: problems, fixes, Fix All (JS review F)
    final JsFormatCoordinator jsFormat; // its Prettier/Biome for Format Document, and JS/TS actions on save (F)
    /** Definition, references, implementations, hierarchies, workspace symbols and library-source tabs. */
    final LspNavigation navigation;
    /** Go to Super Method. */
    final LspSuperMethods superMethods;

    private final Executor workspaceExecutor;
    /** A server's workspace edits: the all-or-nothing apply pipeline, its outcome reports, interrupted edits. */
    final LspWorkspaceEdits workspaceEdits;
    /** Undo Last Refactoring and the Project tree's rename participant. */
    final LspRefactoringUndo refactoringUndo;
    /** Rename and the jdtls refactorings and generators the client drives. */
    final LspRefactorings refactorings;

    /** Diagnostics by file, <b>scoped to open files only</b> (a server publishes project-wide). */
    private final Map<Path, List<LspDiagnostic>> problems = new LinkedHashMap<>();
    /** What each buffer last drew: its faded tags too, which {@link #problems} leaves out (Python 2, C105). */
    private final Map<EditorBuffer, List<LspDiagnostic>> drawn = new java.util.WeakHashMap<>();

    /** The Problems / References / Hierarchy panels, each built on its first use (see the accessors). */
    private @Nullable ProblemsPanel problemsPanel;

    private final ProblemsPanel.Actions problemsActions;
    /** The Problems tree's active file, remembered for a panel not built yet. */
    private @Nullable Path problemsActiveFile;

    /** serverId → whether that server's command was found on this machine (per-server availability). */
    private final Map<String, Boolean> serverAvailable = new java.util.HashMap<>();

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
        this.xmlTrust = new XmlFolderTrust(this, host, lspManager, ops);
        this.html = new HtmlLspAids(host, lspManager, ops);
        this.eslint = new EslintCoordinator(this, host, ops);
        this.jsFormat = new JsFormatCoordinator(this, host, lspManager, ops, eslint);
        this.navigation = new LspNavigation(host, lspManager, ops, new LspNavigation.Host() {
            @Override
            public @Nullable EditorBuffer activeLspBuffer() {
                return LspCoordinator.this.activeLspBuffer();
            }

            @Override
            public @Nullable Path lspPath(EditorBuffer buffer) {
                return LspCoordinator.this.lspPath(buffer);
            }

            @Override
            public @Nullable EditorBuffer bufferForLspPath(@Nullable Path path) {
                return LspCoordinator.this.bufferForLspPath(path);
            }

            @Override
            public void noteLibraryTarget(@Nullable Path anchor, Path target) {
                LspCoordinator.this.noteLibraryTarget(anchor, target);
            }

            @Override
            public boolean followLinkAtCaret() {
                return html.links.followAtCaret();
            }
        });
        this.superMethods = new LspSuperMethods(host, lspManager, ops, navigation, this::openText);
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
        PythonFormatCoordinator.Host formatHost = new PythonFormatCoordinator.Host() {
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
        };
        this.pythonFormat = new PythonFormatCoordinator(formatHost, host::settings, ops::saveBuffer);
        this.xmlFormat = new XmlFormatCoordinator(formatHost); // XML without a formatting server (C8)
        WorkspaceFileOperations files = java.util.Objects.requireNonNull(workspaceFiles, "workspaceFiles");
        this.workspaceExecutor = java.util.Objects.requireNonNull(workspaceExecutor, "workspaceExecutor");
        this.workspaceEdits = new LspWorkspaceEdits(
                host,
                lspManager,
                ops,
                new LspWorkspaceEdits.Host() {
                    @Override
                    public @Nullable Path lspPath(EditorBuffer buffer) {
                        return LspCoordinator.this.lspPath(buffer);
                    }

                    @Override
                    public @Nullable EditorBuffer bufferForLspPath(@Nullable Path path) {
                        return LspCoordinator.this.bufferForLspPath(path);
                    }

                    @Override
                    public void clearDiagnostics(@Nullable Path file) {
                        LspCoordinator.this.clearDiagnostics(file);
                    }

                    @Override
                    public void syncBufferWhenShown(EditorBuffer buffer) {
                        LspCoordinator.this.syncBufferWhenShown(buffer);
                    }

                    @Override
                    public void syncBufferNow(EditorBuffer buffer) {
                        deferredLsp.remove(buffer);
                        syncBuffer(buffer);
                    }

                    @Override
                    public void recordEdit(
                            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
                            java.util.Set<EditorBuffer> touched,
                            Map<EditorBuffer, String> before,
                            java.util.Set<Path> created) {
                        LspCoordinator.this.refactoringUndo.recordEdit(mapped, touched, before, created);
                    }
                },
                files,
                this.workspaceExecutor);
        this.refactoringUndo = new LspRefactoringUndo(host, lspManager, ops, workspaceEdits);
        this.refactorings = new LspRefactorings(
                host,
                lspManager,
                ops,
                new LspRefactorings.Host() {
                    @Override
                    public @Nullable EditorBuffer activeLspBuffer() {
                        return LspCoordinator.this.activeLspBuffer();
                    }

                    @Override
                    public @Nullable Path lspPath(EditorBuffer buffer) {
                        return LspCoordinator.this.lspPath(buffer);
                    }

                    @Override
                    public ProjectScope projectScope() {
                        return projectScope;
                    }

                    @Override
                    public Map<Path, List<LspDiagnostic>> problems() {
                        return problems;
                    }
                },
                workspaceEdits,
                refactoringUndo);
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
        lspManager.setOnSessionCrashed(this::onSessionCrashed);
        lspManager.setOnSharedServerEnded(this::sharedServerEnded);
        lspManager.setOnWatchedFiles(this::rootWatcherChanged);
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
        lspManager.setOnPythonEnvironmentChanged(pythonInterpreters::environmentChanged); // review 2, B103
        pythonInterpreters.setServerRoots(b -> lspRootFor(b, "python")); // the item shows what Pyright is told
        lspManager.setFolderTrust(this::folderTrusted);
        lspManager.setOnStartWithheld(this::onStartWithheld);
        lspManager.setOnBuildNotLoaded(xmlTrust::buildNotLoaded);
        lspManager.setOnSecondServer((serverId, root) -> host.setStatus(tr(
                "status.lsp.secondServer",
                serverLabel(serverId),
                root.getFileName() == null ? root : root.getFileName())));
        lspManager.setOnStartFailed(this::onStartFailed);
        lspManager.setOnStartRefused(this::onStartRefused);
        lspManager.setOnFolderTypeScriptIgnored(nodeTypes::folderTypeScriptIgnored); // said at ready (JS review B109)
        lspManager.setOnRequestError(this::onRequestError);
        pomCompletionNotice = new PomServerSupport(
                host::setStatus,
                PomServerSupport.MavenWorkspace.of(lspManager),
                host::forEachBuffer,
                this::lspPath,
                this::syncBuffer); // keeps pom completion alive (E3, E21-E24)
        lspManager.setApplyEditHandler(
                workspaceEdits::applyWorkspaceEditsConfirmed); // server quick-fix edits land here (#670)
        lspManager.setOnEditBlocked(workspaceEdits::editBlocked);
        lspManager.setOnRefreshRequested(this::refreshRequested);
        lspManager.setOpenDocumentDiagnosticsOnly(!projectWideProblems); // the Problems window's default scope
        lspManager.setOnDiagnosticsUnchanged(this::diagnosticsUnchanged);
        lspManager.setClientUi(serverPrompts()); // a server's questions (Metals' "Import build?") get asked
        xml = new XmlLspActions(
                host,
                lspManager,
                ops::openAndGoto,
                this::activeLspBuffer,
                this::lspPath,
                () -> navigation.findReferences(true),
                this::applyCodeActionItem);
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
        List<LspDiagnostic> last = buffer == null ? null : drawn.get(buffer);
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
            for (var asked : due.entrySet()) {
                String kind = asked.getKey();
                if (!servedByAny(path, asked.getValue())) {
                    continue;
                }
                // The server's answers of this kind changed without the document changing: one already on its way
                // may predate that, so it must not stand in for the request made here. Only this kind (B21).
                lspManager.invalidateRequests(path, kind);
                if (!refreshAppliesTo(kind, buffer == active)) {
                    refreshWhenShown.add(buffer);
                    continue;
                }
                switch (kind) {
                    case "diagnostics" -> lspManager.pullDiagnostics(path);
                    case "semanticTokens" -> requestSemanticTokens(buffer);
                    case "inlayHints" -> requestInlayHints(buffer);
                    case "foldingRanges" -> requestFoldingRanges(buffer);
                    case "codeLenses" -> requestCodeLens(buffer); // XML review 2 B2
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
        return activeBuffer
                || !("semanticTokens".equals(kind) || "inlayHints".equals(kind) || "codeLenses".equals(kind));
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
            crashedServers.put(serverId, reason == null ? "" : reason); // "": gave up, nothing said (Bash A7)
        }
        host.setStatus(
                restart
                        ? tr("status.lsp.crashed", serverLabel(serverId))
                        : reason == null
                                ? tr("status.lsp.crashLoopError", serverLabel(serverId))
                                : tr("status.lsp.crashLoopReasonError", serverLabel(serverId), reason));
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
        xmlTrust.buildWithheld(serverId, root); // a server that would run the folder's build: Trust Folder
        host.forEachBuffer(b -> {
            Path p = lspPath(b);
            if (p != null
                    && serverId.equals(serverIdForBuffer(b))
                    && sameLspRoot(lspRootFor(b, serverId), root)
                    && !lspManager.isManaged(p)) {
                b.setLspActive(false);
                xmlTrust.afterSync(b);
            }
        });
        host.setStatus(xmlTrust.withheldStatus(serverId)); // astro-ls's SDK, an untrusted pom (E1), or a build
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
        noTypeScript.put(
                crashKey(serverId, root),
                refusal.kind() == com.editora.lsp.StartRefusal.Kind.NO_TYPESCRIPT ? refusal : null);
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
        String said = LspStartMessages.refused(
                refusal.kind() == com.editora.lsp.StartRefusal.Kind.NO_JAVA_FOR_SERVER
                        ? serverProgram(serverId)
                        : serverLabel(serverId),
                refusal,
                ops.lspProjectRoot() != null);
        if (refusal.kind() == com.editora.lsp.StartRefusal.Kind.XML_LOADS_MAVEN) {
            refusedServers.put(serverId, said); // Doctor and Settings say so too, not "found" and "OK" (review 4, A32)
        }
        host.setError(said);
        Platform.runLater(() -> host.setError(said));
        updateStatusBar();
        ops.onDetectionSettled(); // NO_TYPESCRIPT: the install banner offers Editora's own TypeScript (JS review B113)
    }

    /** By {@link #crashKey}: the TypeScript server's NO_TYPESCRIPT refusal, valid while its key is start-blocked. */
    private final Map<String, com.editora.lsp.@Nullable StartRefusal> noTypeScript = new java.util.HashMap<>();

    /** The NO_TYPESCRIPT refusal holding back {@code b}'s TypeScript server, or null (JS review B113). */
    com.editora.lsp.@Nullable StartRefusal noTypeScriptFor(EditorBuffer b) {
        String id = com.editora.lsp.TypeScriptServer.SERVER_ID, key = crashKey(id, lspRootFor(b, id));
        return lspPath(b) != null && id.equals(serverIdForBuffer(b)) && startBlocked.contains(key)
                ? noTypeScript.get(key)
                : null;
    }

    /** Servers Editora would not start with their command (the lemminx-maven guard), by id → what the status said. */
    private final Map<String, String> refusedServers = new java.util.HashMap<>();

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
        String said = crashedServers.get(serverId);
        return said == null || said.isEmpty() ? null : said;
    }

    /**
     * {@link #serverFailure}, {@link #serverCrash} or a refused start, whichever says why {@code serverId} is given up
     * on or not running.
     */
    @Nullable
    ServerTrouble serverTrouble(String serverId) {
        String crash = crashedServers.get(serverId);
        if (crash != null) {
            return new ServerTrouble(crash, true);
        }
        String failing = failingServers.get(serverId);
        if (failing != null) {
            return new ServerTrouble(failing, false);
        }
        String refused = refusedServers.get(serverId);
        return refused == null ? null : new ServerTrouble(refused, false, true);
    }

    /**
     * Why a server is given up on: what it said (or, {@code refused}, what Editora said when it would not start its
     * command), and whether it crashed at start rather than failed requests.
     */
    record ServerTrouble(String detail, boolean crashed, boolean refused) {
        ServerTrouble(String detail, boolean crashed) {
            this(detail, crashed, false);
        }
    }

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
     * on the window's first open. Diagnostics that arrived before then are in {@link #problems}; the build shows them.
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
        Path build = root == null ? null : buildFolder(root, file); // a build beside others: its own (twelfth CL2)
        if (build == null || !JavaImportCheck.inSourceFolder(build, file)) {
            return; // a loose file in a project's other folders is a non-project file by rights
        }
        String key = importKey(LspServerRegistry.JAVA_SERVER_ID, root);
        String message = JavaImportCheck.nonProjectMessage(diagnostics);
        Map<Path, String> files = nonProjectFiles.computeIfAbsent(key, k -> new java.util.HashMap<>());
        if (message == null) {
            files.remove(file);
        } else {
            files.put(file, message);
            if (saidReady.contains(statusSource(LspServerRegistry.JAVA_SERVER_ID, root))) {
                nonProjectImport(build, message);
            }
        }
    }

    /**
     * The folder of {@code file}'s build under {@code root}: the root when it has a build file, else the nearest folder
     * above the file that has one; null for none.
     */
    private static @Nullable Path buildFolder(Path root, Path file) {
        if (hasJavaBuildFile(root)) {
            return root;
        }
        for (Path p = file.getParent(); p != null && p.startsWith(root) && !p.equals(root); p = p.getParent()) {
            if (hasJavaBuildFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Marks {@code folder}'s import failed (a module's in a reactor), said once with the build file's parse error when
     * it does not parse (eleventh review CM4), else jdtls's {@code message}.
     */
    private void nonProjectImport(Path folder, String message) {
        if (importFailed.add(importKey(LspServerRegistry.JAVA_SERVER_ID, folder))) {
            Path root = lspManager.javaSessionRoot(folder);
            Path sessionRoot = root != null ? root : folder;
            JavaImportCheck.PomError pomError = JavaImportCheck.unreadablePom(folder.resolve("pom.xml"));
            String pom =
                    sessionRoot.relativize(folder.resolve("pom.xml")).toString().replace('\\', '/');
            JavaImportCheck.Reason why = JavaImportCheck.projectReason(problems, folder);
            String reason = pomError == null
                    ? why != null ? why.line() : message // the pom's "Project build error" (thirteenth CK2)
                    : pomError.line() < 1
                            ? tr("status.lsp.pomUnreadable", pom, pomError.message())
                            : tr(
                                    "status.lsp.pomUnreadableAt",
                                    pom,
                                    pomError.message(),
                                    pomError.line(),
                                    pomError.column());
            if (pomError != null) {
                java.util.logging.Logger.getLogger(LspCoordinator.class.getName())
                        .info(folder.resolve("pom.xml") + " does not parse: " + pomError.describe()); // Debug Log
            }
            host.setError(tr(
                    "status.lsp.importProblemWhy",
                    failedServerName( // a module's named (fourteenth check CJ5)
                            statusServerName(LspServerRegistry.JAVA_SERVER_ID, sessionRoot),
                            LspServerRegistry.JAVA_SERVER_ID,
                            sessionRoot),
                    reason.replaceFirst("[.\\s]+$", "")));
            refreshImportNotes();
        }
    }

    /**
     * A pom.xml at {@code file} with m2e's "Project build error", once its server has said it is ready: that folder's
     * import failed, though jdtls says only "build has problems" and gives its files no diagnostics (thirteenth review
     * CK2, fourteenth check CJ2, CJ5); a healthy project's file off the classpath has no such error (CJ1).
     */
    private void modelError(Path file, List<LspDiagnostic> diagnostics) {
        var error = JavaImportCheck.modelError(file, diagnostics);
        Path root = error == null ? null : lspManager.javaSessionRoot(error.folder());
        if (error != null && root != null && saidReady.contains(statusSource(LspServerRegistry.JAVA_SERVER_ID, root))) {
            nonProjectImport(error.folder(), error.line());
        }
    }

    /**
     * A reactor's module whose pom.xml does not parse: jdtls imports the others and leaves its files without a project
     * or diagnostics, under "Loaded and ready" (eleventh review CO2).
     */
    private void checkModulePoms(Path root) {
        for (Path module : JavaImportCheck.mavenModules(root)) {
            if (JavaImportCheck.unreadablePom(module.resolve("pom.xml")) != null) {
                nonProjectImport(module, "");
            }
        }
    }

    /**
     * Whether the import of {@code folder}, or of a folder above it (its project, the session's root), failed: a build
     * beside others under one root fails on its own (eleventh review CM2).
     */
    private boolean importFailedAt(@Nullable Path folder) {
        if (importFailed.contains(importKey(LspServerRegistry.JAVA_SERVER_ID, null))) {
            return true;
        }
        for (Path p = folder == null ? null : folder.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (importFailed.contains(importKey(LspServerRegistry.JAVA_SERVER_ID, p))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code server}, named with the one folder under {@code root} whose import failed when the root's own did not: a
     * broken Gradle {@code tools/} in a Maven project is not the whole project's failure (thirteenth review CK5).
     */
    private String failedServerName(String server, @Nullable String serverId, @Nullable Path root) {
        String prefix = importKey(serverId, root) + java.io.File.separator;
        List<String> under =
                importFailed.stream().filter(k -> k.startsWith(prefix)).toList();
        if (root == null || under.size() != 1 || importFailed.contains(importKey(serverId, root))) {
            return server;
        }
        Path folder = Path.of(under.get(0).substring(under.get(0).indexOf('\u0000') + 1));
        return tr("status.lsp.serverInFolder", serverProgram(String.valueOf(serverId)), root.relativize(folder));
    }

    /**
     * Whether the import of {@code root} or of a folder under it its server imports (a module, a build beside others)
     * failed; a project under it with a server of its own fails on its own.
     */
    private boolean importFailedUnder(@Nullable Path root) {
        String prefix = importKey(LspServerRegistry.JAVA_SERVER_ID, root);
        return importFailed.stream().anyMatch(k -> k.equals(prefix) || (root != null && sameSessionUnder(k, root)));
    }

    /** Whether import key {@code key} is a folder under {@code root} that {@code root}'s server imports. */
    private boolean sameSessionUnder(String key, Path root) {
        String prefix = importKey(LspServerRegistry.JAVA_SERVER_ID, root) + java.io.File.separator;
        if (!key.startsWith(prefix)) {
            return false;
        }
        Path folder = Path.of(key.substring(key.indexOf('\u0000') + 1));
        Path served = lspManager.javaSessionRoot(folder);
        return served == null
                || served.toAbsolutePath()
                        .normalize()
                        .equals(root.toAbsolutePath().normalize());
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
        diagnostics = eslint.joined(buffer, diagnostics); // a JS/TS file's ESLint problems join the server's
        if (buffer != null) {
            buffer.setLspDiagnostics(diagnostics);
            drawn.put(buffer, diagnostics);
            noteNonProjectFile(file, diagnostics);
            xmlTrust.onDiagnostics(buffer, diagnostics); // a schema not downloaded: Trust Folder on the file (C6)
            String blocked = xmlNotices.onDiagnostics(diagnostics, () -> xmlTrust.downloadsOffStatus(buffer)); // C25
            if (blocked != null) {
                // After the opening's own lines ("Opened …", the server's ready line), which replaced it at once.
                var later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                later.setOnFinished(e -> host.setStatus(blocked));
                later.play();
            }
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
                            && (importFailedAt(project)
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
            modelError(file, diagnostics);
            // The project's error comes after jdtls said the import failed: say the reason again in its words
            // ("Could not find method …", not the Gradle wrapper's "Could not fetch model") (third review CT7).
            Path project = java.nio.file.Files.isDirectory(file) ? file : file.getParent();
            JavaImportCheck.Reason why = project == null ? null : JavaImportCheck.projectReason(problems, project);
            String reason = why == null ? null : why.line();
            String failedKey = importKey(LspServerRegistry.JAVA_SERVER_ID, project);
            if (reason != null
                    && importFailedAt(project)
                    && !reason.equals(saidImportReason.put(failedKey, reason))) { // once, not per publish
                narrowFailure(project);
                Path served = lspManager.javaSessionRoot(project);
                String server = statusServerName(LspServerRegistry.JAVA_SERVER_ID, served != null ? served : project);
                host.setError(tr(
                        "status.lsp.importProblemWhy",
                        served == null ? server : failedServerName(server, LspServerRegistry.JAVA_SERVER_ID, served),
                        reason.replaceFirst("[.\\s]+$", ""))); // named with its folder (CV4, thirteenth CK5)
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
        if (path != null && "python".equals(lspManager.managedServerId(path))) {
            setProjectWideProblems(true); // Pyright then checks every file (Python review 2, E101)
            host.setStatus(tr("status.lsp.pythonWholeProject"));
            return;
        }
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
        drawn.remove(bufferForLspPath(file));
        refreshProblems();
    }

    /** Clears every file's diagnostics (LSP disabled / servers restarted) + refreshes the panel. */
    void clearAllDiagnostics() {
        problems.clear();
        drawn.clear();
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
        String trouble = troubleSignature();
        if (!trouble.equals(appliedTrouble)) { // an install, a trust decision, a command: not a theme (JS B112)
            appliedTrouble = trouble;
            startBlocked.clear(); // a settings change (an install, a trust decision) may be what the server lacked
            noTypeScript.clear();
            failingServers.clear();
            crashedServers.clear();
            refusedServers.clear();
        }
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
        applyXmlSettings();
        appliedProjectRoot = ops.lspProjectRoot();
        appliedProjectOverrides = projectOverrideSignature();
        workspaceEdits.offerInterruptedEdits();
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
        for (String serverId : LspServerCommands.SERVER_IDS) {
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
     * buffer — each of the {@link LspServerCommands#SERVER_IDS} answers on its own, so a buffer whose server had just failed to
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

    /** Every server's effective launch command: {@link LspServerCommands#commandsForAllServers} with the project overrides
     *  that this window's trust allows ({@link #serverCommand(String)}) laid over it. */
    Map<String, String> effectiveCommands() {
        Map<String, String> commands = LspServerCommands.commandsForAllServers(host.settings());
        for (String serverId : LspServerCommands.SERVER_IDS) {
            commands.put(serverId, launchCommand(serverId));
        }
        return commands;
    }

    /** Pushes {@code Settings.lspOnTypeFormatting} to the manager, which jdtls needs to register the provider,
     *  the Default JDK (jdtls's default runtime), and the Maven server's Central-index choice (which restarts
     *  that server when it changes). */
    void applyOnTypeFormatting() {
        lspManager.setJavaOnTypeFormatting(host.settings().isLspOnTypeFormatting());
        lspManager.setJavaDefaultJdk(host.settings().getMavenJdkHome()); // a running jdtls is told at once
        lspManager.setMavenCentralIndex(host.settings().isMavenPomCentralIndex());
        lspManager.setJavaDownloadSources(host.settings().isJavaDownloadSources());
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
        if (com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)
                && com.editora.install.MavenServerInstall.editorasOwn(command, configDir())) {
            return null; // likewise for the pom server's own bundle (review 2, A1)
        }
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(command));
        String fallback = com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId);
        return normalized.equals(String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(fallback)))
                ? null
                : command.strip();
    }

    /** The configured command for a server id (blank ⇒ the server's default). */
    private String serverCommand(String serverId) {
        return projectSettings()
                .commandFor(serverId, LspServerCommands.serverCommand(host.settings(), serverId), projectTrusted());
    }

    /** How a server is started: {@link #serverCommand(String)}, a blank one filled in ({@link LspServerCommands#withOwnCopy}). */
    private String launchCommand(String serverId) {
        return LspServerCommands.withOwnCopy(
                serverId, serverCommand(serverId), configDir(), host.settings().getMavenJdkHome());
    }

    private @Nullable Path configDir() {
        com.editora.config.ConfigManager config = ops.config();
        return config == null ? null : config.getConfigDir();
    }

    /** Whether the user (or a trusted project) set a command other than the default, as launch reads it (A106). */
    boolean serverCommandSet(String serverId) {
        return customServerCommand(serverId) != null;
    }

    /**
     * Whether this window's project folder is trusted, which is what lets its committed
     * {@code .editora/settings.json} choose a program to run. Only consulted when the file overrides
     * something, so a project without one never touches the trust list.
     */
    boolean projectTrusted() {
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
        List<String> requests = new java.util.ArrayList<>(projectSettings()
                .trustRequests(id -> LspServerCommands.serverCommand(host.settings(), id), this::globalServerEnabled));
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

    /** Whether {@code toolId} is switched off (nothing is missing) or its command resolves (a lookup, no process). */
    boolean bashToolFound(String toolId) {
        return !bashIdeConfig().toolEnabled(toolId)
                || com.editora.doctor.DoctorProbes.onPath(List.of(bashToolCommand(toolId)));
    }

    /** The command the Bash server runs for {@code toolId} ({@code shellcheck}/{@code shfmt}) in this window. */
    String bashToolCommand(String toolId) {
        com.editora.lsp.BashIdeConfig c = bashIdeConfig();
        return "shfmt".equals(toolId) ? c.effectiveShfmt() : c.effectiveShellcheck();
    }

    /** What lemminx says that needs saying the Editora way (its symbol-limit notice, a blocked schema download). */
    private final XmlServerNotices xmlNotices = new XmlServerNotices();

    /** Hands the XML servers their settings (Settings → LSP → XML), each folder's trust read again (XML review D3). */
    void applyXmlSettings() {
        com.editora.config.ConfigManager config = ops.config();
        com.editora.lsp.XmlBundledSchemas.ensure(config == null ? null : config.getConfigDir()); // the POM XSDs (D12)
        lspManager.setXmlSettings(
                com.editora.lsp.XmlServerSettings.of(host.settings(), config == null ? null : config.getConfigDir()));
        XmlOutline.setLimit(host.settings().getXmlSymbolLimit()); // the outline without the server too (C9)
        xmlNotices.reset();
        html.applySettings(projectSettings().getHtml()); // the HTML servers' (B2), ignored project keys said (A107)
    }

    /** Hands Bash servers their settings: {@link #bashIdeConfig()} in the project, the user's elsewhere (A1). */
    void applyBashIde() {
        com.editora.config.ConfigManager config = ops.config();
        var user = userBashIde(host.settings(), config == null ? null : config.getConfigDir());
        lspManager.setBashIde(com.editora.lsp.BashIdeConfig.perRoot(bashIdeConfig(), user, ops.lspProjectRoot()));
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
        lspManager.setOwnTypeScriptConfigDir(config == null ? null : config.getConfigDir()); // JS review B101
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
                TrustPromptText.body(root, requests), // a folder's own TypeScript said as such (JS review B110)
                javafx.scene.control.ButtonType.CANCEL,
                trust);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.trust.title"));
        Path name = root.getFileName();
        confirm.setHeaderText(TrustPromptText.header(name == null ? root.toString() : name.toString(), requests));
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
        if (com.editora.lsp.RootResolver.hasAstroConfig(top)) {
            look.accept(top); // not in a folder without Astro: the prompt listed it there (JS review B110)
        }
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
    com.editora.config.ProjectSettings projectSettings() {
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
     * project resolves to different server commands or enables; true when it did. A window learns its project
     * after {@code init} has already configured the manager, so without this the first buffer would launch the
     * previous project's (or the global) command, and the HTML servers get the project's {@code html} object.
     */
    private boolean overridesReapplied() {
        workspaceEdits.offerInterruptedEdits(); // a window learns its project late; asks once per root
        Path root = ops.lspProjectRoot();
        if (java.util.Objects.equals(root, appliedProjectRoot)) {
            return false;
        }
        appliedProjectRoot = root;
        html.applySettings(projectSettings().getHtml()); // at open, its ignored keys said too (HTML review 3 A201)
        if (projectOverrideSignature().equals(appliedProjectOverrides)) {
            return false;
        }
        applySupport(); // re-detects and re-gates every buffer, this one included
        return true;
    }

    /** What a crashed, refused or blocked server may have lacked; only its change retries them ({@link #applySupport}). */
    private String troubleSignature() {
        com.editora.config.ConfigManager config = ops.config();
        return ops.lspFeatureEnabled() + "|" + new java.util.TreeMap<>(effectiveCommands()) + "|"
                + host.settings().isTypescriptTypeAcquisition() + "|"
                + host.settings().getNodePath() + "|"
                + lspManager.detectionGeneration() + "|" + projectOverrideSignature() + "|"
                + (config == null ? "" : config.getTrustStore().trustedRoots());
    }

    private @Nullable String appliedTrouble;

    /** Everything a re-configure depends on: the project's effective commands and enables, as one string. */
    private String projectOverrideSignature() {
        StringBuilder signature = new StringBuilder();
        for (String serverId : LspServerCommands.SERVER_IDS) {
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
        applyXmlSettings();
        html.settingsChanged(); // the HTML markup check switched on or off (D5)
        eslint.settingsChanged(); // ESLint switched, or a folder trusted: checked again
        xmlTrust.trustChanged(); // a pom moves to or off lemminx-maven with its folder's trust (E1)
        appliedProjectRoot = ops.lspProjectRoot();
        workspaceEdits.offerInterruptedEdits();
        if (ops.lspFeatureEnabled() && !projectOverrideSignature().equals(appliedProjectOverrides)) {
            applySupport();
        }
    }

    /** Whether {@code b} is a pom.xml served by plain lemminx because its folder is not trusted (E1). */
    boolean pomWithheldUntrusted(EditorBuffer b) {
        return xmlTrust.pomWithheldUntrusted(b);
    }

    /** Folder trust for the lemminx servers: {@code workspace.trustFolder} and its broadcast (E1, C6). */
    XmlFolderTrust xmlTrust() {
        return xmlTrust;
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
        LspServerCommands.setServerEnabled(host.settings(), serverId, on);
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
     * {@code Position(lineCount, 0)} — the document end, which is what a well-formed whole-file request looks like.
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
                    host,
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
        LspNavigation.gotoInBuffer(buffer, line, span.col());
        if (span.kind() == LspManager.CodeLensKind.IMPLEMENTATIONS) {
            gotoImplementation();
        } else {
            navigation.findReferences(true);
        }
    }

    /** {@code b}'s managing server as {@link LspFeatureNotice} names it ("Pyright"), or null (C107, E103). */
    LspFeatureNotice.@Nullable Server serverOf(@Nullable EditorBuffer b) {
        Path path = b == null ? null : lspPath(b);
        String id = path == null ? null : lspManager.managedServerId(path);
        return path == null || id == null ? null : new LspFeatureNotice.Server(lspManager, path, serverProgram(id));
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
                // A loading shell is empty: its heavy-file flag let Python/Java/XML's line limits through, so they
                // got an empty didOpen, then a whole-file insert (XML review 2 B1). The load re-syncs it.
                && !buffer.isLoading()
                && !buffer.isLargeFile() // 5+ MB files skip LSP (like highlighting/minimap/git) — see setLspActive
                // The intermediate large-source tier skips LSP (keeps highlighting) — up to a per-language limit:
                // Python keeps Pyright to 50,000 lines (review B10).
                && com.editora.lsp.LspServerRegistry.lspAllowedForSize(
                        buffer.getLanguage(), buffer.isHeavyFile(), LspDocumentFacts.lines(buffer))
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
            // Every sync re-records it (a settings apply, a language change); didChange keeps it current (B12).
            lspManager.setDocumentIndent(path, LspDocumentFacts.indent(buffer)); // quick fixes keep its indent (B8)
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
            if (com.editora.lsp.TypeScriptServer.SERVER_ID.equals(serverId) && !remote) {
                nodeTypes.opened(
                        buffer.getLanguage(),
                        buffer.text(),
                        path,
                        lspRootFor(buffer, serverId),
                        host.settings().isTypescriptTypeAcquisition(),
                        lspManager.isReady(path),
                        host::setStatus);
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
        xmlTrust.afterSync(buffer); // an untrusted pom says why it is not Maven-aware (E1)
        if (buffer == host.activeBuffer()) {
            // The "LSP: <server>" segment and the Problems stripe follow whether the active file is managed,
            // which only changes here. Every other refresh (a tab switch, a server detected) runs before a
            // newly opened file reaches this point, so they stayed hidden until the user switched tabs.
            updateStatusBar();
        }
    }

    /** The "Node.js APIs have no types" hint, said once per folder where it can be read (B9, JS review B108). */
    private final NodeTypesNotice nodeTypes = new NodeTypesNotice();

    /** Workspace root for a buffer under {@code serverId}: active project (if Projects on), else nearest build
     *  file (that server's markers), else the file's dir. */
    @Nullable
    Path lspRootFor(EditorBuffer buffer, String serverId) {
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
        if (loose != null && com.editora.lsp.BashLsp.sharesLooseRoot(serverId, ops.lspProjectRoot(), file)) {
            return loose; // one server for every loose script, wherever it is (TypeScript B7, Bash A9)
        }
        Path resolved = com.editora.lsp.RootResolver.resolveTiered(
                ops.lspProjectRoot(), file, com.editora.lsp.LspServerRegistry.rootMarkerTiersForServer(serverId));
        if (resolved != null && "html".equals(serverId)) {
            return com.editora.lsp.RootResolver.siteRoot(resolved, file, ops.lspProjectRoot()); // `/` links (A6)
        }
        if (resolved != null && "python".equals(serverId)) { // a sub-project's own Pyright (Python review 2, B101)
            return com.editora.lsp.RootResolver.pythonProjectRoot(resolved, file, ops.lspProjectRoot());
        }
        if (resolved != null && com.editora.lsp.TypeScriptServer.SERVER_ID.equals(serverId)) {
            return com.editora.lsp.RootResolver.typeScriptWorkspaceRoot(resolved, ops.lspProjectRoot()); // JS B106
        }
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

    /** {@link com.editora.lsp.RootResolver#isLibraryTarget}. Pure. */
    static boolean isLibraryTarget(Path target, Path serverRoot, @Nullable Path projectRoot) {
        return com.editora.lsp.RootResolver.isLibraryTarget(target, serverRoot, projectRoot);
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
     * lemminx-maven isn't installed. Disabling {@code maven-pom} skips the routing entirely (pure native XML), and
     * a pom whose folder is not trusted never reaches lemminx-maven, which would resolve and load what it names.
     */
    @Nullable
    String serverIdForBuffer(EditorBuffer b) {
        Path p = b == null ? null : lspPath(b);
        String base = b == null ? null : com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage());
        String pomServer = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        String fileName =
                p == null || p.getFileName() == null ? null : p.getFileName().toString();
        if (fileName != null
                && com.editora.lsp.LspServerRegistry.isPomFile(fileName)
                && serverEnabled(pomServer)
                && xmlTrust.pomTrusted(b)) { // an untrusted pom stays on plain lemminx (E1)
            Boolean mavenOk = serverAvailable.get(pomServer);
            if (mavenOk == null) {
                return null; // not yet probed — don't open on the plain XML server first; wait for the probe
            }
            // A host runs the pom server only with a command of its own (lsp.maven-pom in its tools): the local one
            // loads this machine's jars (review 2, A5). Without one the host's plain lemminx serves the pom.
            if (mavenOk && PomServerSupport.pomServerRunsFor(p, serverCommand(pomServer))) {
                return pomServer;
            }
            // probed and absent → fall through to the plain XML server (base-XML LSP still works)
        }
        // zsh startup files (.zshrc, /etc/zsh/zshenv, …) say nothing about being zsh, so bash-language-server would
        // lint them as bash. A *.zsh script stays on it (completion, hover, symbols); its SC1071 is dropped (D2).
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
            int kind,
            @Nullable String trigger,
            java.util.function.Consumer<com.editora.completion.CompletionResult> cb) {
        Path p = lspPath(buffer);
        if (p == null || !com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(lspManager.managedServerId(p))) {
            return cb;
        }
        String lineText = line >= 0 && line < buffer.getArea().getParagraphs().size()
                ? buffer.getArea().getParagraph(line).getText()
                : "";
        String prefix = com.editora.lsp.MavenPomServer.elementPrefix(lineText, column);
        String coordinate = PomServerSupport.coordinateElement(lineText, column);
        int failuresBefore = pomCompletionNotice.failuresBefore(p); // read as the request goes out (E21)
        return result -> {
            var items = com.editora.lsp.MavenPomServer.hideEnvironmentValues( // no env values on screen (D31)
                    com.editora.lsp.MavenPomServer.filterCompletion(
                            prefix, result.items(), com.editora.completion.Completion::label));
            var filtered = items == result.items()
                    ? result
                    : new com.editora.completion.CompletionResult(items, result.incomplete());
            boolean takenOver = pomCompletionNotice.onCompletion( // a malformed pom empties it: leave it out (E3)
                    coordinate,
                    items.stream().map(com.editora.completion.Completion::label).toList(),
                    p,
                    failuresBefore,
                    () -> lspManager.completion(
                            p,
                            line,
                            column,
                            kind,
                            trigger,
                            item -> autoImportAccept(buffer, item),
                            pomCompletionFilter(buffer, line, column, kind, trigger, cb)),
                    () -> cb.accept(filtered));
            if (!takenOver) {
                cb.accept(filtered);
            }
        };
    }

    private final PomServerSupport pomCompletionNotice;

    /** The accept hook for a completion item: resolve it + apply any additional edits (a TypeScript
     *  auto-import's {@code import} line). Returns null when the item can't carry extra edits. */
    private @Nullable Runnable autoImportAccept(EditorBuffer buffer, org.eclipse.lsp4j.CompletionItem item) {
        var serverCommand = com.editora.lsp.CompletionMapper.serverCommandOf(item); // the editor's own run in it (B7)
        if (!com.editora.lsp.CompletionMapper.mayHaveAdditionalEdits(item) && serverCommand == null) return null;
        return () -> {
            Path path = lspPath(buffer);
            if (path == null) return;
            Runnable command = () -> {
                var c = serverCommand;
                if (c != null && path.equals(lspPath(buffer)) && !buffer.isDisposed()) {
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

    /** An open file's text, as jdtls has it; null when {@code path} is open in no tab (eleventh review BM3). */
    private @Nullable String openText(Path path) {
        EditorBuffer b = bufferForLspPath(path);
        return b == null ? null : b.getContent();
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

    private final XmlFormatCoordinator xmlFormat;

    /**
     * {@code lsp.formatDocument} without a formatting server: Ruff/Black (Python), the built-in XML formatter, or
     * Prettier/Biome (JS/TS). True when this took the command — then it works with the language server off too.
     */
    boolean formatPythonExternally() {
        pythonInterpreters.captureSnapshot();
        return pythonFormat.formatActive() || xmlFormat.formatActive() || jsFormat.formatActive();
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
        ops.setStatusBarLsp(
                serverId != null
                        ? com.editora.lsp.TypeScriptServer.segmentName(
                                serverProgram(serverId), lspManager.typeScriptVersion(path))
                        : null);
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
    String serverLabel(String serverId) {
        String key = "install.lang." + serverId;
        String label = tr(key);
        return label.equals(key) ? serverProgram(serverId) : label;
    }

    /** The program a server runs ("typescript-language-server"), as the status bar's LSP segment shows it. */
    private String serverProgram(String serverId) {
        String configured = serverCommand(serverId);
        String cmd = configured == null || configured.isBlank()
                ? com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId)
                : configured;
        List<String> toks = com.editora.run.ProgramArgs.commandLine(cmd);
        // A program name that says the wrong thing (`java`, lemminx-linux-x86_64, `node`): its own name (C14, A15)
        String named = com.editora.lsp.LspServerRegistry.displayNameFor(serverId, toks);
        if (named != null) {
            return named;
        }
        List<String> def = com.editora.run.ProgramArgs.commandLine(
                com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId)); // past env/npx wrappers (JS B114)
        return com.editora.lsp.ServerProgramName.of(
                toks.isEmpty() ? List.of(serverId) : toks, def.isEmpty() ? "" : def.get(0));
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
        message = XmlServerNotices.rewrite(serverId, message); // lemminx's symbol limit names Editora's setting
        String source = statusSource(serverId, root);
        if (serverId != null && root != null) {
            if ("Launching".equalsIgnoreCase(type)) {
                lspManager.forgetServerError(serverId, root); // its import's errors are its own (eleventh review CM1)
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
                lspManager.forgetServerError(serverId, root); // an error before it was no import failure (CM1)
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
            for (var status : mavenReadyGates.onStatus(source, type, message)) { // one per root (E34)
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

    /** Holds each Maven server's ready status until its own initialization progress ends (C18; per root, E34). */
    private final com.editora.lsp.MavenPomServer.ReadyGates mavenReadyGates =
            new com.editora.lsp.MavenPomServer.ReadyGates();

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
            for (var line : statusNarrator.tick(System.currentTimeMillis())) {
                if (line.ready()) {
                    ops.setLspLoading(false); // work that never ended (a lost End) spun the bar on
                }
                showStatusLine(line);
            }
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
        if (root == null || folders < 2 || root.getFileName() == null) {
            return name;
        }
        String folder = root.toAbsolutePath().normalize().equals(lspManager.looseRoot())
                ? tr("status.lsp.looseFiles") // not Editora's internal folder's name (A6)
                : root.getFileName().toString();
        return tr("status.lsp.serverInFolder", name, folder);
    }

    /**
     * Forgets how the Java project at {@code root} imported (and what no known folder said), as its import runs
     * again; true when it had failed. Other projects' outcomes stand (sixth review CS1).
     */
    private boolean forgetImport(@Nullable Path root) {
        boolean failed = false;
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add(importKey(LspServerRegistry.JAVA_SERVER_ID, null));
        if (root != null) {
            String prefix = importKey(LspServerRegistry.JAVA_SERVER_ID, root);
            keys.add(prefix);
            importFailed.stream()
                    .filter(k -> sameSessionUnder(k, root))
                    .forEach(keys::add); // a module's, a build's beside others (CO2, CM2)
        }
        for (String key : keys) {
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
     * Moves a failed import from the session's root to {@code folder}, the build that failed, unless the root's own
     * build failed too (its pom.xml does not parse): a Maven build beside a failed Gradle one stays checked, under a
     * root with no build file (eleventh review CM2) or with one of its own (twelfth review CM2).
     */
    private void narrowFailure(@Nullable Path folder) {
        if (folder == null) {
            return;
        }
        Path root = lspManager.javaSessionRoot(folder);
        if (root == null
                || folder.equals(root)
                || JavaImportCheck.unreadablePom(root.resolve("pom.xml")) != null
                || lspManager.startupError(LspServerRegistry.JAVA_SERVER_ID, root) != null) { // the whole import threw
            return;
        }
        if (importFailed.remove(importKey(LspServerRegistry.JAVA_SERVER_ID, root))) {
            importFailed.add(importKey(LspServerRegistry.JAVA_SERVER_ID, folder));
            refreshImportNotes();
        }
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
            boolean failed = importFailedAt(p);
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
                nonProjectFiles.getOrDefault(key, Map.of()).entrySet().stream()
                        .findFirst()
                        .ifPresent(e -> nonProjectImport(
                                java.util.Objects.requireNonNullElse(buildFolder(root, e.getKey()), root),
                                e.getValue())); // CO2, CL2
                checkModulePoms(root);
                if (lspManager.startupError(serverId, root) == null) { // else the whole import's, said below
                    problems.forEach((at, listed) -> modelError(at, listed)); // its pom's model failed (CK2, CJ2)
                }
                host.forEachBuffer(
                        b -> { // a source file of a pom-packaging module is never checked (fourteenth CJ3)
                            Path module = JavaImportCheck.pomPackagingModule(root, lspPath(b));
                            if (module != null) {
                                nonProjectImport(
                                        module, tr("status.lsp.pomPackaging", JavaImportCheck.pomOf(root, module)));
                            }
                        });
                String threw = lspManager.startupError(serverId, root);
                if (threw != null && importFailed.add(key)) { // a pom with a model error (thirteenth review CK2)
                    JavaImportCheck.Reason why = JavaImportCheck.projectReason(problems, root);
                    host.setError(tr(
                            "status.lsp.importProblemWhy",
                            server,
                            (why != null ? why.line() : threw).replaceFirst("[.\\s]+$", "")));
                }
            }
            if (line.problem()) {
                // jdtls says WARNING both for an import that threw (a Gradle build that does not run: it logs the
                // exception) and for one that worked with build problems (a missing Maven artifact: nothing
                // logged). Only the first is "did not import" (re-review CN2, third review CT2).
                String error = serverId == null ? null : lspManager.lastServerError(serverId, root);
                // a dependency it cannot fetch: imported and checked, with problems (fourteenth check CJ4)
                String logged = error == null || error.startsWith("Unresolved dependenc") ? null : error;
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
                        JavaImportCheck.Reason why = JavaImportCheck.projectReason(problems, root);
                        String reason = why == null ? null : why.line();
                        if (reason != null && reason.equals(saidImportReason.get(key))) {
                            return; // the diagnostic said it already
                        }
                        if (why != null) {
                            saidImportReason.put(key, reason);
                            saidImportReason.put(importKey(LspServerRegistry.JAVA_SERVER_ID, why.folder()), reason);
                            narrowFailure(why.folder());
                        }
                        host.setError(tr(
                                "status.lsp.importProblemWhy",
                                failedServerName(server, serverId, root),
                                (reason != null ? reason : logged).replaceFirst("[.\\s]+$", "")));
                    };
                    if (JavaImportCheck.projectReason(problems, root) != null) {
                        say.run();
                    } else {
                        sayOnceReasoned(root, 0, say);
                    }
                }
            } else if (line.ready() && (root != null ? importFailedUnder(root) : importFailed.contains(key))) {
                host.setStatus(tr("status.lsp.readyNotImported", failedServerName(server, serverId, root))); // as error
            } else if (line.ready() && importWarned.remove(key)) {
                warnedAtReady.add(key);
                host.setStatus(tr("status.lsp.readyWithProblems", server));
            } else {
                host.setStatus(
                        line.ready()
                                ? nodeTypes.atReady(root, tr("status.lsp.ready", server))
                                : tr("status.lsp.server", server, line.text()));
                if (line.ready()) {
                    updateStatusBar(); // the segment names the TypeScript the server said it runs (JS review B109)
                }
            }
        }
    }

    /**
     * Runs {@code say} once a project diagnostic names the failed import's cause, its folder with it, or after six
     * seconds: Gradle's names it seconds after its WARNING, and the generic wrapper said first left the folder out
     * (thirteenth review CK5).
     */
    private void sayOnceReasoned(@Nullable Path root, int tries, Runnable say) {
        var wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(tries == 0 ? 600 : 500));
        wait.setOnFinished(e -> {
            if (tries >= 11 || JavaImportCheck.projectReason(problems, root) != null) {
                say.run();
            } else {
                sayOnceReasoned(root, tries + 1, say);
            }
        });
        wait.play();
    }

    /**
     * Stops {@code serverId} while its install is replaced (Doctor's Update), so it does not run on — or, on
     * Windows, hold open — the files being swapped out. The settings apply after the install starts it again, on
     * the new copy, as the files it serves are re-gated.
     */
    void stopServer(String serverId) {
        failingServers.remove(serverId); // the new copy gets a fresh chance
        crashedServers.remove(serverId);
        refusedServers.remove(serverId);
        clearDiagnosticsForServer(serverId); // before shutdown: after it, nothing would re-publish an empty list
        lspManager.shutdownServer(serverId);
    }

    /** Restarts every running server, clears diagnostics, then re-gates each buffer ({@code lsp.restartServers}). */
    void restartServers() {
        startBlocked.clear();
        noTypeScript.clear();
        failingServers.clear();
        crashedServers.clear();
        refusedServers.clear();
        importFailed.clear();
        nonProjectFiles.clear();
        refreshImportNotes();
        importWarned.clear();
        warnedAtReady.clear();
        statusNarrator.forget(null);
        // Every tab a server served reopens at once, shown or not: deferred, the Problems window lost all but one (B17)
        java.util.List<EditorBuffer> served = new java.util.ArrayList<>();
        host.forEachBuffer(b -> served.addAll(lspManager.isManaged(lspPath(b)) ? List.of(b) : List.of()));
        lspManager.restartAll(); // a Java server shared with another window restarts for both
        clearAllDiagnostics();
        applyGating();
        served.forEach(b -> ensureManaged(lspPath(b)));
        host.setStatus(tr("status.lsp.restarted"));
    }

    /** Notifies the server of a save (didSave) for a managed file + refreshes pull-model diagnostics. */
    void notifyDocumentSaved(EditorBuffer buffer, String savedText) {
        eslint.saved(buffer);
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
            if (root != null && importFailedUnder(root)) {
                // The build file of a project that did not import was saved, presumably fixed: import it again,
                // as a saved pom.xml re-syncs a project that did (fourth review CT1).
                reimport(path, root);
            } else if (root != null
                    && !LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))
                    && !lspManager.watchesModelFile(path)) {
                // jdtls updates a module's classpath only when told its build file changed (twelfth review CL1)
                lspManager.notifyWatchedFiles(java.util.List.of(new com.editora.lsp.LspManager.WatchedFile(
                        path, com.editora.lsp.LspManager.WatchedKind.CHANGED)));
            }
        }
        if (buffer != null && path != null && lspManager.isManaged(path)) {
            // Flush the current open-document state first. savedText may be an older snapshot when the user
            // continued typing during I/O; includeText still describes the bytes that actually reached disk.
            lspManager.changeDocument(path, buffer.getContent());
            lspManager.saveDocument(path, savedText);
            lspManager.pullDiagnostics(path); // no-op for push-only servers
            pomCompletionNotice.saved(path, savedText); // a left-out pom saved well-formed: its root restarts (E23)
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
        jsFormat.afterUserSave(buffer); // JS/TS: organize imports, ESLint's fixes, format (JS review F)
        pythonFormat.afterUserSave(buffer); // Python: Ruff/Black when Settings ask (Python review 2, A111)
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
            lspManager.formatDocument(path, formatRequestIndent(buffer), edits -> {
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
        SaveActionUndo.foldIntoOneUndo(buffer, saved);
        savingAfterActions.add(buffer);
        if (!ops.saveBuffer(buffer)) {
            savingAfterActions.remove(buffer);
        }
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
        html.wire(buffer); // the HTML markup check for a page no server serves (D5)
        eslint.wire(buffer); // checked when shown, after a pause in typing and on save
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
            if (path != null && lspManager.isManaged(path)) { // the indent servers are told follows the text (B12)
                lspManager.setDocumentIndent(path, LspDocumentFacts.indent(buffer));
            }
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
        buffer.setLspCompletionSource(com.editora.completion.CompletionSource.named(
                () -> LspFeatureNotice.nameOf(serverOf(buffer)), (line, column, kind, trigger, cb) -> {
                    Path path = lspPath(buffer);
                    if (path != null && lspManager.isManaged(path)) {
                        return lspManager.completion(
                                path,
                                line,
                                column,
                                kind,
                                trigger,
                                item -> autoImportAccept(buffer, item),
                                pomCompletionFilter(buffer, line, column, kind, trigger, cb));
                    }
                    cb.accept(com.editora.completion.CompletionResult.EMPTY);
                    return () -> {};
                }));
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
                com.editora.lsp.FormattingIndent indent = LspDocumentFacts.indent(buffer);
                buffer.sendLspChange(); // the didChange debounce may be pending: format the text on screen
                lspManager.rangeFormatting(path, sl, sc, el, ec, indent, cb); // with its wrap column (HTML A3)
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
                        () -> gotoProblem(buffer, false, true),
                        () -> gotoProblem(buffer, true, true));
        buffer.problemCounter().setSettled(() -> serverSettled(buffer));
        buffer.problemCounter().setUncheckedReason(() -> BashLspUi.uncheckedReason(buffer, lspPath(buffer)));
        buffer.setLspPasteImportsRequester((sl, sc, el, ec, text, stillValid) -> {
            Path path = lspPath(buffer);
            if (path != null && lspManager.isManaged(path)) {
                com.editora.lsp.FormattingIndent indent = LspDocumentFacts.indent(buffer);
                lspManager.handlePasteEvent(
                        path,
                        sl,
                        sc,
                        el,
                        ec,
                        text,
                        indent.tabSize(),
                        indent.insertSpaces(),
                        com.editora.lsp.JdtlsPaste.copiedFrom(text), // its imports name the text's types (A11)
                        stillValid,
                        buffer::getContent, // typed after the paste: imports still land above it (AL2)
                        true,
                        applied -> {});
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
                com.editora.lsp.FormattingIndent indent = LspDocumentFacts.indent(buffer);
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

    /** Palette picker over the LSP servers: toggles the chosen server's per-server enable ({@code lsp.toggleServer}). */
    void chooseServerToggle() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.lsp.toggleServer"),
                tr("palette.setting.pick"),
                () -> List.of(LspServerCommands.SERVER_IDS),
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
                () -> List.of(LspServerCommands.SERVER_IDS),
                id -> id,
                this::serverLabel,
                id -> {
                    if (id == null) {
                        return;
                    }
                    // Prefill with the GLOBAL value: this prompt writes the persistent global setting, so
                    // showing the project's override here would copy a repository's command into it.
                    host.promptText(
                            id,
                            tr("palette.setting.value"),
                            LspServerCommands.serverCommand(host.settings(), id),
                            v -> {
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
        html.requestColors(buffer, path); // colour swatches (D9) ride the same pulses
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

    /** A single-choice picker over {@code items}; {@code onChoose} is not called when it is dismissed. */
    static <T> void pickOne(
            CoordinatorHost host,
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
            reimport(path, root);
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
        cleanJavaWorkspace(b == null ? null : lspPath(b));
    }

    private void cleanJavaWorkspace(@Nullable Path path) {
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

    /**
     * Imports a failed project at {@code root} again: a pom whose model failed left a project without a Java nature in
     * jdtls's workspace, which a restart reuses, so the fixed pom still read "did not import" until Clean Java
     * Workspace; that project's workspace is cleaned instead (fourteenth check CJ7).
     */
    private void reimport(Path path, @Nullable Path root) {
        boolean model = root != null
                && (JavaImportCheck.modelErrorUnder(problems, root)
                        || lspManager.startupError(LspServerRegistry.JAVA_SERVER_ID, root) != null);
        forgetImport(root);
        if (model) {
            cleanJavaWorkspace(path);
        } else {
            restartJavaServer(path);
            host.setStatus(tr("status.lsp.projectReimporting"));
        }
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

    /** What a language server asks, asked in this window. */
    private LspServerPrompts serverPrompts() {
        return new LspServerPrompts(host, this::serverLabel, ops::openAndGoto);
    }

    /** Java roots already offered a Lombok download in this window. */
    private final java.util.Set<Path> lombokOffered = new java.util.HashSet<>();

    /** Asks whether to download Lombok for the project at a root; a field so a test can answer. */
    java.util.function.Function<Path, java.util.concurrent.CompletableFuture<Boolean>> lombokAsker =
            root -> serverPrompts()
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

    // --- navigation: LspNavigation, LspSuperMethods ----------------------------------------------------------

    /** The References tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    ReferencesPanel referencesPanel() {
        return navigation.referencesPanel();
    }

    /** The Hierarchy tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    HierarchyPanel hierarchyPanel() {
        return navigation.hierarchyPanel();
    }

    void callHierarchy() {
        navigation.callHierarchy();
    }

    void typeHierarchy() {
        navigation.typeHierarchy();
    }

    void peekDefinition() {
        navigation.peekDefinition();
    }

    void gotoDefinition() {
        navigation.gotoDefinition();
    }

    void gotoDefinition(@Nullable Runnable afterJump) {
        navigation.gotoDefinition(afterJump);
    }

    void gotoDefinition(@Nullable Runnable afterJump, java.util.function.@Nullable BooleanSupplier noAnswer) {
        navigation.gotoDefinition(afterJump, noAnswer);
    }

    void openLibraryFrame(Path anchorPath, String jdtUri, int line0) {
        navigation.openLibraryFrame(anchorPath, jdtUri, line0);
    }

    void openLibraryFrame(Path anchorPath, String jdtUri, int line0, java.util.function.Consumer<EditorBuffer> opened) {
        navigation.openLibraryFrame(anchorPath, jdtUri, line0, opened);
    }

    void findReferences() {
        navigation.findReferences();
    }

    void gotoImplementation() {
        navigation.gotoImplementation();
    }

    void gotoTypeDefinition() {
        navigation.gotoTypeDefinition();
    }

    void gotoDeclaration() {
        navigation.gotoDeclaration();
    }

    void gotoSuperMethod() {
        superMethods.gotoSuperMethod();
    }

    void librarySources(boolean choose) {
        navigation.librarySources(choose);
    }

    void gotoSymbolInWorkspace() {
        navigation.gotoSymbolInWorkspace();
    }

    void libraryTypes(String query, java.util.function.Consumer<List<LspManager.WorkspaceSymbolMatch>> cb) {
        navigation.libraryTypes(query, cb);
    }

    void libraryTypeItems(
            String query, java.util.function.Consumer<List<com.editora.search.SearchEverywhere.Item>> cb) {
        navigation.libraryTypeItems(query, cb);
    }

    static List<com.editora.search.SearchEverywhere.Item> libraryRows(
            List<LspManager.WorkspaceSymbolMatch> matches, String query) {
        return LspNavigation.libraryRows(matches, query);
    }

    void openLibraryType(LspManager.WorkspaceSymbolMatch match) {
        navigation.openLibraryType(match);
    }

    void showHover() {
        LspNavigation.NavOrigin origin = navigation.navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path path = origin.ownPath();
        navigation.syncForRequest(origin); // sync latest text before the request
        long generation = ++hoverGeneration;
        long version = b.docVersion();
        int caret = area.getCaretPosition();
        // The problems here lead the card: a lemminx error's list of what is allowed reached no keyboard user (C6).
        List<LspDiagnostic> here = path == null
                ? List.of()
                : CaretProblems.at(problemsOf(path), area.getCurrentParagraph(), area.getCaretColumn());
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
                    if ((text == null || text.isBlank()) && here.isEmpty()) {
                        host.setStatus(tr("status.lsp.noHover"));
                    } else {
                        showHoverPopup(area, text == null ? "" : text, here);
                    }
                },
                failure -> {
                    if (stale.getAsBoolean()) return;
                    // The server failed, which is not "nothing here": say so (Maven review C2).
                    host.setStatus(tr("status.lsp.hoverFailed", failure));
                    if (!here.isEmpty()) {
                        showHoverPopup(area, "", here);
                    }
                });
    }

    /** As {@link LspDocumentFacts#indent}, with the trim / final-newline rules saving applies to {@code buffer} (D3). */
    com.editora.lsp.FormattingIndent formatRequestIndent(EditorBuffer buffer) {
        com.editora.editorconfig.EditorConfigProperties rules = projectScope.saveRules(
                host.settings().isEditorConfigSupport()
                        ? buffer.getEditorConfigProps()
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY);
        return LspDocumentFacts.indent(buffer)
                .withSaveRules(rules.trimTrailingWhitespace(), rules.insertFinalNewline());
    }

    /** Reformats the whole active file via its language server ({@code textDocument/formatting}), if the
     *  server is running and advertises formatting. Edits apply through the undoable buffer. */
    void formatDocument() {
        EditorBuffer buffer = host.activeBuffer();
        Path path = buffer == null ? null : lspPath(buffer);
        if (buffer == null
                || path == null
                || !ops.activeEditable()
                || !lspManager.isManaged(path)
                || !lspManager.supportsFormatting(path)) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        // The document's own indentation, not the global tab size: a formatter given the global width
        // re-indents every line of a file indented differently (shfmt -i=4 over a two-space script).
        com.editora.lsp.FormattingIndent indent = formatRequestIndent(buffer);
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
        long errorsBefore = lspManager.loggedErrorCount(path); // a failed format says why only in the log (B3)
        Consumer<List<com.editora.text.LspTextEdit>> onEdits = edits -> {
            if (buffer != host.activeBuffer()) {
                return; // user switched tabs before the server replied
            }
            if (!buffer.getContent().equals(snapshot)) {
                host.setStatus(tr("status.lsp.formatStale")); // edited mid-format — a re-run formats cleanly
                return;
            }
            if (edits.isEmpty() || com.editora.text.LspTextEdits.changesNothing(snapshot, edits)) {
                host.setStatus(FormatOutcomes.noChange( // a failed request, or shfmt's no-op (HTML A5, Bash B3)
                        edits.isEmpty() ? lspManager.formatFailure(path) : null,
                        lspManager.managedServerId(path),
                        () -> !edits.isEmpty() || bashToolFound("shfmt"), // a no-op edit: shfmt ran
                        edits.isEmpty() ? lspManager.errorLoggedSince(path, errorsBefore) : null));
                return;
            }
            if (!NoUndoGuard.allow(buffer, tr("noUndo.op.lsp"))) {
                return;
            }
            buffer.applyLspEdits(edits);
            host.setStatus(tr(
                    selectionOnly
                            ? "status.lsp.selectionFormatted"
                            : sel.getLength() > 0 ? "status.lsp.formattedWholeFile" : "status.lsp.formatted"));
        };
        if (selectionOnly) {
            int startLine = area.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
            var end = area.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
            // Whole lines; a selection ending at a line's start doesn't take that line.
            int endLine = end.getMinor() == 0 && end.getMajor() > startLine ? end.getMajor() - 1 : end.getMajor();
            lspManager.rangeFormatting(path, startLine, 0, endLine, area.getParagraphLength(endLine), indent, onEdits);
        } else {
            lspManager.formatDocument(path, indent, onEdits);
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
        // list is about the caret (Java review A4). Other servers' source actions stay in it, except on a line
        // with a problem, where a whole-file rewrite (lemminx's Minify XML) is no fix (XML review B14).
        EditorBuffer b = host.activeBuffer(); // showCodeActions reports a file with no server, once
        Path path = b == null ? null : lspPath(b);
        boolean onProblem = b != null
                && path != null
                && CaretProblems.onLine(problemsOf(path), b.getFocusedArea().getCurrentParagraph());
        showCodeActions(
                (serverId, item) -> com.editora.lsp.CodeActionKinds.inQuickFixList(
                        LspServerRegistry.JAVA_SERVER_ID.equals(serverId), item, onProblem),
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
            List<LspManager.CodeActionItem> items = com.editora.lsp.CodeActionKinds.arrange(
                    eslint.withFixes(b, start.getMajor(), end.getMajor(), all), item -> keep.test(serverId, item));
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
            assist |= group.compareTo(com.editora.lsp.CodeActionKinds.Group.ASSIST) <= 0 // a suppression, too
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
                : tr("lsp.codeAction.group." + com.editora.lsp.CodeActionKinds.labelKey(item.kind()));
        return new com.editora.editor.lsp.CodeAction(item.title(), label, item.preferred(), item.raw());
    }

    // --- problems at the caret (Java review A6) -------------------------------------------------------

    /**
     * Next / Previous Problem (IntelliJ's F2 / Shift+F2): moves the caret to the next error of the file after it
     * (or the previous one before it), wrapping, and says what it is. Warnings and the rest when the file has
     * no errors. Before this a diagnostic could only be read with the mouse or in the Problems window.
     */
    void gotoProblem(boolean forward) {
        gotoProblem(host.activeBuffer(), forward, false);
    }

    /**
     * As {@link #gotoProblem(boolean)} in {@code b}, which takes the focus first; {@code every}: through all its
     * problems, the notes the counter counts too, as its arrows go (Bash review C5).
     */
    void gotoProblem(@Nullable EditorBuffer b, boolean forward, boolean every) {
        if (b != null && (b != host.activeBuffer() || !b.getFocusedArea().isFocused())) {
            // the editor keeps the focus, even when the last click was in the Problems window it now syncs
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
        List<LspDiagnostic> candidates = new java.util.ArrayList<>(errors.isEmpty() || every ? here : errors);
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
        Path key = ops.canonicalize(path);
        if (problemsPanel != null && key != null) {
            problemsPanel.reveal(key, target); // an open Problems window follows, unfocused
        }
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
        LspDiagnostic best = CaretProblems.nearest(problemsOf(path), area.getCurrentParagraph(), area.getCaretColumn());
        if (best == null) {
            host.setStatus(tr("status.lsp.noProblemHere"));
        } else {
            sayProblem(best);
            showHoverPopup(area, "", List.of(best)); // the whole message, which the status line cuts (XML review C6)
        }
    }

    private List<LspDiagnostic> problemsOf(Path path) {
        return problems.getOrDefault(ops.canonicalize(path), List.of());
    }

    /** Says a problem in the echo line: the script's, not Editora's, so never with the unread-error badge (C13). */
    private void sayProblem(LspDiagnostic d) {
        host.setStatus(tr("status.lsp.problem", d.startLine() + 1, d.message()));
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
        if (refactorings.runGeneratePrompt(path, item)) {
            return; // a jdtls generate prompt: we drive it, not the server (#741)
        }
        if (refactorings.runRefactorCommand(path, item)
                || xml.runsCodeAction(path, item)
                || eslint.runsCodeAction(item)) {
            return; // likewise a refactoring that asks the user where to, or what; or lemminx's binding wizard
        }
        applyCodeActionItem(path, item);
    }

    /** Applies a code action and says how it went (the popup's pick, or XML: Generate Grammar…'s). */
    void applyCodeActionItem(Path path, LspManager.CodeActionItem applied) {
        workspaceEdits.beginReportedEdit();
        lspManager.applyCodeAction(
                path,
                applied,
                ok -> workspaceEdits.reportEdit(
                        ok,
                        tr("status.lsp.codeActionApplied", bareTitle(applied.title())),
                        lspManager.lastCodeActionFailure() != null // the server's reason when it gave one (BN9)
                                ? tr(
                                        "status.lsp.codeActionFailedWhy",
                                        applied.title(),
                                        lspManager.lastCodeActionFailure())
                                : lspManager.lastCodeActionEmpty() // resolved to no change at all (ET1)
                                        ? tr("status.lsp.codeActionNoChange", applied.title())
                                        : lspManager.lastEditUnresolvedUri() != null // XML review B1
                                                ? tr(
                                                        "status.lsp.codeActionBadUri",
                                                        applied.title(),
                                                        lspManager.lastEditUnresolvedUri())
                                                : tr("status.lsp.codeActionFailed", applied.title())));
    }

    // --- Rename and the jdtls refactorings: LspRefactorings -----------------------------------------------

    /** Rename the symbol under the caret ({@link LspRefactorings#rename}). */
    void rename() {
        refactorings.rename();
    }

    String previewLabel(com.editora.lsp.RenamePreview.FileChange change) {
        return refactorings.previewLabel(change);
    }

    void applyPreviewed(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.Set<Path> keep,
            java.util.Set<Path> listed,
            String name) {
        refactorings.applyPreviewed(mapped, keep, listed, name);
    }

    static String initializeScopeLabel(String scope) {
        return LspRefactorings.initializeScopeLabel(scope);
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

    static String wordAtCaret(CodeArea area) {
        return wordAt(area.getParagraph(area.getCurrentParagraph()).getText(), area.getCaretColumn());
    }

    // --- workspace edits and Undo Last Refactoring: LspWorkspaceEdits, LspRefactoringUndo --------------------

    /** As {@link LspWorkspaceEdits#applyWorkspaceEditsConfirmed}: the applier for edits nobody previewed. */
    void applyWorkspaceEditsConfirmed(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> done) {
        workspaceEdits.applyWorkspaceEditsConfirmed(mapped, done);
    }

    /** As {@link LspWorkspaceEdits#applyWorkspaceEditsAsync}: the production workspace-edit path. */
    void applyWorkspaceEditsAsync(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> whenDone) {
        workspaceEdits.applyWorkspaceEditsAsync(mapped, whenDone);
    }

    /** As {@link LspWorkspaceEdits#applyWorkspaceEdits}: the synchronous path the tests drive. */
    boolean applyWorkspaceEdits(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        return workspaceEdits.applyWorkspaceEdits(mapped);
    }

    void beginReportedEdit() {
        workspaceEdits.beginReportedEdit();
    }

    void editBlocked(List<Path> files) {
        workspaceEdits.editBlocked(files);
    }

    void reportEdit(boolean ok, String success, String failure) {
        workspaceEdits.reportEdit(ok, success, failure);
    }

    static String blockedFileNames(List<Path> files) {
        return LspWorkspaceEdits.blockedFileNames(files);
    }

    static Path inSpellingOf(Path tabFrom, Path serverFrom, Path serverTo) {
        return LspWorkspaceEdits.inSpellingOf(tabFrom, serverFrom, serverTo);
    }

    /** Once per opened project, offers to put back a workspace edit a crash interrupted. */
    void offerInterruptedEdits() {
        workspaceEdits.offerInterruptedEdits();
    }

    /** The edit Undo Refactoring would revert (tests). */
    LspRefactoringUndo.@Nullable AppliedEdit lastAppliedEdit() {
        return refactoringUndo.lastAppliedEdit();
    }

    static LspRefactoringUndo.AppliedEdit merged(java.util.List<LspRefactoringUndo.AppliedEdit> edits) {
        return LspRefactoringUndo.merged(edits);
    }

    static org.eclipse.lsp4j.Position endOf(String text) {
        return LspRefactoringUndo.endOf(text);
    }

    void willRenameFile(Path from, Path to, java.util.function.Consumer<Boolean> proceed) {
        refactoringUndo.willRenameFile(from, to, proceed);
    }

    ProjectPanel.RenameParticipant treeParticipant() {
        return refactoringUndo.treeParticipant();
    }

    void setOnTreeMoveUndone(Runnable action) {
        refactoringUndo.setOnTreeMoveUndone(action);
    }

    boolean offerRefactoringUndo(@Nullable EditorBuffer buffer) {
        return refactoringUndo.offerRefactoringUndo(buffer);
    }

    void undoRefactoring() {
        refactoringUndo.undoRefactoring();
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

    /**
     * Shows LSP hover markdown in a dismissable popup at the caret (rendered via the Markdown renderer).
     * Closes on Escape, a click elsewhere (auto-hide), caret movement, scrolling, or another hover.
     */
    private void showHoverPopup(CodeArea area, String markdown, List<LspDiagnostic> problemsHere) {
        hideHoverPopup();
        Node content;
        Node problemsNode = CaretProblems.node(problemsHere);
        if (markdown.isBlank()) {
            content = problemsNode != null ? problemsNode : new Label();
        } else
            try {
                // Links in a hover (documentation URLs) open through the window's link choke point.
                content =
                        MarkdownRenderer.renderPopup(MarkdownRenderer.parseForRender(markdown), host::openExternalUrl);
            } catch (RuntimeException e) {
                Label label = new Label(markdown);
                label.setWrapText(true);
                content = label;
            }
        if (problemsNode != null && content != problemsNode) {
            content = new VBox(10, problemsNode, new javafx.scene.control.Separator(), content);
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
        scroll.setMaxWidth(HoverCard.maxWidth(markdown)); // a man page's 80 columns uncut (Bash review C7)
        scroll.setMaxHeight(HoverCard.maxHeight(area));
        // Wide enough for the signature: the popup sized itself to the docstring's prose, so Pyright's
        // `def helper(` block scrolled sideways under its own language tag (Python review B12).
        scroll.setMinWidth(HoverCard.codeWidth(markdown));
        VBox box = new VBox(scroll);
        box.getStyleClass().add("lsp-hover-popup");
        box.setMaxWidth(HoverCard.maxWidth(markdown));
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());
        com.editora.editor.PopupSheets.followOwnerTheme(
                box, area.getScene() == null ? null : area.getScene().getWindow());

        Popup popup = new Popup();
        popup.setAutoHide(true); // click outside / focus loss dismisses it
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        hoverPopup = popup;

        // Dismiss on Escape, caret movement, or scroll, page with Page Down/Up (and Emacs's C-v/M-v, which the
        // keymap hands over while it shows: C7) — all detached again when the popup hides.
        EventHandler<KeyEvent> esc = HoverCard.keys(scroll, this::hideHoverPopup);
        ChangeListener<Object> dismiss = (o, a, b) -> hideHoverPopup();
        boolean ownChords = area.getProperties()
                        .putIfAbsent(com.editora.command.KeyDispatcher.OWNED_CHORDS, HoverCard.SCROLL_CHORDS)
                == null;
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(dismiss);
        area.estimatedScrollYProperty().addListener(dismiss);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            if (ownChords) {
                area.getProperties().remove(com.editora.command.KeyDispatcher.OWNED_CHORDS, HoverCard.SCROLL_CHORDS);
            }
            area.caretPositionProperty().removeListener(dismiss);
            area.estimatedScrollYProperty().removeListener(dismiss);
            if (hoverPopup == popup) {
                hoverPopup = null;
            }
        });

        showAtCaret(popup, area);
        scroll.setVvalue(0); // from its first line, whatever layout scrolled to (HTML review B8)
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
        html.panelChanged(changes); // the pages' "File not found" marks, LSP on or off (HTML review 2, B106)
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

    /**
     * A tab was reloaded from disk: on lemminx its new text is also the saved one, and only a save makes lemminx
     * revalidate the documents that use it when it is a schema or DTD (XML/pom review 4, B31). FX thread.
     */
    void reloadedFromDisk(EditorBuffer buffer) {
        Path path = lspPath(buffer);
        if (path != null && com.editora.lsp.XmlServerSettings.isLemminx(lspManager.managedServerId(path))) {
            lspManager.changeDocument(path, buffer.getContent()); // the reloaded text first, then its save
            lspManager.saveDocument(path, buffer.getContent());
        }
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
        pomCompletionNotice.filesChanged(List.copyOf(pendingWatched.keySet())); // a left-out pom fixed on disk (E24)
        pendingWatched.forEach((path, kind) -> {
            if (!lspManager.reportsChangesTo(path)) { // the server root's own watcher reports it: not twice (B16)
                batch.add(new com.editora.lsp.LspManager.WatchedFile(path, kind));
            }
        });
        pendingWatched.clear();
        lspManager.notifyWatchedFiles(batch);
        buildFilesChanged(batch);
    }

    /**
     * A batch from the server roots' own watcher ({@link LspManager}), which sees every folder: build files, and a pom
     * left out of a Maven server's workspace and fixed on disk in a folder the Project panel never expanded (E32).
     */
    private void rootWatcherChanged(List<LspManager.WatchedFile> changes) {
        buildFilesChanged(changes);
        pomCompletionNotice.filesChanged(changes.stream()
                .filter(c -> c.kind() != LspManager.WatchedKind.DELETED)
                .map(LspManager.WatchedFile::file)
                .toList());
    }

    /**
     * Build files changed outside Editora (a git pull, another editor): one whose project failed on its pom's model is
     * imported again with a cleaned workspace, as a save does; jdtls's own update of it in place left every file with
     * false errors under "Loaded and ready" (fourteenth check CJ8).
     */
    private void buildFilesChanged(List<LspManager.WatchedFile> changes) {
        html.filesChanged(changes); // and the pages' "File not found" marks (B106)
        for (LspManager.WatchedFile c : changes) {
            Path name = c.file().getFileName();
            Path root = name != null && JAVA_BUILD_FILES.contains(name.toString())
                    ? lspManager.javaSessionRoot(c.file())
                    : null;
            if (root != null
                    && c.kind() != LspManager.WatchedKind.DELETED
                    && importFailedUnder(root)
                    && (JavaImportCheck.modelErrorUnder(problems, root)
                            || lspManager.startupError(LspServerRegistry.JAVA_SERVER_ID, root) != null)) {
                reimport(c.file(), root);
            }
        }
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
                Node doc = MarkdownRenderer.renderPopup(
                        MarkdownRenderer.parseForRender(active.documentation()), host::openExternalUrl);
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
        com.editora.editor.PopupSheets.followOwnerTheme(
                box, area.getScene() == null ? null : area.getScene().getWindow());

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
