package com.editora.ui;

import javafx.scene.control.Button;

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
 * End-to-end coverage of Simple UI mode through {@link MainController}: entering it hides the curated
 * toolbar buttons (Find in Files, splits, …) while deliberately KEEPING the Open button, and leaving it
 * restores them.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleModeFxTest {

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
    void simpleModeHidesCuratedButtonsButKeepsOpen() throws Exception {
        Button findInFiles = FxTestSupport.field(fx.controller, "findInFilesButton");
        Button splitVertical = FxTestSupport.field(fx.controller, "splitVerticalButton");
        Button open = FxTestSupport.field(fx.controller, "openButton");

        assertTrue(FxTestSupport.callOnFx(findInFiles::isVisible), "Find-in-Files visible by default");

        FxTestSupport.runOnFx(() -> {
            settings.setSimpleMode(true);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
        assertFalse(FxTestSupport.callOnFx(findInFiles::isVisible), "Find-in-Files hidden in Simple mode");
        assertFalse(FxTestSupport.callOnFx(splitVertical::isVisible), "split button hidden in Simple mode");
        assertTrue(FxTestSupport.callOnFx(open::isVisible), "Open button kept in Simple mode");

        FxTestSupport.runOnFx(() -> {
            settings.setSimpleMode(false);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
        assertTrue(FxTestSupport.callOnFx(findInFiles::isVisible), "Find-in-Files restored after Simple mode");
        assertTrue(FxTestSupport.callOnFx(splitVertical::isVisible), "split button restored after Simple mode");
    }
}
