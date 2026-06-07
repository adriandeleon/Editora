package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspDiagnostic;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The "Problems" tool window: LSP diagnostics across open buffers, grouped by file (Enter / double-click
 * jumps to one). Mirrors {@link SearchPanel} ({@link ToolWindowContent}); jumps are routed back to the
 * controller via {@link Actions}. The controller pushes the current diagnostics map via
 * {@link #setProblems}.
 */
public final class ProblemsPanel extends VBox implements ToolWindowContent {

    /** Controller callback to open a file at a diagnostic position (0-based line/col). */
    public interface Actions {
        void open(Path file, int line, int col);
    }

    /** A tree row: a file header ({@code diag == null}) or a single diagnostic under it. */
    private record Row(Path file, LspDiagnostic diag) {
    }

    private final Actions actions;
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();

    public ProblemsPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("problems-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        summary.getStyleClass().add("search-summary");
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
        getChildren().addAll(summary, tree);
        setProblems(Map.of());
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> { activateSelected(); e.consume(); }
            case DOWN -> { moveSelection(1); e.consume(); }
            case UP -> { moveSelection(-1); e.consume(); }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> { moveSelection(1); e.consume(); }
                    case P -> { moveSelection(-1); e.consume(); }
                    case M -> { activateSelected(); e.consume(); }
                    default -> { }
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
        if (row.diag() != null) {
            actions.open(row.file(), row.diag().startLine(), row.diag().startCol());
        } else {
            item.setExpanded(!item.isExpanded());
        }
    }

    /** Rebuilds the tree from the current per-file diagnostics (called on the FX thread). */
    public void setProblems(Map<Path, List<LspDiagnostic>> byFile) {
        TreeItem<Row> root = new TreeItem<>();
        int total = 0;
        int files = 0;
        for (var entry : byFile.entrySet()) {
            List<LspDiagnostic> diags = entry.getValue();
            if (diags == null || diags.isEmpty()) {
                continue;
            }
            files++;
            List<LspDiagnostic> sorted = new ArrayList<>(diags);
            sorted.sort((a, b) -> a.startLine() != b.startLine()
                    ? Integer.compare(a.startLine(), b.startLine())
                    : Integer.compare(a.startCol(), b.startCol()));
            TreeItem<Row> fileItem = new TreeItem<>(new Row(entry.getKey(), null));
            fileItem.setExpanded(true);
            for (LspDiagnostic d : sorted) {
                fileItem.getChildren().add(new TreeItem<>(new Row(entry.getKey(), d)));
                total++;
            }
            root.getChildren().add(fileItem);
        }
        tree.setRoot(root);
        summary.setText(total == 0 ? tr("problems.none") : tr("problems.summary", total, files));
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a file header (name + count) or a diagnostic (severity glyph + line + message). */
    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("search-file-row", "problem-error", "problem-warning",
                    "problem-info");
            if (empty || row == null) {
                setText(null);
                return;
            }
            if (row.diag() == null) {
                int count = getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                setText(row.file().getFileName() + "  (" + count + ")");
                getStyleClass().add("search-file-row");
            } else {
                LspDiagnostic d = row.diag();
                String msg = d.message().replace('\n', ' ').strip();
                if (msg.length() > 200) {
                    msg = msg.substring(0, 200) + "…";
                }
                setText(glyph(d.severity()) + "  " + (d.startLine() + 1) + ":  " + msg);
                getStyleClass().add(switch (d.severity()) {
                    case ERROR -> "problem-error";
                    case WARNING -> "problem-warning";
                    default -> "problem-info";
                });
            }
        }

        private static String glyph(LspDiagnostic.Severity s) {
            return switch (s) {
                case ERROR -> "✖";   // ✖
                case WARNING -> "⚠"; // ⚠
                default -> "ℹ";      // ℹ
            };
        }
    }
}
