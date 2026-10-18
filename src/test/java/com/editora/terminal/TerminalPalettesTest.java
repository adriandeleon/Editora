package com.editora.terminal;

import java.util.List;
import java.util.stream.Stream;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The palettes derived from editor themes, and the palette the renderer resolves cell colours with. */
class TerminalPalettesTest {

    static Stream<TerminalPalette> flagship() {
        return Stream.of(TerminalPalettes.EDITORA_DARK, TerminalPalettes.EDITORA_LIGHT);
    }

    @ParameterizedTest
    @MethodSource("flagship")
    void everyPaletteDefinesAllSixteenAnsiColours(TerminalPalette palette) {
        assertEquals(16, palette.ansi().length);
        for (Color colour : palette.ansi()) {
            assertNotNull(colour);
        }
    }

    @ParameterizedTest
    @MethodSource("flagship")
    void defaultTextIsReadable(TerminalPalette palette) {
        assertTrue(TerminalPalettes.contrast(palette.background(), palette.foreground()) > 4.5);
    }

    /**
     * 1-6 and 9-14 are the colours programs use to mean something — an error, a directory, a diff line. 0/8 and
     * 7/15 are the ends of the ramp and legitimately faint on a ground of the same polarity.
     */
    @ParameterizedTest
    @MethodSource("flagship")
    void everyChromaticColourStaysLegible(TerminalPalette palette) {
        for (int i : new int[] {1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14}) {
            double ratio = TerminalPalettes.contrast(palette.background(), palette.ansi()[i]);
            assertTrue(ratio > 1.9, () -> "ANSI colour " + i + " at " + ratio + " is too close to the background");
        }
    }

    @Test
    void theTokenColoursBecomeTheAnsiColoursTheyStandFor() {
        Color bg = Color.web("#101010");
        Color fg = Color.web("#f0f0f0");
        List<Color> tokens = List.of(
                Color.web("#00aaaa"), // caret → cyan (6)
                Color.web("#ff0000"), // keyword → red (1)
                Color.web("#00ff00"), // escape → green (2)
                Color.web("#ffff00"), // type → yellow (3)
                Color.web("#0000ff"), // number → blue (4)
                Color.web("#ff00ff")); // function → magenta (5)
        TerminalPalette palette = TerminalPalettes.derive(new SyntaxColors(
                bg, fg, tokens.get(0), tokens.get(1), tokens.get(2), tokens.get(3), tokens.get(4), tokens.get(5)));

        Color[] ansi = palette.ansi();
        assertEquals(tokens.get(1), ansi[1]);
        assertEquals(tokens.get(2), ansi[2]);
        assertEquals(tokens.get(3), ansi[3]);
        assertEquals(tokens.get(4), ansi[4]);
        assertEquals(tokens.get(5), ansi[5]);
        assertEquals(tokens.get(0), ansi[6]);
        assertEquals(fg, ansi[15]);
        assertEquals(bg, palette.background());
        assertEquals(fg, palette.foreground());
    }

    @Test
    void brightColoursMoveAwayFromTheGround() {
        // Lighter on a dark ground, darker on a light one: a light colour on white is unreadable.
        Color[] dark = TerminalPalettes.EDITORA_DARK.ansi();
        Color[] light = TerminalPalettes.EDITORA_LIGHT.ansi();
        for (int i = 1; i <= 6; i++) {
            assertTrue(TerminalPalettes.luminance(dark[i + 8]) > TerminalPalettes.luminance(dark[i]), "dark " + i);
            assertTrue(TerminalPalettes.luminance(light[i + 8]) < TerminalPalettes.luminance(light[i]), "light " + i);
        }
    }

    @Test
    void lightThemesKeepTheirWhitesReadable() {
        // ANSI white is darkened on a light ground on purpose, so a program that sets colour 7 does not write
        // invisible text. Asserted so the choice cannot be undone by accident.
        Color background = TerminalPalettes.EDITORA_LIGHT.background();
        Color[] ansi = TerminalPalettes.EDITORA_LIGHT.ansi();
        assertTrue(TerminalPalettes.contrast(background, ansi[7]) > 4.0);
        assertTrue(TerminalPalettes.contrast(background, ansi[15]) > 4.0);
    }

    @Test
    void whiteAndBrightWhiteStayDistinct() {
        Color[] ansi = TerminalPalettes.EDITORA_DARK.ansi();
        assertTrue(!ansi[7].equals(ansi[15]));
    }

    @Test
    void theFlagshipPairKeepsTerminasNormalColours() {
        // Termina applied the same rule to these two themes by hand; the derived normal colours are the same.
        assertEquals(Color.web("#ff7b72"), TerminalPalettes.EDITORA_DARK.ansi()[1]);
        assertEquals(Color.web("#43dec5"), TerminalPalettes.EDITORA_DARK.ansi()[6]);
        assertEquals(Color.web("#0550ae"), TerminalPalettes.EDITORA_LIGHT.ansi()[4]);
        assertEquals(Color.web("#1b1e2a"), TerminalPalettes.EDITORA_LIGHT.ansi()[0]);
    }

    @Test
    void paletteDoesNotShareItsArrayWithCallers() {
        // The renderer holds a palette for the life of a theme; a caller mutating the array it got back would
        // silently recolour the terminal.
        Color[] first = TerminalPalettes.EDITORA_DARK.ansi();
        first[0] = Color.HOTPINK;
        assertNotSame(Color.HOTPINK, TerminalPalettes.EDITORA_DARK.ansi()[0]);
    }

    @Test
    void extendedColourCubeMatchesXterm() {
        TerminalPalette palette = TerminalPalettes.EDITORA_DARK;
        // Index 196 is pure red in xterm's 6x6x6 cube; programs compute these arithmetically, so they are not a
        // theme's to restyle.
        assertEquals(Color.rgb(255, 0, 0), palette.resolve(indexed(196), Color.BLACK));
        // 232 is the darkest step of the grey ramp.
        assertEquals(Color.rgb(8, 8, 8), palette.resolve(indexed(232), Color.BLACK));
    }

    @Test
    void indexedColoursBelowSixteenComeFromThePalette() {
        TerminalPalette palette = TerminalPalettes.EDITORA_LIGHT;
        assertEquals(palette.ansi()[1], palette.resolve(indexed(1), Color.BLACK));
    }

    private static com.jediterm.terminal.TerminalColor indexed(int index) {
        return com.jediterm.terminal.TerminalColor.index(index);
    }
}
