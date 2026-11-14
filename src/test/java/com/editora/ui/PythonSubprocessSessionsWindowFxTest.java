package com.editora.ui;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.DapTestSettings;
import com.editora.dap.FakeDebugAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Python subprocess's session through the window, against a {@link FakeDebugAdapter} standing in for the port
 * debugpy names for it: the parent's manager is told of the child ({@code onChildSession}), and a session of its own
 * attaches with debugpy's configuration, named as debugpy names it; Restart is refused there; stopping the parent stops
 * it, and its tab goes once it has ended. {@code SeveralSessionsProbeFxTest} drives real debugpy subprocesses.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class PythonSubprocessSessionsWindowFxTest {

    private FxWindowFixture fx;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        DapManager manager = FxTestSupport.field(fx.controller, "dapManager");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            DapTestSettings.set(manager, "enabled", true);
        });
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

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    @Test
    void aSubprocessGetsASessionOfItsOwnThatGoesWithItsParent() throws Exception {
        DebugSession parent = call(() -> debug.sessions().focused());
        Map<String, Object> configuration = Map.of(
                "name", "Subprocess 4242",
                "type", "debugpy",
                "request", "attach",
                "subProcessId", 4242,
                "connect", Map.of("host", "127.0.0.1", "port", adapter.port()));
        DapManager.Listener events = FxTestSupport.field(parent.dap, "listener");
        FxTestSupport.runOnFx(() -> events.onChildSession(configuration));

        FakeDebugAdapter.Session child = adapter.awaitSession();
        child.awaitRequest("attach");
        assertEquals(4242, ((Number) child.launchArgs.get("subProcessId")).intValue(), "debugpy's configuration");
        assertEquals(2, call(() -> debug.sessions().size()));
        DebugSession session = call(() -> debug.sessions().all().getLast());
        assertSame(parent, session.parent);
        assertEquals("Subprocess 4242", call(() -> session.sessionLabel));
        assertSame(parent, call(() -> debug.sessions().focused()), "not shown until it stops");
        assertEquals(
                List.of(tr("debug.sessions.untitled", 1), "Subprocess 4242"),
                call(() -> debug.view().tabs().stream()
                        .map(DebugSessionsView.Tab::title)
                        .toList()));
        await("attached", () -> call(() -> session.dap.state() == DapManager.State.RUNNING));

        FxTestSupport.runOnFx(() -> debug.focusSession(session));
        FxTestSupport.runOnFx(debug::restart);
        assertEquals(tr("status.debug.cannotRestartSubprocess"), status(), "its program starts it again, not Restart");
        assertFalse(call(debug::restartAvailable));

        FxTestSupport.runOnFx(() -> debug.focusSession(parent));
        FxTestSupport.runOnFx(debug::stop); // the parent: its subprocesses go with it
        assertTrue(child.disconnected.await(10, java.util.concurrent.TimeUnit.SECONDS));
        await("its tab gone once it ended", () -> call(() -> debug.sessions().size()) == 1);
        assertSame(parent, call(() -> debug.sessions().focused()));
    }
}
