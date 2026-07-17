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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
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
import javafx.util.Duration;

import com.editora.build.BuildTool;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.RecentFiles;
import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;
import com.editora.editor.SpellDictionaries;
import com.editora.editor.TabContent;
import com.editora.editor.TextNav;
import com.editora.markdown.MarkdownTable;
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

    @FXML
    private TabPane tabPane;

    @FXML
    private VBox topBox;

    @FXML
    private VBox bottomBox;

    @FXML
    private ToolBar toolBar;

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
    private Button aboutButton;

    @FXML
    private Button quitButton;

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

    /** Back/forward jump list of visited editor locations. */
    private final NavigationHistory navHistory = new NavigationHistory();
    /** True while a back/forward jump is executing — suppresses re-recording the jump into history. */
    private boolean navigating;
    /** True while {@code openAndGoto} drives {@code gotoInFile} — so only the outer call records the jump. */
    private boolean suppressNavRecord;
    /** Shared in-scene overlay host for the command palette + pickers (replaces focus-stealing Popups). */
    private final OverlayHost overlayHost = new OverlayHost();

    /** Max grid the Markdown table-size picker offers (rows × columns). */
    private static final int TABLE_PICKER_MAX_ROWS = 8;

    private static final int TABLE_PICKER_MAX_COLS = 8;

    private QuickOpen<Path> recentPalette;
    private QuickOpen<StructurePanel.Outline> structurePalette;
    private QuickOpen<Tab> openFilesPalette;
    private QuickOpen<ToolWindow> toolWindowPalette;
    private QuickOpen<com.editora.editor.UndoHistory.Checkpoint> undoHistoryPalette;
    private QuickOpen<com.editora.snippet.Snippet> snippetPalette;
    private com.editora.snippet.SnippetManager snippets;
    private com.editora.template.TemplateRegistry templates;
    /** Shared across windows (owned by WindowManager); plugin classes load once, instances are per-window. */
    private com.editora.plugin.PluginManager pluginManager;
    /** Plugin support (discovery/apply/install + the per-window PluginContext); see {@link PluginCoordinator}. */
    private PluginCoordinator pluginCoordinator;

    private com.editora.completion.CompletionEngine completion;
    private FileFinder fileFinder;
    private FileFinder folderFinder;
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
    private Label projectToolbarLabel;
    private Region projectToolbarGap;
    /** Tracks the last-applied project-support state to detect off→on transitions (reveal the panel). */
    private boolean projectSupportApplied;

    // Auto save. Mode keys: "off" | "afterDelay" | "onFocusChange".
    static final String AUTOSAVE_OFF = "off";
    static final String AUTOSAVE_DELAY = "afterDelay";
    static final String AUTOSAVE_FOCUS = "onFocusChange";
    private final PauseTransition autoSaveIdleTimer = new PauseTransition(Duration.millis(1000));
    private final ExecutorService autoSaveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editora-autosave");
        t.setDaemon(true);
        return t;
    });
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
    private final com.editora.editor.MarkdownLintService markdownLintService =
            new com.editora.editor.MarkdownLintService();
    /** Session-only Simple-UI override from the {@code --simple} CLI flag; OR'd with the saved setting. */
    private boolean cliSimpleOverride;
    /** Session-only Zen override from the {@code --zen} CLI flag; OR'd with this window's saved Zen state. */
    private boolean cliZenOverride;
    /** Session-only Expert override from the {@code --expert} CLI flag (the {@code --zen} twin). */
    private boolean cliExpertOverride;
    /**
     * The open tool windows a {@code --zen}/{@code --expert} session override closed, as the persisted
     * {left, right, bottom} ids. Entering a focus mode calls {@link ToolWindowManager#closeAllOpen()}, and
     * {@code close()} persists "nothing open" — so without restoring these at quit, a session-only focus
     * mode would silently lose the user's docked tool windows on the next launch. {@code null} = no CLI
     * focus-mode override is in effect (including after an in-app toggle takes over from the flag).
     */
    private String[] cliFocusToolWindows;
    // --- Remote files (SFTP via MINA SSHD; off-thread connect/auth) — owned by RemoteCoordinator ---
    private RemoteCoordinator remoteCoordinator;
    // MCP server: a single app-wide loopback HTTP endpoint exposing live editor state + the command
    // registry to an LLM agent. Static so only the first window with the feature on starts it (the
    // setting is shared); that window's controller is the bridge. (Multi-window caveat: if the owner
    // window closes, the server stops until a settings re-apply re-arms it from another window.)
    private static com.editora.mcp.McpServer mcpServer;
    private static MainController mcpOwner;
    private final com.editora.pdf.PdfExportService pdfService = new com.editora.pdf.PdfExportService();
    private final com.editora.office.OfficeExportService officeService = new com.editora.office.OfficeExportService();
    private final com.editora.print.PrintService printService = new com.editora.print.PrintService();
    /** HTTP Client: the {@code .http} request runner + response tool window; see {@link HttpClientCoordinator}. */
    private ToolWindow httpToolWindow;
    /** True while we programmatically auto-show/hide the HTTP window, so the state listener ignores it. */
    private boolean httpAutoMutating;
    /** True while the session is being restored (tabs fill per pulse); HTTP auto-show is suppressed until it
     *  completes, then reconciled once — opening a tool window mid-restore mis-sizes it in the SplitPane. */
    private boolean restoringSession;
    /** A right-side tool window we displaced to auto-show HTTP, restored when we auto-hide it (null if none). */
    private ToolWindow httpDisplacedRight;
    /** {@code .http} buffers whose HTTP window the user closed manually — suppresses auto-show for them.
     *  Weak keys so a closed buffer drops out without manual cleanup. */
    private final java.util.Set<EditorBuffer> httpUserClosed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Buffers whose install banner the user dismissed this session (don't re-offer). Weak keys, like above. */
    private final java.util.Set<EditorBuffer> installDismissed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** LSP: manager (one server per workspace root). Diagnostics route to {@link #lspCoordinator} via the
     *  thin {@link #onLspDiagnostics} delegate — a method ref (not a direct field read) so it isn't an
     *  illegal forward reference to the later-declared coordinator; the field read defers to call time. */
    private final com.editora.lsp.LspManager lspManager =
            new com.editora.lsp.LspManager(this::onLspDiagnostics, this::onLspServerStatus);

    private ToolWindow problemsToolWindow;
    private ToolWindow referencesToolWindow;
    /** Run: streams a runnable file's output; see {@link RunCoordinator}. The tool window stays here. */
    private ToolWindow runToolWindow;
    /** External Tools: the feature coordinator owns the service/panel/commands (see {@link ExternalToolCoordinator}). */
    private ToolWindow externalToolToolWindow;
    /** Build tools (Maven/npm/…): one IntelliJ-style tasks-tree tool window per detected tool, whose stripe
     *  appears when the tool's marker file is found (see {@link BuildCoordinator}). */
    private final Map<BuildTool, ToolWindow> buildToolWindows = new java.util.EnumMap<>(BuildTool.class);
    /** The shared tabbed "Build Output" window — one tab per build tool that runs (Maven/npm/Cargo/Go/Gradle),
     *  fed by every {@link BuildCoordinator}; its stripe appears when any tool's marker is detected, and it
     *  auto-opens on a run. */
    private final BuildOutputPanel buildOutputPanel = new BuildOutputPanel();

    private ToolWindow buildOutputToolWindow;

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
    /** The path the Git Log is currently filtered to (file history), or null for the whole repo. */
    private Path gitLogFilter;
    /** Local File History: snapshots local files on save/auto-save/external reload (off-thread). */
    private HistoryCoordinator historyCoordinator;

    private ToolWindow fileHistoryToolWindow;
    /** IntelliJ-style branch dropdown (actions + Local/Remote branches), anchored to the status bar. */
    private final BranchPopup branchPopup = new BranchPopup();

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
    /** Floating "exit Zen" button overlaid top-right of the window; shown only while in Zen mode. */
    private Button zenExitButton;
    /** Floating "exit Expert" button overlaid top-right of the window; shown only while in Expert mode. */
    private Button expertExitButton;
    /** Floating "show toolbar" button overlaid top-left; shown only when the toolbar is hidden (not in Zen). */
    private Button toolbarRestoreButton;
    /** Emacs mark: when set (C-SPC), caret movement extends the selection from the mark. */
    private boolean markActive;
    /** Cycle state for {@code move-to-window-line-top-bottom} (M-r): center → top → bottom. */
    private int windowLineCycle = -1;
    /** Re-entrancy guard so the external-change prompt (which steals focus) doesn't re-trigger itself. */
    private boolean checkingExternalChanges;

    private RecentFiles recentFiles;
    /** Persistent Find-in-Files query history (backs the query combo's dropdown). */
    private com.editora.config.SearchHistory searchHistory;
    /** Persistent AI Agent chat-session history (backs the resume picker). */
    private com.editora.config.AgentSessionHistory agentSessionHistory;
    /** The VSCode-style Welcome page, shown in its own tab when no file is open (or via {@code view.welcome}). */
    private WelcomePane welcomePane;
    /** The single open Welcome tab (a non-buffer {@link TabContent} tab), or null when none is open. */
    private Tab welcomeTab;
    /** Opens external URLs (the Welcome page's home-page link) in the system browser; set from {@code App}. */
    private javafx.application.HostServices hostServices;

    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
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
                checkExternalChanges();
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
                MainController.this.openPath(file);
            }

            @Override
            public void navigateToLine(int line) {
                MainController.this.navigateToLine(line);
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
            public void saveNotes() {
                config.saveNotes();
            }
        });
        // Built here (not as a field initializer) because BookmarksPanel's constructor reads config.getBookmarks().
        this.bookmarkCoordinator = new BookmarkCoordinator(coordinatorHost, new BookmarkCoordinator.Ops() {
            @Override
            public void openPath(java.nio.file.Path file) {
                MainController.this.openPath(file);
            }

            @Override
            public void navigateToLine(int line) {
                MainController.this.navigateToLine(line);
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
            public void saveBookmarks() {
                config.saveBookmarks();
            }
        });
        // Record every executed command into an in-progress macro (the service no-ops unless recording).
        registry.setExecutionListener(macroCoordinator::onCommand);
        this.snippets = new com.editora.snippet.SnippetManager(config);
        this.templates = new com.editora.template.TemplateRegistry(config);
        this.completion = new com.editora.completion.CompletionEngine(snippets, config::getUserDictionary);
        // Project commands (incl. the Project tool window) are hidden from the palette unless project
        // support is enabled.
        // Project + Git commands are hidden from the palette unless their feature is enabled.
        // A command whose feature is off is hidden from the palette (see Chrome.paletteVisible, which is
        // pure + unit-tested); paletteGates() snapshots the live feature-enabled state per filter call.
        this.palette = new CommandPalette(registry, keymap, c -> Chrome.paletteVisible(c.id(), paletteGates()));
        this.findBar = new FindReplaceBar(this::activeBuffer, this::setStatus);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        this.breadcrumb = new FileBreadcrumb(this::openPath);
        // Breadcrumb sits just above the status bar at the bottom (IntelliJ-style).
        bottomBox.getChildren().setAll(breadcrumb, statusBar);
        setupToolWindows();
        this.settingsWindow = new SettingsWindow(
                config,
                toolWindows,
                git.service(),
                mermaid.service(),
                diagram.service(),
                typst.service(),
                buildCoordinators,
                lspManager,
                dapManager,
                this::onSettingsApplied,
                this::setZenMode,
                this::setExpertMode,
                this::openPath,
                this::exportConfig,
                this::showDebugLog);
        this.settingsWindow.setPluginManager(pluginManager); // shared; lists discovered plugins on the Plugins page
        this.pluginCoordinator = new PluginCoordinator(
                coordinatorHost,
                registry,
                keymap,
                snippets,
                templates,
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
        this.settingsWindow.setTemplateRegistry(templates); // backs the Settings → Templates management page
        this.settingsWindow.setMcpConfirm(this::confirmEnableMcp); // security notice before enabling MCP
        this.settingsWindow.setRipgrepProbe(
                searchCoordinator::probeRipgrep); // Settings → Search found/not-found status
        this.settingsWindow.setAiConnectionProbe(
                aiCoordinator::checkConnection); // Settings → AI Actions green/red connection status
        this.settingsWindow.setOnKeymapChanged(this::reloadKeymap); // picker/combo → live keymap switch
        this.settingsWindow.setShortcutActions(new SettingsWindow.ShortcutActions() {
            @Override
            public java.util.List<SettingsWindow.Shortcut> rows() {
                return shortcutRows();
            }

            @Override
            public java.util.List<com.editora.command.KeybindingEdits.Conflict> conflicts(
                    String chordSeq, String commandId) {
                return com.editora.command.KeybindingEdits.conflicts(keymap.bindings(), chordSeq, commandId);
            }

            @Override
            public void rebind(String commandId, String chordSeq) {
                rebindShortcut(commandId, chordSeq);
            }

            @Override
            public void reset(String commandId) {
                resetShortcut(commandId);
            }

            @Override
            public void resetAll() {
                resetAllShortcuts();
            }
        });
        this.settingsWindow.setMacrosChangedHandler(
                this::refreshSavedMacroCommandsAllWindows); // Macros page edits → re-register commands everywhere
        this.settingsWindow.setAgentCoordinator(agentCoordinator); // AI Agent page: per-client status + combo
        debugLogWindow.setSessionFile(DebugLog.sessionFile(config.getConfigDir()));
        this.switcher = new Switcher(
                () -> new java.util.ArrayList<>(tabPane.getTabs()), // list files in tab order
                () -> tabPane.getSelectionModel().getSelectedItem(),
                this::activateAndFocusTab,
                this::closeTabFromSwitcher);
        setupMruTracking();
        registerCommands();
        setupToolbar();
        setupRecentFiles();
        setupJumpPickers();
        setupProjects();
        pluginCoordinator
                .applyPlugins(); // register plugin commands/tool windows/hooks (before restore, so visibility restores)
        toolWindows.restore();
        // Honor a persisted Zen/Expert state on launch: the view options + chrome already read the flags via
        // the apply paths; this hides the side stripes (restore() opened nothing — windows were
        // persisted closed when a focus mode was entered).
        toolWindows.setZenStripesHidden(config.getWorkspaceState().isZenMode()
                || config.getWorkspaceState().isExpertMode());
        applyChromeVisibility();
        applyProjectSupport(); // hide project UI when disabled (default)
        git.applySupport(); // hide Git UI when disabled (default)
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
        applyMarkdownLint(); // push Markdown-lint enabled state to buffers (on by default)
        applyAdminSaveSupport(); // detect the elevation tool (pkexec/osascript) for save-as-admin (off by default)
        setupWelcome(); // Welcome empty-state shown when no file tabs are open

        // Auto save: idle timer fires a save; the window losing focus saves in onFocusChange mode.
        autoSaveIdleTimer.setOnFinished(e -> autoSaveAllDirty());
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && AUTOSAVE_FOCUS.equals(autoSaveMode())) {
                autoSaveAllDirty();
            }
        });
        applyAutoSave();
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
        return pluginManager != null && config.getSettings().isPluginSupport() && !simpleModeActive();
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
                MainController.this.openPath(file);
            }

            @Override
            public void showError(String summary, String detail) {
                git.gitError(summary, detail);
            }
        };
    }

    /** Shows/hides the toolbar and status bar per the saved settings (hidden nodes also unmanaged so
     *  they don't reserve layout space). Cheap: two visibility flags + one layout pass. */
    private void applyChromeVisibility() {
        Settings s = config.getSettings();
        // Zen and Simple are per-window effective overlays: they hide chrome without mutating the shared
        // saved prefs, so the prefs return untouched when the mode is left, and one window's Zen never
        // leaks into another (Zen lives in this window's WorkspaceState, not in Settings).
        boolean zen = zenActive();
        boolean expert = expertActive();
        boolean focus = zen || expert; // the two "focus modes" hide the same chrome, except status bar (below)
        boolean simple = simpleModeActive();
        // Effective visibility (Chrome, pure + unit-tested): a saved pref AND not hidden by a focus mode/Simple.
        boolean toolbarOn = Chrome.toolbar(s.isShowToolbar(), focus);
        toolBar.setVisible(toolbarOn);
        toolBar.setManaged(toolbarOn);
        // The status bar is hidden by Zen but KEPT by Expert, so it keys on the real zen flag, not focus.
        boolean statusOn = Chrome.statusBar(s.isShowStatusBar(), zen);
        statusBar.setVisible(statusOn);
        statusBar.setManaged(statusOn);
        // The tab header is collapsed via a style class (see app.css) rather than visible/managed:
        // the TabPane skin owns the header node, so toggling a CSS class is the supported way.
        tabPane.getStyleClass().remove("no-tab-header");
        if (!Chrome.tabBar(s.isShowTabBar(), focus)) {
            tabPane.getStyleClass().add("no-tab-header");
        }
        breadcrumb.setEnabled(Chrome.breadcrumb(s.isShowBreadcrumb(), focus, simple));
        // Tool stripes (UI only): hidden stripes still let tool windows open via keybinding/palette.
        toolWindows.setStripesEnabled(Chrome.toolStripes(s.isShowToolStripe(), focus, simple));
        applySimpleMode();
        updateZenButton();
        updateExpertButton();
        updateToolbarRestoreButton();
    }

    /** True when Simple UI mode is active (the saved setting OR the session-only {@code --simple} flag). */
    private boolean simpleModeActive() {
        return config.getSettings().isSimpleMode() || cliSimpleOverride;
    }

    /** True when this window is in distraction-free Zen mode — this window's saved state OR the session-only
     *  {@code --zen} flag (which, like {@code --simple}, never touches the saved session). */
    private boolean zenActive() {
        return config.getWorkspaceState().isZenMode() || cliZenOverride;
    }

    /** True when this window is in Expert mode — like Zen, but keeps line numbers + the status bar. */
    private boolean expertActive() {
        return config.getWorkspaceState().isExpertMode() || cliExpertOverride;
    }

    /** Snapshot of which optional features are effectively enabled, for {@link Chrome#paletteVisible}. */
    private Chrome.PaletteGates paletteGates() {
        Settings s = config.getSettings();
        return new Chrome.PaletteGates(
                s.isProjectSupport(),
                git.isEnabled(),
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
                logViewer.isEnabled());
    }

    /**
     * Simple UI mode: hide the marked toolbar groups + status-bar segments (line numbers/minimap are
     * handled via {@link #applyViewSettings}; the project trio via {@link #applyProjectSupport}). Inert
     * when Simple mode is off (everything returns to its normal, gate-respecting state).
     */
    private void applySimpleMode() {
        boolean simple = simpleModeActive();
        // Curated toolbar buttons hidden in Simple mode (project trio + openFolder are gated in
        // applyProjectSupport so its later pass doesn't re-show them). The Open icon is deliberately
        // KEPT so opening a file stays one click away in Simple mode.
        for (Button b : new Button[] {
            newFromTemplateButton, clearRecentButton, findInFilesButton, splitVerticalButton, splitHorizontalButton
        }) {
            b.setVisible(!simple);
            b.setManaged(!simple);
        }
        recentButton.setVisible(!simple);
        recentButton.setManaged(!simple);
        // Each build-tool button's visibility otherwise follows marker-file detection (BuildCoordinator), not
        // this unconditional show/hide — re-derive it from the cached detection now that isEnabled() (which
        // folds in !simpleModeActive()) may have changed, rather than forcing it shown.
        buildCoordinators.forEach(BuildCoordinator::reapplyVisibility);
        collapseToolbarSeparators();
        statusBar.setSimpleMode(simple);
    }

    /**
     * Hide toolbar {@link Separator}s that would be orphaned (leading, trailing, or with no visible
     * control between them and the previous separator), so hiding button groups leaves no stray dividers.
     * Self-correcting — when nothing is hidden, every separator is shown.
     */
    private void collapseToolbarSeparators() {
        Separator pending = null; // a separator with a visible item before it, awaiting one after
        boolean visibleSincePending = false;
        for (javafx.scene.Node item : toolBar.getItems()) {
            if (item instanceof Separator sep) {
                sep.setVisible(false);
                sep.setManaged(false);
                if (visibleSincePending) {
                    pending = sep;
                    visibleSincePending = false;
                }
            } else if (item.isVisible()) {
                if (pending != null) {
                    pending.setVisible(true);
                    pending.setManaged(true);
                    pending = null;
                }
                visibleSincePending = true;
            }
        }
    }

    /**
     * Installs the floating "exit Zen" button into the scene-root overlay (top-right of the window).
     * Called by {@code App} after the scene is built. Hidden until Zen mode is entered.
     */
    public void installZenOverlay(StackPane sceneRoot) {
        zenExitButton = new Button();
        zenExitButton.setGraphic(Icons.zen());
        zenExitButton.getStyleClass().addAll("zen-exit", "flat");
        zenExitButton.setTooltip(new Tooltip(tr("tooltip.zenExit")));
        zenExitButton.setFocusTraversable(false);
        zenExitButton.setOnAction(e -> setZenMode(false));
        StackPane.setAlignment(zenExitButton, Pos.TOP_RIGHT);
        sceneRoot.getChildren().add(zenExitButton);

        // Floating "exit Expert" button (top-right, an "E"): shown only in Expert mode. Mirrors the Zen "Z";
        // the two never coexist (the modes are mutually exclusive).
        expertExitButton = new Button();
        expertExitButton.setGraphic(Icons.expert());
        expertExitButton.getStyleClass().addAll("expert-exit", "flat");
        expertExitButton.setTooltip(new Tooltip(tr("tooltip.expertExit")));
        expertExitButton.setFocusTraversable(false);
        expertExitButton.setOnAction(e -> setExpertMode(false));
        StackPane.setAlignment(expertExitButton, Pos.TOP_RIGHT);
        sceneRoot.getChildren().add(expertExitButton);

        // Floating "show toolbar" button (top-right): restores a hidden toolbar. Never coexists with the
        // Zen "Z" (that's shown only in Zen mode, this only when the toolbar is hidden outside Zen).
        toolbarRestoreButton = new Button();
        toolbarRestoreButton.setGraphic(Icons.tools());
        toolbarRestoreButton.getStyleClass().addAll("toolbar-restore", "flat");
        toolbarRestoreButton.setTooltip(new Tooltip(tr("tooltip.showToolbar")));
        toolbarRestoreButton.setFocusTraversable(false);
        toolbarRestoreButton.setOnAction(e -> toggleToolbar());
        StackPane.setAlignment(toolbarRestoreButton, Pos.TOP_RIGHT);
        StackPane.setMargin(toolbarRestoreButton, new javafx.geometry.Insets(8, 12, 0, 0));
        sceneRoot.getChildren().add(toolbarRestoreButton);

        // In-scene overlay host (replaces focus-stealing Popups): the command palette and pickers show
        // their card here so keyboard focus works on every platform. Installed last so it sits on top.
        overlayHost.install(sceneRoot);
        wireOverlayHost();

        updateZenButton();
        updateExpertButton();
        updateToolbarRestoreButton();
    }

    /**
     * Injects the shared {@link OverlayHost} into every keyboard picker/popup so they render their card
     * in the main scene (focus works on every platform) instead of a focus-stealing {@link javafx.stage.Popup}.
     * Called once from {@link #installZenOverlay}, after all the field pickers are built in {@link #init}.
     * On-demand pickers (LSP references, spell language) get the host at their construction sites.
     */
    private void wireOverlayHost() {
        palette.setOverlayHost(overlayHost);
        palette.setDocsOpener(this::openExternalUrl); // C-h → command docs in the system browser
        recentPalette.setOverlayHost(overlayHost);
        structurePalette.setOverlayHost(overlayHost);
        openFilesPalette.setOverlayHost(overlayHost);
        toolWindowPalette.setOverlayHost(overlayHost);
        undoHistoryPalette.setOverlayHost(overlayHost);
        bookmarkCoordinator.wireOverlayHost();
        notesCoordinator.wireOverlayHost();
        snippetPalette.setOverlayHost(overlayHost);
        projectPicker.setOverlayHost(overlayHost);
        fileFinder.setOverlayHost(overlayHost);
        folderFinder.setOverlayHost(overlayHost);
        switcher.setOverlayHost(overlayHost);
        branchPopup.setOverlayHost(overlayHost);
        statusBar.setOverlayHost(overlayHost);
        buildCoordinators.forEach(c -> c.setOverlayHost(overlayHost));
    }

    /**
     * Shows the floating "show toolbar" button only when the toolbar is hidden and we're not in Zen mode
     * (Zen hides the whole chrome and the "Z" already restores it). Cheap visibility toggle.
     */
    private void updateToolbarRestoreButton() {
        if (toolbarRestoreButton == null) {
            return;
        }
        boolean show = !config.getSettings().isShowToolbar()
                && !zenActive()
                && !expertActive(); // a focus mode hides the toolbar; its E/Z restores it
        toolbarRestoreButton.setVisible(show);
        toolbarRestoreButton.setManaged(show);
    }

    /**
     * Shows the floating exit button only while in Zen mode (so it never overlaps normal chrome). When
     * the active file is Markdown its floating preview controls also sit top-right, so the Z is dropped
     * below them to avoid overlapping.
     */
    private void updateZenButton() {
        if (zenExitButton == null) {
            return;
        }
        boolean zen = zenActive();
        zenExitButton.setVisible(zen);
        zenExitButton.setManaged(zen);
        EditorBuffer active = activeBuffer();
        boolean belowMarkdownControls = zen && active != null && active.hasPreview();
        double top = belowMarkdownControls ? 44 : 8; // clear the Markdown preview toggle when present
        StackPane.setMargin(zenExitButton, new javafx.geometry.Insets(top, 12, 0, 0));
    }

    /**
     * Shows the floating "exit Expert" ("E") button only while in Expert mode (mirrors {@link #updateZenButton};
     * the two modes are mutually exclusive, so the E and Z never show together). Dropped below the Markdown
     * preview controls when the active buffer has a preview, so they don't overlap.
     */
    private void updateExpertButton() {
        if (expertExitButton == null) {
            return;
        }
        boolean expert = expertActive();
        expertExitButton.setVisible(expert);
        expertExitButton.setManaged(expert);
        EditorBuffer active = activeBuffer();
        boolean belowMarkdownControls = expert && active != null && active.hasPreview();
        double top = belowMarkdownControls ? 44 : 8;
        StackPane.setMargin(expertExitButton, new javafx.geometry.Insets(top, 12, 0, 0));
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
        if (welcomeTab != null && tabPane.getTabs().contains(welcomeTab)) {
            tabPane.getSelectionModel().select(welcomeTab);
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
        applyViewSettingsToAllBuffers(settings);
        updateBufferToolWindows(); // a feature toggle (Markdown lint, external tools, …) may re-gate a window
        // The keymap may have switched (it's shared); refresh every chord hint so none stays frozen to the
        // old keymap. Cheap (~25 tooltips + one palette/welcome relabel) and only on a settings/keymap apply.
        refreshToolbarTooltips();
        if (palette != null) {
            palette.refreshBindings();
        }
        if (toolWindows != null) {
            toolWindows.refreshTooltips();
        }
        if (welcomePane != null) {
            welcomePane.refresh();
        }
        maybeOfferInstall(activeBuffer()); // the install-prompts toggle / a feature gate may have changed
        applyAdminSaveSupport(); // the admin-save toggle may have flipped
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
            applyViewSettingsToAllBuffers(settings);
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
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.dispose();
            } else {
                disposeViewerTab(tab); // an image/hex/PDF tab holds a thread + file handle + GPU texture too
            }
        }
        lspManager.shutdownAll(); // don't orphan this window's external language servers
        dapManager.stop(); // end any debug session
        git.shutdown();
        if (historyCoordinator != null) {
            historyCoordinator.shutdown();
        }
        searchCoordinator.shutdown();
        todoCoordinator.shutdown();
        markdownLintService.shutdown();
        mermaid.shutdown();
        diagram.shutdown();
        typst.shutdown();
        buildCoordinators.forEach(BuildCoordinator::shutdown);
        updateService.shutdown(); // stop the update-check worker
        htmlPreview.shutdown(); // stop the HTML-preview HTTP server + worker
        logViewer.shutdown(); // stop any log tail-follow poll thread
        stopMcpIfOwner(); // stop the MCP server if this window owns it
        agentCoordinator.shutdown(); // kill the ACP agent process tree
        aiCoordinator.shutdown(); // cancel any in-flight AI generation
        pdfService.shutdown();
        officeService.shutdown();
        printService.shutdown();
        runCoordinator.shutdown();
        if (installCoordinator != null) {
            installCoordinator.shutdown();
        }
        autoSaveExecutor.shutdownNow();
        diffCoordinator.shutdown(); // the diff-service worker thread
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
        if (tabPane.getTabs().isEmpty()) {
            addWelcomeTab();
        }
    }

    /** Builds the keyboard "Jump to…" pickers (recent files, structure) — command-palette-style popups. */
    private void setupJumpPickers() {
        recentPalette = new QuickOpen<>(
                "Jump to Recent File",
                "Type to filter recent files…",
                () -> List.copyOf(recentFiles.getList()),
                p -> p.getFileName() == null ? p.toString() : p.getFileName().toString(),
                p -> p.getParent() == null ? "" : p.getParent().toString(),
                this::openRecent);
        recentPalette.setItemIcon(p -> FileIcons.forFileName(
                p.getFileName() == null ? p.toString() : p.getFileName().toString()));
        structurePalette = new QuickOpen<>(
                "Jump to Structure",
                "Type to filter symbols…",
                () -> structurePanel.outline(),
                StructurePanel.Outline::label,
                StructurePanel.Outline::kind,
                entry -> navigateToLine(entry.line()));
        openFilesPalette = new QuickOpen<>(
                "Jump to Open File",
                "Type to filter open files…",
                this::openTabsForSwitcher,
                tab -> (isTabDirty(tab) ? "• " : "") + bufferTitle(tab), // dirty marker, like the tab
                tab -> bufferParentDir(tab),
                tab -> bufferTitle(tab), // search by the plain name (no "• " prefix)
                this::activateAndFocusTab);
        openFilesPalette.setItemStyleClass(
                tab -> isTabDirty(tab) ? "dirty-name" : null); // amber/italic, like a dirty tab
        openFilesPalette.setItemIcon(tab -> FileIcons.forFileName(bufferTitle(tab))); // file-type glyph
        toolWindowPalette = new QuickOpen<>(
                "Jump to Tool Window",
                "Type to filter tool windows…",
                () -> toolWindows.getRegisteredToolWindows().stream()
                        .filter(tw -> git.isEnabled() || !"tool.commit".equals(tw.getCommandId()))
                        .filter(tw -> projectsEnabled() || !"tool.project".equals(tw.getCommandId()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)),
                ToolWindow::getTitle,
                tw -> invertBindings().getOrDefault(tw.getCommandId(), ""),
                toolWindows::open);
        undoHistoryPalette = new QuickOpen<>(
                tr("toolwindow.undoHistory"),
                tr("undoHistory.popupPrompt"),
                this::undoHistoryCheckpoints,
                c -> c.linePreview().isEmpty() ? tr("undoHistory.blankLine") : c.linePreview(),
                MainController::undoCheckpointTime, // detail column = the capture time
                this::restoreUndoCheckpoint);
        snippetPalette = new QuickOpen<>(
                "Insert Snippet",
                "Type to filter snippets…",
                () -> {
                    EditorBuffer b = activeBuffer();
                    return new ArrayList<>(snippets.forLanguage(b == null ? "global" : b.getLanguage()));
                },
                s -> s.prefix() + " — " + s.name(),
                com.editora.snippet.Snippet::description,
                s -> {
                    EditorBuffer b = activeBuffer();
                    if (b != null) {
                        b.insertSnippet(s);
                    }
                });
        fileFinder = new FileFinder(this::finderStartDir, this::findFileChosen);
    }

    /** Start directory for the keyboard file finder: the active file's folder, else the home dir. */
    private Path finderStartDir() {
        EditorBuffer buffer = activeBuffer();
        Path path = buffer == null ? null : buffer.getPath();
        if (path != null && path.getParent() != null) {
            return path.getParent();
        }
        Project active = projects == null ? null : projects.active();
        if (active != null) {
            return Path.of(active.root());
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    /** Opens an existing file, or creates a new buffer for a not-yet-existing path (written on save). */
    private void findFileChosen(Path target) {
        if (Files.isRegularFile(target)) {
            openPath(target);
            return;
        }
        Tab existing = tabForPath(target);
        if (existing != null) {
            tabPane.getSelectionModel().select(existing);
            EditorBuffer existingBuffer = bufferOf(existing);
            if (existingBuffer != null) {
                existingBuffer.getArea().requestFocus();
            }
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(target);
        addBuffer(buffer);
        setStatus(tr("status.newFile", target.getFileName()));
    }

    private static String bufferTitle(Tab tab) {
        EditorBuffer b = bufferOf(tab);
        if (b != null) {
            return b.getTitle();
        }
        // Non-buffer tabs (the Welcome tab) carry their title in the TabContent (the tab text is empty
        // because the title lives in a draggable graphic header).
        return tab != null && tab.getUserData() instanceof com.editora.editor.TabContent tc ? tc.title() : "";
    }

    private static String bufferParentDir(Tab tab) {
        EditorBuffer b = bufferOf(tab);
        Path p = b == null ? null : b.getPath();
        return p == null || p.getParent() == null ? "" : p.getParent().toString();
    }

    /** Whether {@code tab}'s buffer has unsaved changes (used to mark dirty files in the pickers). */
    private static boolean isTabDirty(Tab tab) {
        EditorBuffer b = bufferOf(tab);
        return b != null && b.isDirty();
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
        folderFinder = new FileFinder(this::finderStartDir, this::openProjectRoot, true, "Open Project Folder");
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
        boolean showProjectGroup = on && !simpleModeActive();
        openFolderButton.setVisible(showProjectGroup);
        openFolderButton.setManaged(showProjectGroup);
        toolbarProjectCombo.setVisible(showProjectGroup);
        toolbarProjectCombo.setManaged(showProjectGroup);
        projectToolbarLabel.setVisible(showProjectGroup);
        projectToolbarLabel.setManaged(showProjectGroup);
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
            Path img = tabPath(tabPane.getSelectionModel().getSelectedItem());
            stage.setTitle(img != null ? homeCollapsed(img.toString()) + " — " + app : app);
            return;
        }
        Path p = b.getPath();
        String file = p != null ? homeCollapsed(p.toString()) : b.getDisplayName();
        // The OS title bar renders plain text only (no color/italic/weight, so the tab's amber-italic
        // dirty styling can't be mirrored here) — use a prominent leading "●" as the unsaved marker so
        // the modified state is visible even in Zen mode, where the tab strip is hidden.
        stage.setTitle((b.isDirty() ? "● " : "") + file + " — " + app);
    }

    /** Syncs editor/session state after the Project tree renames a file on disk (old → target). */
    private void onProjectFileRenamed(Path old, Path target) {
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
            for (Tab t : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(t);
                Path p = b == null ? null : b.getPath();
                if (p == null) {
                    continue;
                }
                Path pn = p.toAbsolutePath().normalize();
                if (pn.startsWith(oldNorm) && !pn.equals(oldNorm)) {
                    Path moved = target.resolve(oldNorm.relativize(pn));
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
        Tab tab = tabForPath(path);
        if (tab != null) {
            tabPane.getTabs().remove(tab); // file is gone; close without a save prompt
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
        NavigationHistory.Location origin = navigating ? null : captureCurrent();
        // Reveal the target if it's hidden inside a collapsed fold, so we don't scroll to a hidden line.
        if (buffer != null) {
            buffer.getFoldManager().unfoldContaining(line);
        }
        area.moveTo(line, 0);
        if (!navigating && buffer != null && buffer.getPath() != null) {
            recordJump(origin, new NavigationHistory.Location(buffer.getPath(), line, 0));
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

    /** Repopulates the recent-files menu from the persisted list (most-recent first). */
    private void rebuildRecentMenu() {
        recentButton.getItems().clear();
        if (recentFiles.getList().isEmpty()) {
            MenuItem empty = new MenuItem(tr("menu.noRecentFiles"));
            empty.setDisable(true);
            recentButton.getItems().add(empty);
            return;
        }
        for (Path path : recentFiles.getList()) {
            recentButton.getItems().add(recentMenuItem(path));
        }
    }

    /** A recent-file menu entry: filename label that opens the file, plus an inline ✕ icon to remove it. */
    private CustomMenuItem recentMenuItem(Path path) {
        Label name = new Label(path.getFileName().toString());
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
        HBox box = new HBox(8, name, spacer, removeBtn);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.setPrefWidth(220);

        CustomMenuItem item = new CustomMenuItem(box);
        item.setOnAction(e -> openRecent(path));
        Tooltip.install(box, new Tooltip(path.toString()));
        return item;
    }

    private void setupMruTracking() {
        // A mouse click in the editor area repositions the caret, which ends an Emacs mark session.
        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> deactivateMark());
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
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
            lspCoordinator.setProblemsActiveFile(activePath == null ? null : canonicalPath(activePath));
            todoCoordinator.setActiveFile(
                    activePath == null ? null : activePath.toAbsolutePath().normalize());
            updateWindowTitle(); // show the active file's name + path in the window title bar
            updateProjectFolderView(); // global window: retarget the tree at the new file's folder
            searchCoordinator.refreshScope(); // Find-in-Files "current folder" tracks the active file
            if (AUTOSAVE_FOCUS.equals(autoSaveMode())) {
                autoSaveAllDirty(); // saves the outgoing buffer (and any other dirty ones)
            }
            refreshSplitButtons();
            refreshEditState(); // save/undo/redo/cut/copy enablement for the new tab
            refreshPasteState(); // clipboard read off the keystroke path
            updateZenButton(); // re-position the Zen "Z" if the new file is/isn't Markdown
            updateExpertButton(); // and the Expert "E"
            checkExternalChanges(); // prompt if the file we just switched to changed on disk
            git.refresh(); // update branch/status + this file's gutter change bars
            refreshBuildTools(); // re-detect marker files for the newly active file/project
            lspCoordinator
                    .updateStatusBar(); // show/hide the "LSP: <server>" segment + Problems window for the new file
            debugCoordinator.updateDebugAvailability(); // Debug window only for a debuggable file / live session
            updateRunButton(); // show the Run button only for a compact source file
            updateBufferToolWindows(); // hide buffer-only tool windows when there's no actionable buffer
            csvCoordinator.refreshFor(activeBuffer()); // re-target the CSV grid at the new active buffer
            historyCoordinator.refresh(); // re-gate + reload the Local File History list for the new active file
            maybeOfferInstall(activeBuffer()); // offer to install this language's LSP/DAP if it's missing
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
            while (c.next()) {
                // A tab added in the background (e.g. session restore opening many at once) starts
                // rendering-inactive so it holds no minimap snapshot until it's first shown.
                if (c.wasAdded()) {
                    Tab selected = tabPane.getSelectionModel().getSelectedItem();
                    for (Tab added : c.getAddedSubList()) {
                        EditorBuffer b = bufferOf(added);
                        if (b != null && added != selected) {
                            b.setRenderingActive(false);
                        }
                    }
                }
                // A pin reorder removes+re-adds the same tab; skip cleanup so it isn't forgotten.
                if (c.wasRemoved() && !reordering) {
                    mru.removeAll(c.getRemoved());
                    pinned.removeAll(c.getRemoved());
                    // Release each closed buffer's daemon executor threads (markdown-preview +
                    // editor-highlighter); otherwise they accumulate one pair per opened file.
                    for (Tab removed : c.getRemoved()) {
                        EditorBuffer closed = bufferOf(removed);
                        if (closed != null) {
                            if (closed.getPath() != null && lspManager.isManaged(closed.getPath())) {
                                lspManager.closeDocument(closed.getPath());
                                lspCoordinator.clearDiagnostics(closed.getPath());
                            }
                            logViewer.onBufferClosed(closed); // cancel tail-follow + drop per-buffer state
                            csvCoordinator.onBufferClosed(closed); // drop the CSV grid's edit listener
                            closed.dispose();
                        } else {
                            // Tab.setOnClosed only fires for a click on the ✕ — never for a programmatic
                            // remove (Ctrl-W, Close All/Others/Left/Right, window close). See disposeViewerTab.
                            disposeViewerTab(removed);
                        }
                    }
                }
            }
        });
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
        tabPane.getSelectionModel().select(tab);
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
            if (tabPane.getTabs().contains(tab)) {
                ordered.add(tab);
            }
        }
        for (Tab tab : tabPane.getTabs()) {
            if (!ordered.contains(tab)) {
                ordered.add(tab);
            }
        }
        return ordered;
    }

    private void setupToolWindows() {
        toolWindows = new ToolWindowManager(workspace, tabPane, config, keymap);
        projectPanel = new ProjectPanel(
                this::openPath, this::onProjectFileRenamed, this::onProjectFileDeleted, this::isPathModified);
        projectPanel.setPrompt(this::promptText); // in-scene rename prompt
        // Lazy lambda: historyCoordinator is constructed later in this method, so defer the field read to call time.
        projectPanel.setOnBeforeDelete(
                file -> historyCoordinator.captureBeforeDelete(file)); // snapshot to Local History before delete
        projectPanel.setOnNewFromTemplate(this::newFromTemplate); // folder "New From Template…"
        projectPanel.setOnStatus(this::setStatus); // drag-move / multi-delete feedback in the status bar
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
                git.ifEnabled(() -> gitFileHistoryForPath(file));
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
            public void gitCompareWithRevision(Path file) {
                git.ifEnabled(() -> diffCoordinator.diffPathVsCommit(file));
            }

            @Override
            public void gitAnnotate(Path file) {
                git.ifEnabled(() -> {
                    openPath(file);
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
                    openPath(git.repoRoot().resolve(path));
                }
            }

            @Override
            public void stage(String path) {
                git.gitOp("Staged " + path, "add", "--", path);
            }

            @Override
            public void unstage(String path) {
                git.gitOp("Unstaged " + path, "reset", "-q", "HEAD", "--", path);
            }

            @Override
            public void discard(String path, boolean untracked) {
                git.discardChanges(path, untracked);
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
            public void diff(String path, boolean staged) {
                diffCoordinator.diffGitPanelFile(path, staged);
            }
        });
        gitPanel.setOnClone(git::cloneRepo);
        gitPanel.setOnGenerateCommitMessage(aiCoordinator::generateCommitMessage);
        commitToolWindow = new ToolWindow(
                "commit", tr("toolwindow.commit"), ToolWindow.Side.RIGHT, Icons::git, gitPanel, "tool.commit");
        gitLogPanel = new GitLogPanel(gitLogOps = gitLogActions());
        gitLogToolWindow = new ToolWindow(
                "gitLog", tr("toolwindow.gitLog"), ToolWindow.Side.BOTTOM, Icons::gitLog, gitLogPanel, "tool.gitLog");
        historyCoordinator = new HistoryCoordinator(coordinatorHost, diffCoordinator, historyOps());
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
                Icons::find,
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
                openPath(file);
                Platform.runLater(() -> gotoInFile(file, line, col));
            }

            @Override
            public void refresh() {
                runMarkdownLintScan();
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
        runToolWindow = new ToolWindow(
                "run", tr("toolwindow.run"), ToolWindow.Side.BOTTOM, Icons::run, runCoordinator.panel(), "tool.run");
        debugCoordinator = new DebugCoordinator(
                coordinatorHost, dapManager, lspManager, lspCoordinator, new DebugCoordinator.Ops() {
                    @Override
                    public void openToolWindow() {
                        toolWindows.open(debugToolWindow);
                    }

                    @Override
                    public void toggleToolWindow() {
                        toolWindows.toggle(debugToolWindow);
                    }

                    @Override
                    public void setToolWindowAvailable(boolean available) {
                        toolWindows.setAvailable(debugToolWindow, available);
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
                        return save(buffer);
                    }

                    @Override
                    public String programArgs(Path path) {
                        return programArgsFor(path);
                    }

                    @Override
                    public void openLink(com.editora.run.StackTraceLinks.Link link) {
                        openRunLink(link);
                    }

                    @Override
                    public void openPath(Path file) {
                        MainController.this.openPath(file);
                    }

                    @Override
                    public EditorBuffer bufferForPath(Path file) {
                        return bufferOf(tabForPath(file));
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
        httpToolWindow = new ToolWindow(
                "http",
                tr("toolwindow.http"),
                ToolWindow.Side.RIGHT,
                Icons::httpClient,
                httpClient.panel(),
                "tool.http");
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
            buildToolWindows.put(
                    tool,
                    new ToolWindow(
                            tool.id(),
                            tr("toolwindow." + tool.id()),
                            ToolWindow.Side.BOTTOM,
                            c.iconSupplier(),
                            c.tasksPanel(),
                            "tool." + tool.id()));
        }
        // A single shared "Build Output" console for every build tool (auto-opens on a run).
        buildOutputPanel.setOnLink(this::openRunLink);
        buildOutputToolWindow = new ToolWindow(
                "buildOutput",
                tr("toolwindow.buildOutput"),
                ToolWindow.Side.BOTTOM,
                Icons::terminal,
                buildOutputPanel,
                "tool.buildOutput");
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
                maybeOfferInstall(activeBuffer()); // re-evaluate the editor install banner after re-detection
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
        toolWindows.setAvailable(
                gitLogToolWindow, false); // shown only inside a repo (gated by GitCoordinator#applyState)
        toolWindows.register(fileHistoryToolWindow);
        toolWindows.setAvailable(fileHistoryToolWindow, false); // shown only for a local file with history on
        toolWindows.register(fileInfoToolWindow);
        toolWindows.register(undoHistoryToolWindow, false); // stripe off by default; reachable via the
        // undoHistory.jump popup, the tool.undoHistory command, or Settings → Tool Windows
        toolWindows.register(searchToolWindow);
        toolWindows.register(todoToolWindow);
        toolWindows.register(markdownLintToolWindow);
        toolWindows.register(problemsToolWindow);
        toolWindows.register(referencesToolWindow, false); // default-hidden; shown on demand by Find References
        toolWindows.register(runToolWindow);
        toolWindows.setAvailable(runToolWindow, false); // shown only when the active file is a compact source
        toolWindows.register(debugToolWindow);
        toolWindows.setAvailable(debugToolWindow, false); // shown only while debugging is enabled
        toolWindows.register(httpToolWindow);
        toolWindows.setAvailable(httpToolWindow, false); // shown only for a .http file with the feature on
        toolWindows.register(externalToolToolWindow, false); // stripe off by default; reachable via the
        // tool.externalTools command / externalTool.run picker / Settings → Tool Windows
        for (ToolWindow tw : buildToolWindows.values()) {
            toolWindows.register(tw, true); // tasks-tree stripe visible by preference…
            toolWindows.setAvailable(tw, false); // …but hidden until the tool's marker file is detected
        }
        toolWindows.register(buildOutputToolWindow, false); // shared console stripe off by default; auto-opens on run
        toolWindows.setAvailable(buildOutputToolWindow, false); // …available once any build tool is detected
        toolWindows.register(remoteToolWindow, false); // off by default (niche); always available — no buffer needed
        toolWindows.register(
                agentToolWindow, false); // stripe off by default; shown via tool.agent / Settings → AI Agent
        toolWindows.setAvailable(agentToolWindow, agentCoordinator.isEnabled());
        updateBufferToolWindows(); // hide buffer-only windows until there's an actionable buffer (no Welcome flash)
        // Detect a *user* open/close of the HTTP window (vs. our own auto show/hide, guarded by
        // httpAutoMutating) so a manual close is remembered per .http buffer and a manual open clears it.
        toolWindows.setStateListener((tw, opened) -> {
            if (tw == gitLogToolWindow) {
                // Refresh the log whenever the window is opened — via the stripe button (which toggles
                // directly, bypassing showGitLog) or a command. open() only fires this on a real open.
                if (opened && git.isEnabled()) {
                    loadGitLog(gitLogFilter);
                }
                return;
            }
            if (tw != httpToolWindow || httpAutoMutating) {
                return;
            }
            EditorBuffer b = activeBuffer();
            if (b == null || !b.isHttpFile()) {
                return;
            }
            if (opened) {
                httpUserClosed.remove(b); // a manual open re-enables auto-show for this buffer
            } else {
                httpUserClosed.add(b); // a manual close suppresses auto-show until the user reopens it
            }
        });
    }

    /** Home-collapses an absolute folder path for a scope label (e.g. {@code ~/proj}). */
    private static String homeCollapsed(String full) {
        String home = System.getProperty("user.home", "");
        return !home.isEmpty() && (full.equals(home) || full.startsWith(home + java.io.File.separator))
                ? "~" + full.substring(home.length())
                : full;
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

    /** The file/dir used to locate the repo: the active file, else the open project's root, else null. */
    /** Effective Local File History gate: the setting, but off in Simple UI mode (saved setting unchanged). */
    private boolean localHistoryEnabled() {
        return config.getSettings().isLocalHistory() && !simpleModeActive();
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
                MainController.this.openPath(file);
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
                MainController.this.openPath(file);
            }

            @Override
            public void refreshProjectTree() {
                projectPanel.refreshTree();
            }

            @Override
            public String currentTextOf(java.nio.file.Path file) {
                return MainController.this.currentTextOf(file);
            }
        };
    }

    /** Reconciles LaTeX math rendering with its setting + the app theme; re-renders open previews. */
    private void applyMathSupport() {
        com.editora.editor.MathImages.configure(config.getSettings().isMathSupport(), appThemeDark());
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.hasPreview()) {
                b.refreshPreview();
            }
        }
    }

    // --- Feature coordinators (each peeled off MainController; share one CoordinatorHost adapter) -----

    /** One shared adapter handed to every feature coordinator (replaces a per-feature anonymous Host). */
    private final CoordinatorHost coordinatorHost = new Services();

    /** Implements {@link CoordinatorHost} by delegating to this controller's private helpers. */
    private final class Services implements CoordinatorHost {
        @Override
        public Settings settings() {
            return config.getSettings();
        }

        @Override
        public boolean simpleModeActive() {
            return MainController.this.simpleModeActive();
        }

        @Override
        public boolean appThemeDark() {
            return MainController.this.appThemeDark();
        }

        @Override
        public void forEachBuffer(java.util.function.Consumer<EditorBuffer> action) {
            for (Tab tab : tabPane.getTabs()) {
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
        public long fileSize(Path file) {
            return MainController.this.fileSize(file);
        }

        @Override
        public String bufferBaseName(EditorBuffer buffer) {
            return MainController.this.bufferBaseName(buffer);
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
        public void applyAutocomplete() {
            MainController.this.applyAutocomplete();
        }

        @Override
        public void ensurePreviewControls(EditorBuffer buffer) {
            MainController.this.ensurePreviewControls(buffer);
        }

        @Override
        public void restoreMarkdownMode(EditorBuffer buffer) {
            MainController.this.restoreMarkdownMode(buffer);
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
        public OverlayHost overlayHost() {
            return overlayHost;
        }

        @Override
        public javafx.stage.Window window() {
            return stage;
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
        }

        @Override
        public void setGitLogWindowAvailable(boolean available) {
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
        public void checkExternalChanges() {
            MainController.this.checkExternalChanges();
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
        public void openPath(Path file) {
            MainController.this.openPath(file);
        }

        @Override
        public void syncBlameCheck() {
            settingsWindow.syncGitBlameCheck();
        }

        @Override
        public void openCommitFileDiff(String hash, String repoRel) {
            diffCoordinator.diffCommitFile(hash, repoRel);
        }
    });

    /** The diff + merge-conflict viewer (open/refresh diffs, apply-change, compare entry points, patch
     *  export, merge resolution); see {@link DiffCoordinator}. Git-backed diffs reach the repo via {@code git}. */
    private final DiffCoordinator diffCoordinator =
            new DiffCoordinator(coordinatorHost, git, new DiffCoordinator.Ops() {
                @Override
                public void addDiffTab(TabContent pane) {
                    addContentTab(pane, true);
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
                    return save(buffer);
                }

                @Override
                public java.util.List<DiffViewerPane> openDiffPanes() {
                    java.util.List<DiffViewerPane> out = new java.util.ArrayList<>();
                    for (Tab tab : tabPane.getTabs()) {
                        if (tab.getUserData() instanceof DiffViewerPane dp) {
                            out.add(dp);
                        }
                    }
                    return out;
                }

                @Override
                public DiffViewerPane activeDiffPane() {
                    Tab t = tabPane.getSelectionModel().getSelectedItem();
                    return t != null && t.getUserData() instanceof DiffViewerPane dp ? dp : null;
                }

                @Override
                public Path finderStartDir() {
                    return MainController.this.finderStartDir();
                }

                @Override
                public String editorConfigCharset(Path file) {
                    return editorConfigCharsetFor(file);
                }
            });

    // --- Mermaid (mmdc render/export + maid lint) ----------------------------------------------------

    /** The Mermaid feature (diagram render/export, live lint, autocomplete gating); see {@link MermaidCoordinator}. */
    private final MermaidCoordinator mermaid = new MermaidCoordinator(coordinatorHost);

    /** The diagram-as-code feature (Graphviz DOT + PlantUML preview/export); see {@link DiagramCoordinator}. */
    private final DiagramCoordinator diagram = new DiagramCoordinator(coordinatorHost);

    /** The Typst document feature (multi-page rendered preview + export); see {@link TypstCoordinator}. */
    private final TypstCoordinator typst = new TypstCoordinator(coordinatorHost, this::resolveTypstRoot);

    // --- HTML Live Preview (serve via a loopback HttpServer + open in a detected browser) ---------

    /** The HTML Live Preview feature; see {@link HtmlPreviewCoordinator}. */
    private final HtmlPreviewCoordinator htmlPreview = new HtmlPreviewCoordinator(coordinatorHost);

    // --- Log viewer ----------------------------------------------------------------------------------

    /** The server-log-viewer feature (level highlighting, tail-follow, filtering); see {@link LogViewerCoordinator}. */
    private final LogViewerCoordinator logViewer = new LogViewerCoordinator(coordinatorHost);

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
                    openRunLink(link);
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
                            // The shared console is available when *any* build tool is currently detected.
                            if (buildOutputToolWindow != null) {
                                toolWindows.setAvailable(buildOutputToolWindow, anyBuildDetected());
                            }
                        }
                    },
                    buildOutputPanel));
        }
        return list;
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

    /** TODO / highlight-pattern feature; owns the service/panel/scan/commands (the tool window stays here). */
    private final TodoCoordinator todoCoordinator = new TodoCoordinator(coordinatorHost, new TodoCoordinator.Ops() {
        @Override
        public java.nio.file.Path projectRoot() {
            return (windowProject != null && projectsEnabled()) ? java.nio.file.Path.of(windowProject.root()) : null;
        }

        @Override
        public void openMatch(java.nio.file.Path file, int line, int col) {
            openPath(file);
            Platform.runLater(() -> gotoInFile(file, line, col));
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
        openPath(file);
        Platform.runLater(() -> {
            EditorBuffer b = activeBuffer();
            if (b == null || b.getPath() == null || !canonicalPath(b.getPath()).equals(canonicalPath(file))) {
                return;
            }
            // Say so rather than doing nothing: a TODO in a read-only buffer is common (a .log opens in View
            // mode, as does anything not writable on disk — i.e. exactly the vendored/generated code a scan
            // turns up), and a menu click that produced no status, no error and no change looked like a bug.
            if (!activeEditable()) {
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
            csvExportPdf(csvText, baseName);
        }

        @Override
        public void printCsv(String csvText) {
            csvPrint(csvText);
        }

        @Override
        public void exportExcel(java.util.List<java.util.List<String>> rows, boolean hasHeader, String baseName) {
            csvExportSpreadsheet(rows, hasHeader, baseName, true);
        }

        @Override
        public void exportOds(java.util.List<java.util.List<String>> rows, boolean hasHeader, String baseName) {
            csvExportSpreadsheet(rows, hasHeader, baseName, false);
        }
    });

    /** Find-in-Files feature; owns the search service/panel/backend (the tool window + commands stay here). */
    private final SearchCoordinator searchCoordinator =
            new SearchCoordinator(coordinatorHost, new SearchCoordinator.Ops() {
                @Override
                public java.nio.file.Path projectRoot() {
                    return (windowProject != null && projectsEnabled())
                            ? java.nio.file.Path.of(windowProject.root())
                            : null;
                }

                @Override
                public void openMatch(java.nio.file.Path file, int line, int col, boolean focusEditor) {
                    openPath(file);
                    Platform.runLater(() -> {
                        gotoInFile(file, line, col, focusEditor);
                        // A preview (single click / keyboard selection) keeps focus in the results so the user
                        // can keep arrowing — openPath/gotoInFile would otherwise have grabbed editor focus.
                        if (!focusEditor) {
                            searchCoordinator.panel().focusResults();
                        }
                    });
                }

                @Override
                public boolean isToolWindowOpen() {
                    return searchToolWindow != null && toolWindows.isOpen(searchToolWindow);
                }

                @Override
                public void openToolWindow() {
                    toolWindows.open(searchToolWindow, true);
                }

                @Override
                public void closeToolWindow() {
                    toolWindows.close(searchToolWindow);
                }

                @Override
                public EditorBuffer bufferForPath(java.nio.file.Path file) {
                    return bufferOf(tabForPath(file));
                }

                @Override
                public void recordSearch(String query) {
                    if (searchHistory != null) {
                        searchHistory.add(query);
                    }
                }

                @Override
                public javafx.collections.ObservableList<String> searchHistory() {
                    return searchHistory != null
                            ? searchHistory.getList()
                            : javafx.collections.FXCollections.observableArrayList();
                }

                @Override
                public void syncRipgrepStatus(boolean found) {
                    if (settingsWindow != null) {
                        settingsWindow.syncRipgrepStatus(found);
                    }
                }
            });

    /** Run-a-file feature; owns the run service/console panel (the tool window + commands stay here). */
    private final RunCoordinator runCoordinator = new RunCoordinator(coordinatorHost, new RunCoordinator.Ops() {
        @Override
        public void openToolWindow() {
            toolWindows.open(runToolWindow);
        }

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return save(buffer);
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
            openRunLink(link);
        }
    });

    /** The whole LSP integration (nav/format, diagnostics routing, the configure/detect/per-buffer-sync
     *  gating + lifecycle, the status-bar segment, structure outline, semantic tokens); see
     *  {@link LspCoordinator}. The {@code LspManager} stays owned here (DAP layers on its jdtls session, the
     *  MCP bridge reads its diagnostics) and is passed in. */
    private final LspCoordinator lspCoordinator =
            new LspCoordinator(coordinatorHost, lspManager, new LspCoordinator.Ops() {
                @Override
                public void openAndGoto(Path file, int line0, int col0) {
                    MainController.this.openAndGoto(file, line0, col0);
                }

                @Override
                public boolean activeEditable() {
                    return MainController.this.activeEditable();
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
                }

                @Override
                public void openReferencesWindow() {
                    if (referencesToolWindow != null) {
                        toolWindows.open(referencesToolWindow);
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
                    maybeOfferInstall(activeBuffer()); // hide/show the install banner per fresh LSP detection
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
                public void openToolWindow(boolean focus) {
                    if (focus) {
                        toolWindows.open(httpToolWindow, true);
                    } else {
                        toolWindows.open(httpToolWindow);
                    }
                }

                @Override
                public void toggleToolWindow() {
                    toolWindows.toggle(httpToolWindow);
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
            return openBufferForPath(path);
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
        public void refreshProjectTree() {
            projectPanel.refreshTree();
        }

        @Override
        public EditorBuffer openBackgroundBuffer(Path target) {
            return MainController.this.openBackgroundBuffer(target);
        }

        @Override
        public void openPath(Path file) {
            MainController.this.openPath(file);
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
            toolWindows.setAvailable(agentToolWindow, agentCoordinator.isEnabled());
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

    // --- MCP server (loopback HTTP, exposes editor state + commands to an LLM agent) --------------

    private boolean mcpEnabled() {
        // Simple UI mode disables the MCP server too (without changing the saved setting).
        return config.getSettings().isMcpSupport() && !simpleModeActive();
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

    /** Stops the MCP server if this window owns it (called from {@link #disposeWindow}). */
    private void stopMcpIfOwner() {
        if (mcpServer != null && mcpOwner == this) {
            mcpServer.stop();
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

    /** Runs {@code task} on the FX thread and blocks (with a timeout) for its result. */
    private static <T> T mcpOnFx(java.util.function.Supplier<T> task) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            return task.get();
        }
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                f.complete(task.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public java.util.List<OpenFile> listOpenFiles() {
        return mcpOnFx(() -> {
            EditorBuffer active = activeBuffer();
            java.util.List<OpenFile> out = new java.util.ArrayList<>();
            for (Tab tab : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b == null) {
                    continue;
                }
                out.add(new OpenFile(
                        b.getPath() == null ? null : b.getPath().toString(),
                        b.getTitle(),
                        b.getLanguage(),
                        b.isDirty(),
                        b == active));
            }
            return out;
        });
    }

    @Override
    public BufferContent readBuffer(String path) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return null;
            }
            return new BufferContent(
                    b.getPath() == null ? null : b.getPath().toString(),
                    b.getTitle(),
                    b.getLanguage(),
                    b.isDirty(),
                    b.getContent());
        });
    }

    @Override
    public java.util.List<Diagnostic> getDiagnostics(String path) {
        return mcpOnFx(() -> {
            Path target = path != null
                    ? Path.of(path)
                    : (activeBuffer() == null ? null : activeBuffer().getPath());
            if (target == null) {
                return java.util.List.<Diagnostic>of();
            }
            Path key = canonicalPath(target);
            java.util.List<Diagnostic> out = new java.util.ArrayList<>();
            for (var e : lspCoordinator.problems().entrySet()) {
                if (!canonicalPath(e.getKey()).equals(key)) {
                    continue;
                }
                for (com.editora.editor.LspDiagnostic d : e.getValue()) {
                    out.add(new Diagnostic(
                            d.startLine() + 1, d.startCol() + 1, d.severity().name(), d.message(), d.origin()));
                }
            }
            return out;
        });
    }

    @Override
    public java.util.List<SearchMatch> findInFiles(
            String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
        com.editora.search.SearchQuery q = new com.editora.search.SearchQuery(query, caseSensitive, regex, wholeWord);
        java.util.concurrent.CompletableFuture<com.editora.search.SearchService.Outcome> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            java.util.Map<Path, String> open = new java.util.HashMap<>();
            for (Tab tab : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b != null && b.getPath() != null) {
                    open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
                }
            }
            Path root = null;
            Project p = projects == null ? null : projects.active();
            if (p != null) {
                root = Path.of(p.root());
            }
            searchCoordinator.service().search(q, root, open, fut::complete);
        });
        com.editora.search.SearchService.Outcome outcome;
        try {
            outcome = fut.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.util.List<SearchMatch> out = new java.util.ArrayList<>();
        for (com.editora.search.FileResult fr : outcome.files()) {
            for (com.editora.search.LineMatch lm : fr.matches()) {
                out.add(new SearchMatch(fr.file().toString(), lm.line(), lm.col(), lm.lineText()));
            }
        }
        return out;
    }

    @Override
    public java.util.List<CommandInfo> listCommands() {
        return mcpOnFx(() -> {
            java.util.List<CommandInfo> out = new java.util.ArrayList<>();
            for (com.editora.command.Command c : registry.all()) {
                out.add(new CommandInfo(c.id(), c.title(), c.description()));
            }
            return out;
        });
    }

    @Override
    public boolean executeCommand(String id) {
        return mcpOnFx(() -> registry.run(id));
    }

    @Override
    public boolean openFile(String path, int line, int col) {
        Path file = Path.of(path);
        if (!java.nio.file.Files.exists(file)) {
            return false;
        }
        return mcpOnFx(() -> {
            openPath(file);
            // Navigate only for text buffers — an image/hex/binary tab has no caret to move.
            if (line > 0 && openBufferForPath(file.toString()) != null) {
                gotoInFile(file, line, Math.max(col, 1));
            }
            return true;
        });
    }

    @Override
    public String editBuffer(String path, String oldText, String newText, boolean replaceAll) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (!b.isEditable()) {
                return "Buffer is read-only.";
            }
            CodeArea area = b.getArea();
            String replacement = newText == null ? "" : newText;
            if (oldText == null || oldText.isEmpty()) {
                area.replaceText(replacement); // whole-buffer rewrite, one undo step
                return null;
            }
            String text = area.getText();
            int first = text.indexOf(oldText);
            if (first < 0) {
                return "old_text not found in the buffer.";
            }
            if (replaceAll) {
                area.replaceText(text.replace(oldText, replacement));
                return null;
            }
            if (text.indexOf(oldText, first + 1) >= 0) {
                return "old_text occurs more than once; pass replace_all or a longer, unique old_text.";
            }
            area.replaceText(first, first + oldText.length(), replacement);
            return null;
        });
    }

    @Override
    public String saveBuffer(String path) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (b.getPath() == null) {
                // save() would open a Save-As dialog — never pop UI from an agent call.
                return "Untitled buffer has no file path; Save As must be done in the editor.";
            }
            return save(b) ? null : "Save did not complete (elevated write pending or the write failed).";
        });
    }

    @Override
    public Selection getSelection() {
        return mcpOnFx(() -> {
            EditorBuffer b = activeBuffer();
            if (b == null) {
                return null;
            }
            CodeArea area = b.getFocusedArea() != null ? b.getFocusedArea() : b.getArea();
            var fwd = org.fxmisc.richtext.model.TwoDimensional.Bias.Forward;
            var sel = area.getSelection();
            var sp = area.offsetToPosition(sel.getStart(), fwd);
            var ep = area.offsetToPosition(sel.getEnd(), fwd);
            return new Selection(
                    b.getPath() == null ? null : b.getPath().toString(),
                    b.getTitle(),
                    area.getCurrentParagraph() + 1,
                    area.getCaretColumn() + 1,
                    sp.getMajor() + 1,
                    sp.getMinor() + 1,
                    ep.getMajor() + 1,
                    ep.getMinor() + 1,
                    area.getSelectedText());
        });
    }

    @Override
    public java.util.List<Symbol> documentSymbols(String path) {
        java.util.concurrent.CompletableFuture<java.util.List<com.editora.lsp.SymbolNode>> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            Path target = path != null
                    ? Path.of(path)
                    : (activeBuffer() == null ? null : activeBuffer().getPath());
            if (target == null
                    || !lspEnabled()
                    || !lspManager.isManaged(target)
                    || !lspManager.supportsDocumentSymbols(target)) {
                fut.complete(java.util.List.of());
                return;
            }
            lspManager.documentSymbols(target, fut::complete);
        });
        try {
            return mapMcpSymbols(fut.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Maps the LSP outline to the bridge's neutral records, shifting lines to 1-based. */
    private static java.util.List<Symbol> mapMcpSymbols(java.util.List<com.editora.lsp.SymbolNode> in) {
        java.util.List<Symbol> out = new java.util.ArrayList<>(in.size());
        for (com.editora.lsp.SymbolNode n : in) {
            out.add(new Symbol(
                    n.name(), n.detail(), n.kind(), n.line() + 1, n.endLine() + 1, mapMcpSymbols(n.children())));
        }
        return out;
    }

    @Override
    public GitState gitStatus() {
        java.util.concurrent.CompletableFuture<com.editora.git.GitService.RepoState> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            Path context = git.isEnabled() ? git.contextPath() : null;
            if (context == null) {
                fut.complete(com.editora.git.GitService.RepoState.NONE);
                return;
            }
            git.service().status(context, fut::complete);
        });
        com.editora.git.GitService.RepoState state;
        try {
            state = fut.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!state.isRepo()) {
            return new GitState(false, null, null, null, 0, 0, java.util.List.of());
        }
        com.editora.git.GitStatus st = state.status();
        java.util.List<GitFileState> files = new java.util.ArrayList<>();
        for (com.editora.git.GitStatus.FileEntry f : st.files()) {
            files.add(
                    new GitFileState(f.path(), String.valueOf(f.index()), String.valueOf(f.worktree()), f.origPath()));
        }
        return new GitState(true, state.root().toString(), st.branch(), st.upstream(), st.ahead(), st.behind(), files);
    }

    @Override
    public java.util.List<TabInfo> listTabs() {
        return mcpOnFx(() -> {
            Tab active = tabPane.getSelectionModel().getSelectedItem();
            java.util.List<TabInfo> out = new java.util.ArrayList<>();
            for (Tab tab : tabPane.getTabs()) {
                Path p = tabPath(tab);
                out.add(new TabInfo(tabType(tab), bufferTitle(tab), p == null ? null : p.toString(), tab == active));
            }
            return out;
        });
    }

    @Override
    public java.util.List<TodoItem> todoScan() {
        java.util.concurrent.CompletableFuture<com.editora.todo.TodoService.Outcome> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> todoCoordinator.scanForMcp(fut::complete));
        com.editora.todo.TodoService.Outcome outcome;
        try {
            outcome = fut.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.util.List<TodoItem> out = new java.util.ArrayList<>();
        for (com.editora.todo.TodoService.FileTodos ft : outcome.files()) {
            String file = ft.file().toString();
            for (com.editora.todo.TodoMatch m : ft.matches()) {
                com.editora.todo.TodoComment c = m.parsed();
                out.add(new TodoItem(
                        file,
                        m.line() + 1,
                        m.col() + 1,
                        c == null ? m.patternName() : c.keyword(),
                        c == null ? null : c.tag(),
                        c == null ? null : c.priority(),
                        m.lineText()));
            }
        }
        return out;
    }

    /** The MCP {@code type} label for a tab: {@code editor}/{@code image}/{@code hex}/{@code diff}/
     *  {@code merge}/{@code welcome}/{@code other}. */
    private static String tabType(Tab tab) {
        if (bufferOf(tab) != null) {
            return "editor";
        }
        if (imagePaneOf(tab) != null) {
            return "image";
        }
        if (pdfPaneOf(tab) != null) {
            return "pdf";
        }
        if (hexPaneOf(tab) != null) {
            return "hex";
        }
        Object data = tab == null ? null : tab.getUserData();
        if (data instanceof DiffViewerPane) {
            return "diff";
        }
        if (data instanceof MergeViewerPane) {
            return "merge";
        }
        if (data instanceof WelcomePane) {
            return "welcome";
        }
        return "other";
    }

    /** Finds the open buffer whose file matches {@code path} (by canonical path), or null. */
    private EditorBuffer openBufferForPath(String path) {
        Path key = canonicalPath(Path.of(path));
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.getPath() != null && canonicalPath(b.getPath()).equals(key)) {
                return b;
            }
        }
        return null;
    }

    /** True when {@code b}'s file is on the local filesystem (or untitled) — the gate for every feature
     *  that shells out to a local process (LSP/DAP/git/run/HTTP). Remote (SFTP) buffers are text-only. */
    private boolean isLocalBuffer(EditorBuffer b) {
        return b != null && com.editora.vfs.Vfs.isLocal(b.getPath());
    }

    // --- LSP (Language Server Protocol) integration --------------------------------------------

    private boolean lspEnabled() {
        // Simple UI mode disables LSP (servers, diagnostics, completion, navigation); saved setting unchanged.
        return config.getSettings().isLspSupport() && !simpleModeActive();
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
        NavigationHistory.Location origin = navigating ? null : captureCurrent();
        openPath(file);
        Platform.runLater(() -> {
            suppressNavRecord = true; // let this outer call own the recording, not the nested gotoInFile
            gotoInFile(file, line0 + 1, col0 + 1);
            suppressNavRecord = false;
            if (!navigating) {
                recordJump(origin, new NavigationHistory.Location(file, line0, col0));
            }
            navigating = false; // a back/forward jump has landed
        });
    }

    /** The active file-backed buffer's current caret location (0-based), or {@code null} when none. */
    private NavigationHistory.Location captureCurrent() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            return null;
        }
        CodeArea a = b.getArea();
        return a == null
                ? null
                : new NavigationHistory.Location(b.getPath(), a.getCurrentParagraph(), a.getCaretColumn());
    }

    /** Records a jump into the back/forward history: the {@code origin} we left, then the {@code dest}. */
    private void recordJump(NavigationHistory.Location origin, NavigationHistory.Location dest) {
        if (dest == null) {
            return;
        }
        if (origin != null) {
            navHistory.record(origin);
        }
        navHistory.record(dest);
    }

    /** {@code nav.back}: return to the previous location in the jump list. */
    private void navBack() {
        NavigationHistory.Location loc = navHistory.back();
        if (loc == null) {
            setStatus(tr("status.nav.noBack"));
            return;
        }
        navigating = true;
        openAndGoto(loc.path(), loc.line(), loc.column()); // clears `navigating` in its runLater
    }

    /** {@code nav.forward}: go to the next location in the jump list (after going back). */
    private void navForward() {
        NavigationHistory.Location loc = navHistory.forward();
        if (loc == null) {
            setStatus(tr("status.nav.noForward"));
            return;
        }
        navigating = true;
        openAndGoto(loc.path(), loc.line(), loc.column());
    }

    /** A stack-trace location double-clicked in the Run/Debug console: resolve + jump. An absolute
     *  path opens directly; a bare Java file name resolves against the open tabs, then the run file's
     *  directory, then the active project root's top level. */
    private void openRunLink(com.editora.run.StackTraceLinks.Link link) {
        Path resolved = resolveRunLinkFile(link.file());
        if (resolved == null) {
            setStatus(tr("status.run.linkNotFound", link.file()));
            return;
        }
        openAndGoto(resolved, link.line() - 1, 0); // console lines are 1-based
    }

    private Path resolveRunLinkFile(String fileToken) {
        try {
            Path p = Path.of(fileToken);
            if (p.isAbsolute()) {
                return java.nio.file.Files.isRegularFile(p) ? p : null;
            }
            String name = p.getFileName().toString();
            for (Tab t : tabPane.getTabs()) { // an open tab with that file name wins
                EditorBuffer b = bufferOf(t);
                if (b != null
                        && b.getPath() != null
                        && b.getPath().getFileName().toString().equals(name)) {
                    return b.getPath();
                }
            }
            Path lastRunDir = runCoordinator.lastRunDir();
            if (lastRunDir != null) {
                Path sibling = lastRunDir.resolve(name);
                if (java.nio.file.Files.isRegularFile(sibling)) {
                    return sibling;
                }
            }
            Project active = projects == null ? null : projects.active();
            if (active != null) {
                Path inRoot = Path.of(active.root()).resolve(name);
                if (java.nio.file.Files.isRegularFile(inRoot)) {
                    return inRoot;
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed path token — treat as unresolvable.
        }
        return null;
    }

    /** Whether the Personal Notes feature is enabled in Settings (default off). */

    /** The open buffer for {@code target} (canonical-path match), or null if not open. */
    private EditorBuffer openBufferFor(Path target) {
        for (Tab tab : tabPane.getTabs()) {
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
            loadInto(buffer, target);
            addBuffer(buffer, false); // background: keep the caller's current tab focused
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    /** Toggles the IntelliJ-style branch dropdown, fetching local + remote branches off-thread first. */
    private void chooseBranch() {
        // Toggle: a second click on the git status segment closes the open dropdown. (autoHide fires on
        // the same click, hiding it, so also treat a just-now hide as "was open" and leave it closed.)
        if (branchPopup.isShown()) {
            branchPopup.hide();
            return;
        }
        if (branchPopup.justHidden()) {
            return;
        }
        if (git.repoRoot() == null) {
            // Not under version control: the dropdown offers only "Clone Git repository…".
            branchPopup.showNoVcs(stage, statusBar.gitSegmentNode(), git::cloneRepo);
            return;
        }
        git.service().branches(git.repoRoot(), branches -> {
            List<BranchPopup.MenuAction> actions = List.of(
                    new BranchPopup.MenuAction(tr("branch.newBranch"), "", git::newBranch),
                    new BranchPopup.MenuAction(
                            tr("branch.pull"), "", () -> git.gitSync(tr("gitlabel.pull"), "pull", "--ff-only")),
                    new BranchPopup.MenuAction(
                            tr("branch.fetch"), "", () -> git.gitSync(tr("gitlabel.fetch"), "fetch", "--all")),
                    new BranchPopup.MenuAction(tr("branch.push"), "", git::gitPush),
                    new BranchPopup.MenuAction(tr("branch.stash"), "", git::gitStash),
                    new BranchPopup.MenuAction(tr("branch.unstash"), "", git::gitUnstash),
                    new BranchPopup.MenuAction(tr("branch.commit"), "C-x g", git::gitCommitFocus));
            branchPopup.show(
                    stage,
                    statusBar.gitSegmentNode(),
                    git.branchName(),
                    branches.local(),
                    branches.remote(),
                    branches.remoteUrl(),
                    actions,
                    git::checkoutBranch,
                    git::checkoutRemoteBranch);
        });
    }

    // --- Git Log / History tool window -----------------------------------------------------------

    /** The {@link GitLogPanel.Actions} the Git Log tool window routes user actions through. */
    private GitLogPanel.Actions gitLogActions() {
        return new GitLogPanel.Actions() {
            @Override
            public void refresh() {
                loadGitLog(gitLogFilter);
            }

            @Override
            public void showAll() {
                loadGitLog(null);
            }

            @Override
            public void selected(String hash) {
                if (git.repoRoot() != null) {
                    git.service().commitFiles(git.repoRoot(), hash, gitLogPanel::setCommitFiles);
                }
            }

            @Override
            public void openFileDiff(String hash, String repoRel, String origRepoRel) {
                diffCoordinator.diffCommitFile(hash, repoRel, origRepoRel);
            }

            @Override
            public void copyHash(String hash) {
                ClipboardContent content = new ClipboardContent();
                content.putString(hash);
                Clipboard.getSystemClipboard().setContent(content);
                setStatus(tr("status.git.copiedHash", com.editora.git.GitFormat.shortHash(hash)));
            }

            @Override
            public void checkout(String hash) {
                gitMutate(tr("status.git.checkedOut", com.editora.git.GitFormat.shortHash(hash)), "checkout", hash);
            }

            @Override
            public void reset(String hash, String mode) {
                gitMutate(
                        tr("status.git.reset", mode, com.editora.git.GitFormat.shortHash(hash)),
                        "reset",
                        "--" + mode,
                        hash);
                Platform.runLater(MainController.this::checkExternalChanges);
            }

            @Override
            public void revert(String hash) {
                gitMutate(
                        tr("status.git.reverted", com.editora.git.GitFormat.shortHash(hash)),
                        "revert",
                        "--no-edit",
                        hash);
            }

            @Override
            public void cherryPick(String hash) {
                gitMutate(
                        tr("status.git.cherryPicked", com.editora.git.GitFormat.shortHash(hash)), "cherry-pick", hash);
            }

            @Override
            public void newBranch(String hash) {
                promptText(tr("dialog.newBranch.title"), tr("dialog.newBranch.content"), "", input -> {
                    String name = input.strip();
                    if (!name.isEmpty()) {
                        gitMutate(tr("status.createdBranch", name), "checkout", "-b", name, hash);
                        Platform.runLater(MainController.this::reloadAllFromDiskSilently);
                    }
                });
            }
        };
    }

    /** Runs {@code op} on the Git Log panel's selected commit, or reports that none is selected. Git-gated. */
    private void withSelectedCommit(java.util.function.Consumer<String> op) {
        git.ifEnabled(() -> {
            String hash = gitLogPanel.selectedHash();
            if (hash == null || hash.isBlank()) {
                setStatus(tr("status.git.noCommitSelected"));
                return;
            }
            op.accept(hash);
        });
    }

    /** Asks for the reset mode (soft/mixed/hard) then resets the selected commit (the {@code git.log.reset} command). */
    private void promptGitReset(String hash) {
        javafx.scene.control.ChoiceDialog<String> d =
                new javafx.scene.control.ChoiceDialog<>("mixed", java.util.List.of("soft", "mixed", "hard"));
        d.initOwner(stage);
        d.setTitle(tr("dialog.gitReset.title"));
        d.setHeaderText(null);
        d.setContentText(tr("dialog.gitReset.content"));
        d.showAndWait().ifPresent(mode -> gitLogOps.reset(hash, mode));
    }

    /**
     * Opens the Git Log tool window with the given filter (null = whole repo). A fresh open triggers the
     * load via the tool-window state listener; if the window is already open (no state change, so no
     * listener), the log is refreshed explicitly — so opening always shows current history.
     */
    private void openGitLog(Path filter) {
        gitLogFilter = filter;
        boolean wasOpen = toolWindows.isOpen(gitLogToolWindow);
        toolWindows.open(gitLogToolWindow);
        if (wasOpen) {
            loadGitLog(filter);
        }
    }

    /** Opens the Git Log tool window showing the whole-repo history. */
    private void showGitLog() {
        openGitLog(null);
    }

    /** Opens the Git Log filtered to the active file's history. */
    private void showFileHistory() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        openGitLog(b.getPath());
    }

    /** Loads up to 200 commits (whole-repo when {@code file} is null, else that file's history). */
    private void loadGitLog(Path file) {
        gitLogFilter = file;
        if (git.repoRoot() == null) {
            gitLogPanel.setLog(List.of(), null);
            git.reportIfNoRepo(); // echoes "not a repo" / "git not installed"
            return;
        }
        String name = file != null ? file.getFileName().toString() : null;
        git.service().log(git.repoRoot(), file, 200, commits -> gitLogPanel.setLog(commits, name));
    }

    /** Read-only diff of one file at a commit vs its first parent (from the Git Log file list). */
    /** A history mutation (checkout/reset/revert/cherry-pick/branch): run, report, refresh + reload log. */
    private void gitMutate(String successMessage, String... args) {
        if (git.reportIfNoRepo()) {
            return;
        }
        git.service()
                .run(
                        git.repoRoot(),
                        r -> {
                            if (r.ok()) {
                                setStatus(successMessage);
                            } else {
                                git.gitError(tr("status.git.opFailed"), r.message());
                            }
                            git.afterMutation();
                            loadGitLog(gitLogFilter); // HEAD/refs moved → refresh the log
                        },
                        args);
    }

    // --- TODO / highlight patterns ---------------------------------------------------------------

    /** The compiled-pattern matcher pushed to every buffer; rebuilt on init + each settings apply. */

    // --- Markdown lint ---------------------------------------------------------------------------

    /** Whether Markdown linting is effective (the setting; the per-buffer gate adds Markdown + non-huge). */
    private boolean markdownLintEnabled() {
        return config.getSettings().isMarkdownLint();
    }

    /** Pushes the Markdown-lint enabled state to every buffer (init + each settings apply). */
    private void applyMarkdownLint() {
        boolean on = markdownLintEnabled();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setMarkdownLintEnabled(on);
            }
        }
        if (markdownLintToolWindow != null && markdownLintPanel != null && toolWindows.isOpen(markdownLintToolWindow)) {
            runMarkdownLintScan();
        }
    }

    /** Lints the active Markdown buffer once and fills the Lint tool window (off-thread). */
    private void runMarkdownLintScan() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkdown() || !markdownLintEnabled()) {
            markdownLintPanel.setResults(null, java.util.List.of());
            return;
        }
        markdownLintService.validate(
                b.getContent(),
                effectiveMarkdownLintDisabled(b),
                diags -> markdownLintPanel.setResults(b.getPath(), diags));
    }

    /** Toggles the Markdown Lint tool window; opening it auto-scans via {@code focusFirstItem}. */
    private void toggleMarkdownLintWindow() {
        toolWindows.toggle(markdownLintToolWindow);
    }

    /** A cached parse of one {@code .markdownlint.json} (keyed by path, invalidated by mtime). */
    private record MarkdownLintConfigEntry(long mtime, java.util.Set<String> disabled) {}

    private final java.util.Map<java.nio.file.Path, MarkdownLintConfigEntry> markdownLintConfigCache =
            new java.util.HashMap<>();

    /** The rule codes disabled for {@code buffer}: the Settings list ∪ the nearest {@code .markdownlint.json}. */
    private java.util.Set<String> effectiveMarkdownLintDisabled(EditorBuffer buffer) {
        java.util.Set<String> off = new java.util.HashSet<>();
        for (String code : config.getSettings().getMarkdownLintDisabledRules()) {
            if (code != null && !code.isBlank()) {
                off.add(code.strip().toUpperCase(java.util.Locale.ROOT));
            }
        }
        java.nio.file.Path path = buffer == null ? null : buffer.getPath();
        if (path != null && com.editora.vfs.Vfs.isLocal(path)) {
            off.addAll(markdownLintConfigDisabled(path));
        }
        return off;
    }

    /** Walks up from {@code file} for the nearest {@code .markdownlint.json} and returns the rules it disables. */
    private java.util.Set<String> markdownLintConfigDisabled(java.nio.file.Path file) {
        java.nio.file.Path dir = file.getParent();
        while (dir != null) {
            java.nio.file.Path cfg = dir.resolve(".markdownlint.json");
            if (java.nio.file.Files.isRegularFile(cfg)) {
                try {
                    long mtime = java.nio.file.Files.getLastModifiedTime(cfg).toMillis();
                    MarkdownLintConfigEntry cached = markdownLintConfigCache.get(cfg);
                    if (cached == null || cached.mtime() != mtime) {
                        java.util.Set<String> rules = com.editora.markdown.MarkdownLintConfig.disabledRules(
                                java.nio.file.Files.readString(cfg));
                        cached = new MarkdownLintConfigEntry(mtime, rules);
                        markdownLintConfigCache.put(cfg, cached);
                    }
                    return cached.disabled();
                } catch (java.io.IOException e) {
                    return java.util.Set.of();
                }
            }
            dir = dir.getParent();
        }
        return java.util.Set.of();
    }

    /** Applies the safe-to-automate Markdown-lint fixes to the active buffer (undoable). */
    private void fixMarkdownLint() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkdown()) {
            setStatus(tr("status.markdownLint.notMarkdown"));
            return;
        }
        if (!markdownLintEnabled()) {
            setStatus(tr("status.markdownLint.off"));
            return;
        }
        String text = b.getContent();
        String fixed = com.editora.markdown.MarkdownLintFix.fix(
                text, effectiveMarkdownLintDisabled(b), config.getSettings().getTabSize());
        if (fixed.equals(text)) {
            setStatus(tr("status.markdownLint.fixNone"));
            return;
        }
        b.getArea().replaceText(fixed); // whole-document replace (undoable)
        setStatus(tr("status.markdownLint.fixed"));
    }

    /** Picker to enable/disable an individual Markdown-lint rule (writes Settings + re-lints live). */
    private void chooseMarkdownLintRule() {
        chooseSetting(
                "markdownLint.toggleRule",
                () -> com.editora.markdown.MarkdownLint.RULES.stream()
                        .map(com.editora.markdown.MarkdownLint.Rule::code)
                        .toList(),
                code -> {
                    boolean on = !markdownLintRuleDisabled(code);
                    String name = tr("mdlint.rule." + code);
                    return (on ? "✓ " : "✗ ") + code + " — " + name;
                },
                this::toggleMarkdownLintRule);
    }

    private boolean markdownLintRuleDisabled(String code) {
        for (String c : config.getSettings().getMarkdownLintDisabledRules()) {
            if (code.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    private void toggleMarkdownLintRule(String code) {
        java.util.List<String> list =
                new java.util.ArrayList<>(config.getSettings().getMarkdownLintDisabledRules());
        boolean wasDisabled = list.removeIf(c -> code.equalsIgnoreCase(c));
        if (!wasDisabled) {
            list.add(code);
        }
        config.getSettings().setMarkdownLintDisabledRules(list);
        requestSave();
        applyMarkdownLint(); // re-kicks every buffer's validator (new disabled set) + refreshes the panel
        if (settingsWindow != null) {
            settingsWindow.syncAll();
        }
        setStatus(tr(wasDisabled ? "status.markdownLint.ruleEnabled" : "status.markdownLint.ruleDisabled", code));
    }

    // --- Local File History --------------------------------------------------------------------

    /** Project-tree Git ▸ Show File History for {@code file}: loads that file's Git log + opens the window. */
    private void gitFileHistoryForPath(Path file) {
        if (file == null || Files.isDirectory(file)) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        openGitLog(file);
    }

    /** The current text of {@code file}: the open buffer's live text when open, else the on-disk content. */
    private String currentTextOf(Path file) {
        EditorBuffer open = openBufferFor(file);
        if (open != null) {
            return open.getContent();
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

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
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer == null || buffer.getPath() == null || buffer.isDirty()) {
                continue; // never clobber unsaved edits
            }
            Path file = buffer.getPath();
            if (Files.exists(file) && buffer.diskChangedFrom(lastModifiedMillis(file), fileSize(file))) {
                reloadFromDisk(tab, buffer);
            }
        }
    }

    private void setupToolbar() {
        setupButton(newButton, Icons.newFile(), tr("tooltip.new"), "file.new");
        setupButton(newFromTemplateButton, Icons.fileSheet(), tr("tooltip.newFromTemplate"), "template.new");
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
        setupButton(paletteButton, Icons.palette(), tr("tooltip.palette"), "palette.show");
        setupButton(closeTabButton, Icons.closeTab(), tr("tooltip.closeTab"), "buffer.close");
        setupButton(simpleModeButton, Icons.simpleMode(), tr("tooltip.simpleMode"), "view.toggleSimpleMode");
        setupButton(settingsButton, Icons.settings(), tr("tooltip.settings"), "view.settings");
        setupButton(aboutButton, Icons.about(), tr("tooltip.about"), "help.about");
        setupButton(quitButton, Icons.quit(), tr("tooltip.quit"), "app.quit");

        // Reflect open/closed state of the palette and find bar in their toolbar buttons.
        palette.showingProperty().addListener((obs, was, now) -> paletteButton.pseudoClassStateChanged(OPEN, now));
        findBar.visibleProperty().addListener((obs, was, now) -> findButton.pseudoClassStateChanged(OPEN, now));
        // Project switcher (placed right of the Settings icon by arrangeToolbarTail); shown when enabled.
        toolbarProjectCombo = new ProjectCombo(this::switchToProject);
        toolbarProjectCombo.setPrefWidth(184); // 15% longer than the previous 160
        projectToolbarLabel = new Label(tr("toolbar.projectLabel"));
        projectToolbarLabel.getStyleClass().add("toolbar-hint");
        projectToolbarLabel.setTooltip(
                new Tooltip("Projects are single-folder workspaces, each remembering its own open files and layout. "
                        + "Pick one to switch; \"No Project\" returns to the global session."));
        toolbarProjectCombo.setOnDeleteProject(this::deleteProject); // per-item delete in the dropdown
        projectToolbarGap = new Region();
        projectToolbarGap.setMinWidth(78); // ≈ 3 toolbar-icon widths between Settings and the combo
        projectToolbarGap.setPrefWidth(78);
        arrangeToolbarTail();
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
        if (toolBar == null || toolbarProjectCombo == null) {
            return;
        }
        double comboH = toolbarProjectCombo.prefHeight(-1); // independent of the combo's current visibility
        if (comboH <= 0) {
            return;
        }
        javafx.geometry.Insets in = toolBar.getInsets();
        toolBar.setMinHeight(Math.ceil(comboH + in.getTop() + in.getBottom()));
    }

    /**
     * Right-aligns the trailing toolbar group: a Switcher keybinding hint (discoverability), then the
     * About and Quit buttons, with Quit always rightmost and About just left of it. About/Quit are
     * moved here from their FXML slot; the group uses plain spacing (no separators) to stand apart
     * from the separator-delimited icon clusters.
     */
    private void arrangeToolbarTail() {
        var items = toolBar.getItems();
        // Pull About + Quit out of their FXML position, and drop the separator that preceded Quit.
        items.removeAll(aboutButton, quitButton);
        if (!items.isEmpty() && items.get(items.size() - 1) instanceof Separator) {
            items.remove(items.size() - 1);
        }
        // The "Open Folder as Project" icon moves out of the left file-icon group to sit just right of
        // the project combobox (per-project delete lives inside the combo's dropdown).
        items.remove(openFolderButton);

        // Project switcher just right of the Settings icon (a ~3-icon gap), with the open-folder icon
        // immediately to the right of the combobox, before the flexible spacer.
        items.addAll(projectToolbarGap, projectToolbarLabel, toolbarProjectCombo, openFolderButton);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        items.add(spacer);

        // Dev-mode badge (just left of the About icon) when running with --dev, so a development
        // instance is visually distinct from the production one.
        if (config.isDev()) {
            Label devBadge = new Label(tr("badge.devMode"));
            devBadge.getStyleClass().add("dev-mode-badge");
            devBadge.setTooltip(
                    new Tooltip(tr("badge.devMode.tip", config.getConfigDir().toString())));
            items.addAll(toolbarGap(), devBadge);
        }

        items.addAll(toolbarGap(), aboutButton, toolbarGap(), quitButton);
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

    /** Reflects the active buffer's split state in the toolbar toggle buttons. */
    private void refreshSplitButtons() {
        EditorBuffer buffer = activeBuffer();
        EditorBuffer.Split split = buffer == null ? EditorBuffer.Split.NONE : buffer.getSplit();
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

    /**
     * Restores last session's open files (with their carets); falls back to one empty buffer.
     *
     * <p>Two phases so the UI is responsive immediately: first every tab is created (empty), so all
     * tab headers show at once; then content + folds are filled one file per pulse — the active file
     * first. Filling a non-selected tab is cheap (its editor isn't rendered), so a heavily-folded
     * background file can't freeze startup. Tab order and pinning are preserved.
     */
    public void openInitialBuffer() {
        restoringSession = true; // suppress HTTP auto-show until the (pulse-paced) restore finishes
        WorkspaceState state = config.getWorkspaceState();
        List<WorkspaceState.OpenFile> files = new ArrayList<>();
        for (WorkspaceState.OpenFile f : state.getOpenFiles()) {
            // parseStorable reconstructs a local path directly, or a remote (sftp://) one via the resolver —
            // which is null until its connection is open, so a remote entry is skipped at startup rather than
            // reopened as a same-named *local* file.
            Path rp = f.getPath() == null || f.getPath().isBlank()
                    ? null
                    : com.editora.vfs.Vfs.parseStorable(f.getPath());
            if (rp != null && Files.isReadable(rp)) {
                files.add(f);
            }
        }
        if (files.isEmpty()) {
            // No session to restore: apply any CLI action (--new-file / FILE target) first, then show
            // the Welcome tab only if nothing got opened. Both run deferred, in this order.
            runPendingAfterRestore();
            Platform.runLater(this::showWelcomeIfNoTabs);
            return;
        }
        String activePath = state.getActiveFile();
        List<EditorBuffer> buffers = new ArrayList<>();
        int activeIndex = 0;
        for (int i = 0; i < files.size(); i++) {
            WorkspaceState.OpenFile f = files.get(i);
            Path p = com.editora.vfs.Vfs.parseStorable(f.getPath()); // non-null: the filter above kept only readable
            boolean active = f.getPath().equals(activePath);
            if (active) {
                activeIndex = i;
            }
            // A raster image restores into the read-only image viewer (a null buffer placeholder keeps the
            // per-file fill indices aligned; fillSessionFiles skips nulls).
            if (ImageFormats.isSupported(p.getFileName().toString())) {
                Tab tab = openImageTab(p, active);
                if (f.isPinned()) {
                    pinned.add(tab);
                }
                buffers.add(null);
                continue;
            }
            // A PDF restores into the read-only PDF viewer (null placeholder keeps the fill indices aligned).
            if (PdfViewerPane.isPdf(p.getFileName().toString())) {
                Tab tab = openPdfTab(p, active);
                if (f.isPinned()) {
                    pinned.add(tab);
                }
                buffers.add(null);
                continue;
            }
            // A binary file restores into the read-only hex viewer (null placeholder keeps the fill indices
            // aligned, like the image-viewer case; fillSessionFiles skips nulls).
            if (looksBinaryFile(p)) {
                Tab tab = openHexTab(p, active);
                if (f.isPinned()) {
                    pinned.add(tab);
                }
                buffers.add(null);
                continue;
            }
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(p); // sets the tab title/language; content comes later
            Tab tab = addBuffer(buffer, active);
            if (f.isPinned()) {
                pinned.add(tab);
                updateTabMeta(tab, buffer);
            }
            buffers.add(buffer);
        }
        // Fill order: the active file first, then the rest in tab order.
        List<Integer> order = new ArrayList<>();
        order.add(activeIndex);
        for (int i = 0; i < files.size(); i++) {
            if (i != activeIndex) {
                order.add(i);
            }
        }
        fillSessionFiles(files, buffers, order, 0);
    }

    /** Fills one restored buffer per pulse (in {@code order}), keeping the UI responsive between files. */
    private void fillSessionFiles(
            List<WorkspaceState.OpenFile> files, List<EditorBuffer> buffers, List<Integer> order, int k) {
        if (k >= order.size()) {
            runPendingAfterRestore(); // session fully restored — now safe to apply CLI targets
            return;
        }
        Platform.runLater(() -> {
            int i = order.get(k);
            EditorBuffer buffer = buffers.get(i);
            if (buffer != null) { // null = an image-viewer tab; nothing to fill
                fillSessionBuffer(files.get(i), buffer);
            }
            fillSessionFiles(files, buffers, order, k + 1);
        });
    }

    /** A startup file to open, with an optional 1-based line/column ({@code 0} = unspecified). */
    /**
     * Opens OS-delivered files in this window (macOS Finder "Open With" — routed here by
     * {@code App.installMacOpenFilesHandler} → {@code WindowManager.openExternalFiles}). Each path is opened
     * like any other file (an already-open file just re-focuses its tab); paths are normalized to absolute.
     */
    public void openExternalFiles(java.util.List<Path> files) {
        if (files == null) {
            return;
        }
        for (Path f : files) {
            if (f != null) {
                openPath(f.toAbsolutePath().normalize());
            }
        }
    }

    public record OpenTarget(Path file, int line, int column) {}

    /** A one-shot action run after {@link #openInitialBuffer()} finishes restoring the session. */
    private Runnable pendingAfterRestore;

    /**
     * Startup entry point (replaces the bare {@code openInitialBuffer()} call): optionally activates a
     * project, restores the session, then — once restore completes — opens any command-line files
     * (jumping to line:column) and enters Zen/Expert, all additive on top of the restored session. With no
     * arguments it's exactly the old {@code openInitialBuffer()}.
     */
    public void startup(
            Path projectDir, List<OpenTarget> targets, boolean zen, boolean expert, String newFile, boolean simple) {
        if (projectDir != null && projectsEnabled()) {
            activateStartupProject(projectDir); // swap to the project's session before it's restored
        }
        // Run CLI actions AFTER the (deferred, pulse-paced) session restore, so a restored caret can't
        // override a requested line:column.
        pendingAfterRestore = () -> applyStartupTargets(targets, zen, expert, newFile, simple);
        openInitialBuffer();
    }

    private void applyStartupTargets(
            List<OpenTarget> targets, boolean zen, boolean expert, String newFile, boolean simple) {
        if (simple) {
            // --simple: a session-only override (doesn't change the saved setting). Re-apply chrome +
            // per-buffer view settings now that the session's buffers are restored.
            cliSimpleOverride = true;
            applyViewSettingsToAllBuffers(config.getSettings());
            settingsWindow.syncSimpleModeCheck();
        }
        if (newFile != null) {
            // --new-file[=NAME]: open a fresh buffer instead of the Welcome page. "" = blank untitled.
            EditorBuffer buffer = new EditorBuffer();
            if (!newFile.isBlank()) {
                buffer.setDisplayName(newFile);
            }
            addBuffer(buffer, true);
            setStatus(newFile.isBlank() ? tr("status.newBuffer") : tr("status.newFile", newFile));
        }
        if (targets != null) {
            for (OpenTarget t : targets) {
                openPath(t.file().toAbsolutePath().normalize());
            }
            if (targets.stream().anyMatch(t -> t.line() > 0)) {
                // Defer once more so it runs after openPath's own goToStart for any newly-opened file.
                Platform.runLater(() -> {
                    for (OpenTarget t : targets) {
                        if (t.line() > 0) {
                            gotoInFile(t.file().toAbsolutePath().normalize(), t.line(), t.column());
                        }
                    }
                });
            }
        }
        // --zen / --expert: session-only overrides (they don't change the saved session), like --simple.
        // If both were given, Expert wins — the two are mutually exclusive.
        if (expert) {
            applyCliFocusMode(true);
        } else if (zen) {
            applyCliFocusMode(false);
        }
    }

    /** Invokes the one-shot {@link #pendingAfterRestore} action (if any), deferred one pulse. */
    private void runPendingAfterRestore() {
        Runnable r = pendingAfterRestore;
        pendingAfterRestore = null;
        if (r != null) {
            Platform.runLater(r);
        }
        // The session is fully restored now — re-enable HTTP auto-show and reconcile once for the active
        // buffer (the layout is settled, so the panel sizes correctly, unlike a mid-restore open).
        restoringSession = false;
        applyHttpAutoToolWindow();
    }

    /** Activates {@code dir} as the active project (startup-safe; no open buffers to confirm). */
    private void activateStartupProject(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        String name = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        Project p = projects.createOrGet(name, root);
        projects.setActive(p.id());
        projects.save();
        config.setWorkspaceStateFile(projects.stateFile(p)); // openInitialBuffer() then restores it
        projectPanel.setRoot(Path.of(p.root()));
        refreshProjectPanelList();
        updateWindowTitle();
    }

    /** Selects the tab for {@code file} (if open) and moves the caret to a 1-based line/column. */
    private void gotoInFile(Path file, int line1, int col1) {
        gotoInFile(file, line1, col1, true);
    }

    /** Jumps to {@code file}:{@code line1}:{@code col1}; {@code focusEditor} false leaves focus where it is. */
    private void gotoInFile(Path file, int line1, int col1, boolean focusEditor) {
        NavigationHistory.Location origin = (suppressNavRecord || navigating) ? null : captureCurrent();
        Tab tab = tabForPath(file);
        if (tab == null) {
            return;
        }
        tabPane.getSelectionModel().select(tab);
        EditorBuffer buffer = bufferOf(tab);
        CodeArea area = buffer.getArea();
        int total = area.getParagraphs().size();
        int line = Math.max(1, Math.min(total, line1)) - 1;
        int col = 0;
        if (col1 > 0) {
            int lineLen = area.getParagraphLength(line);
            col = Math.max(1, Math.min(lineLen + 1, col1)) - 1;
        }
        buffer.getFoldManager().unfoldContaining(line);
        int targetLine = line;
        int targetCol = col;
        area.moveTo(targetLine, targetCol);
        if (!suppressNavRecord && !navigating) {
            recordJump(origin, new NavigationHistory.Location(file, targetLine, targetCol));
        }
        area.requestFollowCaret();
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(targetLine);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
        if (focusEditor) {
            area.requestFocus();
        }
    }

    /** Loads a restored tab's content, large-file mode, folds, and caret (the tab already exists). */
    private void fillSessionBuffer(WorkspaceState.OpenFile f, EditorBuffer buffer) {
        Path file = Path.of(f.getPath());
        try {
            String note = loadInto(buffer, file);
            if (!note.isEmpty()) {
                setStatus(note);
            }
            restoreFolds(buffer);
            bookmarkCoordinator.restoreBookmarks(buffer);
            debugCoordinator.restoreBreakpoints(buffer);
            notesCoordinator.restoreNotes(buffer);
            restoreReadOnly(buffer);
            restoreMarkdownMode(buffer);
            // The tab (and its LSP session) was set up before content loaded; if the file just entered the
            // large-source tier, close the now-ineligible language server it may have started.
            if (buffer.isHeavyFile()) {
                lspCoordinator.syncBuffer(buffer);
            }
            CodeArea area = buffer.getArea();
            int caret = Math.max(0, Math.min(f.getCaret(), area.getLength()));
            area.moveTo(caret);
            // Defer the scroll until the tab is laid out (mirrors goToStart / StructurePanel.navigateTo).
            Platform.runLater(() -> {
                try {
                    area.showParagraphAtTop(area.getCurrentParagraph());
                } catch (RuntimeException ignored) {
                    // Viewport not ready; ignore.
                }
                // The minimap's first render can run before layout settles; refresh once it has.
                buffer.refreshMinimap();
            });
        } catch (IOException e) {
            // Unreadable now — leave the tab empty.
        }
    }

    public void setStatus(String message) {
        statusBar.setMessage(message);
    }

    private EditorBuffer activeBuffer() {
        return bufferOf(tabPane.getSelectionModel().getSelectedItem());
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

    private static final java.time.format.DateTimeFormatter UNDO_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());

    /** The checkpoint's capture time as {@code HH:mm:ss} for the popup's detail column. */
    private static String undoCheckpointTime(com.editora.editor.UndoHistory.Checkpoint c) {
        return UNDO_TIME.format(java.time.Instant.ofEpochMilli(c.epochMillis()));
    }

    /** Pages the active buffer's Markdown preview if it's the active scroll target (C-v / M-v). */
    private boolean pageActivePreview(boolean down) {
        EditorBuffer b = activeBuffer();
        return b != null && b.pagePreview(down);
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
        // Spell checking: share the user dictionary + persist "Add to Dictionary" (before applyViewSettings,
        // which sets the per-file language and enables checking).
        buffer.setSpellUserWords(config.getUserDictionary());
        buffer.setOnAddToDictionary(this::addUserWordAndRefreshAll);
        applyViewSettings(buffer);
        buffer.getFoldManager().setOnFoldStateChanged(() -> persistFolds(buffer));
        buffer.setOnBookmarksChanged(() -> bookmarkCoordinator.persistBookmarks(buffer));
        buffer.setGutterBookmarkClick(bookmarkCoordinator::onGutterBookmarkClick);
        buffer.setOnNotesChanged(() -> notesCoordinator.persistNotes(buffer));
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
        buffer.setAdminEditAvailable(elevationAvailable() && local); // "Edit as Administrator" on a locked file
        buffer.setHttpRunHandler(line -> httpClient.runRequest(buffer, line)); // .http request ▶
        buffer.setHttpEnabled(httpClient.isEnabled() && local);
        buffer.setMakeRunHandler(target -> runCoordinator.runMakeTarget(buffer, target)); // Makefile target ▶
        // Makefile-run rides the same Run-feature gate as Java/Python/shell (setRunEnabled above).
        // Debugging: the breakpoint gutter gate + change/hover hooks (debuggable languages only).
        debugCoordinator.wireBuffer(buffer);
        buffer.setAddNoteHandler(notesCoordinator::addNoteFromContext);
        buffer.setNotesEnabled(notesCoordinator.isEnabled());
        buffer.setOpenUrlHandler(this::openExternalUrl); // Ctrl/Cmd-click + open-link command
        buffer.setAiActionHandlers(aiCoordinator::explainSelection, aiCoordinator::rewriteSelection); // AI sel. bar
        buffer.setTableFileExporter(this::exportMarkdownTableFile); // Markdown table → CSV/Excel/ODS file
        buffer.setInsertTableHandler(this::markdownInsertTable); // Markdown format-bar "insert table" button
        buffer.setInsertTypstTableHandler(() -> showTableSizePicker(buffer::insertTypstTable)); // Typst #table
        buffer.setTypstImageHandler(() -> insertTypstImageFromChooser(buffer)); // Typst "Insert Image" menu
        buffer.setPreviewExportPngHandler(typst::exportPng); // Typst preview right-click → PNG
        buffer.setPreviewExportSvgHandler(typst::exportSvg); // Typst preview right-click → SVG
        // HTML Live Preview: the debounced edit pulse reloads the browser (only while this file is served).
        buffer.setHtmlPreviewDirtyListener(() -> htmlPreview.onBufferEdited(buffer));
        buffer.setFormatBarEnabled(config.getSettings().isMarkdownFormatBar());
        buffer.setPreviewExportPdfHandler(this::exportPreviewPdf); // preview right-click menu
        buffer.setPreviewPrintHandler(this::printPreview);
        buffer.setPreviewExportDocxHandler(this::exportPreviewDocx); // preview → MS Word
        buffer.setPreviewExportOdtHandler(this::exportPreviewOdt); // preview → OpenDocument
        buffer.setPreviewExportJsonHandler(this::exportMarkwhenJson); // Markwhen preview → JSON
        buffer.setTypstRootResolver(this::resolveTypstRoot); // typst --root: nearest typst.toml / project root
        buffer.setOnMarkwhenViewChanged(() -> persistMarkwhenView(buffer)); // persist timeline/calendar choice
        buffer.setOnEnableEditing(() -> enableEditing(buffer)); // "Enable Editing" banner button
        buffer.setSnippetProvider((lang, prefix) -> snippets.byPrefix(lang, prefix));
        buffer.setCompletionProvider(completion::complete);
        buffer.setAiCompletionProvider(aiCoordinator::inlineComplete);
        buffer.setAiCompletionEnabled(aiCoordinator.isInlineCompletionEnabled());
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
                multiCaretEnabled()); // multiple cursors + Alt+drag column selection (off in Simple UI mode)
        mermaid.wireBuffer(buffer); // live maid validator + initial lint state
        // Markdown linting: the overlay gets the diagnostics; the Lint tool window mirrors them live when
        // this buffer is the active one and the window is open.
        buffer.setMarkdownLintValidator((text, cb) ->
                markdownLintService.validate(text, effectiveMarkdownLintDisabled(buffer), diags -> {
                    cb.accept(diags);
                    if (activeBuffer() == buffer
                            && markdownLintToolWindow != null
                            && toolWindows.isOpen(markdownLintToolWindow)) {
                        markdownLintPanel.setResults(buffer.getPath(), diags);
                    }
                }));
        buffer.setMarkdownLintEnabled(markdownLintEnabled());
        buffer.setImageDropHandler(files -> insertDroppedImages(buffer, files)); // drag image → assets/ + ![](…)
        buffer.setWebImageDropHandler((image, url) -> insertWebImage(buffer, image, url)); // browser image → assets/
        // LSP: wire didChange/diagnostics/completion/format/nav hooks + open+activate if eligible.
        lspCoordinator.wireBuffer(buffer);
        ensurePreviewControls(buffer);
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
            if (AUTOSAVE_DELAY.equals(autoSaveMode())) {
                autoSaveIdleTimer.playFromStart();
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
            tabPane.getSelectionModel().select(tab);
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
        tabPane.getTabs().add(tab);
        if (select) {
            tabPane.getSelectionModel().select(tab);
            Platform.runLater(content.node()::requestFocus);
        }
        return tab;
    }

    /** Refreshes a tab's title (pin + dirty markers), style classes, and full-path tooltip. */
    private void updateTabMeta(Tab tab, EditorBuffer buffer) {
        // Keep the window title's file name/path + dirty marker in step (dirty flips, Save-As, rename).
        if (tab == tabPane.getSelectionModel().getSelectedItem()) {
            updateWindowTitle();
        }
        boolean dirty = buffer.isDirty();
        boolean isPinned = pinned.contains(tab);
        // The title lives in a graphic node (not tab.setText) so it can be a drag handle for
        // mouse reordering. Pinned tabs show an SVG pin graphic (matching the toolbar icons).
        Label title = new Label((dirty ? "• " : "") + buffer.getTitle());
        title.getStyleClass().add("tab-title");
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
        ObservableList<Tab> tabs = tabPane.getTabs();
        boolean draggedPinned = pinned.contains(dragged);
        reordering = true;
        try {
            tabs.remove(dragged);
            int idx = tabs.indexOf(target) + (after ? 1 : 0);
            int pinnedInStrip = (int) tabs.stream().filter(pinned::contains).count();
            int lo = draggedPinned ? 0 : pinnedInStrip;
            int hi = draggedPinned ? pinnedInStrip : tabs.size();
            tabs.add(Math.max(lo, Math.min(idx, hi)), dragged);
        } finally {
            reordering = false;
        }
        tabPane.getSelectionModel().select(dragged);
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
        newFromTemplate(null);
    }

    @FXML
    private void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.openFile.title"));
        Path file = pathOf(chooser.showOpenDialog(stage));
        if (file != null) {
            openPath(file);
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
        openPath(file);
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

    /** Open a file by path; refreshes recent files and reports status. */
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
        openPath(file);
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

    private void openPath(Path file) {
        Tab existing = tabForPath(file);
        if (existing != null) {
            // Already open — switch to its tab instead of opening a duplicate.
            tabPane.getSelectionModel().select(existing);
            EditorBuffer existingBuffer = bufferOf(existing);
            if (existingBuffer != null) {
                existingBuffer.getArea().requestFocus();
            }
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus(tr("status.alreadyOpen", file.getFileName()));
            return;
        }
        // A raster image opens in the read-only image viewer instead of dumping its bytes into a text buffer.
        if (ImageFormats.isSupported(file.getFileName().toString())) {
            openImageTab(file, true);
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus(tr("status.opened", file));
            return;
        }
        // A PDF opens in the read-only PDF viewer (rasterized pages) instead of the hex viewer.
        if (PdfViewerPane.isPdf(file.getFileName().toString())) {
            openPdfTab(file, true);
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus(tr("status.opened", file));
            return;
        }
        // A binary file opens in the read-only hex viewer instead of dumping its bytes as garbage text.
        if (looksBinaryFile(file)) {
            openHexTab(file, true);
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus(tr("status.opened", file));
            return;
        }
        openTextBuffer(file);
    }

    /** Loads {@code file} into a new text {@link EditorBuffer} tab (the normal text path, bypassing the
     *  image/binary routing in {@link #openPath}). Used by {@code openPath}'s fall-through and by the
     *  {@code view.openAsText} command (which forces a text open even for a binary / already-hex file). */
    private void openTextBuffer(Path file) {
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            String note = loadInto(buffer, file);
            // Apply folds before the node is in the scene, so each fold skips per-fold layout.
            restoreFolds(buffer);
            bookmarkCoordinator.restoreBookmarks(buffer);
            debugCoordinator.restoreBreakpoints(buffer);
            notesCoordinator.restoreNotes(buffer);
            restoreReadOnly(buffer); // before addBuffer so the tab meta reflects read-only
            addBuffer(buffer);
            restoreMarkdownMode(buffer); // after addBuffer so the toggle is wired
            // Land on the first line: replaceText leaves the caret at the end, and fold restoration
            // moves it to a fold header. Defer so the viewport scroll runs after the tab is laid out.
            Platform.runLater(buffer::goToStart);
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus(note.isEmpty() ? tr("status.opened", file) : note);
        } catch (IOException e) {
            setStatus(tr("status.failedOpen", e.getMessage()));
            if (recentFiles != null) {
                recentFiles.remove(file);
            }
        }
    }

    /** Command {@code view.openAsText}: opens the active file's bytes in a normal text buffer, bypassing the
     *  image/binary auto-routing (for a file mis-detected as binary, or to read a binary's raw text). No-op
     *  with a status when the active tab has no file. */
    private void openActiveAsText() {
        Path p = tabPath(tabPane.getSelectionModel().getSelectedItem());
        if (p == null) {
            setStatus(tr("status.hex.noFile"));
            return;
        }
        openTextBuffer(p);
    }

    /** Opens {@code file} in a read-only {@link ImageViewerPane} tab (raster images render, not their bytes). */
    private Tab openImageTab(Path file, boolean select) {
        ImageViewerPane pane = new ImageViewerPane(file);
        Tab tab = addContentTab(pane, select);
        // Disposal is driven by the tabs ListChangeListener (see disposeViewerTab) — setOnClosed here would
        // only fire for a click on the ✕, never for Ctrl-W / Close All / window close.
        Platform.runLater(pane::relayout); // fit-to-window once the tab is laid out
        return tab;
    }

    /** The {@link ImageViewerPane} in {@code tab}, or {@code null} for a buffer / non-image tab. */
    private static ImageViewerPane imagePaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof ImageViewerPane pane ? pane : null;
    }

    /** Opens {@code file} in a read-only {@link HexViewerPane} tab (a binary shows its bytes, not garbage text). */
    private Tab openHexTab(Path file, boolean select) {
        HexViewerPane pane = new HexViewerPane(file);
        Tab tab = addContentTab(pane, select);
        return tab;
    }

    /** The {@link HexViewerPane} in {@code tab}, or {@code null} for a buffer / non-hex tab. */
    private static HexViewerPane hexPaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof HexViewerPane pane ? pane : null;
    }

    /** Opens {@code file} in a read-only {@link PdfViewerPane} tab (a PDF renders its pages, not its bytes). */
    private Tab openPdfTab(Path file, boolean select) {
        PdfViewerPane pane = new PdfViewerPane(file);
        Tab tab = addContentTab(pane, select);
        Platform.runLater(pane::relayout); // fit-to-window once the tab is laid out
        return tab;
    }

    /** The {@link PdfViewerPane} in {@code tab}, or {@code null} for a buffer / non-PDF tab. */
    private static PdfViewerPane pdfPaneOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof PdfViewerPane pane ? pane : null;
    }

    /**
     * True when {@code file} looks like a binary (so it opens in the hex viewer, not as garbage text): reads a
     * small sample and applies the {@link BinarySniff} heuristic. Unreadable ⇒ false (the text path reports the
     * error). Skipped for the huge-file case is unnecessary — only a bounded {@link BinarySniff#SAMPLE_BYTES}
     * prefix is read regardless of file size.
     */
    private static boolean looksBinaryFile(Path file) {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            return BinarySniff.looksBinary(in.readNBytes(BinarySniff.SAMPLE_BYTES));
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /** Command {@code view.openAsHex}: opens the active file's bytes in a read-only hex tab (forces hex even
     *  for a text file). No-op with a status when the active tab has no file (an untitled buffer / Welcome). */
    private void openActiveAsHex() {
        Path p = tabPath(tabPane.getSelectionModel().getSelectedItem());
        if (p == null) {
            setStatus(tr("status.hex.noFile"));
            return;
        }
        openHexTab(p, true);
        setStatus(tr("status.opened", p));
    }

    /**
     * Reads {@code file} into {@code buffer} and applies the size-based mode, returning a status note
     * ({@code ""} for a normal file):
     * <ul>
     *   <li>≥ {@link EditorBuffer#HUGE_FILE_BYTES}: read at most that many chars (so a multi-GB file
     *       can't exhaust memory) and open read-only;</li>
     *   <li>≥ {@link EditorBuffer#LARGE_FILE_BYTES}: full read, but highlighting + minimap disabled;</li>
     *   <li>otherwise: full read, normal editing.</li>
     * </ul>
     */
    private String loadInto(EditorBuffer buffer, Path file) throws IOException {
        // One stat call for both size + mtime instead of two separate syscalls per file load.
        long size;
        long mtime;
        try {
            var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            size = attrs.size();
            mtime = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            size = fileSize(file);
            mtime = lastModifiedMillis(file);
        }
        buffer.setDiskSnapshot(mtime, size); // baseline for external-change detection
        boolean isLog = logViewer.handlesLogFile(file);
        // A huge file is read only PARTIALLY below. Flag that, so the save path can refuse: writing a
        // partial buffer back would truncate the user's file on disk (see EditorBuffer.setTruncatedLoad).
        buffer.setTruncatedLoad(size >= EditorBuffer.HUGE_FILE_BYTES);
        if (size >= EditorBuffer.HUGE_FILE_BYTES) {
            if (isLog) {
                // A huge log opens at its END (the tail is what matters) instead of the first chunk.
                com.editora.logviewer.LogTail.Tail tail =
                        com.editora.logviewer.LogTail.readTail(file, EditorBuffer.HUGE_FILE_BYTES);
                buffer.setContent(tail.text());
                buffer.setReadOnly(true);
                logViewer.recordLoadOffset(buffer, tail.offset()); // follow resumes from the true EOF
                return file.getFileName() + " — very large log (" + StatusBar.formatSize(size)
                        + "): read-only, showing last "
                        + StatusBar.formatSize(tail.text().length());
            }
            String content = readCapped(file, (int) EditorBuffer.HUGE_FILE_BYTES);
            buffer.setContent(content);
            buffer.setReadOnly(true);
            return file.getFileName() + " — very large file (" + StatusBar.formatSize(size)
                    + "): read-only, showing first " + StatusBar.formatSize(content.length());
        }
        buffer.setContent(readWithCharset(buffer, file));
        if (isLog) {
            logViewer.recordLoadOffset(buffer, size); // EOF at load — a later Follow streams only newer lines
        }
        if (size >= EditorBuffer.LARGE_FILE_BYTES) {
            buffer.setLargeFile(true);
            return largeFileNote(file);
        }
        // Intermediate tier: a very long single file (e.g. a 13k-line source) keeps highlighting + editing
        // but drops the minimap + LSP — the two heaviest features for a huge source — so it stays responsive.
        int threshold = config.getSettings().getLargeFileThreshold();
        if (threshold > 0 && buffer.lineCount() >= threshold) {
            buffer.setHeavyFile(true);
            return tr("status.largeFileTier", buffer.lineCount());
        }
        return "";
    }

    /**
     * Reads {@code file}, decoding by its BOM if present, else by the EditorConfig {@code charset} (so a
     * no-BOM Latin-1/UTF-16 file round-trips), else UTF-8. Records the chosen charset on the buffer so it
     * shows in the status bar and is reused on save. A read error falls back to plain UTF-8.
     */
    private String readWithCharset(EditorBuffer buffer, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = com.editora.editorconfig.EditorConfigCharset.resolveName(bytes, editorConfigCharsetFor(file));
        buffer.setDetectedCharset(name);
        return com.editora.editorconfig.EditorConfigCharset.decode(bytes, name);
    }

    /** The file's resolved {@code .editorconfig} charset, or null (EditorConfig off / remote / no rule). The
     *  BOM check that overrides it lives in {@link com.editora.editorconfig.EditorConfigCharset#resolveName}. */
    String editorConfigCharsetFor(Path file) {
        if (editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)) {
            return com.editora.editorconfig.EditorConfig.resolveFor(file).charset();
        }
        return null;
    }

    /** Reads up to {@code maxChars} characters (UTF-8) from {@code file}, bounding memory use. */
    private static String readCapped(Path file, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 1 << 20));
        char[] buf = new char[1 << 16];
        try (java.io.Reader r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            int read;
            while (sb.length() < maxChars
                    && (read = r.read(buf, 0, Math.min(buf.length, maxChars - sb.length()))) != -1) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }

    private String largeFileNote(Path file) {
        return file.getFileName() + " — large file (" + StatusBar.formatSize(fileSize(file))
                + "): syntax highlighting and minimap disabled";
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The file's last-modified time in epoch millis, or {@code -1} when unavailable. */
    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Checks the <em>active</em> tab's file against its on-disk snapshot (taken at load/save) and, if it
     * was modified by another program, prompts to reload or keep the in-editor version. Run when the
     * window regains focus and when the user switches tabs — so the prompt only appears for the file the
     * user is actually looking at (background tabs are checked when switched to). Deleted files are left
     * alone (the in-editor copy is kept).
     */
    private void checkExternalChanges() {
        if (checkingExternalChanges || tabPane == null) {
            return;
        }
        checkingExternalChanges = true;
        try {
            Tab tab = tabPane.getSelectionModel().getSelectedItem();
            EditorBuffer buffer = bufferOf(tab);
            if (buffer == null || buffer.getPath() == null) {
                return;
            }
            Path file = buffer.getPath();
            if (com.editora.vfs.Vfs.isRemote(file)) {
                return; // remote (SFTP) mtime polling would be a network call per focus — skipped for now
            }
            if (!Files.exists(file)) {
                return; // deleted/renamed externally — keep what's open (no prompt)
            }
            long mtime = lastModifiedMillis(file);
            long size = fileSize(file);
            if (buffer.diskChangedFrom(mtime, size)) {
                promptExternalChange(tab, buffer, mtime, size);
            }
        } finally {
            checkingExternalChanges = false;
        }
    }

    /** Asks whether to reload an externally-modified file; "keep" just re-baselines so it stops prompting. */
    private void promptExternalChange(Tab tab, EditorBuffer buffer, long mtime, long size) {
        String name = buffer.getPath().getFileName().toString();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.externalChange.title"));
        alert.setHeaderText(tr("dialog.externalChange.header", name));
        alert.setContentText(buffer.isDirty() ? tr("dialog.externalChange.dirty") : tr("dialog.externalChange.clean"));
        ButtonType reload = new ButtonType(tr("dialog.externalChange.reload"));
        ButtonType keep = new ButtonType(
                buffer.isDirty() ? tr("dialog.externalChange.keepMine") : tr("dialog.externalChange.keep"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reload, keep);
        if (alert.showAndWait().filter(b -> b == reload).isPresent()) {
            reloadFromDisk(tab, buffer);
        } else {
            buffer.setDiskSnapshot(mtime, size); // keep the editor's version, stop nagging about this change
        }
    }

    /** Reloads a buffer's content from disk, preserving the caret position as best it can. */
    private void reloadFromDisk(Tab tab, EditorBuffer buffer) {
        Path file = buffer.getPath();
        try {
            CodeArea area = buffer.getArea();
            int caret = area.getCaretPosition();
            historyCoordinator.record(
                    buffer, HistoryRevision.REASON_EXTERNAL); // snapshot the in-memory version before disk wins
            String note = loadInto(buffer, file); // replaces content + re-baselines the disk snapshot
            buffer.markClean();
            area.moveTo(Math.min(caret, area.getLength()));
            updateTabMeta(tab, buffer);
            setStatus(note.isEmpty() ? tr("status.reloaded", file.getFileName()) : note);
        } catch (IOException e) {
            setStatus(tr("status.failedReload", file.getFileName(), e.getMessage()));
        }
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

    @FXML
    private void onSave() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            save(buffer);
        }
    }

    /** {@code file.saveAsAdmin}: write the active buffer via the OS auth agent (root-owned files). */
    private void onSaveAsAdmin() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        if (buffer.getPath() == null) {
            saveAs(buffer);
            return;
        }
        if (!elevationAvailable()) {
            setStatus(tr("status.admin.unavailable"));
            return;
        }
        saveAsAdmin(buffer);
    }

    @FXML
    private void onSaveAs() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            saveAs(buffer);
        }
    }

    /** @return true if the buffer is on disk afterwards (either already saved or just saved). */
    /** Whether the elevation tool is present (macOS osascript always; Linux pkexec probed on PATH). */
    private boolean adminToolAvailable;

    /** Admin-save is enabled in Settings and the OS supports it (Linux/polkit or macOS/osascript). */
    private boolean adminSaveEnabled() {
        return config.getSettings().isAdminSave()
                && com.editora.process.ElevatedSave.supportedOnOs(System.getProperty("os.name"));
    }

    /** Admin-save is enabled and the elevation tool (pkexec/osascript) is actually available. */
    private boolean elevationAvailable() {
        return adminSaveEnabled() && adminToolAvailable;
    }

    /** True when a plain save of {@code p} would fail for permissions but an elevated save could succeed. */
    private boolean adminSaveApplicable(Path p) {
        return elevationAvailable()
                && p != null
                && com.editora.vfs.Vfs.isLocal(p)
                && Files.exists(p)
                && !Files.isWritable(p);
    }

    /**
     * Detects the elevation tool when admin-save is enabled and pushes the "Edit as Administrator"
     * affordance to every open buffer. macOS ships {@code osascript}, so it's available immediately;
     * Linux probes {@code pkexec} off the FX thread (it spawns a process) and updates the gate when it
     * returns. Mirrors {@code applyRipgrepSupport}.
     */
    private void applyAdminSaveSupport() {
        if (!adminSaveEnabled()) {
            adminToolAvailable = false;
            pushAdminEditAvailable();
            return;
        }
        if (com.editora.process.ElevatedSave.isMac(System.getProperty("os.name"))) {
            adminToolAvailable = true; // osascript is always present on macOS
            pushAdminEditAvailable();
            return;
        }
        new Thread(
                        () -> {
                            boolean ok;
                            try {
                                ok = com.editora.process.ProcessRunner.run(
                                                        null,
                                                        java.time.Duration.ofSeconds(5),
                                                        List.of(com.editora.process.ElevatedSave.PKEXEC, "--version"))
                                                .exit()
                                        == 0;
                            } catch (RuntimeException e) {
                                ok = false;
                            }
                            boolean available = ok;
                            Platform.runLater(() -> {
                                adminToolAvailable = available;
                                pushAdminEditAvailable();
                            });
                        },
                        "pkexec-detect")
                .start();
    }

    /** Pushes the current "Edit as Administrator" availability to every open buffer's banner. */
    private void pushAdminEditAvailable() {
        boolean available = elevationAvailable();
        for (Tab t : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(t);
            if (b != null) {
                b.setAdminEditAvailable(available && isLocalBuffer(b));
            }
        }
    }

    /**
     * Blocks every write path for a buffer the loader could only read <em>part</em> of (a file at/over
     * {@link EditorBuffer#HUGE_FILE_BYTES}: the first 50 MB, or a log's <em>last</em> 50 MB). Such a buffer is
     * a slice, not the file — writing it back with {@code Files.write} would truncate the real file on disk
     * and destroy everything outside the slice. The buffer is read-only, but that only stops <em>typing</em>:
     * {@code file.save} (Ctrl/Cmd-S) needs no edit and no dirty flag to fire, so it has to be refused here.
     *
     * @return true when the save was refused (the caller must not write).
     */
    private boolean refuseTruncatedSave(EditorBuffer buffer) {
        if (buffer == null || !buffer.isTruncatedLoad()) {
            return false;
        }
        setStatus(tr("status.truncatedNoSave"));
        return true;
    }

    private boolean save(EditorBuffer buffer) {
        if (refuseTruncatedSave(buffer)) {
            return false;
        }
        if (buffer.getPath() == null) {
            return saveAs(buffer);
        }
        if (adminSaveApplicable(buffer.getPath())) {
            saveAsAdmin(buffer); // async elevated write; the buffer stays dirty until it completes
            return false;
        }
        return writeBuffer(buffer, buffer.getPath());
    }

    /**
     * Writes {@code buffer} to its (e.g. root-owned) file via the OS auth agent ({@code pkexec}/polkit),
     * which prompts for the password itself — Editora never handles it. The bytes go to a private temp
     * file, then {@code cat tmp > target} runs as root, truncating the target in place so its owner and
     * permissions are preserved. Runs off the FX thread (the auth dialog blocks); the result is applied back
     * on the FX thread.
     */
    private void saveAsAdmin(EditorBuffer buffer) {
        Path target = buffer.getPath();
        if (target == null) {
            saveAs(buffer);
            return;
        }
        if (!elevationAvailable()) {
            setStatus(tr("status.admin.unavailable"));
            return;
        }
        byte[] bytes = saveBytes(buffer); // pure transform + encode on the FX thread
        setStatus(tr("status.admin.saving", target));
        new Thread(
                        () -> {
                            Path tmp = null;
                            int exit;
                            String err;
                            try {
                                tmp = Files.createTempFile("editora-admin-", ".tmp");
                                try {
                                    Files.setPosixFilePermissions(
                                            tmp,
                                            java.util.Set.of(
                                                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                                } catch (UnsupportedOperationException | IOException ignore) {
                                    // Non-POSIX filesystem: the temp file keeps default permissions.
                                }
                                Files.write(tmp, bytes);
                                var r = com.editora.process.ProcessRunner.run(
                                        null,
                                        java.time.Duration.ofMinutes(2),
                                        com.editora.process.ElevatedSave.elevatedArgv(
                                                System.getProperty("os.name"),
                                                com.editora.process.ElevatedSave.PKEXEC,
                                                tmp,
                                                target));
                                exit = r.exit();
                                err = r.err();
                            } catch (IOException | RuntimeException e) {
                                exit = -1;
                                err = e.getMessage();
                            } finally {
                                if (tmp != null) {
                                    try {
                                        Files.deleteIfExists(tmp);
                                    } catch (IOException ignore) {
                                        // best-effort cleanup
                                    }
                                }
                            }
                            int code = exit;
                            String detail = err;
                            Platform.runLater(() -> onAdminSaveDone(buffer, target, code, detail));
                        },
                        "admin-save")
                .start();
    }

    /** Applies the outcome of an elevated save on the FX thread (ok / user-cancelled / failed). */
    private void onAdminSaveDone(EditorBuffer buffer, Path target, int exit, String err) {
        if (exit == 0) {
            historyCoordinator.record(buffer, HistoryRevision.REASON_SAVE);
            buffer.markClean();
            buffer.setDiskSnapshot(lastModifiedMillis(target), fileSize(target));
            setStatus(tr("status.admin.saved", target));
            git.refresh();
            lspCoordinator.notifyDocumentSaved(buffer);
            Tab tab = tabForBuffer(buffer);
            if (tab != null) {
                updateTabMeta(tab, buffer);
            }
        } else if (com.editora.process.ElevatedSave.isCancellation(System.getProperty("os.name"), exit, err)) {
            setStatus(tr("status.admin.cancelled")); // user dismissed the auth dialog / not authorized
        } else {
            setStatus(tr("status.admin.failed", err == null || err.isBlank() ? String.valueOf(exit) : err));
        }
    }

    private boolean saveAs(EditorBuffer buffer) {
        if (refuseTruncatedSave(buffer)) {
            return false;
        }
        if (com.editora.vfs.Vfs.isRemote(buffer.getPath())) {
            // A remote buffer always opens with a path, so plain Save writes it back over SFTP; choosing a
            // new remote destination (an async prompt) isn't supported yet.
            setStatus(tr("status.remote.saveAsUnsupported"));
            return false;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveAs.title"));
        if (buffer.getDisplayName() != null) {
            chooser.setInitialFileName(buffer.getDisplayName()); // suggested name from --new-file=NAME
        }
        Path file = pathOf(chooser.showSaveDialog(stage));
        if (file == null) {
            return false;
        }
        return applySaveAsTarget(buffer, file);
    }

    /** Points {@code buffer} at {@code file}, refreshes its previews/tab/breadcrumb, and writes it. */
    private boolean applySaveAsTarget(EditorBuffer buffer, Path file) {
        buffer.setPath(file);
        // The buffer's EditorConfig properties + charset were resolved against the OLD path. Without
        // re-resolving, a Save-As into another tree writes with the previous project's charset/EOL/trim rules
        // (and keeps doing so on every later save), while an untitled buffer saved INTO a project with an
        // .editorconfig gets none of its rules.
        applyEditorConfig(buffer);
        ensurePreviewControls(buffer); // a new untitled saved as .md/.mmd now gets the preview toggle
        htmlPreview.ensureControl(buffer); // a save-as to .html now gets the "open in browser" globe
        logViewer.ensureControl(buffer); // a save-as to .log now gets the log control + level overlay
        boolean ok = writeBuffer(buffer, file);
        Tab tab = tabFor(buffer);
        if (tab != null) {
            updateTabMeta(tab, buffer);
        }
        if (buffer == activeBuffer()) {
            breadcrumb.setActiveFile(buffer.getPath());
            updateProjectFolderView(); // global window: an untitled buffer just gained a folder to show
        }
        return ok;
    }

    /**
     * The keyboard-first Save As: instead of the native file chooser (which the toolbar button uses), prompt
     * for the target path in the in-scene overlay so the command palette / a keybinding stay mouse-free. The
     * field is pre-filled with the current path (or the project folder + suggested name for an untitled
     * buffer); a relative name resolves against that folder, {@code ~} expands to home. Confirms before
     * overwriting a different existing file.
     */
    private void saveAsPrompt(EditorBuffer buffer) {
        if (buffer == null || refuseTruncatedSave(buffer)) {
            return;
        }
        if (buffer.getPath() != null && com.editora.vfs.Vfs.isRemote(buffer.getPath())) {
            setStatus(tr("status.remote.saveAsUnsupported"));
            return;
        }
        Path base = saveAsBaseDir(buffer);
        promptText(tr("dialog.saveAs.title"), tr("dialog.saveAs.prompt"), saveAsInitial(buffer, base), value -> {
            Path target = com.editora.config.PathKeys.resolveUserInput(value, base, System.getProperty("user.home"));
            if (target == null) {
                setStatus(tr("status.saveAs.invalidPath"));
                return;
            }
            Path current = buffer.getPath();
            boolean overwritingOther = Files.exists(target)
                    && (current == null || !com.editora.config.PathKeys.sameNormalized(target, current));
            if (overwritingOther && !confirmOverwrite(target)) {
                return;
            }
            applySaveAsTarget(buffer, target);
        });
    }

    /** The folder a typed Save-As name resolves against: the current file's folder, else project root/home. */
    private Path saveAsBaseDir(EditorBuffer buffer) {
        Path p = buffer.getPath();
        if (p != null && com.editora.vfs.Vfs.isLocal(p) && p.getParent() != null) {
            return p.getParent();
        }
        Path root = projectPanel == null ? null : projectPanel.getRoot();
        if (root != null && com.editora.vfs.Vfs.isLocal(root)) {
            return root;
        }
        return Path.of(System.getProperty("user.home"));
    }

    /** The Save-As prompt's pre-filled value: the current absolute path, else the base folder + a name. */
    private String saveAsInitial(EditorBuffer buffer, Path base) {
        Path p = buffer.getPath();
        if (p != null && com.editora.vfs.Vfs.isLocal(p)) {
            return p.toString();
        }
        String name = buffer.getDisplayName() != null ? buffer.getDisplayName() : "untitled.txt";
        return base.resolve(name).toString();
    }

    /** Native confirmation before overwriting an existing (different) file from the keyboard Save-As prompt. */
    private boolean confirmOverwrite(Path target) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.saveAs.overwrite.title"));
        alert.setHeaderText(tr("dialog.saveAs.overwrite.header", target.getFileName()));
        alert.setContentText(tr("dialog.saveAs.overwrite.content"));
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /**
     * The exact bytes to write for {@code buffer}: the EditorConfig save transforms (trim trailing
     * whitespace / final newline / end-of-line) applied to the text, then encoded in the effective charset
     * (BOM-aware). The live document is not mutated — only what we write. Inert (content + detected charset)
     * when EditorConfig is off.
     */
    private byte[] saveBytes(EditorBuffer buffer) {
        com.editora.editorconfig.EditorConfigProperties p = editorConfigEnabled()
                ? buffer.getEditorConfigProps()
                : com.editora.editorconfig.EditorConfigProperties.EMPTY;
        String text = com.editora.editorconfig.EditorConfigTransform.transform(buffer.getContent(), p);
        String charset = buffer.getEffectiveCharset();
        // A charset that can't represent what the user typed (an em dash / curly quote / emoji under
        // `charset = latin1`) would be written as '?' by String.getBytes — and the editor keeps showing the
        // real character until the file is reopened, so the corruption is invisible until it's permanent.
        // Fall back to UTF-8 and say so: a changed encoding is recoverable, mangled text is not.
        if (!com.editora.editorconfig.EditorConfigCharset.canEncode(text, charset)) {
            setStatus(tr("status.charsetFallback", com.editora.editorconfig.EditorConfigCharset.displayName(charset)));
            charset = com.editora.editorconfig.EditorConfigCharset.UTF_8;
        }
        return com.editora.editorconfig.EditorConfigCharset.encode(text, charset);
    }

    private boolean writeBuffer(EditorBuffer buffer, Path file) {
        try {
            byte[] bytes = saveBytes(buffer);
            // Serialized against auto-save (see autoSaveBuffer): a queued auto-save holding an OLDER snapshot
            // must not land after this write and rewind the file.
            synchronized (fileWriteLock) {
                com.editora.io.AtomicFileWrite.write(file, bytes);
            }
            historyCoordinator.record(buffer, HistoryRevision.REASON_SAVE); // snapshot the just-saved version
            buffer.markClean();
            buffer.setDiskSnapshot(lastModifiedMillis(file), fileSize(file)); // our own write isn't "external"
            setStatus(tr("status.saved", file));
            git.refresh(); // a save changes the working tree → update gutter + status
            refreshBuildTools(); // a saved marker file (or a project-root change) may change the detected model
            // LSP: a save-as of a new Java file opens it on the server; then notify didSave.
            lspCoordinator.syncBuffer(buffer);
            lspCoordinator.notifyDocumentSaved(buffer);
            return true;
        } catch (IOException e) {
            setStatus(tr("status.failedSave", e.getMessage()));
            return false;
        }
    }

    /** Normalizes a stored auto-save mode string to a known key (unknown ⇒ off). */
    static String autoSaveModeOf(String mode) {
        return AUTOSAVE_DELAY.equals(mode) || AUTOSAVE_FOCUS.equals(mode) ? mode : AUTOSAVE_OFF;
    }

    /** Current auto-save mode, parsed leniently from settings. */
    private String autoSaveMode() {
        return autoSaveModeOf(config.getSettings().getAutoSave());
    }

    /** Applies the auto-save setting: refreshes the idle-timer delay and stops it unless in delay mode. */
    private void applyAutoSave() {
        autoSaveIdleTimer.setDuration(
                Duration.millis(Math.max(100, config.getSettings().getAutoSaveDelayMillis())));
        if (!AUTOSAVE_DELAY.equals(autoSaveMode())) {
            autoSaveIdleTimer.stop();
        }
    }

    /** Auto-saves every dirty, file-backed, writable buffer (untitled/read-only buffers are skipped). */
    private void autoSaveAllDirty() {
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null && buffer.isDirty() && buffer.getPath() != null && buffer.isEditable()) {
                autoSaveBuffer(buffer);
            }
        }
    }

    /**
     * Writes {@code buffer} to disk off the UI thread: snapshots the text + path here, writes on a
     * background thread, then clears the dirty flag on the FX thread only if the content is unchanged
     * (so we never mark clean over edits made after the snapshot).
     */
    private void autoSaveBuffer(EditorBuffer buffer) {
        Path file = buffer.getPath();
        // Never auto-save over a file that changed underneath us. checkExternalChanges() only inspects the
        // ACTIVE tab, so a dirty background buffer whose file was rewritten (a git checkout, a generator)
        // would otherwise be silently overwritten by a timer, with no prompt and no way back.
        if (buffer.diskChangedFrom(lastModifiedMillis(file), fileSize(file))) {
            return; // leave it dirty: the external-change prompt handles it when the user comes back to it
        }
        String content = buffer.getContent();
        byte[] bytes = saveBytes(buffer); // transform + encode on the FX thread (pure); write off-thread
        historyCoordinator.record(buffer, HistoryRevision.REASON_AUTOSAVE); // snapshot before the off-thread write
        autoSaveExecutor.submit(() -> {
            try {
                // Take the same lock a manual save takes, and re-check the buffer is still dirty inside it:
                // a Ctrl-S landing between the snapshot above and this write has already written NEWER bytes,
                // and writing our stale snapshot on top would silently rewind the user's file.
                synchronized (fileWriteLock) {
                    if (!buffer.isDirty()) {
                        return; // already saved (manually) — our snapshot is stale
                    }
                    com.editora.io.AtomicFileWrite.write(file, bytes);
                }
                Platform.runLater(() -> {
                    if (content.equals(buffer.getContent())) {
                        buffer.markClean();
                    }
                    buffer.setDiskSnapshot(lastModifiedMillis(file), fileSize(file)); // our write, not external
                    setStatus(tr("status.autoSaved", file.getFileName()));
                    git.refresh();
                });
            } catch (IOException e) {
                Platform.runLater(() -> setStatus(tr("status.autoSaveFailed", e.getMessage())));
            }
        });
    }

    /**
     * Serializes writes to the user's files. A manual save writes synchronously on the FX thread while
     * auto-save writes an earlier snapshot on its own executor — without this, the queued auto-save could land
     * after the manual save and put the older content back on disk, while the buffer showed "saved".
     */
    private final Object fileWriteLock = new Object();

    private void toggleAutoSave() {
        String next =
                switch (autoSaveMode()) {
                    case AUTOSAVE_OFF -> AUTOSAVE_DELAY;
                    case AUTOSAVE_DELAY -> AUTOSAVE_FOCUS;
                    default -> AUTOSAVE_OFF;
                };
        config.getSettings().setAutoSave(next);
        requestSave();
        applyAutoSave();
        setStatus(tr("status.autoSave", autoSaveLabel(next)));
    }

    /** Human-readable label for an auto-save mode key (also used by the settings combo). */
    static String autoSaveLabel(String mode) {
        return switch (mode) {
            case AUTOSAVE_DELAY -> tr("autosave.delay");
            case AUTOSAVE_FOCUS -> tr("autosave.focus");
            default -> tr("autosave.off");
        };
    }

    @FXML
    private void onCloseTab() {
        closeTab(activeTab());
    }

    private Tab activeTab() {
        return tabPane.getSelectionModel().getSelectedItem();
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
            tabPane.getTabs().remove(tab);
        }
    }

    /**
     * Closes each tab in {@code targets} (a snapshot), prompting for dirty buffers and stopping if
     * the user cancels — mirroring {@link #confirmCloseAllBuffers()}.
     */
    private void closeTabs(List<Tab> targets) {
        for (Tab tab : targets) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null && !buffer.isDirty()) {
                tabPane.getTabs().remove(tab);
                continue;
            }
            tabPane.getSelectionModel().select(tab);
            if (buffer != null && !confirmCloseIfDirty(buffer)) {
                return; // user cancelled — stop the batch
            }
            tabPane.getTabs().remove(tab);
        }
    }

    /** Non-pinned tabs whose index is less than {@code pivot}'s. */
    private List<Tab> eligibleToLeft(Tab pivot) {
        int idx = tabPane.getTabs().indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            Tab t = tabPane.getTabs().get(i);
            if (!pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Non-pinned tabs whose index is greater than {@code pivot}'s. */
    private List<Tab> eligibleToRight(Tab pivot) {
        int idx = tabPane.getTabs().indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = idx + 1; i < tabPane.getTabs().size(); i++) {
            Tab t = tabPane.getTabs().get(i);
            if (!pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private void closeOtherTabs(Tab keep) {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabPane.getTabs()) {
            if (t != keep && !pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeAllTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabPane.getTabs()) {
            if (!pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeUnmodifiedTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabPane.getTabs()) {
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
        int from = tabPane.getTabs().indexOf(tab);
        if (from < 0) {
            return;
        }
        reordering = true;
        try {
            tabPane.getTabs().remove(tab);
            int clamped = Math.max(0, Math.min(target, tabPane.getTabs().size()));
            tabPane.getTabs().add(clamped, tab);
        } finally {
            reordering = false;
        }
        tabPane.getSelectionModel().select(tab);
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
                    try {
                        Files.move(old, target);
                    } catch (IOException e) {
                        setStatus(tr("status.renameFailed", e.getMessage()));
                        return;
                    }
                    buffer.setPath(target); // re-detects language/grammar
                    ensurePreviewControls(buffer); // a rename to/from .md/.mmd flips previewability
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
        save.setOnAction(e -> save(buffer));
        MenuItem saveAs = new MenuItem(tr("menu.saveAs"));
        saveAs.setGraphic(Icons.saveAs());
        saveAs.setOnAction(e -> saveAs(buffer));
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
        MenuItem diffCommit = new MenuItem(tr("project.menu.git.compareRevision"));
        diffCommit.setGraphic(Icons.diff());
        diffCommit.setOnAction(e -> git.ifEnabled(() -> diffCoordinator.diffPathVsCommit(buffer.getPath())));
        MenuItem annotate = new MenuItem(tr("project.menu.git.annotate"));
        annotate.setGraphic(Icons.blame());
        annotate.setOnAction(e -> git.ifEnabled(() -> {
            openPath(buffer.getPath());
            git.annotateActive();
        }));
        MenuItem history = new MenuItem(tr("project.menu.git.fileHistory"));
        history.setGraphic(Icons.gitLog());
        history.setOnAction(e -> git.ifEnabled(() -> gitFileHistoryForPath(buffer.getPath())));
        gitMenu.getItems()
                .addAll(
                        stage,
                        unstage,
                        revert,
                        ignore,
                        new SeparatorMenuItem(),
                        diffHead,
                        diffBranch,
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
        if (!buffer.isDirty()) {
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
            return save(buffer);
        }
        return true; // discard
    }

    private Tab tabFor(EditorBuffer buffer) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() == buffer) {
                return tab;
            }
        }
        return null;
    }

    /**
     * The tab whose buffer is backed by {@code file}, or {@code null} if it isn't open. Paths are
     * compared as normalized absolute paths so relative vs. absolute (or {@code .}/{@code ..})
     * spellings of the same file still match. Untitled buffers (no path) are skipped.
     */
    /** True if {@code file} is open in a tab whose buffer has unsaved changes. */
    private boolean isPathModified(Path file) {
        Tab tab = tabForPath(file);
        if (tab == null) {
            return false;
        }
        EditorBuffer buffer = bufferOf(tab);
        return buffer != null && buffer.isDirty();
    }

    private Tab tabForPath(Path file) {
        String target = pathKey(file);
        for (Tab tab : tabPane.getTabs()) {
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
    @FXML
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
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == quit;
    }

    /** Walks every tab and prompts to save/discard each dirty buffer, then persists this window's session.
     *  False = the user cancelled. Package-visible: the quit path drives it for every window. */
    boolean confirmCloseAllBuffers() {
        for (Tab tab : new ArrayList<>(tabPane.getTabs())) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer == null || !buffer.isDirty()) {
                continue;
            }
            tabPane.getSelectionModel().select(tab);
            if (!confirmCloseIfDirty(buffer)) {
                return false;
            }
        }
        persistSession();
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

    private void persistSession() {
        List<WorkspaceState.OpenFile> files = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            Path p = tabPath(tab); // buffer or image-viewer path (image tabs restore too)
            if (p != null) {
                int caret = buffer != null ? buffer.getArea().getCaretPosition() : 0;
                // Vfs.toStorableString keeps a remote file's sftp:// URI — a bare path would be reopened as a
                // *local* file on restart (a same-named local file could silently open in its place).
                files.add(new WorkspaceState.OpenFile(
                        com.editora.vfs.Vfs.toStorableString(p), caret, pinned.contains(tab)));
            }
        }
        WorkspaceState state = config.getWorkspaceState();
        state.setOpenFiles(files);
        Path activePath = tabPath(tabPane.getSelectionModel().getSelectedItem());
        state.setActiveFile(activePath != null ? com.editora.vfs.Vfs.toStorableString(activePath) : "");
        persistWindowBounds(state);
        toolWindows.persistDividers(); // capture a divider dragged but left open (close() only saves on hide)
        restoreCliFocusToolWindows(state);
        config.save(); // durable flush on quit — not coalesced
    }

    /**
     * Undoes the persisted side-effects of a {@code --zen}/{@code --expert} session override, so the flag
     * really is session-only: entering the mode closed the docked tool windows and {@code close()} persisted
     * "nothing open", which would otherwise lose them on the next (flagless) launch. Only fills a side the
     * user hasn't since reopened by hand, and leaves the pre-focus snapshots out of the saved file.
     */
    private void restoreCliFocusToolWindows(WorkspaceState state) {
        if (cliFocusToolWindows == null) {
            return;
        }
        if (state.getOpenLeftToolWindow().isEmpty()) {
            state.setOpenLeftToolWindow(cliFocusToolWindows[0]);
        }
        if (state.getOpenRightToolWindow().isEmpty()) {
            state.setOpenRightToolWindow(cliFocusToolWindows[1]);
        }
        if (state.getOpenBottomToolWindow().isEmpty()) {
            state.setOpenBottomToolWindow(cliFocusToolWindows[2]);
        }
        state.getPreZenToolWindows().clear(); // a snapshot of a mode that was never saved as "on"
        state.getPreExpertToolWindows().clear();
    }

    /** Records the main window's geometry. When maximized, keep the last normal bounds so
     *  un-maximizing on the next launch restores a sensible size. */
    private void persistWindowBounds(WorkspaceState state) {
        if (stage == null) {
            return;
        }
        state.setWindowMaximized(stage.isMaximized());
        if (!stage.isMaximized()) {
            state.setWindowX(stage.getX());
            state.setWindowY(stage.getY());
            state.setWindowWidth(stage.getWidth());
            state.setWindowHeight(stage.getHeight());
        }
    }

    private void nextBuffer() {
        int count = tabPane.getTabs().size();
        if (count > 1) {
            int idx = (tabPane.getSelectionModel().getSelectedIndex() + 1) % count;
            tabPane.getSelectionModel().select(idx);
        }
    }

    // --- Edit actions (delegate to active CodeArea) ---

    @FXML
    private void onUndo() {
        withArea(CodeArea::undo);
    }

    @FXML
    private void onRedo() {
        withArea(CodeArea::redo);
    }

    @FXML
    private void onCut() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer b = activeBuffer();
        if (b != null && b.multiCaretCut()) { // every caret's selection, one undoable step
            deactivateMark();
            refreshPasteState();
            setStatus(tr("status.cut"));
            return;
        }
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        boolean had = area.getSelection().getLength() > 0;
        if (!had && config.getSettings().isCopyLineWhenNoSelection() && b != null) {
            b.cutCurrentLine(); // empty selection → cut the whole current line (VS Code editor.emptySelectionClipboard)
            deactivateMark();
            refreshPasteState();
            setStatus(tr("status.cutLine"));
            return;
        }
        area.cut();
        deactivateMark();
        refreshPasteState(); // clipboard now has content
        setStatus(tr(had ? "status.cut" : "status.nothingToCut"));
    }

    @FXML
    private void onCopy() {
        EditorBuffer b = activeBuffer();
        if (b != null && b.multiCaretCopy()) { // every caret's selection (VS Code one-line-per-caret)
            deactivateMark();
            refreshPasteState();
            setStatus(tr("status.copied"));
            return;
        }
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        boolean had = area.getSelection().getLength() > 0;
        if (!had && config.getSettings().isCopyLineWhenNoSelection() && b != null) {
            b.copyCurrentLine(); // empty selection → copy the whole current line (VS Code
            // editor.emptySelectionClipboard)
            deactivateMark();
            refreshPasteState();
            setStatus(tr("status.copiedLine"));
            return;
        }
        area.copy();
        deactivateMark();
        refreshPasteState(); // clipboard now has content
        setStatus(tr(had ? "status.copied" : "status.nothingToCopy"));
    }

    @FXML
    private void onPaste() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer b = activeBuffer();
        if (b != null && tryMarkdownImagePaste(b)) { // a clipboard image → save to assets/ + insert ![](…)
            deactivateMark();
            return;
        }
        if (b != null && b.trySmartLinkPaste()) { // a clipboard URL over a selection → [selection](url)
            deactivateMark();
            setStatus(tr("status.markdown.linkPasted"));
            return;
        }
        if (b != null && b.multiCaretPaste()) { // distribute clipboard lines one per caret
            deactivateMark();
            setStatus(tr("status.pasted"));
            return;
        }
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        area.paste();
        deactivateMark();
        setStatus(tr("status.pasted"));
    }

    // --- Markdown image paste / drop -------------------------------------------------------------

    /** If the clipboard holds an image and the active buffer is a saved local Markdown file, save it under
     *  {@code assets/} and insert {@code ![](…)}. Returns true if it handled (or claimed) the paste. */
    /** The image-reference snippet for {@code b}: Typst {@code #image("rel")} or Markdown {@code ![alt](rel)}. */
    private static String markupImageSnippet(EditorBuffer b, String rel, String alt) {
        return b.isTypst()
                ? com.editora.typst.TypstMarkup.image(rel)
                : com.editora.markdown.MarkdownImagePaste.snippet(rel, alt);
    }

    /** The Typst "Insert Image" menu action: pick an image file, copy it into the doc's {@code assets/}
     *  dir, and insert {@code #image("assets/…")}. Requires a saved local buffer (so assets/ resolves). */
    private void insertTypstImageFromChooser(EditorBuffer b) {
        if (b.getPath() == null || !isLocalBuffer(b)) {
            setStatus(tr("status.markdown.imageNeedsSave"));
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("command.typst.insertImage"));
        chooser.getExtensionFilters()
                .add(new javafx.stage.FileChooser.ExtensionFilter(
                        "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg", "*.bmp"));
        java.io.File f = chooser.showOpenDialog(stage);
        if (f != null) {
            insertDroppedImages(b, java.util.List.of(f));
        }
    }

    private boolean tryMarkdownImagePaste(EditorBuffer b) {
        if ((!b.isMarkdown() && !b.isTypst()) || !b.isEditable()) {
            return false;
        }
        if (!javafx.scene.input.Clipboard.getSystemClipboard().hasImage()) {
            return false;
        }
        if (!isLocalBuffer(b) || b.getPath() == null) {
            setStatus(tr("status.markdown.imageNeedsSave"));
            return true; // claim it: a raw image can't be pasted as text anyway
        }
        javafx.scene.image.Image img =
                javafx.scene.input.Clipboard.getSystemClipboard().getImage();
        if (img == null) {
            return false;
        }
        try {
            java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
            java.nio.file.Path assets = baseDir.resolve(com.editora.markdown.MarkdownImagePaste.ASSETS_DIR);
            java.nio.file.Files.createDirectories(assets);
            String name = com.editora.markdown.MarkdownImagePaste.uniqueFileName(
                    n -> java.nio.file.Files.exists(assets.resolve(n)), "pasted-image", "png");
            java.nio.file.Path target = assets.resolve(name);
            writeFxImageToPng(img, target);
            String rel = com.editora.markdown.MarkdownImagePaste.relativePath(baseDir, target);
            b.insertAtCaret(markupImageSnippet(b, rel, ""));
            setStatus(tr("status.markdown.imagePasted", rel));
        } catch (Exception ex) {
            setStatus(tr("status.markdown.imageFailed", String.valueOf(ex.getMessage())));
        }
        return true;
    }

    /** Copies dropped image files into the Markdown file's {@code assets/} dir and inserts a link for each. */
    private void insertDroppedImages(EditorBuffer b, java.util.List<java.io.File> files) {
        if (b.getPath() == null || !isLocalBuffer(b)) {
            setStatus(tr("status.markdown.imageNeedsSave"));
            return;
        }
        try {
            java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
            java.nio.file.Path assets = baseDir.resolve(com.editora.markdown.MarkdownImagePaste.ASSETS_DIR);
            java.nio.file.Files.createDirectories(assets);
            StringBuilder out = new StringBuilder();
            for (java.io.File f : files) {
                String ext = extensionOf(f.getName());
                String base = stripExtension(f.getName());
                String name = com.editora.markdown.MarkdownImagePaste.uniqueFileName(
                        n -> java.nio.file.Files.exists(assets.resolve(n)), base, ext);
                java.nio.file.Path target = assets.resolve(name);
                java.nio.file.Files.copy(f.toPath(), target);
                String rel = com.editora.markdown.MarkdownImagePaste.relativePath(baseDir, target);
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(markupImageSnippet(b, rel, base));
            }
            b.insertAtCaret(out.toString());
            setStatus(tr("status.markdown.imageDropped", files.size()));
        } catch (Exception ex) {
            setStatus(tr("status.markdown.imageFailed", String.valueOf(ex.getMessage())));
        }
    }

    /**
     * Handles a raw image / image URL dragged from a web browser onto a Markdown buffer: encodes a raw
     * dragged image now (FX thread), then off the FX thread downloads the URL (or uses the encoded bytes)
     * into {@code assets/} and inserts {@code ![](assets/…)}. If everything fails but a URL exists, it
     * falls back to referencing the remote URL directly (the preview loads http(s) images).
     */
    private void insertWebImage(EditorBuffer b, javafx.scene.image.Image image, String url) {
        if (b.getPath() == null || !isLocalBuffer(b)) {
            setStatus(tr("status.markdown.imageNeedsSave"));
            return;
        }
        // Encode a raw dragged image now, on the FX thread (PixelReader), before going off-thread.
        byte[] inlinePng = image != null ? com.editora.editor.PreviewImageLoader.imageToPng(image) : null;
        java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
        setStatus(tr("status.markdown.imageDownloading"));
        new Thread(
                        () -> {
                            byte[] bytes = null;
                            String ext = "png";
                            if (url != null && !url.isBlank()) {
                                try {
                                    bytes = com.editora.editor.PreviewImageLoader.fetchBytes(url);
                                    if (bytes != null && bytes.length > 0) {
                                        ext = com.editora.markdown.MarkdownImagePaste.extensionForUrl(
                                                url,
                                                com.editora.editor.PreviewImageLoader.looksLikeSvg(bytes)
                                                        ? "svg"
                                                        : "png");
                                    } else {
                                        bytes = null;
                                    }
                                } catch (Exception ignore) {
                                    bytes = null; // fall back to the encoded image / remote reference below
                                }
                            }
                            if (bytes == null) {
                                bytes = inlinePng;
                                ext = "png";
                            }
                            if (bytes == null) {
                                javafx.application.Platform.runLater(() -> {
                                    if (url != null) {
                                        b.insertAtCaret(markupImageSnippet(b, url, ""));
                                        setStatus(tr("status.markdown.imageDropped", 1));
                                    } else {
                                        setStatus(tr("status.markdown.imageFailed", ""));
                                    }
                                });
                                return;
                            }
                            byte[] finalBytes = bytes;
                            String finalExt = ext;
                            try {
                                java.nio.file.Path assets =
                                        baseDir.resolve(com.editora.markdown.MarkdownImagePaste.ASSETS_DIR);
                                java.nio.file.Files.createDirectories(assets);
                                String name = com.editora.markdown.MarkdownImagePaste.uniqueFileName(
                                        n -> java.nio.file.Files.exists(assets.resolve(n)),
                                        webImageBaseName(url),
                                        finalExt);
                                java.nio.file.Path target = assets.resolve(name);
                                java.nio.file.Files.write(target, finalBytes);
                                String rel = com.editora.markdown.MarkdownImagePaste.relativePath(baseDir, target);
                                javafx.application.Platform.runLater(() -> {
                                    b.insertAtCaret(markupImageSnippet(b, rel, ""));
                                    setStatus(tr("status.markdown.imageDropped", 1));
                                });
                            } catch (Exception ex) {
                                javafx.application.Platform.runLater(() ->
                                        setStatus(tr("status.markdown.imageFailed", String.valueOf(ex.getMessage()))));
                            }
                        },
                        "md-web-image")
                .start();
    }

    /** A file-name base for a dragged web image: the URL's last path segment (sans extension), else "image". */
    private static String webImageBaseName(String url) {
        if (url == null || url.startsWith("data:")) {
            return "image";
        }
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int h = u.indexOf('#');
        if (h >= 0) {
            u = u.substring(0, h);
        }
        int slash = u.lastIndexOf('/');
        String seg = slash >= 0 ? u.substring(slash + 1) : u;
        int dot = seg.lastIndexOf('.');
        if (dot > 0) {
            seg = seg.substring(0, dot);
        }
        seg = seg.replaceAll("[^A-Za-z0-9_-]", "");
        return seg.isBlank() ? "image" : seg;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "png";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Writes a JavaFX {@code Image} to a PNG via headless Java2D (no {@code javafx.swing} dependency). */
    private static void writeFxImageToPng(javafx.scene.image.Image img, java.nio.file.Path target)
            throws java.io.IOException {
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(bi, "png", target.toFile());
    }

    @FXML
    private void onFind() {
        if (findBar.isShown()) {
            findBar.hideBar();
        } else {
            findBar.show(false);
        }
    }

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
        boolean httpActive = http && httpClient.isEnabled();
        if (httpToolWindow != null) {
            applyHttpAutoToolWindow();
        }
        if (httpActive) {
            httpClient.refreshEnvironments(buffer);
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
            toolWindows.setAvailable(markdownLintToolWindow, buffer && b.isMarkdown() && markdownLintEnabled());
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
    }

    /**
     * Auto-shows the HTTP tool window for a {@code .http} buffer and auto-hides it otherwise. Showing it
     * displaces (and later restores) any other right-side window. A user's manual close is remembered per
     * buffer ({@link #httpUserClosed}) so it isn't re-shown until they reopen it; an automatic close (leaving
     * the buffer) is not remembered, so returning re-shows it. All our open/close run under
     * {@link #httpAutoMutating} so the state listener doesn't mistake them for user actions.
     *
     * <p>Deferred to a {@code Platform.runLater} so the SplitPane add + divider positioning land on a
     * settled layout pulse (the tool-window {@code open}/{@code close} idiom) rather than mid-tab-switch —
     * adding a right-side panel synchronously during the selection event mis-sized it; the active buffer is
     * re-read inside in case the user switched again before it runs.
     */
    /**
     * Shows the in-editor "install language support?" banner on {@code buffer} when the file's language has an
     * installer (Java/Python/JS/Mermaid), the relevant feature is enabled but that language server (or the
     * Mermaid CLI) isn't installed, the user hasn't dismissed it for this buffer, and the master nudge toggle
     * is on. Otherwise it hides the banner. Driven on tab switch / addBuffer / after an install.
     */
    private void maybeOfferInstall(EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        if (!installPromptsEligible(buffer)) {
            buffer.showInstallBar(false);
            return;
        }
        // A live LSP session already serving this file ⇒ its server is present; never nag.
        if (lspCoordinator.isManaged(buffer.getPath())) {
            buffer.showInstallBar(false);
            return;
        }
        // The rich bundles (Java/Python/JS LSP+DAP, Mermaid CLI) first…
        java.util.Optional<com.editora.install.InstallCatalog.Lang> lang =
                com.editora.install.InstallCatalog.forBufferLanguage(buffer.getLanguage());
        if (lang.isPresent() && langSupportMissing(lang.get())) {
            com.editora.install.InstallCatalog.Lang l = lang.get();
            offerInstall(
                    buffer,
                    tr("install.lang." + l.name().toLowerCase(java.util.Locale.ROOT)),
                    "install.banner.message",
                    cb -> installCoordinator.installSupport(l, cb));
            return;
        }
        // …then the LSP-only servers (json/bash/yaml/dockerfile/toml/typst/…). This offers the *language
        // server* (code intelligence) — distinct from a language's render/run tool that may already work
        // (e.g. the typst preview renders via the typst CLI even when tinymist isn't installed), so the
        // banner says "language server", not "language support".
        String serverId = com.editora.lsp.LspServerRegistry.serverIdFor(buffer.getLanguage());
        if (serverId != null
                && lspEnabled()
                && lspCoordinator.isServerMissing(serverId)
                && com.editora.install.InstallCatalog.installableServerIds().contains(serverId)) {
            String id = serverId;
            offerInstall(
                    buffer,
                    installCoordinator.serverName(id),
                    "install.banner.serverMessage",
                    cb -> installCoordinator.installServer(id, cb));
            return;
        }
        buffer.showInstallBar(false);
    }

    /** Builds + shows the install banner for {@code buffer} with a display name and an install trigger that
     *  is handed a settled-callback (so it can spin the banner + hide on success). */
    private void offerInstall(
            EditorBuffer buffer,
            String displayName,
            String messageKey,
            java.util.function.Consumer<java.util.function.Consumer<Boolean>> installer) {
        buffer.setInstallPrompt(
                tr(messageKey, displayName),
                tr("install.banner.install"),
                () -> {
                    buffer.setInstallBarBusy(true);
                    installer.accept(ok -> {
                        buffer.setInstallBarBusy(false);
                        if (ok) {
                            buffer.showInstallBar(false);
                        }
                    });
                },
                () -> {
                    installDismissed.add(buffer);
                    buffer.showInstallBar(false);
                });
        buffer.showInstallBar(true);
    }

    /** The common gates for offering the install banner (toggle on, not Simple, not dismissed, local file). */
    private boolean installPromptsEligible(EditorBuffer buffer) {
        return config.getSettings().isLspInstallPrompts()
                && !simpleModeActive()
                && !installDismissed.contains(buffer)
                && isLocalBuffer(buffer);
    }

    /** Whether the rich bundle for {@code lang} is missing its primary tool (feature on but tool absent). */
    private boolean langSupportMissing(com.editora.install.InstallCatalog.Lang lang) {
        return switch (lang) {
            case JAVA -> lspEnabled() && lspCoordinator.isServerMissing("java");
            case PYTHON -> lspEnabled() && lspCoordinator.isServerMissing("python");
            case JAVASCRIPT -> lspEnabled() && lspCoordinator.isServerMissing("typescript");
            case MERMAID -> mermaid.isEnabled() && mermaid.mmdcDetected() && !mermaid.mmdcAvailable();
        };
    }

    private void applyHttpAutoToolWindow() {
        if (restoringSession) {
            return; // restore() + persistence handle the initial open; reconciled in runPendingAfterRestore
        }
        Platform.runLater(() -> {
            if (httpToolWindow == null || restoringSession) {
                return;
            }
            EditorBuffer b = activeBuffer();
            boolean httpActive = b != null && b.isHttpFile() && httpClient.isEnabled();
            httpAutoMutating = true;
            try {
                toolWindows.setAvailable(httpToolWindow, httpActive); // false also closes it if open
                if (httpActive) {
                    if (!toolWindows.isOpen(httpToolWindow) && !httpUserClosed.contains(b)) {
                        httpDisplacedRight = openRightWindowExcept(httpToolWindow);
                        toolWindows.open(httpToolWindow, false); // don't steal focus from the editor
                    }
                } else if (httpDisplacedRight != null) {
                    // We left the .http buffer (setAvailable already closed HTTP) — restore what we displaced.
                    toolWindows.open(httpDisplacedRight, false);
                    httpDisplacedRight = null;
                }
            } finally {
                httpAutoMutating = false;
            }
        });
    }

    /** The tool window currently open on the RIGHT side other than {@code except}, or null. */
    private ToolWindow openRightWindowExcept(ToolWindow except) {
        for (ToolWindow tw : toolWindows.getOpenToolWindows()) {
            if (tw != except && toolWindows.currentSide(tw) == ToolWindow.Side.RIGHT) {
                return tw;
            }
        }
        return null;
    }

    /** The remembered program-arguments string for {@code path} ("" when none); shared with debug launches. */
    private String programArgsFor(Path path) {
        String s = config.getWorkspaceState().getProgramArgs().get(path.toString());
        return s == null ? "" : s;
    }

    @FXML
    private void onPalette() {
        palette.show();
    }

    @FXML
    private void onSettings() {
        settingsWindow.show(stage);
    }

    @FXML
    private void onToggleSimpleMode() {
        toggleSimpleMode();
    }

    @FXML
    private void onAbout() {
        SettingsWindow.showAbout(
                stage,
                config.getSettingsFile(),
                this::openPath,
                this::openExternalUrl,
                config.isDev() ? com.editora.AppInfo.gitCommit() : "", // build commit shown only in --dev
                latestKnownUpdate); // "Update available" row when a newer release is known
    }

    private void toggleColumnRuler() {
        Settings s = config.getSettings();
        s.setShowColumnRuler(!s.isShowColumnRuler());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.ruler", tr(s.isShowColumnRuler() ? "common.on" : "common.off")));
    }

    /** Shows/hides the tool stripes (UI only; tool windows still open via keybinding/palette). */
    private void toggleToolStripe() {
        Settings s = config.getSettings();
        s.setShowToolStripe(!s.isShowToolStripe());
        requestSave();
        applyViewSettingsToAllBuffers(s); // → applyChromeVisibility → toolWindows.setStripesEnabled
        settingsWindow.syncToolStripeCheck();
        setStatus(tr("status.toggle.toolStripe", tr(s.isShowToolStripe() ? "common.on" : "common.off")));
    }

    /**
     * Flips the master "Enable plugins" gate. Plugins load only at startup (no hot classloader/UI unload),
     * so this just persists the preference and reports that a restart is needed; the Settings checkbox is
     * re-synced for discoverability.
     */
    private void toggleSimpleMode() {
        Settings s = config.getSettings();
        cliSimpleOverride = false; // an explicit in-app toggle takes over from the --simple session flag
        s.setSimpleMode(!s.isSimpleMode());
        requestSave();
        if (simpleModeActive()) {
            // Entering Simple mode hides the tool stripe, so close any docked tool window too.
            for (ToolWindow tw : toolWindows.getOpenToolWindows()) {
                toolWindows.close(tw);
            }
        }
        applyViewSettingsToAllBuffers(s); // → applyChromeVisibility/applySimpleMode + per-buffer gutter/minimap
        settingsWindow.syncSimpleModeCheck();
        setStatus(tr("status.toggle.simpleMode", tr(s.isSimpleMode() ? "common.on" : "common.off")));
    }

    private void toggleLineHighlight() {
        Settings s = config.getSettings();
        s.setHighlightCurrentLine(!s.isHighlightCurrentLine());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.lineHighlight", tr(s.isHighlightCurrentLine() ? "common.on" : "common.off")));
    }

    private void toggleLineNumbers() {
        Settings s = config.getSettings();
        s.setShowLineNumbers(!s.isShowLineNumbers());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.lineNumbers", tr(s.isShowLineNumbers() ? "common.on" : "common.off")));
    }

    private void toggleMinimap() {
        Settings s = config.getSettings();
        s.setShowMinimap(!s.isShowMinimap());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.minimap", tr(s.isShowMinimap() ? "common.on" : "common.off")));
    }

    private void toggleWordWrap() {
        Settings s = config.getSettings();
        s.setWordWrap(!s.isWordWrap());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        if (settingsWindow != null) {
            settingsWindow.syncViewChecks();
        }
        setStatus(tr("status.toggle.wordWrap", tr(s.isWordWrap() ? "common.on" : "common.off")));
    }

    private void toggleWhitespace() {
        Settings s = config.getSettings();
        s.setShowWhitespace(!s.isShowWhitespace());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.whitespace", tr(s.isShowWhitespace() ? "common.on" : "common.off")));
    }

    private void toggleSpellCheck() {
        Settings s = config.getSettings();
        s.setSpellCheck(!s.isSpellCheck());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.spellCheck", tr(s.isSpellCheck() ? "common.on" : "common.off")));
    }

    private void togglePersonalDictionary() {
        Settings s = config.getSettings();
        s.setPersonalDictionary(!s.isPersonalDictionary());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        settingsWindow.syncPersonalDictionaryCheck();
        setStatus(tr("status.toggle.personalDictionary", tr(s.isPersonalDictionary() ? "common.on" : "common.off")));
    }

    private void toggleTechnicalDictionary() {
        Settings s = config.getSettings();
        s.setTechnicalDictionary(!s.isTechnicalDictionary());
        requestSave();
        applyViewSettingsToAllBuffers(s);
        settingsWindow.syncTechnicalDictionaryCheck();
        setStatus(tr("status.toggle.technicalDictionary", tr(s.isTechnicalDictionary() ? "common.on" : "common.off")));
    }

    private void toggleAutocomplete() {
        Settings s = config.getSettings();
        s.setAutocomplete(!s.isAutocomplete());
        requestSave();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks(); // keep the Settings window in step if it's open
        setStatus(tr("status.toggle.autocomplete", tr(s.isAutocomplete() ? "common.on" : "common.off")));
    }

    private void toggleAutocompleteProse() {
        Settings s = config.getSettings();
        s.setAutocompleteProse(!s.isAutocompleteProse());
        requestSave();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks();
        setStatus(tr("status.toggle.autocompleteProse", tr(s.isAutocompleteProse() ? "common.on" : "common.off")));
    }

    private void toggleAutocompleteSnippets() {
        Settings s = config.getSettings();
        s.setAutocompleteSnippets(!s.isAutocompleteSnippets());
        requestSave();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks();
        setStatus(
                tr("status.toggle.autocompleteSnippets", tr(s.isAutocompleteSnippets() ? "common.on" : "common.off")));
    }

    private void toggleAutocompleteMermaid() {
        Settings s = config.getSettings();
        s.setAutocompleteMermaid(!s.isAutocompleteMermaid());
        requestSave();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks();
        setStatus(tr("status.toggle.autocompleteMermaid", tr(s.isAutocompleteMermaid() ? "common.on" : "common.off")));
    }

    private void toggleMultiCaret() {
        Settings s = config.getSettings();
        s.setMultiCaret(!s.isMultiCaret());
        requestSave();
        applyMultiCaret();
        settingsWindow.syncMultiCaretCheck(); // keep the Settings window in step if it's open
        setStatus(tr("status.toggle.multiCaret", tr(s.isMultiCaret() ? "common.on" : "common.off")));
    }

    // --- Multiple cursors / column selection commands (delegate to the active buffer's fork add-on) ---

    /** Runs {@code action} on the active buffer when multi-caret is enabled; else reports it. */
    private void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
        if (!multiCaretEnabled()) {
            setStatus(tr("status.multiCaret.disabled"));
            return;
        }
        EditorBuffer b = activeBuffer();
        if (b != null) {
            action.accept(b);
        }
    }

    /** Opens a picker to set the spell-check dictionary language for the active file (persisted per file). */
    private void chooseSpellLanguage() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            setStatus(tr("status.noFileOpen"));
            return;
        }
        QuickOpen<String> picker = new QuickOpen<>(
                "Set Spell Check Language",
                "Type to filter languages…",
                SpellDictionaries::available,
                id -> id,
                id -> "",
                id -> setSpellLanguage(buffer, id));
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    private void setSpellLanguage(EditorBuffer buffer, String langId) {
        buffer.setSpellLanguage(langId);
        Path p = buffer.getPath();
        if (p != null) {
            config.getWorkspaceState().getSpellLanguages().put(p.toString(), langId);
            requestSave();
        }
        setStatus(tr("status.spellLanguage", langId));
    }

    /** Picker for the active keybinding theme (the same set as the Settings → Keymaps combo). */
    private void chooseKeymap() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.keymap.select"),
                tr("palette.keymap.prompt"),
                () -> new java.util.ArrayList<>(com.editora.command.KeymapManager.AVAILABLE.keySet()),
                com.editora.command.KeymapManager::displayName,
                id -> "",
                this::applyKeymap);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** {@code install.languageServer}: pick an installable LSP server (json/bash/go/…) and install it. */
    private void chooseInstallServer() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.install.languageServer"),
                tr("palette.install.prompt"),
                () -> new java.util.ArrayList<>(com.editora.install.InstallCatalog.installableServerIds()),
                installCoordinator::serverName,
                id -> "",
                id -> {
                    if (id != null) {
                        installCoordinator.installServer(id);
                    }
                });
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Persists the chosen keymap, reloads it live across all windows, and reports it. */
    private void applyKeymap(String id) {
        if (id == null) {
            return;
        }
        config.getSettings().setKeymap(id);
        config.save();
        reloadKeymap();
        settingsWindow.syncKeymapCombo(); // keep the Settings window combo in step if it's open
        setStatus(tr("status.keymap.changed", com.editora.command.KeymapManager.displayName(id)));
    }

    /** Rebuilds the shared keymap (base + user + plugin overrides) and re-applies it to every window. */
    private void reloadKeymap() {
        if (windowManager != null) {
            windowManager.reloadSharedKeymap();
        } else {
            keymap.loadNamed(config.getSettings().getKeymap());
            keymap.applyOverrides(config.getSettings().getKeybindings());
        }
    }

    // --- Keybinding editor backend (Settings → Keymaps); pure logic in command/KeybindingEdits ---

    /** The active keymap's bindings <em>without</em> user overrides — the defaults to rebind/reset against. */
    private java.util.Map<String, String> baseBindings() {
        com.editora.command.KeymapManager base = new com.editora.command.KeymapManager();
        base.loadNamed(config.getSettings().getKeymap());
        return base.bindings();
    }

    /** All commands with their localized title + current effective chord, for the keybinding editor list. */
    private java.util.List<SettingsWindow.Shortcut> shortcutRows() {
        java.util.Map<String, String> byCommand = invertBindings();
        java.util.List<SettingsWindow.Shortcut> rows = new java.util.ArrayList<>();
        for (Command c : registry.all()) {
            rows.add(new SettingsWindow.Shortcut(c.id(), c.title(), byCommand.get(c.id())));
        }
        rows.sort(java.util.Comparator.comparing(SettingsWindow.Shortcut::title, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Persists a new user-overrides map, then reloads the shared keymap so the change is live. */
    private void applyKeybindingOverrides(java.util.Map<String, String> overrides) {
        config.getSettings().setKeybindings(overrides);
        config.save();
        reloadKeymap();
    }

    private void rebindShortcut(String commandId, String chordSeq) {
        applyKeybindingOverrides(com.editora.command.KeybindingEdits.rebind(
                baseBindings(), config.getSettings().getKeybindings(), commandId, chordSeq));
        setStatus(tr("status.shortcut.bound", chordSeq, commandTitle(commandId)));
    }

    private void resetShortcut(String commandId) {
        applyKeybindingOverrides(com.editora.command.KeybindingEdits.reset(
                baseBindings(), config.getSettings().getKeybindings(), commandId));
        setStatus(tr("status.shortcut.reset", commandTitle(commandId)));
    }

    private void resetAllShortcuts() {
        applyKeybindingOverrides(new java.util.LinkedHashMap<>());
        setStatus(tr("status.shortcut.resetAll"));
    }

    /** Localized title for a command id (for status messages / conflict dialogs); the id if unknown. */
    private String commandTitle(String commandId) {
        for (Command c : registry.all()) {
            if (c.id().equals(commandId)) {
                return c.title();
            }
        }
        return commandId;
    }

    /** Picker for the app (chrome) theme — also switches the editor theme to match. */
    private void chooseAppTheme() {
        QuickOpen<String> picker = new QuickOpen<>(
                "Set App Theme",
                "Type to filter themes…",
                () -> Themes.names(),
                name -> name,
                name -> "",
                this::applyAppTheme);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Picker for the editor color theme only (leaves the chrome theme untouched). */
    private void chooseEditorTheme() {
        QuickOpen<String> picker = new QuickOpen<>(
                "Set Editor Theme",
                "Type to filter themes…",
                () -> EditorThemes.names(),
                name -> name,
                name -> "",
                this::applyEditorThemeChoice);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Re-scans the user-theme folders (config dir) so newly-added themes appear without a restart. */
    private void reloadUserThemes() {
        UserThemes.load(config.getConfigDir());
        if (settingsWindow != null) {
            settingsWindow.syncThemes(); // rebuild the theme/editor-theme combos from the fresh list
        }
        setStatus(tr("status.userThemesReloaded"));
    }

    /** Applies a chrome theme and follows it with the matching editor theme (clears the user-set flag). */
    private void applyAppTheme(String name) {
        Settings s = config.getSettings();
        s.setTheme(name);
        javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(name));
        s.setEditorTheme(EditorThemes.defaultFor(name)); // chrome theme drives the editor theme
        s.setEditorThemeUserSet(false);
        requestSave();
        applyViewSettingsToAllBuffers(s); // swaps the editor-theme stylesheet + per-buffer colors
        if (settingsWindow != null) {
            settingsWindow.syncThemes();
        }
        setStatus(tr("status.appTheme", name));
    }

    /** Applies only the editor color theme (marks it user-set so it won't follow the chrome theme). */
    private void applyEditorThemeChoice(String name) {
        Settings s = config.getSettings();
        s.setEditorTheme(name);
        s.setEditorThemeUserSet(true);
        requestSave();
        applyViewSettingsToAllBuffers(s);
        if (settingsWindow != null) {
            settingsWindow.syncThemes();
        }
        setStatus(tr("status.editorTheme", name));
    }

    private void toggleZen() {
        setZenMode(!zenActive());
    }

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
        if (zenActive() == on) {
            return;
        }
        if (on && expertActive()) {
            setExpertMode(false); // the two focus modes are mutually exclusive
        }
        if (on) {
            ws.setPreZenToolWindows(toolWindows.closeAllOpen());
        }
        clearCliFocusOverride(); // an explicit toggle takes over from the --zen/--expert session flag
        ws.setZenMode(on);
        toolWindows.setZenStripesHidden(on || expertActive());
        if (!on) {
            toolWindows.openByIds(ws.getPreZenToolWindows());
            ws.getPreZenToolWindows().clear();
        }
        applyChromeVisibility();
        applyViewSettingsToAllBuffers(config.getSettings());
        requestSave();
        // When entering Zen the status bar is hidden, so this is mostly seen on exit.
        setStatus(tr("status.toggle.zen", tr(on ? "common.on" : "common.off")));
    }

    private void toggleExpert() {
        setExpertMode(!expertActive());
    }

    /**
     * Enters/leaves Expert mode for <b>this window only</b> — a per-window effective overlay exactly like
     * {@link #setZenMode Zen}, except it <em>keeps the line-number gutter and the status bar</em> (see
     * {@link Chrome}). Mutually exclusive with Zen. Open tool windows are closed on enter and reopened on
     * leave (a per-window UI snapshot, not a pref); the saved {@link Settings} are never mutated. Idempotent.
     */
    void setExpertMode(boolean on) {
        WorkspaceState ws = config.getWorkspaceState();
        if (expertActive() == on) {
            return;
        }
        if (on && zenActive()) {
            setZenMode(false); // the two focus modes are mutually exclusive
        }
        if (on) {
            ws.setPreExpertToolWindows(toolWindows.closeAllOpen());
        }
        clearCliFocusOverride(); // an explicit toggle takes over from the --zen/--expert session flag
        ws.setExpertMode(on);
        toolWindows.setZenStripesHidden(on || zenActive());
        if (!on) {
            toolWindows.openByIds(ws.getPreExpertToolWindows());
            ws.getPreExpertToolWindows().clear();
        }
        applyChromeVisibility();
        applyViewSettingsToAllBuffers(config.getSettings());
        requestSave();
        setStatus(tr("status.toggle.expert", tr(on ? "common.on" : "common.off")));
    }

    /**
     * Applies a {@code --zen} / {@code --expert} <b>session-only</b> focus mode: the same effect as the real
     * mode, but nothing is written to the saved session — quit and relaunch without the flag and the window
     * comes back normal. This mirrors {@code --simple} ({@link #cliSimpleOverride}).
     *
     * <p>Entering a focus mode closes the docked tool windows, and {@link ToolWindowManager#close} <em>persists</em>
     * "nothing open" — so the ids are stashed in {@link #cliFocusToolWindows} and written back at quit
     * ({@link #persistSession}); otherwise a session-only mode would still lose them for good.
     *
     * <p>No-op when this window's <em>saved</em> session already has a focus mode on (nothing to override).
     */
    private void applyCliFocusMode(boolean expert) {
        if (zenActive() || expertActive()) {
            return;
        }
        WorkspaceState ws = config.getWorkspaceState();
        cliFocusToolWindows =
                new String[] {ws.getOpenLeftToolWindow(), ws.getOpenRightToolWindow(), ws.getOpenBottomToolWindow()};
        if (expert) {
            cliExpertOverride = true;
            ws.setPreExpertToolWindows(toolWindows.closeAllOpen()); // the in-app "E" exit reopens from here
        } else {
            cliZenOverride = true;
            ws.setPreZenToolWindows(toolWindows.closeAllOpen()); // ditto for the "Z"
        }
        toolWindows.setZenStripesHidden(true);
        applyChromeVisibility();
        applyViewSettingsToAllBuffers(config.getSettings());
        // Deliberately no requestSave(): the flag must leave the saved session untouched.
    }

    /** Drops a {@code --zen}/{@code --expert} session override — an in-app toggle now owns the state, so the
     *  quit-time tool-window restore must not fire. */
    private void clearCliFocusOverride() {
        cliZenOverride = false;
        cliExpertOverride = false;
        cliFocusToolWindows = null;
    }

    private void toggleToolbar() {
        Settings s = config.getSettings();
        s.setShowToolbar(!s.isShowToolbar());
        requestSave();
        applyChromeVisibility();
        settingsWindow.syncToolbarCheck();
        setStatus(tr("status.toggle.toolbar", tr(s.isShowToolbar() ? "common.on" : "common.off")));
    }

    private void toggleBreadcrumb() {
        Settings s = config.getSettings();
        s.setShowBreadcrumb(!s.isShowBreadcrumb());
        requestSave();
        applyChromeVisibility();
        setStatus(tr("status.toggle.breadcrumb", tr(s.isShowBreadcrumb() ? "common.on" : "common.off")));
    }

    private void toggleStatusBar() {
        Settings s = config.getSettings();
        s.setShowStatusBar(!s.isShowStatusBar());
        requestSave();
        applyChromeVisibility();
        // The status bar may now be hidden, so this message just confirms the toggle while visible.
        setStatus(tr("status.toggle.statusBar", tr(s.isShowStatusBar() ? "common.on" : "common.off")));
    }

    private void toggleTabBar() {
        Settings s = config.getSettings();
        s.setShowTabBar(!s.isShowTabBar());
        requestSave();
        applyChromeVisibility();
        setStatus(tr("status.toggle.tabBar", tr(s.isShowTabBar() ? "common.on" : "common.off")));
    }

    /**
     * Emacs {@code C-x o}: cycles keyboard focus between the editor and any open tool windows.
     * Order: editor, then each open tool window (by side); wraps back to the editor.
     */
    private void otherWindow() {
        List<Node> targets = new ArrayList<>();
        CodeArea area = activeArea();
        if (area != null) {
            targets.add(area);
        }
        for (ToolWindow tw : toolWindows.getOpenToolWindows()) {
            targets.add(tw.getContent());
        }
        if (targets.size() < 2) {
            return; // nothing to switch to
        }
        Node focusOwner = root.getScene() == null ? null : root.getScene().getFocusOwner();
        int current = indexOfContaining(targets, focusOwner);
        int next = current < 0 ? 0 : (current + 1) % targets.size();
        focusWindow(targets.get(next));
    }

    /** Index of the target that contains (or is) the focus owner, or -1 if none. */
    private static int indexOfContaining(List<Node> targets, Node focusOwner) {
        for (int i = 0; i < targets.size(); i++) {
            for (Node n = focusOwner; n != null; n = n.getParent()) {
                if (n == targets.get(i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void focusWindow(Node target) {
        if (target instanceof StructurePanel structure) {
            structure.focusContent();
        } else {
            target.requestFocus();
        }
    }

    private void foldAll() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.foldAll();
            setStatus(tr("status.foldedAll"));
        }
    }

    private void unfoldAll() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.unfoldAll();
            setStatus(tr("status.unfoldedAll"));
        }
    }

    private void foldAtCaret() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().foldAtCaret();
        }
    }

    private void unfoldAtCaret() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().unfoldAtCaret();
        }
    }

    private void toggleFoldAtCaret() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().toggleFoldAtCaret();
        }
    }

    /** Prompts for a 1-based line number and moves the caret there (clamped to the document). */
    private void goToLine() {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        int total = area.getParagraphs().size();
        // Centered card (lowered to the middle of the app) with a muted note re-reminding the user of the
        // line:column notation.
        Label promptLabel = new Label(tr("dialog.goToLine.content", total));
        TextField field = new TextField(String.valueOf(area.getCurrentParagraph() + 1));
        field.setPrefColumnCount(32);
        com.editora.command.TextInputKeymap.install(field, keymap);
        Label note = new Label(tr("dialog.goToLine.header"));
        note.getStyleClass().add("overlay-note");
        note.setWrapText(true);
        VBox body = new VBox(6, promptLabel, field, note);
        OverlayInput.show(
                overlayHost,
                tr("dialog.goToLine.title"),
                body,
                field,
                tr("dialog.goToLine.button"),
                null,
                () -> handleGoToLine(field.getText(), area, total),
                null,
                false,
                true);
    }

    /** Parses {@code input} as {@code line} or {@code line:column} and moves the caret there. */
    private void handleGoToLine(String input, CodeArea area, int total) {
        {
            String text = input.trim();
            String[] parts = text.split(":", 2);
            try {
                int line = Math.max(1, Math.min(total, Integer.parseInt(parts[0].trim()))) - 1;
                int column = 0; // 0-based; default to the start of the line
                if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                    int lineLen = area.getParagraphLength(line);
                    column = Math.max(1, Math.min(lineLen + 1, Integer.parseInt(parts[1].trim()))) - 1;
                }
                int targetLine = line;
                int targetColumn = column;
                EditorBuffer buffer = activeBuffer();
                if (buffer != null) {
                    buffer.getFoldManager().unfoldContaining(targetLine);
                }
                moveAndFollow(a -> a.moveTo(targetLine, targetColumn));
                setStatus(tr("status.gotoResult", targetLine + 1, targetColumn + 1));
            } catch (NumberFormatException e) {
                setStatus(tr("status.gotoInvalid", input));
            }
        }
    }

    /** Lets the user override the syntax language/grammar for the active buffer. */
    private void chooseLanguage() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        names.add(LanguageRegistry.plaintext());
        names.addAll(GrammarRegistry.shared().availableLanguageNames());
        String current = names.contains(buffer.getLanguage()) ? buffer.getLanguage() : names.get(0);
        ChoiceDialog<String> dialog = new ChoiceDialog<>(current, names);
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.setLanguage.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.setLanguage.content"));
        dialog.showAndWait().ifPresent(name -> {
            buffer.setLanguageOverride(name);
            statusBar.refresh();
            setStatus(tr("status.language", name));
        });
    }

    /** Changes the (persisted) tab width and applies it to every buffer. */
    private void chooseTabSize() {
        Settings s = config.getSettings();
        List<Integer> options = List.of(2, 4, 8);
        ChoiceDialog<Integer> dialog =
                new ChoiceDialog<>(options.contains(s.getTabSize()) ? s.getTabSize() : 4, options);
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.tabSize.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.tabSize.content"));
        dialog.showAndWait().ifPresent(size -> {
            s.setTabSize(size);
            requestSave();
            applyViewSettingsToAllBuffers(s);
            statusBar.refresh();
            setStatus(tr("status.tabSize", size));
        });
    }

    // --- Settings palette commands ----------------------------------------------------------------
    // Every Settings-window control has a command-palette equivalent (Editora is command-driven). The
    // helpers below flip/prompt the same Settings field a control writes, then persist, re-apply the
    // feature, keep an open Settings window in step (syncAll), and echo a status. Two generic status
    // keys (status.settingToggled / status.settingChanged) reuse each command's own localized title.

    /** Flips a boolean setting, persists, re-applies, syncs Settings, and echoes "<title> — on/off". */
    private void toggleSetting(
            String commandId,
            java.util.function.BooleanSupplier get,
            java.util.function.Consumer<Boolean> set,
            Runnable apply) {
        boolean next = !get.getAsBoolean();
        set.accept(next);
        requestSave();
        if (apply != null) {
            apply.run();
        }
        if (settingsWindow != null) {
            settingsWindow.syncAll();
        }
        setStatus(tr("status.settingToggled", commandTitle(commandId), tr(next ? "common.on" : "common.off")));
    }

    /** Prompts for a string setting (current value pre-filled), persists, re-applies, and echoes it. */
    private void promptStringSetting(
            String commandId,
            java.util.function.Supplier<String> get,
            java.util.function.Consumer<String> set,
            Runnable apply) {
        promptText(commandTitle(commandId), tr("palette.setting.value"), get.get(), v -> {
            String value = v.trim();
            set.accept(value);
            requestSave();
            if (apply != null) {
                apply.run();
            }
            if (settingsWindow != null) {
                settingsWindow.syncAll();
            }
            setStatus(tr("status.settingChanged", commandTitle(commandId), value));
        });
    }

    /** Toggles the intermediate large-file tier (minimap + LSP off, highlighting on) for the active buffer,
     *  then re-syncs LSP so the session starts/stops to match. */
    private void toggleLargeFileMode() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        boolean now = !b.isHeavyFile();
        b.setHeavyFile(now);
        lspCoordinator.syncBuffer(b); // start (off) / stop (on) the LSP session to match the new state
        setStatus(tr(now ? "status.largeFileMode.on" : "status.largeFileMode.off"));
    }

    /** Prompts for an integer setting (clamped to [min,max]); reports a parse error without changing it. */
    private void promptIntSetting(
            String commandId,
            java.util.function.IntSupplier get,
            int min,
            int max,
            java.util.function.IntConsumer set,
            Runnable apply) {
        promptText(commandTitle(commandId), tr("palette.setting.value"), Integer.toString(get.getAsInt()), v -> {
            int parsed;
            try {
                parsed = Integer.parseInt(v.trim());
            } catch (NumberFormatException ex) {
                setStatus(tr("status.setting.invalidNumber", v.trim()));
                return;
            }
            int clamped = Math.max(min, Math.min(max, parsed));
            set.accept(clamped);
            requestSave();
            if (apply != null) {
                apply.run();
            }
            if (settingsWindow != null) {
                settingsWindow.syncAll();
            }
            setStatus(tr("status.settingChanged", commandTitle(commandId), Integer.toString(clamped)));
        });
    }

    /** Generic single-choice picker that sets a setting to the chosen value (label fn for display). */
    private void chooseSetting(
            String commandId,
            java.util.function.Supplier<List<String>> options,
            java.util.function.Function<String, String> label,
            java.util.function.Consumer<String> onChoose) {
        QuickOpen<String> picker = new QuickOpen<>(
                commandTitle(commandId), tr("palette.setting.pick"), options::get, label::apply, id -> "", id -> {
                    if (id != null) {
                        onChoose.accept(id);
                    }
                });
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Palette entry for the TODO per-part colors: pick a part (tag / priority level), then enter a web-hex
     *  color; applies live to every buffer and syncs an open Settings window. */
    private void chooseTodoPartColor() {
        chooseSetting(
                "todo.setPartColor",
                () -> List.of("tag", "critical", "high", "medium", "low"),
                id -> tr("settings.todo.part." + id),
                part -> {
                    Settings s = config.getSettings();
                    String current =
                            switch (part) {
                                case "tag" -> s.getTodoTagColor();
                                case "critical" -> s.getTodoPriorityCriticalColor();
                                case "high" -> s.getTodoPriorityHighColor();
                                case "medium" -> s.getTodoPriorityMediumColor();
                                case "low" -> s.getTodoPriorityLowColor();
                                default -> "";
                            };
                    promptText(
                            commandTitle("todo.setPartColor"),
                            tr("settings.todo.part." + part),
                            current,
                            hex -> applyTodoPartColor(part, hex));
                });
    }

    /** Validates a web-hex color and stores it in the given TODO part's setting, then re-highlights. */
    private void applyTodoPartColor(String part, String hex) {
        if (hex == null || !hex.strip().matches("#[0-9a-fA-F]{6}")) {
            setStatus(tr("status.todo.badColor"));
            return;
        }
        String c = hex.strip();
        Settings s = config.getSettings();
        switch (part) {
            case "tag" -> s.setTodoTagColor(c);
            case "critical" -> s.setTodoPriorityCriticalColor(c);
            case "high" -> s.setTodoPriorityHighColor(c);
            case "medium" -> s.setTodoPriorityMediumColor(c);
            case "low" -> s.setTodoPriorityLowColor(c);
            default -> {
                return;
            }
        }
        requestSave();
        todoCoordinator.applyHighlight();
        if (settingsWindow != null) {
            settingsWindow.syncTodoPartColors();
        }
        setStatus(tr("status.settingChanged", tr("settings.todo.part." + part), c));
    }

    /** Picker for the global indent style (Detect / Spaces / Tabs); applies live to every buffer. */
    private void chooseIndentStyle() {
        chooseSetting(
                "editor.setIndentStyle",
                () -> List.of("detect", "space", "tab"),
                SettingsWindow::indentStyleName,
                id -> {
                    Settings s = config.getSettings();
                    s.setIndentStyle(id);
                    requestSave();
                    applyViewSettingsToAllBuffers(s);
                    if (settingsWindow != null) {
                        settingsWindow.syncAll();
                    }
                    setStatus(tr(
                            "status.settingChanged",
                            commandTitle("editor.setIndentStyle"),
                            SettingsWindow.indentStyleName(id)));
                });
    }

    /** Picker for the editor font family (same choices as Settings → Appearance). */
    private void chooseFont() {
        chooseSetting("appearance.setFont", SettingsWindow::fontFamilyChoices, name -> name, name -> {
            Settings s = config.getSettings();
            s.setFontFamily(name);
            requestSave();
            applyViewSettingsToAllBuffers(s);
            if (settingsWindow != null) {
                settingsWindow.syncAll();
            }
            setStatus(tr("status.settingChanged", commandTitle("appearance.setFont"), name));
        });
    }

    /** Picker for the UI language (Automatic + bundled locales); applies after a restart. */
    private void chooseUiLanguage() {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(""); // "" = Automatic (follow the system language)
        ids.addAll(com.editora.i18n.Messages.available().keySet());
        chooseSetting(
                "appearance.setUiLanguage",
                () -> ids,
                id -> id.isEmpty() ? tr("settings.language.auto") : com.editora.i18n.Messages.languageName(id),
                id -> {
                    config.getSettings().setUiLanguage(id);
                    requestSave();
                    if (settingsWindow != null) {
                        settingsWindow.syncAll();
                    }
                    setStatus(tr("dialog.language.restart"));
                });
    }

    /** Picker for the PDF export page size. */
    private void choosePdfPageSize() {
        chooseSetting("editor.setPdfPageSize", () -> List.of("letter", "a4"), v -> v, v -> {
            config.getSettings().setPdfPageSize(v);
            requestSave();
            if (settingsWindow != null) {
                settingsWindow.syncAll();
            }
            setStatus(tr("status.settingChanged", commandTitle("editor.setPdfPageSize"), v));
        });
    }

    /** Converts the active buffer's line endings between LF and CRLF. */
    private void chooseLineEndings() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(buffer.getLineEnding(), List.of("LF", "CRLF"));
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.lineEndings.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.lineEndings.content"));
        dialog.showAndWait().ifPresent(choice -> {
            buffer.convertLineEndings("CRLF".equals(choice));
            statusBar.refresh();
            setStatus(tr("status.lineEndingsSet", choice));
        });
    }

    /** Persists the buffer's collapsed fold regions, keyed by its file path. */
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
        requestSave();
    }

    /** Re-applies a file's saved collapsed fold regions after it is opened. */
    private void restoreFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> saved = config.getWorkspaceState().getFoldedRegions().get(file.toString());
        buffer.getFoldManager().applyCollapsedStartLines(saved);
        buffer.markClean();
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
        for (Tab t : tabPane.getTabs()) {
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

    /** Persists the buffer's Markdown view mode, keyed by file path (EDITOR is the unset default). */
    private void persistMarkdownMode(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        var map = config.getWorkspaceState().getMarkdownViewModes();
        EditorBuffer.MarkdownViewMode mode = buffer.getMarkdownViewMode();
        if (mode == EditorBuffer.MarkdownViewMode.EDITOR) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), mode.name());
        }
        requestSave();
    }

    /**
     * Attaches or removes the floating Editor/Split/Preview control to match {@link EditorBuffer#hasPreview()}.
     * Re-evaluated whenever a buffer's language can change its previewability — at open, after Save As
     * (a new untitled buffer becomes a `.md`/`.mmd`), and when the Mermaid feature toggles. The light/dark
     * sun/moon control is Markdown-only (it themes the Markdown CSS; diagrams follow the app theme).
     */
    private void ensurePreviewControls(EditorBuffer buffer) {
        // A CSV buffer becomes previewable (hasPreview()) only once its grid node is injected, so do that
        // first — then the same Editor/Split/Preview toggle attaches below, exactly like Markdown/Mermaid.
        csvCoordinator.ensureCsvPreview(buffer);
        boolean want = buffer.hasPreview();
        boolean has = buffer.hasViewModeControl();
        if (want && !has) {
            MarkdownViewToggle toggle = new MarkdownViewToggle(buffer);
            buffer.setOnViewModeChanged(() -> {
                persistMarkdownMode(buffer);
                toggle.sync();
            });
            buffer.setViewModeControl(toggle);
            if (buffer.isMarkdown()) {
                buffer.setPreviewThemeToggle(this::toggleMarkdownPreviewTheme);
                buffer.applyPreviewTheme(config.getSettings().getMarkdownPreviewTheme(), appThemeDark());
            }
        } else if (!want && has) {
            buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR);
            buffer.setViewModeControl(null);
        }
        // Keep live linting in step when a Save As / rename flips the buffer to/from .mmd.
        mermaid.refreshLint(buffer);
    }

    /** Restores a Markdown/CSV file's saved view mode after it is opened (and its toggle is wired). */
    private void restoreMarkdownMode(EditorBuffer buffer) {
        csvCoordinator.ensureCsvPreview(buffer); // inject the grid first so a CSV buffer reports hasPreview()
        Path file = buffer.getPath();
        if (file == null || !buffer.hasPreview()) {
            return;
        }
        if (buffer.isMarkwhen()) {
            // Restore the timeline/calendar renderer BEFORE the view mode below (so the first render uses it).
            String view = config.getWorkspaceState().getMarkwhenViews().get(file.toString());
            if (view != null) {
                try {
                    buffer.setMarkwhenView(EditorBuffer.MarkwhenView.valueOf(view));
                } catch (IllegalArgumentException ignored) {
                    // unknown persisted value — keep the timeline default
                }
            }
        }
        String saved = config.getWorkspaceState().getMarkdownViewModes().get(file.toString());
        if (saved == null) {
            return;
        }
        try {
            buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.valueOf(saved));
        } catch (IllegalArgumentException ignored) {
            // unknown persisted value — leave in EDITOR mode
        }
    }

    /** Sets the active previewable buffer's view mode (Markdown or Mermaid; no-op otherwise). */
    private void setActiveMarkdownMode(EditorBuffer.MarkdownViewMode mode) {
        EditorBuffer b = activeBuffer();
        if (b != null && b.hasPreview()) {
            b.setMarkdownViewMode(mode);
        } else {
            setStatus(tr("status.notMarkdown"));
        }
    }

    /** Runs a Markdown format action on the active buffer; reports when it isn't an editable Markdown file. */
    private void withMarkdown(java.util.function.Consumer<EditorBuffer> action) {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
            return;
        }
        action.accept(b);
    }

    /** Opens the table-size grid picker; on pick, inserts a fresh GFM table into the active Markdown buffer. */
    private void markdownInsertTable() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
            return;
        }
        showTableSizePicker((rows, cols) -> b.insertTable(rows, cols));
    }

    /**
     * The command-palette path: a keyboard-only {@code RxC} size prompt (e.g. {@code "4x4"}) instead of the
     * mouse grid picker. The grid picker stays the format-bar button / right-click "Insert Table" UI.
     */
    private void markdownInsertTableViaText() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
            return;
        }
        promptText(tr("table.size.title"), tr("table.size.label"), "3x3", input -> {
            int[] rc = MarkdownTable.parseSize(input);
            if (rc == null) {
                setStatus(tr("table.size.invalid"));
                return;
            }
            b.insertTable(rc[0], rc[1]);
        });
    }

    /**
     * A Typora/Word-style table-size grid picker shown as an in-scene overlay: hover to highlight an
     * {@code R × C} block (rows include the header), click to commit. Max {@value #TABLE_PICKER_MAX_ROWS} ×
     * {@value #TABLE_PICKER_MAX_COLS}.
     */
    private void showTableSizePicker(java.util.function.BiConsumer<Integer, Integer> onPick) {
        final int maxR = TABLE_PICKER_MAX_ROWS;
        final int maxC = TABLE_PICKER_MAX_COLS;
        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(8);
        card.getStyleClass().add("table-size-picker");
        card.setPadding(new javafx.geometry.Insets(12));
        // Hug the grid + padding — without a max-size cap the StackPane overlay stretches the card to fill
        // the whole editor area (like QuickOpen's card, which caps to its preferred size).
        card.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        Label heading = new Label(tr("table.picker.prompt"));
        heading.getStyleClass().add("table-size-label");
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(3);
        grid.setVgap(3);
        javafx.scene.shape.Rectangle[][] cells = new javafx.scene.shape.Rectangle[maxR][maxC];
        int[] sel = {0, 0}; // selected rows, cols (1-based; 0 = nothing yet)
        Runnable[] repaint = new Runnable[1];
        repaint[0] = () -> {
            for (int r = 0; r < maxR; r++) {
                for (int c = 0; c < maxC; c++) {
                    boolean on = r < sel[0] && c < sel[1];
                    cells[r][c]
                            .getStyleClass()
                            .setAll("table-size-cell", on ? "table-size-cell-on" : "table-size-cell-off");
                }
            }
            heading.setText(sel[0] == 0 ? tr("table.picker.prompt") : tr("table.picker.size", sel[0], sel[1]));
        };
        for (int r = 0; r < maxR; r++) {
            for (int c = 0; c < maxC; c++) {
                javafx.scene.shape.Rectangle cell = new javafx.scene.shape.Rectangle(16, 16);
                cell.getStyleClass().setAll("table-size-cell", "table-size-cell-off");
                final int rr = r + 1;
                final int cc = c + 1;
                cell.setOnMouseEntered(e -> {
                    sel[0] = rr;
                    sel[1] = cc;
                    repaint[0].run();
                });
                cell.setOnMouseClicked(e -> {
                    overlayHost.hide();
                    onPick.accept(rr, cc);
                });
                cells[r][c] = cell;
                grid.add(cell, c, r);
            }
        }
        card.getChildren().addAll(heading, grid);
        card.getProperties().put("editora.ownsKeys", true);
        overlayHost.show(card, true, () -> {}, () -> {}); // centered — it's a small grid, not a top palette
    }

    private void markdownInline(String marker) {
        withMarkdown(b -> b.formatInline(marker));
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

    /** Runs a Typst markup-format action on the active buffer, or reports it isn't a Typst buffer. */
    private void withTypst(java.util.function.Consumer<EditorBuffer> action) {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isTypst() || !b.canFormatMarkup()) {
            setStatus(tr("status.typst.notTypst"));
            return;
        }
        action.accept(b);
    }

    /** {@code markdown.openLink}: open the link under the caret externally. */
    private void markdownOpenLink() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkdown()) {
            setStatus(tr("status.notMarkdown"));
        } else if (!b.openLinkUnderCaret()) {
            setStatus(tr("status.markdown.noLink"));
        }
    }

    /** {@code markdown.reflowTable}: normalize/align the GFM table around the caret. */
    private void markdownReflowTable() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
        } else if (!b.reflowTable()) {
            setStatus(tr("status.markdown.notTable"));
        }
    }

    /** {@code markdown.toc}: insert a table of contents at the caret, or regenerate the existing TOC block. */
    private void markdownToc() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
        } else if (!b.insertOrUpdateToc()) {
            setStatus(tr("status.markdown.tocNoHeadings"));
        } else {
            setStatus(tr("status.markdown.tocDone"));
        }
    }

    /** {@code markdown.tableFromCsv}: convert the selected CSV (else clipboard CSV) into a GFM table. */
    private void markdownTableFromCsv() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
        } else if (!b.tableFromCsv()) {
            setStatus(tr("status.markdown.csvEmpty"));
        }
    }

    /** {@code markdown.tableToCsv}: copy the caret's GFM table to the clipboard as CSV. */
    private void markdownTableToCsv() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
        } else if (!b.tableToCsv()) {
            setStatus(tr("status.markdown.notTable"));
        } else {
            setStatus(tr("status.markdown.csvCopied"));
        }
    }

    /** {@code markdown.tableExport*}: file-export the caret's GFM table ({@code format} = csv|xlsx|ods). */
    private void markdownTableExport(String format) {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            setStatus(tr("status.notMarkdown"));
        } else if (!b.exportTableFile(format)) {
            setStatus(tr("status.markdown.notTable"));
        }
    }

    /**
     * File-exports a Markdown table the buffer already rendered to {@code csv}. {@code format} = {@code csv}
     * writes the CSV text; {@code xlsx}/{@code ods} parse it and reuse the spreadsheet writers. Wired into each
     * buffer via {@code setTableFileExporter}.
     */
    private void exportMarkdownTableFile(String csv, String format) {
        if (csv == null || csv.isBlank()) {
            setStatus(tr("status.markdown.notTable"));
            return;
        }
        EditorBuffer b = activeBuffer();
        String base = b == null ? "table" : bufferBaseName(b);
        switch (format) {
            case "csv" -> exportCsvTextToFile(csv, base);
            // A Markdown table always leads with a header row (toCsv drops the ---|--- divider).
            case "xlsx" -> csvExportSpreadsheet(com.editora.csv.CsvParser.parse(csv, ','), true, base, true);
            case "ods" -> csvExportSpreadsheet(com.editora.csv.CsvParser.parse(csv, ','), true, base, false);
            default -> {}
        }
    }

    /** Writes {@code csv} text to a user-chosen {@code .csv} file (the Markdown-table → CSV file export). */
    private void exportCsvTextToFile(String csv, String base) {
        java.io.File f = chooseOfficeDestination(base, "csv", "CSV");
        if (f == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(f.toPath(), csv);
            setStatus(tr("status.csv.exported", f.getName()));
        } catch (java.io.IOException ex) {
            setStatus(tr("status.csv.exportFailed", String.valueOf(ex.getMessage())));
        }
    }

    /** {@code csv.copyAsMarkdownTable}: copy the active CSV/TSV buffer to the clipboard as a GFM table. */
    private void csvCopyAsMarkdownTable() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isCsv()) {
            setStatus(tr("status.csv.notCsv"));
            return;
        }
        String md = MarkdownTable.fromCsv(b.getContent());
        if (md == null) {
            setStatus(tr("status.csv.empty"));
            return;
        }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(md);
        Clipboard.getSystemClipboard().setContent(cc);
        setStatus(tr("status.csv.copied"));
    }

    /** {@code csv.align}: pad the active CSV/TSV so its column delimiters line up (Rainbow-CSV Align). */
    private void csvAlign() {
        csvReformat(true);
    }

    /** {@code csv.shrink}: strip column-alignment padding from the active CSV/TSV (reverses {@link #csvAlign}). */
    private void csvShrink() {
        csvReformat(false);
    }

    /** Aligns ({@code align=true}) or shrinks the active CSV buffer's text via {@code CsvAlign}, undoable. */
    private void csvReformat(boolean align) {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isCsv()) {
            setStatus(tr("status.csv.notCsv"));
            return;
        }
        if (!activeEditable()) {
            return;
        }
        String text = b.getContent();
        if (text.isEmpty()) {
            setStatus(tr("status.csv.empty"));
            return;
        }
        char delim = com.editora.csv.CsvParser.detectDelimiter(text);
        // A quoted multi-line field means a record no longer maps to one physical line — line-based
        // align/shrink would corrupt it, so refuse (mirrors the grid's edit guard).
        if (com.editora.csv.CsvParser.hasMultilineField(com.editora.csv.CsvParser.parse(text, delim))) {
            setStatus(tr("status.csv.multiline"));
            return;
        }
        String out = align ? com.editora.csv.CsvAlign.align(text, delim) : com.editora.csv.CsvAlign.shrink(text, delim);
        if (out.equals(text)) {
            setStatus(tr(align ? "status.csv.alignNoChange" : "status.csv.shrinkNoChange"));
            return;
        }
        b.getArea().replaceText(out); // whole-document replace (undoable)
        setStatus(tr(align ? "status.csv.aligned" : "status.csv.shrunk"));
    }

    /** Exports a CSV as a PDF by reusing the Markdown-table → PDF pipeline (the grid's right-click menu). */
    private void csvExportPdf(String csvText, String baseName) {
        String md = MarkdownTable.fromCsv(csvText);
        if (md == null) {
            setStatus(tr("status.csv.empty"));
            return;
        }
        java.io.File f = choosePdfDestination(baseName);
        if (f == null) {
            return;
        }
        setStatus(tr("status.pdf.exporting"));
        pdfService.exportMarkdown(
                md, null, config.getSettings().getPdfPageSize(), null, f.toPath(), r -> reportPdf(r, f));
    }

    /** Opens the print preview for a CSV by reusing the Markdown-table → print pipeline. */
    private void csvPrint(String csvText) {
        String md = MarkdownTable.fromCsv(csvText);
        if (md == null) {
            setStatus(tr("status.csv.empty"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            setStatus(tr("status.print.noPrinter"));
            return;
        }
        setStatus(tr("status.print.preparing"));
        printService.prepareMarkdown(md, null, prepared -> openPrintPreview(job, prepared));
    }

    /** Exports parsed CSV rows to a spreadsheet — {@code xlsx} true → Excel {@code .xlsx}, else ODF {@code .ods}. */
    private void csvExportSpreadsheet(
            java.util.List<java.util.List<String>> rows, boolean hasHeader, String baseName, boolean xlsx) {
        if (rows == null || rows.isEmpty()) {
            setStatus(tr("status.csv.empty"));
            return;
        }
        String ext = xlsx ? "xlsx" : "ods";
        String filter = xlsx ? "Excel" : "OpenDocument Spreadsheet";
        java.io.File f = chooseOfficeDestination(baseName, ext, filter);
        if (f == null) {
            return;
        }
        setStatus(tr("status.office.exporting"));
        java.util.function.Consumer<com.editora.office.OfficeExportService.Result> cb = r -> reportOffice(r, f);
        if (xlsx) {
            officeService.exportXlsx(rows, hasHeader, f.toPath(), cb);
        } else {
            officeService.exportOds(rows, hasHeader, f.toPath(), cb);
        }
    }

    /** {@code markdown.toggleFormatBar}: flip the selection format-bar setting + re-sync every buffer. */
    private void toggleMarkdownFormatBar() {
        Settings s = config.getSettings();
        boolean now = !s.isMarkdownFormatBar();
        s.setMarkdownFormatBar(now);
        requestSave();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setFormatBarEnabled(now);
            }
        }
        settingsWindow.syncMarkdownFormatBarCheck();
        setStatus(tr(now ? "status.markdown.formatBar.on" : "status.markdown.formatBar.off"));
    }

    /** Whether the app (AtlantaFX) theme is dark — seeds/decides the "follow app" preview theme + glyph. */
    private boolean appThemeDark() {
        return Themes.backgroundFor(config.getSettings().getTheme()).getBrightness() < 0.5;
    }

    /** Pushes the (global) Markdown preview theme to every open buffer's preview + its toggle glyph. */
    private void applyMarkdownPreviewTheme() {
        String mode = config.getSettings().getMarkdownPreviewTheme();
        boolean appDark = appThemeDark();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.isMarkdown()) {
                b.applyPreviewTheme(mode, appDark);
            }
        }
    }

    /**
     * Toggles the Markdown preview between light and dark, independent of the app theme. Flips the current
     * effective theme (seeded from the app theme the first time), persists it, and re-applies to all
     * previews. Run by the floating sun/moon control and the {@code view.toggleMarkdownPreviewTheme} command.
     */
    private void toggleMarkdownPreviewTheme() {
        String mode = config.getSettings().getMarkdownPreviewTheme();
        boolean currentlyDark = "dark".equals(mode) || (mode.isEmpty() && appThemeDark());
        String next = currentlyDark ? "light" : "dark";
        config.getSettings().setMarkdownPreviewTheme(next);
        requestSave();
        applyMarkdownPreviewTheme();
        setStatus(tr("status.markdownPreviewTheme", tr("markdown.previewTheme." + next)));
    }

    /** Zooms the active Markdown buffer's preview text: {@code >0} in, {@code <0} out, {@code 0} reset. */
    private void markdownZoom(int direction) {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkdown()) {
            setStatus(tr("status.notMarkdown"));
            return;
        }
        if (direction > 0) {
            b.zoomPreviewIn();
        } else if (direction < 0) {
            b.zoomPreviewOut();
        } else {
            b.resetPreviewZoom();
        }
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
            markdownZoom(direction);
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
        applyViewSettingsToAllBuffers(s);
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
        snippetPalette.show(stage);
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
            openPath(file);
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

    // --- File templates --------------------------------------------------------------------------

    /**
     * Picks a template, runs the variable-entry wizard (if it has any unknown variables), then creates
     * the file(s). {@code targetDir} {@code null} = a new untitled in-editor buffer (single-file only);
     * non-null = write the file(s) into that folder and open the primary one.
     */
    private void newFromTemplate(java.nio.file.Path targetDir) {
        List<com.editora.template.Template> all = templates.all();
        if (all.isEmpty()) {
            setStatus(tr("status.noTemplates"));
            return;
        }
        QuickOpen<com.editora.template.Template> picker = new QuickOpen<>(
                tr("template.picker.title"),
                tr("template.picker.prompt"),
                () -> new ArrayList<>(all),
                com.editora.template.Template::name,
                com.editora.template.Template::description,
                t -> beginTemplate(t, targetDir));
        // Wider than the default picker + a taller minimum: template descriptions are long, so the
        // default 620px clipped them (and showed a horizontal scrollbar).
        picker.setPreferredSize(820, 8);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Discovers the template's unknown variables; prompts for them via a wizard, else applies directly. */
    private void beginTemplate(com.editora.template.Template t, java.nio.file.Path targetDir) {
        List<com.editora.template.TemplateEngine.TemplateVar> vars;
        if (t.isMultiFile()) {
            List<String> texts = new ArrayList<>();
            for (com.editora.template.TemplateFile f : t.files()) {
                texts.add(f.path());
                texts.add(f.body());
            }
            vars = com.editora.template.TemplateEngine.discoverVariables(texts.toArray(new String[0]));
        } else {
            // The file-name pattern's own ${baseName}/${fileName}/${extension} can't be derived for a new
            // file, so prompt for them (the body's stay auto-derived) — otherwise ${baseName:Main}.java
            // silently used its default and the user was never asked for the name.
            vars = com.editora.template.TemplateEngine.discoverVariablesForNewFile(t.fileName(), t.body());
        }
        // Fast path: a variable-less, single-file template invoked with no folder context (palette / toolbar)
        // creates an untitled scratch buffer immediately — no wizard. A multi-file template always writes to
        // disk, so it goes through the wizard (to offer the target folder) even with no variables.
        if (vars.isEmpty() && targetDir == null && !t.isMultiFile()) {
            applyTemplate(t, null, java.util.Map.of());
            return;
        }

        VBox body = new VBox(8);
        // Optional target-folder field (prefilled from the folder context — e.g. the right-clicked project
        // folder). Left blank, a single-file template opens as an untitled buffer (Save prompts for a
        // location); filled (typed or Browse), the file(s) are written into that folder, creating it if needed.
        TextField folderField = new TextField(targetDir == null ? "" : targetDir.toString());
        folderField.setPromptText(tr("template.wizard.folderPrompt"));
        folderField.setPrefColumnCount(28);
        com.editora.command.TextInputKeymap.install(folderField, keymap);
        Button browse = new Button(tr("dialog.clone.browse"));
        browse.setFocusTraversable(false);
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("template.wizard.folderTitle"));
            java.io.File init = templateFolderChooserDir(folderField.getText());
            if (init != null) {
                chooser.setInitialDirectory(init);
            }
            java.io.File dir = chooser.showDialog(stage);
            if (dir != null) {
                folderField.setText(dir.toString());
            }
        });
        HBox folderRow = new HBox(6, folderField, browse);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        body.getChildren().addAll(new Label(tr("template.wizard.folder")), folderRow);

        java.util.LinkedHashMap<String, TextField> fields = new java.util.LinkedHashMap<>();
        for (var v : vars) {
            TextField field = new TextField(v.defaultValue());
            field.setPrefColumnCount(28);
            com.editora.command.TextInputKeymap.install(field, keymap);
            fields.put(v.name(), field);
            body.getChildren().addAll(new Label(v.name()), field);
        }
        // Focus the first variable (the thing most likely to be edited), else the folder field.
        TextField initialFocus =
                fields.isEmpty() ? folderField : fields.values().iterator().next();
        OverlayInput.show(
                overlayHost,
                tr("template.wizard.title"),
                body,
                initialFocus,
                tr("dialog.template.create"),
                null,
                () -> {
                    java.util.LinkedHashMap<String, String> answers = new java.util.LinkedHashMap<>();
                    fields.forEach((name, f) -> answers.put(name, f.getText()));
                    // Blank → null (untitled buffer / defaultNewDir for multi-file); a relative path resolves
                    // against the folder context, else the default new-file dir; ~ expands to home.
                    java.nio.file.Path base = targetDir != null ? targetDir : defaultNewDir();
                    java.nio.file.Path dir = com.editora.config.PathKeys.resolveUserInput(
                            folderField.getText(), base, System.getProperty("user.home"));
                    applyTemplate(t, dir, answers);
                },
                null,
                false);
    }

    /** The folder a template-wizard folder chooser should open at: the typed folder if it exists, walking up
     *  to the nearest existing ancestor, else the default new-file directory. Null only if nothing exists. */
    private java.io.File templateFolderChooserDir(String current) {
        java.nio.file.Path p =
                com.editora.config.PathKeys.resolveUserInput(current, defaultNewDir(), System.getProperty("user.home"));
        if (p == null) {
            p = defaultNewDir();
        }
        while (p != null && !java.nio.file.Files.isDirectory(p)) {
            p = p.getParent();
        }
        return p == null ? null : p.toFile();
    }

    /** Renders {@code t} with {@code answers} and creates the file(s) (untitled buffer or written to disk). */
    private void applyTemplate(
            com.editora.template.Template t, java.nio.file.Path targetDir, java.util.Map<String, String> answers) {
        Settings s = config.getSettings();
        String author = s.getAuthorName();
        String projectName = activeProjectName();
        String packageName = "";
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        if (t.isMultiFile()) {
            applyMultiFileTemplate(
                    t, targetDir != null ? targetDir : defaultNewDir(), answers, author, projectName, packageName, now);
            return;
        }
        // Resolve the file name first (so the body's ${fileName}/${baseName}/${extension} are correct).
        com.editora.template.TemplateVariableResolver pre = new com.editora.template.TemplateVariableResolver(
                answers, author, projectName, packageName, "", targetDir == null ? "" : targetDir.toString(), "", now);
        String fileName = com.editora.template.TemplateEngine.expand(t.fileName(), pre);
        if (fileName.isBlank()) {
            fileName = "untitled";
        }
        java.nio.file.Path target = null;
        if (targetDir != null) {
            // Contain the file name to targetDir, exactly as the multi-file path does via resolveTargetPath:
            // a `../…` or absolute fileName pattern (from a malicious/imported template) must not escape and
            // create files anywhere writable (a shell rc, an autostart entry, a git hook).
            java.nio.file.Path resolved = targetDir.resolve(fileName).normalize();
            if (!resolved.startsWith(targetDir.normalize())) {
                setStatus(tr("status.templatePathEscape"));
                return;
            }
            target = resolved;
        }
        com.editora.template.TemplateVariableResolver vars = new com.editora.template.TemplateVariableResolver(
                answers,
                author,
                projectName,
                packageName,
                fileName,
                targetDir == null ? "" : targetDir.toString(),
                target == null ? "" : target.toString(),
                now);
        com.editora.snippet.ParsedSnippet parsed = com.editora.template.TemplateEngine.substitute(t.body(), vars);

        if (targetDir == null) {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName(fileName); // tab title + extension-based grammar; path stays null → Save-As
            addBuffer(b, true);
            b.applyTemplate(parsed);
            setStatus(tr("status.templateCreated", fileName));
        } else if (writeTemplateFile(target, parsed)) {
            openAndPlaceCaret(target, finalCaret(parsed));
            if (projectPanel != null) {
                projectPanel.refreshTree();
            }
            setStatus(tr("status.templateCreated", fileName));
        }
    }

    private void applyMultiFileTemplate(
            com.editora.template.Template t,
            java.nio.file.Path dir,
            java.util.Map<String, String> answers,
            String author,
            String projectName,
            String packageName,
            java.time.LocalDateTime now) {
        com.editora.template.TemplateVariableResolver vars = new com.editora.template.TemplateVariableResolver(
                answers, author, projectName, packageName, "", dir.toString(), "", now);
        java.nio.file.Path primary = null;
        int primaryCaret = 0;
        for (com.editora.template.TemplateFile f : t.files()) {
            java.nio.file.Path target = com.editora.template.TemplateEngine.resolveTargetPath(dir, f.path(), vars);
            if (target == null) {
                setStatus(tr("status.templatePathEscape"));
                continue;
            }
            com.editora.snippet.ParsedSnippet parsed = com.editora.template.TemplateEngine.substitute(f.body(), vars);
            if (writeTemplateFile(target, parsed) && primary == null) {
                primary = target;
                primaryCaret = finalCaret(parsed);
            }
        }
        if (primary != null) {
            openAndPlaceCaret(primary, primaryCaret);
            if (projectPanel != null) {
                projectPanel.refreshTree();
            }
            setStatus(tr("status.templateCreated", primary.getFileName().toString()));
        }
    }

    /** Writes a rendered template file (UTF-8), refusing to overwrite an existing file. */
    private boolean writeTemplateFile(java.nio.file.Path target, com.editora.snippet.ParsedSnippet parsed) {
        if (java.nio.file.Files.exists(target)) {
            setStatus(tr("status.templateExists", target.getFileName()));
            return false;
        }
        try {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.writeString(target, parsed.text());
            return true;
        } catch (java.io.IOException e) {
            setStatus(tr("status.templateWriteFailed", e.getMessage()));
            return false;
        }
    }

    /** Opens {@code target} and places the caret at the template's {@code ${cursor}} offset. */
    private void openAndPlaceCaret(java.nio.file.Path target, int caret) {
        openPath(target);
        EditorBuffer b = activeBuffer();
        if (b != null) {
            CodeArea a = b.getArea();
            int c = Math.max(0, Math.min(caret, a.getLength()));
            javafx.application.Platform.runLater(() -> {
                a.moveTo(c);
                a.requestFollowCaret();
            });
        }
    }

    /** The absolute offset of the template's {@code $0} ({@code ${cursor}}) stop, else end of text. */
    private static int finalCaret(com.editora.snippet.ParsedSnippet parsed) {
        for (com.editora.snippet.TabStop stop : parsed.stops()) {
            if (stop.isFinal()) {
                return stop.ranges().get(0)[0];
            }
        }
        return parsed.text().length();
    }

    /** The active project's name for {@code ${projectName}}, or {@code ""} when there is none. */
    private String activeProjectName() {
        Project p = projects == null ? null : projects.active();
        return p == null ? "" : p.name();
    }

    /** The folder a "new in folder" template defaults to: the active file's dir, else project root, else home. */
    private java.nio.file.Path defaultNewDir() {
        EditorBuffer b = activeBuffer();
        if (b != null && b.getPath() != null && b.getPath().getParent() != null) {
            return b.getPath().getParent();
        }
        Project p = projects == null ? null : projects.active();
        if (p != null) {
            return java.nio.file.Path.of(p.root());
        }
        return java.nio.file.Path.of(System.getProperty("user.home", "."));
    }

    /** Opens (creating from an example if needed) a user template file under {@code <configDir>/templates}. */
    private void editUserTemplates() {
        java.nio.file.Path file = templates.userDir().resolve("example.json");
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, USER_TEMPLATE_EXAMPLE);
            }
            openPath(file);
            setStatus(tr("status.editingTemplates"));
        } catch (java.io.IOException e) {
            setStatus(tr("status.templateOpenFailed", e.getMessage()));
        }
    }

    private static final String USER_TEMPLATE_EXAMPLE = """
            {
              "name": "My Template",
              "description": "A starter template — edit it, then run \\"Template: Reload Templates\\"",
              "language": "java",
              "fileName": "${className:Example}.java",
              "body": [
                "public class ${className:Example} {",
                "    ${cursor}",
                "}"
              ]
            }
            """;

    // ---- Personal Notes ----

    /** Canonical-path key for a buffer's notes in the store (cheap; no content hashing). */
    private static String noteKey(EditorBuffer buffer) {
        return com.editora.config.PathKeys.canonicalKey(buffer.getPath());
    }

    private Tab tabForKey(String fileKey) {
        for (Tab tab : tabPane.getTabs()) {
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

    /**
     * Zips the active config directory into a timestamped {@code .zip} in the user's home directory
     * (Settings → Advanced "Export configuration", and the {@code config.export} command). Shows the
     * resulting path in a confirmation dialog since the trigger lives in the Settings window.
     */
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

    /**
     * Exports the active Mermaid diagram (.mmd file) to SVG/PNG/PDF via mmdc. Asks for a destination
     * (format inferred from the chosen extension), renders off-thread, and reports the result.
     */
    /**
     * Exports the active buffer's source text to a syntax-highlighted, light-themed PDF (any text file).
     * Honors the Settings toggles (line numbers, syntax highlighting) + page size. Runs off the FX thread.
     */
    private void exportCodePdf() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            setStatus(tr("status.noFileOpen"));
            return;
        }
        String base = bufferBaseName(b);
        java.io.File f = choosePdfDestination(base);
        if (f == null) {
            return;
        }
        Settings s = config.getSettings();
        setStatus(tr("status.pdf.exporting"));
        pdfService.exportCode(
                b.getContent(),
                grammarKey(b),
                s.isPdfSyntaxHighlighting(),
                s.isPdfLineNumbers(),
                s.getTabSize(),
                s.getPdfPageSize(),
                f.toPath(),
                r -> reportPdf(r, f));
    }

    /**
     * Exports the active buffer's rendered preview to PDF: a Mermaid {@code .mmd} diagram via mmdc's
     * native vector PDF, or a Markdown document via the native PDF writer. No-op for non-previewable buffers.
     */
    private void exportPreviewPdf() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.hasPreview()) {
            setStatus(tr("status.pdf.noPreview"));
            return;
        }
        java.io.File f = choosePdfDestination(bufferBaseName(b));
        if (f == null) {
            return;
        }
        setStatus(tr("status.pdf.exporting"));
        String pageSize = config.getSettings().getPdfPageSize();
        java.util.function.Consumer<com.editora.pdf.PdfExportService.Result> report = r -> reportPdf(r, f);
        if (b.isMarkdown()) {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            pdfService.exportMarkdown(
                    b.getContent(), baseDir, pageSize, mermaid.mmdcCommandOrNull(), f.toPath(), report);
        } else if (b.isDiagram()) { // Mermaid (.mmd) — CLI render to PDF
            mermaid.exportDiagram(
                    b.getContent(),
                    f.toPath(),
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else if (b.isRenderedDiagram()) { // Graphviz DOT / PlantUML — CLI render to PDF
            diagram.exportToPath(
                    b.diagramKind(),
                    b.getContent(),
                    f.toPath(),
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else if (b.isSvg()) { // rasterize the SVG source and embed it as a PDF page
            byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (png == null) {
                report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                return;
            }
            pdfService.exportImages(java.util.List.of(png), pageSize, f.toPath(), report);
        } else if (b.isTypst()) { // Typst — native CLI render straight to a (multi-page) PDF
            typst.exportToPath(
                    b.getContent(),
                    b.getPath(),
                    f.toPath(),
                    r -> report.accept(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message())));
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            java.util.List<byte[]> chunks = b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet());
            if (chunks == null || chunks.isEmpty()) {
                report.accept(new com.editora.pdf.PdfExportService.Result(false, tr("status.pdf.noPreview")));
                return;
            }
            pdfService.exportImages(chunks, pageSize, f.toPath(), report);
        }
    }

    /** Exports the active Markdown buffer's rendered preview to a standalone HTML file. */
    private void exportPreviewHtml() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkdown()) {
            setStatus(tr("status.html.notMarkdown"));
            return;
        }
        java.io.File f = chooseHtmlDestination(bufferBaseName(b));
        if (f == null) {
            return;
        }
        try {
            String base = bufferBaseName(b);
            int dot = base.lastIndexOf('.');
            String title = dot > 0 ? base.substring(0, dot) : base;
            String html = com.editora.editor.MarkdownHtmlExport.toHtml(
                    b.getContent(), title, config.getSettings().isMathSupport());
            java.nio.file.Files.writeString(f.toPath(), html);
            setStatus(tr("status.html.exported", f.toString()));
            openPath(f.toPath()); // show the generated HTML in a tab
        } catch (Exception ex) {
            setStatus(tr("status.html.exportFailed", String.valueOf(ex.getMessage())));
        }
    }

    /** A Save dialog defaulting to {@code <base-without-ext>.html}. */
    private java.io.File chooseHtmlDestination(String base) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.htmlExport.title"));
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "document" : base)) + ".html");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("HTML", "*.html"));
        return chooser.showSaveDialog(stage);
    }

    /**
     * The buffer's file name — from its on-disk path when saved, else its suggested display name, else a
     * default. Drives the syntax-highlighting grammar lookup and the default export file name (PDF, Mermaid).
     * ({@code EditorBuffer.getDisplayName()} is null for a saved file, so the path must be consulted.)
     */
    private String bufferBaseName(EditorBuffer b) {
        if (b.getPath() != null) {
            return b.getPath().getFileName().toString();
        }
        String dn = b.getDisplayName();
        return dn == null || dn.isBlank() ? "document" : dn;
    }

    /**
     * The key used to resolve a buffer's grammar for export/print — its <em>full path</em> (so
     * location-based types like {@code ~/.ssh/config} or {@code /etc/hosts} resolve, matching the live
     * editor), or its display base name for an unsaved buffer.
     */
    private String grammarKey(EditorBuffer b) {
        return b.getPath() != null ? b.getPath().toString() : bufferBaseName(b);
    }

    /** A Save dialog defaulting to {@code <base-without-ext>.pdf}. */
    private java.io.File choosePdfDestination(String base) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.pdfExport.title"));
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "document" : base)) + ".pdf");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        return chooser.showSaveDialog(stage);
    }

    /** Exports the active Markwhen buffer's parsed timeline to a JSON file (preview menu + palette). */
    private void exportMarkwhenJson() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkwhen()) {
            setStatus(tr("status.markwhen.notMarkwhen"));
            return;
        }
        String base = bufferBaseName(b);
        int dot = base == null ? -1 : base.lastIndexOf('.');
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "timeline" : base)) + ".json");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        if (b.getPath() != null && b.getPath().getParent() != null && isLocalBuffer(b)) {
            chooser.setInitialDirectory(b.getPath().getParent().toFile());
        }
        java.io.File f = chooser.showSaveDialog(stage);
        if (f == null) {
            return;
        }
        try {
            String json =
                    com.editora.markwhen.MarkwhenJson.toJson(com.editora.markwhen.MarkwhenParser.parse(b.getContent()));
            java.nio.file.Files.writeString(f.toPath(), json);
            setStatus(tr("status.markwhen.jsonExported", f.getName()));
        } catch (java.io.IOException e) {
            setStatus(tr("status.markwhen.exportFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Toggles the active Markwhen buffer's preview between the timeline and calendar renderers. */
    private void toggleMarkwhenView() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkwhen()) {
            setStatus(tr("status.markwhen.notMarkwhen"));
            return;
        }
        b.toggleMarkwhenView();
        setStatus(tr(
                b.getMarkwhenView() == EditorBuffer.MarkwhenView.CALENDAR
                        ? "status.markwhen.viewCalendar"
                        : "status.markwhen.viewTimeline"));
    }

    /** Flips a structured (JSON/YAML/TOML) preview between the tree and the OpenAPI-docs view. */
    private void toggleStructuredView() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isStructured()) {
            setStatus(tr("status.structured.notStructured"));
            return;
        }
        b.toggleStructuredView();
        setStatus(tr(b.isStructuredOpenApi() ? "status.structured.viewToggled" : "status.structured.notOpenApi"));
    }

    /** Persists the Markwhen buffer's preview renderer choice (TIMELINE default → removed). */
    private void persistMarkwhenView(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        java.util.Map<String, String> map = config.getWorkspaceState().getMarkwhenViews();
        if (buffer.getMarkwhenView() == EditorBuffer.MarkwhenView.TIMELINE) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), buffer.getMarkwhenView().name());
        }
        requestSave();
    }

    /** Reports a PDF export result: status + (on failure) an error dialog. */
    private void reportPdf(com.editora.pdf.PdfExportService.Result r, java.io.File f) {
        if (r.ok()) {
            setStatus(tr("status.pdf.exported", f.toString()));
        } else {
            String msg = String.valueOf(r.message());
            setStatus(tr("status.pdf.exportFailed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(stage);
            err.setTitle(tr("dialog.pdfExport.title"));
            err.setHeaderText(tr("status.pdf.exportFailed", ""));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /** Exports the active Markdown preview to a MS Word {@code .docx} (Apache POI). */
    private void exportPreviewDocx() {
        exportPreviewOffice(true);
    }

    /** Exports the active Markdown preview to an OpenDocument Text {@code .odt} (hand-rolled). */
    private void exportPreviewOdt() {
        exportPreviewOffice(false);
    }

    private void exportPreviewOffice(boolean docx) {
        EditorBuffer b = activeBuffer();
        // Word/ODT render the Markdown document model — diagrams (.mmd) aren't supported, only Markdown.
        if (b == null || !b.isMarkdown()) {
            setStatus(tr("status.office.notMarkdown"));
            return;
        }
        String ext = docx ? "docx" : "odt";
        String filter = docx ? "Word" : "OpenDocument";
        java.io.File f = chooseOfficeDestination(bufferBaseName(b), ext, filter);
        if (f == null) {
            return;
        }
        setStatus(tr("status.office.exporting"));
        java.nio.file.Path baseDir = b.getPath() == null ? null : b.getPath().getParent();
        java.util.List<String> mmdc = mermaid.mmdcCommandOrNull(); // ```mermaid blocks → diagram images
        java.util.function.Consumer<com.editora.office.OfficeExportService.Result> cb = r -> reportOffice(r, f);
        if (docx) {
            officeService.exportDocx(b.getContent(), baseDir, mmdc, f.toPath(), cb);
        } else {
            officeService.exportOdt(b.getContent(), baseDir, mmdc, f.toPath(), cb);
        }
    }

    /** A Save dialog defaulting to {@code <base-without-ext>.<ext>}. */
    private java.io.File chooseOfficeDestination(String base, String ext, String filterName) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.officeExport.title"));
        int dot = base == null ? -1 : base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : (base == null ? "document" : base)) + "." + ext);
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(filterName, "*." + ext));
        return chooser.showSaveDialog(stage);
    }

    /** Reports an office export result: status + (on failure) an error dialog. */
    private void reportOffice(com.editora.office.OfficeExportService.Result r, java.io.File f) {
        if (r.ok()) {
            setStatus(tr("status.office.exported", f.toString()));
        } else {
            String msg = String.valueOf(r.message());
            setStatus(tr("status.office.exportFailed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(stage);
            err.setTitle(tr("dialog.officeExport.title"));
            err.setHeaderText(tr("status.office.exportFailed", ""));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /**
     * Prints the active buffer's source code via the native print dialog. Honors the (shared with PDF)
     * "include line numbers" + "syntax highlighting" settings; always light. Off the FX thread.
     */
    private void printCode() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            setStatus(tr("status.noFileOpen"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            setStatus(tr("status.print.noPrinter"));
            return;
        }
        Settings s = config.getSettings();
        setStatus(tr("status.print.preparing"));
        printService.prepareCode(
                b.getContent(),
                grammarKey(b),
                s.isPdfSyntaxHighlighting(),
                s.isPdfLineNumbers(),
                s.getTabSize(),
                prepared -> openPrintPreview(job, prepared));
    }

    /**
     * Prints the active buffer's rendered preview: a Mermaid {@code .mmd} diagram (via mmdc) or a
     * Markdown document (native nodes, block-aware pagination). No-op for non-previewable buffers.
     */
    private void printPreview() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.hasPreview()) {
            setStatus(tr("status.print.noPreview"));
            return;
        }
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) {
            setStatus(tr("status.print.noPrinter"));
            return;
        }
        setStatus(tr("status.print.preparing"));
        java.util.function.Consumer<com.editora.print.PrintService.Prepared> open =
                prepared -> openPrintPreview(job, prepared);
        if (b.isMarkdown()) {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            printService.prepareMarkdown(b.getContent(), baseDir, open);
        } else if (b.isDiagram()) { // Mermaid — CLI render
            printService.prepareMermaid(b.getContent(), mermaid.mmdcCommandOrNull(), appThemeDark(), open);
        } else if (b.isRenderedDiagram()) { // Graphviz DOT / PlantUML — CLI render to a temp PNG, then paginate
            printDiagramViaImage(b, job);
        } else if (b.isSvg()) { // rasterize the SVG source, paginate as image pages
            byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(
                    b.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (png == null) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                return;
            }
            printService.prepareImages(java.util.List.of(png), open);
        } else if (b.isTypst()) { // Typst — CLI render to page PNGs, paginate as image pages
            typst.renderPages(b.getContent(), b.getPath(), pages -> {
                if (pages == null || pages.isEmpty()) {
                    openPrintPreview(
                            job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                    return;
                }
                printService.prepareImages(pages, open);
            });
        } else { // Markwhen timeline / JSON-YAML-TOML tree / XML tree — snapshot the rendered preview (light)
            java.util.List<byte[]> chunks = b.snapshotPreviewChunks(Themes.lightUserAgentStylesheet());
            if (chunks == null || chunks.isEmpty()) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, tr("status.print.noPreview")));
                return;
            }
            printService.prepareImages(chunks, open);
        }
    }

    /** Prints a DOT/PlantUML diagram by rendering it to a temporary PNG via its CLI, then paginating the image
     *  (there's no native-vector print path for the diagram tools, unlike Markdown). */
    private void printDiagramViaImage(EditorBuffer b, javafx.print.PrinterJob job) {
        java.nio.file.Path tmp;
        try {
            tmp = java.nio.file.Files.createTempFile("editora-diagram", ".png");
        } catch (java.io.IOException e) {
            openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, e.getMessage()));
            return;
        }
        diagram.exportToPath(b.diagramKind(), b.getContent(), tmp, r -> {
            if (!r.ok()) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, r.message()));
                return;
            }
            try {
                byte[] png = java.nio.file.Files.readAllBytes(tmp);
                java.nio.file.Files.deleteIfExists(tmp);
                printService.prepareImages(java.util.List.of(png), prepared -> openPrintPreview(job, prepared));
            } catch (java.io.IOException e) {
                openPrintPreview(job, new com.editora.print.PrintService.Prepared(null, e.getMessage()));
            }
        });
    }

    /** Opens the Print Preview window for a prepared document, or reports a preparation failure. */
    private void openPrintPreview(javafx.print.PrinterJob job, com.editora.print.PrintService.Prepared prepared) {
        if (!prepared.ok()) {
            reportPrint(new com.editora.print.PrintService.Result(false, prepared.error()));
            return;
        }
        new PrintPreview(
                        stage,
                        job,
                        prepared.paginator(),
                        this::reportPrint,
                        () -> setStatus(tr("status.print.printing")),
                        () -> setStatus(tr("status.print.cancelled")))
                .show();
    }

    /** Reports a print result: status + (on failure) an error dialog. */
    private void reportPrint(com.editora.print.PrintService.Result r) {
        if (r.ok()) {
            setStatus(tr("status.print.done"));
        } else {
            String msg = String.valueOf(r.message());
            setStatus(tr("status.print.failed", msg));
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.initOwner(stage);
            err.setTitle(tr("command.editor.print"));
            err.setHeaderText(tr("status.print.failed", ""));
            err.setContentText(msg);
            err.showAndWait();
        }
    }

    /** Persists a word to the shared personal dictionary, then drops every open buffer's memoized spell
     *  verdicts — the word set is shared, but each buffer's overlay caches its own results, so "Add to
     *  Dictionary" in one tab used to leave the word squiggled in the others for the rest of the session. */
    private void addUserWordAndRefreshAll(String word) {
        config.addUserWord(word);
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.refreshSpell();
            }
        }
    }

    private void applyViewSettings(EditorBuffer buffer) {
        Settings s = config.getSettings();
        int effectiveFont = Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom()));
        buffer.setFont(s.getFontFamily(), effectiveFont);
        // Zen/Expert (per window, "focus modes") hide distraction-free chrome without clobbering the saved
        // prefs; Simple UI mode additionally removes the whole gutter + minimap. All effective overlays.
        // Expert keeps the full editor view — line numbers, ruler, current-line highlight, minimap — so those
        // key on the real zen flag; only the whitespace guides follow the combined focus flag like Zen.
        boolean zen = zenActive();
        boolean focus = zen || expertActive();
        boolean simple = simpleModeActive();
        buffer.setColumnRulerVisible(Chrome.columnRuler(s.isShowColumnRuler(), zen));
        buffer.setNoteIndicatorsVisible(s.isNotesSupport() && s.isShowNoteIndicators());
        buffer.setLineHighlightOn(Chrome.lineHighlight(s.isHighlightCurrentLine(), zen));
        buffer.setLineNumbersVisible(Chrome.lineNumbers(s.isShowLineNumbers(), zen, simple));
        buffer.setMinimapVisible(Chrome.minimap(s.isShowMinimap(), zen, simple));
        buffer.setWordWrap(s.isWordWrap());
        buffer.setGutterVisible(Chrome.gutter(simple)); // Simple mode removes the entire gutter strip
        if (simple) {
            buffer.unfoldAll(); // collapsed regions would be stranded behind the now-hidden fold chevrons
        }
        buffer.setWhitespaceVisible(Chrome.whitespace(s.isShowWhitespace(), focus));
        buffer.setTabSize(s.getTabSize());
        buffer.setLineHighlightColor(EditorThemes.lineHighlightFor(s.getEditorTheme()));
        buffer.setMinimapColors(
                EditorThemes.minimapTextFor(s.getEditorTheme()), EditorThemes.minimapViewportFor(s.getEditorTheme()));
        buffer.setFoldPreviewColors(
                EditorThemes.editorBackgroundFor(s.getEditorTheme()),
                EditorThemes.editorForegroundFor(s.getEditorTheme()));
        buffer.setSpellLanguage(spellLanguageFor(buffer)); // per-file override, else the global default
        buffer.setSpellCheckEnabled(s.isSpellCheck());
        buffer.setUserDictionaryEnabled(s.isPersonalDictionary());
        buffer.setTechnicalDictionaryEnabled(s.isTechnicalDictionary());
        buffer.setFormatBarEnabled(s.isMarkdownFormatBar());
        buffer.setAiActionsEnabled(aiCoordinator.isActionsAvailable()); // floating selection Explain/Rewrite bar
        buffer.setCsvRainbowEnabled(s.isCsvRainbow()); // per-column CSV coloring (no-op for non-CSV buffers)
        buffer.setStructuredPreviewEnabled(s.isStructuredPreview()); // JSON/YAML/TOML tree + OpenAPI docs preview
        buffer.setSvgPreviewEnabled(s.isSvgPreview()); // rendered image preview for .svg files
        buffer.setTypstPreviewEnabled(s.isTypstSupport()); // multi-page rendered preview for .typ documents
        buffer.setCrontabPreviewEnabled(s.isCrontabPreview()); // schedule decode + next runs for crontab files
        buffer.setFstabPreviewEnabled(s.isFstabPreview()); // per-line mount decode for /etc/fstab files
        buffer.setSystemdPreviewEnabled(s.isSystemdPreview()); // directive glosses + OnCalendar decode
        buffer.setSshConfigPreviewEnabled(s.isSshConfigPreview()); // per-Host connection summary
        buffer.setDockerfilePreviewEnabled(s.isDockerfilePreview()); // per-stage build digest
        buffer.setGithubActionsPreviewEnabled(s.isGithubActionsPreview()); // workflow triggers + jobs digest
        if (buffer.isStructured() || buffer.isXml() || buffer.isSvg()) {
            ensurePreviewControls(buffer); // attach/detach the 3-mode toggle as the structured/XML/SVG gate flips
        }
        buffer.setAutoRenameTag(s.isAutoRenameTag()); // paired-tag rename mirroring (html/xml buffers only)
        buffer.setAutoCloseTags(s.isAutoCloseTags()); // ">" inserts the matching closer (html/xml buffers only)
        applyEditorConfig(buffer); // .editorconfig overrides the global indent/EOL/ruler/charset (when on)
    }

    /** Whether {@code .editorconfig} support is enabled. */
    private boolean editorConfigEnabled() {
        return config.getSettings().isEditorConfigSupport();
    }

    /**
     * Pushes the file's resolved {@code .editorconfig} properties onto {@code buffer} (indent style/size,
     * EOL, ruler column from {@code max_line_length}, and write charset), or clears the overrides when the
     * feature is off / the file is non-local / has no path. The save-time props are stored on the buffer
     * for {@code writeBuffer}. Reuses {@link com.editora.editorconfig.EditorConfig#resolveFor}.
     */
    private void applyEditorConfig(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (!editorConfigEnabled() || path == null || !com.editora.vfs.Vfs.isLocal(path)) {
            buffer.setEditorConfigProps(com.editora.editorconfig.EditorConfigProperties.EMPTY);
            applyEffectiveIndent(buffer, com.editora.editorconfig.EditorConfigProperties.EMPTY);
            buffer.setRulerColumn(null);
            buffer.setCharsetOverride(null);
            return; // EOL override is left to a manual choice; tab size already comes from global settings
        }
        com.editora.editorconfig.EditorConfigProperties p = com.editora.editorconfig.EditorConfig.resolveFor(path);
        buffer.setEditorConfigProps(p);
        applyEffectiveIndent(buffer, p);
        if (p.insertSpaces() != null || p.tabWidth() != null || p.indentSize() != null) {
            buffer.setTabSize(p.effectiveTabWidth(config.getSettings().getTabSize()));
        }
        if (p.endOfLine() != null) {
            buffer.setEolOverride("crlf".equals(p.endOfLine()) ? "CRLF" : "lf".equals(p.endOfLine()) ? "LF" : null);
        }
        buffer.setRulerColumn(p.maxLineLength()); // null = default 80, OFF = hide
        buffer.setCharsetOverride(p.charset());
    }

    /**
     * Resolves the effective indent override for a buffer and pushes it via {@link EditorBuffer#setIndentOverride}.
     * Precedence: a file's {@code .editorconfig} {@code indent_style} (when present) wins; else the global
     * {@link Settings#getIndentStyle()} preference ({@code space}/{@code tab}); else {@code null} → per-file
     * auto-detection ({@code Indenter.detectUnit}). For {@code space}, the size falls back to the global tab size
     * when {@code .editorconfig} didn't specify {@code indent_size}.
     */
    private void applyEffectiveIndent(EditorBuffer buffer, com.editora.editorconfig.EditorConfigProperties p) {
        Boolean insertSpaces = p.insertSpaces();
        Integer size = p.indentSize();
        if (insertSpaces == null) {
            String style = config.getSettings().getIndentStyle();
            if ("space".equals(style)) {
                insertSpaces = Boolean.TRUE;
                if (size == null) {
                    size = config.getSettings().getTabSize();
                }
            } else if ("tab".equals(style)) {
                insertSpaces = Boolean.FALSE;
            }
            // "detect" leaves insertSpaces null → Indenter falls back to detectUnit
        }
        buffer.setIndentOverride(insertSpaces, size);
    }

    /**
     * Opens the {@code .editorconfig} file governing the active buffer (the status-bar indicator's click action,
     * also a palette command). Reports a status message when there's no file / no governing {@code .editorconfig}.
     */
    private void openActiveEditorConfig() {
        EditorBuffer buffer = activeBuffer();
        Path path = buffer == null ? null : buffer.getPath();
        if (path == null || !com.editora.vfs.Vfs.isLocal(path)) {
            setStatus(tr("status.editorConfig.none"));
            return;
        }
        Path ec = com.editora.editorconfig.EditorConfig.nearestFile(path);
        if (ec == null) {
            setStatus(tr("status.editorConfig.none"));
            return;
        }
        openPath(ec);
    }

    /** Re-applies (or clears) {@code .editorconfig} for every open buffer — init + on settings apply. */
    private void applyEditorConfigSupport() {
        if (!editorConfigEnabled()) {
            com.editora.editorconfig.EditorConfig.clearCache();
        }
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                applyEditorConfig(buffer);
            }
        }
        if (statusBar != null) {
            statusBar.refresh();
        }
    }

    /** The spell-check language for a buffer: its per-file override (if any/valid), else the global default. */
    private String spellLanguageFor(EditorBuffer buffer) {
        String def = config.getSettings().getSpellLanguage();
        Path p = buffer.getPath();
        if (p == null) {
            return def;
        }
        String override = config.getWorkspaceState().getSpellLanguages().get(p.toString());
        return override != null && SpellDictionaries.isAvailable(override) ? override : def;
    }

    private void applyViewSettingsToAllBuffers(Settings settings) {
        applyEditorTheme(settings.getEditorTheme());
        applyChromeVisibility();
        applyProjectSupport();
        git.applySupport();
        historyCoordinator.applySupport(); // re-gate the Local File History tool window + refresh its list
        git.applyBlame(); // (re)apply inline blame to the active buffer (effective gate: git + setting + !simple)
        notesCoordinator.applySupport();
        mermaid.applySupport();
        diagram.applySupport();
        typst.applySupport();
        searchCoordinator.applyRipgrepSupport();
        applyMathSupport();
        httpClient.applySupport();
        htmlPreview.applySupport();
        logViewer.applySupport();
        applyMcpSupport();
        applyAgentSupport();
        aiCoordinator.applySupport(); // re-probe connectivity + re-gate the floating selection Explain/Rewrite bar
        todoCoordinator.applyHighlight(); // (re)compile TODO patterns + push the matcher to every buffer
        csvCoordinator.applySupport(); // re-gate the in-editor CSV grid preview on every open buffer
        applyMarkdownLint(); // push Markdown-lint enabled state to every buffer
        applyAutoSave();
        applyAutocomplete();
        applyMultiCaret();
        lspCoordinator.applySupport(); // (re)configure LSP: command/enabled change re-detects + re-gates buffers
        debugCoordinator.applySupport(); // (re)configure DAP after LSP (it layers on jdtls)
        applyMarkdownPreviewTheme(); // re-resolve "follow app" previews + the toggle glyph after a theme change
        // Match the console fonts (External Tools / Run / Debug / build tools) to the editor's code-area font.
        int consoleFont = Math.max(1, (int) Math.round(settings.getFontSize() * settings.getFontZoom()));
        externalToolCoordinator.panel().setOutputFont(settings.getFontFamily(), consoleFont);
        runCoordinator.panel().setOutputFont(settings.getFontFamily(), consoleFont);
        debugCoordinator.panel().setConsoleFont(settings.getFontFamily(), consoleFont);
        buildOutputPanel.setOutputFont(settings.getFontFamily(), consoleFont);
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                applyViewSettings(buffer);
            }
        }
        // If the Welcome tab is open, rebuild it so its Open Folder / Clone actions track the
        // Projects/Git toggles that may have just changed.
        if (welcomeTab != null) {
            welcomePane.refresh();
        }
    }

    /** Whether multiple cursors / column selection is active. Off in Simple UI mode; saved setting unchanged. */
    private boolean multiCaretEnabled() {
        return config.getSettings().isMultiCaret() && !simpleModeActive();
    }

    /** Pushes the multiple-cursors / column-selection setting to every open buffer. */
    private void applyMultiCaret() {
        boolean on = multiCaretEnabled(); // effective: off in Simple UI mode
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.setMultiCaretEnabled(on);
            }
        }
    }

    /** Pushes the autocomplete settings (master + per-source) to every open buffer. */
    private void applyAutocomplete() {
        Settings s = config.getSettings();
        boolean mermaidAc = mermaid.effectiveAutocomplete();
        boolean aiInline = aiCoordinator.isInlineCompletionEnabled();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.setAutocomplete(
                        s.isAutocomplete(), s.isAutocompleteProse(), s.isAutocompleteSnippets(), mermaidAc);
                buffer.setCompletionDocEnabled(s.isCompletionDoc());
                buffer.setAiCompletionEnabled(aiInline);
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
            if (currentEditorThemeCss != null) {
                sheets.remove(currentEditorThemeCss);
            }
            currentEditorThemeCss = EditorThemes.stylesheetFor(themeName);
            if (currentEditorThemeCss != null && !sheets.contains(currentEditorThemeCss)) {
                sheets.add(currentEditorThemeCss);
            }
        }
        Color highlight = EditorThemes.lineHighlightFor(themeName);
        Color mmText = EditorThemes.minimapTextFor(themeName);
        Color mmViewport = EditorThemes.minimapViewportFor(themeName);
        Color editorBg = EditorThemes.editorBackgroundFor(themeName);
        Color editorFg = EditorThemes.editorForegroundFor(themeName);
        for (Tab tab : tabPane.getTabs()) {
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
            markActive = false;
            CodeArea area = activeArea();
            if (area != null) {
                area.deselect();
            }
        }
        setStatus("");
    }

    /** The selection policy for caret-movement commands: extend from the mark when it's active. */
    private SelectionPolicy selPolicy() {
        return markActive ? SelectionPolicy.ADJUST : SelectionPolicy.CLEAR;
    }

    /** Emacs {@code C-SPC}: drop the mark at the caret so subsequent movement selects from here. */
    private void setMark() {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        int caret = area.getCaretPosition();
        area.selectRange(caret, caret); // anchor = caret; ADJUST moves then extend from here
        markActive = true;
        setStatus(tr("status.markSet"));
    }

    /** Emacs {@code C-x C-x}: move the caret to the mark (and the mark to the caret). */
    private void exchangePointAndMark() {
        CodeArea area = activeArea();
        if (area == null || area.getSelection().getLength() == 0) {
            return;
        }
        area.selectRange(area.getCaretPosition(), area.getAnchor());
        markActive = true;
        area.requestFollowCaret();
    }

    /** Clears the Emacs mark (e.g. after a clipboard action or a mouse click). */
    private void deactivateMark() {
        markActive = false;
    }

    private void withArea(java.util.function.Consumer<CodeArea> action) {
        if (!activeEditable()) {
            return;
        }
        CodeArea area = activeArea();
        if (area != null) {
            action.accept(area);
        }
    }

    /**
     * Guards mutating commands: returns {@code false} (and echoes a hint) when the active buffer is
     * read-only (huge-file or user View mode), so edits are refused instead of bypassing
     * {@code setEditable(false)} via the app's own commands. Returns {@code true} when there is no
     * buffer or it is editable.
     */
    private boolean activeEditable() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null && !buffer.isEditable()) {
            setStatus(tr("status.bufferReadOnly"));
            return false;
        }
        return true;
    }

    /**
     * Toggles comments on the selection/current line: a line comment for a single line, a block/region
     * comment for a multi-line selection, depending on the language (see {@link com.editora.editops.Commenter}).
     */
    /** Manually opens the autocomplete popup for the active buffer (the {@code edit.completion} command). */
    private void triggerCompletion() {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.triggerCompletion();
        }
    }

    /** Toggles the completion documentation popup for the open completion list (the {@code edit.completionDoc}
     *  command, Ctrl+Q — IntelliJ "quick documentation"). */
    private void toggleCompletionDoc() {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.toggleCompletionDoc();
        }
    }

    private void toggleComment() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        // The comment logic lives on the buffer (so the editor right-click menu can invoke it too).
        if (!buffer.toggleComment()) {
            setStatus(tr("status.noCommentSyntax"));
        }
    }

    /** Applies an Emacs transpose (chars/words/lines) to the active editable buffer at the caret. */
    private void transpose(java.util.function.BiFunction<String, Integer, com.editora.editops.Transposer.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.Transposer.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /** Applies a pure {@link com.editora.editops.LineOps} edit to the active area (duplicate / move line). */
    private void lineOp(java.util.function.BiFunction<String, Integer, com.editora.editops.LineOps.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.LineOps.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /**
     * Applies a pure {@link com.editora.editops.EmacsEdits} caret-based edit (backward-kill-word, the
     * case-word commands, join-line, the whitespace commands, open-line, kill-whole-line, …) to the
     * active editable buffer. Mirrors {@link #transpose} / {@link #lineOp}.
     */
    private void emacsEdit(java.util.function.BiFunction<String, Integer, com.editora.editops.EmacsEdits.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.EmacsEdits.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        deactivateMark();
        area.requestFocus();
    }

    /** Emacs {@code upcase-region} (`C-x C-u`) / {@code downcase-region} (`C-x C-l`): case the selection. */
    private void emacsCaseRegion(boolean upper) {
        if (!activeEditable()) {
            return;
        }
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        var sel = area.getSelection();
        com.editora.editops.EmacsEdits.Edit edit = upper
                ? com.editora.editops.EmacsEdits.upcaseRegion(area.getText(), sel.getStart(), sel.getEnd())
                : com.editora.editops.EmacsEdits.downcaseRegion(area.getText(), sel.getStart(), sel.getEnd());
        if (edit == null) {
            return; // no selection / already the target case
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        deactivateMark();
        area.requestFocus();
    }

    /** Emacs structural caret motion (forward/backward-sexp, beginning/end-of-defun); honors the mark. */
    private void sexpMove(java.util.function.BiFunction<String, Integer, Integer> nav) {
        moveAndFollow(a -> a.moveTo(nav.apply(a.getText(), a.getCaretPosition()), selPolicy()));
    }

    /** Emacs {@code mark-sexp} (`C-M-SPC`): select the balanced expression after the caret. */
    private void markSexp() {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        int caret = area.getCaretPosition();
        int end = com.editora.editops.SexpNav.forward(area.getText(), caret);
        if (end <= caret) {
            return;
        }
        area.selectRange(caret, end);
        markActive = true;
        area.requestFollowCaret();
    }

    /** Emacs {@code mark-paragraph} (`M-h`): select the paragraph (blank-line delimited) around the caret. */
    private void markParagraph() {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        int[] bounds = com.editora.editops.SexpNav.paragraphBounds(area.getText(), area.getCaretPosition());
        if (bounds[0] >= bounds[1]) {
            return;
        }
        area.selectRange(bounds[0], bounds[1]);
        markActive = true;
        area.requestFollowCaret();
    }

    /** Emacs {@code kill-sexp} (`C-M-k`): delete the balanced expression after the caret. */
    private void killSexp() {
        emacsEdit((text, caret) -> {
            int end = com.editora.editops.SexpNav.forward(text, caret);
            return end > caret ? new com.editora.editops.EmacsEdits.Edit(caret, end, "", caret) : null;
        });
    }

    /**
     * Emacs {@code zap-to-char} (`M-z`): read one more character, then delete from the caret up to and
     * including its next occurrence. The character is captured via a one-shot {@code KEY_TYPED} filter
     * on the focused area (interactive, like AceJump — the span computation is the pure, tested
     * {@link com.editora.editops.EmacsEdits#zapToChar}).
     */
    private void zapToChar() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        setStatus(tr("status.zapToChar"));
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, new javafx.event.EventHandler<>() {
            @Override
            public void handle(javafx.scene.input.KeyEvent e) {
                area.removeEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, this);
                e.consume();
                String ch = e.getCharacter();
                if (ch == null || ch.isEmpty() || ch.charAt(0) < ' ') {
                    setStatus(""); // Escape / no printable character: cancel
                    return;
                }
                com.editora.editops.EmacsEdits.Edit edit =
                        com.editora.editops.EmacsEdits.zapToChar(area.getText(), area.getCaretPosition(), ch.charAt(0));
                if (edit == null) {
                    setStatus(tr("status.zapNotFound", ch));
                    return;
                }
                area.replaceText(edit.from(), edit.to(), edit.replacement());
                area.moveTo(edit.caret());
                deactivateMark();
                setStatus("");
            }
        });
    }

    /**
     * Emacs {@code move-to-window-line-top-bottom} (`M-r`): cycle the caret through the center, top,
     * and bottom visible lines of the editor window on successive presses.
     */
    private void moveToWindowLine() {
        CodeArea a = activeArea();
        if (a == null) {
            return;
        }
        try {
            int first = a.firstVisibleParToAllParIndex();
            int last = a.lastVisibleParToAllParIndex();
            windowLineCycle = (windowLineCycle + 1) % 3;
            int target =
                    switch (windowLineCycle) {
                        case 0 -> (first + last) / 2; // center
                        case 1 -> first; // top
                        default -> last; // bottom
                    };
            a.moveTo(a.getAbsolutePosition(target, 0), selPolicy());
            a.requestFollowCaret();
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet — nothing to move to.
        }
    }

    /** Emacs {@code fill-paragraph} (`M-q`): re-wrap the paragraph at the caret to the fill column. */
    private void fillParagraph() {
        applyFill((text, b) -> com.editora.editops.Filler.fillParagraph(
                text, b.getFocusedArea().getCaretPosition(), fillColumn(), lineCommentFor(b)));
    }

    /** Emacs {@code fill-region}: re-wrap every paragraph in the selection (caret line if no selection). */
    private void fillRegion() {
        applyFill((text, b) -> {
            CodeArea a = b.getFocusedArea();
            int start = a.getSelection().getLength() > 0 ? a.getSelection().getStart() : a.getCaretPosition();
            int end = a.getSelection().getLength() > 0 ? a.getSelection().getEnd() : a.getCaretPosition();
            return com.editora.editops.Filler.fillRegion(text, start, end, fillColumn(), lineCommentFor(b));
        });
    }

    /** Shared applier for the fill commands (guarded by {@link #activeEditable()}). */
    private void applyFill(java.util.function.BiFunction<String, EditorBuffer, com.editora.editops.Filler.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.Filler.Edit edit = op.apply(area.getText(), buffer);
        if (edit == null) {
            return; // nothing to fill / already filled
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /** The active fill column (Settings), clamped to a sane minimum by {@code Settings.getFillColumn}. */
    private int fillColumn() {
        return config.getSettings().getFillColumn();
    }

    /** The buffer language's line-comment token (e.g. {@code "//"}), or {@code null} — for the fill prefix. */
    private static String lineCommentFor(EditorBuffer buffer) {
        String line =
                com.editora.editops.Commenter.styleFor(buffer.getLanguage()).line();
        return line == null || line.isBlank() ? null : line;
    }

    /** The string-manipulation command ids, in the order the {@code edit.stringOps} picker lists them. */
    private static final java.util.List<String> STRING_OP_IDS = java.util.List.of(
            "edit.case.cycle",
            "edit.case.camel",
            "edit.case.pascal",
            "edit.case.snake",
            "edit.case.screamingSnake",
            "edit.case.kebab",
            "edit.case.dot",
            "edit.case.swap",
            "edit.sortLinesAsc",
            "edit.sortLinesDesc",
            "edit.sortLinesByLength",
            "edit.reverseLines",
            "edit.shuffleLines",
            "edit.removeDuplicateLines",
            "edit.removeEmptyLines",
            "edit.trimTrailingWhitespace");

    /**
     * Applies a pure token transform ({@link com.editora.editops.StringCase} — the case-style
     * commands) to the selection, or to the identifier at the caret when nothing is selected; the
     * result is re-selected so repeated invocations (the {@code edit.case.cycle} gesture) keep
     * acting on the same token. One undoable {@code replaceText}, guarded by {@link #activeEditable()}.
     */
    private void caseOp(java.util.function.UnaryOperator<String> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        var sel = area.getSelection();
        int from;
        int to;
        if (sel.getLength() > 0) {
            from = sel.getStart();
            to = sel.getEnd();
        } else {
            int[] token = com.editora.editops.StringCase.tokenAt(area.getText(), area.getCaretPosition());
            if (token == null) {
                setStatus(tr("status.stringops.noTarget"));
                return;
            }
            from = token[0];
            to = token[1];
        }
        String before = area.getText().substring(from, to);
        String after = op.apply(before);
        if (after.equals(before)) {
            setStatus(tr("status.stringops.noChange"));
            return;
        }
        area.replaceText(from, to, after);
        area.selectRange(from, from + after.length());
        area.requestFocus();
    }

    /**
     * Applies a pure whole-line transform ({@link com.editora.editops.LineTransforms} — sort /
     * reverse / shuffle / dedupe / filter / trim) to the selection extended to full-line bounds, or
     * to the whole buffer when nothing is selected. One undoable {@code replaceText}; the
     * transformed lines are re-selected. Guarded by {@link #activeEditable()}.
     */
    private void lineTransform(java.util.function.UnaryOperator<String> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        String text = area.getText();
        var sel = area.getSelection();
        int from;
        int to;
        if (sel.getLength() > 0) {
            int[] bounds = com.editora.editops.LineTransforms.lineBounds(text, sel.getStart(), sel.getEnd());
            from = bounds[0];
            to = bounds[1];
        } else {
            from = 0;
            to = text.length();
        }
        String before = text.substring(from, to);
        String after = op.apply(before);
        if (after.equals(before)) {
            setStatus(tr("status.stringops.noChange"));
            return;
        }
        area.replaceText(from, to, after);
        area.selectRange(from, from + after.length());
        area.requestFocus();
    }

    /** {@code edit.stringOps} (`C-c x`): one picker over all string-manipulation commands (the Alt+M popup). */
    private void stringOpsPicker() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.edit.stringOps"),
                tr("palette.stringops.prompt"),
                () -> new java.util.ArrayList<>(STRING_OP_IDS),
                id -> tr("command." + id),
                id -> tr("command." + id + ".desc"),
                id -> registry.run(id));
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Emacs {@code set-fill-column} (`C-x f`): prompt for the fill column, persist it. */
    private void setFillColumn() {
        promptText(
                tr("dialog.fillColumn.title"), tr("dialog.fillColumn.label"), Integer.toString(fillColumn()), value -> {
                    try {
                        int col = Integer.parseInt(value.strip());
                        if (col < 1) {
                            setStatus(tr("status.fillColumn.invalid"));
                            return;
                        }
                        config.getSettings().setFillColumn(col);
                        config.save();
                        setStatus(tr("status.fillColumn.set", col));
                    } catch (NumberFormatException e) {
                        setStatus(tr("status.fillColumn.invalid"));
                    }
                });
    }

    /** Selects the whole document in the active area (no edit, so it works in read-only/view mode too). */
    private void selectAll() {
        CodeArea area = activeArea();
        if (area != null) {
            area.selectAll();
            area.requestFocus();
        }
    }

    /** Emacs-style vertical caret move (C-n/C-p) preserving the goal column; see {@link EditorBuffer#moveLine}. */
    private void moveLine(int delta) {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.moveLine(delta, selPolicy());
        }
    }

    /** Run a navigation action and scroll the viewport to follow the caret. */
    private void moveAndFollow(java.util.function.Consumer<CodeArea> motion) {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        motion.accept(area);
        area.requestFollowCaret();
    }

    /** Absolute offset of the first non-whitespace char on the caret's line (Emacs M-m). */
    /** Scrolls so the caret's line is vertically centered in the viewport (Emacs C-l, center). */
    private void recenterCaret() {
        CodeArea a = activeArea();
        if (a == null) {
            return;
        }
        try {
            int cur = a.getCurrentParagraph();
            int first = a.firstVisibleParToAllParIndex();
            int last = a.lastVisibleParToAllParIndex();
            int visible = Math.max(1, last - first + 1);
            a.showParagraphAtTop(Math.max(0, cur - visible / 2));
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet — nothing to recenter.
        }
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
        return !simpleModeActive();
    }

    /** Drops stale {@code externalTool.run.*} commands and re-registers the current set; called per window by
     *  {@link WindowManager}'s external-tools broadcast after a Settings edit. Delegates to the coordinator. */
    void refreshExternalToolCommands() {
        externalToolCoordinator.refreshCommands();
    }

    private void registerCommands() {
        registry.register(Command.of("file.new", this::onNew));
        registry.register(Command.of("window.new", () -> {
            if (windowManager != null) {
                windowManager.newWindow();
            }
        }));
        registry.register(Command.of("file.open", this::onOpen));
        registry.register(Command.of("file.find", () -> fileFinder.show(stage)));
        // --- Keyboard macros ---
        macroCoordinator.registerCommands(); // macro.* + one macro.run.<slug> per persisted macro
        // --- External Tools ---
        externalToolCoordinator.registerCommands(
                registry); // externalTool.run/clearOutput/rerunLast + per-tool run.<slug>
        // Project commands no-op when project support is disabled (fully gated).
        registry.register(Command.of("project.open", () -> {
            if (projectsEnabled()) {
                folderFinder.show(stage);
            }
        }));
        registry.register(Command.of("project.switch", () -> {
            if (projectsEnabled()) {
                projectPicker.show(stage);
            }
        }));
        registry.register(Command.of("project.close", () -> {
            if (projectsEnabled()) {
                closeProject();
            }
        }));
        registry.register(Command.of("project.delete", () -> {
            if (projectsEnabled()) {
                deleteProject();
            }
        }));
        registry.register(Command.of("file.save", this::onSave));
        // Palette / keybinding Save As is keyboard-first: prompt for the path in-scene (the toolbar button's
        // FXML onAction still opens the native file chooser).
        registry.register(Command.of("file.saveAs", () -> saveAsPrompt(activeBuffer())));
        registry.register(Command.of("file.saveAsAdmin", this::onSaveAsAdmin));
        registry.register(Command.of("buffer.close", this::onCloseTab));
        registry.register(Command.of("buffer.closeOthers", () -> closeOtherTabs(activeTab())));
        registry.register(Command.of("buffer.closeAll", this::closeAllTabs));
        registry.register(Command.of("buffer.closeUnmodified", this::closeUnmodifiedTabs));
        registry.register(Command.of("buffer.closeLeft", () -> closeTabsToLeft(activeTab())));
        registry.register(Command.of("buffer.closeRight", () -> closeTabsToRight(activeTab())));
        registry.register(Command.of("buffer.copyPath", () -> copyPath(activeBuffer())));
        registry.register(Command.of("buffer.togglePin", () -> togglePin(activeTab())));
        registry.register(Command.of("buffer.rename", () -> renameFile(activeBuffer(), activeTab())));
        registry.register(Command.of("file.revealInFileManager", this::revealActiveBuffer));
        registry.register(Command.of("file.openTerminal", this::openTerminalForActiveBuffer));
        registry.register(Command.of("buffer.next", this::nextBuffer));
        registry.register(Command.of("app.quit", this::onQuit));
        registry.register(Command.of("palette.show", this::onPalette));
        registry.register(Command.of("view.settings", this::onSettings));
        registry.register(Command.of("keymap.select", this::chooseKeymap));
        registry.register(Command.of("theme.setAppTheme", this::chooseAppTheme));
        registry.register(Command.of("theme.setEditorTheme", this::chooseEditorTheme));
        registry.register(Command.of("theme.reloadUserThemes", this::reloadUserThemes));
        // Settings palette commands — a command-palette equivalent for every Settings-window control.
        registry.register(Command.of("appearance.setFont", this::chooseFont));
        registry.register(Command.of(
                "appearance.setFontSize",
                () -> promptIntSetting(
                        "appearance.setFontSize",
                        () -> config.getSettings().getFontSize(),
                        6,
                        72,
                        v -> config.getSettings().setFontSize(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of("appearance.setUiLanguage", this::chooseUiLanguage));
        registry.register(Command.of("editor.setPdfPageSize", this::choosePdfPageSize));
        registry.register(Command.of(
                "view.togglePdfLineNumbers",
                () -> toggleSetting(
                        "view.togglePdfLineNumbers",
                        () -> config.getSettings().isPdfLineNumbers(),
                        v -> config.getSettings().setPdfLineNumbers(v),
                        null)));
        registry.register(Command.of(
                "view.togglePdfSyntaxHighlighting",
                () -> toggleSetting(
                        "view.togglePdfSyntaxHighlighting",
                        () -> config.getSettings().isPdfSyntaxHighlighting(),
                        v -> config.getSettings().setPdfSyntaxHighlighting(v),
                        null)));
        registry.register(Command.of(
                "view.toggleEditorConfig",
                () -> toggleSetting(
                        "view.toggleEditorConfig",
                        () -> config.getSettings().isEditorConfigSupport(),
                        v -> config.getSettings().setEditorConfigSupport(v),
                        this::applyEditorConfigSupport)));
        registry.register(Command.of("editorConfig.openActive", this::openActiveEditorConfig));
        registry.register(Command.of(
                "view.toggleProjectHidden",
                () -> toggleSetting(
                        "view.toggleProjectHidden",
                        () -> config.getSettings().isProjectShowHidden(),
                        v -> config.getSettings().setProjectShowHidden(v),
                        () -> projectPanel.setShowHidden(config.getSettings().isProjectShowHidden()))));
        registry.register(Command.of(
                "view.toggleNoteIndicators",
                () -> toggleSetting(
                        "view.toggleNoteIndicators",
                        () -> config.getSettings().isShowNoteIndicators(),
                        v -> config.getSettings().setShowNoteIndicators(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleCompletionDoc",
                () -> toggleSetting(
                        "view.toggleCompletionDoc",
                        () -> config.getSettings().isCompletionDoc(),
                        v -> config.getSettings().setCompletionDoc(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleProjects",
                () -> toggleSetting(
                        "view.toggleProjects",
                        () -> config.getSettings().isProjectSupport(),
                        v -> config.getSettings().setProjectSupport(v),
                        this::applyProjectSupport)));
        registry.register(Command.of(
                "view.toggleNotes",
                () -> toggleSetting(
                        "view.toggleNotes",
                        () -> config.getSettings().isNotesSupport(),
                        v -> config.getSettings().setNotesSupport(v),
                        notesCoordinator::applySupport)));
        registry.register(Command.of(
                "view.toggleLocalHistory",
                () -> toggleSetting(
                        "view.toggleLocalHistory",
                        () -> config.getSettings().isLocalHistory(),
                        v -> config.getSettings().setLocalHistory(v),
                        historyCoordinator::applySupport)));
        registry.register(Command.of(
                "view.toggleGit",
                () -> toggleSetting(
                        "view.toggleGit",
                        () -> config.getSettings().isGitSupport(),
                        v -> config.getSettings().setGitSupport(v),
                        git::applySupport)));
        registry.register(Command.of(
                "view.toggleMermaid",
                () -> toggleSetting(
                        "view.toggleMermaid",
                        () -> config.getSettings().isMermaidSupport(),
                        v -> config.getSettings().setMermaidSupport(v),
                        mermaid::applySupport)));
        registry.register(Command.of(
                "view.toggleHttpClient",
                () -> toggleSetting(
                        "view.toggleHttpClient",
                        () -> config.getSettings().isHttpClientSupport(),
                        v -> config.getSettings().setHttpClientSupport(v),
                        httpClient::applySupport)));
        registry.register(Command.of(
                "view.toggleDebug",
                () -> toggleSetting(
                        "view.toggleDebug",
                        () -> config.getSettings().isDebugSupport(),
                        v -> config.getSettings().setDebugSupport(v),
                        debugCoordinator::applySupport)));
        registry.register(Command.of(
                "file.setAutoSaveDelay",
                () -> promptIntSetting(
                        "file.setAutoSaveDelay",
                        () -> Math.max(1, (int) Math.round(config.getSettings().getAutoSaveDelayMillis() / 1000.0)),
                        1,
                        3600,
                        v -> config.getSettings().setAutoSaveDelayMillis(v * 1000),
                        this::applyAutoSave)));
        registry.register(Command.of(
                "app.setAuthorName",
                () -> promptStringSetting(
                        "app.setAuthorName",
                        () -> config.getSettings().getAuthorNameRaw(),
                        v -> config.getSettings().setAuthorName(v),
                        null)));
        registry.register(Command.of(
                "history.setMaxPerFile",
                () -> promptIntSetting(
                        "history.setMaxPerFile",
                        () -> config.getSettings().getHistoryMaxPerFile(),
                        1,
                        1000,
                        v -> config.getSettings().setHistoryMaxPerFile(v),
                        historyCoordinator::applySupport)));
        registry.register(Command.of(
                "history.setMaxAgeDays",
                () -> promptIntSetting(
                        "history.setMaxAgeDays",
                        () -> config.getSettings().getHistoryMaxAgeDays(),
                        1,
                        3650,
                        v -> config.getSettings().setHistoryMaxAgeDays(v),
                        historyCoordinator::applySupport)));
        registry.register(Command.of(
                "history.setMaxTotalMb",
                () -> promptIntSetting(
                        "history.setMaxTotalMb",
                        () -> config.getSettings().getHistoryMaxTotalMb(),
                        1,
                        10000,
                        v -> config.getSettings().setHistoryMaxTotalMb(v),
                        historyCoordinator::applySupport)));
        registry.register(Command.of(
                "editor.setLargeFileThreshold",
                () -> promptIntSetting(
                        "editor.setLargeFileThreshold",
                        () -> config.getSettings().getLargeFileThreshold(),
                        0,
                        10_000_000,
                        v -> config.getSettings().setLargeFileThreshold(v),
                        null))); // applies to newly opened files
        registry.register(Command.of("view.toggleLargeFileMode", this::toggleLargeFileMode));
        registry.register(Command.of(
                "view.toggleRipgrep",
                () -> toggleSetting(
                        "view.toggleRipgrep",
                        () -> config.getSettings().isRipgrepSearch(),
                        v -> config.getSettings().setRipgrepSearch(v),
                        searchCoordinator::applyRipgrepSupport)));
        registry.register(Command.of(
                "search.setRipgrepCommand",
                () -> promptStringSetting(
                        "search.setRipgrepCommand",
                        () -> config.getSettings().getRipgrepCommand(),
                        v -> config.getSettings().setRipgrepCommand(v),
                        searchCoordinator::applyRipgrepSupport)));
        registry.register(Command.of(
                "view.toggleSearchGitignore",
                () -> toggleSetting(
                        "view.toggleSearchGitignore",
                        () -> config.getSettings().isSearchRespectGitignore(),
                        v -> config.getSettings().setSearchRespectGitignore(v),
                        searchCoordinator::applyRipgrepSupport)));
        registry.register(Command.of(
                "mermaid.setMmdcCommand",
                () -> promptStringSetting(
                        "mermaid.setMmdcCommand",
                        () -> config.getSettings().getMmdcPath(),
                        v -> config.getSettings().setMmdcPath(v),
                        mermaid::applySupport)));
        registry.register(Command.of(
                "mermaid.setMaidCommand",
                () -> promptStringSetting(
                        "mermaid.setMaidCommand",
                        () -> config.getSettings().getMaidPath(),
                        v -> config.getSettings().setMaidPath(v),
                        mermaid::applySupport)));
        registry.register(Command.of(
                "plugins.toggleRequireSignature",
                () -> toggleSetting(
                        "plugins.toggleRequireSignature",
                        () -> config.getSettings().isPluginRequireSignature(),
                        v -> config.getSettings().setPluginRequireSignature(v),
                        null)));
        registry.register(Command.of(
                "plugins.setRegistryUrl",
                () -> promptStringSetting(
                        "plugins.setRegistryUrl",
                        () -> config.getSettings().getPluginRegistryUrl(),
                        v -> config.getSettings().setPluginRegistryUrl(v),
                        null)));
        registry.register(Command.of("lsp.toggleServer", lspCoordinator::chooseServerToggle));
        registry.register(Command.of("lsp.setServerCommand", lspCoordinator::chooseServerCommand));
        registry.register(Command.of("debug.toggleAdapter", debugCoordinator::chooseAdapterToggle));
        registry.register(Command.of("debug.setAdapterPath", debugCoordinator::chooseAdapterPath));
        registry.register(Command.of(
                "install.javaSupport",
                () -> installCoordinator.installSupport(com.editora.install.InstallCatalog.Lang.JAVA)));
        registry.register(Command.of(
                "install.pythonSupport",
                () -> installCoordinator.installSupport(com.editora.install.InstallCatalog.Lang.PYTHON)));
        registry.register(Command.of(
                "install.jsSupport",
                () -> installCoordinator.installSupport(com.editora.install.InstallCatalog.Lang.JAVASCRIPT)));
        registry.register(Command.of(
                "install.mermaidSupport",
                () -> installCoordinator.installSupport(com.editora.install.InstallCatalog.Lang.MERMAID)));
        registry.register(Command.of("install.typstCli", () -> installCoordinator.installTypstCli()));
        registry.register(Command.of("install.languageServer", this::chooseInstallServer));
        registry.register(Command.of("view.toggleColumnRuler", this::toggleColumnRuler));
        registry.register(Command.of("view.toggleToolStripe", this::toggleToolStripe));
        registry.register(Command.of("view.toggleSimpleMode", this::toggleSimpleMode));
        registry.register(Command.of(
                "view.toggleInstallPrompts",
                () -> toggleSetting(
                        "view.toggleInstallPrompts",
                        () -> config.getSettings().isLspInstallPrompts(),
                        v -> config.getSettings().setLspInstallPrompts(v),
                        () -> maybeOfferInstall(activeBuffer()))));
        registry.register(Command.of("view.togglePlugins", pluginCoordinator::toggleSupport));
        registry.register(Command.of("plugins.browse", pluginCoordinator::browse));
        registry.register(Command.of("plugins.installFromDisk", pluginCoordinator::installFromDisk));
        registry.register(Command.of("config.export", this::exportConfig));
        registry.register(Command.of("editor.setIndentStyle", this::chooseIndentStyle));
        registry.register(Command.of("editor.exportPdf", this::exportCodePdf));
        registry.register(Command.of("preview.exportPdf", this::exportPreviewPdf));
        registry.register(Command.of("preview.exportHtml", this::exportPreviewHtml));
        registry.register(Command.of("preview.exportDocx", this::exportPreviewDocx));
        registry.register(Command.of("preview.exportOdt", this::exportPreviewOdt));
        registry.register(Command.of("editor.print", this::printCode));
        registry.register(Command.of("preview.print", this::printPreview));
        registry.register(Command.of("markwhen.exportJson", this::exportMarkwhenJson));
        registry.register(Command.of("markwhen.toggleView", this::toggleMarkwhenView));
        registry.register(Command.of("structured.toggleView", this::toggleStructuredView));
        registry.register(Command.of(
                "view.toggleStructuredPreview",
                () -> toggleSetting(
                        "view.toggleStructuredPreview",
                        () -> config.getSettings().isStructuredPreview(),
                        v -> config.getSettings().setStructuredPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleSvgPreview",
                () -> toggleSetting(
                        "view.toggleSvgPreview",
                        () -> config.getSettings().isSvgPreview(),
                        v -> config.getSettings().setSvgPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleCrontabPreview",
                () -> toggleSetting(
                        "view.toggleCrontabPreview",
                        () -> config.getSettings().isCrontabPreview(),
                        v -> config.getSettings().setCrontabPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleFstabPreview",
                () -> toggleSetting(
                        "view.toggleFstabPreview",
                        () -> config.getSettings().isFstabPreview(),
                        v -> config.getSettings().setFstabPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleSystemdPreview",
                () -> toggleSetting(
                        "view.toggleSystemdPreview",
                        () -> config.getSettings().isSystemdPreview(),
                        v -> config.getSettings().setSystemdPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleSshConfigPreview",
                () -> toggleSetting(
                        "view.toggleSshConfigPreview",
                        () -> config.getSettings().isSshConfigPreview(),
                        v -> config.getSettings().setSshConfigPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleDockerfilePreview",
                () -> toggleSetting(
                        "view.toggleDockerfilePreview",
                        () -> config.getSettings().isDockerfilePreview(),
                        v -> config.getSettings().setDockerfilePreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleGithubActionsPreview",
                () -> toggleSetting(
                        "view.toggleGithubActionsPreview",
                        () -> config.getSettings().isGithubActionsPreview(),
                        v -> config.getSettings().setGithubActionsPreview(v),
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of("mermaid.export", mermaid::export));
        registry.register(Command.of("diagram.export", diagram::export));
        registry.register(Command.of(
                "view.toggleDiagramSupport",
                () -> toggleSetting(
                        "view.toggleDiagramSupport",
                        () -> config.getSettings().isDiagramSupport(),
                        v -> config.getSettings().setDiagramSupport(v),
                        diagram::applySupport)));
        registry.register(Command.of(
                "diagram.setDotCommand",
                () -> promptStringSetting(
                        "diagram.setDotCommand",
                        () -> config.getSettings().getDotPath(),
                        v -> config.getSettings().setDotPath(v),
                        diagram::applySupport)));
        registry.register(Command.of(
                "diagram.setPlantumlCommand",
                () -> promptStringSetting(
                        "diagram.setPlantumlCommand",
                        () -> config.getSettings().getPlantumlPath(),
                        v -> config.getSettings().setPlantumlPath(v),
                        diagram::applySupport)));
        registry.register(Command.of("typst.export", typst::export));
        registry.register(Command.of(
                "view.toggleTypstSupport",
                () -> toggleSetting(
                        "view.toggleTypstSupport",
                        () -> config.getSettings().isTypstSupport(),
                        v -> config.getSettings().setTypstSupport(v),
                        () -> {
                            typst.applySupport();
                            applyViewSettingsToAllBuffers(config.getSettings());
                        })));
        registry.register(Command.of(
                "typst.setCommand",
                () -> promptStringSetting(
                        "typst.setCommand",
                        () -> config.getSettings().getTypstPath(),
                        v -> config.getSettings().setTypstPath(v),
                        typst::applySupport)));
        registry.register(Command.of("htmlPreview.open", htmlPreview::open));
        registry.register(Command.of("htmlPreview.openIn", htmlPreview::openIn));
        registry.register(Command.of("view.toggleHtmlPreview", htmlPreview::toggle));
        registry.register(Command.of("log.toggleFollow", logViewer::toggleFollowCommand));
        registry.register(Command.of("log.viewAsLog", logViewer::viewAsLog));
        registry.register(Command.of("log.setLevelFilter", logViewer::setLevelFilter));
        registry.register(Command.of("log.setRegexFilter", logViewer::setRegexFilter));
        registry.register(Command.of("log.clearFilter", logViewer::clearFilter));
        registry.register(Command.of("log.nextError", logViewer::jumpToNextError));
        registry.register(Command.of("log.previousError", logViewer::jumpToPreviousError));
        registry.register(Command.of("view.toggleLogViewer", logViewer::toggleViewer));
        registry.register(Command.of(
                "view.toggleCsvGrid",
                () -> toggleSetting(
                        "view.toggleCsvGrid",
                        () -> config.getSettings().isCsvPreview(),
                        config.getSettings()::setCsvPreview,
                        () -> {
                            csvCoordinator.applySupport(); // (de)attach the in-editor grid on every CSV buffer
                            updateBufferToolWindows();
                        })));
        registry.register(Command.of(
                "view.toggleCsvRainbow",
                () -> toggleSetting(
                        "view.toggleCsvRainbow",
                        () -> config.getSettings().isCsvRainbow(),
                        config.getSettings()::setCsvRainbow,
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleAutoRenameTag",
                () -> toggleSetting(
                        "view.toggleAutoRenameTag",
                        () -> config.getSettings().isAutoRenameTag(),
                        config.getSettings()::setAutoRenameTag,
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of(
                "view.toggleAutoCloseTags",
                () -> toggleSetting(
                        "view.toggleAutoCloseTags",
                        () -> config.getSettings().isAutoCloseTags(),
                        config.getSettings()::setAutoCloseTags,
                        () -> applyViewSettingsToAllBuffers(config.getSettings()))));
        registry.register(Command.of("mcp.copyEndpoint", () -> ifMcp(this::copyMcpEndpoint)));
        registry.register(Command.of("view.toggleMcp", this::toggleMcpSupport));
        registry.register(Command.of(
                "view.toggleAiEnabled",
                () -> toggleSetting(
                        "view.toggleAiEnabled",
                        () -> config.getSettings().isAiEnabled(),
                        v -> config.getSettings().setAiEnabled(v),
                        () -> {
                            applyAgentSupport();
                            aiCoordinator.applySupport();
                        })));
        registry.register(Command.of("tool.agent", agentCoordinator::toggleToolWindow));
        registry.register(Command.of("agent.newSession", agentCoordinator::newSession));
        registry.register(Command.of("agent.stop", agentCoordinator::stopTurn));
        registry.register(Command.of("agent.selectModel", agentCoordinator::pickModel));
        registry.register(Command.of("agent.selectMode", agentCoordinator::pickMode));
        registry.register(Command.of("agent.selectClient", agentCoordinator::pickAgentClient));
        registry.register(Command.of("agent.resumeSession", agentCoordinator::resumeSessionPicker));
        registry.register(Command.of(
                "view.toggleAgent",
                () -> toggleSetting(
                        "view.toggleAgent",
                        () -> config.getSettings().isAgentSupport(),
                        v -> config.getSettings().setAgentSupport(v),
                        this::applyAgentSupport)));
        registry.register(Command.of(
                "agent.setCommand",
                () -> promptStringSetting(
                        "agent.setCommand",
                        () -> config.getSettings().getAgentCommand(),
                        v -> config.getSettings().setAgentCommand(v),
                        this::applyAgentSupport)));
        registry.register(Command.of(
                "view.toggleAgentContext",
                () -> toggleSetting(
                        "view.toggleAgentContext",
                        () -> config.getSettings().isAgentIncludeContext(),
                        v -> config.getSettings().setAgentIncludeContext(v),
                        null))); // read fresh on the next sendPrompt call — nothing cached to re-push
        registry.register(Command.of("ai.generateCommitMessage", aiCoordinator::generateCommitMessage));
        registry.register(Command.of("ai.explainSelection", aiCoordinator::explainSelection));
        registry.register(Command.of("ai.rewriteSelection", aiCoordinator::rewriteSelection));
        registry.register(Command.of("ai.cancel", aiCoordinator::cancel));
        registry.register(Command.of(
                "view.toggleAi",
                () -> toggleSetting(
                        "view.toggleAi",
                        () -> config.getSettings().isAiSupport(),
                        v -> config.getSettings().setAiSupport(v),
                        null)));
        registry.register(Command.of(
                "ai.setModel",
                () -> promptStringSetting(
                        "ai.setModel",
                        () -> config.getSettings().getAiModel(),
                        v -> config.getSettings().setAiModel(v),
                        null)));
        registry.register(Command.of(
                "view.toggleAiCompletion",
                () -> toggleSetting(
                        "view.toggleAiCompletion",
                        () -> config.getSettings().isAiInlineCompletion(),
                        v -> config.getSettings().setAiInlineCompletion(v),
                        this::applyAutocomplete)));
        registry.register(Command.of(
                "ai.setCompletionModel",
                () -> promptStringSetting(
                        "ai.setCompletionModel",
                        () -> config.getSettings().getAiCompletionModel(),
                        v -> config.getSettings().setAiCompletionModel(v),
                        null)));
        registry.register(Command.of(
                "ai.setProvider",
                () -> chooseSetting(
                        "ai.setProvider",
                        () -> List.of("anthropic", "openai"),
                        id -> tr("settings.ai.provider." + id),
                        id -> {
                            config.getSettings().setAiProvider(id);
                            requestSave();
                            applyAutocomplete(); // re-gate inline completion (key requirement changed)
                            if (settingsWindow != null) {
                                settingsWindow.syncAll();
                            }
                            setStatus(tr(
                                    "status.settingChanged",
                                    commandTitle("ai.setProvider"),
                                    tr("settings.ai.provider." + id)));
                        })));
        registry.register(Command.of(
                "ai.setEndpoint",
                () -> promptStringSetting(
                        "ai.setEndpoint",
                        () -> config.getSettings().getAiEndpoint(),
                        v -> config.getSettings().setAiEndpoint(v),
                        null)));
        registry.register(Command.of("ai.testConnection", aiCoordinator::testConnection));
        registry.register(Command.of("view.toggleLineHighlight", this::toggleLineHighlight));
        registry.register(Command.of("view.toggleLineNumbers", this::toggleLineNumbers));
        registry.register(Command.of("view.toggleMinimap", this::toggleMinimap));
        registry.register(Command.of("view.toggleWordWrap", this::toggleWordWrap));
        registry.register(Command.of(
                "view.toggleAdminSave",
                () -> toggleSetting(
                        "view.toggleAdminSave",
                        () -> config.getSettings().isAdminSave(),
                        v -> config.getSettings().setAdminSave(v),
                        this::applyAdminSaveSupport)));
        registry.register(Command.of("view.toggleWhitespace", this::toggleWhitespace));
        registry.register(Command.of("view.toggleSpellCheck", this::toggleSpellCheck));
        registry.register(Command.of("view.toggleAutocomplete", this::toggleAutocomplete));
        registry.register(Command.of("view.toggleAutocompleteProse", this::toggleAutocompleteProse));
        registry.register(Command.of("view.toggleAutocompleteSnippets", this::toggleAutocompleteSnippets));
        registry.register(Command.of("view.toggleAutocompleteMermaid", this::toggleAutocompleteMermaid));
        registry.register(Command.of("view.toggleMultiCaret", this::toggleMultiCaret));
        registry.register(Command.of(
                "view.toggleCopyLineWhenNoSelection",
                () -> toggleSetting(
                        "view.toggleCopyLineWhenNoSelection",
                        () -> config.getSettings().isCopyLineWhenNoSelection(),
                        config.getSettings()::setCopyLineWhenNoSelection,
                        null)));
        registry.register(
                Command.of("edit.addCaretNextOccurrence", () -> withMultiCaret(EditorBuffer::addCaretNextOccurrence)));
        registry.register(Command.of("edit.addCaretAbove", () -> withMultiCaret(EditorBuffer::addCaretAbove)));
        registry.register(Command.of("edit.addCaretBelow", () -> withMultiCaret(EditorBuffer::addCaretBelow)));
        registry.register(Command.of("edit.collapseCarets", () -> withMultiCaret(EditorBuffer::collapseCarets)));
        registry.register(Command.of("template.new", () -> newFromTemplate(null)));
        registry.register(Command.of("template.newInFolder", () -> newFromTemplate(defaultNewDir())));
        registry.register(Command.of("template.reload", () -> {
            templates.reload();
            setStatus(tr("status.templatesReloaded"));
        }));
        registry.register(Command.of("template.editUser", this::editUserTemplates));
        registry.register(Command.of("template.manage", () -> settingsWindow.showTemplates(stage)));
        registry.register(Command.of("spell.setLanguage", this::chooseSpellLanguage));
        registry.register(Command.of("spell.manageDictionary", () -> settingsWindow.showSpellCheck(stage)));
        registry.register(Command.of("view.togglePersonalDictionary", this::togglePersonalDictionary));
        registry.register(Command.of("view.toggleTechnicalDictionary", this::toggleTechnicalDictionary));
        registry.register(Command.of("view.toggleToolbar", this::toggleToolbar));
        registry.register(Command.of("view.toggleStatusBar", this::toggleStatusBar));
        registry.register(Command.of("view.toggleTabBar", this::toggleTabBar));
        registry.register(Command.of("view.toggleBreadcrumb", this::toggleBreadcrumb));
        registry.register(Command.of("view.toggleZen", this::toggleZen));
        registry.register(Command.of("view.toggleExpert", this::toggleExpert));
        registry.register(Command.of("view.toggleReadOnly", this::toggleReadOnly));
        registry.register(Command.of("file.toggleAutoSave", this::toggleAutoSave));
        registry.register(Command.of("recent.jump", () -> recentPalette.show(stage)));
        registry.register(Command.of("structure.jump", () -> structurePalette.show(stage)));
        registry.register(Command.of("buffer.jump", () -> openFilesPalette.show(stage)));
        registry.register(Command.of("tool.jump", () -> toolWindowPalette.show(stage)));
        registry.register(Command.of("undoHistory.jump", () -> undoHistoryPalette.show(stage)));
        registry.register(Command.of("bookmarks.toggle", bookmarkCoordinator::toggleAtCaret));
        registry.register(Command.of("bookmarks.editNote", bookmarkCoordinator::editNoteAtCaret));
        registry.register(Command.of("bookmarks.next", () -> bookmarkCoordinator.jump(true)));
        registry.register(Command.of("bookmarks.previous", () -> bookmarkCoordinator.jump(false)));
        registry.register(Command.of("bookmarks.jump", bookmarkCoordinator::openJumpPalette));
        registry.register(Command.of("bookmarks.clearFile", bookmarkCoordinator::clearInFile));
        registry.register(Command.of("notes.add", () -> notesCoordinator.ifEnabled(notesCoordinator::addNoteAtCaret)));
        registry.register(
                Command.of("notes.editNote", () -> notesCoordinator.ifEnabled(notesCoordinator::editNoteAtCaret)));
        registry.register(Command.of(
                "notes.toggleResolved", () -> notesCoordinator.ifEnabled(notesCoordinator::toggleResolvedAtCaret)));
        registry.register(
                Command.of("notes.next", () -> notesCoordinator.ifEnabled(() -> notesCoordinator.jumpNote(true))));
        registry.register(
                Command.of("notes.previous", () -> notesCoordinator.ifEnabled(() -> notesCoordinator.jumpNote(false))));
        registry.register(
                Command.of("notes.jump", () -> notesCoordinator.ifEnabled(notesCoordinator::openJumpPalette)));
        registry.register(Command.of("notes.search", () -> notesCoordinator.ifEnabled(notesCoordinator::searchNotes)));
        registry.register(
                Command.of("notes.delete", () -> notesCoordinator.ifEnabled(notesCoordinator::deleteNoteAtCaret)));
        registry.register(Command.of("notes.export", () -> notesCoordinator.ifEnabled(notesCoordinator::exportNotes)));
        registry.register(Command.of("snippets.insert", this::insertSnippetPicker));
        registry.register(Command.of("snippets.reload", () -> {
            snippets.reload();
            setStatus(tr("status.snippetsReloaded"));
        }));
        registry.register(Command.of("snippets.editUser", this::editUserSnippets));
        registry.register(Command.of("snippets.manage", () -> settingsWindow.showSnippets(stage)));
        registry.register(Command.of("view.splitVertical", this::onSplitVertical));
        registry.register(Command.of("view.splitHorizontal", this::onSplitHorizontal));
        registry.register(Command.of("view.unsplit", this::unsplit));
        registry.register(
                Command.of("view.markdownEditor", () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.EDITOR)));
        registry.register(
                Command.of("view.markdownSplit", () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.SPLIT)));
        registry.register(
                Command.of("view.markdownPreview", () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.PREVIEW)));
        registry.register(Command.of("view.markdownZoomIn", () -> markdownZoom(1)));
        registry.register(Command.of("view.markdownZoomOut", () -> markdownZoom(-1)));
        registry.register(Command.of("view.markdownZoomReset", () -> markdownZoom(0)));
        registry.register(Command.of("view.toggleMarkdownPreviewTheme", this::toggleMarkdownPreviewTheme));
        // Markdown editing (markdown buffers only; no-op with a status elsewhere).
        registry.register(Command.of("markdown.bold", () -> markdownInline("**")));
        registry.register(Command.of("markdown.italic", () -> markdownInline("*")));
        registry.register(Command.of("markdown.strikethrough", () -> markdownInline("~~")));
        registry.register(Command.of("markdown.code", () -> markdownInline("`")));
        registry.register(Command.of("markdown.link", () -> withMarkdown(EditorBuffer::formatLinkFromClipboard)));
        registry.register(Command.of("markdown.bulletList", () -> withMarkdown(EditorBuffer::formatBulletList)));
        registry.register(Command.of("markdown.taskList", () -> withMarkdown(EditorBuffer::formatTaskList)));
        registry.register(Command.of("markdown.insertTable", this::markdownInsertTableViaText));
        registry.register(Command.of("markdown.tableAddRow", () -> withMarkdown(b -> b.tableAddRow())));
        registry.register(Command.of("markdown.tableDeleteRow", () -> withMarkdown(b -> b.tableDeleteRow())));
        registry.register(Command.of("markdown.tableAddColumn", () -> withMarkdown(b -> b.tableAddColumn())));
        registry.register(Command.of("markdown.tableDeleteColumn", () -> withMarkdown(b -> b.tableDeleteColumn())));
        registry.register(Command.of(
                "markdown.tableAlignLeft", () -> withMarkdown(b -> b.tableSetAlignment(MarkdownTable.Align.LEFT))));
        registry.register(Command.of(
                "markdown.tableAlignCenter", () -> withMarkdown(b -> b.tableSetAlignment(MarkdownTable.Align.CENTER))));
        registry.register(Command.of(
                "markdown.tableAlignRight", () -> withMarkdown(b -> b.tableSetAlignment(MarkdownTable.Align.RIGHT))));
        registry.register(Command.of("markdown.headingPromote", () -> withMarkdown(b -> b.formatHeading(-1))));
        registry.register(Command.of("markdown.headingDemote", () -> withMarkdown(b -> b.formatHeading(1))));
        registry.register(Command.of("markdown.openLink", this::markdownOpenLink));
        // Typst markup formatting (mirrors the markdown.* set; Typst uses *bold*, _emph_, `raw`, = headings).
        registry.register(Command.of("typst.bold", () -> withTypst(b -> b.formatInline("*"))));
        registry.register(Command.of("typst.emph", () -> withTypst(b -> b.formatInline("_"))));
        registry.register(Command.of("typst.raw", () -> withTypst(b -> b.formatInline("`"))));
        registry.register(Command.of("typst.link", () -> withTypst(EditorBuffer::formatLinkFromClipboard)));
        registry.register(Command.of("typst.bulletList", () -> withTypst(EditorBuffer::formatBulletList)));
        registry.register(Command.of("typst.headingPromote", () -> withTypst(b -> b.formatHeading(-1))));
        registry.register(Command.of("typst.headingDemote", () -> withTypst(b -> b.formatHeading(1))));
        registry.register(Command.of("typst.insertTable", () -> withTypst(EditorBuffer::insertTypstTableInteractive)));
        registry.register(Command.of("typst.outline", () -> withTypst(EditorBuffer::insertTypstOutline)));
        registry.register(Command.of("typst.insertImage", () -> withTypst(this::insertTypstImageFromChooser)));
        registry.register(Command.of("typst.exportPng", typst::exportPng));
        registry.register(Command.of("typst.exportSvg", typst::exportSvg));
        registry.register(Command.of("markdown.reflowTable", this::markdownReflowTable));
        registry.register(Command.of("markdown.toc", this::markdownToc));
        registry.register(Command.of("markdown.tableFromCsv", this::markdownTableFromCsv));
        registry.register(Command.of("markdown.tableToCsv", this::markdownTableToCsv));
        registry.register(Command.of("markdown.tableExportCsv", () -> markdownTableExport("csv")));
        registry.register(Command.of("markdown.tableExportExcel", () -> markdownTableExport("xlsx")));
        registry.register(Command.of("markdown.tableExportOds", () -> markdownTableExport("ods")));
        registry.register(Command.of("csv.copyAsMarkdownTable", this::csvCopyAsMarkdownTable));
        registry.register(Command.of("csv.align", this::csvAlign));
        registry.register(Command.of("csv.shrink", this::csvShrink));
        registry.register(Command.of("markdown.toggleFormatBar", this::toggleMarkdownFormatBar));
        registry.register(Command.of("view.textZoomIn", () -> textZoom(1)));
        registry.register(Command.of("view.textZoomOut", () -> textZoom(-1)));
        registry.register(Command.of("view.textZoomReset", () -> textZoom(0)));
        registry.register(Command.of("view.foldAll", this::foldAll));
        registry.register(Command.of("view.unfoldAll", this::unfoldAll));
        registry.register(Command.of("view.fold", this::foldAtCaret));
        registry.register(Command.of("view.unfold", this::unfoldAtCaret));
        registry.register(Command.of("view.toggleFold", this::toggleFoldAtCaret));
        registry.register(Command.of("nav.goToLine", this::goToLine));
        registry.register(Command.of("buffer.setLanguage", this::chooseLanguage));
        registry.register(Command.of("buffer.setTabSize", this::chooseTabSize));
        registry.register(Command.of("buffer.convertLineEndings", this::chooseLineEndings));
        registry.register(Command.of("window.other", this::otherWindow));
        // Cross-platform via JavaFX Stage (handles the per-OS window manager specifics on macOS/Linux/Windows).
        registry.register(Command.of("window.maximize", () -> stage.setMaximized(!stage.isMaximized())));
        registry.register(Command.of("window.fullScreen", () -> stage.setFullScreen(!stage.isFullScreen())));
        registry.register(Command.of("file.clearRecent", this::onClearRecent));
        registry.register(Command.of("help.about", this::onAbout));
        registry.register(Command.of("help.checkForUpdates", this::checkForUpdatesNow));
        registry.register(Command.of("update.openDownloadPage", this::openUpdateDownloadPage));
        registry.register(Command.of(
                "view.toggleUpdateCheck",
                () -> toggleSetting(
                        "view.toggleUpdateCheck",
                        () -> config.getSettings().isUpdateCheck(),
                        config.getSettings()::setUpdateCheck,
                        null)));
        registry.register(Command.of("view.welcome", this::showWelcome));
        registry.register(Command.of("view.messageLog", statusBar::showMessageLog));
        registry.register(Command.of("view.debugLog", this::showDebugLog));
        registry.register(Command.of("view.openAsHex", this::openActiveAsHex));
        registry.register(Command.of("view.openAsText", this::openActiveAsText));
        registry.register(Command.of("tool.project", () -> {
            if (projectsEnabled()) {
                toolWindows.toggle(projectToolWindow);
            }
        }));
        registry.register(Command.of("tool.structure", () -> toolWindows.toggle(structureToolWindow)));
        registry.register(Command.of("tool.bookmarks", () -> toolWindows.toggle(bookmarksToolWindow)));
        registry.register(Command.of("tool.undoHistory", () -> toolWindows.toggle(undoHistoryToolWindow)));
        registry.register(
                Command.of("tool.notes", () -> notesCoordinator.ifEnabled(() -> toolWindows.toggle(notesToolWindow))));
        registry.register(Command.of("tool.fileInformation", () -> toolWindows.toggle(fileInfoToolWindow)));
        registry.register(Command.of("tool.remote", () -> {
            remoteCoordinator.refreshPanel();
            toolWindows.toggle(remoteToolWindow);
        }));
        registry.register(Command.of("tool.search", () -> toolWindows.toggle(searchToolWindow)));
        registry.register(Command.of("search.inFiles", searchCoordinator::openToggle));
        registry.register(Command.of("search.inFilesPopup", searchCoordinator::showFindInFilesPopup));
        todoCoordinator.registerCommands(registry); // tool.todo + todo.refresh + todo.addPattern
        csvCoordinator.registerCommands(registry); // csv.exportPdf/print/exportExcel/exportOds
        for (BuildCoordinator c : buildCoordinators) {
            c.registerCommands(registry); // tool.<id> + <id>.showActions/runCustom/stop/rerunLast/refresh
            BuildTool tool = c.tool();
            registry.register(Command.of(
                    tool.toggleCommandId(), // e.g. view.toggleMavenSupport / view.toggleNpmSupport
                    () -> toggleSetting(
                            tool.toggleCommandId(),
                            () -> tool.enabledIn(config.getSettings()),
                            v -> tool.setEnabledIn(config.getSettings(), v),
                            () -> {
                                refreshBuildTools();
                                if (settingsWindow != null) {
                                    settingsWindow.refreshDetectionStatus();
                                }
                            })));
        }
        registry.register(Command.of("tool.buildOutput", () -> toolWindows.toggle(buildOutputToolWindow)));
        registry.register(Command.of(
                "view.toggleTodoHighlight",
                () -> toggleSetting(
                        "view.toggleTodoHighlight",
                        () -> config.getSettings().isTodoHighlight(),
                        v -> config.getSettings().setTodoHighlight(v),
                        todoCoordinator::applyHighlight)));
        registry.register(Command.of("todo.setPartColor", this::chooseTodoPartColor));
        registry.register(Command.of("tool.markdownLint", this::toggleMarkdownLintWindow));
        registry.register(Command.of("markdownLint.refresh", () -> {
            if (!toolWindows.isOpen(markdownLintToolWindow)) {
                toolWindows.toggle(markdownLintToolWindow);
            }
            runMarkdownLintScan();
        }));
        registry.register(Command.of(
                "view.toggleMarkdownLint",
                () -> toggleSetting(
                        "view.toggleMarkdownLint",
                        () -> config.getSettings().isMarkdownLint(),
                        v -> config.getSettings().setMarkdownLint(v),
                        this::applyMarkdownLint)));
        registry.register(Command.of("markdownLint.fix", this::fixMarkdownLint));
        registry.register(Command.of("markdownLint.toggleRule", this::chooseMarkdownLintRule));
        registry.register(Command.of(
                "view.toggleMath",
                () -> toggleSetting(
                        "view.toggleMath",
                        () -> config.getSettings().isMathSupport(),
                        v -> config.getSettings().setMathSupport(v),
                        this::applyMathSupport)));
        registry.register(Command.of("nav.aceJump", this::startAceJump));
        registry.register(Command.of("nav.aceJumpLine", this::startAceJumpLine));
        // Run a Java 25 compact source file (also surfaced as the toolbar Run button when one is active).
        registry.register(Command.of("file.run", runCoordinator::runActiveFile));
        registry.register(Command.of("file.runWithArgs", runCoordinator::runActiveFileWithArgs));
        registry.register(Command.of("run.rerun", runCoordinator::rerunLast));
        registry.register(Command.of("run.stop", runCoordinator::stopRun));
        registry.register(Command.of("run.clear", runCoordinator::clearConsole));
        registry.register(Command.of("tool.run", () -> toolWindows.toggle(runToolWindow)));
        registry.register(Command.of("tool.externalTools", () -> toolWindows.toggle(externalToolToolWindow)));
        // HTTP Client (.http via ijhttp). Gated by the "Enable HTTP Client" setting (default off).
        registry.register(Command.of("http.runRequest", httpClient::runRequestAtCaret));
        registry.register(Command.of("http.runFile", httpClient::runFile));
        registry.register(Command.of("http.selectEnvironment", httpClient::selectEnvironment));
        registry.register(Command.of("http.importCurl", httpClient::importCurl));
        registry.register(Command.of("http.copyAsCurl", httpClient::copyActiveAsCurl));
        registry.register(Command.of("http.openResponseInTab", httpClient::openActiveResponseInTab));
        registry.register(Command.of("tool.http", httpClient::toggleToolWindow));
        // Debugging (DAP). Gated by the "Enable Java debugging" setting (default off).
        registry.register(Command.of("debug.start", () -> debugCoordinator.ifDebug(debugCoordinator::debugStart)));
        registry.register(Command.of("debug.stop", () -> debugCoordinator.ifDebug(dapManager::stop)));
        registry.register(Command.of("debug.restart", () -> debugCoordinator.ifDebug(dapManager::restart)));
        registry.register(Command.of("debug.attach", () -> debugCoordinator.ifDebug(debugCoordinator::debugAttach)));
        registry.register(Command.of("debug.continue", () -> debugCoordinator.ifDebug(dapManager::resume)));
        registry.register(Command.of("debug.pause", () -> debugCoordinator.ifDebug(dapManager::pause)));
        registry.register(
                Command.of("debug.runToCursor", () -> debugCoordinator.ifDebug(debugCoordinator::debugRunToCursor)));
        registry.register(
                Command.of("debug.jumpToLine", () -> debugCoordinator.ifDebug(debugCoordinator::debugJumpToLine)));
        // Debugger data inspection (parity with the Debug panel's own controls).
        registry.register(
                Command.of("debug.evaluate", () -> debugCoordinator.ifDebug(debugCoordinator::focusEvaluate)));
        registry.register(Command.of("debug.addWatch", () -> debugCoordinator.ifDebug(debugCoordinator::addWatch)));
        registry.register(
                Command.of("debug.setValue", () -> debugCoordinator.ifDebug(debugCoordinator::setSelectedValue)));
        registry.register(Command.of("debug.stepOver", () -> debugCoordinator.ifDebug(dapManager::stepOver)));
        registry.register(Command.of("debug.stepInto", () -> debugCoordinator.ifDebug(dapManager::stepInto)));
        registry.register(Command.of("debug.stepOut", () -> debugCoordinator.ifDebug(dapManager::stepOut)));
        registry.register(Command.of(
                "debug.toggleBreakpoint", () -> debugCoordinator.ifDebug(debugCoordinator::toggleBreakpointAtCaret)));
        registry.register(Command.of(
                "debug.editBreakpoint", () -> debugCoordinator.ifDebug(debugCoordinator::editBreakpointAtCaret)));
        registry.register(Command.of(
                "debug.toggleExceptionBreakpoints",
                () -> debugCoordinator.ifDebug(debugCoordinator::toggleExceptionBreakpoints)));
        registry.register(
                Command.of("tool.debug", () -> debugCoordinator.ifDebug(() -> toolWindows.toggle(debugToolWindow))));
        // LSP. Gated by the "Enable LSP" setting (default off); commands no-op with a status when off.
        registry.register(Command.of("tool.problems", () -> ifLsp(() -> toolWindows.toggle(problemsToolWindow))));
        registry.register(Command.of("tool.references", () -> ifLsp(() -> toolWindows.toggle(referencesToolWindow))));
        registry.register(Command.of("lsp.gotoDefinition", () -> ifLsp(lspCoordinator::gotoDefinition)));
        registry.register(Command.of("lsp.findReferences", () -> ifLsp(lspCoordinator::findReferences)));
        registry.register(Command.of("lsp.gotoSymbol", () -> ifLsp(lspCoordinator::gotoSymbolInWorkspace)));
        registry.register(Command.of("lsp.hover", () -> ifLsp(lspCoordinator::showHover)));
        registry.register(Command.of("lsp.restartServers", () -> ifLsp(lspCoordinator::restartServers)));
        registry.register(Command.of("lsp.formatDocument", () -> ifLsp(lspCoordinator::formatDocument)));
        registry.register(Command.of("view.toggleLsp", this::toggleLsp));
        registry.register(Command.of(
                "view.toggleSemanticHighlight",
                () -> toggleSetting(
                        "view.toggleSemanticHighlight",
                        () -> config.getSettings().isSemanticHighlight(),
                        config.getSettings()::setSemanticHighlight,
                        lspCoordinator::applySemanticHighlight)));
        registry.register(Command.of("tool.commit", () -> git.ifEnabled(() -> toolWindows.toggle(commitToolWindow))));
        // Git (native CLI). Gated by the "Enable Git" setting (default off); also no-op when Git is
        // absent / not in a repo. The ifGit wrapper disables the commands + keybindings when Git is off.
        registry.register(Command.of("remote.connect", remoteCoordinator::connect));
        registry.register(Command.of("remote.openFile", remoteCoordinator::openFile));
        registry.register(Command.of("remote.manageConnections", remoteCoordinator::manageConnections));
        registry.register(Command.of("remote.settings", () -> settingsWindow.showRemote(stage)));
        registry.register(Command.of("remote.disconnect", remoteCoordinator::disconnect));
        registry.register(Command.of("git.clone", () -> git.ifEnabled(git::cloneRepo)));
        registry.register(Command.of("git.commit", () -> git.ifEnabled(git::gitCommitFocus)));
        registry.register(Command.of("git.stageFile", () -> git.ifEnabled(git::gitStageActiveFile)));
        registry.register(Command.of("git.unstageFile", () -> git.ifEnabled(git::gitUnstageActiveFile)));
        registry.register(Command.of("git.discardFile", () -> git.ifEnabled(git::gitDiscardActiveFile)));
        registry.register(Command.of("git.switchBranch", () -> git.ifEnabled(this::chooseBranch)));
        registry.register(Command.of("git.newBranch", () -> git.ifEnabled(git::newBranch)));
        registry.register(Command.of("git.fetch", () -> git.ifEnabled(() -> git.gitSync("Fetch", "fetch", "--all"))));
        registry.register(Command.of("git.pull", () -> git.ifEnabled(() -> git.gitSync("Pull", "pull", "--ff-only"))));
        registry.register(Command.of("git.push", () -> git.ifEnabled(git::gitPush)));
        // Git Log: act on the commit selected in the Git Log tool window (parity with its right-click menu).
        registry.register(Command.of("git.log.checkout", () -> withSelectedCommit(gitLogOps::checkout)));
        registry.register(Command.of("git.log.newBranch", () -> withSelectedCommit(gitLogOps::newBranch)));
        registry.register(Command.of("git.log.revert", () -> withSelectedCommit(gitLogOps::revert)));
        registry.register(Command.of("git.log.cherryPick", () -> withSelectedCommit(gitLogOps::cherryPick)));
        registry.register(Command.of("git.log.reset", () -> withSelectedCommit(this::promptGitReset)));
        registry.register(Command.of("git.log.copyHash", () -> withSelectedCommit(gitLogOps::copyHash)));
        registry.register(Command.of(
                "git.refresh",
                () -> git.ifEnabled(() -> {
                    git.invalidateCaches();
                    git.afterMutation();
                })));
        // History / Log, blame, and stash (Core-trio parity with IntelliJ/VSCode).
        registry.register(Command.of("tool.gitLog", () -> git.ifEnabled(this::showGitLog)));
        registry.register(Command.of("tool.fileHistory", historyCoordinator::showActive));
        registry.register(Command.of("history.putLabel", historyCoordinator::putLabel));
        registry.register(Command.of("history.recentChanges", historyCoordinator::showRecentChanges));
        registry.register(Command.of("git.fileHistory", () -> git.ifEnabled(this::showFileHistory)));
        registry.register(Command.of("git.toggleBlame", git::toggleBlame));
        registry.register(Command.of("git.blameShowCommit", () -> git.ifEnabled(git::blameShowCommit)));
        registry.register(Command.of("git.stash", () -> git.ifEnabled(git::gitStash)));
        registry.register(Command.of("git.stashPop", () -> git.ifEnabled(git::gitStashPop)));
        registry.register(Command.of("git.unstash", () -> git.ifEnabled(git::gitUnstash)));
        registry.register(Command.of("git.stashDrop", () -> git.ifEnabled(git::gitStashDrop)));
        // Diff viewer + merge. The git-backed diffs are ifGit-gated; "Compare With…" and "Resolve
        // Conflicts" work on any file (no repo needed), so they are not gated.
        registry.register(Command.of("diff.vsHead", () -> git.ifEnabled(diffCoordinator::diffActiveVsHead)));
        // Diff viewer toolbar actions (act on the active diff tab).
        registry.register(
                Command.of("diff.toggleView", () -> diffCoordinator.withActiveDiff(DiffViewerPane::toggleViewMode)));
        registry.register(
                Command.of("diff.applyAll", () -> diffCoordinator.withActiveDiff(DiffViewerPane::applyAllChanges)));
        registry.register(
                Command.of("diff.nextChange", () -> diffCoordinator.withActiveDiff(DiffViewerPane::goNextChange)));
        registry.register(Command.of(
                "diff.previousChange", () -> diffCoordinator.withActiveDiff(DiffViewerPane::goPreviousChange)));
        registry.register(Command.of("diff.compareWith", diffCoordinator::compareActiveWithFile));
        registry.register(Command.of("diff.openPatchFile", () -> diffCoordinator.openPatchFile(activeBuffer())));
        registry.register(Command.of("diff.vsCommit", () -> git.ifEnabled(diffCoordinator::diffActiveVsCommit)));
        registry.register(Command.of("merge.resolve", diffCoordinator::resolveConflicts));
        registry.register(Command.of("switcher.show", () -> switcher.show(stage, false)));
        registry.register(Command.of("switcher.showReverse", () -> switcher.show(stage, true)));
        registry.register(Command.of("find.show", this::findShowOrNext));
        registry.register(Command.of("find.showBackward", this::findShowOrPrevious));
        registry.register(Command.of("find.replace", this::showReplace));
        registry.register(Command.of("find.next", this::findNextMatch));
        registry.register(Command.of("find.previous", this::findPreviousMatch));
        registry.register(Command.of("find.replaceCurrent", this::findReplaceCurrentMatch));
        registry.register(Command.of("find.replaceAll", this::findReplaceAllMatches));
        registry.register(Command.of("edit.cut", this::onCut));
        registry.register(Command.of("edit.copy", this::onCopy));
        registry.register(Command.of("edit.paste", this::onPaste));
        registry.register(Command.of("edit.undo", this::onUndo));
        registry.register(Command.of("edit.redo", this::onRedo));
        registry.register(Command.of("edit.cancel", this::cancel));
        registry.register(Command.of("edit.completion", this::triggerCompletion));
        registry.register(Command.of("edit.completionDoc", this::toggleCompletionDoc));
        registry.register(Command.of("edit.toggleComment", this::toggleComment));
        registry.register(
                Command.of("edit.transposeChars", () -> transpose(com.editora.editops.Transposer::transposeChars)));
        registry.register(
                Command.of("edit.transposeWords", () -> transpose(com.editora.editops.Transposer::transposeWords)));
        registry.register(
                Command.of("edit.transposeLines", () -> transpose(com.editora.editops.Transposer::transposeLines)));
        registry.register(Command.of("edit.selectAll", this::selectAll));
        registry.register(Command.of("edit.duplicateLine", () -> lineOp(com.editora.editops.LineOps::duplicateLine)));
        registry.register(Command.of("edit.moveLineUp", () -> lineOp(com.editora.editops.LineOps::moveLineUp)));
        registry.register(Command.of("edit.moveLineDown", () -> lineOp(com.editora.editops.LineOps::moveLineDown)));
        // Emacs fill commands: re-wrap paragraphs to the fill column (M-q / fill-region / set-fill-column).
        registry.register(Command.of("edit.fillParagraph", this::fillParagraph));
        registry.register(Command.of("edit.fillRegion", this::fillRegion));
        registry.register(Command.of("edit.setFillColumn", this::setFillColumn));
        // String manipulation (the String-Manipulation-plugin family): case-style conversions on the
        // selection/token at the caret + whole-line sorts/filters, all also reachable via one picker.
        registry.register(Command.of("edit.stringOps", this::stringOpsPicker));
        registry.register(Command.of("edit.case.cycle", () -> caseOp(com.editora.editops.StringCase::cycle)));
        registry.register(Command.of(
                "edit.case.camel",
                () -> caseOp(s -> com.editora.editops.StringCase.to(com.editora.editops.StringCase.Style.CAMEL, s))));
        registry.register(Command.of(
                "edit.case.pascal",
                () -> caseOp(s -> com.editora.editops.StringCase.to(com.editora.editops.StringCase.Style.PASCAL, s))));
        registry.register(Command.of(
                "edit.case.snake",
                () -> caseOp(s -> com.editora.editops.StringCase.to(com.editora.editops.StringCase.Style.SNAKE, s))));
        registry.register(Command.of(
                "edit.case.screamingSnake",
                () -> caseOp(s ->
                        com.editora.editops.StringCase.to(com.editora.editops.StringCase.Style.SCREAMING_SNAKE, s))));
        registry.register(Command.of(
                "edit.case.kebab",
                () -> caseOp(s -> com.editora.editops.StringCase.to(com.editora.editops.StringCase.Style.KEBAB, s))));
        registry.register(Command.of(
                "edit.case.dot",
                () -> caseOp(s -> com.editora.editops.StringCase.to(com.editora.editops.StringCase.Style.DOT, s))));
        registry.register(Command.of("edit.case.swap", () -> caseOp(com.editora.editops.StringCase::swapCase)));
        registry.register(Command.of(
                "edit.sortLinesAsc", () -> lineTransform(com.editora.editops.LineTransforms::sortAscending)));
        registry.register(Command.of(
                "edit.sortLinesDesc", () -> lineTransform(com.editora.editops.LineTransforms::sortDescending)));
        registry.register(Command.of(
                "edit.sortLinesByLength", () -> lineTransform(com.editora.editops.LineTransforms::sortByLength)));
        registry.register(
                Command.of("edit.reverseLines", () -> lineTransform(com.editora.editops.LineTransforms::reverse)));
        registry.register(Command.of(
                "edit.shuffleLines",
                () -> lineTransform(t -> com.editora.editops.LineTransforms.shuffle(t, new java.util.Random()))));
        registry.register(Command.of(
                "edit.removeDuplicateLines",
                () -> lineTransform(com.editora.editops.LineTransforms::removeDuplicates)));
        registry.register(Command.of(
                "edit.removeEmptyLines", () -> lineTransform(com.editora.editops.LineTransforms::removeEmpty)));
        registry.register(Command.of(
                "edit.trimTrailingWhitespace", () -> lineTransform(com.editora.editops.LineTransforms::trimTrailing)));
        // C-a: smart line start — first press to the beginning of the line's text (first non-whitespace),
        // a second press toggles to the true line start (column 0).
        registry.register(Command.of(
                "nav.lineStart",
                () -> moveAndFollow(
                        a -> a.moveTo(TextNav.smartLineStart(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.lineEnd", () -> moveAndFollow(a -> a.lineEnd(selPolicy()))));
        registry.register(Command.of("nav.docStart", () -> moveAndFollow(a -> a.start(selPolicy()))));
        registry.register(Command.of("nav.docEnd", () -> moveAndFollow(a -> a.end(selPolicy()))));
        registry.register(Command.of(
                "nav.charForward",
                () -> moveAndFollow(a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1), selPolicy()))));
        registry.register(Command.of(
                "nav.charBackward",
                () -> moveAndFollow(a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1), selPolicy()))));
        registry.register(Command.of("nav.lineDown", () -> moveLine(1)));
        registry.register(Command.of("nav.lineUp", () -> moveLine(-1)));
        registry.register(Command.of(
                "nav.wordForward",
                () -> moveAndFollow(a -> a.moveTo(nextWordBoundary(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of(
                "nav.wordBackward",
                () -> moveAndFollow(a -> a.moveTo(prevWordBoundary(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.pageDown", () -> {
            if (!pageActivePreview(true)) {
                moveAndFollow(a -> a.nextPage(selPolicy()));
            }
        }));
        registry.register(Command.of("nav.pageUp", () -> {
            if (!pageActivePreview(false)) {
                moveAndFollow(a -> a.prevPage(selPolicy()));
            }
        }));
        registry.register(Command.of(
                "nav.backToIndentation",
                () -> moveAndFollow(
                        a -> a.moveTo(TextNav.backToIndentation(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of(
                "nav.paragraphForward",
                () -> moveAndFollow(
                        a -> a.moveTo(TextNav.forwardParagraph(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of(
                "nav.paragraphBackward",
                () -> moveAndFollow(
                        a -> a.moveTo(TextNav.backwardParagraph(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of(
                "nav.sentenceForward",
                () -> moveAndFollow(
                        a -> a.moveTo(TextNav.forwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of(
                "nav.sentenceBackward",
                () -> moveAndFollow(
                        a -> a.moveTo(TextNav.backwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.recenter", this::recenterCaret));
        registry.register(Command.of("edit.setMark", this::setMark));
        registry.register(Command.of("edit.exchangePointAndMark", this::exchangePointAndMark));
        registry.register(Command.of("edit.deleteChar", () -> withArea(CodeArea::deleteNextChar)));
        registry.register(Command.of(
                "edit.killWord",
                () -> withArea(a -> {
                    int caret = a.getCaretPosition();
                    a.deleteText(caret, nextWordBoundary(a.getText(), caret));
                })));
        registry.register(Command.of("edit.killLine", () -> withArea(this::killLine)));
        // Additional Emacs editing/movement commands (kill-ring features remain deferred).
        registry.register(
                Command.of("edit.backwardKillWord", () -> emacsEdit(com.editora.editops.EmacsEdits::backwardKillWord)));
        registry.register(Command.of("edit.upcaseWord", () -> emacsEdit(com.editora.editops.EmacsEdits::upcaseWord)));
        registry.register(
                Command.of("edit.downcaseWord", () -> emacsEdit(com.editora.editops.EmacsEdits::downcaseWord)));
        registry.register(
                Command.of("edit.capitalizeWord", () -> emacsEdit(com.editora.editops.EmacsEdits::capitalizeWord)));
        registry.register(Command.of("edit.upcaseRegion", () -> emacsCaseRegion(true)));
        registry.register(Command.of("edit.downcaseRegion", () -> emacsCaseRegion(false)));
        registry.register(Command.of(
                "edit.deleteIndentation", () -> emacsEdit(com.editora.editops.EmacsEdits::deleteIndentation)));
        registry.register(Command.of(
                "edit.deleteHorizontalSpace", () -> emacsEdit(com.editora.editops.EmacsEdits::deleteHorizontalSpace)));
        registry.register(
                Command.of("edit.justOneSpace", () -> emacsEdit(com.editora.editops.EmacsEdits::justOneSpace)));
        registry.register(
                Command.of("edit.deleteBlankLines", () -> emacsEdit(com.editora.editops.EmacsEdits::deleteBlankLines)));
        registry.register(Command.of("edit.openLine", () -> emacsEdit(com.editora.editops.EmacsEdits::openLine)));
        registry.register(
                Command.of("edit.killWholeLine", () -> emacsEdit(com.editora.editops.EmacsEdits::killWholeLine)));
        registry.register(Command.of("edit.zapToChar", this::zapToChar));
        registry.register(Command.of("edit.killSexp", this::killSexp));
        registry.register(Command.of("edit.markSexp", this::markSexp));
        registry.register(Command.of("edit.markParagraph", this::markParagraph));
        registry.register(Command.of("nav.forwardSexp", () -> sexpMove(com.editora.editops.SexpNav::forward)));
        registry.register(Command.of("nav.backwardSexp", () -> sexpMove(com.editora.editops.SexpNav::backward)));
        registry.register(Command.of("nav.back", this::navBack));
        registry.register(Command.of("nav.forward", this::navForward));
        registry.register(
                Command.of("nav.beginningOfDefun", () -> sexpMove(com.editora.editops.SexpNav::beginningOfDefun)));
        registry.register(Command.of("nav.endOfDefun", () -> sexpMove(com.editora.editops.SexpNav::endOfDefun)));
        registry.register(Command.of("nav.moveToWindowLine", this::moveToWindowLine));
    }

    private void killLine(CodeArea a) {
        int caret = a.getCaretPosition();
        int para = a.getCurrentParagraph();
        int eol = a.getAbsolutePosition(para, a.getParagraphLength(para));
        if (caret < eol) {
            a.deleteText(caret, eol);
        } else if (para + 1 < a.getParagraphs().size()) {
            a.deleteText(caret, caret + 1);
        }
    }

    /** Position of the next word boundary at or after {@code from}: skip non-word chars, then word chars. */
    static int nextWordBoundary(String text, int from) {
        int i = from;
        while (i < text.length() && !Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Position of the previous word boundary at or before {@code from}. */
    static int prevWordBoundary(String text, int from) {
        int i = from;
        while (i > 0 && !Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }
}
