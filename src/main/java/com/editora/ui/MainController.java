package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.editora.build.BuildTool;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.RecentFiles;
import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.editor.MenuChords;
import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/** Controls the main window: tabbed editors, menu actions, palette/find overlays, and status bar. */
public class MainController implements com.editora.mcp.McpBridge {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MainController.class.getName());
    static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    @FXML
    BorderPane root;

    @FXML
    private BorderPane workspace;

    /**
     * FXML injection point for the editor area's tab strip. <b>Do not use directly</b> — everything goes
     * through {@link #editorArea}, which wraps this and is the seam that lets the editor area hold several
     * independent tab groups (#762). Retained as a field only because FXML injects here (and a handful of FX
     * tests reflect on it by name); it is handed to {@code EditorArea} in {@link #init} and not read again.
     */
    @FXML
    TabPane tabPane;

    /** Long-running background work, surfaced in the status bar so a slow operation isn't mistaken for a hang. */
    final BackgroundTasks backgroundTasks = new BackgroundTasks();

    /** The editor area — the single entry point for reading and mutating open tabs. See {@link EditorArea}. */
    EditorArea editorArea;

    /** Exact-state close prompts for buffers and editable non-buffer tabs. */
    CloseCoordinator closes;

    /** The window's menu bar — a browsable map over the command registry (#763). */
    MainMenuBar menuBar;

    /** Replaces where the Run Configurations page is opened. Tests only. */
    void setRunConfigEditorForTest(java.util.function.Consumer<String> editor) {
        runConfigurations.runConfigEditor = editor == null ? runConfigurations::openRunConfigEditor : editor;
    }

    @FXML
    private VBox topBox;

    @FXML
    private VBox bottomBox;

    @FXML
    ToolBar toolBar;

    /** The row holding the toolbar: the overflowing cluster plus the pinned tail. */
    @FXML
    HBox toolbarRow;

    /** The pinned right end of the bar — never part of the ToolBar, so it never overflows. */
    @FXML
    HBox toolbarTail;

    @FXML
    Button newButton;

    @FXML
    Button newFromTemplateButton;

    @FXML
    Button openButton;

    @FXML
    Button openFolderButton;

    @FXML
    Button saveButton;

    @FXML
    Button saveAsButton;

    @FXML
    Button undoButton;

    @FXML
    Button redoButton;

    @FXML
    Button cutButton;

    @FXML
    Button copyButton;

    @FXML
    Button pasteButton;

    @FXML
    Button findButton;

    @FXML
    Button findInFilesButton;

    @FXML
    Button splitVerticalButton;

    @FXML
    Button splitHorizontalButton;

    @FXML
    Button paletteButton;

    @FXML
    Button closeTabButton;

    @FXML
    Button simpleModeButton;

    @FXML
    Button settingsButton;

    @FXML
    MenuButton recentButton;

    @FXML
    Button clearRecentButton;

    Stage stage;
    ConfigManager config;
    CommandRegistry registry;
    KeymapManager keymap;
    /** Keyboard macros (record/replay/save/run); see {@link MacroCoordinator}. Created in {@link #init}. */
    MacroCoordinator macroCoordinator;

    CommandPalette palette;
    FindReplaceBar findBar;
    StatusBar statusBar;
    FileBreadcrumb breadcrumb;
    SettingsWindow settingsWindow;
    private final DebugLogWindow debugLogWindow = new DebugLogWindow();

    /** Shared in-scene overlay host for the command palette + pickers (replaces focus-stealing Popups). */
    final OverlayHost overlayHost = new OverlayHost();

    com.editora.snippet.SnippetManager snippets;
    SnippetCoordinator snippetCoordinator;

    /** Shared across windows (owned by WindowManager); plugin classes load once, instances are per-window. */
    com.editora.plugin.@Nullable PluginManager pluginManager;
    /** Plugin support (discovery/apply/install + the per-window PluginContext); see {@link PluginCoordinator}. */
    PluginCoordinator pluginCoordinator;

    private com.editora.completion.CompletionEngine completion;

    /** The Project panel; null until {@link #projectPanel()} builds it. */
    ProjectPanel projectPanel;
    /** What the Project tree is rooted at (null: the placeholder), applied to the panel when it is built. */
    @Nullable
    Path projectRoot;
    /** The last Git status pushed to the Project tree, applied to the panel when it is built. */
    java.util.@Nullable Map<java.nio.file.Path, com.editora.git.GitFileStatus> projectGitStatus;

    private ProjectDeleteCoordinator projectDeletes;
    ProjectManager projects;
    /** The multi-window coordinator (null in single-window/test use); set right after {@link #init}. */
    @Nullable
    WindowManager windowManager;
    /** This window's project ({@code null} = the no-project/global window). */
    @Nullable
    Project windowProject;
    /** This window's project key ({@code ""} = global), kept in sync with {@link #windowProject}. */
    private String projectKey = "";

    QuickOpen<Project> projectPicker;
    /** Tracks the last-applied project-support state to detect off→on transitions (reveal the panel). */
    private boolean projectSupportApplied;

    // Auto save. Mode keys: "off" | "afterDelay" | "onFocusChange".

    ToolWindowManager toolWindows;
    ToolWindow projectToolWindow;
    ToolWindow structureToolWindow;
    ToolWindow bookmarksToolWindow;
    ToolWindow notesToolWindow;
    ToolWindow fileInfoToolWindow;
    /** The File Information / Undo History / Structure panels; each null until its accessor builds it. */
    @Nullable
    FileInformationPanel fileInfoPanel;

    ToolWindow undoHistoryToolWindow;

    @Nullable
    UndoHistoryPanel undoHistoryPanel;

    @Nullable
    StructurePanel structurePanel;
    /** Bookmarks feature; owns the panel/jump-picker/persistence. Built in {@link #init} (needs config). */
    BookmarkCoordinator bookmarkCoordinator;

    ToolWindow searchToolWindow;
    ToolWindow todoToolWindow;

    @Nullable
    MarkdownLintPanel markdownLintPanel;

    ToolWindow markdownLintToolWindow;

    // --- Remote files (SFTP via MINA SSHD; off-thread connect/auth) — owned by RemoteCoordinator ---
    RemoteCoordinator remoteCoordinator;

    /**
     * Where this window's files' tools run has changed — a host allowed or withdrawn, a folder bound to a
     * container or released: the features that run tools look again.
     */
    void toolsMoved() {
        lspManager.forgetRemoteDetections(); // a server's command there may have been set
        git.refresh();
        lspCoordinator.remoteExecChanged(); // tabs start, or stop, their servers where their tools run
        if (projectPanel != null) {
            projectPanel.refreshRemoteWatch(); // the folder's host may be watched now, or no longer
        }
    }

    /** The container this window's project's tools run in, if any. */
    ContainerCoordinator containerCoordinator;

    RemoteCoordinator remoteCoordinator() {
        return remoteCoordinator;
    }
    // MCP server: a single app-wide loopback HTTP endpoint exposing live editor state + the command
    // registry to an LLM agent. Static so only the first window with the feature on starts it (the
    // setting is shared); that window's controller is the bridge. (Multi-window caveat: if the owner
    // window closes, the server stops until a settings re-apply re-arms it from another window.)
    static com.editora.mcp.@Nullable McpServer mcpServer;
    private static @Nullable MainController mcpOwner;
    /** LSP: manager (one server per workspace root). Diagnostics route to {@link #lspCoordinator} via the
     *  thin {@link #onLspDiagnostics} delegate — a method ref (not a direct field read) so it isn't an
     *  illegal forward reference to the later-declared coordinator; the field read defers to call time. */
    final com.editora.lsp.LspManager lspManager =
            new com.editora.lsp.LspManager(this::onLspDiagnostics, this::onLspServerStatus);

    ToolWindow problemsToolWindow;
    ToolWindow referencesToolWindow;
    ToolWindow hierarchyToolWindow;
    /** Run: streams a runnable file's output; see {@link RunCoordinator}. The tool window stays here. */
    ToolWindow runToolWindow;
    /** Terminal: shells on this machine; see {@link TerminalCoordinator}. */
    ToolWindow terminalToolWindow;
    /** External Tools: the feature coordinator owns the service/panel/commands (see {@link ExternalToolCoordinator}). */
    ToolWindow externalToolToolWindow;
    /** Build tools (Maven/npm/…): one IntelliJ-style tasks-tree tool window per detected tool, whose stripe
     *  appears when the tool's marker file is found (see {@link BuildCoordinator}). */
    private final Map<BuildTool, ToolWindow> buildToolWindows = new java.util.EnumMap<>(BuildTool.class);
    /** The shared tabbed "Output" window — one tab per build tool that runs (Maven/npm/Cargo/Go/Gradle),
     *  fed by every {@link BuildCoordinator}; its stripe appears when any tool's marker is detected, and it
     *  auto-opens on a run. */
    final BuildOutputPanel buildOutputPanel = new BuildOutputPanel();

    ToolWindow buildOutputToolWindow;

    /** The IntelliJ-style Test Results window; available once a {@code test} run has occurred. */
    ToolWindow testResultsToolWindow;

    /** The release check: automatic (a few seconds after the first paint), manual, notice and download page.
     *  The lambdas read the window's fields at call time, after init. */
    final UpdateCoordinator updates = new UpdateCoordinator(
            () -> this.config.getSettings(), // qualified: these fields are declared below
            () -> this.fileWorkflows.isShutdown(),
            this::requestSave,
            this::setStatus,
            (show, version) -> this.statusBar.setUpdateAvailable(show, version),
            this::openExternalUrl);

    ToolWindow agentToolWindow;

    ToolWindow remoteToolWindow;
    /** Debug (DAP): drives Java debugging layered on the jdtls LSP session. Stays a field (SettingsWindow +
     *  window-dispose reach it); the {@link DebugCoordinator} operates on it (mirrors lspManager/LspCoordinator). */
    final com.editora.dap.DapManager dapManager = new com.editora.dap.DapManager(lspManager);

    ToolWindow debugToolWindow;
    /** The Debugging feature; owns the DebugPanel + breakpoints + DAP session flows. Built in {@link #setupToolWindows}
     *  (its panel reads workspace state). See {@link DebugCoordinator}. */
    DebugCoordinator debugCoordinator;

    /** In-app install of LSP servers + DAP adapters + Mermaid tools. Built in {@link #setupToolWindows}
     *  (after the LSP/Debug coordinators it re-detects through). See {@link InstallCoordinator}. */
    InstallCoordinator installCoordinator;

    /** The Commit panel; null until {@link #gitPanel()} builds it. The state it shows is tracked below. */
    @Nullable
    GitPanel gitPanel;

    com.editora.git.@Nullable GitStatus lastGitStatus;
    com.editora.git.@Nullable GitOperation lastGitOperation;

    @Nullable
    String lastGitRefusal;

    boolean gitAiAvailable;
    ToolWindow commitToolWindow;
    /** The Git Log panel; null until {@link #gitLogPanel()} builds it. */
    @Nullable
    GitLogPanel gitLogPanel;

    GitLogPanel.Actions gitLogOps; // reused by the git.log.* palette commands (act on the selected commit)
    ToolWindow gitLogToolWindow;
    /** GitHub PR/issue tool window; available only inside a GitHub repo. Null until {@link #githubPanel()}. */
    @Nullable
    GitHubPanel githubPanel;

    ToolWindow githubToolWindow;

    /** Local File History: snapshots local files on save/auto-save/external reload (off-thread). */
    HistoryCoordinator historyCoordinator; // read by TabContextMenu
    /** Gate for programmatic bulk edits in a buffer without undo; installed on every buffer in addBuffer. */
    private NoUndoGuard noUndoGuard;

    ToolbarCoordinator toolbarCoordinator;
    /** The toolbar's buttons, fixed tail and button enablement; see {@link WindowToolbar}. */
    final WindowToolbar toolbar = new WindowToolbar(this);

    ToolWindow fileHistoryToolWindow;

    Switcher switcher;
    /** The tab-selection and tab-list listeners, and the MRU order they keep; see {@link EditorTabListeners}. */
    final EditorTabListeners tabListeners = new EditorTabListeners(this);
    /** Pinned tabs (identity-based): kept grouped at the front and skipped by bulk-close actions. */
    final Set<Tab> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The editor-theme override stylesheet currently on the scene, or null for the default theme. */
    private @Nullable String currentEditorThemeCss;

    /** The app-wide recent-files list ({@code SharedConfig}); the search/agent histories are read from there too. */
    RecentFiles recentFiles;

    private final RecentFilesCheck recentCheck = new RecentFilesCheck();
    /** The VSCode-style Welcome page, shown in its own tab when no file is open (or via {@code view.welcome}). */
    WelcomePane welcomePane;
    /** The single open Welcome tab (a non-buffer {@link TabContent} tab), or null when none is open. */
    @Nullable
    Tab welcomeTab;
    /** The single open Doctor tab (external-tool health screen), or null — the {@code welcomeTab} pattern. */
    @Nullable
    Tab doctorTab;
    /** The hint the editor area shows once its last tab is closed (Open File, Command Palette, …). */
    @Nullable
    EditorEmptyState editorEmptyState;
    /** Opens external URLs (the Welcome page's home-page link) in the system browser; set from {@code App}. */
    private javafx.application.@Nullable HostServices hostServices;

    public void installZenOverlay(StackPane sceneRoot) {
        chrome.installZenOverlay(sceneRoot);
    }

    @Override
    public java.util.List<OpenFile> listOpenFiles() {
        return mcpBridge.listOpenFiles();
    }

    @Override
    public BufferContent readBuffer(@Nullable String path) {
        return mcpBridge.readBuffer(path);
    }

    @Override
    public java.util.List<Diagnostic> getDiagnostics(@Nullable String path) {
        return mcpBridge.getDiagnostics(path);
    }

    @Override
    public java.util.List<SearchMatch> findInFiles(
            String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
        return mcpBridge.findInFiles(query, caseSensitive, regex, wholeWord);
    }

    @Override
    public java.util.List<CommandInfo> listCommands() {
        return mcpBridge.listCommands();
    }

    @Override
    public boolean executeCommand(String id) {
        return mcpBridge.executeCommand(id);
    }

    @Override
    public String openFile(String path, int line, int col) {
        return mcpBridge.openFile(path, line, col);
    }

    @Override
    public String editBuffer(@Nullable String path, String oldText, String newText, boolean replaceAll) {
        return mcpBridge.editBuffer(path, oldText, newText, replaceAll);
    }

    @Override
    public String replaceBuffer(@Nullable String path, String newText) {
        return mcpBridge.replaceBuffer(path, newText);
    }

    @Override
    public @Nullable String saveBuffer(@Nullable String path) {
        return mcpBridge.saveBuffer(path);
    }

    @Override
    public Selection getSelection() {
        return mcpBridge.getSelection();
    }

    @Override
    public java.util.List<Symbol> documentSymbols(@Nullable String path) {
        return mcpBridge.documentSymbols(path);
    }

    @Override
    public GitState gitStatus() {
        return mcpBridge.gitStatus();
    }

    @Override
    public java.util.List<TabInfo> listTabs() {
        return mcpBridge.listTabs();
    }

    @Override
    public java.util.List<TodoItem> todoScan() {
        return mcpBridge.todoScan();
    }

    public void openInitialBuffer() {
        sessions.openInitialBuffer();
    }

    public void openExternalFiles(java.util.List<OpenTarget> files) {
        sessions.openExternalFiles(files);
    }

    public void startup(Path projectDir, List<OpenTarget> targets, String newFile) {
        startup(projectDir, targets, newFile, false);
    }

    public void startup(
            @Nullable Path projectDir, List<OpenTarget> targets, @Nullable String newFile, boolean noSession) {
        sessions.startup(projectDir, targets, newFile, noSession);
        recovery.offerLeftovers(); // unsaved edits a previous run did not get to close with (first window only)
    }

    public void startupDiffUi(Path left, Path right) {
        sessions.startupDiffUi(left, right);
    }

    @Initializer
    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
        // Wrap the FXML-injected tab strip before anything reads tabs; every later access goes through this.
        this.editorArea = new EditorArea(tabPane);
        this.closes = new CloseCoordinator(
                stage, editorArea, pinned, MainController::bufferOf, fileWorkflows, this::persistSessionForClose);
        stage.setOnCloseRequest(e -> {
            // Busy terminals first (closing ends them), then this window's dirty buffers + its session; cancel the
            // close if the user backs out. (No separate "Quit?" prompt — each window closes independently now.)
            if (!terminalCoordinator.confirmCloseWithBusyTerminals() || !closes.confirmCloseAll()) {
                e.consume();
                return;
            }
            // Defensive: a disposal hiccup must never block the close (or it would look like the window
            // can't be closed). Let JavaFX close this stage normally afterwards; the app keeps running
            // while other windows remain (implicitExit quits only when the last window closes).
            try {
                disposeWindow();
            } catch (RuntimeException | Error t) {
                java.util.logging.Logger.getLogger(MainController.class.getName())
                        .log(java.util.logging.Level.WARNING, "disposeWindow failed on close", t);
            }
            if (windowManager != null) {
                windowManager.onWindowClosed(this);
            }
        });
        // Detect files changed by another program: re-check open files whenever the window regains focus.
        stage.focusedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                onWindowFocusGained();
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        // Right-click menus show each command's chord beside it, read from the live keymap as the menu bar is.
        com.editora.editor.MenuChords.setChords(keymap::displayChords);
        this.macroCoordinator = new MacroCoordinator(config, registry, coordinatorHost, new MacroCoordinator.Ops() {
            @Override
            public void refreshAllWindows() {
                refreshSavedMacroCommandsAllWindows();
            }

            @Override
            public void setRecordingIndicator(boolean recording) {
                statusBar.setMacroRecording(recording);
            }

            @Override
            public @Nullable Integer prefixArgument() {
                return editing.currentPrefixArg;
            }

            @Override
            public void resetShortcut(String commandId) {
                editorSettings.resetShortcut(commandId);
            }
        });
        // Built here (not as a field initializer) because NotesPanel's constructor reads config.getNotes().
        this.notesCoordinator = new NotesCoordinator(coordinatorHost, new NotesOpsHost(this));
        // Built here (not as a field initializer) because BookmarksPanel's constructor reads config.getBookmarks().
        this.bookmarkCoordinator = new BookmarkCoordinator(coordinatorHost, new BookmarkOpsHost(this));
        // Record every executed command into an in-progress macro (the service no-ops unless recording).
        registry.setStartListener(macroCoordinator::onCommandStart);
        registry.setExecutionListener(macroCoordinator::onCommand);
        registry.setBoundaryHook(editing::undoBoundary); // a command's edit is its own undo step
        this.snippetCoordinator = new SnippetCoordinator(config, coordinatorHost, new SnippetOpsHost(this));
        this.snippets = snippetCoordinator.manager();
        templateActions.templates = new com.editora.template.TemplateRegistry(config);
        this.completion = new com.editora.completion.CompletionEngine(snippets, config::getUserDictionary);
        // Project commands (incl. the Project tool window) are hidden from the palette unless project support is
        // enabled. Project + Git commands are hidden from the palette unless their feature is enabled. A command whose
        // feature is off, or which has nothing to act on right now, is listed but grayed out and inert (see
        // Chrome.paletteEnabled, which is pure + unit-tested). The gate/context snapshot is taken once per palette
        // refresh — not once per command — because the predicate runs over the whole registry (several hundred
        // commands) on every keystroke in the query field, and the context half reads the active buffer (whose
        // hasPreview() sniffs buffer text).
        this.palette = new CommandPalette(registry, keymap, () -> {
            Chrome.PaletteGates gates = paletteGates();
            Chrome.PaletteContext context = paletteContext();
            return c -> Chrome.paletteEnabled(c.id(), gates, context) && jumpPossible(c.id());
        });
        // Tooltip on a grayed row explaining why, and naming the command that would re-enable it.
        this.palette.setDisabledReason(c -> disabledCommandReason(c.id()));
        this.findBar = new FindReplaceBar(this::activeBuffer, this::setStatus);
        this.findBar.setOnSelectAllMatches(this::selectAllFindMatches);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        statusBar.setOnOpenDoctor(this::showDoctor); // the message log's "Open Doctor" (review C4)
        statusBar.setOnHttpEnvironmentClicked(() -> httpClient.showEnvironmentMenu(
                activeBuffer(), statusBar.httpEnvSegmentNode(), javafx.geometry.Side.TOP));
        this.breadcrumb = new FileBreadcrumb(fileWorkflows::openPath, this::windowProjectRoot);
        // The breadcrumb is NOT part of the bottom bar stack — see setupToolWindows, which hangs it under
        // the editor area itself.
        bottomBox.getChildren().setAll(statusBar);
        setupToolWindows();
        this.settingsWindow = new SettingsWindow(
                config,
                toolWindows,
                git.service(),
                github.service(),
                mermaid.service(),
                diagram.service(),
                typst.service(),
                buildCoordinators,
                lspManager,
                dapManager,
                this::onSettingsApplied,
                this::setZenMode,
                this::setExpertMode,
                fileWorkflows::openPath,
                this::exportConfig,
                this::showDebugLog);
        this.settingsWindow.setPluginManager(pluginManager); // shared; lists discovered plugins on the Plugins page
        this.settingsWindow.setStatusSink(this::setStatus);
        this.settingsWindow.setMarkdownLintConfigNote(() -> previews.markdownLintConfigNote());
        this.settingsWindow.setHistoryLimits((perFile, ageDays, totalMb, owner, done) ->
                historyCoordinator.changeLimits(perFile, ageDays, totalMb, owner, done));
        this.pluginCoordinator = new PluginCoordinator(
                coordinatorHost,
                registry,
                keymap,
                snippets,
                templateActions.templates,
                toolWindows,
                statusBar,
                settingsWindow,
                config,
                pluginManager,
                pluginOps());
        SettingsWindowWiring.wire(
                this, settingsWindow, templateActions.templates); // its pages' actions, probes and suggestions
        debugLogWindow.setSessionFile(DebugLog.sessionFile(config.getConfigDir()));
        debugLogWindow.setOnReport(() -> registry.run("help.reportProblem"));
        this.switcher = new Switcher(
                () -> new java.util.ArrayList<>(editorArea.tabs()), // list files in tab order
                () -> editorArea.selectedTab(),
                this::activateAndFocusTab,
                this::closeTabFromSwitcher);
        backgroundTasks.addListener(() -> {
            BackgroundTasks.Task t = backgroundTasks.current();
            statusBar.setBackgroundTasks(t == null ? null : t.label(), backgroundTasks.count());
        });
        exports.exported = fileWorkflows::reloadViewerTab; // an export over an open PDF refreshes its tab
        tabListeners.install();
        // The listeners' last step, kept here: NullAway reads this check as part of init's set-up of projectPanel.
        if (projectPanel != null) {
            EditorBuffer selected = activeBuffer();
            projectPanel.setActiveFile(selected == null ? null : selected.getPath());
        }
        windowCommands.registerCommands();
        recovery.registerCommands(registry);
        setupToolbar();
        runConfigurations.refreshRunConfigs(); // populate the selector + register run.config.<slug> for the saved set
        setupRecentFiles();
        navigation.setupJumpPickers();
        setupProjects();
        pluginCoordinator
                .applyPlugins(); // register plugin commands/tool windows/hooks (before restore, so visibility restores)
        palette.refreshBindings(); // built before the plugins bound their chords: show theirs, drop the replaced
        snippetCoordinator.checkUserFilesAtStartup(); // a broken user snippet file is reported now, not on first use
        toolWindows.restore();
        if (projectsEnabled()
                && WorkspaceState.PROJECT_VIEW_MAP.equals(
                        config.getWorkspaceState().getProjectViewMode())) {
            projectPanel(); // a Map window open last time reopens with the Project tool window closed too
        }
        // Honor a persisted Zen/Expert state on launch: the view options + chrome already read the flags via
        // the apply paths; this hides the side stripes (restore() opened nothing — windows were
        // persisted closed when a focus mode was entered).
        toolWindows.setZenStripesHidden(config.getWorkspaceState().isZenMode()
                || config.getWorkspaceState().isExpertMode());
        chrome.applyChromeVisibility();
        applyProjectSupport(); // hide project UI when disabled (default)
        git.applySupport(); // hide Git UI when disabled (default)
        github.applySupport(); // detect gh + gate the GitHub PR/issue surfaces (on by default, inert until gh is found)
        refreshBuildTools(); // initial marker detection; each toolbar button stays hidden until one is found
        historyCoordinator.applySupport(); // Local File History tool window availability + list (on by default)
        notesCoordinator.applySupport(); // hide Personal Notes UI when disabled (default)
        mermaid.applySupport(); // wire mmdc/maid paths; mermaid rendering off when disabled (default)
        diagram.applySupport(); // wire dot/plantuml paths; DOT/PlantUML preview off when disabled
        typst.applySupport(); // wire typst path; Typst document preview off when disabled
        searchCoordinator
                .applyRipgrepSupport(); // detect rg + pick the Find-in-Files backend (rg when available, else walker)
        applyMathSupport(); // LaTeX math rendering (off by default)
        httpClient.applySupport(); // .http run glyphs + response preview (off when disabled or in Simple UI mode)
        htmlPreview.applySupport(); // HTML "open in browser" control off when disabled (on by default)
        logViewer.applySupport(); // log-viewer control + level overlay (default on for .log files)
        applyMcpSupport(); // MCP server (loopback HTTP) off when disabled (default)
        applyAgentSupport(); // AI Agent chat window off when disabled (default)
        aiCoordinator.applySupport(); // floating selection Explain/Rewrite bar off when disabled (default)
        lspCoordinator.applySupport(); // configure the LSP manager; servers/diagnostics off when disabled (default)
        debugCoordinator
                .applySupport(); // configure DAP; debugging off when disabled (default) — after LSP (it layers on
        // jdtls)
        todoCoordinator.applyHighlight(); // compile TODO/FIXME patterns + highlight (on by default)
        previews.applyMarkdownLint(); // push Markdown-lint enabled state to buffers (on by default)
        fileWorkflows.applyAdminSaveSupport(); // detect the elevation tool (pkexec/osascript) for save-as-admin (off by
        // default)
        setupWelcome(); // Welcome empty-state shown when no file tabs are open

        // Auto save: idle timer fires a save; the window losing focus saves in onFocusChange mode.
        fileWorkflows.autoSaveIdleTimer.setOnFinished(e -> fileWorkflows.autoSaveAllDirty());
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && FileWorkflowCoordinator.AUTOSAVE_FOCUS.equals(fileWorkflows.autoSaveMode())) {
                fileWorkflows.autoSaveAllDirty();
            }
        });
        fileWorkflows.applyAutoSave();
        updates.applyAtStartup(); // the notice of a release an earlier window found + the automatic check
    }

    final StartupFocus startupFocus = new StartupFocus(); // the show-time focus, told from a real regain

    /** The window gained focus: re-check what another program may have changed while we were away — unless
     *  this is the focus gained by being shown ({@link StartupFocus}), which repeats nothing. */
    void onWindowFocusGained() {
        StartupFocus.Action action = startupFocus.onFocusGained();
        if (action == StartupFocus.Action.STARTUP_REFRESH) {
            git.refresh(); // the project root is known only since setWindowContext
            refreshBuildTools();
        }
        if (action != StartupFocus.Action.REGAIN) {
            return;
        }
        // The filesystem may have shifted while we were away — drop cached canonical paths (#680).
        com.editora.config.PathKeys.invalidateCanonicalCache();
        fileWorkflows.checkExternalChanges();
        git.refresh(); // another tool may have changed the repo while we were away
        refreshBuildTools(); // a marker file (or the active file's project) may have changed while away
        toolbar.refreshPasteState(); // clipboard may have changed in another app while we were away
        if (projectPanel != null) {
            projectPanel.refreshTreeOnRegain(); // pick up files/folders added or removed outside Editora
        }
        if (containerCoordinator != null) {
            containerCoordinator.windowFocused(); // a dev container may have been started or stopped meanwhile
        }
        diffCoordinator.refreshOpenDiffs(); // a compared file may have changed on disk while we were away
        httpClient.refreshAllEnvironments(); // an env file edited elsewhere: the env pills and {{variable}} marks
    }

    // --- plugins ----------------------------------------------------------------------------------

    /** Injected by {@link WindowManager} before {@link #init}; shared across windows (classes load once). */
    void setPluginManager(com.editora.plugin.PluginManager pm) {
        this.pluginManager = pm; // the per-window PluginRegistry/PluginInstaller are built in PluginCoordinator
    }

    /** Whether plugins may be browsed and installed — the master gate (also off in Simple UI mode). */
    private boolean pluginsEnabled() {
        return pluginCoordinator != null && pluginCoordinator.isEnabled();
    }

    /** Stops this window's plugins (window close); WindowManager calls this by name. */
    void disposePlugins() {
        if (pluginCoordinator != null) {
            pluginCoordinator.disposePlugins();
        }
    }

    /** Window hooks for {@link PluginCoordinator} (open-path + the plugins' error dialog). */
    private PluginCoordinator.Ops pluginOps() {
        return new PluginCoordinator.Ops() {
            @Override
            public void openPath(java.nio.file.Path file) {
                fileWorkflows.openPath(file);
            }

            @Override
            public void showError(String summary, String detail) {
                // Owned by Settings while the user works there; titled "Plugins", not "Git".
                javafx.stage.Window settings = settingsWindow.stageIfShowing();
                PluginDialogs.error(settings != null ? settings : stage, summary, detail);
            }
        };
    }

    /** Snapshot of which optional features are effectively enabled, for {@link Chrome#paletteVisible}. */
    Chrome.PaletteGates paletteGates() {
        Settings s = config.getSettings();
        return new Chrome.PaletteGates(
                s.isProjectSupport(),
                git.isEnabled(),
                github.isEnabled(),
                s.isNotesSupport(),
                s.isMermaidSupport(),
                diagram.isEnabled(),
                typst.isEnabled(),
                disabledBuildToolIds(),
                lspEnabled(),
                httpClient.isEnabled(),
                htmlPreview.isEnabled(),
                localHistoryEnabled(),
                mcpEnabled(),
                pluginsEnabled(),
                externalToolsEnabled(),
                logViewer.isEnabled(),
                s.isTestRunner() && !chrome.simpleModeActive(),
                // debugCoordinator is assigned during init(); the palette can't be shown before then, but
                // the predicate is lazy so guard rather than assume the ordering.
                debugCoordinator != null && debugCoordinator.debugSupportEnabled(),
                aiCoordinator.isEnabled(),
                agentCoordinator.isEnabled(),
                s.isTodoHighlight(),
                s.isSpellCheck(),
                s.isCsvPreview(),
                s.isStructuredPreview(),
                s.isPomPreview(),
                indexCoordinator.isEnabled(),
                previews.markdownLintEnabled(),
                editorSettings.editorConfigEnabled(),
                chrome.simpleModeActive());
    }

    /**
     * The localized explanation for a grayed-out palette row — why the command can't run, and (for a
     * switched-off feature) the localized title of the command that would switch it back on. Null when the
     * command is actionable or there is nothing useful to say.
     *
     * <p>Only called for rows that already failed the enabled predicate, i.e. at most once per <em>visible</em>
     * cell, so re-deriving the gate/context snapshot here costs nothing measurable — unlike the predicate
     * itself, which runs over the whole registry.
     */
    @Nullable
    String disabledCommandReason(String commandId) {
        Chrome.DisabledReason reason = Chrome.disabledReason(commandId, paletteGates(), paletteContext());
        if (reason == null) {
            return jumpPossible(commandId) ? null : tr("status.debug.jumpUnsupported");
        }
        if (reason.commandArg() == null) {
            return tr(reason.messageKey());
        }
        // The argument is a command id; show its localized title so the tooltip names what the user would
        // actually type into this very palette.
        return tr(reason.messageKey(), tr("command." + reason.commandArg()));
    }

    /**
     * Re-evaluates the main menu's enabled/disabled state for the context as it stands now.
     *
     * <p>Cheap (a pure lookup per item, no relabel, no re-measure) and pushed from the places the context
     * can move: a tab switch, Git reporting whether we are in a repo, and a debug session changing state.
     * The menu also recomputes on open, which is exact — but on the macOS system menu bar AppKit owns the
     * popup and that hook may not fire, so these pushes are what keep it honest there.
     */
    void refreshMenuEnablement() {
        if (menuBar != null) {
            menuBar.refreshEnablement();
        }
    }

    /**
     * The debug manager of the session the window shows: the focused one of {@link DebugCoordinator#sessions()}, or
     * this window's first manager before the coordinator is made.
     */
    com.editora.dap.DapManager focusedDap() {
        return debugCoordinator == null ? dapManager : debugCoordinator.dap();
    }

    private void shutdownDebugSessions() {
        if (debugCoordinator == null) {
            dapManager.shutdown();
        } else {
            debugCoordinator.sessions().shutdown();
        }
    }

    /** Jump to Line is greyed while paused in a debugger that cannot do it (java-debug has no goto). */
    private boolean jumpPossible(String commandId) {
        return !"debug.jumpToLine".equals(commandId)
                || focusedDap().state() != com.editora.dap.DapManager.State.SUSPENDED
                || focusedDap().supportsJumpToLine();
    }

    Chrome.PaletteContext paletteContext() {
        EditorBuffer b = activeBuffer();
        // Any session of the window, starting or being built for included.
        boolean debugActive = debugCoordinator != null && debugCoordinator.anySessionLive();
        return new Chrome.PaletteContext(
                b != null,
                git.repoRoot() != null,
                b != null && (b.isMarkdown() || b.isTypst()),
                b != null && b.isCsv(),
                b != null && b.isHttpFile(),
                b != null && b.isTypst(),
                b != null && b.hasPreview(),
                debugActive,
                focusedDap().state() == com.editora.dap.DapManager.State.SUSPENDED,
                debugCoordinator == null || debugCoordinator.restartAvailable(),
                git.operationInProgress(),
                b != null && b.hasExportablePreview() && !b.previewShowsError(),
                b != null
                        || (imagePaneOf(activeTab()) instanceof ImageViewerPane image
                                && image.printableImage() != null),
                b != null
                        && b.getFocusedArea() != null
                        && b.getFocusedArea().getSelection().getLength() > 0,
                projectPanel != null && projectPanel.isMapOutputAvailable(),
                terminalCoordinator != null && terminalCoordinator.hasTerminals(),
                debugCoordinator != null && debugCoordinator.programRunning());
    }

    private void setupRecentFiles() {
        recentFiles = config.shared().recentFiles();
        agentCoordinator.protectDirectory(config.getConfigDir()); // the agent never writes the editor's own config
        searchCoordinator.refreshHistory(); // fill the query combo's dropdown from the shared history
        recentButton.setGraphic(Icons.recent());
        recentButton.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        Icons.name(recentButton, tr("tooltip.recent"));
        recentButton.setFocusTraversable(false); // a toolbar button: the mouse and the Recent Files command reach it

        rebuildRecentMenu(); // later changes arrive through sharedHistoryChanged()

        toolbar.setupButton(clearRecentButton, Icons.trash(), "file.clearRecent");
    }

    /**
     * Builds the {@link WelcomePane} and wires its callbacks. The pane is shown in its own real tab
     * (see {@link #addWelcomeTab()}); the tab strip handles activation/switching/closing for free, so
     * there is no overlay or visibility juggling.
     */
    private void setupWelcome() {
        welcomePane = new WelcomePane(
                registry,
                keymap,
                recentFiles,
                () -> config.projects().list(),
                this::openRecent,
                this::openExternalUrl,
                this::projectsEnabled,
                git::isEnabled,
                config::getConnections, // saved SFTP sites (most-recent first); empty hides the section
                remoteCoordinator::connect, // pick a site → prefilled connect form
                config.isDev() ? com.editora.AppInfo.gitCommitAsync() : null); // commit: --dev only, off-thread
        welcomePane.setProjectOpener(this::switchToProject);
        welcomePane.setRecentRemover(path -> Platform.runLater(() -> {
            confirmRemoveRecent(path);
            welcomePane.refresh();
        }));
        welcomePane.setCustomizeSource(
                () -> com.editora.command.KeymapManager.displayName(
                        config.getSettings().getKeymap()),
                () -> config.getSettings().getTheme());
    }

    /**
     * Opens (or re-selects) the single Welcome tab, refreshing it first so its recents + Open-Folder /
     * Clone actions track the current state. Used at startup with no session and by {@code view.welcome}.
     */
    private void addWelcomeTab() {
        welcomePane.refresh();
        welcomePane.setFontScale(config.getSettings().getFontZoom()); // match the current text zoom (#540)
        if (welcomeTab != null && editorArea.contains(welcomeTab)) {
            TabFocus.select(editorArea, welcomeTab); // its page, not the toolbar's New button (thirteenth review AK1)
            return;
        }
        Tab tab = addContentTab(welcomePane, true);
        tab.getStyleClass().add("ui-surface-tab"); // the page is on the UI surface, so is its selected tab
        welcomeTab = tab;
        // Welcome is not a document: only a Close action (no Save/Rename/Pin).
        MenuItem close = new MenuItem(tr("menu.close"));
        close.setGraphic(Icons.closeTab());
        close.setOnAction(e -> closeTab(tab));
        MenuChords.tag(close, "buffer.close"); // as on a file's tab: the shortcut closes the active tab
        tab.setContextMenu(new ContextMenu(close));
        tab.setOnClosed(e -> {
            if (welcomeTab == tab) {
                welcomeTab = null;
            }
        });
    }

    /**
     * Opens (or re-selects) the single Doctor tab and (re)runs its health checks — the {@code view.doctor}
     * command. Mirrors {@link #addWelcomeTab()}: one instance, Close-only context menu, field cleared on
     * close. Probes run off the FX thread; rows fill in as results land.
     */
    void showDoctor() {
        if (doctorTab != null && editorArea.contains(doctorTab)) {
            TabFocus.select(editorArea, doctorTab); // (thirteenth review AK1)
            doctorCoordinator.runChecks();
            return;
        }
        Tab tab = addContentTab(doctorCoordinator.pane(), true);
        tab.getStyleClass().add("ui-surface-tab");
        doctorTab = tab;
        MenuItem close = new MenuItem(tr("menu.close"));
        close.setGraphic(Icons.closeTab());
        close.setOnAction(e -> closeTab(tab));
        MenuChords.tag(close, "buffer.close"); // as on a file's tab: the shortcut closes the active tab
        tab.setContextMenu(new ContextMenu(close));
        tab.setOnClosed(e -> {
            if (doctorTab == tab) {
                doctorTab = null;
            }
        });
        doctorCoordinator.runChecks();
    }

    /**
     * Shows a terminal moved out of the Terminal tool window as an editor tab, selected, with the keyboard. Its menu
     * acts on the terminal: Rename, Move to Terminal Tool Window, Close. A close asks what the panel would ask.
     */
    void openTerminalEditorTab(TerminalEditorTab terminal) {
        Tab tab = addContentTab(terminal, true);
        terminal.attach(tab, () -> tabHeaders.contentHeader(tab, terminal));
        tab.setOnCloseRequest(e -> {
            e.consume(); // closed by the terminal panel once it may be
            terminalCoordinator.requestClose(terminal);
        });
        MenuItem rename = new MenuItem(tr("terminal.tab.rename"), Icons.edit());
        rename.setOnAction(e -> terminalCoordinator.rename(terminal));
        MenuChords.tag(rename, "terminal.rename");
        MenuItem back = new MenuItem(tr("terminal.tab.moveToToolWindow"), Icons.dock());
        back.setOnAction(e -> terminalCoordinator.moveToToolWindow(terminal));
        MenuChords.tag(back, "terminal.moveToToolWindow");
        MenuItem close = new MenuItem(tr("menu.close"), Icons.closeTab());
        close.setOnAction(e -> terminalCoordinator.requestClose(terminal));
        MenuChords.tag(close, "buffer.close"); // closeTab on a terminal tab is this same requestClose
        tab.setContextMenu(new ContextMenu(rename, back, new javafx.scene.control.SeparatorMenuItem(), close));
    }

    /** Sets the {@link javafx.application.HostServices} used to open external links (from {@code App}). */
    public void setHostServices(javafx.application.HostServices hostServices) {
        this.hostServices = hostServices;
        exports.systemOpener = hostServices == null
                ? null
                : f -> hostServices.showDocument(f.toUri().toString());
    }

    /**
     * Binds this window to the multi-window coordinator and records which project it edits ({@code null}
     * = the no-project/global window). Called by {@link WindowManager} right after {@link #init} and
     * before {@link #startup}, so the project panel root, title, and combo reflect <em>this</em> window's
     * project (not the globally last-focused one).
     */
    public void setWindowContext(WindowManager windowManager, @Nullable Project project) {
        indexCoordinator.onProjectChanged(); // the previous project's symbols mean nothing here
        this.windowManager = windowManager;
        settingsWindow.setSettingsSync(windowManager == null ? null : windowManager.settingsSync());
        this.windowProject = project;
        this.projectKey = project == null ? "" : project.id();
        recovery.attach(windowManager == null ? null : windowManager.recovery());
        setProjectRoot(project == null ? null : project.rootPath());
        if (containerCoordinator != null) {
            containerCoordinator.projectChanged(true); // its dev container, or the container named for it
        }
        updateProjectFolderView(); // global ("No Project") window: show the active file's folder instead
        searchCoordinator
                .refreshScope(); // Find-in-Files scope label reflects this window's project (or current folder)
        refreshProjectPanelList();
        updateWindowTitle();
        // When a project window is freshly opened, show the Projects tool window so the file tree is
        // right there (called only on a new window build, never when focusing an already-open project).
        if (project != null && projectsEnabled() && projectToolWindow != null) {
            toolWindows.open(projectToolWindow, false);
        }
        if (project != null && project.remote()) {
            // After whoever opened this window has had the chance to hand it credentials of its own.
            Platform.runLater(() -> remoteCoordinator.promptReconnectIfNeeded());
        }
        // This window now knows its project, which decides whether it has run configurations at all (the
        // selector, its per-configuration commands) and half of what gates the toolbar group; the other half
        // (marker detection) re-runs on the tab-selection and focus-regain paths.
        runConfigurations.refreshRunConfigs();
    }

    /**
     * In the global ("No Project") window, points the Project tool window at the active file's parent
     * folder and titles it "Current Folder" — so it doubles as a file explorer for the file being edited,
     * tracking tab switches. No-op when a project is open (the tree stays the project root and the title
     * "Project") or while a remote folder is mounted (that root is set explicitly). With no local file
     * active it shows the placeholder and the default title.
     */
    void updateProjectFolderView() {
        if (windowProject != null
                || !projectsEnabled()
                || (remoteCoordinator != null && remoteCoordinator.isMounted())) {
            return; // a project / remote mount owns the tree root + title — leave them alone
        }
        EditorBuffer b = activeBuffer();
        Path path = b == null ? null : b.getPath();
        Path dir = (path != null && com.editora.vfs.Vfs.isLocal(path))
                ? path.toAbsolutePath().getParent()
                : null;
        if (!com.editora.config.PathKeys.samePath(projectRoot(), dir)) {
            setProjectRoot(dir); // null => placeholder (unsaved/Welcome tab); rebuilds only on change
        }
        if (projectToolWindow != null) {
            projectToolWindow.setTitle(tr(dir != null ? "toolwindow.currentFolder" : "toolwindow.project"));
        }
    }

    /**
     * One lazily built tool window: its panel is made by {@code content} on the window's first open. The
     * title is the {@code toolwindow.<id>} message and the toggle command {@code tool.<id>}; the overload
     * below is for the two whose keys do not follow the id.
     */
    private static ToolWindow toolWindow(
            String id,
            ToolWindow.Side side,
            java.util.function.Supplier<javafx.scene.Node> icon,
            java.util.function.Supplier<? extends Region> content) {
        return toolWindow(id, "toolwindow." + id, side, icon, content, "tool." + id);
    }

    private static ToolWindow toolWindow(
            String id,
            String titleKey,
            ToolWindow.Side side,
            java.util.function.Supplier<javafx.scene.Node> icon,
            java.util.function.Supplier<? extends Region> content,
            String commandId) {
        return new ToolWindow(id, tr(titleKey), side, icon, content, commandId);
    }

    /** The Structure panel, built on its first use; it attaches the active buffer as it is built. */
    StructurePanel structurePanel() {
        if (structurePanel == null) {
            structurePanel = new StructurePanel(bookmarkCoordinator, notesCoordinator);
            structurePanel.setNarrowActions(editing.structureNarrowActions());
            structurePanel.setSimpleMode(chrome::simpleModeActive);
            structurePanel.setChordLookup(id -> keymap == null ? null : keymap.displayChord(id));
            EditorBuffer active = activeBuffer();
            structurePanel.attach(active);
            lspCoordinator.requestStructureSymbols(active); // the LSP outline, when the server offers one
        }
        return structurePanel;
    }

    /** The File Information panel, built on its first use; it attaches the active buffer as it is built. */
    FileInformationPanel fileInfoPanel() {
        if (fileInfoPanel == null) {
            fileInfoPanel = new FileInformationPanel();
            fileInfoPanel.attach(activeBuffer());
        }
        return fileInfoPanel;
    }

    /** The Undo History panel, built on its first use; it attaches the active buffer as it is built. */
    UndoHistoryPanel undoHistoryPanel() {
        if (undoHistoryPanel == null) {
            undoHistoryPanel = new UndoHistoryPanel();
            undoHistoryPanel.attach(activeBuffer());
        }
        return undoHistoryPanel;
    }

    /** The Commit panel, built on its first use with the Git state the window has seen so far. */
    GitPanel gitPanel() {
        if (gitPanel == null) {
            GitPanel panel = new GitPanel(new GitPanelActions(git, diffCoordinator, fileWorkflows));
            panel.setOnClone(git::cloneRepo);
            panel.setOnGenerateCommitMessage(aiCoordinator::generateCommitMessage);
            if (lastGitStatus != null) {
                panel.setStatus(lastGitStatus);
            }
            if (lastGitOperation != null) {
                panel.setOperation(lastGitOperation);
            }
            if (lastGitRefusal != null) {
                panel.setRefusal(lastGitRefusal);
            }
            panel.setAiAvailable(gitAiAvailable);
            gitPanel = panel;
        }
        return gitPanel;
    }

    /** The Git Log panel, built on its first use (the window's open loads the history into it). */
    GitLogPanel gitLogPanel() {
        if (gitLogPanel == null) {
            gitLogPanel = new GitLogPanel(gitLogOps);
        }
        return gitLogPanel;
    }

    /** The GitHub panel, built on its first use (the window's open fetches its list). */
    GitHubPanel githubPanel() {
        if (githubPanel == null) {
            githubPanel = new GitHubPanel(gitWindows.githubActions());
        }
        return githubPanel;
    }

    /** The Markdown Lint panel, built on its first use (the window's open runs the scan that fills it). */
    MarkdownLintPanel markdownLintPanel() {
        if (markdownLintPanel == null) {
            markdownLintPanel = new MarkdownLintPanel(
                    previews.lint(),
                    MarkdownLintPanel.actions(
                            (file, line, col) -> openAndGoto(file, line - 1, col - 1), previews::runMarkdownLintScan));
        }
        return markdownLintPanel;
    }

    /**
     * The Project panel, built on its first use — the tool window's first open, or a reveal into it. Until
     * then the state it would show is tracked here (the root, the Git status, the active file, the settings)
     * and applied when it is built; refreshes that reach a panel not built yet are dropped, since the build
     * reads everything afresh.
     */
    ProjectPanel projectPanel() {
        if (projectPanel != null) {
            return projectPanel;
        }
        ProjectPanel panel = new ProjectPanel(
                fileWorkflows::openPath,
                this::onProjectFileRenamed,
                this::onProjectTreeFileDeleted,
                this::isPathModified,
                this::hasFileOpen,
                this::projectMapPreviewContent);
        panel.setEditorWindow(() -> stage); // the Map window opens even while the Project tool window is closed
        panel.setMapSceneSetup(toolWindows::setUpSecondaryScene);
        panel.setRememberedMapState(config::getWorkspaceState, config::save, this::requestSave);
        panel.setOpenFiles(() -> editorArea.tabs().stream()
                .map(MainController::tabPath)
                .filter(java.util.Objects::nonNull)
                .toList());
        panel.setPrompt(this::promptText); // in-scene rename prompt
        panel.setPromptIn(host -> (title, label, initial, onAccept) ->
                promptTextIn(host, title, label, initial, onAccept)); // the same, over the Map window
        panel.setOnFocusEditor(toolWindows::focusEditor); // Escape on the Project Map
        panel.setDeletePreparation(projectDeletes);
        panel.setOnNewFile(templateActions::newFileOfType); // folder "New ▸ <type>"
        panel.setOnNewFromTemplate(templateActions::newFromTemplate); // folder "New From Template…"
        panel.setRenameParticipant(lspCoordinator.treeParticipant()); // a class goes with its file (B15)
        lspCoordinator.setOnTreeMoveUndone(panel::forgetLastMove);
        panel.setMavenMenu(mavenProjectCoordinator::mavenMenu);
        panel.setOnNewMavenProject(mavenProjectCoordinator::newProject); // folder "New Maven Project…"
        panel.setOnStatus(this::setStatus); // drag-move / multi-delete feedback in the status bar
        panel.setMapOutputActions(exports::printProjectMap, exports::exportProjectMapPdf);
        panel.setMapRootPinned( // the No Project window: its tree follows the active tab, an open Map does not
                () -> windowProject == null && (remoteCoordinator == null || !remoteCoordinator.isMounted()));
        // An external program (a terminal `git`, another editor, a build) changed files under the repo while
        // Editora already had focus: re-evaluate the working-tree-anchored surfaces the focus-regain handler
        // also refreshes — Git status + the Commit stripe, build-tool markers, and open diffs (#529).
        panel.setOnExternalChanges((changes, complete) -> {
            com.editora.config.PathKeys.invalidateCanonicalCache(); // files moved on disk under us (#680)
            indexCoordinator.onExternalChanges(changes, complete);
            previews.lint().onExternalChanges(changes); // a changed .markdownlint* re-lints open buffers
            git.refresh();
            refreshBuildTools();
            diffCoordinator.refreshOpenDiffs();
            fileWorkflows.checkRemoteExternalChanges(changes); // reported by a remote folder's host
        });
        panel.setFsChangeSink(lspCoordinator::watchedFilesChanged); // didChangeWatchedFiles: no stale models (#677)
        panel.setMapGit(git.service()); // the Map's recent changes, on the service's own activity lane
        panel.setOnReveal((p, dir) -> revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        panel.setOnOpenTerminal((p, dir) -> openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        panel.setFileActions(ProjectTreeFileActions.of(this, this::localHistoryEnabled));
        panel.setMarkerActions(new ProjectTreeMarkerActions(bookmarkCoordinator, notesCoordinator));
        panel.setCardGutterSource(MapCardGutterMarks.of(this)); // breakpoints, bookmarks, ▶ and bars on Map cards
        if (debugCoordinator != null) { // the Map follows the debugger's stops, its call stack picks frames
            panel.setOnDebugFramePicked(debugCoordinator::selectFrameFromMap);
            debugCoordinator.setOnMapStop(panel::setDebugStop);
        }
        panel.setMapOpenActions(this::openAndNavigate, this::openNarrowedFromMap);
        panel.setMapServerSymbols(file -> structurePanel == null ? null : structurePanel.serverSymbolsFor(file));
        panel.shareIndexes(indexCoordinator, lspManager); // "@name" in the Map; Search Everywhere's files
        lspCoordinator.setOnProblemsShown(panel::setMapProblems); // the Problems window's errors and warnings
        panel.setMapCardAids(
                () -> { // the Map's code cards read as the editor does: its settings
                    Settings s = config.getSettings();
                    return new MapCardAids(
                            s.isSemanticHighlight(), s.isInlayHints(), s.getInlayHintMode(), s.isStickyScroll());
                });
        panel.setSimpleMode(chrome::simpleModeActive);
        panel.setShowHidden(config.getSettings().isProjectShowHidden());
        panel.setRespectGitignore(config.getSettings().isSearchRespectGitignore());
        if (projectGitStatus != null) {
            panel.setGitStatus(projectGitStatus);
        }
        panel.setRoot(projectRoot);
        EditorBuffer selected = activeBuffer();
        panel.setActiveFile(selected == null ? null : selected.getPath());
        projectPanel = panel;
        remoteCoordinator.panelBuilt(panel);
        return panel;
    }

    /** What the Project tree is rooted at: the built panel's own root, else the one recorded for its build. */
    @Nullable
    Path projectRoot() {
        return projectPanel != null ? projectPanel.getRoot() : projectRoot;
    }

    /** Points the Project tree at {@code root} ({@code null}: the placeholder), now or when the panel is built. */
    void setProjectRoot(@Nullable Path root) {
        projectRoot = root;
        if (projectPanel != null) {
            projectPanel.setRoot(root);
        }
    }

    /** Re-lists the Project tree if it exists; an unbuilt one lists afresh when built. */
    void refreshProjectTreeIfBuilt() {
        if (projectPanel != null) {
            projectPanel.refreshTree();
        }
    }

    private void refreshProjectMarkersIfBuilt() {
        if (projectPanel != null) {
            projectPanel.refreshMarkers();
        }
    }

    /** Re-applies preferences + the editor theme to this window after a Settings change in any window. */
    public void reapplyAfterSharedSettingsChange(Settings settings) {
        settingsReapply.run(settings); // only what the changed settings reach (SettingsReapply)
    }

    private final SettingsReapply settingsReapply = new SettingsReapply(this);

    /** The remote-execution setting, or this window's host's agreement or tools, may have been edited. */
    public void remoteExecSettingsMayHaveChanged() {
        if (remoteCoordinator != null) {
            remoteCoordinator.recheckExecution();
        }
    }

    /**
     * A Settings change was applied in this window. Re-apply it to every open window (the {@link Settings}
     * object is shared by reference, so other windows already see the new values — they just need to
     * restyle). Falls back to this window only when multi-window isn't wired.
     */
    void onSettingsApplied(Settings settings) {
        if (windowManager != null) {
            windowManager.broadcastSettingsApplied(this); // re-applies to every window, including this one
            windowManager.broadcastExternalToolsChanged(); // re-sync externalTool.run.* after a Settings edit
        } else {
            editorSettings.applyViewSettingsToAllBuffers(settings);
            refreshBuildTools(); // as in reapplyAfterSharedSettingsChange: a re-enabled tool must re-detect
            refreshExternalToolCommands();
        }
    }

    /**
     * Closes this window's session programmatically (the "Close Project" command): prompts to save dirty
     * buffers, persists the session, and releases the window's resources. Returns {@code false} if the
     * user cancelled (the window stays open). The caller ({@link WindowManager}) closes the stage.
     */
    public boolean closeWindowProgrammatically() {
        if (!terminalCoordinator.confirmCloseWithBusyTerminals() || !closes.confirmCloseAll()) {
            return false;
        }
        disposeWindow();
        return true;
    }

    /** Releases this window's resources on close: language servers, debug session, and worker threads. */
    void disposeWindow() {
        sessionClosed = true; // no further session writes from this window (see requestSave)
        WindowDisposal.runAll(() -> recovery.dispose()); // stops copying; deletes nothing (see closedByUser)
        // Every step is isolated (WindowDisposal): one shutdown that throws used to skip all the later ones.
        WindowDisposal.runAll(() -> sessions.flushPendingMarks()); // while the buffers still hold their marks
        for (Tab tab : editorArea.tabs()) {
            WindowDisposal.runAll(() -> {
                EditorBuffer buffer = bufferOf(tab);
                if (buffer != null) {
                    fileWorkflows.invalidatePendingWrites(buffer);
                    buffer.dispose();
                } else {
                    disposeViewerTab(tab); // an image/hex/PDF tab holds a thread + file handle + GPU texture too
                }
            });
        }
        WindowDisposal.runAll(
                () -> lspManager.close(), // stop this window's language servers and refuse any late restart
                () -> shutdownDebugSessions(), // end the debug sessions and release their connect workers
                debugCoordinator == null ? null : () -> debugCoordinator.shutdown(), // its before-launch build
                () -> git.shutdown(),
                () -> github.shutdown(), // stop the gh worker thread
                () -> indexCoordinator.dispose(), // stop the symbol-index walker
                historyCoordinator == null ? null : () -> historyCoordinator.shutdown(),
                () -> searchCoordinator.shutdown(),
                () -> todoCoordinator.shutdown(),
                () -> previews.shutdownLint(),
                () -> mermaid.shutdown(),
                () -> diagram.shutdown(),
                () -> typst.shutdown(),
                () -> doctorCoordinator.shutdown(), // stop any in-flight Doctor probes
                () -> buildCoordinators.forEach(BuildCoordinator::shutdown),
                updates::shutdown, // stop the update-check worker
                () -> htmlPreview.shutdown(), // stop the HTML-preview HTTP server + worker
                () -> logViewer.shutdown(), // stop any log tail-follow poll thread
                () -> stopMcpIfOwner(), // stop the MCP server if this window owns it
                () -> agentCoordinator.shutdown(), // kill the ACP agent process tree
                () -> aiCoordinator.shutdown(), // cancel any in-flight AI generation
                () -> exports.shutdown(),
                () -> runCoordinator.shutdown(),
                () -> terminalCoordinator.shutdown(), // end every terminal's shell
                // The WindowManager-wide Settings Sync held this window's Settings listener — and through it the
                // whole closed window (about 21 MB, its plugins' panes and command closures too) — for good.
                () -> settingsWindow.setSettingsSync(null),
                () -> testRunCoordinator.shutdown(), // stop the report poller + elapsed timer
                installCoordinator == null ? null : () -> installCoordinator.shutdown(),
                () -> fileWorkflows.shutdown(),
                () -> diffCoordinator.shutdown(), // the diff-service worker thread
                () -> mavenProjectCoordinator.shutdown(), // archetype:generate process + catalog fetch thread
                () -> externalToolCoordinator.shutdown(), // the external-tool worker thread
                () -> httpClient.shutdown(), // the http-client worker thread
                () -> httpVariableHints.shutdown(), // the {{variable}} check's worker thread
                () -> remoteCoordinator.shutdown(), // SFTP sessions + the SSH client (and un-pin the Vfs hooks)
                () -> {
                    if (projectPanel != null) {
                        projectPanel.dispose(); // stop the project tree's filesystem watcher + its daemon thread
                    }
                });
    }

    /**
     * Releases a non-buffer viewer tab (image / hex / PDF).
     *
     * <p>These panes were only released through {@code Tab.setOnClosed}, and JavaFX fires that from exactly one
     * place — {@code TabPaneBehavior.closeTab()}, i.e. a click on the tab's ✕. Every one of the app's own close
     * paths ({@code closeTab}, Close All / Others / Left / Right, and window close) removes the tab
     * <em>programmatically</em>, which does not fire it. So closing a PDF with Ctrl-W leaked its {@code
     * pdf-render} thread and left the document's file handle open for the life of the process (on Windows,
     * holding a lock on the file), and an image tab kept its decoded texture pinned. Closing the same tab by
     * clicking the ✕ leaked nothing — which is exactly why this survived manual testing.
     */
    void disposeViewerTab(Tab tab) {
        if (tab.getUserData() instanceof ImageViewerPane image) {
            image.dispose();
        } else if (tab.getUserData() instanceof HexViewerPane hex) {
            hex.dispose();
        } else if (tab.getUserData() instanceof PdfViewerPane pdf) {
            pdf.dispose();
        } else if (tab.getUserData() instanceof PrReviewPane pr) {
            github.onReviewPaneClosed(pr); // drop it from the coordinator's per-PR map (no resource to release)
        } else if (tab.getUserData() instanceof TerminalEditorTab terminal) {
            terminalCoordinator.editorTabClosed(terminal); // its shell ends, unless it went back to the panel
        }
    }

    /**
     * Wires the key dispatcher's first-look hook so that <b>M-g</b>, while a tool window is focused,
     * closes that window and returns focus to the editor (instead of starting the go-to prefix).
     */
    public void setKeyDispatcher(com.editora.command.KeyDispatcher dispatcher) {
        toolWindows.setFloatingSceneSetup(dispatcher::install);
        // Keyboard macros: the coordinator is told what was typed and which keys were left to the focused
        // control while recording, and the dispatcher stands aside for the keys a replay sends.
        if (macroCoordinator != null) {
            dispatcher.setMacroCapture(macroCoordinator);
        }
        dispatcher.setPrefixArgumentSupport(
                id -> "edit.setMark".equals(id)
                        || "edit.copyToRegister".equals(id) // C-u cuts the region instead of repeating
                        || toolWindows.isKeyboardCountAware(id)
                        || (macroCoordinator != null && macroCoordinator.isCountAware(id)),
                arg -> toolWindows.setKeyboardPrefixArgument(editing.currentPrefixArg = arg),
                editing::selfInsertRepeat);
        dispatcher.setPreDispatch((token, target) -> {
            if (!"M-g".equals(token)) {
                return false;
            }
            ToolWindow tw = toolWindows.toolWindowOf(target);
            if (tw == null) {
                return false;
            }
            toolWindows.close(tw);
            EditorBuffer b = activeBuffer();
            if (b != null) {
                b.getFocusedArea().requestFocus();
            }
            return true;
        });
    }

    /** Opens a link through the {@link ExternalLinks} allowlist (http/https/mailto) — never a bare showDocument. */
    void openExternalUrl(String url) {
        ExternalLinks.open(url, null, null, hostServices, fileWorkflows::openPath, this::setStatus);
    }

    /** `view.welcome`: opens the Welcome tab (or selects it if already open). */
    void showWelcome() {
        addWelcomeTab();
    }

    /** Opens the Welcome tab when the strip is empty (startup with no session, or after a project swap). */
    void showWelcomeIfNoTabs() {
        if (!sessions.suppressWelcome && editorArea.isEmpty()) {
            addWelcomeTab();
        }
    }

    // --- Projects (single-folder, VSCode-style) ---

    /** Loads the projects index and, if a project is active, points the session at it before restore. */
    private void setupProjects() {
        // The projects index is shared across all windows (one source of truth), so use the shared one.
        projects = config.projects();
        projectPicker = new QuickOpen<>(
                tr("project.picker.title"),
                tr("project.picker.prompt"),
                this::projectsWithNoProject,
                Project::name,
                p -> p.id().isEmpty() ? tr("project.picker.globalSession") : p.root(),
                this::switchToProject);
        // Keyboard "Open Project Folder" — mirrors the file finder, but picks a directory.
        navigation.folderFinder =
                new FileFinder(navigation::finderStartDir, this::openProjectRoot, true, tr("project.openFolder.title"));
        // Find File's C-d: open the highlighted folder as a project instead of descending into it (gated like
        // Open Project Folder, so it is neither offered nor run with project support off).
        navigation.fileFinder.setOpenAsProject(this::openProjectRoot, this::projectsEnabled);
        // Which project this window edits (and its session file) is set by WindowManager via
        // setWindowContext(); the global window just keeps the default workspace-state.json.
        refreshProjectPanelList();
        updateWindowTitle();
    }

    /** Toolbar "Open Folder" icon: native folder dialog (the palette/keybinding uses the finder). */
    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.openFolder.title"));
        java.io.File dir = chooser.showDialog(stage);
        if (dir != null) {
            openProjectRoot(dir.toPath());
        }
    }

    /** Creates/reuses a project for {@code root} and switches to it. */
    private void openProjectRoot(Path root) {
        String name = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        switchToProject(projects.createOrGet(name, root));
    }

    void refreshProjectPanelList() {
        // The toolbar combo shows THIS window's project as selected (each window is one project).
        if (toolbar.toolbarProjectCombo != null) {
            toolbar.toolbarProjectCombo.setProjects(projects.list(), projectKey);
        }
    }

    boolean projectsEnabled() {
        return config.getSettings().isProjectSupport();
    }

    /** Display name for a bookmark/note project key: {@code ""} → "General", else the project's name (fallback:
     *  the raw key, e.g. a deleted project's id). Used to label the per-project groups in the Bookmarks/Notes panels. */
    String projectDisplayName(String key) {
        if (key == null || key.isEmpty()) {
            return tr("scope.general");
        }
        return config.projects().list().stream()
                .filter(p -> p.id().equals(key))
                .map(Project::name)
                .findFirst()
                .orElse(key);
    }

    /** The projects list with a leading "No Project" entry (returns to the global session). */
    private List<Project> projectsWithNoProject() {
        List<Project> all = new ArrayList<>();
        all.add(ProjectCombo.NO_PROJECT);
        all.addAll(projects.list());
        return all;
    }

    /** Shows/hides all project UI per the "Enable projects" setting: toolbar icon + combo, tool window. */
    void applyProjectSupport() {
        if (projectPanel != null) { // else applied when the panel is built
            projectPanel.setShowHidden(config.getSettings().isProjectShowHidden()); // hidden-files toggle
            projectPanel.applyMapCardAids(); // semantic highlighting, inlay hints, sticky scroll in the Map's cards
            projectPanel.setRespectGitignore(
                    config.getSettings().isSearchRespectGitignore()); // skip .gitignore'd files in the filter search
        }
        boolean on = projectsEnabled();
        // Turning projects off just hides the project chrome — each window keeps editing its open files (windows no
        // longer share one session that could be "stranded"). On the next launch with projects off, WindowManager opens
        // a single global window. The project toolbar group is also hidden by Simple UI mode (it stays a project even
        // though the selector is hidden); kept here so this later pass doesn't re-show it over applySimpleMode.
        boolean showProjectGroup = on && !chrome.simpleModeActive();
        openFolderButton.setVisible(showProjectGroup);
        openFolderButton.setManaged(showProjectGroup);
        toolbar.toolbarProjectCombo.setVisible(showProjectGroup);
        toolbar.toolbarProjectCombo.setManaged(showProjectGroup);
        toolbar.projectToolbarGap.setVisible(showProjectGroup);
        toolbar.projectToolbarGap.setManaged(showProjectGroup);
        // Project tool window: force-hide when off; reveal once on the off→on transition; otherwise
        // leave it to the user (so it can be hidden/shown normally while projects are enabled).
        if (!on) {
            toolWindows.setVisible(projectToolWindow, false);
        } else if (!projectSupportApplied) {
            toolWindows.setVisible(projectToolWindow, true);
        }
        projectSupportApplied = on;
    }

    /**
     * Opens (or focuses) {@code p}'s window. Each project lives in its own window now, so this no longer
     * swaps the current window's session in place — it brings up that project's window, leaving this one
     * as-is. The "No Project" sentinel (empty id) opens/focuses the global window.
     */
    boolean switchToProject(Project p) {
        if (p == null) {
            return false;
        }
        if (windowManager == null) {
            return false; // multi-window not wired (shouldn't happen in the running app)
        }
        if (p.id().equals(projectKey)) {
            return true; // already this window (e.g. the combo re-selected the current project)
        }
        windowManager.openOrFocus(p.id().isEmpty() ? null : p);
        // This window didn't change; snap its combo back to its own project.
        refreshProjectPanelList();
        return true;
    }

    /** Closes this project window (with confirmation via the save prompts). */
    void closeProject() {
        if (windowProject == null) {
            setStatus(tr("status.noProjectOpen"));
            return;
        }
        // Closing a project loses nothing (its files are saved or asked about, its session kept): not red.
        ButtonType close = Dialogs.action(tr("dialog.close"));
        ButtonType cancel = Dialogs.cancel();
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.closeProject.body", windowProject.name()), close, cancel));
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.closeProject.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(cancel) != close) {
            return;
        }
        if (windowManager != null) {
            windowManager.requestClose(this); // persists + closes this window
        }
    }

    /** Deletes this window's project (with confirmation). */
    void deleteProject() {
        deleteProject(windowProject);
    }

    /**
     * Deletes a project from the shared list (with confirmation). Only the project entry and its saved
     * session are removed — which is all the confirmation says. The folder and its files on disk are left
     * untouched, and so are the project's personal notes, bookmarks, breakpoints and Local History: they
     * stay in their buckets under the project's id, which is derived from its name and folder, so adding the
     * folder again under the same name re-attaches them. (They used to be dropped here, unannounced and with
     * no way back; Local History in particular is the safety net for the project's files, not list
     * bookkeeping.) If the project has an open window, it is closed first (saving its dirty buffers); a
     * cancelled save prompt aborts the deletion.
     */
    void deleteProject(@Nullable Project p) {
        if (p == null || p.id().isEmpty()) {
            setStatus(tr("status.noProjectToDelete"));
            return;
        }
        if (!Confirm.ask(
                stage,
                tr("dialog.deleteProject.title"),
                tr("dialog.deleteProject.body", p.name()),
                tr("confirm.action.delete"))) {
            return;
        }
        // Close the project's window first (persisting its session + saving dirty buffers); a cancelled
        // save prompt leaves everything intact. No-op when the project has no open window.
        if (windowManager != null && !windowManager.closeWindowForKey(p.id())) {
            return;
        }
        // A write for this project's session may still be sitting in the config-writer queue; it would land
        // after the delete below and re-create the file. Drop it first.
        config.shared().cancelPendingWrite(projects.stateFile(p));
        projects.delete(p.id()); // drops it from the index + open set + deletes its state file
        projects.save();
        // Nothing else: the notes/bookmark/breakpoint/history buckets are user data and stay (see above).
        refreshProjectPanelList();
        setStatus(tr("status.deletedProject", p.name()));
    }

    /**
     * Window title: the active file's name + path, then the project (if any), then "Editora" —
     * e.g. {@code ~/src/app/Main.java — MyProject — Editora}. A {@code •} prefix marks an unsaved
     * buffer; an untitled buffer shows its display name; with no buffer (Welcome) just the app/project.
     */
    void updateWindowTitle() {
        String app = windowProject == null ? "Editora" : windowProject.name() + " — Editora";
        EditorBuffer b = activeBuffer();
        if (b == null) {
            // A non-buffer tab: show an image viewer's file path; else just the app name (Welcome, etc.).
            Path img = tabPath(editorArea.selectedTab());
            stage.setTitle(
                    img != null ? com.editora.config.PathDisplay.collapseHome(img.toString()) + " — " + app : app);
            return;
        }
        Path p = b.getPath();
        // getDisplayName() is null for a plain untitled buffer (it's only set by --new-file=name /
        // templates), and concatenating that rendered a literal "null — … — Editora" title. Fall back to
        // the buffer's tab title ("untitled"), so the title bar always agrees with the tab.
        String file = p != null
                ? com.editora.config.PathDisplay.collapseHome(p.toString())
                : b.getDisplayName() != null ? b.getDisplayName() : b.getTitle();
        // The OS title bar renders plain text only (no color/italic/weight, so the tab's amber-italic
        // dirty styling can't be mirrored here) — use a prominent leading "●" as the unsaved marker so
        // the modified state is visible even in Zen mode, where the tab strip is hidden.
        stage.setTitle((b.isDirty() ? "● " : "") + file + " — " + app);
    }

    /** Syncs editor/session state after the Project tree renames a file on disk (old → target). */
    void onProjectFileRenamed(Path old, Path target) {
        onProjectFileRenamed(old, target, false);
    }

    void onProjectFileRenamed(Path old, Path target, boolean oldLspAlreadyClosed) {
        if (windowManager != null) {
            windowManager.fileRenamedAcrossWindows(this, old, target, oldLspAlreadyClosed);
        } else {
            remapProjectFileLocal(old, target, oldLspAlreadyClosed);
        }
    }

    void remapProjectFileLocal(Path old, Path target, boolean oldLspAlreadyClosed) {
        fileWorkflows.invalidatePendingWrite(old);
        Tab tab = tabForPath(old); // before the cache goes: `old` no longer exists, so it cannot be re-resolved
        com.editora.config.PathKeys.invalidateCanonicalCache(); // stale resolutions must not survive a move (#680)
        indexCoordinator.onFileRenamed(old, target);
        RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
        if (tab != null && placeholder != null) {
            // A restored tab not built yet: its read, when it comes, must find the file where it now is. The
            // path-keyed stores are remapped by the caller; the tab has no buffer-side state to move.
            placeholder.relocate(target);
            tabHeaders.contentHeader(tab, placeholder);
            tab.setTooltip(TabHeaders.tooltip(target));
        } else if (tab != null && bufferOf(tab) instanceof EditorBuffer buffer) {
            buffer.setPath(target);
            editorSettings.applyEditorConfig(buffer);
            lspCoordinator.documentPathChanged(buffer, old, oldLspAlreadyClosed);
            debugCoordinator.bufferPathChanged(buffer, old);
            updateTabMeta(tab, buffer);
            if (buffer == activeBuffer()) {
                breadcrumb.setActiveFile(target);
                statusBar.refresh();
            }
        } else if (Files.isDirectory(target)) {
            // A moved/renamed *directory*: remap every open buffer whose file lived under `old` to the
            // corresponding path under `target` (e.g. drag-moving a folder in the Project tree).
            Path oldNorm = old.toAbsolutePath().normalize();
            for (Tab t : editorArea.tabs()) {
                EditorBuffer b = bufferOf(t);
                RestorePlaceholder unbuilt = b == null ? RestorePlaceholder.of(t) : null;
                Path p = b != null ? b.getPath() : unbuilt == null ? null : unbuilt.path();
                if (p == null) {
                    continue;
                }
                Path pn = p.toAbsolutePath().normalize();
                if (unbuilt != null) {
                    if (com.editora.config.PathKeys.isUnder(pn, oldNorm)) {
                        // Not built yet: its read, when it comes, must find the file where it now is.
                        Path moved = target.resolve(oldNorm.relativize(pn));
                        unbuilt.relocate(moved);
                        t.setTooltip(TabHeaders.tooltip(moved));
                        migrateFileState(p, moved);
                    }
                    continue;
                }
                if (b != null && com.editora.config.PathKeys.isUnder(pn, oldNorm)) { // never on another file system
                    Path moved = target.resolve(oldNorm.relativize(pn));
                    fileWorkflows.invalidatePendingWrite(p);
                    b.setPath(moved);
                    editorSettings.applyEditorConfig(b);
                    lspCoordinator.documentPathChanged(b, p, oldLspAlreadyClosed);
                    debugCoordinator.bufferPathChanged(b, p);
                    updateTabMeta(t, b);
                    migrateFileState(p, moved);
                    if (b == activeBuffer()) {
                        breadcrumb.setActiveFile(moved);
                        statusBar.refresh();
                    }
                }
            }
        }
        migrateFileState(old, target);
        // The saved HTTP responses follow the file (or every .http file under a moved folder), shown or not: the
        // tree's rename never reached the history, so closing the tab or quitting lost it.
        httpClient.onFileMoved(old, target);
        htmlPreview.renamed(old, target); // nothing served from the editor under the old name (E201); globes
        requestSave();
        setStatus(tr("status.renamedTo", target.getFileName()));
    }

    /** Everything stored under the renamed path (a file, or a folder and the files below it) follows it. */
    private void migrateFileState(Path old, Path target) {
        RenamedFileState.rekeyWorkspace(config.getWorkspaceState(), old, target);
        if (recentFiles != null) {
            recentFiles.remove(old);
        }
        bookmarkCoordinator.pathRenamed(old, target);
        notesCoordinator.pathRenamed(old, target);
        debugCoordinator.pathRenamed(old, target);
        historyCoordinator.pathRenamed(old, target);
    }

    /** Syncs editor/session state after the Project tree deletes a file on disk. */
    void onProjectFileDeleted(Path path) {
        onProjectFileDeleted(path, buffer -> false); // nobody was asked: an unsaved buffer stays open
    }

    /** The Project tree deleted {@code path} after its preflight asked every window's unsaved owners. */
    private void onProjectTreeFileDeleted(Path path) {
        onProjectFileDeleted(path, buffer -> projectDeletes != null && projectDeletes.covers(buffer));
    }

    void onProjectFileDeleted(Path path, java.util.function.Predicate<EditorBuffer> discardApproved) {
        if (windowManager != null) {
            windowManager.fileDeletedAcrossWindows(path, discardApproved);
        } else {
            removeProjectFileLocal(path, discardApproved);
        }
    }

    /** The window that holds {@code buffer} in a tab; this one when no other does. */
    private MainController ownerOf(EditorBuffer buffer) {
        return windowManager == null ? this : windowManager.ownerOf(buffer, this);
    }

    boolean holdsBufferLocal(EditorBuffer buffer) {
        return tabFor(buffer) != null;
    }

    /** Brings this window and {@code buffer}'s tab forward, so a prompt about it is asked where it lives. */
    private void revealBufferLocal(EditorBuffer buffer) {
        Tab tab = tabFor(buffer);
        if (tab != null) {
            editorArea.select(tab);
        }
        if (stage != null && !stage.isFocused()) {
            stage.toFront();
            stage.requestFocus();
        }
    }

    void removeProjectFileLocal(Path path, java.util.function.Predicate<EditorBuffer> discardApproved) {
        com.editora.config.PathKeys.invalidateCanonicalCache(); // (#680)
        indexCoordinator.onFileDeleted(path);
        for (EditorBuffer buffer : buffersAtOrUnderLocal(path)) {
            Tab tab = tabFor(buffer);
            if (tab == null) {
                continue;
            }
            // Last line of defence, whichever path deleted the file: unsaved text is dropped only when its
            // owner was asked about exactly this state. Otherwise the tab stays, as the only copy left.
            if ((buffer.isDirty() || fileWorkflows.hasPendingSave(buffer)) && !discardApproved.test(buffer)) {
                buffer.markUnsaved();
                updateTabMeta(tab, buffer);
                setStatus(tr("status.fileGoneKeptUnsaved", buffer.getTitle()));
                continue;
            }
            editorArea.remove(tab);
        }
        WorkspaceState ws = config.getWorkspaceState();
        String key = path.toString();
        ws.getFoldedRegions().remove(key);
        ws.getMarkdownViewModes().remove(key);
        ws.getMarkdownPreviewLayouts().remove(key);
        ws.getSpellLanguages().remove(key);
        ws.getFileEncodings().remove(key);
        ws.getReadOnlyFiles().remove(key);
        if (recentFiles != null) {
            recentFiles.remove(path);
        }
        requestSave();
        setStatus(tr("status.deleted", path.getFileName()));
    }

    /** Moves the active editor's caret to {@code line} and anchors it below any sticky-scroll rows. */
    void navigateToLine(int line) {
        EditorBuffer buffer = activeBuffer();
        CodeArea area = activeArea();
        if (buffer == null
                || area == null
                || line < 0
                || line >= area.getParagraphs().size()) {
            return;
        }
        NavigationHistory.Location origin = navigation.navigating ? null : navigation.captureCurrent();
        // Reveal the target if it's hidden inside a collapsed fold, so we don't scroll to a hidden line.
        buffer.getFoldManager().unfoldContaining(line);
        area.moveTo(line, 0);
        if (!navigation.navigating && buffer.getPath() != null) {
            navigation.recordJumpToLine(origin, buffer, line);
        }
        buffer.jumps().reveal(area, line, false);
        area.requestFocus();
    }

    /**
     * Opens a bookmark/note's file at 0-based {@code line} in the window for {@code projectKey} ({@code ""} =
     * general/no-project). When the entry belongs to <em>this</em> window's project (or Projects are off, or
     * there's no window manager), it opens in place — the original behavior. Otherwise it hands off to
     * {@link WindowManager#openInWindow} so the user lands in that project's window (focused if already open,
     * else built), rather than opening the file out of context in the current window.
     */
    void openInProjectWindow(String projectKey, Path file, int line) {
        String key = projectKey == null ? "" : projectKey;
        if (windowManager == null || !projectsEnabled() || key.equals(config.currentProjectKey())) {
            openAndNavigate(file, line);
            return;
        }
        windowManager.openInWindow(key, file, line);
    }

    /**
     * Cross-window landing for a bookmark/note activated from another project's bucket: opens {@code file} in
     * <em>this</em> (now-focused) window and jumps to 0-based {@code line}. Called by
     * {@link WindowManager#openInWindow} on the target window's controller once it exists and is restored.
     */
    public void openAndNavigate(Path file, int line) {
        if (line < 0) {
            if (projectToolWindow != null) {
                toolWindows.open(projectToolWindow, false);
            }
            projectPanel().revealPathInTree(file);
            return;
        }
        fileWorkflows.openThen(file, () -> navigateToLine(navigation.areaLine(activeBuffer(), line)));
    }

    /**
     * A Project Map declaration's Open Narrowed: opens {@code file} and narrows it to {@code block} (0-based
     * document lines), as the Structure window's Narrow to Fold Region does for a picked symbol.
     */
    private void openNarrowedFromMap(Path file, com.editora.text.FoldRegions.Region block) {
        fileWorkflows.openThen(file, () -> {
            EditorBuffer buffer = activeBuffer();
            Path open = buffer == null ? null : buffer.getPath();
            if (buffer != null
                    && open != null
                    && com.editora.config.PathKeys.samePath(
                            open.toAbsolutePath().normalize(),
                            file.toAbsolutePath().normalize())) {
                editing.narrowToFoldRegionAt(buffer, block.startLine(), block);
            }
        });
    }

    /**
     * The recent entries still worth offering — see {@link RecentFiles#showable}. Kept here so the toolbar
     * dropdown and the Welcome page filter identically; a deleted file must not be offered by either.
     */
    List<Path> showableRecentFiles() {
        return recentFiles == null ? List.of() : recentCheck.showable(recentFiles.getList());
    }

    /** A shared history list changed (in any window): refresh this window's recent menu and query dropdown. */
    void sharedHistoryChanged() {
        rebuildRecentMenu();
        searchCoordinator.refreshHistory();
    }

    /** This window's Settings window, for {@link WindowManager} to bring in line with a change made elsewhere. */
    SettingsWindow settingsWindow() {
        return settingsWindow;
    }

    StatusBar statusBar() {
        return statusBar;
    }

    BackgroundTasks backgroundTasks() {
        return backgroundTasks;
    }

    private void rebuildRecentMenu() {
        recentCheck.revalidate(recentFiles, this::rebuildRecentMenu); // stats off-thread; re-renders if wrong
        recentButton.getItems().clear();
        List<Path> shown = showableRecentFiles();
        if (shown.isEmpty()) {
            MenuItem empty = new MenuItem(tr("menu.noRecentFiles"));
            empty.setDisable(true);
            recentButton.getItems().add(empty);
            return;
        }
        RecentFileRow.Columns columns = new RecentFileRow.Columns(); // lines the project pills up
        for (Path path : shown) {
            recentButton.getItems().add(recentMenuItem(path, columns));
        }
        // "Clear recent files" lives here rather than as its own toolbar icon: as a bare trash can beside
        // the file group it read as "delete this file" and sat a slot away from Save. Inside the dropdown it
        // is unambiguous and can't be hit by accident. (The file.clearRecent command + the catalog entry are
        // unchanged, so anyone who wants the icon back can add it in Settings → Toolbar.)
        recentButton.getItems().add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem(tr("tooltip.clearRecent"), Icons.trash());
        clear.getStyleClass().add("danger"); // a destructive row reads red
        clear.setOnAction(e -> onClearRecent());
        MenuChords.tag(clear, "file.clearRecent");
        recentButton.getItems().add(clear);
    }

    /** A recent-file menu entry: filename label that opens the file, project pill, and an inline remove icon. */
    private CustomMenuItem recentMenuItem(Path path, RecentFileRow.Columns columns) {
        Label name = new Label(path.getFileName().toString());
        Label projectName = RecentProject.containing(path, config.projects().list())
                .map(project -> {
                    Label label = new Label(project.name());
                    label.getStyleClass().add("recent-project-name");
                    return label;
                })
                .orElse(null);
        Button removeBtn = new Button();
        removeBtn.setGraphic(Icons.trash());
        removeBtn.getStyleClass().addAll("button-icon", "flat", "recent-remove");
        removeBtn.setFocusTraversable(false);
        removeBtn.setTooltip(new Tooltip(tr("tooltip.removeRecent")));
        // Remove just this entry, after a confirmation, without opening it. The dropdown closes first: a
        // modal dialog over an open popup would leave the popup stacked above it. Deferred so the dialog's
        // nested event loop doesn't run inside this mouse press.
        removeBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            e.consume();
            recentButton.hide();
            Platform.runLater(() -> confirmRemoveRecent(path));
        });
        RecentFileRow row = new RecentFileRow(columns, name, projectName, removeBtn);

        CustomMenuItem item = new CustomMenuItem(row);
        item.setOnAction(e -> openRecent(path));
        Tooltip.install(row, new Tooltip(path.toString()));
        return item;
    }

    /** Asks before dropping {@code path} from the recent files; the full path tells same-named files apart. */
    private void confirmRemoveRecent(Path path) {
        if (recentFiles == null) {
            return;
        }
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.removeRecent.title"));
        alert.setHeaderText(tr("dialog.removeRecent.header", path.getFileName().toString()));
        alert.setContentText(path.toString());
        ButtonType remove = new ButtonType(tr("dialog.removeRecent.remove"));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(remove, cancel);
        Dialogs.destructive(alert, remove);
        if (alert.showAndWait().orElse(cancel) == remove) {
            recentFiles.remove(path);
        }
    }

    private void closeTabFromSwitcher(Tab tab) {
        closeTab(tab);
    }

    /**
     * Selects a tab chosen in an overlay picker (the Switcher / "Jump to Open File") and moves the caret into
     * its editor. The focus request is deferred via {@code Platform.runLater} because these pickers close through
     * the shared {@link OverlayHost}, whose {@code hide()} synchronously restores focus to the previously-focused
     * area — running the focus request afterward ensures the caret lands in the newly selected buffer instead of
     * the old one (otherwise the user has to click to get a caret). Robust regardless of whether the picker
     * hides before or after invoking this.
     */
    void activateAndFocusTab(Tab tab) {
        editorArea.select(tab);
        Platform.runLater(
                () -> TabFocus.focusTab(tab, MainController::bufferOf)); // a page tab: its page (twelfth review AL1)
    }

    /**
     * The editor area with the breadcrumb hung beneath it, as one node for the tool-window layout.
     *
     * <p>The breadcrumb names the <em>active file</em>, so it belongs to the editor rather than to the
     * window frame. Stacked in the bottom bar it sat between the bottom tool stripe and the status bar —
     * spanning the whole window, sandwiched between two full-width bars, and reading as a second status
     * bar describing the window rather than a path bar describing the file. Inside the horizontal split it
     * is only as wide as the editor, sits directly under the text it describes, and no longer crosses under
     * the left/right tool windows.
     *
     * <p>Wrapping is safe for the stripe alignment {@link ToolWindowManager} does on this node: it looks the
     * tab header up by style class through the whole subtree, and the breadcrumb is added at the bottom, so
     * the top edge it measures from is unchanged.
     */
    private javafx.scene.Node editorFooter() {
        editorEmptyState = new EditorEmptyState(registry, keymap, this::projectsEnabled);
        BorderPane withBreadcrumb =
                new BorderPane(new javafx.scene.layout.StackPane(editorArea.node(), editorEmptyState.node()));
        withBreadcrumb.setBottom(breadcrumb);
        return withBreadcrumb;
    }

    private void setupToolWindows() {
        editorArea.setDraggedTabSource(tabHeaders::draggedTab); // drops onto a group body move/split it
        // The menu bar sits above the toolbar. Built from the registry, so it needs no per-command wiring.
        menuBar = new MainMenuBar(registry, this::invertBindings, this::paletteGates, this::paletteContext, id -> {
            if (registry.get(id).isPresent()) {
                registry.run(id);
            }
        });
        // An extended window has no system title bar, so the menu bar takes its place; asking the stage
        // rather than re-reading the setting means the two can never disagree for a window already built.
        topBox.getChildren()
                .add(
                        0,
                        stage != null && stage.getStyle() == javafx.stage.StageStyle.EXTENDED
                                ? ExtendedWindow.header(menuBar.node(), stage)
                                : menuBar.node());
        toolWindows = new ToolWindowManager(workspace, editorFooter(), config, keymap);
        projectDeletes = new ProjectDeleteCoordinator(
                path -> windowManager == null ? buffersAtOrUnderLocal(path) : windowManager.buffersAtOrUnder(path),
                buffer -> ownerOf(buffer).revealBufferLocal(buffer),
                buffer -> ownerOf(buffer).fileWorkflows.hasPendingSave(buffer),
                buffer -> ownerOf(buffer).closes.confirmCloseIfDirty(buffer),
                fileWorkflows::invalidatePendingWrite,
                (path, completion) -> historyCoordinator.captureBeforeDeleteDurably(path, completion),
                this::setStatus);
        exports.activeTabContent =
                () -> tabPath(activeTab()) == null ? null : activeTab().getUserData();
        // Breadcrumb crumbs offer the same Reveal / Open Terminal as the Project tree (Reveal for local files only).
        breadcrumb.setOnReveal((p, dir) -> revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        breadcrumb.setOnOpenTerminal((p, dir) -> openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        bookmarkCoordinator.setOnChanged(this::refreshProjectMarkersIfBuilt);
        notesCoordinator.setOnChanged(this::refreshProjectMarkersIfBuilt);
        // Every tool window below hands ToolWindow a supplier: the panel is built on the window's first open
        // (ToolWindow.getContent), or by the first caller that needs it — never before first paint for a
        // window that is closed. restore() opens the saved-open windows, which builds exactly those.
        projectToolWindow = toolWindow("project", ToolWindow.Side.RIGHT, Icons::project, this::projectPanel);
        structureToolWindow = toolWindow("structure", ToolWindow.Side.RIGHT, Icons::structure, this::structurePanel);
        bookmarksToolWindow =
                toolWindow("bookmarks", ToolWindow.Side.RIGHT, Icons::bookmark, bookmarkCoordinator::panel);
        notesToolWindow = toolWindow("notes", ToolWindow.Side.RIGHT, Icons::notes, notesCoordinator::panel);
        fileInfoToolWindow = toolWindow(
                "file-information",
                "toolwindow.file-information",
                ToolWindow.Side.RIGHT,
                Icons::about,
                this::fileInfoPanel,
                "tool.fileInformation");
        undoHistoryToolWindow =
                toolWindow("undoHistory", ToolWindow.Side.RIGHT, Icons::history, this::undoHistoryPanel);
        commitToolWindow = toolWindow("commit", ToolWindow.Side.RIGHT, Icons::git, this::gitPanel);
        gitLogOps = gitWindows.gitLogActions();
        gitWindows.listenTo(git);
        gitLogToolWindow = toolWindow("gitLog", ToolWindow.Side.BOTTOM, Icons::gitLog, this::gitLogPanel);
        githubToolWindow = toolWindow("github", ToolWindow.Side.BOTTOM, Icons::github, this::githubPanel);
        historyCoordinator =
                new HistoryCoordinator(coordinatorHost, diffCoordinator, new HistoryOpsHost(this), config.shared());
        noUndoGuard = new NoUndoGuard(historyCoordinator::recordSafetyCopy, coordinatorHost::setStatus);
        git.attachWindow(diffCoordinator, buffer -> addBuffer(buffer, true)); // stash review, patches, revision tabs
        fileHistoryToolWindow =
                toolWindow("fileHistory", ToolWindow.Side.BOTTOM, Icons::history, historyCoordinator::panel);
        searchToolWindow = toolWindow("search", ToolWindow.Side.RIGHT, Icons::findInFiles, searchCoordinator::panel);
        todoToolWindow = toolWindow("todo", ToolWindow.Side.BOTTOM, Icons::todo, todoCoordinator::panel);
        markdownLintToolWindow =
                toolWindow("markdownLint", ToolWindow.Side.BOTTOM, Icons::warning, this::markdownLintPanel);
        problemsToolWindow =
                toolWindow("problems", ToolWindow.Side.BOTTOM, Icons::problems, lspCoordinator::problemsPanel);
        referencesToolWindow =
                toolWindow("references", ToolWindow.Side.BOTTOM, Icons::find, lspCoordinator::referencesPanel);
        hierarchyToolWindow =
                toolWindow("hierarchy", ToolWindow.Side.BOTTOM, Icons::structure, lspCoordinator::hierarchyPanel);
        runToolWindow = toolWindow("run", ToolWindow.Side.BOTTOM, Icons::run, runCoordinator::panel);
        terminalToolWindow =
                toolWindow("terminal", ToolWindow.Side.BOTTOM, Icons::terminal, terminalCoordinator::panel);
        runToolWindow.busyProperty().bind(runCoordinator.runningProperty()); // spinner badge on its stripe button
        debugCoordinator =
                new DebugCoordinator(coordinatorHost, dapManager, lspManager, lspCoordinator, new DebugOpsHost(this));
        debugToolWindow = toolWindow("debug", ToolWindow.Side.BOTTOM, Icons::debug, debugCoordinator::view);
        debugCoordinator.setProgramTerminal(terminalCoordinator::runProgram); // Settings → Debug → in the Terminal
        debugToolWindow.setFirstOpenShare(0.42); // stack, variables and console need rows (review C4)
        externalToolToolWindow = toolWindow(
                "externalTool",
                "toolwindow.externalTools",
                ToolWindow.Side.BOTTOM,
                Icons::tools,
                externalToolCoordinator::panel,
                "tool.externalTools");
        for (BuildCoordinator c : buildCoordinators) {
            BuildTool tool = c.tool();
            // The primary window: the IntelliJ-style tasks tree (its stripe appears when the marker is found).
            // Defaults to the RIGHT stripe (a better fit for a vertical task list); a window with a persisted
            // side keeps it (currentSide reads WorkspaceState first). The shared Output console stays
            // BOTTOM. (#531)
            buildToolWindows.put(
                    tool,
                    new ToolWindow(
                            tool.id(),
                            tr("toolwindow." + tool.id()),
                            ToolWindow.Side.RIGHT,
                            c.iconSupplier(),
                            c::tasksPanel, // built on first open
                            "tool." + tool.id()));
        }
        // A single shared "Output" console for every build tool (auto-opens on a run).
        buildOutputPanel.setOnLink(testNavigation::openRunLink);
        buildOutputPanel.setOnUrl(this::openExternalUrl);
        buildOutputPanel.setOnGitDiff(pulled -> diffCoordinator.diffPulledFile(pulled));
        installCommandLogs();
        buildOutputToolWindow = new ToolWindow(
                "buildOutput",
                tr("toolwindow.buildOutput"),
                ToolWindow.Side.BOTTOM,
                Icons::tools,
                buildOutputPanel,
                "tool.buildOutput");
        testResultsToolWindow =
                toolWindow("testResults", ToolWindow.Side.BOTTOM, Icons::testResults, testRunCoordinator::panel);
        // Mirror a recognized `test` run of any build tool into the Test Results window.
        buildCoordinators.forEach(c -> c.setTestRunHook(testRunCoordinator));
        installCoordinator = new InstallCoordinator(coordinatorHost, new InstallOpsHost(this));
        remoteCoordinator = new RemoteCoordinator(coordinatorHost, new RemoteOpsHost(this));
        containerCoordinator = new ContainerCoordinator(coordinatorHost, new ContainerCoordinator.Ops() {
            @Override
            public @Nullable Path projectRoot() {
                return windowProjectRoot();
            }

            @Override
            public boolean execAllowed(@Nullable String targetId) {
                return targetId != null && config.remoteExecAllowed(targetId);
            }

            @Override
            public void setExecAllowed(@Nullable String targetId, boolean allowed) {
                if (targetId != null) config.setRemoteExecAllowed(targetId, allowed);
            }

            @Override
            public void execChanged() {
                toolsMoved();
            }
        });
        remoteToolWindow = toolWindow("remote", ToolWindow.Side.RIGHT, Icons::remote, remoteCoordinator::panel);
        agentToolWindow = toolWindow("agent", ToolWindow.Side.RIGHT, Icons::agent, agentCoordinator::panel);
        toolWindows.register(projectToolWindow);
        toolWindows.register(structureToolWindow);
        toolWindows.register(bookmarksToolWindow);
        toolWindows.register(notesToolWindow);
        toolWindows.register(commitToolWindow);
        toolWindows.register(gitLogToolWindow);
        toolWindows.register(githubToolWindow, false); // stripe off by default; available inside a GitHub repo
        toolWindows.setAvailable(githubToolWindow, false);
        toolWindows.setAvailable(
                gitLogToolWindow, false); // shown only inside a repo (gated by GitCoordinator#applyState)
        toolWindows.register(fileHistoryToolWindow);
        toolWindows.setAvailable(fileHistoryToolWindow, false); // shown only for a local file with history on
        // The status-bar file-size segment is the File Information window's visual toggle. Keep the
        // window registered for commands, restoration, docking, and Settings, but avoid a duplicate stripe icon.
        toolWindows.registerWithoutStripe(fileInfoToolWindow);
        toolWindows.register(undoHistoryToolWindow, false); // stripe off by default; reachable via the
        // undoHistory.jump popup, the tool.undoHistory command, or Settings → Tool Windows
        // The toolbar is the sole visual toggle for Find in Files; a second icon on the stripe was
        // redundant. Keep the window registered so commands, restoration, and docking still work.
        toolWindows.registerWithoutStripe(searchToolWindow);
        toolWindows.register(todoToolWindow);
        toolWindows.register(markdownLintToolWindow);
        toolWindows.register(indexCoordinator.backlinks.toolWindow(toolWindows), false); // on demand
        toolWindows.register(problemsToolWindow);
        toolWindows.register(referencesToolWindow, false); // default-hidden; shown on demand by Find References
        toolWindows.register(hierarchyToolWindow, false); // default-hidden; shown on demand by lsp.callHierarchy (#682)
        toolWindows.register(runToolWindow);
        toolWindows.register(terminalToolWindow); // always available: a shell needs no file
        toolWindows.setAvailable(runToolWindow, false); // shown only when the active file is a compact source
        toolWindows.register(debugToolWindow);
        toolWindows.setAvailable(debugToolWindow, false); // shown only while debugging is enabled
        toolWindows.register(externalToolToolWindow, false); // stripe off by default; reachable via the
        // tool.externalTools command / externalTool.run picker / Settings → Tool Windows
        for (ToolWindow tw : buildToolWindows.values()) {
            toolWindows.register(tw, true); // tasks-tree stripe visible by preference…
            toolWindows.setAvailable(tw, false); // …but hidden until the tool's marker file is detected
        }
        // Default-visible, with setAvailable below as the real gate: availability already answers "is there
        // anything to show?" (a detected build tool, a Git repo, or a tab already written), so ALSO defaulting
        // the user-visibility flag to false made the stripe button unreachable in exactly those cases. A user
        // who hides it in Settings → Tool Windows still has that preference persisted and respected.
        toolWindows.register(buildOutputToolWindow, true);
        toolWindows.setAvailable(buildOutputToolWindow, false); // …available once any build tool is detected
        // Default-visible by preference, but unavailable until a test run occurs. This matches Build Output:
        // availability answers whether there is anything to show, while Settings controls the user's stripe choice.
        toolWindows.register(testResultsToolWindow, true);
        toolWindows.setAvailable(testResultsToolWindow, false); // …available once a test run has occurred
        toolWindows.register(remoteToolWindow, false); // off by default (niche); always available — no buffer needed
        toolWindows.register(
                agentToolWindow, false); // stripe off by default; shown via tool.agent / Settings → AI Agent
        toolWindows.setAvailable(agentToolWindow, agentCoordinator.isEnabled());
        updateBufferToolWindows(); // hide buffer-only windows until there's an actionable buffer (no Welcome flash)
        toolWindows.setStateListener((tw, opened) -> {
            if (tw == searchToolWindow) {
                findInFilesButton.pseudoClassStateChanged(OPEN, opened);
            }
            if (tw == gitLogToolWindow) {
                // Refresh the log whenever the window is opened — via the stripe button (which toggles
                // directly, bypassing showGitLog) or a command. open() only fires this on a real open.
                if (opened && git.isEnabled()) {
                    gitWindows.loadGitLog(gitWindows.gitLogFilter);
                }
                return;
            }
            if (tw == githubToolWindow) {
                if (opened && github.isEnabled()) {
                    gitWindows.reloadGithubPanel(); // fetch the current mode's list when the window is opened
                }
                return;
            }
        });
    }

    /** Starts AceJump on the active buffer: type a character, then a label, to jump the caret. */
    void startAceJump() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        setStatus(tr("acejump.prompt"));
        b.startAceJump();
    }

    void startAceJumpLine() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        setStatus(tr("acejump.promptLine"));
        b.startAceJumpLine();
    }

    private Region placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("tool-window-placeholder");
        label.setWrapText(true);
        StackPane wrapper = new StackPane(label);
        wrapper.setAlignment(javafx.geometry.Pos.CENTER);
        return wrapper;
    }

    // --- Git integration -------------------------------------------------------------------------

    /** Effective Local File History gate: the setting, but off in Simple UI mode (saved setting unchanged). */
    private boolean localHistoryEnabled() {
        return config.getSettings().isLocalHistory() && !chrome.simpleModeActive();
    }

    /** Local History's index changed in another window: this window's panel shows it as it is now. */
    void localHistoryChanged() {
        if (historyCoordinator != null) {
            historyCoordinator.refresh();
        }
    }

    /** Reconciles LaTeX math rendering with its setting + the app theme; re-renders open previews. */
    void applyMathSupport() {
        previews.applyPreviewSettings();
    }

    // --- Feature coordinators (each peeled off MainController; share one CoordinatorHost adapter) -----

    /** One shared adapter handed to every feature coordinator (replaces a per-feature anonymous Host). */
    final EditingCoordinator editing = new EditingCoordinator(new EditingHost(this));

    @FXML
    private void onUndo() {
        editing.onUndo();
    }

    @FXML
    private void onRedo() {
        editing.onRedo();
    }

    @FXML
    private void onCut() {
        editing.onCut();
    }

    @FXML
    private void onCopy() {
        editing.onCopy();
    }

    @FXML
    private void onPaste() {
        editing.onPaste();
    }

    /** Shows the find/replace bar, or hides it if it's already open. */
    @FXML
    private void onFind() {
        editing.onFind();
    }

    final TemplateCoordinator templateActions = new TemplateCoordinator(new TemplateHost(this));

    final EditorSettingsCoordinator editorSettings = new EditorSettingsCoordinator(new EditorSettingsHost(this));

    final RunConfigurationCoordinator runConfigurations =
            new RunConfigurationCoordinator(new RunConfigurationHost(this));

    /** Toolbar run-configuration selector + its Run/Debug/Stop buttons (#765). */
    @FXML
    javafx.scene.control.ComboBox<com.editora.config.RunConfiguration> runConfigCombo;

    @FXML
    Button runConfigRunButton;

    @FXML
    Button runConfigDebugButton;

    @FXML
    Button runConfigStopButton;

    @FXML
    private void onRunSelectedConfig() {
        runConfigurations.onRunSelectedConfig();
    }

    @FXML
    private void onDebugSelectedConfig() {
        runConfigurations.onDebugSelectedConfig();
    }

    @FXML
    private void onStopRun() {
        runConfigurations.onStopRun();
    }

    private final WindowCommandRegistrar windowCommands = new WindowCommandRegistrar(new WindowCommandHost(this));

    final NavigationCoordinator navigation = new NavigationCoordinator(new NavigationHost(this));

    final PreviewCoordinator previews = new PreviewCoordinator(new PreviewHost(this));

    final GitWindowCoordinator gitWindows = new GitWindowCoordinator(new GitWindowHost(this));

    final WindowChromeCoordinator chrome = new WindowChromeCoordinator(new WindowChromeHost(this));

    final WindowMcpBridge mcpBridge = new WindowMcpBridge(new WindowMcpHost(this));

    final FileWorkflowCoordinator fileWorkflows = new FileWorkflowCoordinator(new FileWorkflowHost(this));

    @FXML
    void onSave() {
        fileWorkflows.onSave();
    }

    @FXML
    private void onSaveAs() {
        fileWorkflows.onSaveAs();
    }

    final WindowSessionCoordinator sessions = new WindowSessionCoordinator(new WindowSessionHost(this));

    final TestNavigationCoordinator testNavigation = new TestNavigationCoordinator(new TestNavigationHost(this));

    final InstallPromptCoordinator installPrompts = new InstallPromptCoordinator(new InstallPromptHost(this));

    final CoordinatorHost coordinatorHost = new WindowServicesHost(this);

    /** Crash recovery for this window's unsaved buffers (see {@link RecoveryCoordinator}). */
    final RecoveryCoordinator recovery =
            new RecoveryCoordinator(coordinatorHost, fileWorkflows, this::restorePerFileState, () -> projectKey);

    final GitCoordinator git = new GitCoordinator(coordinatorHost, new GitOpsHost(this));

    /** The diff + merge-conflict viewer (open/refresh diffs, apply-change, compare entry points, patch
     *  export, merge resolution); see {@link DiffCoordinator}. Git-backed diffs reach the repo via {@code git}. */
    final DiffCoordinator diffCoordinator = new DiffCoordinator(coordinatorHost, git, new DiffOpsHost(this));

    // --- GitHub (native `gh` CLI: PR checkout/diff/create + open-on-GitHub) ---------------------------

    /** The GitHub integration (gh-backed); see {@link GitHubCoordinator}. Rides on {@code git} for the repo
     *  root and reuses {@code diffCoordinator} for PR diff review. */
    final GitHubCoordinator github =
            new GitHubCoordinator(coordinatorHost, git, diffCoordinator, new GitHubOpsHost(this));

    // --- Mermaid (mmdc render/export + maid lint) ----------------------------------------------------

    /** The Mermaid feature (diagram render/export, live lint, autocomplete gating); see {@link MermaidCoordinator}. */
    final MermaidCoordinator mermaid = new MermaidCoordinator(coordinatorHost);

    /** The diagram-as-code feature (Graphviz DOT + PlantUML preview/export); see {@link DiagramCoordinator}. */
    final DiagramCoordinator diagram = new DiagramCoordinator(coordinatorHost);

    /** The Typst document feature (multi-page rendered preview + export); see {@link TypstCoordinator}. */
    final TypstCoordinator typst = new TypstCoordinator(coordinatorHost, this::resolveTypstRoot);

    /** Owns all document export/print services and their window lifecycle. */
    final ExportCoordinator exports =
            new ExportCoordinator(coordinatorHost, mermaid, diagram, typst, fileWorkflows::openPath);

    // --- HTML Live Preview (serve via a loopback HttpServer + open in a detected browser) ---------

    /** The HTML Live Preview feature; see {@link HtmlPreviewCoordinator}. */
    final HtmlPreviewCoordinator htmlPreview = new HtmlPreviewCoordinator(coordinatorHost);

    // --- Log viewer ----------------------------------------------------------------------------------

    /** The server-log-viewer feature (level highlighting, tail-follow, filtering); see {@link LogViewerCoordinator}. */
    final LogViewerCoordinator logViewer = new LogViewerCoordinator(coordinatorHost);

    /** New Maven Project wizard; owns the archetype picker/form and the archetype:generate run. */
    final MavenProjectCoordinator mavenProjectCoordinator =
            new MavenProjectCoordinator(coordinatorHost, new MavenProjectWindowOps(this), buildOutputPanel);

    /** External Tools feature; owns the service/console panel/commands (the tool window stays here). */
    final ExternalToolCoordinator externalToolCoordinator =
            new ExternalToolCoordinator(coordinatorHost, new ExternalToolCoordinator.Ops() {
                @Override
                public java.nio.file.@Nullable Path projectRoot() {
                    return windowProject != null ? windowProject.rootPath() : null;
                }

                @Override
                public void openConsole() {
                    toolWindows.open(externalToolToolWindow, true);
                }

                @Override
                public void onOutputLink(com.editora.run.StackTraceLinks.Link link) {
                    testNavigation.openRunLink(link);
                }
            });

    { // An export, a print or an external tool given an encrypted file's text asks first (ADR 0017).
        exports.plaintextOut = b -> fileWorkflows.encryption().confirmPlaintextOut(b, tr("encryption.out.export"));
        externalToolCoordinator.plaintextOut =
                b -> fileWorkflows.encryption().confirmPlaintextOut(b, tr("encryption.out.tool"));
    }

    /** Build tools (Maven/npm/…): one coordinator per tool — a toolbar icon + actions popup parsed from the
     *  active project's marker file, streaming tasks to a per-tool console (see {@link BuildCoordinator}). */
    final List<BuildCoordinator> buildCoordinators = createBuildCoordinators();

    /**
     * The argv prefix Run and Debug start {@code tool} with from {@code root}: the tool's tasks tree decides
     * (its wrapper, the override, the PATH command) and asks for trust as it would for its own run; null when
     * trust was declined. With the tool's support turned off there is no tree — then the user's own command
     * (the override, else the PATH command), never a repository's wrapper, which nothing has gated.
     */
    @Nullable
    List<String> buildExecutableFor(BuildTool tool, java.nio.file.@Nullable Path root) {
        for (BuildCoordinator c : buildCoordinators) {
            if (c.tool() == tool && root != null) {
                return c.trustedExecutable(root);
            }
        }
        String hostTool = com.editora.process.ExecTargets.remoteTool(root, tool.id());
        return com.editora.build.BuildExecutable.resolve(
                List.of(),
                hostTool != null ? hostTool : coordinatorHost.projectScope().buildCommand(tool, config.getSettings()),
                tool.commandExample());
    }

    private List<BuildCoordinator> createBuildCoordinators() {
        List<BuildCoordinator> list = new ArrayList<>();
        for (BuildTool tool : BuildTool.enabled()) {
            list.add(new BuildCoordinator(
                    tool,
                    coordinatorHost,
                    // Trust, the custom-task history and the build-file link live in BuildTrustOps.
                    new BuildTrustOps(
                            () -> config,
                            () -> stage,
                            this::saveTrustDecision,
                            () -> {
                                if (settingsWindow != null) {
                                    settingsWindow.refreshTrustedFolders();
                                }
                            },
                            this::requestSave,
                            file -> fileWorkflows.openPath(file)) {
                        @Override
                        public java.nio.file.@Nullable Path projectRoot() {
                            return windowProject != null ? windowProject.rootPath() : null;
                        }

                        @Override
                        public void openTasks() {
                            ToolWindow tw = buildToolWindows.get(tool);
                            if (tw != null) {
                                toolWindows.open(tw, true);
                            }
                        }

                        @Override
                        public void openConsole() {
                            if (buildOutputToolWindow != null) {
                                toolWindows.open(buildOutputToolWindow, true);
                            }
                        }

                        @Override
                        public void setToolWindowsAvailable(boolean available) {
                            ToolWindow tasks = buildToolWindows.get(tool);
                            if (tasks != null) {
                                toolWindows.setAvailable(tasks, available);
                            }
                            // The shared console is available when *any* build tool is currently detected
                            // (or once a Git/GitHub command has written a transcript tab into it).
                            refreshBuildOutputAvailability();
                            // Detection is what decides whether the project is launchable at all.
                            runConfigurations.refreshRunConfigToolbar();
                            // A JVM marker (pom/build.gradle) appearing/vanishing flips the JUnit test gutter and
                            // the project main-method gutter — re-gate every open buffer (both no-op on an
                            // unchanged flag).
                            if (tool == BuildTool.MAVEN || tool == BuildTool.GRADLE) {
                                coordinatorHost.forEachBuffer(testNavigation::applyTestGutter);
                                coordinatorHost.forEachBuffer(testNavigation::applyMainGutter);
                            }
                            // Detection is async, so an open Settings page read its status row before this
                            // landed — restate it now rather than leaving a stale "not detected".
                            if (settingsWindow != null) {
                                settingsWindow.refreshBuildToolStatus();
                            }
                        }
                    },
                    buildOutputPanel));
        }
        return list;
    }

    // --- Git / GitHub CLI transcripts (their own Output tabs) ------------------------------

    /**
     * Owner keys for the two CLI-transcript tabs. Deliberately <em>not</em> the coordinators themselves:
     * {@code github} already owns the streaming "CI" tab, and reusing it would make a {@code gh} command
     * wipe the CI log (and vice versa) — {@code started()} clears its console, a transcript must not be.
     */
    private final Object gitConsoleOwner = new Object();

    private final Object ghConsoleOwner = new Object();

    /**
     * Points each native-CLI service at its own Output tab, so the commands Editora runs on the user's
     * behalf are inspectable rather than invisible. The services decide <em>what</em> to report (git: the
     * user-initiated writes only, since status/diff re-run constantly); this only decides where it lands.
     * Both sinks are called on a service worker thread, hence the marshal.
     */
    private void installCommandLogs() {
        // A clone runs for minutes: its command and progress are shown while it runs, not once it is over.
        git.service()
                .setCommandLog(new GitConsoleLog(
                        entry -> logCliCommand(gitConsoleOwner, tr("console.tab.git"), entry),
                        argv -> {
                            buildOutputPanel.commandStarted(
                                    gitConsoleOwner, tr("console.tab.git"), argv, git::cancelNetworkCommand);
                            showCliTranscript(gitConsoleOwner);
                        },
                        (line, transientLine) ->
                                buildOutputPanel.commandProgress(gitConsoleOwner, line, transientLine)));
        github.service()
                .setCommandLog(entry ->
                        Platform.runLater(() -> logCliCommand(ghConsoleOwner, tr("console.tab.github"), entry)));
    }

    private void logCliCommand(Object owner, String tabTitle, com.editora.process.CommandLog.Entry entry) {
        buildOutputPanel.logCommand(owner, tabTitle, entry);
        showCliTranscript(GitConsoleLog.raisesConsole(entry) ? owner : null);
    }

    private void showCliTranscript(@Nullable Object owner) {
        // The console's stripe is gated on a build tool being detected; a repo with no build file still has
        // git, so the first logged command is what makes the window reachable there.
        refreshBuildOutputAvailability();
        // A Git network command (as it starts) and a Git command that failed bring their transcript forward;
        // a local command that worked does not (GitConsoleLog.raisesConsole), nor do GitHub's queries.
        if (owner == gitConsoleOwner && buildOutputToolWindow != null) {
            buildOutputPanel.selectTab(owner);
            toolWindows.open(buildOutputToolWindow);
        }
    }

    /**
     * The shared Output console is available whenever something can write into it: a detected build tool, a
     * tab already written (a finished build, a CI log), or <b>a Git repository</b> — Git and GitHub log every
     * command they run into their own transcript tabs there, and in a repo with no build file the stripe
     * button is the only way to reach them.
     */
    void refreshBuildOutputAvailability() {
        if (buildOutputToolWindow != null) {
            toolWindows.setAvailable(
                    buildOutputToolWindow, anyBuildDetected() || inGitRepo() || buildOutputPanel.hasTabs());
        }
    }

    /** Whether the active context is inside a Git repository (and Git is switched on). */
    private boolean inGitRepo() {
        return git != null && git.isAvailable();
    }

    /** Whether any enabled build tool currently has a detected marker file (drives the shared console stripe). */
    private boolean anyBuildDetected() {
        for (BuildCoordinator c : buildCoordinators) {
            if (c.isEnabled() && c.isDetected()) {
                return true;
            }
        }
        return false;
    }

    /** The coordinator for a specific build tool (for the palette gate / Settings status reads). */
    @Nullable
    BuildCoordinator buildCoordinator(BuildTool tool) {
        for (BuildCoordinator c : buildCoordinators) {
            if (c.tool() == tool) {
                return c;
            }
        }
        return null;
    }

    /** Re-detects the marker file for every build tool (Maven/npm/…) for the active context. */
    void refreshBuildTools() {
        buildCoordinators.forEach(BuildCoordinator::refresh);
    }

    /** The ids of build tools whose commands should be hidden from the palette (disabled / Simple mode). */
    private java.util.Set<String> disabledBuildToolIds() {
        java.util.Set<String> disabled = new java.util.HashSet<>();
        for (BuildCoordinator c : buildCoordinators) {
            if (!c.isEnabled()) {
                disabled.add(c.tool().id());
            }
        }
        return disabled;
    }

    /**
     * The three sources behind Search Everywhere. Commands come from the registry and cost nothing;
     * files and symbols come from the lazily-built project index, so an unscoped query is what triggers
     * the walk the first time.
     */
    final SearchEverywherePopup.Ops searchEverywhereOps = new SearchEverywhereHost(this);

    final IndexCoordinator indexCoordinator = new IndexCoordinator(
            coordinatorHost,
            IndexCoordinator.ops(
                    this::windowProjectRoot,
                    this::openAndGoto,
                    () -> config.getSettings().isSearchRespectGitignore(),
                    (file, text, done) -> historyCoordinator.recordBeforeReplace(file, text, done)));

    final TerminalCoordinator terminalCoordinator = new TerminalCoordinator(coordinatorHost, new TerminalOpsHost(this));

    final TodoCoordinator todoCoordinator = new TodoCoordinator(coordinatorHost, new TodoOpsHost(this));

    /**
     * Rewrites a single source line for a TODO tool-window edit (set priority / mark done / edit description):
     * opens the file so the change is visible, verifies the target line still reads {@code expectedLine}, then
     * replaces that paragraph with {@code newLine} as one undoable edit (marks the buffer dirty). A no-op with a
     * status when the line moved/changed since the scan (the panel re-scans via {@code afterApply}).
     */
    void applyTodoLineEdit(
            java.nio.file.Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
        fileWorkflows.openThen(
                file,
                () -> { // after the load: the shell is empty and read-only until then
                    EditorBuffer b = activeBuffer();
                    if (b == null
                            || b.getPath() == null
                            || !pathKey(b.getPath()).equals(pathKey(file))) {
                        return;
                    }
                    // Say so rather than doing nothing: a TODO in a read-only buffer is common (a .log opens in View
                    // mode, as does anything not writable on disk — i.e. exactly the vendored/generated code a scan
                    // turns up), and a menu click that produced no status, no error and no change looked like a bug.
                    if (!editing.activeEditable()) {
                        setStatus(tr("status.todo.readOnly"));
                        return;
                    }
                    org.fxmisc.richtext.CodeArea area = b.getArea();
                    int idx = navigation.areaLine(b, line - 1); // a narrowed area numbers its lines from the region
                    int paragraphs = area.getParagraphs().size();
                    if (idx < 0 || idx >= paragraphs) {
                        setStatus(tr("status.todo.lineChanged")); // the file shrank under the scan snapshot
                        if (afterApply != null) {
                            afterApply.run();
                        }
                        return;
                    }
                    String current = area.getParagraph(idx).getText();
                    if (!current.equals(expectedLine)) {
                        setStatus(tr("status.todo.lineChanged"));
                        if (afterApply != null) {
                            afterApply.run();
                        }
                        return;
                    }
                    int start = area.getAbsolutePosition(idx, 0);
                    area.replaceText(start, start + current.length(), newLine); // undoable; marks the buffer dirty
                    setStatus(tr("status.todo.edited"));
                    if (afterApply != null) {
                        afterApply.run();
                    }
                });
    }

    /** CSV/TSV grid preview feature; owns the grid panel + parse/refresh (the tool window stays here). */
    final CsvCoordinator csvCoordinator = new CsvCoordinator(coordinatorHost, new CsvOpsHost(this));

    /** Find-in-Files feature; owns the search service/panel/backend (the tool window + commands stay here). */
    final SearchCoordinator searchCoordinator = new SearchCoordinator(
            coordinatorHost,
            SearchCoordinator.ops(
                    new SearchCoordinator.Navigation(
                            () -> (windowProject != null && projectsEnabled()) ? windowProject.rootPath() : null,
                            this::openSearchMatch,
                            () -> searchToolWindow != null && toolWindows.isOpen(searchToolWindow),
                            () -> toolWindows.open(searchToolWindow, true),
                            () -> toolWindows.close(searchToolWindow)),
                    new SearchCoordinator.ReplaceSupport(
                            file -> bufferOf(tabForPath(file)),
                            fileWorkflows.loadingBuffers::contains,
                            (file, content, completion) ->
                                    historyCoordinator.recordBeforeReplace(file, content, completion),
                            file -> config.shared().documentWrites().begin(file)),
                    new SearchCoordinator.Persistence(
                            query -> config.shared().searchHistory().add(query),
                            () -> config.shared().searchHistory().getList(),
                            found -> {
                                if (settingsWindow != null) {
                                    settingsWindow.syncRipgrepStatus(found);
                                }
                            })));

    private void openSearchMatch(Path file, int line, int col, boolean focusEditor) {
        fileWorkflows.openPath(file);
        Platform.runLater(() -> {
            sessions.gotoInFile(file, line, col, focusEditor);
            // A preview keeps focus in the results so the user can continue navigating them.
            if (!focusEditor) {
                searchCoordinator.panel().focusResults();
            }
        });
    }

    /** This window's deployments: running configurations on a host, in a copy of the project there. */
    final DeploymentCoordinator deployments = new DeploymentCoordinator(new DeploymentHost(this));

    /** Run-a-file feature; owns the run service/console panel (the tool window + commands stay here). */
    final RunCoordinator runCoordinator = new RunCoordinator(coordinatorHost, new RunOpsHost(this));

    /** Builds IntelliJ-style Test Results from recognized build-tool test runs. */
    final TestRunCoordinator testRunCoordinator = new TestRunCoordinator(coordinatorHost, new TestRunOpsHost(this));

    /** LSP UI/lifecycle integration; the shared manager remains here because DAP and MCP also use it. */
    final LspCoordinator lspCoordinator = new LspCoordinator(coordinatorHost, lspManager, new LspOpsHost(this));

    /** Personal Notes feature; owns the panel/jump-pickers/persistence. Built in {@link #init} (needs config). */
    NotesCoordinator notesCoordinator;

    // --- HTTP Client (.http request runner + response tool window) -----------------------------------

    /** The HTTP Client feature; see {@link HttpClientCoordinator}. Takes the shared host + http-specific ops. */
    final HttpClientCoordinator httpClient = new HttpClientCoordinator(coordinatorHost, new HttpClientOpsHost(this));

    /** The {@code .http} editor's unresolved-variable marks and {@code {{} completion. */
    final HttpVariableHints httpVariableHints = new HttpVariableHints(new HttpVariableHints.Source() {
        @Override
        public String environment(EditorBuffer buffer) {
            return httpClient.selectedEnvironment(buffer);
        }

        @Override
        public java.nio.file.@Nullable Path envDir(EditorBuffer buffer) {
            return httpClient.envDirFor(buffer);
        }

        @Override
        public void choose(EditorBuffer buffer, String environment) {
            httpClient.chooseEnvironment(buffer, environment); // the "Use environment dev" quick fix
        }
    });

    {
        httpClient.setEnvironmentListener(httpVariableHints::refresh);
        httpClient.setEnvironmentShownListener(this::refreshHttpEnvSegment);
    }

    /** Shows the active {@code .http} file's environment in the status bar ("Env: dev"), or hides the segment. */
    void refreshHttpEnvSegment() {
        if (statusBar == null) {
            return;
        }
        EditorBuffer b = activeBuffer();
        String label = httpClient.environmentLabel(b);
        statusBar.setHttpEnvironment(
                label, label != null && httpClient.selectedEnvironment(b).isEmpty());
    }

    // --- AI Agent (an embedded ACP agent — Claude Code etc. — in the chat tool window) -------------

    final AgentCoordinator agentCoordinator = new AgentCoordinator(coordinatorHost, new AgentOpsHost(this));

    /** Applies the AI Agent feature gate: tears down a disabled agent + re-gates its tool window. */
    void applyAgentSupport() {
        agentCoordinator.applySupport();
        if (agentToolWindow != null) {
            // Keep the stripe hidden while the panel is popped out into its own window (else re-opening the
            // dock would yank the panel node out of the floating window).
            toolWindows.setAvailable(agentToolWindow, agentCoordinator.isEnabled() && !agentCoordinator.isDetached());
        }
    }

    // --- AI actions (direct Anthropic API: commit message / explain / rewrite) ---------------------

    final AiCoordinator aiCoordinator = new AiCoordinator(coordinatorHost, new AiOpsHost(this));

    // --- Doctor (external-tool health screen, a Welcome-style tab; see doctor/ + DoctorCoordinator) ---

    final DoctorCoordinator doctorCoordinator = new DoctorCoordinator(coordinatorHost, new DoctorOpsHost(this));

    // --- MCP server (loopback HTTP, exposes editor state + commands to an LLM agent) --------------

    private boolean mcpEnabled() {
        // Simple UI mode disables the MCP server too (without changing the saved setting).
        return config.getSettings().isMcpSupport() && !chrome.simpleModeActive();
    }

    /**
     * Reconciles the MCP server with its setting (mirrors {@link HttpClientCoordinator#applySupport}). Starts the
     * single app-wide loopback server when first enabled (this window becomes its bridge), or stops it
     * when disabled. Runs at startup and on every settings apply.
     */
    void applyMcpSupport() {
        boolean on = mcpEnabled();
        if (on && mcpServer == null) {
            try {
                com.editora.mcp.McpServer s = new com.editora.mcp.McpServer(this, config.getConfigDir());
                s.start();
                mcpServer = s;
                mcpOwner = this;
            } catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING, "MCP server failed to start", e);
                setStatus(tr("status.mcp.failed"));
            }
        } else if (!on && mcpServer != null && mcpOwner == this) {
            mcpServer.stop();
            mcpServer = null;
            mcpOwner = null;
        }
        statusBar.setMcpRunning(mcpServer != null && mcpServer.isRunning());
    }

    /**
     * A security-notice confirmation shown before the MCP server is enabled (from the palette toggle and
     * the Settings checkbox). Returns true to proceed. Mirrors {@link #confirmEnablePlugin}.
     */
    boolean confirmEnableMcp() {
        ButtonType enable = Dialogs.action(tr("confirm.action.enable"));
        ButtonType cancel = Dialogs.cancel();
        Alert confirm = Dialogs.styled(new Alert(Alert.AlertType.WARNING, tr("dialog.mcp.enableBody"), enable, cancel));
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.mcp.enableTitle"));
        confirm.setHeaderText(tr("dialog.mcp.enableHeader"));
        UiZoom.minWidth(confirm.getDialogPane(), 480);
        return confirm.showAndWait().orElse(cancel) == enable;
    }

    /** Opens Settings on the Workspace page, where the trusted-folder list lives ({@code workspace.manageTrust}). */
    void showTrustedFolders() {
        settingsWindow.refreshTrustedFolders();
        settingsWindow.showWorkspace(stage);
    }

    /** Persists a trust change and lets every window re-resolve what its project may now override. */
    void saveTrustDecision() {
        config.saveTrust();
        if (windowManager != null) {
            windowManager.broadcastTrustChanged();
        }
    }

    /** Another window (or this one) changed folder trust: stop honouring overrides that lost it. */
    void trustChanged() {
        lspCoordinator.reloadProjectSettings();
    }

    /** This window rewrote a file's notes, bookmarks or breakpoints: the other windows re-read them. */
    void marksStored(MarkMerge.Kind kind, java.util.Map<String, ?> bucket, @Nullable String fileKey) {
        if (windowManager != null) {
            windowManager.broadcastMarksChanged(this, new MarkMerge.Change(kind, bucket, fileKey));
        }
        refreshMapCardGuttersIfBuilt();
    }

    /** A breakpoint or a bookmark was stored: the Map cards' gutters show it (Map gaps round 3, item 1). */
    private void refreshMapCardGuttersIfBuilt() {
        if (projectPanel != null) {
            projectPanel.refreshCardGutters();
        }
    }

    /** Another window rewrote a file's notes, bookmarks or breakpoints (see {@link MarkMerge.Change}). */
    void marksChangedElsewhere(MarkMerge.Change change) {
        switch (change.kind()) {
            case NOTES -> notesCoordinator.storeChangedElsewhere(change.bucket(), change.fileKey());
            case BOOKMARKS -> bookmarkCoordinator.storeChangedElsewhere(change.bucket(), change.fileKey());
            case BREAKPOINTS -> debugCoordinator.breakpointsChangedElsewhere(change.bucket(), change.fileKey());
        }
        refreshMapCardGuttersIfBuilt();
    }

    /**
     * Revokes trust for the active window's project root ({@code workspace.revokeTrust}) — the quick undo for
     * a folder trusted by mistake, without hunting for it in the Settings list. Only an <em>explicit</em>
     * entry for this exact root is dropped; trust inherited from an ancestor has to be revoked at that
     * ancestor, which the status message says so the user is never told "revoked" while it still runs.
     */
    void revokeTrustForActiveRoot() {
        java.nio.file.Path root = windowProject != null ? windowProject.rootPath() : null;
        if (root == null) {
            setStatus(tr("status.trust.noProject"));
            return;
        }
        config.getTrustStore().revoke(root);
        saveTrustDecision();
        settingsWindow.refreshTrustedFolders();
        setStatus(tr(
                config.getTrustStore().isTrusted(root) ? "status.trust.revokedButInherited" : "status.trust.revoked",
                root.getFileName() == null
                        ? root.toString()
                        : root.getFileName().toString()));
    }

    /**
     * The HTTP Client's secrets gate: a {@code .http} request in {@code file} that reads {@code reads} (the secret
     * sources it resolves, as written — {@code $env TOKEN}, {@code $dotenv KEY}) runs only from a trusted folder,
     * the same trust store as build wrappers. Asked about the open project when the file is inside it, else the
     * file's own folder — both canonical, the way the trust store compares them (see {@link HttpSecretGate#trustRoot}).
     */
    boolean confirmHttpSecretReads(Path file, List<String> hosts, List<String> reads) {
        Path dir = com.editora.config.PathKeys.canonical(
                java.util.Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file is in a folder"));
        if (config.getTrustStore().isTrusted(dir)) {
            return true;
        }
        Path root = HttpSecretGate.trustRoot(file, projectRoot());
        TrustPrompts.Prompt prompt = TrustPrompts.httpSecrets(stage, root, reads, hosts);
        if (prompt.alert().showAndWait().orElse(Dialogs.cancelOf(prompt.alert())) != prompt.trust()) {
            return false;
        }
        config.getTrustStore().trust(root);
        saveTrustDecision(); // durable: a security decision must survive a crash
        return true;
    }

    /** Whether the response handlers of {@code file} may run: its folder is trusted, or the user trusts it now. */
    boolean confirmHttpScripts(Path file) {
        Path dir = com.editora.config.PathKeys.canonical(
                java.util.Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file is in a folder"));
        if (config.getTrustStore().isTrusted(dir)) {
            return true;
        }
        Path root = HttpSecretGate.trustRoot(file, projectRoot());
        TrustPrompts.Prompt prompt = TrustPrompts.httpScripts(stage, root, file);
        if (prompt.alert().showAndWait().orElse(Dialogs.cancelOf(prompt.alert())) != prompt.trust()) {
            return false;
        }
        config.getTrustStore().trust(root);
        saveTrustDecision(); // durable: a security decision must survive a crash
        return true;
    }

    /**
     * Handles the MCP server when this window (its owner) closes (called from {@link #disposeWindow}). If
     * another window is still open, ownership <b>moves</b> to it — the server is re-bound to that window's
     * bridge (same port/token/endpoint), so a connected agent isn't dropped and the survivor's indicator is
     * refreshed. Only when this is the last window is the server actually stopped (#463).
     */
    private void stopMcpIfOwner() {
        if (mcpServer == null || mcpOwner != this) {
            return;
        }
        MainController survivor = windowManager != null ? windowManager.otherLiveController(this) : null;
        if (survivor != null) {
            mcpServer.rebind(survivor); // move ownership; keep the server running for the surviving window
            mcpOwner = survivor;
            survivor.statusBar.setMcpRunning(mcpServer.isRunning());
        } else {
            mcpServer.stop(); // last window — really stop + remove the endpoint file
            mcpServer = null;
            mcpOwner = null;
        }
    }

    /** Runs {@code action} only when the MCP server is enabled; otherwise reports it (no-op). */
    void ifMcp(Runnable action) {
        if (mcpEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.mcpDisabled"));
        }
    }

    /** {@code view.toggleMcp}: flip the feature (with a security notice before enabling), re-apply, re-sync. */
    void toggleMcpSupport() {
        Settings s = config.getSettings();
        boolean turningOn = !s.isMcpSupport();
        if (turningOn && !confirmEnableMcp()) {
            return; // user declined the security notice; leave the server off
        }
        s.setMcpSupport(turningOn);
        config.save();
        applyMcpSupport();
        if (settingsWindow != null) {
            settingsWindow.syncMcpCheck();
        }
        setStatus(tr("status.toggle.mcp", tr(s.isMcpSupport() ? "common.on" : "common.off")));
    }

    /** Copies a ready-to-paste {@code claude mcp add} command (endpoint URL + bearer token) to the clipboard. */
    void copyMcpEndpoint() {
        if (mcpServer == null || !mcpServer.isRunning()) {
            setStatus(tr("status.mcp.notRunning"));
            return;
        }
        String cmd = "claude mcp add --transport http editora " + mcpServer.url() + " --header \"Authorization: Bearer "
                + mcpServer.token() + "\"";
        ClipboardContent cc = new ClipboardContent();
        cc.putString(cmd);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        setStatus(tr("status.mcp.endpointCopied"));
    }

    // --- McpBridge: marshals editor reads onto the FX thread for the off-FX HTTP worker -----------

    /** The .http ▶ tooltip: "Run this request (C-M-r) · Run all requests: C-M-S-enter", with the keymap's chords. */
    static String httpRunTooltip(com.editora.command.KeymapManager keymap) {
        String run = keymap == null ? null : keymap.displayChord("http.runRequest");
        String all = keymap == null ? null : keymap.displayChord("http.runFile");
        if (run != null && !run.isEmpty() && all != null && !all.isEmpty()) {
            return tr("editmenu.runHttpRequest.tip.chords", run, all);
        }
        return ChordHint.tr(keymap, "editmenu.runHttpRequest.tip", "http.runRequest");
    }

    /** True when {@code b}'s file is on the local filesystem (or untitled) — the gate for every feature
     *  that shells out to a local process (LSP/DAP/git/run/HTTP). Remote (SFTP) buffers are text-only. */
    boolean isLocalBuffer(EditorBuffer b) {
        return b != null && com.editora.vfs.Vfs.isLocal(b.getPath());
    }

    // --- LSP (Language Server Protocol) integration --------------------------------------------

    boolean lspEnabled() {
        // Simple UI mode disables LSP (servers, diagnostics, completion, navigation); saved setting unchanged.
        return config.getSettings().isLspSupport() && !chrome.simpleModeActive();
    }

    /** Thin LspManager diagnostics callback — delegates to {@link LspCoordinator#onDiagnostics} (kept here
     *  only to avoid an illegal forward reference at the manager's field initializer; see {@link #lspManager}). */
    private void onLspDiagnostics(Path file, java.util.List<com.editora.text.LspDiagnostic> diagnostics) {
        lspCoordinator.onDiagnostics(file, diagnostics);
    }

    /** Thin LspManager status callback — delegates to {@link LspCoordinator#onServerStatus} (kept here
     *  only to avoid an illegal forward reference at the manager's field initializer; see {@link #lspManager}). */
    private void onLspServerStatus(String type, String message) {
        lspCoordinator.onServerStatus(type, message);
    }

    /** Runs {@code action} only when LSP is enabled; otherwise reports it (no-op command/key). */
    void ifLsp(Runnable action) {
        if (lspEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.lspDisabled"));
        }
    }

    void toggleLsp() {
        Settings s = config.getSettings();
        s.setLspSupport(!s.isLspSupport());
        requestSave();
        lspCoordinator.applySupport();
        if (settingsWindow != null) {
            settingsWindow.syncLspCheck();
        }
        setStatus(tr("status.toggle.lsp", tr(s.isLspSupport() ? "common.on" : "common.off")));
    }

    /** Opens {@code file} (if needed) and moves the caret to a 0-based LSP line/column. */
    void openAndGoto(@Nullable Path file, int line0, int col0) {
        NavigationHistory.Location origin = navigation.navigating ? null : navigation.captureCurrent();
        fileWorkflows.openPath(file, true); // a jump says no "Already open" (eighth review EQ5)
        Platform.runLater(() -> navigation.landJump(origin, file, line0, col0));
    }

    /** The open buffer for {@code target} (canonical-path match), or null if not open. */
    @Nullable
    EditorBuffer openBufferFor(Path target) {
        return OpenBufferLifecycle.bufferForPath(editorArea, MainController::bufferOf, target);
    }

    /** Open buffers in this window whose path is exactly {@code target} or a descendant of it. */
    void applyEditorConfigLocal() {
        editorSettings.applyEditorConfigSupport();
    }

    List<EditorBuffer> buffersAtOrUnderLocal(Path target) {
        return OpenBufferLifecycle.atOrUnder(editorArea, MainController::bufferOf, target);
    }

    /**
     * Opens {@code target} (assumed not already open — callers check {@link #openBufferFor} first) as a new,
     * unfocused background tab, reading the file on the calling (FX) thread. Only for the two callers that must
     * answer synchronously (a merge pane's save-before-close, the workspace-edit transaction's fallback for a
     * tab closed mid-transaction); everything else goes through {@link #openBackgroundBufferAsync}.
     */
    @Nullable
    EditorBuffer openBackgroundBuffer(Path target) {
        Tab placeholder = tabForPath(target);
        if (RestorePlaceholder.of(placeholder) != null) {
            // The file is open, as a restored tab that has not been built yet: build it (read on the caller's
            // thread, as below) rather than open a second tab on the same file.
            return sessions.materializeLoaded(placeholder);
        }
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(target);
            FileWorkflowCoordinator.PreparedLoad load =
                    fileWorkflows.prepareLoad(target, false).preparedFor(buffer);
            fileWorkflows.applyPreparedLoad(buffer, load);
            fileWorkflows.notePerfContentLoaded(buffer);
            return attachBackground(buffer, load.editorConfig());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Adds a loaded {@code buffer} as an unfocused tab with its stored per-file state, like any other open:
     * without the marks the next mark change would persist over them, and callers gate on the read-only pin.
     * {@code editorConfig} is the load's resolved {@code .editorconfig} result: no ancestor walk on FX again.
     */
    private EditorBuffer attachBackground(
            EditorBuffer buffer, com.editora.editorconfig.EditorConfigProperties editorConfig) {
        Tab tab = addBuffer(buffer, false, editorConfig); // background: keep the caller's current tab focused
        restorePerFileState(buffer);
        previews.restoreMarkdownMode(buffer);
        updateTabMeta(tab, buffer);
        return buffer;
    }

    /**
     * Workspace edits, diff applies and agent writes may touch unopened files. The buffer is created here (on
     * FX) so the load worker can read, decode and split the file into its paragraphs, as {@code openPath} does;
     * the FX thread is left with the document swap and the tab. The shell is disposed when the read fails, the
     * file is binary, or the file was opened by other means while the read ran.
     */
    void openBackgroundBufferAsync(Path target, java.util.function.Consumer<@Nullable EditorBuffer> done) {
        EditorBuffer existing = openBufferFor(target);
        if (existing != null) {
            done.accept(existing);
            return;
        }
        Tab placeholder = tabForPath(target);
        if (RestorePlaceholder.of(placeholder) != null) {
            sessions.materialize(placeholder, false, done); // its own read, on the same executor
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(target);
        fileWorkflows.fileLoadExecutor.execute(() -> {
            try {
                FileWorkflowCoordinator.PreparedLoad load =
                        fileWorkflows.prepareLoad(target, false).preparedFor(buffer);
                Platform.runLater(() -> {
                    if (sessionClosed || load.binary()) { // never attach a buffer to a disposed window
                        buffer.dispose();
                        done.accept(null);
                        return;
                    }
                    EditorBuffer openedWhileLoading = openBufferFor(target);
                    if (openedWhileLoading != null) {
                        buffer.dispose();
                        done.accept(openedWhileLoading);
                        return;
                    }
                    fileWorkflows.applyPreparedLoad(buffer, load);
                    done.accept(attachBackground(buffer, load.editorConfig()));
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    buffer.dispose();
                    done.accept(null);
                });
            }
        });
    }
    // --- GitHub tool window ----------------------------------------------------------------------

    // --- Git Log / History tool window -----------------------------------------------------------

    // --- TODO / highlight patterns ---------------------------------------------------------------

    // --- Markdown lint ---------------------------------------------------------------------------

    // --- Local File History --------------------------------------------------------------------

    void findNextMatch() {
        if (findBar.isShown()) {
            findBar.findNext();
        } else {
            findBar.show(false);
        }
    }

    void findPreviousMatch() {
        if (findBar.isShown()) {
            findBar.findPrevious();
        } else {
            findBar.show(true);
        }
    }

    void findReplaceCurrentMatch() {
        if (findBar.isShown()) {
            findBar.replaceCurrentMatch();
        } else {
            findBar.show(false);
            findBar.focusReplace();
        }
    }

    void findReplaceAllMatches() {
        if (findBar.isShown()) {
            findBar.replaceAllMatches();
        } else {
            findBar.show(false);
            findBar.focusReplace();
        }
    }

    /** Invalidates this window's pending saves selected by a repository working-tree mutation. */
    void invalidatePendingGitWritesLocal(Path root, List<String> pathspecs) {
        OpenBufferLifecycle.invalidateGitWrites(editorArea, MainController::bufferOf, fileWorkflows, root, pathspecs);
    }

    /** After a branch switch/pull, asynchronously reload clean open buffers whose files changed on disk. */
    void reloadAllFromDiskSilently() {
        OpenBufferLifecycle.reloadChanged(editorArea, MainController::bufferOf, fileWorkflows, lspCoordinator);
    }

    private void setupToolbar() {
        toolbar.install();
        // The customizable icon cluster is built data-driven from the persisted layout; the fixed tail
        // (project group + Settings) is refilled after each rebuild by appendFixedTail().
        toolbarCoordinator = new ToolbarCoordinator(coordinatorHost, registry, keymap, new ToolbarOpsHost(this));
        toolbarCoordinator.rebuild();
        toolbar.initialState();
    }

    /**
     * Opens the documentation site in the user's browser.
     *
     * <p>Versioned, via {@link com.editora.AppInfo#releaseVersion()} — the same path the command palette's
     * per-command help uses — so a running build lands on the docs for what it actually is rather than on
     * whatever is current, which for a snapshot build would describe features it does not have.
     */
    void openDocumentation() {
        openExternalUrl(com.editora.AppInfo.docsUrl());
    }

    /** command id -> the chord to show for it, in the active keymap's notation (see KeymapManager.displayChords). */
    java.util.Map<String, String> invertBindings() {
        return keymap.displayChords();
    }

    @FXML
    void onSplitVertical() {
        toggleSplit(EditorBuffer.Split.SIDE_BY_SIDE);
    }

    @FXML
    void onSplitHorizontal() {
        toggleSplit(EditorBuffer.Split.STACKED);
    }

    private void toggleSplit(EditorBuffer.Split orientation) {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        buffer.toggleSplit(orientation);
        toolbar.refreshSplitButtons();
        setStatus(tr(buffer.getSplit() == EditorBuffer.Split.NONE ? "status.editorUnsplit" : "status.editorSplit"));
    }

    void unsplit() {
        EditorBuffer buffer = activeBuffer();
        boolean bufferWasSplit = buffer != null && buffer.getSplit() != EditorBuffer.Split.NONE;
        boolean groupsWereSplit = editorArea.unsplit();
        if (bufferWasSplit && buffer != null) {
            buffer.setSplit(EditorBuffer.Split.NONE);
        }
        if (!groupsWereSplit && !bufferWasSplit) {
            toolWindows.closeAllOpen();
        }
        toolbar.refreshSplitButtons();
        setStatus(tr(groupsWereSplit ? "status.editorGroupsMerged" : "status.editorUnsplit"));
    }

    /** Moves the active tab into a new group; refuses when that would immediately empty and collapse its group. */
    void splitEditorGroup(Orientation orientation) {
        if (editorArea.splitActive(orientation)) {
            setStatus(tr("status.editorGroupSplit"));
        } else {
            setStatus(tr("status.editorGroupSplitUnavailable"));
        }
    }

    /** Moves the active tab to the next editor group, splitting first if the area is not split yet. */
    void moveTabToNextGroup() {
        setStatus(tr(editorArea.moveActiveToNextGroup() ? "status.editorGroupMoved" : "status.editorGroupNone"));
    }

    void focusNextEditorGroup() {
        if (!editorArea.focusNextGroup()) {
            setStatus(tr("status.editorGroupNotSplit"));
        } else {
            TabFocus.focus(activeBuffer()); // its editor, not the tab's container (tenth review AO1)
        }
    }

    /** Merges every editor group back into one. */
    void unsplitEditorGroups() {
        setStatus(tr(editorArea.unsplit() ? "status.editorGroupsMerged" : "status.editorGroupNotSplit"));
    }

    /** A startup file to open, with an optional 1-based line/column ({@code 0} = unspecified). */
    public record OpenTarget(Path file, int line, int column) {}

    /**
     * Applies the <b>session-only chrome flags</b> — {@code --simple}, {@code --zen}, {@code --expert} — and is
     * called during window construction <em>before the stage is shown</em> ({@code WindowManager.buildWindow}).
     *
     * <p><b>Why not with the other CLI actions:</b> these only shape chrome and depend on nothing the session
     * restore produces, so they must not ride {@link #pendingAfterRestore} alongside the file targets. That
     * runnable fires only once the pulse-paced restore has finished, which left the window on screen in full
     * chrome for the whole restore and then visibly stripped it — a flash that grew with the number of
     * restored files. Applying them pre-{@code show()} means the first frame is already correct.
     *
     * <p>Running before the session exists is safe: there are no buffers yet, and every file restored
     * afterwards picks the mode up because {@link #addBuffer} runs {@link #applyViewSettings} per buffer.
     */
    public void applyStartupChrome(boolean zen, boolean expert, boolean simple) {
        applyStartupChrome(zen, expert, simple, false);
    }

    /** As above, with the session-only standalone diff workspace taking precedence over other focus flags. */
    public void applyStartupChrome(boolean zen, boolean expert, boolean simple, boolean diffUi) {
        if (simple) {
            // --simple: a session-only override (doesn't change the saved setting).
            chrome.cliSimpleOverride = true;
            editorSettings.applyViewSettingsToAllBuffers(config.getSettings());
            settingsWindow.syncSimpleModeCheck();
        }
        // --zen / --expert: session-only overrides, like --simple. If both were given, Expert wins — the two
        // are mutually exclusive. (applyCliFocusMode stashes the restored tool windows for the quit-time
        // restore, so it must run after init's toolWindows.restore() — which buildWindow guarantees.)
        if (diffUi) {
            chrome.applyCliDiffUiMode();
        } else if (expert) {
            chrome.applyCliFocusMode(true);
        } else if (zen) {
            chrome.applyCliFocusMode(false);
        }
    }

    public void setStatus(@Nullable String message) {
        statusBar.setMessage(message);
    }

    /**
     * Reports a failure: the echo shows it in the danger colour and it stays flagged as unread until the user
     * opens the message log.
     *
     * <p>Ordinary {@link #setStatus} messages replace one another and are then gone, which is right for
     * "Saved" and wrong for "could not save" — a background failure landing while someone is typing is
     * overwritten a moment later with nothing left to notice.
     */
    public void setError(@Nullable String message) {
        statusBar.setMessage(message, MessageLog.Severity.ERROR);
    }

    @Nullable
    EditorBuffer activeBuffer() {
        return bufferOf(editorArea.selectedTab());
    }

    // --- Undo History popup (the QuickOpen mirror of the Undo History tool window) ---

    /** Checkpoints of the active buffer, newest-first, seeding a baseline when empty (like the panel). */
    java.util.List<com.editora.editor.undo.UndoHistory.Checkpoint> undoHistoryCheckpoints() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return java.util.List.of();
        }
        if (b.getUndoHistory().isEmpty()) {
            b.captureUndoCheckpoint(); // baseline = the current state, so there's always something to pick
        }
        return b.getUndoHistory().entriesNewestFirst();
    }

    void restoreUndoCheckpoint(com.editora.editor.undo.UndoHistory.Checkpoint c) {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.restoreUndoCheckpoint(c);
        }
    }

    @Nullable
    CodeArea activeArea() {
        EditorBuffer buffer = activeBuffer();
        return buffer == null ? null : buffer.getFocusedArea();
    }

    Tab addBuffer(EditorBuffer buffer) {
        return addBuffer(buffer, true);
    }

    /** Adds a tab for {@code buffer}, appended to the strip; selects and focuses it when {@code select}. */
    Tab addBuffer(EditorBuffer buffer, boolean select) {
        return addBuffer(buffer, select, true);
    }

    /**
     * Adds a tab, optionally resolving path-dependent settings. Loading shells pass false: disk-side
     * preparation resolves EditorConfig once in the background and applies that immutable result before the
     * document insertion.
     */
    Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
        return addBuffer(
                buffer, select, resolvePathSettings ? null : com.editora.editorconfig.EditorConfigProperties.EMPTY);
    }

    /** Adds a tab with the file's {@code .editorconfig} result in hand ({@code null}: resolve it here, an
     *  ancestor-directory walk on FX). A load prepared on the worker passes its result: one walk, off FX. */
    Tab addBuffer(
            EditorBuffer buffer,
            boolean select,
            com.editora.editorconfig.@Nullable EditorConfigProperties editorConfig) {
        wireBuffer(buffer, editorConfig);
        Tab tab = addContentTab(buffer, false); // added to the strip; selected below (focus the area, not the node)
        attachBufferTab(tab, buffer);
        if (select) {
            editorArea.select(tab);
            buffer.getArea().requestFocus();
        }
        return tab;
    }

    /**
     * Gives a restored placeholder tab its editor: the wiring of {@link #addBuffer}, into the {@link Tab} that
     * already holds the file's place (pin, group, MRU slot and selection stay). {@code shown}: the tab is on
     * screen, so the editor attaches now; otherwise it stays detached ({@link DeferredTabContent}) and
     * unhighlighted ({@link EditorBuffer#setHighlightDeferred}) until the tab is first selected.
     */
    Tab attachRestoredBuffer(Tab tab, EditorBuffer buffer, boolean shown) {
        wireBuffer(buffer, com.editora.editorconfig.EditorConfigProperties.EMPTY); // prepared on the worker
        if (!shown) {
            DeferredTabContent.defer(buffer.getNode()); // filled in the background, laid out when shown
        }
        tab.setContent(null); // the placeholder's empty node: a deferred attach only fills an empty tab
        DeferredTabContent.install(tab, buffer.getNode());
        if (!shown) {
            // Registered after install, which is what makes the tab deferred: before it, whenShown runs at once.
            buffer.setHighlightDeferred(true);
            DeferredTabContent.whenShown(tab, () -> buffer.setHighlightDeferred(false));
        }
        tab.setUserData(buffer);
        attachBufferTab(tab, buffer);
        // What the tabs listener does for a tab added in the background: the placeholder was the tab that
        // was added, so the buffer gets it here.
        if (!shown) {
            buffer.setRenderingActive(false);
        }
        recovery.track(buffer);
        DeferredTabContent.arm(tab);
        return tab;
    }

    /** A restored file's placeholder tab: title, icon, pin, no editor until {@link #attachRestoredBuffer}. */
    Tab addPlaceholderTab(RestorePlaceholder placeholder) {
        Tab tab = addContentTab(placeholder, false);
        if (placeholder.entry().isPinned()) {
            pinned.add(tab);
            TabHeaders.toggleClass(tab, "pinned", true);
            tabHeaders.contentHeader(tab, placeholder); // again, now that the pin is known
        }
        tab.setTooltip(TabHeaders.tooltip(placeholder.path()));
        // The tab menu is a buffer's; opening it on a placeholder builds the editor first.
        tab.setContextMenu(LazyContextMenu.of(menu -> {
            EditorBuffer built = ensureBuffer(tab);
            if (built != null) {
                TabContextMenu.build(this, tab, built, menu);
            }
        }));
        return tab;
    }

    /**
     * The buffer in {@code tab}, built now when the tab is still a restore placeholder; {@code null} for a
     * viewer or the Welcome page. For callers that need the editor itself rather than the file on disk.
     */
    @Nullable
    EditorBuffer ensureBuffer(@Nullable Tab tab) {
        EditorBuffer buffer = bufferOf(tab);
        return buffer != null ? buffer : sessions.materialize(tab, false);
    }

    /** Everything a buffer is wired to before it has a tab: handlers, settings, feature coordinators.
     *  {@code editorConfig} null: resolve the file's {@code .editorconfig} here, an ancestor walk on FX. */
    private void wireBuffer(
            EditorBuffer buffer, com.editora.editorconfig.@Nullable EditorConfigProperties editorConfig) {
        // Spell checking: share the user dictionary + persist "Add to Dictionary" (before applyViewSettings,
        // which sets the per-file language and enables checking).
        editorSettings.spell().wire(buffer);
        editorSettings.applyViewSettings(buffer, editorConfig);
        buffer.getFoldManager().setOnFoldStateChanged(() -> persistFolds(buffer));
        buffer.setOnBookmarksChanged(() -> bookmarkCoordinator.schedulePersistBookmarks(buffer));
        buffer.setBookmarkToggleRequest(bookmarkCoordinator::onBookmarkToggleRequest);
        buffer.setOnNotesChanged(() -> notesCoordinator.schedulePersistNotes(buffer));
        buffer.setOnNarrowChanged(() -> {
            editing.afterNarrowChanged(buffer);
            if (structurePanel != null) {
                structurePanel.narrowChanged(buffer); // its Widen / Defun / Fold segments mirror the buffer
            }
        });
        NoUndoGuard.install(buffer, noUndoGuard);
        buffer.setNoteMarkerClick(notesCoordinator::onNoteMarkerClick);
        buffer.setGutterBlameClick(git::onGutterBlameClick);
        todoCoordinator.applyToBuffer(buffer); // push the compiled TODO/highlight matcher (on by default)
        // No-project window: the TODO window shows only the active tab and hides while it has no TODOs.
        buffer.setOnTodoMarksChanged(() -> {
            if (activeBuffer() == buffer) {
                updateTodoToolWindow();
                todoCoordinator.activeBufferTodosChanged(buffer);
            }
        });
        // Refresh the Run button when this buffer's runnable status flips (only acts if it's active).
        buffer.setOnRunnableChanged(() -> {
            if (activeBuffer() == buffer) {
                updateRunButton();
            }
        });
        buffer.addLanguageListener(
                () -> { // a picked language or a typed shebang: the status bar says so at once
                    // .editorconfig rules depend on the language (a shell script keeps LF and no BOM).
                    editorSettings.applyEditorConfig(buffer);
                    if (activeBuffer() == buffer) {
                        statusBar.refresh();
                    }
                    Tab tab = tabFor(buffer);
                    if (tab != null) {
                        updateTabMeta(tab, buffer); // the tab's file-type icon follows the language
                    }
                });
        buffer.setRunHandler(runCoordinator::runActiveFile); // "Run File" editor right-click item (runnable files)
        boolean local = isLocalBuffer(buffer);
        // A remote (SFTP) file runs on its host; whether that host may run commands is asked at launch, and a
        // refusal is reported then.
        buffer.setRunEnabled(local || com.editora.vfs.Vfs.isRemote(buffer.getPath()));
        buffer.setAdminEditAvailable(
                fileWorkflows.elevationAvailable() && local); // "Edit as Administrator" on a locked file
        // .http: the request ▶, the editor menu's Run All Requests (active buffer), the running request's ■
        buffer.setHttpHooks(new com.editora.editor.http.HttpHooks(
                line -> httpClient.runRequest(buffer, line),
                httpClient::runFile,
                () -> httpClient.cancelRun(buffer),
                () -> httpRunTooltip(keymap),
                com.editora.editor.http.HttpHooks.NO_LINT,
                null));
        buffer.setHttpDebugHandler(line -> httpClient.debugRequest(buffer, line)); // editor menu → Debug Request
        httpVariableHints.wire(buffer); // {{variable}} squiggles + {{ completion (on for .http buffers)
        JsonLinesMarks.wire(buffer); // a JSON Lines line that is not one JSON value: squiggled
        buffer.setHttpEnabled(httpClient.isEnabled() && local);
        buffer.setMakeRunHandler(target -> runCoordinator.runMakeTarget(buffer, target)); // Makefile target ▶
        buffer.setTestRunHandler(t -> testNavigation.runSingleTest(buffer, t)); // JUnit → build tool; Python → pytest
        buffer.setTestDebugHandler(testNavigation::debugSingleTest); // editor menu → suspended test + debugger attach
        testNavigation.applyTestGutter(
                buffer); // gated by the Test Runner feature + a detected JVM (Maven/Gradle) project
        buffer.setMainRunHandler(m -> runCoordinator.runMainClassNamed(m.fqn())); // project main() ▶ → run
        buffer.setMainDebugHandler(m -> debugCoordinator.debugMainClassNamed(m.fqn())); // editor menu → debug
        testNavigation.applyMainGutter(buffer); // gated by not-Simple + local + JVM project + Java run/debug available
        debugCoordinator.wireBuffer(buffer);
        buffer.setAddNoteHandler(notesCoordinator::addNoteFromContext);
        buffer.setNotesEnabled(notesCoordinator.isEnabled());
        java.util.function.Function<Path, @Nullable EditorBuffer> open = this::openBufferFor;
        buffer.setOpenUrlHandler(u -> HeadingLinks.open(
                u, buffer, windowProjectRoot(), hostServices, fileWorkflows, open, this::openAndGoto, this::setStatus));
        buffer.setAiActionHandlers(aiCoordinator::explainSelection, aiCoordinator::rewriteSelection); // AI sel. bar
        buffer.setTableFileExporter(previews::exportMarkdownTableFile); // Markdown table → CSV/Excel/ODS file
        buffer.setInsertTableHandler(previews::markdownInsertTable); // Markdown format-bar "insert table" button
        buffer.setInsertTypstTableHandler(() -> previews.showTableSizePicker(buffer::insertTypstTable)); // Typst #table
        buffer.setTypstImageHandler(() -> editing.insertImageFromChooser(buffer)); // Typst "Insert Image" menu
        buffer.previewMenu().setExportPng(typst::exportPng); // Typst preview right-click → PNG
        buffer.previewMenu().setExportSvg(typst::exportSvg); // Typst preview right-click → SVG
        htmlPreview.wireBuffer(buffer); // HTML Live Preview: settled edits reload a served site; globe follows language
        buffer.setFormatBarEnabled(config.getSettings().isMarkdownFormatBar());
        buffer.previewMenu().setExportPdf(exports::exportPreviewPdf); // preview right-click menu
        buffer.previewMenu().setPrint(exports::printPreview);
        buffer.setPomViewToggleHandler(previews::togglePomView); // pom preview right-click: summary ⇄ XML tree
        buffer.previewMenu().setExportDocx(exports::exportPreviewDocx); // preview → MS Word
        buffer.previewMenu().setExportOdt(exports::exportPreviewOdt); // preview → OpenDocument
        buffer.previewMenu().setExportJson(exports::exportMarkwhenJson); // Markwhen preview → JSON
        buffer.setTypstRootResolver(this::resolveTypstRoot); // typst --root: nearest typst.toml / project root
        buffer.setOnMarkwhenViewChanged(() -> previews.persistMarkwhenView(buffer)); // persist timeline/calendar choice
        buffer.setOnEnableEditing(() -> enableEditing(buffer)); // "Enable Editing" banner button
        snippetCoordinator.wireBuffer(buffer);
        buffer.setCompletionProvider(completion::complete);
        buffer.setAiCompletionProvider(aiCoordinator::inlineComplete);
        buffer.setAiCompletionEnabled(aiCoordinator.isInlineCompletionEnabled());
        // A Maven submenu only on a pom.xml — mavenMenu itself enforces that, so this surface and the
        // project tree's cannot disagree about when it appears.
        buffer.setBuildMenuContributor(() -> {
            javafx.scene.control.Menu maven = mavenProjectCoordinator.mavenMenu(buffer.getPath());
            if (maven != null) {
                return List.of(maven);
            }
            List<javafx.scene.control.MenuItem> items =
                    new ArrayList<>(previews.lint().editorMenu(buffer));
            items.addAll(runCoordinator.editorMenuItems(buffer)); // Run with Trace on a runnable shell script
            return items;
        });
        buffer.setMenuContributor(
                () -> { // External Tools submenu + plugin-contributed right-click items
                    List<javafx.scene.control.MenuItem> extra =
                            new ArrayList<>(exports.editorMenuItems(buffer, externalToolCoordinator.editorMenuItems()));
                    extra.addAll(pluginCoordinator.editorMenuItems(buffer));
                    return extra;
                });
        Settings acs = config.getSettings();
        buffer.setAutocomplete(
                acs.isAutocomplete(),
                acs.isAutocompleteProse(),
                acs.isAutocompleteSnippets(),
                mermaid.effectiveAutocomplete());
        buffer.setMultiCaretEnabled(
                editorSettings
                        .multiCaretEnabled()); // multiple cursors + Alt+drag column selection (off in Simple UI mode)
        mermaid.wireBuffer(buffer); // live maid validator + initial lint state
        // Markdown linting: the overlay gets the diagnostics; the Lint tool window mirrors them live when
        // this buffer is the active one and the window is open.
        buffer.setMarkdownLintValidator((text, cb) -> previews.lintMarkdown(buffer, text, cb));
        buffer.setMarkdownLintEnabled(previews.markdownLintEnabled());
        buffer.setImageDropHandler(
                files -> editing.insertDroppedImages(buffer, files)); // drag image → assets/ + ![](…)
        buffer.setWebImageDropHandler(
                (image, url) -> editing.insertWebImage(buffer, image, url)); // browser image → assets/
        // LSP: wire didChange/diagnostics/completion/format/nav hooks + open+activate if eligible.
        lspCoordinator.wireBuffer(buffer);
        previews.ensurePreviewControls(buffer);
        htmlPreview.ensureControl(buffer); // the floating "open in browser" globe (HTML buffers, feature on)
        logViewer.ensureControl(buffer); // the floating Follow / level / regex control (log buffers, feature on)
    }

    /** The per-tab part of {@link #addBuffer}: header, dirty/edit/selection listeners and the tab menu. */
    private void attachBufferTab(Tab tab, EditorBuffer buffer) {
        updateTabMeta(tab, buffer); // replaces the default text/icon with the buffer header (drag handle, pin, dirty)
        buffer.dirtyProperty().addListener((obs, was, now) -> {
            updateTabMeta(tab, buffer);
            if (buffer == activeBuffer()) {
                toolbar.refreshEditState(); // save enablement (e.g. after markClean())
            }
            if (projectPanel != null) {
                projectPanel.refreshModified(); // reflect the dirty marker in the Project file tree
            }
        });
        // Auto save (after-delay mode): each edit restarts the idle timer; cheap (no full-text build).
        buffer.getArea().multiPlainChanges().subscribe(c -> {
            if (FileWorkflowCoordinator.AUTOSAVE_DELAY.equals(fileWorkflows.autoSaveMode())) {
                fileWorkflows.autoSaveIdleTimer.playFromStart();
            }
            if (buffer == activeBuffer()) {
                toolbar.scheduleEditStateRefresh(); // edits change undo/redo (and dirty) availability
            }
        });
        // Selection changes toggle cut/copy enablement (no clipboard read here — that's refreshPasteState).
        buffer.onSelectionChanged(() -> {
            if (buffer == activeBuffer()) {
                toolbar.scheduleEditStateRefresh(); // of the split pane in use
            }
        });
        installTabMenu(tab, buffer);
    }

    /**
     * Creates a tab for any {@link TabContent} (a buffer or the Welcome page), appends it to the strip,
     * and stores the content in {@code userData}. The generic title/icon/close affordance come from the
     * content; buffers then override the header via {@link #updateTabMeta}. Selects + focuses the content
     * node when {@code select}.
     */
    Tab addContentTab(TabContent content, boolean select) {
        Tab tab = new Tab();
        DeferredTabContent.install(tab, content.node()); // a restored background tab attaches when shown
        tab.setUserData(content);
        tabHeaders.contentHeader(tab, content);
        tab.setClosable(content.closeable());
        tab.setOnCloseRequest(e -> {
            if (!closes.confirmClose(tab)) {
                e.consume();
            } else {
                rememberClosed(tab);
            }
        });
        editorArea.add(tab);
        if (select) {
            editorArea.select(tab);
            Platform.runLater(content.node()::requestFocus);
        }
        return tab;
    }
    /** Tab headers (icon, title, markers, drag handle); see {@link TabHeaders}. */
    private final TabHeaders tabHeaders = new TabHeaders(() -> editorArea, tab -> pinned.contains(tab));

    /** Refreshes a tab's title (pin + dirty markers), style classes, and full-path tooltip. */
    void updateTabMeta(Tab tab, EditorBuffer buffer) {
        // Keep the window title's file name/path + dirty marker in step (dirty flips, Save-As, rename).
        if (tab == editorArea.selectedTab()) {
            updateWindowTitle();
        }
        if (buffer.isDirty()) {
            // Editing is the clearest possible statement that this file is not a passing glance.
            promoteTab(tab);
        }
        tabHeaders.bufferHeader(tab, buffer, tab == previewTab);
    }

    // --- File actions ---

    @FXML
    void onNew() {
        addBuffer(new EditorBuffer());
        setStatus(tr("status.newBuffer"));
    }

    @FXML
    private void onNewFromTemplate() {
        templateActions.newFromTemplate(null);
    }

    @FXML
    void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.openFile.title"));
        java.io.File chosen = chooser.showOpenDialog(stage);
        Path file = chosen == null ? null : chosen.toPath();
        if (file != null) {
            fileWorkflows.openPath(file);
        }
    }

    /**
     * Opens a file chosen from the recent-files list, project-aware: a file under another project than this
     * window's opens in that project's window (focused, or built), where it was last left. Files that belong
     * to no project, or to this window's, open here.
     *
     * <p>It used to compare with the {@code ProjectManager}'s active project (the window opened last) and,
     * after focusing the other project's window, open the file in this one anyway.
     */
    void openRecent(Path file) {
        Project owner = owningProject(file);
        if (owner != null && windowManager != null && !owner.id().equals(projectKey)) {
            windowManager.openRecentInWindow(owner.id(), file);
            return;
        }
        fileWorkflows.openPath(file);
    }

    /** Opens {@code file} in this window where it was last left; {@link WindowManager#openRecentInWindow}'s landing. */
    void openRecentHere(Path file) {
        fileWorkflows.openPath(file);
    }

    /** The enabled project whose root is the closest ancestor of {@code file}, or {@code null}. */
    private @Nullable Project owningProject(Path file) {
        if (projects == null || !projectsEnabled()) {
            return null;
        }
        Path abs = file.toAbsolutePath().normalize();
        Project best = null;
        for (Project p : projects.list()) {
            if (p.contains(abs)
                    && (best == null || p.root().length() > best.root().length())) {
                best = p; // prefer the deepest (most specific) matching project root
            }
        }
        return best;
    }

    /** Opens the personal dictionary ({@code dictionary.txt}) in the editor, creating it empty if it doesn't
     *  exist yet (so the link works even before any word is added). Backs the Settings → Spell Check link. */
    void openPersonalDictionary() {
        Path file = config.getDictionaryFile();
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, "");
            }
        } catch (IOException e) {
            setStatus(tr("status.dict.openFailed"));
            return;
        }
        fileWorkflows.openPath(file);
    }

    /** Opens the bundled technical-terms dictionary in a read-only tab so the user can browse what it covers.
     *  It's a classpath resource (inside the jar), so there's no on-disk path to open. */
    void openTechnicalDictionary() {
        String text;
        try (var in = MainController.class.getResourceAsStream("/com/editora/dictionaries/technical.txt")) {
            if (in == null) {
                setStatus(tr("status.dict.openFailed"));
                return;
            }
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            setStatus(tr("status.dict.openFailed"));
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName("technical.txt");
        buffer.setContent(text);
        addBuffer(buffer, true);
        buffer.setViewMode(true); // read-only: it's the bundled list, not user-editable
    }

    /**
     * The single tab reused for files being browsed rather than opened, or {@code null} when there is none.
     *
     * <p>Browsing is how navigation is actually used — arrowing a picker, following a definition to see
     * what something is — and without this every glance costs a permanent tab, so the strip fills with
     * files nobody chose to keep. One reusable slot bounds that at one.
     */
    private @Nullable Tab previewTab;

    /**
     * Opens {@code file} in the preview slot: the previous preview tab is replaced rather than added to.
     *
     * <p>An already-open file is simply selected and is <em>not</em> demoted into the slot — it earned its
     * place by some earlier deliberate open, and quietly making it disposable would lose it on the next
     * glance at something else.
     */
    void openPathPreview(Path file) {
        Tab existing = tabForPath(file);
        if (existing != null) {
            TabFocus.select(editorArea, existing);
            return;
        }
        Tab previous = previewTab;
        previewTab = null; // cleared first: closing it runs listeners that would otherwise see a stale slot
        if (previous != null && editorArea.tabs().contains(previous)) {
            EditorBuffer b = bufferOf(previous);
            // A dirty preview tab is not disposable — the edit already promoted it, but guard anyway
            // rather than risk closing unsaved work on a keystroke.
            if (b == null || !b.isDirty()) {
                editorArea.remove(previous); // tabs() is unmodifiable by design; remove() is the seam
            }
        }
        fileWorkflows.openPath(file, true);
        Tab opened = tabForPath(file);
        if (opened != null) {
            previewTab = opened;
            EditorBuffer b = bufferOf(opened);
            if (b != null) {
                updateTabMeta(opened, b); // re-render the header so it shows as italic
            }
        }
    }

    /** Makes {@code tab} permanent — it was chosen, not glanced at. No-op for any other tab. */
    void promoteTab(@Nullable Tab tab) {
        if (tab == null || tab != previewTab) {
            return;
        }
        previewTab = null;
        EditorBuffer b = bufferOf(tab);
        if (b != null) {
            updateTabMeta(tab, b); // drops the italic
        }
    }

    /** Loads {@code file} into a new text {@link EditorBuffer} tab (the normal text path, bypassing the
     *  image/binary routing in {@link #openPath}). Used by {@code openPath}'s fall-through and by the
     *  {@code view.openAsText} command (which forces a text open even for a binary / already-hex file). */
    private void openTextBuffer(Path file) {
        fileWorkflows.openTextBufferAsync(file, false); // explicit Open as Text bypasses binary routing
    }

    void finishAsyncOpen(Tab tab, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
        if (tabForBuffer(buffer) != tab) {
            discardLoading(buffer); // the user closed the shell while its disk read was running
            return;
        }
        if (load.binary()) {
            boolean selected = editorArea.selectedTab() == tab;
            discardLoading(buffer);
            editorArea.remove(tab);
            fileWorkflows.openHexTab(load.file(), selected);
            if (recentFiles != null) {
                recentFiles.add(load.file());
            }
            setStatus(tr("status.opened", com.editora.config.PathDisplay.of(load.file())));
            return;
        }
        String note = fileWorkflows.applyPreparedLoad(buffer, load);
        fileWorkflows.notePerfContentLoaded(buffer);
        restorePerFileState(buffer);
        buffer.setLoading(false);
        previews.restoreMarkdownMode(buffer);
        updateTabMeta(tab, buffer);
        // The loading shell is deliberately read-only until its document lands. Selection attached the
        // status bar while that temporary state was active; refresh after restoring the file's real view
        // mode or the segment can keep saying Read-Only even though the CodeArea is now editable. This is
        // especially visible for `--expert FILE`, where the status bar remains but the tab header is hidden.
        statusBar.refresh();
        lspCoordinator.syncBufferWhenShown(buffer); // the temporary heavy-file shell suppressed this
        // Set the default caret before releasing queued navigation. A later runLater(goToStart) would erase
        // a file:line request that waited on this loading shell.
        buffer.goToStart();
        if (recentFiles != null) {
            recentFiles.add(load.file());
        }
        setStatus(note.isEmpty() ? tr("status.opened", com.editora.config.PathDisplay.of(load.file())) : note);
        clearLoading(buffer);
        fileWorkflows.encryption().opened(buffer); // an encrypted file decrypts now; a public key offers Import
    }

    void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error) {
        discardLoading(buffer);
        if (tabForBuffer(buffer) == tab) {
            editorArea.remove(tab);
        }
        setStatus(tr("status.failedOpen", RemoteReadFailure.reason(file, error)));
        if (recentFiles != null) {
            recentFiles.remove(file);
        }
    }

    void clearLoading(EditorBuffer buffer) {
        fileWorkflows.loadingBuffers.remove(buffer);
        List<Runnable> queued = fileWorkflows.afterBufferLoad.remove(buffer);
        if (queued != null) {
            queued.forEach(Runnable::run);
        }
    }

    void discardLoading(EditorBuffer buffer) {
        fileWorkflows.loadingBuffers.remove(buffer);
        fileWorkflows.afterBufferLoad.remove(buffer);
    }

    /** Command {@code view.openAsText}: opens the active file's bytes in a normal text buffer, bypassing the
     *  image/binary auto-routing (for a file mis-detected as binary, or to read a binary's raw text). No-op
     *  with a status when the active tab has no file. */
    void openActiveAsText() {
        Path p = tabPath(editorArea.selectedTab());
        if (p == null) {
            setStatus(tr("status.hex.noFile"));
            return;
        }
        openTextBuffer(p);
    }

    /** The {@link ImageViewerPane} in {@code tab}, or {@code null} for a buffer / non-image tab. */
    static @Nullable ImageViewerPane imagePaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof ImageViewerPane pane ? pane : null;
    }

    /** The {@link HexViewerPane} in {@code tab}, or {@code null} for a buffer / non-hex tab. */
    static @Nullable HexViewerPane hexPaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof HexViewerPane pane ? pane : null;
    }

    /** The {@link PdfViewerPane} in {@code tab}, or {@code null} for a buffer / non-PDF tab. */
    static @Nullable PdfViewerPane pdfPaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof PdfViewerPane pane ? pane : null;
    }

    /** The file's resolved {@code .editorconfig} charset, or null (EditorConfig off / remote / no rule). The
     *  BOM check that overrides it lives in {@link com.editora.editorconfig.EditorConfigCharset#resolveName}. */
    @Nullable
    String editorConfigCharsetFor(@Nullable Path file) {
        if (file != null && editorSettings.editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)) {
            return com.editora.editorconfig.EditorConfig.resolveFor(file).charset();
        }
        return null;
    }

    @FXML
    void onClearRecent() {
        if (recentFiles == null || recentFiles.getList().isEmpty()) {
            return;
        }
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.clearRecent.title"));
        alert.setHeaderText(tr("dialog.clearRecent.header"));
        alert.setContentText(null);
        ButtonType clear = new ButtonType(tr("dialog.clearRecent.clear"));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(clear, cancel);
        Dialogs.destructive(alert, clear);
        if (alert.showAndWait().orElse(cancel) == clear) {
            recentFiles.clear();
            setStatus(tr("status.recentCleared"));
        }
    }

    /** Normalizes a stored auto-save mode string to a known key (unknown ⇒ off). */
    static String autoSaveModeOf(String mode) {
        return FileWorkflowCoordinator.AUTOSAVE_DELAY.equals(mode)
                        || FileWorkflowCoordinator.AUTOSAVE_FOCUS.equals(mode)
                ? mode
                : FileWorkflowCoordinator.AUTOSAVE_OFF;
    }

    /** Human-readable label for an auto-save mode key (also used by the settings combo). */
    static String autoSaveLabel(String mode) {
        return switch (mode) {
            case FileWorkflowCoordinator.AUTOSAVE_DELAY -> tr("autosave.delay");
            case FileWorkflowCoordinator.AUTOSAVE_FOCUS -> tr("autosave.focus");
            default -> tr("autosave.off");
        };
    }

    @FXML
    void onCloseTab() {
        closeTab(activeTab());
    }

    Tab activeTab() {
        return editorArea.selectedTab();
    }

    /**
     * The {@link EditorBuffer} backing {@code tab}, or {@code null} for a non-buffer tab (e.g. the
     * Welcome tab) — every {@code tab.userData} read must go through here, since {@code userData} is
     * {@code Object} and a raw cast would throw {@link ClassCastException} on a non-buffer tab.
     */
    static @Nullable EditorBuffer bufferOf(@Nullable Tab tab) {
        return tab != null && tab.getUserData() instanceof EditorBuffer b ? b : null;
    }

    /** Closes a single tab, confirming first if it is pinned and/or has unsaved changes. */
    void closeTab(Tab tab) {
        if (tab != null && tab.getUserData() instanceof TerminalEditorTab terminal) {
            terminalCoordinator.requestClose(terminal); // its shell's own question, which may ask the host first
            return;
        }
        if (tab != null && closes.confirmClose(tab)) {
            rememberClosed(tab);
            editorArea.remove(tab);
        }
    }

    /** Files whose tabs the user closed in this window, for Buffer: Reopen Closed Tab. */
    final ClosedTabs closedTabs = new ClosedTabs();

    /** Emacs registers ({@code C-x r s / i / SPC / j}) for this window. */
    final RegisterCommands registers = new RegisterCommands(new RegisterCommandsHost(this));

    /**
     * Remembers a file tab the user is closing. Called from the close command, the batch closes and the tab's
     * own close button only — never for a window close, a project switch or a tab swapped for another view.
     */
    private void rememberClosed(Tab tab) {
        EditorBuffer buffer = bufferOf(tab);
        RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
        if (buffer != null && buffer.getPath() != null) {
            closedTabs.closed(buffer.getPath(), buffer.getArea().getCaretPosition());
        } else if (placeholder != null) {
            closedTabs.closed(placeholder.path(), placeholder.caret()); // a restored tab never shown
        }
    }

    /** {@code buffer.reopenClosed}: opens the file whose tab was closed last, its caret where it was. */
    void reopenClosedTab() {
        for (ClosedTabs.Entry entry = closedTabs.pop(); entry != null; entry = closedTabs.pop()) {
            Path path = entry.path();
            Tab open = tabForPath(path);
            if (open != null) {
                editorArea.select(open); // reopened some other way since
                return;
            }
            if (com.editora.vfs.Vfs.isLocal(path) && !Files.exists(path)) {
                continue; // deleted since: the next one back
            }
            int caret = entry.caret();
            fileWorkflows.openThen(path, () -> {
                EditorBuffer buffer = activeBuffer();
                if (buffer != null && path.equals(buffer.getPath())) {
                    buffer.getArea()
                            .moveTo(Math.clamp(caret, 0, buffer.getArea().getLength()));
                    buffer.getArea().requestFollowCaret();
                }
            });
            return;
        }
        setStatus(tr("status.reopenClosed.none"));
    }

    /**
     * Closes each tab in {@code targets} (a snapshot), prompting for dirty buffers and stopping if
     * the user cancels — mirroring the window-close coordinator.
     */
    private void closeTabs(List<Tab> targets) {
        for (Tab tab : targets) {
            if (tab.getUserData() instanceof TerminalEditorTab terminal) {
                terminalCoordinator.requestClose(terminal); // asks when its shell is running something
                continue;
            }
            EditorBuffer buffer = bufferOf(tab);
            if (buffer == null) {
                if (!closes.confirmClose(tab)) {
                    return;
                }
                rememberClosed(tab);
                editorArea.remove(tab);
                continue;
            }
            if (buffer != null && !buffer.isDirty() && !fileWorkflows.hasPendingSave(buffer)) {
                rememberClosed(tab);
                editorArea.remove(tab);
                continue;
            }
            editorArea.select(tab);
            if (!closes.confirmCloseIfDirty(buffer)) {
                return; // user cancelled — stop the batch
            }
            rememberClosed(tab);
            editorArea.remove(tab);
        }
    }

    /** Non-pinned tabs whose index is less than {@code pivot}'s. */
    List<Tab> eligibleToLeft(Tab pivot) {
        int idx = editorArea.indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            Tab t = editorArea.tabAt(i);
            if (!pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Non-pinned tabs whose index is greater than {@code pivot}'s. */
    List<Tab> eligibleToRight(Tab pivot) {
        int idx = editorArea.indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = idx + 1; i < editorArea.size(); i++) {
            Tab t = editorArea.tabAt(i);
            if (!pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    void closeOtherTabs(Tab keep) {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : editorArea.tabs()) {
            if (t != keep && !pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    void closeAllTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : editorArea.tabs()) {
            if (!pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    void closeUnmodifiedTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf(t);
            if (!pinned.contains(t) && (buffer == null || !buffer.isDirty())) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    void closeTabsToLeft(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToLeft(pivot));
        }
    }

    void closeTabsToRight(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToRight(pivot));
        }
    }

    /** Copies the buffer's absolute path to the system clipboard. */
    void copyPath(@Nullable EditorBuffer buffer) {
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(buffer.getPath().toAbsolutePath().toString());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(tr("status.copiedPath"));
    }

    /** Reveals the active buffer's file in the OS file manager (a no-op for an unsaved/remote file). */
    void revealActiveBuffer() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        revealInFileManager(buffer.getPath(), false, isLocalBuffer(buffer));
    }

    /**
     * Opens a terminal at the active buffer's containing folder, on its host for a remote file. With no saved file
     * active (the Welcome tab, an unsaved buffer) it opens where {@code tool.terminal} would: the project's folder,
     * or home.
     */
    void openTerminalForActiveBuffer() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null || buffer.getPath() == null) {
            terminalCoordinator.newTerminal();
            return;
        }
        openTerminalAt(buffer.getPath(), false, isLocalBuffer(buffer));
    }

    /**
     * Reveals {@code path} in the OS file manager. Used by the palette command, the tab context menu,
     * and the Project tool window. No-op (with a status hint) for an unsaved or remote file.
     */
    void revealInFileManager(@Nullable Path path, boolean isDir, boolean local) {
        if (path == null) {
            setStatus(tr("status.reveal.noFile"));
            return;
        }
        if (!local) {
            setStatus(tr("status.reveal.remote"));
            return;
        }
        com.editora.process.DesktopActions.reveal(
                path.toAbsolutePath(),
                isDir,
                msg -> Platform.runLater(() -> setStatus(tr("status.reveal.failed", msg))));
    }

    /**
     * Opens a terminal at {@code path}'s containing folder (or {@code path}, a folder): a tab in the Terminal
     * tool window, or the system's terminal when Settings → Terminal says so. Shared by the command, tab menu,
     * breadcrumb and tree.
     */
    void openTerminalAt(@Nullable Path path, boolean isDir, boolean local) {
        if (path == null) {
            setStatus(tr("status.reveal.noFile"));
            return;
        }
        if (!local) {
            // A system terminal would open on this machine, not the host, so a remote folder always gets a tab.
            Path dir = isDir ? path : path.getParent();
            terminalCoordinator.openAt(dir == null ? path : dir);
            return;
        }
        if (!config.getSettings().isTerminalUseExternal()) {
            Path abs = path.toAbsolutePath();
            terminalCoordinator.openAt(isDir ? abs : abs.getParent());
            return;
        }
        com.editora.process.DesktopActions.openTerminal(
                path.toAbsolutePath(),
                isDir,
                msg -> Platform.runLater(() -> setStatus(tr("status.terminal.failed", msg))));
    }

    /**
     * Toggles a tab's pinned state. Pinned tabs are kept grouped at the front of the strip (in pin
     * order) and skipped by the bulk-close actions.
     */
    void togglePin(Tab tab) {
        if (tab == null) {
            return;
        }
        EditorBuffer buffer = ensureBuffer(tab); // a restored tab not built yet has a header to mark: build it
        if (buffer == null) {
            // The Welcome tab or a viewer: its header has no pin marker to show, and buffer.togglePin reaches
            // here with whatever tab is selected. Pinning it used to throw after the tab was already pinned.
            setStatus(tr("status.noFileOpen"));
            return;
        }
        if (!pinned.remove(tab)) {
            pinned.add(tab);
        }
        // Either way the tab belongs at the end of its strip's pinned group: last of them once pinned, first
        // after them once unpinned.
        editorArea.moveToPinBoundary(tab, pinned::contains);
        updateTabMeta(tab, buffer);
        setStatus(tr(pinned.contains(tab) ? "status.pinned" : "status.unpinned"));
    }

    /** Prompts for a new name for the buffer's file; see {@link #renameFileTo}. */
    void renameFile(@Nullable EditorBuffer buffer, Tab tab) {
        if (buffer != null && buffer.getPath() != null) {
            Path old = buffer.getPath();
            String name = old.getFileName().toString();
            promptText(tr("dialog.renameFile.title"), tr("dialog.renameFile.content"), name, typed -> {
                if (!typed.isBlank()) {
                    renameFileTo(buffer, old, old.resolveSibling(typed.trim()));
                }
            });
        }
    }

    /**
     * Renames the buffer's file on disk, then follows the rename through {@link #onProjectFileRenamed} — the
     * path the Project tree uses. The tab menu used to re-point only its own buffer, so another window showing
     * the same file kept the old path (and would recreate it on save), and only the fold state moved with it.
     */
    void renameFileTo(EditorBuffer buffer, Path old, Path target) {
        if (target.equals(old)) {
            return;
        }
        boolean caseOnly = com.editora.io.CaseOnlyRename.isAlias(old, target); // README.md on a case-blind volume
        if (!caseOnly && Files.exists(target)) {
            setStatus(tr("status.renameFailedExists", target.getFileName()));
            return;
        }
        fileWorkflows.invalidatePendingWrite(old);
        try {
            if (caseOnly) {
                com.editora.io.CaseOnlyRename.move(old, target);
            } else {
                Files.move(old, target);
            }
        } catch (IOException e) {
            setStatus(tr("status.renameFailed", e.getMessage()));
            return;
        }
        onProjectFileRenamed(old, target); // every window: path, EditorConfig, LSP, tab, path-keyed session state
        previews.ensurePreviewControls(buffer); // a rename to/from .md/.mmd flips previewability
        logViewer.ensureControl(buffer); // a rename to/from .log flips the log control
        if (recentFiles != null) {
            recentFiles.add(target);
        }
        statusBar.refresh();
    }

    /** Builds and attaches the right-click context menu for a tab. */
    private void installTabMenu(Tab tab, EditorBuffer buffer) {
        tab.setContextMenu(
                LazyContextMenu.of(menu -> TabContextMenu.build(this, tab, buffer, menu))); // built on first use
    }

    /** Reflection seam retained for lifecycle tests and project deletion. */
    private boolean confirmCloseIfDirty(EditorBuffer buffer) {
        return closes.confirmCloseIfDirty(buffer);
    }

    static void styleUnsavedChangesButtons(Alert alert, ButtonType save, ButtonType discard) {
        alert.getDialogPane().lookupButton(save).getStyleClass().add("success");
        alert.getDialogPane().lookupButton(discard).getStyleClass().add("danger");
    }

    @Nullable
    Tab tabFor(EditorBuffer buffer) {
        for (Tab tab : editorArea.tabs()) {
            if (tab.getUserData() == buffer) {
                return tab;
            }
        }
        return null;
    }

    /** True if {@code file} is open in a tab whose buffer has unsaved changes. */
    private boolean isPathModified(Path file) {
        Tab tab = tabForPath(file);
        if (tab == null) {
            return false;
        }
        EditorBuffer buffer = bufferOf(tab);
        return buffer != null && buffer.isDirty();
    }

    /** Captures an open editor's current text for the Map's floating preview, including unsaved changes. */
    private ProjectMapPreview.Content projectMapPreviewContent(Path file) {
        EditorBuffer buffer = bufferOf(tabForPath(file));
        if (buffer == null) {
            return ProjectMapPreview.Content.closed(editorConfigCharsetFor(file));
        }
        var area = buffer.getArea();
        int length = area.getLength();
        int end = Math.min(length, ProjectMapPreview.MAX_OUTLINE_CHARS); // a card cuts its own (C2)
        return ProjectMapPreview.Content.buffer(() -> area.getText(0, end), end < length, buffer.docVersion());
    }

    /**
     * Whether this window already has {@code file} open. Used by {@link WindowManager} to route an
     * externally-delivered launch to the window that already holds the file, rather than opening a second
     * buffer on it in a new window — two independent buffers over one file is an edit-loss hazard rather than
     * mere clutter, since saving one leaves the other stale behind an external-change prompt.
     */
    boolean hasFileOpen(Path file) {
        return file != null && tabForPath(file) != null;
    }

    @Nullable
    Tab tabForPath(@Nullable Path file) {
        return file == null ? null : OpenBufferLifecycle.tabForPath(editorArea, MainController::tabPath, file);
    }

    /** The file backing {@code tab} (a buffer / image viewer / hex viewer path), or {@code null} for none. */
    static @Nullable Path tabPath(Tab tab) {
        EditorBuffer buffer = bufferOf(tab);
        if (buffer != null) {
            return buffer.getPath();
        }
        RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
        if (placeholder != null) {
            return placeholder.path(); // a restored file whose editor is not built yet is still open
        }
        ImageViewerPane image = imagePaneOf(tab);
        if (image != null) {
            return image.getPath();
        }
        PdfViewerPane pdf = pdfPaneOf(tab);
        if (pdf != null) {
            return pdf.getPath();
        }
        HexViewerPane hex = hexPaneOf(tab);
        return hex == null ? null : hex.getPath();
    }

    /** A provider-safe identity key for a path: the canonical string for a local file, the {@code sftp://}
     *  URI for a remote one. Avoids {@code Path.equals} across filesystems (MINA SFTP paths throw a
     *  {@link java.nio.file.ProviderMismatchException} when compared to a local path) and a network
     *  {@code toRealPath()} for remote files. */
    private static String pathKey(Path p) {
        return com.editora.config.PathKeys.key(p);
    }

    /** @see com.editora.config.PathKeys#canonical(Path) */
    static Path canonicalPath(Path p) {
        return com.editora.config.PathKeys.canonical(p);
    }

    /**
     * In-app quit. {@code Platform.exit()} fires no {@code Stage.onCloseRequest}, so the per-window close
     * handler never runs — every window has to be prompted + persisted here, or the other windows lose their
     * unsaved buffers and their whole session. {@link WindowManager#confirmCloseAllWindows} does that (and
     * disposes each window's services); the null case is the unit-test/standalone controller.
     */
    boolean onQuit() {
        // One question: quitting, and which terminals that ends (in every window).
        List<String> busy = windowManager != null ? windowManager.busyTerminals() : terminalCoordinator.busyTerminals();
        boolean ok = closes.confirmQuit(busy)
                && (windowManager != null ? windowManager.confirmCloseAllWindows() : closes.confirmCloseAll());
        if (ok) {
            Platform.exit();
        }
        return ok;
    }

    static void styleQuitButtonAsDanger(Alert alert, ButtonType quit) {
        alert.getDialogPane().lookupButton(quit).getStyleClass().add("danger");
    }

    void persistSessionForClose() {
        sessions.persistSession();
        recovery.closedByUser(); // every unsaved buffer was answered for: drop this window's recovery copies
    }

    /** Records the open files (in tab order) and their carets so the next launch can restore them. */
    private boolean configSavePending;

    /**
     * True while a {@link #requestSave()} of this window has not run yet. A command that changes a shared
     * preference requests a save in the same step, so {@code WindowManager} reads this to tell whether the
     * window whose save carried a change is the one that made it.
     */
    boolean saveRequestPending() {
        return configSavePending;
    }

    /**
     * Coalesces config writes to one per FX pulse and performs the disk I/O <em>off</em> the FX thread.
     * Many actions (especially a single Settings apply, which runs ~10 field setters back-to-back) request
     * a save several times in the same pulse; this collapses such a burst into a single end-of-pulse
     * {@code saveAsync()} — which serializes a consistent snapshot on the FX thread, then writes it on the
     * shared {@code ConfigWriter}'s daemon thread (further coalesced per file). No data-loss risk: the
     * durable flush on quit goes through {@link #persistSession()}'s blocking {@code config.save()}, and all
     * settings/session writes funnel through the one ordered writer queue, so an async write can never land
     * after and clobber that durable one.
     */
    void requestSave() {
        if (configSavePending || sessionClosed) {
            return;
        }
        configSavePending = true;
        Platform.runLater(() -> {
            configSavePending = false;
            if (!sessionClosed) {
                sessions.captureSession(); // open files, carets and layout as of this pulse
                config.saveAsync(); // a window disposed in the meantime must not rewrite its session file
            }
        });
    }

    /**
     * True once this window has been closed: its session was persisted and its services disposed, so nothing
     * may write its session file again. Without this, a {@code requestSave()} coalesced earlier in the same FX
     * pulse fires <em>after</em> the close/delete handler returns and re-creates the file — which, for a
     * deleted project, silently <b>resurrects</b> it (ids are derived from the folder path, so re-adding that
     * folder later picks the old state right back up).
     */
    private boolean sessionClosed;

    void nextBuffer() {
        int count = editorArea.focusedSize(); // within the split group, as IntelliJ's (thirteenth review AK3)
        if (count > 1) {
            int idx = (editorArea.selectedIndex() + 1) % count;
            editorArea.select(idx);
            Platform.runLater(() -> TabFocus.focusTab(editorArea.selectedTab(), MainController::bufferOf)); // AQ2, AL1
        }
    }

    // --- Edit actions (delegate to active CodeArea) ---

    // --- Markdown image paste / drop -------------------------------------------------------------

    /** C-s: show the find bar, or — if already showing — cycle to the next match. */
    void findShowOrNext() {
        if (findBar.isShown()) {
            findBar.findNext();
        } else {
            findBar.show(false);
        }
    }

    /** C-r: show the find bar (reverse), or — if already showing — cycle to the previous match. */
    void findShowOrPrevious() {
        if (findBar.isShown()) {
            findBar.findPrevious();
        } else {
            findBar.show(true);
        }
    }

    /** Replace: ensure the find/replace bar is showing and focus the replace field. (C-g closes it.) */
    void showReplace() {
        if (!findBar.isShown()) {
            findBar.show(false);
        }
        findBar.focusReplace();
    }

    @FXML
    private void onFindInFiles() {
        searchCoordinator.openToggle();
    }

    /** Shows the Run tool window's stripe button for a runnable file, or once a run has started in this window.
     *
     * <p>The second half is essential even when the active buffer itself is not directly runnable (for
     * example, a project Java or NPM configuration). Otherwise a normal context refresh — including the one
     * a run's own exit triggers — closes the console on the output the user is about to read.
     */
    void updateRunButton() {
        EditorBuffer buffer = activeBuffer();
        boolean http = buffer != null && buffer.isHttpFile();
        boolean runnable = buffer != null && buffer.isRunnable() && !http;
        if (runToolWindow != null) {
            toolWindows.setAvailable(runToolWindow, runnable || runCoordinator.consoleInUse());
        }
        if (http && httpClient.isEnabled()) {
            httpClient.refreshEnvironments(buffer); // the .http file's environment pill and menu
        }
    }

    /** Set when the no-project window hid an open TODO window because the active tab has no TODOs, so it
     *  comes back (unfocused) on the next tab that has some; a user close clears it by never setting it. */
    private boolean todoReopenWhenAvailable;

    /**
     * Gates the TODO window on the active buffer: any buffer in a project window; in the no-project window
     * only a saved one with TODO matches (see {@link TodoCoordinator#availableFor}). Re-run on every tab
     * switch and on the active buffer's TODO re-scans, so it hides and returns as TODOs come and go.
     */
    private void updateTodoToolWindow() {
        if (todoToolWindow == null) {
            return;
        }
        boolean available = todoCoordinator.availableFor(activeBuffer());
        if (!available) {
            if (toolWindows.isOpen(todoToolWindow)) {
                todoReopenWhenAvailable = todoCoordinator.activeTabOnly();
            }
            toolWindows.setAvailable(todoToolWindow, false); // reconciles every call (see setAvailable)
            return;
        }
        toolWindows.setAvailable(todoToolWindow, true);
        if (todoReopenWhenAvailable) {
            todoReopenWhenAvailable = false;
            if (!toolWindows.isOpen(todoToolWindow)) {
                toolWindows.open(todoToolWindow, false); // unfocused: the user is typing in or switching tabs
                todoCoordinator.refreshPanelIfOpenQuietly();
            }
        }
    }

    /**
     * Gates the stripe buttons of tool windows whose content comes from the <b>active editor buffer</b>, so
     * they only advertise themselves when there is something to act on — hidden on the Welcome page and other
     * non-buffer tabs (mirroring how Run/Debug/HTTP/Commit already gate their buttons). Structure / File
     * Information / TODO need any buffer; Markdown Lint additionally needs a Markdown buffer (with the feature
     * on); External Tools needs a buffer and to not be in Simple mode. Uses transient
     * {@code setAvailable} (never persisted), so the user's show/hide preference is preserved.
     */
    void updateBufferToolWindows() {
        EditorBuffer b = activeBuffer();
        boolean buffer = b != null;
        if (structureToolWindow != null) {
            toolWindows.setAvailable(structureToolWindow, buffer);
        }
        if (fileInfoToolWindow != null) {
            toolWindows.setAvailable(fileInfoToolWindow, buffer);
        }
        if (undoHistoryToolWindow != null) {
            toolWindows.setAvailable(undoHistoryToolWindow, buffer);
        }
        updateTodoToolWindow();
        previews.activeBufferChanged(b); // the Markdown Lint window follows the active tab
        indexCoordinator.backlinks.activeBufferChanged(b);
        if (externalToolToolWindow != null) {
            toolWindows.setAvailable(externalToolToolWindow, buffer && externalToolsEnabled());
        }
        // Plugin tool windows act on the active editor — gate them on an open buffer too. Null-guarded: the
        // first setupToolWindows() pass runs before pluginCoordinator is built (it needs toolWindows), and no
        // plugin tool windows exist until applyPlugins() registers them, so an early skip is harmless.
        if (pluginCoordinator != null) {
            pluginCoordinator.gateToolWindows(buffer);
        }
        // Hide Git Commit / Git Log on a tab with no Git context; otherwise the Git coordinator gates them.
        if (!git.windowsAllowed(editorArea.selectedTab())) {
            if (commitToolWindow != null) {
                toolWindows.setAvailable(commitToolWindow, false);
            }
            if (gitLogToolWindow != null) {
                toolWindows.setAvailable(gitLogToolWindow, false);
            }
        }
        // Re-derive the GitHub window's availability (its setGitHubWindowAvailable already ANDs an open buffer).
        if (githubToolWindow != null) {
            github.refreshAvailability();
        }
    }

    /** The remembered program-arguments string for {@code path} ("" when none); shared with debug launches. */
    String programArgsFor(@Nullable Path path) {
        String s = path == null
                ? null
                : config.getWorkspaceState().getProgramArgs().get(path.toString());
        return s == null ? "" : s;
    }

    /**
     * The palette button and {@code palette.show}. Opens Search Everywhere instead when the user has asked
     * for that (off by default): it is a superset — an empty query lists every command exactly as the
     * palette does, and typing reaches files and symbols too — so the substitution takes nothing away.
     *
     * <p>Opened empty rather than seeded from the selection, because this is the palette's entry point and
     * the palette has never pre-filled itself.
     */
    @FXML
    void onPalette() {
        if (config.getSettings().isPaletteUsesSearchEverywhere() && chrome.searchEverywherePopup != null) {
            chrome.searchEverywherePopup.show("");
            return;
        }
        palette.show();
    }

    @FXML
    void onSettings() {
        settingsWindow.show(stage);
    }

    @FXML
    private void onToggleSimpleMode() {
        chrome.toggleSimpleMode();
    }

    void onAbout() {
        AboutDialog.show(
                stage,
                config.getSettingsFile(),
                fileWorkflows::openPath,
                this::openExternalUrl,
                registry::run,
                config.isDev() ? com.editora.AppInfo.gitCommit() : "", // build commit shown only in --dev
                updates); // what the last update check found, and "Check now"
    }

    /**
     * Picks a tool window to open <em>beside</em> whatever holds its side, splitting that side in two.
     *
     * <p>The picker lists only what could actually join a side right now, so an empty list is the honest
     * answer to "nothing is open to split with" rather than a list of choices that would silently replace.
     */
    void showSplitToolWindowPalette() {
        if (toolWindows.getRegisteredToolWindows().stream().noneMatch(toolWindows::canSplitWith)) {
            setStatus(tr("status.toolwindow.nothingToSplit"));
            return;
        }
        navigation.splitToolWindowPalette.show(stage);
    }

    /**
     * Detaches the active tool window into its own stage, or docks a floating one back.
     *
     * <p>Same target rule as maximize — the focused tool window, else the only open one — because the
     * palette necessarily takes focus out of the panel before the command runs.
     */
    void toggleFloatingToolWindow() {
        ToolWindow tw = toolWindows.maximizeTarget();
        if (tw == null) {
            setStatus(tr("status.toolwindow.noMaximizeTarget"));
            return;
        }
        toolWindows.toggleFloating(tw);
        setStatus(tr(
                toolWindows.isFloating(tw) ? "status.toolwindow.floated" : "status.toolwindow.docked", tw.getTitle()));
    }

    /**
     * Expands the active tool window over its split, or hands the space back if it already holds it.
     *
     * <p>Session-only and deliberately unpersisted: maximizing is a "let me read this for a moment" gesture,
     * and a window that reopened next launch covering the editor would read as a broken layout.
     */
    void toggleMaximizedToolWindow() {
        ToolWindow tw = toolWindows.maximizeTarget();
        if (tw == null) {
            setStatus(tr("status.toolwindow.noMaximizeTarget"));
            return;
        }
        toolWindows.toggleMaximized(tw);
        setStatus(tr(
                toolWindows.isMaximized(tw) ? "status.toolwindow.maximized" : "status.toolwindow.restored",
                tw.getTitle()));
    }

    void toggleToolStripe() {
        Settings s = config.getSettings();
        s.setShowToolStripe(!s.isShowToolStripe());
        requestSave();
        editorSettings.applyViewSettingsToAllBuffers(s); // → applyChromeVisibility → toolWindows.setStripesEnabled
        settingsWindow.syncToolStripeCheck();
        setStatus(tr("status.toggle.toolStripe", tr(s.isShowToolStripe() ? "common.on" : "common.off")));
    }

    // --- Multiple cursors / column selection commands (delegate to the active buffer's fork add-on) ---

    /** Runs {@code action} on the active buffer when multi-caret is enabled; else reports it. */
    void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
        if (!editorSettings.multiCaretEnabled()) {
            setStatus(tr("status.multiCaret.disabled"));
            return;
        }
        EditorBuffer b = activeBuffer();
        if (b != null) {
            action.accept(b);
        }
    }

    /** VS Code {@code selectHighlights} (Ctrl+Shift+L): a caret at every occurrence of the selection/word. */
    void selectAllOccurrences() {
        withMultiCaret(b -> {
            int n = b.selectAllOccurrences();
            setStatus(n == 0 ? tr("status.occurrences.none") : tr("status.occurrences.selected", n));
        });
    }

    /** VS Code {@code selectAllMatches} (Alt+Enter in the find bar): a caret at every find-bar match. */
    void selectAllFindMatches() {
        if (!findBar.isShown()) {
            setStatus(tr("status.find.notOpen"));
            return;
        }
        withMultiCaret(b -> {
            java.util.List<int[]> m = new java.util.ArrayList<>(findBar.currentMatches());
            if (m.isEmpty()) {
                setStatus(tr("status.occurrences.none"));
                return;
            }
            int anchor = activeArea() != null ? activeArea().getCaretPosition() : 0;
            findBar.hideBar(); // returns focus to the editor before the carets are placed
            int n = b.placeOccurrenceCarets(m, anchor);
            setStatus(tr("status.occurrences.selected", n));
        });
    }

    /** A configuration for the active Python, shell, Groovy or Kotlin file (Add / Save as Run Configuration), or null. */
    com.editora.config.@Nullable RunConfiguration suggestedScriptConfiguration() {
        EditorBuffer b = activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null) {
            return null;
        }
        String type = runConfigTypeOf(b);
        // (forScript answers null for the types that launch no script file: Java, make, npm.)
        return type == null ? null : com.editora.run.RunConfigDefaults.forScript(type, file, windowProjectRoot());
    }

    /**
     * A blank configuration of the type that launches the active file, or null when no type does: what Run
     * Configurations → Add starts from when the file is not itself something to launch (a makefile, a
     * {@code package.json}, a script not saved yet), so its Type is not Java whatever is open.
     */
    com.editora.config.@Nullable RunConfiguration suggestedBlankConfiguration() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return null;
        }
        String type = runConfigTypeOf(b);
        return type == null
                ? null
                : com.editora.run.RunConfigDefaults.blankOfType(type, b.getPath(), windowProjectRoot());
    }

    private static @Nullable String runConfigTypeOf(EditorBuffer b) {
        Path name = b.getPath() == null ? null : b.getPath().getFileName();
        return com.editora.run.RunConfigDefaults.typeFor(name == null ? null : name.toString(), b.getLanguage());
    }

    /**
     * The main class a new run configuration should start from: the one the active Java file's project
     * declares, else the first {@code main} in that file. Null when there is nothing to suggest.
     *
     * <p>Shared by {@code run.saveConfig} and Run Configurations → Add, so the two cannot disagree
     * about what "the obvious main class here" is.
     */
    @Nullable
    String suggestedMainClass() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            return null;
        }
        // Prefer the main class a Gradle `application` block declares — that's what `gradle run` would launch,
        // so a config saved in such a project matches the build rather than whichever file happens to be open.
        String declared = com.editora.build.GradleApplication.mainClass(
                com.editora.build.SpringBoot.readGradleBuildFile(JavaProjectRoot.find(b.getPath())));
        if (declared != null) {
            return declared;
        }
        List<com.editora.run.MainMethodScanner.MainMethod> mains =
                com.editora.run.MainMethodScanner.scan(b.getContent());
        return mains.isEmpty() ? null : mains.get(0).fqn();
    }

    /**
     * This <em>window's</em> project root, or null when it has no project open.
     *
     * <p>Not the {@code ProjectManager}'s active project: that is the window opened last (focusing a window does
     * not change it), so a window behind it would act on somebody else's project. Every coordinator's
     * {@code projectRoot()} / {@code activeProjectRoot()} reads {@code windowProject} for that reason.
     */
    @Nullable
    Path windowProjectRoot() {
        Project project = enabledWindowProject();
        return project == null ? null : project.rootPath();
    }

    /** This window's project, or null when it has none or Projects are switched off. */
    @Nullable
    Project enabledWindowProject() {
        return windowProject != null && projectsEnabled() ? windowProject : null;
    }

    // --- Keybinding editor backend (Settings → Keymaps); pure logic in command/KeybindingEdits ---

    /**
     * Enters/leaves distraction-free Zen mode for <b>this window only</b>. Zen is a per-window
     * <em>effective overlay</em> (like Simple UI mode): it lives in this window's {@link WorkspaceState}
     * and is folded into {@link #applyChromeVisibility}/{@link #applyViewSettings} via {@link #zenActive()},
     * so it hides the chrome + editor view options <em>without mutating the shared, app-wide
     * {@link Settings}</em>. Leaving Zen therefore restores the user's saved prefs exactly (nothing was
     * changed), and one window being in Zen never affects another. Open tool windows are closed on enter
     * and reopened on leave (a per-window UI snapshot, not a pref). Idempotent.
     *
     * <p>An explicit toggle also <b>takes over from a {@code --zen}/{@code --expert} session flag</b> (as
     * {@link #toggleSimpleMode} does for {@code --simple}): the CLI override is cleared and the real state is
     * written, so entering the mode from a {@code --zen} launch makes it stick from then on.
     */
    void setZenMode(boolean on) {
        WorkspaceState ws = config.getWorkspaceState();
        if (chrome.zenActive() == on) {
            return;
        }
        if (on && chrome.expertActive()) {
            setExpertMode(false); // the two focus modes are mutually exclusive
        }
        if (on) {
            ws.setPreZenToolWindows(toolWindows.closeAllOpen());
        }
        chrome.clearCliFocusOverride(); // an explicit toggle takes over from the --zen/--expert session flag
        ws.setZenMode(on);
        toolWindows.setZenStripesHidden(on || chrome.expertActive());
        if (!on) {
            toolWindows.openByIds(ws.getPreZenToolWindows());
            ws.getPreZenToolWindows().clear();
        }
        chrome.applyChromeVisibility();
        editorSettings.applyViewSettingsToAllBuffers(config.getSettings());
        settingsWindow.syncFocusModeChecks();
        requestSave();
        // When entering Zen the status bar is hidden, so this is mostly seen on exit.
        setStatus(tr("status.toggle.zen", tr(on ? "common.on" : "common.off")));
    }

    /**
     * Enters/leaves Expert mode for <b>this window only</b> — a per-window effective overlay exactly like
     * {@link #setZenMode Zen}, except it <em>keeps the line-number gutter and the status bar</em> (see
     * {@link Chrome}). Mutually exclusive with Zen. Open tool windows are closed on enter and reopened on
     * leave (a per-window UI snapshot, not a pref); the saved {@link Settings} are never mutated. Idempotent.
     */
    void setExpertMode(boolean on) {
        WorkspaceState ws = config.getWorkspaceState();
        if (chrome.expertActive() == on) {
            return;
        }
        if (on && chrome.zenActive()) {
            setZenMode(false); // the two focus modes are mutually exclusive
        }
        if (on) {
            ws.setPreExpertToolWindows(toolWindows.closeAllOpen());
        }
        chrome.clearCliFocusOverride(); // an explicit toggle takes over from the --zen/--expert session flag
        ws.setExpertMode(on);
        toolWindows.setZenStripesHidden(on || chrome.zenActive());
        if (!on) {
            toolWindows.openByIds(ws.getPreExpertToolWindows());
            ws.getPreExpertToolWindows().clear();
        }
        chrome.applyChromeVisibility();
        editorSettings.applyViewSettingsToAllBuffers(config.getSettings());
        settingsWindow.syncFocusModeChecks();
        requestSave();
        setStatus(tr("status.toggle.expert", tr(on ? "common.on" : "common.off")));
    }

    // --- Settings palette commands ----------------------------------------------------------------
    // Every Settings-window control has a command-palette equivalent (Editora is command-driven). The
    // helpers below flip/prompt the same Settings field a control writes, then persist, re-apply the
    // feature, keep an open Settings window in step (syncAll), and echo a status. Two generic status
    // keys (status.settingToggled / status.settingChanged) reuse each command's own localized title.

    /** Persists the buffer's collapsed fold regions + manual fold ranges, keyed by its file path. */
    void persistFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> lines = buffer.getFoldManager().collapsedStartLines();
        var map = config.getWorkspaceState().getFoldedRegions();
        if (lines.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), lines);
        }
        List<Integer> manual = com.editora.editor.fold.ManualFolds.toFlat(
                buffer.getFoldManager().manualRegions());
        var manualMap = config.getWorkspaceState().getManualFoldRegions();
        if (manual.isEmpty()) {
            manualMap.remove(file.toString());
        } else {
            manualMap.put(file.toString(), manual);
        }
        requestSave();
    }

    /** The stored state every open of a file re-applies: folds, bookmarks, breakpoints, notes, read-only. */
    void restorePerFileState(EditorBuffer buffer) {
        restoreFolds(buffer);
        bookmarkCoordinator.restoreBookmarks(buffer);
        debugCoordinator.restoreBreakpoints(buffer);
        notesCoordinator.restoreNotes(buffer);
        restoreReadOnly(buffer);
    }

    /** Re-applies a file's saved manual fold ranges + collapsed fold regions after it is opened. */
    private void restoreFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> manual = config.getWorkspaceState().getManualFoldRegions().get(file.toString());
        List<Integer> saved = config.getWorkspaceState().getFoldedRegions().getOrDefault(file.toString(), List.of());
        buffer.getFoldManager()
                .restore(manual == null ? List.of() : com.editora.editor.fold.ManualFolds.fromFlat(manual), saved);
    }

    /**
     * Toggles the active buffer's read-only ("View") mode, persists it, and refreshes the indicators.
     * Huge files are already read-only by necessity (truncated load) and can't be made editable.
     */
    void toggleReadOnly() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        if (buffer.isReadOnly()) { // huge-file mode
            setStatus(tr("status.largeReadOnly"));
            return;
        }
        buffer.setViewMode(!buffer.isViewMode());
        afterReadOnlyChange(buffer);
        setStatus(buffer.isViewMode() ? ChordHint.tr("status.viewMode", "view.toggleReadOnly") : tr("status.editable"));
    }

    /** Turns off read-only ("Enable Editing" banner button); persists + refreshes the indicators. */
    private void enableEditing(EditorBuffer buffer) {
        if (buffer == null || !buffer.isViewMode()) {
            return;
        }
        buffer.setViewMode(false);
        afterReadOnlyChange(buffer);
        setStatus(tr("status.editingEnabled"));
    }

    /** Persists the read-only state and refreshes the tab + status-bar indicators for {@code buffer}. */
    private void afterReadOnlyChange(EditorBuffer buffer) {
        persistReadOnly(buffer);
        Tab tab = tabForBuffer(buffer);
        if (tab != null) {
            updateTabMeta(tab, buffer);
        }
        statusBar.refresh();
        if (buffer == activeBuffer()) {
            toolbar.refreshEditState(); // editability change affects cut enablement
            toolbar.schedulePasteStateRefresh(); // ...and paste enablement (a restored shell flips when it lands)
        }
    }

    /** The tab hosting {@code buffer}, or null if not open. */
    @Nullable
    Tab tabForBuffer(EditorBuffer buffer) {
        for (Tab t : editorArea.tabs()) {
            if (t.getUserData() == buffer) {
                return t;
            }
        }
        return null;
    }

    /** Persists whether a file is pinned read-only (View mode), keyed by absolute path. */
    private void persistReadOnly(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<String> list = config.getWorkspaceState().getReadOnlyFiles();
        String key = file.toString();
        if (buffer.isViewMode()) {
            if (!list.contains(key)) {
                list.add(key);
            }
        } else {
            list.remove(key);
        }
        requestSave();
    }

    /**
     * Applies read-only ("View") mode to a freshly opened file: on when the user pinned it read-only
     * in a previous session, or when the file isn't writable on disk. Huge files are already read-only
     * and left untouched.
     */
    private void restoreReadOnly(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || buffer.isReadOnly()) {
            return;
        }
        boolean persisted = config.getWorkspaceState().getReadOnlyFiles().contains(file.toString());
        // Log files open in View mode by default — the log viewer is for reading, and follow-tail still
        // appends programmatically while read-only — but the "Enable Editing" banner lets the user opt in.
        boolean logDefault = logViewer.isEnabled() && buffer.isLog();
        boolean writable = com.editora.vfs.Vfs.isWritableOnDisk(file);
        buffer.setWritableOnDisk(com.editora.vfs.Vfs.isRemote(file) ? writable : null); // the banner cannot ask
        if (shouldOpenReadOnly(persisted, writable) || logDefault) {
            buffer.setViewMode(true);
        }
    }

    /** Whether a file should open in View mode: pinned read-only, or not writable on disk. Pure. */
    public static boolean shouldOpenReadOnly(boolean persisted, boolean writable) {
        return persisted || !writable;
    }

    /**
     * The directory passed to {@code typst compile --root} for a local {@code .typ} file: the nearest
     * {@code typst.toml} ancestor (typst's own project marker), else this window's Editora project root when the
     * file lives inside it, else the file's own folder. Lets a multi-file Typst project resolve
     * {@code #import}/{@code #image} references above the file's folder; a single-file doc is unaffected.
     * Injected into {@link EditorBuffer#setTypstRootResolver} (preview) and {@link TypstCoordinator}
     * (export/print).
     */
    private java.nio.file.Path resolveTypstRoot(java.nio.file.Path file) {
        java.nio.file.Path marker = com.editora.lsp.RootResolver.findMarkerRoot(file, java.util.List.of("typst.toml"));
        if (marker != null) {
            return marker;
        }
        java.nio.file.Path root = com.editora.lsp.RootResolver.resolve(windowProjectRoot(), file, java.util.List.of());
        return root != null ? root : file; // resolve answers null only for a null file
    }

    /** Whether the app (AtlantaFX) theme is dark — seeds/decides the "follow app" preview theme + glyph. */
    boolean appThemeDark() {
        return Themes.isDark(config.getSettings().getTheme());
    }

    /**
     * Routes a Ctrl + mouse-wheel zoom: in Markdown <em>Preview</em> mode it drives the preview's
     * {@code −}/{@code +} zoom (the rendered text), so the editor text zoom is left untouched there;
     * in every other case (Editor or Split) it zooms the editor text. Called from the scene-level
     * scroll filter in {@code App}.
     */
    public void zoomFromWheel(javafx.scene.input.ScrollEvent e) {
        int direction = e.getDeltaY() > 0 ? 1 : -1;
        EditorBuffer b = activeBuffer();
        if (b != null && b.getMarkdownViewMode() == EditorBuffer.MarkdownViewMode.PREVIEW) {
            previews.markdownZoom(direction);
        } else {
            textZoom(direction);
        }
    }

    /**
     * Global text zoom: scales every editor's font on top of the configured size. {@code >0} zooms in,
     * {@code <0} out (±10% steps, clamped 50%–300%), {@code 0} resets to 100%. Persisted in Settings
     * (not shown in the Settings window) and reflected in the status bar.
     */
    public void textZoom(int direction) {
        Settings s = config.getSettings();
        double z = s.getFontZoom();
        if (direction > 0) {
            z += 0.1;
        } else if (direction < 0) {
            z -= 0.1;
        } else {
            z = 1.0;
        }
        z = Math.max(0.5, Math.min(3.0, Math.round(z * 10.0) / 10.0)); // snap to a clean 10% grid
        if (z == s.getFontZoom() && direction != 0) {
            return; // already at the clamp limit
        }
        s.setFontZoom(z);
        requestSave();
        // A font zoom changes no feature gate or theme — apply only the fonts/per-buffer view, not the full
        // settings cascade (editor-theme stylesheet swap + ~20 applySupport() calls). #545
        editorSettings.applyFontsAndPerBufferView(s);
        terminalCoordinator.applySettings(); // terminals zoom with the editors
        statusBar.refresh();
        setStatus(tr("status.textZoom", Math.round(z * 100)));
    }

    /**
     * The UI zoom: scales the whole interface, in every window. {@code >0} zooms in, {@code <0} out (10% steps
     * within {@link Settings#MIN_UI_ZOOM}–{@link Settings#MAX_UI_ZOOM}), {@code 0} goes back to 100%. Saved
     * with the settings; the editor's text keeps its own zoom ({@link #textZoom}).
     */
    public void uiZoom(int direction) {
        Settings s = config.getSettings();
        int now = s.getUiZoom();
        int next = direction > 0
                ? com.editora.editor.UiZoom.in(now)
                : direction < 0 ? com.editora.editor.UiZoom.out(now) : Settings.DEFAULT_UI_ZOOM;
        if (next == now) {
            setStatus(
                    direction > 0
                            ? tr("status.uiZoom.largest", now)
                            : direction < 0 ? tr("status.uiZoom.smallest", now) : tr("status.uiZoom.default"));
            return;
        }
        s.setUiZoom(next);
        requestSave();
        Themes.applyUiZoom(next);
        if (settingsWindow != null) {
            settingsWindow.syncAll();
        }
        setStatus(tr("status.uiZoom", next));
    }

    /** This window's snippets, for {@link WindowManager} to tell about a change made in another window. */
    SnippetCoordinator snippetCoordinator() {
        return snippetCoordinator;
    }

    // --- New file of a known type ("New ▸ …") -----------------------------------------------------

    // --- File templates --------------------------------------------------------------------------

    /**
     * {@code project.editSettings}: opens this project's committed {@code .editora/settings.json}, seeding an
     * explanatory example when it doesn't exist yet.
     *
     * <p>Seeded rather than created empty because the file's whole value is being discoverable and editable
     * by hand — an empty buffer tells nobody which keys exist.
     */
    void editProjectSettings() {
        Path root = windowProjectRoot();
        if (root == null) {
            setError(tr("status.project.settingsNeedProject"));
            return;
        }
        try {
            Path file = com.editora.config.ProjectSettings.migrateLegacyForEditing(root);
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, tr("project.settings.template"));
            }
            fileWorkflows.openPath(file);
            lspCoordinator.reloadProjectSettings(); // an edit here should take effect without a restart
        } catch (java.io.IOException e) {
            setError(tr("status.project.settingsFailed", e.getMessage()));
        }
    }

    // ---- Personal Notes ----

    /** Canonical-path key for a buffer's notes in the store (cheap; no content hashing). */
    static String noteKey(EditorBuffer buffer) {
        return com.editora.config.PathKeys.canonicalKey(buffer.getPath());
    }

    @Nullable
    Tab tabForKey(String fileKey) {
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.getPath() != null && noteKey(b).equals(fileKey)) {
                return tab;
            }
        }
        return null;
    }

    /**
     * Single-line text prompt as an in-scene overlay (replaces {@code TextInputDialog} — see
     * {@link OverlayInput}). {@code onAccept} runs only when the user accepts (Enter / OK) with the raw
     * field text; the caller trims/validates. Cancelling does nothing. Also the {@link OverlayInput.Prompt}
     * the tool-window panels call (so they don't need the overlay host / keymap directly).
     */
    void promptText(String title, String label, @Nullable String initial, Consumer<String> onAccept) {
        promptTextIn(overlayHost, title, label, initial, onAccept);
    }

    /** {@link #promptText} in another of this window's scenes (the Project Map window has its own host). */
    void promptTextIn(
            OverlayHost host, String title, String label, @Nullable String initial, Consumer<String> onAccept) {
        Label promptLabel = new Label(label);
        TextField field = new TextField(initial == null ? "" : initial);
        field.setPrefColumnCount(32);
        // Honor the user's configured keybindings (Emacs caret movement + basic editing) in the field.
        com.editora.command.TextInputKeymap.install(field, keymap);
        VBox body = UiZoom.spacing(new VBox(promptLabel, field), 6);
        OverlayInput.show(
                host, title, body, field, tr("dialog.ok"), null, () -> onAccept.accept(field.getText()), null, false);
    }

    /** Opens the Application Log window: {@code view.debugLog} and Settings → Advanced's "Show Application Log". */
    void showDebugLog() {
        debugLogWindow.show(stage);
    }

    void exportConfig() {
        // Saved HTTP responses (bodies, URLs) and AI API keys are asked about, off by default.
        boolean askHttp = config.shared().hasHttpHistory(),
                askKeys = config.shared().hasApiKeys(),
                askPluginData = config.shared().hasPluginData();
        var choice = askHttp || askKeys || askPluginData
                ? ExportConfigDialog.ask(stage, askHttp, askKeys, askPluginData)
                : java.util.Optional.of(new ExportConfigDialog.Choice(false, false, false));
        if (choice.isEmpty()) {
            return;
        }
        boolean keysLeftOut = askKeys && !choice.get().includeApiKeys();
        BackgroundTasks.Handle task = backgroundTasks.start(tr("dialog.exportConfig.title"));
        // Off the FX thread: the export waits for pending writes and compresses the whole config directory.
        config.shared()
                .exportConfigAsync(
                        choice.get().includeHttpHistory(),
                        choice.get().includeApiKeys(),
                        choice.get().includePluginData())
                .whenComplete((zip, failure) -> Platform.runLater(() -> {
                    task.done();
                    String detail = failure == null ? zip.toString() : String.valueOf(failure.getMessage());
                    String done = keysLeftOut ? "status.config.exportedNoKeys" : "status.config.exported";
                    setStatus(tr(failure == null ? done : "status.config.exportFailed", detail));
                    Alert alert = Dialogs.styled(
                            new Alert(failure == null ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR));
                    alert.initOwner(stage);
                    alert.setTitle(tr("dialog.exportConfig.title"));
                    alert.setHeaderText(
                            tr(failure == null ? "dialog.exportConfig.done" : "dialog.exportConfig.failed"));
                    alert.setContentText(detail);
                    alert.showAndWait();
                }));
    }

    /** Re-runs the spell pass over every open buffer in this window (after the user dictionary changed). */
    void refreshSpellAllTabs() {
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.refreshSpell();
            }
        }
        settingsWindow.syncDictionaryList(); // an open Spell Check page lists the words too
    }

    /**
     * Applies the editor color theme: swaps the override stylesheet on the scene (Primer Light uses
     * none — the defaults in app.css/syntax.css) and updates each buffer's current-line highlight.
     * Safe to call before the scene exists (the stylesheet swap is skipped until it does).
     */
    public void applyEditorTheme(String themeName) {
        if (stage != null && stage.getScene() != null) {
            ObservableList<String> sheets = stage.getScene().getStylesheets();
            String wanted = EditorThemes.stylesheetFor(themeName);
            // Removing and re-adding the same sheet forces a full scene CSS reapply. This runs on every
            // settings apply (most of which don't touch the theme), so only swap when it actually changed.
            if (!java.util.Objects.equals(wanted, currentEditorThemeCss)
                    || (wanted != null && !sheets.contains(wanted))) {
                if (currentEditorThemeCss != null) {
                    sheets.remove(currentEditorThemeCss);
                }
                currentEditorThemeCss = wanted;
                if (wanted != null && !sheets.contains(wanted)) {
                    sheets.add(wanted);
                }
            }
        }
        if (terminalCoordinator != null) { // runs on every settings apply, theme change or not
            terminalCoordinator.applySettings();
            terminalCoordinator.applyTheme();
        }
        Color highlight = EditorThemes.lineHighlightFor(themeName);
        Color mmText = EditorThemes.minimapTextFor(themeName);
        Color mmViewport = EditorThemes.minimapViewportFor(themeName);
        Color editorBg = EditorThemes.editorBackgroundFor(themeName);
        Color editorFg = EditorThemes.editorForegroundFor(themeName);
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.setLineHighlightColor(highlight);
                buffer.setMinimapColors(mmText, mmViewport);
                buffer.setFoldPreviewColors(editorBg, editorFg);
            }
        }
    }

    void cancel() {
        EditorBuffer completing = activeBuffer();
        if (completing != null && completing.completionShowing()) {
            completing.cancelCompletion(); // C-g cancels the inline ghost (the popup is caught in-buffer)
            setStatus("");
            return;
        }
        if (palette.isShown()) {
            palette.hide();
        } else if (findBar.isShown()) {
            findBar.hideBar();
        } else if (macroCoordinator.cancelRecording()) {
            return; // nothing else to dismiss: C-g abandons the macro being recorded (it says so itself)
        } else {
            editing.deactivateMark();
            editing.collapseCarets(); // C-g leaves one caret, as Escape does
            CodeArea area = activeArea();
            if (area != null) {
                area.deselect();
            }
        }
        setStatus("");
    }

    /** The selection policy for caret-movement commands: extend from the mark when it's active. */
    SelectionPolicy selPolicy() {
        return editing.markActive() ? SelectionPolicy.ADJUST : SelectionPolicy.CLEAR;
    }

    // --- Keyboard macros (record / replay / save / run): see MacroCoordinator ---

    /** Drops stale {@code macro.run.*} commands and re-registers the current saved set; called per window by
     *  {@link WindowManager}'s macros broadcast. Delegates to the coordinator, and refreshes this window's
     *  Settings → Macros list too (so an already-open Settings window shows a just-recorded macro live). */
    void refreshSavedMacroCommands() {
        macroCoordinator.refreshCommands();
        settingsWindow.refreshMacrosList();
    }

    /** After the saved-macro set changes, re-register the synthetic commands in every open window. */
    void refreshSavedMacroCommandsAllWindows() {
        if (windowManager != null) {
            windowManager.broadcastMacrosChanged();
        } else {
            refreshSavedMacroCommands();
        }
    }

    // --- External Tools (user-defined CLI commands run on the active file/buffer) ---

    /** True when External Tools are available (always, except in Simple UI mode — the list is empty by default). */
    private boolean externalToolsEnabled() {
        return !chrome.simpleModeActive();
    }

    /** Drops stale {@code externalTool.run.*} commands and re-registers the current set; called per window by
     *  {@link WindowManager}'s external-tools broadcast after a Settings edit. Delegates to the coordinator. */
    void refreshExternalToolCommands() {
        externalToolCoordinator.refreshCommands();
    }
}
