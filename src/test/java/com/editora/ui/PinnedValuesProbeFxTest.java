package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

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
 * Opt-in probe against the installed JDT LS and java-debug adapter, in a real window: values pinned to a line — one
 * from the caret, one typed — are evaluated at every stop and painted at the end of that line with that stop's
 * values, are saved with the project's breakpoints, and are not shown while the program runs. Run it with
 * {@code ./mvnw test -Dtest=PinnedValuesProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class PinnedValuesProbeFxTest {

    private static final String SOURCE = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    int total = 0;
                    for (int i = 0; i < 5; i++) {
                        total += i;
                        Thread.sleep(100);
                    }
                    Thread.sleep(20000);
                }
            }
            """;

    private static final int PIN_LINE = 4; // total += i;
    private static final int STOP_LINE = 5; // Thread.sleep(100);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void pinnedValuesShowEachStopsValuesAtTheirLine(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);
        Path file = Files.createDirectory(dir.resolve("loose")).resolve("Main.java");
        Files.writeString(file, SOURCE);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setJavaLspEnabled(true);
        seed.getSettings().setJavaLspCommand(jdtls.toString());
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setJavaDebugPluginPath(plugin.toString());
        assertTrue(seed.saveSettings());
        seed.shutdown();

        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Java debugging available", 60, () -> call(debug::debugEffective));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(STOP_LINE);
                b.getFocusedArea().moveTo(PIN_LINE, 14); // on "total" (after the indent)
                debug.togglePinAtCaret();
                debug.pinnedValues().toggle(b, PIN_LINE, "i * 10");
            });
            assertEquals(
                    List.of("total", "i * 10"), call(() -> b.getPinManager().expressions(PIN_LINE)));
            assertEquals(
                    List.of("total", "i * 10"),
                    call(() -> c.config
                            .getPins()
                            .get(DebugCoordinator.key(file))
                            .getFirst()
                            .expressions()),
                    "saved with the project's breakpoints");

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitPinned(b, "total = 0  ·  i * 10 = 0");
            FxTestSupport.runOnFx(dap::resume);
            awaitPinned(b, "total = 1  ·  i * 10 = 10"); // the next stop's values

            // Running: nothing pinned is shown (the breakpoint off, so it runs on).
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(STOP_LINE);
                dap.resume();
            });
            await("the pins cleared while running", 20, () -> pinned(b) == null);

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 20, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static Map<Integer, String> pinned(EditorBuffer b) {
        return call(() -> {
            Object overlay = FxTestSupport.field(b, "inlineValues");
            return overlay == null ? null : FxTestSupport.field(overlay, "pinned");
        });
    }

    private static void awaitPinned(EditorBuffer b, String text) throws InterruptedException {
        try {
            await("line " + (PIN_LINE + 1) + " painting " + text, 60, () -> {
                Map<Integer, String> shown = pinned(b);
                return shown != null && text.equals(shown.get(PIN_LINE));
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " (painted: " + pinned(b) + ")", e);
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
