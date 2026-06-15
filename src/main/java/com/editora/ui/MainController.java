package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
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
    private CommandPalette palette;
    private FindReplaceBar findBar;
    private StatusBar statusBar;
    private FileBreadcrumb breadcrumb;
    private SettingsWindow settingsWindow;
    private final DebugLogWindow debugLogWindow = new DebugLogWindow();
    /** Shared in-scene overlay host for the command palette + pickers (replaces focus-stealing Popups). */
    private final OverlayHost overlayHost = new OverlayHost();

    private QuickOpen<Path> recentPalette;
    private QuickOpen<StructurePanel.Outline> structurePalette;
    private QuickOpen<Tab> openFilesPalette;
    private QuickOpen<ToolWindow> toolWindowPalette;
    private QuickOpen<BookmarkEntry> bookmarkPalette;
    private QuickOpen<com.editora.snippet.Snippet> snippetPalette;
    private QuickOpen<com.editora.web.Browsers.Browser> htmlBrowserPalette; // HTML preview: pick a browser
    private com.editora.snippet.SnippetManager snippets;
    private com.editora.template.TemplateRegistry templates;
    /** Shared across windows (owned by WindowManager); plugin classes load once, instances are per-window. */
    private com.editora.plugin.PluginManager pluginManager;
    /** Java plugin instances started in this window (for {@code stop()} on window close). */
    private final java.util.List<com.editora.plugin.Plugin> startedPlugins = new java.util.ArrayList<>();
    /** Editor right-click items contributed by plugins in this window (label + action over the editor). */
    private final java.util.List<EditorMenuContribution> pluginMenuItems = new java.util.ArrayList<>();
    /** Fetches the remote plugin-registry index (created in init when a PluginManager is present). */
    private com.editora.plugin.PluginRegistry pluginRegistry;
    /** Downloads/verifies/unpacks plugin zips (created in init when a PluginManager is present). */
    private com.editora.plugin.PluginInstaller pluginInstaller;
    /** The "Browse Plugins" picker (in-scene overlay); items come from {@link #browseEntries}. */
    private QuickOpen<com.editora.plugin.RegistryEntry> browsePalette;
    /** The last-fetched registry entries, shown by {@link #browsePalette}. */
    private java.util.List<com.editora.plugin.RegistryEntry> browseEntries = java.util.List.of();
    /** Whether the last-fetched registry index verified against the bundled signing key. */
    private boolean browseSigned;

    /** A plugin-contributed editor menu item: a label + an action over the {@link com.editora.plugin.ActiveEditor}. */
    private record EditorMenuContribution(
            String label, java.util.function.Consumer<com.editora.plugin.ActiveEditor> action) {}

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
    /** Tracks the last-applied Git-support state to detect off→on transitions (repopulate the Git UI). */
    private boolean gitSupportApplied;
    /** Tracks the last-applied Notes-support state to detect off→on transitions (reload open buffers' notes). */
    private boolean notesSupportApplied;

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
    private StructurePanel structurePanel;
    private BookmarksPanel bookmarksPanel;
    private NotesPanel notesPanel;
    private SearchPanel searchPanel;
    private ToolWindow searchToolWindow;
    private final com.editora.search.SearchService searchService = new com.editora.search.SearchService();
    private QuickOpen<NoteEntry> notesPalette;
    private QuickOpen<NoteEntry> notesSearchPalette;
    /** Session-only Simple-UI override from the {@code --simple} CLI flag; OR'd with the saved setting. */
    private boolean cliSimpleOverride;
    // --- Remote files (SFTP via MINA SSHD; off-thread connect/auth) ---
    private com.editora.vfs.RemoteFileSystems remoteFs; // lazily created on first remote use
    private String activeRemoteAuthority; // the connection backing the mounted remote root
    // --- Git (native-CLI integration; off-thread via GitService) ---
    private final com.editora.git.GitService gitService = new com.editora.git.GitService();
    private final com.editora.mermaid.MermaidService mermaidService = new com.editora.mermaid.MermaidService();
    // HTML Live Preview: a loopback HttpServer + detected-browser launch (off when disabled, default).
    private final com.editora.web.HtmlPreviewService htmlPreviewService =
            new com.editora.web.HtmlPreviewService(this::openExternalUrl);
    private java.util.List<com.editora.web.Browsers.Browser> htmlBrowsers = java.util.List.of();
    // MCP server: a single app-wide loopback HTTP endpoint exposing live editor state + the command
    // registry to an LLM agent. Static so only the first window with the feature on starts it (the
    // setting is shared); that window's controller is the bridge. (Multi-window caveat: if the owner
    // window closes, the server stops until a settings re-apply re-arms it from another window.)
    private static com.editora.mcp.McpServer mcpServer;
    private static MainController mcpOwner;
    private final com.editora.pdf.PdfExportService pdfService = new com.editora.pdf.PdfExportService();
    private final com.editora.print.PrintService printService = new com.editora.print.PrintService();
    private final com.editora.diff.DiffService diffService = new com.editora.diff.DiffService();
    private boolean mermaidSupportApplied;
    private com.editora.mermaid.MermaidService.Availability mermaidAvail =
            new com.editora.mermaid.MermaidService.Availability(false, false);
    /** HTTP Client: the ijhttp façade, the response tool window, and whether ijhttp was detected. */
    private final com.editora.http.HttpClientService httpService = new com.editora.http.HttpClientService();

    private HttpClientPanel httpPanel;
    private ToolWindow httpToolWindow;
    /** LSP: manager (one server per workspace root), the Problems window, and the latest diagnostics. */
    private final com.editora.lsp.LspManager lspManager =
            new com.editora.lsp.LspManager(this::onLspDiagnostics, this::onLspServerStatus);
    /** serverId → whether that server's command was found on this machine (per-server availability). */
    private final java.util.Map<String, Boolean> lspServerAvailable = new java.util.HashMap<>();
    /** Known LSP server ids (probe/shutdown loops iterate these). */
    private static final String[] LSP_SERVER_IDS = {
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
        "csharp"
    };

    private ProblemsPanel problemsPanel;
    private ToolWindow problemsToolWindow;
    /** Run: streams a Java 25 compact source file's output into the Run tool window. */
    private final com.editora.run.RunService runService = new com.editora.run.RunService();

    private RunPanel runPanel;
    private ToolWindow runToolWindow;
    /** Debug (DAP): drives Java debugging layered on the jdtls LSP session. */
    private final com.editora.dap.DapManager dapManager = new com.editora.dap.DapManager(lspManager);

    private DebugPanel debugPanel;
    private ToolWindow debugToolWindow;
    /** The buffer currently showing the debugger's execution-line highlight, or null. */
    private EditorBuffer execHighlightBuffer;
    /** Exception-breakpoint filters currently active (e.g. {@code uncaught}); toggled by the user. */
    private final java.util.Set<String> exceptionFilters = new java.util.LinkedHashSet<>();
    /** The java-debug bundle jars last pushed to the LSP layer — restart jdtls only when this changes. */
    private List<String> appliedDebugBundles = List.of();

    private final java.util.Map<Path, java.util.List<com.editora.editor.LspDiagnostic>> lspProblems =
            new java.util.LinkedHashMap<>();
    /** The currently-showing LSP hover popup (dismissable), or null. */
    private javafx.stage.Popup lspHoverPopup;

    private GitPanel gitPanel;
    private ToolWindow commitToolWindow;
    private GitLogPanel gitLogPanel;
    private ToolWindow gitLogToolWindow;
    /** The path the Git Log is currently filtered to (file history), or null for the whole repo. */
    private Path gitLogFilter;
    /** Local File History: snapshots local files on save/auto-save/external reload (off-thread). */
    private com.editora.history.HistoryService historyService;

    private FileHistoryPanel fileHistoryPanel;
    private ToolWindow fileHistoryToolWindow;
    /** IntelliJ-style branch dropdown (actions + Local/Remote branches), anchored to the status bar. */
    private final BranchPopup branchPopup = new BranchPopup();
    /** Repo root for the active file, set when {@link #applyGitState} runs (FX thread); null = no repo. */
    private Path currentRepoRoot;
    /** Current branch name for the active repo (FX thread), used to mark it in the branch popup. */
    private String currentBranchName = "";
    /** Current branch's upstream (e.g. {@code origin/main}), or empty when none — drives push. */
    private String currentUpstream = "";

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
    /** Floating "show toolbar" button overlaid top-left; shown only when the toolbar is hidden (not in Zen). */
    private Button toolbarRestoreButton;
    /** Emacs mark: when set (C-SPC), caret movement extends the selection from the mark. */
    private boolean markActive;
    /** Re-entrancy guard so the external-change prompt (which steals focus) doesn't re-trigger itself. */
    private boolean checkingExternalChanges;

    private RecentFiles recentFiles;
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
                refreshGit(); // another tool may have changed the repo while we were away
                refreshPasteState(); // clipboard may have changed in another app while we were away
                if (projectPanel != null) {
                    projectPanel.refreshTree(); // pick up files/folders added or removed outside Editora
                }
                refreshOpenDiffs(); // a compared file may have changed on disk while we were away
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        this.snippets = new com.editora.snippet.SnippetManager(config);
        this.templates = new com.editora.template.TemplateRegistry(config);
        this.completion = new com.editora.completion.CompletionEngine(snippets, config::getUserDictionary);
        // Project commands (incl. the Project tool window) are hidden from the palette unless project
        // support is enabled.
        // Project + Git commands are hidden from the palette unless their feature is enabled.
        this.palette = new CommandPalette(
                registry,
                keymap,
                c -> (config.getSettings().isProjectSupport()
                                || (!c.id().startsWith("project.") && !c.id().equals("tool.project")))
                        && (gitEnabled()
                                || (!c.id().startsWith("git.")
                                        && !c.id().equals("tool.commit")
                                        && !c.id().equals("tool.gitLog")))
                        && (config.getSettings().isNotesSupport()
                                || (!c.id().startsWith("notes.") && !c.id().equals("tool.notes")))
                        && (config.getSettings().isMermaidSupport() || !c.id().startsWith("mermaid."))
                        && (lspEnabled() || (!c.id().startsWith("lsp.") && !c.id().equals("tool.problems")))
                        && (httpEnabled() || (!c.id().startsWith("http.") && !c.id().equals("tool.http")))
                        && (htmlPreviewEnabled() || !c.id().startsWith("htmlPreview."))
                        && (localHistoryEnabled() || !c.id().equals("tool.fileHistory"))
                        && (mcpEnabled() || !c.id().startsWith("mcp."))
                        && (pluginsEnabled() || !c.id().startsWith("plugins.")));
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
                gitService,
                mermaidService,
                lspManager,
                dapManager,
                this::onSettingsApplied,
                this::setZenMode,
                this::openPath,
                this::exportConfig,
                this::showDebugLog);
        this.settingsWindow.setPluginManager(pluginManager); // shared; lists discovered plugins on the Plugins page
        this.settingsWindow.setPluginActions(this::browsePlugins, this::installPluginFromDisk, this::uninstallPlugin);
        this.settingsWindow.setMcpConfirm(this::confirmEnableMcp); // security notice before enabling MCP
        this.settingsWindow.setOnKeymapChanged(this::reloadKeymap); // picker/combo → live keymap switch
        this.settingsWindow.setShortcutActions(new SettingsWindow.ShortcutActions() {
            @Override
            public java.util.List<SettingsWindow.Shortcut> rows() {
                return shortcutRows();
            }

            @Override
            public String commandUsing(String chordSeq) {
                return keymap.commandFor(chordSeq);
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
        debugLogWindow.setSessionFile(DebugLog.sessionFile(config.getConfigDir()));
        this.switcher = new Switcher(
                () -> new java.util.ArrayList<>(tabPane.getTabs()), // list files in tab order
                () -> tabPane.getSelectionModel().getSelectedItem(),
                tab -> tabPane.getSelectionModel().select(tab),
                this::closeTabFromSwitcher);
        setupMruTracking();
        registerCommands();
        setupToolbar();
        setupRecentFiles();
        setupJumpPickers();
        setupProjects();
        applyPlugins(); // register plugin commands/tool windows/hooks (before restore, so their visibility restores)
        toolWindows.restore();
        // Honor a persisted Zen state on launch: the view options + chrome already read the flag via
        // the apply paths; this hides the side stripes (restore() opened nothing — windows were
        // persisted closed when Zen was entered).
        toolWindows.setZenStripesHidden(config.getWorkspaceState().isZenMode());
        applyChromeVisibility();
        applyProjectSupport(); // hide project UI when disabled (default)
        applyGitSupport(); // hide Git UI when disabled (default)
        applyLocalHistory(); // Local File History tool window availability + list (on by default)
        applyNotesSupport(); // hide Personal Notes UI when disabled (default)
        applyMermaidSupport(); // wire mmdc/maid paths; mermaid rendering off when disabled (default)
        applyHttpClientSupport(); // configure ijhttp; .http run glyphs off when disabled (default)
        applyHtmlPreviewSupport(); // HTML "open in browser" control off when disabled (default)
        applyMcpSupport(); // MCP server (loopback HTTP) off when disabled (default)
        applyLspSupport(); // configure the LSP manager; servers/diagnostics off when disabled (default)
        applyDebugSupport(); // configure DAP; debugging off when disabled (default) — after LSP (it layers on jdtls)
        setupWelcome(); // Welcome empty-state shown when no file tabs are open

        // Auto save: idle timer fires a save; the window losing focus saves in onFocusChange mode.
        autoSaveIdleTimer.setOnFinished(e -> autoSaveAllDirty());
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && AUTOSAVE_FOCUS.equals(autoSaveMode())) {
                autoSaveAllDirty();
            }
        });
        applyAutoSave();
    }

    // --- plugins ----------------------------------------------------------------------------------

    /** Injected by {@link WindowManager} before {@link #init}; shared across windows (classes load once). */
    void setPluginManager(com.editora.plugin.PluginManager pm) {
        this.pluginManager = pm;
        if (pm != null) {
            this.pluginRegistry = new com.editora.plugin.PluginRegistry();
            this.pluginInstaller = new com.editora.plugin.PluginInstaller(pm);
        }
    }

    /** Whether plugins may load — the master gate (also off in Simple UI mode). */
    private boolean pluginsEnabled() {
        return pluginManager != null && config.getSettings().isPluginSupport() && !simpleModeActive();
    }

    /**
     * Applies every enabled, error-free plugin to <em>this</em> window: declarative snippet/template source
     * dirs + keymap bindings + external-command palette entries, then each Java plugin's {@code start(ctx)}.
     * Runs once during {@link #init} (off no hot path). One {@code try/catch} per plugin → DebugLog, so a
     * misbehaving plugin never breaks the window.
     */
    private void applyPlugins() {
        if (!pluginsEnabled()) {
            return;
        }
        boolean addedAssets = false;
        for (com.editora.plugin.PluginDescriptor d : pluginManager.descriptors()) {
            if (!d.enabled() || d.loadError() != null) {
                continue;
            }
            try {
                // Declarative: snippet/template source dirs (per-window registries) + keymap (shared).
                java.nio.file.Path snDir = d.dir().resolve("snippets");
                if (java.nio.file.Files.isDirectory(snDir)) {
                    snippets.addExtraSourceDir(snDir);
                    addedAssets = true;
                }
                java.nio.file.Path tplDir = d.dir().resolve("templates");
                if (java.nio.file.Files.isDirectory(tplDir)) {
                    templates.addExtraSourceDir(tplDir);
                    addedAssets = true;
                }
                if (d.manifest().keymap != null && !d.manifest().keymap.isEmpty()) {
                    keymap.applyOverrides(d.manifest().keymap); // shared keymap → applies to every window
                }
                // Declarative external commands → palette commands.
                for (com.editora.plugin.PluginManifest.DeclaredCommand c : d.manifest().commands) {
                    if (c == null || c.id == null || c.id.isBlank()) {
                        continue;
                    }
                    String cid = "plugin." + d.id() + "." + c.id;
                    String title = c.title == null || c.title.isBlank() ? cid : c.title;
                    registry.register(com.editora.command.Command.of(cid, title, () -> runDeclaredCommand(d, c)));
                }
                // Java plugin.
                if (d.hasJavaEntry() && d.classLoader() != null) {
                    com.editora.plugin.Plugin plugin = pluginManager.instantiate(d);
                    if (plugin != null) {
                        plugin.start(new PluginContextImpl(d));
                        startedPlugins.add(plugin);
                    }
                }
            } catch (Throwable t) {
                LOG.log(java.util.logging.Level.WARNING, "Plugin " + d.id() + " failed to apply", t);
                setStatus(tr("status.plugins.failed", d.id()));
            }
        }
        if (addedAssets) {
            snippets.reload();
            templates.reload();
        }
    }

    /** Stops this window's plugins (window close). Each {@code stop()} is isolated. */
    void disposePlugins() {
        for (com.editora.plugin.Plugin p : startedPlugins) {
            try {
                p.stop();
            } catch (Throwable t) {
                LOG.log(java.util.logging.Level.WARNING, "Plugin stop failed", t);
            }
        }
        startedPlugins.clear();
    }

    // --- plugin registry (browse / install) ------------------------------------------------------

    /**
     * Fetches the configured registry index off-thread and shows the "Browse Plugins" picker. No-op (with a
     * status hint) when plugins are disabled or no registry URL is set.
     */
    private void browsePlugins() {
        if (!pluginsEnabled() || pluginRegistry == null) {
            setStatus(tr("status.plugins.disabled"));
            return;
        }
        String url = config.getSettings().getPluginRegistryUrl();
        setStatus(tr("status.plugins.fetching"));
        boolean requireSig = config.getSettings().isPluginRequireSignature();
        pluginRegistry.fetch(url, r -> {
            if (!r.ok()) {
                setStatus(tr("status.plugins.fetchFailed", r.error()));
                return;
            }
            // Signature gate: when "require signed plugins" is on, refuse an unsigned/unverified registry.
            if (requireSig && !r.signed()) {
                gitError(tr("status.plugins.unsigned"), tr("dialog.plugins.unsignedDetail"));
                return;
            }
            browseSigned = r.signed();
            browseEntries = r.entries();
            if (browseEntries.isEmpty()) {
                setStatus(tr("status.plugins.empty"));
                return;
            }
            if (!browseSigned) {
                setStatus(tr("status.plugins.unsignedAllowed"));
            }
            browsePalette.show(stage);
        });
    }

    /** The picker label: "Name  version — Installed/Update available/Install/Requires Editora ≥ x". */
    private String browseEntryLabel(com.editora.plugin.RegistryEntry e) {
        String ver = e.version == null ? "" : e.version;
        return (e.name == null || e.name.isBlank() ? e.id : e.name) + (ver.isBlank() ? "" : "  " + ver) + " — "
                + browseEntryStatus(e);
    }

    /** Installed / Update available / Install / Requires-newer, comparing to the installed descriptor. */
    private String browseEntryStatus(com.editora.plugin.RegistryEntry e) {
        if (!meetsMinEditora(e)) {
            return tr("plugins.status.requiresNewer", e.minEditoraVersion);
        }
        String installed = installedVersion(e.id);
        if (installed == null) {
            return tr("plugins.status.install");
        }
        int cmp = com.editora.plugin.PluginInstaller.compareVersions(e.version, installed);
        return cmp > 0 ? tr("plugins.status.updateAvailable") : tr("plugins.status.installed");
    }

    /** The installed plugin's manifest version, or null when not installed. */
    private String installedVersion(String id) {
        if (pluginManager == null || id == null) {
            return null;
        }
        for (com.editora.plugin.PluginDescriptor d : pluginManager.descriptors()) {
            if (id.equals(d.id())) {
                return d.manifest().version == null ? "" : d.manifest().version;
            }
        }
        return null;
    }

    /** Whether this Editora build satisfies the entry's {@code minEditoraVersion} (blank = any). */
    private static boolean meetsMinEditora(com.editora.plugin.RegistryEntry e) {
        String min = e.minEditoraVersion;
        if (min == null || min.isBlank()) {
            return true;
        }
        return com.editora.plugin.PluginInstaller.compareVersions(com.editora.AppInfo.VERSION, min) >= 0;
    }

    /** Confirms (showing name/version/author/source + the trust warning) then installs from the registry. */
    private void confirmAndInstall(com.editora.plugin.RegistryEntry e) {
        if (e == null) {
            return;
        }
        if (!meetsMinEditora(e)) {
            setStatus(tr("plugins.status.requiresNewer", e.minEditoraVersion));
            return;
        }
        String body = tr(
                "dialog.plugins.installBody",
                (e.name == null || e.name.isBlank() ? e.id : e.name),
                (e.version == null ? "" : e.version),
                (e.author == null ? "" : e.author),
                e.download);
        if (!browseSigned) {
            body = tr("dialog.plugins.unsignedWarn") + "\n\n" + body; // reached only when the gate is off
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.plugins.installTitle"));
        confirm.setHeaderText(tr("dialog.plugins.installHeader"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        setStatus(tr("status.plugins.installing", e.name == null || e.name.isBlank() ? e.id : e.name));
        pluginInstaller.installFromUrl(e, this::onPluginInstalled);
    }

    /** Install-from-disk: pick a {@code .zip}, then install it (no checksum — the user chose the file). */
    private void installPluginFromDisk() {
        if (!pluginsEnabled() || pluginInstaller == null) {
            setStatus(tr("status.plugins.disabled"));
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle(tr("dialog.plugins.installFileTitle"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plugin zip", "*.zip"));
        java.io.File f = fc.showOpenDialog(stage);
        if (f == null) {
            return;
        }
        setStatus(tr("status.plugins.installing", f.getName()));
        pluginInstaller.installFromZip(f.toPath(), this::onPluginInstalled);
    }

    /** Common post-install handling: disclose capabilities + confirm enable, persist, refresh, report. */
    private void onPluginInstalled(com.editora.plugin.PluginInstaller.Result r) {
        if (!r.ok()) {
            gitError(tr("status.plugins.installFailed", r.error() == null ? "" : r.error()), r.error());
            return;
        }
        pluginRegistry.invalidate(); // status labels (Installed/Update) may change
        // Arming gate: the plugin is now on disk; show exactly what it can do before enabling it.
        if (confirmEnablePlugin(r.id())) {
            config.getPluginStore().setEnabled(r.id(), true);
            config.savePlugins();
            setStatus(tr("status.plugins.installed", r.name()));
        } else {
            setStatus(tr("status.plugins.notEnabled", r.name()));
        }
        settingsWindow.syncPluginsCheck(); // rebuilds the per-plugin list
    }

    /**
     * Shows a capability-disclosure confirm before a plugin is <em>enabled</em> (the real arming point —
     * code loads on next launch): whether it ships executable code, the external commands it declares, and
     * any keybindings it remaps. Returns whether the user accepted. Falls back to enabling if the descriptor
     * can't be found.
     */
    private boolean confirmEnablePlugin(String id) {
        com.editora.plugin.PluginDescriptor d = null;
        if (pluginManager != null) {
            for (com.editora.plugin.PluginDescriptor c : pluginManager.descriptors()) {
                if (c.id().equals(id)) {
                    d = c;
                    break;
                }
            }
        }
        if (d == null) {
            return true;
        }
        String name = d.manifest().name == null || d.manifest().name.isBlank() ? d.id() : d.manifest().name;
        String body = tr(
                "dialog.plugins.enableBody",
                name,
                d.manifest().version == null ? "" : d.manifest().version,
                pluginCapabilitySummary(d.manifest(), d.hasJavaEntry()));
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.plugins.enableTitle"));
        confirm.setHeaderText(tr("dialog.plugins.enableHeader"));
        confirm.getDialogPane().setMinWidth(480);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * A human-readable, localized list of what a plugin can do, from its manifest: executable code (a jar),
     * declared external commands (with their argv), and keybinding remaps. Used by the enable-confirm dialogs
     * (here and in {@link SettingsWindow}). Pure aside from {@code tr(...)}.
     */
    public static String pluginCapabilitySummary(com.editora.plugin.PluginManifest m, boolean hasJar) {
        StringBuilder sb = new StringBuilder();
        if (hasJar) {
            sb.append(tr("plugins.cap.code")).append('\n');
        }
        if (m.commands != null && !m.commands.isEmpty()) {
            sb.append(tr("plugins.cap.commands")).append('\n');
            for (com.editora.plugin.PluginManifest.DeclaredCommand c : m.commands) {
                String argv = c.run == null ? "" : String.join(" ", c.run);
                sb.append("    ").append(argv).append('\n');
            }
        }
        if (m.keymap != null && !m.keymap.isEmpty()) {
            sb.append(tr("plugins.cap.keymap")).append('\n');
            m.keymap.forEach((chord, cmd) ->
                    sb.append("    ").append(chord).append(" → ").append(cmd).append('\n'));
        }
        if (sb.length() == 0) {
            sb.append(tr("plugins.cap.assetsOnly"));
        }
        return sb.toString().strip();
    }

    /** Uninstalls a plugin: deletes its folder + drops it from the enabled store (Settings-page Remove). */
    private void uninstallPlugin(String id) {
        if (pluginManager == null || id == null || id.isBlank()) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.plugins.uninstallBody", id), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.plugins.uninstallTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        java.nio.file.Path dir = pluginManager.pluginsDir().resolve(id);
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best-effort
                }
            });
        } catch (java.io.IOException e) {
            gitError(tr("status.plugins.uninstallFailed", id), e.getMessage());
            return;
        }
        config.getPluginStore().setEnabled(id, false);
        config.savePlugins();
        pluginManager.discover();
        settingsWindow.syncPluginsCheck();
        setStatus(tr("status.plugins.uninstalled", id));
    }

    /** Runs a plugin's declared external command via the subprocess runner; reports the result. */
    private void runDeclaredCommand(
            com.editora.plugin.PluginDescriptor d, com.editora.plugin.PluginManifest.DeclaredCommand c) {
        if (c.run == null || c.run.isEmpty()) {
            return;
        }
        java.nio.file.Path cwd = (c.dir == null || c.dir.isBlank())
                ? d.dir()
                : d.dir().resolve(c.dir).normalize();
        setStatus(tr("status.plugins.running", c.title == null || c.title.isBlank() ? c.id : c.title));
        new Thread(
                        () -> {
                            com.editora.process.ProcessRunner.Result r;
                            try {
                                r = com.editora.process.ProcessRunner.run(
                                        cwd,
                                        java.time.Duration.ofSeconds(120),
                                        new java.util.ArrayList<>(c.run),
                                        java.util.Map.of());
                            } catch (RuntimeException e) {
                                Platform.runLater(() -> setStatus(tr("status.plugins.cmdFailed", e.getMessage())));
                                return;
                            }
                            String out = (r.out() + "\n" + r.err()).strip();
                            if (!out.isBlank()) {
                                LOG.info("[plugin " + d.id() + "] " + out);
                            }
                            Platform.runLater(() -> setStatus(
                                    r.ok()
                                            ? tr("status.plugins.cmdDone", c.id)
                                            : tr("status.plugins.cmdFailed", "exit " + r.exit())));
                        },
                        "plugin-cmd-" + d.id())
                .start();
    }

    /** Builds the plugin-contributed right-click items for {@code buffer} (empty when no plugin added any). */
    private java.util.List<javafx.scene.control.MenuItem> pluginEditorMenuItems(EditorBuffer buffer) {
        if (pluginMenuItems.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<javafx.scene.control.MenuItem> items = new java.util.ArrayList<>();
        for (EditorMenuContribution c : pluginMenuItems) {
            javafx.scene.control.MenuItem mi = new javafx.scene.control.MenuItem(c.label());
            mi.setGraphic(Icons.plugin());
            mi.setOnAction(e -> {
                try {
                    c.action().accept(new ActiveEditorImpl(buffer));
                } catch (Throwable t) {
                    LOG.log(java.util.logging.Level.WARNING, "Plugin menu action failed", t);
                }
            });
            items.add(mi);
        }
        return items;
    }

    /** A window-scoped {@link com.editora.plugin.PluginContext}; one per plugin per window. */
    private final class PluginContextImpl implements com.editora.plugin.PluginContext {
        private final com.editora.plugin.PluginDescriptor desc;

        PluginContextImpl(com.editora.plugin.PluginDescriptor desc) {
            this.desc = desc;
        }

        /** Namespaces a bare command id (no dot) to this plugin; a dotted id (a built-in) is used as-is. */
        private String fullId(String id) {
            return id != null && id.indexOf('.') < 0 ? "plugin." + desc.id() + "." + id : id;
        }

        @Override
        public void registerCommand(String id, String title, Runnable action) {
            String cid = fullId(id);
            registry.register(
                    com.editora.command.Command.of(cid, title == null || title.isBlank() ? cid : title, action));
        }

        @Override
        public void bindKey(String chord, String commandId) {
            if (chord != null && !chord.isBlank() && commandId != null) {
                keymap.applyOverrides(java.util.Map.of(chord, fullId(commandId)));
            }
        }

        @Override
        public void registerToolWindow(
                String id,
                String title,
                com.editora.plugin.ToolWindowSide side,
                javafx.scene.layout.Region content,
                String commandId) {
            String twId = fullId(id);
            String cmd = commandId == null || commandId.isBlank() ? twId : fullId(commandId);
            ToolWindow.Side s =
                    switch (side == null ? com.editora.plugin.ToolWindowSide.BOTTOM : side) {
                        case LEFT -> ToolWindow.Side.LEFT;
                        case RIGHT -> ToolWindow.Side.RIGHT;
                        case BOTTOM -> ToolWindow.Side.BOTTOM;
                    };
            ToolWindow tw = new ToolWindow(twId, title == null ? twId : title, s, Icons::plugin, content, cmd);
            toolWindows.register(tw);
            registry.register(
                    com.editora.command.Command.of(cmd, title == null ? twId : title, () -> toolWindows.toggle(tw)));
        }

        @Override
        public void addEditorMenuItem(
                String label, java.util.function.Consumer<com.editora.plugin.ActiveEditor> action) {
            if (label != null && action != null) {
                pluginMenuItems.add(new EditorMenuContribution(label, action));
            }
        }

        @Override
        public void addStatusBarSegment(String label, String commandId) {
            statusBar.addPluginSegment(label, commandId == null ? null : fullId(commandId));
        }

        @Override
        public com.editora.plugin.ActiveEditor activeEditor() {
            return new ActiveEditorImpl(null); // tracks the live active buffer
        }

        @Override
        public java.nio.file.Path pluginDir() {
            return desc.dir();
        }

        @Override
        public java.nio.file.Path dataDir() {
            java.nio.file.Path data = desc.dir().resolve("data");
            try {
                java.nio.file.Files.createDirectories(data);
            } catch (java.io.IOException ignored) {
                // best-effort
            }
            return data;
        }

        @Override
        public java.nio.file.Path configDir() {
            return config.getConfigDir();
        }

        @Override
        public void log(String message) {
            LOG.info("[plugin " + desc.id() + "] " + message);
        }

        @Override
        public void setStatus(String message) {
            MainController.this.setStatus(message);
        }

        @Override
        public void openUrl(String url) {
            if (url != null && !url.isBlank()) {
                MainController.this.openExternalUrl(url);
            }
        }
    }

    /** A {@link com.editora.plugin.ActiveEditor} over a fixed buffer, or the live active buffer when null. */
    private final class ActiveEditorImpl implements com.editora.plugin.ActiveEditor {
        private final EditorBuffer fixed;

        ActiveEditorImpl(EditorBuffer fixed) {
            this.fixed = fixed;
        }

        private EditorBuffer buf() {
            return fixed != null ? fixed : activeBuffer();
        }

        @Override
        public java.nio.file.Path filePath() {
            EditorBuffer b = buf();
            return b == null ? null : b.getPath();
        }

        @Override
        public String text() {
            EditorBuffer b = buf();
            return b == null ? "" : b.getContent();
        }

        @Override
        public String selectedText() {
            EditorBuffer b = buf();
            return b == null ? "" : b.getArea().getSelectedText();
        }

        @Override
        public int caretLine() {
            EditorBuffer b = buf();
            return b == null ? -1 : b.getArea().getCurrentParagraph() + 1; // 1-based
        }

        @Override
        public void replaceSelection(String replacement) {
            EditorBuffer b = buf();
            if (b != null && b.isEditable() && replacement != null) {
                b.getArea().replaceSelection(replacement);
            }
        }

        @Override
        public void insertAtCaret(String text) {
            EditorBuffer b = buf();
            if (b != null && b.isEditable() && text != null) {
                b.getArea().insertText(b.getArea().getCaretPosition(), text);
            }
        }

        @Override
        public void setText(String text) {
            EditorBuffer b = buf();
            if (b != null && b.isEditable() && text != null) {
                b.getArea().replaceText(text); // whole-document replace (undoable, marks dirty)
            }
        }

        @Override
        public void openPath(java.nio.file.Path path) {
            if (path != null) {
                MainController.this.openPath(path);
            }
        }
    }

    /** Shows/hides the toolbar and status bar per the saved settings (hidden nodes also unmanaged so
     *  they don't reserve layout space). Cheap: two visibility flags + one layout pass. */
    private void applyChromeVisibility() {
        Settings s = config.getSettings();
        toolBar.setVisible(s.isShowToolbar());
        toolBar.setManaged(s.isShowToolbar());
        statusBar.setVisible(s.isShowStatusBar());
        statusBar.setManaged(s.isShowStatusBar());
        // The tab header is collapsed via a style class (see app.css) rather than visible/managed:
        // the TabPane skin owns the header node, so toggling a CSS class is the supported way.
        tabPane.getStyleClass().remove("no-tab-header");
        if (!s.isShowTabBar()) {
            tabPane.getStyleClass().add("no-tab-header");
        }
        // Breadcrumb + tool stripes are also hidden by Simple UI mode (effective value preserves the saved
        // prefs, which return when Simple mode is turned off).
        boolean simple = simpleModeActive();
        breadcrumb.setEnabled(s.isShowBreadcrumb() && !simple);
        // Tool stripes (UI only): hidden stripes still let tool windows open via keybinding/palette.
        toolWindows.setStripesEnabled(s.isShowToolStripe() && !simple);
        applySimpleMode();
        updateZenButton();
        updateToolbarRestoreButton();
    }

    /** True when Simple UI mode is active (the saved setting OR the session-only {@code --simple} flag). */
    private boolean simpleModeActive() {
        return config.getSettings().isSimpleMode() || cliSimpleOverride;
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
        recentPalette.setOverlayHost(overlayHost);
        structurePalette.setOverlayHost(overlayHost);
        openFilesPalette.setOverlayHost(overlayHost);
        toolWindowPalette.setOverlayHost(overlayHost);
        bookmarkPalette.setOverlayHost(overlayHost);
        notesPalette.setOverlayHost(overlayHost);
        notesSearchPalette.setOverlayHost(overlayHost);
        snippetPalette.setOverlayHost(overlayHost);
        htmlBrowserPalette.setOverlayHost(overlayHost);
        projectPicker.setOverlayHost(overlayHost);
        fileFinder.setOverlayHost(overlayHost);
        folderFinder.setOverlayHost(overlayHost);
        switcher.setOverlayHost(overlayHost);
        branchPopup.setOverlayHost(overlayHost);
        statusBar.setOverlayHost(overlayHost);
        if (browsePalette != null) {
            browsePalette.setOverlayHost(overlayHost);
        }
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
                && !config.getWorkspaceState().isZenMode();
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
        boolean zen = config.getWorkspaceState().isZenMode();
        zenExitButton.setVisible(zen);
        zenExitButton.setManaged(zen);
        EditorBuffer active = activeBuffer();
        boolean belowMarkdownControls = zen && active != null && active.hasPreview();
        double top = belowMarkdownControls ? 44 : 8; // clear the Markdown preview toggle when present
        StackPane.setMargin(zenExitButton, new javafx.geometry.Insets(top, 12, 0, 0));
    }

    private void setupRecentFiles() {
        recentFiles = new RecentFiles(config.getConfigDir());
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
                this::gitEnabled,
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
        refreshProjectPanelList();
        updateWindowTitle();
        // When a project window is freshly opened, show the Projects tool window so the file tree is
        // right there (called only on a new window build, never when focusing an already-open project).
        if (project != null && projectsEnabled() && projectToolWindow != null) {
            toolWindows.open(projectToolWindow, false);
        }
    }

    /** Re-applies preferences + the editor theme to this window after a Settings change in any window. */
    public void reapplyAfterSharedSettingsChange(Settings settings) {
        applyViewSettingsToAllBuffers(settings);
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
    }

    /**
     * A Settings change was applied in this window. Re-apply it to every open window (the {@link Settings}
     * object is shared by reference, so other windows already see the new values — they just need to
     * restyle). Falls back to this window only when multi-window isn't wired.
     */
    private void onSettingsApplied(Settings settings) {
        if (windowManager != null) {
            windowManager.broadcastSettingsApplied(); // re-applies to every window, including this one
        } else {
            applyViewSettingsToAllBuffers(settings);
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
    private void disposeWindow() {
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.dispose();
            }
        }
        lspManager.shutdownAll(); // don't orphan this window's external language servers
        dapManager.stop(); // end any debug session
        gitService.shutdown();
        if (historyService != null) {
            historyService.shutdown();
        }
        searchService.shutdown();
        mermaidService.shutdown();
        htmlPreviewService.shutdown(); // stop the HTML-preview HTTP server + worker
        stopMcpIfOwner(); // stop the MCP server if this window owns it
        pdfService.shutdown();
        printService.shutdown();
        runService.stop();
        autoSaveExecutor.shutdownNow();
    }

    /**
     * Wires the key dispatcher's first-look hook so that <b>M-g</b>, while a tool window is focused,
     * closes that window and returns focus to the editor (instead of starting the go-to prefix).
     */
    public void setKeyDispatcher(com.editora.command.KeyDispatcher dispatcher) {
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
                tab -> {
                    tabPane.getSelectionModel().select(tab);
                    EditorBuffer b = bufferOf(tab);
                    if (b != null) {
                        b.getArea().requestFocus();
                    }
                });
        openFilesPalette.setItemStyleClass(
                tab -> isTabDirty(tab) ? "dirty-name" : null); // amber/italic, like a dirty tab
        openFilesPalette.setItemIcon(tab -> FileIcons.forFileName(bufferTitle(tab))); // file-type glyph
        toolWindowPalette = new QuickOpen<>(
                "Jump to Tool Window",
                "Type to filter tool windows…",
                () -> toolWindows.getRegisteredToolWindows().stream()
                        .filter(tw -> gitEnabled() || !"tool.commit".equals(tw.getCommandId()))
                        .filter(tw -> projectsEnabled() || !"tool.project".equals(tw.getCommandId()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)),
                ToolWindow::getTitle,
                tw -> invertBindings().getOrDefault(tw.getCommandId(), ""),
                toolWindows::open);
        bookmarkPalette = new QuickOpen<>(
                "Jump to Bookmark",
                "Type to filter bookmarks…",
                this::allBookmarkEntries,
                e -> bookmarkLabel(e.bm()),
                e -> e.file().getFileName() + ":" + (e.bm().line() + 1),
                e -> bookmarkActivate(e.file(), e.bm().line()));
        browsePalette = new QuickOpen<>(
                tr("plugins.browseTitle"),
                tr("plugins.browsePrompt"),
                () -> browseEntries,
                this::browseEntryLabel,
                e -> e.description == null ? "" : e.description,
                e -> (e.name == null ? "" : e.name) + " " + e.id + " "
                        + (e.description == null ? "" : e.description), // search name+id+desc
                this::confirmAndInstall);
        browsePalette.setPreferredSize(960, 10); // wide — registry rows carry a name + a long description
        notesPalette = new QuickOpen<>(
                tr("notes.jumpTitle"),
                tr("notes.jumpPrompt"),
                this::allNoteEntries,
                e -> noteEntryLabel(e.note()),
                e -> Path.of(e.fileKey()).getFileName() + ":"
                        + (e.note().anchor().line() + 1),
                e -> noteActivate(e.fileKey(), e.note()));
        // Search Notes: same picker, but the query matches the full body + tags + file (not just the
        // first line) so you can find a note by any word in it.
        notesSearchPalette = new QuickOpen<>(
                tr("notes.searchTitle"),
                tr("notes.searchPrompt"),
                this::allNoteEntries,
                e -> noteEntryLabel(e.note()),
                e -> Path.of(e.fileKey()).getFileName() + ":"
                        + (e.note().anchor().line() + 1),
                e -> noteSearchText(e),
                e -> noteActivate(e.fileKey(), e.note()));
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
        htmlBrowserPalette = new QuickOpen<>(
                tr("htmlPreview.pickBrowser"),
                tr("htmlPreview.pickBrowser.prompt"),
                () -> new ArrayList<>(htmlBrowsers),
                this::browserLabel,
                b -> "",
                b -> {
                    EditorBuffer active = activeBuffer();
                    if (active != null && active.isHtml()) {
                        previewActiveHtml(active, b);
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
        projects.delete(p.id()); // drops it from the index + open set + deletes its state file
        projects.save();
        config.deleteBookmarksForProject(p.id()); // the project's bookmarks go with it
        config.deleteNotesForProject(p.id()); // ...its personal notes
        config.deleteBreakpointsForProject(p.id()); // ...and its breakpoints
        config.deleteHistoryForProject(p.id()); // ...and its local file history index
        refreshProjectPanelList();
        setStatus(tr("status.deletedProject", p.name()));
    }

    private void updateWindowTitle() {
        stage.setTitle(windowProject == null ? "Editora" : "Editora — " + windowProject.name());
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
        // Reveal the target if it's hidden inside a collapsed fold, so we don't scroll to a hidden line.
        if (buffer != null) {
            buffer.getFoldManager().unfoldContaining(line);
        }
        area.moveTo(line, 0);
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
            }
            fileInfoPanel.attach(buffer);
            structurePanel.attach(buffer);
            statusBar.attach(buffer);
            breadcrumb.setActiveFile(buffer == null ? null : buffer.getPath());
            if (AUTOSAVE_FOCUS.equals(autoSaveMode())) {
                autoSaveAllDirty(); // saves the outgoing buffer (and any other dirty ones)
            }
            refreshSplitButtons();
            refreshEditState(); // save/undo/redo/cut/copy enablement for the new tab
            refreshPasteState(); // clipboard read off the keystroke path
            updateZenButton(); // re-position the Zen "Z" if the new file is/isn't Markdown
            checkExternalChanges(); // prompt if the file we just switched to changed on disk
            refreshGit(); // update branch/status + this file's gutter change bars
            updateLspStatusBar(); // show/hide the "LSP: <server>" segment for the new active file
            updateRunButton(); // show the Run button only for a compact source file
            refreshLocalHistory(); // re-gate + reload the Local File History list for the new active file
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
                                lspProblems.remove(closed.getPath());
                                refreshProblems();
                            }
                            closed.dispose();
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
        projectPanel.setOnNewFromTemplate(this::newFromTemplate); // folder "New From Template…"
        projectPanel.setOnReveal((p, dir) -> revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        projectPanel.setOnOpenTerminal((p, dir) -> openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
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
        bookmarksPanel = new BookmarksPanel(config::getBookmarks, new BookmarksPanel.Actions() {
            @Override
            public void openAndJump(java.nio.file.Path file, int line) {
                bookmarkActivate(file, line);
            }

            @Override
            public void setNote(java.nio.file.Path file, int line, String note) {
                bookmarkSetNote(file, line, note);
            }

            @Override
            public void delete(java.nio.file.Path file, int line) {
                bookmarkDelete(file, line);
            }

            @Override
            public void deleteAll(java.nio.file.Path file) {
                bookmarkDeleteAll(file);
            }

            @Override
            public void moveBookmark(java.nio.file.Path file, int from, int to) {
                MainController.this.moveBookmark(file, from, to);
            }

            @Override
            public void moveFile(int from, int to) {
                moveBookmarkFile(from, to);
            }
        });
        bookmarksPanel.setPrompt(this::promptText); // in-scene bookmark-note prompt
        bookmarksToolWindow = new ToolWindow(
                "bookmarks",
                tr("toolwindow.bookmarks"),
                ToolWindow.Side.RIGHT,
                Icons::bookmark,
                bookmarksPanel,
                "tool.bookmarks");
        notesPanel = new NotesPanel(config::getNotes, new NotesPanel.Actions() {
            @Override
            public void openAndJump(String fileKey, com.editora.config.PersonalNote note) {
                noteActivate(fileKey, note);
            }

            @Override
            public void editBody(String fileKey, com.editora.config.PersonalNote note) {
                noteEditBody(fileKey, note);
            }

            @Override
            public void setStatus(
                    String fileKey, com.editora.config.PersonalNote note, com.editora.config.NoteStatus status) {
                noteSetStatus(fileKey, note, status);
            }

            @Override
            public void delete(String fileKey, com.editora.config.PersonalNote note) {
                noteDelete(fileKey, note);
            }

            @Override
            public void deleteAll(String fileKey) {
                noteDeleteAll(fileKey);
            }
        });
        notesToolWindow = new ToolWindow(
                "notes", tr("toolwindow.notes"), ToolWindow.Side.RIGHT, Icons::notes, notesPanel, "tool.notes");
        fileInfoPanel = new FileInformationPanel();
        fileInfoToolWindow = new ToolWindow(
                "file-information",
                tr("toolwindow.file-information"),
                ToolWindow.Side.RIGHT,
                Icons::about,
                fileInfoPanel,
                "tool.fileInformation");
        gitPanel = new GitPanel(new GitPanel.Actions() {
            @Override
            public void open(String path) {
                if (currentRepoRoot != null) {
                    openPath(currentRepoRoot.resolve(path));
                }
            }

            @Override
            public void stage(String path) {
                gitOp("Staged " + path, "add", "--", path);
            }

            @Override
            public void unstage(String path) {
                gitOp("Unstaged " + path, "reset", "-q", "HEAD", "--", path);
            }

            @Override
            public void discard(String path, boolean untracked) {
                discardChanges(path, untracked);
            }

            @Override
            public void stageAll() {
                gitOp("Staged all changes", "add", "-A");
            }

            @Override
            public void commit(String message) {
                gitCommit(message);
            }

            @Override
            public void push() {
                gitPush();
            }

            @Override
            public void refresh() {
                gitService.invalidateCaches();
                afterGitMutation();
            }

            @Override
            public void diff(String path, boolean staged) {
                diffGitPanelFile(path, staged);
            }
        });
        gitPanel.setOnClone(this::gitClone);
        commitToolWindow = new ToolWindow(
                "commit", tr("toolwindow.commit"), ToolWindow.Side.RIGHT, Icons::git, gitPanel, "tool.commit");
        gitLogPanel = new GitLogPanel(gitLogActions());
        gitLogToolWindow = new ToolWindow(
                "gitLog", tr("toolwindow.gitLog"), ToolWindow.Side.BOTTOM, Icons::gitLog, gitLogPanel, "tool.gitLog");
        historyService = new com.editora.history.HistoryService(
                new com.editora.history.HistoryBlobStore(config.getHistoryBlobsDir()));
        fileHistoryPanel = new FileHistoryPanel(historyActions());
        fileHistoryToolWindow = new ToolWindow(
                "fileHistory",
                tr("toolwindow.fileHistory"),
                ToolWindow.Side.BOTTOM,
                Icons::history,
                fileHistoryPanel,
                "tool.fileHistory");
        searchPanel = new SearchPanel(new SearchPanel.Actions() {
            @Override
            public void search(com.editora.search.SearchQuery query) {
                runFileSearch(query);
            }

            @Override
            public void openMatch(java.nio.file.Path file, int line, int col) {
                openPath(file);
                Platform.runLater(() -> gotoInFile(file, line, col));
            }

            @Override
            public void replaceAll(
                    com.editora.search.SearchQuery query,
                    String replacement,
                    java.util.List<java.nio.file.Path> files) {
                replaceInFiles(query, replacement, files);
            }
        });
        searchToolWindow = new ToolWindow(
                "search", tr("toolwindow.search"), ToolWindow.Side.BOTTOM, Icons::find, searchPanel, "tool.search");
        problemsPanel = new ProblemsPanel(this::openAndGoto);
        problemsToolWindow = new ToolWindow(
                "problems",
                tr("toolwindow.problems"),
                ToolWindow.Side.BOTTOM,
                Icons::problems,
                problemsPanel,
                "tool.problems");
        runPanel = new RunPanel(this::stopRun);
        runPanel.setOnInput(runService::sendInput); // stdin field → the running process
        runPanel.setOnLink(this::openRunLink); // double-clicked stack-trace line → jump
        runToolWindow =
                new ToolWindow("run", tr("toolwindow.run"), ToolWindow.Side.BOTTOM, Icons::run, runPanel, "tool.run");
        debugPanel = new DebugPanel(debugActions());
        debugPanel.setPrompt(this::promptText);
        debugPanel.setOnLink(this::openRunLink); // double-clicked stack-trace line → jump
        debugPanel.setWatches(config.getWorkspaceState().getDebugWatches());
        debugPanel.setOnWatchesChanged(() -> {
            config.getWorkspaceState().setDebugWatches(new java.util.ArrayList<>(debugPanel.getWatches()));
            config.save();
        });
        debugToolWindow = new ToolWindow(
                "debug", tr("toolwindow.debug"), ToolWindow.Side.BOTTOM, Icons::debug, debugPanel, "tool.debug");
        httpPanel = new HttpClientPanel(
                this::saveHttpResponse,
                this::copyHttpAsCurl,
                this::openHttpResponseInTab,
                config.getSettings().getFontFamily(),
                Math.max(1, (int) Math.round(config.getSettings().getFontSize()
                        * config.getSettings().getFontZoom())));
        httpPanel.setOnEnvironmentChanged(env -> {
            config.getWorkspaceState().setHttpEnvironment(env);
            config.save();
        });
        httpToolWindow = new ToolWindow(
                "http", tr("toolwindow.http"), ToolWindow.Side.BOTTOM, Icons::httpClient, httpPanel, "tool.http");
        toolWindows.register(projectToolWindow);
        toolWindows.register(structureToolWindow);
        toolWindows.register(bookmarksToolWindow);
        toolWindows.register(notesToolWindow);
        toolWindows.register(commitToolWindow);
        toolWindows.register(gitLogToolWindow);
        toolWindows.setAvailable(gitLogToolWindow, false); // shown only inside a repo (gated by applyGitState)
        toolWindows.register(fileHistoryToolWindow);
        toolWindows.setAvailable(fileHistoryToolWindow, false); // shown only for a local file with history on
        toolWindows.register(fileInfoToolWindow);
        toolWindows.register(searchToolWindow);
        toolWindows.register(problemsToolWindow);
        toolWindows.register(runToolWindow);
        toolWindows.setAvailable(runToolWindow, false); // shown only when the active file is a compact source
        toolWindows.register(debugToolWindow);
        toolWindows.setAvailable(debugToolWindow, false); // shown only while debugging is enabled
        toolWindows.register(httpToolWindow);
        toolWindows.setAvailable(httpToolWindow, false); // shown only for a .http file with the feature on
        dapManager.setListener(dapListener());
        dapManager.setBreakpointsSupplier(this::collectBreakpoints);
    }

    /** Runs a multi-file search: open buffers (in-memory) + the active project root, results to the panel. */
    private void runFileSearch(com.editora.search.SearchQuery query) {
        java.util.Map<java.nio.file.Path, String> open = new java.util.HashMap<>();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.getPath() != null) {
                open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
            }
        }
        java.nio.file.Path root = null;
        Project p = projects == null ? null : projects.active();
        if (p != null) {
            root = java.nio.file.Path.of(p.root());
        }
        setStatus(tr("search.searching"));
        searchService.search(query, root, open, outcome -> {
            searchPanel.setResults(outcome);
            setStatus(
                    outcome.totalMatches() == 0
                            ? tr("search.none")
                            : tr("search.summary", outcome.totalMatches(), outcome.fileCount()));
        });
    }

    /** Toggles the Find-in-Files tool window: opens it (focusing its query field) when closed, closes it
     *  when already open — so the toolbar icon (and {@code C-S-f}) acts as an open/close toggle. */
    private void openSearchInFiles() {
        if (toolWindows.isOpen(searchToolWindow)) {
            toolWindows.close(searchToolWindow);
        } else {
            toolWindows.open(searchToolWindow, true);
        }
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

    /**
     * Replaces every match of {@code query} with {@code replacement} across {@code files}. Open buffers
     * are edited in-memory (undoable); closed files are rewritten on disk (UTF-8, line endings kept as
     * they live in the text). Asks for confirmation, then re-runs the search to refresh the panel.
     */
    private void replaceInFiles(
            com.editora.search.SearchQuery query, String replacement, java.util.List<java.nio.file.Path> files) {
        if (query == null || query.text() == null || query.text().isEmpty() || files.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("search.replaceConfirm", files.size()),
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                != javafx.scene.control.ButtonType.OK) {
            return;
        }
        int total = 0;
        int changedFiles = 0;
        for (java.nio.file.Path file : files) {
            try {
                Tab tab = tabForPath(file);
                EditorBuffer buffer = bufferOf(tab);
                if (buffer != null) {
                    var r = com.editora.search.MultiFileSearch.replaceAll(buffer.getContent(), query, replacement);
                    if (r.count() > 0) {
                        buffer.setContent(r.text());
                        total += r.count();
                        changedFiles++;
                    }
                } else {
                    String text = java.nio.file.Files.readString(file);
                    var r = com.editora.search.MultiFileSearch.replaceAll(text, query, replacement);
                    if (r.count() > 0) {
                        java.nio.file.Files.writeString(file, r.text());
                        total += r.count();
                        changedFiles++;
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                setStatus(tr("search.replaceFailed", String.valueOf(file.getFileName())));
            }
        }
        setStatus(tr("search.replaced", total, changedFiles));
        runFileSearch(query); // refresh the results panel
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
    private Path gitContextPath() {
        EditorBuffer b = activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null) {
            return file;
        }
        Project active = projects == null ? null : projects.active();
        return active == null ? null : Path.of(active.root());
    }

    /**
     * Recomputes Git status (status bar + tool window) and the active file's gutter change bars, all
     * off the FX thread via {@link com.editora.git.GitService}. Cheap to over-call: stale results are
     * dropped by the service's generation guard, and nothing runs when Git is absent / not a repo.
     */
    /** Whether the Git integration is enabled in Settings (default off). Off in Simple UI mode. */
    private boolean gitEnabled() {
        // Simple UI mode disables Git (status-bar VCS segment, gutter change bars, Commit window); saved setting
        // unchanged.
        return config.getSettings().isGitSupport() && !simpleModeActive();
    }

    /** Effective Local File History gate: the setting, but off in Simple UI mode (saved setting unchanged). */
    private boolean localHistoryEnabled() {
        return config.getSettings().isLocalHistory() && !simpleModeActive();
    }

    /**
     * Reconciles all Git UI with the "Enable Git" setting. When off: the status-bar VCS segment is
     * disabled, the Commit tool window is hidden, every open buffer's gutter change bars are cleared,
     * and commands/keybindings no-op. When on, repopulates on the off→on transition (other triggers
     * keep it fresh thereafter). Runs at startup and on every settings apply (mirrors
     * {@link #applyProjectSupport}).
     */
    private void applyGitSupport() {
        boolean on = gitEnabled();
        statusBar.setGitEnabled(on);
        if (!on) {
            toolWindows.setAvailable(commitToolWindow, false);
            toolWindows.setAvailable(gitLogToolWindow, false);
            currentRepoRoot = null;
            currentBranchName = "";
            currentUpstream = "";
            gitPanel.setStatus(null);
            for (Tab tab : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b != null) {
                    b.setChangeBars(null);
                    b.setBlame(null);
                }
            }
        } else if (!gitSupportApplied) {
            refreshGit(); // off→on: populate status bar + Commit window + active gutter
        }
        gitSupportApplied = on;
    }

    /** Runs {@code action} only when Git is enabled; otherwise reports it (disables the keybinding/command). */
    private void ifGit(Runnable action) {
        if (gitEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.gitDisabled"));
        }
    }

    /** Whether the Mermaid feature is enabled in Settings (default off). */
    private boolean mermaidEnabled() {
        return config.getSettings().isMermaidSupport();
    }

    /**
     * Reconciles the Mermaid feature with its setting. Pushes the configured mmdc/maid paths + the
     * current preview theme + the enabled flag into the service and the editor preview façade, and on
     * any change re-renders open Markdown/diagram previews so {@code ```mermaid} blocks (re)appear or
     * fall back to plain code. Runs at startup and on every settings apply (mirrors
     * {@link #applyGitSupport}).
     */
    private void applyMermaidSupport() {
        Settings s = config.getSettings();
        boolean on = s.isMermaidSupport();
        mermaidService.setPaths(s.getMmdcPath(), s.getMaidPath());
        com.editora.editor.MermaidImages.configure(
                on, mermaidService.mmdcCommand(), mermaidService.maidCommand(), appThemeDark());
        // (Un)wire the preview toggle on open .mmd buffers as the feature flips, restore their saved mode
        // when enabling, and re-render every preview so ```mermaid blocks switch between diagram and code
        // (and re-theme on an app-theme change, which also routes through here).
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b == null) {
                continue;
            }
            ensurePreviewControls(b);
            if (on && b.isDiagram()) {
                restoreMarkdownMode(b);
            }
            b.refreshPreview();
        }
        mermaidSupportApplied = on;
        // Detect the tools (cached), then gate live linting (needs maid) + autocomplete (needs mmdc).
        if (on) {
            mermaidService.detect(avail -> {
                mermaidAvail = avail;
                applyMermaidGating();
            });
        } else {
            mermaidAvail = new com.editora.mermaid.MermaidService.Availability(false, false);
            applyMermaidGating();
        }
    }

    /** Re-applies the tool-detection-dependent gates: per-buffer maid linting + mermaid autocomplete. */
    private void applyMermaidGating() {
        boolean on = mermaidEnabled();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setMermaidLintEnabled(on && mermaidAvail.maid());
            }
        }
        applyAutocomplete();
    }

    /** Runs {@code action} only when Mermaid is enabled; otherwise reports it (no-op command/key). */
    private void ifMermaid(Runnable action) {
        if (mermaidEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.mermaidDisabled"));
        }
    }

    // --- HTML Live Preview (serve via a loopback HttpServer + open in a detected browser) ---------

    /** Whether the HTML Live Preview feature is enabled in Settings (default off). */
    private boolean htmlPreviewEnabled() {
        return config.getSettings().isHtmlPreviewSupport();
    }

    /**
     * Reconciles the HTML Live Preview feature with its setting (mirrors {@link #applyMermaidSupport}):
     * (un)attaches the floating "open in browser" globe on open HTML buffers, detects installed browsers
     * when enabled, and stops the HTTP server when disabled. Runs at startup and on every settings apply.
     */
    private void applyHtmlPreviewSupport() {
        boolean on = htmlPreviewEnabled();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                ensureHtmlPreviewControl(b);
            }
        }
        if (on) {
            htmlPreviewService.detectBrowsers(list -> htmlBrowsers = list);
        } else {
            htmlBrowsers = java.util.List.of();
            htmlPreviewService.stopServer(); // stop serving + clear the preview; the service stays reusable
        }
    }

    /** Attaches/removes the floating browser globe so it shows only on local HTML buffers with the feature on. */
    private void ensureHtmlPreviewControl(EditorBuffer buffer) {
        boolean want = htmlPreviewEnabled() && buffer.isHtml() && isLocalBuffer(buffer);
        boolean has = buffer.hasHtmlPreviewControl();
        if (want && !has) {
            buffer.setHtmlPreviewControl(
                    new HtmlPreviewToggle(() -> htmlBrowsers, this::browserLabel, b -> previewActiveHtml(buffer, b)));
        } else if (!want && has) {
            buffer.setHtmlPreviewControl(null);
        }
    }

    /** The menu/label for a browser; localizes the System Default entry, else uses the browser's name. */
    private String browserLabel(com.editora.web.Browsers.Browser browser) {
        return com.editora.web.Browsers.SYSTEM_DEFAULT.equals(browser.id())
                ? tr("htmlPreview.systemDefault")
                : browser.displayName();
    }

    /** Serves {@code buffer}'s file (live text) and opens it in {@code browser}; remembers the choice. */
    private void previewActiveHtml(EditorBuffer buffer, com.editora.web.Browsers.Browser browser) {
        Path file = buffer.getPath();
        if (file == null) {
            setStatus(tr("status.htmlPreview.unsaved")); // need a file on disk so relative assets resolve
            return;
        }
        config.getSettings().setHtmlPreviewBrowser(browser.id());
        config.save();
        setStatus(tr("status.htmlPreview.opening", browserLabel(browser)));
        htmlPreviewService.preview(file, buffer::getContent, browser, r -> {
            if (r.ok()) {
                setStatus(tr("status.htmlPreview.opened", r.url()));
            } else {
                setStatus(tr("status.htmlPreview.failed", r.message() == null ? "" : r.message()));
            }
        });
    }

    /** The last-used browser (from Settings), else System Default, else the first detected. */
    private com.editora.web.Browsers.Browser lastUsedBrowser() {
        String id = config.getSettings().getHtmlPreviewBrowser();
        for (com.editora.web.Browsers.Browser b : htmlBrowsers) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        for (com.editora.web.Browsers.Browser b : htmlBrowsers) {
            if (com.editora.web.Browsers.SYSTEM_DEFAULT.equals(b.id())) {
                return b;
            }
        }
        return htmlBrowsers.isEmpty()
                ? new com.editora.web.Browsers.Browser(com.editora.web.Browsers.SYSTEM_DEFAULT, "System Default")
                : htmlBrowsers.get(0);
    }

    /** {@code htmlPreview.open}: open the active HTML file in the last-used / default browser. */
    private void htmlPreviewOpen() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isHtml()) {
            setStatus(tr("status.htmlPreview.notHtml"));
            return;
        }
        previewActiveHtml(b, lastUsedBrowser());
    }

    /** {@code htmlPreview.openIn}: pick a detected browser, then open the active HTML file in it. */
    private void htmlPreviewOpenIn() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isHtml()) {
            setStatus(tr("status.htmlPreview.notHtml"));
            return;
        }
        htmlBrowserPalette.show(stage);
    }

    /** {@code view.toggleHtmlPreview}: flip the feature, re-apply, re-sync the Settings checkbox. */
    private void toggleHtmlPreviewSupport() {
        Settings s = config.getSettings();
        s.setHtmlPreviewSupport(!s.isHtmlPreviewSupport());
        config.save();
        applyHtmlPreviewSupport();
        if (settingsWindow != null) {
            settingsWindow.syncHtmlPreviewCheck();
        }
        setStatus(tr("status.toggle.htmlPreview", tr(s.isHtmlPreviewSupport() ? "common.on" : "common.off")));
    }

    /** Runs {@code action} only when HTML Live Preview is enabled; otherwise reports it (no-op). */
    private void ifHtmlPreview(Runnable action) {
        if (htmlPreviewEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.htmlPreviewDisabled"));
        }
    }

    // --- MCP server (loopback HTTP, exposes editor state + commands to an LLM agent) --------------

    private boolean mcpEnabled() {
        // Simple UI mode disables the MCP server too (without changing the saved setting).
        return config.getSettings().isMcpSupport() && !simpleModeActive();
    }

    /**
     * Reconciles the MCP server with its setting (mirrors {@link #applyHttpClientSupport}). Starts the
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
            for (var e : lspProblems.entrySet()) {
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
            searchService.search(q, root, open, fut::complete);
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

    // --- HTTP Client (.http via ijhttp) ----------------------------------------------------------

    private boolean httpEnabled() {
        // Simple UI mode disables the HTTP client entirely (without changing the saved setting).
        return config.getSettings().isHttpClientSupport() && !simpleModeActive();
    }

    /** Runs {@code action} only when the HTTP Client is enabled; otherwise reports it. */
    private void ifHttp(Runnable action) {
        if (httpEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.httpDisabled"));
        }
    }

    /**
     * Reconciles the HTTP Client feature with its setting (mirrors {@link #applyMermaidSupport}):
     * configures the ijhttp command, detects it (cached, async), then gates the request ▶ glyphs +
     * the response tool window. Runs at startup and on every settings apply.
     */
    private void applyHttpClientSupport() {
        boolean on = httpEnabled(); // effective: off in Simple UI mode
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setHttpEnabled(on);
            }
        }
        Settings s = config.getSettings();
        httpPanel.setEditorFont(s.getFontFamily(), Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom())));
        updateRunButton(); // re-gate the HTTP tool window on the active buffer
    }

    /** Runs the request at the caret line of the active {@code .http} buffer (palette command). */
    private void runHttpRequestAtCaret() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isHttpFile()) {
            setStatus(tr("status.http.noRequest"));
            return;
        }
        runHttpRequest(b, b.getArea().getCurrentParagraph());
    }

    /** Executes the request containing {@code line} with the built-in HTTP client, shows the response. */
    private void runHttpRequest(EditorBuffer buffer, int line) {
        if (!httpEnabled()) {
            setStatus(tr("statusbar.tip.httpDisabled"));
            return;
        }
        if (buffer == null || buffer.getPath() == null) {
            setStatus(tr("status.http.saveFirst"));
            return;
        }
        String text = buffer.getContent();
        int index = com.editora.http.HttpFile.requestIndexAt(text, line);
        if (index < 0) {
            setStatus(tr("status.http.noRequest"));
            return;
        }
        com.editora.http.HttpFile.Request req =
                com.editora.http.HttpFile.parse(text).get(index);
        com.editora.http.HttpFile.Parsed parsed = com.editora.http.HttpFile.parseRequest(req);
        String label = parsed.method() + " " + parsed.url();
        startHttpRun(label);
        java.nio.file.Path baseDir = buffer.getPath().toAbsolutePath().getParent();
        httpService.run(parsed, httpVariables(buffer, text), baseDir, ex -> finishHttpRun(label, ex));
    }

    /** Runs every request in the active {@code .http} file sequentially (palette command). */
    private void runHttpFile() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isHttpFile()) {
            setStatus(tr("status.http.noRequest"));
            return;
        }
        if (!httpEnabled()) {
            setStatus(tr("statusbar.tip.httpDisabled"));
            return;
        }
        if (b.getPath() == null) {
            setStatus(tr("status.http.saveFirst"));
            return;
        }
        String text = b.getContent();
        java.util.List<com.editora.http.HttpFile.Parsed> reqs = com.editora.http.HttpFile.parse(text).stream()
                .map(r -> com.editora.http.HttpFile.parseRequest(r))
                .toList();
        if (reqs.isEmpty()) {
            setStatus(tr("status.http.noRequest"));
            return;
        }
        String label = b.getPath().getFileName().toString();
        startHttpRun(label);
        java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
        httpService.runAll(reqs, httpVariables(b, text), baseDir, exchanges -> {
            httpPanel.showExchanges(exchanges);
            boolean allOk = exchanges.stream().allMatch(ex -> ex.result().ok());
            setStatus(allOk ? tr("status.http.done", label) : tr("status.http.failed", exchanges.size()));
        });
    }

    private void startHttpRun(String label) {
        toolWindows.open(httpToolWindow);
        httpPanel.started(label);
        setStatus(tr("status.http.running", label));
    }

    private void finishHttpRun(String label, com.editora.http.HttpExchange ex) {
        httpPanel.showExchanges(java.util.List.of(ex));
        com.editora.http.HttpResult r = ex.result();
        setStatus(r.failed() || !r.ok() ? tr("status.http.failed", r.status()) : tr("status.http.done", label));
    }

    /** Copies {@code ex}'s (resolved) request as a {@code curl} command to the clipboard. */
    private void copyHttpAsCurl(com.editora.http.HttpExchange ex) {
        String curl = com.editora.http.CurlExport.toCurl(ex.method(), ex.url(), ex.headers(), ex.requestBody());
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(curl);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        setStatus(tr("status.http.curlCopied"));
    }

    /** Opens {@code ex}'s response body in a new (untitled) editor tab, highlighted by content type. */
    private void openHttpResponseInTab(com.editora.http.HttpExchange ex) {
        com.editora.http.HttpResult r = ex.result();
        if (r.failed()) {
            setStatus(tr("status.http.noResponse"));
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName("response" + responseExt(r.contentType()));
        addBuffer(buffer, true);
        buffer.setContent(com.editora.http.HttpResponseFormat.prettyBody(r.body(), r.contentType()));
    }

    private static String responseExt(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.contains("json")) {
            return ".json";
        }
        if (ct.contains("html")) {
            return ".html";
        }
        if (ct.contains("xml")) {
            return ".xml";
        }
        return ".txt";
    }

    /** Copies the response viewer's selected request as a {@code curl} command (palette command). */
    private void copyActiveHttpAsCurl() {
        com.editora.http.HttpExchange ex = httpPanel.getSelectedExchange();
        if (ex == null) {
            setStatus(tr("status.http.noResponse"));
            return;
        }
        copyHttpAsCurl(ex);
    }

    /** Opens the response viewer's selected response in a new editor tab (palette command). */
    private void openActiveHttpResponseInTab() {
        com.editora.http.HttpExchange ex = httpPanel.getSelectedExchange();
        if (ex == null) {
            setStatus(tr("status.http.noResponse"));
            return;
        }
        openHttpResponseInTab(ex);
    }

    /** Converts a {@code curl} command on the clipboard into a request block, appending it to the active
     *  {@code .http} buffer (or a new one). */
    private void importCurlFromClipboard() {
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        if (clip == null || clip.isBlank() || !clip.contains("curl")) {
            setStatus(tr("status.http.notCurl"));
            return;
        }
        String request = com.editora.http.CurlImport.toHttpRequest(clip.strip());
        EditorBuffer b = activeBuffer();
        if (b != null && b.isHttpFile() && b.isEditable()) {
            String snippet = (b.getArea().getLength() == 0 ? "" : "\n") + "###\n" + request + "\n";
            b.getArea().insertText(b.getArea().getLength(), snippet);
            b.getArea().moveTo(b.getArea().getLength());
        } else {
            EditorBuffer nb = new EditorBuffer();
            nb.setDisplayName("requests.http");
            addBuffer(nb, true);
            nb.setContent(request);
        }
        setStatus(tr("status.http.curlImported"));
    }

    /** The resolved variable map for a {@code .http} buffer: the selected environment's vars (public +
     *  private env files) overlaid with the file's {@code @var} declarations. */
    private java.util.Map<String, String> httpVariables(EditorBuffer buffer, String text) {
        java.nio.file.Path dir = buffer.getPath().toAbsolutePath().getParent();
        String env = httpPanel.getSelectedEnvironment();
        java.util.Map<String, String> envVars = new java.util.LinkedHashMap<>();
        envVars.putAll(httpEnvVars(dir == null ? null : dir.resolve("http-client.env.json"), env));
        envVars.putAll(httpEnvVars(dir == null ? null : dir.resolve("http-client.private.env.json"), env));
        return com.editora.http.HttpVars.resolve(
                envVars, com.editora.http.HttpFile.fileVariablePairs(text), java.time.LocalDateTime.now());
    }

    private java.util.Map<String, String> httpEnvVars(java.nio.file.Path file, String env) {
        if (file == null || env == null || env.isEmpty() || !java.nio.file.Files.exists(file)) {
            return java.util.Map.of();
        }
        try {
            return com.editora.http.HttpEnv.variables(java.nio.file.Files.readString(file), env);
        } catch (java.io.IOException e) {
            return java.util.Map.of();
        }
    }

    /** The environment names declared in the {@code .http} file's directory (for the picker). */
    private java.util.List<String> httpEnvironmentNames(java.nio.file.Path dir) {
        if (dir == null) {
            return java.util.List.of();
        }
        java.nio.file.Path env = dir.resolve("http-client.env.json");
        if (!java.nio.file.Files.exists(env)) {
            return java.util.List.of();
        }
        try {
            return com.editora.http.HttpEnv.environmentNames(java.nio.file.Files.readString(env));
        } catch (java.io.IOException e) {
            return java.util.List.of();
        }
    }

    /** Saves the current HTTP response text to a chosen file. */
    private void saveHttpResponse() {
        String text = httpPanel.getResponseText();
        if (text == null || text.isEmpty()) {
            setStatus(tr("status.http.noResponse"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveResponse.title"));
        chooser.setInitialFileName("response.txt");
        java.nio.file.Path file = pathOf(chooser.showSaveDialog(stage));
        if (file == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(file, text);
            setStatus(tr("status.http.saved", file.getFileName()));
        } catch (java.io.IOException e) {
            setStatus(tr("status.http.saveFailed", e.getMessage()));
        }
    }

    // --- Debugging (DAP) integration -----------------------------------------------------------

    private boolean debugSupportEnabled() {
        // Simple UI mode disables debugging (+ the breakpoint gutter) entirely; saved setting unchanged.
        return config.getSettings().isDebugSupport() && !simpleModeActive();
    }

    /** Debugging is <em>effective</em> for Java only when LSP + the java server are on and detected, and
     *  the java-debug plugin jar was located. */
    private boolean debugEffective() {
        return debugEffectiveFor("java");
    }

    /**
     * Whether debugging is available for {@code language} (the editor/LSP language id): Java layers on
     * the jdtls LSP server + the java-debug plugin; Python needs {@code pythonDebugEnabled} + debugpy
     * detected; JavaScript needs {@code jsDebugEnabled} + the js-debug server + node detected.
     */
    private boolean debugEffectiveFor(String language) {
        if (!debugSupportEnabled() || language == null) {
            return false;
        }
        Settings s = config.getSettings();
        return switch (language) {
            case "java" ->
                lspEnabled()
                        && serverEnabled("java")
                        && lspServerAvailable.getOrDefault("java", Boolean.FALSE)
                        && dapManager.isAdapterAvailable();
            case "python" -> s.isPythonDebugEnabled() && dapManager.isLanguageAvailable("python");
            case "javascript" -> s.isJsDebugEnabled() && dapManager.isLanguageAvailable("javascript");
            default -> false;
        };
    }

    /** Runs {@code action} only when the Debug feature is enabled; otherwise reports it. */
    private void ifDebug(Runnable action) {
        if (debugSupportEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /**
     * Reconciles the Debug feature with its setting (mirrors {@link #applyLspSupport}). **The plugin jar is
     * located synchronously and pushed into the LSP layer BEFORE any jdtls session can start** — doing it in
     * an async callback raced the session-restore, so jdtls could come up without the debug bundle and
     * report "No delegateCommandHandler for vscode.java.resolveMainClass". When the bundle set changes, a
     * *running* jdtls is restarted so it reloads with (or without) the plugin; otherwise the next session
     * just initializes with the up-to-date bundles. Gates the breakpoint gutter + Debug window. Runs at init
     * + every settings apply.
     */
    private void applyDebugSupport() {
        Settings s = config.getSettings();
        boolean on = debugSupportEnabled(); // effective: off in Simple UI mode
        // Configure all three adapters: java (jdtls plugin, located synchronously) + the standalone
        // python (debugpy) / javascript (vscode-js-debug) adapters (paths resolved synchronously; their
        // availability needs a subprocess probe, run async below).
        dapManager.configure(
                on,
                s.getJavaDebugPluginPath(),
                s.isPythonDebugEnabled(),
                s.getPythonDebugCommand(),
                s.isJsDebugEnabled(),
                s.getJsDebugPath());
        List<String> bundles = on ? dapManager.bundlePaths() : List.of();
        boolean changed = !bundles.equals(appliedDebugBundles);
        lspManager.setDebugBundles(bundles); // set before sessions start — jdtls always gets the bundle
        appliedDebugBundles = bundles;
        if (!on) {
            dapManager.stop();
        }
        if (changed) {
            lspManager.restartServer("java"); // reload a running jdtls with/without the bundle (no-op if none)
            applyLspGating(); // re-open the java buffers on the fresh session
        }
        applyDebugGating();
        if (on) {
            // Probe debugpy / node off-thread; re-gate when each result lands (enables python/js gutters).
            dapManager.detectPython(ok -> applyDebugGating());
            dapManager.detectJs(ok -> applyDebugGating());
        }
    }

    /** Per-buffer breakpoint-gutter gate (only for debuggable languages) + Debug tool-window availability. */
    private void applyDebugGating() {
        boolean on = debugSupportEnabled();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setBreakpointsEnabled(on && isDebuggableBuffer(b));
            }
        }
        toolWindows.setAvailable(debugToolWindow, on);
        if (!on) {
            statusBar.setDebug(null);
            statusBar.setDebugLoading(false);
        }
    }

    /** Whether a buffer's language has a registered debug adapter (java/python/javascript). */
    private boolean isDebuggableBuffer(EditorBuffer b) {
        return b != null
                && com.editora.vfs.Vfs.isLocal(b.getPath())
                && com.editora.dap.DapServerRegistry.isDebuggable(b.getLanguage());
    }

    /** True when {@code b}'s file is on the local filesystem (or untitled) — the gate for every feature
     *  that shells out to a local process (LSP/DAP/git/run/HTTP). Remote (SFTP) buffers are text-only. */
    private boolean isLocalBuffer(EditorBuffer b) {
        return b != null && com.editora.vfs.Vfs.isLocal(b.getPath());
    }

    /** Persists a buffer's breakpoints + (if a session is live) re-sends that file's set to the adapter. */
    private void onBreakpointsChanged(EditorBuffer buffer) {
        persistBreakpoints(buffer);
        if (buffer.getPath() != null && dapManager.isActive()) {
            dapManager.updateBreakpoints(fileBreakpoints(buffer));
        }
    }

    private void persistBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<com.editora.config.Breakpoint> bps = buffer.getBreakpointManager().snapshot();
        var map = config.getBreakpoints();
        if (bps.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(
                    file.toString(),
                    com.editora.config.BreakpointStore.mergePreservingOrder(map.get(file.toString()), bps));
        }
        config.saveBreakpoints();
    }

    private void restoreBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        if (buffer.applyBreakpoints(config.getBreakpoints().get(file.toString()))) {
            persistBreakpoints(buffer); // self-heal re-anchored indices once
        }
    }

    /** The enabled breakpoints of {@code buffer} as a DAP {@code FileBreakpoints} (empty list if none). */
    private com.editora.dap.DapModels.FileBreakpoints fileBreakpoints(EditorBuffer buffer) {
        List<com.editora.dap.DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (com.editora.config.Breakpoint bp : buffer.getBreakpointManager().snapshot()) {
            if (bp.enabled()) {
                lines.add(new com.editora.dap.DapModels.LineBreakpoint(bp.line(), bp.condition(), bp.logMessage()));
            }
        }
        return new com.editora.dap.DapModels.FileBreakpoints(buffer.getPath(), lines);
    }

    /** All open buffers' enabled breakpoints (sent to the adapter when a session initializes). */
    private List<com.editora.dap.DapModels.FileBreakpoints> collectBreakpoints() {
        List<com.editora.dap.DapModels.FileBreakpoints> out = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b == null || b.getPath() == null) {
                continue;
            }
            com.editora.dap.DapModels.FileBreakpoints fb = fileBreakpoints(b);
            if (!fb.breakpoints().isEmpty()) {
                out.add(fb);
            }
        }
        return out;
    }

    // -- DAP event sink + panel actions --

    private com.editora.dap.DapManager.Listener dapListener() {
        return new com.editora.dap.DapManager.Listener() {
            @Override
            public void onState(com.editora.dap.DapManager.State state) {
                debugPanel.setState(state);
                updateDebugStatus(state);
                if (state != com.editora.dap.DapManager.State.SUSPENDED) {
                    clearExecHighlight();
                    clearDebugEditorSurfaces(); // inline values + hover live only while suspended
                }
                boolean starting = state == com.editora.dap.DapManager.State.STARTING;
                statusBar.setDebugLoading(starting);
                if (starting) {
                    toolWindows.open(debugToolWindow);
                }
            }

            @Override
            public void onStopped(int threadId, String reason, List<com.editora.dap.DapModels.StackFrameInfo> frames) {
                debugPanel.setCallStack(frames); // auto-selects the top frame → selectFrame highlights + loads vars
                dapManager.threads(list -> debugPanel.setThreads(list, dapManager.currentThreadId()));
            }

            @Override
            public void onOutput(String text, String category) {
                debugPanel.appendOutput(text, category);
            }

            @Override
            public void onError(String message) {
                setStatus(tr("status.debug.error", message));
            }
        };
    }

    private void updateDebugStatus(com.editora.dap.DapManager.State state) {
        switch (state) {
            case INACTIVE, TERMINATED -> statusBar.setDebug(null);
            case STARTING -> statusBar.setDebug(tr("debug.state.starting"));
            case RUNNING -> statusBar.setDebug(tr("debug.state.running"));
            case SUSPENDED -> statusBar.setDebug(tr("debug.state.suspended"));
        }
    }

    private DebugPanel.Actions debugActions() {
        return new DebugPanel.Actions() {
            @Override
            public void start() {
                debugStart(); // start a session when idle, or continue when suspended
            }

            @Override
            public void pause() {
                dapManager.pause();
            }

            @Override
            public void runToCursor() {
                debugRunToCursor();
            }

            @Override
            public void selectThread(int threadId) {
                dapManager.selectThread(threadId, frames -> debugPanel.setCallStack(frames));
            }

            @Override
            public void stepOver() {
                dapManager.stepOver();
            }

            @Override
            public void stepInto() {
                dapManager.stepInto();
            }

            @Override
            public void stepOut() {
                dapManager.stepOut();
            }

            @Override
            public void stop() {
                dapManager.stop();
            }

            @Override
            public void restart() {
                dapManager.restart();
            }

            @Override
            public void selectFrame(com.editora.dap.DapModels.StackFrameInfo frame) {
                debugFrameId = frame.id(); // the hover evaluator's frame context
                highlightFrame(frame);
                dapManager.scopes(frame.id(), scopes -> {
                    debugPanel.setScopes(scopes);
                    applyInlineValues(frame, scopes);
                });
            }

            @Override
            public void loadChildren(
                    int ref, java.util.function.Consumer<List<com.editora.dap.DapModels.VariableInfo>> cb) {
                dapManager.variables(ref, cb);
            }

            @Override
            public void evaluate(String expr, int frameId, java.util.function.Consumer<String> cb) {
                dapManager.evaluate(expr, frameId, "repl", cb);
            }

            @Override
            public void evaluateWatch(
                    String expr, int frameId, java.util.function.Consumer<com.editora.dap.DapModels.EvalResult> cb) {
                dapManager.evaluateFull(expr, frameId, "watch", cb);
            }

            @Override
            public void setVariable(int parentRef, String name, String value, java.util.function.Consumer<String> cb) {
                dapManager.setVariable(parentRef, name, value, cb);
            }
        };
    }

    /** The selected stack frame's id (the hover evaluator's context); -1 while not suspended. */
    private int debugFrameId = -1;
    /** The buffer currently carrying inline values + an active hover (cleared on resume/terminate). */
    private EditorBuffer debugValuesBuffer;
    /** Inline-value fetch cap — frames can hold hundreds of locals; the overlay needs a name→value map. */
    private static final int MAX_INLINE_VALUES = 100;

    /** Fetches the selected frame's local variables and paints them as inline values in the frame's
     *  file buffer; also arms the hover value tooltip there (IntelliJ's editor surfaces). */
    private void applyInlineValues(
            com.editora.dap.DapModels.StackFrameInfo frame, List<com.editora.dap.DapModels.ScopeInfo> scopes) {
        if (frame == null || frame.file() == null || scopes.isEmpty()) {
            return;
        }
        com.editora.dap.DapModels.ScopeInfo locals =
                scopes.stream().filter(s -> !s.expensive()).findFirst().orElse(null);
        if (locals == null) {
            return;
        }
        dapManager.variables(locals.variablesReference(), vars -> {
            if (dapManager.state() != com.editora.dap.DapManager.State.SUSPENDED) {
                return; // resumed while the request was in flight
            }
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (com.editora.dap.DapModels.VariableInfo v : vars) {
                if (values.size() == MAX_INLINE_VALUES) {
                    break;
                }
                values.put(v.name(), v.value());
            }
            EditorBuffer b = bufferOf(tabForPath(frame.file()));
            if (b == null) {
                return;
            }
            if (debugValuesBuffer != null && debugValuesBuffer != b) {
                debugValuesBuffer.setInlineValues(null); // frame moved to another file
                debugValuesBuffer.setDebugHoverActive(false);
            }
            debugValuesBuffer = b;
            b.setInlineValues(values);
            b.setDebugHoverActive(true);
        });
    }

    private void clearDebugEditorSurfaces() {
        debugFrameId = -1;
        if (debugValuesBuffer != null) {
            debugValuesBuffer.setInlineValues(null);
            debugValuesBuffer.setDebugHoverActive(false);
            debugValuesBuffer = null;
        }
    }

    /** Opens the frame's file and paints the execution-line highlight there. */
    private void highlightFrame(com.editora.dap.DapModels.StackFrameInfo frame) {
        if (frame == null || frame.file() == null) {
            return;
        }
        clearExecHighlight();
        openPath(frame.file()); // opens or focuses the tab
        EditorBuffer b = activeBuffer();
        if (b != null) {
            execHighlightBuffer = b;
            b.setExecutionLine(frame.line());
        }
    }

    private void clearExecHighlight() {
        if (execHighlightBuffer != null) {
            execHighlightBuffer.clearExecutionLine();
            execHighlightBuffer = null;
        }
    }

    // -- Debug commands --

    /** Starts a launch debug session for the active file (saving first, like Run). */
    private void debugStart() {
        EditorBuffer b = activeBuffer();
        if (dapManager.isActive()) {
            // A session is live. If the user switched to a DIFFERENT debuggable file and the old
            // session is merely running (not paused mid-step), retarget: stop it and launch the new
            // file — otherwise the Debug window stays silently bound to the old file. While SUSPENDED
            // the green button keeps its Continue semantics (never yank a paused session).
            boolean differentFile = b != null && b.getPath() != null && !samePath(b.getPath(), dapManager.debugFile());
            if (dapManager.state() == com.editora.dap.DapManager.State.SUSPENDED
                    || !differentFile
                    || !debugEffectiveFor(b.getLanguage())) {
                dapManager.resume(); // F5-style continue (no-op unless suspended)
                return;
            }
            dapManager.stop(); // retarget to the newly active file below
        }
        if (b == null || b.getPath() == null && !save(b)) {
            setStatus(tr("status.debug.saveFirst"));
            return;
        }
        String language = b.getLanguage();
        if (!debugEffectiveFor(language)) {
            setStatus(tr("status.debug.unavailable"));
            return;
        }
        if ((b.isDirty() || b.getPath() == null) && !save(b)) {
            return;
        }
        toolWindows.open(debugToolWindow);
        debugPanel.setSessionFile(b.getPath().getFileName().toString());
        // The debuggee gets the same per-file program arguments the Run feature uses.
        dapManager.setProgramArgs(com.editora.run.ProgramArgs.tokenize(programArgsFor(b.getPath())));
        dapManager.startLaunch(b.getPath(), language, this::pickMainClass);
    }

    /** Whether two paths refer to the same file (normalized absolute comparison; null-safe). */
    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    /** Resumes and stops at the active buffer's caret line via a one-shot temporary breakpoint. */
    private void debugRunToCursor() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || dapManager.state() != com.editora.dap.DapManager.State.SUSPENDED) {
            return;
        }
        dapManager.runToCursor(b.getPath(), b.getArea().getCurrentParagraph());
    }

    /** Jump to Line: move the execution pointer to the caret line without executing in-between code.
     *  Capability-gated — debugpy supports it; java-debug/js-debug report "not supported". */
    private void debugJumpToLine() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || dapManager.state() != com.editora.dap.DapManager.State.SUSPENDED) {
            return;
        }
        if (!dapManager.supportsJumpToLine()) {
            setStatus(tr("status.debug.jumpUnsupported"));
            return;
        }
        dapManager.jumpToLine(
                b.getPath(),
                b.getArea().getCurrentParagraph(),
                err -> setStatus(err.isEmpty() ? tr("status.debug.jumpNoTarget") : err));
    }

    /** Attaches to a running JVM (asks for {@code host:port}). */
    private void debugAttach() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            setStatus(tr("status.debug.saveFirst"));
            return;
        }
        if (!debugEffective()) {
            setStatus(tr("status.debug.unavailable"));
            return;
        }
        promptText(tr("dialog.debug.attachTitle"), tr("dialog.debug.attachContent"), "localhost:5005", input -> {
            String text = input == null ? "" : input.trim();
            String host = "localhost";
            String portStr = text;
            int colon = text.lastIndexOf(':');
            if (colon >= 0) {
                host = text.substring(0, colon);
                portStr = text.substring(colon + 1);
            }
            try {
                int port = Integer.parseInt(portStr.trim());
                toolWindows.open(debugToolWindow);
                debugPanel.setSessionFile(b.getPath().getFileName().toString());
                dapManager.startAttach(b.getPath(), host, port);
            } catch (NumberFormatException e) {
                setStatus(tr("status.debug.badAddress", text));
            }
        });
    }

    /** Main-class chooser (QuickOpen) when jdtls finds several. */
    private void pickMainClass(
            List<com.editora.dap.DapManager.MainClassOption> options,
            java.util.function.Consumer<com.editora.dap.DapManager.MainClassOption> chosen) {
        QuickOpen<com.editora.dap.DapManager.MainClassOption> picker = new QuickOpen<>(
                tr("debug.pickMainTitle"),
                tr("debug.pickMainPrompt"),
                () -> options,
                com.editora.dap.DapManager.MainClassOption::mainClass,
                o -> o.projectName() == null ? "" : o.projectName(),
                chosen);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Toggles a breakpoint on the active buffer's caret line. */
    private void toggleBreakpointAtCaret() {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.toggleBreakpoint(b.getArea().getCurrentParagraph());
        }
    }

    /** Edits the caret line's breakpoint condition / log message (creating one if absent). */
    private void editBreakpointAtCaret() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        int line = b.getArea().getCurrentParagraph();
        var mgr = b.getBreakpointManager();
        if (!mgr.isBreakpoint(line)) {
            b.toggleBreakpoint(line);
        }
        com.editora.config.Breakpoint bp = mgr.get(line);
        String initial = bp == null ? "" : bp.condition();
        promptText(
                tr("dialog.debug.conditionTitle"),
                tr("dialog.debug.conditionContent"),
                initial,
                cond -> mgr.setCondition(line, cond == null ? "" : cond.trim()));
    }

    /** Toggles the "uncaught exceptions" breakpoint filter. */
    private void toggleExceptionBreakpoints() {
        if (exceptionFilters.contains("uncaught")) {
            exceptionFilters.remove("uncaught");
        } else {
            exceptionFilters.add("uncaught");
        }
        dapManager.setExceptionFilters(new ArrayList<>(exceptionFilters));
        setStatus(
                tr(exceptionFilters.contains("uncaught") ? "status.debug.exceptionsOn" : "status.debug.exceptionsOff"));
    }

    // --- LSP (Language Server Protocol) integration --------------------------------------------

    private boolean lspEnabled() {
        // Simple UI mode disables LSP (servers, diagnostics, completion, navigation); saved setting unchanged.
        return config.getSettings().isLspSupport() && !simpleModeActive();
    }

    /**
     * Reconciles the LSP feature with its setting (mirrors {@link #applyMermaidSupport}). Configures the
     * manager + Problems window, then (when enabled) detects the server and gates per-buffer LSP. Runs at
     * init and on every settings apply.
     */
    private void applyLspSupport() {
        Settings s = config.getSettings();
        boolean on = lspEnabled(); // effective: off in Simple UI mode
        lspManager.configure(
                on,
                java.util.Map.ofEntries(
                        java.util.Map.entry("java", s.getJavaLspCommand()),
                        java.util.Map.entry("typescript", s.getTypescriptLspCommand()),
                        java.util.Map.entry("python", s.getPythonLspCommand()),
                        java.util.Map.entry("xml", s.getXmlLspCommand()),
                        java.util.Map.entry("json", s.getJsonLspCommand()),
                        java.util.Map.entry("bash", s.getBashLspCommand()),
                        java.util.Map.entry("yaml", s.getYamlLspCommand()),
                        java.util.Map.entry("go", s.getGoLspCommand()),
                        java.util.Map.entry("rust", s.getRustLspCommand()),
                        java.util.Map.entry("php", s.getPhpLspCommand()),
                        java.util.Map.entry("ruby", s.getRubyLspCommand()),
                        java.util.Map.entry("clangd", s.getClangdLspCommand()),
                        java.util.Map.entry("html", s.getHtmlLspCommand()),
                        java.util.Map.entry("css", s.getCssLspCommand()),
                        java.util.Map.entry("kotlin", s.getKotlinLspCommand()),
                        java.util.Map.entry("lua", s.getLuaLspCommand()),
                        java.util.Map.entry("dockerfile", s.getDockerfileLspCommand()),
                        java.util.Map.entry("sql", s.getSqlLspCommand()),
                        java.util.Map.entry("terraform", s.getTerraformLspCommand()),
                        java.util.Map.entry("toml", s.getTomlLspCommand()),
                        java.util.Map.entry("csharp", s.getCsharpLspCommand())));
        if (problemsToolWindow != null) {
            toolWindows.setAvailable(problemsToolWindow, on);
        }
        // The Run affordance (compact source files) is gated by the LSP feature: toggle every buffer's
        // Run detection, then refresh the active buffer's Run tool-window availability.
        boolean shellRun = on && s.isBashLspEnabled();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setRunEnabled(on);
                b.setShellRunEnabled(shellRun); // shell Run glyph gated by the Bash LSP toggle
            }
        }
        updateRunButton();
        if (!on) {
            lspServerAvailable.clear();
            lspProblems.clear();
            refreshProblems();
            for (Tab tab : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b != null) {
                    b.setLspActive(false);
                }
            }
            statusBar.setLspLoading(false);
            updateLspStatusBar();
            return;
        }
        for (String serverId : LSP_SERVER_IDS) {
            // Stop any server whose per-server toggle is off (frees its process); buffers deactivate below.
            if (!serverEnabled(serverId)) {
                lspManager.shutdownServer(serverId);
            }
            // Probe each known server independently (one may be installed and another not).
            lspManager.detect(serverId, ok -> {
                lspServerAvailable.put(serverId, ok);
                applyLspGating();
            });
        }
    }

    /** Whether a server's own enable toggle is on (under the global LSP enable). */
    private boolean serverEnabled(String serverId) {
        Settings s = config.getSettings();
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
            default -> s.isJavaLspEnabled();
        };
    }

    /** The configured command for a server id (blank ⇒ the server's default). */
    private String serverCommand(String serverId) {
        Settings s = config.getSettings();
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
            default -> s.getJavaLspCommand();
        };
    }

    /** Applies the detection-dependent gate to every open buffer (per the file's own server). */
    private void applyLspGating() {
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                syncBufferLsp(b);
            }
        }
        updateLspStatusBar();
    }

    /** Opens+activates an eligible buffer on its language's server, or deactivates+closes it otherwise. */
    private void syncBufferLsp(EditorBuffer buffer) {
        Path path = buffer.getPath();
        String lang = buffer.getLanguage();
        String serverId = com.editora.lsp.LspServerRegistry.serverIdFor(lang);
        boolean eligible = lspEnabled()
                && path != null
                && com.editora.vfs.Vfs.isLocal(path)
                && !buffer.isLargeFile() // 5+ MB files skip LSP (like highlighting/minimap/git) — see setLspActive
                && serverId != null
                && serverEnabled(serverId)
                && Boolean.TRUE.equals(lspServerAvailable.get(serverId));
        if (eligible) {
            if (!lspManager.isManaged(path)) {
                setStatus(tr("status.lsp.starting", serverLabel(serverId)));
                statusBar.setLspLoading(true); // show the loading bar until the server reports ready
                lspManager.openDocument(path, lspRootFor(buffer), lang, buffer.text());
            }
            buffer.setLspActive(true);
            // Push the server's completion trigger characters + request initial pull diagnostics. Both are
            // no-ops until the server's initialize completes (then onLspServerStatus "ready" refreshes them),
            // and effective immediately when the server for this root is already running (a 2nd file).
            buffer.setLspTriggerChars(lspManager.triggerCharacters(path));
            lspManager.pullDiagnostics(path);
        } else {
            buffer.setLspActive(false);
            buffer.setLspTriggerChars(java.util.Set.of());
            if (path != null && lspManager.isManaged(path)) {
                lspManager.closeDocument(path);
                lspProblems.remove(path);
                refreshProblems();
            }
        }
    }

    /** Workspace root for a buffer: active project (if Projects on), else nearest build file, else dir. */
    private Path lspRootFor(EditorBuffer buffer) {
        Project active = (projects != null && config.getSettings().isProjectSupport()) ? projects.active() : null;
        Path projectRoot = active == null ? null : Path.of(active.root());
        return com.editora.lsp.RootResolver.resolve(
                projectRoot, buffer.getPath(), com.editora.lsp.LspServerRegistry.rootMarkersFor(buffer.getLanguage()));
    }

    /** The accept hook for a completion item: resolve it + apply any additional edits (a TypeScript
     *  auto-import's {@code import} line). Returns null when the item can't carry extra edits. */
    private Runnable autoImportAccept(EditorBuffer buffer, org.eclipse.lsp4j.CompletionItem item) {
        if (!com.editora.lsp.CompletionMapper.mayHaveAdditionalEdits(item)) {
            return null;
        }
        return () -> {
            if (buffer.getPath() != null) {
                lspManager.resolveCompletion(buffer.getPath(), item, buffer::applyLspEdits);
            }
        };
    }

    /** Diagnostics callback from the manager (already on the FX thread): store + paint + refresh Problems. */
    private void onLspDiagnostics(Path file, java.util.List<com.editora.editor.LspDiagnostic> diagnostics) {
        if (!lspEnabled()) {
            return;
        }
        statusBar.setLspLoading(false); // diagnostics flowing ⇒ the server is up; stop the loading bar
        // A language server publishes diagnostics project-wide (jdtls especially), but we only surface
        // problems for files actually OPEN in Editora — otherwise the Problems window fills with whole-
        // workspace noise from a single open file.
        Tab tab = tabForPath(file);
        EditorBuffer buffer = tab == null ? null : bufferOf(tab);
        // A jdtls whose compliance predates JDK 25 flags a compact source file's implicit class as a
        // preview/unsupported feature — pure noise for a file the JDK 25 launcher runs fine. Drop just
        // those complaints (real errors in the file still surface).
        if (buffer != null && "java".equals(buffer.getLanguage()) && buffer.isRunnable()) {
            diagnostics = diagnostics.stream()
                    .filter(d -> !isCompactSourceNoise(d.message()))
                    .toList();
        }
        if (buffer != null) {
            buffer.setLspDiagnostics(diagnostics);
        }
        if (tab == null || diagnostics.isEmpty()) {
            lspProblems.remove(file);
        } else {
            lspProblems.put(file, diagnostics);
        }
        refreshProblems();
    }

    /** Whether an LSP diagnostic on a compact source file is implicit-class noise from a server whose
     *  Java compliance predates JDK 25 (JEP 512 final). Pure — tested. */
    static boolean isCompactSourceNoise(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("implicitly declared class") // JDK 23+ JDT wording (incl. preview gating)
                || m.contains("unnamed class") // the JDK 21/22 preview-era wording
                || m.contains("instance main method"); // "...Instance Main Methods is a preview feature"
    }

    private void refreshProblems() {
        if (problemsPanel != null) {
            problemsPanel.setProblems(lspProblems);
        }
    }

    /** Updates the status-bar LSP segment: "LSP: &lt;server&gt;" when the active file is managed, else hidden. */
    private void updateLspStatusBar() {
        EditorBuffer b = activeBuffer();
        boolean managed = b != null && b.getPath() != null && lspManager.isManaged(b.getPath());
        String serverId = managed ? com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage()) : null;
        statusBar.setLsp(serverId != null ? serverLabel(serverId) : null);
    }

    /** The short server name shown in the status bar — the configured command's basename (e.g. jdtls,
     *  typescript-language-server) for {@code serverId}. */
    private String serverLabel(String serverId) {
        String configured = serverCommand(serverId);
        String cmd = configured == null || configured.isBlank()
                ? com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId)
                : configured;
        java.util.List<String> toks = com.editora.lsp.LspServerRegistry.tokenize(cmd);
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
    private void onLspServerStatus(String type, String message) {
        if (!lspEnabled()) {
            return;
        }
        if (message != null && !message.isBlank()) {
            setStatus(tr("status.lsp.server", message));
        }
        if (type != null) {
            String t = type.toLowerCase(java.util.Locale.ROOT);
            if (t.contains("ready") || t.contains("error")) {
                statusBar.setLspLoading(false); // server finished starting (or failed)
            }
            if (t.contains("ready")) {
                // A server just finished initializing — its capabilities are now known. Push completion
                // trigger characters to every open managed buffer and pull initial diagnostics (the
                // pull-model servers don't publish until asked).
                for (Tab tab : tabPane.getTabs()) {
                    EditorBuffer b = bufferOf(tab);
                    if (b != null && b.getPath() != null && lspManager.isManaged(b.getPath())) {
                        b.setLspTriggerChars(lspManager.triggerCharacters(b.getPath()));
                        lspManager.pullDiagnostics(b.getPath());
                    }
                }
            }
        }
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
        applyLspSupport();
        if (settingsWindow != null) {
            settingsWindow.syncLspCheck();
        }
        setStatus(tr("status.toggle.lsp", tr(s.isLspSupport() ? "common.on" : "common.off")));
    }

    private void restartLspServers() {
        lspManager.shutdownAll();
        lspProblems.clear();
        refreshProblems();
        applyLspGating();
        setStatus(tr("status.lsp.restarted"));
    }

    /** Notifies the server of a save (didSave) for a managed file + refreshes pull-model diagnostics. */
    private void notifyLspSaved(EditorBuffer buffer) {
        if (buffer != null && buffer.getPath() != null && lspManager.isManaged(buffer.getPath())) {
            lspManager.saveDocument(buffer.getPath());
            lspManager.pullDiagnostics(buffer.getPath()); // no-op for push-only servers
        }
    }

    /** Opens {@code file} (if needed) and moves the caret to a 0-based LSP line/column. */
    private void openAndGoto(Path file, int line0, int col0) {
        openPath(file);
        Platform.runLater(() -> gotoInFile(file, line0 + 1, col0 + 1));
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
            if (lastRunFile != null && lastRunFile.getParent() != null) {
                Path sibling = lastRunFile.getParent().resolve(name);
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

    /** The active buffer if it is LSP-managed, reporting + returning null otherwise. */
    private EditorBuffer activeLspBuffer() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || !lspManager.isManaged(b.getPath())) {
            setStatus(tr("status.lsp.unavailable"));
            return null;
        }
        return b;
    }

    private void lspGotoDefinition() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.definition(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (targets.isEmpty()) {
                setStatus(tr("status.lsp.noDefinition"));
            } else {
                com.editora.lsp.LspManager.Target t = targets.get(0);
                openAndGoto(t.file(), t.line(), t.character());
            }
        });
    }

    private void lspFindReferences() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.references(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (targets.isEmpty()) {
                setStatus(tr("status.lsp.noReferences"));
                return;
            }
            QuickOpen<com.editora.lsp.LspManager.Target> picker = new QuickOpen<>(
                    tr("lsp.references.title"),
                    tr("lsp.references.prompt"),
                    () -> targets,
                    t -> t.file().getFileName() + ":" + (t.line() + 1),
                    t -> t.file().toString(),
                    t -> openAndGoto(t.file(), t.line(), t.character()));
            picker.setOverlayHost(overlayHost);
            picker.show(stage);
        });
    }

    private void lspShowHover() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.hover(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), text -> {
            if (text == null || text.isBlank()) {
                setStatus(tr("status.lsp.noHover"));
            } else {
                showHoverPopup(area, text);
            }
        });
    }

    /**
     * Shows LSP hover markdown in a dismissable popup at the caret (rendered via the Markdown renderer).
     * Closes on Escape, a click elsewhere (auto-hide), caret movement, scrolling, or another hover.
     */
    private void showHoverPopup(CodeArea area, String markdown) {
        hideHoverPopup();
        javafx.scene.Node content;
        try {
            javafx.scene.Node rendered = com.editora.editor.MarkdownRenderer.renderDocument(
                    com.editora.editor.MarkdownRenderer.parseToDocument(markdown), null);
            content = rendered;
        } catch (RuntimeException e) {
            javafx.scene.control.Label label = new javafx.scene.control.Label(markdown);
            label.setWrapText(true);
            content = label;
        }
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(content);
        box.getStyleClass().add("lsp-hover-popup");
        box.setMaxWidth(560);
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true); // click outside / focus loss dismisses it
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        lspHoverPopup = popup;

        // Dismiss on Escape, caret movement, or scroll — all detached again when the popup hides.
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> esc = ev -> {
            if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                hideHoverPopup();
                ev.consume();
            }
        };
        javafx.beans.value.ChangeListener<Object> dismiss = (o, a, b) -> hideHoverPopup();
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(dismiss);
        area.estimatedScrollYProperty().addListener(dismiss);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, esc);
            area.caretPositionProperty().removeListener(dismiss);
            area.estimatedScrollYProperty().removeListener(dismiss);
            if (lspHoverPopup == popup) {
                lspHoverPopup = null;
            }
        });

        var bounds = area.getCaretBounds().orElse(null);
        if (bounds != null) {
            popup.show(area, bounds.getMinX(), bounds.getMaxY());
        } else {
            popup.show(area, 0, 0);
        }
    }

    /** Hides the LSP hover popup if one is showing. */
    private void hideHoverPopup() {
        if (lspHoverPopup != null) {
            lspHoverPopup.hide();
            lspHoverPopup = null;
        }
    }

    /** Whether the Personal Notes feature is enabled in Settings (default off). */
    private boolean notesEnabled() {
        return config.getSettings().isNotesSupport();
    }

    /** Runs {@code action} only when Personal Notes is enabled; otherwise reports it (no-op command/key). */
    private void ifNotes(Runnable action) {
        if (notesEnabled()) {
            action.run();
        } else {
            setStatus(tr("statusbar.tip.notesDisabled"));
        }
    }

    /**
     * Reconciles all Personal Notes UI with the "Enable Personal Notes" setting (default off). When off:
     * the Notes tool window is hidden and the editor "Add Note" menu items + commands/keybindings no-op.
     * On the off→on transition each open buffer loads its saved notes from {@code notes.json}. Indicator
     * visibility itself is applied per-buffer in {@link #applyViewSettings} (gated by both flags). Runs at
     * startup and on every settings apply (mirrors {@link #applyGitSupport}).
     */
    private void applyNotesSupport() {
        boolean on = notesEnabled();
        toolWindows.setAvailable(notesToolWindow, on);
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null) {
                b.setNotesEnabled(on);
            }
        }
        if (on && !notesSupportApplied) {
            // off→on: populate open buffers' notes (restoreNotes early-returns while disabled).
            for (Tab tab : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b != null) {
                    restoreNotes(b);
                }
            }
            if (notesPanel != null) {
                notesPanel.refresh();
            }
        }
        notesSupportApplied = on;
    }

    private void refreshGit() {
        if (gitService == null || !gitEnabled()) {
            return;
        }
        EditorBuffer b = activeBuffer();
        Path file = b == null ? null : b.getPath();
        Path context = gitContextPath();
        // Git shells out to a local process — a remote (SFTP) context has no local repo.
        if (context != null && !com.editora.vfs.Vfs.isLocal(context)) {
            applyGitState(com.editora.git.GitService.RepoState.NONE);
            return;
        }
        // Only diff a real, non-huge file (huge files disable the gutter anyway).
        Path diffFile = (file != null && b != null && !b.isLargeFile()) ? file : null;
        gitService.refresh(context, diffFile, this::applyGitState);
    }

    /** Applies a completed Git refresh to the status bar, tool window, and active buffer's gutter. */
    private void applyGitState(com.editora.git.GitService.RepoState state) {
        currentRepoRoot = state.root();
        EditorBuffer b = activeBuffer();
        // The Commit tool window is only available inside a Git repo (transient, doesn't touch the
        // user's show/hide preference).
        toolWindows.setAvailable(commitToolWindow, state.isRepo());
        toolWindows.setAvailable(gitLogToolWindow, state.isRepo());
        if (!state.isRepo()) {
            currentBranchName = "";
            currentUpstream = "";
            statusBar.setGitBranch(null, 0, 0);
            gitPanel.setStatus(null);
            if (b != null) {
                b.setChangeBars(null);
                b.setBlame(null);
            }
            return;
        }
        var status = state.status();
        currentBranchName = status.branch();
        currentUpstream = status.upstream();
        statusBar.setGitBranch(status.branch(), status.ahead(), status.behind());
        gitPanel.setStatus(status);
        if (b != null && b.getPath() != null) {
            java.util.Map<Integer, String> classes = new java.util.HashMap<>();
            state.changes().forEach((line, type) -> classes.put(line, type.cssClass()));
            // An empty map still marks the buffer as tracked (reserves the slot); hunk text feeds the
            // change-bar hover tooltip.
            b.setChangeBars(classes, state.hunks());
        }
        refreshBlame(b); // inline blame for the active file (no-op + clears when blame is off)
    }

    /**
     * Refreshes the whole Git UI after a mutation (commit/stage/discard/checkout/pull/push): the status
     * bar, the Commit tool window, and the active gutter (via {@link #refreshGit()}), plus the gutter of
     * <em>every other open buffer in the same repo</em> (so committing clears bars on background tabs
     * too, not just the visible one). Off the UI thread; bounded by the number of open tabs and only
     * runs on user-initiated git actions, so it's off the hot paths.
     */
    private void afterGitMutation() {
        refreshGit(); // status bar + tool window + active buffer's gutter
        Path root = currentRepoRoot;
        if (root == null) {
            return;
        }
        Path absRoot = root.toAbsolutePath();
        EditorBuffer active = activeBuffer();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buf = bufferOf(tab);
            if (buf == null || buf == active || buf.getPath() == null || buf.isLargeFile()) {
                continue; // active buffer is handled by refreshGit(); skip non-file/huge buffers
            }
            Path p = buf.getPath().toAbsolutePath();
            if (!p.startsWith(absRoot)) {
                continue; // only files inside the affected repo
            }
            gitService.diff(root, p, diff -> {
                java.util.Map<Integer, String> classes = new java.util.HashMap<>();
                diff.changes().forEach((line, type) -> classes.put(line, type.cssClass()));
                buf.setChangeBars(classes, diff.hunks());
            });
        }
        refreshOpenDiffs(); // a commit/stage/checkout changes HEAD/index/working → re-diff open diff tabs
    }

    /** Runs a Git mutation in the active repo, reports the outcome, and refreshes. */
    // --- Diff viewer ----------------------------------------------------------------------------

    /** A re-fetchable side of a diff: delivers the current text (a git blob or the working copy) to a
     *  callback. Re-invoked on refresh so the diff tracks on-disk / git changes. */
    @FunctionalInterface
    private interface DiffSide {
        void fetch(java.util.function.Consumer<String> onText);
    }

    /**
     * Opens a diff tab comparing two re-fetchable sides (diff computed off-thread); reports identical /
     * too-large. {@code headerLeft}/{@code headerRight} label the panes (e.g. "HEAD" / "Working tree");
     * the clean {@code leftName}/{@code rightName} (real file names) drive grammar + patch labels. The
     * pane's refresher re-fetches both sides and re-renders only when the content changed.
     */
    private void openDiff(
            String title,
            String headerLeft,
            String headerRight,
            String leftName,
            String rightName,
            DiffSide leftSide,
            DiffSide rightSide,
            DiffViewerPane.EditableSide editableSide,
            Path target) {
        leftSide.fetch(leftText -> rightSide.fetch(rightText -> diffService.compute(leftText, rightText, model -> {
            if (model == null) {
                setStatus(tr("status.diff.tooLarge"));
                return;
            }
            DiffViewerPane pane = new DiffViewerPane(
                    title,
                    headerLeft,
                    headerRight,
                    leftName,
                    rightName,
                    leftText,
                    rightText,
                    model,
                    config.getSettings().getFontFamily(),
                    config.getSettings().getFontSize(),
                    config.getSettings().isShowLineNumbers(),
                    target == null ? null : target.toString());
            pane.setOnExportPatch(this::exportPatch);
            // "Apply change" arrows write the hunk into the local/editable file (via an undoable
            // editor buffer), with Undo + Save acting on that buffer.
            if (editableSide != DiffViewerPane.EditableSide.NONE && target != null) {
                pane.setEditable(
                        editableSide,
                        newText -> applyToLocal(target, newText),
                        () -> undoLocal(target),
                        () -> saveLocal(target));
            }
            // Refresh: re-fetch both sides; re-render only if the content actually changed
            // (so a focus-regain with no change keeps the view + scroll position).
            pane.setRefresher(() -> leftSide.fetch(l -> rightSide.fetch(r -> {
                if (pane.matches(l, r)) {
                    return;
                }
                diffService.compute(l, r, m -> {
                    if (m != null) {
                        pane.updateContent(l, r, m);
                    }
                });
            })));
            addContentTab(pane, true);
            if (model.isEmpty()) {
                setStatus(tr("status.diff.identical"));
            }
        })));
    }

    /** Re-fetches every open diff tab's sides (run on window focus-regain + after a git mutation), so a
     *  file changed on disk or by a git command is reflected. Each pane skips the rebuild when unchanged. */
    private void refreshOpenDiffs() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() instanceof DiffViewerPane dp) {
                dp.refresh();
            }
        }
    }

    /** Writes a diff "apply change" result into the local file {@code target} via an undoable editor
     *  buffer (opened in the background if not already open), marking it dirty — Undo reverts the apply,
     *  Save persists it. Then re-diffs every open diff tab so the applied hunk disappears. */
    private void applyToLocal(Path target, String newText) {
        EditorBuffer b = bufferForApply(target);
        if (b == null) {
            setStatus(tr("status.diff.applyFailed", target.getFileName()));
            return;
        }
        b.getArea().replaceText(newText);
        setStatus(tr("status.diff.applied"));
        refreshOpenDiffs();
    }

    /** Undoes the last applied change on {@code target}'s buffer (the buffer's own undo). */
    private void undoLocal(Path target) {
        EditorBuffer b = openBufferFor(target);
        if (b != null && b.getArea().isUndoAvailable()) {
            b.getArea().undo();
            refreshOpenDiffs();
        }
    }

    /** Saves {@code target}'s buffer (persisting the applied changes) and re-diffs. */
    private void saveLocal(Path target) {
        EditorBuffer b = openBufferFor(target);
        if (b == null) {
            return;
        }
        if (save(b)) {
            setStatus(tr("status.diff.saved", target.getFileName()));
            refreshOpenDiffs();
        }
    }

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

    /** The editable buffer to apply a diff hunk into: the open buffer for {@code target}, else a fresh
     *  one opened in the background (no tab switch, so the diff stays focused). */
    private EditorBuffer bufferForApply(Path target) {
        EditorBuffer open = openBufferFor(target);
        if (open != null) {
            return open;
        }
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(target);
            loadInto(buffer, target);
            addBuffer(buffer, false); // background: keep the diff tab focused
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    /** Diff the active file's working copy against its committed (HEAD) version. */
    private void diffActiveVsHead() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        String rel = com.editora.git.GitService.repoRelative(currentRepoRoot, b.getPath());
        if (rel == null) {
            setStatus(tr("status.diff.notInRepo"));
            return;
        }
        Path path = b.getPath();
        String name = path.getFileName().toString();
        openDiff(
                tr("diff.title.vsHead", name),
                tr("diff.side.head"),
                tr("diff.side.working"),
                name,
                name,
                cb -> gitService.show(currentRepoRoot, "HEAD:" + rel, cb),
                cb -> cb.accept(worktreeText(path)),
                DiffViewerPane.EditableSide.RIGHT,
                path);
    }

    /** Pick a second file and diff it against the active file. */
    private void compareActiveWithFile() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        Path basePath = b.getPath();
        String leftName = basePath.getFileName().toString();
        FileFinder picker = new FileFinder(
                this::finderStartDir,
                chosen -> {
                    String rightName = chosen.getFileName().toString();
                    // Both sides re-fetch via worktreeText (open buffer's live text if open, else disk), so the
                    // diff tracks either file changing on disk.
                    openDiff(
                            tr("diff.title.compare", leftName, rightName),
                            leftName,
                            rightName,
                            leftName,
                            rightName,
                            cb -> cb.accept(worktreeText(basePath)),
                            cb -> cb.accept(worktreeText(chosen)),
                            DiffViewerPane.EditableSide.LEFT,
                            basePath);
                },
                false,
                tr("diff.compareTitle"));
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Diff the active file against a commit chosen from its history. */
    private void diffActiveVsCommit() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        String rel = com.editora.git.GitService.repoRelative(currentRepoRoot, b.getPath());
        if (rel == null) {
            setStatus(tr("status.diff.notInRepo"));
            return;
        }
        Path path = b.getPath();
        String name = path.getFileName().toString();
        gitService.log(currentRepoRoot, path, 80, commits -> {
            if (commits.isEmpty()) {
                setStatus(tr("status.diff.noHistory"));
                return;
            }
            QuickOpen<com.editora.git.GitService.Commit> picker = new QuickOpen<>(
                    tr("diff.commitPickerTitle"),
                    tr("diff.commitPickerPrompt"),
                    () -> commits,
                    c -> c.shortHash() + "  " + c.subject(),
                    c -> c.date() + " · " + c.author(),
                    c -> c.shortHash() + " " + c.subject() + " " + c.author() + " " + c.date(),
                    chosen -> openDiff(
                            tr("diff.title.vsCommit", name, chosen.shortHash()),
                            chosen.shortHash(),
                            tr("diff.side.working"),
                            name,
                            name,
                            cb -> gitService.show(currentRepoRoot, chosen.hash() + ":" + rel, cb),
                            cb -> cb.accept(worktreeText(path)),
                            DiffViewerPane.EditableSide.RIGHT,
                            path));
            picker.setOverlayHost(overlayHost);
            picker.show(stage);
        });
    }

    /** Diff a Git-panel file row: staged → index↔HEAD, unstaged → worktree↔index. */
    private void diffGitPanelFile(String repoRel, boolean staged) {
        if (currentRepoRoot == null) {
            return;
        }
        java.nio.file.Path abs = currentRepoRoot.resolve(repoRel);
        String name = abs.getFileName().toString();
        if (staged) {
            // index↔HEAD: neither side is the working file, so no "apply" (read-only diff).
            openDiff(
                    tr("diff.title.staged", name),
                    tr("diff.side.head"),
                    tr("diff.side.staged"),
                    name,
                    name,
                    cb -> gitService.show(currentRepoRoot, "HEAD:" + repoRel, cb),
                    cb -> gitService.show(currentRepoRoot, ":" + repoRel, cb),
                    DiffViewerPane.EditableSide.NONE,
                    null);
        } else {
            openDiff(
                    tr("diff.title.unstaged", name),
                    tr("diff.side.staged"),
                    tr("diff.side.working"),
                    name,
                    name,
                    cb -> gitService.show(currentRepoRoot, ":" + repoRel, cb),
                    cb -> cb.accept(worktreeText(abs)),
                    DiffViewerPane.EditableSide.RIGHT,
                    abs);
        }
    }

    /** The current working-tree text of {@code abs}: an open buffer's (incl. unsaved edits) if open,
     *  else the file on disk ("" when unreadable / deleted). */
    private String worktreeText(java.nio.file.Path abs) {
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b.getPath() != null && canonicalPath(b.getPath()).equals(canonicalPath(abs))) {
                return b.text();
            }
        }
        try {
            return java.nio.file.Files.exists(abs) ? java.nio.file.Files.readString(abs) : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** Saves a unified-diff patch (the diff viewer's export action) via a file chooser. */
    private void exportPatch(String patch) {
        if (patch == null || patch.isEmpty()) {
            setStatus(tr("status.diff.identical"));
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(tr("diff.exportPatch"));
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Patch (*.patch)", "*.patch"));
        fc.setInitialFileName("changes.patch");
        java.io.File f = fc.showSaveDialog(stage);
        if (f == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(f.toPath(), patch);
            setStatus(tr("status.diff.patchSaved", f.getName()));
        } catch (java.io.IOException e) {
            setStatus(tr("status.diff.patchFailed", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** Opens the merge-conflict resolution view for the active buffer (if it has conflict markers). */
    private void resolveConflicts() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        String text = b.text();
        if (!com.editora.diff.ConflictParser.hasConflictMarkers(text)) {
            setStatus(tr("status.merge.noConflicts"));
            return;
        }
        java.util.List<String> raw =
                java.util.List.of(text.replace("\r\n", "\n").split("\n", -1));
        com.editora.diff.ConflictParser.ConflictFile cf = com.editora.diff.ConflictParser.parse(raw);
        String name =
                b.getPath() == null ? b.getTitle() : b.getPath().getFileName().toString();
        MergeViewerPane pane = new MergeViewerPane(
                tr("merge.title", name),
                cf,
                config.getSettings().getFontFamily(),
                config.getSettings().getFontSize(),
                resolvedLines -> {
                    b.getArea().replaceText(String.join("\n", resolvedLines));
                    setStatus(tr("status.merge.applied"));
                });
        addContentTab(pane, true);
    }

    private void gitOp(String successMessage, String... args) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        gitService.run(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        setStatus(successMessage);
                    } else {
                        gitError("Git command failed", r.message());
                    }
                    afterGitMutation();
                },
                args);
    }

    /** Confirms then discards a file's changes (or deletes an untracked file) — destructive. */
    private void discardChanges(String path, boolean untracked) {
        if (currentRepoRoot == null) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                untracked ? tr("dialog.discard.untracked", path) : tr("dialog.discard.tracked", path),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.discard.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (untracked) {
            gitOp("Deleted " + path, "clean", "-f", "--", path);
        } else {
            gitOp("Discarded changes to " + path, "checkout", "--", path);
        }
        // The on-disk file changed under any open buffer for it — re-check so it reloads if needed.
        Platform.runLater(this::checkExternalChanges);
    }

    private void gitCommit(String message) {
        if (currentRepoRoot == null) {
            return;
        }
        gitService.run(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        gitPanel.clearMessage();
                        setStatus(tr("status.committed"));
                    } else {
                        gitError("Commit failed", r.message());
                    }
                    afterGitMutation();
                },
                "commit",
                "-m",
                message);
    }

    private void checkoutBranch(String name) {
        if (currentRepoRoot == null || name == null || name.isBlank()) {
            return;
        }
        gitService.run(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        setStatus(tr("status.switchedBranch", name));
                    } else {
                        gitError("Couldn't switch to " + name, r.message());
                    }
                    afterGitMutation();
                    reloadAllFromDiskSilently();
                },
                "checkout",
                name);
    }

    /** Checks out a remote branch (e.g. {@code origin/foo}), creating a local tracking branch. */
    private void checkoutRemoteBranch(String remote) {
        if (currentRepoRoot == null || remote == null || remote.isBlank()) {
            return;
        }
        gitService.run(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        setStatus(tr("status.checkedOut", remote));
                    } else {
                        gitError("Couldn't check out " + remote, r.message());
                    }
                    afterGitMutation();
                    reloadAllFromDiskSilently();
                },
                "checkout",
                "--track",
                remote);
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
        if (currentRepoRoot == null) {
            // Not under version control: the dropdown offers only "Clone Git repository…".
            branchPopup.showNoVcs(stage, statusBar.gitSegmentNode(), this::gitClone);
            return;
        }
        gitService.branches(currentRepoRoot, branches -> {
            List<BranchPopup.MenuAction> actions = List.of(
                    new BranchPopup.MenuAction(tr("branch.newBranch"), "", this::newBranch),
                    new BranchPopup.MenuAction(
                            tr("branch.pull"), "", () -> gitSync(tr("gitlabel.pull"), "pull", "--ff-only")),
                    new BranchPopup.MenuAction(
                            tr("branch.fetch"), "", () -> gitSync(tr("gitlabel.fetch"), "fetch", "--all")),
                    new BranchPopup.MenuAction(tr("branch.push"), "", this::gitPush),
                    new BranchPopup.MenuAction(tr("branch.stash"), "", this::gitStash),
                    new BranchPopup.MenuAction(tr("branch.unstash"), "", this::gitUnstash),
                    new BranchPopup.MenuAction(tr("branch.commit"), "C-x g", this::gitCommitFocus));
            branchPopup.show(
                    stage,
                    statusBar.gitSegmentNode(),
                    currentBranchName,
                    branches.local(),
                    branches.remote(),
                    branches.remoteUrl(),
                    actions,
                    this::checkoutBranch,
                    this::checkoutRemoteBranch);
        });
    }

    private void newBranch() {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        promptText(tr("dialog.newBranch.title"), tr("dialog.newBranch.content"), "", input -> {
            String name = input.strip();
            if (name.isEmpty()) {
                return;
            }
            gitService.run(
                    currentRepoRoot,
                    r -> {
                        if (r.ok()) {
                            setStatus(tr("status.createdBranch", name));
                        } else {
                            gitError("Couldn't create branch " + name, r.message());
                        }
                        afterGitMutation();
                    },
                    "checkout",
                    "-b",
                    name);
        });
    }

    private void gitSync(String label, String... args) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        setStatus(tr("status.gitRunning", label));
        gitService.runNetwork(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        setStatus(tr("status.gitDone", label));
                        reloadAllFromDiskSilently();
                    } else {
                        gitError(label + " failed", r.message());
                    }
                    afterGitMutation();
                },
                args);
    }

    /**
     * Pushes the current branch. A brand-new branch has no upstream, so {@code git push} alone fails;
     * in that case we push with {@code --set-upstream origin <branch>} so the first push "just works"
     * (matching {@code push.autoSetupRemote}). Subsequent pushes use the tracked upstream.
     */
    private void gitPush() {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        if (currentUpstream.isBlank() && !currentBranchName.isBlank()) {
            gitSync(tr("gitlabel.push"), "push", "--set-upstream", "origin", currentBranchName);
        } else {
            gitSync(tr("gitlabel.push"), "push");
        }
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
                if (currentRepoRoot != null) {
                    gitService.commitFiles(currentRepoRoot, hash, gitLogPanel::setCommitFiles);
                }
            }

            @Override
            public void openFileDiff(String hash, String repoRel) {
                diffCommitFile(hash, repoRel);
            }

            @Override
            public void copyHash(String hash) {
                ClipboardContent content = new ClipboardContent();
                content.putString(hash);
                Clipboard.getSystemClipboard().setContent(content);
                setStatus(tr("status.git.copiedHash", shortHash(hash)));
            }

            @Override
            public void checkout(String hash) {
                gitMutate(tr("status.git.checkedOut", shortHash(hash)), "checkout", hash);
            }

            @Override
            public void reset(String hash, String mode) {
                gitMutate(tr("status.git.reset", mode, shortHash(hash)), "reset", "--" + mode, hash);
                Platform.runLater(MainController.this::checkExternalChanges);
            }

            @Override
            public void revert(String hash) {
                gitMutate(tr("status.git.reverted", shortHash(hash)), "revert", "--no-edit", hash);
            }

            @Override
            public void cherryPick(String hash) {
                gitMutate(tr("status.git.cherryPicked", shortHash(hash)), "cherry-pick", hash);
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

    /** A short 7-char hash for status messages. */
    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(7, hash.length()));
    }

    /** Opens the Git Log tool window showing the whole-repo history. */
    private void showGitLog() {
        loadGitLog(null);
        toolWindows.open(gitLogToolWindow);
    }

    /** Opens the Git Log filtered to the active file's history. */
    private void showFileHistory() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            setStatus(tr("status.diff.noFile"));
            return;
        }
        loadGitLog(b.getPath());
        toolWindows.open(gitLogToolWindow);
    }

    /** Loads up to 200 commits (whole-repo when {@code file} is null, else that file's history). */
    private void loadGitLog(Path file) {
        gitLogFilter = file;
        if (currentRepoRoot == null) {
            gitLogPanel.setLog(List.of(), null);
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        String name = file != null ? file.getFileName().toString() : null;
        gitService.log(currentRepoRoot, file, 200, commits -> gitLogPanel.setLog(commits, name));
    }

    /** Read-only diff of one file at a commit vs its first parent (from the Git Log file list). */
    private void diffCommitFile(String hash, String repoRel) {
        if (currentRepoRoot == null) {
            return;
        }
        String name = repoRel.substring(repoRel.lastIndexOf('/') + 1);
        openDiff(
                tr("diff.title.commitFile", name, shortHash(hash)),
                tr("diff.side.parent"),
                tr("diff.title.vsCommitShort", shortHash(hash)),
                name,
                name,
                cb -> gitService.show(currentRepoRoot, hash + "~1:" + repoRel, cb),
                cb -> gitService.show(currentRepoRoot, hash + ":" + repoRel, cb),
                DiffViewerPane.EditableSide.NONE,
                null);
    }

    /** A history mutation (checkout/reset/revert/cherry-pick/branch): run, report, refresh + reload log. */
    private void gitMutate(String successMessage, String... args) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        gitService.run(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        setStatus(successMessage);
                    } else {
                        gitError(tr("status.git.opFailed"), r.message());
                    }
                    afterGitMutation();
                    loadGitLog(gitLogFilter); // HEAD/refs moved → refresh the log
                },
                args);
    }

    // --- Inline blame ----------------------------------------------------------------------------

    /** Whether inline blame is effectively on (Git enabled + the setting + not Simple mode). */
    private boolean gitBlameEnabled() {
        return gitEnabled() && config.getSettings().isGitBlameInline();
    }

    /** Pushes blame to the active buffer (and clears it everywhere else); runs on init / settings apply /
     *  tab switch / git mutation. Only the focused buffer shows blame (caret-line annotation). */
    private void applyGitBlame() {
        EditorBuffer active = activeBuffer();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer b = bufferOf(tab);
            if (b != null && b != active) {
                b.setBlame(null);
            }
        }
        refreshBlame(active);
    }

    /** Fetches blame for {@code b} off-thread and pushes formatted annotations (or clears when ineligible). */
    private void refreshBlame(EditorBuffer b) {
        if (b == null) {
            return;
        }
        if (!gitBlameEnabled()
                || b.getPath() == null
                || b.isLargeFile()
                || !isLocalBuffer(b)
                || currentRepoRoot == null) {
            b.setBlame(null);
            return;
        }
        Path file = b.getPath();
        gitService.blame(currentRepoRoot, file, lines -> {
            if (activeBuffer() != b) {
                return; // the user switched tabs while blame ran
            }
            b.setBlame(toBlameInfos(lines));
        });
    }

    private List<com.editora.editor.BlameInfo> toBlameInfos(List<com.editora.git.BlameParser.BlameLine> lines) {
        long now = System.currentTimeMillis() / 1000L;
        List<com.editora.editor.BlameInfo> out = new java.util.ArrayList<>(lines.size());
        for (com.editora.git.BlameParser.BlameLine bl : lines) {
            String text = bl.uncommitted()
                    ? tr("blame.uncommitted")
                    : tr("blame.annotation", bl.author(), relativeTimeLabel(bl.epochSeconds(), now), bl.summary());
            out.add(new com.editora.editor.BlameInfo(text, bl.uncommitted() ? "" : bl.hash()));
        }
        return out;
    }

    /** Localized "N days ago"-style label from the pure {@link com.editora.git.RelativeTime} bucketing. */
    private String relativeTimeLabel(long epochSeconds, long nowSeconds) {
        com.editora.git.RelativeTime.Span span = com.editora.git.RelativeTime.of(epochSeconds, nowSeconds);
        long v = span.value();
        return switch (span.unit()) {
            case NOW -> tr("blame.now");
            case MINUTES -> tr("blame.minutesAgo", v);
            case HOURS -> tr("blame.hoursAgo", v);
            case DAYS -> tr("blame.daysAgo", v);
            case WEEKS -> tr("blame.weeksAgo", v);
            case MONTHS -> tr("blame.monthsAgo", v);
            case YEARS -> tr("blame.yearsAgo", v);
        };
    }

    // --- Local File History --------------------------------------------------------------------

    /**
     * Reconciles the Local File History UI with its setting (mirrors {@link #applyGitSupport}): updates the
     * tool window's availability for the active file and refreshes its revision list. Runs at startup and on
     * every settings apply.
     */
    private void applyLocalHistory() {
        refreshLocalHistory();
    }

    /** Sets the history tool window's availability (local file + feature on) and reloads its list. */
    private void refreshLocalHistory() {
        if (fileHistoryToolWindow == null) {
            return;
        }
        EditorBuffer b = activeBuffer();
        boolean available = localHistoryEnabled() && b != null && b.getPath() != null && isLocalBuffer(b);
        toolWindows.setAvailable(fileHistoryToolWindow, available);
        if (available) {
            List<HistoryRevision> revs = config.getHistory().getOrDefault(historyKey(b.getPath()), List.of());
            fileHistoryPanel.setRevisions(revs, b.getPath().getFileName().toString());
        } else {
            fileHistoryPanel.setRevisions(List.of(), null);
        }
    }

    /** The per-file key used in the history bucket (absolute path string). */
    private static String historyKey(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    /**
     * Records a snapshot of {@code buffer}'s content (captured here on the FX thread) off-thread, then folds
     * the pruned result back into the per-project history bucket + persists + GCs stale blobs. A no-op when
     * the feature is off, the buffer is remote/untitled, or the content matches the newest revision.
     */
    private void recordHistory(EditorBuffer buffer, String reason) {
        if (!localHistoryEnabled() || buffer == null || buffer.getPath() == null || !isLocalBuffer(buffer)) {
            return;
        }
        Path file = buffer.getPath();
        String content = buffer.getContent();
        String key = historyKey(file);
        List<HistoryRevision> existing = config.getHistory().getOrDefault(key, List.of());
        Settings s = config.getSettings();
        long maxAgeMillis = s.getHistoryMaxAgeDays() > 0 ? s.getHistoryMaxAgeDays() * 86_400_000L : 0;
        var policy = new com.editora.history.HistoryRetention.RetentionPolicy(
                s.getHistoryMaxPerFile(), maxAgeMillis, (long) Math.max(0, s.getHistoryMaxTotalMb()) * 1024L * 1024L);
        long now = System.currentTimeMillis();
        historyService.snapshot(file, content, reason, existing, policy, now, updated -> {
            java.util.Map<String, List<HistoryRevision>> bucket = config.getHistory();
            bucket.put(key, updated);
            // Enforce the per-project byte budget across the whole bucket, then persist + GC.
            var trimmed =
                    com.editora.history.HistoryRetention.enforceProjectBudget(bucket, policy.maxTotalBytesPerProject());
            bucket.clear();
            bucket.putAll(trimmed);
            config.saveHistory();
            historyService.gc(com.editora.history.HistoryRetention.liveHashes(config.getHistoryByProject()));
            EditorBuffer active = activeBuffer();
            if (active != null
                    && active.getPath() != null
                    && historyKey(active.getPath()).equals(key)) {
                refreshLocalHistory();
            }
        });
    }

    private FileHistoryPanel.Actions historyActions() {
        return new FileHistoryPanel.Actions() {
            @Override
            public void refresh() {
                refreshLocalHistory();
            }

            @Override
            public void openDiff(HistoryRevision revision) {
                openLocalHistoryDiff(revision);
            }

            @Override
            public void restore(HistoryRevision revision) {
                restoreHistory(revision);
            }
        };
    }

    /** Opens the Local File History tool window for the active file. */
    private void showLocalHistory() {
        if (!localHistoryEnabled()) {
            setStatus(tr("status.history.disabled"));
            return;
        }
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || !isLocalBuffer(b)) {
            setStatus(tr("status.history.noFile"));
            return;
        }
        refreshLocalHistory();
        toolWindows.open(fileHistoryToolWindow);
    }

    /** Opens a read-only diff of {@code revision} (left) vs the active file's current text (right). */
    private void openLocalHistoryDiff(HistoryRevision revision) {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || revision == null) {
            return;
        }
        Path target = b.getPath();
        String name = target.getFileName().toString();
        openDiff(
                tr("history.diff.title", name),
                tr("history.side.snapshot"),
                tr("history.side.current"),
                name,
                name,
                cb -> historyService.content(revision, text -> cb.accept(text == null ? "" : text)),
                cb -> cb.accept(currentTextOf(target)),
                DiffViewerPane.EditableSide.NONE,
                target);
    }

    /** Restores {@code revision}'s content into the active file via an undoable whole-file replace. */
    private void restoreHistory(HistoryRevision revision) {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || revision == null) {
            return;
        }
        Path target = b.getPath();
        historyService.content(revision, text -> {
            if (text == null) {
                setStatus(tr("status.history.restoreFailed", target.getFileName()));
                return;
            }
            applyToLocal(target, text);
            setStatus(tr("status.history.restored", target.getFileName()));
        });
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

    /** Toggles inline blame (palette + {@code M-g a}); persists the setting and re-applies. */
    private void toggleGitBlame() {
        ifGit(() -> {
            Settings s = config.getSettings();
            s.setGitBlameInline(!s.isGitBlameInline());
            requestSave();
            applyGitBlame();
            settingsWindow.syncGitBlameCheck();
            setStatus(tr("status.toggle.gitBlame", tr(s.isGitBlameInline() ? "common.on" : "common.off")));
        });
    }

    /** Opens the read-only diff of the active file at the caret line's commit vs its parent. */
    private void blameShowCommit() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null || currentRepoRoot == null) {
            return;
        }
        String hash = b.blameHashAtCaret();
        if (hash == null || hash.isBlank()) {
            setStatus(tr("status.git.noBlameLine"));
            return;
        }
        String rel = com.editora.git.GitService.repoRelative(currentRepoRoot, b.getPath());
        if (rel != null) {
            diffCommitFile(hash, rel);
        }
    }

    // --- Stash -----------------------------------------------------------------------------------

    /** Stashes the working tree (optionally with a message). */
    private void gitStash() {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        promptText(tr("stash.prompt.title"), tr("stash.prompt.label"), "", msg -> {
            String m = msg.strip();
            String[] args = m.isEmpty() ? new String[] {"stash", "push"} : new String[] {"stash", "push", "-m", m};
            gitService.run(
                    currentRepoRoot,
                    r -> {
                        if (r.ok()) {
                            setStatus(tr("stash.pushed"));
                        } else {
                            gitError(tr("status.git.opFailed"), r.message());
                        }
                        afterGitMutation();
                        Platform.runLater(this::checkExternalChanges);
                    },
                    args);
        });
    }

    /** Pops the most recent stash. */
    private void gitStashPop() {
        gitMutateStash(tr("stash.popped"), "stash", "pop");
    }

    /** Opens a picker over the stash list to apply / pop / drop a chosen entry. */
    private void gitUnstash() {
        chooseStash(
                tr("stash.picker.applyTitle"),
                entry -> gitMutateStash(tr("stash.applied"), "stash", "apply", entry.ref()));
    }

    /** Opens a picker over the stash list to drop a chosen entry. */
    private void gitStashDrop() {
        chooseStash(
                tr("stash.picker.dropTitle"),
                entry -> gitMutateStash(tr("stash.dropped"), "stash", "drop", entry.ref()));
    }

    private void chooseStash(String title, java.util.function.Consumer<com.editora.git.StashParser.StashEntry> onPick) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        gitService.stashList(currentRepoRoot, stashes -> {
            if (stashes.isEmpty()) {
                setStatus(tr("stash.empty"));
                return;
            }
            QuickOpen<com.editora.git.StashParser.StashEntry> picker = new QuickOpen<>(
                    title,
                    tr("stash.picker.prompt"),
                    () -> stashes,
                    e -> e.ref() + "  " + e.subject(),
                    e -> e.branch(),
                    e -> e.ref() + " " + e.subject() + " " + e.branch(),
                    onPick);
            picker.setOverlayHost(overlayHost);
            picker.show(stage);
        });
    }

    private void gitMutateStash(String successMessage, String... args) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        gitService.run(
                currentRepoRoot,
                r -> {
                    if (r.ok()) {
                        setStatus(successMessage);
                    } else {
                        gitError(tr("status.git.opFailed"), r.message());
                    }
                    afterGitMutation();
                    Platform.runLater(this::checkExternalChanges);
                },
                args);
    }

    /**
     * Shows a Git command's (often multi-line) error output in a readable, scrollable dialog rather than
     * cramming it into the one-line status bar. The status bar gets a short summary.
     */
    private void gitError(String summary, String detail) {
        setStatus(summary);
        String body = detail == null || detail.isBlank() ? summary : detail.strip();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.git.title"));
        alert.setHeaderText(summary);
        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(52);
        area.setPrefRowCount(Math.min(14, (int) body.lines().count() + 1));
        area.getStyleClass().add("git-error-text");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    /**
     * Clones a remote repository via one dialog asking for both the <em>URL</em> and the
     * <em>destination directory</em> (with a Browse button); the directory auto-fills to
     * {@code <home>/<repo-name>} as you type the URL, until you edit it yourself. Clones into that
     * folder, then opens a file from it (its README, if any) so Git lights up. Clone and
     * {@link com.editora.config.ProjectManager Projects} are independent — cloning never creates or
     * requires a project.
     */
    private void gitClone() {
        TextField urlField = new TextField();
        urlField.setPromptText("https://github.com/user/repo.git");
        urlField.setPrefColumnCount(34);
        com.editora.command.TextInputKeymap.install(urlField, keymap);
        TextField dirField = new TextField();
        dirField.setPromptText(tr("dialog.clone.dirPrompt"));
        dirField.setPrefColumnCount(28);
        com.editora.command.TextInputKeymap.install(dirField, keymap);
        Button browse = new Button(tr("dialog.clone.browse"));
        browse.setFocusTraversable(false);

        String defaultParent = System.getProperty("user.home", "");
        boolean[] dirEdited = {false};
        boolean[] autoFilling = {false};
        urlField.textProperty().addListener((o, a, b) -> {
            if (!dirEdited[0]) {
                String name = repoNameFromUrl(b);
                autoFilling[0] = true;
                dirField.setText(
                        name.isEmpty()
                                ? ""
                                : Path.of(defaultParent).resolve(name).toString());
                autoFilling[0] = false;
            }
        });
        dirField.textProperty().addListener((o, a, b) -> {
            if (!autoFilling[0]) {
                dirEdited[0] = true; // user took control of the directory; stop auto-filling
            }
        });
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("dialog.clone.parentTitle"));
            java.io.File parent = chooser.showDialog(stage);
            if (parent != null) {
                String name = repoNameFromUrl(urlField.getText());
                dirField.setText(parent.toPath()
                        .resolve(name.isEmpty() ? "repository" : name)
                        .toString());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("dialog.clone.url")), 0, 0);
        grid.add(urlField, 1, 0, 2, 1);
        grid.add(new Label(tr("dialog.clone.directory")), 0, 1);
        grid.add(dirField, 1, 1);
        grid.add(browse, 2, 1);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(dirField, Priority.ALWAYS);

        // Enable Clone only when both fields are filled (mirrors the old dialog's validation).
        javafx.beans.property.BooleanProperty valid = new javafx.beans.property.SimpleBooleanProperty(false);
        Runnable revalidate = () ->
                valid.set(!urlField.getText().isBlank() && !dirField.getText().isBlank());
        urlField.textProperty().addListener((o, a, b) -> revalidate.run());
        dirField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        OverlayInput.show(
                overlayHost,
                tr("dialog.clone.title"),
                grid,
                urlField,
                tr("dialog.clone.button"),
                valid,
                () -> {
                    String url = urlField.getText().strip();
                    Path destination = Path.of(dirField.getText().strip());
                    if (Files.exists(destination)) {
                        setStatus(tr("status.destExists", destination));
                        return;
                    }
                    setStatus(tr("status.cloning", url));
                    gitService.clone(url, destination, r -> {
                        if (r.ok()) {
                            setStatus(tr("status.clonedInto", destination));
                            openClonedEntry(destination);
                        } else {
                            gitError("Clone failed", r.message());
                        }
                    });
                },
                null,
                false);
    }

    // ===== Remote files (SFTP) =====

    private com.editora.vfs.RemoteFileSystems remoteFs() {
        if (remoteFs == null) {
            remoteFs = new com.editora.vfs.RemoteFileSystems(); // starts the SSH client + wires Vfs resolver
        }
        return remoteFs;
    }

    private void connectRemote() {
        connectRemote(null);
    }

    /** Shows the SFTP connection form (optionally pre-filled from a saved connection); on success, mounts
     *  the remote folder in the Project tool window. */
    private void connectRemote(com.editora.vfs.RemoteConnection prefill) {
        TextField hostField = new TextField();
        hostField.setPromptText(tr("remote.hostPrompt"));
        hostField.setPrefColumnCount(24);
        com.editora.command.TextInputKeymap.install(hostField, keymap);
        TextField portField = new TextField("22");
        portField.setPrefColumnCount(5);
        TextField userField = new TextField(System.getProperty("user.name", ""));
        userField.setPrefColumnCount(16);
        com.editora.command.TextInputKeymap.install(userField, keymap);
        TextField pathField = new TextField();
        pathField.setPromptText(tr("remote.pathPrompt"));
        com.editora.command.TextInputKeymap.install(pathField, keymap);

        ComboBox<com.editora.vfs.RemoteConnection.AuthMethod> authCombo = new ComboBox<>();
        authCombo.getItems().setAll(com.editora.vfs.RemoteConnection.AuthMethod.values());
        authCombo.setValue(com.editora.vfs.RemoteConnection.AuthMethod.DEFAULT_KEYS);
        authCombo.setConverter(new javafx.util.StringConverter<com.editora.vfs.RemoteConnection.AuthMethod>() {
            @Override
            public String toString(com.editora.vfs.RemoteConnection.AuthMethod m) {
                return m == null
                        ? ""
                        : switch (m) {
                            case DEFAULT_KEYS -> tr("remote.auth.defaultKeys");
                            case KEY -> tr("remote.auth.key");
                            case PASSWORD -> tr("remote.auth.password");
                        };
            }

            @Override
            public com.editora.vfs.RemoteConnection.AuthMethod fromString(String s) {
                return null;
            }
        });
        PasswordField secretField = new PasswordField();
        secretField.setPromptText(tr("remote.secretPrompt"));
        TextField keyField = new TextField();
        keyField.setPromptText(tr("remote.keyPrompt"));
        com.editora.command.TextInputKeymap.install(keyField, keymap);
        Button keyBrowse = new Button(tr("dialog.clone.browse"));
        keyBrowse.setFocusTraversable(false);
        keyBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(tr("remote.keyPrompt"));
            java.io.File f = fc.showOpenDialog(stage);
            if (f != null) {
                keyField.setText(f.getAbsolutePath());
            }
        });
        // Show only the fields relevant to the chosen auth method.
        Runnable syncAuth = () -> {
            var m = authCombo.getValue();
            boolean key = m == com.editora.vfs.RemoteConnection.AuthMethod.KEY;
            boolean pwd = m == com.editora.vfs.RemoteConnection.AuthMethod.PASSWORD;
            keyField.setDisable(!key);
            keyBrowse.setDisable(!key);
            secretField.setDisable(!key && !pwd);
            secretField.setPromptText(pwd ? tr("remote.secretPrompt") : tr("remote.passphrasePrompt"));
        };
        authCombo.valueProperty().addListener((o, a, b) -> syncAuth.run());
        if (prefill != null) { // reconnecting a saved connection — fill everything but the secret
            hostField.setText(prefill.host());
            portField.setText(String.valueOf(prefill.port()));
            userField.setText(prefill.user() == null ? "" : prefill.user());
            authCombo.setValue(prefill.auth());
            keyField.setText(prefill.keyPath() == null ? "" : prefill.keyPath());
            pathField.setText(prefill.lastPath() == null ? "" : prefill.lastPath());
        }
        syncAuth.run();

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("remote.host")), 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(new Label(tr("remote.port")), 2, 0);
        grid.add(portField, 3, 0);
        grid.add(new Label(tr("remote.user")), 0, 1);
        grid.add(userField, 1, 1);
        grid.add(new Label(tr("remote.auth")), 0, 2);
        grid.add(authCombo, 1, 2, 3, 1);
        grid.add(new Label(tr("remote.key")), 0, 3);
        grid.add(keyField, 1, 3, 2, 1);
        grid.add(keyBrowse, 3, 3);
        grid.add(new Label(tr("remote.secret")), 0, 4);
        grid.add(secretField, 1, 4, 3, 1);
        grid.add(new Label(tr("remote.path")), 0, 5);
        grid.add(pathField, 1, 5, 3, 1);
        GridPane.setHgrow(hostField, Priority.ALWAYS);

        javafx.beans.property.BooleanProperty valid = new javafx.beans.property.SimpleBooleanProperty(false);
        Runnable revalidate = () ->
                valid.set(!hostField.getText().isBlank() && !userField.getText().isBlank());
        hostField.textProperty().addListener((o, a, b) -> revalidate.run());
        userField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        OverlayInput.show(
                overlayHost,
                tr("remote.connect.title"),
                grid,
                hostField,
                tr("remote.connect.button"),
                valid,
                () -> {
                    int port = 22;
                    try {
                        port = Integer.parseInt(portField.getText().strip());
                    } catch (NumberFormatException ignore) {
                        // keep the default port
                    }
                    String path = pathField.getText().strip();
                    com.editora.vfs.RemoteConnection conn = new com.editora.vfs.RemoteConnection(
                            hostField.getText().strip(),
                            port,
                            userField.getText().strip(),
                            authCombo.getValue(),
                            keyField.getText().strip(),
                            null,
                            path.isEmpty() ? null : path);
                    char[] secret = secretField.getText().isEmpty()
                            ? null
                            : secretField.getText().toCharArray();
                    setStatus(tr("status.remote.connecting", conn.displayLabel()));
                    remoteFs().connect(conn, secret, r -> {
                        if (r.ok()) {
                            mountRemote(conn, r.root());
                        } else {
                            gitError(tr("status.remote.failed", conn.displayLabel()), r.error());
                        }
                    });
                },
                null,
                false);
    }

    /** Mounts a connected remote folder as the Project tree root + opens the Project tool window. */
    private void mountRemote(com.editora.vfs.RemoteConnection conn, Path root) {
        activeRemoteAuthority = conn.id();
        config.putConnection(conn); // remember the connection (metadata only — no secret) for next time
        projectPanel.setRoot(root);
        toolWindows.open(projectToolWindow);
        setStatus(tr("status.remote.connected", conn.displayLabel()));
    }

    /** A picker over the saved SFTP connections; choosing one re-opens the connect form pre-filled. */
    private void manageRemoteConnections() {
        var saved = config.getConnections();
        if (saved.isEmpty()) {
            connectRemote(); // nothing saved yet — go straight to a fresh connection
            return;
        }
        QuickOpen<com.editora.vfs.RemoteConnection> picker = new QuickOpen<>(
                tr("remote.manage.title"),
                tr("remote.manage.prompt"),
                () -> List.copyOf(config.getConnections()),
                com.editora.vfs.RemoteConnection::displayLabel,
                com.editora.vfs.RemoteConnection::id,
                this::connectRemote);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Opens a single remote file from an {@code sftp://user@host/path} URI (its connection must be open). */
    private void openRemoteFile() {
        promptText(tr("remote.openFile.title"), tr("remote.openFile.label"), "sftp://", uri -> {
            Path p = com.editora.vfs.Vfs.parseStorable(uri.strip());
            if (p == null) {
                setStatus(tr("status.remote.notConnected"));
                return;
            }
            openPath(p);
        });
    }

    /** Disconnects the mounted remote folder and returns the Project tree to the active local project. */
    private void disconnectRemote() {
        if (activeRemoteAuthority == null || remoteFs == null) {
            setStatus(tr("status.remote.notConnected"));
            return;
        }
        remoteFs.disconnect(activeRemoteAuthority);
        activeRemoteAuthority = null;
        Project active = projects.active();
        if (active != null) {
            projectPanel.setRoot(Path.of(active.root()));
        }
        setStatus(tr("status.remote.disconnected"));
    }

    /**
     * Opens a representative file from a freshly cloned repo (its README if present) so Git activates
     * for it — no project involved. If there's no obvious entry file, the clone is just reported and the
     * user can open files from it (File: Open / Find File).
     */
    private void openClonedEntry(Path dir) {
        for (String candidate : new String[] {"README.md", "README.markdown", "README.rst", "README.txt", "README"}) {
            Path file = dir.resolve(candidate);
            if (Files.isRegularFile(file)) {
                openPath(file);
                return;
            }
        }
        setStatus(tr("status.clonedOpen", dir));
    }

    /**
     * Derives the working-folder name for a clone URL: the last path segment with any {@code .git}
     * suffix and trailing slashes removed. Handles {@code https://…/repo.git}, {@code git@host:org/repo.git},
     * and local paths. Pure/unit-tested. Returns {@code ""} when no name can be found.
     */
    static String repoNameFromUrl(String url) {
        if (url == null) {
            return "";
        }
        String s = url.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        // The last segment after a '/' or (for scp-style "git@host:org/repo") a ':'.
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String name = cut >= 0 ? s.substring(cut + 1) : s;
        return name.strip();
    }

    /** Stages the active file (used by the {@code git.stageFile} command). */
    private void gitStageActiveFile() {
        EditorBuffer b = activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || currentRepoRoot == null) {
            setStatus(tr("status.noGitFile"));
            return;
        }
        gitOp(
                "Staged " + file.getFileName(),
                "add",
                "--",
                currentRepoRoot.relativize(file.toAbsolutePath()).toString());
    }

    /** Opens the Git tool window and focuses the commit message box. */
    private void gitCommitFocus() {
        toolWindows.open(commitToolWindow);
        Platform.runLater(gitPanel::focusCommitMessage);
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
        WorkspaceState state = config.getWorkspaceState();
        List<WorkspaceState.OpenFile> files = new ArrayList<>();
        for (WorkspaceState.OpenFile f : state.getOpenFiles()) {
            if (f.getPath() != null && !f.getPath().isBlank() && Files.isReadable(Path.of(f.getPath()))) {
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
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(Path.of(f.getPath())); // sets the tab title/language; content comes later
            boolean active = f.getPath().equals(activePath);
            if (active) {
                activeIndex = i;
            }
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
            fillSessionBuffer(files.get(i), buffers.get(i));
            fillSessionFiles(files, buffers, order, k + 1);
        });
    }

    /** A startup file to open, with an optional 1-based line/column ({@code 0} = unspecified). */
    public record OpenTarget(Path file, int line, int column) {}

    /** A one-shot action run after {@link #openInitialBuffer()} finishes restoring the session. */
    private Runnable pendingAfterRestore;

    /**
     * Startup entry point (replaces the bare {@code openInitialBuffer()} call): optionally activates a
     * project, restores the session, then — once restore completes — opens any command-line files
     * (jumping to line:column) and enters Zen, all additive on top of the restored session. With no
     * arguments it's exactly the old {@code openInitialBuffer()}.
     */
    public void startup(Path projectDir, List<OpenTarget> targets, boolean zen, String newFile, boolean simple) {
        if (projectDir != null && projectsEnabled()) {
            activateStartupProject(projectDir); // swap to the project's session before it's restored
        }
        // Run CLI actions AFTER the (deferred, pulse-paced) session restore, so a restored caret can't
        // override a requested line:column.
        pendingAfterRestore = () -> applyStartupTargets(targets, zen, newFile, simple);
        openInitialBuffer();
    }

    private void applyStartupTargets(List<OpenTarget> targets, boolean zen, String newFile, boolean simple) {
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
        if (zen) {
            setZenMode(true);
        }
    }

    /** Invokes the one-shot {@link #pendingAfterRestore} action (if any), deferred one pulse. */
    private void runPendingAfterRestore() {
        Runnable r = pendingAfterRestore;
        pendingAfterRestore = null;
        if (r != null) {
            Platform.runLater(r);
        }
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
        area.requestFollowCaret();
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(targetLine);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
        area.requestFocus();
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
            restoreBookmarks(buffer);
            restoreBreakpoints(buffer);
            restoreNotes(buffer);
            restoreReadOnly(buffer);
            restoreMarkdownMode(buffer);
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
        buffer.setOnAddToDictionary(config::addUserWord);
        applyViewSettings(buffer);
        buffer.getFoldManager().setOnFoldStateChanged(() -> persistFolds(buffer));
        buffer.setOnBookmarksChanged(() -> persistBookmarks(buffer));
        buffer.setGutterBookmarkClick(this::onGutterBookmarkClick);
        buffer.setOnNotesChanged(() -> persistNotes(buffer));
        buffer.setGutterNoteClick(this::onGutterNoteClick);
        // Refresh the Run button when this buffer's runnable status flips (only acts if it's active).
        buffer.setOnRunnableChanged(() -> {
            if (activeBuffer() == buffer) {
                updateRunButton();
            }
        });
        buffer.setRunHandler(this::runActiveFile); // "Run File" editor right-click item (runnable files)
        boolean local = isLocalBuffer(buffer); // remote (SFTP) files can't run a local process
        buffer.setRunEnabled(lspEnabled() && local); // the Run affordance is gated by the LSP feature
        buffer.setShellRunEnabled(lspEnabled() && local && config.getSettings().isBashLspEnabled());
        buffer.setHttpRunHandler(line -> runHttpRequest(buffer, line)); // .http request ▶
        buffer.setHttpEnabled(httpEnabled() && local);
        // Debugging: the leftmost breakpoint gutter strip + persistence + live re-send to a session
        // (only for debuggable languages — java/python/javascript).
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer));
        buffer.setOnBreakpointsChanged(() -> onBreakpointsChanged(buffer));
        buffer.setAddNoteHandler(this::addNoteFromContext);
        buffer.setNotesEnabled(notesEnabled());
        buffer.setOpenUrlHandler(this::openExternalUrl); // Ctrl/Cmd-click + open-link command
        // HTML Live Preview: the debounced edit pulse reloads the browser, but only while this file is the
        // one currently served (and the feature is on) — see applyHtmlPreviewSupport / ensureHtmlPreviewControl.
        buffer.setHtmlPreviewDirtyListener(() -> {
            if (htmlPreviewEnabled() && htmlPreviewService.isPreviewing(buffer.getPath())) {
                htmlPreviewService.notifyChanged();
            }
        });
        buffer.setFormatBarEnabled(config.getSettings().isMarkdownFormatBar());
        buffer.setPreviewExportPdfHandler(this::exportPreviewPdf); // preview right-click menu
        buffer.setPreviewPrintHandler(this::printPreview);
        buffer.setOnEnableEditing(() -> enableEditing(buffer)); // "Enable Editing" banner button
        buffer.setSnippetProvider((lang, prefix) -> snippets.byPrefix(lang, prefix));
        buffer.setCompletionProvider(completion::complete);
        buffer.setMenuContributor(() -> pluginEditorMenuItems(buffer)); // plugin-contributed right-click items
        Settings acs = config.getSettings();
        buffer.setAutocomplete(
                acs.isAutocomplete(),
                acs.isAutocompleteProse(),
                acs.isAutocompleteSnippets(),
                effectiveMermaidAutocomplete());
        buffer.setMultiCaretEnabled(
                multiCaretEnabled()); // multiple cursors + Alt+drag column selection (off in Simple UI mode)
        buffer.setMermaidValidator((text, cb) -> mermaidService.validate(text, cb));
        buffer.setMermaidLintEnabled(mermaidEnabled() && mermaidAvail.maid());
        // Hover value tooltip while suspended: evaluate the hovered identifier in the selected frame.
        buffer.setDebugHoverEvaluator((expr, cb) -> dapManager.evaluateHover(expr, debugFrameId, cb));
        // LSP: debounced didChange sink, async completion source, then open+activate if eligible.
        buffer.setLspChangeListener(text -> {
            if (buffer.getPath() != null) {
                lspManager.changeDocument(buffer.getPath(), text);
            }
        });
        // Pull-model diagnostics (fired on the same debounce as didChange; no-op for push-only servers).
        buffer.setLspDiagnosticsRequester(() -> {
            if (buffer.getPath() != null) {
                lspManager.pullDiagnostics(buffer.getPath());
            }
        });
        buffer.setLspCompletionProvider((pos, cb) -> {
            if (buffer.getPath() != null && lspManager.isManaged(buffer.getPath())) {
                lspManager.completion(
                        buffer.getPath(),
                        pos[0],
                        pos[1],
                        items -> cb.accept(
                                com.editora.lsp.CompletionMapper.map(items, item -> autoImportAccept(buffer, item))));
            } else {
                cb.accept(java.util.List.of());
            }
        });
        buffer.setLspNavActions(this::lspGotoDefinition, this::lspFindReferences, this::lspShowHover);
        syncBufferLsp(buffer);
        ensurePreviewControls(buffer);
        ensureHtmlPreviewControl(buffer); // the floating "open in browser" globe (HTML buffers, feature on)
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
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            String note = loadInto(buffer, file);
            // Apply folds before the node is in the scene, so each fold skips per-fold layout.
            restoreFolds(buffer);
            restoreBookmarks(buffer);
            restoreBreakpoints(buffer);
            restoreNotes(buffer);
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
        if (size >= EditorBuffer.HUGE_FILE_BYTES) {
            String content = readCapped(file, (int) EditorBuffer.HUGE_FILE_BYTES);
            buffer.setContent(content);
            buffer.setReadOnly(true);
            return file.getFileName() + " — very large file (" + StatusBar.formatSize(size)
                    + "): read-only, showing first " + StatusBar.formatSize(content.length());
        }
        buffer.setContent(Files.readString(file));
        if (size >= EditorBuffer.LARGE_FILE_BYTES) {
            buffer.setLargeFile(true);
            return largeFileNote(file);
        }
        return "";
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
            recordHistory(buffer, HistoryRevision.REASON_EXTERNAL); // snapshot the in-memory version before disk wins
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

    @FXML
    private void onSaveAs() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            saveAs(buffer);
        }
    }

    /** @return true if the buffer is on disk afterwards (either already saved or just saved). */
    private boolean save(EditorBuffer buffer) {
        if (buffer.getPath() == null) {
            return saveAs(buffer);
        }
        return writeBuffer(buffer, buffer.getPath());
    }

    private boolean saveAs(EditorBuffer buffer) {
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
        buffer.setPath(file);
        ensurePreviewControls(buffer); // a new untitled saved as .md/.mmd now gets the preview toggle
        ensureHtmlPreviewControl(buffer); // a save-as to .html now gets the "open in browser" globe
        boolean ok = writeBuffer(buffer, file);
        Tab tab = tabFor(buffer);
        if (tab != null) {
            updateTabMeta(tab, buffer);
        }
        if (buffer == activeBuffer()) {
            breadcrumb.setActiveFile(buffer.getPath());
        }
        return ok;
    }

    private boolean writeBuffer(EditorBuffer buffer, Path file) {
        try {
            Files.writeString(file, buffer.getContent());
            recordHistory(buffer, HistoryRevision.REASON_SAVE); // snapshot the just-saved version
            buffer.markClean();
            buffer.setDiskSnapshot(lastModifiedMillis(file), fileSize(file)); // our own write isn't "external"
            setStatus(tr("status.saved", file));
            refreshGit(); // a save changes the working tree → update gutter + status
            // LSP: a save-as of a new Java file opens it on the server; then notify didSave.
            syncBufferLsp(buffer);
            notifyLspSaved(buffer);
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
        String content = buffer.getContent();
        Path file = buffer.getPath();
        recordHistory(buffer, HistoryRevision.REASON_AUTOSAVE); // snapshot before the off-thread write
        autoSaveExecutor.submit(() -> {
            try {
                Files.writeString(file, content);
                Platform.runLater(() -> {
                    if (content.equals(buffer.getContent())) {
                        buffer.markClean();
                    }
                    buffer.setDiskSnapshot(lastModifiedMillis(file), fileSize(file)); // our write, not external
                    setStatus(tr("status.autoSaved", file.getFileName()));
                    refreshGit();
                });
            } catch (IOException e) {
                Platform.runLater(() -> setStatus(tr("status.autoSaveFailed", e.getMessage())));
            }
        });
    }

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
                    ensureHtmlPreviewControl(buffer); // a rename to/from .html flips the browser globe
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
                    migrateBookmarksKey(oldBookmarkKey, target.toString());
                    migrateNotesKey(oldNoteKey, noteKey(buffer));
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
        MenuItem diffHead = new MenuItem(tr("menu.diffHead"));
        diffHead.setGraphic(Icons.diff());
        diffHead.setOnAction(e -> ifGit(this::diffActiveVsHead));
        MenuItem compareWith = new MenuItem(tr("menu.compareWith"));
        compareWith.setGraphic(Icons.diff());
        compareWith.setOnAction(e -> compareActiveWithFile());
        MenuItem history = new MenuItem(tr("command.git.fileHistory"));
        history.setGraphic(Icons.gitLog());
        history.setOnAction(e -> ifGit(this::showFileHistory));
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
                diffHead,
                compareWith,
                history,
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
            compareWith.setDisable(!hasPath);
            diffHead.setDisable(!hasPath || !gitEnabled());
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
            EditorBuffer buffer = bufferOf(tab);
            Path p = buffer == null ? null : buffer.getPath();
            if (p != null && pathKey(p).equals(target)) {
                return tab;
            }
        }
        return null;
    }

    /** A provider-safe identity key for a path: the canonical string for a local file, the {@code sftp://}
     *  URI for a remote one. Avoids {@code Path.equals} across filesystems (MINA SFTP paths throw a
     *  {@link java.nio.file.ProviderMismatchException} when compared to a local path) and a network
     *  {@code toRealPath()} for remote files. */
    private static String pathKey(Path p) {
        return com.editora.vfs.Vfs.isRemote(p)
                ? com.editora.vfs.Vfs.toStorableString(p)
                : canonicalPath(p).toString();
    }

    /** A path for cross-source identity comparison: the real (symlink-resolved) path when it exists,
     *  else the absolute-normalized form. A language server reports diagnostics under the file's
     *  <em>canonical</em> URI (e.g. {@code /private/tmp/…} for a {@code /tmp/…} symlink on macOS); a buffer
     *  keeps the path as opened, so matching by {@code normalize()} alone misses it and drops diagnostics. */
    static Path canonicalPath(Path p) {
        try {
            return p.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    @FXML
    private void onQuit() {
        if (confirmQuit() && confirmCloseAllBuffers()) {
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

    /** Walks every tab and prompts to save/discard each dirty buffer. False = user cancelled. */
    private boolean confirmCloseAllBuffers() {
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
     * Coalesces config writes to one per FX pulse. Many actions (especially a single Settings apply,
     * which runs ~10 field setters back-to-back) call {@code config.save()} several times in the same
     * pulse, each re-serializing the whole settings + workspace-state to disk. This collapses such a
     * burst into a single write at the end of the pulse — negligible delay, no data-loss risk (the
     * durable flush on quit goes through {@link #persistSession()}'s direct {@code config.save()}).
     */
    private void requestSave() {
        if (configSavePending) {
            return;
        }
        configSavePending = true;
        Platform.runLater(() -> {
            configSavePending = false;
            config.save();
        });
    }

    private void persistSession() {
        List<WorkspaceState.OpenFile> files = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null && buffer.getPath() != null) {
                files.add(new WorkspaceState.OpenFile(
                        buffer.getPath().toAbsolutePath().toString(),
                        buffer.getArea().getCaretPosition(),
                        pinned.contains(tab)));
            }
        }
        WorkspaceState state = config.getWorkspaceState();
        state.setOpenFiles(files);
        EditorBuffer active = activeBuffer();
        state.setActiveFile(
                active != null && active.getPath() != null
                        ? active.getPath().toAbsolutePath().toString()
                        : "");
        persistWindowBounds(state);
        config.save(); // durable flush on quit — not coalesced
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
        openSearchInFiles();
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
        boolean httpActive = http && httpEnabled();
        if (httpToolWindow != null) {
            toolWindows.setAvailable(httpToolWindow, httpActive);
        }
        if (httpActive && buffer.getPath() != null) {
            java.nio.file.Path dir = buffer.getPath().toAbsolutePath().getParent();
            httpPanel.setEnvironments(
                    httpEnvironmentNames(dir), config.getWorkspaceState().getHttpEnvironment());
        }
    }

    /**
     * Runs the active runnable file — a compact Java source via the JDK source launcher ({@code java
     * <file>}), a Python script ({@code python3 <file>}), or a shell script ({@code bash <file>}) —
     * streaming output into the Run tool window.
     * Saves first (a dirty file would run stale; an untitled one prompts Save-As), and refuses to start
     * while a previous run is still alive.
     */
    private void runActiveFile() {
        runActiveFile(false);
    }

    /** Prompts for program arguments (pre-filled with the file's remembered ones), then runs. */
    private void runActiveFileWithArgs() {
        runActiveFile(true);
    }

    /** Re-runs the most recent run (same file + argv) without touching the active tab. */
    private void rerunLast() {
        if (lastRunFile == null || lastRunCommand == null) {
            setStatus(tr("status.run.noRerun"));
            return;
        }
        if (runService.isRunning()) {
            setStatus(tr("status.run.busy"));
            return;
        }
        launchRun(lastRunFile, lastRunCommand);
    }

    /** The most recent launch, for {@code run.rerun}. */
    private Path lastRunFile;

    private java.util.List<String> lastRunCommand;

    private void runActiveFile(boolean promptArgs) {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null || !buffer.isRunnable()) {
            setStatus(tr("status.run.notCompact"));
            return;
        }
        if ((buffer.isDirty() || buffer.getPath() == null) && !save(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        if (runService.isRunning()) {
            setStatus(tr("status.run.busy"));
            return;
        }
        boolean java = !buffer.isPython() && !buffer.isShell();
        Runnable proceed = () -> {
            String stored = programArgsFor(path);
            if (promptArgs) {
                promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                    config.getWorkspaceState().getProgramArgs().put(path.toString(), args == null ? "" : args.strip());
                    config.save();
                    launchRun(path, buildRunCommand(buffer, path));
                });
            } else {
                launchRun(path, buildRunCommand(buffer, path));
            }
        };
        if (java) {
            // Compact source files need the JDK 25+ source-file launcher; preflight so an older java
            // on PATH yields a clear message instead of a cryptic launcher error. Cached after once.
            runService.detectJavaMajor(major -> {
                if (major > 0 && major < 25) {
                    setStatus(tr("status.run.needJdk25", major));
                    return;
                }
                proceed.run();
            });
        } else {
            proceed.run();
        }
    }

    /** The remembered program-arguments string for {@code path} ("" when none). */
    private String programArgsFor(Path path) {
        String s = config.getWorkspaceState().getProgramArgs().get(path.toString());
        return s == null ? "" : s;
    }

    /** The launcher argv for the buffer's language: interpreter + file + the remembered args. */
    private java.util.List<String> buildRunCommand(EditorBuffer buffer, Path path) {
        java.util.List<String> command = new java.util.ArrayList<>();
        if (buffer.isPython()) {
            command.add("python3");
        } else if (buffer.isShell()) {
            command.add("bash");
        } else {
            command.add("java");
        }
        command.add(path.toString());
        command.addAll(com.editora.run.ProgramArgs.tokenize(programArgsFor(path)));
        return command;
    }

    private void launchRun(Path path, java.util.List<String> command) {
        lastRunFile = path;
        lastRunCommand = command;
        toolWindows.open(runToolWindow);
        runPanel.started(path.getFileName().toString());
        setStatus(tr("status.run.started", path.getFileName().toString()));
        runService.run(path, command, new com.editora.run.RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                runPanel.started(commandLine);
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                runPanel.appendOutput(line, stderr);
            }

            @Override
            public void onExit(int code) {
                runPanel.finished(code);
                setStatus(code == 0 ? tr("status.run.ok") : tr("status.run.exit", code));
            }

            @Override
            public void onError(String message) {
                runPanel.failed(message);
                setStatus(tr("status.run.failed", message));
            }
        });
    }

    /** Stops the currently running program (Run tool window Stop button / {@code run.stop} command). */
    private void stopRun() {
        if (runService.isRunning()) {
            runService.stop();
            setStatus(tr("status.run.stopped"));
        }
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
                config.isDev() ? com.editora.AppInfo.gitCommit() : ""); // build commit shown only in --dev
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
    private void togglePluginSupport() {
        Settings s = config.getSettings();
        s.setPluginSupport(!s.isPluginSupport());
        requestSave();
        settingsWindow.syncPluginsCheck();
        setStatus(tr("status.toggle.plugins", tr(s.isPluginSupport() ? "common.on" : "common.off")));
    }

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
                () -> Themes.NAMES,
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
                () -> EditorThemes.NAMES,
                name -> name,
                name -> "",
                this::applyEditorThemeChoice);
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
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
        setZenMode(!config.getWorkspaceState().isZenMode());
    }

    // Keys for the pre-Zen view/chrome snapshot (WorkspaceState.preZenView).
    private static final String ZEN_RULER = "columnRuler";
    private static final String ZEN_LINE_HIGHLIGHT = "lineHighlight";
    private static final String ZEN_LINE_NUMBERS = "lineNumbers";
    private static final String ZEN_MINIMAP = "minimap";
    private static final String ZEN_WHITESPACE = "whitespace";
    private static final String ZEN_TOOLBAR = "toolbar";
    private static final String ZEN_STATUS_BAR = "statusBar";
    private static final String ZEN_TAB_BAR = "tabBar";
    private static final String ZEN_BREADCRUMB = "breadcrumb";

    /**
     * Enters/leaves distraction-free Zen mode. Entering snapshots the user's view/chrome prefs and the
     * open tool windows, then turns those prefs off — so while in Zen the normal toggles still work
     * and can re-enable individual items (e.g. line numbers or the status bar); leaving Zen restores
     * the snapshot exactly. Idempotent.
     */
    void setZenMode(boolean on) {
        WorkspaceState ws = config.getWorkspaceState();
        if (ws.isZenMode() == on) {
            return;
        }
        Settings s = config.getSettings();
        if (on) {
            Map<String, Boolean> snap = new LinkedHashMap<>();
            snap.put(ZEN_RULER, s.isShowColumnRuler());
            snap.put(ZEN_LINE_HIGHLIGHT, s.isHighlightCurrentLine());
            snap.put(ZEN_LINE_NUMBERS, s.isShowLineNumbers());
            snap.put(ZEN_MINIMAP, s.isShowMinimap());
            snap.put(ZEN_WHITESPACE, s.isShowWhitespace());
            snap.put(ZEN_TOOLBAR, s.isShowToolbar());
            snap.put(ZEN_STATUS_BAR, s.isShowStatusBar());
            snap.put(ZEN_TAB_BAR, s.isShowTabBar());
            snap.put(ZEN_BREADCRUMB, s.isShowBreadcrumb());
            ws.setPreZenView(snap);
            ws.setPreZenToolWindows(toolWindows.closeAllOpen());
            setZenViewSettings(s, false);
        } else {
            Map<String, Boolean> snap = ws.getPreZenView();
            s.setShowColumnRuler(snap.getOrDefault(ZEN_RULER, true));
            s.setHighlightCurrentLine(snap.getOrDefault(ZEN_LINE_HIGHLIGHT, true));
            s.setShowLineNumbers(snap.getOrDefault(ZEN_LINE_NUMBERS, true));
            s.setShowMinimap(snap.getOrDefault(ZEN_MINIMAP, true));
            s.setShowWhitespace(snap.getOrDefault(ZEN_WHITESPACE, false));
            s.setShowToolbar(snap.getOrDefault(ZEN_TOOLBAR, true));
            s.setShowStatusBar(snap.getOrDefault(ZEN_STATUS_BAR, true));
            s.setShowTabBar(snap.getOrDefault(ZEN_TAB_BAR, true));
            s.setShowBreadcrumb(snap.getOrDefault(ZEN_BREADCRUMB, false));
            ws.getPreZenView().clear();
        }
        ws.setZenMode(on);
        toolWindows.setZenStripesHidden(on);
        if (!on) {
            toolWindows.openByIds(ws.getPreZenToolWindows());
            ws.getPreZenToolWindows().clear();
        }
        applyChromeVisibility();
        applyViewSettingsToAllBuffers(s);
        requestSave();
        // When entering Zen the status bar is hidden, so this is mostly seen on exit.
        setStatus(tr("status.toggle.zen", tr(on ? "common.on" : "common.off")));
    }

    /** Sets the five editor view options and the chrome toggles (bars + breadcrumb) to {@code value}. */
    private void setZenViewSettings(Settings s, boolean value) {
        s.setShowColumnRuler(value);
        s.setHighlightCurrentLine(value);
        s.setShowLineNumbers(value);
        s.setShowMinimap(value);
        s.setShowWhitespace(value);
        s.setShowToolbar(value);
        s.setShowStatusBar(value);
        s.setShowTabBar(value);
        s.setShowBreadcrumb(value);
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

    /** Persists the buffer's bookmarks, keyed by its file path, and refreshes the Bookmarks panel. */
    private void persistBookmarks(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<com.editora.config.Bookmark> marks = buffer.getBookmarkManager().snapshot();
        var map = config.getBookmarks();
        if (marks.isEmpty()) {
            map.remove(file.toString());
        } else {
            // Keep any custom order the user set in the Bookmarks tool window (the snapshot is line-order).
            map.put(
                    file.toString(),
                    com.editora.config.BookmarkStore.mergePreservingOrder(map.get(file.toString()), marks));
        }
        config.saveBookmarks();
        if (bookmarksPanel != null) {
            bookmarksPanel.refresh();
        }
    }

    /** Re-applies a file's saved bookmarks after it is opened (and paints their gutter markers). */
    private void restoreBookmarks(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        boolean reanchored = buffer.applyBookmarks(config.getBookmarks().get(file.toString()));
        // The file changed outside the editor and a bookmark followed its content to a new line —
        // write the corrected indices back so the session self-heals (once; later opens match exactly).
        if (reanchored) {
            persistBookmarks(buffer);
        }
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
        if (shouldOpenReadOnly(persisted, Files.isWritable(file))) {
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
        buffer.setMermaidLintEnabled(mermaidEnabled() && mermaidAvail.maid());
    }

    /** Restores a Markdown file's saved view mode after it is opened (and its toggle is wired). */
    private void restoreMarkdownMode(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || !buffer.hasPreview()) {
            return;
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

    private void markdownInline(String marker) {
        withMarkdown(b -> b.formatInline(marker));
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

    // --- Bookmark commands + panel actions ---------------------------------------------------------

    /** A flattened (file, bookmark) pair for the cross-file "Jump to Bookmark" picker. */
    private record BookmarkEntry(Path file, com.editora.config.Bookmark bm) {}

    /**
     * Handles a click in the gutter: adds a bookmark on an unbookmarked line, or asks for confirmation
     * before removing an existing one. (The keyboard toggle {@code C-c m} removes without a prompt.)
     */
    private void onGutterBookmarkClick(EditorBuffer buffer, int line) {
        if (buffer.getBookmarkManager().isBookmarked(line)) {
            Alert confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    tr("dialog.removeBookmark.body", line + 1),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            confirm.initOwner(stage);
            confirm.setTitle(tr("dialog.removeBookmark.title"));
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                buffer.removeBookmark(line);
            }
        } else {
            buffer.toggleBookmark(line); // add
        }
    }

    /** Toggles a bookmark on the active editor's caret line. */
    private void toggleBookmarkAtCaret() {
        EditorBuffer b = activeBuffer();
        if (b != null && b.getPath() != null) {
            b.toggleBookmark(b.getArea().getCurrentParagraph());
        } else if (b != null) {
            setStatus(tr("status.saveBeforeBookmark"));
        }
    }

    /** Adds (if absent) or edits the note on the bookmark at the active editor's caret line. */
    private void editBookmarkNoteAtCaret() {
        EditorBuffer b = activeBuffer();
        if (b == null || b.getPath() == null) {
            return;
        }
        int line = b.getArea().getCurrentParagraph();
        var mgr = b.getBookmarkManager();
        String current = "";
        for (com.editora.config.Bookmark bm : mgr.snapshot()) {
            if (bm.line() == line) {
                current = bm.note();
                break;
            }
        }
        promptText(tr("dialog.bookmarkNote.title"), tr("dialog.bookmarkNote.content"), current, note -> {
            if (!mgr.isBookmarked(line)) {
                mgr.add(line, note.strip());
                b.refreshGutterLine(line);
            } else {
                mgr.setNote(line, note.strip());
            }
        });
    }

    /** Jumps to the next/previous bookmark within the active file (wrapping). */
    private void jumpBookmark(boolean forward) {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        int from = b.getArea().getCurrentParagraph();
        Integer target = forward
                ? b.getBookmarkManager().next(from)
                : b.getBookmarkManager().previous(from);
        if (target != null) {
            navigateToLine(target);
        } else {
            setStatus(tr("status.noBookmarksInFile"));
        }
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
        picker.setOverlayHost(overlayHost);
        picker.show(stage);
    }

    /** Discovers the template's unknown variables; prompts for them via a wizard, else applies directly. */
    private void beginTemplate(com.editora.template.Template t, java.nio.file.Path targetDir) {
        List<String> texts = new ArrayList<>();
        if (t.isMultiFile()) {
            for (com.editora.template.TemplateFile f : t.files()) {
                texts.add(f.path());
                texts.add(f.body());
            }
        } else {
            texts.add(t.fileName());
            texts.add(t.body());
        }
        var vars = com.editora.template.TemplateEngine.discoverVariables(texts.toArray(new String[0]));
        if (vars.isEmpty()) {
            applyTemplate(t, targetDir, java.util.Map.of());
            return;
        }
        VBox body = new VBox(8);
        java.util.LinkedHashMap<String, TextField> fields = new java.util.LinkedHashMap<>();
        for (var v : vars) {
            TextField field = new TextField(v.defaultValue());
            field.setPrefColumnCount(28);
            com.editora.command.TextInputKeymap.install(field, keymap);
            fields.put(v.name(), field);
            body.getChildren().addAll(new Label(v.name()), field);
        }
        OverlayInput.show(
                overlayHost,
                tr("template.wizard.title"),
                body,
                fields.values().iterator().next(),
                tr("dialog.template.create"),
                null,
                () -> {
                    java.util.LinkedHashMap<String, String> answers = new java.util.LinkedHashMap<>();
                    fields.forEach((name, f) -> answers.put(name, f.getText()));
                    applyTemplate(t, targetDir, answers);
                },
                null,
                false);
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
        java.nio.file.Path target = targetDir == null ? null : targetDir.resolve(fileName);
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

    /** Clears every bookmark in the active file. */
    private void clearBookmarksInFile() {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.clearBookmarks();
        }
    }

    /**
     * The active project's bookmarks, flattened for the jump picker in the <em>stored order</em> — files
     * in their map order, bookmarks in their list order — i.e. exactly the order the Bookmarks tool
     * window shows (including any custom drag/move reordering), so the picker and the panel always agree.
     */
    private List<BookmarkEntry> allBookmarkEntries() {
        List<BookmarkEntry> out = new ArrayList<>();
        config.getBookmarks().forEach((path, marks) -> {
            if (marks != null) {
                Path file = Path.of(path);
                marks.forEach(bm -> out.add(new BookmarkEntry(file, bm)));
            }
        });
        return out;
    }

    /** Reorders a bookmark within its file (Bookmarks tool window drag / Alt+Up/Down), then persists. */
    private void moveBookmark(Path file, int fromIndex, int toIndex) {
        var marks = config.getBookmarks().get(file.toString());
        if (marks == null || !inRange(fromIndex, marks.size()) || !inRange(toIndex, marks.size())) {
            return;
        }
        List<com.editora.config.Bookmark> list = new ArrayList<>(marks);
        list.add(toIndex, list.remove(fromIndex));
        config.getBookmarks().put(file.toString(), list);
        config.saveBookmarks();
        bookmarksPanel.refresh();
    }

    /** Reorders a whole file group among the file headers (Bookmarks tool window drag / Alt+Up/Down). */
    private void moveBookmarkFile(int fromIndex, int toIndex) {
        var map = config.getBookmarks();
        List<String> keys = new ArrayList<>(map.keySet());
        if (!inRange(fromIndex, keys.size()) || !inRange(toIndex, keys.size())) {
            return;
        }
        keys.add(toIndex, keys.remove(fromIndex));
        var reordered = new java.util.LinkedHashMap<String, List<com.editora.config.Bookmark>>();
        keys.forEach(k -> reordered.put(k, map.get(k)));
        map.clear();
        map.putAll(reordered);
        config.saveBookmarks();
        bookmarksPanel.refresh();
    }

    private static boolean inRange(int i, int size) {
        return i >= 0 && i < size;
    }

    private static String bookmarkLabel(com.editora.config.Bookmark bm) {
        if (!bm.note().isEmpty()) {
            return bm.note();
        }
        return bm.lineText().isEmpty() ? "line " + (bm.line() + 1) : bm.lineText();
    }

    /** Opens the file (if needed) and jumps to the bookmarked line. */
    private void bookmarkActivate(Path file, int line) {
        openPath(file);
        Platform.runLater(() -> navigateToLine(line));
    }

    /** Sets a bookmark's note — via the open buffer if loaded, else directly in the persisted map. */
    private void bookmarkSetNote(Path file, int line, String note) {
        Tab tab = tabForPath(file);
        EditorBuffer open = bufferOf(tab);
        if (open != null) {
            open.getBookmarkManager().setNote(line, note);
        } else {
            updateClosedFileBookmarks(
                    file, marks -> marks.replaceAll(bm -> bm.line() == line ? bm.withNote(note) : bm));
        }
    }

    /** Deletes one bookmark — via the open buffer if loaded, else directly in the persisted map. */
    private void bookmarkDelete(Path file, int line) {
        Tab tab = tabForPath(file);
        EditorBuffer open = bufferOf(tab);
        if (open != null) {
            open.removeBookmark(line);
        } else {
            updateClosedFileBookmarks(file, marks -> marks.removeIf(bm -> bm.line() == line));
        }
    }

    /** Deletes all bookmarks in a file — via the open buffer if loaded, else the persisted map. */
    private void bookmarkDeleteAll(Path file) {
        Tab tab = tabForPath(file);
        EditorBuffer open = bufferOf(tab);
        if (open != null) {
            open.clearBookmarks();
        } else {
            config.getBookmarks().remove(file.toString());
            config.saveBookmarks();
            bookmarksPanel.refresh();
        }
    }

    /** Applies a mutation to a closed file's bookmark list in the persisted map, then saves + refreshes. */
    private void updateClosedFileBookmarks(
            Path file, java.util.function.Consumer<List<com.editora.config.Bookmark>> mutator) {
        var map = config.getBookmarks();
        List<com.editora.config.Bookmark> marks = map.get(file.toString());
        if (marks == null) {
            return;
        }
        marks = new ArrayList<>(marks);
        mutator.accept(marks);
        if (marks.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), marks);
        }
        config.saveBookmarks();
        bookmarksPanel.refresh();
    }

    // ---- Personal Notes ----

    private record NoteEntry(String fileKey, com.editora.config.PersonalNote note) {}

    /** Canonical-path key for a buffer's notes in the store (cheap; no content hashing). */
    private static String noteKey(EditorBuffer buffer) {
        Path p = buffer.getPath();
        if (p == null) {
            return "";
        }
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
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

    /** Persists the active buffer's notes (keyed by canonical path), preserving the panel's order. */
    private void persistNotes(EditorBuffer buffer) {
        if (!notesEnabled() || buffer.getPath() == null) {
            return;
        }
        String key = noteKey(buffer);
        List<com.editora.config.PersonalNote> snap = buffer.getNoteManager().snapshot();
        var map = config.getNotes();
        if (snap.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, com.editora.config.NoteStore.mergePreservingOrder(map.get(key), snap));
        }
        config.saveNotes();
        if (notesPanel != null) {
            notesPanel.refresh();
        }
    }

    /** Moves a file's bookmarks from {@code oldKey} to {@code newKey} (used by in-app rename). */
    private void migrateBookmarksKey(String oldKey, String newKey) {
        if (oldKey == null || oldKey.equals(newKey)) {
            return;
        }
        var map = config.getBookmarks();
        List<com.editora.config.Bookmark> moved = map.remove(oldKey);
        if (moved != null) {
            map.put(newKey, moved);
            config.saveBookmarks();
            if (bookmarksPanel != null) {
                bookmarksPanel.refresh();
            }
        }
    }

    /** Moves a file's personal notes from {@code oldKey} to {@code newKey} (used by in-app rename). */
    private void migrateNotesKey(String oldKey, String newKey) {
        if (oldKey == null || oldKey.equals(newKey)) {
            return;
        }
        var map = config.getNotes();
        List<com.editora.config.PersonalNote> moved = map.remove(oldKey);
        if (moved != null) {
            map.put(newKey, moved);
            config.saveNotes();
            if (notesPanel != null) {
                notesPanel.refresh();
            }
        }
    }

    /** Re-applies a file's saved notes after open, re-attaching by content hash if the file was renamed. */
    private void restoreNotes(EditorBuffer buffer) {
        if (!notesEnabled() || buffer.getPath() == null) {
            return;
        }
        String key = noteKey(buffer);
        var map = config.getNotes();
        List<com.editora.config.PersonalNote> saved = map.get(key);
        boolean rekeyed = false;
        if (saved == null && !map.isEmpty()) {
            // The file may have been renamed/moved outside Editora — match by content hash and re-key.
            String matchKey = findNoteKeyByIdentity(map, buffer.fileIdentity());
            if (matchKey != null) {
                saved = map.remove(matchKey);
                map.put(key, saved);
                rekeyed = true;
            }
        }
        boolean moved = buffer.applyNotes(saved);
        if (moved || rekeyed) {
            persistNotes(buffer); // self-heal corrected positions / re-key / orphan status
        } else if (notesPanel != null) {
            notesPanel.refresh();
        }
    }

    private static String findNoteKeyByIdentity(
            Map<String, List<com.editora.config.PersonalNote>> map, com.editora.config.FileIdentity id) {
        if (id == null) {
            return null;
        }
        for (var entry : map.entrySet()) {
            for (com.editora.config.PersonalNote n : entry.getValue()) {
                com.editora.config.FileIdentity.Match m = com.editora.config.FileIdentity.match(n.file(), id);
                if (m == com.editora.config.FileIdentity.Match.CONTENT_HASH
                        || m == com.editora.config.FileIdentity.Match.CANONICAL_PATH) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** "Add Personal Note" (context menu / command): captures the selection/caret anchor, prompts for a body. */
    private void addNoteFromContext(EditorBuffer buffer) {
        if (buffer == null || buffer.getPath() == null) {
            setStatus(tr("notes.saveFirst"));
            return;
        }
        com.editora.editor.NoteDraft draft = buffer.captureNoteDraft();
        promptNoteBody("", body -> {
            buffer.getNoteManager()
                    .add(com.editora.config.PersonalNote.create(
                            buffer.fileIdentity(), draft.scope(), draft.anchor(), body, List.of()));
            buffer.refreshGutter();
        });
    }

    private void addNoteAtCaret() {
        addNoteFromContext(activeBuffer());
    }

    private void promptNoteBody(String initial, java.util.function.Consumer<String> onAccept) {
        showNoteDialog(initial, onAccept, null);
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
     * Multi-line note editor as an in-scene overlay. Saves the (non-blank) body via {@code onAccept}; when
     * {@code onDelete} is non-null an extra Delete button is shown (used when editing an existing note).
     * Enter inserts a newline; Ctrl/Cmd+Enter saves.
     */
    private void showNoteDialog(String initial, java.util.function.Consumer<String> onAccept, Runnable onDelete) {
        TextArea editor = new TextArea(initial == null ? "" : initial);
        editor.setWrapText(true);
        editor.setPrefRowCount(6);
        editor.setPrefColumnCount(42);
        // Honor the user's configured keybindings (Emacs caret movement + basic editing) in the note box.
        com.editora.command.TextInputKeymap.install(editor, keymap);
        Label prompt = new Label(tr("dialog.note.content"));
        VBox body = new VBox(6, prompt, editor);
        OverlayInput.Extra extra = onDelete == null ? null : new OverlayInput.Extra(tr("notes.delete"), onDelete);
        OverlayInput.show(
                overlayHost,
                tr("dialog.note.title"),
                body,
                editor,
                tr("dialog.save"),
                null,
                () -> {
                    String text = editor.getText().strip();
                    if (!text.isBlank()) {
                        onAccept.accept(text);
                    }
                },
                extra,
                true);
    }

    private void onGutterNoteClick(EditorBuffer buffer, int line) {
        var ns = buffer.getNoteManager().notesOnLine(line);
        if (!ns.isEmpty()) {
            editOpenBufferNote(buffer, ns.get(0));
        }
    }

    private void editOpenBufferNote(EditorBuffer buffer, com.editora.config.PersonalNote note) {
        showNoteDialog(note.body(), body -> buffer.getNoteManager().update(note.withBody(body)), () -> {
            buffer.getNoteManager().remove(note.id());
            buffer.refreshGutter();
        });
    }

    /** Deletes the (first) personal note on the active buffer's caret line. */
    private void deleteNoteAtCaret() {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        var ns = b.getNoteManager().notesOnLine(b.getArea().getCurrentParagraph());
        if (ns.isEmpty()) {
            setStatus(tr("status.noNotesInFile"));
            return;
        }
        b.getNoteManager().remove(ns.get(0).id());
        b.refreshGutter();
    }

    private void jumpNote(boolean forward) {
        EditorBuffer b = activeBuffer();
        if (b == null) {
            return;
        }
        int from = b.getArea().getCurrentParagraph();
        Integer target =
                forward ? b.getNoteManager().next(from) : b.getNoteManager().previous(from);
        if (target != null) {
            navigateToLine(target);
        } else {
            setStatus(tr("status.noNotesInFile"));
        }
    }

    /** Opens a cross-file picker that searches notes by their full body, tags, and file path. */
    private void searchNotes() {
        notesSearchPalette.show(stage);
    }

    /** The text {@code notes.search} matches against: the note's body + tags + file path. */
    private static String noteSearchText(NoteEntry e) {
        com.editora.config.PersonalNote n = e.note();
        return n.body() + " " + String.join(" ", n.tags()) + " " + e.fileKey();
    }

    private List<NoteEntry> allNoteEntries() {
        List<NoteEntry> out = new ArrayList<>();
        config.getNotes().forEach((key, notes) -> {
            if (notes != null) {
                notes.forEach(n -> out.add(new NoteEntry(key, n)));
            }
        });
        return out;
    }

    private static String noteEntryLabel(com.editora.config.PersonalNote n) {
        String body = n.body().strip();
        String first = body.isEmpty() ? "" : body.lines().findFirst().orElse("");
        return first.isEmpty() ? tr("notes.empty") : first;
    }

    // --- NotesPanel.Actions (open buffer if loaded, else mutate the persisted closed-file list) ---

    private void noteActivate(String fileKey, com.editora.config.PersonalNote note) {
        String path = note.file() != null && !note.file().path().isBlank()
                ? note.file().path()
                : fileKey;
        openPath(Path.of(path));
        int line = note.anchor().line();
        Platform.runLater(() -> navigateToLine(line));
    }

    private void noteEditBody(String fileKey, com.editora.config.PersonalNote note) {
        EditorBuffer open = bufferOf(tabForKey(fileKey));
        if (open != null) {
            editOpenBufferNote(open, note);
        } else {
            showNoteDialog(
                    note.body(),
                    body -> updateClosedFileNotes(
                            fileKey, list -> list.replaceAll(n -> n.id().equals(note.id()) ? n.withBody(body) : n)),
                    () -> updateClosedFileNotes(fileKey, list -> list.removeIf(n -> n.id().equals(note.id()))));
        }
    }

    private void noteSetStatus(
            String fileKey, com.editora.config.PersonalNote note, com.editora.config.NoteStatus status) {
        EditorBuffer open = bufferOf(tabForKey(fileKey));
        if (open != null) {
            open.getNoteManager().setStatus(note.id(), status);
        } else {
            updateClosedFileNotes(
                    fileKey, list -> list.replaceAll(n -> n.id().equals(note.id()) ? n.withStatus(status) : n));
        }
    }

    private void noteDelete(String fileKey, com.editora.config.PersonalNote note) {
        Tab tab = tabForKey(fileKey);
        if (tab != null) {
            EditorBuffer b = bufferOf(tab);
            b.getNoteManager().remove(note.id());
            b.refreshGutter();
        } else {
            updateClosedFileNotes(fileKey, list -> list.removeIf(n -> n.id().equals(note.id())));
        }
    }

    private void noteDeleteAll(String fileKey) {
        Tab tab = tabForKey(fileKey);
        if (tab != null) {
            EditorBuffer b = bufferOf(tab);
            b.getNoteManager().clear();
            b.refreshGutter();
        } else {
            config.getNotes().remove(fileKey);
            config.saveNotes();
            notesPanel.refresh();
        }
    }

    private void updateClosedFileNotes(
            String fileKey, java.util.function.Consumer<List<com.editora.config.PersonalNote>> mutator) {
        var map = config.getNotes();
        List<com.editora.config.PersonalNote> list = map.get(fileKey);
        if (list == null) {
            return;
        }
        list = new ArrayList<>(list);
        mutator.accept(list);
        if (list.isEmpty()) {
            map.remove(fileKey);
        } else {
            map.put(fileKey, list);
        }
        config.saveNotes();
        notesPanel.refresh();
    }

    private void exportNotes() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.exportNotes.title"));
        chooser.setInitialFileName("notes.json");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File f = chooser.showSaveDialog(stage);
        if (f == null) {
            return;
        }
        try {
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValue(f, config.getNotes());
            setStatus(tr("status.notesExported", f.toString()));
        } catch (IOException e) {
            setStatus(tr("status.notesExportFailed", e.getMessage()));
        }
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
    private void exportMermaid() {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isDiagram()) {
            setStatus(tr("status.mermaid.notDiagram"));
            return;
        }
        String source = b.getContent();
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("dialog.mermaidExport.title"));
        String base = bufferBaseName(b);
        int dot = base.lastIndexOf('.');
        chooser.setInitialFileName((dot > 0 ? base.substring(0, dot) : base) + ".svg");
        chooser.getExtensionFilters()
                .addAll(
                        new javafx.stage.FileChooser.ExtensionFilter("SVG", "*.svg"),
                        new javafx.stage.FileChooser.ExtensionFilter("PNG", "*.png"),
                        new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File f = chooser.showSaveDialog(stage);
        if (f == null) {
            return;
        }
        setStatus(tr("status.mermaid.exporting"));
        mermaidService.export(source, f.toPath(), appThemeDark(), r -> {
            if (r.ok()) {
                setStatus(tr("status.mermaid.exported", f.toString()));
            } else {
                String msg = r.message();
                setStatus(tr("status.mermaid.exportFailed", msg));
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.initOwner(stage);
                err.setTitle(tr("dialog.mermaidExport.title"));
                err.setHeaderText(tr("status.mermaid.exportFailed", ""));
                err.setContentText(msg);
                err.showAndWait();
            }
        });
    }

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
        String base = bufferBaseName(b);
        java.io.File f = choosePdfDestination(base);
        if (f == null) {
            return;
        }
        setStatus(tr("status.pdf.exporting"));
        if (b.isDiagram()) {
            mermaidService.export(
                    b.getContent(),
                    f.toPath(),
                    appThemeDark(),
                    r -> reportPdf(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message()), f));
        } else {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            java.util.List<String> mmdc = mermaidEnabled() ? mermaidService.mmdcCommand() : null;
            pdfService.exportMarkdown(
                    b.getContent(),
                    baseDir,
                    config.getSettings().getPdfPageSize(),
                    mmdc,
                    f.toPath(),
                    r -> reportPdf(r, f));
        }
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
        if (b.isDiagram()) {
            java.util.List<String> mmdc = mermaidEnabled() ? mermaidService.mmdcCommand() : null;
            printService.prepareMermaid(
                    b.getContent(), mmdc, appThemeDark(), prepared -> openPrintPreview(job, prepared));
        } else {
            java.nio.file.Path baseDir =
                    b.getPath() == null ? null : b.getPath().getParent();
            printService.prepareMarkdown(b.getContent(), baseDir, prepared -> openPrintPreview(job, prepared));
        }
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

    private void applyViewSettings(EditorBuffer buffer) {
        Settings s = config.getSettings();
        int effectiveFont = Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom()));
        buffer.setFont(s.getFontFamily(), effectiveFont);
        buffer.setColumnRulerVisible(s.isShowColumnRuler());
        buffer.setNoteIndicatorsVisible(s.isNotesSupport() && s.isShowNoteIndicators());
        buffer.setLineHighlightOn(s.isHighlightCurrentLine());
        boolean simple = simpleModeActive(); // Simple UI mode hides the whole gutter + minimap (without
        buffer.setLineNumbersVisible(s.isShowLineNumbers() && !simple); // clobbering the saved prefs)
        buffer.setMinimapVisible(s.isShowMinimap() && !simple);
        buffer.setGutterVisible(!simple); // Simple mode removes the entire gutter strip
        if (simple) {
            buffer.unfoldAll(); // collapsed regions would be stranded behind the now-hidden fold chevrons
        }
        buffer.setWhitespaceVisible(s.isShowWhitespace());
        buffer.setTabSize(s.getTabSize());
        buffer.setLineHighlightColor(EditorThemes.lineHighlightFor(s.getEditorTheme()));
        buffer.setMinimapColors(
                EditorThemes.minimapTextFor(s.getEditorTheme()), EditorThemes.minimapViewportFor(s.getEditorTheme()));
        buffer.setFoldPreviewColors(
                EditorThemes.editorBackgroundFor(s.getEditorTheme()),
                EditorThemes.editorForegroundFor(s.getEditorTheme()));
        buffer.setSpellLanguage(spellLanguageFor(buffer)); // per-file override, else the global default
        buffer.setSpellCheckEnabled(s.isSpellCheck());
        buffer.setFormatBarEnabled(s.isMarkdownFormatBar());
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
        applyGitSupport();
        applyLocalHistory(); // re-gate the Local File History tool window + refresh its list
        applyGitBlame(); // (re)apply inline blame to the active buffer (effective gate: git + setting + !simple)
        applyNotesSupport();
        applyMermaidSupport();
        applyHttpClientSupport();
        applyHtmlPreviewSupport();
        applyMcpSupport();
        applyAutoSave();
        applyAutocomplete();
        applyMultiCaret();
        applyLspSupport(); // (re)configure LSP: command/enabled change re-detects + re-gates buffers
        applyDebugSupport(); // (re)configure DAP after LSP (it layers on jdtls)
        applyMarkdownPreviewTheme(); // re-resolve "follow app" previews + the toggle glyph after a theme change
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
        boolean mermaidAc = effectiveMermaidAutocomplete();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.setAutocomplete(
                        s.isAutocomplete(), s.isAutocompleteProse(), s.isAutocompleteSnippets(), mermaidAc);
            }
        }
    }

    /** Mermaid autocomplete is effective only with the master toggle, its own toggle, the feature on,
     *  and the mmdc CLI detected. */
    private boolean effectiveMermaidAutocomplete() {
        Settings s = config.getSettings();
        return s.isAutocomplete() && s.isAutocompleteMermaid() && s.isMermaidSupport() && mermaidAvail.mmdc();
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
     * comment for a multi-line selection, depending on the language (see {@link com.editora.editor.Commenter}).
     */
    /** Manually opens the autocomplete popup for the active buffer (the {@code edit.completion} command). */
    private void triggerCompletion() {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.triggerCompletion();
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
        CodeArea area = buffer.getFocusedArea();
        com.editora.editor.Commenter.CommentStyle style = com.editora.editor.Commenter.styleFor(buffer.getLanguage());
        com.editora.editor.Commenter.Edit edit = com.editora.editor.Commenter.toggle(
                area.getText(),
                area.getSelection().getStart(),
                area.getSelection().getEnd(),
                style);
        if (edit == null) {
            setStatus(tr("status.noCommentSyntax"));
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.selectRange(edit.selStart(), edit.selEnd());
        area.requestFocus();
    }

    /** Applies an Emacs transpose (chars/words/lines) to the active editable buffer at the caret. */
    private void transpose(java.util.function.BiFunction<String, Integer, com.editora.editor.Transposer.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editor.Transposer.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /** Applies a pure {@link com.editora.editor.LineOps} edit to the active area (duplicate / move line). */
    private void lineOp(java.util.function.BiFunction<String, Integer, com.editora.editor.LineOps.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editor.LineOps.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /** Emacs {@code fill-paragraph} (`M-q`): re-wrap the paragraph at the caret to the fill column. */
    private void fillParagraph() {
        applyFill((text, b) -> com.editora.editor.Filler.fillParagraph(
                text, b.getFocusedArea().getCaretPosition(), fillColumn(), lineCommentFor(b)));
    }

    /** Emacs {@code fill-region}: re-wrap every paragraph in the selection (caret line if no selection). */
    private void fillRegion() {
        applyFill((text, b) -> {
            CodeArea a = b.getFocusedArea();
            int start = a.getSelection().getLength() > 0 ? a.getSelection().getStart() : a.getCaretPosition();
            int end = a.getSelection().getLength() > 0 ? a.getSelection().getEnd() : a.getCaretPosition();
            return com.editora.editor.Filler.fillRegion(text, start, end, fillColumn(), lineCommentFor(b));
        });
    }

    /** Shared applier for the fill commands (guarded by {@link #activeEditable()}). */
    private void applyFill(java.util.function.BiFunction<String, EditorBuffer, com.editora.editor.Filler.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editor.Filler.Edit edit = op.apply(area.getText(), buffer);
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
                com.editora.editor.Commenter.styleFor(buffer.getLanguage()).line();
        return line == null || line.isBlank() ? null : line;
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
    private static int backToIndentation(CodeArea a) {
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        int col = 0;
        while (col < line.length() && (line.charAt(col) == ' ' || line.charAt(col) == '\t')) {
            col++;
        }
        return a.getAbsolutePosition(par, col);
    }

    /**
     * Target column for a "smart" line start (C-a): the first non-whitespace column — the beginning of
     * the line's <em>text</em> — or column 0 when the caret is already there (so a second press toggles
     * to the true line start). Pure for unit testing.
     */
    static int smartLineStartColumn(String lineText, int caretCol) {
        int indent = 0;
        while (indent < lineText.length() && (lineText.charAt(indent) == ' ' || lineText.charAt(indent) == '\t')) {
            indent++;
        }
        return caretCol == indent ? 0 : indent;
    }

    /** Absolute offset for the smart line start on the caret's current line (see {@link #smartLineStartColumn}). */
    private static int smartLineStart(CodeArea a) {
        int par = a.getCurrentParagraph();
        int col = smartLineStartColumn(a.getParagraph(par).getText(), a.getCaretColumn());
        return a.getAbsolutePosition(par, col);
    }

    /** Start of the blank line below the current block, or document end (Emacs M-}). */
    private static int forwardParagraph(CodeArea a) {
        int n = a.getParagraphs().size();
        int p = a.getCurrentParagraph() + 1;
        while (p < n && a.getParagraph(p).getText().isBlank()) {
            p++; // skip blank lines we're sitting on
        }
        while (p < n && !a.getParagraph(p).getText().isBlank()) {
            p++; // through the text block
        }
        return p >= n ? a.getLength() : a.getAbsolutePosition(p, 0);
    }

    /** Start of the blank line above the current block, or document start (Emacs M-{). */
    private static int backwardParagraph(CodeArea a) {
        int p = a.getCurrentParagraph() - 1;
        while (p >= 0 && a.getParagraph(p).getText().isBlank()) {
            p--;
        }
        while (p >= 0 && !a.getParagraph(p).getText().isBlank()) {
            p--;
        }
        return p < 0 ? 0 : a.getAbsolutePosition(p, 0);
    }

    /** Offset at the start of the next sentence after {@code caret} (Emacs M-e). */
    private static int forwardSentence(String text, int caret) {
        int n = text.length();
        int i = caret;
        while (i < n) {
            char c = text.charAt(i++);
            if (isSentenceEnd(c)) {
                while (i < n
                        && (text.charAt(i) == '"'
                                || text.charAt(i) == '\''
                                || text.charAt(i) == ')'
                                || text.charAt(i) == ']')) {
                    i++; // closing quotes/brackets stay with the sentence
                }
                if (i >= n || Character.isWhitespace(text.charAt(i))) {
                    while (i < n && Character.isWhitespace(text.charAt(i))) {
                        i++;
                    }
                    return i;
                }
            }
        }
        return n;
    }

    /** Offset at the start of the sentence containing/just before {@code caret} (Emacs M-a). */
    private static int backwardSentence(String text, int caret) {
        int i = caret - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--; // skip whitespace immediately before the caret
        }
        // If we're sitting right after a terminator (caret already at a sentence start), skip it so
        // repeated presses keep moving back instead of landing on the same spot.
        while (i >= 0 && isSentenceEnd(text.charAt(i))) {
            i--;
        }
        while (i >= 0 && !isSentenceEnd(text.charAt(i))) {
            i--; // back over the sentence body to the previous sentence's terminator
        }
        if (i < 0) {
            return 0;
        }
        int j = i + 1;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        return j;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?';
    }

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

    private void registerCommands() {
        registry.register(Command.of("file.new", this::onNew));
        registry.register(Command.of("file.open", this::onOpen));
        registry.register(Command.of("file.find", () -> fileFinder.show(stage)));
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
        registry.register(Command.of("file.saveAs", this::onSaveAs));
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
        registry.register(Command.of("view.toggleColumnRuler", this::toggleColumnRuler));
        registry.register(Command.of("view.toggleToolStripe", this::toggleToolStripe));
        registry.register(Command.of("view.toggleSimpleMode", this::toggleSimpleMode));
        registry.register(Command.of("view.togglePlugins", this::togglePluginSupport));
        registry.register(Command.of("plugins.browse", this::browsePlugins));
        registry.register(Command.of("plugins.installFromDisk", this::installPluginFromDisk));
        registry.register(Command.of("config.export", this::exportConfig));
        registry.register(Command.of("editor.exportPdf", this::exportCodePdf));
        registry.register(Command.of("preview.exportPdf", this::exportPreviewPdf));
        registry.register(Command.of("editor.print", this::printCode));
        registry.register(Command.of("preview.print", this::printPreview));
        registry.register(Command.of("mermaid.export", () -> ifMermaid(this::exportMermaid)));
        registry.register(Command.of("htmlPreview.open", () -> ifHtmlPreview(this::htmlPreviewOpen)));
        registry.register(Command.of("htmlPreview.openIn", () -> ifHtmlPreview(this::htmlPreviewOpenIn)));
        registry.register(Command.of("view.toggleHtmlPreview", this::toggleHtmlPreviewSupport));
        registry.register(Command.of("mcp.copyEndpoint", () -> ifMcp(this::copyMcpEndpoint)));
        registry.register(Command.of("view.toggleMcp", this::toggleMcpSupport));
        registry.register(Command.of("view.toggleLineHighlight", this::toggleLineHighlight));
        registry.register(Command.of("view.toggleLineNumbers", this::toggleLineNumbers));
        registry.register(Command.of("view.toggleMinimap", this::toggleMinimap));
        registry.register(Command.of("view.toggleWhitespace", this::toggleWhitespace));
        registry.register(Command.of("view.toggleSpellCheck", this::toggleSpellCheck));
        registry.register(Command.of("view.toggleAutocomplete", this::toggleAutocomplete));
        registry.register(Command.of("view.toggleAutocompleteProse", this::toggleAutocompleteProse));
        registry.register(Command.of("view.toggleAutocompleteSnippets", this::toggleAutocompleteSnippets));
        registry.register(Command.of("view.toggleAutocompleteMermaid", this::toggleAutocompleteMermaid));
        registry.register(Command.of("view.toggleMultiCaret", this::toggleMultiCaret));
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
        registry.register(Command.of("spell.setLanguage", this::chooseSpellLanguage));
        registry.register(Command.of("view.toggleToolbar", this::toggleToolbar));
        registry.register(Command.of("view.toggleStatusBar", this::toggleStatusBar));
        registry.register(Command.of("view.toggleTabBar", this::toggleTabBar));
        registry.register(Command.of("view.toggleBreadcrumb", this::toggleBreadcrumb));
        registry.register(Command.of("view.toggleZen", this::toggleZen));
        registry.register(Command.of("view.toggleReadOnly", this::toggleReadOnly));
        registry.register(Command.of("file.toggleAutoSave", this::toggleAutoSave));
        registry.register(Command.of("recent.jump", () -> recentPalette.show(stage)));
        registry.register(Command.of("structure.jump", () -> structurePalette.show(stage)));
        registry.register(Command.of("buffer.jump", () -> openFilesPalette.show(stage)));
        registry.register(Command.of("tool.jump", () -> toolWindowPalette.show(stage)));
        registry.register(Command.of("bookmarks.toggle", this::toggleBookmarkAtCaret));
        registry.register(Command.of("bookmarks.editNote", this::editBookmarkNoteAtCaret));
        registry.register(Command.of("bookmarks.next", () -> jumpBookmark(true)));
        registry.register(Command.of("bookmarks.previous", () -> jumpBookmark(false)));
        registry.register(Command.of("bookmarks.jump", () -> bookmarkPalette.show(stage)));
        registry.register(Command.of("bookmarks.clearFile", this::clearBookmarksInFile));
        registry.register(Command.of("notes.add", () -> ifNotes(this::addNoteAtCaret)));
        registry.register(Command.of("notes.next", () -> ifNotes(() -> jumpNote(true))));
        registry.register(Command.of("notes.previous", () -> ifNotes(() -> jumpNote(false))));
        registry.register(Command.of("notes.jump", () -> ifNotes(() -> notesPalette.show(stage))));
        registry.register(Command.of("notes.search", () -> ifNotes(this::searchNotes)));
        registry.register(Command.of("notes.delete", () -> ifNotes(this::deleteNoteAtCaret)));
        registry.register(Command.of("notes.export", () -> ifNotes(this::exportNotes)));
        registry.register(Command.of("snippets.insert", this::insertSnippetPicker));
        registry.register(Command.of("snippets.reload", () -> {
            snippets.reload();
            setStatus(tr("status.snippetsReloaded"));
        }));
        registry.register(Command.of("snippets.editUser", this::editUserSnippets));
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
        registry.register(Command.of("markdown.headingPromote", () -> withMarkdown(b -> b.formatHeading(-1))));
        registry.register(Command.of("markdown.headingDemote", () -> withMarkdown(b -> b.formatHeading(1))));
        registry.register(Command.of("markdown.openLink", this::markdownOpenLink));
        registry.register(Command.of("markdown.reflowTable", this::markdownReflowTable));
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
        registry.register(Command.of("view.welcome", this::showWelcome));
        registry.register(Command.of("view.messageLog", statusBar::showMessageLog));
        registry.register(Command.of("view.debugLog", this::showDebugLog));
        registry.register(Command.of("tool.project", () -> {
            if (projectsEnabled()) {
                toolWindows.toggle(projectToolWindow);
            }
        }));
        registry.register(Command.of("tool.structure", () -> toolWindows.toggle(structureToolWindow)));
        registry.register(Command.of("tool.bookmarks", () -> toolWindows.toggle(bookmarksToolWindow)));
        registry.register(Command.of("tool.notes", () -> ifNotes(() -> toolWindows.toggle(notesToolWindow))));
        registry.register(Command.of("tool.fileInformation", () -> toolWindows.toggle(fileInfoToolWindow)));
        registry.register(Command.of("tool.search", () -> toolWindows.toggle(searchToolWindow)));
        registry.register(Command.of("search.inFiles", this::openSearchInFiles));
        registry.register(Command.of("nav.aceJump", this::startAceJump));
        // Run a Java 25 compact source file (also surfaced as the toolbar Run button when one is active).
        registry.register(Command.of("file.run", this::runActiveFile));
        registry.register(Command.of("file.runWithArgs", this::runActiveFileWithArgs));
        registry.register(Command.of("run.rerun", this::rerunLast));
        registry.register(Command.of("run.stop", this::stopRun));
        registry.register(Command.of("tool.run", () -> toolWindows.toggle(runToolWindow)));
        // HTTP Client (.http via ijhttp). Gated by the "Enable HTTP Client" setting (default off).
        registry.register(Command.of("http.runRequest", () -> ifHttp(this::runHttpRequestAtCaret)));
        registry.register(Command.of("http.runFile", () -> ifHttp(this::runHttpFile)));
        registry.register(
                Command.of("http.selectEnvironment", () -> ifHttp(() -> toolWindows.open(httpToolWindow, true))));
        registry.register(Command.of("http.importCurl", () -> ifHttp(this::importCurlFromClipboard)));
        registry.register(Command.of("http.copyAsCurl", () -> ifHttp(this::copyActiveHttpAsCurl)));
        registry.register(Command.of("http.openResponseInTab", () -> ifHttp(this::openActiveHttpResponseInTab)));
        registry.register(Command.of("tool.http", () -> ifHttp(() -> toolWindows.toggle(httpToolWindow))));
        // Debugging (DAP). Gated by the "Enable Java debugging" setting (default off).
        registry.register(Command.of("debug.start", () -> ifDebug(this::debugStart)));
        registry.register(Command.of("debug.stop", () -> ifDebug(dapManager::stop)));
        registry.register(Command.of("debug.restart", () -> ifDebug(dapManager::restart)));
        registry.register(Command.of("debug.attach", () -> ifDebug(this::debugAttach)));
        registry.register(Command.of("debug.continue", () -> ifDebug(dapManager::resume)));
        registry.register(Command.of("debug.pause", () -> ifDebug(dapManager::pause)));
        registry.register(Command.of("debug.runToCursor", () -> ifDebug(this::debugRunToCursor)));
        registry.register(Command.of("debug.jumpToLine", () -> ifDebug(this::debugJumpToLine)));
        registry.register(Command.of("debug.stepOver", () -> ifDebug(dapManager::stepOver)));
        registry.register(Command.of("debug.stepInto", () -> ifDebug(dapManager::stepInto)));
        registry.register(Command.of("debug.stepOut", () -> ifDebug(dapManager::stepOut)));
        registry.register(Command.of("debug.toggleBreakpoint", () -> ifDebug(this::toggleBreakpointAtCaret)));
        registry.register(Command.of("debug.editBreakpoint", () -> ifDebug(this::editBreakpointAtCaret)));
        registry.register(
                Command.of("debug.toggleExceptionBreakpoints", () -> ifDebug(this::toggleExceptionBreakpoints)));
        registry.register(Command.of("tool.debug", () -> ifDebug(() -> toolWindows.toggle(debugToolWindow))));
        // LSP. Gated by the "Enable LSP" setting (default off); commands no-op with a status when off.
        registry.register(Command.of("tool.problems", () -> ifLsp(() -> toolWindows.toggle(problemsToolWindow))));
        registry.register(Command.of("lsp.gotoDefinition", () -> ifLsp(this::lspGotoDefinition)));
        registry.register(Command.of("lsp.findReferences", () -> ifLsp(this::lspFindReferences)));
        registry.register(Command.of("lsp.hover", () -> ifLsp(this::lspShowHover)));
        registry.register(Command.of("lsp.restartServers", () -> ifLsp(this::restartLspServers)));
        registry.register(Command.of("view.toggleLsp", this::toggleLsp));
        registry.register(Command.of("tool.commit", () -> ifGit(() -> toolWindows.toggle(commitToolWindow))));
        // Git (native CLI). Gated by the "Enable Git" setting (default off); also no-op when Git is
        // absent / not in a repo. The ifGit wrapper disables the commands + keybindings when Git is off.
        registry.register(Command.of("remote.connect", this::connectRemote));
        registry.register(Command.of("remote.openFile", this::openRemoteFile));
        registry.register(Command.of("remote.manageConnections", this::manageRemoteConnections));
        registry.register(Command.of("remote.disconnect", this::disconnectRemote));
        registry.register(Command.of("git.clone", () -> ifGit(this::gitClone)));
        registry.register(Command.of("git.commit", () -> ifGit(this::gitCommitFocus)));
        registry.register(Command.of("git.stageFile", () -> ifGit(this::gitStageActiveFile)));
        registry.register(Command.of("git.switchBranch", () -> ifGit(this::chooseBranch)));
        registry.register(Command.of("git.newBranch", () -> ifGit(this::newBranch)));
        registry.register(Command.of("git.fetch", () -> ifGit(() -> gitSync("Fetch", "fetch", "--all"))));
        registry.register(Command.of("git.pull", () -> ifGit(() -> gitSync("Pull", "pull", "--ff-only"))));
        registry.register(Command.of("git.push", () -> ifGit(this::gitPush)));
        registry.register(Command.of(
                "git.refresh",
                () -> ifGit(() -> {
                    gitService.invalidateCaches();
                    afterGitMutation();
                })));
        // History / Log, blame, and stash (Core-trio parity with IntelliJ/VSCode).
        registry.register(Command.of("tool.gitLog", () -> ifGit(this::showGitLog)));
        registry.register(Command.of("tool.fileHistory", this::showLocalHistory));
        registry.register(Command.of("git.fileHistory", () -> ifGit(this::showFileHistory)));
        registry.register(Command.of("git.toggleBlame", this::toggleGitBlame));
        registry.register(Command.of("git.blameShowCommit", () -> ifGit(this::blameShowCommit)));
        registry.register(Command.of("git.stash", () -> ifGit(this::gitStash)));
        registry.register(Command.of("git.stashPop", () -> ifGit(this::gitStashPop)));
        registry.register(Command.of("git.unstash", () -> ifGit(this::gitUnstash)));
        registry.register(Command.of("git.stashDrop", () -> ifGit(this::gitStashDrop)));
        // Diff viewer + merge. The git-backed diffs are ifGit-gated; "Compare With…" and "Resolve
        // Conflicts" work on any file (no repo needed), so they are not gated.
        registry.register(Command.of("diff.vsHead", () -> ifGit(this::diffActiveVsHead)));
        registry.register(Command.of("diff.compareWith", this::compareActiveWithFile));
        registry.register(Command.of("diff.vsCommit", () -> ifGit(this::diffActiveVsCommit)));
        registry.register(Command.of("merge.resolve", this::resolveConflicts));
        registry.register(Command.of("switcher.show", () -> switcher.show(stage, false)));
        registry.register(Command.of("switcher.showReverse", () -> switcher.show(stage, true)));
        registry.register(Command.of("find.show", this::findShowOrNext));
        registry.register(Command.of("find.showBackward", this::findShowOrPrevious));
        registry.register(Command.of("find.replace", this::showReplace));
        registry.register(Command.of("edit.cut", this::onCut));
        registry.register(Command.of("edit.copy", this::onCopy));
        registry.register(Command.of("edit.paste", this::onPaste));
        registry.register(Command.of("edit.undo", this::onUndo));
        registry.register(Command.of("edit.redo", this::onRedo));
        registry.register(Command.of("edit.cancel", this::cancel));
        registry.register(Command.of("edit.completion", this::triggerCompletion));
        registry.register(Command.of("edit.toggleComment", this::toggleComment));
        registry.register(
                Command.of("edit.transposeChars", () -> transpose(com.editora.editor.Transposer::transposeChars)));
        registry.register(
                Command.of("edit.transposeWords", () -> transpose(com.editora.editor.Transposer::transposeWords)));
        registry.register(
                Command.of("edit.transposeLines", () -> transpose(com.editora.editor.Transposer::transposeLines)));
        registry.register(Command.of("edit.selectAll", this::selectAll));
        registry.register(Command.of("edit.duplicateLine", () -> lineOp(com.editora.editor.LineOps::duplicateLine)));
        registry.register(Command.of("edit.moveLineUp", () -> lineOp(com.editora.editor.LineOps::moveLineUp)));
        registry.register(Command.of("edit.moveLineDown", () -> lineOp(com.editora.editor.LineOps::moveLineDown)));
        // Emacs fill commands: re-wrap paragraphs to the fill column (M-q / fill-region / set-fill-column).
        registry.register(Command.of("edit.fillParagraph", this::fillParagraph));
        registry.register(Command.of("edit.fillRegion", this::fillRegion));
        registry.register(Command.of("edit.setFillColumn", this::setFillColumn));
        // C-a: smart line start — first press to the beginning of the line's text (first non-whitespace),
        // a second press toggles to the true line start (column 0).
        registry.register(Command.of(
                "nav.lineStart", () -> moveAndFollow(a -> a.moveTo(smartLineStart(a), selPolicy()))));
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
                "nav.backToIndentation", () -> moveAndFollow(a -> a.moveTo(backToIndentation(a), selPolicy()))));
        registry.register(Command.of(
                "nav.paragraphForward", () -> moveAndFollow(a -> a.moveTo(forwardParagraph(a), selPolicy()))));
        registry.register(Command.of(
                "nav.paragraphBackward", () -> moveAndFollow(a -> a.moveTo(backwardParagraph(a), selPolicy()))));
        registry.register(Command.of(
                "nav.sentenceForward",
                () -> moveAndFollow(a -> a.moveTo(forwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of(
                "nav.sentenceBackward",
                () -> moveAndFollow(a -> a.moveTo(backwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
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
