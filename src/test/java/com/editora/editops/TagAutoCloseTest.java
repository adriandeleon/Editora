package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TagAutoCloseTest {

    /** Text before the caret at the moment {@code >} is typed (the {@code >} itself not yet inserted). */
    private static String html(String beforeCaret) {
        return TagAutoClose.closer(beforeCaret, true);
    }

    private static String xml(String beforeCaret) {
        return TagAutoClose.closer(beforeCaret, false);
    }

    // --- inserts ---

    @Test
    void simpleOpenTagCloses() {
        assertEquals("</body>", html("<body"));
        assertEquals("</div>", html("text <div"));
    }

    @Test
    void tagWithAttributesCloses() {
        assertEquals("</div>", html("<div class=\"box\" id='main'"));
        assertEquals("</a>", html("<a href=https://example.com"));
    }

    @Test
    void quotedAttributeContainingAngleBracketsCloses() {
        assertEquals("</div>", html("<div title=\"a > b < c\""));
    }

    @Test
    void namespacedAndDashedNamesClose() {
        assertEquals("</ns:item>", xml("<ns:item"));
        assertEquals("</my-widget>", html("<my-widget"));
    }

    @Test
    void nestedContextUsesTheNearestTag() {
        assertEquals("</em>", html("<div><em"));
    }

    @Test
    void apostropheInTextContentDoesNotDerailQuoteTracking() {
        // Quote state only applies inside a tag — "it's" must not open a phantom quote.
        assertEquals("</em>", html("<p>it's fine <em"));
    }

    @Test
    void closedQuotedAttributeEarlierInTheTagIsFine() {
        assertEquals("</div>", html("<div title=\"a > b\" data-x=\"1\""));
    }

    // --- no insert ---

    @Test
    void closingTagDoesNotAutoClose() {
        assertNull(html("<div>text</div"));
    }

    @Test
    void selfClosingTagDoesNotAutoClose() {
        assertNull(html("<img src=\"x.png\" /"));
        assertNull(xml("<node/"));
    }

    @Test
    void voidElementsDoNotAutoCloseInHtml() {
        assertNull(html("<br"));
        assertNull(html("<input type=\"text\""));
        assertNull(html("<IMG src=\"x\"")); // case-insensitive void check
    }

    @Test
    void voidElementsCloseNormallyInXml() {
        assertEquals("</br>", xml("<br"));
    }

    @Test
    void doctypeCommentAndPiDoNotAutoClose() {
        assertNull(html("<!DOCTYPE html"));
        assertNull(html("<!-- note --"));
        assertNull(xml("<?xml version=\"1.0\"?"));
    }

    @Test
    void greaterThanInPlainTextDoesNotAutoClose() {
        assertNull(html("<div>a "));
        assertNull(html("if (a "));
    }

    @Test
    void greaterThanInsideAnOpenQuoteDoesNotAutoClose() {
        // The user is typing "a > b" inside an attribute value — the quote is still open.
        assertNull(html("<div title=\"a "));
    }

    @Test
    void emptyOrJunkTagDoesNotAutoClose() {
        assertNull(html("<"));
        assertNull(html("a < b"));
        assertNull(html(""));
    }
}
