package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the Markdown → HTML exporter (non-math path is pure). */
class MarkdownHtmlExportTest {

    @Test
    void rendersStandaloneDocument() {
        String html = MarkdownHtmlExport.toHtml("Hello **world**.\n", "Doc", false);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "has doctype");
        assertTrue(html.contains("<meta charset=\"utf-8\">"), "states its encoding");
        assertTrue(html.contains("<title>Doc</title>"), "a document that states no title takes the given one");
        assertTrue(html.contains("markdown-body"), "wraps body in .markdown-body");
        assertTrue(html.contains("<style>"), "embeds CSS");
        assertTrue(html.contains("<strong>world</strong>"), "renders inline markup");
    }

    @Test
    void theTitleAndLanguageAreTheDocumentsOwn() {
        String h1 = MarkdownHtmlExport.toHtml("intro\n\n# Release *notes* & more\n\n# Second\n", "file", false);
        assertTrue(h1.contains("<title>Release notes &amp; more</title>"), h1);
        assertTrue(h1.contains("<html>"), "no language is claimed when the document states none");
        assertFalse(h1.contains("lang=\"en\""), h1);

        String fm = MarkdownHtmlExport.toHtml(
                "---\ntitle: \"Guía rápida\"\nlang: es\n---\n\n# Otro título\n", "file", false);
        assertTrue(fm.contains("<title>Guía rápida</title>"), "front matter wins over the first heading");
        assertTrue(fm.contains("<html lang=\"es\">"), fm);

        String bad = MarkdownHtmlExport.toHtml("---\nlanguage: \"x\\\" onload=1\"\n---\n\ntext\n", "file", false);
        assertTrue(bad.contains("<html>"), () -> "a value that is not a language tag is not written: " + bad);
        assertTrue(MarkdownHtmlExport.toHtml("---\nlanguage: pt_BR\n---\n", "f", false)
                .contains("lang=\"pt-BR\""));
    }

    /**
     * Raw HTML is kept on purpose, so the page itself says that nothing in it may run: a {@code <script>} or
     * an {@code onerror=} from a document someone else wrote is inert in the reader's browser.
     */
    @Test
    void thePageForbidsScript() {
        String html = MarkdownHtmlExport.toHtml(
                "<script>alert(1)</script>\n\n<img src=x onerror=\"alert(1)\">\n\n<kbd>K</kbd>\n", "t", false);
        String policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'; object-src 'none';"
                + " base-uri 'none'\">";
        assertTrue(html.contains(policy), html);
        assertTrue(html.indexOf(policy) < html.indexOf("<title>"), "in the head, before anything could run");
        assertTrue(html.indexOf(policy) < html.indexOf("<script>alert"), html);
        assertTrue(html.contains("<kbd>K</kbd>"), "ordinary raw HTML still renders");
    }

    @Test
    void taskListsAndAlignedCellsAreMarkedUpForTheStylesheet() {
        String html = MarkdownHtmlExport.toHtml(
                "- [x] done\n- [ ] todo\n\n* plain\n\n| L | C | R | N |\n| :-- | :-: | --: | --- |\n| a | b | c | d |\n",
                "t",
                false);
        assertTrue(html.contains("<ul class=\"contains-task-list\">"), html);
        assertTrue(html.contains("<li class=\"task-list-item\"><input type=\"checkbox\""), html);
        assertTrue(html.contains("<ul>\n<li>plain</li>"), "an ordinary list is left alone");
        assertTrue(html.contains("<th style=\"text-align:center\">C</th>"), html);
        assertTrue(html.contains("<td style=\"text-align:right\">c</td>"), html);
        assertTrue(html.contains("<td>d</td>"), html);
        assertFalse(html.contains(" align=\""), "the align attribute is obsolete in HTML5");
        assertTrue(html.contains("li.task-list-item { list-style: none; }"), "and the sheet has the rule for it");
    }

    @Test
    void theStylesheetFollowsTheReadersColourScheme() {
        String html = MarkdownHtmlExport.toHtml("x", "t", false);
        assertTrue(html.contains("<meta name=\"color-scheme\" content=\"light dark\">"), html);
        assertTrue(html.contains("@media (prefers-color-scheme: dark)"), html);
        assertTrue(html.contains("background: var(--bg);"), "no colour is fixed outside the variables");
        assertTrue(html.indexOf("@media print") > html.indexOf("prefers-color-scheme: dark"), "print stays light");
    }

    @Test
    void rendersHeadingAnchorIds() {
        String html = MarkdownHtmlExport.toHtml("## My Section\n", "Doc", false);
        assertTrue(html.contains("id=\"my-section\""), () -> "expected heading anchor id in: " + html);
    }

    @Test
    void rendersGfmTableAndStrikethrough() {
        String html = MarkdownHtmlExport.toHtml("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~gone~~\n", "Doc", false);
        assertTrue(html.contains("<table>"), "renders GFM table");
        assertTrue(html.contains("<del>gone</del>"), "renders strikethrough");
    }

    @Test
    void escapesTitle() {
        String html = MarkdownHtmlExport.toHtml("x", "a<b>&c", false);
        assertTrue(html.contains("<title>a&lt;b&gt;&amp;c</title>"));
    }

    @Test
    void scriptCapableLinkAndImageUrlsAreNeutralised() {
        String html = MarkdownHtmlExport.toHtml(
                "[click](javascript:alert(document.cookie))\n\n[vb](vbscript:msgbox)\n\n"
                        + "![pix](javascript:alert(1))\n\n[ok](https://example.com/a) [rel](docs/b.md) [mail](mailto:a@b.c)\n",
                "Doc",
                false);
        assertFalse(html.contains("javascript:"), () -> "no javascript: URL survives: " + html);
        assertFalse(html.contains("vbscript:"), html);
        assertTrue(html.contains("<a href=\"\">click</a>"), () -> "the link text stays, the href is emptied: " + html);
        assertTrue(html.contains("<a href=\"https://example.com/a\">ok</a>"), html);
        assertTrue(html.contains("<a href=\"docs/b.md\">rel</a>"), "relative links are untouched");
        assertTrue(html.contains("<a href=\"mailto:a@b.c\">mail</a>"), html);
        assertFalse(html.contains("nofollow"), "the sanitizer's rel=nofollow is not stamped on an author's links");
    }

    @Test
    void authorsRawHtmlIsKeptInTheFileExport() {
        String html =
                MarkdownHtmlExport.toHtml("<details><summary>More</summary>\n\nbody\n\n</details>\n", "Doc", false);
        assertTrue(html.contains("<details><summary>More</summary>"), html);
    }
}
