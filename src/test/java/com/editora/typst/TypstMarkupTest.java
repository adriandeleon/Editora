package com.editora.typst;

import com.editora.markdown.MarkdownEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the Typst markup editing core: headings ({@code =}), links, and list continuation. */
class TypstMarkupTest {

    private static String applied(String text, MarkdownEdit e) {
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void setHeadingLevel_addsAndClearsEqualsPrefix() {
        assertEquals("== hello", applied("hello", TypstMarkup.setHeadingLevel("hello", 0, 5, 2)));
        assertEquals("hello", applied("= hello", TypstMarkup.setHeadingLevel("= hello", 0, 7, 0)));
        // Re-leveling an existing heading replaces its level, not stacks.
        assertEquals("=== x", applied("= x", TypstMarkup.setHeadingLevel("= x", 0, 3, 3)));
    }

    @Test
    void heading_promoteDemoteByDelta() {
        assertEquals("== x", applied("= x", TypstMarkup.heading("= x", 0, 3, 1)));
        assertEquals("= x", applied("== x", TypstMarkup.heading("== x", 0, 4, -1)));
        assertEquals("x", applied("= x", TypstMarkup.heading("= x", 0, 3, -1))); // promote past 1 → plain
    }

    @Test
    void link_emptyUrlPlacesCaretBetweenQuotes() {
        MarkdownEdit e = TypstMarkup.link("text", 0, 4, "");
        assertEquals("#link(\"\")[text]", applied("text", e));
        assertEquals("#link(\"".length(), e.selStart());
        assertEquals(e.selStart(), e.selEnd());
    }

    @Test
    void link_withUrlSelectsLinkText() {
        MarkdownEdit e = TypstMarkup.link("text", 0, 4, "https://x");
        assertEquals("#link(\"https://x\")[text]", applied("text", e));
        assertEquals("text", "#link(\"https://x\")[text]".substring(e.selStart(), e.selEnd()));
    }

    @Test
    void continuation_bulletPlusAndOrdered_butNotBold() {
        assertEquals("- ", TypstMarkup.continuation("- item"));
        assertEquals("+ ", TypstMarkup.continuation("+ item"));
        assertEquals("2. ", TypstMarkup.continuation("1. item"));
        assertEquals("  - ", TypstMarkup.continuation("  - nested"));
        assertNull(TypstMarkup.continuation("*bold* text")); // * is Typst bold, not a bullet
        assertNull(TypstMarkup.continuation("plain text"));
    }

    @Test
    void emptyItem_andDeleteLength() {
        assertTrue(TypstMarkup.isEmptyItem("- "));
        assertTrue(TypstMarkup.isEmptyItem("1. "));
        assertFalse(TypstMarkup.isEmptyItem("- a"));
        assertEquals(2, TypstMarkup.emptyMarkerDeleteLength("- ", 2));
        assertEquals(0, TypstMarkup.emptyMarkerDeleteLength("- a", 3));
    }

    @Test
    void markerLength_whereContentBegins() {
        assertEquals(2, TypstMarkup.markerLength("- a"));
        assertEquals(3, TypstMarkup.markerLength("1. a"));
        assertEquals(0, TypstMarkup.markerLength("plain"));
        assertEquals(0, TypstMarkup.markerLength("*bold*"));
    }
}
