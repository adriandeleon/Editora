package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

import com.editora.config.Project;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The Project tool window: a project switcher (combobox incl. "No Project", + close button) and a
 * filter box over a lazy file tree rooted at the active project's folder. Typing in the filter runs a
 * bounded, debounced project-wide name search (dot-dirs skipped, capped) and shows matches as a flat
 * list; clearing it restores the lazy tree. Emacs-style keyboard nav (C-n/C-p, C-f/C-b, Enter) like
 * the Structure panel; Enter/double-click opens a file; a right-click menu renames/deletes files.
 */
public class ProjectPanel extends VBox implements ToolWindowContent {

    private static final int MAX_VISIT = 20_000;
    private static final int MAX_MATCHES = 300;
    private static final int MAX_DEPTH = 25;

    private final Consumer<Path> onOpenFile;
    private final Consumer<Project> onSwitchProject;
    private final Runnable onCloseProject;
    private final Runnable onDeleteProject;
    private final BiConsumer<Path, Path> onFileRenamed;
    private final Consumer<Path> onFileDeleted;
    private final java.util.function.Predicate<Path> isModified;

    private ProjectCombo projectCombo;
    private final Button closeButton = new Button();
    private final Button deleteButton = new Button();
    private final TextField filterField = new TextField();
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

    private Path root;
    private boolean filtering;
    private boolean loading;

    public ProjectPanel(Consumer<Path> onOpenFile, Consumer<Project> onSwitchProject,
                        Runnable onCloseProject, Runnable onDeleteProject,
                        BiConsumer<Path, Path> onFileRenamed, Consumer<Path> onFileDeleted,
                        java.util.function.Predicate<Path> isModified) {
        this.onOpenFile = onOpenFile;
        this.onSwitchProject = onSwitchProject;
        this.onCloseProject = onCloseProject;
        this.onDeleteProject = onDeleteProject;
        this.onFileRenamed = onFileRenamed;
        this.onFileDeleted = onFileDeleted;
        this.isModified = isModified;
        getStyleClass().add("project-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        buildHeader();
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

        Label placeholder = new Label(tr("project.placeholder"));
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholderPane = new StackPane(placeholder);
        placeholderPane.setAlignment(Pos.CENTER);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        setRoot(null);
    }

    private void buildHeader() {
        // Every combo selection is a switch — including "No Project" (id "" = return to the global
        // session without closing any project); the caller (switchToProject) handles the sentinel.
        projectCombo = new ProjectCombo(onSwitchProject);
        projectCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(projectCombo, Priority.ALWAYS);

        closeButton.setGraphic(Icons.closeSmall());
        closeButton.getStyleClass().addAll("button-icon", "flat");
        closeButton.setTooltip(new Tooltip(tr("project.closeTip")));
        closeButton.setOnAction(e -> onCloseProject.run());

        deleteButton.setGraphic(Icons.trash());
        deleteButton.getStyleClass().addAll("button-icon", "flat");
        deleteButton.setTooltip(new Tooltip(tr("project.deleteTip")));
        deleteButton.setOnAction(e -> onDeleteProject.run());

        HBox header = new HBox(4, projectCombo, deleteButton, closeButton);
        header.getStyleClass().add("project-header");
        header.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(header);
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
    }

    /** Populates the project switcher; {@code activeId} selects the current project (or "" for none). */
    public void setProjects(List<Project> all, String activeId) {
        projectCombo.setProjects(all, activeId);
        boolean noActive = activeId == null || activeId.isEmpty();
        closeButton.setDisable(noActive);
        deleteButton.setDisable(noActive);
    }

    /** Re-renders the visible tree cells so each file's modified marker/color reflects current state. */
    public void refreshModified() {
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
        rebuildBody();
    }

    /** Rebuilds the body: placeholder (no project), filtered flat results, or the lazy tree. */
    private void rebuildBody() {
        long gen = searchGen.incrementAndGet(); // invalidate any in-flight search
        if (root == null || !Files.isDirectory(root)) {
            getChildren().setAll(header(), placeholderPane);
            return;
        }
        String q = filterField.getText().trim();
        if (q.isEmpty()) {
            filtering = false;
            PathItem rootItem = new PathItem(root);
            rootItem.setExpanded(true);
            tree.setRoot(rootItem);
        } else {
            filtering = true;
            // Walk off the FX thread (up to MAX_VISIT entries); apply results back under the gen guard.
            Path searchRoot = root;
            TreeItem<Path> pending = new TreeItem<>(root);
            pending.setExpanded(true);
            tree.setRoot(pending);
            searchExecutor.submit(() -> {
                List<Path> matches = search(searchRoot, q);
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
        getChildren().setAll(header(), filterField, tree);
    }

    private HBox header() {
        return (HBox) getChildren().get(0);
    }

    /** Bounded project-wide filename search: dot-dirs skipped, capped on entries visited and matches. */
    private static List<Path> search(Path root, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            if (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) {
                                return FileVisitResult.SKIP_SUBTREE; // skip .git, etc.
                            }
                            return cap();
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            String name = file.getFileName().toString();
                            if (!name.startsWith(".") && name.toLowerCase(Locale.ROOT).contains(q)) {
                                matches.add(file);
                            }
                            return cap();
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return cap();
                        }

                        private FileVisitResult cap() {
                            return ++visited[0] > MAX_VISIT || matches.size() >= MAX_MATCHES
                                    ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException ex) {
            // Best effort — return whatever matched before the error.
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
                    case N -> { move(1); e.consume(); }
                    case P -> { move(-1); e.consume(); }
                    case F -> { expandOrDescend(); e.consume(); }
                    case B -> { collapseOrAscend(); e.consume(); }
                    case M -> { openSelected(); e.consume(); }
                    default -> { }
                }
            }
        }
    }

    @Override
    public void focusFirstItem() {
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
            tree.scrollTo(0);
        }
        tree.requestFocus();
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

    private void renameItem(TreeItem<Path> item) {
        Path path = item.getValue();
        TextInputDialog dialog = new TextInputDialog(path.getFileName().toString());
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle(tr("project.renameTitle"));
        dialog.setHeaderText(null);
        dialog.setContentText(tr("project.renameContent"));
        dialog.showAndWait().ifPresent(input -> {
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
            refreshAfterChange(item);
            onFileRenamed.accept(path, target);
        });
    }

    private void deleteItem(TreeItem<Path> item) {
        Path path = item.getValue();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                tr("project.deleteFileBody", path.getFileName()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle(tr("project.deleteFileTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            Files.delete(path);
        } catch (IOException ex) {
            showError(tr("project.deleteError", path.getFileName(), ex.getMessage()));
            return;
        }
        refreshAfterChange(item);
        onFileDeleted.accept(path);
    }

    /** Shows a modal error dialog when a filesystem operation (rename/delete) fails. */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.setTitle(tr("project.fileErrorTitle"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    /** Refreshes the view after a rename/delete: re-run the filter, or re-list the lazy parent. */
    private void refreshAfterChange(TreeItem<Path> item) {
        if (filtering) {
            rebuildBody();
        } else if (item.getParent() instanceof PathItem parent) {
            parent.reload();
        }
    }

    // --- lazy tree node ---

    /** Lazily-populated tree node: lists its directory the first time its children are requested. */
    private static final class PathItem extends TreeItem<Path> {
        private boolean loaded;
        private Boolean leaf;

        PathItem(Path path) {
            super(path);
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
                    for (Path child : listDir(getValue())) {
                        kids.add(new PathItem(child));
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

    /** Directory children: directories first then files, case-insensitive; dotfiles hidden; empty on error. */
    private static List<Path> listDir(Path dir) {
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                if (p.getFileName().toString().startsWith(".")) {
                    return;
                }
                (Files.isDirectory(p) ? dirs : files).add(p);
            });
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
        Comparator<Path> byName =
                Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
        dirs.sort(byName);
        files.sort(byName);
        List<Path> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        return all;
    }

    private final class PathCell extends TreeCell<Path> {
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                getStyleClass().removeAll("folder-cell", "file-cell", "modified-file");
                return;
            }
            // Reuse the lazy tree node's cached leaf flag (avoids a Files.isDirectory stat per cell
            // render); filtered flat rows are plain TreeItems, so fall back to a stat for those.
            boolean isDir = getTreeItem() instanceof PathItem pi ? !pi.isLeaf() : Files.isDirectory(item);
            // An open file with unsaved changes: mark it like a dirty tab ("• " + amber italic).
            boolean dirty = !isDir && isModified != null && isModified.test(item);
            // Mark the cell so the stylesheet can theme the folder vs. file icon color.
            getStyleClass().removeAll("folder-cell", "file-cell", "modified-file");
            getStyleClass().add(isDir ? "folder-cell" : "file-cell");
            if (dirty) {
                getStyleClass().add("modified-file");
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
            setGraphic(isDir ? Icons.project() : Icons.fileSheet());
            setContextMenu(isRoot ? null : contextMenuFor(getTreeItem(), isDir));
        }

        private ContextMenu contextMenuFor(TreeItem<Path> treeItem, boolean isDir) {
            MenuItem rename = new MenuItem(tr("project.menu.rename"));
            rename.setGraphic(Icons.edit());
            rename.setOnAction(e -> renameItem(treeItem));
            ContextMenu menu = new ContextMenu(rename);
            if (!isDir) {
                MenuItem delete = new MenuItem(tr("project.menu.delete"));
                delete.setGraphic(Icons.trash());
                delete.setOnAction(e -> deleteItem(treeItem));
                menu.getItems().add(delete);
            }
            return menu;
        }
    }
}
