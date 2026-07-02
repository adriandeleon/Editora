package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import javafx.scene.control.TableRow;
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
 * a "first row is a header" toggle, a filter box, per-column sorting, a summary line (row/column counts + a
 * lightweight column type profile + an inconsistent-column count), and inconsistent-("ragged")-row
 * highlighting. Parsing is done by the coordinator; this panel only renders {@code List<List<String>>}.
 *
 * <p>Each row is wrapped in a {@link Row} carrying its <b>original data-row index</b>, so filtering and
 * sorting reorder the view without breaking the caret-jump / edit-write-back mapping (both resolve back to
 * the original line via {@code Row.index}). When the coordinator marks the grid <b>editable</b>, cells become
 * {@link TextFieldTableCell}s and a committed edit calls {@link EditCommit}; column headers rename on
 * double-click (writing the buffer's line 0). Otherwise the grid is read-only and double-click / Enter (or
 * the right-click "Reveal in editor") jumps the editor caret to the cell's field.
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

    /** A data row plus its original 0-based index (stable through sort/filter, so edits/jumps map back). */
    private record Row(int index, List<String> cells) {}

    private final Jump jump;
    private final CheckBox headerToggle = new CheckBox(tr("csvgrid.headerRow"));
    private final TextField filterField = new TextField();
    private final Label summary = new Label();
    private final TableView<Row> table = new TableView<>();

    private List<List<String>> rows = List.of();
    private boolean editable;
    private EditCommit editCommit = (r, f, v) -> {};
    private ExportActions exportActions;
    private Runnable onShown = () -> {};

    // View state (survives rebuilds so an edit's re-parse keeps the current sort/filter).
    private int sortColumn = -1; // -1 = unsorted; else 0-based data column
    private boolean sortAscending = true;
    private int expectedCols; // for ragged-row highlighting
    private final List<Row> allRows = new ArrayList<>(); // unfiltered/unsorted data rows
    private final List<Label> headerLabels = new ArrayList<>();
    private final List<String> baseTitles = new ArrayList<>();

    CsvGridPanel(Jump jump) {
        this.jump = jump;
        getStyleClass().add("csv-grid");
        setSpacing(4);
        setPadding(new Insets(6));

        headerToggle.setSelected(true);
        headerToggle.getStyleClass().add("csv-grid-header-toggle");
        headerToggle.selectedProperty().addListener((o, was, is) -> rebuild());

        filterField.setPromptText(tr("csvgrid.filterPrompt"));
        filterField.getStyleClass().add("csv-grid-filter");
        filterField.setPrefColumnCount(14);
        filterField.textProperty().addListener((o, was, is) -> applyView());

        summary.getStyleClass().add("csv-grid-summary");
        HBox top = new HBox(8, headerToggle, filterField, spacer(), summary);
        top.getStyleClass().add("csv-grid-bar");
        top.setAlignment(Pos.CENTER_LEFT);

        // "striped" is AtlantaFX's zebra-row style class; "csv-grid-table" carries our border/padding tweaks.
        table.getStyleClass().addAll("csv-grid-table", "striped");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.setPlaceholder(new Label(tr("csvgrid.empty")));
        table.setRowFactory(t -> raggedAwareRow());
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
        buildContextMenu();
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(top, table);
    }

    private void buildContextMenu() {
        MenuItem reveal = new MenuItem(tr("csvgrid.reveal"));
        reveal.setOnAction(e -> jumpToFocusedCell());
        MenuItem sortAsc = new MenuItem(tr("csvgrid.sortAsc"));
        sortAsc.setOnAction(e -> sortByFocusedColumn(true));
        MenuItem sortDesc = new MenuItem(tr("csvgrid.sortDesc"));
        sortDesc.setOnAction(e -> sortByFocusedColumn(false));
        MenuItem clearSort = new MenuItem(tr("csvgrid.clearSort"));
        clearSort.setOnAction(e -> {
            sortColumn = -1;
            applyView();
        });
        MenuItem exportPdf = new MenuItem(tr("csvgrid.exportPdf"));
        exportPdf.setOnAction(e -> runExport(ExportActions::exportPdf));
        MenuItem print = new MenuItem(tr("csvgrid.print"));
        print.setOnAction(e -> runExport(ExportActions::printPreview));
        MenuItem exportExcel = new MenuItem(tr("csvgrid.exportExcel"));
        exportExcel.setOnAction(e -> runExport(ExportActions::exportExcel));
        MenuItem exportOds = new MenuItem(tr("csvgrid.exportOds"));
        exportOds.setOnAction(e -> runExport(ExportActions::exportOds));
        table.setContextMenu(new ContextMenu(
                reveal,
                new SeparatorMenuItem(),
                sortAsc,
                sortDesc,
                clearSort,
                new SeparatorMenuItem(),
                exportPdf,
                print,
                exportExcel,
                exportOds));
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
        headerLabels.clear();
        baseTitles.clear();
        allRows.clear();
        int cols = 0;
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        if (rows.isEmpty() || cols == 0) {
            table.setItems(FXCollections.observableArrayList());
            summary.setText(tr("csvgrid.empty"));
            return;
        }
        boolean header = headerToggle.isSelected();
        List<String> headerRow = header ? rows.get(0) : null;
        List<ColumnType> types = CsvColumns.inferTypes(rows, header);
        expectedCols = CsvColumns.expectedColumns(rows, header);
        List<Integer> contentLengths = CsvColumns.maxContentLengths(rows);

        table.getColumns().add(rowNumberColumn(rows.size()));
        for (int c = 0; c < cols; c++) {
            final int col = c;
            String title =
                    header && col < headerRow.size() && !headerRow.get(col).isBlank()
                            ? headerRow.get(col)
                            : tr("csvgrid.column", col + 1);
            baseTitles.add(title);
            boolean numeric = col < types.size() && CsvColumns.isNumeric(types.get(col));
            TableColumn<Row, String> tc = new TableColumn<>();
            tc.setGraphic(headerNode(tc, col, title, header)); // editable header label (double-click to rename)
            tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                    col < cd.getValue().cells().size() ? cd.getValue().cells().get(col) : ""));
            // Auto-size to the widest of (content, header title + its arrow/rename affordance) so a column
            // rarely needs a manual resize; the header is bold, so give the title a little extra headroom.
            int contentChars = col < contentLengths.size() ? contentLengths.get(col) : 0;
            int chars = Math.max(contentChars, title.length() + HEADER_EXTRA_CHARS);
            tc.setPrefWidth(columnWidthFor(chars));
            tc.setSortable(false);
            tc.setCellFactory(dataCellFactory(numeric));
            if (editable) {
                tc.setOnEditCommit(ev -> onCellCommitted(ev.getTablePosition().getRow(), col, ev.getNewValue()));
            }
            table.getColumns().add(tc);
        }
        // Wrap each data row with its original 0-based index (stable through sort/filter).
        int start = header ? 1 : 0;
        for (int r = start; r < rows.size(); r++) {
            allRows.add(new Row(r - start, rows.get(r)));
        }
        if (sortColumn >= cols) {
            sortColumn = -1;
        }
        applyView();
    }

    /** Applies the current filter + sort to {@link #allRows} → the visible items, and refreshes the summary. */
    private void applyView() {
        String query = filterField.getText() == null
                ? ""
                : filterField.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> view = new ArrayList<>();
        for (Row row : allRows) {
            if (query.isEmpty() || rowMatches(row, query)) {
                view.add(row);
            }
        }
        if (sortColumn >= 0) {
            int col = sortColumn;
            view.sort((a, b) -> {
                int cmp = compareCells(cell(a, col), cell(b, col));
                return sortAscending ? cmp : -cmp;
            });
        }
        table.setItems(FXCollections.observableArrayList(view));
        refreshHeaderArrows();
        int cols = baseTitles.size();
        int ragged = CsvColumns.raggedRowCount(rows, headerToggle.isSelected());
        summary.setText(summaryText(
                allRows.size(), view.size(), cols, CsvColumns.inferTypes(rows, headerToggle.isSelected()), ragged));
    }

    private static boolean rowMatches(Row row, String lowerQuery) {
        for (String cell : row.cells()) {
            if (cell.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    private static String cell(Row row, int col) {
        return col < row.cells().size() ? row.cells().get(col) : "";
    }

    /** Numeric when both cells parse as numbers, else case-insensitive text. */
    private static int compareCells(String a, String b) {
        Double na = tryNumber(a);
        Double nb = tryNumber(b);
        if (na != null && nb != null) {
            return Double.compare(na, nb);
        }
        return a.compareToIgnoreCase(b);
    }

    private static Double tryNumber(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sortByFocusedColumn(boolean ascending) {
        TablePosition<?, ?> pos =
                table.getFocusModel() == null ? null : table.getFocusModel().getFocusedCell();
        if (pos == null) {
            return;
        }
        int col = pos.getColumn() - 1; // column 0 is the "#" gutter
        if (col < 0 || col >= baseTitles.size()) {
            return;
        }
        sortColumn = col;
        sortAscending = ascending;
        applyView();
    }

    /** Appends a ▲/▼ arrow to the sorted column's header label; clears the others. */
    private void refreshHeaderArrows() {
        for (int i = 0; i < headerLabels.size(); i++) {
            String base = i < baseTitles.size() ? baseTitles.get(i) : "";
            String arrow = i == sortColumn ? (sortAscending ? "  ▲" : "  ▼") : "";
            headerLabels.get(i).setText(base + arrow);
        }
    }

    /** A table row that flags an inconsistent-column ("ragged") data row via {@code .csv-grid-ragged-row}. */
    private TableRow<Row> raggedAwareRow() {
        return new TableRow<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                boolean ragged = !empty
                        && item != null
                        && expectedCols > 0
                        && item.cells().size() != expectedCols;
                pseudoClassStateChanged(RAGGED, ragged);
            }
        };
    }

    private static final javafx.css.PseudoClass RAGGED = javafx.css.PseudoClass.getPseudoClass("csv-ragged");

    // Column auto-sizing: an approximate advance for the grid's UI font + cell padding, clamped so a column
    // fits its content without a manual resize but a very long value can't blow the table off-screen.
    private static final double CHAR_PX = 7.5;
    private static final double CELL_PADDING = 22;
    private static final double MIN_COL_WIDTH = 56;
    private static final double MAX_COL_WIDTH = 600;
    private static final int HEADER_EXTRA_CHARS = 3; // room for the sort ▲/▼ (and the bold header's heavier glyphs)

    /** Pixel width for a column whose widest cell is {@code chars} characters, clamped to a sane range. */
    static double columnWidthFor(int chars) {
        double w = Math.max(0, chars) * CHAR_PX + CELL_PADDING;
        return Math.max(MIN_COL_WIDTH, Math.min(MAX_COL_WIDTH, w));
    }

    /** A non-editable leading "#" column showing each row's original 1-based data-row number. */
    private TableColumn<Row, String> rowNumberColumn(int totalPhysicalRows) {
        TableColumn<Row, String> num = new TableColumn<>("#");
        num.setSortable(false);
        num.setEditable(false);
        // Fit the largest row number (e.g. 5 digits for a 10k-row file) so the gutter never truncates.
        num.setPrefWidth(Math.max(
                40, String.valueOf(Math.max(1, totalPhysicalRows)).length() * CHAR_PX + 22));
        num.setReorderable(false);
        num.getStyleClass().add("csv-grid-rownum");
        num.setCellValueFactory(
                cd -> new ReadOnlyStringWrapper(String.valueOf(cd.getValue().index() + 1)));
        num.setCellFactory(c -> {
            TableCell<Row, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty ? null : v);
                }
            };
            cell.getStyleClass().add("csv-grid-rownum-cell");
            return cell;
        });
        return num;
    }

    /** Cell factory for a data column: {@link TextFieldTableCell} when editable, right-aligned for numbers. */
    private Callback<TableColumn<Row, String>, TableCell<Row, String>> dataCellFactory(boolean numeric) {
        Callback<TableColumn<Row, String>, TableCell<Row, String>> base =
                editable ? TextFieldTableCell.forTableColumn() : c -> defaultTextCell();
        if (!numeric) {
            return base;
        }
        return column -> {
            TableCell<Row, String> cell = base.call(column);
            cell.setAlignment(Pos.CENTER_RIGHT);
            return cell;
        };
    }

    private static TableCell<Row, String> defaultTextCell() {
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
    private Label headerNode(TableColumn<Row, String> tc, int field, String title, boolean hasHeader) {
        Label label = new Label(title);
        label.getStyleClass().add("csv-grid-col-header");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && editable && hasHeader) {
                startHeaderEdit(tc, field, baseTitles.get(field));
            }
        });
        if (field < headerLabels.size()) {
            headerLabels.set(field, label);
        } else {
            headerLabels.add(label);
        }
        return label;
    }

    private void startHeaderEdit(TableColumn<Row, String> tc, int field, String current) {
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

    private void finishHeaderEdit(TableColumn<Row, String> tc, TextField tf, int field) {
        if (tc.getGraphic() != tf) {
            return; // already committed/cancelled — the Enter + focus-loss double-fire guard
        }
        String value = tf.getText();
        String display = value.isBlank() ? tr("csvgrid.column", field + 1) : value;
        baseTitles.set(field, display);
        tc.setGraphic(headerNode(tc, field, display, true));
        editCommit.commit(-1, field, value); // -1 → the header row (line 0)
    }

    /** "{shown}/{total} rows × {cols} cols · {n} number, {m} text[ · {r} inconsistent]". */
    private static String summaryText(int total, int shown, int cols, List<ColumnType> types, int ragged) {
        int numeric = 0;
        int text = 0;
        for (ColumnType t : types) {
            if (CsvColumns.isNumeric(t)) {
                numeric++;
            } else if (t == ColumnType.TEXT || t == ColumnType.BOOLEAN) {
                text++;
            }
        }
        String rowsPart = shown == total ? String.valueOf(total) : shown + "/" + total;
        String base = tr("csvgrid.summary", rowsPart, cols, numeric, text);
        return ragged > 0 ? base + " " + tr("csvgrid.ragged", ragged) : base;
    }

    /** A cell edit was committed ({@code viewRow} = index into the visible items). Update the row's cell so it
     *  shows immediately, then write it back through the coordinator using the row's <em>original</em> index. */
    private void onCellCommitted(int viewRow, int col, String value) {
        if (viewRow < 0 || viewRow >= table.getItems().size()) {
            return;
        }
        Row row = table.getItems().get(viewRow);
        List<String> cells = row.cells();
        while (cells.size() <= col) {
            cells.add("");
        }
        cells.set(col, value);
        table.refresh();
        editCommit.commit(row.index(), col, value);
    }

    private void jumpToFocusedCell() {
        TablePosition<?, ?> pos =
                table.getFocusModel() == null ? null : table.getFocusModel().getFocusedCell();
        if (pos == null || pos.getRow() < 0 || pos.getRow() >= table.getItems().size()) {
            return;
        }
        Row row = table.getItems().get(pos.getRow());
        // Column 0 is the "#" row-number gutter, so the data field is the visible column minus one.
        int field = Math.max(0, pos.getColumn() - 1);
        // Map the row back to a document paragraph via its ORIGINAL index (stable through sort/filter): the
        // header (if any) occupies line 0, so the first data row is line 1.
        int lineIndex = headerToggle.isSelected() ? row.index() + 1 : row.index();
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
