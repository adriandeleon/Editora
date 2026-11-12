package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of the debugger's small wins against debugpy and java-debug with JDT LS, in a window. Python: a dict
 * entry's Copy as Expression is debugpy's {@code d['k']}; a step is timed after its line; the gutter's Run to Here stops
 * on the line clicked; a logpoint with {@code $FUNCTION} and {@code $CALLSTACK} is written and the program goes on.
 * Java: a list element's expression is java-debug's, and Jump to Type Source opens a value's own class (a nested record
 * behind an interface) and a JDK class. Run with {@code ./mvnw test -Dtest=DebugSmallWinsProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class DebugSmallWinsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private static final String PYTHON = """
            class Point:
                def __init__(self, x):
                    self.x = x

            def area(p):
                d = {"k": [1, 2]}
                total = p.x * 2
                return total

            p = Point(3)
            for i in range(2):
                print(area(p))
            print("done")
            x = 1
            print(x)
            """;

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), PYTHON);
        FxWindowFixture fx = window(dir, file, st -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("python debugging available", 90, () -> call(() -> debug.debugEffectiveFor("python")));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(6));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 6);

            // Copy as Expression: debugpy's own expression for a dict entry, not the path d.'k'.
            await("d", 30, () -> call(() -> row(debug.panel(), "d") != null));
            TreeItem<DebugPanel.VarRow> entry = child(debug, "d", "'k'");
            assertEquals("d['k']", call(() -> DebugPanel.expression(entry)));

            // A step is timed, after the line it stopped on.
            FxTestSupport.runOnFx(dap::stepOver);
            awaitStop(dap, debug, 7);
            await("the step's time", 30, () -> call(() -> elapsed(b)) != null);
            assertEquals(7, (int)
                    call(() -> FxTestSupport.<Integer>field(FxTestSupport.field(b, "inlineValues"), "elapsedLine")));

            // Run to Here from the gutter: line 13 (print("done")), past the breakpoint taken away.
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(6));
            Label number = call(() -> lineNumber(b, 12));
            FxTestSupport.runOnFx(() -> {
                Event.fireEvent(number, mouse(MouseEvent.MOUSE_ENTERED));
                Event.fireEvent(number, mouse(MouseEvent.MOUSE_CLICKED));
            });
            awaitStop(dap, debug, 12);

            // A logpoint with tokens: written by Editora, and the program goes on to the next breakpoint.
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(13);
                b.getBreakpointManager().setLogMessage(13, "$FUNCTION: i={i}$CALLSTACK");
                b.toggleBreakpoint(14);
            });
            FxTestSupport.runOnFx(dap::resume);
            awaitStop(dap, debug, 14);
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
            assertTrue(console.contains("<module>: i=1\n\t<module>(m.py:14)"), console);
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void java(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "needs JDT LS and java-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loose")).toRealPath().resolve("Main.java"), """
                import java.util.*;
                public class Main {
                    interface Shape { double area(); }
                    record Square(double side) implements Shape { public double area() { return side * side; } }
                    public static void main(String[] args) {
                        Shape shape = new Square(2);
                        List<Integer> items = new ArrayList<>(List.of(1, 2));
                        System.out.println(shape.area() + items.size());
                    }
                }
                """);
        FxWindowFixture fx = window(dir, file, st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            LspManager lsp = FxTestSupport.field(c, "lspManager");
            await("java debugging available", 120, () -> call(() -> debug.debugEffectiveFor("java")));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(7));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 7);
            await("the variables", 30, () -> call(() -> row(debug.panel(), "items") != null));

            // java-debug's own expression for a list element: the path would read items.0 or items[0], not Java.
            TreeItem<DebugPanel.VarRow> first = child(debug, "items", "0");
            assertEquals("items.get(0)", call(() -> DebugPanel.expression(first)));

            await("workspace symbols", 120, () -> call(() -> lsp.supportsWorkspaceSymbols(file)));
            DebugPanel.VarRow shape = call(() -> row(debug.panel(), "shape").getValue());
            assertNotNull(DebugTypeSource.parse(shape.type(), shape.value()), shape.type());
            FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(0, 0));
            FxTestSupport.runOnFx(() -> debug.jumpToTypeSource(shape.type(), shape.value()));
            await(
                    "Square's declaration",
                    60,
                    () -> call(() -> c.activeBuffer() == b && b.getFocusedArea().getCurrentParagraph() == 3));

            DebugPanel.VarRow items = call(() -> row(debug.panel(), "items").getValue());
            FxTestSupport.runOnFx(() -> debug.jumpToTypeSource(items.type(), items.value()));
            try {
                await(
                        "ArrayList's source",
                        60,
                        () -> call(() -> c.activeBuffer() != null
                                && c.activeBuffer() != b
                                && c.activeBuffer().getDisplayName().startsWith("ArrayList")));
            } catch (AssertionError e) {
                throw new AssertionError(
                        e.getMessage() + ": type " + items.type() + ", tab "
                                + call(() -> c.activeBuffer().getDisplayName()) + ", status "
                                + call(() -> FxTestSupport.<javafx.scene.control.Label>field(
                                                FxTestSupport.field(c, "statusBar"), "echo")
                                        .getText()),
                        e);
            }
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static FxWindowFixture window(
            Path dir, Path file, java.util.function.Consumer<com.editora.config.Settings> settings) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        return FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
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
        if (tree.getRoot() == null) {
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

    /** Expands row {@code parent} and returns its child called {@code name}, once loaded. */
    private static TreeItem<DebugPanel.VarRow> child(DebugCoordinator debug, String parent, String name)
            throws Exception {
        FxTestSupport.runOnFx(() -> row(debug.panel(), parent).setExpanded(true));
        try {
            await(parent + "'s " + name, 30, () -> call(() -> find(row(debug.panel(), parent), name) != null));
        } catch (AssertionError e) {
            throw new AssertionError(
                    e.getMessage() + ": "
                            + call(() -> row(debug.panel(), parent).getChildren().stream()
                                    .map(i -> i.getValue() == null
                                            ? "?"
                                            : i.getValue().name() + "="
                                                    + i.getValue().evaluateName())
                                    .toList()),
                    e);
        }
        return call(() -> find(row(debug.panel(), parent), name));
    }

    private static TreeItem<DebugPanel.VarRow> find(TreeItem<DebugPanel.VarRow> item, String name) {
        if (item == null) {
            return null;
        }
        return item.getChildren().stream()
                .filter(i -> i.getValue() != null && i.getValue().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static String elapsed(EditorBuffer b) {
        Object overlay = FxTestSupport.field(b, "inlineValues");
        return overlay == null ? null : FxTestSupport.field(overlay, "elapsed");
    }

    private static Label lineNumber(EditorBuffer b, int line) {
        HBox row = (HBox) b.getFocusedArea().getParagraphGraphicFactory().apply(line);
        return (Label) row.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("lineno"))
                .findFirst()
                .orElseThrow();
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(
                type,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }
}
