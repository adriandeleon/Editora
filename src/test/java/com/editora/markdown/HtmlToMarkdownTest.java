package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToMarkdownTest {

    @Test
    void inlineMarkup() {
        assertEquals(
                "A **bold** and *italic* and `code` and ~~gone~~ [link](https://x.y/a) word.",
                HtmlToMarkdown.convert("<p>A <b>bold</b> and <em>italic</em> and <code>code</code> and <del>gone</del>"
                        + " <a href=\"https://x.y/a\">link</a> word.</p>"));
        assertEquals(
                "a **bold** b", HtmlToMarkdown.convert("a<strong> bold </strong>b"), "blanks stay outside the markers");
        assertEquals("<https://x.y>", HtmlToMarkdown.convert("<a href='https://x.y'>https://x.y</a>"));
        assertEquals(
                "![a cat](<img/my cat.png>)", HtmlToMarkdown.convert("<img src=\"img/my cat.png\" alt=\"a cat\">"));
        assertEquals("plain", HtmlToMarkdown.convert("<a href=\"javascript:void(0)\">plain</a>"));
    }

    @Test
    void textIsEscapedAndEntitiesDecoded() {
        assertEquals(
                "2 \\* 3 &lt; 7 &amp; snake_case \\[x\\]".replace("&lt;", "\\<").replace("&amp;", "&"),
                HtmlToMarkdown.convert("<p>2 * 3 &lt; 7 &amp; snake_case [x]</p>"));
        assertEquals(
                "“quoted” — caf\u00e9 \u20ac",
                HtmlToMarkdown.convert("&ldquo;quoted&rdquo;&nbsp;&mdash; caf&#233; &#x20AC;"));
    }

    @Test
    void headingsParagraphsQuotesAndRules() {
        assertEquals(
                "# Title\n\nFirst para.\n\nSecond\nline.\n\n> quoted\n\n---\n\n### Sub",
                HtmlToMarkdown.convert("<h1>Title</h1><p>First\n para.</p><p>Second<br>line.</p>"
                        + "<blockquote><p>quoted</p></blockquote><hr><h3>Sub</h3>"));
    }

    @Test
    void listsNestNumberAndKeepTaskBoxes() {
        assertEquals(
                "- one\n- two\n  1. a\n  2. b\n- three",
                HtmlToMarkdown.convert("<ul><li>one</li><li>two<ol><li>a</li><li>b</li></ol></li><li>three</ul>"));
        assertEquals("5. five\n6. six", HtmlToMarkdown.convert("<ol start=\"5\"><li>five<li>six</ol>"));
        assertEquals(
                "- [x] done\n- [ ] open",
                HtmlToMarkdown.convert(
                        "<ul><li><input type=\"checkbox\" checked> done</li><li><input type=checkbox> open</li></ul>"));
        assertEquals("- loose item", HtmlToMarkdown.convert("<ul><li><p>loose item</p></li></ul>"));
    }

    @Test
    void codeBlocksKeepTheirTextAndLanguage() {
        assertEquals(
                "```java\nint x = 1;\n  if (a < b) {}\n```",
                HtmlToMarkdown.convert(
                        "<pre><code class=\"language-java\">int x = 1;\n  if (a &lt; b) {}\n</code></pre>"));
        assertEquals("````\n```\nx\n```\n````", HtmlToMarkdown.convert("<pre>```\nx\n```</pre>"));
    }

    @Test
    void aSpreadsheetRangeBecomesATable() {
        // What LibreOffice and Excel put on the clipboard: a bare table, cells unclosed or styled.
        String html = "<html><head><style>td{}</style></head><body><table><tr><td>Name</td><td>Qty</td></tr>"
                + "<tr><td>a|b<td align=right>10</tr><tr><td><b>Total</b></td></tr></table></body></html>";
        assertEquals(
                "| Name      | Qty |\n| --------- | --- |\n| a\\|b      | 10  |\n| **Total** |     |",
                HtmlToMarkdown.convert(html));
    }

    @Test
    void googleDocsStyleSpans() {
        String html =
                "<meta charset='utf-8'><b style=\"font-weight:normal;\"><p><span style=\"font-weight:700\">Bold</span>"
                        + "<span> and </span><span style=\"font-style:italic\">slanted</span></p></b>";
        assertEquals("**Bold** and *slanted*", HtmlToMarkdown.convert(html));
    }

    @Test
    void fragmentsAndJunkAreSurvived() {
        assertEquals(
                "kept",
                HtmlToMarkdown.convert("<!--StartFragment--><script>x()</script><span>kept</span><!--EndFragment-->"));
        assertEquals("a \\< b", HtmlToMarkdown.convert("a < b"));
        assertEquals("", HtmlToMarkdown.convert("  "));
        assertEquals("", HtmlToMarkdown.convert(null));
        assertEquals("x", HtmlToMarkdown.convert("</div>x</p>"), "stray end tags");
    }

    @Test
    void onlyStructuredHtmlIsWorthConverting() {
        assertTrue(HtmlToMarkdown.hasStructure("<table><tr><td>a</td></tr></table>"));
        assertTrue(HtmlToMarkdown.hasStructure("<p>see <a class=x href=\"u\">this</a></p>"));
        assertTrue(HtmlToMarkdown.hasStructure("<ul><li>a</li></ul>"));
        assertFalse(HtmlToMarkdown.hasStructure("<span style=\"color:red\">just text</span>"));
        assertFalse(
                HtmlToMarkdown.hasStructure("<b>bold</b> text"), "emphasis alone does not change how a paste behaves");
        assertFalse(
                HtmlToMarkdown.hasStructure(
                        "<div style=\"font-family: monospace; white-space: pre;\"><a href='x'>y</a></div>"),
                "copied from a code editor");
        assertFalse(HtmlToMarkdown.hasStructure("<meta charset='utf-8'><pre><a href='x'>y</a></pre>"));
        assertFalse(HtmlToMarkdown.hasStructure(null));
    }

    @Test
    void plainTextThatIsMarkdownAlreadyIsRecognised() {
        assertTrue(HtmlToMarkdown.looksLikeMarkdown("# Title\n\ntext"));
        assertTrue(HtmlToMarkdown.looksLikeMarkdown("see [docs](http://x)"));
        assertTrue(HtmlToMarkdown.looksLikeMarkdown("- a\n- b"));
        assertTrue(HtmlToMarkdown.looksLikeMarkdown("| a | b |\n|---|---|"));
        assertTrue(HtmlToMarkdown.looksLikeMarkdown("some **bold** text"));
        assertFalse(HtmlToMarkdown.looksLikeMarkdown("Name\tQty\na\t10"));
        assertFalse(HtmlToMarkdown.looksLikeMarkdown("An ordinary sentence - with a dash."));
        assertFalse(HtmlToMarkdown.looksLikeMarkdown(null));
    }

    @Test
    void mergedCellsKeepEveryOtherCellUnderItsHeading() {
        // Markdown has no merged cells: the text goes in the first cell covered, the rest stay empty.
        String html = "<table><tr><th colspan=2>Name</th><th>Qty</th></tr>"
                + "<tr><td rowspan=2>Ann</td><td>x</td><td>1</td></tr>"
                + "<tr><td>y</td><td>2</td></tr>"
                + "<tr><td>Bob</td><td colspan=\"2\">none</td></tr></table>";
        assertEquals(
                "| Name |      | Qty |\n| ---- | ---- | --- |\n| Ann  | x    | 1   |\n|      | y    | 2   |\n| Bob  | none |     |",
                HtmlToMarkdown.convert(html));
    }

    @Test
    void aNestedTableIsFlattenedIntoItsCell() {
        String html = "<table><tr><td>outer</td><td><table><tr><td>in 1</td><td>in 2</td></tr>"
                + "<tr><td>in 3</td></tr></table></td></tr><tr><td>next</td><td>row</td></tr></table>";
        assertEquals(
                "| outer | in 1 in 2 in 3 |\n| ----- | -------------- |\n| next  | row            |",
                HtmlToMarkdown.convert(html));
    }

    @Test
    void definitionListsUseTheColonSyntax() {
        assertEquals(
                "Apple\n: A fruit.\n: A computer company.\n\nOrange\n: The fruit of an *evergreen* tree.",
                HtmlToMarkdown.convert("<dl><dt>Apple</dt><dd>A fruit.</dd><dd>A computer company.</dd>"
                        + "<dt>Orange<dd>The fruit of an <em>evergreen</em> tree.</dl>"));
        assertEquals("", HtmlToMarkdown.convert("<dl></dl>"));
    }
}
