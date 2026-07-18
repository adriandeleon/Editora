package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;

import static com.editora.i18n.Messages.tr;

/**
 * The Personal Notes tool window: every note (across all files, open or closed) grouped by <b>project</b> then
 * by file, in a tree. It always shows the <b>General</b> (no-project) bucket and the <b>current</b> project's
 * bucket; a "Show all projects" toggle additionally reveals every other project's notes, so nothing
 * appears/disappears when switching projects. Enter / double-click opens the file and jumps to the note; a
 * right-click menu edits the body, resolves/reopens, or deletes. Reads the persisted notes map directly (so it
 * includes closed files) and routes mutations back through {@link Actions}. Mirrors {@link BookmarksPanel}.
 */
public class NotesPanel extends VBox implements ToolWindowContent {

    /** The cross-project view the panel renders: every bucket, the current project key, and a key→name resolver. */
    public record Scope(
            Map<String, Map<String, List<PersonalNote>>> byProject,
            String currentKey,
            Function<String, String> nameFor) {}

    /**
     * Mutations the panel asks the controller to perform (file key = canonical path in the notes map). Each
     * mutation carries the row's {@code projectKey} — the bucket it lives in ({@code ""} = General) — because the
     * panel shows more than one bucket at once (General + current + others), so the controller must target the
     * right one rather than always the active project's.
     */
    public interface Actions {
        void openAndJump(String projectKey, String fileKey, PersonalNote note);

        void editBody(String projectKey, String fileKey, PersonalNote note);

        void setStatus(String projectKey, String fileKey, PersonalNote note, NoteStatus status);

        void delete(String projectKey, String fileKey, PersonalNote note);

        void deleteAll(String projectKey, String fileKey);
    }

    private sealed interface Row permits ProjectRow, FileRow, NoteRow {}

    private record ProjectRow(String key, String name, boolean current) implements Row {}

    private record FileRow(String projectKey, String fileKey) implements Row {}

    private record NoteRow(String projectKey, String fileKey, PersonalNote note) implements Row {}

    private final Supplier<Scope> source;
    private final Actions actions;
    private final TextField filterField = new TextField();
    private final ToggleButton showAll = new ToggleButton();
    private final HBox header;
    private final TreeView<Row> tree = new TreeView<>();
    private final StackPane placeholderPane;

    private String currentKey = "";

    public NotesPanel(Supplier<Scope> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("notes-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        filterField.setPromptText(tr("notes.filterPrompt"));
        filterField.getStyleClass().add("notes-filter");
        filterField.textProperty().addListener((o, w, n) -> refresh());
        FilterFieldNav.install(filterField, tree, this::activateSelected); // Down/Enter → into / open the results
        // Trailing clear ("✕") button — visible only while the filter has text (mirrors the Project panel).
        Button clear = new Button("✕");
        clear.getStyleClass().add("project-filter-clear");
        clear.setFocusTraversable(false);
        clear.setTooltip(new Tooltip(tr("project.filterClear")));
        clear.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clear.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        clear.managedProperty().bind(clear.visibleProperty());
        HBox.setHgrow(filterField, Priority.ALWAYS);

        // "Show all projects" toggle — off by default (General + current project only); on reveals every project.
        showAll.setGraphic(Icons.project());
        showAll.getStyleClass().addAll("flat", "scope-toggle");
        showAll.setFocusTraversable(false);
        showAll.setTooltip(new Tooltip(tr("notes.showAllTip")));
        showAll.selectedProperty().addListener((o, w, n) -> refresh());

        header = new HBox(6, filterField, clear, showAll);
        header.getStyleClass().add("project-filter-bar");
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
        // Land on the filter field so the user can type to filter immediately; Down/Enter move into / open
        // the results (see FilterFieldNav in the constructor).
        filterField.requestFocus();
    }

    /** Rebuilds the tree grouped by project (General, current, and — when {@link #showAll} is on — others). */
    public void refresh() {
        String filter = filterField.getText() == null
                ? ""
                : filterField.getText().strip().toLowerCase();
        Scope scope = source.get();
        Map<String, Map<String, List<PersonalNote>>> byProject = scope.byProject();
        currentKey = scope.currentKey() == null ? "" : scope.currentKey();

        List<String> withContent = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<PersonalNote>>> e : byProject.entrySet()) {
            if (hasAnyNote(e.getValue())) {
                withContent.add(e.getKey());
            }
        }
        List<String> visible = ScopeGroups.visibleKeys(withContent, currentKey, showAll.isSelected(), scope.nameFor());

        TreeItem<Row> root = new TreeItem<>();
        for (String key : visible) {
            Map<String, List<PersonalNote>> bucket = byProject.get(key);
            if (bucket == null) {
                continue;
            }
            boolean current = key.equals(currentKey);
            TreeItem<Row> projectNode =
                    new TreeItem<>(new ProjectRow(key, scope.nameFor().apply(key), current));
            // Expand only the current project's group by default; General and every other project start
            // collapsed (in the no-project window, General *is* the current group, so it expands there).
            projectNode.setExpanded(current);
            bucket.forEach((fileKey, notes) -> {
                if (notes == null || notes.isEmpty()) {
                    return;
                }
                List<TreeItem<Row>> kids = new ArrayList<>();
                for (PersonalNote note : notes) {
                    if (filter.isEmpty() || matches(fileKey, note, filter)) {
                        kids.add(new TreeItem<>(new NoteRow(key, fileKey, note)));
                    }
                }
                if (!kids.isEmpty()) {
                    TreeItem<Row> fileItem = new TreeItem<>(new FileRow(key, fileKey));
                    fileItem.setExpanded(true);
                    fileItem.getChildren().setAll(kids);
                    projectNode.getChildren().add(fileItem);
                }
            });
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
    }

    private static boolean hasAnyNote(Map<String, List<PersonalNote>> bucket) {
        if (bucket == null) {
            return false;
        }
        for (List<PersonalNote> v : bucket.values()) {
            if (v != null && !v.isEmpty()) {
                return true;
            }
        }
        return false;
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
            actions.openAndJump(n.projectKey(), n.fileKey(), n.note());
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
            setTooltip(null);
            getStyleClass()
                    .removeAll(
                            "note-resolved",
                            "note-orphaned",
                            "notes-file-row",
                            "note-project-row",
                            "note-project-current");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof ProjectRow p) {
                setText(p.current() ? tr("scope.currentSuffix", p.name()) : p.name());
                getStyleClass().add("note-project-row");
                if (p.current()) {
                    getStyleClass().add("note-project-current");
                }
                setGraphic(p.key().isEmpty() ? Icons.notes() : Icons.project());
            } else if (item instanceof FileRow f) {
                setText(fileName(f.fileKey()));
                getStyleClass().add("notes-file-row");
                setGraphic(FileIcons.forFileName(fileName(f.fileKey())));
                setTooltip(new Tooltip(f.fileKey()));
                MenuItem deleteAll = new MenuItem(tr("notes.deleteAllInFile"));
                deleteAll.setGraphic(Icons.trash());
                deleteAll.setOnAction(e -> actions.deleteAll(f.projectKey(), f.fileKey()));
                setContextMenu(new ContextMenu(deleteAll));
            } else if (item instanceof NoteRow n) {
                setText(noteLabel(n.note()));
                setGraphic(Icons.notes());
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
            edit.setGraphic(Icons.edit());
            edit.setOnAction(e -> actions.editBody(n.projectKey(), n.fileKey(), n.note()));
            boolean resolved = n.note().status() == NoteStatus.RESOLVED;
            MenuItem toggle = new MenuItem(resolved ? tr("notes.reopen") : tr("notes.resolve"));
            toggle.setGraphic(resolved ? Icons.refresh() : Icons.check());
            toggle.setOnAction(e -> actions.setStatus(
                    n.projectKey(), n.fileKey(), n.note(), resolved ? NoteStatus.ACTIVE : NoteStatus.RESOLVED));
            MenuItem delete = new MenuItem(tr("notes.delete"));
            delete.setGraphic(Icons.trash());
            delete.setOnAction(e -> actions.delete(n.projectKey(), n.fileKey(), n.note()));
            return new ContextMenu(edit, toggle, new SeparatorMenuItem(), delete);
        }
    }
}
