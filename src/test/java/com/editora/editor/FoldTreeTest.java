package com.editora.editor;

import java.util.List;

import com.editora.editor.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tree queries over the flat fold-region list: depth, level, descendants, and fold navigation. */
class FoldTreeTest {

    // A three-deep nest plus a sibling at the top level:
    //   0..20  outer        (level 1, depth 0)
    //     2..15  mid         (level 2, depth 1)
    //       4..8   inner     (level 3, depth 2)
    //   30..40 sibling       (level 1, depth 0)
    private static final Region OUTER = new Region(0, 20);
    private static final Region MID = new Region(2, 15);
    private static final Region INNER = new Region(4, 8);
    private static final Region SIBLING = new Region(30, 40);
    private static final List<Region> TREE = List.of(OUTER, MID, INNER, SIBLING);

    @Test
    void containsIsStrictAndSpanBased() {
        assertTrue(FoldTree.contains(OUTER, MID));
        assertTrue(FoldTree.contains(OUTER, INNER));
        assertTrue(FoldTree.contains(MID, INNER));
        assertFalse(FoldTree.contains(MID, OUTER)); // not the other way
        assertFalse(FoldTree.contains(OUTER, SIBLING)); // disjoint
        assertFalse(FoldTree.contains(OUTER, OUTER)); // not itself
        assertFalse(FoldTree.contains(OUTER, new Region(0, 20))); // equal span is not containment
    }

    @Test
    void depthCountsEnclosingRegions() {
        assertEquals(0, FoldTree.depthOf(TREE, OUTER));
        assertEquals(1, FoldTree.depthOf(TREE, MID));
        assertEquals(2, FoldTree.depthOf(TREE, INNER));
        assertEquals(0, FoldTree.depthOf(TREE, SIBLING));
    }

    @Test
    void levelSelectsRegionsAtThatDepth() {
        assertEquals(List.of(OUTER, SIBLING), FoldTree.atLevel(TREE, 1));
        assertEquals(List.of(MID), FoldTree.atLevel(TREE, 2));
        assertEquals(List.of(INNER), FoldTree.atLevel(TREE, 3));
        assertTrue(FoldTree.atLevel(TREE, 4).isEmpty());
    }

    @Test
    void descendantsAreEveryContainedRegion() {
        assertEquals(List.of(MID, INNER), FoldTree.descendantsOf(TREE, OUTER));
        assertEquals(List.of(INNER), FoldTree.descendantsOf(TREE, MID));
        assertTrue(FoldTree.descendantsOf(TREE, INNER).isEmpty());
    }

    @Test
    void innermostContainingPicksTheDeepestRegion() {
        assertEquals(INNER, FoldTree.innermostContaining(TREE, 6)); // deep in the nest
        assertEquals(MID, FoldTree.innermostContaining(TREE, 10)); // inside mid, below inner
        assertEquals(OUTER, FoldTree.innermostContaining(TREE, 18)); // only outer here
        assertEquals(SIBLING, FoldTree.innermostContaining(TREE, 35));
        assertNull(FoldTree.innermostContaining(TREE, 25)); // between the two top-level regions
    }

    @Test
    void parentFoldAscendsFromBodyThenHeader() {
        // In the body of inner → jump to inner's own header.
        assertEquals(INNER, FoldTree.parentFold(TREE, 6));
        // Already on inner's header → ascend to the enclosing mid.
        assertEquals(MID, FoldTree.parentFold(TREE, INNER.startLine()));
        // On mid's header → ascend to outer.
        assertEquals(OUTER, FoldTree.parentFold(TREE, MID.startLine()));
        // On outer's header → nothing above it.
        assertNull(FoldTree.parentFold(TREE, OUTER.startLine()));
        // Outside every region → nothing.
        assertNull(FoldTree.parentFold(TREE, 25));
    }

    @Test
    void nextAndPreviousFoldWalkByHeaderLine() {
        assertEquals(MID, FoldTree.nextFold(TREE, 0)); // after outer's header
        assertEquals(INNER, FoldTree.nextFold(TREE, 2)); // after mid's header
        assertEquals(SIBLING, FoldTree.nextFold(TREE, 16)); // past the whole nest
        assertNull(FoldTree.nextFold(TREE, 30)); // nothing after the last header

        assertEquals(INNER, FoldTree.previousFold(TREE, 30)); // last header before the sibling
        assertEquals(MID, FoldTree.previousFold(TREE, 4)); // before inner's header
        assertEquals(OUTER, FoldTree.previousFold(TREE, 2)); // before mid's header
        assertNull(FoldTree.previousFold(TREE, 0)); // nothing before the first header
    }

    @Test
    void emptyRegionsAreSafe() {
        List<Region> none = List.of();
        assertNull(FoldTree.innermostContaining(none, 5));
        assertNull(FoldTree.parentFold(none, 5));
        assertNull(FoldTree.nextFold(none, 5));
        assertNull(FoldTree.previousFold(none, 5));
        assertTrue(FoldTree.atLevel(none, 1).isEmpty());
    }
}
