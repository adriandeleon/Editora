package com.editora.ui;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/** Controls the main window: tabbed editors, menu actions, palette/find overlays, and status bar. */
public class MainController implements com.editora.mcp.McpBridge {

    static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MainController.class.getName());
    static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    // The parts of this window's code that live in classes of their own, each holding this controller. Declared
    // before every other field: the field initializers below take method references to them, and a method
    // reference reads its target when it is made.

    /** How the key dispatcher is wired to this window; see {@link KeyDispatcherWiring}. */
    final KeyDispatcherWiring keyWiring = new KeyDispatcherWiring(this);

    /** About, documentation, the Application Log and config export; see {@link WindowAppActions}. */
    final WindowAppActions appActions = new WindowAppActions(this);

    /** The run configuration and main class suggested for the active file; see {@link WindowRunSuggestions}. */
    final WindowRunSuggestions runSuggestions = new WindowRunSuggestions(this);

    /** The Welcome and Doctor tabs and the recent-files menu; see {@link WindowWelcome}. */
    final WindowWelcome welcome = new WindowWelcome(this);

    /** Find-bar, multi-caret, AceJump, undo-checkpoint and cancel commands; see {@link WindowEditCommands}. */
    final WindowEditCommands editCommands = new WindowEditCommands(this);

    /** The window's close, quit, disposal and focus-regain steps; see {@link WindowLifecycle}. */
    final WindowLifecycle lifecycle = new WindowLifecycle(this);

    /** What is enabled or available now, for the palette, the menus and the tool windows; see {@link WindowGates}. */
    final WindowGates windowGates = new WindowGates(this);

    /** A file's stored folds and read-only pin, saved and re-applied; see {@link WindowFileState}. */
    final WindowFileState fileState = new WindowFileState(this);

    /** Zen and Expert modes, zoom, the editor theme and the tool-window view commands; see {@link WindowViewModes}. */
    final WindowViewModes viewModes = new WindowViewModes(this);

    /** Background, preview and asynchronous opens of files; see {@link WindowFileOpens}. */
    final WindowFileOpens fileOpens = new WindowFileOpens(this);

    /** What a buffer is wired to as its tab is added or restored; see {@link WindowBufferWiring}. */
    final WindowBufferWiring bufferWiring = new WindowBufferWiring(this);

    /** The build-tool coordinators and the Git/GitHub command transcripts; see {@link WindowBuildTools}. */
    final WindowBuildTools buildTools = new WindowBuildTools(this);

    /** Folder trust as this window asks and changes it; see {@link WindowTrust}. */
    final WindowTrust trust = new WindowTrust(this);

    /** The MCP server and the Claude Code IDE trial as this window sees them; see {@link WindowMcpSupport}. */
    final WindowMcpSupport mcpSupport = new WindowMcpSupport(this);

    /** The tool-window panels built on first use, and the editor area's footer; see {@link WindowPanels}. */
    final WindowPanels panels = new WindowPanels(this);

    /** Projects, the window title, and files the Project tree renames or deletes; see {@link WindowProjectActions}. */
    final WindowProjectActions projectActions = new WindowProjectActions(this);

    /** Tab closing, reopening, pinning, renaming and stepping, and editor groups; see {@link WindowTabActions}. */
    final WindowTabActions tabActions = new WindowTabActions(this);

    @FXML
    BorderPane root;

    @FXML
    BorderPane workspace;

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

    /** The keys that can follow a pending multi-key prefix, listed after a moment (KBD-14); null before install. */
    @Nullable
    PrefixKeyHint prefixHint;

    /** Replaces where the Run Configurations page is opened. Tests only. */
    void setRunConfigEditorForTest(java.util.function.Consumer<String> editor) {
        runConfigurations.runConfigEditor = editor == null ? runConfigurations::openRunConfigEditor : editor;
    }

    @FXML
    VBox topBox;

    @FXML
    VBox bottomBox;

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
    final DebugLogWindow debugLogWindow = new DebugLogWindow();

    /** Shared in-scene overlay host for the command palette + pickers (replaces focus-stealing Popups). */
    final OverlayHost overlayHost = new OverlayHost();

    com.editora.snippet.SnippetManager snippets;
    SnippetCoordinator snippetCoordinator;

    /** Shared across windows (owned by WindowManager); plugin classes load once, instances are per-window. */
    com.editora.plugin.@Nullable PluginManager pluginManager;
    /** Plugin support (discovery/apply/install + the per-window PluginContext); see {@link PluginCoordinator}. */
    PluginCoordinator pluginCoordinator;

    com.editora.completion.CompletionEngine completion;

    /** The Project panel; null until {@link #projectPanel()} builds it. */
    ProjectPanel projectPanel;

    /** What the Project tree is rooted at (null: the placeholder), applied to the panel when it is built. */
    @Nullable
    Path projectRoot;
    /** The last Git status pushed to the Project tree, applied to the panel when it is built. */
    java.util.@Nullable Map<java.nio.file.Path, com.editora.git.GitFileStatus> projectGitStatus;

    ProjectDeleteCoordinator projectDeletes;
    ProjectManager projects;
    /** The multi-window coordinator (null in single-window/test use); set right after {@link #init}. */
    @Nullable
    WindowManager windowManager;
    /** This window's project ({@code null} = the no-project/global window). */
    @Nullable
    Project windowProject;
    /** This window's project key ({@code ""} = global), kept in sync with {@link #windowProject}. */
    String projectKey = "";

    QuickOpen<Project> projectPicker;

    // Auto save. Mode keys: "off" | "afterDelay" | "onFocusChange".

    ToolWindowManager toolWindows;
    ToolWindow projectToolWindow;
    ToolWindow structureToolWindow;
    ToolWindow bookmarksToolWindow;
    ToolWindow notesToolWindow;
    ToolWindow reviewsToolWindow;
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
    static @Nullable MainController mcpOwner;
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
    final Map<BuildTool, ToolWindow> buildToolWindows = new java.util.EnumMap<>(BuildTool.class);
    /** The shared tabbed "Output" window — one tab per build tool that runs (Maven/npm/Cargo/Go/Gradle),
     *  fed by every {@link BuildCoordinator}; its stripe appears when any tool's marker is detected, and it
     *  auto-opens on a run. The panel is built by its first open or its first tab ({@link BuildOutput}). */
    final BuildOutput buildOutputPanel = new BuildOutput();

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
    /** The project JDK, Maven and Gradle commands and jkit's (install, upgrade, default, remove). */
    ToolchainCommands toolchainCommands;

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
    NoUndoGuard noUndoGuard;

    ToolbarCoordinator toolbarCoordinator;
    /** The toolbar's buttons, fixed tail and button enablement; see {@link WindowToolbar}. */
    final WindowToolbar toolbar = new WindowToolbar(this);

    ToolWindow fileHistoryToolWindow;

    Switcher switcher;
    /** The tab-selection and tab-list listeners, and the MRU order they keep; see {@link EditorTabListeners}. */
    final EditorTabListeners tabListeners = new EditorTabListeners(this);
    /** Pinned tabs (identity-based): kept grouped at the front and skipped by bulk-close actions. */
    final Set<Tab> pinned = Collections.newSetFromMap(new IdentityHashMap<>());

    /** The app-wide recent-files list ({@code SharedConfig}); the search/agent histories are read from there too. */
    RecentFiles recentFiles;

    /** The VSCode-style Welcome page, shown in its own tab when no file is open (or via {@code view.welcome}).
     *  Built by {@link #welcomePane()} when that tab first opens: a window that opens on files builds none. */
    @Nullable
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
    javafx.application.@Nullable HostServices hostServices;

    public void installZenOverlay(StackPane sceneRoot) {
        chrome.installZenOverlay(sceneRoot);
    }

    /**
     * Puts the prefix hint into the window's scene-root stack, kept clear of the status bar. The key dispatcher
     * tells it the pending prefix ({@link #setKeyDispatcher}); see {@link PrefixKeyHint}.
     */
    public void installPrefixHint(StackPane sceneRoot) {
        PrefixKeyHint hint = new PrefixKeyHint(
                registry, keymap, () -> bottomBox.isVisible() && bottomBox.isManaged() ? bottomBox.getHeight() : 0);
        hint.install(sceneRoot);
        prefixHint = hint;
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

    /**
     * A stripe button's press: its tool window's command, when there is one. The Terminal's button stays a
     * plain toggle, because its command gives an open terminal the keyboard before it hides it (a decision
     * kept: it is what the key does in VS Code and IntelliJ), and a click on the button of a window on screen
     * has always hidden it.
     */
    private boolean runStripeCommand(String id) {
        if ("tool.terminal".equals(id) || registry.get(id).isEmpty()) {
            return false;
        }
        registry.run(id);
        return true;
    }

    @Override
    public boolean executeCommand(String id) {
        return mcpBridge.executeCommand(id);
    }

    @Override
    public @Nullable String commandRefusal(String id) {
        return mcpBridge.commandRefusal(id);
    }

    @Override
    public @Nullable String commandDenial(String id, String agent) {
        return mcpBridge.commandDenial(id, agent); // the user is asked before an agent's command runs
    }

    @Override
    public String openFile(String path, int line, int col) {
        return mcpBridge.openFile(path, line, col);
    }

    @Override
    public @Nullable String editBuffer(@Nullable String path, String oldText, String newText, boolean replaceAll) {
        return mcpBridge.editBuffer(path, oldText, newText, replaceAll);
    }

    @Override
    public @Nullable String replaceBuffer(@Nullable String path, String newText) {
        return mcpBridge.replaceBuffer(path, newText);
    }

    @Override
    public ProposalOutcome proposeEdit(
            @Nullable String path,
            @Nullable String oldText,
            String newText,
            boolean replaceAll,
            boolean wholeBuffer,
            @Nullable String why,
            String agent) {
        return mcpBridge.proposeEdit(path, oldText, newText, replaceAll, wholeBuffer, why, agent);
    }

    @Override
    public boolean editsNeedApproval() {
        return mcpBridge.editsNeedApproval();
    }

    @Override
    public ProposalOutcome reviewProposal(@Nullable String path, String newText, @Nullable String why, String agent) {
        return mcpBridge.reviewProposal(path, newText, why, agent);
    }

    @Override
    public java.util.List<Location> navigate(@Nullable String path, int line, int column, boolean references) {
        return mcpBridge.navigate(path, line, column, references);
    }

    @Override
    public String hover(@Nullable String path, int line, int column) {
        return mcpBridge.hover(path, line, column);
    }

    @Override
    public java.util.List<WorkspaceSymbol> workspaceSymbols(String query) {
        return mcpBridge.workspaceSymbols(query);
    }

    @Override
    public java.util.List<HistoryEntry> localHistory(String path) {
        return mcpBridge.localHistory(path);
    }

    @Override
    public String localHistoryText(String path, int index) {
        return mcpBridge.localHistoryText(path, index);
    }

    @Override
    public TestSummary testResults(boolean includePassed) {
        return mcpBridge.testResults(includePassed);
    }

    @Override
    public DebugState debugState() {
        return mcpBridge.debugState();
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

    @Override
    public java.util.Map<String, Long> bufferStamps() {
        return mcpBridge.bufferStamps(); // the resource watch: without this, the default (every file at 0)
    }

    @Override
    public java.util.List<ReviewThreadInfo> listReviews(@Nullable String path, boolean all) {
        return mcpBridge.listReviews(path, all);
    }

    @Override
    public ReviewAdded addReview(
            String path,
            int startLine,
            int endLine,
            String body,
            @Nullable String suggestion,
            @Nullable String severity,
            @Nullable String category,
            String agent) {
        return mcpBridge.addReview(path, startLine, endLine, body, suggestion, severity, category, agent);
    }

    @Override
    public @Nullable String replyReview(String threadId, String body, @Nullable String suggestion, String agent) {
        return mcpBridge.replyReview(threadId, body, suggestion, agent);
    }

    @Override
    public @Nullable String resolveReview(String threadId, String note, String agent) {
        return mcpBridge.resolveReview(threadId, note, agent);
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
            // A Save picked in the question writes off the FX thread: the window stays, and closes once it has landed.
            if (!terminalCoordinator.confirmCloseWithBusyTerminals()
                    || !closes.confirmCloseAll(lifecycle::closeAfterSave)) {
                e.consume();
                return;
            }
            // Defensive: a disposal hiccup must never block the close (or it would look like the window
            // can't be closed). Let JavaFX close this stage normally afterwards; the app keeps running
            // while other windows remain (implicitExit quits only when the last window closes).
            try {
                lifecycle.disposeWindow();
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
                lifecycle.onWindowFocusGained();
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        // Right-click menus show each command's chord beside it, read from the live keymap as the menu bar is.
        com.editora.editor.MenuChords.setChords(keymap::displayChords);
        this.macroCoordinator = new MacroCoordinator(config, registry, coordinatorHost, new MacroOpsHost(this));
        // Built here (not as a field initializer) because NotesPanel's constructor reads config.getNotes().
        this.notesCoordinator = new NotesCoordinator(coordinatorHost, new NotesOpsHost(this));
        this.reviewCoordinator = new ReviewCoordinator(coordinatorHost, config.shared(), new ReviewOpsHost(this));
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
            return c -> Chrome.paletteEnabled(c.id(), gates, context) && windowGates.jumpPossible(c.id());
        });
        // Tooltip on a grayed row explaining why, and naming the command that would re-enable it.
        this.palette.setDisabledReason(c -> disabledCommandReason(c.id()));
        this.findBar = new FindReplaceBar(this::activeBuffer, this::setStatus);
        this.macroCoordinator.workPending = findBar::busy; // a replayed key waits for the search a step started
        this.findBar.setOnSelectAllMatches(this::selectAllFindMatches);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        statusBar.setOnOpenDoctor(welcome::showDoctor); // the message log's "Open Doctor" (review C4)
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
                viewModes::setZenMode,
                viewModes::setExpertMode,
                fileWorkflows::openPath,
                appActions::exportConfig,
                appActions::showDebugLog);
        this.settingsWindow.setPluginManager(pluginManager); // shared; lists discovered plugins on the Plugins page
        this.settingsWindow.setStatusSink(this::setStatus);
        this.settingsWindow.setRemovalUndo(removalUndo); // a removal made in Settings is undone from this window
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
                new PluginOpsHost(this));
        SettingsWindowWiring.wire(
                this, settingsWindow, templateActions.templates); // its pages' actions, probes and suggestions
        debugLogWindow.setSessionFile(DebugLog.sessionFile(config.getConfigDir()));
        debugLogWindow.setOnReport(() -> registry.run("help.reportProblem"));
        this.switcher = new Switcher(
                () -> new java.util.ArrayList<>(editorArea.tabs()), // list files in tab order
                () -> editorArea.selectedTab(),
                this::activateAndFocusTab,
                tabActions::closeTabFromSwitcher);
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
        // The commands behind buttons that only the mouse reached, each area's in a class of its own.
        MapButtonCommands.register(this);
        DiffGitButtonCommands.register(this);
        DebugBuildTestButtonCommands.register(this);
        PanelViewerButtonCommands.register(this);
        HelpCommands.register(this);
        FindBarCommands.register(this);
        ListRowCommands.register(this); // the rows of the lists' right-click menus
        ProjectRowCommands.register(this); // the rows of the Project tree's file menu
        GitRowCommands.register(this); // the rows of the Git windows' menus
        PanelRowCommands.register(this); // the rows of the smaller tool windows' menus
        DebugRowCommands.register(this); // the rows of the Debug window's menus
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

    // --- plugins ----------------------------------------------------------------------------------

    /** Injected by {@link WindowManager} before {@link #init}; shared across windows (classes load once). */
    void setPluginManager(com.editora.plugin.PluginManager pm) {
        this.pluginManager = pm; // the per-window PluginRegistry/PluginInstaller are built in PluginCoordinator
    }

    /** Stops this window's plugins (window close); WindowManager calls this by name. */
    void disposePlugins() {
        if (pluginCoordinator != null) {
            pluginCoordinator.disposePlugins();
        }
    }

    /**
     * The debug manager of the session the window shows: the focused one of {@link DebugCoordinator#sessions()}, or
     * this window's first manager before the coordinator is made.
     */
    com.editora.dap.DapManager focusedDap() {
        return debugCoordinator == null ? dapManager : debugCoordinator.dap();
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
        mcpSupport.refreshClaudeIde(); // the lock file Claude Code finds this window by names its folder
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

    /** Opens a link through the {@link ExternalLinks} allowlist (http/https/mailto) — never a bare showDocument. */
    void openExternalUrl(String url) {
        ExternalLinks.open(url, null, null, hostServices, fileWorkflows::openPath, this::setStatus);
    }

    // --- Projects (single-folder, VSCode-style) ---

    /** Loads the projects index and, if a project is active, points the session at it before restore. */
    private void setupProjects() {
        // The projects index is shared across all windows (one source of truth), so use the shared one.
        projects = config.projects();
        projectPicker = new QuickOpen<>(
                tr("project.picker.title"),
                tr("project.picker.prompt"),
                projectActions::projectsWithNoProject,
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

    /**
     * {@code project.openDialog}: the system's folder dialog — the toolbar button, and the Welcome page's and the
     * empty editor's Open Folder, as File: Open… is for a file (the palette and the keys use the finder).
     */
    @FXML
    void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.openFolder.title"));
        java.io.File dir = chooser.showDialog(stage);
        if (dir != null) {
            openProjectRoot(dir.toPath());
        }
    }

    boolean projectsEnabled() {
        return config.getSettings().isProjectSupport();
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

    private void setupToolWindows() {
        editorArea.setDraggedTabSource(tabHeaders::draggedTab); // drops onto a group body move/split it
        // The menu bar sits above the toolbar. Built from the registry, so it needs no per-command wiring.
        menuBar = new MainMenuBar(registry, this::invertBindings, this::paletteGates, this::paletteContext, id -> {
            if (registry.get(id).isPresent()) {
                registry.run(id);
            }
        });
        menuBar.setOnUnavailable(this::setStatus); // a grayed row chosen says why instead of running (KBD-04)
        if (stage != null) {
            com.editora.editor.RowReasons.sayIn(stage, this::setStatus); // and so does a right-click menu's row
        }
        // An extended window has no system title bar, so the menu bar takes its place; asking the stage
        // rather than re-reading the setting means the two can never disagree for a window already built.
        topBox.getChildren()
                .add(
                        0,
                        stage != null && stage.getStyle() == javafx.stage.StageStyle.EXTENDED
                                ? ExtendedWindow.header(menuBar.node(), stage)
                                : menuBar.node());
        toolWindows = new ToolWindowManager(workspace, panels.editorFooter(), config, keymap);
        toolWindows.setStripeCommand(this::runStripeCommand);
        projectDeletes = new ProjectDeleteCoordinator(
                path -> windowManager == null ? buffersAtOrUnderLocal(path) : windowManager.buffersAtOrUnder(path),
                buffer -> projectActions.ownerOf(buffer).revealBufferLocal(buffer),
                buffer -> projectActions.ownerOf(buffer).fileWorkflows.hasPendingSave(buffer),
                (buffer, afterSave, failed) ->
                        projectActions.ownerOf(buffer).closes.askCloseIfDirty(buffer, afterSave, failed),
                fileWorkflows::invalidatePendingWrite,
                (path, completion) -> historyCoordinator.captureBeforeDeleteDurably(path, completion),
                this::setStatus);
        exports.activeTabContent =
                () -> tabPath(activeTab()) == null ? null : activeTab().getUserData();
        // Breadcrumb crumbs offer the same Reveal / Open Terminal as the Project tree (Reveal for local files only).
        breadcrumb.setOnReveal((p, dir) -> revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        breadcrumb.setOnOpenTerminal((p, dir) -> tabActions.openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        bookmarkCoordinator.setOnChanged(panels::refreshProjectMarkersIfBuilt);
        notesCoordinator.setOnChanged(panels::refreshProjectMarkersIfBuilt);
        // Every tool window below hands ToolWindow a supplier: the panel is built on the window's first open
        // (ToolWindow.getContent), or by the first caller that needs it — never before first paint for a
        // window that is closed. restore() opens the saved-open windows, which builds exactly those.
        projectToolWindow =
                WindowPanels.toolWindow("project", ToolWindow.Side.RIGHT, Icons::project, this::projectPanel);
        structureToolWindow =
                WindowPanels.toolWindow("structure", ToolWindow.Side.RIGHT, Icons::structure, this::structurePanel);
        bookmarksToolWindow = WindowPanels.toolWindow(
                "bookmarks", ToolWindow.Side.RIGHT, Icons::bookmark, bookmarkCoordinator::panel);
        notesToolWindow =
                WindowPanels.toolWindow("notes", ToolWindow.Side.RIGHT, Icons::notes, notesCoordinator::panel);
        reviewsToolWindow =
                WindowPanels.toolWindow("reviews", ToolWindow.Side.RIGHT, Icons::review, reviewCoordinator::panel);
        fileInfoToolWindow = WindowPanels.toolWindow(
                "file-information",
                "toolwindow.file-information",
                ToolWindow.Side.RIGHT,
                Icons::about,
                this::fileInfoPanel,
                "tool.fileInformation");
        undoHistoryToolWindow =
                WindowPanels.toolWindow("undoHistory", ToolWindow.Side.RIGHT, Icons::history, this::undoHistoryPanel);
        commitToolWindow = WindowPanels.toolWindow("commit", ToolWindow.Side.RIGHT, Icons::git, this::gitPanel);
        gitLogOps = gitWindows.gitLogActions();
        gitWindows.listenTo(git);
        gitLogToolWindow = WindowPanels.toolWindow("gitLog", ToolWindow.Side.BOTTOM, Icons::gitLog, this::gitLogPanel);
        githubToolWindow = WindowPanels.toolWindow("github", ToolWindow.Side.BOTTOM, Icons::github, this::githubPanel);
        historyCoordinator =
                new HistoryCoordinator(coordinatorHost, diffCoordinator, new HistoryOpsHost(this), config.shared());
        noUndoGuard = new NoUndoGuard(historyCoordinator::recordSafetyCopy, coordinatorHost::setStatus);
        // Before an agent turn first changes an open file, File History keeps it as it was, named for the turn.
        agentChanges.setOnFirstWrite((turn, file, text) -> {
            String label = tr("agent.changes.historyLabel", AgentChangeSets.name(turn));
            Runnable record = () -> historyCoordinator.recordAgentTurn(file, text, label);
            if (Platform.isFxApplicationThread()) {
                record.run();
            } else {
                Platform.runLater(record);
            }
        });
        git.attachWindow(diffCoordinator, buffer -> addBuffer(buffer, true)); // stash review, patches, revision tabs
        fileHistoryToolWindow = WindowPanels.toolWindow(
                "fileHistory", ToolWindow.Side.BOTTOM, Icons::history, historyCoordinator::panel);
        searchToolWindow =
                WindowPanels.toolWindow("search", ToolWindow.Side.RIGHT, Icons::findInFiles, searchCoordinator::panel);
        todoToolWindow = WindowPanels.toolWindow("todo", ToolWindow.Side.BOTTOM, Icons::todo, todoCoordinator::panel);
        markdownLintToolWindow = WindowPanels.toolWindow(
                "markdownLint", ToolWindow.Side.BOTTOM, Icons::warning, this::markdownLintPanel);
        problemsToolWindow = WindowPanels.toolWindow(
                "problems", ToolWindow.Side.BOTTOM, Icons::problems, lspCoordinator::problemsPanel);
        referencesToolWindow = WindowPanels.toolWindow(
                "references", ToolWindow.Side.BOTTOM, Icons::find, lspCoordinator::referencesPanel);
        hierarchyToolWindow = WindowPanels.toolWindow(
                "hierarchy", ToolWindow.Side.BOTTOM, Icons::structure, lspCoordinator::hierarchyPanel);
        runToolWindow = WindowPanels.toolWindow("run", ToolWindow.Side.BOTTOM, Icons::run, runCoordinator::panel);
        terminalToolWindow = WindowPanels.toolWindow(
                "terminal", ToolWindow.Side.BOTTOM, Icons::terminal, terminalCoordinator::panel);
        runToolWindow.busyProperty().bind(runCoordinator.runningProperty()); // spinner badge on its stripe button
        debugCoordinator =
                new DebugCoordinator(coordinatorHost, dapManager, lspManager, lspCoordinator, new DebugOpsHost(this));
        debugToolWindow =
                WindowPanels.toolWindow("debug", ToolWindow.Side.BOTTOM, Icons::debug, debugCoordinator::view);
        debugCoordinator.setProgramTerminal(terminalCoordinator::runProgram); // Settings → Debug → in the Terminal
        debugToolWindow.setFirstOpenShare(0.42); // stack, variables and console need rows (review C4)
        externalToolToolWindow = WindowPanels.toolWindow(
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
        buildTools.installCommandLogs();
        buildOutputToolWindow = new ToolWindow(
                "buildOutput",
                tr("toolwindow.buildOutput"),
                ToolWindow.Side.BOTTOM,
                Icons::tools,
                buildOutputPanel::panel, // built on first open, or by the first tab written while closed
                "tool.buildOutput");
        testResultsToolWindow = WindowPanels.toolWindow(
                "testResults", ToolWindow.Side.BOTTOM, Icons::testResults, testRunCoordinator::panel);
        // Mirror a recognized `test` run of any build tool into the Test Results window.
        buildCoordinators.forEach(c -> c.setTestRunHook(testRunCoordinator));
        installCoordinator = new InstallCoordinator(coordinatorHost, new InstallOpsHost(this));
        toolchainCommands = new ToolchainCommands(
                coordinatorHost, lspCoordinator::projectScope, lspCoordinator::reloadProjectSettings);
        remoteCoordinator = new RemoteCoordinator(coordinatorHost, new RemoteOpsHost(this));
        containerCoordinator = new ContainerCoordinator(coordinatorHost, new ContainerOpsHost(this));
        remoteToolWindow =
                WindowPanels.toolWindow("remote", ToolWindow.Side.RIGHT, Icons::remote, remoteCoordinator::panel);
        agentToolWindow =
                WindowPanels.toolWindow("agent", ToolWindow.Side.RIGHT, Icons::agent, agentCoordinator::panel);
        toolWindows.register(projectToolWindow);
        toolWindows.register(structureToolWindow);
        toolWindows.register(bookmarksToolWindow);
        toolWindows.register(notesToolWindow);
        toolWindows.register(reviewsToolWindow, false); // stripe off by default: opened from its command or a thread
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

    // --- Git integration -------------------------------------------------------------------------

    /** Effective Local File History gate: the setting, but off in Simple UI mode (saved setting unchanged). */
    boolean localHistoryEnabled() {
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
    void onUndo() {
        editing.onUndo();
    }

    @FXML
    void onRedo() {
        editing.onRedo();
    }

    @FXML
    void onCut() {
        editing.onCut();
    }

    @FXML
    void onCopy() {
        editing.onCopy();
    }

    @FXML
    void onPaste() {
        editing.onPaste();
    }

    /** Shows the find/replace bar, or hides it if it's already open. */
    @FXML
    void onFind() {
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
    void onRunSelectedConfig() {
        runConfigurations.onRunSelectedConfig();
    }

    @FXML
    void onDebugSelectedConfig() {
        runConfigurations.onDebugSelectedConfig();
    }

    @FXML
    void onStopRun() {
        runConfigurations.onStopRun();
    }

    private final WindowCommandRegistrar windowCommands = new WindowCommandRegistrar(new WindowCommandHost(this));

    final NavigationCoordinator navigation = new NavigationCoordinator(new NavigationHost(this));

    final PreviewCoordinator previews = new PreviewCoordinator(new PreviewHost(this));

    final GitWindowCoordinator gitWindows = new GitWindowCoordinator(new GitWindowHost(this));

    final WindowChromeCoordinator chrome = new WindowChromeCoordinator(new WindowChromeHost(this));

    /** What agents (the AI Agent panel, MCP clients) changed, turn by turn, for Agent: Review Changes. */
    final AgentChangeSets agentChanges = new AgentChangeSets(System::currentTimeMillis);

    final WindowMcpBridge mcpBridge = new WindowMcpBridge(new WindowMcpHost(this));

    /** Hover, definitions, references and the Map on a diff's file side. */
    final DiffCodeIntel diffIntel = new DiffCodeIntel(this);

    final FileWorkflowCoordinator fileWorkflows = new FileWorkflowCoordinator(new FileWorkflowHost(this));

    @FXML
    void onSave() {
        fileWorkflows.onSave();
    }

    @FXML
    void onSaveAs() {
        fileWorkflows.onSaveAs();
    }

    final WindowSessionCoordinator sessions = new WindowSessionCoordinator(new WindowSessionHost(this));

    final TestNavigationCoordinator testNavigation = new TestNavigationCoordinator(new TestNavigationHost(this));

    final InstallPromptCoordinator installPrompts = new InstallPromptCoordinator(new InstallPromptHost(this));

    final CoordinatorHost coordinatorHost = new WindowServicesHost(this);

    /** This window's small removals, taken back by Edit: Undo Last Removal; emptied when the window closes. */
    final RemovalUndo removalUndo = new RemovalUndo(this::setStatus);

    /** Crash recovery for this window's unsaved buffers (see {@link RecoveryCoordinator}). */
    final RecoveryCoordinator recovery =
            new RecoveryCoordinator(coordinatorHost, fileWorkflows, fileState::restorePerFileState, () -> projectKey);

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
            new ExternalToolCoordinator(coordinatorHost, new ExternalToolOpsHost(this));

    { // An export, a print or an external tool given an encrypted file's text asks first (ADR 0017).
        exports.plaintextOut = b -> fileWorkflows.encryption().confirmPlaintextOut(b, tr("encryption.out.export"));
        externalToolCoordinator.plaintextOut =
                b -> fileWorkflows.encryption().confirmPlaintextOut(b, tr("encryption.out.tool"));
    }

    /** Build tools (Maven/npm/…): one coordinator per tool — a toolbar icon + actions popup parsed from the
     *  active project's marker file, streaming tasks to a per-tool console (see {@link BuildCoordinator}). */
    final List<BuildCoordinator> buildCoordinators = buildTools.createBuildCoordinators();

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
                    (file, text, done) -> historyCoordinator.recordBeforeReplace(
                            file, text, com.editora.config.HistoryRevision.REASON_LINK_UPDATE, done)));

    final TerminalCoordinator terminalCoordinator = new TerminalCoordinator(coordinatorHost, new TerminalOpsHost(this));

    final TodoCoordinator todoCoordinator = new TodoCoordinator(coordinatorHost, new TodoOpsHost(this));

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

    /** Local code review: the threads on the open files, their cards, the Review Comments window. */
    ReviewCoordinator reviewCoordinator;

    // --- HTTP Client (.http request runner + response tool window) -----------------------------------

    /** The HTTP Client feature; see {@link HttpClientCoordinator}. Takes the shared host + http-specific ops. */
    final HttpClientCoordinator httpClient = new HttpClientCoordinator(coordinatorHost, new HttpClientOpsHost(this));

    /** The {@code .http} editor's unresolved-variable marks and {@code {{} completion. */
    final HttpVariableHints httpVariableHints = new HttpVariableHints(new HttpVariableSourceHost(this));

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

    /** command id -> the chord to show for it, in the active keymap's notation (see KeymapManager.displayChords). */
    java.util.Map<String, String> invertBindings() {
        return keymap.displayChords();
    }

    @FXML
    void onSplitVertical() {
        tabActions.toggleSplit(EditorBuffer.Split.SIDE_BY_SIDE);
    }

    @FXML
    void onSplitHorizontal() {
        tabActions.toggleSplit(EditorBuffer.Split.STACKED);
    }

    /** A startup file to open, with an optional 1-based line/column ({@code 0} = unspecified). */
    public record OpenTarget(Path file, int line, int column) {}

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
        bufferWiring.wireBuffer(buffer, editorConfig);
        Tab tab = addContentTab(buffer, false); // added to the strip; selected below (focus the area, not the node)
        bufferWiring.attachBufferTab(tab, buffer);
        if (select) {
            editorArea.select(tab);
            buffer.getArea().requestFocus();
        }
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
        installContentTabMenu(tab); // every tab of the strip has the tab operations; a buffer's menu replaces it
        tab.setOnCloseRequest(e -> {
            if (!closes.confirmClose(tab, () -> closeTab(tab))) {
                e.consume();
            } else {
                tabActions.rememberClosed(tab);
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
    final TabHeaders tabHeaders =
            new TabHeaders(() -> editorArea, tab -> pinned.contains(tab), MainController::tabPath);

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
    void onNewFromTemplate() {
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
     * The single tab reused for files being browsed rather than opened, or {@code null} when there is none.
     *
     * <p>Browsing is how navigation is actually used — arrowing a picker, following a definition to see
     * what something is — and without this every glance costs a permanent tab, so the strip fills with
     * files nobody chose to keep. One reusable slot bounds that at one.
     */
    @Nullable
    Tab previewTab;

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

    /** Files whose tabs the user closed in this window, for Buffer: Reopen Closed Tab. */
    final ClosedTabs closedTabs = new ClosedTabs();

    /** Emacs registers ({@code C-x r s / i / SPC / j}) for this window. */
    final RegisterCommands registers = new RegisterCommands(new RegisterCommandsHost(this));

    /**
     * The right-click menu of a tab that is not an editor's: the tab operations (and, for a viewer's file, its
     * place on disk), after the tab's {@code own} rows.
     */
    void installContentTabMenu(Tab tab, MenuItem... own) {
        LazyContextMenu.install(tab, menu -> TabContextMenu.buildForContent(this, tab, menu, own));
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
    static String pathKey(Path p) {
        return com.editora.config.PathKeys.key(p);
    }

    /** @see com.editora.config.PathKeys#canonical(Path) */
    static Path canonicalPath(Path p) {
        return com.editora.config.PathKeys.canonical(p);
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
     * {@code saveAsync()} — which copies a consistent snapshot on the FX thread, then serializes and writes it on the
     * shared {@code ConfigWriter}'s daemon thread (further coalesced per file). No data-loss risk: the
     * durable flush on quit is {@code Application.stop()}'s wait for the writer (after {@link #persistSession()}
     * queued the session), and all
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
    boolean sessionClosed;

    @FXML
    void onFindInFiles() {
        searchCoordinator.openToggle();
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
    void onToggleSimpleMode() {
        chrome.toggleSimpleMode();
    }

    final MainClassSuggestions mainClassSuggestions = new MainClassSuggestions();

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

    /**
     * The directory passed to {@code typst compile --root} for a local {@code .typ} file: the nearest
     * {@code typst.toml} ancestor (typst's own project marker), else this window's Editora project root when the
     * file lives inside it, else the file's own folder. Lets a multi-file Typst project resolve
     * {@code #import}/{@code #image} references above the file's folder; a single-file doc is unaffected.
     * Injected into {@link EditorBuffer#setTypstRootResolver} (preview) and {@link TypstCoordinator}
     * (export/print).
     */
    java.nio.file.Path resolveTypstRoot(java.nio.file.Path file) {
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

    /** This window's snippets, for {@link WindowManager} to tell about a change made in another window. */
    SnippetCoordinator snippetCoordinator() {
        return snippetCoordinator;
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

    /** Drops stale {@code externalTool.run.*} commands and re-registers the current set; called per window by
     *  {@link WindowManager}'s external-tools broadcast after a Settings edit. Delegates to the coordinator. */
    void refreshExternalToolCommands() {
        externalToolCoordinator.refreshCommands();
    }

    // --- Kept for callers and tests that reach these by name; the code is in KeyDispatcherWiring ---

    public void setKeyDispatcher(com.editora.command.KeyDispatcher dispatcher) {
        keyWiring.setKeyDispatcher(dispatcher);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowAppActions ---

    void openDocumentation() {
        appActions.openDocumentation();
    }

    void openOnlineDocumentation() {
        appActions.openOnlineDocumentation();
    }

    void onAbout() {
        appActions.onAbout();
    }

    void findInDebugLog() {
        appActions.findInDebugLog();
    }

    void showDebugLog() {
        appActions.showDebugLog();
    }

    void exportConfig() {
        appActions.exportConfig();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowRunSuggestions ---

    String programArgsFor(@Nullable Path path) {
        return runSuggestions.programArgsFor(path);
    }

    com.editora.config.@Nullable RunConfiguration suggestedScriptConfiguration() {
        return runSuggestions.suggestedScriptConfiguration();
    }

    com.editora.config.@Nullable RunConfiguration suggestedBlankConfiguration() {
        return runSuggestions.suggestedBlankConfiguration();
    }

    @Nullable
    String suggestedMainClass() {
        return runSuggestions.suggestedMainClass();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowWelcome ---

    private WelcomePane welcomePane() {
        return welcome.welcomePane();
    }

    private void rebuildRecentMenu() {
        welcome.rebuildRecentMenu();
    }

    void sharedHistoryChanged() {
        welcome.sharedHistoryChanged();
    }

    private void addWelcomeTab() {
        welcome.addWelcomeTab();
    }

    void showDoctor() {
        welcome.showDoctor();
    }

    void openTerminalEditorTab(TerminalEditorTab terminal) {
        welcome.openTerminalEditorTab(terminal);
    }

    void showWelcome() {
        welcome.showWelcome();
    }

    void showWelcomeIfNoTabs() {
        welcome.showWelcomeIfNoTabs();
    }

    List<Path> showableRecentFiles() {
        return welcome.showableRecentFiles();
    }

    @FXML
    void onClearRecent() {
        welcome.onClearRecent();
    }

    static Confirm.Choice removeRecentQuestion(Path path) {
        return WindowWelcome.removeRecentQuestion(path);
    }

    static Confirm.Choice clearRecentQuestion() {
        return WindowWelcome.clearRecentQuestion();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowEditCommands ---

    void cancel() {
        editCommands.cancel();
    }

    void navigateToLine(int line) {
        editCommands.navigateToLine(line);
    }

    void startAceJump() {
        editCommands.startAceJump();
    }

    void startAceJumpLine() {
        editCommands.startAceJumpLine();
    }

    void applyTodoLineEdit(
            java.nio.file.Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
        editCommands.applyTodoLineEdit(file, line, expectedLine, newLine, afterApply);
    }

    void findNextMatch() {
        editCommands.findNextMatch();
    }

    void findPreviousMatch() {
        editCommands.findPreviousMatch();
    }

    void findReplaceCurrentMatch() {
        editCommands.findReplaceCurrentMatch();
    }

    void findReplaceAllMatches() {
        editCommands.findReplaceAllMatches();
    }

    java.util.List<com.editora.editor.undo.UndoHistory.Checkpoint> undoHistoryCheckpoints() {
        return editCommands.undoHistoryCheckpoints();
    }

    void restoreUndoCheckpoint(com.editora.editor.undo.UndoHistory.Checkpoint c) {
        editCommands.restoreUndoCheckpoint(c);
    }

    void findShowOrNext() {
        editCommands.findShowOrNext();
    }

    void findShowOrPrevious() {
        editCommands.findShowOrPrevious();
    }

    void showReplace() {
        editCommands.showReplace();
    }

    void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
        editCommands.withMultiCaret(action);
    }

    void selectAllOccurrences() {
        editCommands.selectAllOccurrences();
    }

    void selectAllFindMatches() {
        editCommands.selectAllFindMatches();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowLifecycle ---

    void disposeWindow() {
        lifecycle.disposeWindow();
    }

    void onWindowFocusGained() {
        lifecycle.onWindowFocusGained();
    }

    public boolean closeWindowProgrammatically() {
        return lifecycle.closeWindowProgrammatically();
    }

    void disposeViewerTab(Tab tab) {
        lifecycle.disposeViewerTab(tab);
    }

    boolean onQuit() {
        return lifecycle.onQuit();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowGates ---

    Chrome.PaletteGates paletteGates() {
        return windowGates.paletteGates();
    }

    Chrome.PaletteContext paletteContext() {
        return windowGates.paletteContext();
    }

    @Nullable
    String disabledCommandReason(String commandId) {
        return windowGates.disabledCommandReason(commandId);
    }

    void refreshMenuEnablement() {
        windowGates.refreshMenuEnablement();
    }

    void updateBufferToolWindows() {
        windowGates.updateBufferToolWindows();
    }

    void updateRunButton() {
        windowGates.updateRunButton();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowFileState ---

    void restorePerFileState(EditorBuffer buffer) {
        fileState.restorePerFileState(buffer);
    }

    void persistFolds(EditorBuffer buffer) {
        fileState.persistFolds(buffer);
    }

    void enableEditing(EditorBuffer buffer) {
        fileState.enableEditing(buffer);
    }

    private void restoreFolds(EditorBuffer buffer) {
        fileState.restoreFolds(buffer);
    }

    void toggleReadOnly() {
        fileState.toggleReadOnly();
    }

    public static boolean shouldOpenReadOnly(boolean persisted, boolean writable) {
        return WindowFileState.shouldOpenReadOnly(persisted, writable);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowViewModes ---

    public void applyStartupChrome(boolean zen, boolean expert, boolean simple) {
        viewModes.applyStartupChrome(zen, expert, simple);
    }

    public void applyStartupChrome(boolean zen, boolean expert, boolean simple, boolean diffUi) {
        viewModes.applyStartupChrome(zen, expert, simple, diffUi);
    }

    void showSplitToolWindowPalette() {
        viewModes.showSplitToolWindowPalette();
    }

    void toggleFloatingToolWindow() {
        viewModes.toggleFloatingToolWindow();
    }

    void toggleMaximizedToolWindow() {
        viewModes.toggleMaximizedToolWindow();
    }

    void toggleToolStripe() {
        viewModes.toggleToolStripe();
    }

    void setZenMode(boolean on) {
        viewModes.setZenMode(on);
    }

    void setExpertMode(boolean on) {
        viewModes.setExpertMode(on);
    }

    public void zoomFromWheel(javafx.scene.input.ScrollEvent e) {
        viewModes.zoomFromWheel(e);
    }

    public void textZoom(int direction) {
        viewModes.textZoom(direction);
    }

    public void uiZoom(int direction) {
        viewModes.uiZoom(direction);
    }

    public void applyEditorTheme(String themeName) {
        viewModes.applyEditorTheme(themeName);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowFileOpens ---

    @Nullable
    EditorBuffer openBackgroundBuffer(Path target) {
        return fileOpens.openBackgroundBuffer(target);
    }

    void openBackgroundBufferAsync(Path target, java.util.function.Consumer<@Nullable EditorBuffer> done) {
        fileOpens.openBackgroundBufferAsync(target, done);
    }

    void promoteTab(@Nullable Tab tab) {
        fileOpens.promoteTab(tab);
    }

    void openPersonalDictionary() {
        fileOpens.openPersonalDictionary();
    }

    void openTechnicalDictionary() {
        fileOpens.openTechnicalDictionary();
    }

    void openPathPreview(Path file) {
        fileOpens.openPathPreview(file);
    }

    void finishAsyncOpen(Tab tab, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
        fileOpens.finishAsyncOpen(tab, buffer, load);
    }

    void discardLoading(EditorBuffer buffer) {
        fileOpens.discardLoading(buffer);
    }

    void clearLoading(EditorBuffer buffer) {
        fileOpens.clearLoading(buffer);
    }

    void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error) {
        fileOpens.failAsyncOpen(tab, buffer, file, error);
    }

    void openActiveAsText() {
        fileOpens.openActiveAsText();
    }

    @Nullable
    String editorConfigCharsetFor(@Nullable Path file) {
        return fileOpens.editorConfigCharsetFor(file);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowBufferWiring ---

    static String httpRunTooltip(com.editora.command.KeymapManager keymap) {
        return WindowBufferWiring.httpRunTooltip(keymap);
    }

    Tab attachRestoredBuffer(Tab tab, EditorBuffer buffer, boolean shown) {
        return bufferWiring.attachRestoredBuffer(tab, buffer, shown);
    }

    Tab addPlaceholderTab(RestorePlaceholder placeholder) {
        return bufferWiring.addPlaceholderTab(placeholder);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowBuildTools ---

    void refreshBuildTools() {
        buildTools.refreshBuildTools();
    }

    @Nullable
    List<String> buildExecutableFor(BuildTool tool, java.nio.file.@Nullable Path root) {
        return buildTools.buildExecutableFor(tool, root);
    }

    void refreshBuildOutputAvailability() {
        buildTools.refreshBuildOutputAvailability();
    }

    @Nullable
    BuildCoordinator buildCoordinator(BuildTool tool) {
        return buildTools.buildCoordinator(tool);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowTrust ---

    void showTrustedFolders() {
        trust.showTrustedFolders();
    }

    void saveTrustDecision() {
        trust.saveTrustDecision();
    }

    void trustChanged() {
        trust.trustChanged();
    }

    void revokeTrustForActiveRoot() {
        trust.revokeTrustForActiveRoot();
    }

    boolean confirmHttpSecretReads(Path file, List<String> hosts, List<String> reads) {
        return trust.confirmHttpSecretReads(file, hosts, reads);
    }

    boolean confirmHttpScripts(Path file) {
        return trust.confirmHttpScripts(file);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowMcpSupport ---

    void applyMcpSupport() {
        mcpSupport.applyMcpSupport();
    }

    void stopMcpIfOwner() {
        mcpSupport.stopMcpIfOwner();
    }

    void applyClaudeIde() {
        mcpSupport.applyClaudeIde();
    }

    void sendSelectionToClaude() {
        mcpSupport.sendSelectionToClaude();
    }

    boolean confirmEnableMcp() {
        return mcpSupport.confirmEnableMcp();
    }

    void ifMcp(Runnable action) {
        mcpSupport.ifMcp(action);
    }

    void toggleMcpSupport() {
        mcpSupport.toggleMcpSupport();
    }

    void copyMcpEndpoint() {
        mcpSupport.copyMcpEndpoint();
    }

    void copyMcpHttpEndpoint() {
        mcpSupport.copyMcpHttpEndpoint();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowPanels ---

    ProjectPanel projectPanel() {
        return panels.projectPanel();
    }

    GitPanel gitPanel() {
        return panels.gitPanel();
    }

    GitLogPanel gitLogPanel() {
        return panels.gitLogPanel();
    }

    GitHubPanel githubPanel() {
        return panels.githubPanel();
    }

    StructurePanel structurePanel() {
        return panels.structurePanel();
    }

    FileInformationPanel fileInfoPanel() {
        return panels.fileInfoPanel();
    }

    UndoHistoryPanel undoHistoryPanel() {
        return panels.undoHistoryPanel();
    }

    MarkdownLintPanel markdownLintPanel() {
        return panels.markdownLintPanel();
    }

    void refreshProjectTreeIfBuilt() {
        panels.refreshProjectTreeIfBuilt();
    }

    private ProjectMapPreview.Content projectMapPreviewContent(Path file) {
        return panels.projectMapPreviewContent(file);
    }

    boolean hasFileOpen(Path file) {
        return panels.hasFileOpen(file);
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowProjectActions ---

    void applyProjectSupport() {
        projectActions.applyProjectSupport();
    }

    void updateProjectFolderView() {
        projectActions.updateProjectFolderView();
    }

    void refreshProjectPanelList() {
        projectActions.refreshProjectPanelList();
    }

    void updateWindowTitle() {
        projectActions.updateWindowTitle();
    }

    private void openProjectRoot(Path root) {
        projectActions.openProjectRoot(root);
    }

    boolean switchToProject(Project p) {
        return projectActions.switchToProject(p);
    }

    String projectDisplayName(String key) {
        return projectActions.projectDisplayName(key);
    }

    void closeProject() {
        projectActions.closeProject();
    }

    void deleteProject() {
        projectActions.deleteProject();
    }

    void deleteProject(@Nullable Project p) {
        projectActions.deleteProject(p);
    }

    static Confirm.Question deleteProjectQuestion(String name) {
        return WindowProjectActions.deleteProjectQuestion(name);
    }

    void onProjectFileRenamed(Path old, Path target) {
        projectActions.onProjectFileRenamed(old, target);
    }

    void onProjectFileRenamed(Path old, Path target, boolean oldLspAlreadyClosed) {
        projectActions.onProjectFileRenamed(old, target, oldLspAlreadyClosed);
    }

    void remapProjectFileLocal(Path old, Path target, boolean oldLspAlreadyClosed) {
        projectActions.remapProjectFileLocal(old, target, oldLspAlreadyClosed);
    }

    void onProjectFileDeleted(Path path) {
        projectActions.onProjectFileDeleted(path);
    }

    void onProjectFileDeleted(Path path, java.util.function.Predicate<EditorBuffer> discardApproved) {
        projectActions.onProjectFileDeleted(path, discardApproved);
    }

    void removeProjectFileLocal(Path path, java.util.function.Predicate<EditorBuffer> discardApproved) {
        projectActions.removeProjectFileLocal(path, discardApproved);
    }

    boolean holdsBufferLocal(EditorBuffer buffer) {
        return projectActions.holdsBufferLocal(buffer);
    }

    void revealBufferLocal(EditorBuffer buffer) {
        projectActions.revealBufferLocal(buffer);
    }

    void openInProjectWindow(String projectKey, Path file, int line) {
        projectActions.openInProjectWindow(projectKey, file, line);
    }

    public void openAndNavigate(Path file, int line) {
        projectActions.openAndNavigate(file, line);
    }

    void openRecent(Path file) {
        projectActions.openRecent(file);
    }

    private @Nullable Project owningProject(Path file) {
        return projectActions.owningProject(file);
    }

    void openRecentHere(Path file) {
        projectActions.openRecentHere(file);
    }

    void editProjectSettings() {
        projectActions.editProjectSettings();
    }

    // --- Kept for callers and tests that reach these by name; the code is in WindowTabActions ---

    void revealInFileManager(@Nullable Path path, boolean isDir, boolean local) {
        tabActions.revealInFileManager(path, isDir, local);
    }

    void openTerminalAt(@Nullable Path path, boolean isDir, boolean local) {
        tabActions.openTerminalAt(path, isDir, local);
    }

    void closeTab(Tab tab) {
        tabActions.closeTab(tab);
    }

    void activateAndFocusTab(Tab tab) {
        tabActions.activateAndFocusTab(tab);
    }

    void unsplit() {
        tabActions.unsplit();
    }

    void splitEditorGroup(Orientation orientation) {
        tabActions.splitEditorGroup(orientation);
    }

    void moveTabToNextGroup() {
        tabActions.moveTabToNextGroup();
    }

    void focusNextEditorGroup() {
        tabActions.focusNextEditorGroup();
    }

    void unsplitEditorGroups() {
        tabActions.unsplitEditorGroups();
    }

    void reopenClosedTab() {
        tabActions.reopenClosedTab();
    }

    List<Tab> eligibleToLeft(Tab pivot) {
        return tabActions.eligibleToLeft(pivot);
    }

    List<Tab> eligibleToRight(Tab pivot) {
        return tabActions.eligibleToRight(pivot);
    }

    void closeOtherTabs(Tab keep) {
        tabActions.closeOtherTabs(keep);
    }

    void closeAllTabs() {
        tabActions.closeAllTabs();
    }

    void closeUnmodifiedTabs() {
        tabActions.closeUnmodifiedTabs();
    }

    void closeTabsToLeft(Tab pivot) {
        tabActions.closeTabsToLeft(pivot);
    }

    void closeTabsToRight(Tab pivot) {
        tabActions.closeTabsToRight(pivot);
    }

    void copyPath(@Nullable EditorBuffer buffer) {
        tabActions.copyPath(buffer);
    }

    void copyPath(@Nullable Path path) {
        tabActions.copyPath(path);
    }

    void revealActiveBuffer() {
        tabActions.revealActiveBuffer();
    }

    void openTerminalForActiveBuffer() {
        tabActions.openTerminalForActiveBuffer();
    }

    void togglePin(Tab tab) {
        tabActions.togglePin(tab);
    }

    void renderViewerPin(Tab tab) {
        tabActions.renderViewerPin(tab);
    }

    void renameFile(@Nullable EditorBuffer buffer, Tab tab) {
        tabActions.renameFile(buffer, tab);
    }

    void renameFileTo(EditorBuffer buffer, Path old, Path target) {
        tabActions.renameFileTo(buffer, old, target);
    }

    void nextBuffer() {
        tabActions.nextBuffer();
    }

    void previousBuffer() {
        tabActions.previousBuffer();
    }
}
