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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (no-toolkit) tests for the Markdown parse step — they validate that commonmark core + the GFM
 * extensions (tables, task lists, strikethrough, autolink) resolve on the module path and produce the
 * expected AST node types. Node→JavaFX rendering needs the FX thread, so it isn't unit-tested here.
 */
class MarkdownRendererTest {

    @org.junit.jupiter.api.BeforeAll
    static void catalog() {
        com.editora.i18n.Messages.init("en"); // the image placeholder is a catalog string
    }

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
        // $$ on its own line, content, then $$ is one display formula, with its LaTeX untouched: "\\," used
        // to reach the renderer as a comma and "\\\\" as one backslash, because math was looked for in text
        // CommonMark had already unescaped.
        org.commonmark.node.Node doc = MarkdownRenderer.parseForRender("$$\n\\int_0^1 x^2 \\, dx\n$$", true);
        com.editora.markdown.MarkdownMath.Block block = (com.editora.markdown.MarkdownMath.Block) doc.getFirstChild();
        assertEquals("\\int_0^1 x^2 \\, dx", block.getLatex());

        // single-line form still works
        org.commonmark.node.Node doc2 = MarkdownRenderer.parseForRender("$$ a^2 + b^2 = c^2 $$", true);
        assertTrue(doc2.getFirstChild() instanceof com.editora.markdown.MarkdownMath.Block, "single-line $$");

        // a normal paragraph is NOT display math, and with the feature off dollars are text
        assertTrue(
                MarkdownRenderer.parseForRender("just some prose here", true).getFirstChild()
                        instanceof org.commonmark.node.Paragraph);
        org.commonmark.node.Node off = MarkdownRenderer.parseForRender("a $x$ b", false);
        assertTrue(off.getFirstChild().getFirstChild() instanceof org.commonmark.node.Text, "math off: plain text");
        assertNull(off.getFirstChild().getFirstChild().getNext());
    }

    @Test
    void theRenderParseReadsInlineFootnotesAndRecordsBlockLines() {
        org.commonmark.node.Node doc = MarkdownRenderer.parseForRender("# T\n\ntext^[inline note] end\n", false);
        org.commonmark.node.Node para = doc.getLastChild();
        boolean found = false;
        for (org.commonmark.node.Node c = para.getFirstChild(); c != null; c = c.getNext()) {
            found |= c instanceof org.commonmark.ext.footnotes.InlineFootnote;
        }
        assertTrue(found, "^[…] is a footnote, not literal text (samples/markdown/extras.md)");
        assertEquals(2, para.getSourceSpans().get(0).getLineIndex());
    }

    @Test
    void theRenderParseKnowsTheExtendedSyntax() {
        org.commonmark.node.Node alert = MarkdownRenderer.parseForRender("> [!NOTE]\n> Read this.\n", false);
        assertTrue(alert.getFirstChild() instanceof com.editora.markdown.MarkdownAlerts.Alert, "GitHub alerts");
        org.commonmark.node.Node para =
                MarkdownRenderer.parseForRender("Go :rocket:", false).getFirstChild();
        assertTrue(para.getLastChild() instanceof com.editora.markdown.MarkdownEmoji.Emoji, "emoji shortcodes");
        org.commonmark.node.Node marks = MarkdownRenderer.parseForRender("==a== b^2^ H~2~O ~gone~ ~~gone~~", false)
                .getFirstChild();
        java.util.List<String> kinds = new java.util.ArrayList<>();
        for (org.commonmark.node.Node n = marks.getFirstChild(); n != null; n = n.getNext()) {
            if (!(n instanceof org.commonmark.node.Text)) {
                kinds.add(n.getClass().getSimpleName());
            }
        }
        assertEquals(
                java.util.List.of("Highlight", "Superscript", "Subscript", "Strikethrough", "Strikethrough"),
                kinds,
                "the marks, and both spellings of strikethrough as before");
        assertTrue(
                MarkdownRenderer.parseForRender("Term\n: Its definition.\n", false)
                                .getFirstChild()
                        instanceof com.editora.markdown.MarkdownDefinitions.DefinitionList,
                "definition lists");
    }

    @Test
    void aCommentWithTextAfterItOnItsLineIsNotInvisible() {
        assertTrue(MarkdownRenderer.isHtmlComment("<!-- a --> <!-- b -->"));
        assertFalse(MarkdownRenderer.isHtmlComment("<!-- note --> **Visible** text after a comment"));
    }

    @Test
    void imagePathsAreReadAsTheWebWritesThem(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path docs = java.nio.file.Files.createDirectories(dir.resolve("repo/docs/guide"));
        java.nio.file.Files.createDirectories(dir.resolve("repo/assets"));
        java.nio.file.Files.write(dir.resolve("repo/assets/logo.png"), new byte[] {1});
        assertEquals("img/my image.png", MarkdownRenderer.localPath("img/my%20image.png"));
        assertEquals("img/a.png", MarkdownRenderer.localPath("img/a.png?raw=true"));
        assertEquals("img/a.png", MarkdownRenderer.localPath("img/a.png#frag"));
        assertEquals("100%.png", MarkdownRenderer.localPath("100%.png"), "a malformed escape is kept");
        assertEquals("a+b.png", MarkdownRenderer.localPath("a+b.png"));
        assertEquals(
                docs.resolve("img/my image.png").toUri().toString(),
                MarkdownRenderer.resolveUrl("img/my%20image.png", docs));
        assertEquals(
                docs.resolve("img/a.png").toUri().toString(), MarkdownRenderer.resolveUrl("img/a.png?raw=true", docs));
        // "/assets/logo.png" in a README means "from the repository root": the nearest folder above that has it.
        assertEquals(
                dir.resolve("repo/assets/logo.png").toUri().toString(),
                MarkdownRenderer.resolveUrl("/assets/logo.png", docs));
        assertEquals("https://x/y.png", MarkdownRenderer.resolveUrl("https://x/y.png", docs));
        assertNull(MarkdownRenderer.resolveUrl("img/a.png", null));
        assertNull(MarkdownRenderer.resolveUrl("#only-a-fragment", docs));
    }

    @Test
    void aLinkIsFollowedByAPlainPrimaryClickOnly() {
        assertTrue(MarkdownRenderer.followsLink(javafx.scene.input.MouseButton.PRIMARY, true));
        assertFalse(MarkdownRenderer.followsLink(javafx.scene.input.MouseButton.SECONDARY, true), "the menu button");
        assertFalse(MarkdownRenderer.followsLink(javafx.scene.input.MouseButton.MIDDLE, true));
        assertFalse(MarkdownRenderer.followsLink(javafx.scene.input.MouseButton.PRIMARY, false), "the end of a drag");
        assertEquals("https://e.org", MarkdownRenderer.linkTooltip(null, "https://e.org"));
        assertEquals("The title\nhttps://e.org", MarkdownRenderer.linkTooltip(" The title ", "https://e.org"));
    }

    @Test
    void aTableIsNeverNarrowerThanItsLongestWordsNeed() {
        // 16 equal columns: each has 1/16 of the width, so a 4-character word decides the table's minimum.
        double[] share = new double[16];
        java.util.Arrays.fill(share, 1.0 / 16);
        java.util.List<Integer> words = java.util.Collections.nCopies(16, 4);
        double min = MarkdownRenderer.tableMinWidth(words, share);
        assertTrue(min >= 16 * (4 * 8.5 + 20) - 1, "every column fits \"v15 \" on one line: " + min);
        // A very long word may break: it does not make the table arbitrarily wide.
        assertEquals(
                MarkdownRenderer.tableMinWidth(java.util.List.of(28), new double[] {1}),
                MarkdownRenderer.tableMinWidth(java.util.List.of(500), new double[] {1}));
        assertEquals(0, MarkdownRenderer.tableMinWidth(java.util.List.of(), new double[0]));
    }

    @Test
    void tokensOfAFenceAreRememberedUpToACharacterBudget() {
        MarkdownRenderer.TokenCache cache = new MarkdownRenderer.TokenCache(10);
        java.util.List<MarkdownRenderer.Run> runs =
                java.util.List.of(new MarkdownRenderer.Run("x", java.util.List.of()));
        cache.put("aaaa", runs);
        cache.put("bbbb", runs);
        assertSame(runs, cache.get("aaaa"));
        cache.put("cccc", runs); // 12 characters > 10: the least recently used ("bbbb") goes
        assertNull(cache.get("bbbb"));
        assertNotNull(cache.get("aaaa"));
        assertNotNull(cache.get("cccc"));
        cache.put("this key is longer than the whole budget", runs);
        assertEquals(2, cache.size());
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
