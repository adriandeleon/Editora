package com.editora.ui;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the menu bar (#763): that it is actually in the window, that its items are wired to
 * real commands, that they carry the live keybinding, and that it obeys the chrome toggles.
 *
 * <p>{@link MenuBarModelTest} already proves the table names commands that exist. What it cannot prove is the
 * wiring — a menu whose items are all present but inert would pass every check there.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuBarFxTest {

    private FxWindowFixture fx;
    private MainMenuBar menuBar;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        menuBar = FxTestSupport.field(fx.controller, "menuBar");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private MenuBar bar() throws Exception {
        return FxTestSupport.callOnFx(() -> menuBar.node());
    }

    @Test
    void theMenuBarIsBuiltAndInTheWindow() throws Exception {
        assertNotNull(menuBar, "the controller built a menu bar");
        MenuBar bar = bar();
        assertEquals(MenuBarModel.menus().size(), bar.getMenus().size(), "one menu per entry in the model");
        assertTrue(FxTestSupport.callOnFx(() -> bar.getScene() != null), "the menu bar is attached to the window");
    }

    /** Every non-separator item must be hooked to something; an inert menu is the failure this catches. */
    @Test
    void everyItemIsWiredToAnAction() throws Exception {
        MenuBar bar = bar();
        int wired = 0;
        for (Menu menu : bar.getMenus()) {
            for (MenuItem item : menu.getItems()) {
                if (item instanceof SeparatorMenuItem) {
                    continue;
                }
                String text = MainMenuBar.displayTextOf(item);
                assertNotNull(item.getOnAction(), "menu item has no action: " + text);
                assertFalse(text.isBlank(), "menu item has no label");
                wired++;
            }
        }
        long modelled = MenuBarModel.menus().stream()
                .flatMap(m -> m.entries().stream())
                .filter(e -> !MenuBarModel.SEPARATOR.equals(e))
                .count();
        assertEquals(modelled, wired, "every modelled command became an item");
    }

    /**
     * Items show their keybinding, which is the menu's main teaching value. Checked against a command that is
     * bound in every bundled keymap so the assertion does not depend on which one is active.
     */
    @Test
    void itemsCarryTheLiveKeybinding() throws Exception {
        MenuBar bar = bar();
        MenuItem save = itemFor(bar, "file.save");
        assertNotNull(save, "File > Save is present");
        String text = MainMenuBar.displayTextOf(save);
        assertTrue(text.length() > "Save".length(), "the item shows a chord beside the title, was: " + text);
    }

    /**
     * A command whose feature is switched off is shown disabled rather than removed — the point of a menu is
     * to be a stable map. Git is off in the test fixture's default settings only if disabled, so this drives
     * the gate directly rather than assuming a default.
     */
    @Test
    void aGatedCommandIsDisabledRatherThanMissing() throws Exception {
        MenuBar bar = bar();
        MenuItem commit = itemFor(bar, "git.commit");
        assertNotNull(commit, "VCS > Commit is present regardless of whether Git is on");

        setFlag(s -> s.setGitSupport(false));
        assertTrue(commit.isDisable(), "with Git off the item is disabled, not hidden");

        setFlag(s -> s.setGitSupport(true));
    }

    /**
     * Simple UI mode strips chrome to a minimum, but the menu bar <em>stays</em> — it is the browsable map,
     * which a beginner-facing mode needs most. What changes is its contents: the reduced table, whose entries
     * are all still actionable in a mode with no LSP, VCS, debugging or tool windows.
     */
    @Test
    void simpleModeReducesTheMenuBarRatherThanHidingIt() throws Exception {
        MenuBar bar = bar();
        assertTrue(FxTestSupport.callOnFx(bar::isVisible), "visible by default");
        int full = FxTestSupport.callOnFx(() -> bar.getMenus().size());

        setFlag(s -> s.setSimpleMode(true));
        assertTrue(FxTestSupport.callOnFx(bar::isVisible), "still visible in Simple UI mode");
        assertEquals(
                MenuBarModel.menus(true).size(),
                (int) FxTestSupport.callOnFx(() -> bar.getMenus().size()),
                "rebuilt from the Simple-mode table");

        setFlag(s -> s.setSimpleMode(false));
        assertEquals(full, (int) FxTestSupport.callOnFx(() -> bar.getMenus().size()), "and back again");
    }

    /** Flips a setting on the controller's own config and re-applies the chrome, as a settings apply does. */
    private void setFlag(java.util.function.Consumer<com.editora.config.Settings> change) throws Exception {
        FxTestSupport.runOnFx(() -> {
            com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            change.accept(cfg.getSettings());
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
        });
    }

    /** The label of the item bound to {@code commandId}, found by its position in the model. */
    private MenuItem itemFor(MenuBar bar, String commandId) {
        for (int m = 0; m < MenuBarModel.menus().size(); m++) {
            var entries = MenuBarModel.menus().get(m).entries();
            for (int i = 0; i < entries.size(); i++) {
                if (commandId.equals(entries.get(i))) {
                    return bar.getMenus().get(m).getItems().get(i);
                }
            }
        }
        return null;
    }
}
