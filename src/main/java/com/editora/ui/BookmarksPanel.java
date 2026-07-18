package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
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

import com.editora.config.Bookmark;

import static com.editora.i18n.Messages.tr;

/**
 * The Bookmarks tool window: every bookmark (across all files, open or closed) grouped by <b>project</b> then
 * by file, in a tree. It always shows the <b>General</b> (no-project) bucket and the <b>current</b> project's
 * bucket; a "Show all projects" toggle additionally reveals every other project's bookmarks, so nothing
 * appears/disappears when switching projects. Enter / double-click opens the file and jumps to the line; a
 * right-click menu edits the note or deletes bookmarks.
 *
 * <p>It reads the persisted bookmark map directly (so it includes files that aren't open) and routes mutations
 * back through {@link Actions}. Reordering (drag / Alt+Up-Down / menu) is confined to the current project's
 * group — the only editable bucket. Marks itself {@code editora.ownsKeys} for local Emacs navigation.
 */
public class BookmarksPanel extends VBox implements ToolWindowContent {

    /** The cross-project view the panel renders: every bucket, the current project key, and a key→name resolver. */
    public record Scope(
            Map<String, Map<String, List<Bookmark>>> byProject, String currentKey, Function<String, String> nameFor) {}

    /** Mutations the panel asks the controller to perform (it knows which files are open). */
    public interface Actions {
        void openAndJump(Path file, int line);

        void setNote(Path file, int line, String note);

        void delete(Path file, int line);

        void deleteAll(Path file);
        /** Reorder a bookmark within its file (indices into that file's stored list, in the current project). */
        void moveBookmark(Path file, int fromIndex, int toIndex);
        /** Reorder a whole file group among the file headers (indices into the current project's file order). */
        void moveFile(int fromIndex, int toIndex);
    }

    /** Tree row: a project-group header, a file header, or a single bookmark. */
    private sealed interface Row permits ProjectRow, FileRow, MarkRow {}

    private record ProjectRow(String key, String name, boolean current) implements Row {}

    private record FileRow(Path file) implements Row {}

    private record MarkRow(Path file, Bookmark bm) implements Row {}

    private static final String SCOPE_HINT = tr("bookmarks.scopeTip");

    private final Supplier<Scope> source;
    private final Actions actions;
    private final TextField filterField = new TextField();
    private final ToggleButton showAll = new ToggleButton();
    private final HBox header;
    private final TreeView<Row> tree = new TreeView<>();
    private final StackPane placeholderPane;

    /** This window's project key, updated on each {@link #refresh()} — reordering is allowed only within it. */
    private String currentKey = "";

    /** In-scene single-line prompt (injected by MainController) used to edit a bookmark's note. */
    private OverlayInput.Prompt prompt;

    /** The row to reselect after the next {@link #refresh()} (set just before a reorder). */
    private Path reselectFile;

    private Integer reselectLine; // null = reselect the file header row
    /** The tree item currently being dragged (for drag-and-drop reordering). */
    private TreeItem<Row> draggedItem;

    public BookmarksPanel(Supplier<Scope> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("bookmarks-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        filterField.setPromptText(tr("bookmarks.filterPrompt"));
        filterField.getStyleClass().add("bookmarks-filter");
        filterField.textProperty().addListener((o, w, n) -> refresh());
        FilterFieldNav.install(filterField, tree, this::activateSelected); // Down/Enter → into / open the results
        HBox.setHgrow(filterField, Priority.ALWAYS);

        // Trailing clear ("✕") button — visible only while the filter has text (mirrors the Project/Notes panels).
        Button clearFilter = new Button("✕");
        clearFilter.getStyleClass().add("project-filter-clear");
        clearFilter.setFocusTraversable(false);
        clearFilter.setTooltip(new Tooltip(tr("project.filterClear")));
        clearFilter.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clearFilter.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        clearFilter.managedProperty().bind(clearFilter.visibleProperty());

        // "Show all projects" toggle — off by default (General + current project only); on reveals every project.
        showAll.setGraphic(Icons.project());
        showAll.getStyleClass().addAll("flat", "scope-toggle");
        showAll.setFocusTraversable(false);
        showAll.setTooltip(new Tooltip(tr("bookmarks.showAllTip")));
        showAll.selectedProperty().addListener((o, w, n) -> refresh());

        // An info badge explaining that bookmarks are scoped per project.
        Label info = new Label("ⓘ");
        info.getStyleClass().add("info-badge");
        Tooltip infoTip = new Tooltip(SCOPE_HINT);
        infoTip.setWrapText(true);
        infoTip.setMaxWidth(320);
        Tooltip.install(info, infoTip);
        header = new HBox(6, filterField, clearFilter, showAll, info);
        header.getStyleClass().add("project-filter-bar");
        header.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("bookmarks-tree");
        tree.setCellFactory(t -> new BookmarkCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });

        Label placeholder = new Label(tr("bookmarks.placeholder"));
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        placeholderPane = new StackPane(placeholder);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        refresh();
    }

    /**
     * Rebuilds the tree grouped by project (General, current, and — when {@link #showAll} is on — others), then by
     * file. Each bucket's file order and each file's bookmark order are preserved verbatim (the user's drag/move
     * order), matching {@link com.editora.ui.MainController#allBookmarkEntries()} so the panel and the {@code M-g b}
     * jump picker agree.
     */
    public void refresh() {
        String query = filterField.getText() == null
                ? ""
                : filterField.getText().strip().toLowerCase();
        Scope scope = source.get();
        Map<String, Map<String, List<Bookmark>>> byProject = scope.byProject();
        currentKey = scope.currentKey() == null ? "" : scope.currentKey();

        List<String> withContent = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Bookmark>>> e : byProject.entrySet()) {
            if (hasAnyBookmark(e.getValue())) {
                withContent.add(e.getKey());
            }
        }
        List<String> visible = ScopeGroups.visibleKeys(withContent, currentKey, showAll.isSelected(), scope.nameFor());

        TreeItem<Row> root = new TreeItem<>();
        for (String key : visible) {
            Map<String, List<Bookmark>> bucket = byProject.get(key);
            if (bucket == null) {
                continue;
            }
            boolean current = key.equals(currentKey);
            TreeItem<Row> projectNode =
                    new TreeItem<>(new ProjectRow(key, scope.nameFor().apply(key), current));
            // Expand the buckets you care about (General + current); collapse other projects.
            projectNode.setExpanded(key.isEmpty() || current);
            for (Map.Entry<String, List<Bookmark>> e : bucket.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                Path file = Path.of(e.getKey());
                boolean fileMatches = fileName(file).toLowerCase().contains(query);
                TreeItem<Row> fileNode = new TreeItem<>(new FileRow(file));
                fileNode.setExpanded(true);
                for (Bookmark bm : e.getValue()) {
                    if (query.isEmpty()
                            || fileMatches
                            || markLabel(bm).toLowerCase().contains(query)) {
                        fileNode.getChildren().add(new TreeItem<>(new MarkRow(file, bm)));
                    }
                }
                if (!fileNode.getChildren().isEmpty()) {
                    projectNode.getChildren().add(fileNode);
                }
            }
            if (!projectNode.getChildren().isEmpty()) {
                root.getChildren().add(projectNode);
            }
        }
        tree.setRoot(root);
        if (root.getChildren().isEmpty()) {
            getChildren().setAll(header, placeholderPane);
        } else {
            getChildren().setAll(header, tree);
        }
        applyPendingSelection();
    }

    private static boolean hasAnyBookmark(Map<String, List<Bookmark>> bucket) {
        if (bucket == null) {
            return false;
        }
        for (List<Bookmark> v : bucket.values()) {
            if (v != null && !v.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether reordering may run now: no filter (so tree indices map to stored ones). Callers also require the
     *  row to be in the current project's group via {@link #inCurrentGroup}. */
    private boolean reorderEnabled() {
        return filterField.getText() == null || filterField.getText().isBlank();
    }

    /** The project-group ancestor of {@code item}, or null. */
    private static TreeItem<Row> projectNodeOf(TreeItem<Row> item) {
        TreeItem<Row> p = item;
        while (p != null && !(p.getValue() instanceof ProjectRow)) {
            p = p.getParent();
        }
        return p;
    }

    /** Whether {@code item} lives under the current project's group (the only bucket reordering can mutate). */
    private boolean inCurrentGroup(TreeItem<Row> item) {
        TreeItem<Row> pn = projectNodeOf(item);
        return pn != null && pn.getValue() instanceof ProjectRow pr && pr.key().equals(currentKey);
    }

    /** Reselects the row remembered before a reorder (so repeated Alt+Up/Down keeps moving the same row). */
    private void applyPendingSelection() {
        if (reselectFile == null) {
            return;
        }
        for (TreeItem<Row> projectNode : tree.getRoot().getChildren()) {
            for (TreeItem<Row> fileNode : projectNode.getChildren()) {
                if (!(fileNode.getValue() instanceof FileRow fr) || !fr.file().equals(reselectFile)) {
                    continue;
                }
                TreeItem<Row> target = fileNode;
                if (reselectLine != null) {
                    for (TreeItem<Row> markItem : fileNode.getChildren()) {
                        if (((MarkRow) markItem.getValue()).bm().line() == reselectLine) {
                            target = markItem;
                            break;
                        }
                    }
                }
                int idx = tree.getRow(target);
                if (idx >= 0) {
                    tree.getSelectionModel().select(idx);
                    tree.scrollTo(idx);
                }
                reselectFile = null;
                reselectLine = null;
                return;
            }
        }
        reselectFile = null;
        reselectLine = null;
    }

    /** Moves keyboard focus into the panel (the filter field), for window-switching. */
    public void focusContent() {
        filterField.requestFocus();
    }

    @Override
    public void focusFirstItem() {
        // Land on the filter field so the user can type to filter immediately; Down/Enter move into / open
        // the results (see FilterFieldNav in the constructor).
        filterField.requestFocus();
    }

    // --- keyboard navigation (mirrors StructurePanel) ---

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                activateSelected();
                e.consume();
            }
            case DOWN -> {
                if (e.isAltDown()) {
                    moveSelected(1);
                } else {
                    move(1);
                }
                e.consume();
            }
            case UP -> {
                if (e.isAltDown()) {
                    moveSelected(-1);
                } else {
                    move(-1);
                }
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
                        activateSelected();
                        e.consume();
                    }
                    default -> {}
                }
            }
        }
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
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
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

    private void activateSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        if (item.getValue() instanceof MarkRow m) {
            actions.openAndJump(m.file(), m.bm().line());
        } else {
            item.setExpanded(!item.isExpanded()); // a project or file header toggles
        }
    }

    // --- reordering (Alt+Up/Down, context menu, drag-and-drop) — current project group only ---

    /** Moves the selected bookmark within its file, or the selected file group among files, by {@code delta}. */
    private void moveSelected(int delta) {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || !reorderEnabled() || !inCurrentGroup(item)) {
            return;
        }
        if (item.getValue() instanceof MarkRow m) {
            TreeItem<Row> parent = item.getParent();
            int from = parent.getChildren().indexOf(item);
            moveMark(
                    m.file(),
                    m.bm().line(),
                    from,
                    from + delta,
                    parent.getChildren().size());
        } else if (item.getValue() instanceof FileRow f) {
            var siblings = item.getParent().getChildren(); // the project group's file headers
            int from = siblings.indexOf(item);
            moveFileGroup(f.file(), from, from + delta, siblings.size());
        }
    }

    private void moveMark(Path file, int line, int from, int to, int count) {
        if (to < 0 || to >= count || from == to) {
            return;
        }
        reselectFile = file; // restore selection on this bookmark after the refresh
        reselectLine = line;
        actions.moveBookmark(file, from, to);
    }

    private void moveFileGroup(Path file, int from, int to, int count) {
        if (to < 0 || to >= count || from == to) {
            return;
        }
        reselectFile = file;
        reselectLine = null;
        actions.moveFile(from, to);
    }

    /** Whether {@code src} may be dropped onto {@code target}: a bookmark onto a sibling, or a file onto a sibling
     *  file — both within the current project's group (the only editable bucket). */
    private boolean canDrop(TreeItem<Row> src, TreeItem<Row> target) {
        if (src == null || target == null || src == target || !inCurrentGroup(src) || !inCurrentGroup(target)) {
            return false;
        }
        if (src.getValue() instanceof MarkRow sm && target.getValue() instanceof MarkRow tm) {
            return sm.file().equals(tm.file());
        }
        // Two file headers under the same project group.
        return src.getValue() instanceof FileRow
                && target.getValue() instanceof FileRow
                && src.getParent() == target.getParent();
    }

    private void handleDrop(TreeItem<Row> src, TreeItem<Row> target, boolean after) {
        if (!canDrop(src, target) || !reorderEnabled()) {
            return;
        }
        if (src.getValue() instanceof MarkRow sm) {
            var siblings = src.getParent().getChildren();
            int from = siblings.indexOf(src);
            moveMark(
                    sm.file(),
                    sm.bm().line(),
                    from,
                    insertIndex(from, siblings.indexOf(target), after),
                    siblings.size());
        } else if (src.getValue() instanceof FileRow sf) {
            var files = src.getParent().getChildren(); // sibling file headers within the project group
            int from = files.indexOf(src);
            moveFileGroup(sf.file(), from, insertIndex(from, files.indexOf(target), after), files.size());
        }
    }

    /** Index to insert at after removing {@code from}, so the row lands before/after {@code target}. */
    static int insertIndex(int from, int target, boolean after) {
        int t = target > from ? target - 1 : target; // removing 'from' shifts later indices down by one
        return t + (after ? 1 : 0);
    }

    private static void toggleStyle(javafx.scene.Node node, String styleClass, boolean on) {
        node.getStyleClass().remove(styleClass);
        if (on) {
            node.getStyleClass().add(styleClass);
        }
    }

    // --- editing ---

    private void editNote(MarkRow m) {
        if (prompt == null) {
            return;
        }
        prompt.show(
                tr("dialog.bookmarkNote.title"),
                tr("dialog.bookmarkNote.content"),
                m.bm().note(),
                note -> actions.setNote(m.file(), m.bm().line(), note.strip()));
    }

    /** Injects the in-scene prompt used to edit a bookmark's note (so the panel needs no overlay host). */
    public void setPrompt(OverlayInput.Prompt prompt) {
        this.prompt = prompt;
    }

    private void deleteMark(MarkRow m) {
        actions.delete(m.file(), m.bm().line());
    }

    private void deleteFile(FileRow f) {
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("bookmarks.deleteFileBody", fileName(f.file())),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle(tr("bookmarks.deleteFileTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            actions.deleteAll(f.file());
        }
    }

    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    /** Leaf label for a bookmark: the note, else the captured line text, else "line N". */
    private static String markLabel(Bookmark bm) {
        if (!bm.note().isEmpty()) {
            return bm.note();
        }
        if (!bm.lineText().isEmpty()) {
            return bm.lineText();
        }
        return "line " + (bm.line() + 1);
    }

    private final class BookmarkCell extends TreeCell<Row> {
        BookmarkCell() {
            // Drag-and-drop reordering (mirrors the tab strip's visuals): a translucent ghost follows
            // the cursor, the dragged row dims, and an accent insertion line shows on the target row's
            // top/bottom edge for the side it would drop on.
            setOnDragDetected(e -> {
                if (!reorderEnabled() || getItem() == null || !inCurrentGroup(getTreeItem())) {
                    return; // only current-project rows are reorderable
                }
                draggedItem = getTreeItem();
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString("bookmark-row"); // a drag needs a payload, even if unused
                db.setContent(content);
                SnapshotParameters params = new SnapshotParameters();
                params.setFill(Color.TRANSPARENT);
                db.setDragView(snapshot(params, null), e.getX(), e.getY());
                tree.refresh(); // repaint so the dragged row picks up the dimmed style
                e.consume();
            });
            setOnDragOver(e -> {
                if (canDrop(draggedItem, getTreeItem())) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    boolean after = e.getY() > getHeight() / 2;
                    toggleStyle(this, "bookmark-drop-below", after);
                    toggleStyle(this, "bookmark-drop-above", !after);
                }
                e.consume();
            });
            setOnDragExited(e -> clearDropMarkers());
            setOnDragDropped(e -> {
                clearDropMarkers();
                handleDrop(draggedItem, getTreeItem(), e.getY() > getHeight() / 2);
                e.setDropCompleted(true);
                e.consume();
            });
            setOnDragDone(e -> {
                draggedItem = null;
                clearDropMarkers();
                tree.refresh(); // clear the dimmed style
                e.consume();
            });
        }

        private void clearDropMarkers() {
            getStyleClass().removeAll("bookmark-drop-above", "bookmark-drop-below");
        }

        /** "Move Up"/"Move Down" items that act on this row (only for the current project's group). */
        private void addMoveItems(ContextMenu menu) {
            boolean can = reorderEnabled() && inCurrentGroup(getTreeItem());
            MenuItem up = new MenuItem(tr("bookmarks.moveUp"));
            up.setGraphic(Icons.arrowUp());
            up.setOnAction(e -> {
                tree.getSelectionModel().select(getTreeItem());
                moveSelected(-1);
            });
            MenuItem down = new MenuItem(tr("bookmarks.moveDown"));
            down.setGraphic(Icons.arrowDown());
            down.setOnAction(e -> {
                tree.getSelectionModel().select(getTreeItem());
                moveSelected(1);
            });
            up.setDisable(!can);
            down.setDisable(!can);
            menu.getItems().addAll(new SeparatorMenuItem(), up, down);
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            // Cells are reused as the tree scrolls/rebuilds; never let styling leak onto a reused cell.
            clearDropMarkers();
            getStyleClass().removeAll("bookmark-file-row", "bookmark-project-row", "bookmark-project-current");
            toggleStyle(this, "bookmark-dragging", !empty && item != null && getTreeItem() == draggedItem);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                return;
            }
            if (item instanceof ProjectRow p) {
                setText(p.current() ? tr("scope.currentSuffix", p.name()) : p.name());
                getStyleClass().add("bookmark-project-row");
                if (p.current()) {
                    getStyleClass().add("bookmark-project-current");
                }
                setGraphic(p.key().isEmpty() ? Icons.notes() : Icons.project());
                setTooltip(null);
                setContextMenu(null);
            } else if (item instanceof FileRow f) {
                setText(fileName(f.file()));
                getStyleClass().add("bookmark-file-row");
                setGraphic(FileIcons.forFileName(fileName(f.file())));
                setTooltip(new Tooltip(f.file().toString()));
                MenuItem deleteAll = new MenuItem(tr("bookmarks.deleteAllInFile"));
                deleteAll.setGraphic(Icons.trash());
                deleteAll.setOnAction(e -> deleteFile(f));
                ContextMenu menu = new ContextMenu(deleteAll);
                addMoveItems(menu);
                setContextMenu(menu);
            } else if (item instanceof MarkRow m) {
                setText(markLabel(m.bm()) + "    line " + (m.bm().line() + 1));
                setGraphic(Icons.bookmark());
                setTooltip(new Tooltip(m.file() + ":" + (m.bm().line() + 1)));
                MenuItem edit = new MenuItem(tr("bookmarks.editNoteItem"));
                edit.setGraphic(Icons.edit());
                edit.setOnAction(e -> editNote(m));
                MenuItem delete = new MenuItem(tr("bookmarks.deleteItem"));
                delete.setGraphic(Icons.trash());
                delete.setOnAction(e -> deleteMark(m));
                ContextMenu menu = new ContextMenu(edit, delete);
                addMoveItems(menu);
                setContextMenu(menu);
            }
        }
    }
}
