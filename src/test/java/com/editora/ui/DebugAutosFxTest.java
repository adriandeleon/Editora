package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The Debug window's Autos, against a {@link FakeDebugAdapter} (reached as the Lua debugger is): at a stop, a node last
 * in the Variables tree, closed and evaluating nothing until opened, then listing the variables of the stop's line and
 * the line before it, evaluated, in their order there; what the adapter cannot evaluate is left out, and the next stop
 * keeps it open with that stop's lines.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class DebugAutosFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path luaDebug;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("scripts")).toRealPath();
        Files.writeString(
                root.resolve("sum.lua"),
                "local total = 0\nfor i = 1, 3 do\n  total = total + i * badness\nend\nprint(total)\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path extension = Files.createDirectories(fx.configDir.resolve("lua-debugger/extension"));
        luaDebug = extension.resolve("lua-debug");
        Files.writeString(
                luaDebug,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain stdio " + adapter.port() + "\n");
        assertTrue(luaDebug.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setLuaDebugPath(luaDebug.toString());
            debug.applySupport();
        });
        await("Lua debugging available", () -> call(() -> debug.debugEffectiveFor("lua")));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
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

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Await.until(condition, what, java.time.Duration.ofSeconds(20));
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && file.equals(b.getPath());
                }));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private static StackFrame frame(Path file, int line) {
        StackFrame f = new StackFrame();
        f.setId(0);
        f.setName("main chunk");
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(file.toString());
        f.setSource(source);
        return f;
    }

    /** The variables tree's top-level node called {@code name}, or null. */
    private javafx.scene.control.TreeItem<DebugPanel.VarRow> node(String name) {
        return call(() -> {
            javafx.scene.control.TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            for (var item : tree.getRoot().getChildren()) {
                if (item.getValue() != null && item.getValue().name().equals(name)) {
                    return item;
                }
            }
            return null;
        });
    }

    private List<String> rows(javafx.scene.control.TreeItem<DebugPanel.VarRow> item) {
        return call(() -> item.getChildren().stream()
                .map(i -> i.getValue().name() + "=" + i.getValue().value())
                .toList());
    }

    @Test
    void theAutosShowTheStopLinesValuesAfterWatches() throws Exception {
        Path sum = root.resolve("sum.lua");
        EditorBuffer b = open(sum);
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.frames = List.of(frame(sum, 2));
        session.stop(session.mainThread, "breakpoint");
        String autos = com.editora.i18n.Messages.tr("debugpanel.autos");
        await("the Autos", () -> node(autos) != null);
        List<String> order = call(() -> {
            javafx.scene.control.TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            return tree.getRoot().getChildren().stream()
                    .map(i -> i.getValue().name())
                    .toList();
        });
        assertEquals(autos, order.getLast(), "last in the tree: " + order);
        assertEquals(false, call(() -> node(autos).isExpanded()), "closed until asked for");
        FxTestSupport.drainFx();
        assertTrue(
                session.evaluateRequests.stream()
                        .noneMatch(a -> a.getExpression().equals("total")),
                "nothing evaluated while it is closed");

        FxTestSupport.runOnFx(() -> node(autos).setExpanded(true));
        await("its values", () -> rows(node(autos)).equals(List.of("i=val(i)", "total=val(total)")));

        // The next stop's lines: print(total) and the end before it.
        session.frames = List.of(frame(sum, 4));
        FxTestSupport.runOnFx(manager::resume);
        session.stop(session.mainThread, "step");
        await("the next stop's Autos, open as it was left", () -> {
            var n = node(autos);
            return n != null && n.isExpanded() && rows(n).equals(List.of("total=val(total)"));
        });
    }
}
