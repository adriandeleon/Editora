package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * Opt-in probe of Debug Test at Caret under Jest and Vitest, in a window: a package with the runner installed (npm,
 * from its cache or the registry), breakpoints in two tests of a suite, the caret in the second: only that test runs,
 * its breakpoint stops with its locals (a {@code .ts} file through Vitest's source maps; Vitest's test runs in a worker
 * process js-debug attaches to), and Continue runs it to its end. Run with {@code ./mvnw test
 * -Dtest=JsTestRunnerProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs Node.js, npm and js-debug.
 */
@Tag("probe")
@Tag("fx")
class JsTestRunnerProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** The first test's check is 0-based line 3, the second's line 7; the caret goes to line 6. */
    static final String TESTS = """
            describe("math (basic)", () => {
              test("adds", () => {
                const sum = 1 + 1;
                expect(sum).toBe(2);
              });
              it("subtracts", () => {
                const diff = 3 - 1;
                expect(diff).toBe(2);
              });
            });
            """;

    @Test
    void jestDebugsTheTestAtTheCaretAlone(@TempDir Path dir) throws Exception {
        Path project = install(dir, "jest");
        probe(dir, project, Files.writeString(project.resolve("math.test.js"), TESTS));
    }

    @Test
    void vitestDebugsTheTestAtTheCaretAloneInItsWorker(@TempDir Path dir) throws Exception {
        Path project = install(dir, "vitest");
        // Vitest's globals are off by default: the test imports them (one line more, so the lines move by one).
        Path test = Files.writeString(
                project.resolve("math.test.ts"), "import { describe, test, it, expect } from \"vitest\";\n" + TESTS);
        probe(dir, project, test, 1);
    }

    private static void probe(Path dir, Path project, Path test) throws Exception {
        probe(dir, project, test, 0);
    }

    private static void probe(Path dir, Path project, Path test, int shift) throws Exception {
        try (Window w = Window.open(dir, project, test)) {
            FxTestSupport.runOnFx(() -> {
                w.buffer.toggleBreakpoint(3 + shift);
                w.buffer.toggleBreakpoint(7 + shift);
                w.buffer.getFocusedArea().moveTo(6 + shift, 0);
            });
            w.run("test.debugAtCaret");
            w.awaitStop(test, 7 + shift); // the second test's: the first did not run
            assertEquals("2", w.evaluate("diff"));
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(7 + shift));
            FxTestSupport.runOnFx(w.dap::resume);
            await("the end", 120, () -> !call(w.dap::isActive));
        }
    }

    /** A package in {@code dir} depending on {@code runner}, installed; skips without the tools. */
    private static Path install(Path dir, String runner) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", home).isPresent(), "needs vscode-js-debug");
        Path project = Files.createDirectory(dir.resolve(runner + "-app")).toRealPath();
        Files.writeString(project.resolve("package.json"), "{ \"name\": \"probe\", \"private\": true }\n");
        Process npm = new ProcessBuilder(com.editora.process.ProcessRunner.resolveExecutable(
                        List.of("npm", "install", "--prefer-offline", "--no-audit", "--no-fund", "-D", runner)))
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("npm.log").toFile())
                .start();
        assumeTrue(npm.waitFor(300, TimeUnit.SECONDS) && npm.exitValue() == 0, "npm install " + runner);
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
                    180,
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
