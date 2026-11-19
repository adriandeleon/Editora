package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import com.editora.testkit.Await;
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

    /**
     * Runs {@code command} and waits for the stop it ends in, with that stop's variables in the tree: until they come
     * back (a new root, see DebugPanel.setScopes) the tree still shows the previous stop's values.
     */
    private static void step(MainController c, DapManager dap, DebugCoordinator debug, String command)
            throws Exception {
        int stops = call(() -> debug.s().stopSerial);
        Object rootBefore = call(() -> variables(debug).getRoot());
        run(c, command);
        await(
                command + "'s stop",
                60,
                () -> call(() -> debug.s().stopSerial > stops
                        && dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && variables(debug).getRoot() != rootBefore));
    }

    private static TreeView<DebugPanel.VarRow> variables(DebugCoordinator debug) {
        return FxTestSupport.field(debug.panel(), "variables");
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
        // Found and read in one FX turn: the variables tree can be refilled between two reads (the stop's
        // scopes load in more than one answer), and a row found in one may be gone in the next.
        String[] value = new String[1];
        await(
                name,
                60,
                () -> (value[0] = call(() -> {
                            TreeItem<DebugPanel.VarRow> row = row(debug, name);
                            return row == null ? null : row.getValue().value();
                        }))
                        != null);
        return value[0];
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
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }
}
