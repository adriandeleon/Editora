package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of View as DataFrame and View as Array against debugpy, in a window: a program using pandas and numpy
 * from its project's {@code .venv} stops, and its DataFrame of 1,500 rows opens as a table of its first 1,000 rows —
 * columns named and typed, a NaN shown as {@code nan} — and Load More brings the rest; a Series is a column of values
 * and a three-dimensional array rows of its last dimension, each named by the indexes before it.
 *
 * <p>The {@code .venv} is a link to an environment with pandas and numpy: {@code -Dpandas.venv=…}, by default
 * {@code ~/.cache/editora/pandas-venv}. Run with {@code ./mvnw test -Dtest=PythonDataProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class PythonDataProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private static final int ROWS = 1500;

    @Test
    void aDataFrameASeriesAndAnArrayOpenAsTables(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path venv = Path.of(System.getProperty(
                "pandas.venv", HOME.resolve(".cache/editora/pandas-venv").toString()));
        assumeTrue(Files.isRegularFile(venv.resolve("pyvenv.cfg")), "needs a venv with pandas and numpy: " + venv);
        Path root = Files.createDirectory(dir.resolve("app")).toRealPath();
        Files.createSymbolicLink(root.resolve(".venv"), venv);
        Path file = Files.writeString(root.resolve("m.py"), """
                import numpy as np
                import pandas as pd
                df = pd.DataFrame({'id': range(%d), 'name': [f"n{i}" for i in range(%d)], 'price': [i / 2 for i in range(%d)]})
                df.loc[3, 'price'] = float('nan')
                prices = df['price']
                grid = np.arange(24).reshape(2, 3, 4)
                print(len(df))
                """.formatted(ROWS, ROWS, ROWS));
        int line = 6;

        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await("python debugging available", 120, () -> call(() -> debug.debugEffectiveFor("python")));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await(
                    "a stop on line " + (line + 1),
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));
            await("the variables", 60, () -> call(() -> item(debug, "grid") != null));

            DebugTableWindow df = open(debug, "df", "debugpanel.viewAsDataFrame");
            await(
                    "the first page",
                    60,
                    () -> call(() -> tr("valuetable.dataFirstOf", ValueTable.MAX_ROWS, ROWS)
                            .equals(df.note().getText())));
            assertEquals(List.of("#", "id", "name", "price"), call(() -> headers(df)));
            assertEquals(
                    tr("valuetable.dataHeading", "df", "DataFrame", ROWS + " × 3"),
                    call(() -> df.heading().getText()));
            assertEquals("float64", call(() -> dtype(df, "price")));
            assertEquals("n0", call(() -> df.table().getItems().getFirst().cell("name")));
            assertEquals("nan", call(() -> df.table().getItems().get(3).cell("price")));
            assertEquals("1.0", call(() -> df.table().getItems().get(2).cell("price")));
            FxTestSupport.runOnFx(df.moreButton()::fire);
            await(
                    "every row",
                    60,
                    () -> call(() ->
                            tr("valuetable.dataCount", ROWS).equals(df.note().getText())));
            assertEquals(
                    String.valueOf(ROWS - 1),
                    call(() -> df.table().getItems().getLast().cell("#")));

            DebugTableWindow prices = open(debug, "prices", "debugpanel.viewAsDataFrame");
            await(
                    "the series",
                    60,
                    () -> call(() -> tr("valuetable.dataFirstOf", ValueTable.MAX_ROWS, ROWS)
                            .equals(prices.note().getText())));
            assertEquals(List.of("#", tr("valuetable.value")), call(() -> headers(prices)));
            assertEquals("0.5", call(() -> prices.table().getItems().get(1).value()));

            DebugTableWindow grid = open(debug, "grid", "debugpanel.viewAsArray");
            await(
                    "the array",
                    60,
                    () -> call(() ->
                            tr("valuetable.dataCount", 6).equals(grid.note().getText())));
            assertEquals(List.of("#", "0", "1", "2", "3"), call(() -> headers(grid)));
            assertEquals("(1, 2)", call(() -> grid.table().getItems().getLast().key()));
            assertEquals("23", call(() -> grid.table().getItems().getLast().cell("3")));

            FxTestSupport.runOnFx(() -> {
                debug.tableWindows().forEach(DebugTableWindow::hide);
                dap.stop();
            });
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    /** Opens the Variables menu's row {@code key} on {@code name}, and the window it opens. */
    private static DebugTableWindow open(DebugCoordinator debug, String name, String key) throws Exception {
        int before = call(() -> debug.tableWindows().size());
        FxTestSupport.runOnFx(() -> menuItem(debug, name, key).fire());
        await(name + "'s table", 30, () -> call(() -> debug.tableWindows().size() > before));
        return call(() -> debug.tableWindows().getLast());
    }

    private static List<String> headers(DebugTableWindow w) {
        return w.table().getColumns().stream()
                .map(c -> c.getGraphic() instanceof Label l ? l.getText() : c.getText())
                .toList();
    }

    private static String dtype(DebugTableWindow w, String column) {
        return w.table().getColumns().stream()
                .filter(c -> c.getGraphic() instanceof Label l && l.getText().equals(column))
                .map(c -> ((Label) c.getGraphic()).getTooltip().getText())
                .findFirst()
                .orElse(null);
    }

    private static TreeItem<DebugPanel.VarRow> item(DebugCoordinator debug, String name) {
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

    private static MenuItem menuItem(DebugCoordinator debug, String name, String key) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        tree.getSelectionModel().select(item(debug, name));
        ContextMenu menu = tree.getContextMenu();
        menu.getOnShowing().handle(null);
        return menu.getItems().stream()
                .filter(i -> tr(key).equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + tr(key) + " on " + name + ": "
                        + menu.getItems().stream().map(MenuItem::getText).toList()));
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }
}
