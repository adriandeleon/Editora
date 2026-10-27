package com.editora.terminal;

import java.util.HashSet;
import java.util.stream.Stream;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The terminal's own colour schemes, chosen apart from the editor theme. */
class TerminalThemesTest {

    static Stream<String> schemes() {
        return TerminalThemes.NAMES.stream();
    }

    @ParameterizedTest
    @MethodSource("schemes")
    void everySchemeHasAllSixteenColoursAndReadableText(String name) {
        TerminalPalette p = TerminalThemes.palette(name);
        assertNotNull(p, name);
        assertEquals(16, p.ansi().length);
        for (Color c : p.ansi()) {
            assertNotNull(c, name);
        }
        // Published values, kept as they are: Solarized Light's base00 is 4.1:1, and the view raises drawn text to
        // TerminalPalette.MIN_CONTRAST anyway. Below 4 would be a typo in a colour.
        assertTrue(TerminalPalettes.contrast(p.background(), p.foreground()) > 4, name + ": default text");
    }

    @ParameterizedTest
    @MethodSource("schemes")
    void aLightOrDarkNameMatchesItsGround(String name) {
        if (name.endsWith(" Light")) {
            assertFalse(TerminalThemes.isDark(name), name);
        } else if (name.endsWith(" Dark")) {
            assertTrue(TerminalThemes.isDark(name), name);
        }
    }

    @Test
    void namesAreUniqueAndNoneIsTheFollowValue() {
        assertEquals(TerminalThemes.NAMES.size(), new HashSet<>(TerminalThemes.NAMES).size());
        assertFalse(TerminalThemes.NAMES.contains(TerminalThemes.FOLLOW_EDITOR));
    }

    @Test
    void blankOrAnUnknownNameFollowsTheEditorTheme() {
        assertNull(TerminalThemes.palette(TerminalThemes.FOLLOW_EDITOR));
        assertNull(TerminalThemes.palette(null));
        assertNull(TerminalThemes.palette("Not A Scheme"));
        assertEquals(TerminalThemes.FOLLOW_EDITOR, TerminalThemes.normalize("Not A Scheme"));
        assertEquals(TerminalThemes.FOLLOW_EDITOR, TerminalThemes.normalize(null));
        assertEquals("Dracula", TerminalThemes.normalize("Dracula"));
    }

    @Test
    void theEditoraSchemesAreTheFlagshipEditorThemesPalettes() {
        assertSame(TerminalPalettes.EDITORA_DARK, TerminalThemes.palette("Editora Dark"));
        assertSame(TerminalPalettes.EDITORA_LIGHT, TerminalThemes.palette("Editora Light"));
    }

    @Test
    void aFixedSchemeKeepsItsPublishedColours() {
        TerminalPalette solarized = TerminalThemes.palette("Solarized Light");
        assertNotNull(solarized);
        assertEquals(Color.web("#fdf6e3"), solarized.background());
        assertEquals(Color.web("#dc322f"), solarized.ansi()[1]);
    }
}
