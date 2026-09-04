package com.editora.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.editor.TabContent;

import static com.editora.i18n.Messages.tr;

/** A single review tab for every file section in a unified patch. */
final class PatchReviewPane implements TabContent {

    record Entry(String label, String status, int additions, int deletions, DiffViewerPane pane) {
        Entry(String label, int additions, int deletions, DiffViewerPane pane) {
            this(label, "", additions, deletions, pane);
        }
    }

    private final String title;
    private final List<Entry> entries;
    private final BorderPane root = new BorderPane();
    private final ListView<Entry> files = new ListView<>();
    private final Label position = new Label();

    PatchReviewPane(String title, List<Entry> entries) {
        this.title = title;
        this.entries = List.copyOf(entries);
        root.getStyleClass().add("patch-review");
        files.getItems().setAll(entries);
        files.getStyleClass().add("patch-file-list");
        files.setCellFactory(v -> new FileCell());
        files.setPrefWidth(245);
        files.setMinWidth(170);
        files.getSelectionModel().selectedIndexProperty().addListener((o, old, next) -> show(next.intValue()));

        Button previous = button(Icons.arrowUp(), tr("diff.previousFile"), () -> selectRelative(-1));
        Button next = button(Icons.arrowDown(), tr("diff.nextFile"), () -> selectRelative(1));
        position.getStyleClass().add("diff-summary");
        HBox nav = new HBox(3, position, previous, next);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.setPadding(new Insets(4, 6, 4, 6));
        nav.getStyleClass().add("diff-toolbar");
        VBox left = new VBox(nav, new Separator(), files);
        VBox.setVgrow(files, Priority.ALWAYS);
        left.getStyleClass().add("patch-review-sidebar");
        root.setLeft(left);
        if (!entries.isEmpty()) {
            files.getSelectionModel().select(0);
        }
    }

    List<DiffViewerPane> panes() {
        return entries.stream().map(Entry::pane).toList();
    }

    DiffViewerPane activePane() {
        int selected = files.getSelectionModel().getSelectedIndex();
        return selected >= 0 && selected < entries.size()
                ? entries.get(selected).pane()
                : null;
    }

    private void selectRelative(int delta) {
        if (entries.isEmpty()) {
            return;
        }
        int current = Math.max(0, files.getSelectionModel().getSelectedIndex());
        int next = Math.max(0, Math.min(entries.size() - 1, current + delta));
        files.getSelectionModel().select(next);
        files.scrollTo(next);
    }

    private void show(int index) {
        if (index < 0 || index >= entries.size()) {
            root.setCenter(null);
            position.setText("");
            return;
        }
        root.setCenter(entries.get(index).pane().node());
        position.setText(tr("diff.filePosition", index + 1, entries.size()));
    }

    private static Button button(Node icon, String tip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon);
        button.setAccessibleText(tip);
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("flat", "diff-toolbar-button");
        button.setOnAction(e -> action.run());
        return button;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node icon() {
        return Icons.diff();
    }

    private static final class FileCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = new Label(entry.label());
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label stat = new Label("+" + entry.additions() + "  −" + entry.deletions());
            stat.getStyleClass().add("diff-summary");
            Label status = new Label(entry.status());
            status.getStyleClass().add("patch-file-status");
            status.setVisible(!entry.status().isBlank());
            status.setManaged(!entry.status().isBlank());
            HBox row = new HBox(8, status, name, stat);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
            setAccessibleText(entry.label()
                    + (entry.status().isBlank() ? "" : ", " + entry.status())
                    + ", plus "
                    + entry.additions()
                    + ", minus "
                    + entry.deletions());
        }
    }
}
