package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import com.editora.config.PersonalNote;
import com.editora.editor.notes.NoteDraft;
import com.editora.editor.notes.PersonalNoteTooltip;
import com.editora.search.FuzzyMatch;
import org.jspecify.annotations.Nullable;

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

    /** Exact pre-delete bytes for every file whose content was captured during preparation. */
    public record DeleteApproval(boolean permitted, Map<Path, byte[]> expectedBytes) {

        public DeleteApproval {
            Map<Path, byte[]> copy = new java.util.LinkedHashMap<>();
            if (expectedBytes != null) {
                expectedBytes.forEach((path, bytes) -> {
                    if (path != null && bytes != null) {
                        copy.put(path, bytes.clone());
                    }
                });
            }
            expectedBytes = java.util.Collections.unmodifiableMap(copy);
        }

        static DeleteApproval approved() {
            return new DeleteApproval(true, Map.of());
        }

        static DeleteApproval denied() {
            return new DeleteApproval(false, Map.of());
        }
    }

    @FunctionalInterface
    public interface DeletePreparation {
        void prepare(List<Path> files, Consumer<DeleteApproval> completion);
    }

    public interface DeleteOperations {

        DeleteOperations SYSTEM = new DeleteOperations() {
            @Override
            public byte[] readAllBytes(Path file) throws IOException {
                return Files.readAllBytes(file);
            }

            @Override
            public void delete(Path file) throws IOException {
                Files.delete(file);
            }

            @Override
            public boolean movesToTrash(Path file) {
                return com.editora.io.Trash.system().accepts(file);
            }

            @Override
            public void moveToTrash(Path file) throws IOException {
                com.editora.io.Trash.system().trash(file);
            }
        };

        byte[] readAllBytes(Path file) throws IOException;

        /** Deletes {@code file} for good. */
        void delete(Path file) throws IOException;

        /**
         * Whether deleting {@code file} moves it to the operating system's trash, where it can be restored,
         * rather than deleting it for good. The confirmation tells the user which of the two will happen.
         */
        default boolean movesToTrash(Path file) {
            return false;
        }

        /** Moves {@code file} to the trash, or throws leaving it in place. Never deletes. */
        default void moveToTrash(Path file) throws IOException {
            throw new IOException("No trash is available for " + file);
        }
    }

    record DeleteResult(boolean prepared, int deleted, List<Path> failed) {
        DeleteResult {
            failed = List.copyOf(failed);
        }
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ProjectPanel.class.getName());

    static final int MAX_VISIT = 20_000;
    private static final int MAX_MATCHES = 300;
    /** The shortest query the Map searches the whole project for; a shorter one filters what is loaded. */
    static final int MIN_MAP_SEARCH_LENGTH = 2;

    private static final int MAX_DEPTH = 25;

    private final Consumer<Path> onOpenFile;
    private final BiConsumer<Path, Path> onFileRenamed;
    private final Consumer<Path> onFileDeleted;
    private final java.util.function.Predicate<Path> isModified;
    private final java.util.function.Predicate<Path> isOpen;
    /** Git working-tree status per file (absolute normalized path → status), for IntelliJ-style tree coloring;
     *  empty when Git is off / not a repo. Pushed by the Git coordinator on each status refresh. */
    private java.util.Map<Path, com.editora.git.GitFileStatus> gitStatus = java.util.Map.of();
    /** Directories that contain at least one Git-changed descendant (colored to hint at nested changes). */
    private java.util.Set<Path> gitChangedDirs = java.util.Set.of();
    /** Resolves dirty buffers and durable history before any selected file is deleted. */
    private DeletePreparation deletePreparation = (files, completion) -> completion.accept(DeleteApproval.approved());
    /** Filesystem boundary for guarded deletion and deterministic failure testing. */
    private DeleteOperations deleteOperations = DeleteOperations.SYSTEM;
    /** Injected by MainController: show a transient status-bar message (drag-move / multi-delete feedback). */
    private Consumer<String> onStatus = m -> {};
    /** Notified after the filesystem watcher picks up an <em>external</em> change (not the app's own edit), so
     *  the window can refresh things anchored to the working tree — Git status / the Commit stripe, build-tool
     *  markers, open diffs — which otherwise only re-evaluate on focus-regain / tab switch (#529). */
    private java.util.function.Consumer<ProjectWatchChanges.Plan> onExternalChange = plan -> {};
    /** Raw watcher events (path + kind), forwarded to LSP as didChangeWatchedFiles (#677); null = off. */
    private volatile java.util.function.@Nullable Consumer<List<FsChange>> fsChangeSink;
    /** Injected by MainController: "New From Template…" on a folder, given the target directory. */
    private @Nullable Consumer<Path> onNewFromTemplate;
    /** Injected by MainController: "New ▸ &lt;type&gt;" on a folder, given the target dir and the file kind. */
    private @Nullable BiConsumer<Path, com.editora.template.NewFileType> onNewFile;

    private @Nullable Consumer<Path> onNewMavenProject;

    /** Supplies the Maven submenu for a folder, or null when that folder holds no Maven project. */
    private java.util.function.@Nullable Function<java.nio.file.Path, javafx.scene.control.@Nullable Menu> mavenMenu;

    /** The Maven submenu for a folder, or null — built fresh per right-click, since a folder gains or
     *  loses its pom while the tree is open. */
    javafx.scene.control.@Nullable Menu mavenMenuFor(java.nio.file.Path dir) {
        return mavenMenu == null ? null : mavenMenu.apply(dir);
    }

    public void setMavenMenu(
            java.util.function.Function<java.nio.file.Path, javafx.scene.control.@Nullable Menu> supplier) {
        this.mavenMenu = supplier;
    }
    /** Injected by MainController: reveal a path in the OS file manager. Args: (path, isDirectory). */
    private @Nullable BiConsumer<Path, Boolean> onReveal;
    /** Injected by MainController: open a terminal at a path's folder. Args: (path, isDirectory). */
    private @Nullable BiConsumer<Path, Boolean> onOpenTerminal;
    /** Injected by MainController: per-file Local History + Git actions for the cell menu (files only). */
    private @Nullable FileActions fileActions;
    /** Injected by MainController: file-level entry points into the line-anchored bookmark/note stores. */
    private @Nullable MarkerActions markerActions;
    /** Injected by MainController: whether Simple UI mode is on, which trims the cell menu. */
    private java.util.function.BooleanSupplier simpleMode = () -> false;

    /**
     * File-scoped actions the Project tree's cell menu offers — Local History and Git. Injected by
     * {@code MainController} so the panel stays decoupled from the editor/history/git internals. The
     * {@code *Enabled} flags gate (disable) the corresponding menu entries to match the feature toggles.
     */
    public interface FileActions {
        boolean localHistoryEnabled();

        /** Encrypt…: opens {@code file} and asks who to encrypt it for (writes {@code file.gpg} beside it). */
        default void encrypt(Path file) {}

        void showLocalHistory(Path file);

        /**
         * Which repository {@code path} belongs to: none (or Git is off) — the Git submenu is disabled; the
         * window's active one — the tree's status colouring describes it; or another one (a nested
         * repository, a submodule, a second worktree), whose status is only known once git is asked.
         */
        GitPathScope gitScope(Path path);

        void gitShowFileHistory(Path file);

        void gitCompareWithHead(Path file);

        /** Diff {@code file} against its version on a branch chosen from the repo. */
        void gitCompareWithBranch(Path file);

        /** Diff {@code file} or folder against its version at a tag chosen from the repo. */
        void gitCompareWithTag(Path file);

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

        /** Tooltip text for notes attached directly to {@code path}, or blank when it has none. */
        default String personalNotesTooltip(Path path) {
            return "";
        }

        void addBookmark(Path file);

        void addPersonalNote(Path file);

        default void addBookmark(Path file, int line) {
            addBookmark(file);
        }

        default void addPersonalNote(Path file, NoteDraft draft) {
            addPersonalNote(file);
        }

        /**
         * As {@link #addPersonalNote(Path, NoteDraft)}, asked from a Project Map card: the prompt shows in
         * {@code over} (the Map window's overlay), where the user is, and names the file and lines.
         */
        default void addPersonalNote(Path file, NoteDraft draft, OverlayHost over) {
            addPersonalNote(file, draft);
        }

        default List<PersonalNote> personalNotes(Path path) {
            return List.of();
        }

        default void updatePersonalNote(Path path, PersonalNote note, String body) {}

        /**
         * Every path that may carry a bookmark or a Personal Note (the stores' keys). The Map uses it to mark
         * the collapsed folders that hold such files; {@link #hasBookmarks} / {@link #hasPersonalNotes} still
         * decide each one, so a superset is fine. Must not touch the filesystem.
         */
        default java.util.Collection<Path> markedPaths() {
            return List.of();
        }
    }

    /** Parses stored path keys, dropping any this platform cannot parse. For {@link MarkerActions#markedPaths}. */
    static List<Path> pathsOf(java.util.Collection<String> first, java.util.Collection<String> second) {
        List<Path> result = new ArrayList<>(first.size() + second.size());
        for (java.util.Collection<String> keys : List.of(first, second)) {
            for (String key : keys) {
                try {
                    result.add(Path.of(key));
                } catch (RuntimeException unparseable) {
                    // a remote or foreign-platform key: it cannot be under a local project root anyway
                }
            }
        }
        return result;
    }

    /** In-scene single-line prompt (injected by MainController) used to rename a file/folder. */
    private OverlayInput.@Nullable Prompt prompt;

    private final TextField filterField = new TextField();
    /** Opens the Project Map in its own window (or brings that window forward). */
    private final Button mapButton = new Button(tr("project.view.map"));
    /** Filter row: the text field plus a trailing clear ("✕") button shown only while there's text. */
    private final HBox filterBar = new HBox();

    private final TreeView<Path> tree = new TreeView<>();
    private final ProjectMapView mapView;
    /** The Map's own search field, in its window above the Map (the panel's field filters the tree). */
    private final TextField mapFilterField = new TextField();

    private final PauseTransition mapFilterDebounce = new PauseTransition(Duration.millis(150));
    /** Bumped by every Map search and by the Map window closing, so a stale result has nowhere to land. */
    private final AtomicLong mapSearchGen = new AtomicLong();
    /** The window the Map always opens in; built on the first open. */
    private final ProjectMapWindow mapWindow;
    /** The editor window this panel belongs to, for when the panel itself is not on screen. */
    private java.util.function.Supplier<javafx.stage.@Nullable Window> editorWindow = () -> null;
    /**
     * The workspace remembered the Map window as open, or the project it showed closed under it: reopen it once
     * there is a project and a window.
     */
    private boolean reopenMap;
    /**
     * The folder the Map shows: {@link #root}, except in a window whose root follows the active tab (the No
     * Project window), where an open Map stays on the folder it was opened on (see {@link #setMapRootPinned}).
     */
    private @Nullable Path mapRoot;
    /** Whether this window's root follows its active tab, so an open Map keeps its own root. */
    private java.util.function.BooleanSupplier mapRootPinned = () -> false;
    /** When the Map last asked to create a file in a folder (New ▸ from a Map row), to reveal the result. */
    private @Nullable Path mapNewFileFolder;

    private long mapNewFileAt;

    private Runnable onFocusEditor = () -> {};
    /** The tree currently holds the lazy project tree (not flat filter results) built for {@link #root}. */
    private boolean treeShowsProject;
    /** Told when the user opens or closes the Map window, so the workspace can remember it. */
    private Consumer<Boolean> onMapWindowChanged = open -> {};

    private final StackPane placeholderPane;
    /** Shown instead of the placeholder while the window's project is on a host that is not connected. */
    private final StackPane disconnectedPane;

    private final Label disconnectedLabel = new Label();
    private final Button reconnectButton = new Button();
    private boolean disconnected;
    private final PauseTransition filterDebounce = new PauseTransition(Duration.millis(150));

    // Filter searches walk the filesystem off the FX thread; a generation guard drops stale results.
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "project-filter-search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong searchGen = new AtomicLong();
    // Folder listings get their own thread: on the filter's they queued behind whatever walk was in flight,
    // so expanding a folder waited for a search nobody was waiting for any more.
    private final ExecutorService listExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "project-tree-list");
        t.setDaemon(true);
        return t;
    });
    private @Nullable Path pendingTreeSelection;
    private @Nullable Path pendingTreeReveal;
    /** Test seam for holding directory I/O without blocking the JavaFX thread. */
    volatile @Nullable Runnable beforeDirectoryListForTest;

    // Filesystem watcher: auto-refresh the tree when files change on disk. Watches only the root + currently
    // -expanded directories (re-synced on expand/collapse and after each refresh) so it's cheap even on huge
    // trees; a daemon thread drains events and a debounce coalesces bursts into one tick that re-lists just
    // the directories whose entries came or went (applyWatchChanges). Local roots
    // only (a remote SFTP root has no local WatchService). On macOS the JDK uses a polling watcher, so external
    // changes may take a few seconds to show (the focus-regain refresh covers the immediate case).
    private java.nio.file.@Nullable WatchService watchService;
    private @Nullable Thread watchThread;
    private final java.util.Map<java.nio.file.WatchKey, Path> watchKeys = new java.util.HashMap<>();
    private final PauseTransition watchDebounce = new PauseTransition(Duration.millis(250));
    private volatile boolean disposed;
    // In-app rename/delete update the tree directly (instantly); the watcher then re-fires for that same
    // change (the OS delivers the event a beat later — ~1 s on macOS) and would run a redundant full
    // refreshTree() rebuild that reads as the edit "settling" a second later. The paths an in-app operation
    // touched (path -> when) are recorded, and for a short window the watcher's events for them — and only
    // them — are taken for that operation's echo. Somebody else's change landing in the same window is not.
    private final java.util.Map<Path, Long> localChanges = new java.util.HashMap<>();

    private static final long SELF_CHANGE_WINDOW_MS = 1500;
    // What the watcher reported since the last debounce tick, by directory (FX thread only).
    private final ProjectWatchChanges watchChanges = new ProjectWatchChanges();
    // Files Editora saved a moment ago (path -> when): the watcher reports the atomic rename as the file being
    // created, which is neither an external change nor, for a file the tree already shows, a reason to re-list.
    private final java.util.Map<Path, Long> localWrites = new java.util.HashMap<>();
    /**
     * How often the JDK's polling watcher looks at a directory ({@code sun.nio.fs.PollingWatchService}, the
     * only one there is on macOS): the events of a write arrive up to this long after it.
     */
    static final long POLLING_WATCH_INTERVAL_MS = 2000;
    // How long after a save its watch events are still that save's; set with the watcher (see below).
    long localWriteWindowMs = SELF_CHANGE_WINDOW_MS;
    /** The clock the saved-a-moment-ago window is measured on; a test moves it instead of waiting. */
    java.util.function.LongSupplier localWriteClockForTest = System::currentTimeMillis;
    // A file that is merely rewritten (a log, a build output) changes no tree, and the Git/diff refresh it
    // asks for is coalesced to one per interval rather than one per write.
    private static final long MODIFY_THROTTLE_MS = 2000;
    private final PauseTransition externalThrottle = new PauseTransition();
    private final List<FsChange> queuedExternal = new ArrayList<>();
    private boolean queuedUnknown;
    private long lastExternalNotifyMs;
    /** Counts whole-tree refreshes and single-directory listings; read by tests. */
    int treeRefreshCountForTest;

    int directoryListCountForTest;

    private @Nullable Path root;
    private boolean filtering;
    private boolean loading;
    /** Show hidden (dot) files/folders in the tree + filter search. Toggled from Settings. */
    private boolean showHidden;
    /** Skip {@code .gitignore}d files/folders in the filter search (default on; the shared Search setting). */
    private boolean respectGitignore = true;
    /** The paths being drag-moved (in-panel drag onto a folder); empty when no drag is in progress. */
    private List<Path> draggedPaths = List.of();
    /** The active file (absolute normalized path), or {@code null} when none is active. */
    private @Nullable Path activeFile;

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
            java.util.function.Function<Path, ProjectMapPreview.@Nullable Content> previewContent) {
        this.onOpenFile = onOpenFile;
        this.onFileRenamed = onFileRenamed;
        this.onFileDeleted = onFileDeleted;
        this.isModified = isModified;
        this.isOpen = isOpen == null ? path -> false : isOpen;
        this.mapView = new ProjectMapView(this::openFromMap, isOpen, isModified, previewContent);
        this.mapView.setShowing(false); // nothing is listed for the Map until its window first shows
        this.mapView.setShowHidden(showHidden);
        this.mapView.setOnExpandedChanged(this::syncWatches);
        this.mapView.setContextMenuFactory(entry -> raisingEditorWindow(contextMenuFor(
                new MapRowItem(entry.path()), entry.directory(), entry.path().equals(mapRoot))));
        // F2 / Delete on the map's selection run the tree's row actions (see onKey), with the same limits.
        this.mapView.setRowActions(
                entry -> {
                    if (!entry.path().equals(mapRoot)) {
                        renameItem(new MapRowItem(entry.path()));
                    }
                },
                entry -> deleteSelected(new MapRowItem(entry.path())));
        this.mapView.setEscapeActions(mapFilterField::clear, this::leaveMap);
        this.mapWindow = new ProjectMapWindow(buildMapWindowContent());
        this.mapWindow.setOnShowing(mapView::setShowing); // refreshes, polls and loads follow the window
        this.mapWindow.setOnHidden(this::mapWindowHidden);
        this.mapWindow.setOnUserClosed(() -> onMapWindowChanged.accept(false));
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
        RowContextMenu.install(tree); // Menu key / Shift+F10 open the selected row's menu (cells are not focusable)
        // F2 / Delete / Alt+Insert are row actions here (see onKey), even in keymaps that bind them to a global
        // command: the IntelliJ keymap's Alt+Insert is Generate in the editor and New in the Project view (EN1).
        tree.getProperties()
                .put(
                        com.editora.command.KeyDispatcher.CLAIMED_KEYS,
                        java.util.Set.of("f2", "delete", "M-insert", "Cmd-n")); // ⌘N: the same on macOS (EN7)
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        // Expanding/collapsing a folder changes which directories we need to watch.
        javafx.event.EventHandler<TreeItem.TreeModificationEvent<Path>> onExpanded = e -> {
            // A folder that was listed, collapsed (so no longer watched) and is opened again may have changed
            // meanwhile: look again, in the background, keeping the rows that are still right.
            if (e.getTreeItem() instanceof PathItem item) {
                item.refresh();
            }
            syncWatches();
        };
        javafx.event.EventHandler<TreeItem.TreeModificationEvent<Path>> onCollapsed = e -> syncWatches();
        // On the root item, not on the TreeView: a TreeItem's events travel up its parent items and stop at
        // the root — they never reach the control, where these two handlers used to sit unheard.
        tree.rootProperty().addListener((obs, old, now) -> {
            if (old != null) {
                old.removeEventHandler(TreeItem.<Path>branchExpandedEvent(), onExpanded);
                old.removeEventHandler(TreeItem.<Path>branchCollapsedEvent(), onCollapsed);
            }
            if (now != null) {
                now.addEventHandler(TreeItem.<Path>branchExpandedEvent(), onExpanded);
                now.addEventHandler(TreeItem.<Path>branchCollapsedEvent(), onCollapsed);
            }
        });
        // A coalesced filesystem-change event re-scans the tree (preserving expansion + selection) — unless
        // we just made the change ourselves (in-app rename/delete already updated the tree instantly).
        watchDebounce.setOnFinished(e -> applyWatchChanges());
        externalThrottle.setOnFinished(e -> flushExternal());

        Label placeholder = new Label(tr("project.placeholder"));
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholderPane = new StackPane(placeholder);
        placeholderPane.setAlignment(Pos.CENTER);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);
        disconnectedLabel.getStyleClass().add("tool-window-placeholder");
        disconnectedLabel.setWrapText(true);
        disconnectedLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox disconnectedBox = new VBox(10, disconnectedLabel, reconnectButton);
        disconnectedBox.setAlignment(Pos.CENTER);
        disconnectedBox.setPadding(new javafx.geometry.Insets(16));
        disconnectedPane = new StackPane(disconnectedBox);
        VBox.setVgrow(disconnectedPane, Priority.ALWAYS);

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
        // C-n / C-p move the selection from this field (FilterFieldNav); outside Emacs the keymap binds them
        // to New File / Print / Find File, which would run instead.
        filterField.getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, java.util.Set.of("C-n", "C-p"));

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

        mapButton.getStyleClass().add("project-view-toggle");
        mapButton.setTooltip(new Tooltip(tr("project.view.map.tooltip")));
        // Reachable by Tab: without it opening the Map was mouse-only (project.toggleMapView aside).
        mapButton.setFocusTraversable(true);
        mapButton.setOnAction(e -> openMapWindow(true));
        HBox viewModes = new HBox(mapButton);
        viewModes.getStyleClass().add("project-view-modes");
        // The search field gives way in a narrow panel; the button never truncates to "M…".
        mapButton.setMinWidth(Region.USE_PREF_SIZE);
        viewModes.setMinWidth(Region.USE_PREF_SIZE);

        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER);
        filterBar.getChildren().setAll(filterField, clear, viewModes);
    }

    /**
     * The Project Map window's content: its own search row over the Map. The Map searches the whole project
     * as the tree's filter does, but the two fields are separate — each window filters what it shows.
     */
    private VBox buildMapWindowContent() {
        mapFilterField.setPromptText(tr("project.map.filterPrompt"));
        mapFilterField.getStyleClass().add("project-filter");
        mapFilterField.setId("project-map-filter");
        mapFilterField.textProperty().addListener((o, w, n) -> {
            if (!loading) {
                mapFilterDebounce.playFromStart();
            }
        });
        mapFilterDebounce.setOnFinished(e -> searchMap());
        // Down / Enter / C-n / C-p hand navigation to the Canvas surface; outside Emacs the keymap binds C-n /
        // C-p to New File / Print / Find File, which would run instead.
        mapFilterField
                .getProperties()
                .put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, java.util.Set.of("C-n", "C-p"));
        mapFilterField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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

        HBox filterRow = new HBox(mapFilterField, ClearableField.clearButton(mapFilterField));
        filterRow.getStyleClass().add("project-filter-bar");
        filterRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(mapFilterField, Priority.ALWAYS);
        VBox content = new VBox(filterRow, mapView);
        content.getStyleClass().add("project-map-window");
        VBox.setVgrow(mapView, Priority.ALWAYS);
        return content;
    }

    /**
     * Opens the Project Map in its own window, or brings that window forward when it is open already. The Map
     * never shows inside the Project tool window: it wants the room of a document, not of a side panel.
     */
    public void openMapWindow(boolean focus) {
        showMapWindow(focus, true);
    }

    /** {@code remember}: whether opening it is the user's choice to store (not a restore of what was stored). */
    private void showMapWindow(boolean focus, boolean remember) {
        boolean wasShowing = mapWindow.isShowing();
        if (!wasShowing) {
            if (root == null) {
                return;
            }
            // Opened (not brought forward): the Map maps what the tree shows now. Only an open Map keeps a
            // root of its own, in the window whose tree follows the active tab.
            if (!com.editora.config.PathKeys.samePath(mapRoot, root)) {
                applyMapRoot(root);
            }
        }
        reopenMap = false;
        updateMapWindowTitle();
        mapWindow.show(editorWindow()); // its showing drives the Map's first load (and every reload on reopen)
        if (!wasShowing) {
            syncWatches();
            if (remember) {
                onMapWindowChanged.accept(true);
            }
            revealActiveFileOnOpen();
        }
        if (focus) {
            mapView.focusMap();
        }
    }

    /** Opened with nothing chosen on it (the root alone is selected): the Map starts at the file being edited. */
    private void revealActiveFileOnOpen() {
        Path selected = mapView.windowSelectedPath();
        if (activeFile != null && (selected == null || com.editora.config.PathKeys.samePath(selected, mapRoot))) {
            mapView.revealPath(activeFile);
        }
    }

    /** Points the Map at {@code next}, which clears what it showed of the previous folder (its search too). */
    private void applyMapRoot(@Nullable Path next) {
        mapRoot = next;
        mapView.setRoot(next);
        mapView.setGitStatus(gitStatus); // recompute changed-directory ancestry against the new root
        loading = true;
        mapFilterField.clear();
        loading = false;
        mapView.setQuery("");
        updateMapWindowTitle();
    }

    /**
     * Whether this window's tree root follows its active tab (the No Project window, with no remote folder
     * mounted). There an open Map keeps the folder it was opened on: opening a file from it, or switching to
     * a tab in another folder or to one with no folder at all, leaves it as it is. It takes the tree's root
     * again only when it is opened anew, or when {@link #revealActiveFileInMap} needs a file it does not hold.
     */
    public void setMapRootPinned(java.util.function.BooleanSupplier pinned) {
        mapRootPinned = pinned == null ? () -> false : pinned;
    }

    /** The folder the Map shows (it differs from {@link #getRoot()} only while pinned; tests). */
    @Nullable
    Path mapRoot() {
        return mapRoot;
    }

    /**
     * {@code projectMap.revealActiveFile}: opens the Map window (or brings it forward) and selects the active
     * file on it. A pinned Map that does not hold the file takes the tree's root first. Says so when there is
     * no file to reveal.
     */
    public void revealActiveFileInMap() {
        Path file = activeFile;
        if (file != null
                && root != null
                && mapRoot != null
                && !com.editora.config.PathKeys.isAtOrUnder(file, mapRoot)
                && com.editora.config.PathKeys.isAtOrUnder(file, root)) {
            applyMapRoot(root); // the open Map's pinned folder does not hold the file the user asked for
        }
        showMapWindow(true, true);
        if (!isMapShowing()) {
            return;
        }
        if (file == null || mapRoot == null || !com.editora.config.PathKeys.isAtOrUnder(file, mapRoot)) {
            onStatus.accept(tr("status.projectMap.noActiveFile"));
            return;
        }
        mapView.revealPath(file);
    }

    /**
     * {@code project.toggleMapView}: opens the Map window, or closes it when it is the window in use — so the
     * one key both summons the Map and puts it away.
     */
    public void toggleMapView() {
        if (mapWindow.isFocused()) {
            mapWindow.closeByUser();
            leaveMap();
        } else {
            openMapWindow(true);
        }
    }

    /** Whether the Project Map's window is open. */
    public boolean isMapShowing() {
        return mapWindow.isShowing();
    }

    /** The Map's window (for tests: its stage, its content). */
    ProjectMapWindow mapWindow() {
        return mapWindow;
    }

    /**
     * The editor window this panel belongs to — the window holding the panel, or, while the Project tool
     * window is closed, the one the controller names. The Map window copies its look and closes with it.
     */
    void setEditorWindow(java.util.function.Supplier<javafx.stage.@Nullable Window> window) {
        editorWindow = window == null ? () -> null : window;
        mapView.setEditorFontSource(this::editorWindow); // the Map's cards use that window's editor font
    }

    /** What the Map window's scene gets, as the editor window's did: its key dispatcher. */
    void setMapSceneSetup(Consumer<javafx.scene.Scene> setup) {
        mapWindow.setSceneSetup(setup);
    }

    private javafx.stage.@Nullable Window editorWindow() {
        javafx.stage.Window own = getScene() == null ? null : getScene().getWindow();
        return own != null ? own : editorWindow.get();
    }

    private void updateMapWindowTitle() {
        Path name = mapRoot == null ? null : mapRoot.getFileName();
        mapWindow.setTitle(
                name == null ? tr("project.map.windowTitleNoName") : tr("project.map.windowTitle", name.toString()));
    }

    /** The Map window stopped showing (closed by the user, its editor window, or the project going away). */
    private void mapWindowHidden() {
        mapSearchGen.incrementAndGet(); // a search still in flight has nowhere to land
        syncWatches(); // the Map's open folders need no watching now; its cards keep their place
    }

    /** Runs the Map window's search: the whole project, off the FX thread, as the tree's filter does. */
    private void searchMap() {
        long gen = mapSearchGen.incrementAndGet(); // invalidate any in-flight Map search
        String q = mapFilterField.getText().trim();
        mapView.setQuery(q);
        syncWatches();
        Path searchRoot = mapRoot;
        if (q.isEmpty() || searchRoot == null || !isMapShowing()) {
            return;
        }
        boolean includeHidden = showHidden;
        boolean useGitignore = respectGitignore && com.editora.vfs.Vfs.isLocal(searchRoot);
        if (q.codePointCount(0, q.length()) < MIN_MAP_SEARCH_LENGTH) {
            // One character matches most of a project: walking it, opening the folders of 300 matches and
            // drawing them stalled every window for a third of a second on a mid-size repository. It filters
            // the rows already on the map instead, and the status says how to search the whole project.
            mapView.setSearchMatches(q, List.of(), 0, false);
            onStatus.accept(tr("project.map.status.searchTooShort", MIN_MAP_SEARCH_LENGTH));
            return;
        }
        submitUnlessDisposed(searchExecutor, () -> {
            com.editora.search.GitignoreFilter gitignore = useGitignore
                    ? com.editora.search.GitignoreFilter.load(searchRoot)
                    : com.editora.search.GitignoreFilter.NONE;
            SearchResult found = searchRanked(searchRoot, q, includeHidden, gitignore, () -> gen != mapSearchGen.get());
            Platform.runLater(() -> {
                if (gen != mapSearchGen.get() || !isMapShowing()) {
                    return;
                }
                mapView.setSearchMatches(q, found.matches(), found.total(), found.partial());
            });
        });
    }

    /** Re-runs a Map search on show, after a setting changed what it finds. */
    private void searchMapAgain() {
        if (mapRoot != null && isMapShowing() && !mapFilterField.getText().isBlank()) {
            searchMap();
        }
    }

    /** Escape on the Map with nothing left to dismiss: back to the editor, in the editor window. */
    private void leaveMap() {
        raiseEditorWindow();
        onFocusEditor.run();
    }

    /** A file opened from the Map (its rows or a preview card): opened in the editor, which comes forward. */
    private void openFromMap(Path file) {
        onOpenFile.accept(file);
        raiseEditorWindow();
    }

    /**
     * Brings the editor window in front of the Map window when the user is working in the Map — what they
     * asked for (a file, a diff, a prompt for a name) happens there, and the Map window may be covering it.
     */
    private void raiseEditorWindow() {
        if (mapWindow.isFocused() && editorWindow() instanceof javafx.stage.Stage editor) {
            editor.toFront();
            editor.requestFocus();
        }
    }

    /** Property on a menu item whose action stays in the Map window (its own dialog, or nothing to show). */
    static final String STAYS_IN_MAP = "editora.project.staysInMap";

    /**
     * The Map window's copy of a row's context menu: each item that does its work in the editor window
     * brings that window forward first. Rename, New Folder and Delete stay: they ask over the Map.
     */
    private ContextMenu raisingEditorWindow(ContextMenu menu) {
        raiseFromItems(menu.getItems());
        // Some items are filled in as the menu opens; wrap those too.
        menu.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> raiseFromItems(menu.getItems()));
        return menu;
    }

    private void raiseFromItems(List<MenuItem> items) {
        for (MenuItem item : items) {
            if (item instanceof Menu sub) {
                raiseFromItems(sub.getItems());
                if (sub.getProperties().putIfAbsent(STAYS_IN_MAP + ".hooked", Boolean.TRUE) == null) {
                    sub.addEventHandler(Menu.ON_SHOWN, e -> raiseFromItems(sub.getItems()));
                }
                continue;
            }
            javafx.event.EventHandler<javafx.event.ActionEvent> action = item.getOnAction();
            if (action == null || item.getProperties().containsKey(STAYS_IN_MAP)) {
                continue;
            }
            item.getProperties().put(STAYS_IN_MAP, Boolean.FALSE); // wrapped once, never twice
            item.setOnAction(e -> {
                raiseEditorWindow();
                action.handle(e);
            });
        }
    }

    /**
     * Where a name is asked for: in the Map window's own overlay while the user works there; else in the
     * editor window's.
     */
    private OverlayInput.@Nullable Prompt activePrompt() {
        java.util.function.Function<OverlayHost, OverlayInput.Prompt> in = promptIn;
        if (mapWindow.isFocused() && in != null) {
            return in.apply(mapWindow.overlayHost());
        }
        return prompt;
    }

    /** The in-scene prompt shown in a given overlay host — the Map window's, while the user works there. */
    private java.util.function.@Nullable Function<OverlayHost, OverlayInput.Prompt> promptIn;

    /** Injects how a name is asked for in another window's overlay host (the Map window's). */
    public void setPromptIn(java.util.function.Function<OverlayHost, OverlayInput.Prompt> promptIn) {
        this.promptIn = promptIn;
    }

    /** Test seam for {@link #promptIn}: the one prompt, whichever window asks. */
    void setMapPromptForTest(OverlayInput.Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        this.promptIn = host -> prompt;
    }

    /** Whose window a file-operation dialog belongs over: the Map's while the user works there. */
    private javafx.stage.@Nullable Window dialogOwner() {
        if (mapWindow.isFocused()) {
            return mapWindow.stage();
        }
        return getScene() == null ? editorWindow() : getScene().getWindow();
    }

    /** Open editor files, so the Map's Open / Modified chips can mark the collapsed folders that hold them. */
    public void setOpenFiles(java.util.function.Supplier<java.util.Collection<Path>> openFiles) {
        mapView.setOpenFiles(openFiles);
    }

    /** Re-renders the visible tree cells so each file's modified marker/color reflects current state. */
    public void refreshModified() {
        tree.refresh();
        mapView.refreshStates();
    }

    /** Refreshes the Map's open-tab markers after the editor's tab membership changes. */
    public void refreshOpenFiles() {
        tree.refresh();
        mapView.refreshStates();
    }

    /** Re-renders bookmark and Personal Note indicators in both Project views. */
    public void refreshMarkers() {
        tree.refresh();
        mapView.refreshStates();
    }

    /** Updates the active-file marker used for current-file emphasis in the tree. */
    public void setActiveFile(@Nullable Path file) {
        Path next = file == null ? null : file.toAbsolutePath().normalize();
        if (com.editora.config.PathKeys.samePath(activeFile, next)) {
            return;
        }
        activeFile = next;
        tree.refresh();
        mapView.refreshStates();
        if (next != null
                && isMapShowing()
                && mapRoot != null
                && com.editora.config.PathKeys.isAtOrUnder(next, mapRoot)) {
            if (mapView.isFollowActiveFile() || createdFromMap(next)) {
                mapView.revealPath(next); // "Always select opened file", or the file New ▸ just made from the Map
            }
        }
    }

    /** How long after New ▸ on a Map row the file it opens is taken to be that one (the name prompt is open). */
    private static final long MAP_NEW_FILE_WINDOW_MS = 5 * 60_000L;

    /** Whether {@code file} is the one New ▸ on a Map row asked for: opened in that folder since. Once only. */
    private boolean createdFromMap(Path file) {
        Path folder = mapNewFileFolder;
        if (folder == null || System.currentTimeMillis() - mapNewFileAt > MAP_NEW_FILE_WINDOW_MS) {
            return false;
        }
        if (!com.editora.config.PathKeys.isAtOrUnder(file, folder) || java.nio.file.Files.isDirectory(file)) {
            return false;
        }
        mapNewFileFolder = null;
        return true;
    }

    /** A folder or file just created from a Map row: shown and selected on the Map, as the tree reveals it. */
    private void revealCreatedOnMap(TreeItem<Path> origin, Path created) {
        if (origin instanceof MapRowItem && isMapShowing()) {
            mapView.revealPath(created);
        }
    }

    /**
     * A Map row's stand-in for the tree item the shared row actions take: a detached item, so expanding it
     * reveals nothing; what it was made for is the Map, which reveals the result itself.
     */
    private static final class MapRowItem extends TreeItem<Path> {
        MapRowItem(Path path) {
            super(path);
        }
    }

    /** Reveals and selects a file or folder in the project tree. */
    public void revealPath(Path path) {
        if (path == null || root == null) {
            return;
        }
        Path target = path.toAbsolutePath().normalize();
        if (!(tree.getRoot() instanceof PathItem item)
                || !com.editora.config.PathKeys.isAtOrUnder(
                        target, root.toAbsolutePath().normalize())) {
            return;
        }
        pendingTreeReveal = target;
        continuePendingReveal(item);
    }

    /** Continues an asynchronous tree reveal as each directory level finishes loading. */
    private void continuePendingReveal(PathItem rootItem) {
        Path target = pendingTreeReveal;
        Path projectRoot = root; // set whenever a reveal is pending (setRoot clears both)
        if (target == null || projectRoot == null) {
            return;
        }
        TreeItem<Path> current = rootItem;
        current.setExpanded(true);
        Path relative = projectRoot.toAbsolutePath().normalize().relativize(target);
        for (Path segment : relative) {
            TreeItem<Path> next = current.getChildren().stream()
                    .filter(child -> child.getValue().getFileName().equals(segment))
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                if (current instanceof PathItem pathItem && !pathItem.childrenReady) {
                    return;
                }
                pendingTreeReveal = null;
                return;
            }
            current = next;
            if (!current.isLeaf()) {
                current.setExpanded(true);
            }
        }
        tree.getSelectionModel().select(current);
        tree.scrollTo(tree.getRow(current));
        tree.requestFocus();
        pendingTreeReveal = null;
    }

    /** Clears the tree's filter and reveals {@code path} in the project tree. */
    public void revealPathInTree(Path path) {
        loading = true;
        filterField.clear();
        loading = false;
        rebuildBody();
        revealPath(path);
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
     * Cheap: only re-lists directories that are currently expanded. Called on window focus-regain. The Map,
     * while its window is open, reloads too.
     */
    public void refreshTree() {
        treeRefreshCountForTest++;
        GitPathScope.learnRemote(root, false); // a repository made or removed there since (at most once a minute)
        if (isMapShowing()) {
            mapView.refresh();
        }
        if (root == null || filtering || !(tree.getRoot() instanceof PathItem rootItem)) {
            return;
        }
        List<PathItem> expanded = new ArrayList<>();
        collectExpandedItems(rootItem, expanded);
        expanded.forEach(PathItem::refresh);
    }

    /** Re-lists just {@code dirs} (those the tree currently shows expanded); the rest of the tree is untouched. */
    private void refreshDirectories(java.util.Collection<Path> dirs) {
        if (isMapShowing()) {
            mapView.refresh(dirs); // the Map lists these again and reuses the rest
        }
        if (root == null || filtering || !(tree.getRoot() instanceof PathItem rootItem)) {
            return;
        }
        for (Path dir : dirs) {
            if (findVisible(rootItem, dir) instanceof PathItem item && item.isExpanded()) {
                item.refresh();
            }
        }
    }

    /** Whether the tree already has a row for {@code path} (so a save of it adds nothing to show). */
    private boolean treeShows(Path path) {
        return !filtering && tree.getRoot() instanceof PathItem rootItem && findVisible(rootItem, path) != null;
    }

    /**
     * Records that Editora itself just wrote {@code file} (a completed save), so the watch events that write
     * produces are not taken for an external change. FX thread.
     */
    public void noteLocalWrite(Path file) {
        if (file == null || watchService == null || !com.editora.vfs.Vfs.isLocal(file)) {
            return;
        }
        long now = localWriteClockForTest.getAsLong();
        localWrites.values().removeIf(at -> now - at >= localWriteWindowMs);
        localWrites.put(file.toAbsolutePath().normalize(), now);
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            mapView.listingsChanged(List.of(parent)); // its size and time changed; no entry came or went
        }
    }

    /**
     * How long after Editora wrote a file the watcher's events for that file are taken for the write. It has
     * to outlast the watcher's own delay: a polling watcher reports a save up to a whole interval later, which
     * is longer than the window a native one needs — so on macOS a save Editora made itself came back as
     * somebody else's change, and refreshed Git, the diffs and the index after it.
     */
    static long localWriteWindowMs(boolean pollingWatcher) {
        return SELF_CHANGE_WINDOW_MS + (pollingWatcher ? POLLING_WATCH_INTERVAL_MS : 0);
    }

    /** Whether {@code service} is the JDK's polling watcher rather than one the OS notifies. */
    static boolean isPollingWatcher(java.nio.file.WatchService service) {
        return service != null
                && "PollingWatchService".equals(service.getClass().getSimpleName());
    }

    /** {@link #noteLocalWrite(Path)} for a caller whose window may have no panel (a test host). */
    static void noteLocalWrite(ProjectPanel panel, Path file) {
        if (panel != null) {
            panel.noteLocalWrite(file);
        }
    }

    private boolean recentlyWrittenLocally(Path path) {
        Long at = localWrites.get(path.toAbsolutePath().normalize());
        return at != null && localWriteClockForTest.getAsLong() - at < localWriteWindowMs;
    }

    /** The debounced half of the watcher: applies what accumulated since the last tick. FX thread. */
    private void applyWatchChanges() {
        if (disposed) {
            return;
        }
        // Events for what an in-app rename, move, delete or create touched are its echo: that operation already
        // refreshed the tree. Everything else in the batch is applied as usual.
        ProjectWatchChanges.Plan plan = watchChanges.drain(this::recentlyWrittenLocally, this::changedLocally);
        if (plan.relistAll()) {
            refreshTree();
        } else {
            java.util.Set<Path> dirs = new java.util.LinkedHashSet<>(plan.relistDirs());
            for (Path written : plan.localWrites()) {
                if (written.getParent() != null && (isMapShowing() || !treeShows(written))) {
                    dirs.add(written.getParent()); // a first save of a new file: the row is still missing
                }
            }
            if (!dirs.isEmpty()) {
                refreshDirectories(dirs);
            }
        }
        syncWatches(); // a newly-created folder that's expanded would need watching
        if (plan.hasExternal()) {
            java.util.Set<Path> rewritten = new java.util.HashSet<>();
            for (FsChange change : plan.external()) {
                if (change.kind() == FsKind.CHANGED && change.path().getParent() != null) {
                    rewritten.add(change.path().getParent());
                }
            }
            mapView.listingsChanged(rewritten); // a rewritten file's size and time, read with its next listing
            mapView.filesChangedOnDisk(); // a Map preview card re-reads a file that was rewritten
            // An external change → refresh Git/Commit stripe, build markers, diffs (#529).
            queuedExternal.addAll(plan.external());
            queuedUnknown |= plan.unknown();
            long sinceLast = System.currentTimeMillis() - lastExternalNotifyMs;
            if (plan.structural() || plan.unknown() || sinceLast >= MODIFY_THROTTLE_MS) {
                flushExternal();
            } else if (externalThrottle.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                externalThrottle.setDuration(Duration.millis(MODIFY_THROTTLE_MS - sinceLast));
                externalThrottle.playFromStart();
            }
        }
    }

    private void flushExternal() {
        externalThrottle.stop();
        if (disposed || (queuedExternal.isEmpty() && !queuedUnknown)) {
            return;
        }
        ProjectWatchChanges.Plan plan = new ProjectWatchChanges.Plan(
                false, java.util.Set.of(), List.of(), List.copyOf(queuedExternal), queuedUnknown);
        queuedExternal.clear();
        queuedUnknown = false;
        lastExternalNotifyMs = System.currentTimeMillis();
        onExternalChange.accept(plan);
    }

    /** Every expanded directory row whose children are loaded, parents before children. */
    private static void collectExpandedItems(TreeItem<Path> item, List<PathItem> out) {
        if (!item.isExpanded() || !(item instanceof PathItem dir)) {
            return;
        }
        out.add(dir);
        for (TreeItem<Path> child : item.getChildren()) {
            collectExpandedItems(child, out);
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

    /** Finds a (visible) tree item for {@code target} among the expanded items, or null if gone. */
    private static @Nullable TreeItem<Path> findVisible(TreeItem<Path> item, Path target) {
        if (com.editora.config.PathKeys.samePath(target, item.getValue())) {
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
    public void setRoot(@Nullable Path root) {
        this.root = root;
        lastMove = List.of(); // an undo must not reach back into the project that was open before
        pendingTreeSelection = null;
        pendingTreeReveal = null;
        loading = true;
        filterField.clear();
        loading = false;
        // In the No Project window the tree follows the active tab, but the Map does not: an open Map keeps the
        // folder it shows (a local one), and a closed one takes the tree's root when it is next opened
        // (showMapWindow), so its cards survive the tab switches in between.
        boolean pinned = mapRootPinned.getAsBoolean();
        boolean keepMapRoot =
                pinned && (!mapWindow.isShowing() || mapRoot != null && com.editora.vfs.Vfs.isLocal(mapRoot));
        if (!keepMapRoot) {
            if (root == null && mapWindow.isShowing()) {
                mapWindow.closeQuietly(); // the project closed under it: it comes back with the next, as at startup
                reopenMap = !pinned;
            }
            applyMapRoot(root);
        }
        ensureWatchOrStop(); // start a watcher for a local root (or stop it for remote/none)
        rebuildBody(); // ends with syncWatches() — registers the new root's dirs, cancels the old root's
        if (root == null && isMapShowing()) {
            syncWatches(); // the placeholder registers nothing: keep watching what the pinned Map shows
        }
        GitPathScope.learnRemote(root, true); // a remote project: where its repositories are, for the menus
        reopenMapWhenReady();
    }

    /**
     * Says, in place of the tree, that this window's project is on a host that is not connected, with a
     * button that reconnects. Takes effect while there is no root; {@link #clearDisconnected} takes it away.
     */
    public void showDisconnected(String message, String buttonText, Runnable reconnect) {
        disconnected = true;
        disconnectedLabel.setText(message);
        reconnectButton.setText(buttonText);
        reconnectButton.setOnAction(e -> reconnect.run());
        if (root == null) {
            rebuildBody();
        }
    }

    public void clearDisconnected() {
        if (disconnected) {
            disconnected = false;
            if (root == null) {
                rebuildBody();
            }
        }
    }

    /** Whether the not-connected message is what the panel shows (tests). */
    boolean showsDisconnected() {
        return disconnected && root == null;
    }

    /** The folder the tree is currently rooted at, or {@code null} when showing the placeholder. */
    public @Nullable Path getRoot() {
        return root;
    }

    /** Show/hide hidden (dot) files and folders; rebuilds the tree when the value changes. */
    public void setShowHidden(boolean showHidden) {
        if (this.showHidden == showHidden) {
            return;
        }
        this.showHidden = showHidden;
        mapView.setShowHidden(showHidden); // each Map column's default, and what the Map loads
        if (root != null) {
            rebuildBody(); // recreate the tree (PathItems capture the flag) with the new visibility
            searchMapAgain();
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
        searchMapAgain();
    }

    /** Rebuilds the body: placeholder (no project), filtered flat results, or the lazy tree. */
    private void rebuildBody() {
        long gen = searchGen.incrementAndGet(); // invalidate any in-flight search
        if (root == null) {
            getChildren().setAll(disconnected ? disconnectedPane : placeholderPane);
            return;
        }
        String q = filterField.getText().trim();
        treeShowsProject = q.isEmpty();
        if (q.isEmpty()) {
            filtering = false;
            PathItem rootItem = new PathItem(root, showHidden, true);
            rootItem.setExpanded(true);
            tree.setRoot(rootItem);
        } else {
            filtering = true;
            // Walk off the FX thread (up to MAX_VISIT entries); apply results back under the gen guard.
            Path searchRoot = root;
            boolean includeHidden = showHidden;
            boolean useGitignore = respectGitignore && com.editora.vfs.Vfs.isLocal(root);
            PathItem pending = new PathItem(root, includeHidden, true, true);
            pending.setExpanded(true);
            tree.setRoot(pending);
            submitUnlessDisposed(searchExecutor, () -> {
                // Load the root .gitignore off the FX thread (reads one file); NONE = no exclusion.
                com.editora.search.GitignoreFilter gitignore = useGitignore
                        ? com.editora.search.GitignoreFilter.load(searchRoot)
                        : com.editora.search.GitignoreFilter.NONE;
                List<Path> matches = search(searchRoot, q, includeHidden, gitignore, () -> gen != searchGen.get());
                Platform.runLater(() -> {
                    if (gen != searchGen.get()) {
                        return; // a newer query (or a tree switch) superseded this one
                    }
                    PathItem rootItem = new PathItem(searchRoot, includeHidden, true, true);
                    rootItem.setExpanded(true);
                    for (Path match : matches) {
                        rootItem.getChildren().add(new PathItem(match, includeHidden, false));
                    }
                    tree.setRoot(rootItem);
                    if (!rootItem.getChildren().isEmpty()) {
                        // Highlight the first match. Enter in the filter field opens the highlighted row: with
                        // none it did nothing, and Down first landed on the project's own row.
                        tree.getSelectionModel().select(rootItem.getChildren().get(0));
                    }
                });
            });
        }
        getChildren().setAll(filterBar, tree);
        syncWatches(); // register the (new) tree's directories with the filesystem watcher
    }

    // --- filesystem watcher (auto-refresh on disk changes) ---------------------------------------

    /**
     * Starts the watcher for a local root, or cancels all watches for a remote/absent root. A remote root
     * whose host may run commands is watched there ({@link RemoteWatcher}); its reports join the same
     * pipeline as a local watcher's.
     */
    private void ensureWatchOrStop() {
        if (root != null && !watchedOnTarget(root)) {
            stopRemoteWatch();
            ensureWatchService();
        } else {
            cancelAllWatches();
            ensureRemoteWatch();
        }
    }

    /**
     * Whether {@code dir} is watched where its tools run rather than by this machine's watcher: a remote
     * folder, or a local one whose target says this machine is not told of its changes (a WSL distribution's,
     * once its tools run there). No I/O.
     */
    static boolean watchedOnTarget(Path dir) {
        return !com.editora.vfs.Vfs.isLocal(dir)
                || !com.editora.process.ExecTargets.forPath(dir).localWatchSeesChanges();
    }

    /** The watcher on the host of a remote root, and the root it watches. */
    private @Nullable RemoteWatcher remoteWatcher;

    private @Nullable Path remoteWatched;

    private void ensureRemoteWatch() {
        if (root == null || disposed || !watchedOnTarget(root)) {
            stopRemoteWatch();
            return;
        }
        if (remoteWatcher != null && root.equals(remoteWatched) && remoteWatcher.isRunning()) {
            return;
        }
        stopRemoteWatch();
        Path watched = root;
        remoteWatcher = RemoteWatcher.start(
                watched,
                batch -> Platform.runLater(() -> {
                    if (!disposed && watched.equals(root)) {
                        onRemoteChanges(batch);
                    }
                }));
        remoteWatched = remoteWatcher == null ? null : watched;
    }

    private void stopRemoteWatch() {
        RemoteWatcher w = remoteWatcher;
        remoteWatcher = null;
        remoteWatched = null;
        if (w != null) {
            w.stop();
        }
    }

    /**
     * A batch from the host: what the local watch loop does with a batch of events, for paths that were
     * reported one by one rather than per watched directory. A reported directory is re-listed itself (on a
     * host without inotifywait that is how a deletion shows: the folder changed).
     */
    private void onRemoteChanges(List<FsChange> batch) {
        var sink = fsChangeSink;
        if (sink != null) {
            sink.accept(batch);
        }
        java.util.Map<Path, List<FsChange>> byDir = new java.util.LinkedHashMap<>();
        for (FsChange change : batch) {
            Path dir = change.path().getParent();
            if (dir != null) {
                byDir.computeIfAbsent(dir, d -> new java.util.ArrayList<>()).add(change);
            }
        }
        byDir.forEach((dir, changes) -> watchChanges.add(dir, changes, false));
        watchDebounce.playFromStart();
    }

    /**
     * Watches the root where it should be watched after what may run where changed — the user agreed to
     * commands on a host or in a WSL distribution, or withdrew it, or switched remote commands on or off: a
     * remote root on its host or not at all, a WSL one in the distribution or back on this machine's watcher.
     */
    public void refreshRemoteWatch() {
        if (root != null && !disposed) {
            ensureWatchOrStop();
            syncWatches();
        }
    }

    /** Whether the host of this remote root is being watched (tests). */
    boolean watchesRemotely() {
        return remoteWatcher != null && remoteWatcher.isRunning();
    }

    /** Lazily creates the {@link java.nio.file.WatchService} + its daemon drain thread (once per panel). */
    private void ensureWatchService() {
        if (watchService != null || disposed) {
            return;
        }
        // The system's watcher, or a polling one when it refuses (on Linux: the user's inotify instances are
        // used up). The tree used to stop following the disk without a word then.
        watchService = com.editora.io.WatchServices.open();
        localWriteWindowMs = localWriteWindowMs(isPollingWatcher(watchService));
        watchThread = new Thread(this::watchLoop, "project-fs-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /** Folders watched only for the project's Java style (DN4): the tree does not show them, so no refresh. */
    private volatile java.util.Set<Path> styleOnlyDirs = java.util.Set.of();

    /**
     * Registers the root + currently-expanded directories with the watcher and cancels watches for
     * directories no longer present/expanded. Runs on the FX thread (it reads the tree). Cheap: the set is
     * just the visible folders.
     */
    private void syncWatches() {
        if (watchService == null) {
            return;
        }
        if (root != null && watchedOnTarget(root)) {
            cancelAllWatches(); // watched where its tools run: this machine's watcher would see nothing, or twice
            mapView.setWatchedDirectories(java.util.Set.of());
            return;
        }
        java.util.Set<Path> desired = new java.util.HashSet<>();
        if (root != null) {
            desired.add(root);
        }
        if (tree.getRoot() instanceof PathItem rootItem) {
            collectExpanded(rootItem, desired); // expanded directories (root included)
        }
        if (isMapShowing()) {
            desired.addAll(mapView.expandedDirectories());
        }
        // The folders the project's Java style is read from, open in the tree or not: an outside edit to a profile
        // in .settings went unseen in a project window (fifth review DN4). Their changes refresh no tree.
        java.util.Set<Path> style = new java.util.HashSet<>();
        if (root != null && !com.editora.vfs.Vfs.isRemote(root)) {
            for (Path dir : com.editora.lsp.JavaProjectSettings.styleFolders(root)) {
                if (desired.add(dir)) {
                    style.add(dir);
                }
            }
        }
        styleOnlyDirs = java.util.Set.copyOf(style);
        watchKeys.entrySet().removeIf(entry -> {
            if (!desired.contains(entry.getValue())) {
                entry.getKey().cancel();
                return true;
            }
            return false;
        });
        java.util.Set<Path> already = new java.util.HashSet<>(watchKeys.values());
        for (Path dir : desired) {
            if (already.contains(dir)) {
                continue;
            }
            try {
                java.nio.file.WatchKey key = com.editora.io.WatchServices.register(
                        dir,
                        watchService,
                        java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_DELETE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                watchKeys.put(key, dir);
            } catch (IOException | RuntimeException ignored) {
                // a dir we can't watch (perms, vanished) is simply not watched
            }
        }
        // The Map reuses a folder's listing only while this watcher would tell it the folder changed. The
        // style-only folders' events refresh nothing, so they do not count.
        java.util.Set<Path> followed = new java.util.HashSet<>(watchKeys.values());
        followed.removeAll(style);
        mapView.setWatchedDirectories(followed);
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
                List<FsChange> batch = List.copyOf(changes);
                if (sink != null && !batch.isEmpty()) {
                    Platform.runLater(() -> {
                        if (!disposed) {
                            sink.accept(batch);
                        }
                    });
                }
                if (!watchEventsWarrantRefresh(names, overflow)) {
                    continue; // only Editora's own temp files changed — no tree rebuild
                }
                if (watchedDir != null && styleOnlyDirs.contains(watchedDir)) {
                    continue; // watched for the Java style only: the tree does not show it
                }
                boolean lost = overflow;
                Platform.runLater(() -> {
                    if (!disposed) {
                        // Carried through the debounce by directory, so the tick re-lists the folders whose
                        // entries came or went rather than the whole tree.
                        watchChanges.add(watchedDir, batch, lost);
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
        return ProjectWatchChanges.isInternalName(name);
    }

    /**
     * Runs {@code task} on {@code executor} unless this panel has been disposed, which shuts both executors
     * down. A tree can still be laid out once more as its window closes (a folder is then asked for its
     * children) and a filter's debounce can still fire: those threw RejectedExecutionException on the FX thread.
     */
    private void submitUnlessDisposed(ExecutorService executor, Runnable task) {
        if (disposed) {
            return;
        }
        try {
            executor.submit(task);
        } catch (RejectedExecutionException shutDown) {
            // disposed meanwhile: nothing is shown any more
        }
    }

    /** Stops the watcher + its thread; call on window close so the daemon thread + native handles are freed. */
    public void dispose() {
        disposed = true;
        stopRemoteWatch();
        watchDebounce.stop();
        externalThrottle.stop();
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
        listExecutor.shutdownNow();
        mapFilterDebounce.stop();
        mapWindow.closeQuietly(); // not owned by the editor window, so not closed with it by JavaFX
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
        return search(root, query, includeHidden, gitignore, () -> false);
    }

    /**
     * As above, giving up as soon as {@code cancelled} says so (the result is then partial and unused). Every
     * keystroke in the filter field starts a walk of up to {@link #MAX_VISIT} entries; without this each one
     * ran to the end on the single filter thread, so the walk for what is typed now waited behind the walks
     * for every prefix typed on the way to it.
     */
    static List<Path> search(
            Path root,
            String query,
            boolean includeHidden,
            com.editora.search.GitignoreFilter gitignore,
            java.util.function.BooleanSupplier cancelled) {
        return searchRanked(root, query, includeHidden, gitignore, cancelled).matches();
    }

    /**
     * What a filename search found: the best {@link #MAX_MATCHES} matches, best first; how many names matched
     * in all; and whether the walk stopped at {@link #MAX_VISIT} entries before it saw the whole project.
     */
    record SearchResult(List<Path> matches, int total, boolean partial) {
        static final SearchResult NONE = new SearchResult(List.of(), 0, false);

        /** Whether more matched (or may have) than {@link #matches()} holds. */
        boolean truncated() {
            return partial || total > matches.size();
        }
    }

    /**
     * As {@link #search}, with the counts. The walk is bounded by the entries it visits, not by the matches it
     * has found: stopping at the first {@link #MAX_MATCHES} hits of a breadth-first walk kept shallow files and
     * never reached the deep one a short query named. Every match is ranked as it is found and only the best
     * {@link #MAX_MATCHES} are kept (a bounded heap), so the result is the best of everything visited.
     */
    static SearchResult searchRanked(
            Path root,
            String query,
            boolean includeHidden,
            com.editora.search.GitignoreFilter gitignore,
            java.util.function.BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return SearchResult.NONE; // superseded while it sat in the queue
        }
        String q = query.toLowerCase(Locale.ROOT);
        com.editora.search.GitignoreFilter rootIgnore =
                gitignore == null ? com.editora.search.GitignoreFilter.NONE : gitignore;
        // Worst first, so the heap's head is what a better match pushes out.
        java.util.PriorityQueue<SearchHit> best = new java.util.PriorityQueue<>(SearchHit.ORDER.reversed());
        int total = 0;
        boolean partial = false;
        // Each queued directory carries the ignore rules in force inside it: the root's, plus those of any
        // .gitignore met on the way down (packages/<name>/.gitignore naming node_modules).
        record Dir(Path path, int depth, com.editora.search.GitignoreFilter ignore) {}
        java.util.Deque<Dir> queue = new java.util.ArrayDeque<>();
        queue.add(new Dir(root, 0, rootIgnore));
        int visited = 0;
        while (!queue.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return SearchResult.NONE;
            }
            if (visited > MAX_VISIT) {
                partial = true;
                break;
            }
            Dir current = queue.poll();
            com.editora.search.GitignoreFilter ignore = current.ignore();
            boolean useGitignore = !ignore.isEmpty();
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(current.path())) {
                for (Path p : entries) {
                    if (++visited > MAX_VISIT) {
                        partial = true;
                        break;
                    }
                    if ((visited & 0xFF) == 0 && cancelled.getAsBoolean()) {
                        return SearchResult.NONE; // one huge directory must not pin the thread either
                    }
                    String name = p.getFileName().toString();
                    boolean hidden = name.startsWith(".");
                    boolean dir = Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    String rel = useGitignore || (dir && ignore.nests())
                            ? root.relativize(p).toString().replace(java.io.File.separatorChar, '/')
                            : ""; // not needed: "" ignores nothing and nests no filter
                    if (useGitignore && ignore.ignored(rel, dir)) {
                        continue; // .gitignore'd (e.g. target/, node_modules/, *.log) — skip file + subtree
                    }
                    if (dir) {
                        if (current.depth() + 1 < MAX_DEPTH && (includeHidden || !hidden)) {
                            // descend later — shallower first
                            queue.add(new Dir(p, current.depth() + 1, ignore.nested(p, rel)));
                        }
                    } else if ((includeHidden || !hidden) && FuzzyMatch.of(name, q) != null) {
                        total++;
                        SearchHit hit = SearchHit.of(root, p, name, q);
                        if (best.size() < MAX_MATCHES) {
                            best.add(hit);
                        } else if (SearchHit.ORDER.compare(hit, best.peek()) < 0) {
                            best.poll();
                            best.add(hit);
                        }
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Unreadable directory — skip it, keep searching the rest (best effort).
            }
        }
        List<SearchHit> ranked = new ArrayList<>(best);
        ranked.sort(SearchHit.ORDER);
        return new SearchResult(ranked.stream().map(SearchHit::path).toList(), total, partial);
    }

    /**
     * One filename match and how it ranks. Relevance, not the alphabet: first how the name answers the query
     * ({@link ProjectSearchRank#tier} — the exact name, then a prefix, then whole words…), then, among names
     * that answer it as well, the shorter name (fewer characters the user did not type), then the fuzzy score
     * of the whole relative path, which lets a query name a directory as well as a file, then the shallower
     * path and the name.
     */
    private record SearchHit(Path path, int tier, int nameLength, int score, int depth, String name) {
        static final java.util.Comparator<SearchHit> ORDER = java.util.Comparator.comparingInt(SearchHit::tier)
                .thenComparingInt(hit -> hit.tier() == ProjectSearchRank.FUZZY ? 0 : hit.nameLength())
                .thenComparing(
                        java.util.Comparator.comparingInt(SearchHit::score).reversed())
                .thenComparingInt(SearchHit::depth)
                .thenComparing(SearchHit::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(hit -> hit.path().toString());

        static SearchHit of(Path root, Path path, String name, String query) {
            Path rel = root.relativize(path);
            FuzzyMatch.Match m = FuzzyMatch.ofPath(rel.toString().replace(java.io.File.separatorChar, '/'), query);
            // The walk matched on the file name, so a path-level score can be absent (a name-only match
            // whose characters don't line up across the whole path); such a row keeps a floor score.
            return new SearchHit(
                    path,
                    ProjectSearchRank.tier(name, query),
                    name.length(),
                    m == null ? Integer.MIN_VALUE : m.score(),
                    rel.getNameCount(),
                    name);
        }
    }

    // --- keyboard navigation (mirrors StructurePanel) ---

    private void onKey(KeyEvent e) {
        // F2 renames and Delete deletes the selected row — the row actions that were reachable only from
        // the right-click menu. Only while the tree itself has the key: in the filter field they are text
        // editing keys.
        boolean alt = e.isAltDown() && !e.isControlDown() && !e.isMetaDown() && !e.isShiftDown();
        boolean cmd = e.isMetaDown() && !e.isAltDown() && !e.isControlDown() && !e.isShiftDown();
        boolean rowActionKey = rowKey(e.getCode(), alt, cmd, true, true) != RowKey.NONE; // else: no lookups
        TreeItem<Path> selected = rowActionKey ? tree.getSelectionModel().getSelectedItem() : null;
        switch (rowKey(
                e.getCode(), alt, cmd, rowActionKey && inTree(e), selected != null && selected.getValue() != null)) {
            case NEW -> {
                if (selected != null) {
                    showNewMenu(selected.isLeaf() && selected.getParent() != null ? selected.getParent() : selected);
                }
                e.consume();
                return;
            }
            case RENAME -> {
                if (selected != null // RENAME needs a selected row
                        && !selected.getValue().equals(root)) { // as the menu: the project root is never renamed
                    renameItem(selected);
                }
                e.consume();
                return;
            }
            case DELETE -> {
                deleteSelected(selected); // the menu's confirmed flow: files only, the whole multi-selection
                e.consume();
                return;
            }
            default -> {}
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

    /** A row action a bare key triggers in the file tree. */
    enum RowKey {
        NONE,
        RENAME,
        DELETE,
        NEW
    }

    /**
     * Which row action a key press means: F2 renames, Delete deletes — but only for a press aimed at the tree
     * with a row selected. Anywhere else in the panel (the filter field) those keys keep their own meaning.
     * Pure — tested.
     */
    static RowKey rowKey(javafx.scene.input.KeyCode code, boolean inTree, boolean hasSelection) {
        return rowKey(code, false, inTree, hasSelection);
    }

    /** As above, with Alt held: Alt+Insert is New in the selected folder, as in IntelliJ's Project view. */
    static RowKey rowKey(javafx.scene.input.KeyCode code, boolean alt, boolean inTree, boolean hasSelection) {
        return rowKey(code, alt, false, inTree, hasSelection);
    }

    /** As above, with Cmd held: ⌘N is New in the selected folder, as in IntelliJ's macOS Project view (EN7). */
    static RowKey rowKey(
            javafx.scene.input.KeyCode code, boolean alt, boolean cmd, boolean inTree, boolean hasSelection) {
        if (!inTree || !hasSelection) {
            return RowKey.NONE;
        }
        if (cmd) {
            return code == javafx.scene.input.KeyCode.N ? RowKey.NEW : RowKey.NONE;
        }
        return switch (code) {
            case F2 -> alt ? RowKey.NONE : RowKey.RENAME;
            case DELETE -> alt ? RowKey.NONE : RowKey.DELETE;
            case INSERT -> alt ? RowKey.NEW : RowKey.NONE;
            default -> RowKey.NONE;
        };
    }

    /** The folder's New ▸ entries as a popup at its row (Alt+Insert in the tree). */
    private void showNewMenu(TreeItem<Path> dirItem) {
        Menu entries = newMenu(dirItem, simpleMode.getAsBoolean());
        javafx.scene.control.ContextMenu popup = new javafx.scene.control.ContextMenu();
        popup.getItems().setAll(new java.util.ArrayList<>(entries.getItems()));
        javafx.geometry.Bounds row = RowContextMenu.selectedRowOnScreen(tree);
        if (row != null) {
            popup.show(tree, row.getMinX() + RowContextMenu.anchorX(row.getWidth()), row.getMaxY());
        }
    }

    /** Whether a key event is aimed at the file tree (or one of its cells) rather than the filter field. */
    private boolean inTree(KeyEvent e) {
        for (Node n = e.getTarget() instanceof Node t ? t : null; n != null; n = n.getParent()) {
            if (n == tree) {
                return true;
            }
        }
        return false;
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
        if (item instanceof PathItem pathItem ? !pathItem.isLeaf() : Files.isDirectory(path)) {
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

    /**
     * What may change before a rename: {@code (from, to, proceed)} makes the edits that go with it (a Java class
     * renamed with its file) and then calls {@code proceed} with whether to go on. By default nothing does.
     */
    public interface RenameParticipant {
        void willRename(Path from, Path to, Consumer<Boolean> proceed);

        /** A drag-move, or its Undo Move, of {@code count} items begins: its renames are one change until {@link #endMove}. */
        default void beginMove(int count) {}

        /** It is done; {@code undo} when it took the last drag-move back. Runs after the tree's own status. */
        default void endMove(boolean undo) {}

        /**
         * As {@link #endMove(boolean)}, with the tree's {@code status}: a save the move made can land after it, and the
         * participant says it again once the drag is recorded (seventh review BF3).
         */
        default void endMove(boolean undo, String status) {
            endMove(undo);
        }

        /** Whether the last {@code willRename} that answered false was the user's No, not a failure (BR4). */
        default boolean refusedByUser() {
            return true;
        }
    }

    private RenameParticipant renameParticipant = (from, to, proceed) -> proceed.accept(true);

    public void setRenameParticipant(RenameParticipant participant) {
        this.renameParticipant = participant == null ? (from, to, proceed) -> proceed.accept(true) : participant;
    }

    /** Injects the dirty-buffer and durable-history gate run after confirmation but before filesystem changes. */
    public void setDeletePreparation(DeletePreparation deletePreparation) {
        this.deletePreparation = deletePreparation == null
                ? (files, completion) -> completion.accept(DeleteApproval.approved())
                : deletePreparation;
    }

    void setDeleteOperations(DeleteOperations deleteOperations) {
        this.deleteOperations = Objects.requireNonNull(deleteOperations, "deleteOperations");
    }

    /**
     * Asks a yes/no question about a tree operation (permanently deleting what the trash refused, moving a
     * folder or several items). {@code true} = go ahead. Replaceable so tests answer without a modal dialog.
     */
    private java.util.function.Predicate<String> confirmQuestion = this::confirmQuestionDialog;

    /** Test seam for {@link #confirmQuestion}. */
    void setConfirmQuestionForTest(java.util.function.Predicate<String> confirm) {
        this.confirmQuestion = Objects.requireNonNull(confirm, "confirm");
    }

    private boolean confirmQuestionDialog(String message) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(dialogOwner());
        confirm.setTitle(tr("project.fileOperationTitle"));
        confirm.setHeaderText(null);
        cancelByDefault(confirm);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Makes Cancel the button Enter presses: for a question whose "yes" cannot be taken back. */
    private static void cancelByDefault(Alert confirm) {
        if (confirm.getDialogPane().lookupButton(ButtonType.OK) instanceof javafx.scene.control.Button ok) {
            ok.setDefaultButton(false);
        }
        if (confirm.getDialogPane().lookupButton(ButtonType.CANCEL) instanceof javafx.scene.control.Button cancel) {
            cancel.setDefaultButton(true);
        }
    }

    /** Injects the status-message sink used for drag-move / multi-delete feedback. */
    public void setOnStatus(Consumer<String> onStatus) {
        this.onStatus = onStatus == null ? m -> {} : onStatus;
        mapView.setOnStatus(this.onStatus);
    }

    /** Injects how the Project Map hands keyboard focus back to the editor (Escape with nothing left to dismiss). */
    public void setOnFocusEditor(Runnable onFocusEditor) {
        this.onFocusEditor = onFocusEditor == null ? () -> {} : onFocusEditor;
    }

    /** Restores and persists the Project Map's directional layout in workspace state. */
    public void setRememberedMapFlow(String flow, Consumer<String> onChanged) {
        mapView.setRememberedFlow(flow, value -> {
            if (onChanged != null) {
                onChanged.accept(value.name());
            }
        });
    }

    /**
     * Restores what the workspace remembers of this panel — Tree or Map, the Map's flow, its ⋯ options and
     * "Hide unmatched" — and writes later changes back through {@code state} and {@code save}.
     */
    public void setRememberedMapState(
            java.util.function.Supplier<com.editora.config.WorkspaceState> state, Runnable save) {
        com.editora.config.WorkspaceState stored = state.get();
        setRememberedMapFlow(stored.getProjectMapFlow(), flow -> {
            state.get().setProjectMapFlow(flow);
            save.run();
        });
        mapView.setRememberedNavigation(
                stored.isProjectMapKeepZoom(), stored.isProjectMapFocusNewColumn(), (keepZoom, focusColumn) -> {
                    state.get().setProjectMapKeepZoom(keepZoom);
                    state.get().setProjectMapFocusNewColumn(focusColumn);
                    save.run();
                });
        mapView.setRememberedHideUnmatched(stored.isProjectMapHideUnmatched(), hide -> {
            state.get().setProjectMapHideUnmatched(hide);
            save.run();
        });
        mapView.setRememberedOptions(
                stored.isProjectMapSingleClickOpens(),
                opens -> {
                    state.get().setProjectMapSingleClickOpens(opens);
                    save.run();
                },
                stored.isProjectMapCompactChains(),
                compact -> {
                    state.get().setProjectMapCompactChains(compact);
                    save.run();
                },
                stored.isProjectMapFollowActiveFile(),
                follow -> {
                    state.get().setProjectMapFollowActiveFile(follow);
                    save.run();
                });
        mapWindow.setBoundsMemory(
                () -> state.get().getProjectMapWindowBounds(),
                bounds -> state.get().setProjectMapWindowBounds(bounds));
        mapWindow.setMaximizedMemory(
                () -> state.get().isProjectMapWindowMaximized(),
                maximized -> state.get().setProjectMapWindowMaximized(maximized));
        onMapWindowChanged = open -> {
            state.get()
                    .setProjectViewMode(
                            open
                                    ? com.editora.config.WorkspaceState.PROJECT_VIEW_MAP
                                    : com.editora.config.WorkspaceState.PROJECT_VIEW_TREE);
            save.run();
        };
        reopenMap = com.editora.config.WorkspaceState.PROJECT_VIEW_MAP.equals(stored.getProjectViewMode());
        reopenMapWhenReady();
    }

    /**
     * Reopens the Map window the workspace remembered as open, once there is a project to map and the editor
     * window is on screen — without taking focus from the editor, as a restored window should not.
     */
    private void reopenMapWhenReady() {
        if (!reopenMap || root == null || disposed) {
            return;
        }
        javafx.stage.Window window = editorWindow();
        if (window == null) {
            return; // asked again when the panel is next given a root
        }
        if (window.isShowing()) {
            Platform.runLater(() -> {
                if (reopenMap && root != null && !disposed) {
                    showMapWindow(false, false);
                    window.requestFocus();
                }
            });
            return;
        }
        javafx.beans.value.ChangeListener<Boolean> once = new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(
                    javafx.beans.value.ObservableValue<? extends Boolean> obs, Boolean was, Boolean showing) {
                if (showing) {
                    window.showingProperty().removeListener(this);
                    reopenMapWhenReady();
                }
            }
        };
        window.showingProperty().addListener(once);
    }

    /** Injects the window-owned print and PDF handlers for a full Project Map snapshot. */
    void setMapOutputActions(Consumer<ProjectMapOutput> print, Consumer<ProjectMapOutput> pdf) {
        mapView.setOutputActions(
                output -> print.accept(fromMapWindow(output)), output -> pdf.accept(fromMapWindow(output)));
    }

    /** {@code output}, asked for in the Map window: its dialogs open over that window, its file named for the Map. */
    private ProjectMapOutput fromMapWindow(ProjectMapOutput output) {
        javafx.stage.Window owner = mapWindow.stage();
        Path folder = mapRoot == null ? null : mapRoot.getFileName();
        String base = (folder == null ? "project" : folder.toString()) + "-map";
        return new ProjectMapOutput() {
            @Override
            public boolean landscape() {
                return output.landscape();
            }

            @Override
            public @Nullable Rendered render(double pageWidth, double pageHeight) {
                return output.render(pageWidth, pageHeight);
            }

            @Override
            public javafx.stage.@Nullable Window owner() {
                return owner;
            }

            @Override
            public String baseName() {
                return base;
            }
        };
    }

    /** Whether the Project Map's window is open and the Map has something to print or export. */
    public boolean isMapOutputAvailable() {
        return isMapShowing() && mapView.canOutput();
    }

    /** {@code projectMap.print}: what the map's Print… button does. */
    public void printMap() {
        mapView.print();
    }

    /** {@code projectMap.exportPdf}: what the map's PDF… button does. */
    public void exportMapPdf() {
        mapView.exportPdf();
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
        this.onExternalChange = onExternalChange == null ? plan -> {} : plan -> onExternalChange.run();
    }

    /**
     * As {@link #setOnExternalChange(Runnable)}, but told what changed: the files the watcher named, and
     * whether that list is complete ({@code false} after an overflow — then anything may have changed).
     */
    public void setOnExternalChanges(java.util.function.BiConsumer<List<FsChange>, Boolean> onExternalChanges) {
        this.onExternalChange = onExternalChanges == null
                ? plan -> {}
                : plan -> onExternalChanges.accept(plan.external(), !plan.unknown());
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

    /**
     * Injects the Simple UI mode check. While it answers true the cell menu keeps file management only — New
     * File / New Folder, Rename, Undo Move, Delete, Reveal in File Manager — as the Simple menu bar does.
     */
    public void setSimpleMode(java.util.function.BooleanSupplier simpleMode) {
        this.simpleMode = simpleMode;
    }

    /**
     * Where the Map's declaration cards and rows go: the file opened at a 0-based line, and the file opened
     * narrowed to a block. Either brings the editor window forward.
     */
    void setMapOpenActions(
            BiConsumer<Path, Integer> openAt, BiConsumer<Path, com.editora.text.FoldRegions.Region> openNarrowed) {
        mapView.setOpenActions(
                (file, line) -> {
                    openAt.accept(file, line);
                    raiseEditorWindow();
                },
                (file, block) -> {
                    openNarrowed.accept(file, block);
                    raiseEditorWindow();
                });
    }

    /** The language server's outline of an open buffer, for the Map's structure columns. */
    void setMapServerSymbols(
            java.util.function.Function<Path, java.util.@Nullable List<com.editora.lsp.SymbolNode>> symbols) {
        mapView.setServerSymbols(symbols);
    }

    /** Injects bookmark/note actions and state used by both Project renderers. */
    public void setMarkerActions(MarkerActions markerActions) {
        this.markerActions = markerActions;
        mapView.setNotePreviewActions(markerActions);
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
                                // A card is only ever in the Map window: its prompt belongs over the Map.
                                markerActions.addPersonalNote(file, draft, mapWindow.overlayHost());
                            }
                        });
        mapView.setMarkerCandidates(
                () -> this.markerActions == null ? List.<Path>of() : this.markerActions.markedPaths());
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
    private Menu newMenu(TreeItem<Path> dirItem, boolean simple) {
        Menu newMenu = new Menu(tr("newfile.menu.new"));
        newMenu.setGraphic(Icons.newFile());
        Path dir = dirItem.getValue();
        BiConsumer<Path, com.editora.template.NewFileType> newFile = onNewFile;
        Consumer<Path> newFromTemplate = onNewFromTemplate;
        Consumer<Path> newMavenProject = onNewMavenProject;

        if (newFile != null) {
            newMenu.getItems().add(typeItem(com.editora.template.NewFileCatalog.PLAIN, dirItem, newFile));
        }
        MenuItem folder = new MenuItem(tr("project.menu.newFolder"));
        folder.setGraphic(Icons.newFolder());
        folder.setOnAction(e -> newFolder(dirItem));
        folder.getProperties().put(STAYS_IN_MAP, Boolean.TRUE); // asks over the Map window
        newMenu.getItems().add(folder);

        if (simple) {
            return newMenu; // a text file or a folder: no file-type catalog, templates or Maven project
        }
        if (newFile != null) {
            newMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            for (com.editora.template.NewFileType type : com.editora.template.NewFileCatalog.topLevel()) {
                newMenu.getItems().add(typeItem(type, dirItem, newFile));
            }
            for (com.editora.template.NewFileCatalog.Category category :
                    com.editora.template.NewFileCatalog.categories()) {
                Menu submenu = new Menu(tr(category.labelKey()));
                // The category's own glyph is its first member's — "Java" gets the Java icon, and so on.
                submenu.setGraphic(FileIcons.forFileName(category.types().get(0).suggestedFileName()));
                for (com.editora.template.NewFileType type : category.types()) {
                    submenu.getItems().add(typeItem(type, dirItem, newFile));
                }
                newMenu.getItems().add(submenu);
            }
        }
        if (newFromTemplate != null || newMavenProject != null) {
            newMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        if (newFromTemplate != null) {
            MenuItem fromTemplate = new MenuItem(tr("project.menu.newFromTemplate"));
            fromTemplate.setGraphic(Icons.template());
            fromTemplate.setOnAction(e -> newFromTemplate.accept(dir));
            newMenu.getItems().add(fromTemplate);
        }
        if (newMavenProject != null) {
            MenuItem maven = new MenuItem(tr("project.menu.newMavenProject"));
            maven.setGraphic(Icons.newFolder());
            maven.setOnAction(e -> newMavenProject.accept(dir));
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
        String literal = type.label(); // null unless type.hasLiteralLabel()
        return literal != null ? literal : tr(type.labelKey());
    }

    /** One "New ▸" entry: the type's label and the glyph the file will carry once it exists. */
    private MenuItem typeItem(
            com.editora.template.NewFileType type,
            TreeItem<Path> dirItem,
            BiConsumer<Path, com.editora.template.NewFileType> onNewFile) {
        MenuItem item = new MenuItem(labelFor(type));
        item.setGraphic(
                type.extension().isEmpty() && type.suggestedFileName().isEmpty()
                        ? Icons.newFile()
                        : FileIcons.forFileName(type.suggestedFileName()));
        item.setOnAction(e -> {
            dirItem.setExpanded(true); // reveal where the new file will land
            if (dirItem instanceof MapRowItem) {
                mapNewFileFolder = dirItem.getValue(); // the file it opens is revealed on the Map (setActiveFile)
                mapNewFileAt = System.currentTimeMillis();
                revealCreatedOnMap(dirItem, dirItem.getValue());
            }
            onNewFile.accept(dirItem.getValue(), type);
        });
        return item;
    }

    /** Prompts for a name and creates a new subfolder inside {@code dirItem}'s directory. */
    private void newFolder(TreeItem<Path> dirItem) {
        OverlayInput.Prompt prompt = activePrompt();
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
            markLocalChange(List.of(target)); // tree re-listed below; the watcher skips its echo
            dirItem.setExpanded(true); // reveal the new folder under its parent
            refreshAfterChange();
            revealCreatedOnMap(dirItem, target);
        });
    }

    private void renameItem(TreeItem<Path> item) {
        OverlayInput.Prompt prompt = activePrompt();
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
                    if (target.equals(path)) {
                        return; // the same name: nothing to do
                    }
                    if (Files.exists(target) && !caseOnlyRename(path, target)) {
                        showError(tr("project.newFolderExists", name)); // taken: say so, do not just close
                        return;
                    }
                    renameParticipant.willRename(path, target, ok -> {
                        if (ok) {
                            move(path, target); // says why when the file is gone or the name taken by now
                        }
                    });
                });
    }

    /**
     * Whether renaming {@code path} to {@code target} only changes the case of its name on a file system that
     * ignores case (macOS, Windows): {@code target} then "exists" — it is the file itself.
     */
    static boolean caseOnlyRename(Path path, Path target) {
        Path from = path.getFileName();
        Path to = target.getFileName();
        if (from == null || to == null || from.toString().equals(to.toString())) {
            return false;
        }
        if (!from.toString().equalsIgnoreCase(to.toString())) {
            return false;
        }
        try {
            return Files.isSameFile(path, target);
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    /**
     * Renames {@code path} to {@code target}. A case-only rename goes through a temporary name in the same
     * folder: on a case-insensitive file system a direct move to the other case is a no-op or "already exists".
     */
    static void moveRenaming(Path path, Path target) throws IOException {
        if (!caseOnlyRename(path, target)) {
            Files.move(path, target);
            return;
        }
        Path step = path.resolveSibling(path.getFileName() + ".editora-rename-" + System.nanoTime());
        Files.move(path, step);
        try {
            Files.move(step, target);
        } catch (IOException ex) {
            Files.move(step, path); // put it back under the name it had
            throw ex;
        }
    }

    private void move(Path path, Path target) {
        try {
            moveRenaming(path, target);
        } catch (IOException ex) {
            showError(tr("project.renameError", path.getFileName(), ex.getMessage()));
            return;
        }
        markLocalChange(List.of(path, target)); // tree re-listed below; the watcher skips its echo
        mapView.pathRenamed(path, target); // a renamed open folder stays open in the Map
        refreshAfterChange();
        onFileRenamed.accept(path, target);
    }

    private void deleteItem(TreeItem<Path> item) {
        Path path = item.getValue();
        if (!confirmDelete(List.of(path))) {
            return;
        }
        deleteConfirmed(List.of(path));
    }

    /**
     * The delete confirmation. It says which of two different things is about to happen: the files go to the
     * operating system's trash (restorable — OK is the default), or they are deleted for good, where the
     * trash cannot take them (not restorable — Cancel is the default, so Enter alone deletes nothing).
     */
    private boolean confirmDelete(List<Path> files) {
        int trashed = trashCount(files, deleteOperations);
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION, deleteQuestion(files, trashed), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(dialogOwner());
        confirm.setTitle(tr("project.deleteFileTitle"));
        confirm.setHeaderText(null);
        if (trashed < files.size()) {
            cancelByDefault(confirm);
        }
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** How many of {@code files} a delete would move to the trash rather than delete for good. */
    static int trashCount(List<Path> files, DeleteOperations operations) {
        int count = 0;
        for (Path file : files) {
            if (operations.movesToTrash(file)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The text of the delete confirmation for {@code files}, {@code trashed} of which go to the trash:
     * "Move … to the trash?" when all do, the long-standing "Delete …? This cannot be undone." when none do,
     * and a sentence giving both counts when the selection is mixed. Package-visible for tests.
     */
    static String deleteQuestion(List<Path> files, int trashed) {
        int total = files.size();
        if (trashed >= total) {
            return total == 1
                    ? tr("project.trashFileBody", files.get(0).getFileName())
                    : tr("project.trashMultiBody", total);
        }
        if (trashed <= 0) {
            return total == 1
                    ? tr("project.deleteFileBody", files.get(0).getFileName())
                    : tr("project.deleteMultiBody", total);
        }
        return tr("project.deleteMixedBody", total, trashed, total - trashed);
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

    /** One completed move, kept so the last drag-move can be taken back. */
    record Move(Path from, Path to) {}

    /** The moves of the most recent drag-move, in the order they were made; empty when there is none. */
    private List<Move> lastMove = List.of();

    /**
     * Whether a drag-move of {@code sources} asks first: a folder (its whole subtree is relocated, which in a
     * build tree silently breaks packages and imports) or more than one item. A single file moves at once,
     * as before. Pure over the file system — package-visible for tests.
     */
    static boolean moveNeedsConfirmation(List<Path> sources) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        return sources.size() > 1 || Files.isDirectory(sources.get(0));
    }

    /** The question asked before a drag-move that {@link #moveNeedsConfirmation needs one}. */
    static String moveQuestion(List<Path> sources, Path targetDir) {
        Object target = targetDir.getFileName() == null ? targetDir : targetDir.getFileName();
        return sources.size() == 1
                ? tr("project.moveFolderBody", sources.get(0).getFileName(), target)
                : tr("project.moveMultiBody", sources.size(), target);
    }

    /** Moves each of {@code sources} into {@code targetDir} (drag-and-drop). Skips no-ops, name conflicts,
     *  and invalid moves (into itself); reports how many moved. A folder or a multi-selection is confirmed
     *  first — a drag that ends on a folder row is easy to make by accident. Notifies {@code onFileRenamed}
     *  per moved path so open buffers (a file, or files under a moved folder) follow, and remembers the moves
     *  so {@link #undoLastMove()} can take them back. */
    private void moveInto(List<Path> dropped, @Nullable Path targetDir) {
        List<Path> sources = pruneNestedSources(dropped);
        if (sources.isEmpty() || targetDir == null || !Files.isDirectory(targetDir)) {
            return;
        }
        if (moving) {
            // one drag at a time: a second drop during the first's jdtls answer shared its state, and lost a move
            // that it reported as cancelled (seventh review BR4)
            onStatus.accept(tr("project.moveInProgress"));
            return;
        }
        int skipped = 0;
        List<Path> movable = new ArrayList<>();
        for (Path src : sources) {
            // a file/folder of that name already there is never clobbered
            if (!canMoveInto(src, targetDir) || Files.exists(targetDir.resolve(src.getFileName()))) {
                skipped++;
            } else {
                movable.add(src);
            }
        }
        if (moveNeedsConfirmation(movable) && !confirmQuestion.test(moveQuestion(movable, targetDir))) {
            onStatus.accept(tr("project.moveCancelled"));
            return;
        }
        List<Move> planned = new ArrayList<>();
        for (Path src : movable) {
            planned.add(new Move(src, targetDir.resolve(src.getFileName())));
        }
        int alreadySkipped = skipped;
        refused[0] = 0;
        editFailed[0] = 0;
        moving = true;
        renameParticipant.beginMove(javaItems(planned)); // the question is about the Java items only (BV3)
        moveThrough(planned, 0, new ArrayList<>(), new int[] {0}, (done, failed) -> {
            int moved = done.size();
            if (moved > 0) {
                lastMove = List.copyOf(done); // each move was recorded as a local change when it was made
                refreshAfterChange();
            }
            int notMoved = alreadySkipped + failed - refused[0];
            String status = editFailed[0] > 0
                    // the Java server's edit no longer applied: a failure, not the user's No (seventh review BR4)
                    ? tr("project.moveEditFailed", moved, editFailed[0])
                    : moved == 0 && refused[0] > 0 && notMoved == 0
                            ? tr("project.moveCancelled") // "No" to moving without edits is no conflict (BV3)
                            : notMoved == 0 && refused[0] > 0
                                    // some moved, the rest kept by that "No": said, not left out (sixth review BV3)
                                    ? tr("project.movedSomeKept", moved, targetDir.getFileName(), refused[0])
                                    : notMoved == 0
                                            ? tr("project.moved", moved, targetDir.getFileName())
                                            : tr("project.movedSome", moved, notMoved);
            onStatus.accept(status);
            moving = false;
            renameParticipant.endMove(false, status);
        });
    }

    /**
     * Moves {@code moves[i…]} one after another, each through the rename participant first: a {@code .java} file
     * or package folder moved by drag-and-drop is a Move refactoring, its package line and the imports of its
     * users edited by jdtls, as a tree rename is (Java review 3, BT1). The next move waits for the last one's
     * edits, which must land before jdtls is asked again. {@code finish} gets the moves made and the count that
     * failed or were refused.
     */
    private void moveThrough(
            List<Move> moves,
            int i,
            List<Move> done,
            int[] failed,
            java.util.function.BiConsumer<List<Move>, Integer> finish) {
        if (i >= moves.size()) {
            finish.accept(done, failed[0]);
            return;
        }
        Move m = moves.get(i);
        renameParticipant.willRename(m.from(), m.to(), ok -> {
            if (!ok) {
                failed[0]++;
                if (renameParticipant.refusedByUser()) {
                    refused[0]++;
                } else {
                    editFailed[0]++;
                }
            } else {
                try {
                    Files.move(m.from(), m.to());
                    markLocalChange(List.of(m.from(), m.to()));
                    mapView.pathRenamed(m.from(), m.to());
                    onFileRenamed.accept(m.from(), m.to()); // the open buffer(s) of a moved file / under a moved dir
                    done.add(m);
                } catch (IOException ex) {
                    showError(tr("project.moveError", m.from().getFileName(), ex.getMessage()));
                    failed[0]++;
                }
            }
            // On the next pulse: the participant records what this move did once this callback returns, and the
            // last move's finish ends the drag. Run inline, the drag ended before its last file's edit was
            // recorded, and undoing it left that file in the wrong package (fifth review BV1).
            javafx.application.Platform.runLater(() -> moveThrough(moves, i + 1, done, failed, finish));
        });
    }

    /** The moves of the drag under way that the participant refused (the user said not to move without edits). */
    private final int[] refused = {0};

    /** The moves of the drag under way whose Java edit failed to apply: not the user's choice (BR4). */
    private final int[] editFailed = {0};

    /** Whether a drag-move or its Undo Move is under way; another drop is refused until it ends (BR4). */
    private boolean moving;

    /**
     * The Java items among {@code moves}, which the participant may ask about: {@code .java} files, and folders with
     * Java in them (a folder of text files is none, eighth review BV3).
     */
    static int javaItems(List<Move> moves) {
        return (int) moves.stream()
                .filter(m -> m.from().toString().endsWith(".java") || com.editora.lsp.LspManager.mayHoldJava(m.from()))
                .count();
    }

    /** Whether there is a drag-move to take back (the tree menu offers "Undo Move" while there is). */
    boolean canUndoMove() {
        return !lastMove.isEmpty();
    }

    /**
     * Takes back the most recent drag-move: every item that is still where the move put it, and whose old
     * place is still free, goes back there (never onto something that has appeared since). Open buffers
     * follow through {@code onFileRenamed}, as they did on the way out. One step: after it there is nothing
     * to undo until the next move.
     */
    void undoLastMove() {
        if (moving) {
            onStatus.accept(tr("project.undoMoveInProgress")); // not "drop again": this was Undo Move (BQ5)
            return;
        }
        List<Move> moves = lastMove;
        lastMove = List.of();
        if (moves.isEmpty()) {
            return;
        }
        int stuck = 0;
        List<Move> back = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0; i--) {
            Move move = moves.get(i);
            Path parent = move.from().getParent();
            if (!Files.exists(move.to(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(move.from(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || parent == null
                    || !Files.isDirectory(parent)) {
                stuck++;
            } else {
                back.add(new Move(move.to(), move.from())); // moved back as moved: jdtls edits the packages back
            }
        }
        int alreadyStuck = stuck;
        refused[0] = 0;
        editFailed[0] = 0;
        moving = true;
        renameParticipant.beginMove(javaItems(back));
        moveThrough(back, 0, new ArrayList<>(), new int[] {0}, (done, failed) -> {
            if (!done.isEmpty()) {
                refreshAfterChange(); // each move back was recorded as a local change when it was made
            }
            // a "No" to moving back without edits is the user's choice, not a conflict (sixth review BV3)
            int notBack = alreadyStuck + failed - refused[0];
            String status = editFailed[0] > 0
                    ? tr("project.moveEditFailed", done.size(), editFailed[0])
                    : notBack == 0 && refused[0] > 0
                            ? tr("project.moveUndoneKept", done.size(), refused[0])
                            : notBack == 0
                                    ? tr("project.moveUndone", done.size())
                                    : tr("project.moveUndoneSome", done.size(), notBack);
            onStatus.accept(status);
            moving = false;
            renameParticipant.endMove(true, status);
        });
    }

    /** Undo Last Refactoring took the last drag-move back: there is nothing left for Undo Move (fourth review BF1). */
    void forgetLastMove() {
        lastMove = List.of();
    }

    /** The files to act on for a cell action: the whole multi-selection when {@code clicked} is part of it,
     *  else just {@code clicked}. Excludes the root. */
    private List<TreeItem<Path>> actionTargets(@Nullable TreeItem<Path> clicked) {
        List<TreeItem<Path>> sel = new ArrayList<>(tree.getSelectionModel().getSelectedItems());
        sel.removeIf(i -> i == null || i.getValue() == null || i.getValue().equals(root));
        if (clicked != null && sel.contains(clicked) && sel.size() > 1) {
            return sel;
        }
        return clicked == null ? List.of() : List.of(clicked);
    }

    /** Deletes the selected files (or just {@code clicked}); confirms once. Folders/root are skipped (delete
     *  is files-only, like the single-file menu). */
    private void deleteSelected(@Nullable TreeItem<Path> clicked) {
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
        if (!confirmDelete(files)) {
            return;
        }
        deleteConfirmed(files);
    }

    /** Runs the post-confirmation delete transaction: preflight every file, verify every captured preimage,
     *  then perform the filesystem deletes. Package-visible completion lets integration tests await the same
     *  asynchronous boundary the UI uses without timing sleeps. */
    CompletableFuture<DeleteResult> deleteConfirmed(List<Path> requested) {
        CompletableFuture<DeleteResult> completion = new CompletableFuture<>();
        List<Path> files = requested == null
                ? List.of()
                : requested.stream().filter(Objects::nonNull).distinct().toList();
        if (files.isEmpty()) {
            completion.complete(new DeleteResult(true, 0, List.of()));
            return completion;
        }
        try {
            deletePreparation.prepare(files, approval -> onFx(() -> finishDelete(files, approval, completion)));
        } catch (RuntimeException failure) {
            completion.complete(new DeleteResult(false, 0, files));
        }
        return completion;
    }

    private void finishDelete(List<Path> files, DeleteApproval approval, CompletableFuture<DeleteResult> completion) {
        if (completion.isDone()) {
            return;
        }
        if (approval == null || !approval.permitted()) {
            completion.complete(new DeleteResult(false, 0, files));
            return;
        }
        for (Map.Entry<Path, byte[]> expected : approval.expectedBytes().entrySet()) {
            try {
                if (!java.util.Arrays.equals(expected.getValue(), deleteOperations.readAllBytes(expected.getKey()))) {
                    onStatus.accept(
                            tr("project.deleteChanged", expected.getKey().getFileName()));
                    completion.complete(new DeleteResult(false, 0, files));
                    return;
                }
            } catch (IOException | RuntimeException changedOrMissing) {
                onStatus.accept(tr("project.deleteChanged", expected.getKey().getFileName()));
                completion.complete(new DeleteResult(false, 0, files));
                return;
            }
        }

        int deleted = 0;
        int trashed = 0;
        List<Path> failed = new ArrayList<>();
        List<Path> refusedByTrash = new ArrayList<>();
        String trashRefusal = null;
        for (Path file : files) {
            boolean toTrash = deleteOperations.movesToTrash(file);
            try {
                if (toTrash) {
                    deleteOperations.moveToTrash(file);
                    trashed++;
                } else {
                    deleteOperations.delete(file);
                }
            } catch (IOException | RuntimeException failure) {
                if (toTrash) {
                    // The user was told "to the trash". Deleting for good instead is a different act: ask.
                    refusedByTrash.add(file);
                    trashRefusal = trashRefusal == null ? String.valueOf(failure.getMessage()) : trashRefusal;
                } else {
                    failed.add(file);
                    showError(tr("project.deleteError", file.getFileName(), failure.getMessage()));
                }
                continue;
            }
            onFileDeleted.accept(file);
            deleted++;
        }
        if (!refusedByTrash.isEmpty()) {
            String question = refusedByTrash.size() == 1
                    ? tr("project.trashFailedBody", refusedByTrash.get(0).getFileName(), trashRefusal)
                    : tr("project.trashFailedMultiBody", refusedByTrash.size(), trashRefusal);
            boolean permanently = confirmQuestion.test(question);
            for (Path file : refusedByTrash) {
                if (!permanently) {
                    failed.add(file); // kept, by the user's choice
                    continue;
                }
                try {
                    deleteOperations.delete(file);
                } catch (IOException | RuntimeException failure) {
                    failed.add(file);
                    showError(tr("project.deleteError", file.getFileName(), failure.getMessage()));
                    continue;
                }
                onFileDeleted.accept(file);
                deleted++;
            }
        }
        if (deleted > 0) {
            markLocalChange(files);
            refreshAfterChange();
        }
        if (trashed == 1) {
            onStatus.accept(tr("project.trashedOne", firstTrashedName(files, failed, refusedByTrash)));
        } else if (trashed > 1) {
            onStatus.accept(tr("project.trashedMany", trashed));
        }
        completion.complete(new DeleteResult(true, deleted, failed));
    }

    /** The name of the one file that went to the trash (for the status line). */
    private String firstTrashedName(List<Path> files, List<Path> failed, List<Path> refusedByTrash) {
        for (Path file : files) {
            if (!failed.contains(file) && !refusedByTrash.contains(file) && !Files.exists(file)) {
                return String.valueOf(file.getFileName());
            }
        }
        return String.valueOf(files.get(0).getFileName());
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * Records the paths an in-app filesystem change touched (a renamed or moved item at both ends, a deleted
     * one, a created folder), so the watcher's echo of that change does not refresh again for a short window.
     * Only events at or under these paths are skipped: an outside change in the same window still lands.
     */
    private void markLocalChange(java.util.Collection<Path> paths) {
        long now = localWriteClockForTest.getAsLong();
        localChanges.values().removeIf(at -> now - at >= localWriteWindowMs);
        for (Path path : paths) {
            if (path != null) {
                localChanges.put(path.toAbsolutePath().normalize(), now);
            }
        }
    }

    private boolean changedLocally(Path path) {
        if (localChanges.isEmpty()) {
            return false;
        }
        long now = localWriteClockForTest.getAsLong();
        Path normalized = path.toAbsolutePath().normalize();
        for (java.util.Map.Entry<Path, Long> change : localChanges.entrySet()) {
            if (now - change.getValue() < localWriteWindowMs && normalized.startsWith(change.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** Shows a modal error dialog when a filesystem operation (rename/delete) fails. */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(dialogOwner());
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
            if (isMapShowing()) {
                mapView.refresh(); // refreshTree would have, but a filtered tree is re-searched instead
            }
            rebuildBody();
        } else {
            refreshTree();
        }
        if (isMapShowing() && !mapFilterField.getText().isBlank()) {
            // Re-running the search only reloads the Map when the matches changed; the refresh above covers a
            // deleted or renamed row that was not a match.
            searchMap();
        }
    }

    // --- lazy tree node ---

    /** Lazily-populated tree node: lists its directory the first time its children are requested. */
    private final class PathItem extends TreeItem<Path> {
        private boolean loaded;
        private final boolean directory;
        private final boolean showHidden;
        private boolean childrenReady;
        private long loadGeneration;

        PathItem(Path path, boolean showHidden, boolean directory) {
            this(path, showHidden, directory, false);
        }

        PathItem(Path path, boolean showHidden, boolean directory, boolean loaded) {
            super(path);
            this.showHidden = showHidden;
            this.directory = directory;
            this.loaded = loaded;
            this.childrenReady = loaded || !directory;
        }

        @Override
        public boolean isLeaf() {
            return !directory;
        }

        @Override
        public ObservableList<TreeItem<Path>> getChildren() {
            if (!loaded) {
                loaded = true;
                if (directory) {
                    childrenReady = false;
                    long generation = ++loadGeneration;
                    long treeGeneration = searchGen.get();
                    Path parent = getValue();
                    submitUnlessDisposed(listExecutor, () -> {
                        Runnable hook = beforeDirectoryListForTest;
                        if (hook != null) {
                            hook.run();
                        }
                        List<PathEntry> entries = listDir(parent, showHidden);
                        Platform.runLater(() -> {
                            if (disposed
                                    || !loaded
                                    || generation != loadGeneration
                                    || treeGeneration != searchGen.get()) {
                                return;
                            }
                            if (entries.isEmpty() && RemoteReadFailure.connectionClosed(parent)) {
                                onStatus.accept(RemoteReadFailure.unreadable(parent)); // not "an empty folder"
                            }
                            List<TreeItem<Path>> kids = new ArrayList<>(entries.size());
                            for (PathEntry child : entries) {
                                kids.add(new PathItem(child.path(), showHidden, child.directory()));
                            }
                            PathItem.super.getChildren().setAll(kids);
                            childrenReady = true;
                            restorePendingTreeSelection();
                            if (tree.getRoot() instanceof PathItem currentRoot) {
                                continuePendingReveal(currentRoot);
                            }
                            syncWatches();
                        });
                    });
                }
            }
            return super.getChildren();
        }

        /**
         * Lists this directory again off the FX thread and reconciles the rows with the result: rows for
         * entries that are still there are kept (with their expansion, their loaded children and their place
         * in the selection), and only the differences are inserted or removed. A no-op while the first
         * listing is still in flight — that one is as fresh as this would be.
         */
        void refresh() {
            if (!directory || !loaded || !childrenReady || filtering) {
                return;
            }
            long generation = ++loadGeneration;
            long treeGeneration = searchGen.get();
            Path parent = getValue();
            directoryListCountForTest++;
            submitUnlessDisposed(listExecutor, () -> {
                Runnable hook = beforeDirectoryListForTest;
                if (hook != null) {
                    hook.run();
                }
                List<PathEntry> entries = listDir(parent, showHidden);
                Platform.runLater(() -> {
                    if (disposed || !loaded || generation != loadGeneration || treeGeneration != searchGen.get()) {
                        return;
                    }
                    if (entries.isEmpty() && RemoteReadFailure.connectionClosed(parent)) {
                        onStatus.accept(RemoteReadFailure.unreadable(parent)); // not "an empty folder"
                    }
                    TreeItem<Path> selectedItem = tree.getSelectionModel().getSelectedItem();
                    Path selected = selectedItem == null ? null : selectedItem.getValue();
                    boolean changed = ChildReconciler.reconcile(
                            PathItem.super.getChildren(),
                            entries,
                            item -> new PathEntry(item.getValue(), !item.isLeaf()),
                            entry -> new PathItem(entry.path(), showHidden, entry.directory()));
                    if (!changed) {
                        return;
                    }
                    TreeItem<Path> now = tree.getSelectionModel().getSelectedItem();
                    if (selected != null && (now == null || !selected.equals(now.getValue()))) {
                        pendingTreeSelection = selected; // a wholesale replacement dropped it: put it back
                        restorePendingTreeSelection();
                        pendingTreeSelection = null;
                    }
                    syncWatches();
                });
            });
        }
    }

    private void restorePendingTreeSelection() {
        Path selected = pendingTreeSelection;
        TreeItem<Path> currentRoot = tree.getRoot();
        if (selected == null || currentRoot == null) {
            return;
        }
        TreeItem<Path> found = findVisible(currentRoot, selected);
        if (found != null) {
            tree.getSelectionModel().select(found);
            pendingTreeSelection = null;
        }
    }

    private record PathEntry(Path path, boolean directory) {}

    /** Directory children: directories first then files, case-insensitive; dotfiles hidden unless
     *  {@code includeHidden}; empty on error. */
    private static List<PathEntry> listDir(Path dir, boolean includeHidden) {
        List<PathEntry> dirs = new ArrayList<>();
        List<PathEntry> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                if (!includeHidden && p.getFileName().toString().startsWith(".")) {
                    return;
                }
                boolean directory = Files.isDirectory(p);
                (directory ? dirs : files).add(new PathEntry(p, directory));
            });
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
        // The directory flag was read once, above; the comparator must not stat again per comparison.
        java.util.Comparator<PathEntry> order = ProjectPathOrder.directoriesFirst(
                PathEntry::directory, e -> e.path().getFileName().toString());
        dirs.sort(order);
        files.sort(order);
        List<PathEntry> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        return all;
    }

    /** All mutually-exclusive style classes a tree cell may carry (cleared before re-adding on each render). */
    private static final String[] CELL_CLASSES = {
        "folder-cell",
        "file-cell",
        "modified-file",
        "project-open-file",
        "project-current-open-file",
        "git-status-added",
        "git-status-modified",
        "git-status-deleted",
        "git-status-renamed",
        "git-status-untracked",
        "git-status-conflict",
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
                    // After the drop event: the move may ask a question, and a modal dialog opened from
                    // inside a drag-and-drop callback can leave the platform's drag session hanging.
                    List<Path> dropped = new ArrayList<>(draggedPaths);
                    Platform.runLater(() -> moveInto(dropped, target));
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
        private @Nullable Path dropTargetDir() {
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
                setAccessibleText(null);
                setContextMenu(null);
                setTooltip(null);
                getStyleClass().removeAll(CELL_CLASSES);
                return;
            }
            // Reuse the lazy tree node's cached leaf flag (avoids a Files.isDirectory stat per cell
            // render); filtered flat rows are plain TreeItems, so fall back to a stat for those.
            boolean isDir = getTreeItem() instanceof PathItem pi ? !pi.isLeaf() : Files.isDirectory(item);
            // An open file with unsaved changes: mark it like a dirty tab ("• " + amber italic).
            boolean dirty = !isDir && isModified != null && isModified.test(item);
            Path absolute = item.toAbsolutePath().normalize();
            boolean open = !isDir && ProjectPanel.this.isOpen.test(absolute);
            boolean active =
                    !isDir && open && activeFile != null && com.editora.config.PathKeys.samePath(absolute, activeFile);
            // Mark the cell so the stylesheet can theme the folder vs. file icon color.
            getStyleClass().removeAll(CELL_CLASSES);
            getStyleClass().add(isDir ? "folder-cell" : "file-cell");
            // The file's Git status (null for a clean/dirty file, a folder, or Git off) — drives both the
            // color class and the single-letter label prefix, matching the Commit tool window.
            com.editora.git.GitFileStatus fileStatus = null;
            if (dirty) {
                getStyleClass().add("modified-file"); // unsaved-in-editor takes precedence over the Git color
            } else if (active) {
                getStyleClass().add("project-current-open-file");
            } else if (open) {
                getStyleClass().add("project-open-file");
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
            setText(null);
            setAccessibleText(label);
            setContextMenu(null); // built lazily in setOnContextMenuRequested (see the PathCell constructor)
            setTooltip(null);
            Path fileName = item.getFileName();
            // Box the folder glyph in the same fixed icon column as the (already-boxed) file glyphs, so
            // folder and file rows share one icon width and every label starts at the same x.
            Node glyph = FileIcons.forProjectItem(fileName == null ? label : fileName.toString(), isDir);
            Node base = FileIcons.withStatusLetter(glyph, fileStatus == null ? null : fileStatus.letter());
            HBox graphic = new HBox(3, base);
            graphic.setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(label);
            name.getStyleClass().add("project-file-name");
            graphic.getChildren().add(name);
            if (active || open) {
                Label openMarker = new Label(active ? "◉" : "◌");
                openMarker.getStyleClass().add("project-open-indicator");
                openMarker.setMouseTransparent(true);
                graphic.getChildren().add(openMarker);
            }
            if (markerActions != null) {
                if (markerActions.hasBookmarks(item)) {
                    graphic.getChildren().add(smallMarker(Icons.bookmark(), "project-bookmark-indicator"));
                }
                if (markerActions.personalNotesEnabled() && markerActions.hasPersonalNotes(item)) {
                    graphic.getChildren().add(smallMarker(Icons.notes(), "project-note-indicator"));
                    if (isDir) {
                        String text = markerActions.personalNotesTooltip(item);
                        if (text != null && !text.isBlank()) {
                            Tooltip notes = PersonalNoteTooltip.create(text);
                            notes.setWrapText(true);
                            notes.setMaxWidth(420);
                            setTooltip(notes);
                        }
                    }
                }
            }
            setGraphic(graphic);
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
        // Read once: the actions below run later and use what was checked here.
        FileActions fileOps = fileActions;
        MarkerActions markerOps = markerActions;
        BiConsumer<Path, Boolean> revealer = onReveal;
        BiConsumer<Path, Boolean> terminalOpener = onOpenTerminal;
        ContextMenu menu = new ContextMenu();
        // Read per right-click (the menu is built for each one), so a Simple-mode toggle applies to the next.
        boolean simple = simpleMode.getAsBoolean();
        if (isDir) {
            menu.getItems().add(newMenu(treeItem, simple));
        }
        // Offered on a folder holding a pom and on a pom.xml row itself — the same submenu the editor's
        // right-click on that file shows. Built per right-click rather than once: a folder gains or loses
        // its pom.xml while the tree is open, and mavenMenuFor owns the whole "is there anything here?"
        // rule (it answers null for a folder with no pom, and for any file that is not a pom.xml).
        javafx.scene.control.Menu maven = simple ? null : mavenMenuFor(treeItem.getValue());
        if (maven != null) {
            menu.getItems().add(maven);
        }
        // Rename is offered on every file/folder EXCEPT the project root — renaming that would move the
        // whole project folder on disk and leave the project pointing at a path that no longer exists.
        if (!isRoot) {
            MenuItem rename = new MenuItem(tr("project.menu.rename"));
            rename.setGraphic(Icons.edit());
            rename.setOnAction(e -> renameItem(treeItem));
            rename.getProperties().put(STAYS_IN_MAP, Boolean.TRUE);
            menu.getItems().add(rename);
        }
        if (canUndoMove()) {
            MenuItem undoMove = new MenuItem(tr("project.menu.undoMove"));
            undoMove.setGraphic(Icons.undo());
            undoMove.setOnAction(e -> undoLastMove());
            undoMove.getProperties().put(STAYS_IN_MAP, Boolean.TRUE);
            menu.getItems().add(undoMove);
        }
        if (!isDir) {
            MenuItem delete = new MenuItem(tr("project.menu.delete"));
            delete.setGraphic(Icons.trash());
            // Deletes the whole multi-file selection when this row is part of it, else just this file
            // (the confirm dialog shows the count).
            delete.setOnAction(e -> deleteSelected(treeItem));
            delete.getProperties().put(STAYS_IN_MAP, Boolean.TRUE);
            menu.getItems().add(delete);
            Path target = treeItem.getValue();
            if (fileOps != null
                    && target != null
                    && com.editora.text.EncryptedNames.extensionOf(String.valueOf(target.getFileName())) == null) {
                MenuItem encrypt = new MenuItem(tr("project.menu.encrypt"));
                encrypt.setGraphic(Icons.lock());
                encrypt.setOnAction(e -> fileOps.encrypt(target));
                menu.getItems().add(encrypt);
            }
        }
        if (markerOps != null && !simple) {
            Path target = treeItem.getValue();
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

            MenuItem bookmark = new MenuItem(tr(isDir ? "project.menu.addFolderBookmark" : "project.menu.addBookmark"));
            bookmark.setGraphic(Icons.bookmark());
            bookmark.setOnAction(e -> markerOps.addBookmark(target));
            menu.getItems().add(bookmark);

            MenuItem note =
                    new MenuItem(tr(isDir ? "project.menu.addFolderPersonalNote" : "project.menu.addPersonalNote"));
            note.setGraphic(Icons.notes());
            note.setDisable(!markerOps.personalNotesEnabled());
            note.setOnAction(e -> markerOps.addPersonalNote(target));
            menu.getItems().add(note);
        }
        boolean terminalShown = terminalOpener != null && !simple;
        if (revealer != null || terminalShown) {
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        if (revealer != null) {
            MenuItem reveal = new MenuItem(tr("project.menu.revealInFileManager"));
            reveal.setGraphic(Icons.revealInFiles());
            reveal.setOnAction(e -> revealer.accept(treeItem.getValue(), isDir));
            reveal.getProperties().put(STAYS_IN_MAP, Boolean.TRUE);
            menu.getItems().add(reveal);
        }
        if (terminalOpener != null && terminalShown) {
            MenuItem terminal = new MenuItem(tr("project.menu.openTerminal"));
            terminal.setGraphic(Icons.terminal());
            terminal.setOnAction(e -> terminalOpener.accept(treeItem.getValue(), isDir));
            menu.getItems().add(terminal);
        }
        // Local History + Git act on a concrete file (not a folder).
        if (fileOps != null && !isDir && !simple) {
            Path file = treeItem.getValue();
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

            MenuItem localHistory = new MenuItem(tr("project.menu.localHistory"));
            localHistory.setGraphic(Icons.history());
            localHistory.setOnAction(e -> fileOps.showLocalHistory(file));
            menu.getItems().add(localHistory);

            Menu git = new Menu(tr("project.menu.git"));
            git.setGraphic(Icons.git());
            MenuItem stage = new MenuItem(tr("project.menu.git.stage"));
            stage.setGraphic(Icons.stageAll());
            stage.setOnAction(e -> fileOps.gitStage(file));
            MenuItem unstage = new MenuItem(tr("project.menu.git.unstage"));
            unstage.setGraphic(Icons.remove());
            unstage.setOnAction(e -> fileOps.gitUnstage(file));
            MenuItem revert = new MenuItem(tr("project.menu.git.revert"));
            revert.setGraphic(Icons.undo());
            revert.setOnAction(e -> fileOps.gitRevert(file));
            MenuItem ignore = new MenuItem(tr("project.menu.git.addToGitignore"));
            ignore.setGraphic(Icons.git());
            ignore.setOnAction(e -> fileOps.gitAddToGitignore(file));
            MenuItem compareHead = new MenuItem(tr("project.menu.git.compareHead"));
            compareHead.setGraphic(Icons.diff());
            compareHead.setOnAction(e -> fileOps.gitCompareWithHead(file));
            MenuItem compareBranch = new MenuItem(tr("project.menu.git.compareBranch"));
            compareBranch.setGraphic(Icons.diff());
            compareBranch.setOnAction(e -> fileOps.gitCompareWithBranch(file));
            MenuItem compareRevision = new MenuItem(tr("project.menu.git.compareRevision"));
            compareRevision.setGraphic(Icons.diff());
            compareRevision.setOnAction(e -> fileOps.gitCompareWithRevision(file));
            MenuItem compareTag = new MenuItem(tr("project.menu.git.compareTag"));
            compareTag.setGraphic(Icons.diff());
            compareTag.setOnAction(e -> fileOps.gitCompareWithTag(file));
            MenuItem annotate = new MenuItem(tr("project.menu.git.annotate"));
            annotate.setGraphic(Icons.blame());
            annotate.setOnAction(e -> fileOps.gitAnnotate(file));
            MenuItem fileHistory = new MenuItem(tr("project.menu.git.fileHistory"));
            fileHistory.setGraphic(Icons.gitLog());
            fileHistory.setOnAction(e -> fileOps.gitShowFileHistory(file));
            git.getItems()
                    .addAll(
                            stage,
                            unstage,
                            revert,
                            ignore,
                            new javafx.scene.control.SeparatorMenuItem(),
                            compareHead,
                            compareBranch,
                            compareTag,
                            compareRevision,
                            annotate,
                            fileHistory);
            menu.getItems().add(git);

            // Disable to match the live feature toggles + the file's actual status.
            menu.setOnShowing(e -> {
                localHistory.setDisable(!fileOps.localHistoryEnabled());
                GitPathScope scope = fileOps.gitScope(file);
                git.setDisable(scope == GitPathScope.NONE); // grey out the Git submenu when there's no VCS
                com.editora.git.GitFileStatus st =
                        gitStatus.get(file.toAbsolutePath().normalize());
                // The tree's status map is the active repository's. For a file of another one it says
                // nothing, so the status-dependent items stay enabled and the action itself reports.
                boolean known = scope == GitPathScope.ACTIVE;
                revert.setDisable(known && st == null); // nothing to revert on a clean file
                ignore.setDisable(known && st != com.editora.git.GitFileStatus.UNTRACKED); // for new files
            });
        }
        // On a folder: Local History plus subtree-wide Git mutation and comparison actions.
        if (fileOps != null && isDir && !simple) {
            Path dir = treeItem.getValue();
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            MenuItem folderHistory = new MenuItem(tr("project.menu.localHistory"));
            folderHistory.setGraphic(Icons.history());
            folderHistory.setOnAction(e -> fileOps.showLocalHistory(dir));
            menu.getItems().add(folderHistory);

            Menu git = new Menu(tr("project.menu.git"));
            git.setGraphic(Icons.git());
            MenuItem stage = new MenuItem(tr("project.menu.git.stage"));
            stage.setGraphic(Icons.stageAll());
            stage.setOnAction(e -> fileOps.gitStage(dir));
            MenuItem revert = new MenuItem(tr("project.menu.git.revert"));
            revert.setGraphic(Icons.undo());
            revert.setOnAction(e -> fileOps.gitRevert(dir));
            MenuItem compareHead = new MenuItem(tr("project.menu.git.compareHead"));
            compareHead.setGraphic(Icons.diff());
            compareHead.setOnAction(e -> fileOps.gitCompareWithHead(dir));
            MenuItem compareBranch = new MenuItem(tr("project.menu.git.compareBranch"));
            compareBranch.setGraphic(Icons.diff());
            compareBranch.setOnAction(e -> fileOps.gitCompareWithBranch(dir));
            MenuItem compareTag = new MenuItem(tr("project.menu.git.compareTag"));
            compareTag.setGraphic(Icons.diff());
            compareTag.setOnAction(e -> fileOps.gitCompareWithTag(dir));
            MenuItem compareRevision = new MenuItem(tr("project.menu.git.compareRevision"));
            compareRevision.setGraphic(Icons.diff());
            compareRevision.setOnAction(e -> fileOps.gitCompareWithRevision(dir));
            git.getItems()
                    .addAll(
                            stage,
                            revert,
                            new javafx.scene.control.SeparatorMenuItem(),
                            compareHead,
                            compareBranch,
                            compareTag,
                            compareRevision);
            menu.getItems().add(git);

            menu.setOnShowing(e -> {
                folderHistory.setDisable(!fileOps.localHistoryEnabled());
                GitPathScope scope = fileOps.gitScope(dir);
                git.setDisable(scope == GitPathScope.NONE);
                revert.setDisable(scope == GitPathScope.ACTIVE
                        && !gitChangedDirs.contains(dir.toAbsolutePath().normalize()));
            });
        }
        return menu;
    }
}
