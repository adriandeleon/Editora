package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import static com.editora.i18n.Messages.tr;

/**
 * The "References" tool window: the results of Find References ({@code textDocument/references}), grouped
 * <b>by file</b> and browsable (unlike the transient picker). Enter / double-click jumps to a reference; a
 * file header toggles. Mirrors {@link ProblemsPanel} ({@link ToolWindowContent}); jumps route back through
 * {@link Actions}. The coordinator pushes results via {@link #setReferences}.
 */
public final class ReferencesPanel extends VBox implements ToolWindowContent {

    /** Controller callback to open a file at a 0-based reference position. */
    public interface Actions {
        void open(Path file, int line, int col);
    }

    /** One reference occurrence: a file + 0-based line/col + a one-line preview ({@code ""} when unavailable). */
    public record Reference(Path file, int line, int col, String preview) {}

    /** A tree row: a <b>file header</b> ({@code ref == null}) or a <b>reference</b> ({@code ref != null}). */
    private record Row(Path file, Reference ref) {}

    private final Actions actions;
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();

    public ReferencesPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("references-panel");
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
        setReferences(List.of());
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
        if (row.ref() != null) {
            actions.open(row.ref().file(), row.ref().line(), row.ref().col());
        } else {
            item.setExpanded(!item.isExpanded());
        }
    }

    /** Rebuilds the tree, grouping {@code refs} by file (called on the FX thread). Selects the first row. */
    public void setReferences(List<Reference> refs) {
        Map<Path, List<Reference>> byFile = new LinkedHashMap<>();
        for (Reference r : refs) {
            byFile.computeIfAbsent(r.file(), k -> new ArrayList<>()).add(r);
        }
        List<Path> files = new ArrayList<>(byFile.keySet());
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

        TreeItem<Row> root = new TreeItem<>();
        for (Path file : files) {
            List<Reference> list = byFile.get(file);
            list.sort((a, b) ->
                    a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col()));
            TreeItem<Row> fileItem = new TreeItem<>(new Row(file, null));
            fileItem.setExpanded(true);
            for (Reference r : list) {
                fileItem.getChildren().add(new TreeItem<>(new Row(file, r)));
            }
            root.getChildren().add(fileItem);
        }
        tree.setRoot(root);
        summary.setText(refs.isEmpty() ? tr("references.none") : tr("references.summary", refs.size(), files.size()));
        if (tree.getExpandedItemCount() > 0) {
            tree.getSelectionModel().select(0);
        }
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a file header (name + count) or a reference (line + preview). */
    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("search-file-row");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (row.ref() != null) {
                Reference r = row.ref();
                String preview = r.preview() == null ? "" : r.preview().strip();
                setText((r.line() + 1) + ":" + (r.col() + 1) + (preview.isEmpty() ? "" : "  " + preview));
                setGraphic(null);
            } else {
                int count =
                        getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                setText(row.file().getFileName() + "  (" + count + ")");
                setGraphic(FileIcons.forFileName(String.valueOf(row.file().getFileName())));
                getStyleClass().add("search-file-row");
            }
        }
    }
}
