package com.editora.editops;

import com.editora.editops.HtmlCaretRegion.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the caret is in a page, for the snippets offered there (HTML review B5/C4). */
class HtmlCaretRegionTest {

    @Test
    void contentTagsCommentsAndRawText() {
        assertEquals(Region.CONTENT, HtmlCaretRegion.at(""));
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("<p>Some te"));
        assertEquals(Region.TAG, HtmlCaretRegion.at("<p>x</p>\n<a hr"));
        assertEquals(Region.TAG, HtmlCaretRegion.at("<p style=\"col"), "an attribute value is still in the tag");
        assertEquals(Region.TAG, HtmlCaretRegion.at("<a title=\"a > b\" hr"), "a quoted > does not end the tag");
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("<p>it's <b>bold</b> x"), "an apostrophe in text is text");
        assertEquals(Region.COMMENT, HtmlCaretRegion.at("<!-- <a hr"));
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("<!-- <style> --> <p>col"));
        assertEquals(Region.STYLE, HtmlCaretRegion.at("<head>\n<style>\np { col"));
        assertEquals(Region.STYLE, HtmlCaretRegion.at("<STYLE media=\"screen\">a > b { col"), "case and > in CSS");
        assertEquals(Region.SCRIPT, HtmlCaretRegion.at("<script>\nif (a<b && c) {\n  var"), "a < in code");
        assertEquals(Region.SCRIPT, HtmlCaretRegion.at("<script type=\"module\">let s = '<div>'; va"));
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("<script>x()</script>\n<p>va"));
        assertEquals(Region.TAG, HtmlCaretRegion.at("<style>a{}</style><a hr"));
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("<textarea><b>x</b> va"), "textarea holds text, no tags");
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("<br/><p>x"));
        assertEquals(Region.CONTENT, HtmlCaretRegion.at("if (a < b) x"), "a < before a space starts no tag");
    }

    @Test
    void theSnippetLanguageFollowsTheRegionInHtmlOnly() {
        assertEquals("css", HtmlCaretRegion.snippetLanguage("html", "<style>p { col"));
        assertEquals("javascript", HtmlCaretRegion.snippetLanguage("html", "<script>\n  var"));
        assertNull(HtmlCaretRegion.snippetLanguage("html", "<a hr"));
        assertEquals("html", HtmlCaretRegion.snippetLanguage("html", "<body>\n  hr"));
        assertEquals("html", HtmlCaretRegion.snippetLanguage("html", "<!-- hr"));
        assertEquals("xml", HtmlCaretRegion.snippetLanguage("xml", "<a hr"), "only HTML is split by region");
    }

    @Test
    void aCaretInTheNameOfAWholeEndTagIsInOne() {
        assertTrue(HtmlCaretRegion.inWholeEndTag("<p>a</", "p>\n"), "where a hand-typed closer joins");
        assertTrue(HtmlCaretRegion.inWholeEndTag("<item>a</it", "em>"));
        assertTrue(HtmlCaretRegion.inWholeEndTag("<p>a</p", ">"));
        assertFalse(HtmlCaretRegion.inWholeEndTag("<p>a</", ""), "a closer still to be typed: complete it");
        assertFalse(HtmlCaretRegion.inWholeEndTag("<p>a</", "\n</div>"));
        assertFalse(HtmlCaretRegion.inWholeEndTag("<p>a</", ">"), "no name at all");
        assertFalse(HtmlCaretRegion.inWholeEndTag("<p>a", "</p>"), "before the closer, not in it");
        assertFalse(HtmlCaretRegion.inWholeEndTag("a / b", "c>"));
    }
}
