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
        assertEquals(
                "- a\n- b",
                MarkdownLines.toggleTask("- [ ] a\n- [ ] b", 0, 15).replacement(),
                "the box goes, the item stays");
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

    // ---- caret mapping, block bounds, one line model ----

    /** {@code ‸} marks a collapsed caret, {@code «»} a selection; the result is marked the same way. */
    private static String run(String marked, java.util.function.BiFunction<String, int[], MarkdownEdit> op) {
        int caret = marked.indexOf('‸');
        int a = marked.indexOf('«');
        int b = marked.indexOf('»') - 1;
        String clean = marked.replace("‸", "").replace("«", "").replace("»", "");
        MarkdownEdit e = op.apply(clean, caret >= 0 ? new int[] {caret, caret} : new int[] {a, b});
        if (e == null) {
            return marked;
        }
        StringBuilder out = new StringBuilder(clean.substring(0, e.from()) + e.replacement() + clean.substring(e.to()));
        if (e.selStart() == e.selEnd()) {
            out.insert(e.selStart(), '‸');
        } else {
            out.insert(e.selEnd(), '»').insert(e.selStart(), '«');
        }
        return out.toString();
    }

    private static String bullet(String marked) {
        return run(marked, (t, s) -> MarkdownLines.toggleBullet(t, s[0], s[1]));
    }

    private static String task(String marked) {
        return run(marked, (t, s) -> MarkdownLines.toggleTask(t, s[0], s[1]));
    }

    private static String enter(String marked) {
        return run(marked, (t, s) -> MarkdownLines.enter(t, s[0]));
    }

    private static String tab(String marked) {
        return run(marked, (t, s) -> MarkdownLines.indentItem(t, s[0], false));
    }

    private static String shiftTab(String marked) {
        return run(marked, (t, s) -> MarkdownLines.indentItem(t, s[0], true));
    }

    private static String backspace(String marked) {
        return run(marked, (t, s) -> MarkdownLines.backspace(t, s[0]));
    }

    @Test
    void aLineCommandKeepsTheCaretOnItsText() {
        // It used to select the rewritten line, so the next key typed replaced the line.
        assertEquals("- My it‸em\nnext", bullet("My it‸em\nnext"));
        assertEquals("- [ ] My it‸em", task("My it‸em"));
        assertEquals("My it‸em", bullet("- My it‸em"));
        assertEquals("- a «word» b", bullet("a «word» b"));
    }

    @Test
    void aSelectionEndingAtALineStartDoesNotTakeTheNextLine() {
        assertEquals("«- one\n»two\n", bullet("«one\n»two\n"));
        assertEquals("«- [ ] one\n»two", task("«one\n»two"));
    }

    @Test
    void markersAreConvertedNotStacked() {
        assertEquals("«- a\n- b»", bullet("«1. a\n2. b»"));
        assertEquals("«- a\n- b»", bullet("«- a\nb»"));
        assertEquals("‸a", bullet("- [ ] ‸a"), "bullet off takes the task box with it");
        assertEquals("1. [ ] ‸a", task("1. ‸a"));
        assertEquals("  * [ ] ‸a", task("  * ‸a"));
        assertEquals("> - ‸q", bullet("> ‸q"));
        assertNull(MarkdownLines.toggleBullet("", 0, 0));
        assertNull(MarkdownLines.toggleTask("\n", 0, 0));
    }

    @Test
    void enterKeepsAListInsideABlockquote() {
        assertEquals("> - a\n> - ‸", enter("> - a‸"));
        assertEquals("> ‸", enter("> - ‸"), "an empty item steps out to the quote");
        assertEquals("‸", enter("> ‸"));
        assertEquals(4, MarkdownLines.markerLength("> - a"));
    }

    @Test
    void enterKeepsTheTaskBoxOfANumberedItem() {
        assertEquals("1. [x] t\n2. [ ] ‸", enter("1. [x] t‸"));
        assertEquals("2. [ ] ", MarkdownLines.continuation("1. [ ] t"));
    }

    @Test
    void enterRenumbersTheItemsThatFollow() {
        assertEquals("1. one\n2. ‸\n3. two\n4. three\n", enter("1. one‸\n2. two\n3. three\n"));
        assertEquals("1. one\n2. ‸\n   - sub\n3. two\n\n3. other", enter("1. one‸\n   - sub\n2. two\n\n3. other"));
        assertEquals("1. a\n1. ‸\n1. b", enter("1. a‸\n1. b"), "a list numbered 1. 1. 1. keeps its style");
        assertEquals("9) a\n10) ‸\n11) b", enter("9) a‸\n10) b"));
    }

    @Test
    void enterMovesTheRestOfTheItemWithoutItsLeadingBlank() {
        assertEquals("- one\n- ‸two", enter("- one‸ two"));
        assertEquals("1. a\n2. ‸b\n3. c", enter("1. a‸ b\n2. c"));
    }

    @Test
    void enterLeavesNonItemsAlone() {
        assertNull(MarkdownLines.enter("plain", 5));
        assertNull(MarkdownLines.enter("- one", 0), "caret in front of the marker");
        assertNull(MarkdownLines.enter("- - -", 5), "a thematic break");
        assertNull(MarkdownLines.enter("```yaml\n- a\n```", 11), "inside a fenced block");
    }

    @Test
    void enterOnAnEmptyNestedItemStepsOutOneLevel() {
        assertEquals("- a\n- ‸\n", enter("- a\n  - ‸\n"));
        assertEquals("- a\n‸\n", enter("- a\n- ‸\n"));
        assertEquals("1. a\n   1. b\n2. ‸", enter("1. a\n   1. b\n   2. ‸"));
    }

    @Test
    void backspaceOnAnEmptyItemStepsOutOneLevel() {
        assertEquals("- a\n- ‸", backspace("- a\n  - ‸"));
        assertEquals("- a\n‸", backspace("- a\n- ‸"));
        assertEquals("> ‸", backspace("> - [ ] ‸"));
        assertNull(MarkdownLines.backspace("- a", 3));
        assertNull(MarkdownLines.backspace("- ", 1), "caret not at the end");
        assertNull(MarkdownLines.backspace("x\n", 2), "an empty line");
    }

    @Test
    void tabNestsAListItemUnderTheOneAbove() {
        assertEquals("- a\n  - b‸\n", tab("- a\n- b‸\n"));
        assertEquals("- a\n  - ‸", tab("- a\n- ‸"));
        assertEquals("1. a\n   1. b‸\n2. c", tab("1. a\n2. b‸\n3. c"));
        assertEquals("1. a\n   1. x\n   2. b‸", tab("1. a\n   1. x\n2. b‸"));
        assertEquals("- a\n\n  - b‸", tab("- a\n\n- b‸"), "a loose list");
    }

    @Test
    void tabOnAnItemThatCannotNestChangesNothingButIsStillAnEdit() {
        MarkdownEdit first = MarkdownLines.indentItem("- a", 3, false);
        assertEquals(new MarkdownEdit(3, 3, "", 3, 3), first, "no spaces in the middle of the item");
        assertEquals("- a\n  - b‸", tab("- a\n  - b‸"), "already the first child");
        assertNull(MarkdownLines.indentItem("plain", 2, false));
        assertNull(MarkdownLines.indentItem("- - -", 2, false));
    }

    @Test
    void shiftTabMovesAnItemOutToItsParentsLevel() {
        assertEquals("- a\n- b‸", shiftTab("- a\n  - b‸"));
        assertEquals(
                "1. a\n2. c‸\n   1. d\n3. e",
                shiftTab("1. a\n   1. b\n   2. c‸\n   3. d\n2. e").replace("   1. b\n", ""));
        assertNull(MarkdownLines.indentItem("- a", 3, true), "nothing to outdent: the ordinary Shift-Tab runs");
    }

    @Test
    void codeIsRecognisedWhereverItIsNested() {
        // A fence inside a list item is indented past the three columns a top-level fence may have.
        String nested = "1. step\n\n      ```yaml\n      - a\n      ```\n";
        assertNull(MarkdownLines.enter(nested, nested.indexOf("- a") + 3));
        String quoted = "> ~~~\n> - a\n> ~~~\n";
        assertNull(MarkdownLines.enter(quoted, quoted.indexOf("- a") + 3));
        assertNull(MarkdownLines.enter("text\n\n    - code\n", 16), "an indented code block");
        assertNull(MarkdownLines.enter("---\ntags:\n- a\n---\n", 13), "front matter");
        assertNull(MarkdownLines.enter("<!--\n- a\n-->\n", 8), "an HTML comment");
        assertNull(MarkdownLines.indentItem(nested, nested.indexOf("- a") + 3, false));
        assertEquals("- a\n  - b\n  - ‸", enter("- a\n  - b‸"), "a nested item is still an item");
    }

    @Test
    void aListIsNumberedInSequenceAgainAfterAnItemMoved() {
        assertEquals("1. b‸\n2. a\n3. c", run("2. b‸\n1. a\n3. c", (t, s) -> MarkdownLines.renumber(t, s[0])));
        assertEquals(
                "5. a\n6. c‸\n   1. sub\n7. b",
                run("5. a\n7. c‸\n   1. sub\n6. b", (t, s) -> MarkdownLines.renumber(t, s[0])),
                "from the smallest number; nested lines are left alone");
        assertEquals("9. a\n10. b‸", run("10. a\n9. b‸", (t, s) -> MarkdownLines.renumber(t, s[0])));
        assertNull(MarkdownLines.renumber("1. a\n1. b\n1. c", 5), "a list numbered 1. 1. 1. keeps its style");
        assertNull(MarkdownLines.renumber("1. a\n2. b", 5), "already in order");
        assertNull(MarkdownLines.renumber("- a\n- b", 5));
        assertNull(MarkdownLines.renumber("plain", 2));
    }

    // ---- feature-gap pass: more line commands ----

    @Test
    void theNumberedListToggleNumbersConvertsAndRemoves() {
        assertEquals("«1. a\n2. b\n\n3. c»", run("«a\nb\n\nc»", (t, s) -> MarkdownLines.toggleOrdered(t, s[0], s[1])));
        assertEquals("1. it‸em", run("- it‸em", (t, s) -> MarkdownLines.toggleOrdered(t, s[0], s[1])));
        assertEquals(
                "1. [ ] t‸", run("- [ ] t‸", (t, s) -> MarkdownLines.toggleOrdered(t, s[0], s[1])), "the box stays");
        assertEquals("«a\nb»", run("«1. a\n2. b»", (t, s) -> MarkdownLines.toggleOrdered(t, s[0], s[1])));
        assertEquals(
                "«1. a\n   1. x\n   2. y\n2. b»",
                run("«- a\n   - x\n   - y\n- b»", (t, s) -> MarkdownLines.toggleOrdered(t, s[0], s[1])),
                "each level counts for itself");
        assertNull(MarkdownLines.toggleOrdered("", 0, 0));
    }

    @Test
    void theQuoteToggleQuotesAWholeBlockAndTakesOneLevelOff() {
        assertEquals("> a‸", run("a‸", (t, s) -> MarkdownLines.toggleQuote(t, s[0], s[1])));
        assertEquals("«> a\n>\n> b»", run("«a\n\nb»", (t, s) -> MarkdownLines.toggleQuote(t, s[0], s[1])), "one quote");
        assertEquals("«a\n\nb»", run("«> a\n>\n> b»", (t, s) -> MarkdownLines.toggleQuote(t, s[0], s[1])));
        assertEquals("> q‸", run("> > q‸", (t, s) -> MarkdownLines.toggleQuote(t, s[0], s[1])), "one level at a time");
        assertEquals("«> > a\n> b»", run("«> a\nb»", (t, s) -> MarkdownLines.toggleQuote(t, s[0], s[1])));
        assertEquals(
                "> ‸", run("‸", (t, s) -> MarkdownLines.toggleQuote(t, s[0], s[1])), "an empty line starts a quote");
    }

    @Test
    void theFenceCommandWrapsLinesAndUnwrapsTheBodyOfABlock() {
        assertEquals(
                "```‸\nint x;\nint y;\n```",
                run("«int x;\nint y;»", (t, s) -> MarkdownLines.toggleFence(t, s[0], s[1])));
        assertEquals("a\n```‸\n\n```\nb", run("a\n‸\nb", (t, s) -> MarkdownLines.toggleFence(t, s[0], s[1])));
        assertEquals(
                "````‸\n```js\nx\n```\n````",
                run("«```js\nx\n```»", (t, s) -> MarkdownLines.toggleFence(t, s[0], s[1])));
        assertEquals(
                "a\n«x\ny»\nb", run("a\n```sh\n«x\ny»\n```\nb", (t, s) -> MarkdownLines.toggleFence(t, s[0], s[1])));
        assertEquals("x‸", run("~~~\nx‸\n~~~", (t, s) -> MarkdownLines.toggleFence(t, s[0], s[1])));
    }

    @Test
    void toggleDoneTicksAndUnticksTaskItems() {
        assertEquals("- [x] a‸", run("- [ ] a‸", (t, s) -> MarkdownLines.toggleDone(t, s[0], s[1])));
        assertEquals("- [ ] a‸", run("- [x] a‸", (t, s) -> MarkdownLines.toggleDone(t, s[0], s[1])));
        assertEquals(
                "«- [x] a\ntext\n1. [x] b»",
                run("«- [x] a\ntext\n1. [ ] b»", (t, s) -> MarkdownLines.toggleDone(t, s[0], s[1])),
                "some open: all done; other lines untouched");
        assertEquals(
                "«- [ ] a\n- [ ] b»", run("«- [X] a\n- [x] b»", (t, s) -> MarkdownLines.toggleDone(t, s[0], s[1])));
        assertNull(MarkdownLines.toggleDone("- plain", 3, 3), "no task item: nothing to do");
    }

    @Test
    void tabAndShiftTabTakeAnItemsChildrenAlong() {
        assertEquals("1. a\n   1. b‸\n      - kid\n\n      more\n2. c", tab("1. a\n2. b‸\n   - kid\n\n   more\n3. c"));
        assertEquals(
                "1. a\n   1. b\n2. c‸\n   - kid\n   1. d\n3. e",
                shiftTab("1. a\n   1. b\n   2. c‸\n      - kid\n   3. d\n2. e"));
        assertEquals("- a\n  - b‸\n    - kid\n- c", tab("- a\n- b‸\n  - kid\n- c"));
    }

    @Test
    void renumberingMapsBothEndsOfASelection() {
        MarkdownEdit e = MarkdownLines.renumber("9. a\n8. b\n10. c\n", 0, 10);
        assertEquals("8. a\n9. b\n10. c", e.replacement());
        assertEquals(0, e.selStart());
        assertEquals(10, e.selEnd());
        assertNull(MarkdownLines.renumber("text\n1. a", 0, 6), "the selection does not start in a list");
    }
}
