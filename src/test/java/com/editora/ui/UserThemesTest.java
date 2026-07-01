package com.editora.ui;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure helpers of the user-theme loader (name derivation + color parsing). No FX toolkit needed. */
class UserThemesTest {

    @Test
    void baseNameStripsExtension() {
        assertEquals("my-theme", UserThemes.baseName("my-theme.css"));
        assertEquals("my-theme", UserThemes.baseName("my-theme.CSS".replace(".CSS", ".css")));
        assertEquals("noext", UserThemes.baseName("noext"));
    }

    @Test
    void titleCaseFromFileName() {
        assertEquals("My Cool Theme", UserThemes.titleCase("my-cool-theme"));
        assertEquals("My Cool Theme", UserThemes.titleCase("my_cool_theme"));
        assertEquals("Solarized Dark", UserThemes.titleCase("solarized dark"));
        assertEquals("Ocean", UserThemes.titleCase("ocean"));
        assertEquals("", UserThemes.titleCase("   "));
    }

    @Test
    void parseColorFindsHexAfterToken() {
        String css = ".root { -color-bg-default: #142342; -color-fg-default: #F4F6F9; }";
        assertEquals(Color.web("#142342"), UserThemes.parseColor(css, "-color-bg-default"));
        assertEquals(Color.web("#f4f6f9"), UserThemes.parseColor(css, "-color-fg-default"));
    }

    @Test
    void parseColorHandlesAlphaAndCssProps() {
        assertEquals(Color.web("#58a6ff40"), UserThemes.parseColor("-color-x: #58a6ff40;", "-color-x"));
        assertEquals(
                Color.web("#1e1e1e"),
                UserThemes.parseColor(".editor-area { -fx-background-color: #1e1e1e; }", "-fx-background-color"));
    }

    @Test
    void parseColorReturnsNullWhenAbsentOrRgba() {
        assertNull(UserThemes.parseColor(".root { -color-fg-default: #fff000; }", "-color-bg-default"));
        // rgba(...) isn't a #hex → not parsed (fine; callers fall back / derive).
        assertNull(UserThemes.parseColor("-color-accent-muted: rgba(0,0,0,0.3);", "-color-accent-muted"));
    }
}
