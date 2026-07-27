package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropZoneTest {

    @Test
    void theMiddleOfAGroupDropsIntoIt() {
        assertEquals(DropZone.CENTER, DropZone.of(400, 300, 800, 600));
        assertFalse(DropZone.CENTER.isSplit());
    }

    @Test
    void eachEdgeSplitsThatWay() {
        assertEquals(DropZone.LEFT, DropZone.of(5, 300, 800, 600));
        assertEquals(DropZone.RIGHT, DropZone.of(795, 300, 800, 600));
        assertEquals(DropZone.TOP, DropZone.of(400, 5, 800, 600));
        assertEquals(DropZone.BOTTOM, DropZone.of(400, 595, 800, 600));
        assertTrue(DropZone.LEFT.isSplit());
    }

    /**
     * A corner is ambiguous, and resolving it by raw pixel distance would make the answer depend on the
     * group's aspect ratio — the same visual corner of a tall column and a wide strip would split different
     * ways. Comparing how far into each edge margin the point is, as a fraction, keeps it symmetric.
     */
    @Test
    void cornersResolveTheSameWayRegardlessOfAspectRatio() {
        // Dead-on the top-left corner of a wide group and of a tall one: both should agree.
        assertEquals(DropZone.of(0, 0, 1600, 200), DropZone.of(0, 0, 200, 1600));

        // Just inside the left margin but hard against the top: vertical penetration is deeper, so TOP.
        assertEquals(DropZone.TOP, DropZone.of(90, 0, 800, 600));
        // Hard against the left but only just inside the top margin: LEFT.
        assertEquals(DropZone.LEFT, DropZone.of(0, 90, 800, 600));
    }

    /**
     * The edge margin is a fraction of the group, so it stays reachable in a narrow column — but capped, so
     * a very wide editor does not put its "split left" target hundreds of pixels from the left edge.
     */
    @Test
    void theEdgeMarginIsProportionalButCapped() {
        // Narrow column: 25% of 200 = 50px, so 40px in is still the edge.
        assertEquals(DropZone.LEFT, DropZone.of(40, 300, 200, 600));
        assertEquals(DropZone.CENTER, DropZone.of(60, 300, 200, 600));

        // Very wide: 25% would be 750px, but the cap holds it to MAX_EDGE_PX.
        assertEquals(DropZone.LEFT, DropZone.of(100, 300, 3000, 600));
        assertEquals(DropZone.CENTER, DropZone.of(200, 300, 3000, 600));
    }

    @Test
    void aDegenerateGroupIsAllCentre() {
        assertEquals(DropZone.CENTER, DropZone.of(0, 0, 0, 0));
        assertEquals(DropZone.CENTER, DropZone.of(5, 5, 0, 600));
    }
}
