package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownHtmlTest {

    @Test
    void tokensSplitTagsAndTextAndDecodeEntities() {
        List<MarkdownHtml.Token> t =
                MarkdownHtml.tokens("press <kbd>Ctrl</kbd> &amp; <b>go</b> &#x41;&#66;&bogus; a < b");
        assertEquals(new MarkdownHtml.Chars("press "), t.get(0));
        MarkdownHtml.Tag kbd = assertInstanceOf(MarkdownHtml.Tag.class, t.get(1));
        assertEquals("kbd", kbd.name());
        assertFalse(kbd.close());
        assertEquals(new MarkdownHtml.Chars("Ctrl"), t.get(2));
        assertTrue(((MarkdownHtml.Tag) t.get(3)).close());
        assertEquals(new MarkdownHtml.Chars(" & "), t.get(4));
        assertEquals(new MarkdownHtml.Chars(" AB&bogus; a < b"), t.get(t.size() - 1), "a lone < is text");
    }

    @Test
    void attributesAreReadInEveryQuotingStyle() {
        MarkdownHtml.Tag img = MarkdownHtml.tag("<IMG SRC=\"img/a b.png\" width=60 alt='A &amp; B' hidden>");
        assertEquals("img", img.name());
        assertEquals("img/a b.png", img.attr("src"));
        assertEquals("60", img.attr("width"));
        assertEquals("A & B", img.attr("alt"));
        assertEquals("", img.attr("hidden"));
        assertNull(img.attr("title"));
        assertEquals("a>b", MarkdownHtml.tag("<a title=\"a>b\" href=x>").attr("title"), "a > inside quotes");
        assertNull(MarkdownHtml.tag("<!-- a comment -->"));
        assertNull(MarkdownHtml.tag("not a tag"));
    }

    @Test
    void commentsScriptsAndStylesAreDroppedWithTheirContent() {
        assertEquals(" Visible text", MarkdownHtml.strip("<!-- note --> Visible text"));
        assertEquals("ab", MarkdownHtml.strip("a<script>alert(1)</script>b"));
        assertEquals("ab", MarkdownHtml.strip("a<STYLE type=x>p{}</style>b"));
        assertEquals("a", MarkdownHtml.strip("a<script>never closed"));
        assertTrue(MarkdownHtml.strip("<!-- one -->\n<!-- two\nlines -->").isBlank());
        assertEquals("", MarkdownHtml.strip("<!DOCTYPE html><?xml version=\"1.0\"?>"));
        assertEquals("", MarkdownHtml.strip(null));
        assertTrue(MarkdownHtml.tokens("<script>x</script>").isEmpty());
    }

    @Test
    void theSafeSubsetMapsToStylesAndEverythingElseToNothing() {
        assertEquals("md-bold", MarkdownHtml.styleOf("strong"));
        assertEquals("md-italic", MarkdownHtml.styleOf("em"));
        assertEquals("md-kbd", MarkdownHtml.styleOf("kbd"));
        assertEquals("md-sub", MarkdownHtml.styleOf("sub"));
        assertEquals("md-sup", MarkdownHtml.styleOf("sup"));
        assertEquals("md-html-code", MarkdownHtml.styleOf("code"));
        assertNull(MarkdownHtml.styleOf("span"));
        assertNull(MarkdownHtml.styleOf("iframe"));
        assertTrue(MarkdownHtml.isBlockTag("p"));
        assertTrue(MarkdownHtml.isBlockTag("h3"));
        assertFalse(MarkdownHtml.isBlockTag("span"));
        assertEquals(3, MarkdownHtml.headingLevel("h3"));
        assertEquals(0, MarkdownHtml.headingLevel("hr"));
        assertEquals(0, MarkdownHtml.headingLevel("h7"));
    }

    @Test
    void centeringIsRecognisedInItsThreeSpellings() {
        assertTrue(MarkdownHtml.centered(MarkdownHtml.tag("<p align=\"center\">")));
        assertTrue(MarkdownHtml.centered(MarkdownHtml.tag("<div ALIGN=Center>")));
        assertTrue(MarkdownHtml.centered(MarkdownHtml.tag("<center>")));
        assertTrue(MarkdownHtml.centered(MarkdownHtml.tag("<h1 style=\"text-align: center\">")));
        assertFalse(MarkdownHtml.centered(MarkdownHtml.tag("<p align=\"left\">")));
        assertFalse(MarkdownHtml.centered(MarkdownHtml.tag("<p>")));
    }

    @Test
    void detailsDepthSaysWhichBlockOpensAndWhichCloses() {
        assertEquals(1, MarkdownHtml.detailsDepth("<details>\n<summary>Click</summary>"));
        assertEquals(1, MarkdownHtml.detailsDepth("<details open>"));
        assertEquals(-1, MarkdownHtml.detailsDepth("</details>"));
        assertEquals(0, MarkdownHtml.detailsDepth("<details><summary>a</summary>b</details>"));
        assertEquals(0, MarkdownHtml.detailsDepth("<p>no details</p>"));
    }

    @Test
    void pixelLengthsAndWhitespace() {
        assertEquals(60, MarkdownHtml.pixels("60"));
        assertEquals(120, MarkdownHtml.pixels(" 120px "));
        assertEquals(-1, MarkdownHtml.pixels("50%"));
        assertEquals(-1, MarkdownHtml.pixels("-3"));
        assertEquals(-1, MarkdownHtml.pixels(null));
        assertEquals(" a b c ", MarkdownHtml.collapse("\n  a \t\n b   c\n"));
    }
}
