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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of expression evaluation against the real Python (debugpy), JavaScript (js-debug) and Java (java-debug
 * in JDT LS) adapters, in a window: the evaluate field over Variables shows a list as a first row whose elements open
 * under it, a method call runs and what it changed shows, a code fragment of several lines is sent whole (what the
 * adapter makes of it is printed), and the Evaluate window evaluates in the same frame. Run with
 * {@code ./mvnw test -Dtest=EvaluateProbeFxTest -Dgroups=probe -Dlsp.probe=true} (the Node test needs
 * {@code -Dnode.probe=true} too).
 */
@Tag("probe")
@Tag("fx")
class EvaluateProbeFxTest {

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
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("main.py"),
                "items = [1, 2, 3]\ntotal = 0\nprint('total', total, items)\n");
        probe(dir, file, 2, "python", "items", "items.append(7)", "len(items)", "x = 2\nx * 3", s -> {});
    }

    @Test
    void javascript(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("main.js"),
                "const items = [1, 2, 3];\nlet total = 0;\nconsole.log('total', total, items);\n");
        probe(dir, file, 2, "javascript", "items", "items.push(7)", "items.length", "let x = 2;\nx * 3", s -> {});
    }

    @Test
    void java(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "needs JDT LS and java-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loose")).toRealPath().resolve("Main.java"), """
                import java.util.ArrayList;
                import java.util.List;

                public class Main {
                    public static void main(String[] args) {
                        List<Integer> items = new ArrayList<>(List.of(1, 2, 3));
                        int total = 0;
                        System.out.println("total " + total + " " + items);
                    }
                }
                """);
        probe(dir, file, 7, "java", "items", "items.add(7)", "items.size()", "int x = 2;\nreturn x * 3;", s -> {
            s.setLspSupport(true);
            s.setJavaLspEnabled(true);
            s.setJavaLspCommand(jdtls.toString());
            s.setJavaDebugPluginPath(plugin.toString());
        });
    }

    private void probe(
            Path dir,
            Path file,
            int line,
            String language,
            String list,
            String call,
            String size,
            String fragment,
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
            await(
                    "a stop on line " + (line + 1),
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));
            DebugPanel panel = call(debug::panel);
            await("the frame's variables", 30, () -> call(() -> topRows(panel).size() > 1));
            assertFalse(call(() -> panel.evaluateField().languageFile()).isEmpty(), "highlighted as the frame's file");

            // A list: the first row, its elements under it.
            FxTestSupport.runOnFx(() -> panel.evaluateInVariables(list));
            await(
                    "the list evaluated",
                    30,
                    () -> call(() -> result(panel) != null
                            && !"…".equals(result(panel).getValue().value())));
            TreeItem<DebugPanel.VarRow> listRow = call(() -> result(panel));
            System.out.println("PROBE " + language + " " + list + " = "
                    + listRow.getValue().value());
            assertFalse(listRow.getValue().failed(), listRow.getValue().value());
            await(
                    "its elements",
                    30,
                    () -> call(() -> result(panel).getChildren().size() >= 3));

            // A method call runs (context "repl"), and what it changed shows.
            FxTestSupport.runOnFx(() -> panel.evaluateInVariables(call));
            await(
                    "the call evaluated",
                    30,
                    () -> call(() -> result(panel) != null
                            && result(panel).getValue().name().equals(call)
                            && !"…".equals(result(panel).getValue().value())));
            System.out.println("PROBE " + language + " " + call + " = "
                    + call(() -> result(panel).getValue().value()));
            assertFalse(
                    call(() -> result(panel).getValue().failed()),
                    call(() -> result(panel).getValue().value()));
            FxTestSupport.runOnFx(() -> panel.evaluateInVariables(size));
            await(
                    "the size evaluated",
                    30,
                    () -> call(() -> result(panel) != null
                            && result(panel).getValue().name().equals(size)
                            && !"…".equals(result(panel).getValue().value())));
            assertTrue(call(() -> result(panel).getValue().value()).contains("4"), "the call added an element");

            // A code fragment of several lines: sent whole; the adapter decides.
            FxTestSupport.runOnFx(() -> panel.evaluateInVariables(fragment));
            await(
                    "the fragment evaluated",
                    60,
                    () -> call(() -> result(panel) != null
                            && result(panel).getValue().name().equals(fragment)
                            && !"…".equals(result(panel).getValue().value())));
            DebugPanel.VarRow fragmentRow = call(() -> result(panel).getValue());
            System.out.println(
                    "PROBE " + language + " fragment failed=" + fragmentRow.failed() + " value=" + fragmentRow.value());

            // The Evaluate window, in the same frame.
            DebugEvaluateWindow window = call(() -> debug.openEvaluateWindow(size));
            FxTestSupport.runOnFx(() -> window.evaluate(size));
            await(
                    "the window's result",
                    30,
                    () -> call(() -> window.resultTree().getRoot() != null
                            && !"…"
                                    .equals(window.resultTree()
                                            .getRoot()
                                            .getValue()
                                            .value())));
            System.out.println("PROBE " + language + " window " + size + " = "
                    + call(() -> window.resultTree().getRoot().getValue().value()));
            assertTrue(
                    call(() -> window.resultTree().getRoot().getValue().value()).contains("4"));
            assertTrue(call(window::contextText).length() > 0);
            FxTestSupport.runOnFx(window::close);

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
            assertEquals(null, call(() -> result(panel)), "the result was that stop's");
        } finally {
            fx.dispose();
        }
    }

    private static List<String> topRows(DebugPanel panel) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        return tree.getRoot().getChildren().stream()
                .map(i -> i.getValue().name())
                .toList();
    }

    private static TreeItem<DebugPanel.VarRow> result(DebugPanel panel) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        return tree.getRoot().getChildren().stream()
                .filter(i -> i.getValue() != null && i.getValue().kind() == DebugPanel.Kind.RESULT)
                .findFirst()
                .orElse(null);
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
