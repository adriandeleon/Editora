package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Recording breakpoints through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: a hit is
 * kept in Debug History with the frame's values and the watches, the program is continued, and the editor is not taken
 * to it; with Debug History off, or past the session's limit, the breakpoint stops as any other.
 * {@code DebugHistoryProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordingBreakpointsWindowFxTest {

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

    private String console() {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                .getText());
    }

    private long continues(FakeDebugAdapter.Session session) {
        return session.requests.stream().filter("continue"::equals).count();
    }

    /** The program stops on 0-based {@code line} with {@code x} = {@code x}. */
    private void hit(FakeDebugAdapter.Session session, int line, String x) throws Exception {
        session.frameLine = line + 1; // DAP lines start at 1
        session.variables.put(FakeDebugAdapter.Session.LOCALS, List.of(FakeDebugAdapter.Session.variable("x", x, 0)));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "breakpoint");
    }

    @Test
    void aRecordingBreakpointKeepsTheStopAndGoesOn() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "shelf.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(1, 0));
        run("debug.toggleRecordingBreakpoint");
        var mgr = b.getBreakpointManager();
        assertTrue(call(() -> mgr.get(1).recording()));
        assertEquals("recording", call(() -> mgr.styleClasses(1)));
        assertEquals(tr("status.debug.recordingAdded", 2), status());
        FxTestSupport.runOnFx(() -> debug.panel().setWatches(List.of("x + 1")));

        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        DebugPanel panel = call(debug::panel);

        hit(session, 1, "2");
        await("recorded and continued", () -> continues(session) == 1);
        DebugHistory.Stop first = call(() -> panel.history().last());
        assertEquals(1, first.number);
        assertEquals("2", first.value(0, List.of("Locals", "x")), "the frame's values, kept");
        assertEquals("val(x + 1)", first.value(0, List.of(tr("debugpanel.watches"), "x + 1")), "and the watches");
        assertEquals("2", first.inline(0).values().get("x"));
        await(
                "said in the console",
                () -> console().contains(tr("debug.console.recorded", 1, "").strip()));
        assertFalse(
                call(() ->
                        b.getFocusedArea().getParagraph(1).getParagraphStyle().contains("exec-line")),
                "the editor is not taken to a recorded stop");

        hit(session, 1, "3");
        await("the second hit, recorded", () -> continues(session) == 2);
        assertEquals(2, call(() -> panel.history().size()));
        FxTestSupport.runOnFx(panel::historyBack);
        assertEquals("3", call(() -> panel.viewing().value(0, List.of("Locals", "x"))));
        FxTestSupport.runOnFx(panel::leaveHistory);

        // Past the session's limit it stops, and says why.
        FxTestSupport.runOnFx(() -> debug.s().recordedStops = DebugCoordinator.MAX_RECORDED_STOPS);
        hit(session, 1, "4");
        await(
                "stopped",
                () -> call(manager::state) == DapManager.State.SUSPENDED
                        && call(() -> !debug.panel().callStack().isEmpty()));
        await(
                "the limit said",
                () -> tr("status.debug.recordingLimit", DebugCoordinator.MAX_RECORDED_STOPS)
                        .equals(status()));
        assertEquals(2, continues(session));
        FxTestSupport.runOnFx(manager::resume);

        // With Debug History off there is nowhere to record: it stops.
        FxTestSupport.runOnFx(() -> {
            debug.s().recordedStops = 0;
            panel.history().setCapacity(0);
        });
        hit(session, 1, "5");
        await(
                "the history being off said",
                () -> tr("status.debug.recordingHistoryOff").equals(status()));
        assertEquals(call(manager::state), DapManager.State.SUSPENDED);
        FxTestSupport.runOnFx(() -> panel.history().setCapacity(DebugHistory.DEFAULT_CAPACITY));

        // A breakpoint that is no longer recording stops as any other.
        FxTestSupport.runOnFx(manager::resume);
        run("debug.toggleRecordingBreakpoint"); // the caret is still on it: removed
        assertEquals(null, call(() -> mgr.get(1)));
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
    }
}
