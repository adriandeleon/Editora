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
    void anOrderedNumberTooLargeToIncrementDoesNotThrow() {
        // 20 nines overflows a long; must not throw NumberFormatException out of the Enter key filter.
        assertNull(MarkdownLines.continuation("99999999999999999999. item"), "overflowing number → no continuation");
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

    @Test
    void emptyMarkerBackspaceDeletesWholeMarker() {
        assertEquals(2, MarkdownLines.emptyMarkerDeleteLength("- ", 2)); // bullet
        assertEquals(3, MarkdownLines.emptyMarkerDeleteLength("1. ", 3)); // ordered
        assertEquals(2, MarkdownLines.emptyMarkerDeleteLength("> ", 2)); // quote
        assertEquals(6, MarkdownLines.emptyMarkerDeleteLength("- [ ] ", 6)); // task box
        assertEquals(4, MarkdownLines.emptyMarkerDeleteLength("  - ", 4)); // indented
        assertEquals(0, MarkdownLines.emptyMarkerDeleteLength("- Item", 6)); // has content
        assertEquals(0, MarkdownLines.emptyMarkerDeleteLength("- ", 1)); // caret not at end
        assertEquals(0, MarkdownLines.emptyMarkerDeleteLength("text", 4)); // not a marker
    }

    @Test
    void aListLineInsideAnOpenFenceHasNoMarkerForEnter() {
        String text = "```yaml\n- name: x";
        assertEquals(0, MarkdownLines.listMarkerLength(text, 8, "- name: x"));
        String tilde = "~~~\n1. step";
        assertEquals(0, MarkdownLines.listMarkerLength(tilde, 4, "1. step"));
    }

    @Test
    void aListLineAfterTheFenceClosesKeepsItsMarker() {
        String text = "```\ncode\n```\n- item";
        assertEquals(2, MarkdownLines.listMarkerLength(text, 13, "- item"));
        assertEquals(2, MarkdownLines.listMarkerLength("- item", 0, "- item"), "no fence at all");
    }

    @Test
    void aFenceIsClosedOnlyByItsOwnKindAndLength() {
        // ~~~ does not close ```, and a shorter run does not close a longer one.
        assertTrue(MarkdownLines.insideFence("```\n~~~\n", 8));
        assertTrue(MarkdownLines.insideFence("````\n```\n", 9));
        assertFalse(MarkdownLines.insideFence("```\n````\n", 9), "a longer run does close it");
        assertTrue(MarkdownLines.insideFence("```\n``` not a closer\n", 22), "a closer carries no text");
        assertFalse(MarkdownLines.insideFence("`code` and ``` inline ```\n", 26), "an inline run opens nothing");
        assertFalse(MarkdownLines.insideFence("    ```\n", 8), "four spaces of indent is a code block, not a fence");
    }

    @Test
    void aSpacedThematicBreakIsNotAListItem() {
        assertEquals(0, MarkdownLines.listMarkerLength("- - -", 0, "- - -"));
        assertEquals(0, MarkdownLines.listMarkerLength("* * *", 0, "* * *"));
        assertEquals(0, MarkdownLines.listMarkerLength(" -  -  - -", 0, " -  -  - -"));
        assertEquals(2, MarkdownLines.listMarkerLength("- - x", 0, "- - x"), "a nested item, not a rule");
        assertEquals(2, MarkdownLines.listMarkerLength("- -", 0, "- -"), "two marks are not a rule");
    }

    @Test
    void theDocumentIsOnlyAskedForWhenTheLineIsAListItem() {
        // Enter runs this on every line of a Markdown file; only a real item needs the text above it (the
        // fence test), so an editor must not have to build its whole document to hear "no marker".
        int[] asked = new int[1];
        java.util.function.Supplier<String> document = () -> {
            asked[0]++;
            return "intro\n\n- item\nplain text\n- - -\n";
        };
        assertEquals(0, MarkdownLines.listMarkerLength(document, 16, "plain text"));
        assertEquals(0, MarkdownLines.listMarkerLength(document, 27, "- - -"), "a thematic break");
        assertEquals(0, asked[0], "neither needed the document");
        assertEquals(2, MarkdownLines.listMarkerLength(document, 7, "- item"));
        assertEquals(1, asked[0]);
    }

    @Test
    void aTableRowIsRecognizedFromItsLineAlone() {
        // The same gate for the table keys: blockBounds finds nothing unless the caret's own line is a row.
        assertTrue(MarkdownTable.isRow("| a | b |"));
        assertFalse(MarkdownTable.isRow("plain text"));
        assertFalse(MarkdownTable.isRow(""));
        assertNull(MarkdownTable.blockBounds("| a |\nplain text\n| b |", 8));
    }
}
