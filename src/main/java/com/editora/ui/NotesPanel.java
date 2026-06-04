package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Personal Notes tool window: every note in the active project, grouped by file in a tree. Enter /
 * double-click opens the file and jumps to the note; a right-click menu edits the body, resolves/reopens,
 * or deletes. Reads the persisted notes map directly (so it includes closed files) and routes mutations
 * back through {@link Actions}. Mirrors {@link BookmarksPanel}.
 */
public class NotesPanel extends VBox implements ToolWindowContent {

    /** Mutations the panel asks the controller to perform (file key = canonical path in the notes map). */
    public interface Actions {
        void openAndJump(String fileKey, PersonalNote note);
        void editBody(String fileKey, PersonalNote note);
        void setStatus(String fileKey, PersonalNote note, NoteStatus status);
        void delete(String fileKey, PersonalNote note);
        void deleteAll(String fileKey);
    }

    private sealed interface Row permits FileRow, NoteRow { }
    private record FileRow(String fileKey) implements Row { }
    private record NoteRow(String fileKey, PersonalNote note) implements Row { }

    private final Supplier<Map<String, List<PersonalNote>>> source;
    private final Actions actions;
    private final TextField filterField = new TextField();
    private final TreeView<Row> tree = new TreeView<>();
    private final StackPane placeholderPane;

    public NotesPanel(Supplier<Map<String, List<PersonalNote>>> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("notes-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        filterField.setPromptText(tr("notes.filterPrompt"));
        filterField.getStyleClass().add("notes-filter");
        filterField.textProperty().addListener((o, w, n) -> refresh());
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox header = new HBox(6, filterField);
        header.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("notes-tree");
        tree.setCellFactory(t -> new NoteCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                activateSelected();
                e.consume();
            }
        });

        Label placeholder = new Label(tr("notes.placeholder"));
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        placeholderPane = new StackPane(placeholder);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        getChildren().addAll(header, tree);
        refresh();
    }

    @Override
    public void focusFirstItem() {
        if (!tree.getRoot().getChildren().isEmpty()) {
            tree.requestFocus();
            tree.getSelectionModel().select(tree.getRoot().getChildren().get(0));
        }
    }

    /** Rebuilds the tree from the persisted notes map, applying the filter. */
    public void refresh() {
        String filter = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase();
        TreeItem<Row> root = new TreeItem<>();
        Map<String, List<PersonalNote>> map = source.get();
        map.forEach((fileKey, notes) -> {
            if (notes == null || notes.isEmpty()) {
                return;
            }
            List<TreeItem<Row>> kids = new java.util.ArrayList<>();
            for (PersonalNote note : notes) {
                if (filter.isEmpty() || matches(fileKey, note, filter)) {
                    kids.add(new TreeItem<>(new NoteRow(fileKey, note)));
                }
            }
            if (!kids.isEmpty()) {
                TreeItem<Row> fileItem = new TreeItem<>(new FileRow(fileKey));
                fileItem.setExpanded(true);
                fileItem.getChildren().setAll(kids);
                root.getChildren().add(fileItem);
            }
        });
        tree.setRoot(root);
        if (root.getChildren().isEmpty()) {
            if (getChildren().size() < 2 || getChildren().get(1) != placeholderPane) {
                getChildren().set(1, placeholderPane);
            }
        } else if (getChildren().get(1) != tree) {
            getChildren().set(1, tree);
        }
    }

    private static boolean matches(String fileKey, PersonalNote note, String filter) {
        return fileKey.toLowerCase().contains(filter)
                || note.body().toLowerCase().contains(filter)
                || note.tags().stream().anyMatch(t -> t.toLowerCase().contains(filter));
    }

    private void activateSelected() {
        TreeItem<Row> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        if (sel.getValue() instanceof NoteRow n) {
            actions.openAndJump(n.fileKey(), n.note());
        } else {
            sel.setExpanded(!sel.isExpanded());
        }
    }

    private static String fileName(String key) {
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /** First non-blank line of the note body, or a scope/status placeholder. */
    private static String noteLabel(PersonalNote note) {
        String body = note.body().strip();
        String firstLine = body.isEmpty() ? "" : body.lines().findFirst().orElse("");
        String prefix = tr("notes.line", note.anchor().line() + 1) + ": ";
        String text = firstLine.isEmpty() ? tr("notes.empty") : firstLine;
        if (note.status() == NoteStatus.RESOLVED) {
            text = "✓ " + text;
        } else if (note.status() == NoteStatus.ORPHANED) {
            text = "⚠ " + text;
        }
        return prefix + text;
    }

    private final class NoteCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            getStyleClass().removeAll("note-resolved", "note-orphaned");
            if (empty || item == null) {
                setText(null);
                return;
            }
            if (item instanceof FileRow f) {
                setText(fileName(f.fileKey()));
                MenuItem deleteAll = new MenuItem(tr("notes.deleteAllInFile"));
                deleteAll.setOnAction(e -> actions.deleteAll(f.fileKey()));
                setContextMenu(new ContextMenu(deleteAll));
            } else if (item instanceof NoteRow n) {
                setText(noteLabel(n.note()));
                if (n.note().status() == NoteStatus.RESOLVED) {
                    getStyleClass().add("note-resolved");
                } else if (n.note().status() == NoteStatus.ORPHANED) {
                    getStyleClass().add("note-orphaned");
                }
                setContextMenu(noteMenu(n));
            }
        }

        private ContextMenu noteMenu(NoteRow n) {
            MenuItem edit = new MenuItem(tr("notes.editBody"));
            edit.setOnAction(e -> actions.editBody(n.fileKey(), n.note()));
            boolean resolved = n.note().status() == NoteStatus.RESOLVED;
            MenuItem toggle = new MenuItem(resolved ? tr("notes.reopen") : tr("notes.resolve"));
            toggle.setOnAction(e -> actions.setStatus(n.fileKey(), n.note(),
                    resolved ? NoteStatus.ACTIVE : NoteStatus.RESOLVED));
            MenuItem delete = new MenuItem(tr("notes.delete"));
            delete.setOnAction(e -> actions.delete(n.fileKey(), n.note()));
            return new ContextMenu(edit, toggle, new SeparatorMenuItem(), delete);
        }
    }
}
