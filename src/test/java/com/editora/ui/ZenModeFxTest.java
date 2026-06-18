package com.editora.ui;

import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of Zen mode through the real {@link MainController} (the recently-fixed
 * cross-window leak had none). Asserts that entering Zen hides the toolbar/status bar/tab header and
 * leaving it restores them, and that Zen is per-window state (this window's {@code WorkspaceState}),
 * never a shared {@code Settings} mutation.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZenModeFxTest {

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

    @Test
    void zenHidesChromeAndExitRestoresIt() throws Exception {
        ToolBar toolBar = FxTestSupport.field(fx.controller, "toolBar");
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");

        // Default window: chrome shown, tab header present.
        assertTrue(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar visible by default");
        assertTrue(FxTestSupport.callOnFx(statusBar::isVisible), "status bar visible by default");
        assertFalse(
                FxTestSupport.callOnFx(() -> tabPane.getStyleClass().contains("no-tab-header")),
                "tab header present by default");

        // Enter Zen → all chrome hidden.
        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(true));
        assertFalse(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar hidden in Zen");
        assertFalse(FxTestSupport.callOnFx(toolBar::isManaged), "toolbar unmanaged in Zen");
        assertFalse(FxTestSupport.callOnFx(statusBar::isVisible), "status bar hidden in Zen");
        assertTrue(
                FxTestSupport.callOnFx(() -> tabPane.getStyleClass().contains("no-tab-header")),
                "tab header collapsed in Zen");
        // Zen lives in this window's WorkspaceState (per-window), not the shared Settings.
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        assertTrue(
                FxTestSupport.callOnFx(() -> config.getWorkspaceState().isZenMode()), "Zen flag set on window state");

        // Leave Zen → chrome restored.
        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(false));
        assertTrue(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar restored after Zen");
        assertTrue(FxTestSupport.callOnFx(statusBar::isVisible), "status bar restored after Zen");
        assertFalse(
                FxTestSupport.callOnFx(() -> tabPane.getStyleClass().contains("no-tab-header")),
                "tab header restored after Zen");
        assertFalse(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isZenMode()), "Zen flag cleared");
    }
}
