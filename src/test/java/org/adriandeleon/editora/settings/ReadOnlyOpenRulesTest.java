package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.theme.EditorTheme;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyOpenRulesTest {

    @Test
    void matchesBuiltInPatternsByDefault() {
        EditorSettings settings = new EditorSettings(
                EditorTheme.defaultTheme(),
                false,
                true,
                EditorSettings.DEFAULT_MINI_MAP_VISIBLE,
                EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE,
                EditorSettings.DEFAULT_TOOL_DOCK_VISIBLE,
                true,
                EditorSettings.DEFAULT_TOOL_DOCK_SIDE,
                CommandPaletteShortcut.DEFAULT_VALUE,
                EditorSettings.DEFAULT_EDITOR_FONT_FAMILY,
                EditorSettings.DEFAULT_EDITOR_FONT_SIZE,
                true,
                EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS
        );

        assertTrue(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("README"), settings));
        assertTrue(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("notes.md"), settings));
        assertTrue(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("file.TXT"), settings));
        assertFalse(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("Main.java"), settings));
    }

    @Test
    void appliesCustomPatternsAndNormalization() {
        EditorSettings settings = new EditorSettings(
                EditorTheme.defaultTheme(),
                false,
                true,
                EditorSettings.DEFAULT_MINI_MAP_VISIBLE,
                EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE,
                EditorSettings.DEFAULT_TOOL_DOCK_VISIBLE,
                true,
                EditorSettings.DEFAULT_TOOL_DOCK_SIDE,
                CommandPaletteShortcut.DEFAULT_VALUE,
                EditorSettings.DEFAULT_EDITOR_FONT_FAMILY,
                EditorSettings.DEFAULT_EDITOR_FONT_SIZE,
                true,
                List.of("*.log", "LICENSE", "*.trace")
        );

        assertTrue(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("app.log"), settings));
        assertTrue(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("LICENSE"), settings));
        assertTrue(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("run.trace"), settings));
        assertFalse(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("README.adoc"), settings));

        assertEquals("*.log, LICENSE, *.trace", ReadOnlyOpenRules.normalizePatternText("*.log; LICENSE\n*.trace"));
    }

    @Test
    void disabledSettingNeverForcesReadOnly() {
        EditorSettings settings = new EditorSettings(
                EditorTheme.defaultTheme(),
                false,
                true,
                EditorSettings.DEFAULT_MINI_MAP_VISIBLE,
                EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE,
                EditorSettings.DEFAULT_TOOL_DOCK_VISIBLE,
                true,
                EditorSettings.DEFAULT_TOOL_DOCK_SIDE,
                CommandPaletteShortcut.DEFAULT_VALUE,
                EditorSettings.DEFAULT_EDITOR_FONT_FAMILY,
                EditorSettings.DEFAULT_EDITOR_FONT_SIZE,
                false,
                List.of("*.java")
        );

        assertFalse(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("Main.java"), settings));
    }

    @Test
    void explicitEmptyPatternListDisablesTheFormerDefaultsToo() {
        EditorSettings settings = new EditorSettings(
                EditorTheme.defaultTheme(),
                false,
                true,
                EditorSettings.DEFAULT_MINI_MAP_VISIBLE,
                EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE,
                EditorSettings.DEFAULT_TOOL_DOCK_VISIBLE,
                true,
                EditorSettings.DEFAULT_TOOL_DOCK_SIDE,
                CommandPaletteShortcut.DEFAULT_VALUE,
                EditorSettings.DEFAULT_EDITOR_FONT_FAMILY,
                EditorSettings.DEFAULT_EDITOR_FONT_SIZE,
                true,
                List.of()
        );

        assertFalse(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("README"), settings));
        assertFalse(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("notes.md"), settings));
        assertFalse(ReadOnlyOpenRules.shouldOpenReadOnly(Path.of("file.txt"), settings));
    }
}

