package com.editora.ui;

import javafx.scene.control.Button;
import javafx.scene.control.MenuBar;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of Simple UI mode through {@link MainController}: entering it hides the curated
 * toolbar buttons (Find in Files, splits, …) while deliberately KEEPING the Open button, and leaving it
 * restores them — and it keeps the menu bar, swapping in the reduced table rather than hiding the map.
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

    /**
     * Simple mode simplifies the menu instead of removing it. The menu bar stays visible and switches to the
     * reduced table — and must still offer the way back out, or the mode is a one-way door for anyone who
     * reaches it from the menu.
     */
    @Test
    void simpleModeKeepsTheMenuBarButReducesIt() throws Exception {
        MainMenuBar menu = FxTestSupport.field(fx.controller, "menuBar");
        MenuBar bar = FxTestSupport.callOnFx(menu::node);
        int fullMenus = FxTestSupport.callOnFx(() -> bar.getMenus().size());

        try {
            FxTestSupport.runOnFx(() -> {
                settings.setSimpleMode(true);
                FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
            });
            assertTrue(FxTestSupport.callOnFx(bar::isVisible), "the menu bar stays visible in Simple mode");
            int simpleMenus = FxTestSupport.callOnFx(() -> bar.getMenus().size());
            assertTrue(
                    simpleMenus < fullMenus,
                    "Simple mode shows fewer menus (" + simpleMenus + " of " + fullMenus + ")");
            assertEquals(
                    MenuBarModel.menus(true).size(), simpleMenus, "the bar was rebuilt from the Simple-mode table");
            assertTrue(
                    FxTestSupport.callOnFx(() -> bar.getMenus().stream()
                            .flatMap(m -> m.getItems().stream())
                            .anyMatch(i -> !MainMenuBar.displayTextOf(i).isBlank()
                                    && MainMenuBar.displayTextOf(i)
                                            .startsWith(
                                                    com.editora.i18n.Messages.tr("command.view.toggleSimpleMode")))),
                    "the Simple-mode menu offers the way back out");
        } finally {
            FxTestSupport.runOnFx(() -> {
                settings.setSimpleMode(false);
                FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
            });
        }
        assertEquals(fullMenus, FxTestSupport.callOnFx(() -> bar.getMenus().size()), "the full menu is restored");
    }
}
