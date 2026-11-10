package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Debug History through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is (a stand-in that
 * announces its port and relays): two stops, Back shows the first one's line grey with its inline values, Forward
 * comes back to the live stop, Show History lists the stops, and after Stop they can still be read.
 * {@code DebugHistoryProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugHistoryWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        script = Files.writeString(root.resolve("shelf.rb"), "x = 1\nx += 1\nputs x\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path rdbg = fx.configDir.resolve("rdbg");
        Files.writeString(
                rdbg,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce-rdbg " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            debug.applySupport();
        });
        await("Ruby debugging available", () -> call(() -> debug.debugEffectiveFor("ruby")));
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
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private void stopAt(FakeDebugAdapter.Session session, int line, String x, String reason) throws Exception {
        session.frameLine = line + 1; // DAP lines start at 1
        session.variables.put(FakeDebugAdapter.Session.LOCALS, List.of(FakeDebugAdapter.Session.variable("x", x, 0)));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, reason);
        await(
                "stopped on line " + (line + 1),
                () -> call(manager::state) == DapManager.State.SUSPENDED
                        && !call(() -> debug.panel().callStack().isEmpty())
                        && call(() -> debug.panel().callStack().getFirst().line()) == line);
    }

    private javafx.scene.Node picker() {
        javafx.scene.Scene scene = call(() ->
                FxTestSupport.<javafx.stage.Stage>field(fx.controller, "stage").getScene());
        return call(() -> scene.getRoot().lookup(".command-palette"));
    }

    private boolean historyLine(EditorBuffer b, int line) {
        return call(
                () -> b.getFocusedArea().getParagraph(line).getParagraphStyle().contains("exec-history"));
    }

    private boolean liveLine(EditorBuffer b, int line) {
        return call(() -> {
            var style = b.getFocusedArea().getParagraph(line).getParagraphStyle();
            return style.contains("exec-line") && !style.contains("exec-history");
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> inlineValues(EditorBuffer b) {
        return call(() -> {
            Object overlay = FxTestSupport.call(b, "inlineValues", new Class<?>[0]);
            return (Map<String, String>) FxTestSupport.field(overlay, "values");
        });
    }

    @Test
    void backShowsTheFirstStopInTheEditorAndForwardTheLiveOne() throws Exception {
        run("debug.showHistory");
        assertEquals(tr("status.debug.noHistory"), status(), "nothing kept before a session");
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "shelf.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        stopAt(session, 0, "1", "breakpoint");
        await(
                "x's inline value",
                () -> "1"
                        .equals(inlineValues(b) == null ? null : inlineValues(b).get("x")));
        run("debug.stepOver");
        stopAt(session, 1, "2", "step");
        await(
                "x's new inline value",
                () -> inlineValues(b) != null && "2".equals(inlineValues(b).get("x")));

        run("debug.historyBack");
        DebugPanel panel = call(debug::panel);
        assertEquals(1, call(() -> panel.viewing().number));
        await("the first stop's line, grey", () -> historyLine(b, 0));
        assertEquals("1", inlineValues(b).get("x"), "its inline value as it was");

        run("debug.historyForward");
        assertNull(call(panel::viewing));
        await("the live line again", () -> liveLine(b, 1));
        assertFalse(historyLine(b, 1));
        await(
                "the live inline value",
                () -> inlineValues(b) != null && "2".equals(inlineValues(b).get("x")));

        run("debug.showHistory");
        await("the history list", () -> picker() != null);
        FxTestSupport.runOnFx(() -> {
            javafx.scene.Node card = picker();
            var list = (javafx.scene.control.ListView<?>) card.lookup(".list-view");
            assertEquals(2, list.getItems().size());
            list.getSelectionModel().select(1); // newest first: the first stop
            card.lookup(".text-field")
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ENTER,
                            false,
                            false,
                            false,
                            false));
        });
        FxTestSupport.drainFx();
        await("the first stop again", () -> call(() -> panel.viewing() != null && panel.viewing().number == 1));

        run("debug.stepOver"); // acting on the live program comes back to it
        await("live again", () -> call(panel::viewing) == null);
        stopAt(session, 2, "2", "step");

        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        run("debug.historyBack");
        assertEquals(3, call(() -> panel.viewing().number), "the ended session's last stop");
        await("its line, grey", () -> historyLine(b, 2));
        FxTestSupport.runOnFx(panel::leaveHistory);
        await("no line once back", () -> !historyLine(b, 2) && !liveLine(b, 2));
    }
}
