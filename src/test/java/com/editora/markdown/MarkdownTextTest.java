package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownTextTest {

    @Test
    void emphasisMarkersGo() {
        assertEquals("bold and italic and both", MarkdownText.plain("**bold** and *italic* and ***both***"));
        assertEquals("under and strong", MarkdownText.plain("_under_ and __strong__"));
        assertEquals("gone", MarkdownText.plain("~~gone~~"));
    }

    @Test
    void markerCharactersThatAreTextStay() {
        assertEquals("2 * 3", MarkdownText.plain("2 * 3"));
        assertEquals("snake_case_name", MarkdownText.plain("snake_case_name"));
        assertEquals("~5 minutes", MarkdownText.plain("~5 minutes"));
        assertEquals("a*b", MarkdownText.plain("a\\*b"));
    }

    @Test
    void linksAndImagesKeepTheirText() {
        assertEquals("A link b", MarkdownText.plain("A [link](http://x.com/a_(b)) b"));
        assertEquals("see ref", MarkdownText.plain("see [ref][id]"));
        assertEquals("pic alt", MarkdownText.plain("pic ![alt](x.png \"title\")"));
        assertEquals("a bold link", MarkdownText.plain("a [**bold** link](u)"));
        assertEquals("[not a link]", MarkdownText.plain("[not a link]"));
        assertEquals("go http://x.y now", MarkdownText.plain("go <http://x.y> now"));
    }

    @Test
    void codeKeepsItsContentVerbatim() {
        assertEquals("The foo_bar* API", MarkdownText.plain("The `foo_bar*` API"));
        assertEquals("a ` b", MarkdownText.plain("`` a ` b ``"));
        assertEquals("stray ` tick", MarkdownText.plain("stray ` tick"));
    }

    @Test
    void htmlTagsShowNothing() {
        assertEquals("html and break", MarkdownText.plain("<b>html</b> and<br/> break"));
        assertEquals("a < b > c", MarkdownText.plain("a < b > c"));
    }

    @Test
    void nothingIn_nothingOut() {
        assertEquals("", MarkdownText.plain(null));
        assertEquals("", MarkdownText.plain("  "));
    }
}
