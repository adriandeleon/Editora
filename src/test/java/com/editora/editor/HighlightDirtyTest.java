package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The range an incremental highlight pass owes, across edits and across passes that never apply. */
class HighlightDirtyTest {

    private static HighlightDirty clean() {
        HighlightDirty dirty = new HighlightDirty();
        dirty.applied(); // the initial full pass landed
        return dirty;
    }

    private static void assertRange(int start, int end, HighlightDirty dirty) {
        assertFalse(dirty.isClean());
        assertEquals(start, dirty.start(), "start of " + dirty);
        assertEquals(end, dirty.end(), "end of " + dirty);
    }

    @Test
    void aFreshBufferOwesTheWholeDocument() {
        assertRange(0, Integer.MAX_VALUE, new HighlightDirty());
    }

    @Test
    void anEditOwesExactlyTheTextItInserted() {
        HighlightDirty dirty = clean();
        assertTrue(dirty.isClean());
        dirty.edited(10, 0, 3);
        assertRange(10, 13, dirty);

        HighlightDirty deletion = clean();
        deletion.edited(10, 5, 0);
        assertRange(10, 10, deletion);
    }

    @Test
    void aPassThatNeverAppliedKeepsItsRangeForTheNextOne() {
        HighlightDirty dirty = clean();
        dirty.edited(100, 0, 1); // pass A is dispatched for this and then superseded: nothing is consumed
        dirty.edited(500, 0, 1); // a second edit further down — pass B must still cover offset 100
        assertRange(100, 501, dirty);
        dirty.edited(800, 0, 1);
        assertRange(100, 801, dirty);

        dirty.applied();
        dirty.edited(700, 0, 1);
        assertRange(700, 701, dirty);
    }

    @Test
    void theRangeFollowsTheTextThroughLaterEdits() {
        HighlightDirty dirty = clean();
        dirty.edited(100, 0, 10); // owes [100, 110)
        dirty.edited(20, 0, 5); // five characters typed above it: the same text is now [105, 115)
        assertRange(20, 115, dirty);

        HighlightDirty below = clean();
        below.edited(100, 0, 10);
        below.edited(200, 0, 5); // an edit below leaves the start alone
        assertRange(100, 205, below);

        HighlightDirty shrunk = clean();
        shrunk.edited(100, 0, 10);
        shrunk.edited(20, 30, 0); // thirty characters deleted above it: [70, 80), joined with the deletion point
        assertRange(20, 80, shrunk);
    }

    @Test
    void anEditThatSwallowsTheRangeCollapsesItOntoTheReplacement() {
        HighlightDirty dirty = clean();
        dirty.edited(100, 0, 10); // [100, 110)
        dirty.edited(90, 40, 2); // [90, 130) replaced by two characters
        assertRange(90, 92, dirty);

        HighlightDirty overlap = clean();
        overlap.edited(100, 0, 10); // [100, 110)
        overlap.edited(105, 20, 1); // the tail of the range goes with the deleted text
        assertRange(100, 106, overlap);
    }

    @Test
    void aMultiChangeEditIsMappedChangeByChange() {
        // Two carets typing "x": the second change is reported in the text as the first left it.
        HighlightDirty dirty = clean();
        dirty.edited(10, 0, 1);
        dirty.edited(51, 0, 1);
        assertRange(10, 52, dirty);
        // A batch of deletions, last position first reported against the longer text.
        HighlightDirty deletions = clean();
        deletions.edited(50, 10, 0);
        deletions.edited(10, 10, 0);
        assertRange(10, 40, deletions);
    }

    @Test
    void aRestyleRequestJoinsTheRangeWithoutMovingIt() {
        HighlightDirty dirty = clean();
        dirty.include(40, 60);
        assertRange(40, 60, dirty);
        dirty.edited(10, 0, 2);
        assertRange(10, 62, dirty);
        dirty.include(5, 8);
        assertRange(5, 62, dirty);
    }

    @Test
    void invalidateOwesEverythingAndEditsCannotNarrowIt() {
        HighlightDirty dirty = clean();
        dirty.edited(25, 0, 1);
        dirty.invalidate(); // language changed
        dirty.edited(300, 0, 4);
        assertRange(0, Integer.MAX_VALUE, dirty);
    }
}
