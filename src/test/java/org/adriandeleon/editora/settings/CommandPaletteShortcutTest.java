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
        EditorSettings invalid = new EditorSettings(EditorTheme.PRIMER_DARK, false, true, true, true, false, false, true, null, "not-a-shortcut", "", -1, true, java.util.List.of());
        EditorSettings reserved = new EditorSettings(EditorTheme.PRIMER_DARK, false, true, false, false, true, false, false, ToolWindowSide.RIGHT, "ALT+F", "", -1, true, java.util.List.of());

        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, invalid.commandPaletteShortcut());
        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, reserved.commandPaletteShortcut());
        assertTrue(invalid.miniMapVisible());
        assertFalse(reserved.miniMapVisible());
        assertTrue(invalid.searchBarVisible());
        assertFalse(invalid.toolDockVisible());
        assertFalse(reserved.searchBarVisible());
        assertTrue(reserved.toolDockVisible());
        assertTrue(invalid.breadcrumbBarVisible());
        assertFalse(reserved.breadcrumbBarVisible());
        assertEquals(EditorSettings.DEFAULT_TOOL_DOCK_SIDE, invalid.toolDockSide());
        assertEquals(ToolWindowSide.RIGHT, reserved.toolDockSide());
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

