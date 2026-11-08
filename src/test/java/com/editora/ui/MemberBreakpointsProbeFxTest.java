package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.MemberBreakpoint;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the installed JDT LS and java-debug adapter, in a real window: a method breakpoint set from
 * the caret (the method read from jdtls's outline) stops the program on entering the method, and a field watchpoint
 * set from the caret while it runs stops it where the field is assigned — after the method breakpoint was taken
 * away again. Both are marked in the gutter on their declaration lines, read from jdtls's outline, and a mark follows
 * its member when an edit moves it. Run it with {@code ./mvnw test -Dtest=MemberBreakpointsProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class MemberBreakpointsProbeFxTest {

    private static final String SOURCE = """
            public class Main {
                static int count;
                static void bump(int n) {
                    count += n;
                }
                public static void main(String[] args) throws Exception {
                    Thread.sleep(500);
                    for (int i = 1; i <= 3; i++) {
                        bump(i);
                    }
                    Thread.sleep(20000);
                }
            }
            """;

    private static final int FIELD_LINE = 1; // static int count;
    private static final int BODY_LINE = 3; // count += n;
    private static final int DECLARATION_LINE = 2; // static void bump(int n) {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void methodBreakpointAndFieldWatchpointFromTheCaret(@TempDir Path dir) throws Exception {
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

            // The caret in bump's body: jdtls's outline says which method that is.
            MemberBreakpoint bump = MemberBreakpoint.method("Main", "bump");
            com.editora.lsp.LspManager lsp = FxTestSupport.field(c, "lspManager");
            await("jdtls's outline of the file", 60, () -> call(() -> lsp.supportsDocumentSymbols(file)));
            FxTestSupport.runOnFx(() -> {
                b.getFocusedArea().moveTo(BODY_LINE, 4);
                debug.toggleMethodBreakpoint();
            });
            await(
                    "the method breakpoint from the caret",
                    30,
                    () -> call(() -> debug.memberBreakpoints().all()).contains(bump));
            // Its mark: on bump's declaration line, read from jdtls's outline.
            awaitMark(b, DECLARATION_LINE, "method");

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, BODY_LINE, debug);
            awaitRow(debug, "n", "1");
            awaitStatusText(debug, tr("debugpanel.state.methodBreakpoint"));

            // While suspended: a watchpoint on the field (caret on its declaration), the method breakpoint off.
            FxTestSupport.runOnFx(() -> {
                b.getFocusedArea().moveTo(FIELD_LINE, 16);
                debug.toggleFieldWatchpoint();
            });
            MemberBreakpoint count = MemberBreakpoint.field("Main", "count", MemberBreakpoint.WRITE);
            await(
                    "the field watchpoint from the caret",
                    20,
                    () -> call(() -> debug.memberBreakpoints().all()).contains(count));
            awaitMark(b, FIELD_LINE, "field");
            FxTestSupport.runOnFx(() -> debug.memberBreakpoints().remove(bump));
            assertEquals(List.of(count), call(() -> debug.memberBreakpoints().all()));
            await(
                    "bump's mark gone",
                    20,
                    () -> call(() -> b.getBreakpointManager().memberMark(DECLARATION_LINE)) == null);

            // A method breakpoint stops before the body runs: this call's own assignment is the next stop, and
            // the call after it stops only at its assignment, not on entering (that breakpoint is gone).
            resume(dap, debug);
            awaitStop(dap, BODY_LINE, debug);
            awaitRow(debug, "n", "1");
            awaitStatusText(debug, tr("debugpanel.state.fieldWatchpoint"));
            resume(dap, debug);
            awaitStop(dap, BODY_LINE, debug);
            awaitRow(debug, "n", "2");
            awaitStatusText(debug, tr("debugpanel.state.fieldWatchpoint"));

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 20, () -> !call(dap::isActive));

            // An edit above moves the field: after the typing pause its mark is on its new line.
            FxTestSupport.runOnFx(() -> b.getArea().insertText(0, 0, "// moved\n"));
            awaitMark(b, FIELD_LINE + 1, "field");
            assertEquals(null, call(() -> b.getBreakpointManager().memberMark(FIELD_LINE)));
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

    /** A member mark of {@code kind} ({@code method} / {@code field}) on (view) {@code line}. */
    private static void awaitMark(EditorBuffer b, int line, String kind) throws InterruptedException {
        await(
                "a " + kind + " mark on line " + (line + 1),
                60,
                () -> call(() -> {
                    String classes = b.getBreakpointManager().glyphClasses(line);
                    return b.getBreakpointManager().memberMark(line) != null
                            && classes != null
                            && classes.startsWith(kind);
                }));
    }

    private static void awaitStop(DapManager dap, int line, DebugCoordinator debug) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                90,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().get(0).line() == line));
    }

    private static void awaitStatusText(DebugCoordinator debug, String text) throws InterruptedException {
        await(
                "the state line '" + text + "'",
                20,
                () -> call(() -> {
                    Label status = FxTestSupport.field(debug.panel(), "status");
                    return status.getText().contains(text);
                }));
    }

    /** Resumes, and forgets the tree shown so far: a row is looked for only in the next stop's tree. */
    private static void resume(DapManager dap, DebugCoordinator debug) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            staleRoot = tree.getRoot();
            dap.resume();
        });
    }

    private static TreeItem<DebugPanel.VarRow> staleRoot;

    private static TreeItem<DebugPanel.VarRow> row(DebugPanel panel, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        if (tree.getRoot() == staleRoot) {
            return null;
        }
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
                20,
                () -> call(() -> {
                    TreeItem<DebugPanel.VarRow> row = row(debug.panel(), name);
                    return row != null && value.equals(row.getValue().value());
                }));
    }
}
