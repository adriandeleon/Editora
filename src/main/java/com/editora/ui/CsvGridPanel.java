package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.csv.CsvColumns;
import com.editora.csv.CsvColumns.ColumnType;

import static com.editora.i18n.Messages.tr;

/**
 * Grid preview of a CSV/TSV buffer — a spreadsheet-style {@link TableView} built from the parsed rows, with
 * a "first row is a header" toggle and a summary line (row/column counts + a lightweight column type
 * profile). The parsing is done by the coordinator (off the parse-once path); this panel only renders
 * {@code List<List<String>>}.
 *
 * <p>When the coordinator marks the grid <b>editable</b> (an editable buffer whose rows map 1:1 to physical
 * lines — no quoted multi-line fields), cells become {@link TextFieldTableCell}s and committing an edit calls
 * back through {@link EditCommit} to rewrite that row in the buffer (undoable). Otherwise the grid is
 * read-only and double-click / Enter jumps the editor caret to the cell's field; the right-click
 * "Reveal in editor" item jumps in either mode.
 */
final class CsvGridPanel extends VBox implements ToolWindowContent {

    /** Jumps the editor caret to a grid cell: {@code lineIndex} = 0-based document paragraph, {@code field}
     *  = 0-based column. The coordinator resolves the field to a character offset. */
    interface Jump {
        void jump(int lineIndex, int field);
    }

    /** Commits a cell edit: rewrite {@code dataRow} (0-based, excluding the header) column {@code field} to
     *  {@code value} in the buffer. */
    interface EditCommit {
        void commit(int dataRow, int field, String value);
    }

    private final Jump jump;
    private final CheckBox headerToggle = new CheckBox(tr("csvgrid.headerRow"));
    private final Label summary = new Label();
    private final TableView<List<String>> table = new TableView<>();

    private List<List<String>> rows = List.of();
    private boolean editable;
    private EditCommit editCommit = (r, f, v) -> {};
    /** Invoked when the tool window is shown (via {@link #focusFirstItem()}) so the coordinator can populate
     *  the grid from the current buffer even when the window was opened by the stripe, not a command. */
    private Runnable onShown = () -> {};

    CsvGridPanel(Jump jump) {
        this.jump = jump;
        getStyleClass().add("csv-grid");
        setSpacing(4);
        setPadding(new Insets(6));

        headerToggle.setSelected(true);
        headerToggle.getStyleClass().add("csv-grid-header-toggle");
        headerToggle.selectedProperty().addListener((o, was, is) -> rebuild());

        summary.getStyleClass().add("csv-grid-summary");
        HBox top = new HBox(8, headerToggle, spacer(), summary);
        top.getStyleClass().add("csv-grid-bar");

        table.getStyleClass().add("csv-grid-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.setPlaceholder(new Label(tr("csvgrid.empty")));
        // Double-click / Enter jumps to the field — but only in read-only mode; when editable, those
        // gestures start/commit a cell edit instead. "Reveal in editor" (context menu) jumps in either mode.
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !editable) {
                jumpToFocusedCell();
            }
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !editable) {
                jumpToFocusedCell();
            }
        });
        MenuItem reveal = new MenuItem(tr("csvgrid.reveal"));
        reveal.setOnAction(e -> jumpToFocusedCell());
        table.setContextMenu(new ContextMenu(reveal));
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(top, table);
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Replaces the grid contents with {@code parsedRows} (already parsed by the coordinator). */
    void setData(List<List<String>> parsedRows) {
        this.rows = parsedRows == null ? List.of() : parsedRows;
        rebuild();
    }

    /** Enables/disables in-place cell editing; when enabled, a committed edit calls {@code onCommit}. Stores
     *  the state only — the caller must follow with {@link #setData} to rebuild the columns. */
    void setEditable(boolean editable, EditCommit onCommit) {
        this.editable = editable;
        this.editCommit = onCommit == null ? (r, f, v) -> {} : onCommit;
        table.setEditable(editable);
    }

    /** Whether the first row is treated as a header (the coordinator adds 1 to the data-row→line mapping). */
    boolean isHeaderRow() {
        return headerToggle.isSelected();
    }

    /** Sets the callback run when the tool window is shown (to populate the grid from the current buffer). */
    void setOnShown(Runnable onShown) {
        this.onShown = onShown == null ? () -> {} : onShown;
    }

    private void rebuild() {
        table.getColumns().clear();
        table.setItems(FXCollections.observableArrayList());
        int cols = 0;
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        if (rows.isEmpty() || cols == 0) {
            summary.setText(tr("csvgrid.empty"));
            return;
        }
        boolean header = headerToggle.isSelected();
        List<String> headerRow = header ? rows.get(0) : null;
        for (int c = 0; c < cols; c++) {
            final int col = c;
            String title =
                    header && col < headerRow.size() && !headerRow.get(col).isBlank()
                            ? headerRow.get(col)
                            : tr("csvgrid.column", col + 1);
            TableColumn<List<String>, String> tc = new TableColumn<>(title);
            tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                    col < cd.getValue().size() ? cd.getValue().get(col) : ""));
            tc.setPrefWidth(120);
            tc.setSortable(false);
            if (editable) {
                tc.setCellFactory(TextFieldTableCell.forTableColumn());
                tc.setOnEditCommit(ev -> onCellCommitted(ev.getTablePosition().getRow(), col, ev.getNewValue()));
            }
            table.getColumns().add(tc);
        }
        List<List<String>> data = header ? rows.subList(1, rows.size()) : rows;
        table.setItems(FXCollections.observableArrayList(new ArrayList<>(data)));
        summary.setText(summaryText(rows.size() - (header ? 1 : 0), cols, CsvColumns.inferTypes(rows, header)));
    }

    /** "{rows} rows × {cols} cols · {n} number, {m} text" — the lightweight column profile. */
    private static String summaryText(int dataRows, int cols, List<ColumnType> types) {
        int numeric = 0;
        int text = 0;
        for (ColumnType t : types) {
            if (CsvColumns.isNumeric(t)) {
                numeric++;
            } else if (t == ColumnType.TEXT || t == ColumnType.BOOLEAN) {
                text++;
            }
        }
        return tr("csvgrid.summary", dataRows, cols, numeric, text);
    }

    /** A cell edit was committed ({@code viewRow} = index into the visible items = the data row). Update the
     *  item so the cell shows the new value immediately, then write it back through the coordinator. */
    private void onCellCommitted(int viewRow, int col, String value) {
        if (viewRow < 0 || viewRow >= table.getItems().size()) {
            return;
        }
        List<String> item = table.getItems().get(viewRow);
        while (item.size() <= col) {
            item.add("");
        }
        item.set(col, value);
        table.refresh();
        editCommit.commit(viewRow, col, value);
    }

    private void jumpToFocusedCell() {
        TablePosition<?, ?> pos =
                table.getFocusModel() == null ? null : table.getFocusModel().getFocusedCell();
        if (pos == null || pos.getRow() < 0) {
            return;
        }
        int dataRow = pos.getRow();
        int field = Math.max(0, pos.getColumn());
        // Map the grid's data row back to a document paragraph: the header (if any) occupies line 0, so the
        // first data row is line 1. Approximate for CSV whose quoted fields span physical lines.
        int lineIndex = headerToggle.isSelected() ? dataRow + 1 : dataRow;
        jump.jump(lineIndex, field);
    }

    @Override
    public void focusFirstItem() {
        onShown.run(); // populate from the current buffer (covers stripe-open, not just the command)
        table.requestFocus();
        if (!table.getItems().isEmpty()) {
            table.getSelectionModel().select(0);
            table.getFocusModel()
                    .focus(
                            0,
                            table.getColumns().isEmpty()
                                    ? null
                                    : table.getColumns().get(0));
        }
    }
}
