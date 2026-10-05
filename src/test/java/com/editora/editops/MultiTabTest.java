package com.editora.editops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultiTabTest {

    /** A code buffer with a four-space indent, as {@code EditorBuffer} asks for one caret. */
    private static final MultiTab.Edits JAVA =
            (text, start, end, shift) -> Indenter.smartTab(text, start, end, "java", 4, shift, Boolean.TRUE, 4);

    private static final MultiTab.Edits PROSE =
            (text, start, end, shift) -> PlainTab.edit(text, start, end, "markdown", 4, shift, Boolean.TRUE, 4);

    private static String apply(String text, MultiTab.Plan plan) {
        StringBuilder sb = new StringBuilder(text);
        for (int i = plan.changes().size() - 1; i >= 0; i--) {
            MultiTab.Change c = plan.changes().get(i);
            sb.replace(c.from(), c.to(), c.replacement());
        }
        return sb.toString();
    }

    private static int[] caret(int at) {
        return new int[] {at, at};
    }

    @Test
    void tabInsertsOneIndentUnitAtEveryCaretNotATabCharacter() {
        String text = "ab cd\nef gh\nij kl";
        MultiTab.Plan plan = MultiTab.plan(text, List.of(caret(2), caret(8), caret(14)), false, PROSE);
        assertEquals("ab     cd\nef     gh\nij     kl", apply(text, plan));
        assertEquals(
                List.of(new MultiTab.Range(6, 6), new MultiTab.Range(16, 16), new MultiTab.Range(26, 26)),
                plan.ranges());
    }

    @Test
    void theOrderTheCaretsWereGivenInIsKept() {
        String text = "ab cd\nef gh";
        MultiTab.Plan plan = MultiTab.plan(text, List.of(caret(8), caret(2)), false, PROSE); // primary below
        assertEquals("ab     cd\nef     gh", apply(text, plan));
        assertEquals(List.of(new MultiTab.Range(16, 16), new MultiTab.Range(6, 6)), plan.ranges());
    }

    @Test
    void selectionsAreIndentedAsBlocksAndStaySelected() {
        String text = "a\nb\nc\nd\n";
        // Lines 0-1 selected by one caret (backwards), line 3 by another.
        MultiTab.Plan plan = MultiTab.plan(text, List.of(new int[] {3, 0}, new int[] {6, 7}), false, JAVA);
        assertEquals("    a\n    b\nc\n    d\n", apply(text, plan));
        assertEquals(new MultiTab.Range(11, 0), plan.ranges().get(0), "anchor at the end, as it was selected");
        assertEquals(new MultiTab.Range(14, 19), plan.ranges().get(1));
    }

    @Test
    void shiftTabDedentsEveryCaretsLine() {
        String text = "    a\n        b\nc\n";
        MultiTab.Plan plan = MultiTab.plan(text, List.of(caret(5), caret(15), caret(17)), true, JAVA);
        assertEquals("a\n    b\nc\n", apply(text, plan));
        assertEquals(
                List.of(new MultiTab.Range(1, 1), new MultiTab.Range(7, 7), new MultiTab.Range(9, 9)), plan.ranges());
    }

    @Test
    void twoCaretsOnOneLineDedentItOnce() {
        String text = "        ab cd\n";
        MultiTab.Plan plan = MultiTab.plan(text, List.of(caret(10), caret(13)), true, JAVA);
        assertEquals("    ab cd\n", apply(text, plan));
        assertEquals(List.of(new MultiTab.Range(6, 6), new MultiTab.Range(9, 9)), plan.ranges());
    }

    @Test
    void twoSelectionsSharingALineIndentItOnce() {
        String text = "a\nb\nc\n";
        // [0,3) covers lines 0-1; [2,5) covers lines 1-2: line 1 is in both.
        MultiTab.Plan plan = MultiTab.plan(text, List.of(new int[] {0, 3}, new int[] {2, 5}), false, JAVA);
        assertEquals("    a\n    b\nc\n", apply(text, plan), "the second block overlaps the first and yields");
        assertEquals(new MultiTab.Range(0, 11), plan.ranges().get(0));
    }

    @Test
    void aCaretThatNeedsNoChangeIsStillCarriedPastTheOthers() {
        // Line 2 is already at the indent its context implies: Tab only moves that caret to the indent's end.
        String text = "{\nx\n    y\n}";
        MultiTab.Plan plan = MultiTab.plan(text, List.of(caret(2), caret(4)), false, JAVA);
        assertEquals("{\n    x\n    y\n}", apply(text, plan));
        assertEquals(List.of(new MultiTab.Range(6, 6), new MultiTab.Range(12, 12)), plan.ranges());
    }

    @Test
    void aBufferThatDeclinesTabDeclinesForAllCarets() {
        assertNull(MultiTab.plan("a\nb", List.of(caret(0), caret(2)), false, (t, s, e, shift) -> null));
    }
}
