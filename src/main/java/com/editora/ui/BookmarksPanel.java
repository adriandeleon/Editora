package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.editora.config.Bookmark;

import javafx.scene.control.Alert;
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
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Bookmarks tool window: a GLOBAL list of every bookmark across all files in the session (open or
 * closed), grouped by file in a tree. Enter / double-click opens the file and jumps to the line; a
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
    }

    /** Tree row: a file header or a single bookmark under it. */
    private sealed interface Row permits FileRow, MarkRow { }
    private record FileRow(Path file) implements Row { }
    private record MarkRow(Path file, Bookmark bm) implements Row { }

    private static final String SCOPE_HINT =
            "Bookmarks are saved per project (and with the global session when no project is open). "
            + "Switching projects shows that project's bookmarks.";

    private final Supplier<Map<String, List<Bookmark>>> source;
    private final Actions actions;
    private final TextField filterField = new TextField();
    private final HBox header;
    private final TreeView<Row> tree = new TreeView<>();
    private final StackPane placeholderPane;

    public BookmarksPanel(Supplier<Map<String, List<Bookmark>>> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("bookmarks-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        filterField.setPromptText("Filter bookmarks…");
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

        Label placeholder = new Label("No bookmarks in this project");
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        placeholderPane = new StackPane(placeholder);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        refresh();
    }

    /** Rebuilds the tree from the current persisted bookmark map (all files, sorted), applying the filter. */
    public void refresh() {
        String query = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase();
        Map<String, List<Bookmark>> map = source.get();
        TreeItem<Row> root = new TreeItem<>();
        List<Map.Entry<String, List<Bookmark>>> files = new ArrayList<>(map.entrySet());
        files.removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
        files.sort(Comparator.comparing(e -> fileName(Path.of(e.getKey())), String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, List<Bookmark>> e : files) {
            Path file = Path.of(e.getKey());
            List<Bookmark> marks = new ArrayList<>(e.getValue());
            marks.sort(Comparator.comparingInt(Bookmark::line));
            boolean fileMatches = fileName(file).toLowerCase().contains(query);
            TreeItem<Row> fileNode = new TreeItem<>(new FileRow(file));
            fileNode.setExpanded(true);
            for (Bookmark bm : marks) {
                if (query.isEmpty() || fileMatches || markLabel(bm).toLowerCase().contains(query)) {
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
            case ENTER -> { activateSelected(); e.consume(); }
            case DOWN -> { move(1); e.consume(); }
            case UP -> { move(-1); e.consume(); }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> { move(1); e.consume(); }
                    case P -> { move(-1); e.consume(); }
                    case F -> { expandOrDescend(); e.consume(); }
                    case B -> { collapseOrAscend(); e.consume(); }
                    case M -> { activateSelected(); e.consume(); }
                    default -> { }
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

    // --- editing ---

    private void editNote(MarkRow m) {
        TextInputDialog dialog = new TextInputDialog(m.bm().note());
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle("Bookmark Note");
        dialog.setHeaderText(null);
        dialog.setContentText("Note:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(note -> actions.setNote(m.file(), m.bm().line(), note.strip()));
    }

    private void deleteMark(MarkRow m) {
        actions.delete(m.file(), m.bm().line());
    }

    private void deleteFile(FileRow f) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove all bookmarks in \"" + fileName(f.file()) + "\"?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle("Delete Bookmarks");
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
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
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
                MenuItem deleteAll = new MenuItem("Delete all in file");
                deleteAll.setOnAction(e -> deleteFile(f));
                setContextMenu(new ContextMenu(deleteAll));
            } else if (item instanceof MarkRow m) {
                setText(markLabel(m.bm()) + "    line " + (m.bm().line() + 1));
                setGraphic(Icons.bookmark());
                setTooltip(new Tooltip(m.file() + ":" + (m.bm().line() + 1)));
                MenuItem edit = new MenuItem("Edit note…");
                edit.setOnAction(e -> editNote(m));
                MenuItem delete = new MenuItem("Delete");
                delete.setOnAction(e -> deleteMark(m));
                setContextMenu(new ContextMenu(edit, delete));
            }
        }
    }
}
