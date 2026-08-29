package com.editora.editor;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimapViewportEdgeTest {

    @Test
    void theEdgeKeepsTheFillsHueAndOnlyRaisesItsAlpha() {
        Color fill = Color.web("#0969da24"); // Primer Light's viewport wash
        Color edge = Minimap.viewportEdge(fill);
        assertEquals(fill.getRed(), edge.getRed(), 1e-9);
        assertEquals(fill.getGreen(), edge.getGreen(), 1e-9);
        assertEquals(fill.getBlue(), edge.getBlue(), 1e-9);
        assertTrue(edge.getOpacity() > fill.getOpacity(), "the outline must read against the fill it bounds");
    }

    @Test
    void aVeryFaintFillStillGetsAnEdgeYouCanSee() {
        // Without a floor, tripling 4% alpha yields 12% — still invisible over a dense minimap.
        // Compared with a tolerance because Color stores its components as FLOAT: a Color built with
        // opacity 0.35 reads back as 0.34999999, so an exact >= on the floor is false by 6e-9.
        assertEquals(0.35, Minimap.viewportEdge(Color.web("#0969da0a")).getOpacity(), 1e-6);
    }

    @Test
    void anAlreadyStrongFillIsNotPushedPastOpaque() {
        assertEquals(1.0, Minimap.viewportEdge(Color.web("#0969dacc")).getOpacity(), 1e-9);
    }
}
