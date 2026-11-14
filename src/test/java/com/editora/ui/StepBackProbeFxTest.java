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
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Step Back on the real GDB (its DAP mode, GDB 14 or later), in a window: a lone C file is debugged to
 * a breakpoint in a loop, Record Execution turns GDB's recording on, three steps forward add to the total, Step Back
 * goes back over the last one (the total is what it was), and Reverse Continue goes back to where recording began.
 * Run with {@code ./mvnw test -Dtest=StepBackProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code gdb} and
 * {@code cc}.
 */
@Tag("probe")
@Tag("fx")
class StepBackProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final String LOOP = """
            #include <stdio.h>
            int main(void) {
                int total = 0;
                for (int i = 1; i <= 5; i++) {
                    total += i;
                }
                printf("%d\\n", total);
                return 0;
            }
            """;

    /** The 0-based line of {@code total += i;}. */
    private static final int ADD = 4;

    @Test
    void gdbGoesBackOverWhatItRecorded(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("gdb") && onPath("cc"), "needs gdb and cc");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loop")).toRealPath().resolve("loop.c"), LOOP);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setCppDebugger("gdb");
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await(
                    "C on GDB available",
                    120,
                    () -> call(() -> debug.debugEffectiveFor("cpp") && dap.isExternalLocated(DebugLanguages.GDB)));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(ADD));
            run(c, "debug.start");
            awaitStop(dap, debug, ADD);
            assertEquals("0", awaitValue(debug, "total"), "the first time round");
            assertTrue(call(dap::isGdbSession));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(ADD)); // stepping now, not stopping on it each time round

            run(c, "debug.toggleRecording");
            await("recording", 30, () -> call(dap::isRecording));
            assertTrue(call(dap::canRunBackward));

            // Forward: total += 1, then round the loop to total += 2 (total is 1 there).
            step(c, dap, debug, "debug.stepOver");
            step(c, dap, debug, "debug.stepOver");
            int line = call(() -> debug.panel().callStack().getFirst().line());
            String before = awaitValue(debug, "total");
            step(c, dap, debug, "debug.stepOver");
            String after = awaitValue(debug, "total");

            // Back over the last step: the line and the total it had.
            step(c, dap, debug, "debug.stepBack");
            assertEquals(line, call(() -> debug.panel().callStack().getFirst().line()), "back on the line");
            assertEquals(before, awaitValue(debug, "total"), "the total as it was (it was " + after + " after)");

            // Back to where recording began: the first time round, total 0.
            step(c, dap, debug, "debug.reverseContinue");
            assertEquals(ADD, call(() -> debug.panel().callStack().getFirst().line()));
            assertEquals("0", awaitValue(debug, "total"), "where recording began");

            run(c, "debug.toggleRecording");
            await("not recording", 30, () -> !call(dap::isRecording));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    /** Runs {@code command} and waits for the stop it ends in. */
    private static void step(MainController c, DapManager dap, DebugCoordinator debug, String command)
            throws Exception {
        int stops = call(() -> debug.s().stopSerial);
        run(c, command);
        await(
                command + "'s stop",
                60,
                () -> call(() -> debug.s().stopSerial > stops
                        && dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()));
    }

    private static void awaitStop(DapManager dap, DebugCoordinator debug, int line) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                180,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
    }

    /** {@code name}'s value in the variables of the stop shown, once it is loaded. */
    private static String awaitValue(DebugCoordinator debug, String name) throws InterruptedException {
        await(name, 60, () -> call(() -> row(debug, name) != null));
        return call(() -> row(debug, name).getValue().value());
    }

    private static TreeItem<DebugPanel.VarRow> row(DebugCoordinator debug, String name) {
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

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    private static boolean onPath(String tool) {
        return Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                .isAbsolute();
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
