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
 * Opt-in probe of Lua debugging against local-lua-debugger and a real Lua, in a window: F5 on a Lua script stops at a
 * breakpoint in a function, its locals read, a step goes to the next line, the script's own output reaches the Debug
 * console, and Continue runs it to its end; a script that raises an error stops there. Run with {@code ./mvnw test
 * -Dtest=LuaDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs Node.js, a {@code lua} (or {@code lua5.4}…) and
 * local-lua-debugger (Editora's install, or {@code -Dluadebug.path=<debugAdapter.js>}).
 */
@Tag("probe")
@Tag("fx")
class LuaDebugProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** 0-based line 2 is {@code return sum}. */
    static final String SCRIPT = """
            local function add(a, b)
              local sum = a + b
              return sum
            end
            local total = 0
            for i = 1, 3 do
              total = add(total, i)
              print("running " .. total)
            end
            print("total " .. total)
            """;

    /** 0-based line 1 raises. */
    static final String FAILING = """
            local t = nil
            print(t.field)
            """;

    @Test
    void aScriptStopsInAFunctionWithItsLocalsStepsPrintsAndEnds(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.toRealPath().resolve("sum.lua"), SCRIPT);
        try (Window w = Window.open(dir, script)) {
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(2));
            w.run("debug.start");
            w.awaitStop(script, 2);
            assertEquals("1", w.evaluate("sum"), "the first sum");
            assertEquals("0", w.evaluate("a"));
            w.run("debug.stepOver");
            await(
                    "the next line",
                    60,
                    () -> call(() -> w.dap.state() == DapManager.State.SUSPENDED
                            && !w.debug.panel().callStack().isEmpty()
                            && w.debug.panel().callStack().getFirst().line() != 2));
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(2)); // off: Continue runs to the end
            FxTestSupport.runOnFx(w.dap::resume);
            await("the end", 120, () -> !call(w.dap::isActive));
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(w.debug.panel(), "console")
                    .getText());
            assertTrue(console.contains("total 6"), "the script's output: " + console);
        }
    }

    @Test
    void anErrorStopsWhereItIsRaised(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.toRealPath().resolve("fail.lua"), FAILING);
        try (Window w = Window.open(dir, script)) {
            w.run("debug.start");
            w.awaitStop(script, 1);
            FxTestSupport.runOnFx(w.dap::stop);
            await("stopped", 60, () -> !call(w.dap::isActive));
        }
    }

    /** A window with {@code file} open, debugging on; skips without the tools. */
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
            await("Lua debugging available", 120, () -> call(() -> debug.debugEffectiveFor("lua")));
            buffer = FxTestSupport.callOnFx(c::activeBuffer);
        }

        static Window open(Path dir, Path file) throws Exception {
            assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
            String configured = System.getProperty("luadebug.path", "");
            assumeTrue(DapManager.locateExternal(DebugLanguages.LUA, configured) != null, "needs local-lua-debugger");
            boolean lua = com.editora.dap.ScriptLaunch.LUA_INTERPRETERS.stream()
                    .anyMatch(n -> Path.of(com.editora.process.ProcessRunner.resolveExecutable(List.of(n))
                                    .getFirst())
                            .isAbsolute());
            assumeTrue(lua, "needs lua on the PATH");
            Path configDir = Files.createDirectory(dir.resolve("config"));
            SharedConfig seed = new SharedConfig(configDir, false);
            seed.load();
            seed.getSettings().setDebugSupport(true);
            seed.getSettings().setLuaDebugPath(configured);
            assertTrue(seed.saveSettings());
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
            assertEquals(line, top.line(), "on line " + line + ": " + top);
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
