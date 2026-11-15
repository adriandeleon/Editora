package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.dap.FakeDebugAdapter.Session.variable;
import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * View as Table through the Variables menu, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: a list of
 * objects becomes a row per element and a column per field (the debugger's own rows left out, a pointer followed to
 * what it points to), a long array is asked for one page and says how much is shown, the filter and Open as CSV work
 * on what is shown, and Refresh needs a paused program. {@code DebugTableProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class DebugTableWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("tables")).toRealPath();
        script = Files.writeString(root.resolve("main.rb"), "orders = []\nputs orders\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path rdbg = fx.configDir.resolve("rdbg");
        Files.writeString(
                rdbg,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce-rdbg " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            debug.applySupport();
        });
        await("Ruby debugging available", () -> call(() -> debug.debugEffectiveFor("ruby")));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(() -> {
            debug.tableWindows().forEach(DebugTableWindow::hide);
            manager.stop();
        });
        await("stopped", () -> !call(manager::isActive));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    /** Starts the script and stops it with these locals and containers scripted. */
    private FakeDebugAdapter.Session stopped(java.util.function.Consumer<FakeDebugAdapter.Session> script)
            throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(this.script));
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && this.script.equals(fx.controller.activeBuffer().getPath())));
        FxTestSupport.runOnFx(
                () -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run("debug.start"));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = this.script.toString();
        session.frameLine = 2;
        script.accept(session);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "breakpoint");
        await("the variables", () -> call(() -> item("n") != null));
        return session;
    }

    private TreeItem<DebugPanel.VarRow> item(String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        if (tree.getRoot() == null) {
            return null;
        }
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            for (TreeItem<DebugPanel.VarRow> child : top.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
        }
        return null;
    }

    /** The Variables menu's View as Table on the row called {@code name}, or null when it offers none. FX thread. */
    private MenuItem viewAsTable(String name) {
        return viewAs(name, "debugpanel.viewAsTable");
    }

    /** The Variables menu's row {@code key} (View as Table, View as DataFrame…) on the row {@code name}. FX thread. */
    private MenuItem viewAs(String name, String key) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        tree.getSelectionModel().select(item(name));
        ContextMenu menu = tree.getContextMenu();
        menu.getOnShowing().handle(null);
        return menu.getItems().stream()
                .filter(i -> tr(key).equals(i.getText()))
                .findFirst()
                .orElse(null);
    }

    private DebugTableWindow open(String name) throws Exception {
        return open(name, "debugpanel.viewAsTable");
    }

    private DebugTableWindow open(String name, String key) throws Exception {
        FxTestSupport.runOnFx(() -> viewAs(name, key).fire());
        await("the table window", () -> call(() -> !debug.tableWindows().isEmpty()));
        return call(() -> debug.tableWindows().getLast());
    }

    /** The columns' names: a data column's is the label it has for its dtype's tooltip. */
    private static List<String> headers(DebugTableWindow w) {
        return w.table().getColumns().stream()
                .map(c -> c.getGraphic() instanceof javafx.scene.control.Label l ? l.getText() : c.getText())
                .toList();
    }

    /** The dtype a data column's header shows on hover. */
    private static String dtype(DebugTableWindow w, String column) {
        return w.table().getColumns().stream()
                .filter(c -> c.getGraphic() instanceof javafx.scene.control.Label l
                        && l.getText().equals(column))
                .map(c -> ((javafx.scene.control.Label) c.getGraphic())
                        .getTooltip()
                        .getText())
                .findFirst()
                .orElse(null);
    }

    private static List<String> column(DebugTableWindow w, String column) {
        return w.table().getItems().stream().map(r -> r.cell(column)).toList();
    }

    @Test
    void aListOfObjectsIsARowPerElementAndAColumnPerField() throws Exception {
        stopped(s -> {
            s.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(variable("orders", "[#<Order>, #<Order>]", 2000), variable("n", "42", 0)));
            s.variables.put(
                    2000,
                    List.of(
                            variable("special variables", "", 2009),
                            variable("0", "#<Order n=1>", 2001),
                            variable("1", "#<Order n=2>", 2002),
                            variable("len()", "2", 0)));
            s.variables.put(2001, List.of(variable("n", "1", 0), variable("who", "\"ann\"", 0)));
            s.variables.put(
                    2002,
                    List.of(
                            variable("special variables", "", 2019),
                            variable("n", "2", 0),
                            variable("who", "\"bob\"", 0),
                            variable("rush", "true", 0)));
        });
        assertNull(call(() -> viewAsTable("n")), "a number has no elements");
        DebugTableWindow w = open("orders");
        await(
                "the fields",
                () -> call(() -> tr("valuetable.count", 2).equals(w.note().getText())));
        assertEquals(List.of("#", "n", "who", "rush"), call(() -> headers(w)));
        assertEquals(List.of("0", "1"), call(() -> column(w, "#")));
        assertEquals(List.of("\"ann\"", "\"bob\""), call(() -> column(w, "who")));
        assertEquals(List.of("", "true"), call(() -> column(w, "rush")));

        FxTestSupport.runOnFx(() -> w.filter().setText("BOB"));
        assertEquals(List.of("1"), call(() -> column(w, "#")));
        FxTestSupport.runOnFx(() -> w.filter().clear());

        Button asCsv =
                call(() -> FxTestSupport.<Button>field(w, "refresh").getParent().getChildrenUnmodifiable().stream()
                        .filter(n -> n instanceof Button b
                                && tr("valuetable.openAsCsv").equals(b.getText()))
                        .map(Button.class::cast)
                        .findFirst()
                        .orElseThrow());
        FxTestSupport.runOnFx(asCsv::fire);
        await(
                "the CSV tab",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && fx.controller.activeBuffer().isCsv()));
        assertEquals(
                "#,n,who,rush\n0,1,\"\"\"ann\"\"\",\n1,2,\"\"\"bob\"\"\",true\n",
                call(() -> fx.controller.activeBuffer().getArea().getText()));

        // Refresh evaluates the expression again (this stand-in's evaluate has nothing to expand: it says so).
        assertFalse(call(() -> w.refreshButton().isDisabled()));
        FxTestSupport.runOnFx(w.refreshButton()::fire);
        await(
                "the refresh",
                () -> call(() ->
                        tr("valuetable.refreshFailed", "orders").equals(w.note().getText())));
        FxTestSupport.runOnFx(manager::resume);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertTrue(call(() -> w.refreshButton().isDisabled()), "Refresh needs a paused program");
        assertEquals(List.of("0", "1"), call(() -> column(w, "#")), "the table keeps the stop's values");
    }

    @Test
    void aPointerIsFollowedAndALongArrayIsAskedForOnePage() throws Exception {
        FakeDebugAdapter.Session session = stopped(s -> {
            s.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(
                            variable("ptrs", "[]*main.Order len: 1", 3000),
                            s.array("nums", 4000, ValueTable.MAX_ROWS + 500),
                            variable("n", "1", 0)));
            s.variables.put(3000, List.of(variable("[0]", "*main.Order {N: 7}", 3001)));
            s.variables.put(3001, List.of(variable("", "main.Order {N: 7}", 3002)));
            s.variables.put(3002, List.of(variable("N", "7", 0)));
        });
        DebugTableWindow ptrs = open("ptrs");
        await("the pointee's fields", () -> call(() -> List.of("#", "N").equals(headers(ptrs))));
        assertEquals(List.of("7"), call(() -> column(ptrs, "N")));

        DebugTableWindow nums = open("nums");
        await(
                "one page",
                () -> call(() -> tr("valuetable.firstOf", ValueTable.MAX_ROWS, ValueTable.MAX_ROWS + 500)
                        .equals(nums.note().getText())));
        assertEquals(List.of("#", tr("valuetable.value")), call(() -> headers(nums)));
        VariablesArguments page = session.variableRequests.stream()
                .filter(a -> a.getVariablesReference() == 4000)
                .findFirst()
                .orElse(null);
        assertNotNull(page);
        assertEquals(ValueTable.MAX_ROWS, page.getCount(), "only what is shown is asked for");

        // Sorting by the value orders numbers as numbers.
        FxTestSupport.runOnFx(() -> {
            TableColumn<ValueTable.Row, ?> value = nums.table().getColumns().get(1);
            value.setSortType(TableColumn.SortType.DESCENDING);
            nums.table().getSortOrder().setAll(value);
        });
        assertEquals("999", call(() -> nums.table().getItems().getFirst().cell(ValueTable.VALUE_COLUMN)));
    }

    /** A typed Python value with children, as debugpy lists a DataFrame or an array. */
    private static org.eclipse.lsp4j.debug.Variable typed(String name, String type, int ref) {
        org.eclipse.lsp4j.debug.Variable v = variable(name, type + " …", ref);
        v.setType(type);
        v.setEvaluateName(name);
        return v;
    }

    /** The helper's answer for {@code count} rows from {@code start} of a DataFrame of {@code total}, as debugpy says it. */
    private static String frame(int start, int count, int total) {
        StringBuilder rows = new StringBuilder();
        StringBuilder index = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            rows.append(i > start ? ", " : "")
                    .append("[\"")
                    .append(i)
                    .append("\", \"it's #")
                    .append(i)
                    .append("\", \"")
                    .append(i % 2 == 0 ? "1.5" : "nan")
                    .append("\"]");
            index.append(i > start ? ", " : "").append("\"r").append(i).append("\"");
        }
        return PythonDataTable.pythonString("{\"kind\": \"DataFrame\", \"shape\": [" + total + ", 3], \"columns\": "
                + "[\"id\", \"name\", \"name\"], \"dtypes\": [\"int64\", \"object\", \"float64\"], \"index\": ["
                + index + "], \"rows\": [" + rows + "], \"total\": " + total + ", \"width\": 3}");
    }

    @Test
    void aDataFrameShowsItsRowsAPageAtATimeAndAnArrayItsValues() throws Exception {
        int total = ValueTable.MAX_ROWS + 2;
        FakeDebugAdapter.Session session = stopped(s -> {
            s.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(
                            typed("df", "DataFrame", 5000),
                            typed("a", "ndarray", 5001),
                            typed("bad", "ndarray", 5002),
                            variable("n", "1", 0)));
            s.variables.put(5000, List.of(variable("T", "<transposed dataframe>", 0)));
            s.variables.put(5001, List.of(variable("0", "7", 0)));
            s.variables.put(5002, List.of(variable("0", "7", 0)));
            s.evaluateResults.put(
                    PythonDataTable.expression("df", 0, ValueTable.MAX_ROWS), frame(0, ValueTable.MAX_ROWS, total));
            s.evaluateResults.put(
                    PythonDataTable.expression("df", ValueTable.MAX_ROWS, ValueTable.MAX_ROWS),
                    frame(ValueTable.MAX_ROWS, 2, total));
            s.evaluateResults.put(
                    PythonDataTable.expression("a", 0, ValueTable.MAX_ROWS),
                    PythonDataTable.pythonString("{\"kind\": \"ndarray\", \"shape\": [2], \"leaf\": true, "
                            + "\"columns\": [\"\"], \"dtypes\": [\"int64\"], \"index\": [\"0\", \"1\"], "
                            + "\"rows\": [[\"7\"], [\"8\"]], \"total\": 2, \"width\": 1}"));
            s.evaluateResults.put(
                    PythonDataTable.expression("bad", 0, ValueTable.MAX_ROWS),
                    PythonDataTable.pythonString("{\"kind\": \"list\", \"error\": \"unsupported\"}"));
        });
        // The menu says what the value is in a Python session; elsewhere a DataFrame is a table of its children.
        assertNull(call(() -> viewAs("df", "debugpanel.viewAsDataFrame")), "a Ruby session's");
        python();
        assertNull(call(() -> viewAs("df", "debugpanel.viewAsTable")));
        assertNotNull(call(() -> viewAs("a", "debugpanel.viewAsArray")));

        DebugTableWindow df = open("df", "debugpanel.viewAsDataFrame");
        await(
                "the first page",
                () -> call(() -> tr("valuetable.dataFirstOf", ValueTable.MAX_ROWS, total)
                        .equals(df.note().getText())));
        assertEquals(List.of("#", "id", "name", "name (2)"), call(() -> headers(df)), "each column once");
        assertEquals("object", call(() -> dtype(df, "name")));
        assertEquals(
                tr("valuetable.dataHeading", "df", "DataFrame", total + " × 3"),
                call(() -> df.heading().getText()));
        assertEquals("it's #0", call(() -> df.table().getItems().getFirst().cell("name")));
        assertEquals("r0", call(() -> df.table().getItems().getFirst().cell("#")));
        assertEquals(
                "clipboard",
                session.evaluateRequests.getLast().getContext(),
                "the context where debugpy answers a long string whole");

        assertTrue(call(() -> df.moreButton().isVisible()));
        FxTestSupport.runOnFx(df.moreButton()::fire);
        await(
                "the next page",
                () -> call(
                        () -> tr("valuetable.dataCount", total).equals(df.note().getText())));
        assertFalse(call(() -> df.moreButton().isVisible()), "every row shown");
        assertEquals(
                "r" + (total - 1), call(() -> df.table().getItems().getLast().cell("#")));

        DebugTableWindow a = open("a", "debugpanel.viewAsArray");
        await(
                "the values",
                () -> call(() -> tr("valuetable.dataCount", 2).equals(a.note().getText())));
        assertEquals(List.of("#", tr("valuetable.value")), call(() -> headers(a)));
        assertEquals("int64", call(() -> dtype(a, tr("valuetable.value"))));
        assertEquals(List.of("7", "8"), call(() -> column(a, ValueTable.VALUE_COLUMN)));

        DebugTableWindow bad = open("bad", "debugpanel.viewAsArray");
        await(
                "why not",
                () -> call(() -> tr("valuetable.dataFailed", "bad", tr("valuetable.dataUnsupported"))
                        .equals(bad.note().getText())));

        // Refresh asks again from the first page; once the program goes on, Load More and Refresh wait for a stop.
        FxTestSupport.runOnFx(df.refreshButton()::fire);
        await(
                "the first page again",
                () -> call(() -> tr("valuetable.dataFirstOf", ValueTable.MAX_ROWS, total)
                        .equals(df.note().getText())));
        FxTestSupport.runOnFx(manager::resume);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertTrue(call(() -> df.moreButton().isDisabled()));
        assertTrue(call(() -> df.refreshButton().isDisabled()));
    }

    /** Makes the session a Python one, as debugpy's is: the stand-in is reached as Ruby's debugger. */
    private void python() throws Exception {
        java.lang.reflect.Field language = DapManager.class.getDeclaredField("sessionLanguage");
        language.setAccessible(true);
        language.set(manager, "python");
    }
}
