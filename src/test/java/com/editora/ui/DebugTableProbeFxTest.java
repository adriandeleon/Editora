package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of View as Table against debugpy, java-debug with JDT LS, and Delve, in a window: a list of two orders
 * stopped on opens as a table of two rows whose columns are the orders' fields — without debugpy's {@code special
 * variables} and {@code len()}, java-debug's or Delve's own rows. Run with {@code ./mvnw test
 * -Dtest=DebugTableProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class DebugTableProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), """
                class Order:
                    def __init__(self, n, who):
                        self.n = n
                        self.who = who
                orders = [Order(1, "ann"), Order(2, "bob")]
                print(len(orders))
                """);
        probe(dir, file, "python", 5, "n", "who", st -> {});
    }

    @Test
    void java(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "needs JDT LS and java-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loose")).toRealPath().resolve("Main.java"), """
                import java.util.ArrayList;
                import java.util.List;

                public class Main {
                    record Order(int n, String who) {}
                    public static void main(String[] args) {
                        List<Order> orders = new ArrayList<>(List.of(new Order(1, "ann"), new Order(2, "bob")));
                        System.out.println(orders.size());
                    }
                }
                """);
        probe(dir, file, "java", 7, "n", "who", st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        Path root = Files.createDirectory(dir.resolve("orders")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/orders\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), """
                package main

                import "fmt"

                type Order struct {
                \tN   int
                \tWho string
                }

                func main() {
                \torders := []*Order{{1, "ann"}, {2, "bob"}}
                \tfmt.Println(len(orders))
                }
                """);
        probe(dir, file, "go", 11, "N", "Who", st -> {});
    }

    /** Stops on 0-based {@code line} of {@code file} and opens {@code orders} as a table. */
    private static void probe(
            Path dir, Path file, String language, int line, String number, String name, Consumer<Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await(
                    "a stop on line " + (line + 1),
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));
            await("orders", 60, () -> call(() -> item(debug, "orders") != null));
            FxTestSupport.runOnFx(() -> viewAsTable(debug).fire());
            await("the table", 30, () -> call(() -> !debug.tableWindows().isEmpty()));
            DebugTableWindow w = call(() -> debug.tableWindows().getLast());
            await(
                    "two rows with their fields",
                    60,
                    () -> call(() -> tr("valuetable.count", 2).equals(w.note().getText())));
            List<String> headers = call(() ->
                    w.table().getColumns().stream().map(TableColumn::getText).toList());
            assertTrue(headers.contains(number) && headers.contains(name), headers.toString());
            assertTrue(
                    headers.stream().noneMatch(h -> h.contains("variables") || h.equals("len()")),
                    "no debugger rows: " + headers);
            assertEquals(
                    List.of("1", "2"),
                    call(() -> w.table().getItems().stream()
                            .map(r -> r.cell(number))
                            .toList()));
            assertTrue(
                    call(() -> w.table().getItems().getLast().cell(name)).contains("bob"),
                    call(() -> w.table().getItems().getLast().cell(name)));
            FxTestSupport.runOnFx(() -> {
                w.hide();
                dap.stop();
            });
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
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

    private static MenuItem viewAsTable(DebugCoordinator debug) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        tree.getSelectionModel().select(item(debug, "orders"));
        ContextMenu menu = tree.getContextMenu();
        menu.getOnShowing().handle(null);
        return menu.getItems().stream()
                .filter(i -> tr("debugpanel.viewAsTable").equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no View as Table on orders"));
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
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
