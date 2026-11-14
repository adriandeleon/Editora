package com.editora.ui;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two commands behind the Help menu's keyboard rows: Keyboard Shortcuts opens the keybinding editor, and
 * Focus Menu Bar opens the first menu for a keymap whose F10 does something else.
 */
@Tag("fx")
class HelpCommandsFxTest {

    private static FxWindowFixture fx;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static String echo() {
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(statusBar, "echo").getText();
    }

    @Test
    void keyboardShortcutsOpensTheKeybindingEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            assertTrue(c.registry.run("help.keyboardShortcuts"));
            try {
                assertTrue(c.settingsWindow.stage.isShowing());
                ListView<Object> sidebar = FxTestSupport.field(c.settingsWindow, "sidebar");
                assertEquals("KEYMAPS", ((Enum<?>) sidebar.getSelectionModel().getSelectedItem()).name());
            } finally {
                c.settingsWindow.stage.hide();
            }
        });
    }

    @Test
    void focusMenuBarOpensTheFirstMenuOrSaysWhyItCannot() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            if (!c.stage.isShowing()) {
                c.stage.show();
            }
            MenuBar bar = c.menuBar.node();
            Menu first = bar.getMenus().get(0);
            assertTrue(c.registry.run("view.focusMenuBar"));
            if (c.menuBar.onSystemMenuBar()) {
                // macOS: the bar is the system's, reached with the system's keys.
                assertEquals(tr("status.menuBar.system"), echo());
                return;
            }
            assertTrue(first.isShowing(), "the first menu is open, for the arrow keys to walk the bar");
            first.hide();

            bar.setVisible(false);
            try {
                assertTrue(c.registry.run("view.focusMenuBar"));
                assertFalse(first.isShowing());
                assertEquals(tr("status.menuBar.hidden"), echo());
            } finally {
                bar.setVisible(true);
            }
        });
    }

    @Test
    void everyKeymapThatTakesF10BindsAWayToTheMenuBar() {
        for (String name : java.util.List.of("emacs", "cua", "vscode", "sublime", "intellij")) {
            com.editora.command.KeymapManager keymap = new com.editora.command.KeymapManager();
            keymap.loadNamed(name);
            assertTrue(keymap.displayChord("view.focusMenuBar") != null, name + " binds view.focusMenuBar");
        }
    }
}
