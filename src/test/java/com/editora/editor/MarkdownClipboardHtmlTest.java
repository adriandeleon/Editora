package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clipboard HTML fragment. The properties that matter for a rich paste are structural, so that is what
 * is asserted: it is a fragment (no document scaffolding), the styling is inline (a pasted {@code <style>}
 * block is stripped by Gmail/Teams, and CSS custom properties are resolved by nobody), and the semantic tags
 * survive so a consumer that drops every attribute still gets headings/bold/lists/tables right.
 */
class MarkdownClipboardHtmlTest {

    private static String html(String md) {
        return MarkdownClipboardHtml.toHtml(md, false);
    }

    @Test
    void isAFragmentNotAStandalonePage() {
        String out = html("# Title\n\nBody.\n");
        assertFalse(out.contains("<!DOCTYPE"), "no doctype — the text/html flavor takes a fragment");
        assertFalse(out.contains("<html"), "no <html> wrapper");
        assertFalse(out.contains("<head"), "no <head>");
        assertTrue(out.startsWith("<div style=\""), "wrapped in one styled div carrying the base font");
    }

    @Test
    void carriesNoStyleBlockAndNoCssVariables() {
        String out = html("# Title\n\n> quoted\n\n| a | b |\n| - | - |\n| 1 | 2 |\n");
        assertFalse(out.contains("<style"), "styling is inline, not a <style> block a consumer may strip");
        assertFalse(out.contains("var(--"), "custom properties do not resolve in any rich-paste importer");
        assertFalse(out.contains(":root"), "no :root selector leaks in");
    }

    @Test
    void keepsSemanticTagsSoAnAttributeStrippingConsumerStillReadsRight() {
        String out = html("# H1\n\n**bold** and *em* and `code`\n\n- one\n- two\n\n[link](https://example.com)\n");
        assertTrue(out.contains("<h1"), "heading");
        assertTrue(out.contains("<strong>bold</strong>"), "bold");
        assertTrue(out.contains("<em>em</em>"), "italic");
        assertTrue(out.contains("<code"), "inline code");
        assertTrue(out.contains("<ul"), "list");
        assertTrue(out.contains("<li"), "list item");
        assertTrue(out.contains("href=\"https://example.com\""), "link target");
    }

    @Test
    void stylesTheThingsHtmlSemanticsCannotCarry() {
        String out = html("| a | b |\n| - | - |\n| 1 | 2 |\n\n> quoted\n\n```\ncode\n```\n");
        assertTrue(out.contains("border-collapse:collapse"), "table borders collapse");
        assertTrue(out.contains("<td style=\"border:1px solid"), "cell borders — a bare <td> draws none");
        assertTrue(out.contains("<th style=\"border:1px solid"), "header cell borders");
        assertTrue(out.contains("border-left:4px solid"), "the blockquote rule");
        assertTrue(out.contains("<pre style="), "the code block's background/padding");
    }

    /**
     * commonmark calls the attribute provider once per emitted <em>tag</em>, so a fenced block hands it the
     * same {@code FencedCodeBlock} twice — for the {@code <pre>} and for the {@code <code>} nested in it.
     * Keying the style off the node rather than the tag paints the background and padding on both.
     */
    @Test
    void aCodeBlockPaintsItsBackgroundOnceNotTwice() {
        assertEquals(1, count(html("```\ncode\n```\n"), "background:"), "plain fence");
        assertEquals(1, count(html("```java\nint x;\n```\n"), "background:"), "fence with a language");
        assertEquals(1, count(html("    indented\n"), "background:"), "indented block");
        assertTrue(html("some `x` here\n").contains("<code style="), "inline code is still its own pill");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void headingsCarryTheSizeScaleAndTheH1H2Rule() {
        assertTrue(html("# a\n").contains("font-size:2em"), "h1 is the largest");
        assertTrue(html("## a\n").contains("border-bottom:1px solid"), "h2 keeps the underline rule");
        assertFalse(html("### a\n").contains("border-bottom"), "h3 and below have no rule");
    }

    @Test
    void gfmExtensionsRenderAndKeepTheirOwnAttributes() {
        String out = html("~~gone~~\n\n- [x] done\n- [ ] todo\n");
        assertTrue(out.contains("<del>gone</del>"), "strikethrough");
        // The task-list extension stamps its own attributes; the style provider must merge, not replace.
        assertTrue(out.contains("type=\"checkbox\""), "task list checkboxes");
        assertTrue(out.contains("checked"), "the ticked item stays ticked");
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertTrue(MarkdownClipboardHtml.toHtml(null, false).startsWith("<div"), "null renders an empty fragment");
        assertTrue(MarkdownClipboardHtml.toHtml("", false).startsWith("<div"), "empty renders an empty fragment");
    }
}
