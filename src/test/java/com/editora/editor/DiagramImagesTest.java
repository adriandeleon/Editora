package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagramImagesTest {

    @Test
    void onlyTheLatestRenderForASurfaceRuns() {
        assertTrue(DiagramImages.superseded("preview", 1L, 2L));
        assertFalse(DiagramImages.superseded("preview", 2L, 2L));
        assertFalse(DiagramImages.superseded(null, 1L, 2L));
    }
}
