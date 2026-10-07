package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayHostPositionTest {

    @Test
    void anchoredCardStaysInsideRightEdge() {
        assertEquals(516, OverlayHost.clampLeft(700, 480, 1000));
    }

    @Test
    void anchoredCardKeepsRequestedPositionWhenItFits() {
        assertEquals(120, OverlayHost.clampLeft(120, 480, 1000));
    }

    @Test
    void anchoredCardKeepsMinimumInsetAtLeftEdge() {
        assertEquals(4, OverlayHost.clampLeft(-20, 480, 1000));
    }

    /** A card shown at a line of text ({@code showAt}) goes under the line, or over it near the bottom. */
    @Test
    void aCardAtALineGoesBelowItUnlessOnlyTheSpaceAboveFits() {
        assertTrue(OverlayHost.goesBelow(300, 280, 200, 800), "room below");
        assertFalse(OverlayHost.goesBelow(700, 680, 200, 800), "no room below, room above");
        assertTrue(OverlayHost.goesBelow(300, 150, 600, 800), "fits on neither side: keep its top on screen");
    }
}
