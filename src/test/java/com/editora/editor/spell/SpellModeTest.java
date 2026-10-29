package com.editora.editor.spell;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import com.editora.text.LanguageRegistry;
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

    /** The code-line scan over a whole snapshot (what runs off the FX thread) splits as RichTextFX does. */
    @Test
    void codeLinesOfASnapshotMatchTheParagraphScan() {
        String markdown = "prose\n```bash\nmvn test\n```\nmore prose\n";
        String[] lines = markdown.split("\n", -1);
        BitSet fromParagraphs = SpellMode.MARKDOWN.codeLines(lines.length, i -> lines[i]);
        assertEquals(fromParagraphs, SpellMode.MARKDOWN.codeLines(markdown));
        assertTrue(SpellMode.MARKDOWN.codeLines(markdown).get(2));
        assertFalse(SpellMode.MARKDOWN.codeLines(markdown).get(4));
        assertEquals(new BitSet(), SpellMode.MARKDOWN.codeLines(""), "an empty document is one prose paragraph");
        assertTrue(
                SpellMode.HTML.codeLines("<p>a</p>\n<script>\nx\n</script>\n").get(2));
        for (SpellMode mode : List.of(SpellMode.PROSE, SpellMode.CODE)) {
            assertFalse(mode.marksCodeLines());
            assertEquals(new BitSet(), mode.codeLines(markdown), mode + " has no code lines to scan for");
        }
        for (SpellMode mode : List.of(SpellMode.MARKDOWN, SpellMode.HTML, SpellMode.TYPST)) {
            assertTrue(mode.marksCodeLines());
        }
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
        // Whole lines only where the code covers them; the tag lines are skipped by column (HTML review C13).
        assertEquals(bits(2, 5, 10), codeLines(SpellMode.HTML, page));
        assertEquals(bits(1), codeLines(SpellMode.HTML, "<script>\nnever closed\n"), "runs to the end");
        SpellMode.Skips skips = SpellMode.HTML.skips(page);
        assertTrue(skips.skips(8, "<script>".length()), "inline() is code");
        assertFalse(skips.skips(6, "</script><p>".length()), "\"after\" is prose");
        assertFalse(skips.skips(12, 1), "<prefix> is not <pre>");
    }

    @Test
    void htmlSkipsByColumnReadsVisibleAttributesAndIgnoresTagsInComments() {
        String page = "<p>Use <script src=\"a.js\"></script> then reed this sentense</p>\n" // 0
                + "<!-- TODO: drop the <pre> wrapper later -->\n" // 1
                + "<p>Speling <code>cnst</code> <img alt=\"lgo\" title='Clik here' src=\"x.png\"></p>\n" // 2
                + "<input\n  placeholder=\"Serch\">\n"; // 3, 4
        SpellMode.Skips skips = SpellMode.HTML.skips(page);
        String[] lines = page.split("\n", -1);
        assertFalse(skips.skips(0, lines[0].indexOf("reed")), "prose after an inline script on its line");
        assertFalse(skips.skips(2, lines[2].indexOf("Speling")), "a <pre> in a comment opens nothing (E20)");
        assertTrue(skips.skips(2, lines[2].indexOf("cnst")), "<code> content");
        assertTrue(skips.reads(2, lines[2].indexOf("lgo")), "alt is read");
        assertTrue(skips.reads(2, lines[2].indexOf("Clik")), "title is read");
        assertFalse(skips.reads(2, lines[2].indexOf("x.png")), "src is not");
        assertTrue(skips.reads(4, lines[4].indexOf("Serch")), "an attribute on a tag's second line");
        // The value becomes words of its own: name, '=' and quotes are blanked.
        String prose = SpellMode.HTML.prose(lines[2]);
        assertEquals(lines[2].length(), prose.length());
        assertTrue(prose.contains(" lgo "), prose);
        assertTrue(prose.contains(" Clik here "), prose);
        assertTrue(prose.contains("src=\"x.png\""), "other attributes are left alone: " + prose);
        assertEquals(SpellMode.HTML, SpellMode.forLanguage("html-template"));
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

    // --- Markdown: what is not prose ----------------------------------------------------------------------

    @Test
    void frontMatterAndReferenceDefinitionsAreNotProse() {
        String md = "---\ntitle: Notes\nslug: speling-here\n---\n\nText [a][ref] and more[^n].\n\n"
                + "[ref]: https://example.com \"Titel\"\n   [Other Ref]: <my file.md>\n[^n]: A footnote is prose.\n"
                + "[not a definition] : text\n";
        BitSet code = SpellMode.MARKDOWN.codeLines(md);
        for (int line : new int[] {0, 1, 2, 3, 7, 8}) {
            assertTrue(code.get(line), "line " + line + " is skipped");
        }
        for (int line : new int[] {4, 5, 6, 9, 10}) {
            assertFalse(code.get(line), "line " + line + " is checked");
        }
        assertEquals(new BitSet(), SpellMode.MARKDOWN.codeLines("---\nno closing delimiter\n"), "not front matter");
        assertFalse(SpellMode.MARKDOWN.codeLines("text\n\n---\nkey: v\n---\n").get(3), "only at the very top");
        assertTrue(SpellMode.MARKDOWN.codeLines("---\nkey: v\n...\n").get(1), "three dots close it too");
        // a definition inside a fence is just code, and stays within the fence's own range
        assertEquals(3, SpellMode.MARKDOWN.codeLines("```\n[a]: b\n```\ntext\n").cardinality());
    }

    @Test
    void codeLinesFollowTheDocumentsBlocks() {
        String md = "prose\n~~~\ntilde code\n~~~\n````\n```\nnested\n```\n````\nprose again\n\n    indented code\n\n"
                + "<!-- a comment is prose -->\n```\nunterminated\nto the end\n";
        BitSet code = SpellMode.MARKDOWN.codeLines(md);
        for (int line : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 11, 14, 15, 16}) {
            assertTrue(code.get(line), "line " + line + " is code");
        }
        for (int line : new int[] {0, 9, 13}) {
            assertFalse(code.get(line), "line " + line + " is prose");
        }
    }

    private static String prose(String line) {
        String out = SpellMode.MARKDOWN.prose(line);
        assertEquals(line.length(), out.length(), "offsets into the line stay valid");
        return out;
    }

    /** {@code line} with each of {@code parts} (in order of appearance) replaced by as many spaces. */
    private static String blanked(String line, String... parts) {
        String out = line;
        int from = 0;
        for (String part : parts) {
            int at = out.indexOf(part, from);
            assertTrue(at >= 0, "'" + part + "' is in the line");
            out = out.substring(0, at) + " ".repeat(part.length()) + out.substring(at + part.length());
            from = at + part.length();
        }
        return out;
    }

    private static void assertBlanks(String line, String... parts) {
        assertEquals(blanked(line, parts), prose(line));
    }

    @Test
    void aLinksTargetIsBlankedSoItsTextIsAWordOfItsOwn() {
        assertBlanks("see [the text](https://example.com/path).", "(https://example.com/path)");
        assertBlanks("![alt words](img/pic.png \"A title\") end", "(img/pic.png \"A title\")");
        assertBlanks("[a](<my file.md>) and [b](x_(y).md 't')", "(<my file.md>)", "(x_(y).md 't')");
        assertBlanks("[text][labell] and [text][]", "[labell]", "[]");
        assertBlanks("[just brackets] (and parens)");
        assertBlanks("a [x] task");
    }

    @Test
    void htmlTagsAndLintDirectivesAreBlanked() {
        assertBlanks("Press <kbd>Ctrl</kbd> now", "<kbd>", "</kbd>");
        assertBlanks("<span class=\"navbarr\">words</span>", "<span class=\"navbarr\">", "</span>");
        assertBlanks("see <https://example.com> here", "<https://example.com>");
        assertBlanks("text. <!-- markdownlint-disable-line MD034 -->", "<!-- markdownlint-disable-line MD034 -->");
        assertBlanks("<!-- an ordinary comment is prose -->");
        assertBlanks("a < b and c > d");
    }

    @Test
    void onlyMarkdownIsMasked() {
        String line = "see [the text](https://example.com) <b>x</b>";
        for (SpellMode mode : List.of(SpellMode.PROSE, SpellMode.HTML, SpellMode.TYPST, SpellMode.CODE)) {
            assertEquals(line, mode.prose(line), mode.toString());
        }
        assertEquals("plain words", SpellMode.MARKDOWN.prose("plain words"));
    }
}
