package com.editora.editor;

import org.junit.jupiter.api.Test;

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
}
