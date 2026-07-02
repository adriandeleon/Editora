package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.csv.CsvColumns;
import com.editora.csv.CsvColumns.ColumnType;

import static com.editora.i18n.Messages.tr;

/**
 * Read-only grid preview of a CSV/TSV buffer — a spreadsheet-style {@link TableView} built from the parsed
 * rows, with a "first row is a header" toggle and a summary line (row/column counts + a lightweight column
 * type profile). Double-clicking (or Enter on) a cell jumps the editor caret to that field. The parsing is
 * done by the coordinator (off the parse-once path); this panel only renders {@code List<List<String>>}.
 */
final class CsvGridPanel extends VBox implements ToolWindowContent {

    /** Jumps the editor caret to a grid cell: {@code lineIndex} = 0-based document paragraph, {@code field}
     *  = 0-based column. The coordinator resolves the field to a character offset. */
    interface Jump {
        void jump(int lineIndex, int field);
    }

    private final Jump jump;
    private final CheckBox headerToggle = new CheckBox(tr("csvgrid.headerRow"));
    private final Label summary = new Label();
    private final TableView<List<String>> table = new TableView<>();

    private List<List<String>> rows = List.of();
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
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                jumpToFocusedCell();
            }
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                jumpToFocusedCell();
            }
        });
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
