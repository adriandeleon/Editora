package com.editora.editor.markdown;

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

    // --- what lands on the clipboard is pasted into other applications: no raw HTML, no script URLs ---

    @Test
    void rawHtmlFromTheDocumentIsEscapedNotPasted() {
        String out = html("before <img src=x onerror=alert(1)> after\n\n<script>alert(1)</script>\n\n"
                + "<iframe src=\"https://attacker.example\"></iframe>\n");
        assertFalse(out.contains("<img"), out);
        assertFalse(out.contains("<script"), out);
        assertFalse(out.contains("<iframe"), out);
        assertTrue(out.contains("&lt;img src=x onerror=alert(1)&gt;"), () -> "shown as text: " + out);
        assertTrue(out.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), out);
    }

    @Test
    void scriptCapableUrlsAreEmptied() {
        String out = html("[click](javascript:alert(1)) and [ok](https://example.com) ![p](javascript:alert(2))\n");
        assertFalse(out.contains("javascript:"), out);
        assertTrue(out.contains("href=\"https://example.com\""), out);
        assertFalse(out.contains("nofollow"), out);
    }

    @Test
    void generatedMathImagesSurviveButLookalikesWrittenInTheDocumentDoNot() {
        org.junit.jupiter.api.Assumptions.assumeTrue(MathImages.renderPng("x^2", false, 18f, false) != null, "math");
        String forged = "<img class=\"md-math md-math-inline\" alt=\"x\" src=\"https://attacker.example/t.png\">";
        String out = MarkdownClipboardHtml.toHtml("inline $x^2$ here\n\n$$a+b$$\n\nforged " + forged + " end\n", true);
        assertEquals(2, count(out, "src=\"data:image/png;base64,"), () -> "both equations are real <img> tags: " + out);
        assertFalse(out.contains("src=\"https://attacker.example"), () -> "the forged tag is not live markup: " + out);
        assertTrue(
                out.contains("&lt;img class=&quot;md-math md-math-inline&quot;"), () -> "it is shown as text: " + out);
    }

    // --- pictures travel with the fragment ----------------------------------------------------------------

    private static byte[] png() throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void aLocalPictureIsInlinedSoThePasteStillShowsIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("img dir"));
        java.nio.file.Files.write(dir.resolve("img dir/shot.png"), png());
        java.nio.file.Path doc = dir.resolve("notes.md");
        String md = "![a](<img dir/shot.png>) ![b](img%20dir/shot.png) ![web](https://example.com/w.png) "
                + "![gone](missing.png)\n";
        String out = MarkdownClipboardHtml.toHtml(md, false, doc);
        String data =
                "src=\"data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png()) + "\"";
        assertEquals(2, count(out, data), () -> "both spellings of the local path are inlined: " + out);
        assertTrue(out.contains("src=\"https://example.com/w.png\""), "a web address already works anywhere");
        assertTrue(out.contains("src=\"missing.png\""), "a file that is not there is left as written");
        // Without a saved document there is nothing to resolve a relative path against.
        assertTrue(MarkdownClipboardHtml.toHtml(md, false).contains("src=\"img%20dir/shot.png\""));
        assertTrue(MarkdownClipboardHtml.toHtml(md, false, null).contains("src=\"img%20dir/shot.png\""));
    }

    @Test
    void aPictureTooLargeToInlineIsNamedByItsAbsoluteFileUri(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path big = dir.resolve("big.png");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(MarkdownClipboardHtml.MAX_INLINE_IMAGE_BYTES + 1L);
        }
        assertEquals(big.toUri().toString(), MarkdownClipboardHtml.portableImageSrc("big.png", dir));
        assertEquals(null, MarkdownClipboardHtml.portableImageSrc("data:image/png;base64,AAAA", dir));
        assertEquals(null, MarkdownClipboardHtml.portableImageSrc("file:///etc/passwd", dir));
        assertEquals(null, MarkdownClipboardHtml.portableImageSrc("dir-not-file", dir.getParent()));
        assertEquals(null, MarkdownClipboardHtml.portableImageSrc(" ", dir));
    }

    // --- "Copy as HTML": markup for an HTML file, not a styled paste ---------------------------------------

    @Test
    void theMarkupFormHasNoInlineStylesAndNoWrapper() {
        String out = MarkdownClipboardHtml.toMarkup(
                "# Title\n\nSome `code` and a [link](https://example.com).\n\n- [x] done\n- [ ] todo\n\n"
                        + "| L | R |\n| :-- | --: |\n| a | b |\n\n```java\nint x;\n```\n",
                false);
        assertTrue(out.startsWith("<h1>Title</h1>"), out);
        assertFalse(out.contains("<div"), out);
        assertEquals(
                4, count(out, "style=\""), () -> "only the cells of the two aligned columns carry a style: " + out);
        assertTrue(out.contains("<td style=\"text-align:right\">b</td>"), out);
        assertFalse(out.contains("align=\""), "not the obsolete attribute");
        assertTrue(out.contains("<ul class=\"contains-task-list\">"), out);
        assertTrue(out.contains("<li class=\"task-list-item\">"), out);
        assertTrue(out.contains("<pre><code class=\"language-java\">int x;"), out);
        assertTrue(out.contains("<a href=\"https://example.com\">link</a>"), out);
    }

    @Test
    void theMarkupFormKeepsTheSameSafetyAsTheRichFragment() {
        String out = MarkdownClipboardHtml.toMarkup(
                "<script>alert(1)</script>\n\n[x](javascript:alert(1)) <kbd>K</kbd>\n", false);
        assertFalse(out.contains("<script"), out);
        assertFalse(out.contains("javascript:"), out);
        assertTrue(out.contains("&lt;kbd&gt;"), out);
        assertEquals("", MarkdownClipboardHtml.toMarkup(null, false));
    }

    @Test
    void aWikiLinkIsCopiedAsALinkToTheFileItResolvesTo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve("Other Note.md"), "# Other\n\n## The Part\n");
        String html = MarkdownClipboardHtml.toHtml(
                "See [[Other Note#The Part|the part]] and [[missing]].\n", false, dir.resolve("doc.md"));
        assertTrue(html.contains("href=\"Other%20Note.md#the-part\""), html);
        assertTrue(html.contains(">the part</a>"), html);
        assertTrue(html.contains("missing") && !html.contains("[["), "unresolved: its text, no link: " + html);
        assertFalse(html.contains("href=\"Other%20Note#"), "not the raw note name: " + html);
    }
}
