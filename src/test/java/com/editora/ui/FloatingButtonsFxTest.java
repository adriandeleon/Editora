package com.editora.ui;

import javafx.scene.control.Button;
import javafx.scene.layout.AnchorPane;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the floating controls {@code installZenOverlay} creates: the
 * <b>toolbar-restore</b> "Tool" button (shown only when the toolbar is hidden and not in Zen, so a user
 * who hid the toolbar can always bring it back) and the <b>Zen-exit</b> "Z" button (shown only in Zen).
 * They never coexist.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FloatingButtonsFxTest {

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
    void restoreAndZenExitButtonsTrackChromeState() throws Exception {
        Button restore = FxTestSupport.field(fx.controller, "toolbarRestoreButton");
        Button zenExit = FxTestSupport.field(fx.controller, "zenExitButton");

        // Default: toolbar shown, not Zen → neither floating button is shown.
        applyChrome(() -> settings.setShowToolbar(true));
        assertFalse(FxTestSupport.callOnFx(restore::isVisible), "restore hidden when toolbar shown");
        assertFalse(FxTestSupport.callOnFx(zenExit::isVisible), "Zen-exit hidden when not in Zen");

        // Hide the toolbar (not Zen) → the restore button appears; Zen-exit stays hidden.
        applyChrome(() -> settings.setShowToolbar(false));
        assertTrue(FxTestSupport.callOnFx(restore::isVisible), "restore shown when toolbar hidden");
        assertFalse(FxTestSupport.callOnFx(zenExit::isVisible), "Zen-exit still hidden (not Zen)");

        // Enter Zen → restore button must NOT show in Zen; the Zen-exit "Z" does.
        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(true));
        assertFalse(FxTestSupport.callOnFx(restore::isVisible), "restore hidden in Zen");
        assertTrue(FxTestSupport.callOnFx(zenExit::isVisible), "Zen-exit shown in Zen");

        // Leave Zen (toolbar still hidden) → restore returns, Zen-exit gone.
        FxTestSupport.runOnFx(() -> fx.controller.setZenMode(false));
        assertTrue(FxTestSupport.callOnFx(restore::isVisible), "restore returns after Zen");
        assertFalse(FxTestSupport.callOnFx(zenExit::isVisible), "Zen-exit gone after Zen");

        // Restore the default so the shared fixture is left clean.
        applyChrome(() -> settings.setShowToolbar(true));
    }

    @Test
    void expertExitFloatsInsideTheCodePaneAndClearsTheMinimap() throws Exception {
        Button expertExit = FxTestSupport.field(fx.controller, "expertExitButton");
        EditorBuffer buffer = new EditorBuffer();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            fx.controller.setExpertMode(true);
        });
        try {
            FxTestSupport.runOnFx(() -> {
                AnchorPane root = FxTestSupport.field(buffer, "root");
                assertTrue(root.getChildren().contains(expertExit), "E is owned by the active code pane");
                assertTrue(
                        AnchorPane.getRightAnchor(expertExit) > 14,
                        "E clears the editor scrollbar and the minimap rather than floating over them");
            });
        } finally {
            FxTestSupport.runOnFx(() -> fx.controller.setExpertMode(false));
        }
    }

    private void applyChrome(Runnable mutateSettings) throws Exception {
        FxTestSupport.runOnFx(() -> {
            mutateSettings.run();
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
    }
}
