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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of triggered breakpoints against the real Python (debugpy), JavaScript (js-debug) and Java (java-debug
 * in JDT LS) adapters, in a window: in a four-pass loop, a breakpoint on the loop's first line waits for one inside
 * {@code if (i == 1)}; the program first stops on that one with {@code i} 1, then on the waiting one with {@code i} 2
 * and 3 — never with 0 or 1. Run with {@code ./mvnw test -Dtest=TriggeredBreakpointsProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true} (the Node test needs {@code -Dnode.probe=true} too); each test needs its adapter installed where
 * Editora looks.
 */
@Tag("probe")
@Tag("fx")
class TriggeredBreakpointsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("gate.py"),
                "total = 0\nfor i in range(4):\n    total += i\n    if i == 1:\n        mark = i\n"
                        + "print('total', total)\n");
        probe(dir, file, 2, 4, "python", s -> {});
    }

    @Test
    void javascript(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("gate.js"), """
                let total = 0;
                for (let i = 0; i < 4; i++) {
                  total += i;
                  if (i === 1) {
                    const mark = i;
                  }
                }
                console.log('total', total);
                """);
        probe(dir, file, 2, 4, "javascript", s -> {});
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
                    public static void main(String[] args) {
                        int total = 0;
                        for (int i = 0; i < 4; i++) {
                            total += i;
                            if (i == 1) {
                                int mark = i;
                            }
                        }
                        System.out.println("total " + total);
                    }
                }
                """);
        probe(dir, file, 4, 6, "java", s -> {
            s.setLspSupport(true);
            s.setJavaLspEnabled(true);
            s.setJavaLspCommand(jdtls.toString());
            s.setJavaDebugPluginPath(plugin.toString());
        });
    }

    /**
     * {@code waiting} (0-based) waits for {@code trigger}: the program stops on {@code trigger} with i 1, then on
     * {@code waiting} with i 2 and 3, and runs to its end.
     */
    private void probe(
            Path dir,
            Path file,
            int waiting,
            int trigger,
            String language,
            java.util.function.Consumer<com.editora.config.Settings> settings)
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
            await(language + " debugging available", 90, () -> call(() -> debug.debugEffectiveFor(language)));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(trigger));
            BreakpointsPicker.Entry target = call(() ->
                    BreakpointsPicker.lineEntries(FxTestSupport.field(debug, "host"), FxTestSupport.field(debug, "ops"))
                            .stream()
                            .filter(e -> e.line() == trigger)
                            .findFirst()
                            .orElseThrow());
            FxTestSupport.runOnFx(() -> debug.linkTriggered(b, waiting, target));
            assertTrue(call(() -> b.getBreakpointManager().get(waiting).isTriggered()));

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, trigger);
            awaitRow(debug, "i", "1");
            for (int i = 2; i <= 3; i++) {
                FxTestSupport.runOnFx(dap::resume);
                awaitStop(dap, debug, waiting);
                awaitRow(debug, "i", String.valueOf(i));
            }
            FxTestSupport.runOnFx(dap::resume);
            await("the program ran to its end", 60, () -> !call(dap::isActive));
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

    private static void awaitStop(DapManager dap, DebugCoordinator debug, int line) throws InterruptedException {
        try {
            await(
                    "a stop on line " + (line + 1),
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : "line " + (debug.panel().callStack().getFirst().line() + 1));
            TreeItem<DebugPanel.VarRow> i = call(() -> row(debug.panel(), "i"));
            throw new AssertionError(
                    e.getMessage() + " (" + call(dap::state) + ", " + top + ", i = "
                            + (i == null ? "?" : i.getValue().value()) + ")",
                    e);
        }
    }

    /** The first row called {@code name} in any scope of the tree shown. */
    private static TreeItem<DebugPanel.VarRow> row(DebugPanel panel, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
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
                30,
                () -> call(() -> {
                    TreeItem<DebugPanel.VarRow> row = row(debug.panel(), name);
                    return row != null && value.equals(row.getValue().value());
                }));
    }
}
