package com.editora.ui;

import java.lang.reflect.Proxy;

import javafx.scene.control.ProgressIndicator;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Debug panel's toolbar spinner: it turns while something is under way — a before-launch build, a session
 * starting, the program running — and not while the program is paused (it waits on the user) or no session
 * lives.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugPanelSpinnerFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static DebugPanel.Actions noop() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    @Test
    void spinsWhilePreparingStartingAndRunningButNotWhilePaused() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noop());
            ProgressIndicator busy = FxTestSupport.field(panel, "busy");
            assertFalse(busy.isVisible(), "no session, no spinner");

            panel.setPreparing(true);
            assertTrue(busy.isVisible() && busy.isManaged(), "spins during the before-launch build");
            panel.setPreparing(false);
            assertFalse(busy.isVisible(), "the build ended without a session");

            panel.setState(DapManager.State.STARTING);
            assertTrue(busy.isVisible(), "spins while the session starts");
            panel.setState(DapManager.State.RUNNING);
            assertTrue(busy.isVisible(), "spins while the program runs");
            panel.setState(DapManager.State.SUSPENDED);
            assertFalse(busy.isVisible(), "paused at a breakpoint: waiting on the user, not working");
            panel.setState(DapManager.State.RUNNING);
            assertTrue(busy.isVisible(), "Continue spins again");
            panel.setState(DapManager.State.TERMINATED);
            assertFalse(busy.isVisible(), "the program ended");
            panel.setState(DapManager.State.INACTIVE);
            assertFalse(busy.isVisible(), "no session");
        });
    }
}
