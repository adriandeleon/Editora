package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the AtlantaFX-theme and editor-theme catalogs: every listed theme must resolve to a real,
 * loadable stylesheet URL, every AtlantaFX theme must have a matching editor theme, and every
 * editor theme's code-driven colors must parse. Pure (no FX toolkit needed — {@code Color.web} and
 * {@code getResource} don't require {@code Platform.startup}).
 */
class ThemesTest {

    @Test
    void everyAppThemeResolvesToALoadableStylesheet() {
        for (String name : Themes.NAMES) {
            String sheet = Themes.stylesheetFor(name);
            assertNotNull(sheet, "no stylesheet URL for app theme " + name);
            // A bare classpath path (leading '/') means getResource returned null → the resource is
            // missing or unreachable; a real URL has a scheme.
            assertTrue(
                    sheet.contains(":/") || sheet.startsWith("jrt:") || sheet.startsWith("file:"),
                    "app theme " + name + " did not resolve to a URL: " + sheet);
        }
    }

    @Test
    void everyBundledCommunityThemeCssExists() {
        for (var entry : Themes.BUNDLED.entrySet()) {
            String base = entry.getValue()[0];
            assertNotNull(
                    Themes.class.getResource("/com/editora/styles/atlantafx-themes/" + base + ".css"),
                    "missing vendored CSS for " + entry.getKey() + " (" + base + ".css)");
        }
    }

    @Test
    void adaptiveEditorThemeCssExists() {
        assertNotNull(
                EditorThemes.class.getResource("/com/editora/styles/editor-themes/adaptive.css"),
                "missing adaptive.css");
    }

    @Test
    void everyAppThemeHasAMatchingEditorTheme() {
        for (String name : Themes.NAMES) {
            assertTrue(EditorThemes.NAMES.contains(name), "no matching editor theme for app theme " + name);
            assertEquals(name, EditorThemes.defaultFor(name), "editor theme mismatch for " + name);
        }
    }

    @Test
    void everyEditorThemeResolvesStylesheetAndColors() {
        for (String name : EditorThemes.NAMES) {
            // Primer Light is the default (its colors live in app.css/syntax.css) → null stylesheet is OK.
            String sheet = EditorThemes.stylesheetFor(name);
            if (!EditorThemes.DEFAULT.equals(name)) {
                assertNotNull(sheet, "no editor stylesheet URL for " + name);
            }
            assertDoesNotThrow(() -> EditorThemes.editorBackgroundFor(name), "bad bg for " + name);
            assertDoesNotThrow(() -> EditorThemes.editorForegroundFor(name), "bad fg for " + name);
            assertDoesNotThrow(() -> EditorThemes.lineHighlightFor(name), "bad line highlight for " + name);
            assertDoesNotThrow(() -> EditorThemes.minimapTextFor(name), "bad minimap text for " + name);
            assertDoesNotThrow(() -> EditorThemes.minimapViewportFor(name), "bad minimap viewport for " + name);
        }
    }

    @Test
    void unknownThemesFallBackToDefault() {
        assertEquals(Themes.DEFAULT, Themes.normalize("no-such-theme"));
        assertEquals(EditorThemes.DEFAULT, EditorThemes.normalize("no-such-theme"));
        assertNotNull(Themes.stylesheetFor("no-such-theme")); // falls back to Primer Light
    }
}
