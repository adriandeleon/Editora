package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A selection widened to whole lines, with the numbers those lines have in the file. */
class LineSelectionTest {

    private static final String TEXT = "one\ntwo\nthree\nfour\n";

    private static String cut(String text, LineSelection s) {
        return text.substring(s.start(), s.end());
    }

    @Test
    void aSelectionInsideOneLineTakesThatWholeLine() {
        LineSelection s = LineSelection.of(TEXT, TEXT.indexOf("wo"), TEXT.indexOf("wo") + 1);
        assertEquals("two", cut(TEXT, s));
        assertEquals(2, s.firstLine());
        assertEquals(2, s.lastLine());
    }

    @Test
    void aSelectionAcrossLinesTakesEveryLineItTouches() {
        LineSelection s = LineSelection.of(TEXT, TEXT.indexOf("o\nth"), TEXT.indexOf("ree"));
        assertEquals("two\nthree", cut(TEXT, s));
        assertEquals(2, s.firstLine());
        assertEquals(3, s.lastLine());
    }

    /** Selecting lines downwards leaves the caret at column 0 of the next line, which is not selected. */
    @Test
    void aSelectionEndingAtTheStartOfALineLeavesThatLineOut() {
        LineSelection s = LineSelection.of(TEXT, TEXT.indexOf("two"), TEXT.indexOf("four"));
        assertEquals("two\nthree", cut(TEXT, s));
        assertEquals(3, s.lastLine());
        // A lone line break selected at the end of "two" is line 2, not lines 2–3.
        LineSelection lineBreak = LineSelection.of(TEXT, TEXT.indexOf("\nthree"), TEXT.indexOf("three"));
        assertEquals("two", cut(TEXT, lineBreak));
        assertEquals(2, lineBreak.firstLine());
    }

    @Test
    void theFirstAndLastLinesAndCrLfAreHandled() {
        assertEquals("one", cut(TEXT, LineSelection.of(TEXT, 0, 1)));
        assertEquals(1, LineSelection.of(TEXT, 0, 1).firstLine());
        String noFinalBreak = "a\nb";
        LineSelection last = LineSelection.of(noFinalBreak, 2, 3);
        assertEquals("b", cut(noFinalBreak, last));
        assertEquals(2, last.firstLine());
        String crlf = "a\r\nbb\r\nc";
        LineSelection mid = LineSelection.of(crlf, crlf.indexOf("bb"), crlf.indexOf("bb") + 1);
        assertEquals("bb", cut(crlf, mid), "the carriage return is not part of the line");
        assertEquals(2, mid.firstLine());
    }

    @Test
    void anEmptyOrOutOfRangeSelectionIsNothing() {
        assertNull(LineSelection.of(TEXT, 3, 3));
        assertNull(LineSelection.of(TEXT, 5, 2));
        assertNull(LineSelection.of(TEXT, 0, TEXT.length() + 1));
        assertNull(LineSelection.of(null, 0, 1));
    }
}
