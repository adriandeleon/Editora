package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Keymaps filter: words in any order, and a chord typed in any notation. */
class ShortcutFilterTest {

    private static final SettingsWindow.Shortcut SAVE =
            new SettingsWindow.Shortcut("file.save", "File: Save", "Ctrl+S");
    private static final SettingsWindow.Shortcut SAVE_AS =
            new SettingsWindow.Shortcut("file.saveAs", "File: Save As…", "Ctrl+Shift+S");
    private static final SettingsWindow.Shortcut CLOSE =
            new SettingsWindow.Shortcut("view.closeAll", "View: Close All", "Ctrl+K Ctrl+W");

    @Test
    void everyWordMustMatchInAnyOrder() {
        assertTrue(SettingsWindow.shortcutMatches(SAVE, "file save", null));
        assertTrue(SettingsWindow.shortcutMatches(SAVE, "Save File", null));
        assertTrue(SettingsWindow.shortcutMatches(SAVE, "", null));
        assertFalse(SettingsWindow.shortcutMatches(SAVE, "file open", null));
    }

    @Test
    void aTypedChordFindsItsCommandExactlyOrAsTheStartOfASequence() {
        // "C-s" or "Ctrl+S" typed under CUA: shown as Ctrl+S. It used to match Ctrl+Shift+S as text too.
        assertTrue(SettingsWindow.shortcutMatches(SAVE, "C-s", "Ctrl+S"));
        assertFalse(SettingsWindow.shortcutMatches(SAVE_AS, "C-s", "Ctrl+S"));
        assertTrue(SettingsWindow.shortcutMatches(CLOSE, "Ctrl+K", "Ctrl+K"));
        assertFalse(SettingsWindow.shortcutMatches(CLOSE, "Ctrl+W", "Ctrl+W"));
    }
}
