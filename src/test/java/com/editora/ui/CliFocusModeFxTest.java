package com.editora.ui;

import javafx.scene.control.ToolBar;

import com.editora.config.ConfigManager;
import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --zen} and {@code --expert} are <b>session-only</b> overrides, exactly like {@code --simple}: they
 * put the window into the focus mode for this run, but must not write it into the saved session — launching
 * once with the flag must not leave the user stuck in the mode on every later launch.
 *
 * <p>The subtle half is the tool windows: entering a focus mode closes the docked ones, and
 * {@code ToolWindowManager.close()} <em>persists</em> "nothing open". So a session-only focus mode has to put
 * those ids back at quit, or it would silently lose the user's docked tool windows for good — a worse bug
 * than the one being fixed.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliFocusModeFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private WorkspaceState state() throws Exception {
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        return config.getWorkspaceState();
    }

    /** Applies the CLI flag exactly as {@code applyStartupTargets} does. */
    private void applyCliFocusMode(boolean expert) throws Exception {
        FxTestSupport.call(fx.controller, "applyCliFocusMode", new Class<?>[] {boolean.class}, expert);
    }

    private void persistSession() throws Exception {
        FxTestSupport.invoke(fx.controller, "persistSession");
    }

    @Test
    void zenFlagAppliesTheModeWithoutSavingIt() throws Exception {
        ToolBar toolBar = FxTestSupport.field(fx.controller, "toolBar");
        assertTrue(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar visible by default");

        FxTestSupport.runOnFx(() -> {
            try {
                applyCliFocusMode(false); // --zen
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // The window IS in Zen for this session...
        assertFalse(FxTestSupport.callOnFx(toolBar::isVisible), "--zen hides the chrome for this session");
        assertTrue(
                FxTestSupport.callOnFx(
                        () -> (Boolean) FxTestSupport.call(fx.controller, "zenActive", new Class<?>[] {})),
                "zenActive() reflects the flag");
        // ...but nothing was written to the saved session, so the next flagless launch is normal.
        assertFalse(state().isZenMode(), "--zen must not persist Zen into the saved session");

        // Quitting keeps it that way.
        FxTestSupport.runOnFx(() -> {
            try {
                persistSession();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(state().isZenMode(), "still not saved after a quit");
        assertFalse(state().isExpertMode(), "and Expert was never touched");

        // Leave it, so the shared fixture isn't left in Zen for the next test.
        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(false));
    }

    @Test
    void theFlagDoesNotEatYourDockedToolWindows() throws Exception {
        ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        WorkspaceState ws = state();

        // A docked tool window, as a returning user would have.
        ToolWindow project = FxTestSupport.field(fx.controller, "projectToolWindow");
        FxTestSupport.runOnFx(() -> toolWindows.open(project));
        String docked = ws.getOpenRightToolWindow(); // the Project window docks on the right by default
        assertFalse(docked.isEmpty(), "a tool window is docked");

        // --expert closes it, and close() persists "nothing open" — the trap.
        FxTestSupport.runOnFx(() -> {
            try {
                applyCliFocusMode(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(ws.getOpenRightToolWindow().isEmpty(), "entering the focus mode closed it");

        // On quit the session-only override puts it back, so the next launch still has it.
        FxTestSupport.runOnFx(() -> {
            try {
                persistSession();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(docked, ws.getOpenRightToolWindow(), "the docked tool window survives a --expert session");
        assertFalse(ws.isExpertMode(), "--expert still isn't saved");
        assertTrue(ws.getPreExpertToolWindows().isEmpty(), "no stale snapshot of a mode that was never saved");

        FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(false));
    }

    @Test
    void anInAppToggleTakesOverFromTheFlag() throws Exception {
        // Launch with --zen, then turn Zen ON from inside the app (via the palette/keybinding). That's an
        // explicit choice, so it must stick — mirroring how toggleSimpleMode() drops cliSimpleOverride.
        FxTestSupport.runOnFx(() -> {
            try {
                applyCliFocusMode(false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(state().isZenMode(), "the flag alone doesn't save");

        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(false)); // exit: the override is dropped
        assertFalse(
                FxTestSupport.callOnFx(
                        () -> (Boolean) FxTestSupport.call(fx.controller, "zenActive", new Class<?>[] {})),
                "exiting the flag's Zen actually leaves Zen");

        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(true)); // now an explicit in-app enter
        assertTrue(state().isZenMode(), "an explicit toggle IS saved");

        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(false));
        assertFalse(state().isZenMode());
    }
}
