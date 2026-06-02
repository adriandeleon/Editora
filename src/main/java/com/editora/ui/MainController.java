package com.editora.ui;

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
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;

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
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.geometry.Pos;
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
    private ToolWindow fileInfoToolWindow;
    private FileInformationPanel fileInfoPanel;
    private StructurePanel structurePanel;
    private BookmarksPanel bookmarksPanel;
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
    /** Emacs mark: when set (C-SPC), caret movement extends the selection from the mark. */
    private boolean markActive;
    private RecentFiles recentFiles;

    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
        stage.setOnCloseRequest(e -> {
            if (!confirmQuit() || !confirmCloseAllBuffers()) {
                e.consume();
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        // Project commands (incl. the Project tool window) are hidden from the palette unless project
        // support is enabled.
        this.palette = new CommandPalette(registry, keymap,
                c -> config.getSettings().isProjectSupport()
                        || (!c.id().startsWith("project.") && !c.id().equals("tool.project")));
        this.findBar = new FindReplaceBar(this::activeArea, this::setStatus);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        this.breadcrumb = new FileBreadcrumb(this::openPath);
        // Breadcrumb sits just above the status bar at the bottom (IntelliJ-style).
        bottomBox.getChildren().setAll(breadcrumb, statusBar);
        setupToolWindows();
        this.settingsWindow = new SettingsWindow(config, toolWindows,
                this::applyViewSettingsToAllBuffers, this::setZenMode, this::openPath);
        this.switcher = new Switcher(this::openTabsForSwitcher,
                tab -> tabPane.getSelectionModel().select(tab),
                this::closeTabFromSwitcher,
                toolWindows);
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
    }

    private void setupRecentFiles() {
        recentFiles = new RecentFiles(config.getConfigDir());
        recentButton.setGraphic(Icons.recent());
        recentButton.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        recentButton.setTooltip(new Tooltip("Recent files"));

        // Rebuild the dropdown whenever the recent-files list changes.
        recentFiles.getList().addListener((ListChangeListener<Path>) c -> rebuildRecentMenu());
        rebuildRecentMenu();

        setupButton(clearRecentButton, Icons.trash(), "Clear recent files");
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
                tab -> bufferTitle(tab),
                tab -> bufferParentDir(tab),
                tab -> {
                    tabPane.getSelectionModel().select(tab);
                    EditorBuffer b = (EditorBuffer) tab.getUserData();
                    if (b != null) {
                        b.getArea().requestFocus();
                    }
                });
        toolWindowPalette = new QuickOpen<>("Jump to Tool Window", "Type to filter tool windows…",
                () -> toolWindows.getRegisteredToolWindows().stream()
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
            ((EditorBuffer) existing.getUserData()).getArea().requestFocus();
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(target);
        addBuffer(buffer);
        setStatus("New file: " + target.getFileName());
    }

    private static String bufferTitle(Tab tab) {
        EditorBuffer b = (EditorBuffer) tab.getUserData();
        return b == null ? "" : b.getTitle();
    }

    private static String bufferParentDir(Tab tab) {
        EditorBuffer b = (EditorBuffer) tab.getUserData();
        Path p = b == null ? null : b.getPath();
        return p == null || p.getParent() == null ? "" : p.getParent().toString();
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
        chooser.setTitle("Open Folder as Project");
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
            setStatus("No project");
        } else {
            projects.setActive(p.id());
            projects.save();
            config.setWorkspaceStateFile(projects.stateFile(p));
            activateSession(Path.of(p.root()));
            setStatus("Project: " + p.name());
        }
        return true;
    }

    /** Closes the active project (with confirmation), returning to the default global session. */
    private void closeProject() {
        Project active = projects.active();
        if (active == null) {
            setStatus("No project open");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Close project \"" + active.name() + "\"? Open files will be saved/closed and the "
                + "global session restored.", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("Close Project");
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
        setStatus("Project closed");
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
            setStatus("No project to delete");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete project \"" + p.name() + "\" from your projects list? The folder and its files "
                + "on disk are kept; only the project and its saved session are removed.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("Delete Project");
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
            config.useDefaultWorkspaceStateFile();
            activateSession(null); // restores the global session + refreshes the combos/panel
        } else {
            // Deleting a project we're not in: just drop it from the list; the session is unaffected.
            projects.delete(p.id());
            projects.save();
            refreshProjectPanelList();
        }
        setStatus("Deleted project " + p.name());
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
        if (keepProjectPanelOpen && projectsEnabled() && !toolWindows.isOpen(projectToolWindow)) {
            toolWindows.open(projectToolWindow);
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
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
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
        setStatus("Renamed to " + target.getFileName());
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
        setStatus("Deleted " + path.getFileName());
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
            MenuItem empty = new MenuItem("No recent files");
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
        removeBtn.setTooltip(new Tooltip("Remove from recent files"));
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
            EditorBuffer buffer = now == null ? null : (EditorBuffer) now.getUserData();
            fileInfoPanel.attach(buffer);
            structurePanel.attach(buffer);
            statusBar.attach(buffer);
            breadcrumb.setActiveFile(buffer == null ? null : buffer.getPath());
            if (AUTOSAVE_FOCUS.equals(autoSaveMode())) {
                autoSaveAllDirty(); // saves the outgoing buffer (and any other dirty ones)
            }
            refreshSplitButtons();
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
            while (c.next()) {
                // A pin reorder removes+re-adds the same tab; skip cleanup so it isn't forgotten.
                if (c.wasRemoved() && !reordering) {
                    mru.removeAll(c.getRemoved());
                    pinned.removeAll(c.getRemoved());
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
        projectToolWindow = new ToolWindow("project", "Project", ToolWindow.Side.RIGHT,
                Icons::project, projectPanel, "tool.project");
        structurePanel = new StructurePanel();
        structureToolWindow = new ToolWindow("structure", "Structure", ToolWindow.Side.RIGHT,
                Icons::structure, structurePanel, "tool.structure");
        bookmarksPanel = new BookmarksPanel(() -> config.getWorkspaceState().getBookmarks(),
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
                });
        bookmarksToolWindow = new ToolWindow("bookmarks", "Bookmarks", ToolWindow.Side.RIGHT,
                Icons::bookmark, bookmarksPanel, "tool.bookmarks");
        fileInfoPanel = new FileInformationPanel();
        fileInfoToolWindow = new ToolWindow("file-information", "File Information", ToolWindow.Side.RIGHT,
                Icons::about, fileInfoPanel, "tool.fileInformation");
        toolWindows.register(projectToolWindow);
        toolWindows.register(structureToolWindow);
        toolWindows.register(bookmarksToolWindow);
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

    private void setupToolbar() {
        setupButton(newButton, Icons.newFile(), "New");
        setupButton(openButton, Icons.open(), "Open (C-x C-f)");
        setupButton(openFolderButton, Icons.openFolder(), "Open Folder as Project…");
        setupButton(saveButton, Icons.save(), "Save (C-x C-s)");
        setupButton(saveAsButton, Icons.saveAs(), "Save As (C-x C-w)");
        setupButton(undoButton, Icons.undo(), "Undo (C-/)");
        setupButton(redoButton, Icons.redo(), "Redo (C-S-/)");
        setupButton(cutButton, Icons.cut(), "Cut (C-w)");
        setupButton(copyButton, Icons.copy(), "Copy (M-w)");
        setupButton(pasteButton, Icons.paste(), "Paste (C-y)");
        setupButton(findButton, Icons.find(), "Find / Replace (C-s)");
        setupButton(splitVerticalButton, Icons.splitVertical(), "Split Editor — Side by Side (C-x 3)");
        setupButton(splitHorizontalButton, Icons.splitHorizontal(), "Split Editor — Stacked (C-x 2)");
        setupButton(paletteButton, Icons.palette(), "Command Palette (M-x)");
        setupButton(closeTabButton, Icons.closeTab(), "Close Tab (C-x k)");
        setupButton(settingsButton, Icons.settings(), "Settings");
        setupButton(aboutButton, Icons.about(), "About Editora");
        setupButton(quitButton, Icons.quit(), "Quit (C-x C-c)");

        // Reflect open/closed state of the palette and find bar in their toolbar buttons.
        palette.showingProperty().addListener(
                (obs, was, now) -> paletteButton.pseudoClassStateChanged(OPEN, now));
        findBar.visibleProperty().addListener(
                (obs, was, now) -> findButton.pseudoClassStateChanged(OPEN, now));
        // Project switcher (placed right of the Settings icon by arrangeToolbarTail); shown when enabled.
        toolbarProjectCombo = new ProjectCombo(this::switchToProject);
        toolbarProjectCombo.setPrefWidth(184); // 15% longer than the previous 160
        projectToolbarLabel = new Label("Project:");
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
            Label hint = new Label("Switcher: " + chord);
            hint.getStyleClass().add("toolbar-hint");
            hint.setTooltip(new Tooltip(
                    "Switcher (" + chord + "): quickly jump between open files and tool windows."));
            items.add(hint);
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
        setStatus(buffer.getSplit() == EditorBuffer.Split.NONE ? "Editor unsplit" : "Editor split");
    }

    private void unsplit() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.setSplit(EditorBuffer.Split.NONE);
            refreshSplitButtons();
            setStatus("Editor unsplit");
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
            addBuffer(new EditorBuffer());
            runPendingAfterRestore();
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
    public void startup(Path projectDir, List<OpenTarget> targets, boolean zen) {
        if (projectDir != null && projectsEnabled()) {
            activateStartupProject(projectDir); // swap to the project's session before it's restored
        }
        // Run CLI actions AFTER the (deferred, pulse-paced) session restore, so a restored caret can't
        // override a requested line:column.
        pendingAfterRestore = () -> applyStartupTargets(targets, zen);
        openInitialBuffer();
    }

    private void applyStartupTargets(List<OpenTarget> targets, boolean zen) {
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
        EditorBuffer buffer = (EditorBuffer) tab.getUserData();
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
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        return tab == null ? null : (EditorBuffer) tab.getUserData();
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
        applyViewSettings(buffer);
        buffer.getFoldManager().setOnFoldStateChanged(() -> persistFolds(buffer));
        buffer.setOnBookmarksChanged(() -> persistBookmarks(buffer));
        buffer.setGutterBookmarkClick(this::onGutterBookmarkClick);
        buffer.setOnEnableEditing(() -> enableEditing(buffer)); // "Enable Editing" banner button
        if (buffer.isMarkdown()) {
            MarkdownViewToggle toggle = new MarkdownViewToggle(buffer);
            buffer.setOnViewModeChanged(() -> {
                persistMarkdownMode(buffer);
                toggle.sync();
            });
            buffer.setViewModeControl(toggle); // floating Editor/Split/Preview control, top-right
        }
        Tab tab = new Tab();
        tab.setContent(buffer.getNode());
        tab.setUserData(buffer);
        tab.setOnCloseRequest(e -> {
            if (!confirmClose(tab)) {
                e.consume();
            }
        });
        updateTabMeta(tab, buffer);
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
        tabPane.getTabs().add(tab);
        if (select) {
            tabPane.getSelectionModel().select(tab);
            buffer.getArea().requestFocus();
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
        setStatus("New buffer");
    }

    @FXML
    private void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open File");
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
            ((EditorBuffer) existing.getUserData()).getArea().requestFocus();
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus("Already open: " + file.getFileName());
            return;
        }
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            String note = loadInto(buffer, file);
            // Apply folds before the node is in the scene, so each fold skips per-fold layout.
            restoreFolds(buffer);
            restoreBookmarks(buffer);
            restoreReadOnly(buffer); // before addBuffer so the tab meta reflects read-only
            addBuffer(buffer);
            restoreMarkdownMode(buffer); // after addBuffer so the toggle is wired
            // Land on the first line: replaceText leaves the caret at the end, and fold restoration
            // moves it to a fold header. Defer so the viewport scroll runs after the tab is laid out.
            Platform.runLater(buffer::goToStart);
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus(note.isEmpty() ? "Opened " + file : note);
        } catch (IOException e) {
            setStatus("Failed to open: " + e.getMessage());
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

    @FXML
    private void onClearRecent() {
        if (recentFiles == null || recentFiles.getList().isEmpty()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Clear recent files");
        alert.setHeaderText("Clear the entire recent files list?");
        alert.setContentText(null);
        ButtonType clear = new ButtonType("Clear");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(clear, cancel);
        if (alert.showAndWait().filter(b -> b == clear).isPresent()) {
            recentFiles.clear();
            setStatus("Recent files cleared");
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
        chooser.setTitle("Save As");
        Path file = pathOf(chooser.showSaveDialog(stage));
        if (file == null) {
            return false;
        }
        buffer.setPath(file);
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
            setStatus("Saved " + file);
            return true;
        } catch (IOException e) {
            setStatus("Failed to save: " + e.getMessage());
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
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
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
                    setStatus("Auto-saved " + file.getFileName());
                });
            } catch (IOException e) {
                Platform.runLater(() -> setStatus("Auto-save failed: " + e.getMessage()));
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
        setStatus("Auto save: " + autoSaveLabel(next));
    }

    /** Human-readable label for an auto-save mode key (also used by the settings combo). */
    static String autoSaveLabel(String mode) {
        return switch (mode) {
            case AUTOSAVE_DELAY -> "After delay";
            case AUTOSAVE_FOCUS -> "On focus change";
            default -> "Off";
        };
    }

    @FXML
    private void onCloseTab() {
        closeTab(activeTab());
    }

    private Tab activeTab() {
        return tabPane.getSelectionModel().getSelectedItem();
    }

    private static EditorBuffer bufferOf(Tab tab) {
        return tab == null ? null : (EditorBuffer) tab.getUserData();
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
        setStatus("Copied path");
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
        setStatus(pinned.contains(tab) ? "Pinned" : "Unpinned");
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
        dialog.setTitle("Rename File");
        dialog.setHeaderText(null);
        dialog.setContentText("New name:");
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
                setStatus("Rename failed: " + target.getFileName() + " already exists");
                return;
            }
            try {
                Files.move(old, target);
            } catch (IOException e) {
                setStatus("Rename failed: " + e.getMessage());
                return;
            }
            buffer.setPath(target); // re-detects language/grammar
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
            updateTabMeta(tab, buffer);
            statusBar.refresh();
            if (buffer == activeBuffer()) {
                breadcrumb.setActiveFile(buffer.getPath());
            }
            setStatus("Renamed to " + target.getFileName());
        });
    }

    /** Builds and attaches the right-click context menu for a tab. */
    private void installTabMenu(Tab tab, EditorBuffer buffer) {
        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> closeTab(tab));
        MenuItem closeOthers = new MenuItem("Close Other Tabs");
        closeOthers.setOnAction(e -> closeOtherTabs(tab));
        MenuItem closeAll = new MenuItem("Close All Tabs");
        closeAll.setOnAction(e -> closeAllTabs());
        MenuItem closeUnmodified = new MenuItem("Close Unmodified Tabs");
        closeUnmodified.setOnAction(e -> closeUnmodifiedTabs());
        MenuItem closeLeft = new MenuItem("Close Tabs to the Left");
        closeLeft.setOnAction(e -> closeTabsToLeft(tab));
        MenuItem closeRight = new MenuItem("Close Tabs to the Right");
        closeRight.setOnAction(e -> closeTabsToRight(tab));
        MenuItem copyPath = new MenuItem("Copy Path");
        copyPath.setOnAction(e -> copyPath(buffer));
        MenuItem pin = new MenuItem("Pin Tab");
        pin.setOnAction(e -> togglePin(tab));
        MenuItem rename = new MenuItem("Rename File…");
        rename.setOnAction(e -> renameFile(buffer, tab));

        ContextMenu menu = new ContextMenu(
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
        alert.setTitle("Pinned tab");
        alert.setHeaderText("Close pinned tab " + buffer.getTitle() + "?");
        alert.setContentText(null);
        ButtonType close = new ButtonType("Close");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
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
        alert.setTitle("Unsaved changes");
        alert.setHeaderText("Save changes to " + buffer.getTitle() + " before closing?");
        alert.setContentText(null);
        ButtonType save = new ButtonType("Save");
        ButtonType discard = new ButtonType("Discard");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
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
        EditorBuffer buffer = (EditorBuffer) tab.getUserData();
        return buffer != null && buffer.isDirty();
    }

    private Tab tabForPath(Path file) {
        Path target = file.toAbsolutePath().normalize();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
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
        alert.setTitle("Quit Editora");
        alert.setHeaderText("Quit Editora?");
        alert.setContentText(null);
        ButtonType quit = new ButtonType("Quit");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(quit, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == quit;
    }

    /** Walks every tab and prompts to save/discard each dirty buffer. False = user cancelled. */
    private boolean confirmCloseAllBuffers() {
        for (Tab tab : new ArrayList<>(tabPane.getTabs())) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
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
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
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
        setStatus(had ? "Cut" : "Nothing to cut");
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
        setStatus(had ? "Copied" : "Nothing to copy (no selection)");
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
        setStatus("Pasted");
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
        SettingsWindow.showAbout(stage, this::openPath);
    }

    private void toggleColumnRuler() {
        Settings s = config.getSettings();
        s.setShowColumnRuler(!s.isShowColumnRuler());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("80-column ruler: " + (s.isShowColumnRuler() ? "on" : "off"));
    }

    private void toggleLineHighlight() {
        Settings s = config.getSettings();
        s.setHighlightCurrentLine(!s.isHighlightCurrentLine());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Current line highlight: " + (s.isHighlightCurrentLine() ? "on" : "off"));
    }

    private void toggleLineNumbers() {
        Settings s = config.getSettings();
        s.setShowLineNumbers(!s.isShowLineNumbers());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Line numbers: " + (s.isShowLineNumbers() ? "on" : "off"));
    }

    private void toggleMinimap() {
        Settings s = config.getSettings();
        s.setShowMinimap(!s.isShowMinimap());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Minimap: " + (s.isShowMinimap() ? "on" : "off"));
    }

    private void toggleWhitespace() {
        Settings s = config.getSettings();
        s.setShowWhitespace(!s.isShowWhitespace());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Hidden characters: " + (s.isShowWhitespace() ? "on" : "off"));
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
        setStatus("Zen mode: " + (on ? "on" : "off"));
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
        setStatus("Toolbar: " + (s.isShowToolbar() ? "on" : "off"));
    }

    private void toggleBreadcrumb() {
        Settings s = config.getSettings();
        s.setShowBreadcrumb(!s.isShowBreadcrumb());
        config.save();
        applyChromeVisibility();
        setStatus("File breadcrumb: " + (s.isShowBreadcrumb() ? "on" : "off"));
    }

    private void toggleStatusBar() {
        Settings s = config.getSettings();
        s.setShowStatusBar(!s.isShowStatusBar());
        config.save();
        applyChromeVisibility();
        // The status bar may now be hidden, so this message just confirms the toggle while visible.
        setStatus("Status bar: " + (s.isShowStatusBar() ? "on" : "off"));
    }

    private void toggleTabBar() {
        Settings s = config.getSettings();
        s.setShowTabBar(!s.isShowTabBar());
        config.save();
        applyChromeVisibility();
        setStatus("Tab bar: " + (s.isShowTabBar() ? "on" : "off"));
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
            setStatus("Folded all regions");
        }
    }

    private void unfoldAll() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.unfoldAll();
            setStatus("Unfolded all regions");
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
        dialog.setTitle("Go to Line");
        dialog.setHeaderText("Enter a line number, or line:column (column is optional).");
        dialog.setContentText("Line (1–" + total + "):");
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
                setStatus("Line " + (targetLine + 1) + ", Col " + (targetColumn + 1));
            } catch (NumberFormatException e) {
                setStatus("Not a valid line or line:column — " + input);
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
        dialog.setTitle("Set Language");
        dialog.setHeaderText(null);
        dialog.setContentText("Language:");
        dialog.showAndWait().ifPresent(name -> {
            buffer.setLanguageOverride(name);
            statusBar.refresh();
            setStatus("Language: " + name);
        });
    }

    /** Changes the (persisted) tab width and applies it to every buffer. */
    private void chooseTabSize() {
        Settings s = config.getSettings();
        List<Integer> options = List.of(2, 4, 8);
        ChoiceDialog<Integer> dialog = new ChoiceDialog<>(
                options.contains(s.getTabSize()) ? s.getTabSize() : 4, options);
        dialog.initOwner(stage);
        dialog.setTitle("Tab Size");
        dialog.setHeaderText(null);
        dialog.setContentText("Tab size (columns):");
        dialog.showAndWait().ifPresent(size -> {
            s.setTabSize(size);
            config.save();
            applyViewSettingsToAllBuffers(s);
            statusBar.refresh();
            setStatus("Tab size: " + size);
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
        dialog.setTitle("Line Endings");
        dialog.setHeaderText(null);
        dialog.setContentText("Line endings:");
        dialog.showAndWait().ifPresent(choice -> {
            buffer.convertLineEndings("CRLF".equals(choice));
            statusBar.refresh();
            setStatus("Line endings: " + choice);
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
        var map = config.getWorkspaceState().getBookmarks();
        if (marks.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), marks);
        }
        config.save();
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
                buffer.applyBookmarks(config.getWorkspaceState().getBookmarks().get(file.toString()));
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
            setStatus("Large file is read-only and can't be made editable");
            return;
        }
        buffer.setViewMode(!buffer.isViewMode());
        afterReadOnlyChange(buffer);
        setStatus(buffer.isViewMode() ? "Read-only (View mode) — C-x C-q to edit" : "Editable");
    }

    /** Turns off read-only ("Enable Editing" banner button); persists + refreshes the indicators. */
    private void enableEditing(EditorBuffer buffer) {
        if (buffer == null || !buffer.isViewMode()) {
            return;
        }
        buffer.setViewMode(false);
        afterReadOnlyChange(buffer);
        setStatus("Editing enabled");
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

    /** Restores a Markdown file's saved view mode after it is opened (and its toggle is wired). */
    private void restoreMarkdownMode(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || !buffer.isMarkdown()) {
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

    /** Sets the active Markdown buffer's view mode (no-op for non-Markdown files). */
    private void setActiveMarkdownMode(EditorBuffer.MarkdownViewMode mode) {
        EditorBuffer b = activeBuffer();
        if (b != null && b.isMarkdown()) {
            b.setMarkdownViewMode(mode);
        } else {
            setStatus("Not a Markdown file");
        }
    }

    /** Zooms the active Markdown buffer's preview text: {@code >0} in, {@code <0} out, {@code 0} reset. */
    private void markdownZoom(int direction) {
        EditorBuffer b = activeBuffer();
        if (b == null || !b.isMarkdown()) {
            setStatus("Not a Markdown file");
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
        setStatus("Text zoom: " + Math.round(z * 100) + "%");
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
                    "Remove the bookmark on line " + (line + 1) + "?", ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(stage);
            confirm.setTitle("Remove Bookmark");
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
            setStatus("Save the file before bookmarking");
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
        dialog.setTitle("Bookmark Note");
        dialog.setHeaderText(null);
        dialog.setContentText("Note:");
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
            setStatus("No bookmarks in this file");
        }
    }

    /** Clears every bookmark in the active file. */
    private void clearBookmarksInFile() {
        EditorBuffer b = activeBuffer();
        if (b != null) {
            b.clearBookmarks();
        }
    }

    /** All bookmarks across all files, flattened for the jump picker (sorted by file then line). */
    private List<BookmarkEntry> allBookmarkEntries() {
        List<BookmarkEntry> out = new ArrayList<>();
        config.getWorkspaceState().getBookmarks().forEach((path, marks) -> {
            if (marks != null) {
                Path file = Path.of(path);
                marks.forEach(bm -> out.add(new BookmarkEntry(file, bm)));
            }
        });
        out.sort(java.util.Comparator
                .comparing((BookmarkEntry e) -> e.file().toString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(e -> e.bm().line()));
        return out;
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
        if (tab != null) {
            ((EditorBuffer) tab.getUserData()).getBookmarkManager().setNote(line, note);
        } else {
            updateClosedFileBookmarks(file, marks -> marks.replaceAll(
                    bm -> bm.line() == line ? bm.withNote(note) : bm));
        }
    }

    /** Deletes one bookmark — via the open buffer if loaded, else directly in the persisted map. */
    private void bookmarkDelete(Path file, int line) {
        Tab tab = tabForPath(file);
        if (tab != null) {
            ((EditorBuffer) tab.getUserData()).removeBookmark(line);
        } else {
            updateClosedFileBookmarks(file, marks -> marks.removeIf(bm -> bm.line() == line));
        }
    }

    /** Deletes all bookmarks in a file — via the open buffer if loaded, else the persisted map. */
    private void bookmarkDeleteAll(Path file) {
        Tab tab = tabForPath(file);
        if (tab != null) {
            ((EditorBuffer) tab.getUserData()).clearBookmarks();
        } else {
            config.getWorkspaceState().getBookmarks().remove(file.toString());
            config.save();
            bookmarksPanel.refresh();
        }
    }

    /** Applies a mutation to a closed file's bookmark list in the persisted map, then saves + refreshes. */
    private void updateClosedFileBookmarks(Path file,
            java.util.function.Consumer<List<com.editora.config.Bookmark>> mutator) {
        var map = config.getWorkspaceState().getBookmarks();
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
        config.save();
        bookmarksPanel.refresh();
    }

    private void applyViewSettings(EditorBuffer buffer) {
        Settings s = config.getSettings();
        int effectiveFont = Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom()));
        buffer.setFont(s.getFontFamily(), effectiveFont);
        buffer.setColumnRulerVisible(s.isShowColumnRuler());
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
    }

    private void applyViewSettingsToAllBuffers(Settings settings) {
        applyEditorTheme(settings.getEditorTheme());
        applyChromeVisibility();
        applyProjectSupport();
        applyAutoSave();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            if (buffer != null) {
                applyViewSettings(buffer);
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
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            if (buffer != null) {
                buffer.setLineHighlightColor(highlight);
                buffer.setMinimapColors(mmText, mmViewport);
                buffer.setFoldPreviewColors(editorBg, editorFg);
            }
        }
    }

    private void cancel() {
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
        setStatus("Mark set");
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
            setStatus("Buffer is read-only — C-x C-q to allow edits");
            return false;
        }
        return true;
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
        registry.register(Command.of("file.new", "File: New", this::onNew));
        registry.register(Command.of("file.open", "File: Open…", this::onOpen));
        registry.register(Command.of("file.find", "File: Find File…", () -> fileFinder.show(stage)));
        // Project commands no-op when project support is disabled (fully gated).
        registry.register(Command.of("project.open", "Project: Open Folder…",
                () -> { if (projectsEnabled()) { folderFinder.show(stage); } }));
        registry.register(Command.of("project.switch", "Project: Switch…",
                () -> { if (projectsEnabled()) { projectPicker.show(stage); } }));
        registry.register(Command.of("project.close", "Project: Close",
                () -> { if (projectsEnabled()) { closeProject(); } }));
        registry.register(Command.of("project.delete", "Project: Delete…",
                () -> { if (projectsEnabled()) { deleteProject(); } }));
        registry.register(Command.of("file.save", "File: Save", this::onSave));
        registry.register(Command.of("file.saveAs", "File: Save As…", this::onSaveAs));
        registry.register(Command.of("buffer.close", "Buffer: Close", this::onCloseTab));
        registry.register(Command.of("buffer.closeOthers", "Buffer: Close Other Tabs",
                () -> closeOtherTabs(activeTab())));
        registry.register(Command.of("buffer.closeAll", "Buffer: Close All Tabs", this::closeAllTabs));
        registry.register(Command.of("buffer.closeUnmodified", "Buffer: Close Unmodified Tabs",
                this::closeUnmodifiedTabs));
        registry.register(Command.of("buffer.closeLeft", "Buffer: Close Tabs to the Left",
                () -> closeTabsToLeft(activeTab())));
        registry.register(Command.of("buffer.closeRight", "Buffer: Close Tabs to the Right",
                () -> closeTabsToRight(activeTab())));
        registry.register(Command.of("buffer.copyPath", "Buffer: Copy Path",
                () -> copyPath(activeBuffer())));
        registry.register(Command.of("buffer.togglePin", "Buffer: Toggle Pin",
                () -> togglePin(activeTab())));
        registry.register(Command.of("buffer.rename", "Buffer: Rename File…",
                () -> renameFile(activeBuffer(), activeTab())));
        registry.register(Command.of("buffer.next", "Buffer: Next", this::nextBuffer));
        registry.register(Command.of("app.quit", "Application: Quit", this::onQuit));
        registry.register(Command.of("palette.show", "Command Palette", this::onPalette));
        registry.register(Command.of("view.settings", "Settings", this::onSettings));
        registry.register(Command.of("view.toggleColumnRuler", "View: Toggle 80-Column Ruler",
                this::toggleColumnRuler));
        registry.register(Command.of("view.toggleLineHighlight", "View: Toggle Current Line Highlight",
                this::toggleLineHighlight));
        registry.register(Command.of("view.toggleLineNumbers", "View: Toggle Line Numbers",
                this::toggleLineNumbers));
        registry.register(Command.of("view.toggleMinimap", "View: Toggle Minimap",
                this::toggleMinimap));
        registry.register(Command.of("view.toggleWhitespace", "View: Toggle Hidden Characters",
                this::toggleWhitespace));
        registry.register(Command.of("view.toggleToolbar", "View: Toggle Toolbar", this::toggleToolbar));
        registry.register(Command.of("view.toggleStatusBar", "View: Toggle Status Bar",
                this::toggleStatusBar));
        registry.register(Command.of("view.toggleTabBar", "View: Toggle Tab Bar", this::toggleTabBar));
        registry.register(Command.of("view.toggleBreadcrumb", "View: Toggle File Breadcrumb",
                this::toggleBreadcrumb));
        registry.register(Command.of("view.toggleZen", "View: Toggle Zen Mode", this::toggleZen));
        registry.register(Command.of("view.toggleReadOnly", "View: Toggle Read-Only (View Mode)",
                this::toggleReadOnly));
        registry.register(Command.of("file.toggleAutoSave", "File: Toggle Auto Save", this::toggleAutoSave));
        registry.register(Command.of("recent.jump", "Recent Files: Jump…",
                () -> recentPalette.show(stage)));
        registry.register(Command.of("structure.jump", "Structure: Jump…",
                () -> structurePalette.show(stage)));
        registry.register(Command.of("buffer.jump", "Open Files: Jump…",
                () -> openFilesPalette.show(stage)));
        registry.register(Command.of("tool.jump", "Tool Windows: Jump…",
                () -> toolWindowPalette.show(stage)));
        registry.register(Command.of("bookmarks.toggle", "Bookmarks: Toggle on Line",
                this::toggleBookmarkAtCaret));
        registry.register(Command.of("bookmarks.editNote", "Bookmarks: Edit Note…",
                this::editBookmarkNoteAtCaret));
        registry.register(Command.of("bookmarks.next", "Bookmarks: Next in File",
                () -> jumpBookmark(true)));
        registry.register(Command.of("bookmarks.previous", "Bookmarks: Previous in File",
                () -> jumpBookmark(false)));
        registry.register(Command.of("bookmarks.jump", "Bookmarks: Jump…",
                () -> bookmarkPalette.show(stage)));
        registry.register(Command.of("bookmarks.clearFile", "Bookmarks: Clear in File",
                this::clearBookmarksInFile));
        registry.register(Command.of("view.splitVertical", "View: Split Editor — Side by Side",
                this::onSplitVertical));
        registry.register(Command.of("view.splitHorizontal", "View: Split Editor — Stacked",
                this::onSplitHorizontal));
        registry.register(Command.of("view.unsplit", "View: Unsplit Editor", this::unsplit));
        registry.register(Command.of("view.markdownEditor", "Markdown: Editor",
                () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.EDITOR)));
        registry.register(Command.of("view.markdownSplit", "Markdown: Editor and Preview",
                () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.SPLIT)));
        registry.register(Command.of("view.markdownPreview", "Markdown: Preview",
                () -> setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.PREVIEW)));
        registry.register(Command.of("view.markdownZoomIn", "Markdown: Zoom In Preview",
                () -> markdownZoom(1)));
        registry.register(Command.of("view.markdownZoomOut", "Markdown: Zoom Out Preview",
                () -> markdownZoom(-1)));
        registry.register(Command.of("view.markdownZoomReset", "Markdown: Reset Preview Zoom",
                () -> markdownZoom(0)));
        registry.register(Command.of("view.textZoomIn", "View: Zoom In Text", () -> textZoom(1)));
        registry.register(Command.of("view.textZoomOut", "View: Zoom Out Text", () -> textZoom(-1)));
        registry.register(Command.of("view.textZoomReset", "View: Reset Text Zoom", () -> textZoom(0)));
        registry.register(Command.of("view.foldAll", "View: Fold All", this::foldAll));
        registry.register(Command.of("view.unfoldAll", "View: Unfold All", this::unfoldAll));
        registry.register(Command.of("view.fold", "View: Fold", this::foldAtCaret));
        registry.register(Command.of("view.unfold", "View: Unfold", this::unfoldAtCaret));
        registry.register(Command.of("view.toggleFold", "View: Toggle Fold", this::toggleFoldAtCaret));
        registry.register(Command.of("nav.goToLine", "Go: Go to Line…", this::goToLine));
        registry.register(Command.of("buffer.setLanguage", "Buffer: Set Language…", this::chooseLanguage));
        registry.register(Command.of("buffer.setTabSize", "Buffer: Set Tab Size…", this::chooseTabSize));
        registry.register(Command.of("buffer.convertLineEndings", "Buffer: Convert Line Endings (LF/CRLF)…",
                this::chooseLineEndings));
        registry.register(Command.of("window.other", "Window: Other (Editor / Tool Window)",
                this::otherWindow));
        // Cross-platform via JavaFX Stage (handles the per-OS window manager specifics on macOS/Linux/Windows).
        registry.register(Command.of("window.maximize", "Window: Toggle Maximize",
                () -> stage.setMaximized(!stage.isMaximized())));
        registry.register(Command.of("window.fullScreen", "Window: Toggle Full Screen",
                () -> stage.setFullScreen(!stage.isFullScreen())));
        registry.register(Command.of("file.clearRecent", "File: Clear Recent Files", this::onClearRecent));
        registry.register(Command.of("help.about", "About Editora", this::onAbout));
        registry.register(Command.of("tool.project", "Tool Window: Project",
                () -> { if (projectsEnabled()) { toolWindows.toggle(projectToolWindow); } }));
        registry.register(Command.of("tool.structure", "Tool Window: Structure",
                () -> toolWindows.toggle(structureToolWindow)));
        registry.register(Command.of("tool.bookmarks", "Tool Window: Bookmarks",
                () -> toolWindows.toggle(bookmarksToolWindow)));
        registry.register(Command.of("tool.fileInformation", "Tool Window: File Information",
                () -> toolWindows.toggle(fileInfoToolWindow)));
        registry.register(Command.of("switcher.show", "Switcher",
                () -> switcher.show(stage, false)));
        registry.register(Command.of("switcher.showReverse", "Switcher (Reverse)",
                () -> switcher.show(stage, true)));
        registry.register(Command.of("find.show", "Find", () -> toggleFind(false)));
        registry.register(Command.of("find.showBackward", "Find Backward", () -> toggleFind(true)));
        registry.register(Command.of("find.replace", "Replace", () -> toggleFind(false)));
        registry.register(Command.of("edit.cut", "Edit: Cut", this::onCut));
        registry.register(Command.of("edit.copy", "Edit: Copy", this::onCopy));
        registry.register(Command.of("edit.paste", "Edit: Paste", this::onPaste));
        registry.register(Command.of("edit.undo", "Edit: Undo", this::onUndo));
        registry.register(Command.of("edit.redo", "Edit: Redo", this::onRedo));
        registry.register(Command.of("edit.cancel", "Cancel", this::cancel));
        registry.register(Command.of("nav.lineStart", "Go: Line Start",
                () -> moveAndFollow(a -> a.lineStart(selPolicy()))));
        registry.register(Command.of("nav.lineEnd", "Go: Line End",
                () -> moveAndFollow(a -> a.lineEnd(selPolicy()))));
        registry.register(Command.of("nav.docStart", "Go: Document Start",
                () -> moveAndFollow(a -> a.start(selPolicy()))));
        registry.register(Command.of("nav.docEnd", "Go: Document End",
                () -> moveAndFollow(a -> a.end(selPolicy()))));
        registry.register(Command.of("nav.charForward", "Go: Forward Char",
                () -> moveAndFollow(a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1), selPolicy()))));
        registry.register(Command.of("nav.charBackward", "Go: Backward Char",
                () -> moveAndFollow(a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1), selPolicy()))));
        registry.register(Command.of("nav.lineDown", "Go: Next Line", () -> moveLine(1)));
        registry.register(Command.of("nav.lineUp", "Go: Previous Line", () -> moveLine(-1)));
        registry.register(Command.of("nav.wordForward", "Go: Forward Word",
                () -> moveAndFollow(a -> a.moveTo(nextWordBoundary(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.wordBackward", "Go: Backward Word",
                () -> moveAndFollow(a -> a.moveTo(prevWordBoundary(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.pageDown", "Go: Page Down",
                () -> moveAndFollow(a -> a.nextPage(selPolicy()))));
        registry.register(Command.of("nav.pageUp", "Go: Page Up",
                () -> moveAndFollow(a -> a.prevPage(selPolicy()))));
        registry.register(Command.of("nav.backToIndentation", "Go: Back to Indentation",
                () -> moveAndFollow(a -> a.moveTo(backToIndentation(a), selPolicy()))));
        registry.register(Command.of("nav.paragraphForward", "Go: Forward Paragraph",
                () -> moveAndFollow(a -> a.moveTo(forwardParagraph(a), selPolicy()))));
        registry.register(Command.of("nav.paragraphBackward", "Go: Backward Paragraph",
                () -> moveAndFollow(a -> a.moveTo(backwardParagraph(a), selPolicy()))));
        registry.register(Command.of("nav.sentenceForward", "Go: Forward Sentence",
                () -> moveAndFollow(a -> a.moveTo(forwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.sentenceBackward", "Go: Backward Sentence",
                () -> moveAndFollow(a -> a.moveTo(backwardSentence(a.getText(), a.getCaretPosition()), selPolicy()))));
        registry.register(Command.of("nav.recenter", "Go: Recenter on Caret", this::recenterCaret));
        registry.register(Command.of("edit.setMark", "Edit: Set Mark", this::setMark));
        registry.register(Command.of("edit.exchangePointAndMark", "Edit: Exchange Point and Mark",
                this::exchangePointAndMark));
        registry.register(Command.of("edit.deleteChar", "Edit: Delete Forward Char",
                () -> withArea(CodeArea::deleteNextChar)));
        registry.register(Command.of("edit.killWord", "Edit: Kill Forward Word",
                () -> withArea(a -> {
                    int caret = a.getCaretPosition();
                    a.deleteText(caret, nextWordBoundary(a.getText(), caret));
                })));
        registry.register(Command.of("edit.killLine", "Edit: Kill Line",
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
