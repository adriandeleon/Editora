package com.editora.ui;

import javafx.beans.InvalidationListener;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #545: a text zoom (Ctrl+wheel / {@code C-=}) must NOT re-run the full settings cascade. The old
 * {@code textZoom} called {@code applyViewSettingsToAllBuffers}, which re-swaps the editor-theme stylesheet on the
 * scene (a full-scene CSS reapply) and runs ~20 {@code applySupport()} service calls — none of which change on a
 * font zoom. This asserts that a zoom advances the zoom level but leaves the scene stylesheet list untouched
 * (the old path fired remove+add on it, i.e. ≥1 mutation).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextZoomLightweightFxTest {

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
    void zoomAdvancesTheLevelWithoutReswappingTheEditorThemeStylesheet() throws Exception {
        int[] stylesheetMutations = {0};
        double newZoom = FxTestSupport.callOnFx(() -> {
            ConfigManager config = FxTestSupport.field(fx.controller, "config");
            Settings settings = config.getSettings();

            // Force a non-default editor theme (its override stylesheet is non-null), then apply it so
            // currentEditorThemeCss is established and the sheet is present on the scene.
            settings.setEditorTheme("Dracula");
            FxTestSupport.call(fx.controller, "applyEditorTheme", new Class[] {String.class}, "Dracula");

            Stage stage = FxTestSupport.field(fx.controller, "stage");
            InvalidationListener counter = obs -> stylesheetMutations[0]++;
            stage.getScene().getStylesheets().addListener(counter);

            double before = settings.getFontZoom();
            fx.controller.textZoom(1); // one wheel notch in
            double after = settings.getFontZoom();

            stage.getScene().getStylesheets().removeListener(counter);
            // Sanity: the zoom actually advanced (so the method didn't early-return).
            assertEquals(Math.round((before + 0.1) * 10.0) / 10.0, after, 1e-9, "zoom advanced by one notch");
            return after;
        });

        // Editor buffers may exist or not, but the SCENE STYLESHEET must be untouched by a font zoom.
        assertEquals(0, stylesheetMutations[0], "a text zoom must not re-swap the editor-theme stylesheet");
        assertEquals(1.1, newZoom, 1e-9, "zoom is now 110%");
    }
}
