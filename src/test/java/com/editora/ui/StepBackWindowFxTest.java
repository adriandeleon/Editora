package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DebugLanguages;
import com.editora.dap.FakeDebugAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Step Back, Reverse Continue and Record Execution through the window, against a {@link FakeDebugAdapter}: reached as
 * Ruby's rdbg, an adapter that steps back (from {@code initialize}, or announced later in a {@code capabilities}
 * event, as Delve on rr does) is sent {@code stepBack} and {@code reverseContinue}, and one that cannot shows no such
 * buttons and says why; reached as GDB, Record Execution sends {@code record full} and the backward controls GDB's
 * own commands through its console. {@code StepBackProbeFxTest} drives the real GDB.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepBackWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;
    private Path cFile;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("back")).toRealPath();
        script = Files.writeString(root.resolve("main.rb"), "x = 1\nx += 1\nputs x\n");
        cFile = Files.writeString(root.resolve("loop.c"), "int main(void) {\n    int t = 0;\n    return t;\n}\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String relay = "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                + "' com.editora.dap.RelayAdapterMain ";
        Path rdbg = Files.writeString(fx.configDir.resolve("rdbg"), relay + "announce-rdbg " + adapter.port() + "\n");
        Path gdb = Files.writeString(fx.configDir.resolve("gdb"), relay + "stdio " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true) && gdb.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            fx.shared.getSettings().setCppDebugger("gdb");
            fx.shared.getSettings().setGdbPath(gdb.toString());
            debug.applySupport();
        });
        await(
                "Ruby and GDB debugging available",
                () -> call(() -> debug.debugEffectiveFor("ruby"))
                        && call(() -> manager.isExternalLocated(DebugLanguages.GDB)));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        adapter.stepBack = false;
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
            return FxTestSupport.<Label>field(bar, "echo").getText();
        });
    }

    private Button button(String name) {
        return call(() -> FxTestSupport.field(debug.panel(), name));
    }

    /** Starts the Ruby script on the stand-in rdbg and stops it on line 2. */
    private FakeDebugAdapter.Session stoppedInRuby(java.util.function.Consumer<FakeDebugAdapter.Session> before)
            throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        session.frameLine = 2;
        before.accept(session);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint");
        await("the stop", () -> call(manager::state) == DapManager.State.SUSPENDED);
        return session;
    }

    /** Stops the session again after a backward step: the adapter's {@code stopped}. */
    private void stoppedAgain(FakeDebugAdapter.Session session, String request) throws Exception {
        session.awaitRequest(request);
        // The step is under way once its answer is in: the program runs until the adapter says it stopped.
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "step");
        await("stopped again", () -> call(manager::state) == DapManager.State.SUSPENDED);
    }

    @Test
    void anAdapterThatStepsBackIsSentStepBackAndReverseContinue() throws Exception {
        adapter.stepBack = true;
        FakeDebugAdapter.Session session = stoppedInRuby(s -> {});
        assertTrue(call(manager::canRunBackward));
        assertTrue(
                call(() -> button("stepBack").isVisible() && !button("stepBack").isDisabled()));
        assertTrue(call(() -> button("reverseContinue").isVisible()));
        assertFalse(
                call(() -> FxTestSupport.<ToggleButton>field(debug.panel(), "record")
                        .isVisible()),
                "not GDB");
        assertTrue(call(() -> Chrome.contextEnabled("debug.stepBack", fx.controller.paletteContext())));

        run("debug.stepBack");
        stoppedAgain(session, "stepBack");
        run("debug.reverseContinue");
        stoppedAgain(session, "reverseContinue");
        assertFalse(session.requests.contains("evaluate"), "the adapter's own requests, no console commands");
    }

    @Test
    void stepBackAnnouncedAfterTheLaunchIsHeard() throws Exception {
        FakeDebugAdapter.Session session = stoppedInRuby(FakeDebugAdapter.Session::announceStepBack);
        assertTrue(call(manager::canRunBackward), "Delve says so in a capabilities event once on rr");
        assertTrue(
                call(() -> button("stepBack").isVisible() && !button("stepBack").isDisabled()));
        run("debug.stepBack");
        stoppedAgain(session, "stepBack");
    }

    @Test
    void anAdapterThatCannotShowsNoBackwardControlsAndSaysWhy() throws Exception {
        FakeDebugAdapter.Session session = stoppedInRuby(s -> {});
        assertFalse(call(manager::canRunBackward));
        assertFalse(call(() -> button("stepBack").isVisible()));
        assertFalse(call(() -> Chrome.contextEnabled("debug.stepBack", fx.controller.paletteContext())));
        assertFalse(call(() -> Chrome.contextEnabled("debug.toggleRecording", fx.controller.paletteContext())));
        run("debug.stepBack");
        assertEquals(tr("status.debug.cannotRunBackward"), status());
        FxTestSupport.drainFx();
        assertFalse(session.requests.contains("stepBack"));
        assertEquals(DapManager.State.SUSPENDED, call(manager::state));
    }

    @Test
    void gdbRecordsThenGoesBackThroughItsConsole() throws Exception {
        FxTestSupport.runOnFx(() -> manager.startExternal(
                cFile, DebugLanguages.GDB, Map.of("type", "gdb", "request", "launch", "program", cFile.toString())));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = cFile.toString();
        session.frameLine = 2;
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint");
        await("the stop", () -> call(manager::state) == DapManager.State.SUSPENDED);
        assertTrue(call(manager::isGdbSession));

        ToggleButton record = call(() -> FxTestSupport.field(debug.panel(), "record"));
        assertTrue(call(record::isVisible), "Record Execution in a GDB session");
        assertTrue(
                call(() -> button("stepBack").isVisible() && button("stepBack").isDisabled()), "not yet");
        run("debug.stepBack");
        assertEquals(tr("status.debug.recordFirst"), status());

        run("debug.toggleRecording");
        await("recording", () -> call(manager::isRecording));
        assertEquals("record full", session.evaluateRequests.getLast().getExpression());
        assertTrue(session.evaluateRequests.getLast().getFrameId() == null, "a console command, in no frame");
        assertEquals(tr("status.debug.recordingOn"), status());
        assertTrue(call(record::isSelected));
        assertTrue(call(() -> !button("stepBack").isDisabled()));
        assertTrue(call(() ->
                FxTestSupport.<Label>field(debug.panel(), "status").getText().contains(tr("debugpanel.recording"))));

        run("debug.stepBack");
        await(
                "reverse-next sent",
                () -> session.evaluateRequests.stream().anyMatch(a -> "reverse-next".equals(a.getExpression())));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "step");
        await("stopped again", () -> call(manager::state) == DapManager.State.SUSPENDED);
        run("debug.reverseContinue");
        await(
                "reverse-continue sent",
                () -> session.evaluateRequests.stream().anyMatch(a -> "reverse-continue".equals(a.getExpression())));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "step");
        await("stopped again", () -> call(manager::state) == DapManager.State.SUSPENDED);
        assertFalse(session.requests.contains("stepBack"), "GDB's DAP mode has no stepBack");

        run("debug.toggleRecording");
        await("not recording", () -> !call(manager::isRecording));
        assertEquals("record stop", session.evaluateRequests.getLast().getExpression());
    }
}
