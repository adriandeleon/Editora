package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of the evaluation time limit against real debuggers, in a window (Settings → Debugging → Evaluation
 * time limit set to {@value #LIMIT} s): stopped in a program, a call that takes a second is answered, a call that never
 * returns is stopped by the debugger soon after the limit, the program stays paused where it was, and the next
 * expression is evaluated there — on GDB and debugpy. CodeLLDB stops a call in a watch by itself, limit or none, and
 * goes on the same way after it. Run with {@code ./mvnw test
 * -Dtest=EvaluationTimeLimitProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class EvaluationTimeLimitProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    /** The limit set, in seconds. */
    private static final int LIMIT = 3;

    private static final String C = """
            #include <stdio.h>
            #include <unistd.h>
            int spin(void) {
                volatile long n = 0;
                for (;;) {
                    n++;
                }
                return (int) n;
            }
            int slow(void) {
                sleep(1);
                return 3;
            }
            int main(void) {
                int x = 7;
                printf("%d\\n", x);
                return 0;
            }
            """;

    @Test
    void gdb(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("gdb") && onPath("cc"), "needs gdb and cc");
        native_(dir, "gdb");
    }

    @Test
    void codelldb(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("cc"), "needs cc");
        assumeTrue(DapManager.locateExternal(DebugLanguages.CPP, "") != null, "needs CodeLLDB");
        native_(dir, "codelldb");
    }

    private static void native_(Path dir, String debugger) throws Exception {
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("spin")).toRealPath().resolve("spin.c"), C);
        // A watch is an expression: CodeLLDB's own evaluator calls a function with /nat. CodeLLDB stops a call in a
        // watch by itself in under a second, whatever the limit: slow() too, so it is not asked.
        boolean lldb = "codelldb".equals(debugger);
        probe(
                dir,
                file,
                "cpp",
                15,
                lldb ? null : "slow()",
                lldb ? "/nat spin()" : "spin()",
                s -> s.setCppDebugger(debugger));
    }

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), """
                import time
                def spin():
                    while True:
                        pass
                def slow():
                    time.sleep(1)
                    return 3
                def main():
                    x = 7
                    print(x)
                main()
                """);
        probe(dir, file, "python", 9, "slow()", "spin()", s -> {});
    }

    /**
     * Stops on 0-based {@code line} of {@code file}; {@code slow} (when given) is answered 3, {@code spin} fails soon
     * after the limit, and {@code x} is 7 after it.
     */
    private static void probe(
            Path dir,
            Path file,
            String language,
            int line,
            @org.jspecify.annotations.Nullable String slow,
            String spin,
            Consumer<Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setDebugEvaluationTimeLimit(LIMIT);
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
            int frame = call(() -> debug.panel().selectedFrameId());

            if (slow != null) {
                DapModels.EvalResult answered = evaluate(dap, slow, frame);
                assertEquals("3", answered.result(), "a call under the limit is answered: " + answered.result());
            }

            long start = System.nanoTime();
            DapModels.EvalResult stopped = evaluate(dap, spin, frame);
            long seconds = (System.nanoTime() - start) / 1_000_000_000L;
            assertTrue(stopped.failed(), "a call that never returns is stopped: " + stopped.result());
            assertTrue(seconds <= LIMIT + 3, "stopped by the debugger, not Editora's wait: " + seconds + " s");
            // GDB answers the evaluation before it says the program stopped again: paused soon after, not running on.
            await("the program paused again", 10, () -> call(dap::state) == DapManager.State.SUSPENDED);
            assertEquals(line, call(() -> debug.panel().callStack().getFirst().line()), "where it was");

            int now = call(() -> debug.panel().selectedFrameId());
            assertEquals("7", evaluate(dap, "x", now).result(), "the frame evaluates after it");
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static DapModels.EvalResult evaluate(DapManager dap, String expression, int frame) throws Exception {
        AtomicReference<DapModels.EvalResult> result = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> dap.evaluateFull(expression, frame, "watch", result::set));
        await(expression + " answered", 60, () -> result.get() != null);
        return result.get();
    }

    private static boolean onPath(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) {
                return true;
            }
        }
        return false;
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
