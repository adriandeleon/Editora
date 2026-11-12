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

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Mark Object and View As against java-debug and debugpy, in a window: two variables holding the same
 * object show the label given to one of them, another object shows none; a string holding JSON opens laid out, read
 * only, in a JSON tab — its text as the program has it, escapes and all — and one that is not XML opens as text with
 * the reason said. Run with {@code ./mvnw test -Dtest=MarkObjectProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class MarkObjectProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

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
                        java.util.List<Integer> first = new java.util.ArrayList<>(java.util.List.of(1, 2));
                        java.util.List<Integer> same = first;
                        java.util.List<Integer> other = new java.util.ArrayList<>();
                        String s = "{\\"a\\": \\"x\\\\ny\\", \\"b\\": [1, 2]}";
                        System.out.println(s + same + other);
                    }
                }
                """);
        probe(dir, file, 6, "java", st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
    }

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"),
                "class Box:\n    pass\nfirst = Box()\nsame = first\nother = Box()\n"
                        + "s = '{\"a\": \"x\\\\ny\", \"b\": [1, 2]}'\nprint(s, same, other)\n");
        probe(dir, file, 6, "python", st -> {});
    }

    /** At a stop on {@code line}: mark {@code first}, see it on {@code same} only, then View As JSON and XML of s. */
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
            awaitStop(dap, debug, line);
            await(
                    "the variables",
                    30,
                    () -> call(() -> row(debug.panel(), "s") != null && row(debug.panel(), "same") != null));
            DebugPanel panel = call(debug::panel);
            DebugPanel.VarRow first = call(() -> row(panel, "first").getValue());
            FxTestSupport.runOnFx(() -> panel.markPrompt(first));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(c, "the list"));
            ObjectMarks marks = call(panel::objectMarks);
            assertEquals(
                    "the list",
                    call(() -> marks.labelOf(row(panel, "same").getValue().value())),
                    "the same object");
            assertNull(call(() -> marks.labelOf(row(panel, "other").getValue().value())), "another object");

            DebugPanel.VarRow s = call(() -> row(panel, "s").getValue());
            FxTestSupport.runOnFx(() -> panel.viewAs(s, "s", DebugPanel.ViewAs.JSON));
            await(
                    "the JSON tab",
                    30,
                    () -> call(() -> c.activeBuffer() != null
                            && c.activeBuffer().getPath() == null
                            && "json".equals(c.activeBuffer().getLanguage())));
            assertEquals(
                    "{\n  \"a\" : \"x\\ny\",\n  \"b\" : [ 1, 2 ]\n}\n",
                    call(() -> c.activeBuffer().getArea().getText()),
                    "laid out, its escape a JSON one");
            assertTrue(call(() -> c.activeBuffer().isViewMode()));
            FxTestSupport.runOnFx(() -> panel.viewAs(s, "s", DebugPanel.ViewAs.XML));
            await(
                    "said why",
                    30,
                    // In the log of what the status line showed, not on the line itself: the line holds the newest
                    // message, and a language server that is still starting writes its progress over this one.
                    () -> call(() -> {
                        StatusBar bar = FxTestSupport.field(c, "statusBar");
                        String said = tr("status.debug.viewAsFailed", "XML", "").strip();
                        return FxTestSupport.<MessageLog>field(bar, "messageLog").entries().stream()
                                .anyMatch(message -> message.text().startsWith(said));
                    }));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
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
