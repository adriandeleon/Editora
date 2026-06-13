package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.config.Bookmark;

import static com.editora.i18n.Messages.tr;

/**
 * The Bookmarks tool window: a list of every bookmark in the active project (across all its files, open
 * or closed), grouped by file in a tree. Enter / double-click opens the file and jumps to the line; a
 * right-click menu edits the note or deletes bookmarks.
 *
 * <p>It reads the persisted bookmark map directly (so it includes files that aren't open) and routes
 * mutations back through {@link Actions} so the controller can update an open buffer's gutter or the
 * persisted map for a closed file. Marks itself {@code editora.ownsKeys} for local Emacs navigation,
 * mirroring {@link StructurePanel}.
 */
public class BookmarksPanel extends VBox implements ToolWindowContent {

    /** Mutations the panel asks the controller to perform (it knows which files are open). */
    public interface Actions {
        void openAndJump(Path file, int line);

        void setNote(Path file, int line, String note);

        void delete(Path file, int line);

        void deleteAll(Path file);
        /** Reorder a bookmark within its file (indices into that file's stored list). */
        void moveBookmark(Path file, int fromIndex, int toIndex);
        /** Reorder a whole file group among the file headers (indices into the stored file order). */
        void moveFile(int fromIndex, int toIndex);
    }

    /** Tree row: a file header or a single bookmark under it. */
    private sealed interface Row permits FileRow, MarkRow {}

    private record FileRow(Path file) implements Row {}

    private record MarkRow(Path file, Bookmark bm) implements Row {}

    private static final String SCOPE_HINT = tr("bookmarks.scopeTip");

    private final Supplier<Map<String, List<Bookmark>>> source;
    private final Actions actions;
    private final TextField filterField = new TextField();
    private final HBox header;
    private final TreeView<Row> tree = new TreeView<>();
    private final StackPane placeholderPane;

    /** In-scene single-line prompt (injected by MainController) used to edit a bookmark's note. */
    private OverlayInput.Prompt prompt;

    /** The row to reselect after the next {@link #refresh()} (set just before a reorder). */
    private Path reselectFile;

    private Integer reselectLine; // null = reselect the file header row
    /** The tree item currently being dragged (for drag-and-drop reordering). */
    private TreeItem<Row> draggedItem;

    public BookmarksPanel(Supplier<Map<String, List<Bookmark>>> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("bookmarks-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        filterField.setPromptText(tr("bookmarks.filterPrompt"));
        filterField.getStyleClass().add("bookmarks-filter");
        filterField.textProperty().addListener((o, w, n) -> refresh());
        HBox.setHgrow(filterField, Priority.ALWAYS);

        // An info badge explaining that bookmarks are scoped to the active project/session.
        Label info = new Label("ⓘ");
        info.getStyleClass().add("info-badge");
        Tooltip infoTip = new Tooltip(SCOPE_HINT);
        infoTip.setWrapText(true);
        infoTip.setMaxWidth(320);
        Tooltip.install(info, infoTip);
        header = new HBox(6, filterField, info);
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
     * Rebuilds the tree from the bookmark map, applying the filter. The map's iteration order (files) and
     * each file's list order (bookmarks) are preserved verbatim — that's the user's order (drag / move),
     * and {@link com.editora.ui.MainController#allBookmarkEntries()} reads it the same way, so the panel
     * and the {@code M-g b} jump picker always agree.
     */
    public void refresh() {
        String query = filterField.getText() == null
                ? ""
                : filterField.getText().strip().toLowerCase();
        Map<String, List<Bookmark>> map = source.get();
        TreeItem<Row> root = new TreeItem<>();
        for (Map.Entry<String, List<Bookmark>> e : map.entrySet()) {
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
                root.getChildren().add(fileNode);
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

    /** Whether reordering is allowed right now (only with no filter, so tree indices map to stored ones). */
    private boolean reorderEnabled() {
        return filterField.getText() == null || filterField.getText().isBlank();
    }

    /** Reselects the row remembered before a reorder (so repeated Alt+Up/Down keeps moving the same row). */
    private void applyPendingSelection() {
        if (reselectFile == null) {
            return;
        }
        for (TreeItem<Row> fileNode : tree.getRoot().getChildren()) {
            FileRow fr = (FileRow) fileNode.getValue();
            if (!fr.file().equals(reselectFile)) {
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
            break;
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
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
            tree.scrollTo(0);
        }
        tree.requestFocus();
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
        } else if (item.getValue() instanceof FileRow) {
            item.setExpanded(!item.isExpanded());
        }
    }

    // --- reordering (Alt+Up/Down, context menu, drag-and-drop) ---

    /** Moves the selected bookmark within its file, or the selected file group among files, by {@code delta}. */
    private void moveSelected(int delta) {
        if (!reorderEnabled()) {
            return;
        }
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
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
            int from = tree.getRoot().getChildren().indexOf(item);
            moveFileGroup(
                    f.file(), from, from + delta, tree.getRoot().getChildren().size());
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

    /** Whether {@code src} may be dropped onto {@code target}: a bookmark onto a sibling, or a file onto a file. */
    private boolean canDrop(TreeItem<Row> src, TreeItem<Row> target) {
        if (src == null || target == null || src == target) {
            return false;
        }
        if (src.getValue() instanceof MarkRow sm && target.getValue() instanceof MarkRow tm) {
            return sm.file().equals(tm.file());
        }
        return src.getValue() instanceof FileRow && target.getValue() instanceof FileRow;
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
            var roots = tree.getRoot().getChildren();
            int from = roots.indexOf(src);
            moveFileGroup(sf.file(), from, insertIndex(from, roots.indexOf(target), after), roots.size());
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
                if (!reorderEnabled() || getItem() == null) {
                    return;
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

        /** "Move Up"/"Move Down" items that act on this row (disabled while a filter is active). */
        private void addMoveItems(ContextMenu menu) {
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
            up.setDisable(!reorderEnabled());
            down.setDisable(!reorderEnabled());
            menu.getItems().addAll(new SeparatorMenuItem(), up, down);
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            // Cells are reused as the tree scrolls/rebuilds; never let drag styling leak onto a reused
            // cell. The dragged row stays dimmed via a re-applied style each repaint.
            clearDropMarkers();
            toggleStyle(this, "bookmark-dragging", !empty && item != null && getTreeItem() == draggedItem);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                getStyleClass().remove("bookmark-file-row");
                return;
            }
            getStyleClass().remove("bookmark-file-row");
            if (item instanceof FileRow f) {
                setText(fileName(f.file()));
                getStyleClass().add("bookmark-file-row");
                setGraphic(Icons.fileSheet());
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
