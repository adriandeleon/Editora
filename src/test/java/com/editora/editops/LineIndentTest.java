package com.editora.editops;

import java.util.List;

import com.editora.text.LspTextEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LineIndentTest {

    private static final int LINE = 3;

    @Test
    void noEditsKeepsCurrentIndent() {
        assertEquals("\t\t", LineIndent.formattedIndent("\t\tx", List.of(), LINE));
        assertEquals("  ", LineIndent.formattedIndent("  foo", null, LINE));
    }

    @Test
    void insertedIndentIsAdopted() {
        // Formatter inserts 4 spaces at column 0 of an unindented line.
        var edits = List.of(new LspTextEdit(LINE, 0, LINE, 0, "    "));
        assertEquals("    ", LineIndent.formattedIndent("x", edits, LINE));
    }

    @Test
    void replacedIndentIsAdopted() {
        // Formatter replaces 6 leading spaces with 2.
        var edits = List.of(new LspTextEdit(LINE, 0, LINE, 6, "  "));
        assertEquals("  ", LineIndent.formattedIndent("      x", edits, LINE));
    }

    @Test
    void intraLineEditDoesNotChangeIndent() {
        // Formatter fixes spacing inside the line ("( )" → "()") but not the indentation.
        var edits = List.of(new LspTextEdit(LINE, 5, LINE, 8, "()"));
        assertEquals("  ", LineIndent.formattedIndent("  foo( )", edits, LINE));
    }

    @Test
    void multiLineOrOffLineEditsAreUnusable() {
        assertNull(LineIndent.formattedIndent("x", List.of(new LspTextEdit(LINE, 0, LINE, 0, "\n  ")), LINE));
        assertNull(LineIndent.formattedIndent("x", List.of(new LspTextEdit(LINE, 0, LINE + 1, 0, "  ")), LINE));
        assertNull(LineIndent.formattedIndent("x", List.of(new LspTextEdit(LINE - 2, 0, LINE - 2, 0, "  ")), LINE));
        assertNull(
                LineIndent.formattedIndent("  x", List.of(new LspTextEdit(LINE - 1, 4, LINE, 0, "")), LINE),
                "joins the lines");
        assertNull(
                LineIndent.formattedIndent("  x", List.of(new LspTextEdit(LINE - 1, 4, LINE, 3, "\n  ")), LINE),
                "eats past the indent");
    }

    /**
     * lemminx answers a range from the previous line's end with one edit replacing the break and the indent,
     * plus in-line edits elsewhere; and nothing at all for a one-line range (XML review B4).
     */
    @Test
    void anIndentGivenFromThePreviousLinesEndIsAdopted() {
        // `  <a>` then `<b>1</b>` at column 0: lemminx's {prev:5 → this:0, "\n    "}
        assertEquals(
                "    ",
                LineIndent.formattedIndent("<b>1</b>", List.of(new LspTextEdit(LINE - 1, 5, LINE, 0, "\n    ")), LINE));
        // over-indented, with the previous line's trailing blanks trimmed and an in-line edit further right
        assertEquals(
                "  ",
                LineIndent.formattedIndent(
                        "        <c x=\"1\"   y=\"2\"/>",
                        List.of(
                                new LspTextEdit(LINE - 1, 1, LINE - 1, 2, ""),
                                new LspTextEdit(LINE - 1, 6, LINE, 8, "\n  "),
                                new LspTextEdit(LINE, 15, LINE, 18, " ")),
                        LINE));
        assertNull(
                LineIndent.formattedIndent(
                        "  x",
                        List.of(
                                new LspTextEdit(LINE - 1, 4, LINE, 2, "\n "),
                                new LspTextEdit(LINE - 1, 4, LINE, 2, "\n ")),
                        LINE),
                "two of them contradict");
    }

    @Test
    void leadingWhitespaceHelper() {
        assertEquals("   ", LineIndent.leadingWhitespace("   abc"));
        assertEquals("\t ", LineIndent.leadingWhitespace("\t abc"));
        assertEquals("", LineIndent.leadingWhitespace("abc"));
    }
}
