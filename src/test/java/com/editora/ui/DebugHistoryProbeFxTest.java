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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Debug History against the real Python (debugpy), JavaScript (js-debug) and Java (java-debug in JDT
 * LS) adapters, in a window: a loop stops three times, each stop keeps the values it showed, Back shows the one
 * before as it was, Forward comes back to the live one, Show Value History follows {@code i}, and after Stop the stops
 * can still be read. Run with {@code ./mvnw test -Dtest=DebugHistoryProbeFxTest -Dgroups=probe -Dlsp.probe=true}
 * (the Node test needs {@code -Dnode.probe=true} too); each test needs its adapter installed where Editora looks.
 */
@Tag("probe")
@Tag("fx")
class DebugHistoryProbeFxTest {

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
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("loop.py"),
                "total = 0\nfor i in range(5):\n    total += i\nprint('total', total)\n");
        probe(dir, file, 2, "python", s -> {});
    }

    @Test
    void javascript(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("loop.js"),
                "let total = 0;\nfor (let i = 0; i < 5; i++) {\n  total += i;\n}\nconsole.log('total', total);\n");
        probe(dir, file, 2, "javascript", s -> {});
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
                        for (int i = 0; i < 5; i++) {
                            total += i;
                        }
                        System.out.println("total " + total);
                    }
                }
                """);
        probe(dir, file, 4, "java", s -> {
            s.setLspSupport(true);
            s.setJavaLspEnabled(true);
            s.setJavaLspCommand(jdtls.toString());
            s.setJavaDebugPluginPath(plugin.toString());
        });
    }

    /** Three stops on {@code line} of {@code file}'s loop, then the history read back. */
    private void probe(
            Path dir,
            Path file,
            int line,
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
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            for (int pass = 0; pass < 3; pass++) {
                awaitStop(dap, debug, line);
                awaitRow(debug, "i", String.valueOf(pass)); // shown, so kept
                if (pass < 2) {
                    FxTestSupport.runOnFx(dap::resume);
                    await("running", 20, () -> call(dap::state) != DapManager.State.SUSPENDED);
                }
            }
            DebugPanel panel = call(debug::panel);
            List<DebugHistory.Stop> stops = call(() -> panel.history().stops());
            assertEquals(3, stops.size(), "one kept per stop");
            assertEquals(
                    List.of("0", "1", "2"),
                    stops.stream().map(s -> keptValue(s, "i")).toList());

            FxTestSupport.runOnFx(panel::historyBack);
            assertEquals(2, call(() -> panel.viewing().number));
            assertEquals("1", call(() -> row(panel, "i").getValue().value()), "the stop before, as it was");
            await(
                    "its line marked as an earlier stop's",
                    10,
                    () -> call(() -> b.getFocusedArea()
                            .getParagraph(line)
                            .getParagraphStyle()
                            .contains("exec-history")));
            FxTestSupport.runOnFx(panel::historyForward);
            assertNull(call(panel::viewing), "back to the live stop");
            awaitRow(debug, "i", "2");

            DebugHistory.Stop live = stops.getLast();
            String key = live.frameKey(live.shownFrame);
            List<String> path = live.rows(live.shownFrame).stream()
                    .filter(r -> r.name().equals("i"))
                    .findFirst()
                    .orElseThrow()
                    .path();
            assertEquals(
                    List.of("0", "1", "2"),
                    call(() -> panel.history().valueHistory(key, path)).stream()
                            .map(DebugHistory.Seen::value)
                            .toList(),
                    "Show Value History follows i");

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
            FxTestSupport.runOnFx(panel::historyBack);
            assertEquals(3, call(() -> panel.viewing().number), "after Stop, the last stop");
            assertEquals("2", call(() -> row(panel, "i").getValue().value()));
        } finally {
            fx.dispose();
        }
    }

    /** The value {@code name} showed in the frame a stop selected. */
    private static String keptValue(DebugHistory.Stop stop, String name) {
        return stop.rows(stop.shownFrame).stream()
                .filter(r -> r.name().equals(name))
                .map(DebugHistory.Row::value)
                .findFirst()
                .orElse("(none)");
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
        await(
                "a stop on line " + (line + 1),
                120,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
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
