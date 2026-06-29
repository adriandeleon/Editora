package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinesTest {

    @Test
    void continuesBullets() {
        assertEquals("- ", MarkdownLines.continuation("- item"));
        assertEquals("  * ", MarkdownLines.continuation("  * a"));
        assertEquals("+ ", MarkdownLines.continuation("+ x"));
    }

    @Test
    void continuesAndIncrementsOrdered() {
        assertEquals("2. ", MarkdownLines.continuation("1. first"));
        assertEquals("4) ", MarkdownLines.continuation("3) third"));
        assertEquals("10. ", MarkdownLines.continuation("9. nine"));
    }

    @Test
    void continuesTasksResetToUnchecked() {
        assertEquals("- [ ] ", MarkdownLines.continuation("- [ ] todo"));
        assertEquals("- [ ] ", MarkdownLines.continuation("- [x] done"));
    }

    @Test
    void continuesBlockquote() {
        assertEquals("> ", MarkdownLines.continuation("> quote"));
    }

    @Test
    void nonListLineHasNoContinuation() {
        assertNull(MarkdownLines.continuation("plain text"));
        assertNull(MarkdownLines.continuation(""));
    }

    @Test
    void markerLengthMeasuresPrefix() {
        assertEquals(2, MarkdownLines.markerLength("- item"));
        assertEquals(3, MarkdownLines.markerLength("1. first"));
        assertEquals(6, MarkdownLines.markerLength("- [ ] todo"));
        assertEquals(2, MarkdownLines.markerLength("> q"));
        assertEquals(0, MarkdownLines.markerLength("plain"));
    }

    @Test
    void toggleBulletAddsAndRemoves() {
        // add to plain lines
        MarkdownEdit add = MarkdownLines.toggleBullet("a\nb", 0, 3);
        assertEquals("- a\n- b", add.replacement());
        // remove when all lines are already bullets
        MarkdownEdit rm = MarkdownLines.toggleBullet("- a\n- b", 0, 7);
        assertEquals("a\nb", rm.replacement());
    }

    @Test
    void toggleTaskAddsBoxAndRemoves() {
        // plain line -> "- [ ] "
        assertEquals("- [ ] a\n- [ ] b", MarkdownLines.toggleTask("a\nb", 0, 3).replacement());
        // existing bullet keeps the marker, gains a box
        assertEquals("- [ ] a", MarkdownLines.toggleTask("- a", 0, 3).replacement());
        // already-a-task line is untouched when adding (mixed selection)
        assertEquals(
                "- [ ] a\n- [ ] b", MarkdownLines.toggleTask("- [ ] a\nb", 0, 9).replacement());
        // all task items -> stripped back to plain content
        assertEquals("a\nb", MarkdownLines.toggleTask("- [ ] a\n- [ ] b", 0, 15).replacement());
    }

    @Test
    void detectsEmptyItems() {
        assertTrue(MarkdownLines.isEmptyItem("- "));
        assertTrue(MarkdownLines.isEmptyItem("1. "));
        assertTrue(MarkdownLines.isEmptyItem("- [ ] "));
        assertTrue(MarkdownLines.isEmptyItem("> "));
        assertFalse(MarkdownLines.isEmptyItem("- item"));
        assertFalse(MarkdownLines.isEmptyItem("1. first"));
        assertFalse(MarkdownLines.isEmptyItem("plain"));
    }
}
