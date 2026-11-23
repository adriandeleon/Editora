package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
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
 * Opt-in probe of Deno debugging through vscode-js-debug, in a window: F5 on a TypeScript file of a Deno project
 * ({@code deno.json}) runs it under {@code deno run --inspect-wait}, stops at its breakpoint with its locals, and
 * Continue runs it to its end; Debug Test at Caret in a test file runs {@code deno test} filtered to the test at the
 * caret, which stops at its own breakpoint and not at the other test's. Run with {@code ./mvnw test
 * -Dtest=DenoDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code deno}, {@code node} and js-debug.
 */
@Tag("probe")
@Tag("fx")
class DenoDebugProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** 0-based line 2 is {@code const message = …}. */
    static final String PROGRAM = """
            function greet(name: string): string {
              const shout: string = name.toUpperCase();
              const message = `Hello, ${shout}`;
              return message;
            }
            console.log(greet("deno"));
            """;

    /** The first test's check is 0-based line 2, the second's line 6; the caret goes to line 4. */
    static final String TESTS = """
            Deno.test("first adds", () => {
              const sum = 1 + 1;
              if (sum !== 2) throw new Error("bad");
            });
            Deno.test({ name: "second (subtracts)", fn() {
              const diff = 3 - 1;
              if (diff !== 2) throw new Error("bad");
            } });
            """;

    @Test
    void f5OnAFileOfADenoProjectStopsWithItsLocalsAndRunsToItsEnd(@TempDir Path dir) throws Exception {
        Path project = assumeDeno(dir);
        Path main = Files.writeString(project.resolve("main.ts"), PROGRAM);
        try (Window w = Window.open(dir, project, main)) {
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(2));
            w.run("debug.start");
            w.awaitStop(main, 2);
            assertEquals("'DENO'", w.evaluate("shout"));
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(2));
            FxTestSupport.runOnFx(w.dap::resume);
            await("the end", 120, () -> !w.call(w.dap::isActive));
        }
    }

    @Test
    void debugTestAtCaretRunsThatDenoTestAlone(@TempDir Path dir) throws Exception {
        Path project = assumeDeno(dir);
        Path tests = Files.writeString(project.resolve("math_test.ts"), TESTS);
        try (Window w = Window.open(dir, project, tests)) {
            FxTestSupport.runOnFx(() -> {
                w.buffer.toggleBreakpoint(2);
                w.buffer.toggleBreakpoint(6);
                w.buffer.getFocusedArea().moveTo(4, 0);
            });
            w.run("test.debugAtCaret");
            w.awaitStop(tests, 6); // the second test's: the first one did not run
            assertEquals("2", w.evaluate("diff"));
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(6));
            FxTestSupport.runOnFx(w.dap::resume);
            await("the end", 120, () -> !w.call(w.dap::isActive));
        }
    }

    /** A Deno project folder in {@code dir}, after checking the tools are here. */
    private static Path assumeDeno(Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", home).isPresent(), "needs vscode-js-debug");
        assumeTrue(Path.of(com.editora.run.DenoRun.executable()).isAbsolute(), "needs deno");
        Path project = Files.createDirectory(dir.resolve("deno-app")).toRealPath();
        Files.writeString(project.resolve("deno.json"), "{}\n");
        return project;
    }

    /** A window with {@code file} open, its folder trusted, debugging on. */
    private static final class Window implements AutoCloseable {
        final FxWindowFixture fx;
        final DebugCoordinator debug;
        final DapManager dap;
        final CommandRegistry registry;
        final EditorBuffer buffer;

        private Window(FxWindowFixture fx) throws Exception {
            this.fx = fx;
            MainController c = fx.controller;
            debug = FxTestSupport.field(c, "debugCoordinator");
            dap = FxTestSupport.field(c, "dapManager");
            registry = FxTestSupport.field(c, "registry");
            await("JavaScript debugging available", 120, () -> call(() -> debug.debugEffectiveFor("javascript")));
            buffer = FxTestSupport.callOnFx(c::activeBuffer);
        }

        static Window open(Path dir, Path root, Path file) throws Exception {
            Path configDir = Files.createDirectory(dir.resolve("config"));
            SharedConfig seed = new SharedConfig(configDir, false);
            seed.load();
            seed.getSettings().setDebugSupport(true);
            seed.getTrustStore().trust(root);
            assertTrue(seed.saveSettings());
            seed.saveTrust();
            seed.shutdown();
            return new Window(FxWindowFixture.create(
                    configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {}));
        }

        void run(String command) throws Exception {
            FxTestSupport.runOnFx(() -> registry.run(command));
        }

        void awaitStop(Path file, int line) throws Exception {
            await(
                    "a stop",
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));
            DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
            assertEquals(line, top.line(), "on the breakpoint: " + top);
            assertEquals(file, top.file());
        }

        String evaluate(String expression) throws Exception {
            int frame = call(() -> debug.panel().selectedFrameId());
            java.util.concurrent.atomic.AtomicReference<DapModels.EvalResult> r =
                    new java.util.concurrent.atomic.AtomicReference<>();
            FxTestSupport.runOnFx(() -> dap.evaluateFull(expression, frame, "watch", r::set));
            await(expression, 60, () -> r.get() != null);
            return r.get().result();
        }

        <T> T call(Check<T> check) {
            return DenoDebugProbeFxTest.call(check);
        }

        @Override
        public void close() throws Exception {
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
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }
}
