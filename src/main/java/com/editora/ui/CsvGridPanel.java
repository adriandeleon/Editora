package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

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

    /** Commits a cell edit: rewrite {@code dataRow} column {@code field} to {@code value} in the buffer.
     *  {@code dataRow} is 0-based over the data rows (excluding the header); {@code -1} means the header row. */
    interface EditCommit {
        void commit(int dataRow, int field, String value);
    }

    /** Export actions for the right-click menu (each acts on the whole file/grid). */
    interface ExportActions {
        void exportPdf();

        void printPreview();

        void exportExcel();

        void exportOds();
    }

    private final Jump jump;
    private final CheckBox headerToggle = new CheckBox(tr("csvgrid.headerRow"));
    private final Label summary = new Label();
    private final TableView<List<String>> table = new TableView<>();

    private List<List<String>> rows = List.of();
    private boolean editable;
    private EditCommit editCommit = (r, f, v) -> {};
    private ExportActions exportActions;
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

        // "striped" is AtlantaFX's zebra-row style class; "csv-grid-table" carries our border/padding tweaks.
        table.getStyleClass().addAll("csv-grid-table", "striped");
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
        MenuItem exportPdf = new MenuItem(tr("csvgrid.exportPdf"));
        exportPdf.setOnAction(e -> runExport(ExportActions::exportPdf));
        MenuItem print = new MenuItem(tr("csvgrid.print"));
        print.setOnAction(e -> runExport(ExportActions::printPreview));
        MenuItem exportExcel = new MenuItem(tr("csvgrid.exportExcel"));
        exportExcel.setOnAction(e -> runExport(ExportActions::exportExcel));
        MenuItem exportOds = new MenuItem(tr("csvgrid.exportOds"));
        exportOds.setOnAction(e -> runExport(ExportActions::exportOds));
        table.setContextMenu(
                new ContextMenu(reveal, new SeparatorMenuItem(), exportPdf, print, exportExcel, exportOds));
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

    /** Injects the right-click export actions (PDF / Print / Excel / ODS). */
    void setExportActions(ExportActions exportActions) {
        this.exportActions = exportActions;
    }

    private void runExport(java.util.function.Consumer<ExportActions> action) {
        if (exportActions != null) {
            action.accept(exportActions);
        }
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
        List<ColumnType> types = CsvColumns.inferTypes(rows, header);

        table.getColumns().add(rowNumberColumn());
        for (int c = 0; c < cols; c++) {
            final int col = c;
            String title =
                    header && col < headerRow.size() && !headerRow.get(col).isBlank()
                            ? headerRow.get(col)
                            : tr("csvgrid.column", col + 1);
            boolean numeric = col < types.size() && CsvColumns.isNumeric(types.get(col));
            TableColumn<List<String>, String> tc = new TableColumn<>();
            tc.setGraphic(headerNode(tc, col, title, header)); // editable header label (double-click to rename)
            tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                    col < cd.getValue().size() ? cd.getValue().get(col) : ""));
            tc.setPrefWidth(120);
            tc.setSortable(false);
            tc.setCellFactory(dataCellFactory(numeric));
            if (editable) {
                tc.setOnEditCommit(ev -> onCellCommitted(ev.getTablePosition().getRow(), col, ev.getNewValue()));
            }
            table.getColumns().add(tc);
        }
        List<List<String>> data = header ? rows.subList(1, rows.size()) : rows;
        table.setItems(FXCollections.observableArrayList(new ArrayList<>(data)));
        summary.setText(summaryText(rows.size() - (header ? 1 : 0), cols, types));
    }

    /** A non-editable leading "#" column showing 1-based data-row numbers (spreadsheet gutter). */
    private TableColumn<List<String>, String> rowNumberColumn() {
        TableColumn<List<String>, String> num = new TableColumn<>("#");
        num.setSortable(false);
        num.setEditable(false);
        num.setPrefWidth(48);
        num.setReorderable(false);
        num.getStyleClass().add("csv-grid-rownum");
        num.setCellValueFactory(cd -> new ReadOnlyStringWrapper(""));
        num.setCellFactory(c -> {
            TableCell<List<String>, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty || getIndex() < 0 ? null : String.valueOf(getIndex() + 1));
                }
            };
            cell.getStyleClass().add("csv-grid-rownum-cell"); // added once, not per updateItem
            return cell;
        });
        return num;
    }

    /** Cell factory for a data column: {@link TextFieldTableCell} when editable, right-aligned for numbers. */
    private Callback<TableColumn<List<String>, String>, TableCell<List<String>, String>> dataCellFactory(
            boolean numeric) {
        Callback<TableColumn<List<String>, String>, TableCell<List<String>, String>> base =
                editable ? TextFieldTableCell.forTableColumn() : c -> defaultTextCell();
        if (!numeric) {
            return base;
        }
        return column -> {
            TableCell<List<String>, String> cell = base.call(column);
            cell.setAlignment(Pos.CENTER_RIGHT);
            return cell;
        };
    }

    private static TableCell<List<String>, String> defaultTextCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
            }
        };
    }

    /** The header graphic for a data column — a bold label that, when the grid is editable and a header row
     *  exists, becomes an inline text field on double-click and commits the new header to the buffer's line 0. */
    private Label headerNode(TableColumn<List<String>, String> tc, int field, String title, boolean hasHeader) {
        Label label = new Label(title);
        label.getStyleClass().add("csv-grid-col-header");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && editable && hasHeader) {
                startHeaderEdit(tc, field, label.getText());
            }
        });
        return label;
    }

    private void startHeaderEdit(TableColumn<List<String>, String> tc, int field, String current) {
        TextField tf = new TextField(current);
        tf.getStyleClass().add("csv-grid-col-editor");
        tf.setOnAction(a -> finishHeaderEdit(tc, tf, field));
        tf.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                finishHeaderEdit(tc, tf, field);
            }
        });
        tf.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                tc.setGraphic(headerNode(tc, field, current, true)); // cancel — restore the label
            }
        });
        tc.setGraphic(tf);
        tf.requestFocus();
        tf.selectAll();
    }

    private void finishHeaderEdit(TableColumn<List<String>, String> tc, TextField tf, int field) {
        if (tc.getGraphic() != tf) {
            return; // already committed/cancelled — the Enter + focus-loss double-fire guard
        }
        String value = tf.getText();
        String display = value.isBlank() ? tr("csvgrid.column", field + 1) : value;
        tc.setGraphic(headerNode(tc, field, display, true));
        editCommit.commit(-1, field, value); // -1 → the header row (line 0)
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
        // Column 0 is the "#" row-number gutter, so the data field is the visible column minus one.
        int field = Math.max(0, pos.getColumn() - 1);
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
