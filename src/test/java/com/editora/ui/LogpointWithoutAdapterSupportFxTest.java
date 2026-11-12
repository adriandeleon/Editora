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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Logpoints with an adapter that does not log them (netcoredbg advertises no {@code supportsLogPoints} and stops on
 * them as on plain breakpoints), against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: the window logs the
 * message with its values and lets the program go on, without taking the editor to the line or keeping the stop in
 * Debug History. An adapter that logs them itself is left to do so.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogpointWithoutAdapterSupportFxTest {

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

    private String console() {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                .getText());
    }

    private static long continues(FakeDebugAdapter.Session session) {
        return session.requests.stream().filter("continue"::equals).count();
    }

    /** Starts a session and waits for it to run, with the frame in shelf.rb. */
    private FakeDebugAdapter.Session start() throws Exception {
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        session.frameLine = 2; // DAP lines start at 1: the logpoint's line
        session.variables.put(FakeDebugAdapter.Session.LOCALS, List.of(FakeDebugAdapter.Session.variable("x", "2", 0)));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        return session;
    }

    private void stopSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
    }

    @Test
    void aLogpointTheAdapterStopsOnIsLoggedByTheWindowAndTheProgramGoesOn() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "shelf.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        var mgr = b.getBreakpointManager();
        FxTestSupport.runOnFx(() -> {
            mgr.toggle(1);
            mgr.setLogMessage(1, "x is {x}, then {bad x}");
        });
        assertTrue(call(() -> mgr.get(1).isLogpoint()));

        // The adapter (no supportsLogPoints) stops on it: the message is logged and the program goes on.
        adapter.logPoints = false;
        FakeDebugAdapter.Session session = start();
        DebugPanel panel = call(debug::panel);
        session.stop(1, "breakpoint");
        await("logged and continued", () -> continues(session) == 1);
        await("the message in the console", () -> console().contains("x is val(x), then "));
        assertTrue(
                console().contains("Cannot evaluate: bad x"), "a failed value logs the debugger's error: " + console());
        assertEquals(0, call(() -> panel.history().size()), "the stop is not kept in Debug History");
        assertFalse(
                call(() ->
                        b.getFocusedArea().getParagraph(1).getParagraphStyle().contains("exec-line")),
                "the editor is not taken to the logpoint");

        // A step that ends on the line is a stop like any other.
        await("running again", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "step");
        await(
                "stopped by the step",
                () -> call(manager::state) == DapManager.State.SUSPENDED
                        && call(() -> !debug.panel().callStack().isEmpty()));
        assertEquals(1, continues(session));
        stopSession();

        // An adapter that logs logpoints itself is left to: a stop there is the adapter's own, and stays.
        adapter.logPoints = true;
        FakeDebugAdapter.Session own = start();
        own.stop(1, "breakpoint");
        await(
                "stopped",
                () -> call(manager::state) == DapManager.State.SUSPENDED
                        && call(() -> !debug.panel().callStack().isEmpty()));
        assertEquals(0, continues(own));
        stopSession();
    }
}
