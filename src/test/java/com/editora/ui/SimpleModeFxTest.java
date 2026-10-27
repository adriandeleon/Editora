package com.editora.ui;

import java.util.List;

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
            FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
        });
        assertFalse(FxTestSupport.callOnFx(findInFiles::isVisible), "Find-in-Files hidden in Simple mode");
        assertFalse(FxTestSupport.callOnFx(splitVertical::isVisible), "split button hidden in Simple mode");
        assertTrue(FxTestSupport.callOnFx(open::isVisible), "Open button kept in Simple mode");

        FxTestSupport.runOnFx(() -> {
            settings.setSimpleMode(false);
            FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
        });
        assertTrue(FxTestSupport.callOnFx(findInFiles::isVisible), "Find-in-Files restored after Simple mode");
        assertTrue(FxTestSupport.callOnFx(splitVertical::isVisible), "split button restored after Simple mode");
    }

    /**
     * The toolbar button reads as "on" while the mode is — the same {@code :open} pseudo-class the find,
     * palette and split toggles use. Asserted through the pseudo-class rather than a rendered colour, since
     * that is the contract {@code app.css} styles against.
     */
    @Test
    void theToolbarButtonReadsAsSelectedWhileSimpleModeIsOn() throws Exception {
        Button simpleButton = FxTestSupport.field(fx.controller, "simpleModeButton");
        javafx.css.PseudoClass open = javafx.css.PseudoClass.getPseudoClass("open");

        assertFalse(
                FxTestSupport.callOnFx(() -> simpleButton.getPseudoClassStates().contains(open)),
                "not selected while Simple mode is off");

        FxTestSupport.runOnFx(() -> {
            settings.setSimpleMode(true);
            FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
        });
        assertTrue(
                FxTestSupport.callOnFx(() -> simpleButton.getPseudoClassStates().contains(open)),
                "selected while Simple mode is on");

        FxTestSupport.runOnFx(() -> {
            settings.setSimpleMode(false);
            FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
        });
        assertFalse(
                FxTestSupport.callOnFx(() -> simpleButton.getPseudoClassStates().contains(open)),
                "and clears again when it is switched off");
    }

    /**
     * The toggle is pinned at the right end of the bar, immediately left of Settings, in the fixed tail rather
     * than the customizable cluster: it is the way back out of a mode that hides much of that cluster, and the
     * cluster is what overflows into the chevron on a narrow window. The catalog must not offer it as well —
     * it is one node, so an entry that put it back in the cluster would pull it out of the tail on the next
     * rebuild. And the pair sits clear of the project switcher's Open-Folder icon, not hard against it.
     */
    @Test
    void theToggleIsPinnedBesideSettingsClearOfTheProjectControls() throws Exception {
        Button simpleButton = FxTestSupport.field(fx.controller, "simpleModeButton");
        Button settingsButton = FxTestSupport.field(fx.controller, "settingsButton");
        Button openFolder = FxTestSupport.field(fx.controller, "openFolderButton");
        javafx.scene.layout.HBox tail = FxTestSupport.field(fx.controller, "toolbarTail");
        javafx.scene.control.ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");

        assertFalse(com.editora.toolbar.ToolbarCatalog.isKnownId("view.toggleSimpleMode"));
        assertFalse(FxTestSupport.callOnFx(() -> bar.getItems().contains(simpleButton)), "not in the cluster");
        List<javafx.scene.Node> tailNodes = FxTestSupport.callOnFx(() -> List.copyOf(tail.getChildren()));
        assertEquals(
                List.of(simpleButton, settingsButton),
                tailNodes.subList(tailNodes.size() - 2, tailNodes.size()),
                "the tail should end with the Simple-mode toggle then Settings");

        double gap = FxTestSupport.callOnFx(() -> {
            javafx.scene.Parent root = tail.getScene().getRoot();
            root.resize(1600, 800);
            root.applyCss();
            root.layout();
            return simpleButton.localToScene(simpleButton.getBoundsInLocal()).getMinX()
                    - openFolder.localToScene(openFolder.getBoundsInLocal()).getMaxX();
        });
        assertTrue(gap >= 40, "the toggle is only " + Math.round(gap) + "px right of the Open-Folder icon");
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
                FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
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
                                            .startsWith(com.editora.editor.MenuTitles.of(
                                                    com.editora.i18n.Messages.tr("command.view.toggleSimpleMode"))))),
                    "the Simple-mode menu offers the way back out");
        } finally {
            FxTestSupport.runOnFx(() -> {
                settings.setSimpleMode(false);
                FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
            });
        }
        assertEquals(fullMenus, FxTestSupport.callOnFx(() -> bar.getMenus().size()), "the full menu is restored");
    }
}
