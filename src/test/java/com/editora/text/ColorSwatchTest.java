package com.editora.text;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Swatches move with the edits made before the server's next answer (HTML review 2 C111/B108). */
class ColorSwatchTest {

    private static ColorSwatch at(int line, int column) {
        return new ColorSwatch(line, column, 1, 0, 0, 1);
    }

    private static List<String> where(List<ColorSwatch> swatches) {
        return swatches.stream().map(s -> s.line() + ":" + s.column()).toList();
    }

    @Test
    void textTypedBeforeASwatchMovesItAlongItsLine() {
        List<ColorSwatch> all = List.of(at(0, 11), at(0, 28), at(1, 11));
        assertEquals(
                List.of("0:35", "0:52", "1:11"), where(ColorSwatch.shifted(all, 0, 4, "", "border-top-color: navy; ")));
        assertEquals(
                List.of("0:12", "0:29", "1:11"), where(ColorSwatch.shifted(all, 0, 11, "", "x")), "typed right before");
        assertEquals(
                List.of("0:11", "0:27", "1:11"), where(ColorSwatch.shifted(all, 0, 20, "x", "")), "deleted between");
        assertSame(all, ColorSwatch.shifted(all, 1, 20, "", "x"), "after every swatch: the same list");
        assertSame(all, ColorSwatch.shifted(all, 0, 4, "", ""), "no edit");
    }

    @Test
    void lineBreaksMoveTheSwatchesBelowAndThoseAfterTheBreakOnItsLine() {
        List<ColorSwatch> all = List.of(at(0, 11), at(0, 28), at(1, 11));
        assertEquals(List.of("0:11", "1:10", "2:11"), where(ColorSwatch.shifted(all, 0, 20, "", "\n  ")));
        assertEquals(List.of("1:11", "1:28", "2:11"), where(ColorSwatch.shifted(all, 0, 0, "", "x {}\n")));
        // Joining line 1 onto line 0 (the break at 0:40 deleted): line 1's swatch lands at column 40 + 11.
        List<ColorSwatch> joined = ColorSwatch.shifted(all, 0, 40, "\n", "");
        assertEquals(List.of("0:11", "0:28", "0:51"), where(joined));
        assertEquals(List.of("0:11", "1:7"), where(ColorSwatch.shifted(all, 0, 20, "lor: x;\nb { co", "\nab")));
    }

    @Test
    void aSwatchWhoseLiteralWasReplacedIsDropped() {
        List<ColorSwatch> all = List.of(at(0, 11), at(0, 28));
        assertEquals(List.of("0:29"), where(ColorSwatch.shifted(all, 0, 11, "red", "#00f")));
        assertEquals(List.of(), where(ColorSwatch.shifted(all, 0, 0, "a { color: red; background: lime; }", "")));
    }
}
