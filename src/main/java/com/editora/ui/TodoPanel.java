package com.editora.ui;

import java.nio.file.Path;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.todo.TodoMatch;
import com.editora.todo.TodoService;

import static com.editora.i18n.Messages.tr;

/**
 * The "TODO" results tool window: every configured-pattern match grouped by file, each row tagged with
 * its pattern's color (Enter / double-click jumps to it). Auto-scoped by the controller — the open
 * project's tree when one is open, else the open buffers. Mirrors {@link SearchPanel}
 * ({@link ToolWindowContent}); work is routed back to the controller via {@link Actions}.
 */
public final class TodoPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks. */
    public interface Actions {
        void openMatch(Path file, int line, int col);

        void refresh();
    }

    /** A tree row: a file header ({@code match == null}) or a single match under it. */
    private record Row(Path file, TodoMatch match) {}

    private final Actions actions;
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();

    public TodoPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("todo-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        Button refreshBtn = new Button();
        refreshBtn.setGraphic(Icons.refresh());
        refreshBtn.getStyleClass().add("todo-refresh");
        refreshBtn.setTooltip(new Tooltip(tr("todo.refresh")));
        refreshBtn.setOnAction(e -> actions.refresh());
        summary.getStyleClass().add("todo-summary");
        HBox.setHgrow(summary, Priority.ALWAYS);
        HBox toolbar = new HBox(6, summary, refreshBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new RowCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(toolbar, tree);
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                activateSelected();
                e.consume();
            }
            case DOWN -> {
                moveSelection(1);
                e.consume();
            }
            case UP -> {
                moveSelection(-1);
                e.consume();
            }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        moveSelection(1);
                        e.consume();
                    }
                    case P -> {
                        moveSelection(-1);
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

    private void moveSelection(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int i = tree.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(rows - 1, (i < 0 ? 0 : i) + delta));
        tree.getSelectionModel().select(next);
        tree.scrollTo(next);
    }

    private void activateSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Row row = item.getValue();
        if (row.match() != null) {
            actions.openMatch(row.file(), row.match().line(), row.match().col());
        } else {
            item.setExpanded(!item.isExpanded());
        }
    }

    /** Populates the tree from a scan outcome (called on the FX thread by the controller). */
    public void setResults(TodoService.Outcome outcome) {
        TreeItem<Row> root = new TreeItem<>();
        for (TodoService.FileTodos fr : outcome.files()) {
            TreeItem<Row> fileItem = new TreeItem<>(new Row(fr.file(), null));
            fileItem.setExpanded(true);
            for (TodoMatch m : fr.matches()) {
                fileItem.getChildren().add(new TreeItem<>(new Row(fr.file(), m)));
            }
            root.getChildren().add(fileItem);
        }
        tree.setRoot(root);
        summary.setText(
                outcome.totalMatches() == 0
                        ? tr("todo.none")
                        : tr(
                                outcome.truncated() ? "todo.summaryTruncated" : "todo.summary",
                                outcome.totalMatches(),
                                outcome.fileCount()));
    }

    @Override
    public void focusFirstItem() {
        actions.refresh(); // auto-scan whenever the tool window is shown (any entry point)
        tree.requestFocus();
        if (tree.getExpandedItemCount() > 0) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a file header (name + match count) or a match line (color tag + line number + preview). */
    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().remove("todo-file-row");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (row.match() == null) {
                int count =
                        getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                setGraphic(null);
                setText(row.file().getFileName() + "  (" + count + ")");
                if (!getStyleClass().contains("todo-file-row")) {
                    getStyleClass().add("todo-file-row");
                }
            } else {
                TodoMatch m = row.match();
                Region tag = new Region();
                tag.getStyleClass().add("todo-color-tag");
                tag.setMinSize(10, 10);
                tag.setPrefSize(10, 10);
                tag.setMaxSize(10, 10);
                tag.setStyle("-fx-background-color: " + safeColor(m.color()) + ";");
                setGraphic(tag);
                String preview = m.lineText().strip();
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "…";
                }
                setText(m.line() + ":  " + preview);
            }
        }

        private static String safeColor(String web) {
            return web == null || web.isBlank() ? "#E5C07B" : web;
        }
    }
}
