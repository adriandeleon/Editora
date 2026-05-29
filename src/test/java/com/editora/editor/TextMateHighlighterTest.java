package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

class TextMateHighlighterTest {

    private static boolean hasStyle(StyleSpans<Collection<String>> spans, String style) {
        for (StyleSpan<Collection<String>> span : spans) {
            if (span.getStyle().contains(style)) {
                return true;
            }
        }
        return false;
    }

    // --- scope -> style mapping (pure logic) ---

    @Test
    void mostSpecificScopeWins() {
        assertEquals("keyword",
                TextMateHighlighter.styleForScopes(List.of("source.java", "keyword.control.java")));
        assertEquals("comment",
                TextMateHighlighter.styleForScopes(List.of("source.java", "comment.line.double-slash.java")));
        assertEquals("number",
                TextMateHighlighter.styleForScopes(List.of("source.java", "constant.numeric.java")));
        assertEquals("string",
                TextMateHighlighter.styleForScopes(List.of("source.java", "string.quoted.double.java")));
        assertEquals("function",
                TextMateHighlighter.styleForScopes(List.of("source.java", "entity.name.function.java")));
    }

    @Test
    void unmappedScopesYieldNull() {
        assertNull(TextMateHighlighter.styleForScopes(List.of("source.java", "meta.class.body.java")));
        assertNull(TextMateHighlighter.styleForScopes(List.of()));
        assertNull(TextMateHighlighter.styleForScopes(null));
    }

    // --- end-to-end tokenization through a real grammar ---

    @Test
    void emptyTextReturnsNull() {
        IGrammar java = GrammarRegistry.shared().forFileName("A.java");
        assertNotNull(java, "java grammar should load");
        assertNull(TextMateHighlighter.compute("", java));
    }

    @Test
    void javaGrammarLoadsAndStylesKeywords() {
        IGrammar java = GrammarRegistry.shared().forFileName("Foo.java");
        assertNotNull(java);
        String text = "public class Foo {\n    int x = 1;\n}\n";
        StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, java);
        assertNotNull(spans);
        assertEquals(text.length(), spans.length());
        assertTrue(hasStyle(spans, "keyword"), "expected a keyword span");
        assertTrue(hasStyle(spans, "number"), "expected a number span");
    }

    @Test
    void multiLineBlockCommentStaysComment() {
        IGrammar java = GrammarRegistry.shared().forFileName("A.java");
        assertNotNull(java);
        // The comment opens on line 1 and closes on line 3 — state must carry across lines.
        String text = "/* start\n still comment\n end */ int y;\n";
        StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, java);
        assertEquals(text.length(), spans.length());
        assertTrue(hasStyle(spans, "comment"));
    }

    @Test
    void unknownExtensionHasNoGrammar() {
        assertNull(GrammarRegistry.shared().forFileName("notes.txt"));
        assertNull(GrammarRegistry.shared().forFileName(null));
    }
}
