package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownHeadingTest {

    private static String applied(String text, int from, int to, int delta) {
        MarkdownEdit e = MarkdownHeading.apply(text, from, to, delta);
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void demotePromote() {
        assertEquals("# foo", applied("foo", 0, 0, 1));
        assertEquals("## foo", applied("# foo", 0, 0, 1));
        assertEquals("## foo", applied("### foo", 0, 0, -1));
        assertEquals("foo", applied("# foo", 0, 0, -1));
    }

    @Test
    void clampsToOneThroughSix() {
        assertEquals("###### h", applied("###### h", 0, 0, 1));
        assertEquals("foo", applied("foo", 0, 0, -1));
    }

    @Test
    void setLevelAbsolute() {
        MarkdownEdit e = MarkdownHeading.setLevel("foo", 0, 0, 3);
        assertEquals("### foo", e.replacement());
        MarkdownEdit z = MarkdownHeading.setLevel("## foo", 0, 0, 0);
        assertEquals("foo", z.replacement());
    }

    @Test
    void multiLineSelection() {
        MarkdownEdit e = MarkdownHeading.apply("a\nb", 0, 3, 1);
        assertEquals("# a\n# b", e.replacement());
        assertEquals(0, e.from());
        assertEquals(3, e.to());
    }

    @Test
    void preservesIndentation() {
        assertEquals("  ## x", applied("  # x", 0, 0, 1));
    }
}
