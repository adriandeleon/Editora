package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {

    private static boolean hasStyle(StyleSpans<Collection<String>> spans, String style) {
        for (StyleSpan<Collection<String>> span : spans) {
            if (span.getStyle().contains(style)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void emptyTextReturnsNull() {
        assertNull(SyntaxHighlighter.compute("", LanguageRegistry.plaintext()));
    }

    @Test
    void spansCoverEntireText() {
        String text = "public class Foo {}";
        StyleSpans<Collection<String>> spans =
                SyntaxHighlighter.compute(text, LanguageRegistry.forFileName("Foo.java"));
        assertEquals(text.length(), spans.length());
    }

    @Test
    void javaKeywordsAreStyled() {
        StyleSpans<Collection<String>> spans =
                SyntaxHighlighter.compute("public int x = 1;", LanguageRegistry.forFileName("A.java"));
        assertTrue(hasStyle(spans, "keyword"));
        assertTrue(hasStyle(spans, "number"));
    }

    @Test
    void jsonStringsAndPunctuationStyled() {
        StyleSpans<Collection<String>> spans =
                SyntaxHighlighter.compute("{\"k\": true}", LanguageRegistry.forFileName("a.json"));
        assertTrue(hasStyle(spans, "string"));
        assertTrue(hasStyle(spans, "keyword")); // true literal
        assertTrue(hasStyle(spans, "punct"));
    }

    @Test
    void plaintextHasNoStyledSpans() {
        StyleSpans<Collection<String>> spans =
                SyntaxHighlighter.compute("just words", LanguageRegistry.forFileName("notes.txt"));
        assertEquals("plaintext", LanguageRegistry.forFileName("notes.txt").name());
        for (StyleSpan<Collection<String>> span : spans) {
            assertTrue(span.getStyle().isEmpty());
        }
    }
}
