package com.editora.editor;

import java.util.List;

import com.editora.editor.MathSpans.Span;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure LaTeX math span detector. */
class MathSpansTest {

    @Test
    void inlineMath() {
        List<Span> s = MathSpans.find("the value $a^2 + b^2$ is known");
        assertEquals(1, s.size());
        assertEquals("a^2 + b^2", s.get(0).latex());
        assertFalse(s.get(0).display());
    }

    @Test
    void displayMath() {
        List<Span> s = MathSpans.find("$$E = mc^2$$");
        assertEquals(1, s.size());
        assertEquals("E = mc^2", s.get(0).latex());
        assertTrue(s.get(0).display());
        assertEquals(0, s.get(0).start());
        assertEquals(12, s.get(0).end());
    }

    @Test
    void doesNotEatCurrencyProse() {
        // "$5 and $10" — closer followed by a digit, opener followed by digit but closer rule blocks it.
        assertTrue(MathSpans.find("It costs $5 and $10 total").isEmpty());
        assertTrue(MathSpans.find("Prices: $3.50, $4.00").isEmpty());
    }

    @Test
    void requiresNonSpaceAtEdges() {
        assertTrue(MathSpans.find("a $ x $ b").isEmpty()); // spaces just inside the delimiters
        assertTrue(MathSpans.find("$x$").get(0).latex().equals("x"));
    }

    @Test
    void escapedDollarIsLiteral() {
        assertTrue(MathSpans.find("price is \\$5 today").isEmpty());
    }

    @Test
    void inlineMathDoesNotSpanNewline() {
        assertTrue(MathSpans.find("$a\nb$").isEmpty());
    }

    @Test
    void segmentsSplitTextAndMath() {
        List<MathSpans.Segment> segs = MathSpans.segments("pre $x$ post");
        assertEquals(3, segs.size());
        assertEquals("pre ", segs.get(0).text());
        assertEquals("x", segs.get(1).span().latex());
        assertEquals(" post", segs.get(2).text());
    }
}
