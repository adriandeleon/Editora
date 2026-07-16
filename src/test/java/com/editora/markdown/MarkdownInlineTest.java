package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkdownInlineTest {

    @Test
    void wrapsSelection() {
        MarkdownEdit e = MarkdownInline.toggle("foo", 0, 3, "**");
        assertEquals(new MarkdownEdit(0, 3, "**foo**", 2, 5), e);
    }

    @Test
    void unwrapsWhenMarkersInsideSelection() {
        MarkdownEdit e = MarkdownInline.toggle("**foo**", 0, 7, "**");
        assertEquals(new MarkdownEdit(0, 7, "foo", 0, 3), e);
    }

    @Test
    void unwrapsWhenMarkersOutsideSelection() {
        // selection is just "foo" inside **foo**
        MarkdownEdit e = MarkdownInline.toggle("**foo**", 2, 5, "**");
        assertEquals(new MarkdownEdit(0, 7, "foo", 0, 3), e);
    }

    @Test
    void emptySelectionInsertsPairWithCaretBetween() {
        MarkdownEdit e = MarkdownInline.toggle("", 0, 0, "*");
        assertEquals(new MarkdownEdit(0, 0, "**", 1, 1), e);
    }

    @Test
    void codeAndStrikeMarkers() {
        assertEquals(new MarkdownEdit(0, 1, "`x`", 1, 2), MarkdownInline.toggle("x", 0, 1, "`"));
        assertEquals(new MarkdownEdit(0, 2, "~~hi~~", 2, 4), MarkdownInline.toggle("hi", 0, 2, "~~"));
    }

    @Test
    void linkWithEmptyUrlPlacesCaretInParens() {
        MarkdownEdit e = MarkdownInline.link("foo", 0, 3, "");
        assertEquals(new MarkdownEdit(0, 3, "[foo]()", 6, 6), e);
        assertEquals(')', e.replacement().charAt(6 - e.from()));
    }

    @Test
    void linkWithUrlSelectsText() {
        MarkdownEdit e = MarkdownInline.link("foo", 0, 3, "http://x");
        assertEquals(new MarkdownEdit(0, 3, "[foo](http://x)", 1, 4), e);
    }

    @Test
    void linkAroundFindsInlineAndBareUrls() {
        String inline = "see [a](http://x) end";
        assertEquals("http://x", MarkdownInline.linkAround(inline, 6));
        assertNull(MarkdownInline.linkAround(inline, 0));
        String bare = "go https://y/z now";
        assertEquals("https://y/z", MarkdownInline.linkAround(bare, 6));
        assertNull(MarkdownInline.linkAround(bare, 0));
    }

    private static String apply(String text, int selStart, int selEnd, String marker) {
        MarkdownEdit e = MarkdownInline.toggle(text, selStart, selEnd, marker);
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    /**
     * Toggling italic over bold matched the *inner* asterisk of each `**` and unwrapped it, so the bold was
     * silently lost ("**bold**" → "*bold*") where every other editor adds italics ("***bold***").
     */
    @Test
    void togglingItalicOverBoldAddsItalicRatherThanStrippingTheBold() {
        assertEquals("***bold***", apply("**bold**", 2, 6, "*"));
        assertEquals("***it***", apply("*it*", 1, 3, "**"), "the inverse already worked");
    }

    /** The unwrap path itself must still work — only a *longer run* of the marker char is off-limits. */
    @Test
    void unwrappingMatchingMarkersStillWorks() {
        assertEquals("it", apply("*it*", 1, 3, "*"));
        assertEquals("bold", apply("**bold**", 2, 6, "**"));
        assertEquals("s", apply("~~s~~", 2, 3, "~~"));
        assertEquals("c", apply("`c`", 1, 2, "`"));
    }
}
