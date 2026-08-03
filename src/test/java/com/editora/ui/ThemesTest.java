package com.editora.ui;

import java.util.List;

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
            // Primer Light carries no override — its colors *are* app.css/syntax.css → null is OK.
            String sheet = EditorThemes.stylesheetFor(name);
            if (!EditorThemes.BARE.equals(name)) {
                assertNotNull(sheet, "no editor stylesheet URL for " + name);
            }
            assertDoesNotThrow(() -> EditorThemes.editorBackgroundFor(name), "bad bg for " + name);
            assertDoesNotThrow(() -> EditorThemes.editorForegroundFor(name), "bad fg for " + name);
            assertDoesNotThrow(() -> EditorThemes.lineHighlightFor(name), "bad line highlight for " + name);
            assertDoesNotThrow(() -> EditorThemes.minimapTextFor(name), "bad minimap text for " + name);
            assertDoesNotThrow(() -> EditorThemes.minimapViewportFor(name), "bad minimap viewport for " + name);
        }
    }

    /**
     * The flagship "Caret & Ink" pair is the first-run default, so a missing stylesheet or a name that
     * drifts out of one of the catalogs would leave every fresh install on a fallback theme — a failure
     * that looks like a design choice rather than a bug.
     */
    @Test
    void flagshipThemeIsWiredEverywhere() {
        assertEquals("Editora Light", Themes.DEFAULT);
        assertEquals(Themes.DEFAULT, EditorThemes.DEFAULT, "app and editor defaults must be the same theme");
        for (String name : List.of("Editora Light", "Editora Dark")) {
            assertTrue(Themes.NAMES.contains(name), name + " missing from the app-theme catalog");
            assertTrue(EditorThemes.NAMES.contains(name), name + " missing from the editor-theme catalog");
            assertNotNull(EditorThemes.stylesheetFor(name), name + " must carry its own editor stylesheet");
            assertNotNull(Themes.stylesheetFor(name), name + " must resolve a control stylesheet");
        }
        assertTrue(Themes.themeFor("Editora Dark").isDarkMode(), "Editora Dark must report dark mode");
        assertTrue(!Themes.themeFor("Editora Light").isDarkMode(), "Editora Light must not report dark mode");
    }

    /**
     * The Editora stylesheets are authored, not vendored: their {@code .root} palette block was written
     * by hand and their body reuses the vendored one. A token the body references but the palette omits
     * does not fail any build — JavaFX just leaves that property unresolved and the control silently
     * renders with a default color, so pin the token set against a known-good vendored theme.
     */
    @Test
    void flagshipPaletteDefinesEveryTokenTheVendoredThemesDo() throws Exception {
        var expected = paletteTokens("blue-light");
        for (String base : List.of("editora-light", "editora-dark")) {
            assertEquals(expected, paletteTokens(base), base + ".css palette tokens differ from the vendored set");
        }
    }

    /** The {@code -color-*} token names declared in a theme's leading {@code .root} block. */
    private static java.util.Set<String> paletteTokens(String base) throws Exception {
        var url = Themes.class.getResource("/com/editora/styles/atlantafx-themes/" + base + ".css");
        assertNotNull(url, "missing " + base + ".css");
        String css;
        try (var in = url.openStream()) {
            css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int start = css.indexOf(".root {");
        assertTrue(start >= 0, base + ".css has no .root block");
        String block = css.substring(start, css.indexOf("\n}", start));
        var names = new java.util.TreeSet<String>();
        var m = java.util.regex.Pattern.compile("(-color-[a-z0-9-]+)\\s*:").matcher(block);
        while (m.find()) {
            names.add(m.group(1));
        }
        assertTrue(names.size() > 90, base + ".css palette looks truncated: " + names.size() + " tokens");
        return names;
    }

    @Test
    void unknownThemesFallBackToDefault() {
        assertEquals(Themes.DEFAULT, Themes.normalize("no-such-theme"));
        assertEquals(EditorThemes.DEFAULT, EditorThemes.normalize("no-such-theme"));
        assertNotNull(Themes.stylesheetFor("no-such-theme")); // falls back to Primer Light
    }
}
