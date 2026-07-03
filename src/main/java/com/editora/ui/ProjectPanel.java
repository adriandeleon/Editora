package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static com.editora.i18n.Messages.tr;

/**
 * The Project tool window: a filter box over a lazy file tree rooted at the active project's folder.
 * Typing in the filter runs a bounded, debounced project-wide name search (dot-dirs skipped, capped)
 * and shows matches as a flat list; clearing it restores the lazy tree. Emacs-style keyboard nav
 * (C-n/C-p, C-f/C-b, Enter) like the Structure panel; Enter/double-click opens a file; a right-click
 * menu renames/deletes files. (Project switch/close/delete live on the toolbar project combo and the
 * {@code project.*} palette commands — each window is one project, named in the window title.)
 */
public class ProjectPanel extends VBox implements ToolWindowContent {

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
    /** Injected by MainController: "New From Template…" on a folder, given the target directory. */
    private Consumer<Path> onNewFromTemplate;
    /** Injected by MainController: reveal a path in the OS file manager. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onReveal;
    /** Injected by MainController: open a terminal at a path's folder. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onOpenTerminal;
    /** Injected by MainController: per-file Local History + Git actions for the cell menu (files only). */
    private FileActions fileActions;

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

    /** In-scene single-line prompt (injected by MainController) used to rename a file/folder. */
    private OverlayInput.Prompt prompt;

    private final TextField filterField = new TextField();
    /** Filter row: the text field plus a trailing clear ("✕") button shown only while there's text. */
    private final HBox filterBar = new HBox();

    private final TreeView<Path> tree = new TreeView<>();
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

    public ProjectPanel(
            Consumer<Path> onOpenFile,
            BiConsumer<Path, Path> onFileRenamed,
            Consumer<Path> onFileDeleted,
            java.util.function.Predicate<Path> isModified) {
        this.onOpenFile = onOpenFile;
        this.onFileRenamed = onFileRenamed;
        this.onFileDeleted = onFileDeleted;
        this.isModified = isModified;
        getStyleClass().add("project-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        buildFilter();

        tree.setShowRoot(true);
        tree.getStyleClass().add("project-tree");
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
        // Since focus lands on the filter field (focusFirstItem), Down/Up move into the results and Enter
        // opens the selected (or first) match, so the whole flow is keyboard-only.
        filterField.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    if (tree.getExpandedItemCount() > 0) {
                        if (tree.getSelectionModel().isEmpty()) {
                            tree.getSelectionModel().select(0);
                        }
                        tree.requestFocus();
                        tree.scrollTo(tree.getSelectionModel().getSelectedIndex());
                    }
                    e.consume();
                }
                case ENTER -> {
                    if (tree.getSelectionModel().isEmpty() && tree.getExpandedItemCount() > 0) {
                        tree.getSelectionModel().select(0);
                    }
                    openSelected();
                    e.consume();
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

        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER);
        filterBar.getChildren().setAll(filterField, clear);
    }

    /** Re-renders the visible tree cells so each file's modified marker/color reflects current state. */
    public void refreshModified() {
        tree.refresh();
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
    }

    /**
     * Re-scans the tree against the current filesystem so files/folders added or removed outside Editora
     * show up. Preserves the expanded folders and the selection. No-op while filtering or with no project.
     * Cheap: only re-lists directories that are currently expanded. Called on window focus-regain.
     */
    public void refreshTree() {
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
        if (tree.getRoot() instanceof PathItem rootItem) {
            collectExpanded(rootItem, desired); // expanded directories (root included)
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
        while (!disposed) {
            java.nio.file.WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return; // disposed
            }
            key.pollEvents(); // we re-list rather than apply individual events; just drain them
            key.reset();
            Platform.runLater(() -> {
                if (!disposed) {
                    watchDebounce.playFromStart();
                }
            });
        }
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
                    } else if ((includeHidden || !hidden)
                            && name.toLowerCase(Locale.ROOT).contains(q)) {
                        matches.add(p);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Unreadable directory — skip it, keep searching the rest (best effort).
            }
        }
        matches.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return matches;
    }

    // --- keyboard navigation (mirrors StructurePanel) ---

    private void onKey(KeyEvent e) {
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
        tree.getSelectionModel().select(next);
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
    public void setOnNewFromTemplate(Consumer<Path> onNewFromTemplate) {
        this.onNewFromTemplate = onNewFromTemplate;
    }

    /** Injects a hook called with a regular file just before it's deleted (to snapshot it into Local History). */
    public void setOnBeforeDelete(Consumer<Path> onBeforeDelete) {
        this.onBeforeDelete = onBeforeDelete == null ? p -> {} : onBeforeDelete;
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
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
        dirs.sort(byName);
        files.sort(byName);
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
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
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
            if (dirty) {
                getStyleClass().add("modified-file"); // unsaved-in-editor takes precedence over the Git color
            } else if (!gitStatus.isEmpty() || !gitChangedDirs.isEmpty()) {
                Path norm = item.toAbsolutePath().normalize();
                if (isDir) {
                    if (gitChangedDirs.contains(norm)) {
                        getStyleClass().add("git-status-dir-changed");
                    }
                } else {
                    com.editora.git.GitFileStatus st = gitStatus.get(norm);
                    if (st != null) {
                        getStyleClass().add(st.cssClass());
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
            setText(dirty ? "• " + label : label);
            Path fileName = item.getFileName();
            // Box the folder glyph in the same fixed icon column as the (already-boxed) file glyphs, so
            // folder and file rows share one icon width and every label starts at the same x.
            setGraphic(
                    isDir
                            ? FileIcons.boxed(Icons.project())
                            : FileIcons.forFileName(fileName == null ? label : fileName.toString()));
            setContextMenu(isRoot ? null : contextMenuFor(getTreeItem(), isDir));
        }

        private ContextMenu contextMenuFor(TreeItem<Path> treeItem, boolean isDir) {
            ContextMenu menu = new ContextMenu();
            if (isDir && onNewFromTemplate != null) {
                MenuItem newFromTemplate = new MenuItem(tr("project.menu.newFromTemplate"));
                newFromTemplate.setGraphic(Icons.newFile());
                newFromTemplate.setOnAction(e -> onNewFromTemplate.accept(treeItem.getValue()));
                menu.getItems().add(newFromTemplate);
            }
            MenuItem rename = new MenuItem(tr("project.menu.rename"));
            rename.setGraphic(Icons.edit());
            rename.setOnAction(e -> renameItem(treeItem));
            menu.getItems().add(rename);
            if (!isDir) {
                MenuItem delete = new MenuItem(tr("project.menu.delete"));
                delete.setGraphic(Icons.trash());
                delete.setOnAction(e -> deleteItem(treeItem));
                menu.getItems().add(delete);
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
                    revert.setDisable(
                            !gitChangedDirs.contains(dir.toAbsolutePath().normalize()));
                });
            }
            return menu;
        }
    }
}
