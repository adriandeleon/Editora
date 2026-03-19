package org.adriandeleon.editora.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorThemeTest {

    @Test
    void mapsLegacyStoredValuesToPrimerThemes() {
        assertEquals(EditorTheme.PRIMER_LIGHT, EditorTheme.fromStoredValue("LIGHT"));
        assertEquals(EditorTheme.PRIMER_DARK, EditorTheme.fromStoredValue("DARK"));
        assertEquals(EditorTheme.PRIMER_LIGHT, EditorTheme.fromStoredValue("broken"));
    }

    @Test
    void exposesPrimerLightAsTheDefaultTheme() {
        assertEquals(EditorTheme.PRIMER_LIGHT, EditorTheme.defaultTheme());
    }

    @Test
    void exposesGroupedDisplayNamesForSettingsAndCommands() {
        assertEquals("Primer · Light", EditorTheme.PRIMER_LIGHT.getSettingsDisplayName());
        assertEquals("Cupertino · Dark", EditorTheme.CUPERTINO_DARK.getSettingsDisplayName());
        assertEquals("Dracula", EditorTheme.DRACULA.getSettingsDisplayName());
        assertEquals("Set Theme: Nord Dark", EditorTheme.NORD_DARK.getCommandName());
    }

    @Test
    void cyclesThroughAllThemesAndPreservesDarkMetadata() {
        assertEquals(EditorTheme.PRIMER_DARK, EditorTheme.PRIMER_LIGHT.next());
        assertEquals(EditorTheme.PRIMER_LIGHT, EditorTheme.DRACULA.next());
        assertFalse(EditorTheme.NORD_LIGHT.isDark());
        assertTrue(EditorTheme.DRACULA.isDark());
    }
}

