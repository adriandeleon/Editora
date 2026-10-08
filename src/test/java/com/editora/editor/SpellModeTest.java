package com.editora.editor;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which words of which kind of buffer are spell-checked. */
class SpellModeTest {

    @Test
    void theLanguageDecidesTheMode() {
        assertEquals(SpellMode.PROSE, SpellMode.forLanguage(null));
        assertEquals(SpellMode.PROSE, SpellMode.forLanguage(LanguageRegistry.PLAINTEXT));
        assertEquals(SpellMode.MARKDOWN, SpellMode.forLanguage("markdown"));
        assertEquals(SpellMode.HTML, SpellMode.forLanguage("html"));
        assertEquals(SpellMode.TYPST, SpellMode.forLanguage("typst"));
        assertEquals(SpellMode.CODE, SpellMode.forLanguage("java"));
        assertEquals(SpellMode.CODE, SpellMode.forLanguage("json"));
        assertEquals(SpellChecker.Syntax.HTML, SpellMode.HTML.syntax());
        assertEquals(SpellChecker.Syntax.TYPST, SpellMode.TYPST.syntax());
        assertEquals(SpellChecker.Syntax.PLAIN, SpellMode.MARKDOWN.syntax());
    }

    @Test
    void proseChecksEverythingButCodeAndLinks() {
        for (SpellMode mode : List.of(SpellMode.PROSE, SpellMode.MARKDOWN)) {
            assertTrue(mode.eligible(Set.of()));
            assertTrue(mode.eligible(Set.of("heading")));
            assertTrue(mode.eligible(Set.of("bold")));
            assertFalse(mode.eligible(Set.of("code")));
            assertFalse(mode.eligible(Set.of("link")));
        }
    }

    @Test
    void codeChecksCommentsAndStringsOnly() {
        assertTrue(SpellMode.CODE.eligible(Set.of("comment")));
        assertTrue(SpellMode.CODE.eligible(Set.of("string")));
        assertFalse(SpellMode.CODE.eligible(Set.of()));
        assertFalse(SpellMode.CODE.eligible(Set.of("keyword")));
    }

    @Test
    void markupChecksTextContentAndCommentsButNotTagsOrStrings() {
        for (SpellMode mode : List.of(SpellMode.HTML, SpellMode.TYPST)) {
            assertTrue(mode.eligible(Set.of()), "unstyled text is the document's prose");
            assertTrue(mode.eligible(Set.of("comment")));
            assertTrue(mode.eligible(Set.of("heading")));
            assertTrue(mode.eligible(Set.of("bold")));
            assertTrue(mode.eligible(Set.of("italic")));
            assertFalse(mode.eligible(Set.of("tag")));
            assertFalse(mode.eligible(Set.of("attribute")));
            assertFalse(mode.eligible(Set.of("string")), "an attribute value, or Typst math");
            assertFalse(mode.eligible(Set.of("code")));
            assertFalse(mode.eligible(Set.of("keyword")));
            assertFalse(mode.eligible(Set.of("bold", "function")));
        }
    }

    private static BitSet codeLines(SpellMode mode, String text) {
        String[] lines = text.split("\n", -1);
        return mode.codeLines(lines.length, i -> lines[i]);
    }

    private static BitSet bits(int... set) {
        BitSet b = new BitSet();
        for (int i : set) {
            b.set(i);
        }
        return b;
    }

    @Test
    void htmlScriptStyleAndPreBlocksAreCodeLines() {
        String page = "<html><head>\n" // 0
                + "<style>\n" // 1
                + ".contaner { colr: red; }\n" // 2
                + "</style>\n" // 3
                + "<SCRIPT type=\"module\">\n" // 4
                + "var helo = 1;\n" // 5
                + "</script><p>after</p>\n" // 6
                + "<p>Paragraf</p>\n" // 7
                + "<script>inline()</script>\n" // 8: opens and closes on one line
                + "<p>Text <pre>\n" // 9
                + "raw blokk\n" // 10
                + "</pre>\n" // 11
                + "<prefix>not pre</prefix> <stylesheet>\n"; // 12
        assertEquals(bits(1, 2, 3, 4, 5, 6, 8, 9, 10, 11), codeLines(SpellMode.HTML, page));
        assertEquals(bits(0, 1, 2), codeLines(SpellMode.HTML, "<script>\nnever closed\n"), "runs to the end");
    }

    @Test
    void typstInstructionLinesAreCodeLines() {
        String doc = "= Title\n" // 0
                + "#let valu = 1\n" // 1
                + "  #set text(font: \"Libertinus\")\n" // 2
                + "Prose with #emph[inline] code\n" // 3
                + "# not an instruction\n" // 4
                + "#figure(caption: [A caption])\n"; // 5
        assertEquals(bits(1, 2, 5), codeLines(SpellMode.TYPST, doc));
    }

    @Test
    void markdownFencesAreCodeLinesAndProseAndCodeHaveNone() {
        assertEquals(bits(1, 2, 3), codeLines(SpellMode.MARKDOWN, "text\n```\ncode\n```\nmore\n"));
        assertTrue(codeLines(SpellMode.PROSE, "text\n```\ncode\n```\n").isEmpty());
        assertTrue(codeLines(SpellMode.CODE, "<script>\nx\n</script>\n").isEmpty());
    }
}
