package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.persistence.EditoraPersistence;
import org.adriandeleon.editora.theme.EditorTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagerTest {

    @TempDir
    Path tempDir;

    private final Preferences preferences = Preferences.userNodeForPackage(SettingsManager.class);
    private String previousHomeOverride;

    @BeforeEach
    void clearBefore() throws BackingStoreException {
        previousHomeOverride = System.getProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, tempDir.resolve("home").toString());
        preferences.clear();
    }

    @AfterEach
    void clearAfter() throws BackingStoreException {
        preferences.clear();
        if (previousHomeOverride == null) {
            System.clearProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        } else {
            System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, previousHomeOverride);
        }
    }

    @Test
    void saveAndLoadRoundTripsNormalizedSettings() {
        SettingsManager.save(new EditorSettings(
                EditorTheme.NORD_LIGHT,
                true,
                false,
                true,
                true,
                false,
                false,
                "alt+shortcut+x",
                "  Fira Code  ",
                16
        ));

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.NORD_LIGHT, loaded.theme());
        assertTrue(loaded.wrapText());
        assertFalse(loaded.diagnosticsEnabled());
        assertTrue(loaded.miniMapVisible());
        assertTrue(loaded.searchBarVisible());
        assertFalse(loaded.projectExplorerVisible());
        assertFalse(loaded.breadcrumbBarVisible());
        assertEquals("ALT+SHORTCUT+X", loaded.commandPaletteShortcut());
        assertEquals("Fira Code", loaded.editorFontFamily());
        assertEquals(16, loaded.editorFontSize());
        assertTrue(Files.isRegularFile(SettingsManager.persistenceFile()));
    }

    @Test
    void loadFallsBackWhenStoredJsonIsInvalid() throws IOException {
        Files.createDirectories(SettingsManager.persistenceFile().getParent());
        Files.writeString(SettingsManager.persistenceFile(), "{ not-json }");

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.PRIMER_LIGHT, loaded.theme());
        assertFalse(loaded.wrapText());
        assertTrue(loaded.diagnosticsEnabled());
        assertTrue(loaded.miniMapVisible());
        assertFalse(loaded.searchBarVisible());
        assertFalse(loaded.projectExplorerVisible());
        assertTrue(loaded.breadcrumbBarVisible());
        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, loaded.commandPaletteShortcut());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_FAMILY, loaded.editorFontFamily());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_SIZE, loaded.editorFontSize());
    }

    @Test
    void loadDefaultsThemeToPrimerLightWhenUnset() {
        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.PRIMER_LIGHT, loaded.theme());
    }

    @Test
    void loadMigratesLegacyLightAndDarkThemeValues() {
        preferences.put("theme", "LIGHT");
        assertEquals(EditorTheme.PRIMER_LIGHT, SettingsManager.load().theme());

        try {
            Files.deleteIfExists(SettingsManager.persistenceFile());
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
        preferences.put("theme", "DARK");
        assertEquals(EditorTheme.PRIMER_DARK, SettingsManager.load().theme());
    }

    @Test
    void loadMigratesLegacyPreferencesIntoJsonPersistence() {
        preferences.put("theme", "BROKEN_THEME");
        preferences.putBoolean("wrapText", true);
        preferences.putBoolean("diagnosticsEnabled", false);
        preferences.putBoolean("miniMapVisible", false);
        preferences.putBoolean("searchBarVisible", true);
        preferences.putBoolean("projectExplorerVisible", true);
        preferences.putBoolean("breadcrumbBarVisible", false);
        preferences.put("commandPaletteShortcut", "not-a-shortcut");
        preferences.put("editorFontFamily", "   ");
        preferences.putInt("editorFontSize", 200);

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.PRIMER_LIGHT, loaded.theme());
        assertTrue(loaded.wrapText());
        assertFalse(loaded.diagnosticsEnabled());
        assertFalse(loaded.miniMapVisible());
        assertTrue(loaded.searchBarVisible());
        assertTrue(loaded.projectExplorerVisible());
        assertFalse(loaded.breadcrumbBarVisible());
        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, loaded.commandPaletteShortcut());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_FAMILY, loaded.editorFontFamily());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_SIZE, loaded.editorFontSize());
        assertTrue(Files.isRegularFile(SettingsManager.persistenceFile()));
    }

    @Test
    void loadDefaultsBreadcrumbsToVisibleWhenUnset() {
        EditorSettings loaded = SettingsManager.load();

        assertTrue(loaded.miniMapVisible());
        assertFalse(loaded.searchBarVisible());
        assertFalse(loaded.projectExplorerVisible());
        assertTrue(loaded.breadcrumbBarVisible());
    }
}

