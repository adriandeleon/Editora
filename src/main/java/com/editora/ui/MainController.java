package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;
import com.editora.editor.SpellDictionaries;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

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

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.util.Duration;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Controls the main window: tabbed editors, menu actions, palette/find overlays, and status bar. */
public class MainController {

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
    private Button splitVerticalButton;
    @FXML
    private Button splitHorizontalButton;
    @FXML
    private Button paletteButton;
    @FXML
    private Button closeTabButton;
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
    private QuickOpen<Path> recentPalette;
    private QuickOpen<StructurePanel.Outline> structurePalette;
    private QuickOpen<Tab> openFilesPalette;
    private QuickOpen<ToolWindow> toolWindowPalette;
    private QuickOpen<BookmarkEntry> bookmarkPalette;
    private QuickOpen<com.editora.snippet.Snippet> snippetPalette;
    private com.editora.snippet.SnippetManager snippets;
    private com.editora.completion.CompletionEngine completion;
    private FileFinder fileFinder;
    private FileFinder folderFinder;
    private ProjectPanel projectPanel;
    private ProjectManager projects;
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
    private QuickOpen<NoteEntry> notesPalette;
    private QuickOpen<NoteEntry> notesSearchPalette;
    // --- Git (native-CLI integration; off-thread via GitService) ---
    private final com.editora.git.GitService gitService = new com.editora.git.GitService();
    private final com.editora.mermaid.MermaidService mermaidService = new com.editora.mermaid.MermaidService();
    private final com.editora.pdf.PdfExportService pdfService = new com.editora.pdf.PdfExportService();
    private boolean mermaidSupportApplied;
    private com.editora.mermaid.MermaidService.Availability mermaidAvail =
            new com.editora.mermaid.MermaidService.Availability(false, false);
    private GitPanel gitPanel;
    private ToolWindow commitToolWindow;
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
            if (!confirmQuit() || !confirmCloseAllBuffers()) {
                e.consume();
            }
        });
        // Detect files changed by another program: re-check open files whenever the window regains focus.
        stage.focusedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                checkExternalChanges();
                refreshGit(); // another tool may have changed the repo while we were away
                if (projectPanel != null) {
                    projectPanel.refreshTree(); // pick up files/folders added or removed outside Editora
                }
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        this.snippets = new com.editora.snippet.SnippetManager(config);
        this.completion = new com.editora.completion.CompletionEngine(snippets, config::getUserDictionary);
        // Project commands (incl. the Project tool window) are hidden from the palette unless project
        // support is enabled.
        // Project + Git commands are hidden from the palette unless their feature is enabled.
        this.palette = new CommandPalette(registry, keymap,
                c -> (config.getSettings().isProjectSupport()
                        || (!c.id().startsWith("project.") && !c.id().equals("tool.project")))
                        && (config.getSettings().isGitSupport()
                        || (!c.id().startsWith("git.") && !c.id().equals("tool.commit")))
                        && (config.getSettings().isNotesSupport()
                        || (!c.id().startsWith("notes.") && !c.id().equals("tool.notes")))
                        && (config.getSettings().isMermaidSupport()
                        || !c.id().startsWith("mermaid.")));
        this.findBar = new FindReplaceBar(this::activeArea, this::setStatus);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        this.breadcrumb = new FileBreadcrumb(this::openPath);
        // Breadcrumb sits just above the status bar at the bottom (IntelliJ-style).
        bottomBox.getChildren().setAll(breadcrumb, statusBar);
        setupToolWindows();
        this.settingsWindow = new SettingsWindow(config, toolWindows, gitService, mermaidService,
                this::applyViewSettingsToAllBuffers, this::setZenMode, this::openPath,
                this::exportConfig);
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
        toolWindows.restore();
        // Honor a persisted Zen state on launch: the view options + chrome already read the flag via
        // the apply paths; this hides the side stripes (restore() opened nothing — windows were
        // persisted closed when Zen was entered).
        toolWindows.setZenStripesHidden(config.getWorkspaceState().isZenMode());
        applyChromeVisibility();
        applyProjectSupport(); // hide project UI when disabled (default)
        applyGitSupport(); // hide Git UI when disabled (default)
        applyNotesSupport(); // hide Personal Notes UI when disabled (default)
        applyMermaidSupport(); // wire mmdc/maid paths; mermaid rendering off when disabled (default)
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
        breadcrumb.setEnabled(s.isShowBreadcrumb());
        // Tool stripes (UI only): hidden stripes still let tool windows open via keybinding/palette.
        toolWindows.setStripesEnabled(s.isShowToolStripe());
        updateZenButton();
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
        updateZenButton();
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

        setupButton(clearRecentButton, Icons.trash(), tr("tooltip.clearRecent"));
    }

    /**
     * Builds the {@link WelcomePane} and wires its callbacks. The pane is shown in its own real tab
     * (see {@link #addWelcomeTab()}); the tab strip handles activation/switching/closing for free, so
     * there is no overlay or visibility juggling.
     */
    private void setupWelcome() {
        welcomePane = new WelcomePane(registry, keymap, recentFiles, this::openRecent,
                this::openExternalUrl, this::projectsEnabled, this::gitEnabled,
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
        recentPalette = new QuickOpen<>("Jump to Recent File", "Type to filter recent files…",
                () -> List.copyOf(recentFiles.getList()),
                p -> p.getFileName() == null ? p.toString() : p.getFileName().toString(),
                p -> p.getParent() == null ? "" : p.getParent().toString(),
                this::openRecent);
        structurePalette = new QuickOpen<>("Jump to Structure", "Type to filter symbols…",
                () -> structurePanel.outline(),
                StructurePanel.Outline::label,
                StructurePanel.Outline::kind,
                entry -> navigateToLine(entry.line()));
        openFilesPalette = new QuickOpen<>("Jump to Open File", "Type to filter open files…",
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
        openFilesPalette.setItemStyleClass(tab -> isTabDirty(tab) ? "dirty-name" : null); // amber/italic, like a dirty tab
        toolWindowPalette = new QuickOpen<>("Jump to Tool Window", "Type to filter tool windows…",
                () -> toolWindows.getRegisteredToolWindows().stream()
                        .filter(tw -> gitEnabled() || !"tool.commit".equals(tw.getCommandId()))
                        .filter(tw -> projectsEnabled() || !"tool.project".equals(tw.getCommandId()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)),
                ToolWindow::getTitle,
                tw -> invertBindings().getOrDefault(tw.getCommandId(), ""),
                toolWindows::open);
        bookmarkPalette = new QuickOpen<>("Jump to Bookmark", "Type to filter bookmarks…",
                this::allBookmarkEntries,
                e -> bookmarkLabel(e.bm()),
                e -> e.file().getFileName() + ":" + (e.bm().line() + 1),
                e -> bookmarkActivate(e.file(), e.bm().line()));
        notesPalette = new QuickOpen<>(tr("notes.jumpTitle"), tr("notes.jumpPrompt"),
                this::allNoteEntries,
                e -> noteEntryLabel(e.note()),
                e -> Path.of(e.fileKey()).getFileName() + ":" + (e.note().anchor().line() + 1),
                e -> noteActivate(e.fileKey(), e.note()));
        // Search Notes: same picker, but the query matches the full body + tags + file (not just the
        // first line) so you can find a note by any word in it.
        notesSearchPalette = new QuickOpen<>(tr("notes.searchTitle"), tr("notes.searchPrompt"),
                this::allNoteEntries,
                e -> noteEntryLabel(e.note()),
                e -> Path.of(e.fileKey()).getFileName() + ":" + (e.note().anchor().line() + 1),
                e -> noteSearchText(e),
                e -> noteActivate(e.fileKey(), e.note()));
        snippetPalette = new QuickOpen<>("Insert Snippet", "Type to filter snippets…",
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
        projects = new ProjectManager(config.getConfigDir());
        projectPicker = new QuickOpen<>("Switch Project", "Type to filter projects…",
                this::projectsWithNoProject,
                Project::name,
                p -> p.id().isEmpty() ? "global session" : p.root(),
                this::switchToProject);
        // Keyboard "Open Project Folder" — mirrors the file finder, but picks a directory.
        folderFinder = new FileFinder(this::finderStartDir, this::openProjectRoot,
                true, "Open Project Folder");
        Project active = projects.active();
        if (active != null) {
            // Restore this project's session (openInitialBuffer + toolWindows.restore run after init).
            config.setWorkspaceStateFile(projects.stateFile(active));
            projectPanel.setRoot(Path.of(active.root()));
        }
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
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        switchToProject(projects.createOrGet(name, root));
    }

    private void refreshProjectPanelList() {
        Project active = projects.active();
        String activeId = active == null ? "" : active.id();
        projectPanel.setProjects(projects.list(), activeId);
        if (toolbarProjectCombo != null) {
            toolbarProjectCombo.setProjects(projects.list(), activeId);
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
        // Turning projects off while one is active: return to the global session first (saving the
        // project's session) so the editor isn't stranded in a now-hidden project. If the user cancels
        // the unsaved-changes prompt, abort the disable and re-check the box. The projectSupportApplied
        // guard limits this to the runtime on→off toggle (it's false during the initial apply).
        if (!on && projectSupportApplied && projects != null && projects.active() != null) {
            if (!switchToProject(ProjectCombo.NO_PROJECT)) {
                config.getSettings().setProjectSupport(true);
                config.save();
                if (settingsWindow != null) {
                    settingsWindow.syncProjectsCheck();
                }
                return; // leave the project UI in place
            }
        }
        openFolderButton.setVisible(on);
        openFolderButton.setManaged(on);
        toolbarProjectCombo.setVisible(on);
        toolbarProjectCombo.setManaged(on);
        projectToolbarLabel.setVisible(on);
        projectToolbarLabel.setManaged(on);
        projectToolbarGap.setVisible(on);
        projectToolbarGap.setManaged(on);
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
     * Switches the editor to {@code p}'s session (saving the current one first). An empty id is the
     * "No Project" sentinel — it returns to the default global session without closing any project.
     */
    private boolean switchToProject(Project p) {
        if (p == null) {
            return false;
        }
        boolean toNoProject = p.id().isEmpty();
        Project active = projects.active();
        if (toNoProject ? active == null : p.equals(active)) {
            return true; // already there (e.g. re-selected in the combo)
        }
        if (!confirmCloseAllBuffers()) {
            refreshProjectPanelList(); // cancelled — snap the combo back to the actual active project
            return false;
        }
        exitZenIfActive();
        if (toNoProject) {
            projects.setActive("");
            projects.save();
            config.useDefaultWorkspaceStateFile();
            activateSession(null);
            setStatus(tr("status.noProject"));
        } else {
            projects.setActive(p.id());
            projects.save();
            config.setWorkspaceStateFile(projects.stateFile(p));
            activateSession(Path.of(p.root()));
            setStatus(tr("status.project", p.name()));
        }
        return true;
    }

    /** Closes the active project (with confirmation), returning to the default global session. */
    private void closeProject() {
        Project active = projects.active();
        if (active == null) {
            setStatus(tr("status.noProjectOpen"));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                tr("dialog.closeProject.body", active.name()), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.closeProject.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (!confirmCloseAllBuffers()) {
            return;
        }
        exitZenIfActive();
        projects.setActive("");
        projects.save();
        config.useDefaultWorkspaceStateFile();
        activateSession(null);
        setStatus(tr("status.projectClosed"));
    }

    /** Deletes the active project (with confirmation). */
    private void deleteProject() {
        deleteProject(projects.active());
    }

    /**
     * Deletes a specific project from the managed list (with confirmation). Only the project entry + its
     * saved session are removed — the folder and its files on disk are left untouched. If it's the
     * active project, the global session is restored; otherwise the current session is untouched.
     */
    private void deleteProject(Project p) {
        if (p == null || p.id().isEmpty()) {
            setStatus(tr("status.noProjectToDelete"));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                tr("dialog.deleteProject.body", p.name()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.deleteProject.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (p.equals(projects.active())) {
            // Deleting the open project: save/close its buffers and fall back to the global session.
            if (!confirmCloseAllBuffers()) {
                return;
            }
            exitZenIfActive();
            projects.delete(p.id()); // also clears the active id
            projects.save();
            config.deleteBookmarksForProject(p.id()); // the project's bookmarks go with it
            config.useDefaultWorkspaceStateFile();
            activateSession(null); // restores the global session + refreshes the combos/panel
        } else {
            // Deleting a project we're not in: just drop it from the list; the session is unaffected.
            projects.delete(p.id());
            projects.save();
            config.deleteBookmarksForProject(p.id()); // the project's bookmarks go with it
            refreshProjectPanelList();
        }
        setStatus(tr("status.deletedProject", p.name()));
    }

    /**
     * Leaves Zen before a session switch, restoring the real view settings. The snapshot needed to
     * restore them lives in the *current* session's {@link WorkspaceState}; once the workspace-state
     * file is swapped that snapshot is orphaned, so exiting Zen first prevents a permanently mangled
     * UI (all chrome off with no way back).
     */
    private void exitZenIfActive() {
        if (config.getWorkspaceState().isZenMode()) {
            setZenMode(false);
        }
    }

    /** Replaces the editor + tool-window state with the freshly-loaded session (after the file swap). */
    private void activateSession(Path root) {
        // Switching from the Project tool window's own combo shouldn't close the panel the user is in,
        // even if the target session didn't have it open — keep it open across the switch if it was.
        boolean keepProjectPanelOpen = toolWindows.isOpen(projectToolWindow);
        // A live session switch lands in normal (non-Zen) view. Zen is transient and its restore
        // snapshot belongs to the previous session, so drop any persisted Zen flag on the incoming
        // session to keep it consistent with the chrome already restored by exitZenIfActive().
        WorkspaceState incoming = config.getWorkspaceState();
        if (incoming.isZenMode()) {
            incoming.setZenMode(false);
            incoming.getPreZenView().clear();
            incoming.getPreZenToolWindows().clear();
        }
        tabPane.getTabs().clear(); // the tabs listener clears mru/pinned for removed tabs
        mru.clear();
        pinned.clear();
        openInitialBuffer();
        toolWindows.closeAllOpen();
        toolWindows.restore();
        toolWindows.setZenStripesHidden(false);
        applyChromeVisibility();
        projectPanel.setRoot(root);
        refreshProjectPanelList();
        if (bookmarksPanel != null) {
            bookmarksPanel.refresh(); // the swapped session has its own bookmarks
        }
        if (notesPanel != null) {
            notesPanel.refresh(); // the swapped session has its own notes
        }
        gitService.invalidateCaches(); // a different project may be a different repo
        refreshGit();
        if (keepProjectPanelOpen && projectsEnabled() && !toolWindows.isOpen(projectToolWindow)) {
            toolWindows.open(projectToolWindow, false); // keep-open across a session swap; don't grab focus
        }
        updateWindowTitle();
    }

    private void updateWindowTitle() {
        Project active = projects == null ? null : projects.active();
        stage.setTitle(active == null ? "Editora" : "Editora — " + active.name());
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
        // Migrate persisted state keyed by the absolute path string.
        var folded = config.getWorkspaceState().getFoldedRegions();
        List<Integer> folds = folded.remove(old.toString());
        if (folds != null) {
            folded.put(target.toString(), folds);
        }
        if (recentFiles != null) {
            recentFiles.remove(old);
        }
        config.save();
        setStatus(tr("status.renamedTo", target.getFileName()));
    }

    /** Syncs editor/session state after the Project tree deletes a file on disk. */
    private void onProjectFileDeleted(Path path) {
        Tab tab = tabForPath(path);
        if (tab != null) {
            tabPane.getTabs().remove(tab); // file is gone; close without a save prompt
        }
        config.getWorkspaceState().getFoldedRegions().remove(path.toString());
        if (recentFiles != null) {
            recentFiles.remove(path);
        }
        config.save();
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
            EditorBuffer buffer = bufferOf(now);
            fileInfoPanel.attach(buffer);
            structurePanel.attach(buffer);
            statusBar.attach(buffer);
            breadcrumb.setActiveFile(buffer == null ? null : buffer.getPath());
            if (AUTOSAVE_FOCUS.equals(autoSaveMode())) {
                autoSaveAllDirty(); // saves the outgoing buffer (and any other dirty ones)
            }
            refreshSplitButtons();
            updateZenButton(); // re-position the Zen "Z" if the new file is/isn't Markdown
            checkExternalChanges(); // prompt if the file we just switched to changed on disk
            refreshGit(); // update branch/status + this file's gutter change bars
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
            while (c.next()) {
                // A pin reorder removes+re-adds the same tab; skip cleanup so it isn't forgotten.
                if (c.wasRemoved() && !reordering) {
                    mru.removeAll(c.getRemoved());
                    pinned.removeAll(c.getRemoved());
                    // Release each closed buffer's daemon executor threads (markdown-preview +
                    // editor-highlighter); otherwise they accumulate one pair per opened file.
                    for (Tab removed : c.getRemoved()) {
                        EditorBuffer closed = bufferOf(removed);
                        if (closed != null) {
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
        projectPanel = new ProjectPanel(this::openPath, this::switchToProject, this::closeProject,
                this::deleteProject, this::onProjectFileRenamed, this::onProjectFileDeleted,
                this::isPathModified);
        projectToolWindow = new ToolWindow("project", tr("toolwindow.project"), ToolWindow.Side.RIGHT,
                Icons::project, projectPanel, "tool.project");
        structurePanel = new StructurePanel();
        structureToolWindow = new ToolWindow("structure", tr("toolwindow.structure"), ToolWindow.Side.RIGHT,
                Icons::structure, structurePanel, "tool.structure");
        bookmarksPanel = new BookmarksPanel(config::getBookmarks,
                new BookmarksPanel.Actions() {
                    @Override public void openAndJump(java.nio.file.Path file, int line) {
                        bookmarkActivate(file, line);
                    }
                    @Override public void setNote(java.nio.file.Path file, int line, String note) {
                        bookmarkSetNote(file, line, note);
                    }
                    @Override public void delete(java.nio.file.Path file, int line) {
                        bookmarkDelete(file, line);
                    }
                    @Override public void deleteAll(java.nio.file.Path file) {
                        bookmarkDeleteAll(file);
                    }
                    @Override public void moveBookmark(java.nio.file.Path file, int from, int to) {
                        MainController.this.moveBookmark(file, from, to);
                    }
                    @Override public void moveFile(int from, int to) {
                        moveBookmarkFile(from, to);
                    }
                });
        bookmarksToolWindow = new ToolWindow("bookmarks", tr("toolwindow.bookmarks"), ToolWindow.Side.RIGHT,
                Icons::bookmark, bookmarksPanel, "tool.bookmarks");
        notesPanel = new NotesPanel(config::getNotes, new NotesPanel.Actions() {
            @Override public void openAndJump(String fileKey, com.editora.config.PersonalNote note) {
                noteActivate(fileKey, note);
            }
            @Override public void editBody(String fileKey, com.editora.config.PersonalNote note) {
                noteEditBody(fileKey, note);
            }
            @Override public void setStatus(String fileKey, com.editora.config.PersonalNote note,
                    com.editora.config.NoteStatus status) {
                noteSetStatus(fileKey, note, status);
            }
            @Override public void delete(String fileKey, com.editora.config.PersonalNote note) {
                noteDelete(fileKey, note);
            }
            @Override public void deleteAll(String fileKey) {
                noteDeleteAll(fileKey);
            }
        });
        notesToolWindow = new ToolWindow("notes", tr("toolwindow.notes"), ToolWindow.Side.RIGHT,
                Icons::notes, notesPanel, "tool.notes");
        fileInfoPanel = new FileInformationPanel();
        fileInfoToolWindow = new ToolWindow("file-information", tr("toolwindow.file-information"), ToolWindow.Side.RIGHT,
                Icons::about, fileInfoPanel, "tool.fileInformation");
        gitPanel = new GitPanel(new GitPanel.Actions() {
            @Override public void open(String path) {
                if (currentRepoRoot != null) {
                    openPath(currentRepoRoot.resolve(path));
                }
            }
            @Override public void stage(String path) {
                gitOp("Staged " + path, "add", "--", path);
            }
            @Override public void unstage(String path) {
                gitOp("Unstaged " + path, "reset", "-q", "HEAD", "--", path);
            }
            @Override public void discard(String path, boolean untracked) {
                discardChanges(path, untracked);
            }
            @Override public void stageAll() {
                gitOp("Staged all changes", "add", "-A");
            }
            @Override public void commit(String message) {
                gitCommit(message);
            }
            @Override public void push() {
                gitPush();
            }
            @Override public void refresh() {
                gitService.invalidateCaches();
                afterGitMutation();
            }
        });
        gitPanel.setOnClone(this::gitClone);
        commitToolWindow = new ToolWindow("commit", tr("toolwindow.commit"), ToolWindow.Side.RIGHT,
                Icons::git, gitPanel, "tool.commit");
        toolWindows.register(projectToolWindow);
        toolWindows.register(structureToolWindow);
        toolWindows.register(bookmarksToolWindow);
        toolWindows.register(notesToolWindow);
        toolWindows.register(commitToolWindow);
        toolWindows.register(fileInfoToolWindow);
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
    /** Whether the Git integration is enabled in Settings (default off). */
    private boolean gitEnabled() {
        return config.getSettings().isGitSupport();
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
            currentRepoRoot = null;
            currentBranchName = "";
            currentUpstream = "";
            gitPanel.setStatus(null);
            for (Tab tab : tabPane.getTabs()) {
                EditorBuffer b = bufferOf(tab);
                if (b != null) {
                    b.setChangeBars(null);
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
        com.editora.editor.MermaidImages.configure(on, mermaidService.mmdcCommand(),
                mermaidService.maidCommand(), appThemeDark());
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
        if (!state.isRepo()) {
            currentBranchName = "";
            currentUpstream = "";
            statusBar.setGitBranch(null, 0, 0);
            gitPanel.setStatus(null);
            if (b != null) {
                b.setChangeBars(null);
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
            b.setChangeBars(classes); // an empty map still marks the buffer as tracked (reserves the slot)
        }
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
            gitService.diff(root, p, changes -> {
                java.util.Map<Integer, String> classes = new java.util.HashMap<>();
                changes.forEach((line, type) -> classes.put(line, type.cssClass()));
                buf.setChangeBars(classes);
            });
        }
    }

    /** Runs a Git mutation in the active repo, reports the outcome, and refreshes. */
    private void gitOp(String successMessage, String... args) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        gitService.run(currentRepoRoot, r -> {
            if (r.ok()) {
                setStatus(successMessage);
            } else {
                gitError("Git command failed", r.message());
            }
            afterGitMutation();
        }, args);
    }

    /** Confirms then discards a file's changes (or deletes an untracked file) — destructive. */
    private void discardChanges(String path, boolean untracked) {
        if (currentRepoRoot == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                untracked ? tr("dialog.discard.untracked", path)
                        : tr("dialog.discard.tracked", path),
                ButtonType.OK, ButtonType.CANCEL);
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
        gitService.run(currentRepoRoot, r -> {
            if (r.ok()) {
                gitPanel.clearMessage();
                setStatus(tr("status.committed"));
            } else {
                gitError("Commit failed", r.message());
            }
            afterGitMutation();
        }, "commit", "-m", message);
    }

    private void checkoutBranch(String name) {
        if (currentRepoRoot == null || name == null || name.isBlank()) {
            return;
        }
        gitService.run(currentRepoRoot, r -> {
            if (r.ok()) {
                setStatus(tr("status.switchedBranch", name));
            } else {
                gitError("Couldn't switch to " + name, r.message());
            }
            afterGitMutation();
            reloadAllFromDiskSilently();
        }, "checkout", name);
    }

    /** Checks out a remote branch (e.g. {@code origin/foo}), creating a local tracking branch. */
    private void checkoutRemoteBranch(String remote) {
        if (currentRepoRoot == null || remote == null || remote.isBlank()) {
            return;
        }
        gitService.run(currentRepoRoot, r -> {
            if (r.ok()) {
                setStatus(tr("status.checkedOut", remote));
            } else {
                gitError("Couldn't check out " + remote, r.message());
            }
            afterGitMutation();
            reloadAllFromDiskSilently();
        }, "checkout", "--track", remote);
    }

    /** Opens the IntelliJ-style branch dropdown, fetching local + remote branches off-thread first. */
    private void chooseBranch() {
        if (currentRepoRoot == null) {
            // Not under version control: the dropdown offers only "Clone Git repository…".
            branchPopup.showNoVcs(stage, statusBar.gitSegmentNode(), this::gitClone);
            return;
        }
        gitService.branches(currentRepoRoot, branches -> {
            List<BranchPopup.MenuAction> actions = List.of(
                    new BranchPopup.MenuAction(tr("branch.newBranch"), "", this::newBranch),
                    new BranchPopup.MenuAction(tr("branch.pull"), "", () -> gitSync(tr("gitlabel.pull"), "pull", "--ff-only")),
                    new BranchPopup.MenuAction(tr("branch.fetch"), "", () -> gitSync(tr("gitlabel.fetch"), "fetch", "--all")),
                    new BranchPopup.MenuAction(tr("branch.push"), "", this::gitPush),
                    new BranchPopup.MenuAction(tr("branch.commit"), "C-x g", this::gitCommitFocus));
            branchPopup.show(stage, statusBar.gitSegmentNode(), currentBranchName,
                    branches.local(), branches.remote(), branches.remoteUrl(), actions,
                    this::checkoutBranch, this::checkoutRemoteBranch);
        });
    }

    private void newBranch() {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.newBranch.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.newBranch.content"));
        dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).ifPresent(name ->
                gitService.run(currentRepoRoot, r -> {
                    if (r.ok()) {
                        setStatus(tr("status.createdBranch", name));
                    } else {
                        gitError("Couldn't create branch " + name, r.message());
                    }
                    afterGitMutation();
                }, "checkout", "-b", name));
    }

    private void gitSync(String label, String... args) {
        if (currentRepoRoot == null) {
            setStatus(tr(gitService.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
            return;
        }
        setStatus(tr("status.gitRunning", label));
        gitService.runNetwork(currentRepoRoot, r -> {
            if (r.ok()) {
                setStatus(tr("status.gitDone", label));
                reloadAllFromDiskSilently();
            } else {
                gitError(label + " failed", r.message());
            }
            afterGitMutation();
        }, args);
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
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.clone.title"));
        dialog.setHeaderText(null);
        ButtonType cloneType = new ButtonType(tr("dialog.clone.button"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(cloneType, ButtonType.CANCEL);

        TextField urlField = new TextField();
        urlField.setPromptText("https://github.com/user/repo.git");
        urlField.setPrefColumnCount(34);
        TextField dirField = new TextField();
        dirField.setPromptText(tr("dialog.clone.dirPrompt"));
        dirField.setPrefColumnCount(28);
        Button browse = new Button(tr("dialog.clone.browse"));

        String defaultParent = System.getProperty("user.home", "");
        boolean[] dirEdited = {false};
        boolean[] autoFilling = {false};
        urlField.textProperty().addListener((o, a, b) -> {
            if (!dirEdited[0]) {
                String name = repoNameFromUrl(b);
                autoFilling[0] = true;
                dirField.setText(name.isEmpty() ? "" : Path.of(defaultParent).resolve(name).toString());
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
            java.io.File parent = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (parent != null) {
                String name = repoNameFromUrl(urlField.getText());
                dirField.setText(parent.toPath().resolve(name.isEmpty() ? "repository" : name).toString());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(4, 0, 0, 0));
        grid.add(new Label(tr("dialog.clone.url")), 0, 0);
        grid.add(urlField, 1, 0, 2, 1);
        grid.add(new Label(tr("dialog.clone.directory")), 0, 1);
        grid.add(dirField, 1, 1);
        grid.add(browse, 2, 1);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(dirField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(bt -> bt);

        javafx.scene.Node cloneButton = dialog.getDialogPane().lookupButton(cloneType);
        cloneButton.setDisable(true);
        Runnable validate = () -> cloneButton.setDisable(
                urlField.getText().isBlank() || dirField.getText().isBlank());
        urlField.textProperty().addListener((o, a, b) -> validate.run());
        dirField.textProperty().addListener((o, a, b) -> validate.run());
        Platform.runLater(urlField::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != cloneType) {
            return;
        }
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
    }

    /**
     * Opens a representative file from a freshly cloned repo (its README if present) so Git activates
     * for it — no project involved. If there's no obvious entry file, the clone is just reported and the
     * user can open files from it (File: Open / Find File).
     */
    private void openClonedEntry(Path dir) {
        for (String candidate : new String[]{"README.md", "README.markdown", "README.rst",
                "README.txt", "README"}) {
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
        gitOp("Staged " + file.getFileName(), "add", "--",
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
        setupButton(newButton, Icons.newFile(), tr("tooltip.new"));
        setupButton(openButton, Icons.open(), tr("tooltip.open"));
        setupButton(openFolderButton, Icons.openFolder(), tr("tooltip.openFolder"));
        setupButton(saveButton, Icons.save(), tr("tooltip.save"));
        setupButton(saveAsButton, Icons.saveAs(), tr("tooltip.saveAs"));
        setupButton(undoButton, Icons.undo(), tr("tooltip.undo"));
        setupButton(redoButton, Icons.redo(), tr("tooltip.redo"));
        setupButton(cutButton, Icons.cut(), tr("tooltip.cut"));
        setupButton(copyButton, Icons.copy(), tr("tooltip.copy"));
        setupButton(pasteButton, Icons.paste(), tr("tooltip.paste"));
        setupButton(findButton, Icons.find(), tr("tooltip.find"));
        setupButton(splitVerticalButton, Icons.splitVertical(), tr("tooltip.splitVertical"));
        setupButton(splitHorizontalButton, Icons.splitHorizontal(), tr("tooltip.splitHorizontal"));
        setupButton(paletteButton, Icons.palette(), tr("tooltip.palette"));
        setupButton(closeTabButton, Icons.closeTab(), tr("tooltip.closeTab"));
        setupButton(settingsButton, Icons.settings(), tr("tooltip.settings"));
        setupButton(aboutButton, Icons.about(), tr("tooltip.about"));
        setupButton(quitButton, Icons.quit(), tr("tooltip.quit"));

        // Reflect open/closed state of the palette and find bar in their toolbar buttons.
        palette.showingProperty().addListener(
                (obs, was, now) -> paletteButton.pseudoClassStateChanged(OPEN, now));
        findBar.visibleProperty().addListener(
                (obs, was, now) -> findButton.pseudoClassStateChanged(OPEN, now));
        // Project switcher (placed right of the Settings icon by arrangeToolbarTail); shown when enabled.
        toolbarProjectCombo = new ProjectCombo(this::switchToProject);
        toolbarProjectCombo.setPrefWidth(184); // 15% longer than the previous 160
        projectToolbarLabel = new Label(tr("toolbar.projectLabel"));
        projectToolbarLabel.getStyleClass().add("toolbar-hint");
        projectToolbarLabel.setTooltip(new Tooltip(
                "Projects are single-folder workspaces, each remembering its own open files and layout. "
                + "Pick one to switch; \"No Project\" returns to the global session."));
        toolbarProjectCombo.setOnDeleteProject(this::deleteProject); // per-item delete in the dropdown
        projectToolbarGap = new Region();
        projectToolbarGap.setMinWidth(78); // ≈ 3 toolbar-icon widths between Settings and the combo
        projectToolbarGap.setPrefWidth(78);
        arrangeToolbarTail();
        refreshSplitButtons();
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

        String chord = invertBindings().get("switcher.show");
        if (chord != null && !chord.isBlank()) {
            Label hint = new Label(tr("hint.switcher", chord));
            hint.getStyleClass().add("toolbar-hint");
            hint.setTooltip(new Tooltip(tr("hint.switcher.tip", chord)));
            items.add(hint);
        }

        // Dev-mode badge (just left of the About icon) when running with --dev, so a development
        // instance is visually distinct from the production one.
        if (config.isDev()) {
            Label devBadge = new Label(tr("badge.devMode"));
            devBadge.getStyleClass().add("dev-mode-badge");
            devBadge.setTooltip(new Tooltip(tr("badge.devMode.tip", config.getConfigDir().toString())));
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

    private void setupButton(Button button, Node icon, String tooltip) {
        button.setGraphic(icon);
        button.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        button.setTooltip(new Tooltip(tooltip));
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
    private void fillSessionFiles(List<WorkspaceState.OpenFile> files, List<EditorBuffer> buffers,
            List<Integer> order, int k) {
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
    public record OpenTarget(Path file, int line, int column) { }

    /** A one-shot action run after {@link #openInitialBuffer()} finishes restoring the session. */
    private Runnable pendingAfterRestore;

    /**
     * Startup entry point (replaces the bare {@code openInitialBuffer()} call): optionally activates a
     * project, restores the session, then — once restore completes — opens any command-line files
     * (jumping to line:column) and enters Zen, all additive on top of the restored session. With no
     * arguments it's exactly the old {@code openInitialBuffer()}.
     */
    public void startup(Path projectDir, List<OpenTarget> targets, boolean zen, String newFile) {
        if (projectDir != null && projectsEnabled()) {
            activateStartupProject(projectDir); // swap to the project's session before it's restored
        }
        // Run CLI actions AFTER the (deferred, pulse-paced) session restore, so a restored caret can't
        // override a requested line:column.
        pendingAfterRestore = () -> applyStartupTargets(targets, zen, newFile);
        openInitialBuffer();
    }

    private void applyStartupTargets(List<OpenTarget> targets, boolean zen, String newFile) {
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
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
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
        buffer.setAddNoteHandler(this::addNoteFromContext);
        buffer.setNotesEnabled(notesEnabled());
        buffer.setOnEnableEditing(() -> enableEditing(buffer)); // "Enable Editing" banner button
        buffer.setSnippetProvider((lang, prefix) -> snippets.byPrefix(lang, prefix));
        buffer.setCompletionProvider(completion::complete);
        Settings acs = config.getSettings();
        buffer.setAutocomplete(acs.isAutocomplete(), acs.isAutocompleteProse(),
                acs.isAutocompleteSnippets(), effectiveMermaidAutocomplete());
        buffer.setMermaidValidator((text, cb) -> mermaidService.validate(text, cb));
        buffer.setMermaidLintEnabled(mermaidEnabled() && mermaidAvail.maid());
        ensurePreviewControls(buffer);
        Tab tab = addContentTab(buffer, false); // added to the strip; selected below (focus the area, not the node)
        updateTabMeta(tab, buffer); // replaces the default text/icon with the buffer header (drag handle, pin, dirty)
        buffer.dirtyProperty().addListener((obs, was, now) -> {
            updateTabMeta(tab, buffer);
            if (projectPanel != null) {
                projectPanel.refreshModified(); // reflect the dirty marker in the Project file tree
            }
        });
        // Auto save (after-delay mode): each edit restarts the idle timer; cheap (no full-text build).
        buffer.getArea().multiPlainChanges().subscribe(c -> {
            if (AUTOSAVE_DELAY.equals(autoSaveMode())) {
                autoSaveIdleTimer.playFromStart();
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
        header.getChildren().add(title);
        enableTabDrag(header, tab);
        tab.setText("");
        tab.setGraphic(header);
        toggleClass(tab, "dirty", dirty);
        toggleClass(tab, "pinned", isPinned);
        toggleClass(tab, "read-only", !buffer.isEditable());
        Path p = buffer.getPath();
        tab.setTooltip(new Tooltip(p != null ? p.toAbsolutePath().toString() : "untitled"));
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
            if (abs.startsWith(root) && (best == null || p.root().length() > best.root().length())) {
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
        long size = fileSize(file);
        buffer.setDiskSnapshot(lastModifiedMillis(file), size); // baseline for external-change detection
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
        alert.setContentText(buffer.isDirty()
                ? tr("dialog.externalChange.dirty")
                : tr("dialog.externalChange.clean"));
        ButtonType reload = new ButtonType(tr("dialog.externalChange.reload"));
        ButtonType keep = new ButtonType(buffer.isDirty() ? tr("dialog.externalChange.keepMine") : tr("dialog.externalChange.keep"),
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
            buffer.markClean();
            buffer.setDiskSnapshot(lastModifiedMillis(file), fileSize(file)); // our own write isn't "external"
            setStatus(tr("status.saved", file));
            refreshGit(); // a save changes the working tree → update gutter + status
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
        autoSaveIdleTimer.setDuration(Duration.millis(Math.max(100, config.getSettings().getAutoSaveDelayMillis())));
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
        String next = switch (autoSaveMode()) {
            case AUTOSAVE_OFF -> AUTOSAVE_DELAY;
            case AUTOSAVE_DELAY -> AUTOSAVE_FOCUS;
            default -> AUTOSAVE_OFF;
        };
        config.getSettings().setAutoSave(next);
        config.save();
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
        TextInputDialog dialog = new TextInputDialog(old.getFileName().toString());
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.renameFile.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.renameFile.content"));
        dialog.showAndWait().ifPresent(name -> {
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
            config.save();
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
        save.setOnAction(e -> save(buffer));
        MenuItem saveAs = new MenuItem(tr("menu.saveAs"));
        saveAs.setOnAction(e -> saveAs(buffer));
        MenuItem close = new MenuItem(tr("menu.close"));
        close.setOnAction(e -> closeTab(tab));
        MenuItem closeOthers = new MenuItem(tr("menu.closeOthers"));
        closeOthers.setOnAction(e -> closeOtherTabs(tab));
        MenuItem closeAll = new MenuItem(tr("menu.closeAll"));
        closeAll.setOnAction(e -> closeAllTabs());
        MenuItem closeUnmodified = new MenuItem(tr("menu.closeUnmodified"));
        closeUnmodified.setOnAction(e -> closeUnmodifiedTabs());
        MenuItem closeLeft = new MenuItem(tr("menu.closeLeft"));
        closeLeft.setOnAction(e -> closeTabsToLeft(tab));
        MenuItem closeRight = new MenuItem(tr("menu.closeRight"));
        closeRight.setOnAction(e -> closeTabsToRight(tab));
        MenuItem copyPath = new MenuItem(tr("menu.copyPath"));
        copyPath.setOnAction(e -> copyPath(buffer));
        MenuItem pin = new MenuItem(tr("menu.pin"));
        pin.setOnAction(e -> togglePin(tab));
        MenuItem rename = new MenuItem(tr("menu.rename"));
        rename.setOnAction(e -> renameFile(buffer, tab));

        ContextMenu menu = new ContextMenu(
                save, saveAs,
                new SeparatorMenuItem(),
                close, closeOthers, closeAll, closeUnmodified,
                new SeparatorMenuItem(),
                closeLeft, closeRight,
                new SeparatorMenuItem(),
                copyPath, pin, rename);
        menu.setOnShowing(e -> {
            closeLeft.setDisable(eligibleToLeft(tab).isEmpty());
            closeRight.setDisable(eligibleToRight(tab).isEmpty());
            boolean hasPath = buffer.getPath() != null;
            copyPath.setDisable(!hasPath);
            rename.setDisable(!hasPath);
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
        Path target = file.toAbsolutePath().normalize();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            Path p = buffer == null ? null : buffer.getPath();
            if (p != null && p.toAbsolutePath().normalize().equals(target)) {
                return tab;
            }
        }
        return null;
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
        state.setActiveFile(active != null && active.getPath() != null
                ? active.getPath().toAbsolutePath().toString() : "");
        persistWindowBounds(state);
        config.save();
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
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        boolean had = area.getSelection().getLength() > 0;
        area.cut();
        deactivateMark();
        setStatus(tr(had ? "status.cut" : "status.nothingToCut"));
    }

    @FXML
    private void onCopy() {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        boolean had = area.getSelection().getLength() > 0;
        area.copy();
        deactivateMark();
        setStatus(tr(had ? "status.copied" : "status.nothingToCopy"));
    }

    @FXML
    private void onPaste() {
        if (!activeEditable()) {
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
        toggleFind(false);
    }

    /** Shows the find/replace bar, or hides it if it's already open. */
    private void toggleFind(boolean backward) {
        if (findBar.isShown()) {
            findBar.hideBar();
        } else {
            findBar.show(backward);
        }
    }

    @FXML
    private void onPalette() {
        palette.show(stage);
    }

    @FXML
    private void onSettings() {
        settingsWindow.show(stage);
    }

    @FXML
    private void onAbout() {
        SettingsWindow.showAbout(stage, config.getSettingsFile(), this::openPath, this::openExternalUrl,
                config.isDev() ? com.editora.AppInfo.gitCommit() : ""); // build commit shown only in --dev
    }

    private void toggleColumnRuler() {
        Settings s = config.getSettings();
        s.setShowColumnRuler(!s.isShowColumnRuler());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.ruler", tr(s.isShowColumnRuler() ? "common.on" : "common.off")));
    }

    /** Shows/hides the tool stripes (UI only; tool windows still open via keybinding/palette). */
    private void toggleToolStripe() {
        Settings s = config.getSettings();
        s.setShowToolStripe(!s.isShowToolStripe());
        config.save();
        applyViewSettingsToAllBuffers(s); // → applyChromeVisibility → toolWindows.setStripesEnabled
        settingsWindow.syncToolStripeCheck();
        setStatus(tr("status.toggle.toolStripe", tr(s.isShowToolStripe() ? "common.on" : "common.off")));
    }

    private void toggleLineHighlight() {
        Settings s = config.getSettings();
        s.setHighlightCurrentLine(!s.isHighlightCurrentLine());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.lineHighlight", tr(s.isHighlightCurrentLine() ? "common.on" : "common.off")));
    }

    private void toggleLineNumbers() {
        Settings s = config.getSettings();
        s.setShowLineNumbers(!s.isShowLineNumbers());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.lineNumbers", tr(s.isShowLineNumbers() ? "common.on" : "common.off")));
    }

    private void toggleMinimap() {
        Settings s = config.getSettings();
        s.setShowMinimap(!s.isShowMinimap());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.minimap", tr(s.isShowMinimap() ? "common.on" : "common.off")));
    }

    private void toggleWhitespace() {
        Settings s = config.getSettings();
        s.setShowWhitespace(!s.isShowWhitespace());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.whitespace", tr(s.isShowWhitespace() ? "common.on" : "common.off")));
    }

    private void toggleSpellCheck() {
        Settings s = config.getSettings();
        s.setSpellCheck(!s.isSpellCheck());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus(tr("status.toggle.spellCheck", tr(s.isSpellCheck() ? "common.on" : "common.off")));
    }

    private void toggleAutocomplete() {
        Settings s = config.getSettings();
        s.setAutocomplete(!s.isAutocomplete());
        config.save();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks(); // keep the Settings window in step if it's open
        setStatus(tr("status.toggle.autocomplete", tr(s.isAutocomplete() ? "common.on" : "common.off")));
    }

    private void toggleAutocompleteProse() {
        Settings s = config.getSettings();
        s.setAutocompleteProse(!s.isAutocompleteProse());
        config.save();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks();
        setStatus(tr("status.toggle.autocompleteProse", tr(s.isAutocompleteProse() ? "common.on" : "common.off")));
    }

    private void toggleAutocompleteSnippets() {
        Settings s = config.getSettings();
        s.setAutocompleteSnippets(!s.isAutocompleteSnippets());
        config.save();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks();
        setStatus(tr("status.toggle.autocompleteSnippets", tr(s.isAutocompleteSnippets() ? "common.on" : "common.off")));
    }

    private void toggleAutocompleteMermaid() {
        Settings s = config.getSettings();
        s.setAutocompleteMermaid(!s.isAutocompleteMermaid());
        config.save();
        applyAutocomplete();
        settingsWindow.syncAutocompleteChecks();
        setStatus(tr("status.toggle.autocompleteMermaid", tr(s.isAutocompleteMermaid() ? "common.on" : "common.off")));
    }

    /** Opens a picker to set the spell-check dictionary language for the active file (persisted per file). */
    private void chooseSpellLanguage() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            setStatus(tr("status.noFileOpen"));
            return;
        }
        QuickOpen<String> picker = new QuickOpen<>(
                "Set Spell Check Language", "Type to filter languages…",
                SpellDictionaries::available,
                id -> id,
                id -> "",
                id -> setSpellLanguage(buffer, id));
        picker.show(stage);
    }

    private void setSpellLanguage(EditorBuffer buffer, String langId) {
        buffer.setSpellLanguage(langId);
        Path p = buffer.getPath();
        if (p != null) {
            config.getWorkspaceState().getSpellLanguages().put(p.toString(), langId);
            config.save();
        }
        setStatus(tr("status.spellLanguage", langId));
    }

    /** Picker for the app (chrome) theme — also switches the editor theme to match. */
    private void chooseAppTheme() {
        QuickOpen<String> picker = new QuickOpen<>(
                "Set App Theme", "Type to filter themes…",
                () -> Themes.NAMES,
                name -> name,
                name -> "",
                this::applyAppTheme);
        picker.show(stage);
    }

    /** Picker for the editor color theme only (leaves the chrome theme untouched). */
    private void chooseEditorTheme() {
        QuickOpen<String> picker = new QuickOpen<>(
                "Set Editor Theme", "Type to filter themes…",
                () -> EditorThemes.NAMES,
                name -> name,
                name -> "",
                this::applyEditorThemeChoice);
        picker.show(stage);
    }

    /** Applies a chrome theme and follows it with the matching editor theme (clears the user-set flag). */
    private void applyAppTheme(String name) {
        Settings s = config.getSettings();
        s.setTheme(name);
        javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(name));
        s.setEditorTheme(EditorThemes.defaultFor(name)); // chrome theme drives the editor theme
        s.setEditorThemeUserSet(false);
        config.save();
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
        config.save();
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
        config.save();
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
        config.save();
        applyChromeVisibility();
        setStatus(tr("status.toggle.toolbar", tr(s.isShowToolbar() ? "common.on" : "common.off")));
    }

    private void toggleBreadcrumb() {
        Settings s = config.getSettings();
        s.setShowBreadcrumb(!s.isShowBreadcrumb());
        config.save();
        applyChromeVisibility();
        setStatus(tr("status.toggle.breadcrumb", tr(s.isShowBreadcrumb() ? "common.on" : "common.off")));
    }

    private void toggleStatusBar() {
        Settings s = config.getSettings();
        s.setShowStatusBar(!s.isShowStatusBar());
        config.save();
        applyChromeVisibility();
        // The status bar may now be hidden, so this message just confirms the toggle while visible.
        setStatus(tr("status.toggle.statusBar", tr(s.isShowStatusBar() ? "common.on" : "common.off")));
    }

    private void toggleTabBar() {
        Settings s = config.getSettings();
        s.setShowTabBar(!s.isShowTabBar());
        config.save();
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
        TextInputDialog dialog = new TextInputDialog(String.valueOf(area.getCurrentParagraph() + 1));
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.goToLine.title"));
        dialog.setHeaderText(tr("dialog.goToLine.header"));
        dialog.setContentText(tr("dialog.goToLine.content", total));
        dialog.showAndWait().ifPresent(input -> {
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
        });
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
        ChoiceDialog<Integer> dialog = new ChoiceDialog<>(
                options.contains(s.getTabSize()) ? s.getTabSize() : 4, options);
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.tabSize.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.tabSize.content"));
        dialog.showAndWait().ifPresent(size -> {
            s.setTabSize(size);
            config.save();
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
        config.save();
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
            map.put(file.toString(),
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
        boolean reanchored =
                buffer.applyBookmarks(config.getBookmarks().get(file.toString()));
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
        config.save();
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
        config.save();
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
        config.save();
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
        config.save();
        applyViewSettingsToAllBuffers(s);
        statusBar.refresh();
        setStatus(tr("status.textZoom", Math.round(z * 100)));
    }

    // --- Bookmark commands + panel actions ---------------------------------------------------------

    /** A flattened (file, bookmark) pair for the cross-file "Jump to Bookmark" picker. */
    private record BookmarkEntry(Path file, com.editora.config.Bookmark bm) { }

    /**
     * Handles a click in the gutter: adds a bookmark on an unbookmarked line, or asks for confirmation
     * before removing an existing one. (The keyboard toggle {@code C-c m} removes without a prompt.)
     */
    private void onGutterBookmarkClick(EditorBuffer buffer, int line) {
        if (buffer.getBookmarkManager().isBookmarked(line)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    tr("dialog.removeBookmark.body", line + 1), ButtonType.OK, ButtonType.CANCEL);
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
        TextInputDialog dialog = new TextInputDialog(current);
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.bookmarkNote.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("dialog.bookmarkNote.content"));
        dialog.showAndWait().ifPresent(note -> {
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
        Integer target = forward ? b.getBookmarkManager().next(from) : b.getBookmarkManager().previous(from);
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
            updateClosedFileBookmarks(file, marks -> marks.replaceAll(
                    bm -> bm.line() == line ? bm.withNote(note) : bm));
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
    private void updateClosedFileBookmarks(Path file,
            java.util.function.Consumer<List<com.editora.config.Bookmark>> mutator) {
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

    private record NoteEntry(String fileKey, com.editora.config.PersonalNote note) { }

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

    private static String findNoteKeyByIdentity(Map<String, List<com.editora.config.PersonalNote>> map,
            com.editora.config.FileIdentity id) {
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
            buffer.getNoteManager().add(com.editora.config.PersonalNote.create(
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
     * Multi-line note editor dialog. Saves the (non-blank) body via {@code onAccept}; when {@code onDelete}
     * is non-null an extra Delete button is shown (used when editing an existing note). Enter inserts a
     * newline; Ctrl/Cmd+Enter saves.
     */
    private void showNoteDialog(String initial, java.util.function.Consumer<String> onAccept, Runnable onDelete) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.note.title"));

        TextArea editor = new TextArea(initial == null ? "" : initial);
        editor.setWrapText(true);
        editor.setPrefRowCount(6);
        editor.setPrefColumnCount(42);
        // Honor the user's configured keybindings (Emacs caret movement + basic editing) in the note box.
        com.editora.command.TextInputKeymap.install(editor, keymap);
        Label prompt = new Label(tr("dialog.note.content"));
        VBox box = new VBox(6, prompt, editor);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        ButtonType deleteType = null;
        if (onDelete != null) {
            deleteType = new ButtonType(tr("notes.delete"), ButtonBar.ButtonData.LEFT);
            dialog.getDialogPane().getButtonTypes().add(deleteType);
        }
        // Enter alone inserts a newline in the TextArea; Ctrl/Cmd+Enter accepts the dialog.
        editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && (e.isShortcutDown() || e.isControlDown())) {
                e.consume();
                dialog.setResult(ButtonType.OK);
                dialog.close();
            }
        });
        Platform.runLater(editor::requestFocus);

        final ButtonType deleteButton = deleteType;
        dialog.showAndWait().ifPresent(bt -> {
            if (deleteButton != null && bt == deleteButton) {
                onDelete.run();
            } else if (bt == ButtonType.OK) {
                String text = editor.getText().strip();
                if (!text.isBlank()) {
                    onAccept.accept(text);
                }
            }
        });
    }

    private void onGutterNoteClick(EditorBuffer buffer, int line) {
        var ns = buffer.getNoteManager().notesOnLine(line);
        if (!ns.isEmpty()) {
            editOpenBufferNote(buffer, ns.get(0));
        }
    }

    private void editOpenBufferNote(EditorBuffer buffer, com.editora.config.PersonalNote note) {
        showNoteDialog(note.body(),
                body -> buffer.getNoteManager().update(note.withBody(body)),
                () -> {
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
        Integer target = forward ? b.getNoteManager().next(from) : b.getNoteManager().previous(from);
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
        String path = note.file() != null && !note.file().path().isBlank() ? note.file().path() : fileKey;
        openPath(Path.of(path));
        int line = note.anchor().line();
        Platform.runLater(() -> navigateToLine(line));
    }

    private void noteEditBody(String fileKey, com.editora.config.PersonalNote note) {
        EditorBuffer open = bufferOf(tabForKey(fileKey));
        if (open != null) {
            editOpenBufferNote(open, note);
        } else {
            showNoteDialog(note.body(),
                    body -> updateClosedFileNotes(fileKey,
                            list -> list.replaceAll(n -> n.id().equals(note.id()) ? n.withBody(body) : n)),
                    () -> updateClosedFileNotes(fileKey, list -> list.removeIf(n -> n.id().equals(note.id()))));
        }
    }

    private void noteSetStatus(String fileKey, com.editora.config.PersonalNote note,
            com.editora.config.NoteStatus status) {
        EditorBuffer open = bufferOf(tabForKey(fileKey));
        if (open != null) {
            open.getNoteManager().setStatus(note.id(), status);
        } else {
            updateClosedFileNotes(fileKey,
                    list -> list.replaceAll(n -> n.id().equals(note.id()) ? n.withStatus(status) : n));
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

    private void updateClosedFileNotes(String fileKey,
            java.util.function.Consumer<List<com.editora.config.PersonalNote>> mutator) {
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
        chooser.getExtensionFilters().addAll(
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
        pdfService.exportCode(b.getContent(), base, s.isPdfSyntaxHighlighting(), s.isPdfLineNumbers(),
                s.getTabSize(), s.getPdfPageSize(), f.toPath(), r -> reportPdf(r, f));
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
            mermaidService.export(b.getContent(), f.toPath(), appThemeDark(),
                    r -> reportPdf(new com.editora.pdf.PdfExportService.Result(r.ok(), r.message()), f));
        } else {
            java.nio.file.Path baseDir = b.getPath() == null ? null : b.getPath().getParent();
            java.util.List<String> mmdc = mermaidEnabled() ? mermaidService.mmdcCommand() : null;
            pdfService.exportMarkdown(b.getContent(), baseDir, config.getSettings().getPdfPageSize(),
                    mmdc, f.toPath(), r -> reportPdf(r, f));
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

    private void applyViewSettings(EditorBuffer buffer) {
        Settings s = config.getSettings();
        int effectiveFont = Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom()));
        buffer.setFont(s.getFontFamily(), effectiveFont);
        buffer.setColumnRulerVisible(s.isShowColumnRuler());
        buffer.setNoteIndicatorsVisible(s.isNotesSupport() && s.isShowNoteIndicators());
        buffer.setLineHighlightOn(s.isHighlightCurrentLine());
        buffer.setLineNumbersVisible(s.isShowLineNumbers());
        buffer.setMinimapVisible(s.isShowMinimap());
        buffer.setWhitespaceVisible(s.isShowWhitespace());
        buffer.setTabSize(s.getTabSize());
        buffer.setLineHighlightColor(EditorThemes.lineHighlightFor(s.getEditorTheme()));
        buffer.setMinimapColors(EditorThemes.minimapTextFor(s.getEditorTheme()),
                EditorThemes.minimapViewportFor(s.getEditorTheme()));
        buffer.setFoldPreviewColors(EditorThemes.editorBackgroundFor(s.getEditorTheme()),
                EditorThemes.editorForegroundFor(s.getEditorTheme()));
        buffer.setSpellLanguage(spellLanguageFor(buffer)); // per-file override, else the global default
        buffer.setSpellCheckEnabled(s.isSpellCheck());
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
        applyNotesSupport();
        applyMermaidSupport();
        applyAutoSave();
        applyAutocomplete();
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

    /** Pushes the autocomplete settings (master + per-source) to every open buffer. */
    private void applyAutocomplete() {
        Settings s = config.getSettings();
        boolean mermaidAc = effectiveMermaidAutocomplete();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null) {
                buffer.setAutocomplete(s.isAutocomplete(), s.isAutocompleteProse(),
                        s.isAutocompleteSnippets(), mermaidAc);
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
        com.editora.editor.Commenter.CommentStyle style =
                com.editora.editor.Commenter.styleFor(buffer.getLanguage());
        com.editora.editor.Commenter.Edit edit = com.editora.editor.Commenter.toggle(
                area.getText(), area.getSelection().getStart(), area.getSelection().getEnd(), style);
        if (edit == null) {
            setStatus(tr("status.noCommentSyntax"));
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.selectRange(edit.selStart(), edit.selEnd());
        area.requestFocus();
    }

    /** Applies an Emacs transpose (chars/words/lines) to the active editable buffer at the caret. */
    private void transpose(java.util.function.BiFunction<String, Integer,
            com.editora.editor.Transposer.Edit> op) {
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
                while (i < n && (text.charAt(i) == '"' || text.charAt(i) == '\''
                        || text.charAt(i) == ')' || text.charAt(i) == ']')) {
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
        registry.register(Command.of("project.open",
                () -> { if (projectsEnabled()) { folderFinder.show(stage); } }));
        registry.register(Command.of("project.switch",
                () -> { if (projectsEnabled()) { projectPicker.show(stage); } }));
        registry.register(Command.of("project.close",
                () -> { if (projectsEnabled()) { closeProject(); } }));
        registry.register(Command.of("project.delete",
                () -> { if (projectsEnabled()) { deleteProject(); } }));
        registry.register(Command.of("file.save", this::onSave));
        registry.register(Command.of("file.saveAs", this::onSaveAs));
        registry.register(Command.of("buffer.close", this::onCloseTab));
        registry.register(Command.of("buffer.closeOthers",
                () -> closeOtherTabs(activeTab())));
        registry.register(Command.of("buffer.closeAll", this::closeAllTabs));
        registry.register(Command.of("buffer.closeUnmodified",
                this::closeUnmodifiedTabs));
        registry.register(Command.of("buffer.closeLeft",
                () -> closeTabsToLeft(activeTab())));
        registry.register(Command.of("buffer.closeRight",
                () -> closeTabsToRight(activeTab())));
        registry.register(Command.of("buffer.copyPath",
                () -> copyPath(activeBuffer())));
        registry.register(Command.of("buffer.togglePin",
                () -> togglePin(activeTab())));
        registry.register(Command.of("buffer.rename",
                () -> renameFile(activeBuffer(), activeTab())));
        registry.register(Command.of("buffer.next", this::nextBuffer));
        registry.register(Command.of("app.quit", this::onQuit));
        registry.register(Command.of("palette.show", this::onPalette));
        registry.register(Command.of("view.settings", this::onSettings));
        registry.register(Command.of("theme.setAppTheme",
                this::chooseAppTheme));
        registry.register(Command.of("theme.setEditorTheme",
                this::chooseEditorTheme));
        registry.register(Command.of("view.toggleColumnRuler",
                this::toggleColumnRuler));
        registry.register(Command.of("view.toggleToolStripe",
                this::toggleToolStripe));
        registry.register(Command.of("config.export", this::exportConfig));
        registry.register(Command.of("editor.exportPdf", this::exportCodePdf));
        registry.register(Command.of("preview.exportPdf", this::exportPreviewPdf));
        registry.register(Command.of("mermaid.export", () -> ifMermaid(this::exportMermaid)));
        registry.register(Command.of("view.toggleLineHighlight",
                this::toggleLineHighlight));
        registry.register(Command.of("view.toggleLineNumbers",
                this::toggleLineNumbers));
        registry.register(Command.of("view.toggleMinimap",
                this::toggleMinimap));
        registry.register(Command.of("view.toggleWhitespace",
                this::toggleWhitespace));
        registry.register(Command.of("view.toggleSpellCheck",
                this::toggleSpellCheck));
        registry.register(Command.of("view.toggleAutocomplete", this::toggleAutocomplete));
        registry.register(Command.of("view.toggleAutocompleteProse", this::toggleAutocompleteProse));
        registry.register(Command.of("view.toggleAutocompleteSnippets", this::toggleAutocompleteSnippets));
        registry.register(Command.of("view.toggleAutocompleteMermaid", this::toggleAutocompleteMermaid));
        registry.register(Command.of("spell.setLanguage",
                this::chooseSpellLanguage));
        registry.register(Command.of("view.toggleToolbar", this::toggleToolbar));
        registry.register(Command.of("view.toggleStatusBar",
                this::toggleStatusBar));
        registry.register(Command.of("view.toggleTabBar", this::toggleTabBar));
        registry.register(Command.of("view.toggleBreadcrumb",
                this::toggleBreadcrumb));
        registry.register(Command.of("view.toggleZen", this::toggleZen));
        registry.register(Command.of("view.toggleReadOnly",
                this::toggleReadOnly));
        registry.register(Command.of("file.toggleAutoSave", this::toggleAutoSave));
        registry.register(Command.of("recent.jump",
                () -> recentPalette.show(stage)));
        registry.register(Command.of("structure.jump",
                () -> structurePalette.show(stage)));
        registry.register(Command.of("buffer.jump",
                () -> openFilesPalette.show(stage)));
        registry.register(Command.of("tool.jump",
                () -> toolWindowPalette.show(stage)));
        registry.register(Command.of("bookmarks.toggle",
                this::toggleBookmarkAtCaret));
        registry.register(Command.of("bookmarks.editNote",
                this::editBookmarkNoteAtCaret));
        registry.register(Command.of("bookmarks.next",
                () -> jumpBookmark(true)));
        registry.register(Command.of("bookmarks.previous",
                () -> jumpBookmark(false)));
        registry.register(Command.of("bookmarks.jump",
                () -> bookmarkPalette.show(stage)));
        registry.register(Command.of("bookmarks.clearFile",
                this::clearBookmarksInFile));
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
        registry.register(Command.of("snippets.editUser",
                this::editUserSnippets));
        registry.register(Command.of("view.splitVertical",
                this::onSplitVertical));
        registry.register(Command.of("view.splitHorizontal",
                this::onSplitHorizontal));
        registry.register(Command.of("view.unsplit", this::unsplit));
        registry.register(Command.of("view.markdownEditor",
                () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.EDITOR)));
        registry.register(Command.of("view.markdownSplit",
                () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.SPLIT)));
        registry.register(Command.of("view.markdownPreview",
                () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.PREVIEW)));
        registry.register(Command.of("view.markdownZoomIn",
                () -> markdownZoom(1)));
        registry.register(Command.of("view.markdownZoomOut",
                () -> markdownZoom(-1)));
        registry.register(Command.of("view.markdownZoomReset",
                () -> markdownZoom(0)));
        registry.register(Command.of("view.toggleMarkdownPreviewTheme",
                this::toggleMarkdownPreviewTheme));
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
        registry.register(Command.of("buffer.convertLineEndings",
                this::chooseLineEndings));
        registry.register(Command.of("window.other",
                this::otherWindow));
        // Cross-platform via JavaFX Stage (handles the per-OS window manager specifics on macOS/Linux/Windows).
        registry.register(Command.of("window.maximize",
                () -> stage.setMaximized(!stage.isMaximized())));
        registry.register(Command.of("window.fullScreen",
                () -> stage.setFullScreen(!stage.isFullScreen())));
        registry.register(Command.of("file.clearRecent", this::onClearRecent));
        registry.register(Command.of("help.about", this::onAbout));
        registry.register(Command.of("view.welcome", this::showWelcome));
        registry.register(Command.of("view.messageLog", statusBar::showMessageLog));
        registry.register(Command.of("tool.project",
                () -> { if (projectsEnabled()) { toolWindows.toggle(projectToolWindow); } }));
        registry.register(Command.of("tool.structure",
                () -> toolWindows.toggle(structureToolWindow)));
        registry.register(Command.of("tool.bookmarks",
                () -> toolWindows.toggle(bookmarksToolWindow)));
        registry.register(Command.of("tool.notes",
                () -> ifNotes(() -> toolWindows.toggle(notesToolWindow))));
        registry.register(Command.of("tool.fileInformation",
                () -> toolWindows.toggle(fileInfoToolWindow)));
        registry.register(Command.of("tool.commit",
                () -> ifGit(() -> toolWindows.toggle(commitToolWindow))));
        // Git (native CLI). Gated by the "Enable Git" setting (default off); also no-op when Git is
        // absent / not in a repo. The ifGit wrapper disables the commands + keybindings when Git is off.
        registry.register(Command.of("git.clone", () -> ifGit(this::gitClone)));
        registry.register(Command.of("git.commit", () -> ifGit(this::gitCommitFocus)));
        registry.register(Command.of("git.stageFile",
                () -> ifGit(this::gitStageActiveFile)));
        registry.register(Command.of("git.switchBranch", () -> ifGit(this::chooseBranch)));
        registry.register(Command.of("git.newBranch", () -> ifGit(this::newBranch)));
        registry.register(Command.of("git.fetch",
                () -> ifGit(() -> gitSync("Fetch", "fetch", "--all"))));
        registry.register(Command.of("git.pull",
                () -> ifGit(() -> gitSync("Pull", "pull", "--ff-only"))));
        registry.register(Command.of("git.push", () -> ifGit(this::gitPush)));
        registry.register(Command.of("git.refresh", () -> ifGit(() -> {
            gitService.invalidateCaches();
            afterGitMutation();
        })));
        registry.register(Command.of("switcher.show",
                () -> switcher.show(stage, false)));
        registry.register(Command.of("switcher.showReverse",
                () -> switcher.show(stage, true)));
        registry.register(Command.of("find.show", () -> toggleFind(false)));
        registry.register(Command.of("find.showBackward", () -> toggleFind(true)));
        registry.register(Command.of("find.replace", () -> toggleFind(false)));
        registry.register(Command.of("edit.cut", this::onCut));
        registry.register(Command.of("edit.copy", this::onCopy));
        registry.register(Command.of("edit.paste", this::onPaste));
        registry.register(Command.of("edit.undo", this::onUndo));
        registry.register(Command.of("edit.redo", this::onRedo));
        registry.register(Command.of("edit.cancel", this::cancel));
        registry.register(Command.of("edit.completion", this::triggerCompletion));
        registry.register(Command.of("edit.toggleComment", this::toggleComment));
        registry.register(Command.of("edit.transposeChars",
                () -> transpose(com.editora.editor.Transposer::transposeChars)));
        registry.register(Command.of("edit.transposeWords",
                () -> transpose(com.editora.editor.Transposer::transposeWords)));
        registry.register(Command.of("edit.transposeLines",
                () -> transpose(com.editora.editor.Transposer::transposeLines)));
        registry.register(Command.of("nav.lineStart",
                () -> moveAndFollow(a -> a.lineStart(selPolicy()))));
        registry.register(Command.of("nav.lineEnd",
                () -> moveAndFollow(a -> a.lineEnd(selPolicy()))));
        registry.register(Command.of("nav.docStart",
                () -> moveAndFollow(a -> a.start(selPolicy()))));
        registry.register(Command.of("nav.docEnd",
                () -> moveAndFollow(a -> a.end(selPolicy()))));
        registry.register(Command.of("nav.charForward",
                () -> moveAndFollow(a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1), selPolicy()))));
        registry.register(Command.of("nav.charBackward",
                () -> moveAndFollow(a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1), selPolicy()))));
        registry.register(Command.of("nav.lineDown", () -> moveLine(1)));
        registry.register(Command.of("nav.lineUp", () -> moveLine(-1)));
        registry.register(Command.of("nav.wordForward",
                () -> moveAndFollow(a -> a.moveTo(nextWordBoundary(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.wordBackward",
                () -> moveAndFollow(a -> a.moveTo(prevWordBoundary(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.pageDown",
                () -> { if (!pageActivePreview(true)) { moveAndFollow(a -> a.nextPage(selPolicy())); } }));
        registry.register(Command.of("nav.pageUp",
                () -> { if (!pageActivePreview(false)) { moveAndFollow(a -> a.prevPage(selPolicy())); } }));
        registry.register(Command.of("nav.backToIndentation",
                () -> moveAndFollow(a -> a.moveTo(backToIndentation(a), selPolicy()))));
        registry.register(Command.of("nav.paragraphForward",
                () -> moveAndFollow(a -> a.moveTo(forwardParagraph(a), selPolicy()))));
        registry.register(Command.of("nav.paragraphBackward",
                () -> moveAndFollow(a -> a.moveTo(backwardParagraph(a), selPolicy()))));
        registry.register(Command.of("nav.sentenceForward",
                () -> moveAndFollow(a -> a.moveTo(forwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.sentenceBackward",
                () -> moveAndFollow(a -> a.moveTo(backwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.recenter", this::recenterCaret));
        registry.register(Command.of("edit.setMark", this::setMark));
        registry.register(Command.of("edit.exchangePointAndMark",
                this::exchangePointAndMark));
        registry.register(Command.of("edit.deleteChar",
                () -> withArea(CodeArea::deleteNextChar)));
        registry.register(Command.of("edit.killWord",
                () -> withArea(a -> {
                    int caret = a.getCaretPosition();
                    a.deleteText(caret, nextWordBoundary(a.getText(), caret));
                })));
        registry.register(Command.of("edit.killLine",
                () -> withArea(this::killLine)));
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
