package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
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
import com.editora.editor.MenuChords;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

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

    /** Export actions for the right-click menu (each puts out the rows the grid shows — see {@link Shown}). */
    interface ExportActions {
        void exportPdf();

        void printPreview();

        void exportExcel();

        void exportOds();
    }

    /**
     * What a print or an export of a CSV holds: {@code rows} in the order to put out, under {@code header}
     * (null: no header row, every row is data), out of {@code totalRows} data rows in the file.
     */
    record Shown(@Nullable List<String> header, List<List<String>> rows, int totalRows) {
        /** Whether a filter is hiding some of the file's rows. */
        boolean filtered() {
            return rows.size() < totalRows;
        }

        /** The header (when there is one) followed by the rows — the shape the spreadsheet writers take. */
        List<List<String>> withHeader() {
            List<List<String>> all = new ArrayList<>(rows.size() + 1);
            if (header != null) {
                all.add(header);
            }
            all.addAll(rows);
            return all;
        }

        /** A whole file as parsed, in file order, its first record the header (a buffer showing no grid). */
        static Shown wholeFile(List<List<String>> parsed) {
            List<List<String>> all = new ArrayList<>(parsed);
            while (!all.isEmpty() && all.get(all.size() - 1).stream().allMatch(String::isBlank)) {
                all.remove(all.size() - 1); // drop trailing blank records
            }
            if (all.isEmpty()) {
                return new Shown(null, List.of(), 0);
            }
            return new Shown(all.get(0), List.copyOf(all.subList(1, all.size())), all.size() - 1);
        }
    }

    /**
     * A data row plus its original 0-based index (stable through sort/filter, so edits/jumps map back), and its
     * cells lower-cased once for the filter: a keystroke in the filter field no longer lower-cases every cell
     * of the file (perf review P3).
     */
    static final class Row {
        private final int index;
        private final List<String> cells;
        private String @Nullable [] lower;

        Row(int index, List<String> cells) {
            this.index = index;
            this.cells = cells;
        }

        int index() {
            return index;
        }

        List<String> cells() {
            return cells;
        }

        /** Whether a cell contains {@code lowerQuery} (already lower-cased), ignoring case. */
        boolean matches(String lowerQuery) {
            String[] folded = lower;
            if (folded == null || folded.length != cells.size()) {
                folded = new String[cells.size()];
                for (int i = 0; i < folded.length; i++) {
                    folded[i] = cells.get(i).toLowerCase(Locale.ROOT);
                }
                lower = folded;
            }
            for (String cell : folded) {
                if (cell.contains(lowerQuery)) {
                    return true;
                }
            }
            return false;
        }

        /** A cell was edited in the grid: the lower-cased copy is out of date. */
        void edited() {
            lower = null;
        }
    }

    /**
     * Everything the grid works out from parsed rows — column types, ragged rows, widths, the data rows —
     * computed by {@link #prepare} off the FX thread for a re-parse while typing (perf review P3), so the FX
     * thread only sets columns and items.
     */
    record Prepared(
            List<List<String>> rows,
            boolean header,
            int cols,
            List<ColumnType> types,
            int ragged,
            int expectedCols,
            List<Integer> contentLengths,
            List<Row> dataRows) {}

    /** Works out {@link Prepared} for {@code rows} with or without a header row; any thread. */
    static Prepared prepare(List<List<String>> rows, boolean header) {
        int cols = 0;
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        if (rows.isEmpty() || cols == 0) {
            return new Prepared(rows, header, 0, List.of(), 0, 0, List.of(), List.of());
        }
        int start = header ? 1 : 0;
        List<Row> data = new ArrayList<>(Math.max(0, rows.size() - start));
        for (int r = start; r < rows.size(); r++) {
            data.add(new Row(r - start, rows.get(r)));
        }
        return new Prepared(
                rows,
                header,
                cols,
                CsvColumns.inferTypes(rows, header),
                CsvColumns.raggedRowCount(rows, header),
                CsvColumns.expectedColumns(rows, header),
                CsvColumns.maxContentLengths(rows),
                data);
    }

    private final Jump jump;
    private final CheckBox headerToggle = new CheckBox(tr("csvgrid.headerRow"));
    private final TextField filterField = new TextField();
    private final Label summary = new Label();
    private final TableView<Row> table = new TableView<>();

    private List<List<String>> rows = List.of();
    private boolean editable;
    private EditCommit editCommit = (r, f, v) -> {};
    private @Nullable ExportActions exportActions;
    private Runnable onShown = () -> {};

    // View state (survives rebuilds so an edit's re-parse keeps the current sort/filter).
    private int sortColumn = -1; // -1 = unsorted; else 0-based data column
    private boolean sortAscending = true;
    private int expectedCols; // for ragged-row highlighting
    /** The column types and ragged-row count of the rows shown, worked out once when they are set: the
     *  filter's keystrokes only filter (PERF-08, Pauta 6.2.0). Null until then. */
    private @org.jspecify.annotations.Nullable List<ColumnType> shownTypes;

    private int shownRagged;
    private final List<Row> allRows = new ArrayList<>(); // unfiltered/unsorted data rows
    private final List<Label> headerLabels = new ArrayList<>();
    private final List<String> baseTitles = new ArrayList<>();

    CsvGridPanel(Jump jump) {
        this.jump = jump;
        getStyleClass().add("csv-grid");
        UiZoom.spacing(this, 4);
        UiZoom.padding(this, 6);

        headerToggle.setSelected(true);
        headerToggle.getStyleClass().add("csv-grid-header-toggle");
        headerToggle.selectedProperty().addListener((o, was, is) -> rebuild());
        FocusCommands.bind(this, "csv.toggleHeaderRow", headerToggle::fire);
        // A header's double-click rename, for the focused cell's column (an editable grid with a header row).
        FocusCommands.bindIf(this, "csv.renameColumn", this::renameFocusedColumn);

        filterField.setPromptText(tr("csvgrid.filterPrompt"));
        // The configured keymap's caret/editing chords act on this field (the KeyDispatcher leaves them to it).
        com.editora.command.TextInputKeymap.installShared(filterField);
        filterField.getStyleClass().add("csv-grid-filter");
        filterField.setPrefColumnCount(14);
        filterField.textProperty().addListener((o, was, is) -> applyView());

        summary.getStyleClass().add("csv-grid-summary");
        // Summary sits left-of-spacer so the bar's top-right corner stays clear for the floating
        // Editor/Split/Preview toggle overlaid there in PREVIEW mode (mirroring the Markdown preview).
        HBox top = UiZoom.spacing(new HBox(headerToggle, filterField, summary, spacer()), 8);
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
        MenuItem exportPdf = MenuChords.tag(new MenuItem(tr("csvgrid.exportPdf")), "csv.exportPdf");
        exportPdf.setOnAction(e -> runExport(ExportActions::exportPdf));
        MenuItem print = MenuChords.tag(new MenuItem(tr("csvgrid.print")), "csv.print");
        print.setOnAction(e -> runExport(ExportActions::printPreview));
        MenuItem exportExcel = MenuChords.tag(new MenuItem(tr("csvgrid.exportExcel")), "csv.exportExcel");
        exportExcel.setOnAction(e -> runExport(ExportActions::exportExcel));
        MenuItem exportOds = MenuChords.tag(new MenuItem(tr("csvgrid.exportOds")), "csv.exportOds");
        exportOds.setOnAction(e -> runExport(ExportActions::exportOds));
        ContextMenu menu = new ContextMenu(
                reveal,
                new SeparatorMenuItem(),
                sortAsc,
                sortDesc,
                clearSort,
                new SeparatorMenuItem(),
                exportPdf,
                print,
                exportExcel,
                exportOds);
        table.setContextMenu(menu);
        RowContextMenu.anchor(table, menu); // the Menu key / Shift+F10: at the focused cell's row
        FocusCommands.row(reveal, "csv.revealFocusedCell");
        FocusCommands.row(sortAsc, "csv.sortAscending");
        FocusCommands.row(sortDesc, "csv.sortDescending");
        FocusCommands.row(clearSort, "csv.clearSort");
        FocusCommands.ownMenu(table); // the rows' commands act on the grid's focused cell, as the rows do
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Replaces the grid contents with {@code parsedRows} (already parsed by the coordinator). */
    void setData(List<List<String>> parsedRows) {
        this.rows = parsedRows == null ? List.of() : parsedRows;
        shownTypes = null;
        rebuild();
    }

    /**
     * As {@link #setData(List)} with the grid's figures already worked out off the FX thread. Worked out for
     * another header setting than the one now shown (toggled meanwhile), they are worked out again here.
     */
    void setPrepared(Prepared prepared) {
        this.rows = prepared.rows();
        shownTypes = null;
        rebuild(prepared.header() == headerToggle.isSelected() ? prepared : null);
    }

    /** Whether the first row is treated as a header, for {@link #prepare} off the FX thread. */
    boolean headerRowShown() {
        return headerToggle.isSelected();
    }

    /** Enables/disables in-place cell editing; when enabled, a committed edit calls {@code onCommit}. Stores
     *  the state only — the caller must follow with {@link #setData} to rebuild the columns. */
    void setEditable(boolean editable, @Nullable EditCommit onCommit) {
        this.editable = editable;
        this.editCommit = onCommit == null ? (r, f, v) -> {} : onCommit;
        table.setEditable(editable);
    }

    /** Whether the first row is treated as a header (the coordinator adds 1 to the data-row→line mapping). */
    boolean isHeaderRow() {
        return headerToggle.isSelected();
    }

    /**
     * The grid as displayed: the visible rows in their displayed order (filter and sort applied) and the
     * header row when "first row is a header" is on. Print and the exports put out exactly this.
     */
    Shown shown() {
        List<List<String>> visible = new ArrayList<>(table.getItems().size());
        for (Row row : table.getItems()) {
            visible.add(List.copyOf(row.cells()));
        }
        List<String> header = headerToggle.isSelected() && !rows.isEmpty() ? List.copyOf(rows.get(0)) : null;
        return new Shown(header, visible, allRows.size());
    }

    /** The number of data rows currently held (unfiltered) — for the FX test harness. */
    int rowCount() {
        return allRows.size();
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
        rebuild(null);
    }

    /** What the columns were built for: the same again keeps them and replaces only the rows (perf review P3). */
    private record ColumnsKey(List<String> titles, boolean header, boolean editable, List<Boolean> numeric) {}

    private @Nullable ColumnsKey builtColumns;

    private void rebuild(@Nullable Prepared ready) {
        Prepared p = ready != null ? ready : prepare(rows, headerToggle.isSelected());
        allRows.clear();
        int cols = p.cols();
        if (cols == 0) {
            headerLabels.clear();
            table.getColumns().clear();
            baseTitles.clear();
            builtColumns = null;
            table.setItems(FXCollections.observableArrayList());
            summary.setText(tr("csvgrid.empty"));
            return;
        }
        boolean header = p.header();
        List<String> headerRow = header ? rows.get(0) : null;
        List<ColumnType> types = p.types();
        shownTypes = types;
        shownRagged = p.ragged();
        expectedCols = p.expectedCols();
        List<Integer> contentLengths = p.contentLengths();

        List<String> titles = new ArrayList<>(cols);
        List<Boolean> numeric = new ArrayList<>(cols);
        for (int col = 0; col < cols; col++) {
            titles.add(
                    headerRow != null
                                    && col < headerRow.size()
                                    && !headerRow.get(col).isBlank()
                            ? headerRow.get(col)
                            : tr("csvgrid.column", col + 1));
            numeric.add(col < types.size() && CsvColumns.isNumeric(types.get(col)));
        }
        ColumnsKey key = new ColumnsKey(titles, header, editable, numeric);
        boolean reuse = key.equals(builtColumns) && table.getColumns().size() == cols + 1;
        if (reuse) {
            // The same columns: only their widths may move with the content, as a rebuild would set them.
            setRowNumberWidth(table.getColumns().get(0), rows.size());
            for (int col = 0; col < cols; col++) {
                table.getColumns().get(col + 1).setPrefWidth(UiZoom.px(widthFor(col, titles.get(col), contentLengths)));
            }
        } else {
            table.getColumns().clear();
            headerLabels.clear();
            baseTitles.clear();
            baseTitles.addAll(titles);
            builtColumns = key;
            table.getColumns().add(rowNumberColumn(rows.size()));
            for (int c = 0; c < cols; c++) {
                final int col = c;
                String title = titles.get(col);
                TableColumn<Row, String> tc = new TableColumn<>();
                tc.setGraphic(headerNode(tc, col, title, header)); // editable header label (double-click to rename)
                tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                        col < cd.getValue().cells().size()
                                ? cd.getValue().cells().get(col)
                                : ""));
                tc.setPrefWidth(UiZoom.px(widthFor(col, title, contentLengths)));
                tc.setSortable(false);
                tc.setCellFactory(dataCellFactory(numeric.get(col)));
                if (editable) {
                    tc.setOnEditCommit(
                            ev -> onCellCommitted(ev.getTablePosition().getRow(), col, ev.getNewValue()));
                }
                table.getColumns().add(tc);
            }
        }
        allRows.addAll(p.dataRows());
        if (sortColumn >= cols) {
            sortColumn = -1;
        }
        applyView();
    }

    /**
     * Auto-size to the widest of (content, header title + its arrow/rename affordance) so a column rarely needs a
     * manual resize; the header is bold, so give the title a little extra headroom.
     */
    private static double widthFor(int col, String title, List<Integer> contentLengths) {
        int contentChars = col < contentLengths.size() ? contentLengths.get(col) : 0;
        return columnWidthFor(Math.max(contentChars, title.length() + HEADER_EXTRA_CHARS));
    }

    /** Applies the current filter + sort to {@link #allRows} → the visible items, and refreshes the summary. */
    private void applyView() {
        String query = filterField.getText() == null
                ? ""
                : filterField.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> view = new ArrayList<>();
        for (Row row : allRows) {
            if (query.isEmpty() || row.matches(query)) {
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
        List<ColumnType> types = shownTypes;
        if (types == null) {
            types = CsvColumns.inferTypes(rows, headerToggle.isSelected());
            shownTypes = types;
            shownRagged = CsvColumns.raggedRowCount(rows, headerToggle.isSelected());
        }
        summary.setText(summaryText(allRows.size(), view.size(), cols, types, shownRagged));
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

    private static @Nullable Double tryNumber(String s) {
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

    /** Fit the largest row number (e.g. 5 digits for a 10k-row file) so the gutter never truncates. */
    private static void setRowNumberWidth(TableColumn<Row, ?> num, int totalPhysicalRows) {
        num.setPrefWidth(UiZoom.px(Math.max(
                40, String.valueOf(Math.max(1, totalPhysicalRows)).length() * CHAR_PX + 22)));
    }

    /** A non-editable leading "#" column showing each row's original 1-based data-row number. */
    private TableColumn<Row, String> rowNumberColumn(int totalPhysicalRows) {
        TableColumn<Row, String> num = new TableColumn<>("#");
        num.setSortable(false);
        num.setEditable(false);
        setRowNumberWidth(num, totalPhysicalRows);
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

    /** Starts renaming the focused cell's column, as a double-click on its header does; false where that would not. */
    private boolean renameFocusedColumn() {
        TablePosition<?, ?> pos = table.getFocusModel().getFocusedCell();
        int field = pos == null ? -1 : pos.getColumn() - 1; // column 0 is the "#" gutter
        if (!editable || !headerToggle.isSelected() || field < 0 || field >= baseTitles.size()) {
            return false;
        }
        @SuppressWarnings("unchecked")
        TableColumn<Row, String> tc =
                (TableColumn<Row, String>) table.getColumns().get(pos.getColumn());
        startHeaderEdit(tc, field, baseTitles.get(field));
        return true;
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
        row.edited();
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
