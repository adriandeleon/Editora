package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Bash debugging against vscode-bash-debug and the bashdb it ships, in a window: F5 on a Bash script
 * stops at a breakpoint in a loop, its variables read by name ({@code total}), a step goes to the next line, and Continue runs it to
 * its end. Run with {@code ./mvnw test -Dtest=BashDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs Node.js and
 * vscode-bash-debug (Editora's install, or {@code -Dbashdebug.path=<bashDebug.js>}).
 */
@Tag("probe")
@Tag("fx")
class BashDebugProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** 0-based line 3 is {@code total=$((total + n))}. */
    static final String SCRIPT = """
            #!/usr/bin/env bash
            total=0
            for n in 1 2 3; do
              total=$((total + n))
              echo "running $total"
            done
            echo "total $total"
            """;

    @Test
    void aScriptStopsInItsLoopWithItsVariablesStepsAndEnds(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String configured = System.getProperty("bashdebug.path", "");
        assumeTrue(DapManager.locateExternal(DebugLanguages.BASH, configured) != null, "needs vscode-bash-debug");
        Path script = Files.writeString(
                Files.createDirectory(dir.resolve("loop")).toRealPath().resolve("loop.sh"), SCRIPT);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setBashDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(script, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            await("Bash debugging available", 120, () -> call(() -> debug.debugEffectiveFor("bash")));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            assertTrue(call(b::isBreakpointsEnabled), "a Bash script has the breakpoint gutter");
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(3));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await(
                    "a stop in the loop",
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));
            DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
            assertEquals(3, top.line(), "on the breakpoint: " + top);
            assertEquals(script, top.file());
            // bash-debug lists $PWD and $? as Variables; the script's own are read by name (a watch, hover).
            int frame = call(() -> debug.panel().selectedFrameId());
            // bashdb's examine shows a variable named bare: with a $ it is expanded in the debugger's own shell.
            assertTrue(evaluate(dap, "total", frame).contains("total=\"0\""), "total, before the first sum");
            assertTrue(evaluate(dap, "n", frame).contains("n=\"1\""), "the loop's first n");
            assertTrue(evaluate(dap, "$total", frame).contains("total=\"0\""), "as a script writes it: named bare");

            FxTestSupport.runOnFx(() -> registry.run("debug.stepOver"));
            await(
                    "the next line",
                    60,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == 4));

            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(3)); // off: Continue runs to the end
            FxTestSupport.runOnFx(dap::resume);
            await("the end", 120, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static String evaluate(DapManager dap, String expression, int frame) throws Exception {
        java.util.concurrent.atomic.AtomicReference<DapModels.EvalResult> r =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxTestSupport.runOnFx(() -> dap.evaluateFull(expression, frame, "watch", r::set));
        await(expression, 60, () -> r.get() != null);
        return r.get().result();
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
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }
}
