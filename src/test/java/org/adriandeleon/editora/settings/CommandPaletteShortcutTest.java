package org.adriandeleon.editora.settings;

import javafx.scene.input.KeyCodeCombination;
import org.adriandeleon.editora.theme.EditorTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPaletteShortcutTest {

    @Test
    void editorSettingsFallsBackToDefaultForInvalidOrReservedShortcuts() {
        EditorSettings invalid = new EditorSettings(EditorTheme.PRIMER_DARK, false, true, "not-a-shortcut", "", -1);
        EditorSettings reserved = new EditorSettings(EditorTheme.PRIMER_DARK, false, true, "ALT+F", "", -1);

        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, invalid.commandPaletteShortcut());
        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, reserved.commandPaletteShortcut());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_FAMILY, invalid.editorFontFamily());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_SIZE, invalid.editorFontSize());
    }

    @Test
    void normalizesAndFormatsShortcutValues() {
        String normalized = CommandPaletteShortcut.normalize("alt+x");

        assertEquals("ALT+X", normalized);
        assertFalse(CommandPaletteShortcut.displayText(normalized).isBlank());
        assertTrue(CommandPaletteShortcut.isReserved("shortcut+comma"));
        assertTrue(CommandPaletteShortcut.isReserved("alt+f"));
    }

    @Test
    void buildsAKeyCombinationFromStoredShortcut() {
        var combination = CommandPaletteShortcut.keyCombination("ALT+X");

        KeyCodeCombination keyCodeCombination = assertInstanceOf(KeyCodeCombination.class, combination);
        assertEquals("X", keyCodeCombination.getCode().name());
        assertFalse(keyCodeCombination.getDisplayText().isBlank());
    }
}

