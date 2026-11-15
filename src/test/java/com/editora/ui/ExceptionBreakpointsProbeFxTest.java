package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.ExceptionBreakpoint;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of exception breakpoints against debugpy and java-debug with JDT LS, in a window. Each program throws
 * three exceptions, each caught: two of one type (the first and the third) and one of another. With the Caught
 * choice off and one exception breakpoint on the first type whose condition holds only the third time, the program
 * stops once — at the third throw — and runs to its end. Run with {@code ./mvnw test
 * -Dtest=ExceptionBreakpointsProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ExceptionBreakpointsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), """
                def risky(n):
                    if n == 1:
                        raise KeyError("one")
                    if n == 2:
                        raise ValueError("two")
                    raise KeyError("three")
                for n in (1, 2, 3):
                    try:
                        risky(n)
                    except Exception:
                        pass
                print("done")
                """);
        probe(dir, file, "python", new ExceptionBreakpoint("python", "KeyError", "n == 3", true), 5, st -> {});
    }

    @Test
    void java(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "needs JDT LS and java-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loose")).toRealPath().resolve("Main.java"), """
                public class Main {
                    static void risky(int n) {
                        if (n == 1) throw new IllegalStateException("one");
                        if (n == 2) throw new IllegalArgumentException("two");
                        throw new IllegalStateException("three");
                    }
                    public static void main(String[] args) {
                        for (int n = 1; n <= 3; n++) {
                            try { risky(n); } catch (RuntimeException e) { }
                        }
                        System.out.println("done");
                    }
                }
                """);
        probe(dir, file, "java", new ExceptionBreakpoint("java", "IllegalStateException", "n == 3", true), 4, st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
    }

    /**
     * Debugs {@code file} with {@code breakpoint} the project's one exception breakpoint, resuming every stop shown;
     * the program is expected to stop once, on 0-based {@code line}, and end.
     */
    private static void probe(
            Path dir, Path file, String language, ExceptionBreakpoint breakpoint, int line, Consumer<Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> {
                c.config.getExceptionBreakpoints().add(breakpoint);
                debug.pushExceptionBreakpoints();
            });
            assertTrue(!call(() -> debug.exceptionToggleOn(true)), "Caught is off: the breakpoint decides");
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await("started", 120, () -> call(dap::isActive));

            // Every stop shown is noted and resumed, until the program ends.
            List<Integer> shown = new ArrayList<>();
            List<String> types = new ArrayList<>();
            int[] seen = {call(() -> debug.s().stopSerial)};
            long end = System.nanoTime() + 180_000_000_000L;
            while (call(dap::isActive) && System.nanoTime() < end) {
                Boolean stopped = call(() -> dap.state() == DapManager.State.SUSPENDED
                        && debug.s().stopSerial > seen[0]
                        && debug.s().quietStop != debug.s().stopSerial
                        && !debug.panel().callStack().isEmpty());
                if (stopped) {
                    seen[0] = call(() -> debug.s().stopSerial);
                    shown.add(call(() -> debug.panel().callStack().getFirst().line()));
                    await("the exception named", 30, () -> call(() -> debug.s().stopException != null));
                    types.add(call(() -> debug.s().stopException.type()));
                    await("resumed", 30, () -> {
                        if (call(dap::state) == DapManager.State.SUSPENDED) {
                            call(() -> {
                                dap.resume();
                                return true;
                            });
                        }
                        return call(dap::state) != DapManager.State.SUSPENDED || !call(dap::isActive);
                    });
                }
                Thread.sleep(50);
            }
            assertTrue(!call(dap::isActive), "the program ended");
            assertEquals(List.of(line), shown, "one stop, the third throw: " + types);
            assertTrue(types.getFirst().endsWith(breakpoint.type()), types.toString());
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
}
