package com.editora.editor;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (no-toolkit) tests for the Markdown parse step — they validate that commonmark core + the GFM
 * extensions (tables, task lists, strikethrough, autolink) resolve on the module path and produce the
 * expected AST node types. Node→JavaFX rendering needs the FX thread, so it isn't unit-tested here.
 */
class MarkdownRendererTest {

    @Test
    void tableColumnWeightReservesSpaceForCellPadding() {
        assertEquals(8, MarkdownRenderer.tableColumnWeight(5), "ID and Smoke-sized columns");
        assertEquals(11, MarkdownRenderer.tableColumnWeight(8), "Priority-sized columns");
        assertEquals(40, MarkdownRenderer.tableColumnWeight(100), "long cells remain bounded");
    }

    @Test
    void htmlCommentsAreRecognizedAsInvisible() {
        assertTrue(MarkdownRenderer.isHtmlComment("<!-- hidden -->"));
        assertTrue(MarkdownRenderer.isHtmlComment("   <!--\nmulti\nline\n-->"));
        assertFalse(MarkdownRenderer.isHtmlComment("<div>shown</div>"));
        assertFalse(MarkdownRenderer.isHtmlComment(""));
        assertFalse(MarkdownRenderer.isHtmlComment(null));
    }

    private static final String SAMPLE = """
            # Heading

            A paragraph with ~~struck~~ text and a bare URL https://example.com here.

            - [x] done
            - [ ] todo

            | A | B |
            |---|---|
            | 1 | 2 |
            """;

    private static class Counter extends AbstractVisitor {
        boolean heading;
        boolean table;
        boolean checkedTask;
        boolean strikethrough;
        boolean link;

        @Override
        public void visit(Heading h) {
            heading = true;
            super.visit(h);
        }

        @Override
        public void visit(Link l) {
            link = true; // autolink turns the bare URL into a Link
            super.visit(l);
        }

        @Override
        protected void visitChildren(Node parent) {
            if (parent instanceof TableBlock) {
                table = true;
            } else if (parent instanceof Strikethrough) {
                strikethrough = true;
            } else if (parent instanceof TaskListItemMarker m && m.isChecked()) {
                checkedTask = true;
            }
            super.visitChildren(parent);
        }
    }

    @Test
    void parsesCommonMarkPlusGfmExtensions() {
        Node doc = MarkdownRenderer.parseToDocument(SAMPLE);
        Counter c = new Counter();
        doc.accept(c);
        assertTrue(c.heading, "expected a Heading");
        assertTrue(c.table, "expected a GFM TableBlock");
        assertTrue(c.checkedTask, "expected a checked TaskListItemMarker");
        assertTrue(c.strikethrough, "expected a Strikethrough");
        assertTrue(c.link, "expected an autolinked Link");
    }

    @Test
    void plainTextStripsMarkupKeepsVisibleText() {
        String t = MarkdownRenderer.plainText(SAMPLE);
        // visible text is kept
        assertTrue(t.contains("Heading"), "heading text");
        assertTrue(t.contains("struck"), "strikethrough content");
        assertTrue(t.contains("done") && t.contains("todo"), "task item text");
        assertTrue(t.contains("https://example.com"), "link text");
        // GFM table cell text survives (the tables extension renders to plain text too)
        assertTrue(t.contains("1") && t.contains("2"), "table cell text");
        // markup is removed
        assertFalse(t.contains("#"), "no heading hashes");
        assertFalse(t.contains("~~"), "no strikethrough markers");
        assertFalse(t.contains("---"), "no table delimiter row");
    }

    @Test
    void multiLineDisplayMathIsRecognized() {
        // $$ on its own line, content, then $$ — commonmark inserts SoftLineBreaks; paragraphText must
        // span them so the paragraph is recognized as a sole $$…$$ display block (regression: it bailed
        // on the first SoftLineBreak, so only single-line $$…$$ rendered).
        org.commonmark.node.Node doc = MarkdownRenderer.parseToDocument("$$\n\\int_0^1 x^2 dx\n$$");
        org.commonmark.node.Paragraph p = (org.commonmark.node.Paragraph) doc.getFirstChild();
        String latex = MarkdownRenderer.soleDisplayMath(MarkdownRenderer.paragraphText(p));
        assertTrue(latex != null && latex.contains("\\int_0^1 x^2 dx"), "multi-line $$ block, got: " + latex);

        // single-line form still works
        org.commonmark.node.Node doc2 = MarkdownRenderer.parseToDocument("$$ a^2 + b^2 = c^2 $$");
        org.commonmark.node.Paragraph p2 = (org.commonmark.node.Paragraph) doc2.getFirstChild();
        assertTrue(MarkdownRenderer.soleDisplayMath(MarkdownRenderer.paragraphText(p2)) != null, "single-line $$");

        // a normal paragraph is NOT display math
        org.commonmark.node.Node doc3 = MarkdownRenderer.parseToDocument("just some prose here");
        org.commonmark.node.Paragraph p3 = (org.commonmark.node.Paragraph) doc3.getFirstChild();
        assertFalse(MarkdownRenderer.soleDisplayMath(MarkdownRenderer.paragraphText(p3)) != null, "prose");
    }

    @Test
    void fenceInfoResolvesGrammar() {
        assertNotNull(MarkdownRenderer.grammarForInfo("java"), "language name");
        assertNotNull(MarkdownRenderer.grammarForInfo("JS"), "alias → extension, case-insensitive");
        assertNotNull(MarkdownRenderer.grammarForInfo("python title=foo"), "first token only");
        assertNull(MarkdownRenderer.grammarForInfo("no-such-language-xyz"), "unknown language");
        assertNull(MarkdownRenderer.grammarForInfo(""), "blank");
        assertNull(MarkdownRenderer.grammarForInfo(null), "null");
    }

    @Test
    void codeFenceTokenizerProducesStyledRuns() {
        var grammar = MarkdownRenderer.grammarForInfo("java");
        assertNotNull(grammar);
        var runs = MarkdownRenderer.tokenizeRuns("public class X { void main() {} }", grammar);
        assertNotNull(runs, "tokenization succeeds");
        assertFalse(runs.isEmpty(), "produced runs");
        assertTrue(
                runs.stream().anyMatch(r -> !r.classes().isEmpty()),
                "at least one run carries a token style class (e.g. keyword)");
        // The runs reconstruct the original text exactly.
        String joined = runs.stream().map(MarkdownRenderer.Run::text).reduce("", String::concat);
        assertTrue(joined.equals("public class X { void main() {} }"), "runs cover the whole input");
    }

    // --- image policy: which surfaces may load which images ---

    @Test
    void theDocumentPolicyAllowsWhatThePreviewAlwaysLoaded() {
        MarkdownRenderer.ImagePolicy doc = MarkdownRenderer.ImagePolicy.DOCUMENT;
        assertTrue(doc.allows("https://img.shields.io/badge/a-b-green"));
        assertTrue(doc.allows("file:///home/u/notes/pic.png"));
        assertTrue(doc.allows("data:image/png;base64,AAAA"));
        assertFalse(doc.allows(null));
        assertFalse(doc.allows(" "));
    }

    @Test
    void theDataOnlyPolicyAllowsNothingThatLeavesTheMessage() {
        MarkdownRenderer.ImagePolicy untrusted = MarkdownRenderer.ImagePolicy.DATA_ONLY;
        assertTrue(untrusted.allows("data:image/png;base64,AAAA"));
        assertTrue(untrusted.allows("DATA:image/svg+xml,%3Csvg/%3E"));
        assertFalse(untrusted.allows("https://attacker.example/pixel.png?d=SECRET"), "an exfiltration beacon");
        assertFalse(untrusted.allows("http://169.254.169.254/latest/meta-data"));
        assertFalse(untrusted.allows("file:///etc/passwd"));
        assertFalse(untrusted.allows("//attacker.example/x.png"));
        assertFalse(untrusted.allows("datafile.png"), "only the data: scheme, not a name that starts like it");
        assertFalse(untrusted.allows(null));
    }

    @Test
    void aBlockedImagePlaceholderNamesTheAltTextAndTheUrl() {
        assertEquals("[image]", MarkdownRenderer.imagePlaceholder("", null));
        assertEquals("[image: logo]", MarkdownRenderer.imagePlaceholder("logo", null));
        assertEquals(
                "[image: logo] https://h.example/a.png",
                MarkdownRenderer.imagePlaceholder("logo", "https://h.example/a.png"));
        assertEquals(
                "[image] https://h.example/a.png", MarkdownRenderer.imagePlaceholder(" ", " https://h.example/a.png "));
        String shown = MarkdownRenderer.imagePlaceholder("x", "https://h.example/?d=" + "A".repeat(5000));
        assertTrue(shown.length() < 130 && shown.endsWith("…"), "a kilobyte query string is cut: " + shown.length());
    }

    /** Print cuts a code block between lines, so token runs are regrouped by line — pure. */
    @Test
    void codeLinesRegroupsRunsByLine() {
        java.util.List<MarkdownRenderer.Run> runs = java.util.List.of(
                new MarkdownRenderer.Run("int", java.util.List.of("keyword")),
                new MarkdownRenderer.Run(" a;\r\n\n/* b\nc */", java.util.List.of("comment")),
                new MarkdownRenderer.Run("\n", java.util.List.of()));
        java.util.List<java.util.List<MarkdownRenderer.Run>> lines = MarkdownRenderer.codeLines(runs);
        assertEquals(5, lines.size(), "four line breaks make five lines");
        assertEquals(
                java.util.List.of(
                        new MarkdownRenderer.Run("int", java.util.List.of("keyword")),
                        new MarkdownRenderer.Run(" a;", java.util.List.of("comment"))),
                lines.get(0),
                "a run is cut at the line break, the CR of a CRLF dropped, its classes kept");
        assertEquals(" ", lines.get(1).get(0).text(), "an empty line keeps a space so it has a line's height");
        assertEquals(
                java.util.List.of("comment"), lines.get(2).get(0).classes(), "a multi-line token colours each line");
        assertEquals("c */", lines.get(3).get(0).text());
        assertEquals(" ", lines.get(4).get(0).text());
    }

    /** Print cuts long inline code into pieces the line can break between — pure. */
    @Test
    void longInlineCodeIsCutIntoPiecesForPrint() {
        assertEquals(java.util.List.of("mvn test"), MarkdownRenderer.codeChunks("mvn test"), "short code is one pill");
        assertEquals(java.util.List.of(""), MarkdownRenderer.codeChunks(null));
        String command = "npm install -g @mermaid-js/mermaid-cli --registry=https://registry.example.org/";
        java.util.List<String> chunks = MarkdownRenderer.codeChunks(command);
        assertEquals(command, String.join("", chunks), "nothing is lost or added");
        assertTrue(chunks.size() > 4, "cut into several: " + chunks);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= MarkdownRenderer.CODE_CHUNK, "no piece over the limit: '" + chunk + "'");
        }
        assertEquals("npm install -g ", chunks.get(0), "cut after a space where there is one");
        assertEquals("@mermaid-js/", chunks.get(1), "then after punctuation");
        java.util.List<String> unbroken = MarkdownRenderer.codeChunks("A".repeat(40));
        assertEquals(java.util.List.of("A".repeat(16), "A".repeat(16), "A".repeat(8)), unbroken, "then anywhere");
        String astral = "x".repeat(15) + "\uD83D\uDE00" + "y".repeat(10);
        for (String chunk : MarkdownRenderer.codeChunks(astral)) {
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)), "a surrogate pair is not cut");
        }
    }

    /** What a printed link adds after its text so the address survives on paper — pure. */
    @Test
    void aPrintedLinkNamesItsDestinationUnlessTheTextAlreadyDoes() {
        assertEquals(
                "https://example.org/docs", MarkdownRenderer.printedLinkTarget("https://example.org/docs", "the docs"));
        assertEquals("someone@example.org", MarkdownRenderer.printedLinkTarget("mailto:someone@example.org", "write"));
        assertNull(MarkdownRenderer.printedLinkTarget("#details", "below"), "an in-document anchor");
        assertNull(MarkdownRenderer.printedLinkTarget("docs/README.md", "a relative file"));
        assertNull(MarkdownRenderer.printedLinkTarget("https://example.org/x", "https://example.org/x"), "an autolink");
        assertNull(
                MarkdownRenderer.printedLinkTarget("https://Example.org/", "example.org"), "the text is the address");
        assertNull(MarkdownRenderer.printedLinkTarget("mailto:a@b.example", "a@b.example"));
        assertNull(MarkdownRenderer.printedLinkTarget("https://example.org/badge", " "), "a linked image: no text");
        assertNull(MarkdownRenderer.printedLinkTarget(null, "x"));
    }
}
