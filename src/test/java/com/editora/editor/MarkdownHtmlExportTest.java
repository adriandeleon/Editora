package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the Markdown → HTML exporter (non-math path is pure). */
class MarkdownHtmlExportTest {

    @Test
    void rendersStandaloneDocument() {
        String html = MarkdownHtmlExport.toHtml("# Title\n\nHello **world**.\n", "Doc", false);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "has doctype");
        assertTrue(html.contains("<title>Doc</title>"), "has title");
        assertTrue(html.contains("markdown-body"), "wraps body in .markdown-body");
        assertTrue(html.contains("<style>"), "embeds CSS");
        assertTrue(html.contains("<strong>world</strong>"), "renders inline markup");
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
