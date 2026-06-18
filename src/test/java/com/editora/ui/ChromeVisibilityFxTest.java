package com.editora.ui;

import javafx.scene.control.ToolBar;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the saved chrome-visibility prefs flowing through {@link MainController}'s
 * {@code applyChromeVisibility} into real node visibility — complements the pure {@code ChromeTest}
 * (which tests the decision in isolation) by proving the controller actually wires the decision to the
 * toolbar/status-bar nodes.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChromeVisibilityFxTest {

    private FxWindowFixture fx;
    private Settings settings;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        settings = config.getSettings();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void toolbarPrefTogglesToolbarVisibility() throws Exception {
        ToolBar toolBar = FxTestSupport.field(fx.controller, "toolBar");

        FxTestSupport.runOnFx(() -> {
            settings.setShowToolbar(false);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
        assertFalse(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar hidden when showToolbar=false");
        assertFalse(FxTestSupport.callOnFx(toolBar::isManaged), "toolbar unmanaged when hidden");

        FxTestSupport.runOnFx(() -> {
            settings.setShowToolbar(true);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
        assertTrue(FxTestSupport.callOnFx(toolBar::isVisible), "toolbar shown when showToolbar=true");
    }

    @Test
    void statusBarPrefTogglesStatusBarVisibility() throws Exception {
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");

        FxTestSupport.runOnFx(() -> {
            settings.setShowStatusBar(false);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
        assertFalse(FxTestSupport.callOnFx(statusBar::isVisible), "status bar hidden when showStatusBar=false");

        FxTestSupport.runOnFx(() -> {
            settings.setShowStatusBar(true);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
        assertTrue(FxTestSupport.callOnFx(statusBar::isVisible), "status bar shown when showStatusBar=true");
    }
}
