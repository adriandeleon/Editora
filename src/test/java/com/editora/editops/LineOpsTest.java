package com.editora.editops;

import com.editora.editops.LineOps.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LineOpsTest {

    /** Applies an Edit and re-inserts a {@code |} at the resulting caret, like TransposerTest. */
    private static String apply(String text, Edit e) {
        if (e == null) {
            return null;
        }
        String out = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return out.substring(0, e.caret()) + "|" + out.substring(e.caret());
    }

    private static String dup(String withCaret) {
        int caret = withCaret.indexOf('|');
        String text = withCaret.replace("|", "");
        return apply(text, LineOps.duplicateLine(text, caret));
    }

    private static String up(String withCaret) {
        int caret = withCaret.indexOf('|');
        String text = withCaret.replace("|", "");
        return apply(text, LineOps.moveLineUp(text, caret));
    }

    private static String down(String withCaret) {
        int caret = withCaret.indexOf('|');
        String text = withCaret.replace("|", "");
        return apply(text, LineOps.moveLineDown(text, caret));
    }

    // --- duplicate ---

    @Test
    void duplicateMiddleLineKeepsColumnOnCopy() {
        assertEquals("ab\na|b\nc", dup("a|b\nc"));
    }

    @Test
    void duplicateLastLineNoTrailingNewline() {
        assertEquals("a\nb\n|b", dup("a\n|b"));
    }

    @Test
    void duplicateEmptyLine() {
        assertEquals("\n|\nx", dup("|\nx"));
    }

    // --- move up ---

    @Test
    void moveUpSwapsWithPreviousKeepingColumn() {
        assertEquals("tw|o\none\nthree", up("one\ntw|o\nthree"));
    }

    @Test
    void moveUpLastLineNoTrailingNewline() {
        assertEquals("tw|o\none", up("one\ntw|o"));
    }

    @Test
    void moveUpNoOpOnFirstLine() {
        assertNull(LineOps.moveLineUp("one\ntwo", 1));
    }

    // --- move down ---

    @Test
    void moveDownSwapsWithNextKeepingColumn() {
        assertEquals("two\non|e\nthree", down("on|e\ntwo\nthree"));
    }

    @Test
    void moveDownIntoLastLineNoTrailingNewline() {
        assertEquals("two\non|e", down("on|e\ntwo"));
    }

    @Test
    void moveDownNoOpOnLastLine() {
        assertNull(LineOps.moveLineDown("one\ntwo", 5));
    }
}
