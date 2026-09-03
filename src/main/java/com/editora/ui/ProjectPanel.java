package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import com.editora.editor.NoteDraft;
import com.editora.search.FuzzyMatch;

import static com.editora.i18n.Messages.tr;

/**
 * The Project tool window: a filter box over a lazy file tree or spatial map rooted at the active project's
 * folder. The Canvas map is an alternate navigation surface and shares the tree's context-menu actions.
 * Typing in the filter runs a bounded, debounced project-wide name search (dot-dirs skipped, capped)
 * and shows matches as a flat list; clearing it restores the lazy tree. Emacs-style keyboard nav
 * (C-n/C-p, C-f/C-b, Enter) like the Structure panel; Enter/double-click opens a file; a right-click
 * menu renames/deletes files. Acts as a mini file manager: multi-select (Ctrl/Cmd- and Shift-click),
 * drag a file/folder (or the whole selection) onto a folder to <b>move</b> it there, and delete the whole
 * selection at once. (Project switch/close/delete live on the toolbar project combo and the
 * {@code project.*} palette commands — each window is one project, named in the window title.)
 */
public class ProjectPanel extends VBox implements ToolWindowContent {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ProjectPanel.class.getName());

    private static final int MAX_VISIT = 20_000;
    private static final int MAX_MATCHES = 300;
    private static final int MAX_DEPTH = 25;

    private final Consumer<Path> onOpenFile;
    private final BiConsumer<Path, Path> onFileRenamed;
    private final Consumer<Path> onFileDeleted;
    private final java.util.function.Predicate<Path> isModified;
    /** Git working-tree status per file (absolute normalized path → status), for IntelliJ-style tree coloring;
     *  empty when Git is off / not a repo. Pushed by the Git coordinator on each status refresh. */
    private java.util.Map<Path, com.editora.git.GitFileStatus> gitStatus = java.util.Map.of();
    /** Directories that contain at least one Git-changed descendant (colored to hint at nested changes). */
    private java.util.Set<Path> gitChangedDirs = java.util.Set.of();
    /** Injected by MainController: snapshot a regular file into Local History just before it's deleted. */
    private Consumer<Path> onBeforeDelete = p -> {};
    /** Injected by MainController: show a transient status-bar message (drag-move / multi-delete feedback). */
    private Consumer<String> onStatus = m -> {};
    /** Notified after the filesystem watcher picks up an <em>external</em> change (not the app's own edit), so
     *  the window can refresh things anchored to the working tree — Git status / the Commit stripe, build-tool
     *  markers, open diffs — which otherwise only re-evaluate on focus-regain / tab switch (#529). */
    private Runnable onExternalChange = () -> {};
    /** Raw watcher events (path + kind), forwarded to LSP as didChangeWatchedFiles (#677); null = off. */
    private volatile java.util.function.Consumer<List<FsChange>> fsChangeSink;
    /** Injected by MainController: "New From Template…" on a folder, given the target directory. */
    private Consumer<Path> onNewFromTemplate;
    /** Injected by MainController: "New ▸ &lt;type&gt;" on a folder, given the target dir and the file kind. */
    private BiConsumer<Path, com.editora.template.NewFileType> onNewFile;

    private Consumer<Path> onNewMavenProject;

    /** Supplies the Maven submenu for a folder, or null when that folder holds no Maven project. */
    private java.util.function.Function<java.nio.file.Path, javafx.scene.control.Menu> mavenMenu;

    /** The Maven submenu for a folder, or null — built fresh per right-click, since a folder gains or
     *  loses its pom while the tree is open. */
    javafx.scene.control.Menu mavenMenuFor(java.nio.file.Path dir) {
        return mavenMenu == null ? null : mavenMenu.apply(dir);
    }

    public void setMavenMenu(java.util.function.Function<java.nio.file.Path, javafx.scene.control.Menu> supplier) {
        this.mavenMenu = supplier;
    }
    /** Injected by MainController: reveal a path in the OS file manager. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onReveal;
    /** Injected by MainController: open a terminal at a path's folder. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onOpenTerminal;
    /** Injected by MainController: per-file Local History + Git actions for the cell menu (files only). */
    private FileActions fileActions;
    /** Injected by MainController: file-level entry points into the line-anchored bookmark/note stores. */
    private MarkerActions markerActions;

    /**
     * File-scoped actions the Project tree's cell menu offers — Local History and Git. Injected by
     * {@code MainController} so the panel stays decoupled from the editor/history/git internals. The
     * {@code *Enabled} flags gate (disable) the corresponding menu entries to match the feature toggles.
     */
    public interface FileActions {
        boolean localHistoryEnabled();

        void showLocalHistory(Path file);

        /** True when Git actions can run (the feature is on AND the context is inside a repo). */
        boolean gitAvailable();

        void gitShowFileHistory(Path file);

        void gitCompareWithHead(Path file);

        /** Diff {@code file} against its version on a branch chosen from the repo. */
        void gitCompareWithBranch(Path file);

        /** Diff {@code file} against a commit chosen from its history. */
        void gitCompareWithRevision(Path file);

        /** Open {@code file} and show inline blame annotations (enables blame if it's off). */
        void gitAnnotate(Path file);

        void gitStage(Path file);

        void gitUnstage(Path file);

        /** Revert local changes to {@code file} (git checkout / clean an untracked file); confirms first. */
        void gitRevert(Path file);

        /** Add {@code file} (a directory gets a trailing {@code /}) to the repo-root {@code .gitignore}. */
        void gitAddToGitignore(Path file);
    }

    /** Bookmark/note state and actions shared by the classic tree and Canvas map. */
    public interface MarkerActions {
        boolean personalNotesEnabled();

        boolean hasBookmarks(Path file);

        boolean hasPersonalNotes(Path file);

        void addBookmark(Path file);

        void addPersonalNote(Path file);

        default void addBookmark(Path file, int line) {
            addBookmark(file);
        }

        default void addPersonalNote(Path file, NoteDraft draft) {
            addPersonalNote(file);
        }
    }

    /** In-scene single-line prompt (injected by MainController) used to rename a file/folder. */
    private OverlayInput.Prompt prompt;

    private final TextField filterField = new TextField();
    /** Filter row: the text field plus a trailing clear ("✕") button shown only while there's text. */
    private final HBox filterBar = new HBox();

    private final TreeView<Path> tree = new TreeView<>();
    private final ProjectMapView mapView;
    private boolean mapMode;
    private final StackPane placeholderPane;
    private final PauseTransition filterDebounce = new PauseTransition(Duration.millis(150));

    // Filter searches walk the filesystem off the FX thread; a generation guard drops stale results.
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "project-filter-search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong searchGen = new AtomicLong();

    // Filesystem watcher: auto-refresh the tree when files change on disk. Watches only the root + currently
    // -expanded directories (re-synced on expand/collapse and after each refresh) so it's cheap even on huge
    // trees; a daemon thread drains events and a debounce coalesces bursts into one refreshTree(). Local roots
    // only (a remote SFTP root has no local WatchService). On macOS the JDK uses a polling watcher, so external
    // changes may take a few seconds to show (the focus-regain refresh covers the immediate case).
    private java.nio.file.WatchService watchService;
    private Thread watchThread;
    private final java.util.Map<java.nio.file.WatchKey, Path> watchKeys = new java.util.HashMap<>();
    private final PauseTransition watchDebounce = new PauseTransition(Duration.millis(250));
    private volatile boolean disposed;
    // In-app rename/delete update the tree directly (instantly); the watcher then re-fires for that same
    // change (the OS delivers the event a beat later — ~1 s on macOS) and would run a redundant full
    // refreshTree() rebuild that reads as the edit "settling" a second later. Skip the watcher refresh for a
    // short window after an in-app filesystem change so the instant update is the only one the user sees.
    private volatile long lastLocalChangeMs;

    private static final long SELF_CHANGE_WINDOW_MS = 1500;

    private Path root;
    private boolean filtering;
    private boolean loading;
    /** Show hidden (dot) files/folders in the tree + filter search. Toggled from Settings. */
    private boolean showHidden;
    /** Skip {@code .gitignore}d files/folders in the filter search (default on; the shared Search setting). */
    private boolean respectGitignore = true;
    /** The paths being drag-moved (in-panel drag onto a folder); empty when no drag is in progress. */
    private List<Path> draggedPaths = List.of();

    public ProjectPanel(
            Consumer<Path> onOpenFile,
            BiConsumer<Path, Path> onFileRenamed,
            Consumer<Path> onFileDeleted,
            java.util.function.Predicate<Path> isModified) {
        this(onOpenFile, onFileRenamed, onFileDeleted, isModified, path -> false);
    }

    public ProjectPanel(
            Consumer<Path> onOpenFile,
            BiConsumer<Path, Path> onFileRenamed,
            Consumer<Path> onFileDeleted,
            java.util.function.Predicate<Path> isModified,
            java.util.function.Predicate<Path> isOpen) {
        this(onOpenFile, onFileRenamed, onFileDeleted, isModified, isOpen, path -> null);
    }

    public ProjectPanel(
            Consumer<Path> onOpenFile,
            BiConsumer<Path, Path> onFileRenamed,
            Consumer<Path> onFileDeleted,
            java.util.function.Predicate<Path> isModified,
            java.util.function.Predicate<Path> isOpen,
            java.util.function.Function<Path, ProjectMapPreview.Content> previewContent) {
        this.onOpenFile = onOpenFile;
        this.onFileRenamed = onFileRenamed;
        this.onFileDeleted = onFileDeleted;
        this.isModified = isModified;
        this.mapView = new ProjectMapView(onOpenFile, isOpen, isModified, previewContent);
        this.mapView.setOnExpandedChanged(this::syncWatches);
        this.mapView.setContextMenuFactory(entry -> contextMenuFor(
                new TreeItem<>(entry.path()), entry.directory(), entry.path().equals(root)));
        getStyleClass().add("project-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        buildFilter();

        tree.setShowRoot(true);
        tree.getStyleClass().add("project-tree");
        // Multi-select (Ctrl/Cmd- and Shift-click) so several files can be dragged/deleted at once, like a
        // file manager. Keyboard nav (onKey) uses clearAndSelect, so arrows still move a single selection.
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(t -> new PathCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        // Expanding/collapsing a folder changes which directories we need to watch.
        tree.addEventHandler(TreeItem.<Path>branchExpandedEvent(), e -> syncWatches());
        tree.addEventHandler(TreeItem.<Path>branchCollapsedEvent(), e -> syncWatches());
        // A coalesced filesystem-change event re-scans the tree (preserving expansion + selection) — unless
        // we just made the change ourselves (in-app rename/delete already updated the tree instantly).
        watchDebounce.setOnFinished(e -> {
            if (disposed) {
                return;
            }
            if (System.currentTimeMillis() - lastLocalChangeMs < SELF_CHANGE_WINDOW_MS) {
                syncWatches(); // our own edit already refreshed the tree; just keep the watch set current
                return;
            }
            refreshTree();
            syncWatches(); // a newly-created folder that's expanded would need watching
            onExternalChange.run(); // an external change → refresh Git/Commit stripe, build markers, diffs (#529)
        });

        Label placeholder = new Label(tr("project.placeholder"));
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholderPane = new StackPane(placeholder);
        placeholderPane.setAlignment(Pos.CENTER);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        setRoot(null);
    }

    private void buildFilter() {
        filterField.setPromptText(tr("project.filterPrompt"));
        filterField.getStyleClass().add("project-filter");
        filterField.textProperty().addListener((o, w, n) -> {
            if (!loading) {
                filterDebounce.playFromStart();
            }
        });
        filterDebounce.setOnFinished(e -> rebuildBody());
        // Focus lands on the filter field (focusFirstItem); Down moves into the results and Enter opens the
        // selected (or first) match, so the whole flow is keyboard-only (shared with Structure/Bookmarks/Notes).
        FilterFieldNav.install(filterField, tree, this::openSelected);
        // The same search field fronts both modes. Intercept navigation before FilterFieldNav's tree handler
        // when Map is active, then hand focus/activation to the Canvas surface.
        filterField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!mapMode) {
                return;
            }
            switch (e.getCode()) {
                case DOWN -> {
                    mapView.focusMap();
                    e.consume();
                }
                case ENTER -> {
                    mapView.openSelection();
                    e.consume();
                }
                case N -> {
                    if (e.isControlDown()) {
                        mapView.moveSelection(1);
                        e.consume();
                    }
                }
                case P -> {
                    if (e.isControlDown()) {
                        mapView.moveSelection(-1);
                        e.consume();
                    }
                }
                default -> {}
            }
        });

        // Trailing clear button — visible only while the filter has text; clicking it empties the filter
        // (which restores the lazy tree via the debounce) and returns focus to the field.
        Button clear = new Button("✕");
        clear.getStyleClass().add("project-filter-clear");
        clear.setFocusTraversable(false);
        clear.setTooltip(new Tooltip(tr("project.filterClear")));
        clear.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clear.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        clear.managedProperty().bind(clear.visibleProperty()); // reclaim its width when hidden

        ToggleButton treeMode = new ToggleButton(tr("project.view.tree"));
        ToggleButton mapModeButton = new ToggleButton(tr("project.view.map"));
        treeMode.getStyleClass().add("project-view-toggle");
        mapModeButton.getStyleClass().add("project-view-toggle");
        treeMode.setTooltip(new Tooltip(tr("project.view.tree.tooltip")));
        mapModeButton.setTooltip(new Tooltip(tr("project.view.map.tooltip")));
        treeMode.setFocusTraversable(false);
        mapModeButton.setFocusTraversable(false);
        ToggleGroup modes = new ToggleGroup();
        treeMode.setToggleGroup(modes);
        mapModeButton.setToggleGroup(modes);
        treeMode.setSelected(true);
        // A selected mode cannot be cleared: clicking the already-selected toggle leaves it selected.
        modes.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                modes.selectToggle(old);
                return;
            }
            mapMode = selected == mapModeButton;
            rebuildBody();
        });
        HBox viewModes = new HBox(treeMode, mapModeButton);
        viewModes.getStyleClass().add("project-view-modes");

        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER);
        filterBar.getChildren().setAll(filterField, clear, viewModes);
    }

    /** Re-renders the visible tree cells so each file's modified marker/color reflects current state. */
    public void refreshModified() {
        tree.refresh();
        mapView.refreshStates();
    }

    /** Refreshes the Map's open-tab markers after the editor's tab membership changes. */
    public void refreshOpenFiles() {
        mapView.refreshStates();
    }

    /** Re-renders bookmark and Personal Note indicators in both Project views. */
    public void refreshMarkers() {
        tree.refresh();
        mapView.refreshStates();
    }

    /**
     * Sets the per-file Git working-tree status (absolute normalized path → {@link com.editora.git.GitFileStatus},
     * from {@code GitFileStatus.byPath}) used to color the tree IntelliJ-style, and re-renders the cells. Also
     * derives the set of directories that contain a changed descendant (so folders hint at nested changes).
     * Pushed by the Git coordinator on each status refresh; an empty map clears the coloring (Git off / clean).
     */
    public void setGitStatus(java.util.Map<Path, com.editora.git.GitFileStatus> byPath) {
        gitStatus = byPath == null ? java.util.Map.of() : byPath;
        java.util.Set<Path> dirs = new java.util.HashSet<>();
        for (Path file : gitStatus.keySet()) {
            for (Path dir = file.getParent();
                    dir != null && !dir.equals(root) && root != null && dir.startsWith(root);
                    dir = dir.getParent()) {
                if (!dirs.add(dir)) {
                    break; // this ancestor (and everything above it) is already recorded
                }
            }
        }
        gitChangedDirs = dirs;
        tree.refresh();
        mapView.setGitStatus(gitStatus);
    }

    /**
     * Re-scans the tree against the current filesystem so files/folders added or removed outside Editora
     * show up. Preserves the expanded folders and the selection. No-op while filtering or with no project.
     * Cheap: only re-lists directories that are currently expanded. Called on window focus-regain.
     */
    public void refreshTree() {
        if (mapMode) {
            mapView.refresh();
            return;
        }
        if (root == null || filtering || !(tree.getRoot() instanceof PathItem rootItem)) {
            return;
        }
        java.util.Set<Path> expanded = new java.util.HashSet<>();
        collectExpanded(rootItem, expanded);
        TreeItem<Path> selected = tree.getSelectionModel().getSelectedItem();
        Path selectedPath = selected == null ? null : selected.getValue();

        reExpand(rootItem, expanded);

        if (selectedPath != null) {
            TreeItem<Path> found = findVisible(rootItem, selectedPath);
            if (found != null) {
                tree.getSelectionModel().select(found);
            }
        }
    }

    /** Collects the paths of every currently-expanded directory (children are already loaded). */
    private static void collectExpanded(TreeItem<Path> item, java.util.Set<Path> out) {
        if (!item.isExpanded()) {
            return;
        }
        out.add(item.getValue());
        for (TreeItem<Path> child : item.getChildren()) {
            collectExpanded(child, out);
        }
    }

    /** Re-lists {@code item} from disk and re-expands the descendants that were previously expanded. */
    private static void reExpand(PathItem item, java.util.Set<Path> expanded) {
        item.reload(); // re-read this directory's children from disk
        item.setExpanded(true); // only ever called for items that were expanded
        for (TreeItem<Path> child : item.getChildren()) {
            if (child instanceof PathItem dir && expanded.contains(dir.getValue())) {
                reExpand(dir, expanded);
            }
        }
    }

    /** Finds a (visible) tree item for {@code target} among the expanded items, or null if gone. */
    private static TreeItem<Path> findVisible(TreeItem<Path> item, Path target) {
        if (target.equals(item.getValue())) {
            return item;
        }
        if (!item.isExpanded()) {
            return null;
        }
        for (TreeItem<Path> child : item.getChildren()) {
            TreeItem<Path> found = findVisible(child, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Points the tree at {@code root} (a project folder), or shows the placeholder when {@code null}. */
    public void setRoot(Path root) {
        this.root = root;
        mapView.setRoot(root);
        mapView.setGitStatus(gitStatus); // recompute changed-directory ancestry against the new root
        loading = true;
        filterField.clear();
        loading = false;
        ensureWatchOrStop(); // start a watcher for a local root (or stop it for remote/none)
        rebuildBody(); // ends with syncWatches() — registers the new root's dirs, cancels the old root's
    }

    /** The folder the tree is currently rooted at, or {@code null} when showing the placeholder. */
    public Path getRoot() {
        return root;
    }

    /** Show/hide hidden (dot) files and folders; rebuilds the tree when the value changes. */
    public void setShowHidden(boolean showHidden) {
        if (this.showHidden == showHidden) {
            return;
        }
        this.showHidden = showHidden;
        if (root != null) {
            rebuildBody(); // recreate the tree (PathItems capture the flag) with the new visibility
        }
    }

    /**
     * Whether the filter search skips {@code .gitignore}d files/folders (the shared {@code searchRespectGitignore}
     * Setting, default on). Only affects the filtered results — the full lazy tree still shows every file.
     */
    public void setRespectGitignore(boolean respectGitignore) {
        if (this.respectGitignore == respectGitignore) {
            return;
        }
        this.respectGitignore = respectGitignore;
        if (root != null && filtering) {
            rebuildBody(); // re-run the current filter with the new exclusion
        }
    }

    /** Rebuilds the body: placeholder (no project), filtered flat results, or the lazy tree. */
    private void rebuildBody() {
        long gen = searchGen.incrementAndGet(); // invalidate any in-flight search
        if (root == null || !Files.isDirectory(root)) {
            getChildren().setAll(placeholderPane);
            return;
        }
        String q = filterField.getText().trim();
        if (mapMode) {
            filtering = false;
            mapView.setQuery(q);
            getChildren().setAll(filterBar, mapView);
            VBox.setVgrow(mapView, Priority.ALWAYS);
            syncWatches();
            return;
        }
        mapView.hidePreview();
        if (q.isEmpty()) {
            filtering = false;
            PathItem rootItem = new PathItem(root, showHidden);
            rootItem.setExpanded(true);
            tree.setRoot(rootItem);
        } else {
            filtering = true;
            // Walk off the FX thread (up to MAX_VISIT entries); apply results back under the gen guard.
            Path searchRoot = root;
            boolean includeHidden = showHidden;
            boolean useGitignore = respectGitignore && com.editora.vfs.Vfs.isLocal(root);
            TreeItem<Path> pending = new TreeItem<>(root);
            pending.setExpanded(true);
            tree.setRoot(pending);
            searchExecutor.submit(() -> {
                // Load the root .gitignore off the FX thread (reads one file); NONE = no exclusion.
                com.editora.search.GitignoreFilter gitignore = useGitignore
                        ? com.editora.search.GitignoreFilter.load(searchRoot)
                        : com.editora.search.GitignoreFilter.NONE;
                List<Path> matches = search(searchRoot, q, includeHidden, gitignore);
                Platform.runLater(() -> {
                    if (gen != searchGen.get()) {
                        return; // a newer query (or a tree switch) superseded this one
                    }
                    TreeItem<Path> rootItem = new TreeItem<>(searchRoot);
                    rootItem.setExpanded(true);
                    for (Path match : matches) {
                        rootItem.getChildren().add(new TreeItem<>(match));
                    }
                    tree.setRoot(rootItem);
                });
            });
        }
        getChildren().setAll(filterBar, tree);
        syncWatches(); // register the (new) tree's directories with the filesystem watcher
    }

    // --- filesystem watcher (auto-refresh on disk changes) ---------------------------------------

    /** Starts the watcher for a local root, or cancels all watches for a remote/absent root. */
    private void ensureWatchOrStop() {
        if (root != null && com.editora.vfs.Vfs.isLocal(root) && Files.isDirectory(root)) {
            ensureWatchService();
        } else {
            cancelAllWatches();
        }
    }

    /** Lazily creates the {@link java.nio.file.WatchService} + its daemon drain thread (once per panel). */
    private void ensureWatchService() {
        if (watchService != null || disposed) {
            return;
        }
        try {
            watchService = java.nio.file.FileSystems.getDefault().newWatchService();
        } catch (IOException | RuntimeException e) {
            watchService = null; // watching unsupported here; the focus-regain refresh still applies
            return;
        }
        watchThread = new Thread(this::watchLoop, "project-fs-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /**
     * Registers the root + currently-expanded directories with the watcher and cancels watches for
     * directories no longer present/expanded. Runs on the FX thread (it reads the tree). Cheap: the set is
     * just the visible folders.
     */
    private void syncWatches() {
        if (watchService == null) {
            return;
        }
        java.util.Set<Path> desired = new java.util.HashSet<>();
        if (root != null && Files.isDirectory(root)) {
            desired.add(root);
        }
        if (!mapMode && tree.getRoot() instanceof PathItem rootItem) {
            collectExpanded(rootItem, desired); // expanded directories (root included)
        }
        if (mapMode) {
            desired.addAll(mapView.expandedDirectories());
        }
        watchKeys.entrySet().removeIf(entry -> {
            if (!desired.contains(entry.getValue())) {
                entry.getKey().cancel();
                return true;
            }
            return false;
        });
        java.util.Set<Path> already = new java.util.HashSet<>(watchKeys.values());
        for (Path dir : desired) {
            if (already.contains(dir) || !Files.isDirectory(dir)) {
                continue;
            }
            try {
                java.nio.file.WatchKey key = dir.register(
                        watchService,
                        java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_DELETE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                watchKeys.put(key, dir);
            } catch (IOException | RuntimeException ignored) {
                // a dir we can't watch (perms, vanished) is simply not watched
            }
        }
    }

    private void cancelAllWatches() {
        for (java.nio.file.WatchKey key : watchKeys.keySet()) {
            key.cancel();
        }
        watchKeys.clear();
    }

    /** Daemon loop: drains watch events and schedules a single coalesced refresh on the FX thread. */
    private void watchLoop() {
        // Bind the service once: dispose() nulls the field, so re-reading it each iteration can NPE on this
        // daemon thread if dispose() lands between the !disposed test and the take(). Closing it below still
        // unblocks the take() with a ClosedWatchServiceException, which is the intended exit.
        java.nio.file.WatchService ws = watchService;
        while (ws != null && !disposed) {
            java.nio.file.WatchKey key;
            try {
                key = ws.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return; // disposed
            }
            try {
                // Inspect the events so a batch that is ONLY Editora's own throwaway temp files (the typst render
                // input, created+deleted every render) doesn't spuriously rebuild the tree (#465). Otherwise we
                // re-list from disk (individual events aren't applied) — an OVERFLOW or any real file forces it.
                List<java.nio.file.WatchEvent<?>> events = key.pollEvents();
                Path watchedDir = key.watchable() instanceof Path p ? p : null;
                key.reset();
                boolean overflow = false;
                List<String> names = new java.util.ArrayList<>();
                List<FsChange> changes = new java.util.ArrayList<>(); // typed, for the LSP sink (#677)
                for (java.nio.file.WatchEvent<?> ev : events) {
                    if (ev.kind() == java.nio.file.StandardWatchEventKinds.OVERFLOW) {
                        overflow = true;
                        continue;
                    }
                    Object ctx = ev.context();
                    String name = ctx instanceof Path p ? p.getFileName().toString() : "";
                    names.add(name);
                    if (watchedDir != null && ctx instanceof Path child && !isEditoraTempName(name)) {
                        FsKind kind = ev.kind() == java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
                                ? FsKind.CREATED
                                : ev.kind() == java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
                                        ? FsKind.DELETED
                                        : FsKind.CHANGED;
                        changes.add(new FsChange(watchedDir.resolve(child), kind));
                    }
                }
                var sink = fsChangeSink;
                if (sink != null && !changes.isEmpty()) {
                    List<FsChange> batch = List.copyOf(changes);
                    Platform.runLater(() -> {
                        if (!disposed) {
                            sink.accept(batch);
                        }
                    });
                }
                if (!watchEventsWarrantRefresh(names, overflow)) {
                    continue; // only Editora's own temp files changed — no tree rebuild
                }
                Platform.runLater(() -> {
                    if (!disposed) {
                        watchDebounce.playFromStart();
                    }
                });
            } catch (Throwable t) {
                // A transient failure processing one batch must NOT kill the watcher thread — that would
                // silently stop live tree refresh for the rest of the session. Log and keep watching (the
                // focus-regain refreshTree() still covers any gap).
                LOG.log(java.util.logging.Level.WARNING, "project filesystem watch loop error", t);
            }
        }
    }

    /** Whether a batch of filesystem watch events warrants a tree refresh — {@code false} only when every
     *  changed name is one of Editora's own throwaway temp files (so a typst render, which creates+deletes a
     *  {@code .editora-typst-*.typ} input beside the file, doesn't rebuild the tree). An OVERFLOW or an empty
     *  batch forces a refresh (we can't tell what changed). Pure; unit-tested (#465). */
    static boolean watchEventsWarrantRefresh(List<String> names, boolean overflow) {
        if (overflow || names.isEmpty()) {
            return true;
        }
        return names.stream().anyMatch(n -> !isEditoraTempName(n));
    }

    /** Whether {@code name} is an Editora-internal throwaway file the tree watcher should ignore. */
    static boolean isEditoraTempName(String name) {
        return name != null && name.startsWith(".editora-typst-");
    }

    /** Stops the watcher + its thread; call on window close so the daemon thread + native handles are freed. */
    public void dispose() {
        disposed = true;
        watchDebounce.stop();
        watchKeys.clear();
        java.nio.file.WatchService ws = watchService;
        watchService = null;
        if (ws != null) {
            try {
                ws.close(); // unblocks watchLoop's take()
            } catch (IOException ignored) {
                // closing best-effort
            }
        }
        searchExecutor.shutdownNow();
        mapView.dispose();
    }

    /**
     * Bounded, <em>breadth-first</em> project-wide filename search: dot-dirs skipped (unless showing
     * hidden), capped on entries visited and matches. BFS is deliberate — a depth-first walk descends fully
     * into the first subtree it meets, so under a large root (e.g. a home dir with huge {@code .cache}/
     * {@code .m2}/{@code node_modules} trees) the {@link #MAX_VISIT} budget is exhausted deep inside one of
     * them before a shallow, top-level file (like {@code .profile}) is ever reached. BFS visits every
     * shallower entry before descending, so a top-level match is never starved by a deep sibling subtree.
     * Package-visible for tests.
     */
    static List<Path> search(Path root, String query, boolean includeHidden) {
        return search(root, query, includeHidden, com.editora.search.GitignoreFilter.NONE);
    }

    /**
     * As above, additionally skipping paths matched by {@code gitignore} (the repo-root {@code .gitignore};
     * pass {@link com.editora.search.GitignoreFilter#NONE} to disable). An ignored directory is not descended
     * into and an ignored file is not matched, so {@code target/}, {@code node_modules/}, {@code *.log}, … drop
     * out of the filter results. Package-visible for tests.
     */
    static List<Path> search(
            Path root, String query, boolean includeHidden, com.editora.search.GitignoreFilter gitignore) {
        String q = query.toLowerCase(Locale.ROOT);
        boolean useGitignore = gitignore != null && !gitignore.isEmpty();
        List<Path> matches = new ArrayList<>();
        record Dir(Path path, int depth) {}
        java.util.Deque<Dir> queue = new java.util.ArrayDeque<>();
        queue.add(new Dir(root, 0));
        int visited = 0;
        while (!queue.isEmpty() && visited <= MAX_VISIT && matches.size() < MAX_MATCHES) {
            Dir current = queue.poll();
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(current.path())) {
                for (Path p : entries) {
                    if (++visited > MAX_VISIT || matches.size() >= MAX_MATCHES) {
                        break;
                    }
                    String name = p.getFileName().toString();
                    boolean hidden = name.startsWith(".");
                    boolean dir = Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (useGitignore
                            && gitignore.ignored(
                                    root.relativize(p).toString().replace(java.io.File.separatorChar, '/'), dir)) {
                        continue; // .gitignore'd (e.g. target/, node_modules/, *.log) — skip file + subtree
                    }
                    if (dir) {
                        if (current.depth() + 1 < MAX_DEPTH && (includeHidden || !hidden)) {
                            queue.add(new Dir(p, current.depth() + 1)); // descend later — shallower first
                        }
                    } else if ((includeHidden || !hidden) && FuzzyMatch.of(name, q) != null) {
                        matches.add(p);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Unreadable directory — skip it, keep searching the rest (best effort).
            }
        }
        // Relevance, not the alphabet. Alphabetical order is arbitrary with respect to what was typed, so
        // the file actually being looked for sat wherever its initial happened to fall. Scoring the whole
        // relative path (rather than just the name) is what lets a query name a directory as well as a
        // file, and ofPath keeps a basename hit above a file that merely lives in a folder of that name.
        record Scored(Path path, int score, String name) {}
        List<Scored> scored = new ArrayList<>(matches.size());
        for (Path p : matches) {
            Path rel = root.relativize(p);
            FuzzyMatch.Match m = FuzzyMatch.ofPath(rel.toString().replace(java.io.File.separatorChar, '/'), q);
            // The walk matched on the file name, so a path-level score can be absent (a name-only match
            // whose characters don't line up across the whole path); such a row keeps a floor score and
            // sorts alphabetically among its peers rather than dropping out of a list it belongs in.
            scored.add(new Scored(
                    p,
                    m == null ? Integer.MIN_VALUE : m.score(),
                    p.getFileName().toString()));
        }
        scored.sort(java.util.Comparator.comparingInt((Scored s) -> s.score())
                .reversed()
                .thenComparing(s -> s.name(), String.CASE_INSENSITIVE_ORDER));
        return scored.stream().map(Scored::path).toList();
    }

    // --- keyboard navigation (mirrors StructurePanel) ---

    private void onKey(KeyEvent e) {
        if (mapMode) {
            return; // the Canvas surface owns its arrows/C-n/C-p/Enter in Map mode
        }
        switch (e.getCode()) {
            case ENTER -> {
                openSelected();
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        move(1);
                        e.consume();
                    }
                    case P -> {
                        move(-1);
                        e.consume();
                    }
                    case F -> {
                        expandOrDescend();
                        e.consume();
                    }
                    case B -> {
                        collapseOrAscend();
                        e.consume();
                    }
                    case M -> {
                        openSelected();
                        e.consume();
                    }
                    default -> {}
                }
            }
        }
    }

    @Override
    public void focusFirstItem() {
        // Land on the filter field so the user can type to filter immediately (IDE convention). Down / Enter
        // then move into / open the results (see the filter field's key handler in buildFilter).
        filterField.requestFocus();
    }

    private void move(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int idx = tree.getSelectionModel().getSelectedIndex();
        int next = idx < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(idx + delta, rows);
        tree.getSelectionModel().clearAndSelect(next); // replace (not extend) the multi-selection on arrow nav
        tree.scrollTo(next);
    }

    private void expandOrDescend() {
        TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            move(-1);
            return;
        }
        if (!item.isLeaf() && item.isExpanded()) {
            item.setExpanded(false);
        } else if (item.getParent() != null && item.getParent() != tree.getRoot()) {
            tree.getSelectionModel().clearSelection();
            tree.getSelectionModel().select(item.getParent());
            tree.scrollTo(tree.getSelectionModel().getSelectedIndex());
        } else {
            move(-1);
        }
    }

    /** Opens the selected file; for a directory, toggles its expansion. */
    private void openSelected() {
        TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) {
            return;
        }
        Path path = item.getValue();
        if (Files.isDirectory(path)) {
            item.setExpanded(!item.isExpanded());
        } else {
            onOpenFile.accept(path);
        }
    }

    // --- rename / delete ---

    /** Injects the in-scene prompt used to rename a file/folder (so the panel needs no overlay host). */
    public void setPrompt(OverlayInput.Prompt prompt) {
        this.prompt = prompt;
    }

    /** Injects the "New From Template…" handler (given the target folder) shown on a folder's menu. */
    public void setOnNewMavenProject(Consumer<Path> onNewMavenProject) {
        this.onNewMavenProject = onNewMavenProject;
    }

    public void setOnNewFromTemplate(Consumer<Path> onNewFromTemplate) {
        this.onNewFromTemplate = onNewFromTemplate;
    }

    /** Injects a hook called with a regular file just before it's deleted (to snapshot it into Local History). */
    public void setOnBeforeDelete(Consumer<Path> onBeforeDelete) {
        this.onBeforeDelete = onBeforeDelete == null ? p -> {} : onBeforeDelete;
    }

    /** Injects the status-message sink used for drag-move / multi-delete feedback. */
    public void setOnStatus(Consumer<String> onStatus) {
        this.onStatus = onStatus == null ? m -> {} : onStatus;
    }

    /** Restores and persists the Project Map's directional layout in workspace state. */
    public void setRememberedMapFlow(String flow, Consumer<String> onChanged) {
        mapView.setRememberedFlow(flow, value -> {
            if (onChanged != null) {
                onChanged.accept(value.name());
            }
        });
    }

    /** Injects the window-owned print and PDF handlers for a full Project Map snapshot. */
    public void setMapOutputActions(Consumer<javafx.scene.image.Image> print, Consumer<javafx.scene.image.Image> pdf) {
        mapView.setOutputActions(print, pdf);
    }

    /** One filesystem watcher event: what happened to which path (#677). */
    public record FsChange(Path path, FsKind kind) {}

    /** The watcher event kinds forwarded to the sink. */
    public enum FsKind {
        CREATED,
        CHANGED,
        DELETED
    }

    /** Injects the raw watcher-event sink (invoked on the FX thread with each drained batch) — how external
     *  file changes reach the language servers (#677). {@code null} disables forwarding. */
    public void setFsChangeSink(java.util.function.Consumer<List<FsChange>> sink) {
        this.fsChangeSink = sink;
    }

    /** Injects the external-change hook (see {@link #onExternalChange}); {@code null} restores the no-op. */
    public void setOnExternalChange(Runnable onExternalChange) {
        this.onExternalChange = onExternalChange == null ? () -> {} : onExternalChange;
    }

    /** Injects the "Reveal in File Manager" handler ({@code (path, isDirectory)}) for the cell menu. */
    public void setOnReveal(BiConsumer<Path, Boolean> onReveal) {
        this.onReveal = onReveal;
    }

    /** Injects the "Open Terminal Here" handler ({@code (path, isDirectory)}) for the cell menu. */
    public void setOnOpenTerminal(BiConsumer<Path, Boolean> onOpenTerminal) {
        this.onOpenTerminal = onOpenTerminal;
    }

    /** Injects the per-file Local History + Git actions shown on a file cell's menu. */
    public void setFileActions(FileActions fileActions) {
        this.fileActions = fileActions;
    }

    /** Injects bookmark/note actions and state used by both Project renderers. */
    public void setMarkerActions(MarkerActions markerActions) {
        this.markerActions = markerActions;
        mapView.setPreviewMarkerActions(
                markerActions == null
                        ? null
                        : new ProjectMapPreview.MarkerActions() {
                            @Override
                            public boolean personalNotesEnabled() {
                                return markerActions.personalNotesEnabled();
                            }

                            @Override
                            public void addBookmark(Path file, int line) {
                                markerActions.addBookmark(file, line);
                            }

                            @Override
                            public void addPersonalNote(Path file, NoteDraft draft) {
                                markerActions.addPersonalNote(file, draft);
                            }
                        });
        mapView.setMarkerStates(
                path -> this.markerActions != null && this.markerActions.hasBookmarks(path),
                path -> this.markerActions != null
                        && this.markerActions.personalNotesEnabled()
                        && this.markerActions.hasPersonalNotes(path));
        refreshMarkers();
    }

    /**
     * Injects the "New ▸ &lt;type&gt;" handler ({@code (targetFolder, type)}) — creating the file itself
     * needs the window (the name prompt, opening the result, the status line), so the panel only offers
     * the menu.
     */
    public void setOnNewFile(BiConsumer<Path, com.editora.template.NewFileType> onNewFile) {
        this.onNewFile = onNewFile;
    }

    /**
     * The folder menu's "New ▸" submenu: the generic File… and Folder… entries, the two everyday file
     * types, one submenu per {@link com.editora.template.NewFileCatalog} category, then the template
     * scaffolds.
     *
     * <p>Built from the catalog rather than written out, so a new file type appears here, in the palette
     * picker and in the tests from one table row. Each entry carries the same {@link FileIcons} glyph the
     * file will show in the tree once it exists, which is what makes a long list scannable.
     */
    private Menu newMenu(TreeItem<Path> dirItem) {
        Menu newMenu = new Menu(tr("newfile.menu.new"));
        newMenu.setGraphic(Icons.newFile());
        Path dir = dirItem.getValue();

        if (onNewFile != null) {
            newMenu.getItems().add(typeItem(com.editora.template.NewFileCatalog.PLAIN, dirItem));
        }
        MenuItem folder = new MenuItem(tr("project.menu.newFolder"));
        folder.setGraphic(Icons.newFolder());
        folder.setOnAction(e -> newFolder(dirItem));
        newMenu.getItems().add(folder);

        if (onNewFile != null) {
            newMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            for (com.editora.template.NewFileType type : com.editora.template.NewFileCatalog.topLevel()) {
                newMenu.getItems().add(typeItem(type, dirItem));
            }
            for (com.editora.template.NewFileCatalog.Category category :
                    com.editora.template.NewFileCatalog.categories()) {
                Menu submenu = new Menu(tr(category.labelKey()));
                // The category's own glyph is its first member's — "Java" gets the Java icon, and so on.
                submenu.setGraphic(FileIcons.forFileName(category.types().get(0).suggestedFileName()));
                for (com.editora.template.NewFileType type : category.types()) {
                    submenu.getItems().add(typeItem(type, dirItem));
                }
                newMenu.getItems().add(submenu);
            }
        }
        if (onNewFromTemplate != null || onNewMavenProject != null) {
            newMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        if (onNewFromTemplate != null) {
            MenuItem fromTemplate = new MenuItem(tr("project.menu.newFromTemplate"));
            fromTemplate.setGraphic(Icons.template());
            fromTemplate.setOnAction(e -> onNewFromTemplate.accept(dir));
            newMenu.getItems().add(fromTemplate);
        }
        if (onNewMavenProject != null) {
            MenuItem maven = new MenuItem(tr("project.menu.newMavenProject"));
            maven.setGraphic(Icons.newFolder());
            maven.setOnAction(e -> onNewMavenProject.accept(dir));
            newMenu.getItems().add(maven);
        }
        return newMenu;
    }

    /**
     * A file type's display label: a proper name ("Python", "Dockerfile") verbatim, an ordinary word
     * ("Class", "Text File") from the i18n catalog. Shared with the palette picker so the menu and the
     * picker can never disagree about what a type is called.
     */
    static String labelFor(com.editora.template.NewFileType type) {
        return type.hasLiteralLabel() ? type.label() : tr(type.labelKey());
    }

    /** One "New ▸" entry: the type's label and the glyph the file will carry once it exists. */
    private MenuItem typeItem(com.editora.template.NewFileType type, TreeItem<Path> dirItem) {
        MenuItem item = new MenuItem(labelFor(type));
        item.setGraphic(
                type.extension().isEmpty() && type.suggestedFileName().isEmpty()
                        ? Icons.newFile()
                        : FileIcons.forFileName(type.suggestedFileName()));
        item.setOnAction(e -> {
            dirItem.setExpanded(true); // reveal where the new file will land
            onNewFile.accept(dirItem.getValue(), type);
        });
        return item;
    }

    /** Prompts for a name and creates a new subfolder inside {@code dirItem}'s directory. */
    private void newFolder(TreeItem<Path> dirItem) {
        if (prompt == null) {
            return;
        }
        Path dir = dirItem.getValue();
        prompt.show(tr("project.newFolderTitle"), tr("project.newFolderContent"), "", input -> {
            String name = input.trim();
            if (name.isEmpty()) {
                return;
            }
            Path target = dir.resolve(name);
            if (Files.exists(target)) {
                showError(tr("project.newFolderExists", name));
                return;
            }
            try {
                Files.createDirectories(target);
            } catch (IOException ex) {
                showError(tr("project.newFolderError", name, ex.getMessage()));
                return;
            }
            markLocalChange(); // tree re-listed below; suppress the watcher's redundant refresh
            dirItem.setExpanded(true); // reveal the new folder under its parent
            refreshAfterChange();
        });
    }

    private void renameItem(TreeItem<Path> item) {
        if (prompt == null) {
            return;
        }
        Path path = item.getValue();
        prompt.show(
                tr("project.renameTitle"),
                tr("project.renameContent"),
                path.getFileName().toString(),
                input -> {
                    String name = input.trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    Path target = path.resolveSibling(name);
                    if (target.equals(path) || Files.exists(target)) {
                        return;
                    }
                    try {
                        Files.move(path, target);
                    } catch (IOException ex) {
                        showError(tr("project.renameError", path.getFileName(), ex.getMessage()));
                        return;
                    }
                    markLocalChange(); // tree re-listed below; suppress the watcher's redundant refresh
                    refreshAfterChange();
                    onFileRenamed.accept(path, target);
                });
    }

    private void deleteItem(TreeItem<Path> item) {
        Path path = item.getValue();
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("project.deleteFileBody", path.getFileName()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle(tr("project.deleteFileTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (Files.isRegularFile(path)) {
            onBeforeDelete.accept(path); // snapshot into Local History so the file can be recovered
        }
        try {
            Files.delete(path);
        } catch (IOException ex) {
            showError(tr("project.deleteError", path.getFileName(), ex.getMessage()));
            return;
        }
        markLocalChange(); // suppress the watcher's redundant ~1s-later refresh for our own delete
        refreshAfterChange();
        onFileDeleted.accept(path);
    }

    // --- drag-to-move + multi-delete (mini file-manager) ---

    /**
     * Whether moving {@code source} into directory {@code targetDir} is valid and not a no-op: the target
     * can't be the source's current parent (already there), and can't be the source itself or a descendant of
     * it (a folder can't move into its own subtree). Pure — package-visible for tests.
     */
    static boolean canMoveInto(Path source, Path targetDir) {
        if (source == null || targetDir == null) {
            return false;
        }
        Path s = source.toAbsolutePath().normalize();
        Path t = targetDir.toAbsolutePath().normalize();
        if (t.equals(s.getParent())) {
            return false; // no-op: already in this folder
        }
        return !t.startsWith(s); // t == s (onto itself) or a descendant of s → invalid
    }

    /** True if at least one of {@code sources} can meaningfully move into {@code targetDir}. */
    static boolean canDropInto(List<Path> sources, Path targetDir) {
        return sources != null && sources.stream().anyMatch(s -> canMoveInto(s, targetDir));
    }

    /**
     * Drops any source that already lives under another source, so a selection holding both a folder and
     * something inside it moves once (the folder carries its contents along). Without this the parent moves
     * first — {@code TreeView} hands the selection back in row order — and the child's own move then fails
     * with a {@code NoSuchFileException} on a path that no longer exists. Pure — package-visible for tests.
     */
    static List<Path> pruneNestedSources(List<Path> sources) {
        if (sources == null || sources.size() < 2) {
            return sources == null ? List.of() : List.copyOf(sources);
        }
        List<Path> norm = sources.stream()
                .filter(java.util.Objects::nonNull)
                .map(p -> p.toAbsolutePath().normalize())
                .toList();
        List<Path> out = new ArrayList<>();
        for (Path p : norm) {
            boolean nested = norm.stream().anyMatch(other -> !other.equals(p) && p.startsWith(other));
            if (!nested) {
                out.add(p);
            }
        }
        return out;
    }

    /** Moves each of {@code sources} into {@code targetDir} (drag-and-drop). Skips no-ops, name conflicts,
     *  and invalid moves (into itself); reports how many moved. Notifies {@code onFileRenamed} per moved path
     *  so open buffers (a file, or files under a moved folder) follow. */
    private void moveInto(List<Path> dropped, Path targetDir) {
        List<Path> sources = pruneNestedSources(dropped);
        if (sources.isEmpty() || targetDir == null || !Files.isDirectory(targetDir)) {
            return;
        }
        int moved = 0;
        int skipped = 0;
        for (Path src : sources) {
            if (!canMoveInto(src, targetDir)) {
                skipped++;
                continue;
            }
            Path dest = targetDir.resolve(src.getFileName());
            if (Files.exists(dest)) {
                skipped++; // a file/folder of that name is already there — don't clobber
                continue;
            }
            try {
                Files.move(src, dest);
            } catch (IOException ex) {
                showError(tr("project.moveError", src.getFileName(), ex.getMessage()));
                skipped++;
                continue;
            }
            onFileRenamed.accept(src, dest); // update the open buffer(s) for a moved file / under a moved dir
            moved++;
        }
        if (moved > 0) {
            markLocalChange();
            refreshAfterChange();
        }
        onStatus.accept(
                skipped == 0
                        ? tr("project.moved", moved, targetDir.getFileName())
                        : tr("project.movedSome", moved, skipped));
    }

    /** The files to act on for a cell action: the whole multi-selection when {@code clicked} is part of it,
     *  else just {@code clicked}. Excludes the root. */
    private List<TreeItem<Path>> actionTargets(TreeItem<Path> clicked) {
        List<TreeItem<Path>> sel = new ArrayList<>(tree.getSelectionModel().getSelectedItems());
        sel.removeIf(i -> i == null || i.getValue() == null || i.getValue().equals(root));
        if (clicked != null && sel.contains(clicked) && sel.size() > 1) {
            return sel;
        }
        return clicked == null ? List.of() : List.of(clicked);
    }

    /** Deletes the selected files (or just {@code clicked}); confirms once. Folders/root are skipped (delete
     *  is files-only, like the single-file menu). */
    private void deleteSelected(TreeItem<Path> clicked) {
        List<Path> files = new ArrayList<>();
        for (TreeItem<Path> it : actionTargets(clicked)) {
            if (Files.isRegularFile(it.getValue())) {
                files.add(it.getValue());
            }
        }
        if (files.isEmpty()) {
            return;
        }
        if (files.size() == 1 && clicked != null && Files.isRegularFile(clicked.getValue())) {
            deleteItem(clicked); // single: reuse the existing per-file confirm flow
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("project.deleteMultiBody", files.size()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle(tr("project.deleteFileTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        int deleted = 0;
        for (Path p : files) {
            onBeforeDelete.accept(p); // snapshot into Local History first
            try {
                Files.delete(p);
            } catch (IOException ex) {
                showError(tr("project.deleteError", p.getFileName(), ex.getMessage()));
                continue;
            }
            onFileDeleted.accept(p);
            deleted++;
        }
        if (deleted > 0) {
            markLocalChange();
            refreshAfterChange();
        }
    }

    /** Records an in-app filesystem change so the watcher skips its redundant refresh for a short window. */
    private void markLocalChange() {
        lastLocalChangeMs = System.currentTimeMillis();
    }

    /** Shows a modal error dialog when a filesystem operation (rename/delete) fails. */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.setTitle(tr("project.fileErrorTitle"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    /**
     * Refreshes the view after an in-app rename/delete by re-scanning the live tree from disk (by path) —
     * <em>not</em> the captured {@code TreeItem}. The confirm/rename dialog is a separate window, so closing
     * it regains focus on the main window and fires its focus-regain {@link #refreshTree()}, which rebuilds
     * the tree with new node instances; the captured item is then detached and removing/reloading it would
     * be a no-op. Re-listing from disk drops the just-deleted file (and shows the renamed name) reliably.
     */
    private void refreshAfterChange() {
        if (filtering) {
            rebuildBody();
        } else {
            refreshTree();
        }
    }

    // --- lazy tree node ---

    /** Lazily-populated tree node: lists its directory the first time its children are requested. */
    private static final class PathItem extends TreeItem<Path> {
        private boolean loaded;
        private Boolean leaf;
        private final boolean showHidden;

        PathItem(Path path, boolean showHidden) {
            super(path);
            this.showHidden = showHidden;
        }

        @Override
        public boolean isLeaf() {
            if (leaf == null) {
                leaf = !Files.isDirectory(getValue());
            }
            return leaf;
        }

        @Override
        public ObservableList<TreeItem<Path>> getChildren() {
            if (!loaded) {
                loaded = true;
                if (Files.isDirectory(getValue())) {
                    List<TreeItem<Path>> kids = new ArrayList<>();
                    for (Path child : listDir(getValue(), showHidden)) {
                        kids.add(new PathItem(child, showHidden));
                    }
                    super.getChildren().setAll(kids);
                }
            }
            return super.getChildren();
        }

        void reload() {
            loaded = false;
            leaf = null;
            getChildren();
        }
    }

    /** Directory children: directories first then files, case-insensitive; dotfiles hidden unless
     *  {@code includeHidden}; empty on error. */
    private static List<Path> listDir(Path dir, boolean includeHidden) {
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                if (!includeHidden && p.getFileName().toString().startsWith(".")) {
                    return;
                }
                (Files.isDirectory(p) ? dirs : files).add(p);
            });
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
        dirs.sort(ProjectPathOrder.DIRECTORIES_FIRST);
        files.sort(ProjectPathOrder.DIRECTORIES_FIRST);
        List<Path> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        return all;
    }

    /** All mutually-exclusive style classes a tree cell may carry (cleared before re-adding on each render). */
    private static final String[] CELL_CLASSES = {
        "folder-cell",
        "file-cell",
        "modified-file",
        "git-status-added",
        "git-status-modified",
        "git-status-deleted",
        "git-status-renamed",
        "git-status-untracked",
        "git-status-dir-changed"
    };

    private final class PathCell extends TreeCell<Path> {
        PathCell() {
            // Build the (20-odd node, SVG-iconed) menu only when it's actually asked for. Doing it in
            // updateItem would re-allocate the whole thing for every visible row on each cell recycle —
            // i.e. on every scroll tick and every tree.refresh() from setGitStatus/refreshModified.
            setOnContextMenuRequested(e -> {
                TreeItem<Path> ti = getTreeItem();
                Path item = getItem();
                if (isEmpty() || ti == null || item == null) {
                    return;
                }
                boolean isDir = ti instanceof PathItem pi ? !pi.isLeaf() : Files.isDirectory(item);
                contextMenuFor(ti, isDir, item.equals(root)).show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            // Drag a file/folder (or the whole multi-selection) and drop it onto a folder to MOVE it there.
            setOnDragDetected(e -> {
                TreeItem<Path> ti = getTreeItem();
                if (filtering
                        || ti == null
                        || ti.getValue() == null
                        || ti.getValue().equals(root)) {
                    return; // no drag from the flat filtered view or from the project root
                }
                List<Path> paths = new ArrayList<>();
                for (TreeItem<Path> it : actionTargets(ti)) {
                    paths.add(it.getValue());
                }
                if (paths.isEmpty()) {
                    return;
                }
                draggedPaths = paths;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString("project-file"); // in-panel marker; the real payload is draggedPaths
                db.setContent(content);
                SnapshotParameters params = new SnapshotParameters();
                params.setFill(Color.TRANSPARENT);
                db.setDragView(snapshot(params, null), e.getX(), e.getY());
                e.consume();
            });
            setOnDragOver(e -> {
                Path target = dropTargetDir();
                if (target != null && canDropInto(draggedPaths, target)) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    if (!getStyleClass().contains("project-drop-target")) {
                        getStyleClass().add("project-drop-target");
                    }
                }
                e.consume();
            });
            setOnDragExited(e -> getStyleClass().remove("project-drop-target"));
            setOnDragDropped(e -> {
                getStyleClass().remove("project-drop-target");
                Path target = dropTargetDir();
                boolean ok = target != null && !draggedPaths.isEmpty();
                if (ok) {
                    moveInto(new ArrayList<>(draggedPaths), target);
                }
                e.setDropCompleted(ok);
                e.consume();
            });
            setOnDragDone(e -> {
                draggedPaths = List.of();
                getStyleClass().remove("project-drop-target");
                e.consume();
            });
        }

        /** The folder this cell would drop INTO — the cell's own directory (or the root); {@code null} for a
         *  file cell or the filtered view, so drops only land on folders. */
        private Path dropTargetDir() {
            if (filtering) {
                return null;
            }
            Path item = getItem();
            if (item == null) {
                return null;
            }
            boolean isDir = getTreeItem() instanceof PathItem pi ? !pi.isLeaf() : Files.isDirectory(item);
            return isDir ? item : null;
        }

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("project-drop-target"); // a recycled cell must not keep a stale drop highlight
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                getStyleClass().removeAll(CELL_CLASSES);
                return;
            }
            // Reuse the lazy tree node's cached leaf flag (avoids a Files.isDirectory stat per cell
            // render); filtered flat rows are plain TreeItems, so fall back to a stat for those.
            boolean isDir = getTreeItem() instanceof PathItem pi ? !pi.isLeaf() : Files.isDirectory(item);
            // An open file with unsaved changes: mark it like a dirty tab ("• " + amber italic).
            boolean dirty = !isDir && isModified != null && isModified.test(item);
            // Mark the cell so the stylesheet can theme the folder vs. file icon color.
            getStyleClass().removeAll(CELL_CLASSES);
            getStyleClass().add(isDir ? "folder-cell" : "file-cell");
            // The file's Git status (null for a clean/dirty file, a folder, or Git off) — drives both the
            // color class and the single-letter label prefix, matching the Commit tool window.
            com.editora.git.GitFileStatus fileStatus = null;
            if (dirty) {
                getStyleClass().add("modified-file"); // unsaved-in-editor takes precedence over the Git color
            } else if (!gitStatus.isEmpty() || !gitChangedDirs.isEmpty()) {
                Path norm = item.toAbsolutePath().normalize();
                if (isDir) {
                    if (gitChangedDirs.contains(norm)) {
                        getStyleClass().add("git-status-dir-changed");
                    }
                } else {
                    fileStatus = gitStatus.get(norm);
                    if (fileStatus != null) {
                        getStyleClass().add(fileStatus.cssClass());
                    }
                }
            }
            // In filtered (flat) mode, show each match's path relative to the project root.
            boolean isRoot = item.equals(root);
            String label;
            if (filtering && !isRoot && root != null) {
                label = root.relativize(item).toString();
            } else {
                Path name = item.getFileName();
                label = name == null ? item.toString() : name.toString();
            }
            // Mark a changed file with its status letter (M/A/D/R/U), like the Commit window; an unsaved
            // (dirty) file keeps its "• " marker instead. The color comes from the git-status style class.
            // The letter goes in the graphic beside the glyph rather than into the text, so it can be bold
            // on its own (a Labeled cannot weight part of its string).
            if (dirty) {
                label = "• " + label;
            }
            setText(label);
            setContextMenu(null); // built lazily in setOnContextMenuRequested (see the PathCell constructor)
            Path fileName = item.getFileName();
            // Box the folder glyph in the same fixed icon column as the (already-boxed) file glyphs, so
            // folder and file rows share one icon width and every label starts at the same x.
            Node glyph = FileIcons.forProjectItem(fileName == null ? label : fileName.toString(), isDir);
            Node base = FileIcons.withStatusLetter(glyph, fileStatus == null ? null : fileStatus.letter());
            if (!isDir && markerActions != null) {
                HBox graphic = new HBox(3, base);
                graphic.setAlignment(Pos.CENTER_LEFT);
                if (markerActions.hasBookmarks(item)) {
                    graphic.getChildren().add(smallMarker(Icons.bookmark(), "project-bookmark-indicator"));
                }
                if (markerActions.personalNotesEnabled() && markerActions.hasPersonalNotes(item)) {
                    graphic.getChildren().add(smallMarker(Icons.notes(), "project-note-indicator"));
                }
                setGraphic(graphic);
            } else {
                setGraphic(base);
            }
        }

        private ContextMenu contextMenuFor(TreeItem<Path> treeItem, boolean isDir, boolean isRoot) {
            return ProjectPanel.this.contextMenuFor(treeItem, isDir, isRoot);
        }
    }

    private static Node smallMarker(Node icon, String styleClass) {
        icon.getStyleClass().add(styleClass);
        icon.setScaleX(0.68);
        icon.setScaleY(0.68);
        StackPane box = new StackPane(icon);
        box.setMinSize(12, 12);
        box.setPrefSize(12, 12);
        box.setMaxSize(12, 12);
        box.setMouseTransparent(true);
        return box;
    }

    /** One lazily built file-management menu shared by Tree rows and Canvas map nodes. */
    private ContextMenu contextMenuFor(TreeItem<Path> treeItem, boolean isDir, boolean isRoot) {
        ContextMenu menu = new ContextMenu();
        if (isDir) {
            menu.getItems().add(newMenu(treeItem));
        }
        // Offered on a folder holding a pom and on a pom.xml row itself — the same submenu the editor's
        // right-click on that file shows. Built per right-click rather than once: a folder gains or loses
        // its pom.xml while the tree is open, and mavenMenuFor owns the whole "is there anything here?"
        // rule (it answers null for a folder with no pom, and for any file that is not a pom.xml).
        javafx.scene.control.Menu maven = mavenMenuFor(treeItem.getValue());
        if (maven != null) {
            menu.getItems().add(maven);
        }
        // Rename is offered on every file/folder EXCEPT the project root — renaming that would move the
        // whole project folder on disk and leave the project pointing at a path that no longer exists.
        if (!isRoot) {
            MenuItem rename = new MenuItem(tr("project.menu.rename"));
            rename.setGraphic(Icons.edit());
            rename.setOnAction(e -> renameItem(treeItem));
            menu.getItems().add(rename);
        }
        if (!isDir) {
            MenuItem delete = new MenuItem(tr("project.menu.delete"));
            delete.setGraphic(Icons.trash());
            // Deletes the whole multi-file selection when this row is part of it, else just this file
            // (the confirm dialog shows the count).
            delete.setOnAction(e -> deleteSelected(treeItem));
            menu.getItems().add(delete);
            if (markerActions != null) {
                Path file = treeItem.getValue();
                menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

                MenuItem bookmark = new MenuItem(tr("project.menu.addBookmark"));
                bookmark.setGraphic(Icons.bookmark());
                bookmark.setOnAction(e -> markerActions.addBookmark(file));
                menu.getItems().add(bookmark);

                MenuItem note = new MenuItem(tr("project.menu.addPersonalNote"));
                note.setGraphic(Icons.notes());
                note.setDisable(!markerActions.personalNotesEnabled());
                note.setOnAction(e -> markerActions.addPersonalNote(file));
                menu.getItems().add(note);
            }
        }
        if (onReveal != null || onOpenTerminal != null) {
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        if (onReveal != null) {
            MenuItem reveal = new MenuItem(tr("project.menu.revealInFileManager"));
            reveal.setGraphic(Icons.revealInFiles());
            reveal.setOnAction(e -> onReveal.accept(treeItem.getValue(), isDir));
            menu.getItems().add(reveal);
        }
        if (onOpenTerminal != null) {
            MenuItem terminal = new MenuItem(tr("project.menu.openTerminal"));
            terminal.setGraphic(Icons.terminal());
            terminal.setOnAction(e -> onOpenTerminal.accept(treeItem.getValue(), isDir));
            menu.getItems().add(terminal);
        }
        // Local History + Git act on a concrete file (not a folder).
        if (fileActions != null && !isDir) {
            Path file = treeItem.getValue();
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

            MenuItem localHistory = new MenuItem(tr("project.menu.localHistory"));
            localHistory.setGraphic(Icons.history());
            localHistory.setOnAction(e -> fileActions.showLocalHistory(file));
            menu.getItems().add(localHistory);

            Menu git = new Menu(tr("project.menu.git"));
            git.setGraphic(Icons.git());
            MenuItem stage = new MenuItem(tr("project.menu.git.stage"));
            stage.setGraphic(Icons.stageAll());
            stage.setOnAction(e -> fileActions.gitStage(file));
            MenuItem unstage = new MenuItem(tr("project.menu.git.unstage"));
            unstage.setGraphic(Icons.remove());
            unstage.setOnAction(e -> fileActions.gitUnstage(file));
            MenuItem revert = new MenuItem(tr("project.menu.git.revert"));
            revert.setGraphic(Icons.undo());
            revert.setOnAction(e -> fileActions.gitRevert(file));
            MenuItem ignore = new MenuItem(tr("project.menu.git.addToGitignore"));
            ignore.setGraphic(Icons.git());
            ignore.setOnAction(e -> fileActions.gitAddToGitignore(file));
            MenuItem compareHead = new MenuItem(tr("project.menu.git.compareHead"));
            compareHead.setGraphic(Icons.diff());
            compareHead.setOnAction(e -> fileActions.gitCompareWithHead(file));
            MenuItem compareBranch = new MenuItem(tr("project.menu.git.compareBranch"));
            compareBranch.setGraphic(Icons.diff());
            compareBranch.setOnAction(e -> fileActions.gitCompareWithBranch(file));
            MenuItem compareRevision = new MenuItem(tr("project.menu.git.compareRevision"));
            compareRevision.setGraphic(Icons.diff());
            compareRevision.setOnAction(e -> fileActions.gitCompareWithRevision(file));
            MenuItem annotate = new MenuItem(tr("project.menu.git.annotate"));
            annotate.setGraphic(Icons.blame());
            annotate.setOnAction(e -> fileActions.gitAnnotate(file));
            MenuItem fileHistory = new MenuItem(tr("project.menu.git.fileHistory"));
            fileHistory.setGraphic(Icons.gitLog());
            fileHistory.setOnAction(e -> fileActions.gitShowFileHistory(file));
            git.getItems()
                    .addAll(
                            stage,
                            unstage,
                            revert,
                            ignore,
                            new javafx.scene.control.SeparatorMenuItem(),
                            compareHead,
                            compareBranch,
                            compareRevision,
                            annotate,
                            fileHistory);
            menu.getItems().add(git);

            // Disable to match the live feature toggles + the file's actual status.
            menu.setOnShowing(e -> {
                localHistory.setDisable(!fileActions.localHistoryEnabled());
                git.setDisable(!fileActions.gitAvailable()); // grey out the Git submenu when there's no VCS
                com.editora.git.GitFileStatus st =
                        gitStatus.get(file.toAbsolutePath().normalize());
                revert.setDisable(st == null); // nothing to revert on a clean file
                ignore.setDisable(st != com.editora.git.GitFileStatus.UNTRACKED); // ignore = for new files
            });
        }
        // On a folder: Local History (folder view) + Git Stage/Revert of the whole subtree.
        if (fileActions != null && isDir) {
            Path dir = treeItem.getValue();
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            MenuItem folderHistory = new MenuItem(tr("project.menu.localHistory"));
            folderHistory.setGraphic(Icons.history());
            folderHistory.setOnAction(e -> fileActions.showLocalHistory(dir));
            menu.getItems().add(folderHistory);

            Menu git = new Menu(tr("project.menu.git"));
            git.setGraphic(Icons.git());
            MenuItem stage = new MenuItem(tr("project.menu.git.stage"));
            stage.setGraphic(Icons.stageAll());
            stage.setOnAction(e -> fileActions.gitStage(dir));
            MenuItem revert = new MenuItem(tr("project.menu.git.revert"));
            revert.setGraphic(Icons.undo());
            revert.setOnAction(e -> fileActions.gitRevert(dir));
            git.getItems().addAll(stage, revert);
            menu.getItems().add(git);

            menu.setOnShowing(e -> {
                folderHistory.setDisable(!fileActions.localHistoryEnabled());
                boolean gitOn = fileActions.gitAvailable();
                git.setDisable(!gitOn);
                revert.setDisable(!gitChangedDirs.contains(dir.toAbsolutePath().normalize()));
            });
        }
        return menu;
    }
}
