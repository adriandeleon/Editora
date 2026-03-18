package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.theme.EditorTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagerTest {

    private final Preferences preferences = Preferences.userNodeForPackage(SettingsManager.class);

    @BeforeEach
    void clearBefore() throws BackingStoreException {
        preferences.clear();
    }

    @AfterEach
    void clearAfter() throws BackingStoreException {
        preferences.clear();
    }

    @Test
    void saveAndLoadRoundTripsNormalizedSettings() {
        SettingsManager.save(new EditorSettings(
                EditorTheme.LIGHT,
                true,
                false,
                "alt+shortcut+x",
                "  Fira Code  ",
                16
        ));

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.LIGHT, loaded.theme());
        assertTrue(loaded.wrapText());
        assertFalse(loaded.diagnosticsEnabled());
        assertEquals("ALT+SHORTCUT+X", loaded.commandPaletteShortcut());
        assertEquals("Fira Code", loaded.editorFontFamily());
        assertEquals(16, loaded.editorFontSize());
    }

    @Test
    void loadFallsBackWhenStoredPreferencesAreInvalid() {
        preferences.put("theme", "BROKEN_THEME");
        preferences.putBoolean("wrapText", true);
        preferences.putBoolean("diagnosticsEnabled", false);
        preferences.put("commandPaletteShortcut", "not-a-shortcut");
        preferences.put("editorFontFamily", "   ");
        preferences.putInt("editorFontSize", 200);

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.DARK, loaded.theme());
        assertTrue(loaded.wrapText());
        assertFalse(loaded.diagnosticsEnabled());
        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, loaded.commandPaletteShortcut());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_FAMILY, loaded.editorFontFamily());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_SIZE, loaded.editorFontSize());
    }
}

