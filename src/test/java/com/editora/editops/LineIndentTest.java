package com.editora.editops;

import java.util.List;

import com.editora.editor.LspTextEdit;
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
        assertNull(LineIndent.formattedIndent("x", List.of(new LspTextEdit(LINE - 1, 0, LINE - 1, 0, "  ")), LINE));
    }

    @Test
    void leadingWhitespaceHelper() {
        assertEquals("   ", LineIndent.leadingWhitespace("   abc"));
        assertEquals("\t ", LineIndent.leadingWhitespace("\t abc"));
        assertEquals("", LineIndent.leadingWhitespace("abc"));
    }
}
