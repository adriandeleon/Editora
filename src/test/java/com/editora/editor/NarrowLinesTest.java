package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NarrowLinesTest {

    @Test
    void theRegionsFirstLineIsTheNumberOfLineBreaksBeforeIt() {
        String doc = "a\nb\nc\nd\n";
        assertEquals(0, NarrowLines.firstLine(doc, 0));
        assertEquals(0, NarrowLines.firstLine(doc, 1), "still on the first line");
        assertEquals(2, NarrowLines.firstLine(doc, 4));
        assertEquals(4, NarrowLines.firstLine(doc, 999), "clamped to the document");
    }

    @Test
    void aDocumentLineInsideTheRegionIsRebased() {
        // region = document lines 19..24 (six lines)
        assertEquals(0, NarrowLines.toRegionLine(19, 6, 19));
        assertEquals(3, NarrowLines.toRegionLine(19, 6, 22));
        assertEquals(5, NarrowLines.toRegionLine(19, 6, 24));
    }

    @Test
    void aDocumentLineOutsideTheRegionIsNotMappedOntoIt() {
        assertEquals(-1, NarrowLines.toRegionLine(19, 6, 3), "before the region — not the region's 4th line");
        assertEquals(-1, NarrowLines.toRegionLine(19, 6, 25));
        assertEquals(-1, NarrowLines.toRegionLine(19, 6, 300));
    }
}
