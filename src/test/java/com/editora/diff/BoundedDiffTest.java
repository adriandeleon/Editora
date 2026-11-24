package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The budgeted Myers diff and its coarse fallback (perf review 2026-11-23, P7). */
class BoundedDiffTest {

    /** {@code n} lines with nothing in common between the two sides, framed by a shared head and tail. */
    private static List<List<String>> farApart(int n) {
        List<String> left = new ArrayList<>(List.of("head"));
        List<String> right = new ArrayList<>(List.of("head"));
        for (int i = 0; i < n; i++) {
            left.add("left " + i);
            right.add("right " + i);
        }
        left.add("tail");
        right.add("tail");
        return List.of(left, right);
    }

    @Test
    void closeTextsGetTheFullDiff() {
        Patch<String> patch =
                BoundedDiff.orChangedMiddle(List.of("a", "b", "c", "d"), List.of("a", "x", "c", "d", "e"));
        assertEquals(2, patch.getDeltas().size());
        assertEquals(DeltaType.CHANGE, patch.getDeltas().get(0).getType());
        assertEquals(DeltaType.INSERT, patch.getDeltas().get(1).getType());
    }

    @Test
    void textsPastTheBudgetGetOneChangedMiddleQuickly() throws Exception {
        List<List<String>> sides = farApart(30_000); // 60,000 edits: tens of seconds unbounded
        Patch<String> patch = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> BoundedDiff.orChangedMiddle(sides.get(0), sides.get(1)));
        assertEquals(1, patch.getDeltas().size());
        AbstractDelta<String> delta = patch.getDeltas().get(0);
        assertEquals(DeltaType.CHANGE, delta.getType());
        assertEquals(1, delta.getSource().getPosition());
        assertEquals(30_000, delta.getSource().size());
        assertEquals(30_000, delta.getTarget().size());
        assertEquals(sides.get(1), patch.applyTo(sides.get(0)), "the coarse patch is still a valid patch");
    }

    @Test
    void theBudgetIsInEditsNotLines() {
        List<List<String>> sides = farApart(BoundedDiff.MAX_LINE_EDITS); // twice the budget in edits
        assertNull(BoundedDiff.diff(sides.get(0), sides.get(1), BoundedDiff.MAX_LINE_EDITS));
        List<List<String>> near = farApart(BoundedDiff.MAX_LINE_EDITS / 2 - 1);
        assertEquals(
                1,
                BoundedDiff.orChangedMiddle(near.get(0), near.get(1))
                        .getDeltas()
                        .size());
    }

    @Test
    void changedMiddleCoversPureInsertionsAndDeletions() {
        assertEquals(
                DeltaType.INSERT,
                BoundedDiff.changedMiddle(List.of("a", "b"), List.of("a", "x", "b"))
                        .getDeltas()
                        .get(0)
                        .getType());
        assertEquals(
                DeltaType.DELETE,
                BoundedDiff.changedMiddle(List.of("a", "x", "b"), List.of("a", "b"))
                        .getDeltas()
                        .get(0)
                        .getType());
        assertEquals(
                0,
                BoundedDiff.changedMiddle(List.of("a"), List.of("a"))
                        .getDeltas()
                        .size());
    }
}
