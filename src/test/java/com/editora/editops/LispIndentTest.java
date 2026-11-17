package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LispIndent}: the indent of the line after a point in a Lisp (Clojure) file, past strings, comments,
 * character literals and deep nesting. {@code IndenterTest} covers the everyday forms through Enter.
 */
class LispIndentTest {

    private static String after(String text) {
        return LispIndent.indentAfter(text, text.length());
    }

    /** The indent of a line that follows {@code above} (which ends in a line break) and has text of its own. */
    private static String lineAfter(String above) {
        return LispIndent.indentAfter(above + "x", above.length());
    }

    @Test
    void aParenInAStringOrAfterAnEscapedQuoteDoesNotCount() {
        assertEquals("   ", lineAfter("(f \"a\\\"(\"\n"), "aligned with the string, its ( ignored");
    }

    @Test
    void aCommentRunsToItsLineEndOrToThePoint() {
        assertEquals("  ", after("(f ; note ("), "a comment to the end of the text");
        assertEquals("  ", LispIndent.indentAfter("(f ; note\n(", 6), "a comment going on past the point");
        assertEquals("  ", lineAfter("(f ; c (\n"), "the comment ends at its line; its ( did not open");
    }

    @Test
    void deepNestingAlignsWithTheInnermostForm() {
        String deep = "(".repeat(20) + "x";
        assertEquals(" ".repeat(21), after(deep), "twenty levels: two past the innermost paren");
    }

    @Test
    void aStrayCloserAtTopLevelIsIgnored() {
        assertEquals("    ", after(")(f x"), "aligned with x as if the ) were not there");
    }

    @Test
    void aFormWithNoOperatorLinesUpWithItsFirstElementOrGoesTwoIn() {
        assertEquals("  ", after("("), "a bare paren");
        assertEquals("  ", lineAfter("(\n"), "nothing on its line");
        assertEquals("  ", after("( x"), "a space before the first element");
        assertEquals(" ", lineAfter("((f x)\n"), "a form that starts with a form: aligned with it");
    }

    @Test
    void theFirstArgumentIsFoundPastTabsAndCommasButNotAtALineEnd() {
        assertEquals("    \t ", lineAfter("(foo\t,bar\n"), "the tab is kept, so the column matches");
        assertEquals("  ", lineAfter("(foo\r\n"), "a CRLF line end: no argument on the line");
        assertEquals("    ", after("(f, x"), "a comma is whitespace in Clojure");
        assertEquals("\t     ", after("\t(foo bar"), "the line's own tab is kept");
    }

    @Test
    void theOperatorEndsAtABracketAStringOrAComment() {
        assertEquals("   ", after("(ff[1] x"), "aligned with the vector that follows the operator");
        assertEquals("   ", after("(ff{} x"));
        assertEquals("   ", after("(ff\"s\" x"));
        assertEquals("  ", lineAfter("(ff;c\n"), "a comment right after it: no argument");
    }

    @Test
    void aBackquotedListIsData() {
        assertEquals("  ", after("`(a b"), "aligned past its paren, not as a call");
    }

    @Test
    void bodyFormsAreKnownByNameWhateverTheirNamespace() {
        assertTrue(LispIndent.isBodyForm("s/def"));
        assertTrue(LispIndent.isBodyForm("clojure.core/when"));
        assertTrue(LispIndent.isBodyForm("with-open"));
        assertTrue(LispIndent.isBodyForm("if-some"));
        assertTrue(LispIndent.isBodyForm("deftest"));
        assertFalse(LispIndent.isBodyForm("str"));
        assertFalse(LispIndent.isBodyForm("foo/"), "a trailing slash: the whole name, no form");
    }

    @Test
    void theScanStartsAtTheNearestLineThatOpensATopLevelForm() {
        String text = "\"oops\n(defn f\n  y";
        int line = text.lastIndexOf('\n') + 1;
        assertEquals("  ", LispIndent.indentAfter(text, line), "an unclosed string above that form is not read");
        String closed = "x\n(a b)\n(c";
        assertEquals("", LispIndent.indentAfter(closed, closed.lastIndexOf('(')), "the form above is closed");
    }
}
