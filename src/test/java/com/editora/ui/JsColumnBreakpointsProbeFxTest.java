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
 * Opt-in probe against the installed vscode-js-debug, in a real window: a lambda breakpoint set from the caret on an
 * arrow function stops inside its body each time it runs, with its parameter in scope; made a plain line breakpoint, it
 * stops once, when the line runs, and never inside the arrow. Run it with {@code ./mvnw test
 * -Dtest=JsColumnBreakpointsProbeFxTest -Dgroups=probe -Dlsp.probe=true -Dnode.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class JsColumnBreakpointsProbeFxTest {

    private static final String SOURCE = """
            const items = [1, 2, 3];
            let sum = 0;
            for (let round = 0; round < 2; round++) {
              items.forEach(x => { sum += x * 10; });
            }
            console.log('sum', sum);
            setTimeout(() => {}, 20000);
            """;

    private static final int LINE = 3; // items.forEach(x => { sum += x * 10; });

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void anArrowBreakpointStopsInTheBodyEachTimeAndALineBreakpointOnlyOnTheLine(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.createDirectory(dir.resolve("app")).toRealPath().resolve("loop.js");
        Files.writeString(file, SOURCE);
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
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> {
                b.getFocusedArea().moveTo(LINE, 25); // in the arrow's body
                debug.toggleLambdaBreakpointAtCaret();
            });
            assertEquals(
                    List.of(1), call(() -> b.getBreakpointManager().get(LINE).lambdas()));
            assertTrue(call(() -> b.getBreakpointManager().get(LINE).lambdasOnly()));

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            // what the gutter last showed for the line, for a failure's message: looked at after each pulse
            Thread watch = Thread.ofVirtual().start(() -> {
                try {
                    com.editora.testkit.Await.holdsWithin(
                            () -> {
                                Object live =
                                        call(() -> b.getBreakpointManager().live(LINE));
                                if (live != null) {
                                    lastLive = live;
                                }
                                return false;
                            },
                            java.time.Duration.ofSeconds(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            // Inside the arrow, once per item: not when the line itself runs.
            for (int x = 1; x <= 3; x++) {
                awaitStop(dap, debug);
                awaitRow(debug, "x", String.valueOf(x));
                if (x == 3) {
                    // Now a plain line breakpoint (the gutter menu's arrow unticked): it stops on the line once.
                    FxTestSupport.runOnFx(() -> b.getBreakpointManager().setLambdas(LINE, List.of(), false));
                    // js-debug answers setBreakpoints in its own time, and the gutter looks the same before and
                    // after (verified): a resume sent first could still stop in the arrow, in the next round.
                    FxTestSupport.drainFx();
                    await(
                            "js-debug to answer the new breakpoints",
                            20,
                            () -> call(dap::unansweredBreakpointsForTest) == 0);
                }
                resume(dap, debug);
            }
            awaitStop(dap, debug);
            awaitRow(debug, "round", "1");
            resume(dap, debug);
            // The second round's arrows run without stopping, on to the timer: the sum after the loop is printed.
            await(
                    "the loop to finish",
                    30,
                    () -> call(() -> {
                        Object panel = debug.panel();
                        return panel != null
                                && FxTestSupport.<org.fxmisc.richtext.CodeArea>field(panel, "console")
                                        .getText()
                                        .contains("sum 120");
                    }));
            assertEquals(DapManager.State.RUNNING, call(dap::state), "a line breakpoint never stops in the arrow");

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
        com.editora.testkit.Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }

    /** A stop on {@link #LINE}; which one (in the arrow, or on the line) its variables tell. */
    private static void awaitStop(DapManager dap, DebugCoordinator debug) throws InterruptedException {
        try {
            await(
                    "a stop on line " + (LINE + 1),
                    60,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == LINE));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + (debug.panel().callStack().get(0).line() + 1));
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
            throw new AssertionError(
                    e.getMessage() + " (" + call(dap::state) + ", " + top + ", " + lastLive + ")\n" + console, e);
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

    /** What the adapter last said of the breakpoint (a failure's message names it). */
    private static volatile Object lastLive;

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
