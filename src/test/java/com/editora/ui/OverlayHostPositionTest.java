package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
