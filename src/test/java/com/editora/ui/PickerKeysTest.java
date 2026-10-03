package com.editora.ui;

import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.ui.PickerKeys.Action;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Picker navigation resolves through the keymap, and the legend names only keys that reach the picker. */
class PickerKeysTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static KeymapManager keymap(String name) {
        KeymapManager km = new KeymapManager();
        km.loadNamed(name);
        return km;
    }

    // --- resolve ---

    @Test
    void theFixedKeysAlwaysWork() {
        assertEquals(Action.DOWN, PickerKeys.resolve("down", null, true));
        assertEquals(Action.UP, PickerKeys.resolve("up", null, true));
        assertEquals(Action.PAGE_DOWN, PickerKeys.resolve("pagedown", null, true));
        assertEquals(Action.PAGE_UP, PickerKeys.resolve("pageup", null, true));
        assertEquals(Action.ACCEPT, PickerKeys.resolve("enter", null, true));
        assertEquals(Action.CANCEL, PickerKeys.resolve("escape", null, true));
        assertEquals(Action.FIRST, PickerKeys.resolve("C-home", null, true));
        assertEquals(Action.LAST, PickerKeys.resolve("C-end", null, true));
    }

    @Test
    void plainHomeAndEndBelongToTheQueryFieldWhenThereIsOne() {
        assertEquals(Action.NONE, PickerKeys.resolve("home", null, true));
        assertEquals(Action.NONE, PickerKeys.resolve("end", null, true));
        assertEquals(Action.FIRST, PickerKeys.resolve("home", null, false));
        assertEquals(Action.LAST, PickerKeys.resolve("end", null, false));
    }

    @Test
    void theKeymapsCaretCommandsNavigate() {
        assertEquals(Action.DOWN, PickerKeys.resolve("C-n", "nav.lineDown", true));
        assertEquals(Action.UP, PickerKeys.resolve("C-p", "nav.lineUp", true));
        assertEquals(Action.PAGE_DOWN, PickerKeys.resolve("C-v", "nav.pageDown", true));
        assertEquals(Action.PAGE_UP, PickerKeys.resolve("M-v", "nav.pageUp", true));
        assertEquals(Action.FIRST, PickerKeys.resolve("M-S-,", "nav.docStart", true));
        assertEquals(Action.LAST, PickerKeys.resolve("M-S-.", "nav.docEnd", true));
        assertEquals(Action.CANCEL, PickerKeys.resolve("C-g", "edit.cancel", true));
    }

    @Test
    void aChordBoundToSomethingElseIsNotNavigation() {
        // CUA: Ctrl+N is file.new and Ctrl+P is editor.print — never "move down/up" just because of the letter.
        assertEquals(Action.NONE, PickerKeys.resolve("C-n", "file.new", true));
        assertEquals(Action.NONE, PickerKeys.resolve("C-p", "editor.print", true));
        assertEquals(Action.NONE, PickerKeys.resolve("C-g", "nav.goToLine", true));
        assertEquals(Action.NONE, PickerKeys.resolve("C-n", null, true), "an unbound chord is not navigation");
        assertEquals(Action.NONE, PickerKeys.resolve("a", null, true));
    }

    // --- target ---

    @Test
    void upAndDownWrap() {
        assertEquals(1, PickerKeys.target(Action.DOWN, 0, 3, 8));
        assertEquals(0, PickerKeys.target(Action.DOWN, 2, 3, 8));
        assertEquals(2, PickerKeys.target(Action.UP, 0, 3, 8));
        assertEquals(0, PickerKeys.target(Action.DOWN, -1, 3, 8), "nothing selected: Down starts at the top");
        assertEquals(2, PickerKeys.target(Action.UP, -1, 3, 8), "nothing selected: Up starts at the bottom");
    }

    @Test
    void pagingAndEndsClampInsteadOfWrapping() {
        assertEquals(8, PickerKeys.target(Action.PAGE_DOWN, 0, 20, 8));
        assertEquals(19, PickerKeys.target(Action.PAGE_DOWN, 15, 20, 8));
        assertEquals(0, PickerKeys.target(Action.PAGE_UP, 3, 20, 8));
        assertEquals(7, PickerKeys.target(Action.PAGE_UP, 15, 20, 8));
        assertEquals(0, PickerKeys.target(Action.FIRST, 15, 20, 8));
        assertEquals(19, PickerKeys.target(Action.LAST, 0, 20, 8));
        assertEquals(1, PickerKeys.target(Action.PAGE_DOWN, 0, 20, 0), "a page is never less than one row");
    }

    @Test
    void nonNavigationAndEmptyListsHaveNoTarget() {
        assertEquals(-1, PickerKeys.target(Action.ACCEPT, 0, 3, 8));
        assertEquals(-1, PickerKeys.target(Action.NONE, 0, 3, 8));
        assertEquals(-1, PickerKeys.target(Action.DOWN, 0, 0, 8));
        assertTrue(PickerKeys.isNavigation(Action.PAGE_UP));
        assertFalse(PickerKeys.isNavigation(Action.CANCEL));
    }

    // --- legend ---

    @Test
    void emacsLegendNamesTheEmacsChords() {
        String legend = PickerKeys.legend(keymap("emacs"), PickerKeys.hint("select", "↵"));
        assertEquals("↑↓ / C-n C-p move  ·  ↵ select  ·  esc / C-g cancel", legend);
    }

    @Test
    void aGuiKeymapLegendNamesOnlyTheFixedKeys() {
        // No GUI keymap binds line-down/up or cancel, so the legend must not advertise C-n/C-p/C-g: those
        // chords are file.new / print / go-to-line there and never reach the picker.
        for (String name : new String[] {"cua", "vscode", "sublime", "intellij"}) {
            String legend = PickerKeys.legend(keymap(name), PickerKeys.hint("run", "↵"));
            assertEquals("↑↓ move  ·  ↵ run  ·  esc cancel", legend, name);
        }
    }

    @Test
    void theLegendFollowsARebind() {
        KeymapManager km = keymap("cua");
        km.applyOverrides(Map.of("M-j", "nav.lineDown", "M-k", "nav.lineUp"));
        String legend = PickerKeys.legend(km);
        assertTrue(legend.startsWith("↑↓ / "), legend);
        assertTrue(legend.contains(km.displayChord("nav.lineDown")), legend);
        assertTrue(legend.contains(km.displayChord("nav.lineUp")), legend);
    }

    @Test
    void halfBoundNavigationIsNotAdvertised() {
        KeymapManager km = keymap("cua");
        km.applyOverrides(Map.of("M-j", "nav.lineDown")); // down only
        assertEquals("↑↓", PickerKeys.withChords("↑↓", km, "nav.lineDown", "nav.lineUp"));
        assertNull(PickerKeys.chords(null, "nav.lineDown"));
    }

    @Test
    void nullEntriesAreSkippedAndNoKeymapStillGivesALegend() {
        assertEquals(
                "↑↓ move  ·  ↵ open  ·  esc cancel",
                PickerKeys.legend((KeymapManager) null, null, PickerKeys.hint("open", "↵")));
        assertNull(PickerKeys.hint("docs", ""), "an action with no key is left out, not shown keyless");
        assertNull(PickerKeys.hint("docs", null));
    }

    @Test
    void thePickerLocalKeyIsOneTheKeymapLeavesFree() {
        // The palette's "docs" key: C-h is free in Emacs but is Replace in the CUA/VS Code/Sublime keymaps.
        assertEquals("C-h", PickerKeys.freeChord(keymap("emacs"), "C-h", "f1", "S-f1"));
        KeymapManager cua = new KeymapManager();
        cua.loadNamed("cua");
        cua.applyOverrides(Map.of("C-h", "find.replace")); // true on every platform's cua map after this
        assertEquals("f1", PickerKeys.freeChord(cua, "C-h", "f1", "S-f1"));
        cua.applyOverrides(Map.of("f1", "palette.show", "S-f1 x", "file.save"));
        assertNull(PickerKeys.freeChord(cua, "C-h", "f1", "S-f1"), "a prefix is taken too");
        assertEquals("C-h", PickerKeys.freeChord(null, "C-h", "f1"));
    }
}
