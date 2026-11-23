package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
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
 * Opt-in probe of return values on debugpy, in a window: a breakpoint on a function's return line, Step Out, and the
 * caller's Locals show {@code (return) add} with the value it returned. Run with {@code ./mvnw test
 * -Dtest=ReturnValueProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs Python and debugpy (Editora's install).
 */
@Tag("probe")
@Tag("fx")
class ReturnValueProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** 0-based line 2 is {@code return total}. */
    static final String SCRIPT = """
            def add(a, b):
                total = a + b
                return total

            result = add(2, 3)
            print("result", result)
            """;

    @Test
    void stepOutOfAPythonFunctionShowsWhatItReturned(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path script = Files.writeString(dir.toRealPath().resolve("adder.py"), SCRIPT);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(script, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            await("Python debugging available", 120, () -> call(() -> debug.debugEffectiveFor("python")));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            awaitStopAt(debug, dap, 2);
            FxTestSupport.runOnFx(() -> registry.run("debug.stepOut"));
            awaitStopAt(debug, dap, 4);
            int frame = call(() -> debug.panel().callStack().getFirst().id());
            List<DapModels.VariableInfo> locals = locals(dap, frame);
            DapModels.VariableInfo returned = locals.stream()
                    .filter(v -> v.name().startsWith("(return)"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no return value in " + locals));
            assertTrue(returned.name().contains("add"), returned.name());
            assertEquals("5", returned.value());
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2));
            FxTestSupport.runOnFx(dap::resume);
            await("the end", 120, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static void awaitStopAt(DebugCoordinator debug, DapManager dap, int line) throws Exception {
        await(
                "a stop on line " + line,
                120,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
    }

    /** The first scope's variables of {@code frame} (debugpy's Locals). */
    private static List<DapModels.VariableInfo> locals(DapManager dap, int frame) throws Exception {
        CompletableFuture<List<DapModels.VariableInfo>> out = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> dap.scopes(frame, scopes -> {
            if (scopes.isEmpty()) {
                out.complete(List.of());
            } else {
                dap.variables(scopes.getFirst().variablesReference(), out::complete);
            }
        }));
        return out.get(60, TimeUnit.SECONDS);
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
