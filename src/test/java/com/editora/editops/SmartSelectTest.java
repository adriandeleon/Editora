package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartSelectTest {

    /** The substring a range selects, for readable assertions. */
    private static String sel(String text, int[] r) {
        return r == null ? null : text.substring(r[0], r[1]);
    }

    /** Expands from a bare caret at {@code caret}, then repeatedly, collecting the selected substrings. */
    private static List<String> ladder(String text, int caret, int steps) {
        List<String> out = new ArrayList<>();
        int s = caret;
        int e = caret;
        for (int i = 0; i < steps; i++) {
            int[] r = SmartSelect.expand(text, s, e);
            if (r == null) {
                out.add(null);
                break;
            }
            out.add(sel(text, r));
            s = r[0];
            e = r[1];
        }
        return out;
    }

    // --- each expand strictly grows ---

    @Test
    void everyExpandStrictlyContainsThePrevious() {
        String text = "int main() {\n    return foo(bar, baz);\n}\n";
        int caret = text.indexOf("bar");
        int s = caret;
        int e = caret;
        int prevSpan = -1;
        for (int i = 0; i < 20; i++) {
            int[] r = SmartSelect.expand(text, s, e);
            if (r == null) {
                break;
            }
            assertTrue(r[0] <= s && r[1] >= e && (r[0] < s || r[1] > e), "must strictly contain the previous range");
            int span = r[1] - r[0];
            assertTrue(span > prevSpan, "each step must grow");
            prevSpan = span;
            s = r[0];
            e = r[1];
        }
        assertEquals(text.length(), e - s, "the ladder ends at the whole document");
    }

    @Test
    void wholeDocumentReturnsNull() {
        String text = "abc";
        assertNull(SmartSelect.expand(text, 0, 3));
    }

    // --- the word step ---

    @Test
    void firstExpandSelectsTheWordUnderTheCaret() {
        String text = "foo bar baz";
        int caret = text.indexOf("bar") + 1; // inside "bar"
        assertEquals("bar", sel(text, SmartSelect.expand(text, caret, caret)));
    }

    @Test
    void caretAdjacentToAWordSelectsThatWord() {
        String text = "foo.bar";
        int caret = 4; // between '.' and 'bar'
        assertEquals("bar", sel(text, SmartSelect.expand(text, caret, caret)));
    }

    @Test
    void caretInWhitespaceSelectsTheLine() {
        String text = "    foo\n";
        int caret = 2; // in the leading spaces
        // the trimmed content ("foo") doesn't contain a caret sitting in the indent, so the first range
        // that strictly contains the caret is the whole line
        assertEquals("    foo", sel(text, SmartSelect.expand(text, caret, caret)));
    }

    // --- brackets ---

    @Test
    void expandsThroughBracketContentsThenDelimiters() {
        String text = "foo(bar)";
        int caret = text.indexOf("bar") + 1;
        List<String> steps = ladder(text, caret, 4);
        assertEquals("bar", steps.get(0)); // word (== bracket contents, so contents is skipped)
        assertEquals("(bar)", steps.get(1)); // the delimiters included
        // then the whole line / document
        assertTrue(steps.get(2).contains("foo(bar)"));
    }

    @Test
    void multiArgumentCallStepsContentsThenParens() {
        String text = "call(a, b, c)";
        int caret = text.indexOf('b');
        List<String> steps = ladder(text, caret, 4);
        assertEquals("b", steps.get(0));
        assertEquals("a, b, c", steps.get(1)); // bracket contents
        assertEquals("(a, b, c)", steps.get(2)); // with the parens
    }

    @Test
    void nestedBracketsExpandInnermostFirst() {
        String text = "f(g(x))";
        int caret = text.indexOf('x');
        List<String> steps = ladder(text, caret, 6);
        assertEquals("x", steps.get(0));
        assertEquals("(x)", steps.get(1)); // inner parens (contents "x" == word, skipped)
        assertEquals("g(x)", steps.get(2)); // outer contents
        assertEquals("(g(x))", steps.get(3)); // outer parens
    }

    @Test
    void mismatchedBracketsDoNotDerailTheScan() {
        // a stray ']' before the real pair must not swallow the enclosing '(' … ')'
        String text = "a] b(c)";
        int caret = text.indexOf('c');
        List<String> steps = ladder(text, caret, 3);
        assertEquals("c", steps.get(0));
        assertEquals("(c)", steps.get(1));
    }

    // --- quotes ---

    @Test
    void expandsThroughStringContentsThenQuotes() {
        String text = "x = \"hello world\";";
        int caret = text.indexOf("world");
        List<String> steps = ladder(text, caret, 4);
        assertEquals("world", steps.get(0));
        assertEquals("hello world", steps.get(1)); // string contents
        assertEquals("\"hello world\"", steps.get(2)); // with the quotes
    }

    @Test
    void quotesOnlyPairWithinTheLine() {
        // the ' in "don't" is on the code line; a lone quote must not pair across the newline
        String text = "a = 'x'\nb = 2\n";
        int caret = text.indexOf('x');
        assertEquals("x", sel(text, SmartSelect.expand(text, caret, caret)));
        assertEquals("'x'", sel(text, SmartSelect.expand(text, text.indexOf('x'), text.indexOf('x') + 1)));
    }

    // --- lines ---

    @Test
    void lineTrimmedThenWholeLine() {
        String text = "    return x;\n";
        int caret = text.indexOf('x');
        List<String> steps = ladder(text, caret, 5);
        assertEquals("x", steps.get(0));
        // no brackets/quotes here → next is the trimmed line, then the full line (with indent)
        assertEquals("return x;", steps.get(1));
        assertEquals("    return x;", steps.get(2));
    }

    // --- paragraph / document ---

    @Test
    void expandsToParagraphThenDocument() {
        String text = "one two\nthree four\n\nlater\n";
        int caret = text.indexOf("three");
        int s = caret;
        int e = caret;
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int[] r = SmartSelect.expand(text, s, e);
            if (r == null) {
                break;
            }
            seen.add(sel(text, r));
            s = r[0];
            e = r[1];
        }
        assertTrue(seen.contains("one two\nthree four"), "a blank-line paragraph is a step: " + seen);
        assertEquals(text, sel(text, new int[] {s, e}), "ends at the whole document");
    }

    // --- robustness ---

    @Test
    void emptyDocumentAndOutOfRangeAreSafe() {
        assertNull(SmartSelect.expand("", 0, 0));
        // out-of-range offsets are clamped, not thrown — 5,9 → a caret at end-of-doc, which expands to the doc
        assertEquals("ab", sel("ab", SmartSelect.expand("ab", 5, 9)));
        int[] r = SmartSelect.expand("ab", -3, 1);
        assertEquals("ab", sel("ab", r));
    }

    @Test
    void reversedSelectionIsNormalised() {
        String text = "call(a, b, c)";
        int a = text.indexOf("a,"); // the argument 'a', not the one in "call"
        // an anchor-after-caret selection of "a" must still expand to the bracket contents
        assertEquals("a, b, c", sel(text, SmartSelect.expand(text, a + 1, a)));
    }
}
