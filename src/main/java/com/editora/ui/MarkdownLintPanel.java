package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.MarkdownLint;

import static com.editora.i18n.Messages.tr;

/**
 * The "Markdown Lint" tool window: a flat list of the active Markdown buffer's lint diagnostics
 * (Enter / double-click jumps to one). Mirrors {@link TodoPanel} ({@link ToolWindowContent}); work
 * is routed back to the controller via {@link Actions}. Scoped to the active buffer (single file).
 */
public final class MarkdownLintPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks. */
    public interface Actions {
        void open(Path file, int line, int col);

        void refresh();
    }

    private final Actions actions;
    private final Label summary = new Label();
    private final ListView<MarkdownLint.Diagnostic> list = new ListView<>();
    private Path file;

    public MarkdownLintPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("markdown-lint-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        Button refreshBtn = new Button();
        refreshBtn.setGraphic(Icons.refresh());
        refreshBtn.setTooltip(new Tooltip(tr("markdownLint.refresh")));
        refreshBtn.setOnAction(e -> actions.refresh());
        summary.getStyleClass().add("markdown-lint-summary");
        HBox.setHgrow(summary, Priority.ALWAYS);
        HBox toolbar = new HBox(6, summary, refreshBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(v -> new DiagnosticCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(toolbar, list);
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                activateSelected();
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
                    case N -> {
                        move(1);
                        e.consume();
                    }
                    case P -> {
                        move(-1);
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
        int rows = list.getItems().size();
        if (rows == 0) {
            return;
        }
        int i = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(rows - 1, (i < 0 ? 0 : i) + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void activateSelected() {
        MarkdownLint.Diagnostic d = list.getSelectionModel().getSelectedItem();
        if (d != null && file != null) {
            actions.open(file, d.line(), d.column());
        }
    }

    /** Populates the list from the active buffer's diagnostics (called on the FX thread by the controller). */
    public void setResults(Path file, List<MarkdownLint.Diagnostic> diagnostics) {
        this.file = file;
        list.getItems().setAll(diagnostics == null ? List.of() : diagnostics);
        int n = list.getItems().size();
        summary.setText(n == 0 ? tr("markdownLint.none") : tr("markdownLint.summary", n));
    }

    @Override
    public void focusFirstItem() {
        actions.refresh();
        list.requestFocus();
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    /** A row: a severity dot + "Ln L:C  CODE  message". */
    private static final class DiagnosticCell extends ListCell<MarkdownLint.Diagnostic> {
        @Override
        protected void updateItem(MarkdownLint.Diagnostic d, boolean empty) {
            super.updateItem(d, empty);
            if (empty || d == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Region tag = new Region();
            tag.getStyleClass().add("markdown-lint-tag");
            tag.getStyleClass().add(d.isError() ? "lint-error" : "lint-warning");
            tag.setMinSize(10, 10);
            tag.setPrefSize(10, 10);
            tag.setMaxSize(10, 10);
            setGraphic(tag);
            setText(tr("markdownLint.row", d.line(), d.column()) + "  " + d.code() + "  " + d.message());
        }
    }
}
