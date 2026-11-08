package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the installed JDT LS and java-debug adapter, in a real window: a lambda breakpoint set from
 * the caret stops inside the lambda each time its body runs, with the lambda's parameter in scope; made a plain line
 * breakpoint, it stops once, when the line runs, and never inside the lambda. Run it with
 * {@code ./mvnw test -Dtest=LambdaBreakpointsProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class LambdaBreakpointsProbeFxTest {

    private static final String SOURCE = """
            import java.util.List;

            public class Main {
                public static void main(String[] args) throws Exception {
                    List<Integer> items = List.of(1, 2, 3);
                    int[] sum = {0};
                    for (int round = 0; round < 2; round++) {
                        items.forEach(x -> sum[0] += x * 10);
                        Thread.sleep(200);
                    }
                    Thread.sleep(20000);
                }
            }
            """;

    private static final int LINE = 7; // items.forEach(x -> sum[0] += x * 10);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aLambdaBreakpointStopsInTheBodyEachTimeAndALineBreakpointOnlyOnTheLine(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);
        Path file = Files.createDirectory(dir.resolve("loose")).resolve("Main.java");
        Files.writeString(file, SOURCE);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setJavaLspEnabled(true);
        seed.getSettings().setJavaLspCommand(jdtls.toString());
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setJavaDebugPluginPath(plugin.toString());
        assertTrue(seed.saveSettings());
        seed.shutdown();

        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Java debugging available", 60, () -> call(debug::debugEffective));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> {
                b.getFocusedArea().moveTo(LINE, 30); // in the lambda's body
                debug.toggleLambdaBreakpointAtCaret();
            });
            assertEquals(
                    List.of(1), call(() -> b.getBreakpointManager().get(LINE).lambdas()));
            assertTrue(call(() -> b.getBreakpointManager().get(LINE).lambdasOnly()));

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            // Inside the lambda, once per item: not when the line itself runs.
            for (int x = 1; x <= 3; x++) {
                awaitStop(dap, debug, true);
                awaitRow(debug, "x", String.valueOf(x));
                if (x == 3) {
                    // Now a plain line breakpoint (the gutter menu's lambda unticked): it stops on the line once.
                    FxTestSupport.runOnFx(() -> b.getBreakpointManager().setLambdas(LINE, List.of(), false));
                }
                resume(dap, debug);
            }
            awaitStop(dap, debug, false);
            awaitRow(debug, "round", "1");
            resume(dap, debug);
            // The second round's lambdas run without stopping, on to the long sleep.
            Thread.sleep(1500);
            assertEquals(DapManager.State.RUNNING, call(dap::state), "a line breakpoint never stops in the lambda");

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 20, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    /** A stop on {@link #LINE}, in the lambda's body ({@code inLambda}) or in {@code main} running the line. */
    private static void awaitStop(DapManager dap, DebugCoordinator debug, boolean inLambda)
            throws InterruptedException {
        try {
            await(
                    "a stop on line " + (LINE + 1) + (inLambda ? " in the lambda" : " in main"),
                    90,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == LINE
                            && debug.panel().callStack().get(0).name().contains("lambda$") == inLambda));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + (debug.panel().callStack().get(0).line() + 1));
            throw new AssertionError(e.getMessage() + " (" + call(dap::state) + ", " + top + ")", e);
        }
    }

    /** Resumes, and forgets the tree shown so far: a row is looked for only in the next stop's tree. */
    private static void resume(DapManager dap, DebugCoordinator debug) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            staleRoot = tree.getRoot();
            dap.resume();
        });
    }

    private static TreeItem<DebugPanel.VarRow> staleRoot;

    private static TreeItem<DebugPanel.VarRow> row(DebugPanel panel, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        if (tree.getRoot() == staleRoot) {
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

    private static void awaitRow(DebugCoordinator debug, String name, String value) throws InterruptedException {
        await(
                name + " = " + value,
                20,
                () -> call(() -> {
                    TreeItem<DebugPanel.VarRow> row = row(debug.panel(), name);
                    return row != null && value.equals(row.getValue().value());
                }));
    }
}
