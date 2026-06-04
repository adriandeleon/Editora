package com.editora.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextInputKeymapTest {

    private static final String TEXT = "abc\n  hello\nlonger line here\n";
    //  offsets: a0 b1 c2 \n3 | (sp)4 (sp)5 h6 e7 l8 l9 o10 \n11 | l12 ... e27 \n28

    @Test
    void lineStartAndEnd() {
        assertEquals(0, TextInputKeymap.lineStart(TEXT, 2));   // within "abc"
        assertEquals(3, TextInputKeymap.lineEnd(TEXT, 1));     // end of "abc" (before \n)
        assertEquals(4, TextInputKeymap.lineStart(TEXT, 8));   // within "  hello"
        assertEquals(11, TextInputKeymap.lineEnd(TEXT, 8));    // end of "  hello"
    }

    @Test
    void lineEndAtEndOfTextHasNoNewline() {
        String t = "one\ntwo";
        assertEquals(7, TextInputKeymap.lineEnd(t, 5)); // no trailing \n → text length
    }

    @Test
    void backToIndentationSkipsLeadingWhitespace() {
        assertEquals(6, TextInputKeymap.backToIndentation(TEXT, 11)); // "  hello" → first non-space 'h'
        assertEquals(0, TextInputKeymap.backToIndentation(TEXT, 2));  // "abc" has no indent
    }

    @Test
    void lineDownKeepsColumnClampedToTargetLine() {
        // caret at column 2 of "abc" (offset 2) → column 2 of "  hello" = offset 4 + 2 = 6
        assertEquals(6, TextInputKeymap.lineDown(TEXT, 2));
        // last line → end of text
        assertEquals(TEXT.length(), TextInputKeymap.lineDown(TEXT, TEXT.length() - 1));
    }

    @Test
    void lineDownClampsWhenTargetLineShorter() {
        String t = "longer\nab"; // l0..r5 \n6 a7 b8
        // caret at column 4 of "longer" (offset 4) → "ab" has only 2 chars → clamp to its end (offset 9)
        assertEquals(9, TextInputKeymap.lineDown(t, 4));
    }

    @Test
    void lineUpKeepsColumnAndClamps() {
        // caret at column 4 of "  hello" (offset 8) → "abc" has only 3 chars → clamp to end (offset 3)
        assertEquals(3, TextInputKeymap.lineUp(TEXT, 8));
        // first line → start of text
        assertEquals(0, TextInputKeymap.lineUp(TEXT, 2));
    }
}
