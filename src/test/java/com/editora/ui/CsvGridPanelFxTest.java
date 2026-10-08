package com.editora.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV grid driven as a user drives it: filtering and sorting through its menu (the rows keep their file
 * positions), revealing a cell in the editor, editing a cell and renaming a header when the grid is
 * editable, and what the summary and the row gutter show for a ragged file.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvGridPanelFxTest {

    private final List<String> calls = new ArrayList<>();
    private Stage stage;
    private StackPane root;
    private CsvGridPanel panel;

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 700, 400));
            stage.show();
        });
    }

    @AfterAll
    void close() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void create() throws Exception {
        calls.clear();
        FxTestSupport.runOnFx(() -> {
            panel = new CsvGridPanel((line, field) -> calls.add("jump " + line + "," + field));
            root.getChildren().setAll(panel);
        });
    }

    /** name,qty,city — three data rows, the last one short. */
    private static List<List<String>> rows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.of("name", "qty", "city")));
        rows.add(new ArrayList<>(List.of("pear", "10", "Lima")));
        rows.add(new ArrayList<>(List.of("apple", "9", "Oslo")));
        rows.add(new ArrayList<>(List.of("fig", "100")));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private TableView<Object> table() {
        return (TableView<Object>) FxTestSupport.<TableView<?>>field(panel, "table");
    }

    private List<String> firstColumn() {
        return panel.shown().rows().stream().map(List::getFirst).toList();
    }

    private MenuItem menu(String key) {
        return table().getContextMenu().getItems().stream()
                .filter(i -> tr(key).equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void focus(int row, int column) {
        table().getFocusModel().focus(row, table().getColumns().get(column));
    }

    private String summary() {
        return FxTestSupport.<Label>field(panel, "summary").getText();
    }

    private Label header(int dataColumn) {
        return (Label) table().getColumns().get(dataColumn + 1).getGraphic();
    }

    private static MouseEvent click(int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    @Test
    void theGridShowsTheDataRowsUnderTheHeaderAndSaysWhatItHolds() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(null);
            assertEquals(tr("csvgrid.empty"), summary());
            assertEquals(0, panel.rowCount());
            panel.setData(List.of(List.of(), List.of()));
            assertEquals(tr("csvgrid.empty"), summary(), "records with no fields are nothing to show");

            panel.setData(rows());
            assertEquals(3, panel.rowCount());
            assertEquals(4, table().getColumns().size(), "the row gutter and three data columns");
            assertEquals(
                    List.of("name", "qty", "city"),
                    List.of(header(0).getText(), header(1).getText(), header(2).getText()));
            assertEquals(tr("csvgrid.summary", "3", 3, 1, 2) + " " + tr("csvgrid.ragged", 1), summary());
            assertEquals(List.of("name", "qty", "city"), panel.shown().header());
            assertFalse(panel.shown().filtered());
            assertEquals(4, panel.shown().withHeader().size());
        });
    }

    @Test
    void withoutAHeaderRowEveryRecordIsDataAndColumnsAreNumbered() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            CheckBox headerToggle = FxTestSupport.field(panel, "headerToggle");
            headerToggle.setSelected(false);
            assertFalse(panel.isHeaderRow());
            assertEquals(4, panel.rowCount());
            assertEquals(tr("csvgrid.column", 2), header(1).getText());
            assertNull(panel.shown().header());
            assertEquals(4, panel.shown().withHeader().size());

            focus(0, 1);
            menu("csvgrid.reveal").fire();
            assertEquals(List.of("jump 0,0"), calls, "with no header, data row 0 is line 0");

            // A header cell that is blank falls back to the column's number.
            headerToggle.setSelected(true);
            List<List<String>> blankHeader = rows();
            blankHeader.getFirst().set(1, " ");
            panel.setData(blankHeader);
            assertEquals(tr("csvgrid.column", 2), header(1).getText());
        });
    }

    @Test
    void theFilterNarrowsTheRowsAndTheShownRowsSayTheyAreFiltered() throws Exception {
        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            filter.setText("  OSLO ");
            assertEquals(List.of("apple"), firstColumn());
            assertTrue(panel.shown().filtered());
            assertEquals(3, panel.shown().totalRows());
            assertTrue(summary().startsWith(tr("csvgrid.summary", "1/3", 3, 1, 2)), summary());

            filter.setText("no such value");
            assertEquals(List.of(), firstColumn());
            filter.setText("");
            assertEquals(3, firstColumn().size());
        });
    }

    @Test
    void sortingOrdersNumbersAsNumbersTextAsTextAndCanBeCleared() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            menu("csvgrid.sortAsc").fire();
            assertEquals(List.of("pear", "apple", "fig"), firstColumn(), "no focused cell: nothing to sort by");

            focus(0, 0); // the row gutter is not a data column
            menu("csvgrid.sortAsc").fire();
            assertEquals(List.of("pear", "apple", "fig"), firstColumn());

            focus(0, 2); // qty
            menu("csvgrid.sortAsc").fire();
            assertEquals(List.of("apple", "pear", "fig"), firstColumn(), "9 < 10 < 100, not as text");
            assertEquals("qty  ▲", header(1).getText());
            focus(0, 2); // re-sorting replaced the rows; a right click focuses the cell under it again
            menu("csvgrid.sortDesc").fire();
            assertEquals(List.of("fig", "pear", "apple"), firstColumn());
            assertEquals("qty  ▼", header(1).getText());

            focus(0, 1); // name
            menu("csvgrid.sortAsc").fire();
            assertEquals(List.of("apple", "fig", "pear"), firstColumn());
            assertEquals("qty", header(1).getText(), "only the sorted column carries the arrow");

            focus(0, 3); // city: the short row sorts as empty
            menu("csvgrid.sortAsc").fire();
            assertEquals(List.of("fig", "pear", "apple"), firstColumn());

            menu("csvgrid.clearSort").fire();
            assertEquals(List.of("pear", "apple", "fig"), firstColumn(), "back in file order");
            assertEquals("city", header(2).getText());

            // A sort on a column the new data no longer has is dropped.
            focus(0, 3);
            menu("csvgrid.sortDesc").fire();
            panel.setData(List.of(
                    new ArrayList<>(List.of("only")), new ArrayList<>(List.of("b")), new ArrayList<>(List.of("a"))));
            assertEquals(List.of("b", "a"), firstColumn());
        });
    }

    @Test
    void aReadOnlyGridRevealsTheFocusedCellOnEnterDoubleClickOrItsMenu() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            menu("csvgrid.reveal").fire();
            assertEquals(List.of("jump 1,0"), calls, "nothing chosen yet: the first data row, the file's line 1");
            calls.clear();

            focus(0, 2);
            menu("csvgrid.sortDesc").fire(); // fig, pear, apple — the view order is not the file order
            focus(0, 2);
            Event.fireEvent(
                    table(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(List.of("jump 3,1"), calls, "fig is the file's line 3, whatever row it is shown in");

            focus(2, 0); // the gutter: reveal the row's first field
            Event.fireEvent(table(), click(2));
            Event.fireEvent(table(), click(1));
            Event.fireEvent(table(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, false, false, false));
            assertEquals(List.of("jump 3,1", "jump 2,0"), calls);
        });
    }

    @Test
    void anEditableGridWritesACellEditBackWithTheRowsFilePosition() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setEditable(true, (row, field, value) -> calls.add("commit " + row + "," + field + "=" + value));
            panel.setData(rows());
            focus(0, 2);
            menu("csvgrid.sortDesc").fire(); // fig, pear, apple
            calls.clear();

            TableView<Object> table = table();
            @SuppressWarnings("unchecked")
            TableColumn<Object, String> city =
                    (TableColumn<Object, String>) table.getColumns().get(3);
            Event.fireEvent(
                    city,
                    new TableColumn.CellEditEvent<>(
                            table, new TablePosition<>(table, 0, city), TableColumn.editCommitEvent(), "Rome"));
            assertEquals(List.of("commit 2,2=Rome"), calls, "fig is data row 2; its missing third field is created");
            assertEquals(
                    Arrays.asList("fig", "100", "Rome"), panel.shown().rows().getFirst());

            // A row that is no longer there (the view changed under the editor) is ignored.
            Event.fireEvent(
                    city,
                    new TableColumn.CellEditEvent<>(
                            table, new TablePosition<>(table, 9, city), TableColumn.editCommitEvent(), "x"));
            assertEquals(1, calls.size());

            // Enter and a double click edit here; they do not jump to the editor.
            focus(1, 1);
            Event.fireEvent(
                    table, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            Event.fireEvent(table, click(2));
            assertEquals(1, calls.size());
        });
    }

    @Test
    void anEditableGridRenamesAHeaderOnDoubleClick() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setEditable(true, (row, field, value) -> calls.add("commit " + row + "," + field + "=" + value));
            panel.setData(rows());
            TableColumn<Object, ?> qty = table().getColumns().get(2);

            Event.fireEvent(header(1), click(1));
            assertTrue(qty.getGraphic() instanceof Label, "a single click does not start a rename");

            Event.fireEvent(header(1), click(2));
            TextField editor = (TextField) qty.getGraphic();
            assertEquals("qty", editor.getText());
            editor.setText("amount");
            Event.fireEvent(editor, new javafx.event.ActionEvent());
            assertEquals(List.of("commit -1,1=amount"), calls, "row -1 is the header line");
            assertEquals("amount", ((Label) qty.getGraphic()).getText());
            Event.fireEvent(editor, new javafx.event.ActionEvent()); // the focus-loss twin of Enter
            assertEquals(1, calls.size(), "committed once");

            // Escape leaves the name alone.
            Event.fireEvent(qty.getGraphic(), click(2));
            TextField second = (TextField) qty.getGraphic();
            second.setText("discarded");
            Event.fireEvent(
                    second, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertEquals("amount", ((Label) qty.getGraphic()).getText());
            assertEquals(1, calls.size());

            // A blank name shows the column's number; the file gets what was typed.
            Event.fireEvent(qty.getGraphic(), click(2));
            TextField third = (TextField) qty.getGraphic();
            third.setText(" ");
            Event.fireEvent(third, new javafx.event.ActionEvent());
            assertEquals(tr("csvgrid.column", 2), ((Label) qty.getGraphic()).getText());
            assertEquals("commit -1,1= ", calls.getLast());
        });
    }

    @Test
    void aReadOnlyGridOrOneWithoutAHeaderRowDoesNotRenameHeaders() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            Event.fireEvent(header(0), click(2));
            assertTrue(table().getColumns().get(1).getGraphic() instanceof Label, "read-only");

            panel.setEditable(true, null);
            FxTestSupport.<CheckBox>field(panel, "headerToggle").setSelected(false);
            panel.setData(rows());
            Event.fireEvent(header(0), click(2));
            assertTrue(table().getColumns().get(1).getGraphic() instanceof Label, "no header row to rename");
        });
    }

    @Test
    void aRowWithTheWrongNumberOfFieldsIsMarkedAndTheGutterNumbersRowsAsInTheFile() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            javafx.css.PseudoClass ragged = javafx.css.PseudoClass.getPseudoClass("csv-ragged");
            TableRow<Object> row = table().getRowFactory().call(table());
            row.updateTableView(table());
            row.updateIndex(2); // fig,100
            assertTrue(row.getPseudoClassStates().contains(ragged));
            row.updateIndex(0);
            assertFalse(row.getPseudoClassStates().contains(ragged));
            row.updateIndex(-1);
            assertFalse(row.getPseudoClassStates().contains(ragged));

            focus(0, 1);
            menu("csvgrid.sortAsc").fire(); // apple, fig, pear
            @SuppressWarnings("unchecked")
            TableColumn<Object, String> gutter =
                    (TableColumn<Object, String>) table().getColumns().getFirst();
            assertEquals("#", gutter.getText());
            assertEquals("2", gutter.getCellObservableValue(0).getValue(), "apple is the file's second data row");
            TableCell<Object, String> cell = gutter.getCellFactory().call(gutter);
            assertTrue(cell.getStyleClass().contains("csv-grid-rownum-cell"));
            cell.updateTableView(table());
            cell.updateTableColumn(gutter);
            cell.updateIndex(0);
            assertEquals("2", cell.getText());
            cell.updateIndex(-1);
            assertNull(cell.getText());

            @SuppressWarnings("unchecked")
            TableColumn<Object, String> qty =
                    (TableColumn<Object, String>) table().getColumns().get(2);
            TableCell<Object, String> number = qty.getCellFactory().call(qty);
            assertEquals(javafx.geometry.Pos.CENTER_RIGHT, number.getAlignment(), "a numeric column is right-aligned");
            number.updateTableView(table());
            number.updateTableColumn(qty);
            number.updateIndex(0);
            assertEquals("9", number.getText());
            assertEquals(
                    "",
                    ((TableColumn<Object, String>) table().getColumns().get(3))
                            .getCellObservableValue(1)
                            .getValue(),
                    "a missing field shows empty");
        });
    }

    @Test
    void theExportMenuAndTheShownCallbackReachTheWindowOnlyOnceWired() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setData(rows());
            menu("csvgrid.exportPdf").fire(); // not wired yet: nothing happens
            panel.focusFirstItem();
            assertEquals(List.of(), calls);
            assertEquals(0, table().getSelectionModel().getSelectedIndex());

            panel.setExportActions(new CsvGridPanel.ExportActions() {
                @Override
                public void exportPdf() {
                    calls.add("pdf");
                }

                @Override
                public void printPreview() {
                    calls.add("print");
                }

                @Override
                public void exportExcel() {
                    calls.add("xlsx");
                }

                @Override
                public void exportOds() {
                    calls.add("ods");
                }
            });
            panel.setOnShown(() -> calls.add("shown"));
            menu("csvgrid.exportPdf").fire();
            menu("csvgrid.print").fire();
            menu("csvgrid.exportExcel").fire();
            menu("csvgrid.exportOds").fire();
            panel.focusFirstItem();
            assertEquals(List.of("pdf", "print", "xlsx", "ods", "shown"), calls);

            panel.setOnShown(null);
            panel.setData(List.of());
            panel.focusFirstItem(); // an empty grid: nothing to select, nothing to call
            assertEquals(5, calls.size());
        });
    }

    @Test
    void aWholeFileWithNoGridOnScreenIsItsRecordsWithoutTrailingBlankOnes() {
        CsvGridPanel.Shown shown = CsvGridPanel.Shown.wholeFile(
                List.of(List.of("h1", "h2"), List.of("a", "b"), List.of(" ", ""), List.of("")));
        assertEquals(List.of("h1", "h2"), shown.header());
        assertEquals(List.of(List.of("a", "b")), shown.rows());
        assertEquals(1, shown.totalRows());
        assertFalse(shown.filtered());

        CsvGridPanel.Shown empty = CsvGridPanel.Shown.wholeFile(List.of(List.of(""), List.of(" ")));
        assertNull(empty.header());
        assertEquals(List.of(), empty.withHeader());

        assertEquals(56, CsvGridPanel.columnWidthFor(-5), "never narrower than the minimum");
        assertEquals(600, CsvGridPanel.columnWidthFor(10_000), "never wider than the maximum");
        assertEquals(10 * 7.5 + 22, CsvGridPanel.columnWidthFor(10));
    }
}
