package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
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
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.editora.build.BuildTool;
import com.editora.command.Command;
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
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

import static com.editora.i18n.Messages.tr;

/** Controls the main window: tabbed editors, menu actions, palette/find overlays, and status bar. */
public class MainController implements com.editora.mcp.McpBridge {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MainController.class.getName());
    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    @FXML
    private BorderPane root;

    @FXML
    private BorderPane workspace;

    /**
     * FXML injection point for the editor area's tab strip. <b>Do not use directly</b> — everything goes
     * through {@link #editorArea}, which wraps this and is the seam that lets the editor area hold several
     * independent tab groups (#762). Retained as a field only because FXML injects here (and a handful of FX
     * tests reflect on it by name); it is handed to {@code EditorArea} in {@link #init} and not read again.
     */
    @FXML
    private TabPane tabPane;

    /** Long-running background work, surfaced in the status bar so a slow operation isn't mistaken for a hang. */
    private final BackgroundTasks backgroundTasks = new BackgroundTasks();

    /** The editor area — the single entry point for reading and mutating open tabs. See {@link EditorArea}. */
    private EditorArea editorArea;

    /** The window's menu bar — a browsable map over the command registry (#763). */
    private MainMenuBar menuBar;

    /** Replaces where the Run Configurations page is opened. Tests only. */
    void setRunConfigEditorForTest(java.util.function.Consumer<String> editor) {
        runConfigurations.runConfigEditor = editor == null ? runConfigurations::openRunConfigEditor : editor;
    }

    @FXML
    private VBox topBox;

    @FXML
    private VBox bottomBox;

    @FXML
    private ToolBar toolBar;

    /** The row holding the toolbar: the overflowing cluster plus the pinned tail. */
    @FXML
    private HBox toolbarRow;

    /** The pinned right end of the bar — never part of the ToolBar, so it never overflows. */
    @FXML
    private HBox toolbarTail;

    @FXML
    private Button newButton;

    @FXML
    private Button newFromTemplateButton;

    @FXML
    private Button openButton;

    @FXML
    private Button openFolderButton;

    @FXML
    private Button saveButton;

    @FXML
    private Button saveAsButton;

    @FXML
    private Button undoButton;

    @FXML
    private Button redoButton;

    @FXML
    private Button cutButton;

    @FXML
    private Button copyButton;

    @FXML
    private Button pasteButton;

    @FXML
    private Button findButton;

    @FXML
    private Button findInFilesButton;

    @FXML
    private Button splitVerticalButton;

    @FXML
    private Button splitHorizontalButton;

    @FXML
    private Button paletteButton;

    @FXML
    private Button closeTabButton;

    @FXML
    private Button simpleModeButton;

    @FXML
    private Button settingsButton;

    @FXML
    private MenuButton recentButton;

    @FXML
    private Button clearRecentButton;

    private Stage stage;
    private ConfigManager config;
    private CommandRegistry registry;
    private KeymapManager keymap;
    /** Keyboard macros (record/replay/save/run); see {@link MacroCoordinator}. Created in {@link #init}. */
    private MacroCoordinator macroCoordinator;

    private CommandPalette palette;
    private FindReplaceBar findBar;
    private StatusBar statusBar;
    private FileBreadcrumb breadcrumb;
    private SettingsWindow settingsWindow;
    private final DebugLogWindow debugLogWindow = new DebugLogWindow();

    /** Shared in-scene overlay host for the command palette + pickers (replaces focus-stealing Popups). */
    private final OverlayHost overlayHost = new OverlayHost();

    private com.editora.snippet.SnippetManager snippets;

    /** Shared across windows (owned by WindowManager); plugin classes load once, instances are per-window. */
    private com.editora.plugin.PluginManager pluginManager;
    /** Plugin support (discovery/apply/install + the per-window PluginContext); see {@link PluginCoordinator}. */
    private PluginCoordinator pluginCoordinator;

    private com.editora.completion.CompletionEngine completion;

    private ProjectPanel projectPanel;
    private ProjectManager projects;
    /** The multi-window coordinator (null in single-window/test use); set right after {@link #init}. */
    private WindowManager windowManager;
    /** This window's project ({@code null} = the no-project/global window). */
    private Project windowProject;
    /** This window's project key ({@code ""} = global), kept in sync with {@link #windowProject}. */
    private String projectKey = "";

    private QuickOpen<Project> projectPicker;
    private ProjectCombo toolbarProjectCombo;
    private Region projectToolbarGap;
    /** Tracks the last-applied project-support state to detect off→on transitions (reveal the panel). */
    private boolean projectSupportApplied;

    // Auto save. Mode keys: "off" | "afterDelay" | "onFocusChange".

    private ToolWindowManager toolWindows;
    private ToolWindow projectToolWindow;
    private ToolWindow structureToolWindow;
    private ToolWindow bookmarksToolWindow;
    private ToolWindow notesToolWindow;
    private ToolWindow fileInfoToolWindow;
    private FileInformationPanel fileInfoPanel;
    private ToolWindow undoHistoryToolWindow;
    private UndoHistoryPanel undoHistoryPanel;
    private StructurePanel structurePanel;
    /** Bookmarks feature; owns the panel/jump-picker/persistence. Built in {@link #init} (needs config). */
    private BookmarkCoordinator bookmarkCoordinator;

    private ToolWindow searchToolWindow;
    private ToolWindow todoToolWindow;
    private MarkdownLintPanel markdownLintPanel;
    private ToolWindow markdownLintToolWindow;

    // --- Remote files (SFTP via MINA SSHD; off-thread connect/auth) — owned by RemoteCoordinator ---
    private RemoteCoordinator remoteCoordinator;
    // MCP server: a single app-wide loopback HTTP endpoint exposing live editor state + the command
    // registry to an LLM agent. Static so only the first window with the feature on starts it (the
    // setting is shared); that window's controller is the bridge. (Multi-window caveat: if the owner
    // window closes, the server stops until a settings re-apply re-arms it from another window.)
    private static com.editora.mcp.McpServer mcpServer;
    private static MainController mcpOwner;
    /** LSP: manager (one server per workspace root). Diagnostics route to {@link #lspCoordinator} via the
     *  thin {@link #onLspDiagnostics} delegate — a method ref (not a direct field read) so it isn't an
     *  illegal forward reference to the later-declared coordinator; the field read defers to call time. */
    private final com.editora.lsp.LspManager lspManager =
            new com.editora.lsp.LspManager(this::onLspDiagnostics, this::onLspServerStatus);

    private ToolWindow problemsToolWindow;
    private ToolWindow referencesToolWindow;
    private ToolWindow hierarchyToolWindow;
    /** Run: streams a runnable file's output; see {@link RunCoordinator}. The tool window stays here. */
    private ToolWindow runToolWindow;
    /** External Tools: the feature coordinator owns the service/panel/commands (see {@link ExternalToolCoordinator}). */
    private ToolWindow externalToolToolWindow;
    /** Build tools (Maven/npm/…): one IntelliJ-style tasks-tree tool window per detected tool, whose stripe
     *  appears when the tool's marker file is found (see {@link BuildCoordinator}). */
    private final Map<BuildTool, ToolWindow> buildToolWindows = new java.util.EnumMap<>(BuildTool.class);
    /** The shared tabbed "Output" window — one tab per build tool that runs (Maven/npm/Cargo/Go/Gradle),
     *  fed by every {@link BuildCoordinator}; its stripe appears when any tool's marker is detected, and it
     *  auto-opens on a run. */
    private final BuildOutputPanel buildOutputPanel = new BuildOutputPanel();

    private ToolWindow buildOutputToolWindow;

    /** The IntelliJ-style Test Results window; available once a {@code test} run has occurred. */
    private ToolWindow testResultsToolWindow;

    /** Checks GitHub for a newer release (background, once/day + manual). */
    private final com.editora.update.UpdateService updateService = new com.editora.update.UpdateService();
    /** The newest release found to be newer than the running version, shared across windows in-process (so a
     *  second window and the About dialog see it); null when up to date / not yet checked. */
    private static volatile com.editora.update.ReleaseInfo latestKnownUpdate;
    /** Set once the first window kicks off the session's background update check, so others don't duplicate it. */
    private static volatile boolean updateCheckStartedThisSession;

    private ToolWindow agentToolWindow;

    private ToolWindow remoteToolWindow;
    /** Debug (DAP): drives Java debugging layered on the jdtls LSP session. Stays a field (SettingsWindow +
     *  window-dispose reach it); the {@link DebugCoordinator} operates on it (mirrors lspManager/LspCoordinator). */
    private final com.editora.dap.DapManager dapManager = new com.editora.dap.DapManager(lspManager);

    private ToolWindow debugToolWindow;
    /** The Debugging feature; owns the DebugPanel + breakpoints + DAP session flows. Built in {@link #setupToolWindows}
     *  (its panel reads workspace state). See {@link DebugCoordinator}. */
    private DebugCoordinator debugCoordinator;

    /** In-app install of LSP servers + DAP adapters + Mermaid tools. Built in {@link #setupToolWindows}
     *  (after the LSP/Debug coordinators it re-detects through). See {@link InstallCoordinator}. */
    private InstallCoordinator installCoordinator;

    private GitPanel gitPanel;
    private ToolWindow commitToolWindow;
    private GitLogPanel gitLogPanel;
    private GitLogPanel.Actions gitLogOps; // reused by the git.log.* palette commands (act on the selected commit)
    private ToolWindow gitLogToolWindow;
    /** GitHub PR/issue tool window; available only inside a GitHub repo. */
    private GitHubPanel githubPanel;

    private ToolWindow githubToolWindow;

    /** Local File History: snapshots local files on save/auto-save/external reload (off-thread). */
    private HistoryCoordinator historyCoordinator;

    private ToolbarCoordinator toolbarCoordinator;
    private java.util.Map<String, javafx.scene.Node> toolbarBaseWidgets;

    private ToolWindow fileHistoryToolWindow;

    private Switcher switcher;
    /** Most-recently-used tab order, head = most recent. */
    private final LinkedList<Tab> mru = new LinkedList<>();
    /** Pinned tabs (identity-based): kept grouped at the front and skipped by bulk-close actions. */
    private final Set<Tab> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Guards programmatic tab reordering so the MRU list listener doesn't drop the moved tab. */
    private boolean reordering;
    /** The tab currently being dragged to reorder the strip, or null. */
    private Tab draggedTab;
    /** The editor-theme override stylesheet currently on the scene, or null for the default theme. */
    private String currentEditorThemeCss;

    private RecentFiles recentFiles;
    /** Persistent Find-in-Files query history (backs the query combo's dropdown). */
    private com.editora.config.SearchHistory searchHistory;
    /** Persistent AI Agent chat-session history (backs the resume picker). */
    private com.editora.config.AgentSessionHistory agentSessionHistory;
    /** The VSCode-style Welcome page, shown in its own tab when no file is open (or via {@code view.welcome}). */
    private WelcomePane welcomePane;
    /** The single open Welcome tab (a non-buffer {@link TabContent} tab), or null when none is open. */
    private Tab welcomeTab;
    /** The single open Doctor tab (external-tool health screen), or null — the {@code welcomeTab} pattern. */
    private Tab doctorTab;
    /** Opens external URLs (the Welcome page's home-page link) in the system browser; set from {@code App}. */
    private javafx.application.HostServices hostServices;

    public void installZenOverlay(StackPane sceneRoot) {
        chrome.installZenOverlay(sceneRoot);
    }

    @Override
    public java.util.List<OpenFile> listOpenFiles() {
        return mcpBridge.listOpenFiles();
    }

    @Override
    public BufferContent readBuffer(String path) {
        return mcpBridge.readBuffer(path);
    }

    @Override
    public java.util.List<Diagnostic> getDiagnostics(String path) {
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
    public boolean openFile(String path, int line, int col) {
        return mcpBridge.openFile(path, line, col);
    }

    @Override
    public String editBuffer(String path, String oldText, String newText, boolean replaceAll) {
        return mcpBridge.editBuffer(path, oldText, newText, replaceAll);
    }

    @Override
    public String saveBuffer(String path) {
        return mcpBridge.saveBuffer(path);
    }

    @Override
    public Selection getSelection() {
        return mcpBridge.getSelection();
    }

    @Override
    public java.util.List<Symbol> documentSymbols(String path) {
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
        sessions.startup(projectDir, targets, newFile);
    }

    public void startup(Path projectDir, List<OpenTarget> targets, String newFile, boolean noSession) {
        sessions.startup(projectDir, targets, newFile, noSession);
    }

    public void startupDiffUi(Path left, Path right) {
        sessions.startupDiffUi(left, right);
    }

    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
        // Wrap the FXML-injected tab strip before anything reads tabs; every later access goes through this.
        this.editorArea = new EditorArea(tabPane);
        stage.setOnCloseRequest(e -> {
            // Save/prompt this window's dirty buffers + persist its session; cancel the close if the
            // user backs out. (No separate "Quit?" prompt — each window closes independently now.)
            if (!confirmCloseAllBuffers()) {
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
                // The filesystem may have shifted while we were away — drop cached canonical paths (#680).
                com.editora.config.PathKeys.invalidateCanonicalCache();
                fileWorkflows.checkExternalChanges();
                git.refresh(); // another tool may have changed the repo while we were away
                refreshBuildTools(); // a marker file (or the active file's project) may have changed while away
                refreshPasteState(); // clipboard may have changed in another app while we were away
                if (projectPanel != null) {
                    projectPanel.refreshTree(); // pick up files/folders added or removed outside Editora
                }
                diffCoordinator.refreshOpenDiffs(); // a compared file may have changed on disk while we were away
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        this.macroCoordinator = new MacroCoordinator(config, registry, coordinatorHost, new MacroCoordinator.Ops() {
            @Override
            public void refreshAllWindows() {
                refreshSavedMacroCommandsAllWindows();
            }

            @Override
            public void setRecordingIndicator(boolean recording) {
                statusBar.setMacroRecording(recording);
            }
        });
        // Built here (not as a field initializer) because NotesPanel's constructor reads config.getNotes().
        this.notesCoordinator = new NotesCoordinator(coordinatorHost, new NotesCoordinator.Ops() {
            @Override
            public void openPath(java.nio.file.Path file) {
                fileWorkflows.openPath(file);
            }

            @Override
            public void navigateToLine(int line) {
                MainController.this.navigateToLine(line);
            }

            @Override
            public void openInProjectWindow(String projectKey, java.nio.file.Path file, int line) {
                MainController.this.openInProjectWindow(projectKey, file, line);
            }

            @Override
            public String noteKey(EditorBuffer buffer) {
                return MainController.noteKey(buffer);
            }

            @Override
            public EditorBuffer bufferForKey(String fileKey) {
                return bufferOf(tabForKey(fileKey));
            }

            @Override
            public EditorBuffer bufferForPath(java.nio.file.Path file) {
                return bufferOf(tabForPath(file));
            }

            @Override
            public void installEmacsKeys(javafx.scene.control.TextInputControl control) {
                com.editora.command.TextInputKeymap.install(control, keymap);
            }

            @Override
            public void setToolWindowAvailable(boolean available) {
                toolWindows.setAvailable(notesToolWindow, available);
            }

            @Override
            public java.util.Map<String, java.util.List<com.editora.config.PersonalNote>> notes() {
                return config.getNotes();
            }

            @Override
            public java.util.Map<String, java.util.Map<String, java.util.List<com.editora.config.PersonalNote>>>
                    allNotes() {
                return config.getAllNotes();
            }

            @Override
            public String currentProjectKey() {
                return config.currentProjectKey();
            }

            @Override
            public String projectName(String key) {
                return MainController.this.projectDisplayName(key);
            }

            @Override
            public void saveNotes() {
                config.saveNotes();
            }
        });
        // Built here (not as a field initializer) because BookmarksPanel's constructor reads config.getBookmarks().
        this.bookmarkCoordinator = new BookmarkCoordinator(coordinatorHost, new BookmarkCoordinator.Ops() {
            @Override
            public void openPath(java.nio.file.Path file) {
                fileWorkflows.openPath(file);
            }

            @Override
            public void navigateToLine(int line) {
                MainController.this.navigateToLine(line);
            }

            @Override
            public void openInProjectWindow(String projectKey, java.nio.file.Path file, int line) {
                MainController.this.openInProjectWindow(projectKey, file, line);
            }

            @Override
            public EditorBuffer bufferForPath(java.nio.file.Path file) {
                return bufferOf(tabForPath(file));
            }

            @Override
            public void promptText(
                    String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
                MainController.this.promptText(title, label, initial, onAccept);
            }

            @Override
            public java.util.Map<String, java.util.List<com.editora.config.Bookmark>> bookmarks() {
                return config.getBookmarks();
            }

            @Override
            public java.util.Map<String, java.util.Map<String, java.util.List<com.editora.config.Bookmark>>>
                    allBookmarks() {
                return config.getAllBookmarks();
            }

            @Override
            public String currentProjectKey() {
                return config.currentProjectKey();
            }

            @Override
            public String projectName(String key) {
                return MainController.this.projectDisplayName(key);
            }

            @Override
            public void saveBookmarks() {
                config.saveBookmarks();
            }
        });
        // Record every executed command into an in-progress macro (the service no-ops unless recording).
        registry.setExecutionListener(macroCoordinator::onCommand);
        this.snippets = new com.editora.snippet.SnippetManager(config);
        templateActions.templates = new com.editora.template.TemplateRegistry(config);
        this.completion = new com.editora.completion.CompletionEngine(snippets, config::getUserDictionary);
        // Project commands (incl. the Project tool window) are hidden from the palette unless project
        // support is enabled.
        // Project + Git commands are hidden from the palette unless their feature is enabled.
        // A command whose feature is off, or which has nothing to act on right now, is listed but grayed
        // out and inert (see Chrome.paletteEnabled, which is pure + unit-tested). The gate/context snapshot
        // is taken once per palette refresh — not once per command — because the predicate runs over the
        // whole registry (several hundred commands) on every keystroke in the query field, and the context
        // half reads the active buffer (whose hasPreview() sniffs buffer text).
        this.palette = new CommandPalette(registry, keymap, () -> {
            Chrome.PaletteGates gates = paletteGates();
            Chrome.PaletteContext context = paletteContext();
            return c -> Chrome.paletteEnabled(c.id(), gates, context);
        });
        // Tooltip on a grayed row explaining why, and naming the command that would re-enable it.
        this.palette.setDisabledReason(c -> disabledCommandReason(c.id()));
        this.findBar = new FindReplaceBar(this::activeBuffer, this::setStatus);
        this.findBar.setOnSelectAllMatches(this::selectAllFindMatches);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        this.breadcrumb = new FileBreadcrumb(fileWorkflows::openPath);
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
        this.settingsWindow.setPluginActions(
                pluginCoordinator::browse, pluginCoordinator::installFromDisk, pluginCoordinator::uninstall);
        this.settingsWindow.setDictionaryActions(
                this::openTechnicalDictionary, this::openPersonalDictionary); // Spell Check file links
        this.settingsWindow.setInstallActions(
                key -> { // Settings LSP/Mermaid/Typst "Install…" buttons
                    if ("typst".equals(key)) { // the typst CLI (renderer), not an LSP server
                        installCoordinator.installTypstCli();
                        return;
                    }
                    com.editora.install.InstallCatalog.Lang lang =
                            switch (key) {
                                case "python" -> com.editora.install.InstallCatalog.Lang.PYTHON;
                                case "javascript" -> com.editora.install.InstallCatalog.Lang.JAVASCRIPT;
                                case "mermaid" -> com.editora.install.InstallCatalog.Lang.MERMAID;
                                default -> com.editora.install.InstallCatalog.Lang.JAVA;
                            };
                    installCoordinator.installSupport(lang);
                });
        this.settingsWindow.setInstallServerActions(installCoordinator::installServer); // per-LSP-server Install
        this.settingsWindow.setSnippetManager(snippets); // backs the Settings → Snippets management page
        // Read on each Add click, not now: the Settings window outlives whichever tab is in front.
        this.settingsWindow.setRunConfigSuggestion(this::suggestedMainClass);
        this.settingsWindow.setTemplateRegistry(
                templateActions.templates); // backs the Settings → Templates management page
        this.settingsWindow.setMcpConfirm(this::confirmEnableMcp); // security notice before enabling MCP
        this.settingsWindow.setTrustActions(new SettingsWindow.TrustActions() {
            @Override
            public List<String> trustedRoots() {
                return config.getTrustStore().trustedRoots();
            }

            @Override
            public void revoke(String root) {
                config.getTrustStore().revoke(java.nio.file.Path.of(root));
                config.saveTrust();
            }

            @Override
            public void revokeAll() {
                config.getTrustStore().revokeAll();
                config.saveTrust();
            }
        });
        this.settingsWindow.setRipgrepProbe(
                searchCoordinator::probeRipgrep); // Settings → Search found/not-found status
        this.settingsWindow.setAiConnectionProbe(
                aiCoordinator::checkConnection); // Settings → AI Actions green/red connection status
        this.settingsWindow.setOnKeymapChanged(editorSettings::reloadKeymap); // picker/combo → live keymap switch
        this.settingsWindow.setShortcutActions(new SettingsWindow.ShortcutActions() {
            @Override
            public java.util.List<SettingsWindow.Shortcut> rows() {
                return editorSettings.shortcutRows();
            }

            @Override
            public java.util.List<com.editora.command.KeybindingEdits.Conflict> conflicts(
                    String chordSeq, String commandId) {
                return com.editora.command.KeybindingEdits.conflicts(keymap.bindings(), chordSeq, commandId);
            }

            @Override
            public void rebind(String commandId, String chordSeq) {
                editorSettings.rebindShortcut(commandId, chordSeq);
            }

            @Override
            public void reset(String commandId) {
                editorSettings.resetShortcut(commandId);
            }

            @Override
            public void resetAll() {
                editorSettings.resetAllShortcuts();
            }
        });
        this.settingsWindow.setRunConfigsChangedHandler(
                runConfigurations
                        ::refreshRunConfigs); // Run Configurations page edits → repopulate the toolbar selector
        this.settingsWindow.setMacrosChangedHandler(
                this::refreshSavedMacroCommandsAllWindows); // Macros page edits → re-register commands everywhere
        this.settingsWindow.setAgentCoordinator(agentCoordinator); // AI Agent page: per-client status + combo
        // Toolbar page ↔ the (later-constructed) ToolbarCoordinator; the lambdas resolve the field lazily.
        this.settingsWindow.setToolbarActions(new SettingsWindow.ToolbarActions() {
            @Override
            public java.util.List<String> current() {
                return toolbarCoordinator.effectiveLayout();
            }

            @Override
            public void apply(java.util.List<String> layout) {
                toolbarCoordinator.setLayout(layout);
            }

            @Override
            public void restoreDefault() {
                toolbarCoordinator.restoreDefault();
            }
        });
        debugLogWindow.setSessionFile(DebugLog.sessionFile(config.getConfigDir()));
        this.switcher = new Switcher(
                () -> new java.util.ArrayList<>(editorArea.tabs()), // list files in tab order
                () -> editorArea.selectedTab(),
                this::activateAndFocusTab,
                this::closeTabFromSwitcher);
        backgroundTasks.addListener(() -> {
            BackgroundTasks.Task t = backgroundTasks.current();
            statusBar.setBackgroundTasks(t == null ? null : t.label(), backgroundTasks.count());
        });
        setupMruTracking();
        windowCommands.registerCommands();
        setupToolbar();
        runConfigurations.refreshRunConfigs(); // populate the selector + register run.config.<slug> for the saved set
        setupRecentFiles();
        navigation.setupJumpPickers();
        setupProjects();
        pluginCoordinator
                .applyPlugins(); // register plugin commands/tool windows/hooks (before restore, so visibility restores)
        toolWindows.restore();
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
        httpClient.applySupport(); // .http run glyphs + response window off when disabled (default)
        htmlPreview.applySupport(); // HTML "open in browser" control off when disabled (default)
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
        refreshUpdateNotice(); // reflect any update an earlier window already found
        maybeCheckForUpdates(); // background check (once/session, once/day, only if enabled)
    }

    // --- update check ------------------------------------------------------------------------------

    /** Kicks off the background update check when enabled and due — at most once per app session (a static
     *  guard, so multiple windows don't each hit GitHub) and at most once per day (the persisted timestamp). */
    private void maybeCheckForUpdates() {
        if (!config.getSettings().isUpdateCheck() || updateCheckStartedThisSession) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!com.editora.update.UpdateCheck.isDue(
                config.getSettings().getLastUpdateCheckEpoch(),
                now,
                com.editora.update.UpdateCheck.DEFAULT_INTERVAL_MS)) {
            return;
        }
        updateCheckStartedThisSession = true;
        config.getSettings().setLastUpdateCheckEpoch(now); // throttle even if the check fails (don't retry all day)
        requestSave();
        updateService.check(com.editora.AppInfo.VERSION, o -> onUpdateOutcome(o, false));
    }

    /** Applies a check result: on a newer release, cache it + show the notice; a manual check also echoes an
     *  up-to-date / failure status. */
    private void onUpdateOutcome(com.editora.update.UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.ok()) {
            if (manual) {
                setStatus(tr("status.update.failed", outcome.error()));
            }
            return;
        }
        if (outcome.available()) {
            latestKnownUpdate = outcome.latest();
            refreshUpdateNotice();
            setStatus(tr("status.update.available", outcome.latest().version()));
        } else {
            latestKnownUpdate = null; // running the latest
            refreshUpdateNotice();
            if (manual) {
                setStatus(tr("status.update.upToDate", com.editora.AppInfo.VERSION));
            }
        }
    }

    /** Shows this window's status-bar update segment when a newer, non-dismissed release is known; else hides it. */
    private void refreshUpdateNotice() {
        com.editora.update.ReleaseInfo info = latestKnownUpdate;
        boolean show =
                info != null && !info.version().equals(config.getSettings().getDismissedUpdateVersion());
        statusBar.setUpdateAvailable(show, show ? info.version() : null);
    }

    /** Manual "Check for Updates" — always checks (ignores the throttle + the auto-check setting). */
    private void checkForUpdatesNow() {
        setStatus(tr("status.update.checking"));
        updateService.check(com.editora.AppInfo.VERSION, o -> onUpdateOutcome(o, true));
    }

    /** Opens the release page for the known update (else the releases page) and dismisses that version's notice. */
    private void openUpdateDownloadPage() {
        com.editora.update.ReleaseInfo info = latestKnownUpdate;
        String url = info != null && !info.url().isBlank() ? info.url() : com.editora.AppInfo.RELEASES_PAGE;
        openExternalUrl(url);
        if (info != null) {
            config.getSettings().setDismissedUpdateVersion(info.version());
            requestSave();
        }
        refreshUpdateNotice();
    }

    // --- plugins ----------------------------------------------------------------------------------

    /** Injected by {@link WindowManager} before {@link #init}; shared across windows (classes load once). */
    void setPluginManager(com.editora.plugin.PluginManager pm) {
        this.pluginManager = pm; // the per-window PluginRegistry/PluginInstaller are built in PluginCoordinator
    }

    /** Whether plugins may load — the master gate (also off in Simple UI mode). */
    private boolean pluginsEnabled() {
        return pluginManager != null && config.getSettings().isPluginSupport() && !chrome.simpleModeActive();
    }

    /** Stops this window's plugins (window close); WindowManager calls this by name. */
    void disposePlugins() {
        if (pluginCoordinator != null) {
            pluginCoordinator.disposePlugins();
        }
    }

    /** Window hooks for {@link PluginCoordinator} (open-path + the shared Git error dialog). */
    private PluginCoordinator.Ops pluginOps() {
        return new PluginCoordinator.Ops() {
            @Override
            public void openPath(java.nio.file.Path file) {
                fileWorkflows.openPath(file);
            }

            @Override
            public void showError(String summary, String detail) {
                git.gitError(summary, detail);
            }
        };
    }

    /** Snapshot of which optional features are effectively enabled, for {@link Chrome#paletteVisible}. */
    private Chrome.PaletteGates paletteGates() {
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
    private String disabledCommandReason(String commandId) {
        Chrome.DisabledReason reason = Chrome.disabledReason(commandId, paletteGates(), paletteContext());
        if (reason == null) {
            return null;
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
    private void refreshMenuEnablement() {
        if (menuBar != null) {
            menuBar.refreshEnablement();
        }
    }

    private Chrome.PaletteContext paletteContext() {
        EditorBuffer b = activeBuffer();
        boolean debugActive = dapManager.isActive();
        boolean suspended = dapManager.state() == com.editora.dap.DapManager.State.SUSPENDED;
        return new Chrome.PaletteContext(
                b != null,
                git.repoRoot() != null,
                b != null && (b.isMarkdown() || b.isTypst()),
                b != null && b.isCsv(),
                b != null && b.isHttpFile(),
                b != null && b.isTypst(),
                b != null && b.hasPreview(),
                debugActive,
                suspended);
    }

    private void setupRecentFiles() {
        recentFiles = new RecentFiles(config.getConfigDir());
        searchHistory = new com.editora.config.SearchHistory(config.getConfigDir());
        agentSessionHistory = new com.editora.config.AgentSessionHistory(config.getConfigDir());
        searchCoordinator.refreshHistory(); // bind the query combo's dropdown to history
        recentButton.setGraphic(Icons.recent());
        recentButton.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        recentButton.setTooltip(new Tooltip(tr("tooltip.recent")));

        // Rebuild the dropdown whenever the recent-files list changes.
        recentFiles.getList().addListener((ListChangeListener<Path>) c -> rebuildRecentMenu());
        rebuildRecentMenu();

        setupButton(clearRecentButton, Icons.trash(), tr("tooltip.clearRecent"), "file.clearRecent");
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
                config.isDev() ? com.editora.AppInfo.gitCommit() : ""); // build commit shown only in --dev
    }

    /**
     * Opens (or re-selects) the single Welcome tab, refreshing it first so its recents + Open-Folder /
     * Clone actions track the current state. Used at startup with no session and by {@code view.welcome}.
     */
    private void addWelcomeTab() {
        welcomePane.refresh();
        welcomePane.setFontScale(config.getSettings().getFontZoom()); // match the current text zoom (#540)
        if (welcomeTab != null && editorArea.contains(welcomeTab)) {
            editorArea.select(welcomeTab);
            return;
        }
        Tab tab = addContentTab(welcomePane, true);
        welcomeTab = tab;
        // Welcome is not a document: only a Close action (no Save/Rename/Pin).
        MenuItem close = new MenuItem(tr("menu.close"));
        close.setGraphic(Icons.closeTab());
        close.setOnAction(e -> closeTab(tab));
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
    private void showDoctor() {
        if (doctorTab != null && editorArea.contains(doctorTab)) {
            editorArea.select(doctorTab);
            doctorCoordinator.runChecks();
            return;
        }
        Tab tab = addContentTab(doctorCoordinator.pane(), true);
        doctorTab = tab;
        MenuItem close = new MenuItem(tr("menu.close"));
        close.setGraphic(Icons.closeTab());
        close.setOnAction(e -> closeTab(tab));
        tab.setContextMenu(new ContextMenu(close));
        tab.setOnClosed(e -> {
            if (doctorTab == tab) {
                doctorTab = null;
            }
        });
        doctorCoordinator.runChecks();
    }

    /** Sets the {@link javafx.application.HostServices} used to open external links (from {@code App}). */
    public void setHostServices(javafx.application.HostServices hostServices) {
        this.hostServices = hostServices;
    }

    /**
     * Binds this window to the multi-window coordinator and records which project it edits ({@code null}
     * = the no-project/global window). Called by {@link WindowManager} right after {@link #init} and
     * before {@link #startup}, so the project panel root, title, and combo reflect <em>this</em> window's
     * project (not the globally last-focused one).
     */
    public void setWindowContext(WindowManager windowManager, Project project) {
        indexCoordinator.onProjectChanged(); // the previous project's symbols mean nothing here
        this.windowManager = windowManager;
        this.windowProject = project;
        this.projectKey = project == null ? "" : project.id();
        projectPanel.setRoot(project == null ? null : Path.of(project.root()));
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
        // This window now knows its project, which is half of what gates the run-config group; the other
        // half (marker detection) re-runs on the tab-selection and focus-regain paths.
        runConfigurations.refreshRunConfigToolbar();
    }

    /**
     * In the global ("No Project") window, points the Project tool window at the active file's parent
     * folder and titles it "Current Folder" — so it doubles as a file explorer for the file being edited,
     * tracking tab switches. No-op when a project is open (the tree stays the project root and the title
     * "Project") or while a remote folder is mounted (that root is set explicitly). With no local file
     * active it shows the placeholder and the default title.
     */
    private void updateProjectFolderView() {
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
        if (!java.util.Objects.equals(projectPanel.getRoot(), dir)) {
            projectPanel.setRoot(dir); // null => placeholder (unsaved/Welcome tab); rebuilds only on change
        }
        if (projectToolWindow != null) {
            projectToolWindow.setTitle(tr(dir != null ? "toolwindow.currentFolder" : "toolwindow.project"));
        }
    }

    /** Re-applies preferences + the editor theme to this window after a Settings change in any window. */
    public void reapplyAfterSharedSettingsChange(Settings settings) {
        editorSettings.applyViewSettingsToAllBuffers(settings);
        updateBufferToolWindows(); // a feature toggle (Markdown lint, external tools, …) may re-gate a window
        // A build tool may have just been switched back on in Settings. Its cached detection was cleared the
        // last time refresh() ran while it was off, and every other apply path only re-derives the stripe from
        // that cache — so without a real re-detect the tool stays "not detected" (no stripe, no tasks tree)
        // until an unrelated tab switch or focus-regain happens to run one. The palette toggle command always
        // did this; the Settings checkbox did not.
        refreshBuildTools();
        // The keymap may have switched (it's shared); refresh every chord hint so none stays frozen to the
        // old keymap. Cheap (~25 tooltips + one palette/welcome relabel) and only on a settings/keymap apply.
        refreshToolbarTooltips();
        if (toolbarCoordinator != null) {
            toolbarCoordinator.rebuild(); // the toolbar layout is a shared setting — rebuild from it
        }
        if (palette != null) {
            palette.refreshBindings();
        }
        if (toolWindows != null) {
            toolWindows.refreshTooltips();
        }
        if (welcomePane != null) {
            welcomePane.refresh();
        }
        installPrompts.maybeOfferInstall(
                activeBuffer()); // the install-prompts toggle / a feature gate may have changed
        fileWorkflows.applyAdminSaveSupport(); // the admin-save toggle may have flipped
        runConfigurations.refreshRunConfigs(); // the Settings page edits the same list this selector shows
        lspCoordinator.reloadProjectSettings(); // pick up an edited .editora/settings.json
    }

    /**
     * A Settings change was applied in this window. Re-apply it to every open window (the {@link Settings}
     * object is shared by reference, so other windows already see the new values — they just need to
     * restyle). Falls back to this window only when multi-window isn't wired.
     */
    private void onSettingsApplied(Settings settings) {
        if (windowManager != null) {
            windowManager.broadcastSettingsApplied(); // re-applies to every window, including this one
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
        if (!confirmCloseAllBuffers()) {
            return false;
        }
        disposeWindow();
        return true;
    }

    /** Releases this window's resources on close: language servers, debug session, and worker threads. */
    void disposeWindow() {
        sessionClosed = true; // no further session writes from this window (see requestSave)
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                fileWorkflows.invalidatePendingWrites(buffer);
                buffer.dispose();
            } else {
                disposeViewerTab(tab); // an image/hex/PDF tab holds a thread + file handle + GPU texture too
            }
        }
        lspManager.shutdownAll(); // don't orphan this window's external language servers
        dapManager.shutdown(); // end the debug session and release the per-window connect worker
        git.shutdown();
        github.shutdown(); // stop the gh worker thread
        indexCoordinator.dispose(); // stop the symbol-index walker
        if (historyCoordinator != null) {
            historyCoordinator.shutdown();
        }
        searchCoordinator.shutdown();
        todoCoordinator.shutdown();
        previews.markdownLintService.shutdown();
        mermaid.shutdown();
        diagram.shutdown();
        typst.shutdown();
        doctorCoordinator.shutdown(); // stop any in-flight Doctor probes
        buildCoordinators.forEach(BuildCoordinator::shutdown);
        updateService.shutdown(); // stop the update-check worker
        htmlPreview.shutdown(); // stop the HTML-preview HTTP server + worker
        logViewer.shutdown(); // stop any log tail-follow poll thread
        stopMcpIfOwner(); // stop the MCP server if this window owns it
        agentCoordinator.shutdown(); // kill the ACP agent process tree
        aiCoordinator.shutdown(); // cancel any in-flight AI generation
        exports.shutdown();
        runCoordinator.shutdown();
        testRunCoordinator.shutdown(); // stop the report poller + elapsed timer
        if (installCoordinator != null) {
            installCoordinator.shutdown();
        }
        fileWorkflows.shutdown();
        diffCoordinator.shutdown(); // the diff-service worker thread
        mavenProjectCoordinator.shutdown(); // archetype:generate process + catalog fetch thread
        externalToolCoordinator.shutdown(); // the external-tool worker thread
        httpClient.shutdown(); // the http-client worker thread
        remoteCoordinator.shutdown(); // SFTP sessions + the SSH client (and un-pin the static Vfs hooks)
        projectPanel.dispose(); // stop the project tree's filesystem watcher + its daemon thread
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
    private void disposeViewerTab(Tab tab) {
        if (tab.getUserData() instanceof ImageViewerPane image) {
            image.dispose();
        } else if (tab.getUserData() instanceof HexViewerPane hex) {
            hex.dispose();
        } else if (tab.getUserData() instanceof PdfViewerPane pdf) {
            pdf.dispose();
        } else if (tab.getUserData() instanceof PrReviewPane pr) {
            github.onReviewPaneClosed(pr); // drop it from the coordinator's per-PR map (no resource to release)
        }
    }

    /**
     * Wires the key dispatcher's first-look hook so that <b>M-g</b>, while a tool window is focused,
     * closes that window and returns focus to the editor (instead of starting the go-to prefix).
     */
    public void setKeyDispatcher(com.editora.command.KeyDispatcher dispatcher) {
        // Record literally-typed characters + the bare editing/navigation keys the area handles itself into
        // an in-progress macro (all no-ops unless recording), gated to keys aimed at the active editor — the
        // hooks are scene filters, so they'd otherwise capture the palette's / find bar's own input.
        if (macroCoordinator != null) {
            dispatcher.setTypedListener(macroCoordinator::onTypedChar);
            dispatcher.setKeyListener(macroCoordinator::onKey);
            dispatcher.setRecordTarget(macroCoordinator::isRecordableTarget);
        }
        // Prefix argument (C-u): setMark reads it (C-u C-SPC = pop-to-mark); every other command is repeated;
        // a self-inserting character is typed N times.
        dispatcher.setPrefixArgumentSupport(
                "edit.setMark"::equals, arg -> editing.currentPrefixArg = arg, editing::selfInsertRepeat);
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

    /** Opens a URL in the system browser (no-op if HostServices isn't available). */
    private void openExternalUrl(String url) {
        if (hostServices != null && url != null) {
            hostServices.showDocument(url);
        }
    }

    /** `view.welcome`: opens the Welcome tab (or selects it if already open). */
    private void showWelcome() {
        addWelcomeTab();
    }

    /** Opens the Welcome tab when the strip is empty (startup with no session, or after a project swap). */
    private void showWelcomeIfNoTabs() {
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
                "Switch Project",
                "Type to filter projects…",
                this::projectsWithNoProject,
                Project::name,
                p -> p.id().isEmpty() ? "global session" : p.root(),
                this::switchToProject);
        // Keyboard "Open Project Folder" — mirrors the file finder, but picks a directory.
        navigation.folderFinder =
                new FileFinder(navigation::finderStartDir, this::openProjectRoot, true, "Open Project Folder");
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

    private void refreshProjectPanelList() {
        // The toolbar combo shows THIS window's project as selected (each window is one project).
        if (toolbarProjectCombo != null) {
            toolbarProjectCombo.setProjects(projects.list(), projectKey);
        }
    }

    private boolean projectsEnabled() {
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
    private void applyProjectSupport() {
        projectPanel.setShowHidden(config.getSettings().isProjectShowHidden()); // hidden-files toggle (init + apply)
        projectPanel.setRespectGitignore(
                config.getSettings().isSearchRespectGitignore()); // skip .gitignore'd files in the filter search
        boolean on = projectsEnabled();
        // Turning projects off just hides the project chrome — each window keeps editing its open files
        // (windows no longer share one session that could be "stranded"). On the next launch with projects
        // off, WindowManager opens a single global window.
        // The project toolbar group is also hidden by Simple UI mode (it stays a project even though the
        // selector is hidden); kept here so this later pass doesn't re-show it over applySimpleMode.
        boolean showProjectGroup = on && !chrome.simpleModeActive();
        openFolderButton.setVisible(showProjectGroup);
        openFolderButton.setManaged(showProjectGroup);
        toolbarProjectCombo.setVisible(showProjectGroup);
        toolbarProjectCombo.setManaged(showProjectGroup);
        projectToolbarGap.setVisible(showProjectGroup);
        projectToolbarGap.setManaged(showProjectGroup);
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
    private boolean switchToProject(Project p) {
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
    private void closeProject() {
        if (windowProject == null) {
            setStatus(tr("status.noProjectOpen"));
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.closeProject.body", windowProject.name()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.closeProject.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (windowManager != null) {
            windowManager.requestClose(this); // persists + closes this window
        }
    }

    /** Deletes this window's project (with confirmation). */
    private void deleteProject() {
        deleteProject(windowProject);
    }

    /**
     * Deletes a project from the shared list (with confirmation). Only the project entry, its saved
     * session, and its bookmark/note/breakpoint buckets are removed — the folder and its files on disk are
     * left untouched. If the project has an open window, it is closed first (saving its dirty buffers); a
     * cancelled save prompt aborts the deletion.
     */
    private void deleteProject(Project p) {
        if (p == null || p.id().isEmpty()) {
            setStatus(tr("status.noProjectToDelete"));
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.deleteProject.body", p.name()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.deleteProject.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
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
        config.deleteBookmarksForProject(p.id()); // the project's bookmarks go with it
        config.deleteNotesForProject(p.id()); // ...its personal notes
        config.deleteBreakpointsForProject(p.id()); // ...and its breakpoints
        config.deleteHistoryForProject(p.id()); // ...and its local file history index
        refreshProjectPanelList();
        setStatus(tr("status.deletedProject", p.name()));
    }

    /**
     * Window title: the active file's name + path, then the project (if any), then "Editora" —
     * e.g. {@code ~/src/app/Main.java — MyProject — Editora}. A {@code •} prefix marks an unsaved
     * buffer; an untitled buffer shows its display name; with no buffer (Welcome) just the app/project.
     */
    private void updateWindowTitle() {
        String app = windowProject == null ? "Editora" : windowProject.name() + " — Editora";
        EditorBuffer b = activeBuffer();
        if (b == null) {
            // A non-buffer tab: show an image viewer's file path; else just the app name (Welcome, etc.).
            Path img = tabPath(editorArea.selectedTab());
            stage.setTitle(img != null ? homeCollapsed(img.toString()) + " — " + app : app);
            return;
        }
        Path p = b.getPath();
        // getDisplayName() is null for a plain untitled buffer (it's only set by --new-file=name /
        // templates), and concatenating that rendered a literal "null — … — Editora" title. Fall back to
        // the buffer's tab title ("untitled"), so the title bar always agrees with the tab.
        String file = p != null
                ? homeCollapsed(p.toString())
                : b.getDisplayName() != null ? b.getDisplayName() : b.getTitle();
        // The OS title bar renders plain text only (no color/italic/weight, so the tab's amber-italic
        // dirty styling can't be mirrored here) — use a prominent leading "●" as the unsaved marker so
        // the modified state is visible even in Zen mode, where the tab strip is hidden.
        stage.setTitle((b.isDirty() ? "● " : "") + file + " — " + app);
    }

    /** Syncs editor/session state after the Project tree renames a file on disk (old → target). */
    private void onProjectFileRenamed(Path old, Path target) {
        fileWorkflows.invalidatePendingWrite(old);
        com.editora.config.PathKeys.invalidateCanonicalCache(); // stale resolutions must not survive a move (#680)
        Tab tab = tabForPath(old);
        if (tab != null) {
            EditorBuffer buffer = bufferOf(tab);
            buffer.setPath(target);
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
                Path p = b == null ? null : b.getPath();
                if (p == null) {
                    continue;
                }
                Path pn = p.toAbsolutePath().normalize();
                if (pn.startsWith(oldNorm) && !pn.equals(oldNorm)) {
                    Path moved = target.resolve(oldNorm.relativize(pn));
                    fileWorkflows.invalidatePendingWrite(p);
                    b.setPath(moved);
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
        requestSave();
        setStatus(tr("status.renamedTo", target.getFileName()));
    }

    /** Re-keys every path-keyed session map (folds, markdown mode, spell language, read-only) on rename. */
    private void migrateFileState(Path old, Path target) {
        WorkspaceState ws = config.getWorkspaceState();
        String oldKey = old.toString();
        String newKey = target.toString();
        rekey(ws.getFoldedRegions(), oldKey, newKey);
        rekey(ws.getMarkdownViewModes(), oldKey, newKey);
        rekey(ws.getSpellLanguages(), oldKey, newKey);
        if (ws.getReadOnlyFiles().remove(oldKey)) {
            ws.getReadOnlyFiles().add(newKey);
        }
        if (recentFiles != null) {
            recentFiles.remove(old);
        }
        // Bookmarks (re-anchored by lineText) and notes (re-keyed by content hash) self-heal on reopen.
    }

    /** Moves a value from {@code oldKey} to {@code newKey} if present, preserving it across a rename. */
    private static <V> void rekey(Map<String, V> map, String oldKey, String newKey) {
        V value = map.remove(oldKey);
        if (value != null) {
            map.put(newKey, value);
        }
    }

    /** Syncs editor/session state after the Project tree deletes a file on disk. */
    private void onProjectFileDeleted(Path path) {
        com.editora.config.PathKeys.invalidateCanonicalCache(); // (#680)
        Tab tab = tabForPath(path);
        if (tab != null) {
            editorArea.remove(tab); // file is gone; close without a save prompt
        }
        WorkspaceState ws = config.getWorkspaceState();
        String key = path.toString();
        ws.getFoldedRegions().remove(key);
        ws.getMarkdownViewModes().remove(key);
        ws.getSpellLanguages().remove(key);
        ws.getReadOnlyFiles().remove(key);
        if (recentFiles != null) {
            recentFiles.remove(path);
        }
        requestSave();
        setStatus(tr("status.deleted", path.getFileName()));
    }

    /** Moves the active editor's caret to {@code line} and anchors it at the top of the viewport. */
    private void navigateToLine(int line) {
        EditorBuffer buffer = activeBuffer();
        CodeArea area = activeArea();
        if (area == null || line < 0 || line >= area.getParagraphs().size()) {
            return;
        }
        NavigationHistory.Location origin = navigation.navigating ? null : navigation.captureCurrent();
        // Reveal the target if it's hidden inside a collapsed fold, so we don't scroll to a hidden line.
        if (buffer != null) {
            buffer.getFoldManager().unfoldContaining(line);
        }
        area.moveTo(line, 0);
        if (!navigation.navigating && buffer != null && buffer.getPath() != null) {
            navigation.recordJump(origin, new NavigationHistory.Location(buffer.getPath(), line, 0));
        }
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(line);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
        area.requestFollowCaret();
        area.requestFocus();
    }

    /**
     * Opens a bookmark/note's file at 0-based {@code line} in the window for {@code projectKey} ({@code ""} =
     * general/no-project). When the entry belongs to <em>this</em> window's project (or Projects are off, or
     * there's no window manager), it opens in place — the original behavior. Otherwise it hands off to
     * {@link WindowManager#openInWindow} so the user lands in that project's window (focused if already open,
     * else built), rather than opening the file out of context in the current window.
     */
    private void openInProjectWindow(String projectKey, Path file, int line) {
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
            projectPanel.revealPathInTree(file);
            return;
        }
        fileWorkflows.openPath(file);
        if (line >= 0) {
            Platform.runLater(() -> navigateToLine(line));
        }
    }

    /**
     * The recent entries still worth offering — see {@link RecentFiles#showable}. Kept here so the toolbar
     * dropdown and the Welcome page filter identically; a deleted file must not be offered by either.
     */
    List<Path> showableRecentFiles() {
        return recentFiles == null
                ? List.of()
                : RecentFiles.showable(
                        recentFiles.getList(), com.editora.vfs.Vfs::isLocal, java.nio.file.Files::exists);
    }

    private void rebuildRecentMenu() {
        recentButton.getItems().clear();
        List<Path> shown = showableRecentFiles();
        if (shown.isEmpty()) {
            MenuItem empty = new MenuItem(tr("menu.noRecentFiles"));
            empty.setDisable(true);
            recentButton.getItems().add(empty);
            return;
        }
        for (Path path : shown) {
            recentButton.getItems().add(recentMenuItem(path));
        }
        // "Clear recent files" lives here rather than as its own toolbar icon: as a bare trash can beside
        // the file group it read as "delete this file" and sat a slot away from Save. Inside the dropdown it
        // is unambiguous and can't be hit by accident. (The file.clearRecent command + the catalog entry are
        // unchanged, so anyone who wants the icon back can add it in Settings → Toolbar.)
        recentButton.getItems().add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem(tr("tooltip.clearRecent"), Icons.trash());
        clear.setOnAction(e -> onClearRecent());
        recentButton.getItems().add(clear);
    }

    /** A recent-file menu entry: filename label that opens the file, plus an inline ✕ icon to remove it. */
    private CustomMenuItem recentMenuItem(Path path) {
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
        // Remove just this entry (no confirmation) without opening it or closing the menu.
        removeBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            recentFiles.remove(path);
            e.consume();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, name);
        if (projectName != null) {
            box.getChildren().add(projectName);
        }
        box.getChildren().addAll(spacer, removeBtn);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.setPrefWidth(220);

        CustomMenuItem item = new CustomMenuItem(box);
        item.setOnAction(e -> openRecent(path));
        Tooltip.install(box, new Tooltip(path.toString()));
        return item;
    }

    private void setupMruTracking() {
        // A mouse click in the editor area repositions the caret, which ends an Emacs mark session.
        editorArea.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> editing.deactivateMark());
        editorArea.addSelectionListener((obs, was, now) -> {
            if (now != null) {
                mru.remove(now);
                mru.addFirst(now);
            }
            // Release the outgoing buffer's per-tab GPU caches (minimap snapshot) and restore the
            // incoming one's, so retained VRAM doesn't grow with the number of open files.
            EditorBuffer outgoing = bufferOf(was);
            if (outgoing != null) {
                outgoing.setRenderingActive(false);
            }
            EditorBuffer buffer = bufferOf(now);
            if (buffer != null) {
                buffer.setRenderingActive(true);
                buffer.onTabShown(); // force a repaint: a switched-to area can come up blank until the next pulse
                // A restored background tab defers its language server until it's actually looked at.
                lspCoordinator.onBufferShown(buffer);
            }
            fileInfoPanel.attach(buffer);
            structurePanel.attach(buffer);
            undoHistoryPanel.attach(buffer);
            lspCoordinator.requestStructureSymbols(
                    buffer); // upgrade the outline to LSP symbols when the server supports them
            statusBar.attach(buffer);
            breadcrumb.setActiveFile(buffer == null ? null : buffer.getPath());
            // Sort the Problems / TODO tool windows so the active file's group is on top (IDE convention).
            // Problems is keyed by canonical (LSP) paths; TODO by as-walked (scan) paths.
            Path activePath = buffer == null ? null : buffer.getPath();
            if (projectPanel != null) {
                projectPanel.setActiveFile(activePath);
            }
            lspCoordinator.setProblemsActiveFile(activePath == null ? null : canonicalPath(activePath));
            todoCoordinator.setActiveFile(
                    activePath == null ? null : activePath.toAbsolutePath().normalize());
            updateWindowTitle(); // show the active file's name + path in the window title bar
            updateProjectFolderView(); // global window: retarget the tree at the new file's folder
            searchCoordinator.refreshScope(); // Find-in-Files "current folder" tracks the active file
            if (FileWorkflowCoordinator.AUTOSAVE_FOCUS.equals(fileWorkflows.autoSaveMode())) {
                fileWorkflows.autoSaveAllDirty(); // saves the outgoing buffer (and any other dirty ones)
            }
            refreshSplitButtons();
            refreshEditState(); // save/undo/redo/cut/copy enablement for the new tab
            refreshPasteState(); // clipboard read off the keystroke path
            chrome.updateZenButton(); // re-position the Zen "Z" if the new file is/isn't Markdown
            chrome.updateExpertButton(); // and the Expert "E"
            fileWorkflows.checkExternalChanges(); // prompt if the file we just switched to changed on disk
            git.refresh(); // update branch/status + this file's gutter change bars
            refreshBuildTools(); // re-detect marker files for the newly active file/project
            lspCoordinator
                    .updateStatusBar(); // show/hide the "LSP: <server>" segment + Problems window for the new file
            debugCoordinator.updateDebugAvailability(); // Debug window only for a debuggable file / live session
            updateRunButton(); // show the Run button only for a compact source file
            updateBufferToolWindows(); // hide buffer-only tool windows when there's no actionable buffer
            csvCoordinator.refreshFor(activeBuffer()); // re-target the CSV grid at the new active buffer
            httpClient.refreshFor(activeBuffer()); // …and the .http response preview
            historyCoordinator.refresh(); // re-gate + reload the Local File History list for the new active file
            installPrompts.maybeOfferInstall(
                    activeBuffer()); // offer to install this language's LSP/DAP if it's missing
            refreshMenuEnablement(); // buffer-shaped menu items (preview, CSV, .http, Typst) follow the tab
        });
        editorArea.addTabsListener((ListChangeListener<Tab>) c -> {
            boolean membershipChanged = false;
            while (c.next()) {
                membershipChanged |= c.wasAdded() || c.wasRemoved();
                // A tab added in the background (e.g. session restore opening many at once) starts
                // rendering-inactive so it holds no minimap snapshot until it's first shown.
                if (c.wasAdded()) {
                    Tab selected = editorArea.selectedTab();
                    for (Tab added : c.getAddedSubList()) {
                        EditorBuffer b = bufferOf(added);
                        if (b != null && added != selected) {
                            b.setRenderingActive(false);
                        }
                    }
                }
                // A pin reorder removes+re-adds the same tab, and so does moving one between editor groups
                // (EditorArea.isRelocating) — in both cases the tab is not closing, so skip the cleanup or
                // the buffer would be disposed and its language server shut down out from under a live tab.
                if (c.wasRemoved() && !reordering && !editorArea.isRelocating()) {
                    mru.removeAll(c.getRemoved());
                    pinned.removeAll(c.getRemoved());
                    // Tear down each closed buffer. dispose() bumps the generation guards so any
                    // in-flight preview/highlight/TODO task discards its result instead of touching a
                    // dead buffer, and drops the preview subscription. It does NOT stop threads: the
                    // preview/highlight executors are shared app-lifetime pools, not one pair per
                    // buffer (measured: 4 highlighter threads with 24 files open). See
                    // EditorBuffer.dispose.
                    for (Tab removed : c.getRemoved()) {
                        EditorBuffer closed = bufferOf(removed);
                        if (closed != null) {
                            fileWorkflows.invalidatePendingWrites(closed);
                            if (closed.getPath() != null && lspManager.isManaged(closed.getPath())) {
                                lspManager.closeDocument(closed.getPath());
                                lspCoordinator.clearDiagnostics(closed.getPath());
                            }
                            logViewer.onBufferClosed(closed); // cancel tail-follow + drop per-buffer state
                            csvCoordinator.onBufferClosed(closed); // drop the CSV grid's edit listener
                            httpClient.onBufferClosed(closed); // drop this buffer's HTTP response panel
                            closed.dispose();
                        } else {
                            // Tab.setOnClosed only fires for a click on the ✕ — never for a programmatic
                            // remove (Ctrl-W, Close All/Others/Left/Right, window close). See disposeViewerTab.
                            disposeViewerTab(removed);
                        }
                    }
                }
            }
            if (membershipChanged && projectPanel != null) {
                projectPanel.refreshOpenFiles();
            }
        });
        if (projectPanel != null) {
            EditorBuffer selected = activeBuffer();
            projectPanel.setActiveFile(selected == null ? null : selected.getPath());
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
    private void activateAndFocusTab(Tab tab) {
        editorArea.select(tab);
        EditorBuffer buffer = bufferOf(tab);
        if (buffer != null) {
            Platform.runLater(() -> buffer.getArea().requestFocus());
        }
    }

    /**
     * All open tabs for the Switcher, most-recently-used first. The MRU only records tabs that have
     * been selected, so any open-but-never-activated tab (e.g. a restored background tab) is appended
     * in tab-strip order — ensuring every open file is listed.
     */
    private List<Tab> openTabsForSwitcher() {
        List<Tab> ordered = new ArrayList<>();
        for (Tab tab : mru) {
            if (editorArea.contains(tab)) {
                ordered.add(tab);
            }
        }
        for (Tab tab : editorArea.tabs()) {
            if (!ordered.contains(tab)) {
                ordered.add(tab);
            }
        }
        return ordered;
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
        BorderPane withBreadcrumb = new BorderPane(editorArea.node());
        withBreadcrumb.setBottom(breadcrumb);
        return withBreadcrumb;
    }

    private void setupToolWindows() {
        editorArea.setDraggedTabSource(() -> draggedTab); // drops onto a group body move/split it
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
        projectPanel = new ProjectPanel(
                fileWorkflows::openPath,
                this::onProjectFileRenamed,
                this::onProjectFileDeleted,
                this::isPathModified,
                this::hasFileOpen,
                this::projectMapPreviewContent);
        projectPanel.setRememberedMapFlow(config.getWorkspaceState().getProjectMapFlow(), flow -> {
            config.getWorkspaceState().setProjectMapFlow(flow);
            config.save();
        });
        projectPanel.setPrompt(this::promptText); // in-scene rename prompt
        projectPanel.setDeletePreparation(new ProjectDeleteCoordinator(
                path -> bufferOf(tabForPath(path)),
                path -> editorArea.select(tabForPath(path)),
                fileWorkflows::hasPendingSave,
                this::confirmCloseIfDirty,
                fileWorkflows::invalidatePendingWrite,
                (path, completion) -> historyCoordinator.captureBeforeDeleteDurably(path, completion),
                this::setStatus));
        projectPanel.setOnNewFile(templateActions::newFileOfType); // folder "New ▸ <type>"
        projectPanel.setOnNewFromTemplate(templateActions::newFromTemplate); // folder "New From Template…"
        projectPanel.setMavenMenu(mavenProjectCoordinator::mavenMenu);
        projectPanel.setOnNewMavenProject(mavenProjectCoordinator::newProject); // folder "New Maven Project…"
        projectPanel.setOnStatus(this::setStatus); // drag-move / multi-delete feedback in the status bar
        projectPanel.setMapOutputActions(
                exports::printProjectMap, image -> exports.exportProjectMapPdf(image, projectMapBaseName()));
        // An external program (a terminal `git`, another editor, a build) changed files under the repo while
        // Editora already had focus: re-evaluate the working-tree-anchored surfaces the focus-regain handler
        // also refreshes — Git status + the Commit stripe, build-tool markers, and open diffs (#529).
        projectPanel.setOnExternalChange(() -> {
            com.editora.config.PathKeys.invalidateCanonicalCache(); // files moved on disk under us (#680)
            git.refresh();
            refreshBuildTools();
            diffCoordinator.refreshOpenDiffs();
        });
        // Forward the watcher's raw events to the language servers (didChangeWatchedFiles) so a git
        // checkout / CLI build / external editor doesn't leave their project models stale (#677).
        projectPanel.setFsChangeSink(lspCoordinator::watchedFilesChanged);
        projectPanel.setOnReveal((p, dir) -> revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        projectPanel.setOnOpenTerminal((p, dir) -> openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        // Breadcrumb crumbs offer the same Reveal / Open Terminal as the Project tree (local files only).
        breadcrumb.setOnReveal((p, dir) -> revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        breadcrumb.setOnOpenTerminal((p, dir) -> openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        projectPanel.setFileActions(new ProjectPanel.FileActions() {
            @Override
            public boolean localHistoryEnabled() {
                return MainController.this.localHistoryEnabled();
            }

            @Override
            public void showLocalHistory(Path file) {
                historyCoordinator.showForPath(file);
            }

            @Override
            public boolean gitAvailable() {
                return git.isAvailable();
            }

            @Override
            public void gitShowFileHistory(Path file) {
                git.ifEnabled(() -> gitWindows.gitFileHistoryForPath(file));
            }

            @Override
            public void gitCompareWithHead(Path file) {
                git.ifEnabled(() -> diffCoordinator.diffPathVsHead(file));
            }

            @Override
            public void gitCompareWithBranch(Path file) {
                git.ifEnabled(() -> diffCoordinator.diffPathVsBranch(file));
            }

            @Override
            public void gitCompareWithTag(Path file) {
                git.ifEnabled(() -> diffCoordinator.diffPathVsTag(file));
            }

            @Override
            public void gitCompareWithRevision(Path file) {
                git.ifEnabled(() -> diffCoordinator.diffPathVsCommit(file));
            }

            @Override
            public void gitAnnotate(Path file) {
                git.ifEnabled(() -> {
                    fileWorkflows.openPath(file);
                    git.annotateActive();
                });
            }

            @Override
            public void gitStage(Path file) {
                git.ifEnabled(() -> git.gitStagePath(file));
            }

            @Override
            public void gitUnstage(Path file) {
                git.ifEnabled(() -> git.gitUnstagePath(file));
            }

            @Override
            public void gitRevert(Path file) {
                git.ifEnabled(() -> git.gitRevertPath(file));
            }

            @Override
            public void gitAddToGitignore(Path file) {
                git.ifEnabled(() -> git.addToGitignore(file));
            }
        });
        projectPanel.setMarkerActions(new ProjectPanel.MarkerActions() {
            @Override
            public boolean personalNotesEnabled() {
                return notesCoordinator.isEnabled();
            }

            @Override
            public boolean hasBookmarks(Path file) {
                return bookmarkCoordinator.hasBookmarks(file);
            }

            @Override
            public boolean hasPersonalNotes(Path file) {
                return notesCoordinator.hasPersonalNotes(file);
            }

            @Override
            public String personalNotesTooltip(Path path) {
                return notesCoordinator.personalNotesTooltip(path);
            }

            @Override
            public void addBookmark(Path file) {
                bookmarkCoordinator.addBookmark(file);
            }

            @Override
            public void addBookmark(Path file, int line) {
                bookmarkCoordinator.addBookmark(file, line);
            }

            @Override
            public void addPersonalNote(Path file) {
                notesCoordinator.addPersonalNote(file);
            }

            @Override
            public void addPersonalNote(Path file, com.editora.editor.NoteDraft draft) {
                notesCoordinator.addPersonalNote(file, draft);
            }

            @Override
            public java.util.List<com.editora.config.PersonalNote> personalNotes(Path path) {
                java.util.List<com.editora.config.PersonalNote> notes = notesCoordinator.notesFor(path);
                return notes == null ? java.util.List.of() : java.util.List.copyOf(notes);
            }

            @Override
            public void updatePersonalNote(Path path, com.editora.config.PersonalNote note, String body) {
                notesCoordinator.updatePersonalNote(path, note, body);
            }
        });
        bookmarkCoordinator.setOnChanged(projectPanel::refreshMarkers);
        notesCoordinator.setOnChanged(projectPanel::refreshMarkers);
        projectToolWindow = new ToolWindow(
                "project",
                tr("toolwindow.project"),
                ToolWindow.Side.RIGHT,
                Icons::project,
                projectPanel,
                "tool.project");
        structurePanel = new StructurePanel();
        structureToolWindow = new ToolWindow(
                "structure",
                tr("toolwindow.structure"),
                ToolWindow.Side.RIGHT,
                Icons::structure,
                structurePanel,
                "tool.structure");
        bookmarksToolWindow = new ToolWindow(
                "bookmarks",
                tr("toolwindow.bookmarks"),
                ToolWindow.Side.RIGHT,
                Icons::bookmark,
                bookmarkCoordinator.panel(),
                "tool.bookmarks");
        notesToolWindow = new ToolWindow(
                "notes",
                tr("toolwindow.notes"),
                ToolWindow.Side.RIGHT,
                Icons::notes,
                notesCoordinator.panel(),
                "tool.notes");
        fileInfoPanel = new FileInformationPanel();
        fileInfoToolWindow = new ToolWindow(
                "file-information",
                tr("toolwindow.file-information"),
                ToolWindow.Side.RIGHT,
                Icons::about,
                fileInfoPanel,
                "tool.fileInformation");
        undoHistoryPanel = new UndoHistoryPanel();
        undoHistoryToolWindow = new ToolWindow(
                "undoHistory",
                tr("toolwindow.undoHistory"),
                ToolWindow.Side.RIGHT,
                Icons::history,
                undoHistoryPanel,
                "tool.undoHistory");
        gitPanel = new GitPanel(new GitPanel.Actions() {
            @Override
            public void open(String path) {
                if (git.repoRoot() != null) {
                    fileWorkflows.openPath(git.repoRoot().resolve(path));
                }
            }

            @Override
            public void stage(List<String> paths) {
                git.gitStagePaths(paths);
            }

            @Override
            public void unstage(List<String> paths) {
                git.gitUnstagePaths(paths);
            }

            @Override
            public void discard(List<String> tracked, List<String> untracked) {
                git.discardChanges(tracked, untracked);
            }

            @Override
            public void stageAll() {
                git.gitOp("Staged all changes", "add", "-A");
            }

            @Override
            public void commit(String message) {
                git.gitCommit(message);
            }

            @Override
            public void push() {
                git.gitPush();
            }

            @Override
            public void refresh() {
                git.invalidateCaches();
                git.afterMutation();
            }

            @Override
            public void review(boolean staged) {
                diffCoordinator.reviewGitChanges(staged);
            }

            @Override
            public void diff(String path, boolean staged) {
                diffCoordinator.diffGitPanelFile(path, staged);
            }
        });
        gitPanel.setOnClone(git::cloneRepo);
        gitPanel.setOnGenerateCommitMessage(aiCoordinator::generateCommitMessage);
        commitToolWindow = new ToolWindow(
                "commit", tr("toolwindow.commit"), ToolWindow.Side.RIGHT, Icons::git, gitPanel, "tool.commit");
        gitLogPanel = new GitLogPanel(gitLogOps = gitWindows.gitLogActions());
        gitLogToolWindow = new ToolWindow(
                "gitLog", tr("toolwindow.gitLog"), ToolWindow.Side.BOTTOM, Icons::gitLog, gitLogPanel, "tool.gitLog");
        githubPanel = new GitHubPanel(gitWindows.githubActions());
        githubToolWindow = new ToolWindow(
                "github", tr("toolwindow.github"), ToolWindow.Side.BOTTOM, Icons::github, githubPanel, "tool.github");
        historyCoordinator = new HistoryCoordinator(coordinatorHost, diffCoordinator, historyOps(), config.shared());
        fileHistoryToolWindow = new ToolWindow(
                "fileHistory",
                tr("toolwindow.fileHistory"),
                ToolWindow.Side.BOTTOM,
                Icons::history,
                historyCoordinator.panel(),
                "tool.fileHistory");
        searchToolWindow = new ToolWindow(
                "search",
                tr("toolwindow.search"),
                ToolWindow.Side.RIGHT,
                // The same glyph as the toolbar's Find in Files button — one feature, one mark. The plain
                // magnifier this used belongs to the in-file find bar, which is a different thing.
                Icons::findInFiles,
                searchCoordinator.panel(),
                "tool.search");
        todoToolWindow = new ToolWindow(
                "todo",
                tr("toolwindow.todo"),
                ToolWindow.Side.BOTTOM,
                Icons::todo,
                todoCoordinator.panel(),
                "tool.todo");
        markdownLintPanel = new MarkdownLintPanel(new MarkdownLintPanel.Actions() {
            @Override
            public void open(java.nio.file.Path file, int line, int col) {
                fileWorkflows.openPath(file);
                Platform.runLater(() -> sessions.gotoInFile(file, line, col));
            }

            @Override
            public void refresh() {
                previews.runMarkdownLintScan();
            }
        });
        markdownLintToolWindow = new ToolWindow(
                "markdownLint",
                tr("toolwindow.markdownLint"),
                ToolWindow.Side.BOTTOM,
                Icons::warning,
                markdownLintPanel,
                "tool.markdownLint");
        problemsToolWindow = new ToolWindow(
                "problems",
                tr("toolwindow.problems"),
                ToolWindow.Side.BOTTOM,
                Icons::problems,
                lspCoordinator.problemsPanel(),
                "tool.problems");
        referencesToolWindow = new ToolWindow(
                "references",
                tr("toolwindow.references"),
                ToolWindow.Side.BOTTOM,
                Icons::find,
                lspCoordinator.referencesPanel(),
                "tool.references");
        hierarchyToolWindow = new ToolWindow(
                "hierarchy",
                tr("toolwindow.hierarchy"),
                ToolWindow.Side.BOTTOM,
                Icons::structure,
                lspCoordinator.hierarchyPanel(),
                "tool.hierarchy");
        runToolWindow = new ToolWindow(
                "run", tr("toolwindow.run"), ToolWindow.Side.BOTTOM, Icons::run, runCoordinator.panel(), "tool.run");
        debugCoordinator = new DebugCoordinator(
                coordinatorHost, dapManager, lspManager, lspCoordinator, new DebugCoordinator.Ops() {
                    @Override
                    public void openToolWindow() {
                        toolWindows.open(debugToolWindow);
                    }

                    @Override
                    public void editConfiguration(String name) {
                        runConfigurations.runConfigEditor.accept(name);
                    }

                    @Override
                    public void toggleToolWindow() {
                        toolWindows.toggle(debugToolWindow);
                    }

                    @Override
                    public void setToolWindowAvailable(boolean available) {
                        toolWindows.setAvailable(debugToolWindow, available);
                        // Called on every DapManager state change (including SUSPENDED), which is what the
                        // step/evaluate menu items are gated on.
                        refreshMenuEnablement();
                    }

                    @Override
                    public void setStatusDebug(String text) {
                        statusBar.setDebug(text);
                    }

                    @Override
                    public void setStatusDebugLoading(boolean loading) {
                        statusBar.setDebugLoading(loading);
                    }

                    @Override
                    public boolean saveBuffer(EditorBuffer buffer) {
                        return fileWorkflows.saveSynchronously(buffer);
                    }

                    @Override
                    public String programArgs(Path path) {
                        return programArgsFor(path);
                    }

                    @Override
                    public void openLink(com.editora.run.StackTraceLinks.Link link) {
                        testNavigation.openRunLink(link);
                    }

                    @Override
                    public void openPath(Path file) {
                        fileWorkflows.openPath(file);
                    }

                    @Override
                    public EditorBuffer bufferForPath(Path file) {
                        return bufferOf(tabForPath(file));
                    }

                    @Override
                    public void afterBufferLoad(EditorBuffer buffer, Runnable action) {
                        if (fileWorkflows.loadingBuffers.contains(buffer)) {
                            fileWorkflows
                                    .afterBufferLoad
                                    .computeIfAbsent(buffer, ignored -> new ArrayList<>())
                                    .add(action);
                        } else {
                            action.run();
                        }
                    }

                    @Override
                    public List<String> debugWatches() {
                        return config.getWorkspaceState().getDebugWatches();
                    }

                    @Override
                    public void persistDebugWatches(List<String> watches) {
                        config.getWorkspaceState().setDebugWatches(new java.util.ArrayList<>(watches));
                        config.save();
                    }

                    @Override
                    public java.util.Map<String, List<com.editora.config.Breakpoint>> breakpointMap() {
                        return config.getBreakpoints();
                    }

                    @Override
                    public void saveBreakpoints() {
                        config.saveBreakpoints();
                    }
                });
        debugToolWindow = new ToolWindow(
                "debug",
                tr("toolwindow.debug"),
                ToolWindow.Side.BOTTOM,
                Icons::debug,
                debugCoordinator.panel(),
                "tool.debug");
        externalToolToolWindow = new ToolWindow(
                "externalTool",
                tr("toolwindow.externalTools"),
                ToolWindow.Side.BOTTOM,
                Icons::tools,
                externalToolCoordinator.panel(),
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
                            c.tasksPanel(),
                            "tool." + tool.id()));
        }
        // A single shared "Output" console for every build tool (auto-opens on a run).
        buildOutputPanel.setOnLink(testNavigation::openRunLink);
        buildOutputPanel.setOnUrl(this::openExternalUrl);
        installCommandLogs();
        buildOutputToolWindow = new ToolWindow(
                "buildOutput",
                tr("toolwindow.buildOutput"),
                ToolWindow.Side.BOTTOM,
                Icons::terminal,
                buildOutputPanel,
                "tool.buildOutput");
        testResultsToolWindow = new ToolWindow(
                "testResults",
                tr("toolwindow.testResults"),
                ToolWindow.Side.BOTTOM,
                Icons::testResults,
                testRunCoordinator.panel(),
                "tool.testResults");
        // Mirror a recognized `test` run of any build tool into the Test Results window.
        buildCoordinators.forEach(c -> c.setTestRunHook(testRunCoordinator));
        installCoordinator = new InstallCoordinator(coordinatorHost, new InstallCoordinator.Ops() {
            @Override
            public java.nio.file.Path configDir() {
                return config.getConfigDir();
            }

            @Override
            public boolean lspAvailable(String serverId) {
                return lspCoordinator.isServerAvailable(serverId);
            }

            @Override
            public boolean dapAvailable(String language) {
                return dapManager.isLanguageAvailable(language);
            }

            @Override
            public boolean mmdcAvailable() {
                return mermaid.mmdcAvailable();
            }

            @Override
            public boolean typstCliAvailable() {
                return typst.isTypstCliAvailable();
            }

            @Override
            public void reapplyToolSupport() {
                lspManager.invalidateDetection();
                lspCoordinator.applySupport();
                debugCoordinator.applySupport();
                mermaid.applySupport();
                diagram.applySupport();
                typst.applySupport();
                requestSave(); // persist a resolved command (e.g. the installed jdtls launcher path)
                if (settingsWindow != null) {
                    settingsWindow.refreshDetectionStatus(); // flip the Settings Install buttons to "Installed"
                }
                installPrompts.maybeOfferInstall(
                        activeBuffer()); // re-evaluate the editor install banner after re-detection
            }
        });
        remoteCoordinator = new RemoteCoordinator(coordinatorHost, remoteOps());
        remoteToolWindow = new ToolWindow(
                "remote",
                tr("toolwindow.remote"),
                ToolWindow.Side.RIGHT,
                Icons::remote,
                remoteCoordinator.panel(),
                "tool.remote");
        agentToolWindow = new ToolWindow(
                "agent",
                tr("toolwindow.agent"),
                ToolWindow.Side.RIGHT,
                Icons::agent,
                agentCoordinator.panel(),
                "tool.agent");
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
        toolWindows.register(problemsToolWindow);
        toolWindows.register(referencesToolWindow, false); // default-hidden; shown on demand by Find References
        toolWindows.register(hierarchyToolWindow, false); // default-hidden; shown on demand by lsp.callHierarchy (#682)
        toolWindows.register(runToolWindow);
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

    /** Home-collapses an absolute folder path for a scope label (e.g. {@code ~/proj}). */
    private static String homeCollapsed(String full) {
        return com.editora.config.PathDisplay.collapseHome(full);
    }

    /** Starts AceJump on the active buffer: type a character, then a label, to jump the caret. */
    private void startAceJump() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        setStatus(tr("acejump.prompt"));
        b.startAceJump();
    }

    private void startAceJumpLine() {
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

    /** Window hooks for {@link HistoryCoordinator} (config history buckets + tool window + tree + current text). */
    private RemoteCoordinator.Ops remoteOps() {
        return new RemoteCoordinator.Ops() {
            @Override
            public com.editora.command.KeymapManager keymap() {
                return keymap;
            }

            @Override
            public void openPath(Path file) {
                fileWorkflows.openPath(file);
            }

            @Override
            public void setProjectRoot(Path root) {
                projectPanel.setRoot(root);
            }

            @Override
            public void openProjectToolWindow() {
                toolWindows.open(projectToolWindow);
            }

            @Override
            public Path activeProjectRoot() {
                Project active = projects == null ? null : projects.active();
                return active == null ? null : Path.of(active.root());
            }

            @Override
            public void reportError(String summary, String detail) {
                git.gitError(summary, detail);
            }

            @Override
            public java.util.List<com.editora.vfs.RemoteConnection> connections() {
                return config.getConnections();
            }

            @Override
            public void putConnection(com.editora.vfs.RemoteConnection conn) {
                config.putConnection(conn);
            }

            @Override
            public void removeConnection(String id) {
                config.removeConnection(id);
            }

            @Override
            public void invalidatePendingWrite(Path path) {
                fileWorkflows.invalidatePendingWrite(path);
            }
        };
    }

    private HistoryCoordinator.Ops historyOps() {
        return new HistoryCoordinator.Ops() {
            @Override
            public java.util.Map<String, java.util.List<com.editora.config.HistoryRevision>> historyMap() {
                return config.getHistory();
            }

            @Override
            public java.util.Map<String, java.util.Map<String, java.util.List<com.editora.config.HistoryRevision>>>
                    historyByProject() {
                return config.getHistoryByProject();
            }

            @Override
            public void saveHistory() {
                config.saveHistory();
            }

            @Override
            public void saveHistory(java.util.function.Consumer<Boolean> completion) {
                config.saveHistory(completion);
            }

            @Override
            public java.nio.file.Path blobsDir() {
                return config.getHistoryBlobsDir();
            }

            @Override
            public void setToolWindowAvailable(boolean available) {
                toolWindows.setAvailable(fileHistoryToolWindow, available);
            }

            @Override
            public void openToolWindow() {
                toolWindows.open(fileHistoryToolWindow);
            }

            @Override
            public void openPath(java.nio.file.Path file) {
                fileWorkflows.openPath(file);
            }

            @Override
            public void refreshProjectTree() {
                projectPanel.refreshTree();
            }

            @Override
            public String currentTextOf(java.nio.file.Path file) {
                return gitWindows.currentTextOf(file);
            }
        };
    }

    /** Reconciles LaTeX math rendering with its setting + the app theme; re-renders open previews. */
    private void applyMathSupport() {
        com.editora.editor.MathImages.configure(config.getSettings().isMathSupport(), appThemeDark());
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.hasPreview()) {
                b.refreshPreview();
            }
        }
    }

    // --- Feature coordinators (each peeled off MainController; share one CoordinatorHost adapter) -----

    /** One shared adapter handed to every feature coordinator (replaces a per-feature anonymous Host). */
    private final EditingCoordinator editing = new EditingCoordinator(new EditingCoordinator.Host() {
        @Override
        public EditorSettingsCoordinator editorSettings() {
            return editorSettings;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public CommandRegistry registry() {
            return registry;
        }

        @Override
        public FindReplaceBar findBar() {
            return findBar;
        }

        @Override
        public StatusBar statusBar() {
            return statusBar;
        }

        @Override
        public SettingsWindow settingsWindow() {
            return settingsWindow;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public void updateWindowTitle() {
            MainController.this.updateWindowTitle();
        }

        @Override
        public void navigateToLine(int line) {
            MainController.this.navigateToLine(line);
        }

        @Override
        public GitCoordinator git() {
            return git;
        }

        @Override
        public LspCoordinator lspCoordinator() {
            return lspCoordinator;
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer b) {
            return MainController.this.isLocalBuffer(b);
        }

        @Override
        public void refreshPasteState() {
            MainController.this.refreshPasteState();
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public CodeArea activeArea() {
            return MainController.this.activeArea();
        }

        @Override
        public void promptText(
                String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
            MainController.this.promptText(title, label, initial, onAccept);
        }

        @Override
        public SelectionPolicy selPolicy() {
            return MainController.this.selPolicy();
        }
    });

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

    @FXML
    private void onFind() {
        editing.onFind();
    }

    private final TemplateCoordinator templateActions = new TemplateCoordinator(new TemplateCoordinator.Host() {
        @Override
        public FileWorkflowCoordinator fileWorkflows() {
            return fileWorkflows;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public KeymapManager keymap() {
            return keymap;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public ProjectPanel projectPanel() {
            return projectPanel;
        }

        @Override
        public ProjectManager projects() {
            return projects;
        }

        @Override
        public WindowManager windowManager() {
            return windowManager;
        }

        @Override
        public boolean projectsEnabled() {
            return MainController.this.projectsEnabled();
        }

        @Override
        public String homeCollapsed(String full) {
            return MainController.this.homeCollapsed(full);
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public void setError(String message) {
            MainController.this.setError(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer) {
            return MainController.this.addBuffer(buffer);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer, boolean select) {
            return MainController.this.addBuffer(buffer, select);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
            return MainController.this.addBuffer(buffer, select, resolvePathSettings);
        }

        @Override
        public void promptText(
                String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
            MainController.this.promptText(title, label, initial, onAccept);
        }
    });

    private final EditorSettingsCoordinator editorSettings =
            new EditorSettingsCoordinator(new EditorSettingsCoordinator.Host() {
                @Override
                public TestNavigationCoordinator testNavigation() {
                    return testNavigation;
                }

                @Override
                public FileWorkflowCoordinator fileWorkflows() {
                    return fileWorkflows;
                }

                @Override
                public WindowChromeCoordinator chrome() {
                    return chrome;
                }

                @Override
                public PreviewCoordinator previews() {
                    return previews;
                }

                @Override
                public EditorArea editorArea() {
                    return editorArea;
                }

                @Override
                public Stage stage() {
                    return stage;
                }

                @Override
                public ConfigManager config() {
                    return config;
                }

                @Override
                public CommandRegistry registry() {
                    return registry;
                }

                @Override
                public KeymapManager keymap() {
                    return keymap;
                }

                @Override
                public StatusBar statusBar() {
                    return statusBar;
                }

                @Override
                public SettingsWindow settingsWindow() {
                    return settingsWindow;
                }

                @Override
                public OverlayHost overlayHost() {
                    return overlayHost;
                }

                @Override
                public WindowManager windowManager() {
                    return windowManager;
                }

                @Override
                public BuildOutputPanel buildOutputPanel() {
                    return buildOutputPanel;
                }

                @Override
                public DebugCoordinator debugCoordinator() {
                    return debugCoordinator;
                }

                @Override
                public HistoryCoordinator historyCoordinator() {
                    return historyCoordinator;
                }

                @Override
                public WelcomePane welcomePane() {
                    return welcomePane;
                }

                @Override
                public Tab welcomeTab() {
                    return welcomeTab;
                }

                @Override
                public Tab doctorTab() {
                    return doctorTab;
                }

                @Override
                public void applyProjectSupport() {
                    MainController.this.applyProjectSupport();
                }

                @Override
                public void applyMathSupport() {
                    MainController.this.applyMathSupport();
                }

                @Override
                public EditingCoordinator editing() {
                    return editing;
                }

                @Override
                public CoordinatorHost coordinatorHost() {
                    return coordinatorHost;
                }

                @Override
                public GitCoordinator git() {
                    return git;
                }

                @Override
                public GitHubCoordinator github() {
                    return github;
                }

                @Override
                public MermaidCoordinator mermaid() {
                    return mermaid;
                }

                @Override
                public DiagramCoordinator diagram() {
                    return diagram;
                }

                @Override
                public TypstCoordinator typst() {
                    return typst;
                }

                @Override
                public HtmlPreviewCoordinator htmlPreview() {
                    return htmlPreview;
                }

                @Override
                public LogViewerCoordinator logViewer() {
                    return logViewer;
                }

                @Override
                public ExternalToolCoordinator externalToolCoordinator() {
                    return externalToolCoordinator;
                }

                @Override
                public IndexCoordinator indexCoordinator() {
                    return indexCoordinator;
                }

                @Override
                public TodoCoordinator todoCoordinator() {
                    return todoCoordinator;
                }

                @Override
                public CsvCoordinator csvCoordinator() {
                    return csvCoordinator;
                }

                @Override
                public SearchCoordinator searchCoordinator() {
                    return searchCoordinator;
                }

                @Override
                public RunCoordinator runCoordinator() {
                    return runCoordinator;
                }

                @Override
                public TestRunCoordinator testRunCoordinator() {
                    return testRunCoordinator;
                }

                @Override
                public LspCoordinator lspCoordinator() {
                    return lspCoordinator;
                }

                @Override
                public NotesCoordinator notesCoordinator() {
                    return notesCoordinator;
                }

                @Override
                public HttpClientCoordinator httpClient() {
                    return httpClient;
                }

                @Override
                public void applyAgentSupport() {
                    MainController.this.applyAgentSupport();
                }

                @Override
                public AiCoordinator aiCoordinator() {
                    return aiCoordinator;
                }

                @Override
                public DoctorCoordinator doctorCoordinator() {
                    return doctorCoordinator;
                }

                @Override
                public void applyMcpSupport() {
                    MainController.this.applyMcpSupport();
                }

                @Override
                public void applyMainGutter(EditorBuffer buffer) {
                    testNavigation.applyMainGutter(buffer);
                }

                @Override
                public java.util.Map<String, String> invertBindings() {
                    return MainController.this.invertBindings();
                }

                @Override
                public void setStatus(String message) {
                    MainController.this.setStatus(message);
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return MainController.this.activeBuffer();
                }

                @Override
                public void openPath(Path file) {
                    fileWorkflows.openPath(file);
                }

                @Override
                public void openPath(Path file, boolean quietIfOpen) {
                    fileWorkflows.openPath(file, quietIfOpen);
                }

                @Override
                public EditorBuffer bufferOf(Tab tab) {
                    return MainController.this.bufferOf(tab);
                }

                @Override
                public void requestSave() {
                    MainController.this.requestSave();
                }

                @Override
                public void promptText(
                        String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
                    MainController.this.promptText(title, label, initial, onAccept);
                }

                @Override
                public void refreshSpellAllTabs() {
                    MainController.this.refreshSpellAllTabs();
                }

                @Override
                public void applyEditorTheme(String themeName) {
                    MainController.this.applyEditorTheme(themeName);
                }
            });

    private final RunConfigurationCoordinator runConfigurations =
            new RunConfigurationCoordinator(new RunConfigurationCoordinator.Host() {
                @Override
                public WindowChromeCoordinator chrome() {
                    return chrome;
                }

                @Override
                public Button runConfigStopButton() {
                    return runConfigStopButton;
                }

                @Override
                public Button runConfigDebugButton() {
                    return runConfigDebugButton;
                }

                @Override
                public Button runConfigRunButton() {
                    return runConfigRunButton;
                }

                @Override
                public javafx.scene.control.ComboBox<com.editora.config.RunConfiguration> runConfigCombo() {
                    return runConfigCombo;
                }

                @Override
                public Stage stage() {
                    return stage;
                }

                @Override
                public ConfigManager config() {
                    return config;
                }

                @Override
                public CommandRegistry registry() {
                    return registry;
                }

                @Override
                public SettingsWindow settingsWindow() {
                    return settingsWindow;
                }

                @Override
                public OverlayHost overlayHost() {
                    return overlayHost;
                }

                @Override
                public DebugCoordinator debugCoordinator() {
                    return debugCoordinator;
                }

                @Override
                public String homeCollapsed(String full) {
                    return MainController.this.homeCollapsed(full);
                }

                @Override
                public List<BuildCoordinator> buildCoordinators() {
                    return buildCoordinators;
                }

                @Override
                public RunCoordinator runCoordinator() {
                    return runCoordinator;
                }

                @Override
                public String readGradleBuildFile(java.nio.file.Path root) {
                    return MainController.this.readGradleBuildFile(root);
                }

                @Override
                public void setStatus(String message) {
                    MainController.this.setStatus(message);
                }

                @Override
                public void setError(String message) {
                    MainController.this.setError(message);
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return MainController.this.activeBuffer();
                }

                @Override
                public boolean save(EditorBuffer buffer) {
                    return fileWorkflows.saveSynchronously(buffer);
                }

                @Override
                public void requestSave() {
                    MainController.this.requestSave();
                }

                @Override
                public String programArgsFor(Path path) {
                    return MainController.this.programArgsFor(path);
                }

                @Override
                public String suggestedMainClass() {
                    return MainController.this.suggestedMainClass();
                }

                @Override
                public Path windowProjectRoot() {
                    return MainController.this.windowProjectRoot();
                }

                @Override
                public Path activeProjectRoot() {
                    return MainController.this.activeProjectRoot();
                }

                @Override
                public void promptText(
                        String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
                    MainController.this.promptText(title, label, initial, onAccept);
                }
            });

    @FXML
    private javafx.scene.control.ComboBox<com.editora.config.RunConfiguration> runConfigCombo;

    @FXML
    private Button runConfigRunButton;

    @FXML
    private Button runConfigDebugButton;

    @FXML
    private Button runConfigStopButton;

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

    private final WindowCommandRegistrar windowCommands = new WindowCommandRegistrar(new WindowCommandRegistrar.Host() {
        @Override
        public WindowChromeCoordinator chrome() {
            return chrome;
        }

        @Override
        public GitWindowCoordinator gitWindows() {
            return gitWindows;
        }

        @Override
        public NavigationCoordinator navigation() {
            return navigation;
        }

        @Override
        public PreviewCoordinator previews() {
            return previews;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public CommandRegistry registry() {
            return registry;
        }

        @Override
        public MacroCoordinator macroCoordinator() {
            return macroCoordinator;
        }

        @Override
        public StatusBar statusBar() {
            return statusBar;
        }

        @Override
        public SettingsWindow settingsWindow() {
            return settingsWindow;
        }

        @Override
        public com.editora.snippet.SnippetManager snippets() {
            return snippets;
        }

        @Override
        public PluginCoordinator pluginCoordinator() {
            return pluginCoordinator;
        }

        @Override
        public ProjectPanel projectPanel() {
            return projectPanel;
        }

        @Override
        public WindowManager windowManager() {
            return windowManager;
        }

        @Override
        public QuickOpen<Project> projectPicker() {
            return projectPicker;
        }

        @Override
        public ToolWindowManager toolWindows() {
            return toolWindows;
        }

        @Override
        public ToolWindow projectToolWindow() {
            return projectToolWindow;
        }

        @Override
        public ToolWindow structureToolWindow() {
            return structureToolWindow;
        }

        @Override
        public ToolWindow bookmarksToolWindow() {
            return bookmarksToolWindow;
        }

        @Override
        public ToolWindow notesToolWindow() {
            return notesToolWindow;
        }

        @Override
        public ToolWindow fileInfoToolWindow() {
            return fileInfoToolWindow;
        }

        @Override
        public ToolWindow undoHistoryToolWindow() {
            return undoHistoryToolWindow;
        }

        @Override
        public BookmarkCoordinator bookmarkCoordinator() {
            return bookmarkCoordinator;
        }

        @Override
        public ToolWindow searchToolWindow() {
            return searchToolWindow;
        }

        @Override
        public ToolWindow markdownLintToolWindow() {
            return markdownLintToolWindow;
        }

        @Override
        public RemoteCoordinator remoteCoordinator() {
            return remoteCoordinator;
        }

        @Override
        public ToolWindow problemsToolWindow() {
            return problemsToolWindow;
        }

        @Override
        public ToolWindow referencesToolWindow() {
            return referencesToolWindow;
        }

        @Override
        public ToolWindow hierarchyToolWindow() {
            return hierarchyToolWindow;
        }

        @Override
        public ToolWindow runToolWindow() {
            return runToolWindow;
        }

        @Override
        public ToolWindow externalToolToolWindow() {
            return externalToolToolWindow;
        }

        @Override
        public ToolWindow buildOutputToolWindow() {
            return buildOutputToolWindow;
        }

        @Override
        public ToolWindow testResultsToolWindow() {
            return testResultsToolWindow;
        }

        @Override
        public ToolWindow remoteToolWindow() {
            return remoteToolWindow;
        }

        @Override
        public com.editora.dap.DapManager dapManager() {
            return dapManager;
        }

        @Override
        public ToolWindow debugToolWindow() {
            return debugToolWindow;
        }

        @Override
        public DebugCoordinator debugCoordinator() {
            return debugCoordinator;
        }

        @Override
        public InstallCoordinator installCoordinator() {
            return installCoordinator;
        }

        @Override
        public ToolWindow commitToolWindow() {
            return commitToolWindow;
        }

        @Override
        public GitLogPanel.Actions gitLogOps() {
            return gitLogOps;
        }

        @Override
        public GitHubPanel githubPanel() {
            return githubPanel;
        }

        @Override
        public ToolWindow githubToolWindow() {
            return githubToolWindow;
        }

        @Override
        public HistoryCoordinator historyCoordinator() {
            return historyCoordinator;
        }

        @Override
        public ToolbarCoordinator toolbarCoordinator() {
            return toolbarCoordinator;
        }

        @Override
        public Switcher switcher() {
            return switcher;
        }

        @Override
        public void checkForUpdatesNow() {
            MainController.this.checkForUpdatesNow();
        }

        @Override
        public void openUpdateDownloadPage() {
            MainController.this.openUpdateDownloadPage();
        }

        @Override
        public void showDoctor() {
            MainController.this.showDoctor();
        }

        @Override
        public void showWelcome() {
            MainController.this.showWelcome();
        }

        @Override
        public boolean projectsEnabled() {
            return MainController.this.projectsEnabled();
        }

        @Override
        public void applyProjectSupport() {
            MainController.this.applyProjectSupport();
        }

        @Override
        public void closeProject() {
            MainController.this.closeProject();
        }

        @Override
        public void deleteProject() {
            MainController.this.deleteProject();
        }

        @Override
        public void deleteProject(Project p) {
            MainController.this.deleteProject(p);
        }

        @Override
        public void startAceJump() {
            MainController.this.startAceJump();
        }

        @Override
        public void startAceJumpLine() {
            MainController.this.startAceJumpLine();
        }

        @Override
        public void applyMathSupport() {
            MainController.this.applyMathSupport();
        }

        @Override
        public EditingCoordinator editing() {
            return editing;
        }

        @Override
        public TemplateCoordinator templateActions() {
            return templateActions;
        }

        @Override
        public EditorSettingsCoordinator editorSettings() {
            return editorSettings;
        }

        @Override
        public RunConfigurationCoordinator runConfigurations() {
            return runConfigurations;
        }

        @Override
        public GitCoordinator git() {
            return git;
        }

        @Override
        public DiffCoordinator diffCoordinator() {
            return diffCoordinator;
        }

        @Override
        public GitHubCoordinator github() {
            return github;
        }

        @Override
        public MermaidCoordinator mermaid() {
            return mermaid;
        }

        @Override
        public DiagramCoordinator diagram() {
            return diagram;
        }

        @Override
        public TypstCoordinator typst() {
            return typst;
        }

        @Override
        public ExportCoordinator exports() {
            return exports;
        }

        @Override
        public HtmlPreviewCoordinator htmlPreview() {
            return htmlPreview;
        }

        @Override
        public LogViewerCoordinator logViewer() {
            return logViewer;
        }

        @Override
        public MavenProjectCoordinator mavenProjectCoordinator() {
            return mavenProjectCoordinator;
        }

        @Override
        public ExternalToolCoordinator externalToolCoordinator() {
            return externalToolCoordinator;
        }

        @Override
        public List<BuildCoordinator> buildCoordinators() {
            return buildCoordinators;
        }

        @Override
        public void refreshBuildTools() {
            MainController.this.refreshBuildTools();
        }

        @Override
        public IndexCoordinator indexCoordinator() {
            return indexCoordinator;
        }

        @Override
        public TodoCoordinator todoCoordinator() {
            return todoCoordinator;
        }

        @Override
        public CsvCoordinator csvCoordinator() {
            return csvCoordinator;
        }

        @Override
        public SearchCoordinator searchCoordinator() {
            return searchCoordinator;
        }

        @Override
        public RunCoordinator runCoordinator() {
            return runCoordinator;
        }

        @Override
        public TestRunCoordinator testRunCoordinator() {
            return testRunCoordinator;
        }

        @Override
        public LspCoordinator lspCoordinator() {
            return lspCoordinator;
        }

        @Override
        public NotesCoordinator notesCoordinator() {
            return notesCoordinator;
        }

        @Override
        public HttpClientCoordinator httpClient() {
            return httpClient;
        }

        @Override
        public AgentCoordinator agentCoordinator() {
            return agentCoordinator;
        }

        @Override
        public void applyAgentSupport() {
            MainController.this.applyAgentSupport();
        }

        @Override
        public AiCoordinator aiCoordinator() {
            return aiCoordinator;
        }

        @Override
        public void showTrustedFolders() {
            MainController.this.showTrustedFolders();
        }

        @Override
        public void revokeTrustForActiveRoot() {
            MainController.this.revokeTrustForActiveRoot();
        }

        @Override
        public void ifMcp(Runnable action) {
            MainController.this.ifMcp(action);
        }

        @Override
        public void toggleMcpSupport() {
            MainController.this.toggleMcpSupport();
        }

        @Override
        public void copyMcpEndpoint() {
            MainController.this.copyMcpEndpoint();
        }

        @Override
        public void ifLsp(Runnable action) {
            MainController.this.ifLsp(action);
        }

        @Override
        public void toggleLsp() {
            MainController.this.toggleLsp();
        }

        @Override
        public void runTestsForContext() {
            testNavigation.runTestsForContext();
        }

        @Override
        public void runTestAtCaret(boolean classLevel) {
            testNavigation.runTestAtCaret(classLevel);
        }

        @Override
        public void applyTestRunner() {
            testNavigation.applyTestRunner();
        }

        @Override
        public void findNextMatch() {
            MainController.this.findNextMatch();
        }

        @Override
        public void findPreviousMatch() {
            MainController.this.findPreviousMatch();
        }

        @Override
        public void findReplaceCurrentMatch() {
            MainController.this.findReplaceCurrentMatch();
        }

        @Override
        public void findReplaceAllMatches() {
            MainController.this.findReplaceAllMatches();
        }

        @Override
        public void openDocumentation() {
            MainController.this.openDocumentation();
        }

        @Override
        public void onSplitVertical() {
            MainController.this.onSplitVertical();
        }

        @Override
        public void onSplitHorizontal() {
            MainController.this.onSplitHorizontal();
        }

        @Override
        public void unsplit() {
            MainController.this.unsplit();
        }

        @Override
        public void splitEditorGroup(Orientation orientation) {
            MainController.this.splitEditorGroup(orientation);
        }

        @Override
        public void moveTabToNextGroup() {
            MainController.this.moveTabToNextGroup();
        }

        @Override
        public void focusNextEditorGroup() {
            MainController.this.focusNextEditorGroup();
        }

        @Override
        public void unsplitEditorGroups() {
            MainController.this.unsplitEditorGroups();
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public void onNew() {
            MainController.this.onNew();
        }

        @Override
        public void onOpen() {
            MainController.this.onOpen();
        }

        @Override
        public void openActiveAsText() {
            MainController.this.openActiveAsText();
        }

        @Override
        public void openActiveAsHex() {
            fileWorkflows.openActiveAsHex();
        }

        @Override
        public void onClearRecent() {
            MainController.this.onClearRecent();
        }

        @Override
        public void onSave() {
            MainController.this.onSave();
        }

        @Override
        public void onSaveAsAdmin() {
            fileWorkflows.onSaveAsAdmin();
        }

        @Override
        public void applyAdminSaveSupport() {
            fileWorkflows.applyAdminSaveSupport();
        }

        @Override
        public void saveAsPrompt(EditorBuffer buffer) {
            fileWorkflows.saveAsPrompt(buffer);
        }

        @Override
        public void applyAutoSave() {
            fileWorkflows.applyAutoSave();
        }

        @Override
        public void toggleAutoSave() {
            fileWorkflows.toggleAutoSave();
        }

        @Override
        public void onCloseTab() {
            MainController.this.onCloseTab();
        }

        @Override
        public Tab activeTab() {
            return MainController.this.activeTab();
        }

        @Override
        public void closeOtherTabs(Tab keep) {
            MainController.this.closeOtherTabs(keep);
        }

        @Override
        public void closeAllTabs() {
            MainController.this.closeAllTabs();
        }

        @Override
        public void closeUnmodifiedTabs() {
            MainController.this.closeUnmodifiedTabs();
        }

        @Override
        public void closeTabsToLeft(Tab pivot) {
            MainController.this.closeTabsToLeft(pivot);
        }

        @Override
        public void closeTabsToRight(Tab pivot) {
            MainController.this.closeTabsToRight(pivot);
        }

        @Override
        public void copyPath(EditorBuffer buffer) {
            MainController.this.copyPath(buffer);
        }

        @Override
        public void revealActiveBuffer() {
            MainController.this.revealActiveBuffer();
        }

        @Override
        public void openTerminalForActiveBuffer() {
            MainController.this.openTerminalForActiveBuffer();
        }

        @Override
        public void togglePin(Tab tab) {
            MainController.this.togglePin(tab);
        }

        @Override
        public void renameFile(EditorBuffer buffer, Tab tab) {
            MainController.this.renameFile(buffer, tab);
        }

        @Override
        public void onQuit() {
            MainController.this.onQuit();
        }

        @Override
        public void requestSave() {
            MainController.this.requestSave();
        }

        @Override
        public void nextBuffer() {
            MainController.this.nextBuffer();
        }

        @Override
        public void findShowOrNext() {
            MainController.this.findShowOrNext();
        }

        @Override
        public void findShowOrPrevious() {
            MainController.this.findShowOrPrevious();
        }

        @Override
        public void showReplace() {
            MainController.this.showReplace();
        }

        @Override
        public void updateBufferToolWindows() {
            MainController.this.updateBufferToolWindows();
        }

        @Override
        public void maybeOfferInstall(EditorBuffer buffer) {
            installPrompts.maybeOfferInstall(buffer);
        }

        @Override
        public void onPalette() {
            MainController.this.onPalette();
        }

        @Override
        public void onSettings() {
            MainController.this.onSettings();
        }

        @Override
        public void onAbout() {
            MainController.this.onAbout();
        }

        @Override
        public void showSplitToolWindowPalette() {
            MainController.this.showSplitToolWindowPalette();
        }

        @Override
        public void toggleFloatingToolWindow() {
            MainController.this.toggleFloatingToolWindow();
        }

        @Override
        public void toggleMaximizedToolWindow() {
            MainController.this.toggleMaximizedToolWindow();
        }

        @Override
        public void toggleToolStripe() {
            MainController.this.toggleToolStripe();
        }

        @Override
        public void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
            MainController.this.withMultiCaret(action);
        }

        @Override
        public void selectAllOccurrences() {
            MainController.this.selectAllOccurrences();
        }

        @Override
        public void selectAllFindMatches() {
            MainController.this.selectAllFindMatches();
        }

        @Override
        public void chooseInstallServer() {
            installPrompts.chooseInstallServer();
        }

        @Override
        public void toggleReadOnly() {
            MainController.this.toggleReadOnly();
        }

        @Override
        public void textZoom(int direction) {
            MainController.this.textZoom(direction);
        }

        @Override
        public void insertSnippetPicker() {
            MainController.this.insertSnippetPicker();
        }

        @Override
        public void editUserSnippets() {
            MainController.this.editUserSnippets();
        }

        @Override
        public void editProjectSettings() {
            MainController.this.editProjectSettings();
        }

        @Override
        public void showDebugLog() {
            MainController.this.showDebugLog();
        }

        @Override
        public void exportConfig() {
            MainController.this.exportConfig();
        }

        @Override
        public void cancel() {
            MainController.this.cancel();
        }

        @Override
        public SelectionPolicy selPolicy() {
            return MainController.this.selPolicy();
        }
    });

    private final NavigationCoordinator navigation = new NavigationCoordinator(new NavigationCoordinator.Host() {
        @Override
        public WindowSessionCoordinator sessions() {
            return sessions;
        }

        @Override
        public WindowChromeCoordinator chrome() {
            return chrome;
        }

        @Override
        public FileWorkflowCoordinator fileWorkflows() {
            return fileWorkflows;
        }

        @Override
        public EditorArea editorArea() {
            return editorArea;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public KeymapManager keymap() {
            return keymap;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public com.editora.snippet.SnippetManager snippets() {
            return snippets;
        }

        @Override
        public ProjectManager projects() {
            return projects;
        }

        @Override
        public ToolWindowManager toolWindows() {
            return toolWindows;
        }

        @Override
        public StructurePanel structurePanel() {
            return structurePanel;
        }

        @Override
        public RecentFiles recentFiles() {
            return recentFiles;
        }

        @Override
        public boolean projectsEnabled() {
            return MainController.this.projectsEnabled();
        }

        @Override
        public void navigateToLine(int line) {
            MainController.this.navigateToLine(line);
        }

        @Override
        public void activateAndFocusTab(Tab tab) {
            MainController.this.activateAndFocusTab(tab);
        }

        @Override
        public List<Tab> openTabsForSwitcher() {
            return MainController.this.openTabsForSwitcher();
        }

        @Override
        public String homeCollapsed(String full) {
            return MainController.this.homeCollapsed(full);
        }

        @Override
        public EditingCoordinator editing() {
            return editing;
        }

        @Override
        public GitCoordinator git() {
            return git;
        }

        @Override
        public IndexCoordinator indexCoordinator() {
            return indexCoordinator;
        }

        @Override
        public LspCoordinator lspCoordinator() {
            return lspCoordinator;
        }

        @Override
        public void openAndGoto(Path file, int line0, int col0) {
            MainController.this.openAndGoto(file, line0, col0);
        }

        @Override
        public java.util.Map<String, String> invertBindings() {
            return MainController.this.invertBindings();
        }

        @Override
        public void splitEditorGroup(Orientation orientation) {
            MainController.this.splitEditorGroup(orientation);
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public java.util.List<com.editora.editor.UndoHistory.Checkpoint> undoHistoryCheckpoints() {
            return MainController.this.undoHistoryCheckpoints();
        }

        @Override
        public void restoreUndoCheckpoint(com.editora.editor.UndoHistory.Checkpoint c) {
            MainController.this.restoreUndoCheckpoint(c);
        }

        @Override
        public CodeArea activeArea() {
            return MainController.this.activeArea();
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer) {
            return MainController.this.addBuffer(buffer);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer, boolean select) {
            return MainController.this.addBuffer(buffer, select);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
            return MainController.this.addBuffer(buffer, select, resolvePathSettings);
        }

        @Override
        public void openRecent(Path file) {
            MainController.this.openRecent(file);
        }

        @Override
        public void openPathPreview(Path file) {
            MainController.this.openPathPreview(file);
        }

        @Override
        public EditorBuffer bufferOf(Tab tab) {
            return MainController.this.bufferOf(tab);
        }

        @Override
        public Tab tabForPath(Path file) {
            return MainController.this.tabForPath(file);
        }

        @Override
        public void persistFolds(EditorBuffer buffer) {
            MainController.this.persistFolds(buffer);
        }
    });

    private final PreviewCoordinator previews = new PreviewCoordinator(new PreviewCoordinator.Host() {
        @Override
        public EditorArea editorArea() {
            return editorArea;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public SettingsWindow settingsWindow() {
            return settingsWindow;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public ToolWindowManager toolWindows() {
            return toolWindows;
        }

        @Override
        public MarkdownLintPanel markdownLintPanel() {
            return markdownLintPanel;
        }

        @Override
        public ToolWindow markdownLintToolWindow() {
            return markdownLintToolWindow;
        }

        @Override
        public EditingCoordinator editing() {
            return editing;
        }

        @Override
        public EditorSettingsCoordinator editorSettings() {
            return editorSettings;
        }

        @Override
        public MermaidCoordinator mermaid() {
            return mermaid;
        }

        @Override
        public ExportCoordinator exports() {
            return exports;
        }

        @Override
        public CsvCoordinator csvCoordinator() {
            return csvCoordinator;
        }

        @Override
        public HttpClientCoordinator httpClient() {
            return httpClient;
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public EditorBuffer bufferOf(Tab tab) {
            return MainController.this.bufferOf(tab);
        }

        @Override
        public void requestSave() {
            MainController.this.requestSave();
        }

        @Override
        public boolean appThemeDark() {
            return MainController.this.appThemeDark();
        }

        @Override
        public void promptText(
                String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
            MainController.this.promptText(title, label, initial, onAccept);
        }
    });

    private final GitWindowCoordinator gitWindows = new GitWindowCoordinator(new GitWindowCoordinator.Host() {
        @Override
        public FileWorkflowCoordinator fileWorkflows() {
            return fileWorkflows;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public StatusBar statusBar() {
            return statusBar;
        }

        @Override
        public ToolWindowManager toolWindows() {
            return toolWindows;
        }

        @Override
        public GitPanel gitPanel() {
            return gitPanel;
        }

        @Override
        public ToolWindow commitToolWindow() {
            return commitToolWindow;
        }

        @Override
        public GitLogPanel gitLogPanel() {
            return gitLogPanel;
        }

        @Override
        public GitLogPanel.Actions gitLogOps() {
            return gitLogOps;
        }

        @Override
        public ToolWindow gitLogToolWindow() {
            return gitLogToolWindow;
        }

        @Override
        public GitHubPanel githubPanel() {
            return githubPanel;
        }

        @Override
        public GitCoordinator git() {
            return git;
        }

        @Override
        public DiffCoordinator diffCoordinator() {
            return diffCoordinator;
        }

        @Override
        public GitHubCoordinator github() {
            return github;
        }

        @Override
        public EditorBuffer openBufferFor(Path target) {
            return MainController.this.openBufferFor(target);
        }

        @Override
        public void reloadAllFromDiskSilently() {
            MainController.this.reloadAllFromDiskSilently();
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public void promptText(
                String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
            MainController.this.promptText(title, label, initial, onAccept);
        }
    });

    private final WindowChromeCoordinator chrome = new WindowChromeCoordinator(new WindowChromeCoordinator.Host() {
        @Override
        public PseudoClass OPEN() {
            return OPEN;
        }

        @Override
        public BorderPane root() {
            return root;
        }

        @Override
        public EditorArea editorArea() {
            return editorArea;
        }

        @Override
        public MainMenuBar menuBar() {
            return menuBar;
        }

        @Override
        public ToolBar toolBar() {
            return toolBar;
        }

        @Override
        public HBox toolbarRow() {
            return toolbarRow;
        }

        @Override
        public Button newFromTemplateButton() {
            return newFromTemplateButton;
        }

        @Override
        public Button findInFilesButton() {
            return findInFilesButton;
        }

        @Override
        public Button splitVerticalButton() {
            return splitVerticalButton;
        }

        @Override
        public Button splitHorizontalButton() {
            return splitHorizontalButton;
        }

        @Override
        public Button simpleModeButton() {
            return simpleModeButton;
        }

        @Override
        public MenuButton recentButton() {
            return recentButton;
        }

        @Override
        public Button clearRecentButton() {
            return clearRecentButton;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public CommandPalette palette() {
            return palette;
        }

        @Override
        public StatusBar statusBar() {
            return statusBar;
        }

        @Override
        public FileBreadcrumb breadcrumb() {
            return breadcrumb;
        }

        @Override
        public SettingsWindow settingsWindow() {
            return settingsWindow;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public QuickOpen<Project> projectPicker() {
            return projectPicker;
        }

        @Override
        public ToolWindowManager toolWindows() {
            return toolWindows;
        }

        @Override
        public BookmarkCoordinator bookmarkCoordinator() {
            return bookmarkCoordinator;
        }

        @Override
        public Switcher switcher() {
            return switcher;
        }

        @Override
        public void openExternalUrl(String url) {
            MainController.this.openExternalUrl(url);
        }

        @Override
        public EditorSettingsCoordinator editorSettings() {
            return editorSettings;
        }

        @Override
        public RunConfigurationCoordinator runConfigurations() {
            return runConfigurations;
        }

        @Override
        public NavigationCoordinator navigation() {
            return navigation;
        }

        @Override
        public GitWindowCoordinator gitWindows() {
            return gitWindows;
        }

        @Override
        public List<BuildCoordinator> buildCoordinators() {
            return buildCoordinators;
        }

        @Override
        public SearchEverywherePopup.Ops searchEverywhereOps() {
            return searchEverywhereOps;
        }

        @Override
        public IndexCoordinator indexCoordinator() {
            return indexCoordinator;
        }

        @Override
        public NotesCoordinator notesCoordinator() {
            return notesCoordinator;
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public CodeArea activeArea() {
            return MainController.this.activeArea();
        }

        @Override
        public void requestSave() {
            MainController.this.requestSave();
        }

        @Override
        public void setZenMode(boolean on) {
            MainController.this.setZenMode(on);
        }

        @Override
        public void setExpertMode(boolean on) {
            MainController.this.setExpertMode(on);
        }
    });

    private final WindowMcpBridge mcpBridge = new WindowMcpBridge(new WindowMcpBridge.Host() {
        @Override
        public WindowSessionCoordinator sessions() {
            return sessions;
        }

        @Override
        public FileWorkflowCoordinator fileWorkflows() {
            return fileWorkflows;
        }

        @Override
        public EditorArea editorArea() {
            return editorArea;
        }

        @Override
        public CommandRegistry registry() {
            return registry;
        }

        @Override
        public ProjectManager projects() {
            return projects;
        }

        @Override
        public com.editora.lsp.LspManager lspManager() {
            return lspManager;
        }

        @Override
        public NavigationCoordinator navigation() {
            return navigation;
        }

        @Override
        public GitCoordinator git() {
            return git;
        }

        @Override
        public TodoCoordinator todoCoordinator() {
            return todoCoordinator;
        }

        @Override
        public SearchCoordinator searchCoordinator() {
            return searchCoordinator;
        }

        @Override
        public LspCoordinator lspCoordinator() {
            return lspCoordinator;
        }

        @Override
        public boolean lspEnabled() {
            return MainController.this.lspEnabled();
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public ImageViewerPane imagePaneOf(Tab tab) {
            return MainController.this.imagePaneOf(tab);
        }

        @Override
        public HexViewerPane hexPaneOf(Tab tab) {
            return MainController.this.hexPaneOf(tab);
        }

        @Override
        public PdfViewerPane pdfPaneOf(Tab tab) {
            return MainController.this.pdfPaneOf(tab);
        }

        @Override
        public EditorBuffer bufferOf(Tab tab) {
            return MainController.this.bufferOf(tab);
        }

        @Override
        public Path tabPath(Tab tab) {
            return MainController.this.tabPath(tab);
        }

        @Override
        public Path canonicalPath(Path p) {
            return MainController.this.canonicalPath(p);
        }
    });

    private final FileWorkflowCoordinator fileWorkflows =
            new FileWorkflowCoordinator(new FileWorkflowCoordinator.Host() {

                @Override
                public EditorArea editorArea() {
                    return editorArea;
                }

                @Override
                public Stage stage() {
                    return stage;
                }

                @Override
                public ConfigManager config() {
                    return config;
                }

                @Override
                public FileBreadcrumb breadcrumb() {
                    return breadcrumb;
                }

                @Override
                public ProjectPanel projectPanel() {
                    return projectPanel;
                }

                @Override
                public HistoryCoordinator historyCoordinator() {
                    return historyCoordinator;
                }

                @Override
                public RecentFiles recentFiles() {
                    return recentFiles;
                }

                @Override
                public void updateProjectFolderView() {
                    MainController.this.updateProjectFolderView();
                }

                @Override
                public EditorSettingsCoordinator editorSettings() {
                    return editorSettings;
                }

                @Override
                public PreviewCoordinator previews() {
                    return previews;
                }

                @Override
                public GitCoordinator git() {
                    return git;
                }

                @Override
                public HtmlPreviewCoordinator htmlPreview() {
                    return htmlPreview;
                }

                @Override
                public LogViewerCoordinator logViewer() {
                    return logViewer;
                }

                @Override
                public void refreshBuildTools() {
                    MainController.this.refreshBuildTools();
                }

                @Override
                public IndexCoordinator indexCoordinator() {
                    return indexCoordinator;
                }

                @Override
                public LspCoordinator lspCoordinator() {
                    return lspCoordinator;
                }

                @Override
                public boolean isLocalBuffer(EditorBuffer b) {
                    return MainController.this.isLocalBuffer(b);
                }

                @Override
                public void setStatus(String message) {
                    MainController.this.setStatus(message);
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return MainController.this.activeBuffer();
                }

                @Override
                public Tab addBuffer(EditorBuffer buffer) {
                    return MainController.this.addBuffer(buffer);
                }

                @Override
                public Tab addBuffer(EditorBuffer buffer, boolean select) {
                    return MainController.this.addBuffer(buffer, select);
                }

                @Override
                public Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
                    return MainController.this.addBuffer(buffer, select, resolvePathSettings);
                }

                @Override
                public Tab addContentTab(TabContent content, boolean select) {
                    return MainController.this.addContentTab(content, select);
                }

                @Override
                public void updateTabMeta(Tab tab, EditorBuffer buffer) {
                    MainController.this.updateTabMeta(tab, buffer);
                }

                @Override
                public void promoteTab(Tab tab) {
                    MainController.this.promoteTab(tab);
                }

                @Override
                public void finishAsyncOpen(Tab tab, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
                    MainController.this.finishAsyncOpen(tab, buffer, load);
                }

                @Override
                public void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error) {
                    MainController.this.failAsyncOpen(tab, buffer, file, error);
                }

                @Override
                public String autoSaveModeOf(String mode) {
                    return MainController.this.autoSaveModeOf(mode);
                }

                @Override
                public String autoSaveLabel(String mode) {
                    return MainController.this.autoSaveLabel(mode);
                }

                @Override
                public EditorBuffer bufferOf(Tab tab) {
                    return MainController.this.bufferOf(tab);
                }

                @Override
                public Tab tabFor(EditorBuffer buffer) {
                    return MainController.this.tabFor(buffer);
                }

                @Override
                public Tab tabForPath(Path file) {
                    return MainController.this.tabForPath(file);
                }

                @Override
                public Path tabPath(Tab tab) {
                    return MainController.this.tabPath(tab);
                }

                @Override
                public void requestSave() {
                    MainController.this.requestSave();
                }

                @Override
                public Tab tabForBuffer(EditorBuffer buffer) {
                    return MainController.this.tabForBuffer(buffer);
                }

                @Override
                public void promptText(
                        String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
                    MainController.this.promptText(title, label, initial, onAccept);
                }

                @Override
                public Path pathOf(java.io.File file) {
                    return MainController.this.pathOf(file);
                }
            });

    @FXML
    private void onSave() {
        fileWorkflows.onSave();
    }

    @FXML
    private void onSaveAs() {
        fileWorkflows.onSaveAs();
    }

    private final WindowSessionCoordinator sessions = new WindowSessionCoordinator(new WindowSessionCoordinator.Host() {

        @Override
        public TabPane tabPane() {
            return tabPane;
        }

        @Override
        public EditorArea editorArea() {
            return editorArea;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public ProjectPanel projectPanel() {
            return projectPanel;
        }

        @Override
        public ProjectManager projects() {
            return projects;
        }

        @Override
        public ToolWindowManager toolWindows() {
            return toolWindows;
        }

        @Override
        public BookmarkCoordinator bookmarkCoordinator() {
            return bookmarkCoordinator;
        }

        @Override
        public DebugCoordinator debugCoordinator() {
            return debugCoordinator;
        }

        @Override
        public Set<Tab> pinned() {
            return pinned;
        }

        @Override
        public void showWelcomeIfNoTabs() {
            MainController.this.showWelcomeIfNoTabs();
        }

        @Override
        public void refreshProjectPanelList() {
            MainController.this.refreshProjectPanelList();
        }

        @Override
        public boolean projectsEnabled() {
            return MainController.this.projectsEnabled();
        }

        @Override
        public void updateWindowTitle() {
            MainController.this.updateWindowTitle();
        }

        @Override
        public NavigationCoordinator navigation() {
            return navigation;
        }

        @Override
        public PreviewCoordinator previews() {
            return previews;
        }

        @Override
        public WindowChromeCoordinator chrome() {
            return chrome;
        }

        @Override
        public FileWorkflowCoordinator fileWorkflows() {
            return fileWorkflows;
        }

        @Override
        public DiffCoordinator diffCoordinator() {
            return diffCoordinator;
        }

        @Override
        public LspCoordinator lspCoordinator() {
            return lspCoordinator;
        }

        @Override
        public NotesCoordinator notesCoordinator() {
            return notesCoordinator;
        }

        @Override
        public EditorBuffer openBackgroundBuffer(Path target) {
            return MainController.this.openBackgroundBuffer(target);
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer) {
            return MainController.this.addBuffer(buffer);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer, boolean select) {
            return MainController.this.addBuffer(buffer, select);
        }

        @Override
        public Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
            return MainController.this.addBuffer(buffer, select, resolvePathSettings);
        }

        @Override
        public void updateTabMeta(Tab tab, EditorBuffer buffer) {
            MainController.this.updateTabMeta(tab, buffer);
        }

        @Override
        public void clearLoading(EditorBuffer buffer) {
            MainController.this.clearLoading(buffer);
        }

        @Override
        public void discardLoading(EditorBuffer buffer) {
            MainController.this.discardLoading(buffer);
        }

        @Override
        public EditorBuffer bufferOf(Tab tab) {
            return MainController.this.bufferOf(tab);
        }

        @Override
        public Tab tabForPath(Path file) {
            return MainController.this.tabForPath(file);
        }

        @Override
        public Path tabPath(Tab tab) {
            return MainController.this.tabPath(tab);
        }

        @Override
        public Path windowProjectRoot() {
            return MainController.this.windowProjectRoot();
        }

        @Override
        public void restoreFolds(EditorBuffer buffer) {
            MainController.this.restoreFolds(buffer);
        }

        @Override
        public Tab tabForBuffer(EditorBuffer buffer) {
            return MainController.this.tabForBuffer(buffer);
        }

        @Override
        public void restoreReadOnly(EditorBuffer buffer) {
            MainController.this.restoreReadOnly(buffer);
        }

        @Override
        public void refreshStatusBar() {
            statusBar.refresh();
        }
    });

    private final TestNavigationCoordinator testNavigation =
            new TestNavigationCoordinator(new TestNavigationCoordinator.Host() {

                @Override
                public EditorArea editorArea() {
                    return editorArea;
                }

                @Override
                public ConfigManager config() {
                    return config;
                }

                @Override
                public ProjectManager projects() {
                    return projects;
                }

                @Override
                public Project windowProject() {
                    return windowProject;
                }

                @Override
                public ToolWindowManager toolWindows() {
                    return toolWindows;
                }

                @Override
                public com.editora.lsp.LspManager lspManager() {
                    return lspManager;
                }

                @Override
                public ToolWindow testResultsToolWindow() {
                    return testResultsToolWindow;
                }

                @Override
                public WindowChromeCoordinator chrome() {
                    return chrome;
                }

                @Override
                public FileWorkflowCoordinator fileWorkflows() {
                    return fileWorkflows;
                }

                @Override
                public WindowSessionCoordinator sessions() {
                    return sessions;
                }

                @Override
                public CoordinatorHost coordinatorHost() {
                    return coordinatorHost;
                }

                @Override
                public List<BuildCoordinator> buildCoordinators() {
                    return buildCoordinators;
                }

                @Override
                public RunCoordinator runCoordinator() {
                    return runCoordinator;
                }

                @Override
                public LspCoordinator lspCoordinator() {
                    return lspCoordinator;
                }

                @Override
                public boolean isLocalBuffer(EditorBuffer b) {
                    return MainController.this.isLocalBuffer(b);
                }

                @Override
                public boolean lspEnabled() {
                    return MainController.this.lspEnabled();
                }

                @Override
                public void openAndGoto(Path file, int line0, int col0) {
                    MainController.this.openAndGoto(file, line0, col0);
                }

                @Override
                public void setStatus(String message) {
                    MainController.this.setStatus(message);
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return MainController.this.activeBuffer();
                }

                @Override
                public EditorBuffer bufferOf(Tab tab) {
                    return MainController.this.bufferOf(tab);
                }

                @Override
                public Tab tabForPath(Path file) {
                    return MainController.this.tabForPath(file);
                }
            });

    private final InstallPromptCoordinator installPrompts =
            new InstallPromptCoordinator(new InstallPromptCoordinator.Host() {
                @Override
                public Stage stage() {
                    return stage;
                }

                @Override
                public ConfigManager config() {
                    return config;
                }

                @Override
                public OverlayHost overlayHost() {
                    return overlayHost;
                }

                @Override
                public InstallCoordinator installCoordinator() {
                    return installCoordinator;
                }

                @Override
                public WindowChromeCoordinator chrome() {
                    return chrome;
                }

                @Override
                public MermaidCoordinator mermaid() {
                    return mermaid;
                }

                @Override
                public LspCoordinator lspCoordinator() {
                    return lspCoordinator;
                }

                @Override
                public boolean isLocalBuffer(EditorBuffer b) {
                    return MainController.this.isLocalBuffer(b);
                }

                @Override
                public boolean lspEnabled() {
                    return MainController.this.lspEnabled();
                }
            });

    private final CoordinatorHost coordinatorHost = new Services();

    /** Implements {@link CoordinatorHost} by delegating to this controller's private helpers. */
    private final class Services implements CoordinatorHost {
        @Override
        public long fileSize(Path file) {
            return FileWorkflowCoordinator.fileSize(file);
        }

        @Override
        public Settings settings() {
            return config.getSettings();
        }

        @Override
        public boolean appThemeDark() {
            return MainController.this.appThemeDark();
        }

        @Override
        public void forEachBuffer(java.util.function.Consumer<EditorBuffer> action) {
            for (Tab tab : editorArea.tabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b != null) {
                    action.accept(b);
                }
            }
        }

        @Override
        public EditorBuffer activeBuffer() {
            return MainController.this.activeBuffer();
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer buffer) {
            return MainController.this.isLocalBuffer(buffer);
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public void setError(String message) {
            MainController.this.setError(message);
        }

        @Override
        public AutoCloseable startBackgroundTask(String label) {
            BackgroundTasks.Handle h = backgroundTasks.start(label);
            return h::done;
        }

        @Override
        public String bufferBaseName(EditorBuffer buffer) {
            return ExportCoordinator.bufferBaseName(buffer);
        }

        @Override
        public void requestSave() {
            MainController.this.requestSave();
        }

        @Override
        public void save() {
            config.save();
        }

        @Override
        public void syncSettingsWindow() {
            if (settingsWindow != null) {
                settingsWindow.syncAll();
            }
        }

        @Override
        public void openExternalUrl(String url) {
            MainController.this.openExternalUrl(url);
        }

        @Override
        public void promptText(
                String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
            MainController.this.promptText(title, label, initial, onAccept);
        }

        @Override
        public boolean isLspManaged(java.nio.file.Path file) {
            return lspEnabled() && file != null && lspManager.isManaged(file);
        }

        @Override
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public javafx.stage.Window window() {
            return stage;
        }

        @Override
        public void applyAutocomplete() {
            editorSettings.applyAutocomplete();
        }

        @Override
        public void ensurePreviewControls(EditorBuffer buffer) {
            previews.ensurePreviewControls(buffer);
        }

        @Override
        public void restoreMarkdownMode(EditorBuffer buffer) {
            previews.restoreMarkdownMode(buffer);
        }

        @Override
        public boolean simpleModeActive() {
            return chrome.simpleModeActive();
        }
    }

    // --- Git (native-CLI integration; off-thread via GitService) -------------------------------------

    /** The stateful core of the Git integration (GitService + repo state + the status/gutter state
     *  machine); see {@link GitCoordinator}. Operations (commit/branch/log/blame/diff) stay below and
     *  reach in via {@code git.service()}/{@code git.repoRoot()}. */
    private final GitCoordinator git = new GitCoordinator(coordinatorHost, new GitCoordinator.WindowOps() {
        @Override
        public void setStatusBarGitEnabled(boolean enabled) {
            statusBar.setGitEnabled(enabled);
        }

        @Override
        public void setStatusBarBranch(String branch, int ahead, int behind) {
            statusBar.setGitBranch(branch, ahead, behind);
        }

        @Override
        public void setCommitWindowAvailable(boolean available) {
            // Also require an open buffer: these act on the active file/tab, so they hide on Welcome
            // (and any non-buffer tab) even inside a repo.
            toolWindows.setAvailable(commitToolWindow, available && activeBuffer() != null);
            // Git's answer to "are we in a repo" has just landed, and it arrives asynchronously well after
            // the window (and its menu) were built — this is the signal that ungreys the VCS menu.
            refreshMenuEnablement();
        }

        @Override
        public void setGitLogWindowAvailable(boolean available) {
            // Entering or leaving a repo also decides whether the shared Output console is reachable — Git
            // writes its command transcripts there. Hooked here because this runs on every applyGitState
            // (tab switch / focus / save / mutation), which is exactly when the repo context can change.
            refreshBuildOutputAvailability();
            toolWindows.setAvailable(gitLogToolWindow, available && activeBuffer() != null);
        }

        @Override
        public void setGitPanelStatus(com.editora.git.GitStatus status) {
            gitPanel.setStatus(status);
        }

        @Override
        public void setProjectGitStatus(java.util.Map<java.nio.file.Path, com.editora.git.GitFileStatus> byPath) {
            if (projectPanel != null) {
                projectPanel.setGitStatus(byPath);
            }
        }

        @Override
        public void refreshOpenDiffs() {
            diffCoordinator.refreshOpenDiffs();
        }

        @Override
        public Path projectRoot() {
            Project active = projects == null ? null : projects.active();
            return active == null ? null : Path.of(active.root());
        }

        @Override
        public void reloadAllFromDiskSilently() {
            MainController.this.reloadAllFromDiskSilently();
        }

        @Override
        public void clearCommitMessage() {
            gitPanel.clearMessage();
        }

        @Override
        public void openCommitWindow() {
            toolWindows.open(commitToolWindow);
        }

        @Override
        public void focusCommitMessage() {
            Platform.runLater(gitPanel::focusCommitMessage);
        }

        @Override
        public com.editora.command.KeymapManager keymap() {
            return keymap;
        }

        @Override
        public void syncBlameCheck() {
            settingsWindow.syncGitBlameCheck();
        }

        @Override
        public void openCommitFileDiff(String hash, String repoRel) {
            diffCoordinator.diffCommitFile(hash, repoRel);
        }

        @Override
        public void checkExternalChanges() {
            fileWorkflows.checkExternalChanges();
        }

        @Override
        public void openPath(Path file) {
            fileWorkflows.openPath(file);
        }
    });

    /** The diff + merge-conflict viewer (open/refresh diffs, apply-change, compare entry points, patch
     *  export, merge resolution); see {@link DiffCoordinator}. Git-backed diffs reach the repo via {@code git}. */
    private final DiffCoordinator diffCoordinator =
            new DiffCoordinator(coordinatorHost, git, new DiffCoordinator.Ops() {
                @Override
                public void addDiffTab(TabContent pane) {
                    if (pane instanceof DiffViewerPane diffPane) {
                        diffPane.setExitDiffUiAction(chrome.diffUiActive() ? chrome::exitDiffUiMode : null);
                    } else if (pane instanceof DirectoryReviewPane reviewPane) {
                        reviewPane.setExitDiffUiAction(chrome.diffUiActive() ? chrome::exitDiffUiMode : null);
                    }
                    addContentTab(pane, true);
                }

                @Override
                public void prepareDiffPane(DiffViewerPane pane) {
                    pane.setExitDiffUiAction(chrome.diffUiActive() ? chrome::exitDiffUiMode : null);
                }

                @Override
                public EditorBuffer openBufferFor(Path target) {
                    return MainController.this.openBufferFor(target);
                }

                @Override
                public EditorBuffer openBackgroundBuffer(Path target) {
                    return MainController.this.openBackgroundBuffer(target);
                }

                @Override
                public boolean saveBuffer(EditorBuffer buffer) {
                    return fileWorkflows.saveSynchronously(buffer);
                }

                @Override
                public java.util.List<DiffViewerPane> openDiffPanes() {
                    java.util.List<DiffViewerPane> out = new java.util.ArrayList<>();
                    for (Tab tab : editorArea.tabs()) {
                        if (tab.getUserData() instanceof DiffViewerPane dp) {
                            out.add(dp);
                        } else if (tab.getUserData() instanceof PatchReviewPane review) {
                            out.addAll(review.panes());
                        } else if (tab.getUserData() instanceof DirectoryReviewPane review) {
                            out.addAll(review.panes());
                        }
                    }
                    return out;
                }

                @Override
                public DiffViewerPane activeDiffPane() {
                    Tab t = editorArea.selectedTab();
                    if (t == null) {
                        return null;
                    }
                    if (t.getUserData() instanceof DiffViewerPane dp) {
                        return dp;
                    }
                    if (t.getUserData() instanceof PatchReviewPane review) {
                        return review.activePane();
                    }
                    return t.getUserData() instanceof DirectoryReviewPane review ? review.activePane() : null;
                }

                @Override
                public Path finderStartDir() {
                    return navigation.finderStartDir();
                }

                @Override
                public String editorConfigCharset(Path file) {
                    return editorConfigCharsetFor(file);
                }

                @Override
                public void openAt(Path file, int line) {
                    fileWorkflows.openPath(file);
                    Platform.runLater(() -> navigateToLine(Math.max(0, line - 1)));
                }
            });

    // --- GitHub (native `gh` CLI: PR checkout/diff/create + open-on-GitHub) ---------------------------

    /** The GitHub integration (gh-backed); see {@link GitHubCoordinator}. Rides on {@code git} for the repo
     *  root and reuses {@code diffCoordinator} for PR diff review. */
    private final GitHubCoordinator github =
            new GitHubCoordinator(coordinatorHost, git, diffCoordinator, new GitHubCoordinator.WindowOps() {
                @Override
                public void reloadAllFromDiskSilently() {
                    MainController.this.reloadAllFromDiskSilently();
                }

                @Override
                public void checkExternalChanges() {
                    fileWorkflows.checkExternalChanges();
                }

                @Override
                public com.editora.command.KeymapManager keymap() {
                    return keymap;
                }

                @Override
                public void setGitHubWindowAvailable(boolean available) {
                    // Repo-scoped (not buffer-scoped): the GitHub window lists the repo's PRs/issues, so it stays
                    // available across tab switches (incl. the Welcome tab) as long as the project is a GitHub
                    // repo with open activity — unlike the Commit/Git Log windows, which act on the active file.
                    if (githubToolWindow != null) {
                        toolWindows.setAvailable(githubToolWindow, available);
                    }
                }

                @Override
                public void enableGitHubWindowByDefault() {
                    if (githubToolWindow != null) {
                        toolWindows.setVisibleIfUnset(githubToolWindow, true);
                    }
                }

                @Override
                public void toggleGitHubWindow() {
                    if (githubToolWindow != null) {
                        toolWindows.toggle(githubToolWindow);
                    }
                }

                @Override
                public void setStatusBarChecks(com.editora.github.ChecksParser.ChecksSummary summary) {
                    statusBar.setGitHubChecks(summary);
                }

                @Override
                public void addReviewTab(com.editora.editor.TabContent pane) {
                    if (pane instanceof PrReviewPane p) {
                        p.setFontScale(config.getSettings().getFontZoom());
                    }
                    addContentTab(pane, true);
                }

                @Override
                public boolean selectTabOf(com.editora.editor.TabContent pane) {
                    for (Tab t : editorArea.tabs()) {
                        if (t.getUserData() == pane) {
                            editorArea.select(t);
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public void reloadGitHubPanel() {
                    gitWindows.reloadGithubPanel();
                }

                // The Output console is owner-routed, so passing the GitHub coordinator as the owner
                // gives CI logs their own persistent "CI" tab beside the Maven/npm build tabs.
                @Override
                public void ciLogStarted(String header, Runnable onStop) {
                    refreshBuildOutputAvailability(); // a repo with no build tool still gets the CI tab
                    toolWindows.open(buildOutputToolWindow);
                    buildOutputPanel.started(
                            github, tr("github.ci.tab"), header, com.editora.build.OutputStyle.ci(), onStop);
                }

                @Override
                public void ciLogAppend(String line) {
                    buildOutputPanel.appendOutput(github, line, false);
                }

                @Override
                public void ciLogFinished() {
                    buildOutputPanel.finished(github, 0);
                }

                @Override
                public void ciLogFailed(String message) {
                    buildOutputPanel.failed(github, message);
                }
            });

    // --- Mermaid (mmdc render/export + maid lint) ----------------------------------------------------

    /** The Mermaid feature (diagram render/export, live lint, autocomplete gating); see {@link MermaidCoordinator}. */
    private final MermaidCoordinator mermaid = new MermaidCoordinator(coordinatorHost);

    /** The diagram-as-code feature (Graphviz DOT + PlantUML preview/export); see {@link DiagramCoordinator}. */
    private final DiagramCoordinator diagram = new DiagramCoordinator(coordinatorHost);

    /** The Typst document feature (multi-page rendered preview + export); see {@link TypstCoordinator}. */
    private final TypstCoordinator typst = new TypstCoordinator(coordinatorHost, this::resolveTypstRoot);

    /** Owns all document export/print services and their window lifecycle. */
    private final ExportCoordinator exports =
            new ExportCoordinator(coordinatorHost, mermaid, diagram, typst, fileWorkflows::openPath);

    // --- HTML Live Preview (serve via a loopback HttpServer + open in a detected browser) ---------

    /** The HTML Live Preview feature; see {@link HtmlPreviewCoordinator}. */
    private final HtmlPreviewCoordinator htmlPreview = new HtmlPreviewCoordinator(coordinatorHost);

    // --- Log viewer ----------------------------------------------------------------------------------

    /** The server-log-viewer feature (level highlighting, tail-follow, filtering); see {@link LogViewerCoordinator}. */
    private final LogViewerCoordinator logViewer = new LogViewerCoordinator(coordinatorHost);

    /** New Maven Project wizard; owns the archetype picker/form and the archetype:generate run. */
    private final MavenProjectCoordinator mavenProjectCoordinator = new MavenProjectCoordinator(
            coordinatorHost,
            new MavenProjectCoordinator.Ops() {
                @Override
                public java.nio.file.Path defaultParentDir() {
                    return templateActions.defaultNewDir();
                }

                @Override
                public boolean replaceOpenBuffer(java.nio.file.Path file, String text) {
                    EditorBuffer buffer = bufferOf(tabForPath(file));
                    if (buffer == null) {
                        return false;
                    }
                    // One replaceText, so the whole update is a single undo step rather than one per
                    // artifact — and the buffer goes dirty, so it is the user who decides to save it.
                    buffer.getArea().replaceText(text);
                    return true;
                }

                @Override
                public void openProject(
                        java.nio.file.Path root, String name, com.editora.maven.GeneratedProject.MainClass main) {
                    if (!projectsEnabled()) {
                        return; // still generated on disk; just not registered as a project
                    }
                    Project project = projects.createOrGet(name, root);
                    projects.save();
                    seedNewProjectSession(project, root, name, main);
                    if (windowManager != null) {
                        windowManager.openOrFocus(project);
                    }
                }

                @Override
                public void openPath(java.nio.file.Path file) {
                    fileWorkflows.openPath(file);
                }

                @Override
                public com.editora.command.KeymapManager keymap() {
                    return keymap;
                }

                @Override
                public boolean confirmArchetype(com.editora.maven.MavenArchetype archetype) {
                    return confirmArchetypeGenerate(archetype);
                }

                @Override
                public void refreshProjectTree() {
                    if (projectPanel != null) {
                        projectPanel.refreshTree();
                    }
                }
            },
            buildOutputPanel);

    /** External Tools feature; owns the service/console panel/commands (the tool window stays here). */
    private final ExternalToolCoordinator externalToolCoordinator =
            new ExternalToolCoordinator(coordinatorHost, new ExternalToolCoordinator.Ops() {
                @Override
                public java.nio.file.Path projectRoot() {
                    return windowProject != null ? java.nio.file.Path.of(windowProject.root()) : null;
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

    /** Build tools (Maven/npm/…): one coordinator per tool — a toolbar icon + actions popup parsed from the
     *  active project's marker file, streaming tasks to a per-tool console (see {@link BuildCoordinator}). */
    private final List<BuildCoordinator> buildCoordinators = createBuildCoordinators();

    private List<BuildCoordinator> createBuildCoordinators() {
        List<BuildCoordinator> list = new ArrayList<>();
        for (BuildTool tool : BuildTool.enabled()) {
            list.add(new BuildCoordinator(
                    tool,
                    coordinatorHost,
                    new BuildCoordinator.Ops() {
                        @Override
                        public java.nio.file.Path projectRoot() {
                            return windowProject != null ? java.nio.file.Path.of(windowProject.root()) : null;
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

                        @Override
                        public boolean isTrusted(java.nio.file.Path root) {
                            return config.getTrustStore().isTrusted(root);
                        }

                        @Override
                        public boolean confirmTrust(java.nio.file.Path root, java.nio.file.Path wrapper) {
                            return MainController.this.confirmTrustFolder(root, wrapper);
                        }

                        @Override
                        public void trust(java.nio.file.Path root) {
                            config.getTrustStore().trust(root);
                            config.saveTrust(); // durable: a security decision must survive a crash
                        }
                    },
                    buildOutputPanel));
        }
        return list;
    }

    /** Whether any enabled build tool currently has a detected marker file (drives the shared console stripe). */
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
        git.service()
                .setCommandLog(
                        entry -> Platform.runLater(() -> logCliCommand(gitConsoleOwner, tr("console.tab.git"), entry)));
        github.service()
                .setCommandLog(entry ->
                        Platform.runLater(() -> logCliCommand(ghConsoleOwner, tr("console.tab.github"), entry)));
    }

    private void logCliCommand(Object owner, String tabTitle, com.editora.process.CommandLog.Entry entry) {
        buildOutputPanel.logCommand(owner, tabTitle, entry);
        // The console's stripe is gated on a build tool being detected; a repo with no build file still has
        // git, so the first logged command is what makes the window reachable there.
        refreshBuildOutputAvailability();
    }

    /**
     * The shared Output console is available whenever something can write into it: a detected build tool, a
     * tab already written (a finished build, a CI log), or <b>a Git repository</b> — Git and GitHub log every
     * command they run into their own transcript tabs there, and in a repo with no build file the stripe
     * button is the only way to reach them.
     */
    private void refreshBuildOutputAvailability() {
        if (buildOutputToolWindow != null) {
            toolWindows.setAvailable(
                    buildOutputToolWindow, anyBuildDetected() || inGitRepo() || buildOutputPanel.hasTabs());
        }
    }

    /** Whether the active context is inside a Git repository (and Git is switched on). */
    private boolean inGitRepo() {
        return git != null && git.isAvailable();
    }

    private boolean anyBuildDetected() {
        for (BuildCoordinator c : buildCoordinators) {
            if (c.isEnabled() && c.isDetected()) {
                return true;
            }
        }
        return false;
    }

    /** The coordinator for a specific build tool (for the palette gate / Settings status reads). */
    private BuildCoordinator buildCoordinator(BuildTool tool) {
        for (BuildCoordinator c : buildCoordinators) {
            if (c.tool() == tool) {
                return c;
            }
        }
        return null;
    }

    /** Re-detects the marker file for every build tool (Maven/npm/…) for the active context. */
    private void refreshBuildTools() {
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
    private final SearchEverywherePopup.Ops searchEverywhereOps = new SearchEverywherePopup.Ops() {
        @Override
        public java.util.List<com.editora.search.SearchEverywhere.Item> commands(String query) {
            java.util.List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
            var gates = paletteGates();
            var context = paletteContext();
            // Hoisted: inverting the keymap allocates a map of every binding, so doing it per matching
            // command turned a one-character query into hundreds of rebuilds of the same map.
            java.util.Map<String, String> chords = invertBindings();
            boolean all = query == null || query.isBlank();
            for (Command c : CommandPalette.orderedMatches(registry.all(), query)) {
                // A command whose feature is off is listed, grayed, with an explanation — the same choice
                // the palette makes (#532). Hiding it means the user never learns it exists, which matters
                // most here precisely because this picker can stand in for the palette.
                boolean enabled = Chrome.paletteEnabled(c.id(), gates, context);
                com.editora.search.FuzzyMatch.Match m = all ? null : com.editora.search.FuzzyMatch.of(c.title(), query);
                if (all || m != null) {
                    out.add(new com.editora.search.SearchEverywhere.Item(
                            com.editora.search.SearchEverywhere.Kind.COMMAND,
                            c.title(),
                            chords.getOrDefault(c.id(), ""),
                            c.description(),
                            all ? 0 : m.score(),
                            enabled,
                            c));
                }
            }
            return out;
        }

        @Override
        public java.util.List<com.editora.search.SearchEverywhere.Item> files(String query) {
            java.util.List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
            for (IndexCoordinator.FileHit hit : indexCoordinator.searchFiles(query, 40)) {
                String name = hit.file().getFileName().toString();
                String parent = hit.relativePath().contains("/")
                        ? hit.relativePath().substring(0, hit.relativePath().lastIndexOf('/'))
                        : "";
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.FILE, name, parent, hit.score(), hit.file()));
            }
            return out;
        }

        @Override
        public java.util.List<com.editora.search.SearchEverywhere.Item> symbols(String query) {
            java.util.List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
            for (com.editora.index.SymbolIndex.Hit hit : indexCoordinator.searchSymbols(query, 40)) {
                String container = hit.symbol().container();
                String where = hit.file().getFileName() + ":" + (hit.symbol().line() + 1);
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.SYMBOL,
                        hit.symbol().name(),
                        container.isEmpty() ? where : container + " — " + where,
                        hit.score(),
                        hit));
            }
            return out;
        }

        @Override
        public void ensureIndex(Runnable then) {
            indexCoordinator.ensureBuilt(then);
        }

        @Override
        public String disabledReason(com.editora.search.SearchEverywhere.Item item) {
            // Only a command can be listed-but-inert; a file or symbol row is always actionable.
            return item.payload() instanceof Command c ? disabledCommandReason(c.id()) : null;
        }

        @Override
        public void openDocs(com.editora.search.SearchEverywhere.Item item) {
            if (item.payload() instanceof Command c) {
                openExternalUrl(CommandPalette.docsUrl(c.id()));
            } else {
                setStatus(tr("status.searchEverywhere.noDocs"));
            }
        }

        @Override
        public void choose(com.editora.search.SearchEverywhere.Item item) {
            switch (item.payload()) {
                case Command c -> registry.run(c.id());
                case java.nio.file.Path file -> fileWorkflows.openPath(file);
                case com.editora.index.SymbolIndex.Hit hit ->
                    openAndGoto(hit.file(), hit.symbol().line(), hit.symbol().column());
                default -> {
                    // Unreachable: every Item this window builds carries one of the three above.
                }
            }
        }
    };

    private final IndexCoordinator indexCoordinator = new IndexCoordinator(coordinatorHost, new IndexCoordinator.Ops() {
        @Override
        public java.nio.file.Path projectRoot() {
            return (windowProject != null && projectsEnabled()) ? java.nio.file.Path.of(windowProject.root()) : null;
        }

        @Override
        public void openAndGoto(java.nio.file.Path file, int line, int column) {
            MainController.this.openAndGoto(file, line, column);
        }

        @Override
        public boolean respectGitignore() {
            return config.getSettings().isSearchRespectGitignore();
        }
    });

    private final TodoCoordinator todoCoordinator = new TodoCoordinator(coordinatorHost, new TodoCoordinator.Ops() {
        @Override
        public java.nio.file.Path projectRoot() {
            return (windowProject != null && projectsEnabled()) ? java.nio.file.Path.of(windowProject.root()) : null;
        }

        @Override
        public void openMatch(java.nio.file.Path file, int line, int col) {
            fileWorkflows.openPath(file);
            Platform.runLater(() -> sessions.gotoInFile(file, line, col));
        }

        @Override
        public boolean isToolWindowOpen() {
            return todoToolWindow != null && toolWindows.isOpen(todoToolWindow);
        }

        @Override
        public void toggleToolWindow() {
            toolWindows.toggle(todoToolWindow);
        }

        @Override
        public String homeCollapsed(String absolutePath) {
            return MainController.this.homeCollapsed(absolutePath);
        }

        @Override
        public void applyLineEdit(
                java.nio.file.Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
            applyTodoLineEdit(file, line, expectedLine, newLine, afterApply);
        }
    });

    /**
     * Rewrites a single source line for a TODO tool-window edit (set priority / mark done / edit description):
     * opens the file so the change is visible, verifies the target line still reads {@code expectedLine}, then
     * replaces that paragraph with {@code newLine} as one undoable edit (marks the buffer dirty). A no-op with a
     * status when the line moved/changed since the scan (the panel re-scans via {@code afterApply}).
     */
    private void applyTodoLineEdit(
            java.nio.file.Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
        fileWorkflows.openPath(file);
        Platform.runLater(() -> {
            EditorBuffer b = activeBuffer();
            if (b == null || b.getPath() == null || !canonicalPath(b.getPath()).equals(canonicalPath(file))) {
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
            int idx = line - 1;
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
    private final CsvCoordinator csvCoordinator = new CsvCoordinator(coordinatorHost, new CsvCoordinator.Ops() {
        @Override
        public void jumpTo(int line, int col) {
            EditorBuffer b = activeBuffer();
            if (b == null) {
                return;
            }
            var area = b.getArea();
            int paragraphs = area.getParagraphs().size();
            int p = Math.max(0, Math.min(line, paragraphs - 1));
            int c = Math.max(0, Math.min(col, area.getParagraphLength(p)));
            area.moveTo(p, c);
            area.requestFollowCaret();
            area.requestFocus();
        }

        @Override
        public void exportPdf(String csvText, String baseName) {
            exports.csvExportPdf(csvText, baseName);
        }

        @Override
        public void printCsv(String csvText) {
            exports.csvPrint(csvText);
        }

        @Override
        public void exportExcel(java.util.List<java.util.List<String>> rows, boolean hasHeader, String baseName) {
            exports.csvExportSpreadsheet(rows, hasHeader, baseName, true);
        }

        @Override
        public void exportOds(java.util.List<java.util.List<String>> rows, boolean hasHeader, String baseName) {
            exports.csvExportSpreadsheet(rows, hasHeader, baseName, false);
        }
    });

    /** Find-in-Files feature; owns the search service/panel/backend (the tool window + commands stay here). */
    private final SearchCoordinator searchCoordinator = new SearchCoordinator(
            coordinatorHost,
            SearchCoordinator.ops(
                    new SearchCoordinator.Navigation(
                            () -> (windowProject != null && projectsEnabled()) ? Path.of(windowProject.root()) : null,
                            this::openSearchMatch,
                            () -> searchToolWindow != null && toolWindows.isOpen(searchToolWindow),
                            () -> toolWindows.open(searchToolWindow, true),
                            () -> toolWindows.close(searchToolWindow)),
                    new SearchCoordinator.ReplaceSupport(
                            file -> bufferOf(tabForPath(file)),
                            fileWorkflows.loadingBuffers::contains,
                            (file, content, completion) ->
                                    historyCoordinator.recordDurably(file, content, "replace-in-files", completion),
                            file -> config.shared().documentWrites().begin(file)),
                    new SearchCoordinator.Persistence(
                            query -> {
                                if (searchHistory != null) {
                                    searchHistory.add(query);
                                }
                            },
                            () -> searchHistory != null
                                    ? searchHistory.getList()
                                    : javafx.collections.FXCollections.observableArrayList(),
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

    /** Run-a-file feature; owns the run service/console panel (the tool window + commands stay here). */
    private final RunCoordinator runCoordinator = new RunCoordinator(coordinatorHost, new RunCoordinator.Ops() {
        @Override
        public void openToolWindow() {
            toolWindows.open(runToolWindow);
        }

        @Override
        public void onRunStateChanged() {
            runConfigurations.updateRunConfigButtons();
        }

        @Override
        public void editConfiguration(String name) {
            runConfigurations.runConfigEditor.accept(name);
        }

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return fileWorkflows.saveSynchronously(buffer);
        }

        @Override
        public String programArgs(java.nio.file.Path path) {
            return programArgsFor(path);
        }

        @Override
        public void setProgramArgs(java.nio.file.Path path, String args) {
            config.getWorkspaceState().getProgramArgs().put(path.toString(), args);
            config.save();
        }

        @Override
        public void openLink(com.editora.run.StackTraceLinks.Link link) {
            testNavigation.openRunLink(link);
        }

        @Override
        public void openUrl(String url) {
            openExternalUrl(url);
        }

        @Override
        public java.nio.file.Path javaProjectRoot(java.nio.file.Path file) {
            return JavaProjectRoot.find(file);
        }

        @Override
        public java.nio.file.Path projectRoot() {
            return windowProjectRoot();
        }

        @Override
        public boolean javaLaunchAvailable() {
            // Resolving a project main class + classpath rides jdtls's java-debug bundle (the same gate as
            // Java debugging). PR4 adds a build-tool fallback so Run works without it.
            return debugCoordinator.debugEffectiveFor("java");
        }

        @Override
        public List<com.editora.config.RunConfiguration> runConfigurations() {
            return List.copyOf(config.getWorkspaceState().getRunConfigurations());
        }

        @Override
        public String selectedRunConfigName() {
            return config.getWorkspaceState().getSelectedRunConfig();
        }

        @Override
        public void resolveJavaMainClasses(
                java.nio.file.Path routingFile, java.util.function.Consumer<List<com.editora.run.JavaMainClass>> cb) {
            // The routing file may be a background tab whose server start was deferred; open it first or
            // jdtls has never seen it and answers "no language server for file".
            lspCoordinator.ensureManaged(routingFile);
            dapManager.resolveMainClasses(
                    routingFile,
                    opts -> cb.accept(opts.stream()
                            .map(o -> new com.editora.run.JavaMainClass(o.mainClass(), o.projectName(), o.filePath()))
                            .toList()));
        }

        @Override
        public void resolveJavaLaunch(
                java.nio.file.Path routingFile,
                com.editora.run.JavaMainClass mc,
                java.util.function.Consumer<com.editora.run.JavaLaunchInfo> cb) {
            lspCoordinator.ensureManaged(routingFile); // see resolveJavaMainClasses
            dapManager.resolveLaunch(
                    routingFile,
                    new com.editora.dap.DapManager.MainClassOption(mc.fqn(), mc.projectName(), mc.filePath()),
                    r -> cb.accept(new com.editora.run.JavaLaunchInfo(
                            r.javaExec(), r.modulePaths(), r.classPaths(), r.error())));
        }

        @Override
        public boolean mavenProjectAt(java.nio.file.Path root) {
            return root != null && java.nio.file.Files.isRegularFile(root.resolve("pom.xml"));
        }

        @Override
        public boolean gradleProjectAt(java.nio.file.Path root) {
            if (root == null) {
                return false;
            }
            for (String m : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
                if (java.nio.file.Files.isRegularFile(root.resolve(m))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void resolveMavenClasspath(java.nio.file.Path root, java.util.function.Consumer<List<String>> cb) {
            Thread t = new Thread(
                    () -> {
                        List<String> cp = null;
                        try {
                            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("editora-cp", ".txt");
                            try {
                                // Multi-module: run from the reactor root with -pl <module> -am so the sibling
                                // modules the target depends on are built and included; else run in the module.
                                java.nio.file.Path reactor = com.editora.maven.MavenReactor.reactorRoot(
                                        root, d -> java.nio.file.Files.isRegularFile(d.resolve("pom.xml")));
                                boolean multi = reactor != null && !reactor.equals(root);
                                java.nio.file.Path cwd = multi ? reactor : root;
                                List<String> argv = multi
                                        ? com.editora.maven.MavenClasspath.reactorArgv(
                                                tmp, com.editora.maven.MavenReactor.moduleSelector(reactor, root))
                                        : com.editora.maven.MavenClasspath.argv(tmp);
                                com.editora.process.ProcessRunner.Result r = com.editora.process.ProcessRunner.run(
                                        cwd, java.time.Duration.ofMinutes(3), argv);
                                if (r.exit() == 0) {
                                    String content =
                                            java.nio.file.Files.exists(tmp) ? java.nio.file.Files.readString(tmp) : "";
                                    cp = com.editora.maven.MavenClasspath.assemble(content, root);
                                }
                            } finally {
                                java.nio.file.Files.deleteIfExists(tmp);
                            }
                        } catch (Exception e) {
                            cp = null;
                        }
                        List<String> result = cp;
                        Platform.runLater(() -> cb.accept(result));
                    },
                    "editora-maven-cp");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void runGradleRunTask(java.nio.file.Path root) {
            String task =
                    com.editora.build.SpringBoot.gradleRunTask(readGradleBuildFile(root)); // bootRun for Spring Boot
            for (BuildCoordinator c : buildCoordinators) {
                if (c.tool() == BuildTool.GRADLE && c.isEnabled() && c.isDetected()) {
                    c.runTask(List.of(task), List.of());
                    return;
                }
            }
        }
    });

    /** Reads the Gradle build script at {@code root} ({@code build.gradle} or {@code .kts}) for the Spring
     *  Boot sniff, or "" when neither is readable. */
    private static String readGradleBuildFile(java.nio.file.Path root) {
        if (root == null) {
            return "";
        }
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            java.nio.file.Path f = root.resolve(name);
            if (java.nio.file.Files.isRegularFile(f)) {
                try {
                    return java.nio.file.Files.readString(f);
                } catch (java.io.IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** The IntelliJ-style Test Results feature: it hooks each build coordinator (see the injection where the
     *  tool windows are built) and builds the results tree from a recognized {@code test} run. See
     *  {@link TestRunCoordinator}. */
    private final TestRunCoordinator testRunCoordinator =
            new TestRunCoordinator(coordinatorHost, new TestRunCoordinator.Ops() {
                @Override
                public void openTestResults() {
                    if (testResultsToolWindow != null) {
                        toolWindows.open(testResultsToolWindow, false);
                    }
                }

                @Override
                public void setTestResultsAvailable(boolean available) {
                    if (testResultsToolWindow != null) {
                        toolWindows.setAvailable(testResultsToolWindow, available);
                    }
                }

                @Override
                public void openLink(com.editora.run.StackTraceLinks.Link link) {
                    testNavigation.openRunLink(link);
                }

                @Override
                public void jumpToTest(com.editora.test.TestNode node, BuildTool tool) {
                    testNavigation.jumpToTestSource(node, tool);
                }

                @Override
                public void runTest(BuildTool tool, Path root, List<String> taskArgs, List<String> toggleArgs) {
                    testNavigation.buildCoordinatorFor(tool).ifPresent(c -> c.runTask(taskArgs, toggleArgs));
                }

                @Override
                public void stopTest(BuildTool tool) {
                    testNavigation.buildCoordinatorFor(tool).ifPresent(BuildCoordinator::stop);
                }

                @Override
                public boolean debugAvailable() {
                    return debugCoordinator.debugEffectiveFor("java"); // already folds in the master gate
                }

                @Override
                public void attachDebugger(String className, String host, int port) {
                    // Anchor the session on the test's own source when we can find it, else the active buffer.
                    String hint = com.editora.test.TestSourceLocator.fileHint(className, BuildTool.MAVEN);
                    Path anchor = hint == null ? null : testNavigation.resolveTestSourceFile(hint);
                    if (anchor == null) {
                        EditorBuffer b = activeBuffer();
                        anchor = b == null ? null : b.getPath();
                    }
                    setStatus(tr("status.testrunner.debugAttaching", port));
                    debugCoordinator.attachToPort(anchor, host, port);
                }
            });

    /** The whole LSP integration (nav/format, diagnostics routing, the configure/detect/per-buffer-sync
     *  gating + lifecycle, the status-bar segment, structure outline, semantic tokens); see
     *  {@link LspCoordinator}. The {@code LspManager} stays owned here (DAP layers on its jdtls session, the
     *  MCP bridge reads its diagnostics) and is passed in. */
    private final LspCoordinator lspCoordinator =
            new LspCoordinator(coordinatorHost, lspManager, new LspCoordinator.Ops() {
                @Override
                public String homeCollapsed(String absolutePath) {
                    return MainController.homeCollapsed(absolutePath);
                }

                @Override
                public void openAndGoto(Path file, int line0, int col0) {
                    MainController.this.openAndGoto(file, line0, col0);
                }

                @Override
                public EditorBuffer openReadOnlyDoc(String title, String content, String language) {
                    // The openTechnicalDictionary pattern: an in-memory, read-only, path-less buffer (#665).
                    EditorBuffer buffer = new EditorBuffer();
                    buffer.setDisplayName(title);
                    buffer.setContent(content);
                    buffer.setLanguageOverride(language);
                    addBuffer(buffer, true);
                    buffer.setViewMode(true); // library source is for reading, not editing
                    return buffer;
                }

                @Override
                public boolean selectBufferTab(EditorBuffer buffer) {
                    for (Tab tab : editorArea.tabs()) {
                        if (bufferOf(tab) == buffer) {
                            editorArea.select(tab);
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public boolean activeEditable() {
                    return editing.activeEditable();
                }

                @Override
                public boolean lspFeatureEnabled() {
                    return lspEnabled();
                }

                @Override
                public void setLspLoading(boolean loading) {
                    statusBar.setLspLoading(loading);
                }

                @Override
                public EditorBuffer bufferForPath(Path file) {
                    return bufferOf(tabForPath(file));
                }

                @Override
                public EditorBuffer openBackgroundBuffer(Path file) {
                    return MainController.this.openBackgroundBuffer(file);
                }

                @Override
                public void openBackgroundBufferAsync(Path file, java.util.function.Consumer<EditorBuffer> done) {
                    MainController.this.openBackgroundBufferAsync(file, done);
                }

                @Override
                public void fileRenamed(Path from, Path to) {
                    onProjectFileRenamed(from, to); // remap buffer/tab + migrate per-file session state
                    if (projectPanel != null) {
                        projectPanel.refreshTree();
                    }
                }

                @Override
                public void fileCreated(Path file) {
                    if (projectPanel != null) {
                        projectPanel.refreshTree();
                    }
                }

                @Override
                public void fileDeleted(Path file) {
                    onProjectFileDeleted(file);
                    if (projectPanel != null) {
                        projectPanel.refreshTree();
                    }
                }

                @Override
                public void setStatusBarLsp(String label) {
                    statusBar.setLsp(label);
                }

                @Override
                public void setProblemsAvailable(boolean available) {
                    if (problemsToolWindow != null) {
                        toolWindows.setAvailable(problemsToolWindow, available);
                    }
                    if (referencesToolWindow != null) {
                        toolWindows.setAvailable(referencesToolWindow, available);
                    }
                    if (hierarchyToolWindow != null) {
                        toolWindows.setAvailable(hierarchyToolWindow, available); // same LSP-managed gate (#682)
                    }
                }

                @Override
                public void enableNavigationWindowsByDefault() {
                    if (referencesToolWindow != null) {
                        toolWindows.setVisibleIfUnset(referencesToolWindow, true);
                    }
                    if (hierarchyToolWindow != null) {
                        toolWindows.setVisibleIfUnset(hierarchyToolWindow, true);
                    }
                }

                @Override
                public void openReferencesWindow() {
                    if (referencesToolWindow != null) {
                        toolWindows.open(referencesToolWindow);
                    }
                }

                @Override
                public void openHierarchyWindow() {
                    if (hierarchyToolWindow != null) {
                        toolWindows.open(hierarchyToolWindow);
                    }
                }

                @Override
                public void setStructureSymbols(EditorBuffer buffer, java.util.List<com.editora.lsp.SymbolNode> syms) {
                    structurePanel.setLspSymbols(buffer, syms);
                }

                @Override
                public void refreshRunButton() {
                    updateRunButton();
                }

                @Override
                public Path jdtlsWorkspaceBase() {
                    return config.getConfigDir().resolve("jdtls-workspaces");
                }

                @Override
                public Path lspProjectRoot() {
                    Project active =
                            (projects != null && config.getSettings().isProjectSupport()) ? projects.active() : null;
                    return active == null ? null : Path.of(active.root());
                }

                @Override
                public void onDetectionSettled() {
                    installPrompts.maybeOfferInstall(
                            activeBuffer()); // hide/show the install banner per fresh LSP detection
                }

                @Override
                public void onServerCapabilitiesReady() {
                    // A jdtls that ships java-debug (e.g. Homebrew's) advertises the debug commands here —
                    // there is no local plugin jar to locate, so this is what un-gates debugging (#711).
                    debugCoordinator.refreshJavaDebugAvailability();
                    installPrompts.maybeOfferInstall(activeBuffer()); // the install banner may no longer be warranted
                }

                @Override
                public Path canonicalize(Path file) {
                    return canonicalPath(file);
                }
            });

    /** Personal Notes feature; owns the panel/jump-pickers/persistence. Built in {@link #init} (needs config). */
    private NotesCoordinator notesCoordinator;

    // --- HTTP Client (.http request runner + response tool window) -----------------------------------

    /** The HTTP Client feature; see {@link HttpClientCoordinator}. Takes the shared host + http-specific ops. */
    private final HttpClientCoordinator httpClient =
            new HttpClientCoordinator(coordinatorHost, new HttpClientCoordinator.WindowOps() {
                @Override
                public void openTab(EditorBuffer buffer) {
                    addBuffer(buffer, true);
                }

                @Override
                public void updateRunGating() {
                    updateRunButton();
                }

                @Override
                public String savedEnvironment() {
                    return config.getWorkspaceState().getHttpEnvironment();
                }

                @Override
                public void persistEnvironment(String env) {
                    config.getWorkspaceState().setHttpEnvironment(env);
                    config.save();
                }
            });

    // --- AI Agent (an embedded ACP agent — Claude Code etc. — in the chat tool window) -------------

    private final AgentCoordinator agentCoordinator = new AgentCoordinator(coordinatorHost, new AgentCoordinator.Ops() {
        @Override
        public Path projectRoot() {
            Project active = (projects != null && config.getSettings().isProjectSupport()) ? projects.active() : null;
            return active == null ? null : Path.of(active.root());
        }

        @Override
        public EditorBuffer bufferForPath(String path) {
            return mcpBridge.openBufferForPath(path);
        }

        @Override
        public void toggleToolWindow() {
            toolWindows.toggle(agentToolWindow);
        }

        @Override
        public void openToolWindow(boolean focus) {
            if (focus) {
                toolWindows.open(agentToolWindow, true);
            } else {
                toolWindows.open(agentToolWindow);
            }
        }

        @Override
        public void closeToolWindow() {
            toolWindows.close(agentToolWindow);
        }

        @Override
        public void setToolWindowAvailable(boolean available) {
            toolWindows.setAvailable(agentToolWindow, available);
        }

        @Override
        public void refreshProjectTree() {
            projectPanel.refreshTree();
        }

        @Override
        public EditorBuffer openBackgroundBuffer(Path target) {
            return MainController.this.openBackgroundBuffer(target);
        }

        @Override
        public void openPath(Path file) {
            fileWorkflows.openPath(file);
        }

        @Override
        public void rememberSession(
                String sessionId, String cwd, String candidateLabel, long updatedAt, String agentId) {
            if (agentSessionHistory != null) {
                agentSessionHistory.remember(sessionId, cwd, candidateLabel, updatedAt, agentId);
            }
        }

        @Override
        public javafx.collections.ObservableList<com.editora.config.AgentSessionHistory.Entry> sessionHistory() {
            return agentSessionHistory != null
                    ? agentSessionHistory.getList()
                    : javafx.collections.FXCollections.observableArrayList();
        }
    });

    /** Applies the AI Agent feature gate: tears down a disabled agent + re-gates its tool window. */
    private void applyAgentSupport() {
        agentCoordinator.applySupport();
        if (agentToolWindow != null) {
            // Keep the stripe hidden while the panel is popped out into its own window (else re-opening the
            // dock would yank the panel node out of the floating window).
            toolWindows.setAvailable(agentToolWindow, agentCoordinator.isEnabled() && !agentCoordinator.isDetached());
        }
    }

    // --- AI actions (direct Anthropic API: commit message / explain / rewrite) ---------------------

    private final AiCoordinator aiCoordinator = new AiCoordinator(coordinatorHost, new AiCoordinator.Ops() {
        @Override
        public Path repoRoot() {
            return git.repoRoot();
        }

        @Override
        public void stagedDiff(Path root, java.util.function.Consumer<String> onResult) {
            git.service().run(root, r -> onResult.accept(r.ok() ? r.out() : null), "diff", "--cached");
        }

        @Override
        public void setCommitMessage(String message) {
            gitPanel.setCommitMessage(message);
        }

        @Override
        public void openCommitWindow() {
            toolWindows.open(commitToolWindow, true);
            gitPanel.focusCommitMessage();
        }

        @Override
        public void openTab(EditorBuffer buffer) {
            addBuffer(buffer, true);
        }

        @Override
        public void setCommitAiAvailable(boolean available) {
            gitPanel.setAiAvailable(available);
        }
    });

    // --- Doctor (external-tool health screen, a Welcome-style tab; see doctor/ + DoctorCoordinator) ---

    private final DoctorCoordinator doctorCoordinator =
            new DoctorCoordinator(coordinatorHost, new DoctorCoordinator.Ops() {
                @Override
                public com.editora.mermaid.MermaidService mermaidService() {
                    return mermaid.service();
                }

                @Override
                public com.editora.diagram.DiagramService diagramService() {
                    return diagram.service();
                }

                @Override
                public com.editora.typst.TypstService typstService() {
                    return typst.service();
                }

                @Override
                public boolean gitFeatureEnabled() {
                    return git.isEnabled();
                }

                @Override
                public boolean lspFeatureEnabled() {
                    return lspEnabled();
                }

                @Override
                public boolean debugFeatureEnabled() {
                    return debugCoordinator != null && debugCoordinator.debugSupportEnabled();
                }

                @Override
                public java.util.List<String> lspServerIds() {
                    return LspCoordinator.serverIds();
                }

                @Override
                public boolean lspServerEnabled(String serverId) {
                    return lspCoordinator.serverEnabled(serverId);
                }

                @Override
                public java.util.List<String> lspServerArgv(String serverId) {
                    return lspCoordinator.serverArgv(serverId);
                }

                @Override
                public void installServer(String serverId, java.util.function.Consumer<Boolean> onDone) {
                    installCoordinator.installServer(serverId, onDone);
                }

                @Override
                public void installLang(
                        com.editora.install.InstallCatalog.Lang lang, java.util.function.Consumer<Boolean> onDone) {
                    installCoordinator.installSupport(lang, onDone);
                }

                @Override
                public void installTypstCli(java.util.function.Consumer<Boolean> onDone) {
                    installCoordinator.installTypstCli(onDone);
                }

                @Override
                public void openSettingsFor(String settingsKey) {
                    settingsWindow.showDoctorTarget(settingsKey, stage);
                }
            });

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
    private void applyMcpSupport() {
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
    private boolean confirmEnableMcp() {
        Alert confirm =
                new Alert(Alert.AlertType.WARNING, tr("dialog.mcp.enableBody"), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.mcp.enableTitle"));
        confirm.setHeaderText(tr("dialog.mcp.enableHeader"));
        confirm.getDialogPane().setMinWidth(480);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Opens Settings on the Workspace page, where the trusted-folder list lives ({@code workspace.manageTrust}). */
    private void showTrustedFolders() {
        settingsWindow.refreshTrustedFolders();
        settingsWindow.showWorkspace(stage);
    }

    /**
     * Revokes trust for the active window's project root ({@code workspace.revokeTrust}) — the quick undo for
     * a folder trusted by mistake, without hunting for it in the Settings list. Only an <em>explicit</em>
     * entry for this exact root is dropped; trust inherited from an ancestor has to be revoked at that
     * ancestor, which the status message says so the user is never told "revoked" while it still runs.
     */
    private void revokeTrustForActiveRoot() {
        java.nio.file.Path root = windowProject != null ? java.nio.file.Path.of(windowProject.root()) : null;
        if (root == null) {
            setStatus(tr("status.trust.noProject"));
            return;
        }
        config.getTrustStore().revoke(root);
        config.saveTrust();
        settingsWindow.refreshTrustedFolders();
        setStatus(tr(
                config.getTrustStore().isTrusted(root) ? "status.trust.revokedButInherited" : "status.trust.revoked",
                root.getFileName() == null
                        ? root.toString()
                        : root.getFileName().toString()));
    }

    /**
     * Consent before generating from an archetype we did not vet (a user-typed GAV, or one pulled from the
     * remote catalog). {@code archetype:generate} downloads and executes third-party Maven plugin code, and
     * the workspace-trust gate cannot cover it: that only fires when a repo ships an {@code mvnw}, and a
     * brand-new empty directory has none. Default button is Cancel, so dismissing never grants consent.
     */
    /**
     * Gives a freshly generated Maven project a run configuration named after it, so Run works on the first
     * click instead of sending the user to Edit Configurations.
     *
     * <p>Written into the <b>new project's</b> session file before {@code openOrFocus} builds its window —
     * that window loads {@code projects/<id>.json} on construction, so seeding afterwards would be a race,
     * and writing it into <em>this</em> window's state would put the configuration in the wrong project.
     * <p><b>Deliberately does not call {@code ConfigManager.load()}.</b> That runs
     * {@code SharedConfig.load()}, which replaces the shared {@code Settings} instance every open window
     * holds <em>by reference</em> and re-reads every shared store from disk — corrupting live windows in
     * order to write one file. A brand-new project needs no load: {@code ConfigManager} starts with a fresh
     * {@code WorkspaceState}, which is exactly the right starting point, and an existing session file is
     * left strictly alone.
     *
     * <p>The open file matters as much as the configuration: a Java launch resolves its classpath through
     * jdtls routed via an <b>open Java file</b>, so a window that opens on {@code pom.xml} alone has nothing
     * to route through and Run reports "open a Java file from the project".
     *
     * <p>No main class means no configuration: a webapp or plugin archetype has nothing to launch, and a
     * configuration that fails at the click is worse than none at all.
     */
    private void seedNewProjectSession(
            Project project, java.nio.file.Path root, String name, com.editora.maven.GeneratedProject.MainClass main) {
        if (main == null || main.fqn() == null || main.fqn().isBlank()) {
            return;
        }
        try {
            java.nio.file.Path stateFile = projects.stateFile(project);
            if (java.nio.file.Files.exists(stateFile)) {
                return; // an existing project reused by name keeps its own session
            }
            ConfigManager seeded = new ConfigManager(config.shared(), stateFile);
            WorkspaceState state = seeded.getWorkspaceState(); // fresh defaults; see the note above
            // workingDir = the project root, so the launch resolves against this project even with several
            // open, and a relative path in the program behaves as it would from a terminal there.
            // projectName = the artifactId, which is what jdtls names an imported Maven project. Blank works
            // too (Run falls back to jdtls's own enumeration), but naming it lets the very first launch
            // resolve directly instead of round-tripping an enumeration first.
            // beforeLaunch = compile. archetype:generate writes SOURCES only, and Editora runs jdtls with
            // autobuild disabled, so nothing has produced target/classes yet: the resolved classpath is
            // correct and the JVM still dies with ClassNotFoundException on the very first Run. A
            // before-launch step is the mechanism for exactly this — a non-zero exit aborts the launch, so a
            // failed compile never runs a stale binary. Bare `mvn` rather than a resolved absolute path:
            // this is persisted, and a path would rot the moment the user changed their Maven install.
            state.setRunConfigurations(List.of(new com.editora.config.RunConfiguration(
                    name, "java", "", main.fqn(), name, "", "", root.toString(), "", "mvn -q compile")));
            state.setSelectedRunConfig(name);
            state.setOpenFiles(List.of(new WorkspaceState.OpenFile(main.file().toString(), 0, false)));
            state.setActiveFile(main.file().toString());
            seeded.save();
        } catch (RuntimeException e) {
            // The project is generated and open either way; a missing configuration is a papercut, not a
            // reason to fail the whole flow.
            LOG.log(java.util.logging.Level.WARNING, "could not seed the new project's session", e);
        }
    }

    private boolean confirmArchetypeGenerate(com.editora.maven.MavenArchetype archetype) {
        ButtonType proceed = new ButtonType(tr("dialog.mavenProject.trustAccept"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(
                Alert.AlertType.WARNING,
                tr("dialog.mavenProject.trustBody", archetype.gav()),
                ButtonType.CANCEL,
                proceed);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.mavenProject.trustTitle"));
        confirm.setHeaderText(tr("dialog.mavenProject.trustHeader", archetype.artifactId()));
        confirm.getDialogPane().setMinWidth(520);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == proceed;
    }

    private boolean confirmTrustFolder(java.nio.file.Path root, java.nio.file.Path wrapper) {
        java.nio.file.Path name = root.getFileName();
        ButtonType trust = new ButtonType(tr("dialog.trust.accept"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(
                Alert.AlertType.WARNING,
                tr("dialog.trust.body", wrapper.getFileName().toString(), root.toString()),
                ButtonType.CANCEL,
                trust);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.trust.title"));
        confirm.setHeaderText(tr("dialog.trust.header", name == null ? root.toString() : name.toString()));
        confirm.getDialogPane().setMinWidth(520);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == trust;
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
    private void ifMcp(Runnable action) {
        if (mcpEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.mcpDisabled"));
        }
    }

    /** {@code view.toggleMcp}: flip the feature (with a security notice before enabling), re-apply, re-sync. */
    private void toggleMcpSupport() {
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
    private void copyMcpEndpoint() {
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

    /** True when {@code b}'s file is on the local filesystem (or untitled) — the gate for every feature
     *  that shells out to a local process (LSP/DAP/git/run/HTTP). Remote (SFTP) buffers are text-only. */
    private boolean isLocalBuffer(EditorBuffer b) {
        return b != null && com.editora.vfs.Vfs.isLocal(b.getPath());
    }

    // --- LSP (Language Server Protocol) integration --------------------------------------------

    private boolean lspEnabled() {
        // Simple UI mode disables LSP (servers, diagnostics, completion, navigation); saved setting unchanged.
        return config.getSettings().isLspSupport() && !chrome.simpleModeActive();
    }

    /** Thin LspManager diagnostics callback — delegates to {@link LspCoordinator#onDiagnostics} (kept here
     *  only to avoid an illegal forward reference at the manager's field initializer; see {@link #lspManager}). */
    private void onLspDiagnostics(Path file, java.util.List<com.editora.editor.LspDiagnostic> diagnostics) {
        lspCoordinator.onDiagnostics(file, diagnostics);
    }

    /** Thin LspManager status callback — delegates to {@link LspCoordinator#onServerStatus} (kept here
     *  only to avoid an illegal forward reference at the manager's field initializer; see {@link #lspManager}). */
    private void onLspServerStatus(String type, String message) {
        lspCoordinator.onServerStatus(type, message);
    }

    /** Runs {@code action} only when LSP is enabled; otherwise reports it (no-op command/key). */
    private void ifLsp(Runnable action) {
        if (lspEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.lspDisabled"));
        }
    }

    private void toggleLsp() {
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
    private void openAndGoto(Path file, int line0, int col0) {
        NavigationHistory.Location origin = navigation.navigating ? null : navigation.captureCurrent();
        fileWorkflows.openPath(file);
        Platform.runLater(() -> {
            navigation.suppressNavRecord = true; // let this outer call own the recording, not the nested gotoInFile
            sessions.gotoInFile(file, line0 + 1, col0 + 1);
            navigation.suppressNavRecord = false;
            if (!navigation.navigating) {
                navigation.recordJump(origin, new NavigationHistory.Location(file, line0, col0));
            }
            navigation.navigating = false; // a back/forward jump has landed
        });
    }

    /** The open buffer for {@code target} (canonical-path match), or null if not open. */
    private EditorBuffer openBufferFor(Path target) {
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.getPath() != null && canonicalPath(b.getPath()).equals(canonicalPath(target))) {
                return b;
            }
        }
        return null;
    }

    /** Opens {@code target} (assumed not already open — callers check {@link #openBufferFor} first) as a
     *  new, unfocused background tab. Used when something other than the user opens a file the editor
     *  doesn't have a tab for yet (a diff's compare-with-local target, an AI agent's newly-written file). */
    private EditorBuffer openBackgroundBuffer(Path target) {
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(target);
            fileWorkflows.loadInto(buffer, target);
            addBuffer(buffer, false); // background: keep the caller's current tab focused
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    /** Workspace edits may touch unopened files. Decode them on the normal file-load executor and only
     *  create/attach the RichTextFX buffer on the FX thread. */
    private void openBackgroundBufferAsync(Path target, java.util.function.Consumer<EditorBuffer> done) {
        EditorBuffer existing = openBufferFor(target);
        if (existing != null) {
            done.accept(existing);
            return;
        }
        fileWorkflows.fileLoadExecutor.execute(() -> {
            try {
                FileWorkflowCoordinator.PreparedLoad load = fileWorkflows.prepareLoad(target, false);
                Platform.runLater(() -> {
                    if (load.binary()) {
                        done.accept(null);
                        return;
                    }
                    EditorBuffer buffer = new EditorBuffer();
                    buffer.setPath(target);
                    fileWorkflows.applyPreparedLoad(buffer, load);
                    addBuffer(buffer, false);
                    done.accept(buffer);
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> done.accept(null));
            }
        });
    }
    // --- GitHub tool window ----------------------------------------------------------------------

    // --- Git Log / History tool window -----------------------------------------------------------

    /** Read-only diff of one file at a commit vs its first parent (from the Git Log file list). */

    // --- TODO / highlight patterns ---------------------------------------------------------------

    /** The compiled-pattern matcher pushed to every buffer; rebuilt on init + each settings apply. */

    // --- Markdown lint ---------------------------------------------------------------------------

    /** A cached parse of one {@code .markdownlint.json} (keyed by path, invalidated by mtime). */

    // --- Local File History --------------------------------------------------------------------

    private void findNextMatch() {
        if (findBar.isShown()) {
            findBar.findNext();
        } else {
            findBar.show(false);
        }
    }

    private void findPreviousMatch() {
        if (findBar.isShown()) {
            findBar.findPrevious();
        } else {
            findBar.show(true);
        }
    }

    private void findReplaceCurrentMatch() {
        if (findBar.isShown()) {
            findBar.replaceCurrentMatch();
        } else {
            findBar.show(false);
            findBar.focusReplace();
        }
    }

    private void findReplaceAllMatches() {
        if (findBar.isShown()) {
            findBar.replaceAllMatches();
        } else {
            findBar.show(false);
            findBar.focusReplace();
        }
    }

    /** After a branch switch/pull, silently reload any open buffer whose file changed on disk. */
    private void reloadAllFromDiskSilently() {
        java.util.List<Path> reloaded = new java.util.ArrayList<>();
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer == null || buffer.getPath() == null || buffer.isDirty()) {
                continue; // never clobber unsaved edits
            }
            Path file = buffer.getPath();
            if (Files.exists(file)
                    && buffer.diskChangedFrom(fileWorkflows.lastModifiedMillis(file), fileWorkflows.fileSize(file))) {
                fileWorkflows.reloadFromDisk(tab, buffer);
                reloaded.add(file);
            }
        }
        // A branch switch / pull changed these on disk — tell the language servers too (#677). The open
        // buffers' didChange covers their content, but the servers' project models (dependencies, indexes)
        // key off watched-file events. The project watcher only covers expanded dirs, so this path matters.
        lspCoordinator.watchedFilesReloaded(reloaded);
    }

    private void setupToolbar() {
        setupButton(newButton, Icons.newFile(), tr("tooltip.new"), "file.new");
        setupButton(newFromTemplateButton, Icons.template(), tr("tooltip.newFromTemplate"), "template.new");
        setupButton(openButton, Icons.open(), tr("tooltip.open"), "file.find");
        setupButton(openFolderButton, Icons.openFolder(), tr("tooltip.openFolder"), "project.open");
        setupButton(saveButton, Icons.save(), tr("tooltip.save"), "file.save");
        setupButton(saveAsButton, Icons.saveAs(), tr("tooltip.saveAs"), "file.saveAs");
        setupButton(undoButton, Icons.undo(), tr("tooltip.undo"), "edit.undo");
        setupButton(redoButton, Icons.redo(), tr("tooltip.redo"), "edit.redo");
        setupButton(cutButton, Icons.cut(), tr("tooltip.cut"), "edit.cut");
        setupButton(copyButton, Icons.copy(), tr("tooltip.copy"), "edit.copy");
        setupButton(pasteButton, Icons.paste(), tr("tooltip.paste"), "edit.paste");
        setupButton(findButton, Icons.find(), tr("tooltip.find"), "find.show");
        setupButton(findInFilesButton, Icons.findInFiles(), tr("tooltip.findInFiles"), "search.inFiles");
        setupButton(splitVerticalButton, Icons.splitVertical(), tr("tooltip.splitVertical"), "view.splitVertical");
        setupButton(
                splitHorizontalButton, Icons.splitHorizontal(), tr("tooltip.splitHorizontal"), "view.splitHorizontal");
        setupButton(runConfigRunButton, Icons.run(), tr("tooltip.runConfigRun"), null);
        setupButton(runConfigDebugButton, Icons.debug(), tr("tooltip.runConfigDebug"), null);
        setupButton(runConfigStopButton, Icons.stopSquare(), tr("tooltip.runConfigStop"), null);
        // The kit's one splash of state colour in the toolbar: green play / accent bug / red stop.
        runConfigRunButton.getStyleClass().add("run-config-play");
        runConfigDebugButton.getStyleClass().add("run-config-bug");
        runConfigStopButton.getStyleClass().add("run-config-stop");
        runConfigurations.setupRunConfigCombo();
        setupButton(paletteButton, Icons.palette(), tr("tooltip.palette"), "palette.show");
        setupButton(closeTabButton, Icons.closeTab(), tr("tooltip.closeTab"), "buffer.close");
        setupButton(simpleModeButton, Icons.simpleMode(), tr("tooltip.simpleMode"), "view.toggleSimpleMode");
        setupButton(settingsButton, Icons.settings(), tr("tooltip.settings"), "view.settings");

        // Reflect open/closed state of the palette and find bar in their toolbar buttons.
        palette.showingProperty().addListener((obs, was, now) -> paletteButton.pseudoClassStateChanged(OPEN, now));
        findBar.visibleProperty().addListener((obs, was, now) -> findButton.pseudoClassStateChanged(OPEN, now));
        // Project switcher (placed after the customizable icon cluster by appendFixedTail); shown when enabled.
        toolbarProjectCombo = new ProjectCombo(this::switchToProject);
        toolbarProjectCombo.setPrefWidth(184); // 15% longer than the previous 160
        // No "Project:" label: it is the only labelled control on a bar of unlabelled icons, and the combo
        // already reads "No Project" or the project's name. Its explanation moves onto the combo itself —
        // where it was also the one hardcoded English string on the toolbar.
        toolbarProjectCombo.setTooltip(new Tooltip(tr("toolbar.project.tip")));
        toolbarProjectCombo.setOnDeleteProject(this::deleteProject); // per-item delete in the dropdown
        projectToolbarGap = new Region();
        projectToolbarGap.setMinWidth(78); // ≈ 3 toolbar-icon widths between the icon cluster and the combo
        projectToolbarGap.setPrefWidth(78);
        // The ToolBar is the one that gives when the window narrows: it takes whatever width the pinned tail
        // leaves and moves the icons it cannot fit into its own overflow chevron. Without a zero minimum an
        // HBox would squeeze the tail instead, which is the behaviour this split exists to prevent.
        toolBar.setMinWidth(0);
        // ...and the tail is the one that doesn't. An HBox short of space shrinks EVERY resizable child
        // toward its minimum, sharing the deficit out, so without this the tail would be squeezed alongside
        // the ToolBar instead of the ToolBar absorbing all of it. (Same fix, and the same reason, as
        // StatusBar.pinSegmentWidths.)
        toolbarTail.setMinWidth(Region.USE_PREF_SIZE);
        // The customizable icon cluster is built data-driven from the persisted layout; the fixed tail
        // (project group + Settings) is refilled after each rebuild by appendFixedTail().
        toolbarCoordinator = new ToolbarCoordinator(coordinatorHost, registry, keymap, toolbarOps());
        toolbarCoordinator.rebuild();
        refreshSplitButtons();
        refreshEditState(); // start disabled (no buffer yet)
        refreshPasteState();
        // Pin the toolbar height so it doesn't jump when the (taller) project combo is hidden — by
        // Simple UI mode or by disabling Projects. Measured after the first layout pass (skin + CSS applied).
        Platform.runLater(this::stabilizeToolbarHeight);
    }

    /**
     * Keeps the toolbar a constant height regardless of whether the project combo is showing. The
     * {@link ProjectCombo} is taller than the icon buttons, so toggling its visibility (Simple UI mode, or the
     * Projects feature being off) would otherwise resize the whole bar. Pinning {@code minHeight} to the
     * combo-driven height (measured, no magic number) makes that the bar's floor in every mode; it still grows
     * for any taller child. No-op until the combo has a valid preferred height (after the first layout).
     */
    private void stabilizeToolbarHeight() {
        if (toolbarRow == null || toolbarTail == null || toolbarProjectCombo == null) {
            return;
        }
        double comboH = toolbarProjectCombo.prefHeight(-1); // independent of the combo's current visibility
        if (comboH <= 0) {
            return;
        }
        javafx.geometry.Insets in = toolbarTail.getInsets();
        // The row, not the ToolBar: the combo lives in the tail now, so the ToolBar's own height no longer
        // moves when it is hidden — the row's does.
        toolbarRow.setMinHeight(Math.ceil(comboH + in.getTop() + in.getBottom()));
    }

    /**
     * Fills the fixed, non-customizable toolbar tail — the run-configuration group, the project-combo group
     * (a gap, the combo, and the Open-Folder icon), Recent, an optional {@code snapshot}/{@code --dev}
     * badge, and the right-pinned Settings button. Called by {@link ToolbarCoordinator} after it lays out
     * the customizable cluster.
     *
     * <p>The tail is its <b>own container</b> beside the {@link javafx.scene.control.ToolBar}, not items in
     * it: a ToolBar overflows from its END, so with everything in one bar a narrow window pushed Settings
     * and the project switcher into the chevron while keeping fourteen icons — exactly backwards. The
     * ToolBar now takes the leftover width and overflows its own icons, every one of which is also in the
     * menus and the palette.
     *
     * <p>Re-uses the same field instances, so their show/hide state from Simple mode / Projects survives a
     * rebuild.
     */
    private void appendFixedTail() {
        // Cleared here: the coordinator calls this after every rebuild, and the tail is no longer emptied
        // for us by the ToolBar being cleared.
        var items = toolbarTail.getChildren();
        items.clear();

        // Run configurations lead the tail: the selector and its Run/Debug/Stop buttons sit at the right
        // end of the bar, across the ToolBar's slack from the icon cluster, the way an IDE pins its run
        // widget. Here rather than in the customizable cluster because the cluster is what overflows into
        // the chevron when the window narrows, and starting a run is not something to lose to a window
        // width; the group hides itself when there is nothing to launch (refreshRunConfigToolbar).
        items.addAll(runConfigCombo, runConfigRunButton, runConfigDebugButton, runConfigStopButton);

        // Project switcher next (a ~3-icon gap keeps it clear of the run controls), with the open-folder
        // icon immediately to the right of the combobox.
        // Recent is NOT here any more: it is a file action, so it lives in the customizable cluster's file
        // group between Save As and Undo (ToolbarCatalog.defaultLayout), not beside the project controls.
        items.addAll(projectToolbarGap, toolbarProjectCombo, openFolderButton);

        // Snapshot badge when the pom carries -SNAPSHOT, i.e. this was built off master between
        // releases rather than from a release tag — so a test build is obvious without opening About.
        if (com.editora.AppInfo.isSnapshot()) {
            Label snapshotBadge = new Label(tr("badge.snapshot"));
            snapshotBadge.getStyleClass().add("snapshot-badge");
            snapshotBadge.setTooltip(new Tooltip(tr("badge.snapshot.tip", com.editora.AppInfo.VERSION)));
            items.addAll(toolbarGap(), snapshotBadge);
        }

        // Dev-mode badge (just left of Settings) when running with --dev, so a development instance is
        // visually distinct from the production one.
        if (config.isDev()) {
            Label devBadge = new Label(tr("badge.devMode"));
            devBadge.getStyleClass().add("dev-mode-badge");
            devBadge.setTooltip(
                    new Tooltip(tr("badge.devMode.tip", config.getConfigDir().toString())));
            items.addAll(toolbarGap(), devBadge);
        }

        // Settings is the only app-level control left on the bar. About and Quit were removed: both live in
        // the Help/File menus and the palette, neither is an action anyone takes mid-edit, and Quit in
        // particular sat in the top-right corner directly under the window's own close button — a
        // one-icon-high miss between "minimise this window" and "exit the application".
        items.addAll(toolbarGap(), settingsButton);
    }

    /**
     * Opens the documentation site in the user's browser.
     *
     * <p>Versioned, via {@link com.editora.AppInfo#releaseVersion()} — the same path the command palette's
     * per-command help uses — so a running build lands on the docs for what it actually is rather than on
     * whatever is current, which for a snapshot build would describe features it does not have.
     */
    private void openDocumentation() {
        openExternalUrl(com.editora.AppInfo.docsUrl());
    }

    /** Id → the existing {@code @FXML} toolbar widget backing each default/special customizable item. */
    private java.util.Map<String, javafx.scene.Node> toolbarBaseWidgets() {
        if (toolbarBaseWidgets == null) {
            java.util.Map<String, javafx.scene.Node> m = new java.util.HashMap<>();
            m.put("file.new", newButton);
            m.put("template.new", newFromTemplateButton);
            m.put("file.find", openButton);
            m.put("buffer.close", closeTabButton);
            m.put("file.save", saveButton);
            m.put("file.saveAs", saveAsButton);
            m.put("file.clearRecent", clearRecentButton);
            m.put("toolbar.recent", recentButton);
            m.put("edit.undo", undoButton);
            m.put("edit.redo", redoButton);
            m.put("edit.cut", cutButton);
            m.put("edit.copy", copyButton);
            m.put("edit.paste", pasteButton);
            m.put("find.show", findButton);
            m.put("search.inFiles", findInFilesButton);
            m.put("view.splitVertical", splitVerticalButton);
            m.put("view.splitHorizontal", splitHorizontalButton);
            m.put("palette.show", paletteButton);
            m.put("view.toggleSimpleMode", simpleModeButton);
            toolbarBaseWidgets = m;
        }
        return toolbarBaseWidgets;
    }

    private ToolbarCoordinator.Ops toolbarOps() {
        return new ToolbarCoordinator.Ops() {
            @Override
            public javafx.scene.control.ToolBar toolBar() {
                return toolBar;
            }

            @Override
            public java.util.Map<String, javafx.scene.Node> baseWidgets() {
                return toolbarBaseWidgets();
            }

            @Override
            public void appendFixedTail() {
                MainController.this.appendFixedTail();
            }

            @Override
            public void afterRebuild() {
                chrome.applySimpleMode(); // hides the curated Simple-mode buttons + collapses orphaned separators
            }

            @Override
            public void openSettingsToolbarPage() {
                settingsWindow.showToolbar(stage);
            }

            @Override
            public void broadcastToolbarChanged() {
                onSettingsApplied(config.getSettings()); // re-applies + rebuilds every window's toolbar
            }
        };
    }

    /** A fixed-width blank gap used to separate the trailing toolbar group without a separator line. */
    private static Region toolbarGap() {
        Region gap = new Region();
        gap.setMinWidth(14);
        gap.setPrefWidth(14);
        return gap;
    }

    /** command id -> first chord bound to it (first binding wins), from the active keymap. */
    private java.util.Map<String, String> invertBindings() {
        java.util.Map<String, String> byCommand = new java.util.LinkedHashMap<>();
        keymap.bindings().forEach((sequence, id) -> byCommand.putIfAbsent(id, sequence));
        return byCommand;
    }

    @FXML
    private void onSplitVertical() {
        toggleSplit(EditorBuffer.Split.SIDE_BY_SIDE);
    }

    @FXML
    private void onSplitHorizontal() {
        toggleSplit(EditorBuffer.Split.STACKED);
    }

    private void toggleSplit(EditorBuffer.Split orientation) {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        buffer.toggleSplit(orientation);
        refreshSplitButtons();
        setStatus(tr(buffer.getSplit() == EditorBuffer.Split.NONE ? "status.editorUnsplit" : "status.editorSplit"));
    }

    private void unsplit() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.setSplit(EditorBuffer.Split.NONE);
            refreshSplitButtons();
            setStatus(tr("status.editorUnsplit"));
        }
    }

    /**
     * Moves the active tab into a new editor group beside its own, so two different files show at once.
     * Refused with a status when there is nothing to move or only one tab in the group — moving the only
     * tab would empty its group, collapse it, and land back where it started.
     */
    private void splitEditorGroup(Orientation orientation) {
        if (editorArea.splitActive(orientation)) {
            setStatus(tr("status.editorGroupSplit"));
        } else {
            setStatus(tr("status.editorGroupSplitUnavailable"));
        }
    }

    /** Moves the active tab to the next editor group, splitting first if the area is not split yet. */
    private void moveTabToNextGroup() {
        setStatus(tr(editorArea.moveActiveToNextGroup() ? "status.editorGroupMoved" : "status.editorGroupNone"));
    }

    /** Moves keyboard focus to the next editor group. */
    private void focusNextEditorGroup() {
        if (!editorArea.focusNextGroup()) {
            setStatus(tr("status.editorGroupNotSplit"));
        }
    }

    /** Merges every editor group back into one. */
    private void unsplitEditorGroups() {
        setStatus(tr(editorArea.unsplit() ? "status.editorGroupsMerged" : "status.editorGroupNotSplit"));
    }

    /**
     * Reflects the active buffer's split state in the toolbar toggle buttons, and disables them on a tab
     * that can't be split at all — the Welcome/Doctor pages and the image/hex/PDF/diff viewers, none of
     * which is an {@link EditorBuffer}, so {@link #toggleSplit} would silently do nothing there.
     */
    private void refreshSplitButtons() {
        EditorBuffer buffer = activeBuffer();
        EditorBuffer.Split split = buffer == null ? EditorBuffer.Split.NONE : buffer.getSplit();
        splitVerticalButton.setDisable(buffer == null);
        splitHorizontalButton.setDisable(buffer == null);
        splitVerticalButton.pseudoClassStateChanged(OPEN, split == EditorBuffer.Split.SIDE_BY_SIDE);
        splitHorizontalButton.pseudoClassStateChanged(OPEN, split == EditorBuffer.Split.STACKED);
    }

    /** A toolbar button whose tooltip shows the live chord for {@code commandId} (refreshed on keymap switch). */
    private record ToolbarTip(Button button, String base, String commandId) {}

    private final java.util.List<ToolbarTip> toolbarTips = new java.util.ArrayList<>();

    private void setupButton(Button button, Node icon, String tooltip, String commandId) {
        button.setGraphic(icon);
        button.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        // Drop any chord baked into the i18n label (e.g. "Save (C-x C-s)"); the live chord is appended below
        // so the hint tracks the active keymap instead of being frozen to the Emacs binding.
        String base = tooltip.replaceAll("\\s*\\([^()]*\\)\\s*$", "");
        toolbarTips.add(new ToolbarTip(button, base, commandId));
        applyToolbarTip(button, base, commandId);
    }

    private void applyToolbarTip(Button button, String base, String commandId) {
        String chord = commandId == null ? null : invertBindings().get(commandId);
        button.setTooltip(new Tooltip(chord == null || chord.isEmpty() ? base : base + " (" + chord + ")"));
    }

    /** Re-applies every toolbar tooltip's chord from the current keymap (after a live keymap switch). */
    private void refreshToolbarTooltips() {
        for (ToolbarTip t : toolbarTips) {
            applyToolbarTip(t.button(), t.base(), t.commandId());
        }
    }

    /**
     * Enables/disables the state-dependent toolbar edit icons (save/undo/redo/cut/copy) for the active
     * buffer. A non-buffer tab (Welcome) or no buffer ⇒ all disabled. Runs on tab switch and on the
     * buffer's own edit/selection/dirty pulses — no polling. Disabling is cosmetic only; the keybinding
     * commands still work. Clipboard-dependent Paste is handled separately by {@link #refreshPasteState}
     * to keep the system-clipboard read off the per-keystroke path.
     */
    private void refreshEditState() {
        if (saveButton == null) {
            return; // toolbar not built yet
        }
        EditorBuffer buffer = activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        boolean hasBuffer = buffer != null;
        boolean editable = hasBuffer && buffer.isEditable();
        boolean hasSelection = area != null && area.getSelection().getLength() > 0;
        saveButton.setDisable(!hasBuffer || !buffer.isDirty());
        undoButton.setDisable(area == null || !area.isUndoAvailable());
        redoButton.setDisable(area == null || !area.isRedoAvailable());
        cutButton.setDisable(!hasSelection || !editable);
        copyButton.setDisable(!hasSelection);
    }

    /**
     * Enables/disables Paste from the system clipboard. Kept separate from {@link #refreshEditState} so
     * the clipboard read happens only on tab switch / window focus-regain / after copy — never per
     * keystroke.
     */
    private void refreshPasteState() {
        if (pasteButton == null) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        boolean editable = buffer != null && buffer.isEditable();
        boolean hasClip = Clipboard.getSystemClipboard().hasString();
        pasteButton.setDisable(!editable || !hasClip);
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

    public void setStatus(String message) {
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
    public void setError(String message) {
        statusBar.setMessage(message, MessageLog.Severity.ERROR);
    }

    private EditorBuffer activeBuffer() {
        return bufferOf(editorArea.selectedTab());
    }

    // --- Undo History popup (the QuickOpen mirror of the Undo History tool window) ---

    /** Checkpoints of the active buffer, newest-first, seeding a baseline when empty (like the panel). */
    private java.util.List<com.editora.editor.UndoHistory.Checkpoint> undoHistoryCheckpoints() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return java.util.List.of();
        }
        if (b.getUndoHistory().isEmpty()) {
            b.captureUndoCheckpoint(); // baseline = the current state, so there's always something to pick
        }
        return b.getUndoHistory().entriesNewestFirst();
    }

    private void restoreUndoCheckpoint(com.editora.editor.UndoHistory.Checkpoint c) {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.restoreUndoCheckpoint(c);
        }
    }

    private CodeArea activeArea() {
        EditorBuffer buffer = activeBuffer();
        return buffer == null ? null : buffer.getFocusedArea();
    }

    private Tab addBuffer(EditorBuffer buffer) {
        return addBuffer(buffer, true);
    }

    /** Adds a tab for {@code buffer}, appended to the strip; selects and focuses it when {@code select}. */
    private Tab addBuffer(EditorBuffer buffer, boolean select) {
        return addBuffer(buffer, select, true);
    }

    /**
     * Adds a tab, optionally resolving path-dependent settings. Loading shells pass false: disk-side
     * preparation resolves EditorConfig once in the background and applies that immutable result before the
     * document insertion.
     */
    private Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
        // Spell checking: share the user dictionary + persist "Add to Dictionary" (before applyViewSettings,
        // which sets the per-file language and enables checking).
        buffer.setSpellUserWords(config.getUserDictionary());
        buffer.setOnAddToDictionary(editorSettings::addUserWordAndRefreshAll);
        editorSettings.applyViewSettings(buffer, resolvePathSettings);
        buffer.getFoldManager().setOnFoldStateChanged(() -> persistFolds(buffer));
        buffer.setOnBookmarksChanged(() -> bookmarkCoordinator.schedulePersistBookmarks(buffer));
        buffer.setBookmarkToggleRequest(bookmarkCoordinator::onBookmarkToggleRequest);
        buffer.setOnNotesChanged(() -> notesCoordinator.schedulePersistNotes(buffer));
        buffer.setOnNarrowChanged(() -> editing.afterNarrowChanged(buffer));
        buffer.setNoteMarkerClick(notesCoordinator::onNoteMarkerClick);
        buffer.setGutterBlameClick(git::onGutterBlameClick);
        todoCoordinator.applyToBuffer(buffer); // push the compiled TODO/highlight matcher (on by default)
        // Refresh the Run button when this buffer's runnable status flips (only acts if it's active).
        buffer.setOnRunnableChanged(() -> {
            if (activeBuffer() == buffer) {
                updateRunButton();
            }
        });
        buffer.setRunHandler(runCoordinator::runActiveFile); // "Run File" editor right-click item (runnable files)
        boolean local = isLocalBuffer(buffer); // remote (SFTP) files can't run a local process
        buffer.setRunEnabled(lspEnabled() && local); // the Run affordance is gated by the LSP feature
        buffer.setShellRunEnabled(lspEnabled() && local && config.getSettings().isBashLspEnabled());
        buffer.setAdminEditAvailable(
                fileWorkflows.elevationAvailable() && local); // "Edit as Administrator" on a locked file
        buffer.setHttpRunHandler(line -> httpClient.runRequest(buffer, line)); // .http request ▶
        buffer.setHttpEnabled(httpClient.isEnabled() && local);
        buffer.setMakeRunHandler(target -> runCoordinator.runMakeTarget(buffer, target)); // Makefile target ▶
        // Makefile-run rides the same Run-feature gate as Java/Python/shell (setRunEnabled above).
        buffer.setTestRunHandler(testNavigation::runSingleTest); // JUnit class/method gutter ▶ → the build tool
        testNavigation.applyTestGutter(
                buffer); // gated by the Test Runner feature + a detected JVM (Maven/Gradle) project
        buffer.setMainRunHandler(m -> runCoordinator.runMainClassNamed(m.fqn())); // project main() ▶ → run
        buffer.setMainDebugHandler(m -> debugCoordinator.debugMainClassNamed(m.fqn())); // editor menu → debug
        testNavigation.applyMainGutter(buffer); // gated by not-Simple + local + JVM project + Java run/debug available
        // Debugging: the breakpoint gutter gate + change/hover hooks (debuggable languages only).
        debugCoordinator.wireBuffer(buffer);
        buffer.setAddNoteHandler(notesCoordinator::addNoteFromContext);
        buffer.setNotesEnabled(notesCoordinator.isEnabled());
        buffer.setOpenUrlHandler(this::openExternalUrl); // Ctrl/Cmd-click + open-link command
        buffer.setAiActionHandlers(aiCoordinator::explainSelection, aiCoordinator::rewriteSelection); // AI sel. bar
        buffer.setTableFileExporter(previews::exportMarkdownTableFile); // Markdown table → CSV/Excel/ODS file
        buffer.setInsertTableHandler(previews::markdownInsertTable); // Markdown format-bar "insert table" button
        buffer.setInsertTypstTableHandler(() -> previews.showTableSizePicker(buffer::insertTypstTable)); // Typst #table
        buffer.setTypstImageHandler(() -> editing.insertTypstImageFromChooser(buffer)); // Typst "Insert Image" menu
        buffer.setPreviewExportPngHandler(typst::exportPng); // Typst preview right-click → PNG
        buffer.setPreviewExportSvgHandler(typst::exportSvg); // Typst preview right-click → SVG
        // HTML Live Preview: the debounced edit pulse reloads the browser (only while this file is served).
        buffer.setHtmlPreviewDirtyListener(() -> htmlPreview.onBufferEdited(buffer));
        buffer.setFormatBarEnabled(config.getSettings().isMarkdownFormatBar());
        buffer.setPreviewExportPdfHandler(exports::exportPreviewPdf); // preview right-click menu
        buffer.setPreviewPrintHandler(exports::printPreview);
        buffer.setPomViewToggleHandler(previews::togglePomView); // pom preview right-click: summary ⇄ XML tree
        buffer.setPreviewExportDocxHandler(exports::exportPreviewDocx); // preview → MS Word
        buffer.setPreviewExportOdtHandler(exports::exportPreviewOdt); // preview → OpenDocument
        buffer.setPreviewExportJsonHandler(exports::exportMarkwhenJson); // Markwhen preview → JSON
        buffer.setTypstRootResolver(this::resolveTypstRoot); // typst --root: nearest typst.toml / project root
        buffer.setOnMarkwhenViewChanged(() -> previews.persistMarkwhenView(buffer)); // persist timeline/calendar choice
        buffer.setOnEnableEditing(() -> enableEditing(buffer)); // "Enable Editing" banner button
        buffer.setSnippetProvider((lang, prefix) -> snippets.byPrefix(lang, prefix));
        buffer.setCompletionProvider(completion::complete);
        buffer.setAiCompletionProvider(aiCoordinator::inlineComplete);
        buffer.setAiCompletionEnabled(aiCoordinator.isInlineCompletionEnabled());
        // A Maven submenu only on a pom.xml — mavenMenu itself enforces that, so this surface and the
        // project tree's cannot disagree about when it appears.
        buffer.setBuildMenuContributor(() -> {
            javafx.scene.control.Menu maven = mavenProjectCoordinator.mavenMenu(buffer.getPath());
            return maven == null ? List.of() : List.of(maven);
        });
        buffer.setMenuContributor(
                () -> { // External Tools submenu + plugin-contributed right-click items
                    List<javafx.scene.control.MenuItem> extra =
                            new ArrayList<>(externalToolCoordinator.editorMenuItems());
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
        buffer.setMarkdownLintValidator((text, cb) ->
                previews.markdownLintService.validate(text, previews.effectiveMarkdownLintDisabled(buffer), diags -> {
                    cb.accept(diags);
                    if (activeBuffer() == buffer
                            && markdownLintToolWindow != null
                            && toolWindows.isOpen(markdownLintToolWindow)) {
                        markdownLintPanel.setResults(buffer.getPath(), diags);
                    }
                }));
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
        Tab tab = addContentTab(buffer, false); // added to the strip; selected below (focus the area, not the node)
        updateTabMeta(tab, buffer); // replaces the default text/icon with the buffer header (drag handle, pin, dirty)
        buffer.dirtyProperty().addListener((obs, was, now) -> {
            updateTabMeta(tab, buffer);
            if (buffer == activeBuffer()) {
                refreshEditState(); // save enablement (e.g. after markClean())
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
                refreshEditState(); // edits change undo/redo (and dirty) availability
            }
        });
        // Selection changes toggle cut/copy enablement (no clipboard read here — that's refreshPasteState).
        buffer.getArea().selectionProperty().addListener((obs, was, now) -> {
            if (buffer == activeBuffer()) {
                refreshEditState();
            }
        });
        installTabMenu(tab, buffer);
        if (select) {
            editorArea.select(tab);
            buffer.getArea().requestFocus();
        }
        return tab;
    }

    /**
     * Creates a tab for any {@link TabContent} (a buffer or the Welcome page), appends it to the strip,
     * and stores the content in {@code userData}. The generic title/icon/close affordance come from the
     * content; buffers then override the header via {@link #updateTabMeta}. Selects + focuses the content
     * node when {@code select}.
     */
    private Tab addContentTab(TabContent content, boolean select) {
        Tab tab = new Tab();
        tab.setContent(content.node());
        tab.setUserData(content);
        // Title lives in a graphic header (not tab.setText) so it's a drag handle for mouse reorder, like
        // buffer tabs. Buffer tabs replace this header via updateTabMeta; non-buffer tabs (Welcome) keep it.
        Label title = new Label(content.title());
        title.getStyleClass().add("tab-title");
        HBox header = new HBox(6);
        header.getStyleClass().add("tab-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Node icon = content.icon();
        if (icon != null) {
            header.getChildren().add(icon);
        }
        header.getChildren().add(title);
        enableTabDrag(header, tab);
        tab.setGraphic(header);
        tab.setClosable(content.closeable());
        tab.setOnCloseRequest(e -> {
            if (!confirmClose(tab)) {
                e.consume();
            }
        });
        editorArea.add(tab);
        if (select) {
            editorArea.select(tab);
            Platform.runLater(content.node()::requestFocus);
        }
        return tab;
    }

    /** Refreshes a tab's title (pin + dirty markers), style classes, and full-path tooltip. */
    private void updateTabMeta(Tab tab, EditorBuffer buffer) {
        // Keep the window title's file name/path + dirty marker in step (dirty flips, Save-As, rename).
        if (tab == editorArea.selectedTab()) {
            updateWindowTitle();
        }
        boolean dirty = buffer.isDirty();
        if (dirty) {
            // Editing is the clearest possible statement that this file is not a passing glance.
            promoteTab(tab);
        }
        boolean isPinned = pinned.contains(tab);
        // The title lives in a graphic node (not tab.setText) so it can be a drag handle for
        // mouse reordering. Pinned tabs show an SVG pin graphic (matching the toolbar icons).
        Label title = new Label((dirty ? "• " : "") + buffer.getTitle());
        title.getStyleClass().add("tab-title");
        if (tab == previewTab) {
            title.getStyleClass().add("preview-tab-title"); // italic: this tab will be reused
        }
        HBox header = new HBox(6);
        header.getStyleClass().add("tab-header");
        header.setAlignment(Pos.CENTER_LEFT);
        if (isPinned) {
            header.getChildren().add(Icons.pin());
        }
        Path p = buffer.getPath();
        boolean remote = com.editora.vfs.Vfs.isRemote(p);
        if (remote) {
            header.getChildren().add(Icons.remote()); // cloud glyph: this file lives on a remote host
        }
        // A file-type glyph reflecting the file's kind (Java/Python/image/…), or a generic doc.
        Node typeIcon = FileIcons.forFileName(buffer.getTitle());
        typeIcon.getStyleClass().add("tab-file-icon");
        header.getChildren().add(typeIcon);
        header.getChildren().add(title);
        enableTabDrag(header, tab);
        tab.setText("");
        tab.setGraphic(header);
        toggleClass(tab, "dirty", dirty);
        toggleClass(tab, "pinned", isPinned);
        toggleClass(tab, "read-only", !buffer.isEditable());
        toggleClass(tab, "remote", remote);
        tab.setTooltip(new Tooltip(
                p == null
                        ? "untitled"
                        : remote
                                ? com.editora.vfs.Vfs.displayLabel(p)
                                : p.toAbsolutePath().toString()));
    }

    /** Wires drag-and-drop on a tab's header so the user can reorder the strip with the mouse. */
    private void enableTabDrag(Node header, Tab tab) {
        header.setOnDragDetected(e -> {
            Dragboard db = header.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString("tab"); // marker; the dragged tab is tracked in draggedTab
            db.setContent(content);
            // A snapshot of the tab follows the cursor so it's clear what's being dragged.
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            db.setDragView(header.snapshot(params, null), e.getX(), e.getY());
            draggedTab = tab;
            header.getStyleClass().add("tab-dragging");
            e.consume();
        });
        header.setOnDragOver(e -> {
            if (draggedTab != null && draggedTab != tab) {
                e.acceptTransferModes(TransferMode.MOVE);
                // Show an insertion line on the side the tab would land.
                boolean after = e.getX() > header.getBoundsInLocal().getWidth() / 2;
                toggleStyle(header, "tab-drop-after", after);
                toggleStyle(header, "tab-drop-before", !after);
            }
            e.consume();
        });
        header.setOnDragExited(e -> clearDropMarkers(header));
        header.setOnDragDropped(e -> {
            clearDropMarkers(header);
            boolean done = false;
            if (draggedTab != null && draggedTab != tab) {
                // Drop on the right half of the target inserts after it, left half before it.
                reorderTab(draggedTab, tab, e.getX() > header.getBoundsInLocal().getWidth() / 2);
                done = true;
            }
            e.setDropCompleted(done);
            e.consume();
        });
        header.setOnDragDone(e -> {
            header.getStyleClass().remove("tab-dragging");
            clearDropMarkers(header);
            draggedTab = null;
        });
    }

    private static void clearDropMarkers(Node header) {
        header.getStyleClass().removeAll("tab-drop-before", "tab-drop-after");
    }

    private static void toggleStyle(Node node, String styleClass, boolean on) {
        node.getStyleClass().remove(styleClass);
        if (on) {
            node.getStyleClass().add(styleClass);
        }
    }

    /**
     * Moves {@code dragged} next to {@code target} (after it when {@code after} is true, else before),
     * keeping pinned tabs grouped at the front: a drop is clamped to the dragged tab's own group.
     */
    private void reorderTab(Tab dragged, Tab target, boolean after) {
        boolean draggedPinned = pinned.contains(dragged);
        reordering = true;
        try {
            editorArea.remove(dragged);
            // Every index below is read *after* the removal, so it already accounts for the gap it left.
            int idx = editorArea.indexOf(target) + (after ? 1 : 0);
            int pinnedInStrip =
                    (int) editorArea.tabs().stream().filter(pinned::contains).count();
            int lo = draggedPinned ? 0 : pinnedInStrip;
            int hi = draggedPinned ? pinnedInStrip : editorArea.size();
            editorArea.add(Math.max(lo, Math.min(idx, hi)), dragged);
        } finally {
            reordering = false;
        }
        editorArea.select(dragged);
    }

    private static void toggleClass(Tab tab, String styleClass, boolean on) {
        if (on) {
            if (!tab.getStyleClass().contains(styleClass)) {
                tab.getStyleClass().add(styleClass);
            }
        } else {
            tab.getStyleClass().remove(styleClass);
        }
    }

    // --- File actions ---

    @FXML
    private void onNew() {
        addBuffer(new EditorBuffer());
        setStatus(tr("status.newBuffer"));
    }

    @FXML
    private void onNewFromTemplate() {
        templateActions.newFromTemplate(null);
    }

    @FXML
    private void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.openFile.title"));
        Path file = pathOf(chooser.showOpenDialog(stage));
        if (file != null) {
            fileWorkflows.openPath(file);
        }
    }

    /**
     * Opens a file chosen from the recent-files list, project-aware: if the file lives under a project
     * other than the active one, switch to that project first (so it opens in that project's session,
     * tree, and layout) and then open it. Files that belong to no project — or to the active one — open
     * directly in the current context.
     */
    private void openRecent(Path file) {
        Project owner = owningProject(file);
        if (owner != null && !owner.equals(projects.active())) {
            if (!switchToProject(owner)) {
                return; // user cancelled the switch (e.g. unsaved changes) — don't open elsewhere
            }
        }
        fileWorkflows.openPath(file);
    }

    /** The enabled project whose root is the closest ancestor of {@code file}, or {@code null}. */
    private Project owningProject(Path file) {
        if (projects == null || !projectsEnabled()) {
            return null;
        }
        Path abs = file.toAbsolutePath().normalize();
        Project best = null;
        for (Project p : projects.list()) {
            Path root = Path.of(p.root()).toAbsolutePath().normalize();
            if (abs.startsWith(root)
                    && (best == null || p.root().length() > best.root().length())) {
                best = p; // prefer the deepest (most specific) matching project root
            }
        }
        return best;
    }

    /** Opens the personal dictionary ({@code dictionary.txt}) in the editor, creating it empty if it doesn't
     *  exist yet (so the link works even before any word is added). Backs the Settings → Spell Check link. */
    private void openPersonalDictionary() {
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
    private void openTechnicalDictionary() {
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
    private Tab previewTab;

    /**
     * Opens {@code file} in the preview slot: the previous preview tab is replaced rather than added to.
     *
     * <p>An already-open file is simply selected and is <em>not</em> demoted into the slot — it earned its
     * place by some earlier deliberate open, and quietly making it disposable would lose it on the next
     * glance at something else.
     */
    private void openPathPreview(Path file) {
        Tab existing = tabForPath(file);
        if (existing != null) {
            editorArea.select(existing);
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
    private void promoteTab(Tab tab) {
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

    private void finishAsyncOpen(Tab tab, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
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
        restoreFolds(buffer);
        bookmarkCoordinator.restoreBookmarks(buffer);
        debugCoordinator.restoreBreakpoints(buffer);
        notesCoordinator.restoreNotes(buffer);
        restoreReadOnly(buffer);
        buffer.setLoading(false);
        previews.restoreMarkdownMode(buffer);
        updateTabMeta(tab, buffer);
        // The loading shell is deliberately read-only until its document lands. Selection attached the
        // status bar while that temporary state was active; refresh after restoring the file's real view
        // mode or the segment can keep saying Read-Only even though the CodeArea is now editable. This is
        // especially visible for `--expert FILE`, where the status bar remains but the tab header is hidden.
        statusBar.refresh();
        lspCoordinator.syncBuffer(buffer); // the temporary heavy-file shell deliberately suppressed this
        // Set the default caret before releasing queued navigation. A later runLater(goToStart) would erase
        // a file:line request that waited on this loading shell.
        buffer.goToStart();
        if (recentFiles != null) {
            recentFiles.add(load.file());
        }
        setStatus(note.isEmpty() ? tr("status.opened", com.editora.config.PathDisplay.of(load.file())) : note);
        clearLoading(buffer);
    }

    private void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error) {
        discardLoading(buffer);
        if (tabForBuffer(buffer) == tab) {
            editorArea.remove(tab);
        }
        setStatus(tr("status.failedOpen", error.getMessage()));
        if (recentFiles != null) {
            recentFiles.remove(file);
        }
    }

    private void clearLoading(EditorBuffer buffer) {
        fileWorkflows.loadingBuffers.remove(buffer);
        List<Runnable> queued = fileWorkflows.afterBufferLoad.remove(buffer);
        if (queued != null) {
            queued.forEach(Runnable::run);
        }
    }

    private void discardLoading(EditorBuffer buffer) {
        fileWorkflows.loadingBuffers.remove(buffer);
        fileWorkflows.afterBufferLoad.remove(buffer);
    }

    /** Command {@code view.openAsText}: opens the active file's bytes in a normal text buffer, bypassing the
     *  image/binary auto-routing (for a file mis-detected as binary, or to read a binary's raw text). No-op
     *  with a status when the active tab has no file. */
    private void openActiveAsText() {
        Path p = tabPath(editorArea.selectedTab());
        if (p == null) {
            setStatus(tr("status.hex.noFile"));
            return;
        }
        openTextBuffer(p);
    }

    /** The {@link ImageViewerPane} in {@code tab}, or {@code null} for a buffer / non-image tab. */
    private static ImageViewerPane imagePaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof ImageViewerPane pane ? pane : null;
    }

    /** The {@link HexViewerPane} in {@code tab}, or {@code null} for a buffer / non-hex tab. */
    private static HexViewerPane hexPaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof HexViewerPane pane ? pane : null;
    }

    /** The {@link PdfViewerPane} in {@code tab}, or {@code null} for a buffer / non-PDF tab. */
    private static PdfViewerPane pdfPaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof PdfViewerPane pane ? pane : null;
    }

    /** The file's resolved {@code .editorconfig} charset, or null (EditorConfig off / remote / no rule). The
     *  BOM check that overrides it lives in {@link com.editora.editorconfig.EditorConfigCharset#resolveName}. */
    String editorConfigCharsetFor(Path file) {
        if (editorSettings.editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)) {
            return com.editora.editorconfig.EditorConfig.resolveFor(file).charset();
        }
        return null;
    }

    @FXML
    private void onClearRecent() {
        if (recentFiles == null || recentFiles.getList().isEmpty()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.clearRecent.title"));
        alert.setHeaderText(tr("dialog.clearRecent.header"));
        alert.setContentText(null);
        ButtonType clear = new ButtonType(tr("dialog.clearRecent.clear"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(clear, cancel);
        if (alert.showAndWait().filter(b -> b == clear).isPresent()) {
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
    private void onCloseTab() {
        closeTab(activeTab());
    }

    private Tab activeTab() {
        return editorArea.selectedTab();
    }

    /**
     * The {@link EditorBuffer} backing {@code tab}, or {@code null} for a non-buffer tab (e.g. the
     * Welcome tab) — every {@code tab.userData} read must go through here, since {@code userData} is
     * {@code Object} and a raw cast would throw {@link ClassCastException} on a non-buffer tab.
     */
    private static EditorBuffer bufferOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof EditorBuffer b ? b : null;
    }

    /** Closes a single tab, confirming first if it is pinned and/or has unsaved changes. */
    private void closeTab(Tab tab) {
        if (tab != null && confirmClose(tab)) {
            editorArea.remove(tab);
        }
    }

    /**
     * Closes each tab in {@code targets} (a snapshot), prompting for dirty buffers and stopping if
     * the user cancels — mirroring {@link #confirmCloseAllBuffers()}.
     */
    private void closeTabs(List<Tab> targets) {
        for (Tab tab : targets) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null && !buffer.isDirty() && !fileWorkflows.hasPendingSave(buffer)) {
                editorArea.remove(tab);
                continue;
            }
            editorArea.select(tab);
            if (buffer != null && !confirmCloseIfDirty(buffer)) {
                return; // user cancelled — stop the batch
            }
            editorArea.remove(tab);
        }
    }

    /** Non-pinned tabs whose index is less than {@code pivot}'s. */
    private List<Tab> eligibleToLeft(Tab pivot) {
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
    private List<Tab> eligibleToRight(Tab pivot) {
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

    private void closeOtherTabs(Tab keep) {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : editorArea.tabs()) {
            if (t != keep && !pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeAllTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : editorArea.tabs()) {
            if (!pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeUnmodifiedTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf(t);
            if (!pinned.contains(t) && (buffer == null || !buffer.isDirty())) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeTabsToLeft(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToLeft(pivot));
        }
    }

    private void closeTabsToRight(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToRight(pivot));
        }
    }

    /** Copies the buffer's absolute path to the system clipboard. */
    private void copyPath(EditorBuffer buffer) {
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(buffer.getPath().toAbsolutePath().toString());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(tr("status.copiedPath"));
    }

    /** Reveals the active buffer's file in the OS file manager (a no-op for an unsaved/remote file). */
    private void revealActiveBuffer() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        revealInFileManager(buffer.getPath(), false, isLocalBuffer(buffer));
    }

    /** Opens a terminal at the active buffer's containing folder (no-op for an unsaved/remote file). */
    private void openTerminalForActiveBuffer() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        openTerminalAt(buffer.getPath(), false, isLocalBuffer(buffer));
    }

    /**
     * Reveals {@code path} in the OS file manager. Used by the palette command, the tab context menu,
     * and the Project tool window. No-op (with a status hint) for an unsaved or remote file.
     */
    private void revealInFileManager(Path path, boolean isDir, boolean local) {
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

    /** Opens a terminal at {@code path}'s containing folder. Shared by the command, tab menu, and tree. */
    private void openTerminalAt(Path path, boolean isDir, boolean local) {
        if (path == null) {
            setStatus(tr("status.reveal.noFile"));
            return;
        }
        if (!local) {
            setStatus(tr("status.reveal.remote"));
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
    private void togglePin(Tab tab) {
        if (tab == null) {
            return;
        }
        if (pinned.remove(tab)) {
            // Unpinned: move just past the remaining pinned group.
            moveTab(tab, pinned.size());
        } else {
            pinned.add(tab);
            // Pinned: park at the end of the pinned group so multiple pins stay grouped.
            moveTab(tab, pinned.size() - 1);
        }
        updateTabMeta(tab, bufferOf(tab));
        setStatus(tr(pinned.contains(tab) ? "status.pinned" : "status.unpinned"));
    }

    /** Moves {@code tab} to {@code target} without corrupting the MRU (see the reordering guard). */
    private void moveTab(Tab tab, int target) {
        int from = editorArea.indexOf(tab);
        if (from < 0) {
            return;
        }
        reordering = true;
        try {
            editorArea.remove(tab);
            int clamped = Math.max(0, Math.min(target, editorArea.size()));
            editorArea.add(clamped, tab);
        } finally {
            reordering = false;
        }
        editorArea.select(tab);
    }

    /** Renames the buffer's file on disk and migrates path-keyed state (folds, recent files). */
    private void renameFile(EditorBuffer buffer, Tab tab) {
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        Path old = buffer.getPath();
        promptText(
                tr("dialog.renameFile.title"),
                tr("dialog.renameFile.content"),
                old.getFileName().toString(),
                name -> {
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) {
                        return;
                    }
                    Path target = old.resolveSibling(trimmed);
                    if (target.equals(old)) {
                        return;
                    }
                    if (Files.exists(target)) {
                        setStatus(tr("status.renameFailedExists", target.getFileName()));
                        return;
                    }
                    // Capture the per-file storage keys while the old file still exists (the note key is the
                    // canonical/real path, which can't be recomputed once the file has moved away).
                    String oldBookmarkKey = old.toString();
                    String oldNoteKey = noteKey(buffer);
                    fileWorkflows.invalidatePendingWrite(old);
                    try {
                        Files.move(old, target);
                    } catch (IOException e) {
                        setStatus(tr("status.renameFailed", e.getMessage()));
                        return;
                    }
                    buffer.setPath(target); // re-detects language/grammar
                    previews.ensurePreviewControls(buffer); // a rename to/from .md/.mmd flips previewability
                    htmlPreview.ensureControl(buffer); // a rename to/from .html flips the browser globe
                    logViewer.ensureControl(buffer); // a rename to/from .log flips the log control
                    // Migrate state keyed by the absolute path string.
                    var folded = config.getWorkspaceState().getFoldedRegions();
                    List<Integer> folds = folded.remove(old.toString());
                    if (folds != null) {
                        folded.put(target.toString(), folds);
                    }
                    if (recentFiles != null) {
                        recentFiles.remove(old);
                        recentFiles.add(target);
                    }
                    requestSave();
                    // Carry bookmarks + personal notes over to the new path so an in-app rename never strands them.
                    bookmarkCoordinator.migrateKey(oldBookmarkKey, target.toString());
                    notesCoordinator.migrateKey(oldNoteKey, noteKey(buffer));
                    updateTabMeta(tab, buffer);
                    statusBar.refresh();
                    if (buffer == activeBuffer()) {
                        breadcrumb.setActiveFile(buffer.getPath());
                    }
                    setStatus(tr("status.renamedTo", target.getFileName()));
                });
    }

    /** Builds and attaches the right-click context menu for a tab. */
    private void installTabMenu(Tab tab, EditorBuffer buffer) {
        MenuItem save = new MenuItem(tr("menu.save"));
        save.setGraphic(Icons.save());
        save.setOnAction(e -> fileWorkflows.save(buffer));
        MenuItem saveAs = new MenuItem(tr("menu.saveAs"));
        saveAs.setGraphic(Icons.saveAs());
        saveAs.setOnAction(e -> fileWorkflows.saveAs(buffer));
        MenuItem close = new MenuItem(tr("menu.close"));
        close.setGraphic(Icons.closeTab());
        close.setOnAction(e -> closeTab(tab));
        MenuItem closeOthers = new MenuItem(tr("menu.closeOthers"));
        closeOthers.setGraphic(Icons.closeOtherTabs());
        closeOthers.setOnAction(e -> closeOtherTabs(tab));
        MenuItem closeAll = new MenuItem(tr("menu.closeAll"));
        closeAll.setGraphic(Icons.closeAllTabs());
        closeAll.setOnAction(e -> closeAllTabs());
        MenuItem closeUnmodified = new MenuItem(tr("menu.closeUnmodified"));
        closeUnmodified.setGraphic(Icons.closeUnmodifiedTabs());
        closeUnmodified.setOnAction(e -> closeUnmodifiedTabs());
        MenuItem closeLeft = new MenuItem(tr("menu.closeLeft"));
        closeLeft.setGraphic(Icons.closeTabsLeft());
        closeLeft.setOnAction(e -> closeTabsToLeft(tab));
        MenuItem closeRight = new MenuItem(tr("menu.closeRight"));
        closeRight.setGraphic(Icons.closeTabsRight());
        closeRight.setOnAction(e -> closeTabsToRight(tab));
        MenuItem copyPath = new MenuItem(tr("menu.copyPath"));
        copyPath.setGraphic(Icons.copy());
        copyPath.setOnAction(e -> copyPath(buffer));
        MenuItem pin = new MenuItem(tr("menu.pin"));
        pin.setGraphic(Icons.pin());
        pin.setOnAction(e -> togglePin(tab));
        MenuItem rename = new MenuItem(tr("menu.rename"));
        rename.setGraphic(Icons.edit());
        rename.setOnAction(e -> renameFile(buffer, tab));
        // Git submenu — mirrors the Project tree's cell "Git" submenu, acting on this tab's file.
        Menu gitMenu = new Menu(tr("project.menu.git"));
        gitMenu.setGraphic(Icons.git());
        MenuItem stage = new MenuItem(tr("project.menu.git.stage"));
        stage.setGraphic(Icons.stageAll());
        stage.setOnAction(e -> git.ifEnabled(() -> git.gitStagePath(buffer.getPath())));
        MenuItem unstage = new MenuItem(tr("project.menu.git.unstage"));
        unstage.setGraphic(Icons.remove());
        unstage.setOnAction(e -> git.ifEnabled(() -> git.gitUnstagePath(buffer.getPath())));
        MenuItem revert = new MenuItem(tr("project.menu.git.revert"));
        revert.setGraphic(Icons.undo());
        revert.setOnAction(e -> git.ifEnabled(() -> git.gitRevertPath(buffer.getPath())));
        MenuItem ignore = new MenuItem(tr("project.menu.git.addToGitignore"));
        ignore.setGraphic(Icons.git());
        ignore.setOnAction(e -> git.ifEnabled(() -> git.addToGitignore(buffer.getPath())));
        MenuItem diffHead = new MenuItem(tr("project.menu.git.compareHead"));
        diffHead.setGraphic(Icons.diff());
        diffHead.setOnAction(e -> git.ifEnabled(() -> diffCoordinator.diffPathVsHead(buffer.getPath())));
        MenuItem diffBranch = new MenuItem(tr("project.menu.git.compareBranch"));
        diffBranch.setGraphic(Icons.diff());
        diffBranch.setOnAction(e -> git.ifEnabled(() -> diffCoordinator.diffPathVsBranch(buffer.getPath())));
        MenuItem diffTag = new MenuItem(tr("project.menu.git.compareTag"));
        diffTag.setGraphic(Icons.diff());
        diffTag.setOnAction(e -> git.ifEnabled(() -> diffCoordinator.diffPathVsTag(buffer.getPath())));
        MenuItem diffCommit = new MenuItem(tr("project.menu.git.compareRevision"));
        diffCommit.setGraphic(Icons.diff());
        diffCommit.setOnAction(e -> git.ifEnabled(() -> diffCoordinator.diffPathVsCommit(buffer.getPath())));
        MenuItem annotate = new MenuItem(tr("project.menu.git.annotate"));
        annotate.setGraphic(Icons.blame());
        annotate.setOnAction(e -> git.ifEnabled(() -> {
            fileWorkflows.openPath(buffer.getPath());
            git.annotateActive();
        }));
        MenuItem history = new MenuItem(tr("project.menu.git.fileHistory"));
        history.setGraphic(Icons.gitLog());
        history.setOnAction(e -> git.ifEnabled(() -> gitWindows.gitFileHistoryForPath(buffer.getPath())));
        gitMenu.getItems()
                .addAll(
                        stage,
                        unstage,
                        revert,
                        ignore,
                        new SeparatorMenuItem(),
                        diffHead,
                        diffBranch,
                        diffTag,
                        diffCommit,
                        annotate,
                        history);
        // "Compare With…" (any two files) and "Open in Diff Viewer" (a .patch/.diff file) are not Git
        // actions, so they stay outside the Git submenu.
        MenuItem compareWith = new MenuItem(tr("menu.compareWith"));
        compareWith.setGraphic(Icons.diff());
        compareWith.setOnAction(e -> diffCoordinator.compareActiveWithFile());
        MenuItem openPatch = new MenuItem(tr("menu.openInDiffViewer"));
        openPatch.setGraphic(Icons.diff());
        openPatch.setOnAction(e -> diffCoordinator.openPatchFile(buffer));
        MenuItem reveal = new MenuItem(tr("menu.revealInFileManager"));
        reveal.setGraphic(Icons.revealInFiles());
        reveal.setOnAction(e -> revealInFileManager(buffer.getPath(), false, isLocalBuffer(buffer)));
        MenuItem terminal = new MenuItem(tr("menu.openTerminal"));
        terminal.setGraphic(Icons.terminal());
        terminal.setOnAction(e -> openTerminalAt(buffer.getPath(), false, isLocalBuffer(buffer)));

        ContextMenu menu = new ContextMenu(
                save,
                saveAs,
                new SeparatorMenuItem(),
                close,
                closeOthers,
                closeAll,
                closeUnmodified,
                new SeparatorMenuItem(),
                closeLeft,
                closeRight,
                new SeparatorMenuItem(),
                gitMenu,
                compareWith,
                openPatch,
                new SeparatorMenuItem(),
                reveal,
                terminal,
                copyPath,
                pin,
                rename);
        menu.setOnShowing(e -> {
            closeLeft.setDisable(eligibleToLeft(tab).isEmpty());
            closeRight.setDisable(eligibleToRight(tab).isEmpty());
            boolean hasPath = buffer.getPath() != null;
            // Reveal/terminal only make sense for a saved, local file.
            boolean localPath = hasPath && isLocalBuffer(buffer);
            reveal.setDisable(!localPath);
            terminal.setDisable(!localPath);
            copyPath.setDisable(!hasPath);
            rename.setDisable(!hasPath);
            compareWith.setDisable(!hasPath); // not a Git action — works on any two files
            // Only shown for a .patch/.diff file — parses the buffer's own (possibly unsaved) text.
            openPatch.setVisible(hasPath
                    && PatchFiles.isPatchFile(buffer.getPath().getFileName().toString()));
            // The Git submenu is only shown for a saved file (an untitled buffer can't be in a repo) and is
            // greyed out when there's no VCS (Git off / not inside a repo) — mirroring the Project tree.
            gitMenu.setVisible(hasPath);
            gitMenu.setDisable(!git.isAvailable());
            com.editora.git.GitFileStatus st = git.statusFor(buffer.getPath());
            revert.setDisable(st == null); // nothing to revert on a clean/untracked-clean file
            ignore.setDisable(st != com.editora.git.GitFileStatus.UNTRACKED); // ignore = for new (untracked) files
            // Save is a no-op for an unchanged, on-disk file; untitled/dirty buffers can always save.
            save.setDisable(hasPath && !buffer.isDirty());
            pin.setText(pinned.contains(tab) ? "Unpin Tab" : "Pin Tab");
        });
        tab.setContextMenu(menu);
    }

    /**
     * @return true if {@code tab} may close — confirming first if it is pinned, then running the
     *         unsaved-changes check. Used by every single-tab close (the X, the command, the menu).
     */
    private boolean confirmClose(Tab tab) {
        EditorBuffer buffer = bufferOf(tab);
        if (buffer == null) {
            return true;
        }
        if (pinned.contains(tab) && !confirmClosePinned(buffer)) {
            return false;
        }
        return confirmCloseIfDirty(buffer);
    }

    /** @return true if the user confirms closing a pinned tab. */
    private boolean confirmClosePinned(EditorBuffer buffer) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.pinnedTab.title"));
        alert.setHeaderText(tr("dialog.pinnedTab.header", buffer.getTitle()));
        alert.setContentText(null);
        ButtonType close = new ButtonType(tr("dialog.close"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(close, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == close;
    }

    /** @return true if the tab is allowed to close (saved, discarded, or wasn't dirty). */
    private boolean confirmCloseIfDirty(EditorBuffer buffer) {
        if (!buffer.isDirty() && !fileWorkflows.hasPendingSave(buffer)) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.unsaved.title"));
        alert.setHeaderText(tr("dialog.unsaved.header", buffer.getTitle()));
        alert.setContentText(null);
        ButtonType save = new ButtonType(tr("dialog.save"));
        ButtonType discard = new ButtonType(tr("dialog.discard"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        if (result.get() == save) {
            return fileWorkflows.saveSynchronously(buffer);
        }
        return true; // discard
    }

    private Tab tabFor(EditorBuffer buffer) {
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
            return null;
        }
        var area = buffer.getArea();
        int length = area.getLength();
        int end = Math.min(length, ProjectMapPreview.MAX_PREVIEW_CHARS);
        return new ProjectMapPreview.Content(area.getText(0, end), end < length);
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

    private Tab tabForPath(Path file) {
        String target = pathKey(file);
        for (Tab tab : editorArea.tabs()) {
            Path p = tabPath(tab); // buffer path, else an image-viewer tab's path
            if (p != null && pathKey(p).equals(target)) {
                return tab;
            }
        }
        return null;
    }

    /** The file backing {@code tab} (a buffer / image viewer / hex viewer path), or {@code null} for none. */
    private static Path tabPath(Tab tab) {
        EditorBuffer buffer = bufferOf(tab);
        if (buffer != null) {
            return buffer.getPath();
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
    private void onQuit() {
        if (!confirmQuit()) {
            return;
        }
        boolean ok = windowManager != null ? windowManager.confirmCloseAllWindows() : confirmCloseAllBuffers();
        if (ok) {
            Platform.exit();
        }
    }

    /** @return true if the user confirms quitting the app. */
    private boolean confirmQuit() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.quit.title"));
        alert.setHeaderText(tr("dialog.quit.header"));
        alert.setContentText(null);
        ButtonType quit = new ButtonType(tr("dialog.quit.button"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(quit, cancel);
        styleQuitButtonAsDanger(alert, quit);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == quit;
    }

    static void styleQuitButtonAsDanger(Alert alert, ButtonType quit) {
        alert.getDialogPane().lookupButton(quit).getStyleClass().add("danger");
    }

    /** Walks every tab and prompts to save/discard each dirty buffer, then persists this window's session.
     *  False = the user cancelled. Package-visible: the quit path drives it for every window. */
    boolean confirmCloseAllBuffers() {
        for (Tab tab : new ArrayList<>(editorArea.tabs())) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer == null || !buffer.isDirty() && !fileWorkflows.hasPendingSave(buffer)) {
                continue;
            }
            editorArea.select(tab);
            if (!confirmCloseIfDirty(buffer)) {
                return false;
            }
        }
        sessions.persistSession();
        return true;
    }

    /** Records the open files (in tab order) and their carets so the next launch can restore them. */
    private boolean configSavePending;

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
    private void requestSave() {
        if (configSavePending || sessionClosed) {
            return;
        }
        configSavePending = true;
        Platform.runLater(() -> {
            configSavePending = false;
            if (!sessionClosed) {
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

    private void nextBuffer() {
        int count = editorArea.size();
        if (count > 1) {
            int idx = (editorArea.selectedIndex() + 1) % count;
            editorArea.select(idx);
        }
    }

    // --- Edit actions (delegate to active CodeArea) ---

    // --- Markdown image paste / drop -------------------------------------------------------------

    /** Shows the find/replace bar, or hides it if it's already open. */
    /** C-s: show the find bar, or — if already showing — cycle to the next match. */
    private void findShowOrNext() {
        if (findBar.isShown()) {
            findBar.findNext();
        } else {
            findBar.show(false);
        }
    }

    /** C-r: show the find bar (reverse), or — if already showing — cycle to the previous match. */
    private void findShowOrPrevious() {
        if (findBar.isShown()) {
            findBar.findPrevious();
        } else {
            findBar.show(true);
        }
    }

    /** Replace: ensure the find/replace bar is showing and focus the replace field. (C-g closes it.) */
    private void showReplace() {
        if (!findBar.isShown()) {
            findBar.show(false);
        }
        findBar.focusReplace();
    }

    @FXML
    private void onFindInFiles() {
        searchCoordinator.openToggle();
    }

    /** Shows the Run tool window's stripe button only for a runnable file (the in-editor affordance is
     *  the green gutter Run glyph on the entry line + the right-click menu). */
    private void updateRunButton() {
        EditorBuffer buffer = activeBuffer();
        boolean http = buffer != null && buffer.isHttpFile();
        boolean runnable = buffer != null && buffer.isRunnable() && !http;
        if (runToolWindow != null) {
            toolWindows.setAvailable(runToolWindow, runnable);
        }
        if (http && httpClient.isEnabled()) {
            httpClient.refreshEnvironments(buffer); // the response preview's environment picker
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
    private void updateBufferToolWindows() {
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
        if (todoToolWindow != null) {
            toolWindows.setAvailable(todoToolWindow, buffer);
        }
        if (markdownLintToolWindow != null) {
            toolWindows.setAvailable(
                    markdownLintToolWindow, buffer && b.isMarkdown() && previews.markdownLintEnabled());
        }
        if (externalToolToolWindow != null) {
            toolWindows.setAvailable(externalToolToolWindow, buffer && externalToolsEnabled());
        }
        // Plugin tool windows act on the active editor — gate them on an open buffer too. Null-guarded: the
        // first setupToolWindows() pass runs before pluginCoordinator is built (it needs toolWindows), and no
        // plugin tool windows exist until applyPlugins() registers them, so an early skip is harmless.
        if (pluginCoordinator != null) {
            pluginCoordinator.gateToolWindows(buffer);
        }
        // Git Commit / Git Log act on the active file's repo — hide on a non-buffer tab (e.g. Welcome).
        // When a buffer IS open, leave them to the Git coordinator's in-repo gating (don't force-show).
        if (!buffer) {
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
    private String programArgsFor(Path path) {
        String s = config.getWorkspaceState().getProgramArgs().get(path.toString());
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
    private void onPalette() {
        if (config.getSettings().isPaletteUsesSearchEverywhere() && chrome.searchEverywherePopup != null) {
            chrome.searchEverywherePopup.show("");
            return;
        }
        palette.show();
    }

    @FXML
    private void onSettings() {
        settingsWindow.show(stage);
    }

    @FXML
    private void onToggleSimpleMode() {
        chrome.toggleSimpleMode();
    }

    private void onAbout() {
        SettingsWindow.showAbout(
                stage,
                config.getSettingsFile(),
                fileWorkflows::openPath,
                this::openExternalUrl,
                config.isDev() ? com.editora.AppInfo.gitCommit() : "", // build commit shown only in --dev
                latestKnownUpdate); // "Update available" row when a newer release is known
    }

    /**
     * Expands the active tool window over its split, or hands the space back if it already holds it.
     *
     * <p>Session-only and deliberately unpersisted: maximizing is a "let me read this for a moment" gesture,
     * and a window that reopened next launch covering the editor would read as a broken layout.
     */
    /**
     * Picks a tool window to open <em>beside</em> whatever holds its side, splitting that side in two.
     *
     * <p>The picker lists only what could actually join a side right now, so an empty list is the honest
     * answer to "nothing is open to split with" rather than a list of choices that would silently replace.
     */
    private void showSplitToolWindowPalette() {
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
    private void toggleFloatingToolWindow() {
        ToolWindow tw = toolWindows.maximizeTarget();
        if (tw == null) {
            setStatus(tr("status.toolwindow.noMaximizeTarget"));
            return;
        }
        toolWindows.toggleFloating(tw);
        setStatus(tr(
                toolWindows.isFloating(tw) ? "status.toolwindow.floated" : "status.toolwindow.docked", tw.getTitle()));
    }

    private void toggleMaximizedToolWindow() {
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

    private void toggleToolStripe() {
        Settings s = config.getSettings();
        s.setShowToolStripe(!s.isShowToolStripe());
        requestSave();
        editorSettings.applyViewSettingsToAllBuffers(s); // → applyChromeVisibility → toolWindows.setStripesEnabled
        settingsWindow.syncToolStripeCheck();
        setStatus(tr("status.toggle.toolStripe", tr(s.isShowToolStripe() ? "common.on" : "common.off")));
    }

    // --- Multiple cursors / column selection commands (delegate to the active buffer's fork add-on) ---

    /** Runs {@code action} on the active buffer when multi-caret is enabled; else reports it. */
    private void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
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
    private void selectAllOccurrences() {
        withMultiCaret(b -> {
            int n = b.selectAllOccurrences();
            setStatus(n == 0 ? tr("status.occurrences.none") : tr("status.occurrences.selected", n));
        });
    }

    /** VS Code {@code selectAllMatches} (Alt+Enter in the find bar): a caret at every find-bar match. */
    private void selectAllFindMatches() {
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

    /**
     * The main class a new run configuration should start from: the one the active Java file's project
     * declares, else the first {@code main} in that file. Null when there is nothing to suggest.
     *
     * <p>Shared by {@code run.saveConfig} and Run Configurations → Add, so the two cannot disagree
     * about what "the obvious main class here" is.
     */
    String suggestedMainClass() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            return null;
        }
        // Prefer the main class a Gradle `application` block declares — that's what `gradle run` would launch,
        // so a config saved in such a project matches the build rather than whichever file happens to be open.
        String declared =
                com.editora.build.GradleApplication.mainClass(readGradleBuildFile(JavaProjectRoot.find(b.getPath())));
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
     * <p>Not {@link #activeProjectRoot()}, which reads the {@code ProjectManager}'s active project — that is
     * the last-focused window's, so an unfocused window would gate its toolbar on somebody else's project.
     * Every coordinator's {@code Ops.projectRoot()} reads {@code windowProject} for the same reason.
     */
    private Path windowProjectRoot() {
        return (windowProject != null && projectsEnabled()) ? Path.of(windowProject.root()) : null;
    }

    /** This window's project root, or null when it has no project open. */
    private Path activeProjectRoot() {
        Project active = projects == null ? null : projects.active();
        return active == null ? null : Path.of(active.root());
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
        requestSave();
        setStatus(tr("status.toggle.expert", tr(on ? "common.on" : "common.off")));
    }

    // --- Settings palette commands ----------------------------------------------------------------
    // Every Settings-window control has a command-palette equivalent (Editora is command-driven). The
    // helpers below flip/prompt the same Settings field a control writes, then persist, re-apply the
    // feature, keep an open Settings window in step (syncAll), and echo a status. Two generic status
    // keys (status.settingToggled / status.settingChanged) reuse each command's own localized title.

    /** Persists the buffer's collapsed fold regions + manual fold ranges, keyed by its file path. */
    private void persistFolds(EditorBuffer buffer) {
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
        List<Integer> manual =
                com.editora.editor.ManualFolds.toFlat(buffer.getFoldManager().manualRegions());
        var manualMap = config.getWorkspaceState().getManualFoldRegions();
        if (manual.isEmpty()) {
            manualMap.remove(file.toString());
        } else {
            manualMap.put(file.toString(), manual);
        }
        requestSave();
    }

    /** Re-applies a file's saved manual fold ranges + collapsed fold regions after it is opened. */
    private void restoreFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> manual = config.getWorkspaceState().getManualFoldRegions().get(file.toString());
        List<Integer> saved = config.getWorkspaceState().getFoldedRegions().get(file.toString());
        buffer.getFoldManager()
                .restore(manual == null ? List.of() : com.editora.editor.ManualFolds.fromFlat(manual), saved);
    }

    /**
     * Toggles the active buffer's read-only ("View") mode, persists it, and refreshes the indicators.
     * Huge files are already read-only by necessity (truncated load) and can't be made editable.
     */
    private void toggleReadOnly() {
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
        setStatus(buffer.isViewMode() ? tr("status.viewMode") : tr("status.editable"));
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
            refreshEditState(); // editability change affects cut enablement
            refreshPasteState(); // ...and paste enablement
        }
    }

    /** The tab hosting {@code buffer}, or null if not open. */
    private Tab tabForBuffer(EditorBuffer buffer) {
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
        if (shouldOpenReadOnly(persisted, Files.isWritable(file)) || logDefault) {
            buffer.setViewMode(true);
        }
    }

    /** Whether a file should open in View mode: pinned read-only, or not writable on disk. Pure. */
    public static boolean shouldOpenReadOnly(boolean persisted, boolean writable) {
        return persisted || !writable;
    }

    /**
     * The directory passed to {@code typst compile --root} for a local {@code .typ} file: the nearest
     * {@code typst.toml} ancestor (typst's own project marker), else the active Editora project root when the
     * file lives inside it, else the file's own folder. Lets a multi-file Typst project resolve
     * {@code #import}/{@code #image} references above the file's folder; a single-file doc is unaffected.
     * Injected into {@link EditorBuffer#setTypstRootResolver} (preview) and {@link TypstCoordinator}
     * (export/print).
     */
    private java.nio.file.Path resolveTypstRoot(java.nio.file.Path file) {
        if (file == null) {
            return null;
        }
        java.nio.file.Path marker = com.editora.lsp.RootResolver.findMarkerRoot(file, java.util.List.of("typst.toml"));
        if (marker != null) {
            return marker;
        }
        Project active = (projects != null && config.getSettings().isProjectSupport()) ? projects.active() : null;
        java.nio.file.Path projectRoot = active == null ? null : Path.of(active.root());
        return com.editora.lsp.RootResolver.resolve(projectRoot, file, java.util.List.of());
    }

    private String projectMapBaseName() {
        Path projectRoot = projectPanel == null ? null : projectPanel.getRoot();
        Path name = projectRoot == null ? null : projectRoot.getFileName();
        return (name == null ? "project" : name.toString()) + "-map";
    }

    /** Whether the app (AtlantaFX) theme is dark — seeds/decides the "follow app" preview theme + glyph. */
    private boolean appThemeDark() {
        return Themes.backgroundFor(config.getSettings().getTheme()).getBrightness() < 0.5;
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
        statusBar.refresh();
        setStatus(tr("status.textZoom", Math.round(z * 100)));
    }

    /** Opens the snippet picker for the active buffer's language (plus global snippets). */
    private void insertSnippetPicker() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        if (snippets.forLanguage(b.getLanguage()).isEmpty()) {
            setStatus(tr("status.noSnippets"));
            return;
        }
        navigation.snippetPalette.show(stage);
    }

    /** Opens (creating from a template if needed) the user snippet file for the active language. */
    private void editUserSnippets() {
        EditorBuffer b = activeBuffer();
        String lang = b == null ? "global" : b.getLanguage();
        Path file = snippets.userFile(lang);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, USER_SNIPPET_TEMPLATE);
            }
            fileWorkflows.openPath(file);
            setStatus(tr("status.editingSnippets", lang));
        } catch (IOException e) {
            setStatus(tr("status.snippetOpenFailed", e.getMessage()));
        }
    }

    private static final String USER_SNIPPET_TEMPLATE = """
            {
              "Example": {
                "prefix": "ex",
                "body": ["// ${1:summary}", "$0"],
                "description": "Example snippet — edit or add your own, then reload"
              }
            }
            """;

    // --- New file of a known type ("New ▸ …") -----------------------------------------------------

    // --- File templates --------------------------------------------------------------------------

    /**
     * {@code project.editSettings}: opens this project's committed {@code .editora/settings.json}, seeding an
     * explanatory example when it doesn't exist yet.
     *
     * <p>Seeded rather than created empty because the file's whole value is being discoverable and editable
     * by hand — an empty buffer tells nobody which keys exist.
     */
    private void editProjectSettings() {
        Path root = activeProjectRoot();
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
    private static String noteKey(EditorBuffer buffer) {
        return com.editora.config.PathKeys.canonicalKey(buffer.getPath());
    }

    private Tab tabForKey(String fileKey) {
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
    void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        Label promptLabel = new Label(label);
        TextField field = new TextField(initial == null ? "" : initial);
        field.setPrefColumnCount(32);
        // Honor the user's configured keybindings (Emacs caret movement + basic editing) in the field.
        com.editora.command.TextInputKeymap.install(field, keymap);
        VBox body = new VBox(6, promptLabel, field);
        OverlayInput.show(
                overlayHost,
                title,
                body,
                field,
                tr("dialog.ok"),
                null,
                () -> onAccept.accept(field.getText()),
                null,
                false);
    }

    /** Opens the Debug Log window (captured java.util.logging output + uncaught exceptions). Backs the
     *  {@code view.debugLog} command and the Settings → Advanced "Show Debug Log" button. */
    private void showDebugLog() {
        debugLogWindow.show(stage);
    }

    private void exportConfig() {
        try {
            java.nio.file.Path zip = config.exportConfig();
            setStatus(tr("status.config.exported", zip.toString()));
            Alert ok = new Alert(Alert.AlertType.INFORMATION);
            ok.initOwner(stage);
            ok.setTitle(tr("dialog.exportConfig.title"));
            ok.setHeaderText(tr("dialog.exportConfig.done"));
            ok.setContentText(zip.toString());
            ok.showAndWait();
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            setStatus(tr("status.config.exportFailed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(stage);
            err.setTitle(tr("dialog.exportConfig.title"));
            err.setHeaderText(tr("dialog.exportConfig.failed"));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /** Re-runs the spell pass over every open buffer in this window (after the user dictionary changed). */
    void refreshSpellAllTabs() {
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.refreshSpell();
            }
        }
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

    private void cancel() {
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
        } else {
            editing.markActive = false;
            CodeArea area = activeArea();
            if (area != null) {
                area.deselect();
            }
        }
        setStatus("");
    }

    /** The selection policy for caret-movement commands: extend from the mark when it's active. */
    private SelectionPolicy selPolicy() {
        return editing.markActive ? SelectionPolicy.ADJUST : SelectionPolicy.CLEAR;
    }

    private static Path pathOf(java.io.File file) {
        return file == null ? null : file.toPath();
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
    private void refreshSavedMacroCommandsAllWindows() {
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
