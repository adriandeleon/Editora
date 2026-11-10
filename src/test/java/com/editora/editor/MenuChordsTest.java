package com.editora.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Right-click menu items show their command's chord from the live keymap, and only ever display it. */
class MenuChordsTest {

    private final Map<String, String> keymap = new HashMap<>();

    @AfterEach
    void reset() {
        MenuChords.useChords(Map::of);
    }

    @Test
    void aTaggedItemShowsItsCommandsChordIncludingAMultiKeySequence() {
        keymap.put("edit.toggleComment", "C-x C-;");
        MenuChords.useChords(() -> keymap);
        MenuItem item = MenuChords.tag(new MenuItem("Toggle Comment"), "edit.toggleComment");

        assertEquals("edit.toggleComment", MenuChords.commandOf(item));
        assertEquals("C-x C-;", item.getAccelerator().getDisplayText());
        assertEquals("Toggle Comment", item.getText(), "the title is untouched — the chord has its own column");
    }

    @Test
    void anUnboundCommandShowsNothing() {
        MenuChords.useChords(() -> keymap);
        MenuItem item = MenuChords.tag(new MenuItem("Run File"), "file.run");
        assertNull(item.getAccelerator());
    }

    @Test
    void untaggedItemsAndRealAcceleratorsAreLeftAlone() {
        keymap.put("edit.copy", "M-w");
        MenuChords.useChords(() -> keymap);
        MenuItem plain = new MenuItem("Copy");
        KeyCombination real =
                new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        MenuItem terminal = new MenuItem("Copy");
        terminal.setAccelerator(real);

        MenuChords.refresh(List.of(plain, terminal));

        assertNull(plain.getAccelerator());
        assertSame(real, terminal.getAccelerator());
        assertNull(MenuChords.commandOf(plain));
    }

    @Test
    void refreshFollowsAKeymapSwitchIntoSubmenus() {
        keymap.put("lsp.rename", "S-<f6>");
        keymap.put("edit.cut", "C-w");
        MenuChords.useChords(() -> keymap);
        MenuItem rename = MenuChords.tag(new MenuItem("Rename"), "lsp.rename");
        Menu lsp = new Menu("LSP", null, rename);
        MenuItem cut = MenuChords.tag(new MenuItem("Cut"), "edit.cut");

        keymap.put("lsp.rename", "Shift+F6");
        keymap.remove("edit.cut");
        MenuChords.refresh(List.of(lsp, cut));

        assertEquals("Shift+F6", rename.getAccelerator().getDisplayText());
        assertNull(cut.getAccelerator(), "a binding removed since the menu was built disappears");
    }

    @Test
    void theShownChordNeverMatchesAKeyAndNeverEqualsARealCombination() {
        MenuChords.Shown shown = new MenuChords.Shown("C-x k");
        KeyEvent ctrlX = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.X, false, true, false, false);
        assertFalse(shown.match(ctrlX), "display only — it must never fire the item or steal a keystroke");

        KeyCombination none = new KeyCodeCombination(KeyCode.K);
        assertNotEquals(shown, none);
        assertNotEquals(none, shown);
        assertNotEquals(shown, new MenuChords.Shown("C-x k"));
        assertTrue(shown.equals(shown));
    }
}
