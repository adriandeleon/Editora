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

    @Test
    void anEqualsWantsQuotesOnlyAfterAnAttributeNameInAStartTag() {
        assertTrue(HtmlCaretRegion.quotesAfterEquals("<a class", ">"));
        assertTrue(HtmlCaretRegion.quotesAfterEquals("<button @click", ""));
        for (String angular : new String[] {"<button (click)", "<input [(ngModel)]", "<li *ngIf", "<input [value]"}) {
            assertTrue(HtmlCaretRegion.quotesAfterEquals(angular, ">"), angular + ": an Angular binding (C209)");
        }
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<p {{#if a}}class", ">"), "after a template tag");
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<a href=page#top", ">"), "inside an unquoted value");
        assertTrue(HtmlCaretRegion.quotesAfterEquals("<a href=\"x\" title", " >"));
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<a href=\"x title", "\">"), "inside a value");
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<a class", "\"x\">"), "the quote is there");
        assertFalse(HtmlCaretRegion.quotesAfterEquals("</a x", ">"), "an end tag");
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<!DOCTYPE html", ">"));
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<a class ", ">"), "a space, not a name, before the caret");
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<style>a { b", ""), "CSS");
    }

    private static String before(String text) {
        return HtmlCaretRegion.before(text::substring, text.length());
    }

    /** A long inline script read from its middle looked like markup (HTML review 2 E112). */
    @Test
    void theWindowOfALongScriptIsReadFromTheScriptsStartTag() {
        String code = "for (var i = 0; i<n; i++) { total += i; }\n".repeat(1_000);
        String page = "<body>\n<script>\n" + code + "total";
        String window = page.substring(page.length() - HtmlCaretRegion.MAX_SCAN);
        assertTrue(HtmlCaretRegion.quotesAfterEquals(window, ""), "the trap: the window alone reads `<n;` as a tag");
        assertFalse(HtmlCaretRegion.quotesAfterEquals(before(page), ""));
        assertEquals(Region.SCRIPT, HtmlCaretRegion.at(before(page)));
        assertEquals("javascript", HtmlCaretRegion.snippetLanguage("html", before(page)));
        assertEquals(Region.STYLE, HtmlCaretRegion.at(before("<STYLE>\n" + "a{}\n".repeat(6_000) + "p { col")));

        String closed = "<script>x()</script>\n" + "<p class=\"a\">i<n</p>\n".repeat(1_500) + "<p id";
        assertEquals(Region.TAG, HtmlCaretRegion.at(before(closed)), "after a closed script: markup");
        assertTrue(HtmlCaretRegion.quotesAfterEquals(before(closed), ">"));
        String commented = "<!-- <script> -->\n" + "<p>x</p>\n".repeat(4_000) + "<p id";
        assertTrue(HtmlCaretRegion.quotesAfterEquals(before(commented), ">"), "a script mentioned in a comment");
        assertEquals("<p id", before("<p id"), "a short page is read whole");
        assertNull(HtmlCaretRegion.rawOpenBefore(page::substring, 0, 0));
        String far = "<script>\n" + "x();\n".repeat(30_000);
        assertNull(
                HtmlCaretRegion.rawOpenBefore(far::substring, far.length(), far.length()),
                "beyond the look-back: not guessed");
        // A start tag cut by a chunk's edge is still read by its name.
        String edge = "<p>\n<script>" + "y".repeat(HtmlCaretRegion.MAX_SCAN - 6); // its '<' 2 before the window
        assertEquals(
                "script",
                HtmlCaretRegion.rawOpenBefore(
                        edge::substring, edge.length() - HtmlCaretRegion.MAX_SCAN, edge.length()));
    }

    @Test
    void templateTagsInAStartTagTakeNoQuotes() {
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<option {% if a", ">"));
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<p {% set x", ">"));
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<button {{action \"save\" on", ">"));
        assertFalse(HtmlCaretRegion.quotesAfterEquals("<p <% if a", ">"));
        assertTrue(HtmlCaretRegion.quotesAfterEquals("{% if x %}<p class", ">"), "a template tag closed before");
        assertTrue(HtmlCaretRegion.quotesAfterEquals("<p {% if x %}selected{% endif %} class", ">"));
        assertTrue(HtmlCaretRegion.inTemplateTag("<b {{ x", 0));
        assertFalse(HtmlCaretRegion.inTemplateTag("{{ x <b", 3), "opened before the tag");
    }
}
