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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagerTest {

    @TempDir
    Path tempDir;

    private String previousHomeOverride;

    @BeforeEach
    void clearBefore() {
        previousHomeOverride = System.getProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, tempDir.resolve("home").toString());
    }

    @AfterEach
    void clearAfter() {
        if (previousHomeOverride == null) {
            System.clearProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        } else {
            System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, previousHomeOverride);
        }
    }

    @Test
    void saveAndLoadRoundTripsNormalizedSettings() throws IOException {
        SettingsManager.save(new EditorSettings(
                EditorTheme.NORD_LIGHT,
                true,
                false,
                true,
                true,
                false,
                false,
                ToolWindowSide.RIGHT,
                "alt+shortcut+x",
                "  Fira Code  ",
                16,
                false,
                java.util.List.of("*.log", "LICENSE")
        ));

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.NORD_LIGHT, loaded.theme());
        assertTrue(loaded.wrapText());
        assertFalse(loaded.diagnosticsEnabled());
        assertTrue(loaded.miniMapVisible());
        assertTrue(loaded.searchBarVisible());
        assertFalse(loaded.toolDockVisible());
        assertFalse(loaded.breadcrumbBarVisible());
        assertEquals(ToolWindowSide.RIGHT, loaded.toolDockSide());
        assertEquals("ALT+SHORTCUT+X", loaded.commandPaletteShortcut());
        assertEquals("Fira Code", loaded.editorFontFamily());
        assertEquals(16, loaded.editorFontSize());
        assertFalse(loaded.readOnlyOpenEnabled());
        assertEquals(java.util.List.of("*.log", "LICENSE"), loaded.readOnlyOpenPatterns());
        assertTrue(Files.isRegularFile(SettingsManager.persistenceFile()));
        String persistedSettings = Files.readString(SettingsManager.persistenceFile());
        assertTrue(persistedSettings.contains("\"toolDockVisible\""));
        assertTrue(persistedSettings.contains("\"toolDockSide\""));
        assertFalse(persistedSettings.contains("\"projectExplorerVisible\""));
        assertFalse(persistedSettings.contains("\"projectExplorerSide\""));
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
        assertFalse(loaded.toolDockVisible());
        assertTrue(loaded.breadcrumbBarVisible());
        assertEquals(EditorSettings.DEFAULT_TOOL_DOCK_SIDE, loaded.toolDockSide());
        assertEquals(CommandPaletteShortcut.DEFAULT_VALUE, loaded.commandPaletteShortcut());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_FAMILY, loaded.editorFontFamily());
        assertEquals(EditorSettings.DEFAULT_EDITOR_FONT_SIZE, loaded.editorFontSize());
        assertTrue(loaded.readOnlyOpenEnabled());
        assertEquals(EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS, loaded.readOnlyOpenPatterns());
    }

    @Test
    void loadDefaultsThemeToPrimerLightWhenUnset() {
        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.PRIMER_LIGHT, loaded.theme());
    }

    @Test
    void loadSeedsDefaultReadOnlyPatternsWhenKeyIsMissing() throws IOException {
        Files.createDirectories(SettingsManager.persistenceFile().getParent());
        Files.writeString(SettingsManager.persistenceFile(), """
                {
                  "theme": "PRIMER_DARK",
                  "readOnlyOpenEnabled": true
                }
                """);

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.PRIMER_DARK, loaded.theme());
        assertEquals(EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS, loaded.readOnlyOpenPatterns());
    }

    @Test
    void loadPreservesExplicitlyEmptyReadOnlyPatternLists() throws IOException {
        Files.createDirectories(SettingsManager.persistenceFile().getParent());
        Files.writeString(SettingsManager.persistenceFile(), """
                {
                  "readOnlyOpenEnabled": true,
                  "readOnlyOpenPatterns": []
                }
                """);

        EditorSettings loaded = SettingsManager.load();

        assertTrue(loaded.readOnlyOpenEnabled());
        assertTrue(loaded.readOnlyOpenPatterns().isEmpty());
    }

    @Test
    void loadIgnoresLegacyProjectExplorerSettingKeys() throws IOException {
        Files.createDirectories(SettingsManager.persistenceFile().getParent());
        Files.writeString(SettingsManager.persistenceFile(), """
                {
                  "theme": "NORD_DARK",
                  "projectExplorerVisible": true,
                  "projectExplorerSide": "right"
                }
                """);

        EditorSettings loaded = SettingsManager.load();

        assertEquals(EditorTheme.NORD_DARK, loaded.theme());
        assertFalse(loaded.toolDockVisible());
        assertEquals(EditorSettings.DEFAULT_TOOL_DOCK_SIDE, loaded.toolDockSide());
    }

    @Test
    void loadDefaultsBreadcrumbsToVisibleWhenUnset() {
        EditorSettings loaded = SettingsManager.load();

        assertTrue(loaded.miniMapVisible());
        assertFalse(loaded.searchBarVisible());
        assertFalse(loaded.toolDockVisible());
        assertTrue(loaded.breadcrumbBarVisible());
        assertEquals(EditorSettings.DEFAULT_TOOL_DOCK_SIDE, loaded.toolDockSide());
        assertTrue(loaded.readOnlyOpenEnabled());
        assertEquals(EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS, loaded.readOnlyOpenPatterns());
    }
}

