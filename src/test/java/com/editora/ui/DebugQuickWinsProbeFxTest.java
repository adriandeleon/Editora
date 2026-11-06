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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the installed JDT LS and java-debug adapter, in a real window: a temporary breakpoint is
 * removed when the program stops on it, the Variables tree marks the values a loop changed, numbers come back in
 * hexadecimal and back, Force Run to Cursor runs past a breakpoint, and Pause suspends the whole program — a worker thread's output stops until Continue. Run it with {@code ./mvnw test
 * -Dtest=DebugQuickWinsProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class DebugQuickWinsProbeFxTest {

    private static final String SOURCE = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    Thread worker = new Thread(() -> {
                        for (int n = 0; n < 5000; n++) {
                            System.out.println("tick " + n);
                            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                        }
                    }, "worker");
                    worker.setDaemon(true);
                    worker.start();
                    int total = 0;
                    for (int i = 0; i < 5; i++) {
                        total += i;
                        Thread.sleep(100);
                    }
                    Thread.sleep(20000);
                    System.out.println("done " + total);
                }
            }
            """;

    private static final int TEMPORARY_LINE = 12; // total += i;
    private static final int SLEEP_LINE = 13; // Thread.sleep(100);
    private static final int LONG_SLEEP_LINE = 15; // Thread.sleep(20000);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void temporaryBreakpointChangedValuesAndPauseAll(@TempDir Path dir) throws Exception {
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
                b.getFocusedArea().moveTo(TEMPORARY_LINE, 0);
                debug.toggleTemporaryBreakpointAtCaret();
                b.toggleBreakpoint(SLEEP_LINE);
            });

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, TEMPORARY_LINE, debug);
            awaitRow(debug, "total", "0");
            await(
                    "the temporary breakpoint removed",
                    10,
                    () -> !call(() -> b.getBreakpointManager().isBreakpoint(TEMPORARY_LINE)));
            assertTrue(call(() -> b.getBreakpointManager().isBreakpoint(SLEEP_LINE)), "the ordinary one stays");

            resume(dap, debug);
            awaitStop(dap, SLEEP_LINE, debug); // i = 0, total = 0
            awaitRow(debug, "i", "0");
            resume(dap, debug);
            awaitStop(dap, SLEEP_LINE, debug); // i = 1, total = 1
            awaitRow(debug, "total", "1");
            awaitRow(debug, "i", "1");
            DebugPanel panel = debug.panel();
            assertTrue(call(() -> changed(panel, "i")), "i went from 0 to 1");
            assertTrue(call(() -> changed(panel, "total")), "total went from 0 to 1");
            assertFalse(call(() -> changed(panel, "args")), "args did not change");

            // Hexadecimal: java-debug answers the same values in the other format, and back.
            FxTestSupport.runOnFx(() -> {
                forgetTree(debug);
                panel.toggleHexValues();
            });
            await(
                    "total in hexadecimal",
                    20,
                    () -> call(() -> {
                        TreeItem<DebugPanel.VarRow> row = row(debug.panel(), "total");
                        return row != null && row.getValue().value().startsWith("0x");
                    }));
            FxTestSupport.runOnFx(() -> {
                forgetTree(debug);
                panel.toggleHexValues();
            });
            awaitRow(debug, "total", "1");

            // Force Run to Cursor: past the breakpoint on the sleep line (three more passes) to the long sleep.
            FxTestSupport.runOnFx(() -> {
                forgetTree(debug);
                b.getFocusedArea().moveTo(LONG_SLEEP_LINE, 0);
                debug.debugForceRunToCursor();
            });
            awaitStop(dap, LONG_SLEEP_LINE, debug);
            awaitRow(debug, "total", "10"); // 0 + 1 + 2 + 3 + 4: the loop ran to the end without stopping
            assertTrue(call(() -> b.getBreakpointManager().isBreakpoint(SLEEP_LINE)), "the breakpoint is still there");

            // Pause while main sleeps: the worker's ticks must stop too.
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(SLEEP_LINE));
            FxTestSupport.runOnFx(dap::resume);
            await("running", 10, () -> call(dap::state) == DapManager.State.RUNNING);
            Thread.sleep(1500);
            FxTestSupport.runOnFx(dap::pause);
            await("paused", 20, () -> call(dap::state) == DapManager.State.SUSPENDED);
            Thread.sleep(800); // output already on its way
            int atPause = ticks(debug);
            Thread.sleep(1500);
            int later = ticks(debug);
            assertTrue(atPause > 0, "the worker printed before the pause");
            assertEquals(atPause, later, "no thread runs while the program is paused");

            FxTestSupport.runOnFx(dap::resume);
            Thread.sleep(1500);
            assertTrue(ticks(debug) > later + 10, "Continue resumed the worker too");
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

    private static void awaitStop(DapManager dap, int line, DebugCoordinator debug) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                90,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().get(0).line() == line));
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

    /** A row is looked for only in the tree shown after this (FX thread). */
    private static void forgetTree(DebugCoordinator debug) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        staleRoot = tree.getRoot();
    }

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

    private static boolean changed(DebugPanel panel, String name) {
        TreeItem<DebugPanel.VarRow> row = row(panel, name);
        return row != null && panel.changed(row, row.getValue());
    }

    private static int ticks(DebugCoordinator debug) {
        String text =
                call(() -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(debug.panel(), "console")).getText());
        return (int) text.lines().filter(l -> l.startsWith("tick ")).count();
    }
}
