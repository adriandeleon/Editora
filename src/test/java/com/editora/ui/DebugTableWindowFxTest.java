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
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        tree.getSelectionModel().select(item(name));
        ContextMenu menu = tree.getContextMenu();
        menu.getOnShowing().handle(null);
        return menu.getItems().stream()
                .filter(i -> tr("debugpanel.viewAsTable").equals(i.getText()))
                .findFirst()
                .orElse(null);
    }

    private DebugTableWindow open(String name) throws Exception {
        FxTestSupport.runOnFx(() -> viewAsTable(name).fire());
        await("the table window", () -> call(() -> !debug.tableWindows().isEmpty()));
        return call(() -> debug.tableWindows().getLast());
    }

    private static List<String> headers(DebugTableWindow w) {
        return w.table().getColumns().stream().map(TableColumn::getText).toList();
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
}
