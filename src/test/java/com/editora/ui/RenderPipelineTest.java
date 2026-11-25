package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a toolkit without 3D means JavaFX fell back from the GPU pipeline it was asked for. */
class RenderPipelineTest {

    @Test
    void aGpuPipelineAskedForButNo3dIsAFallback() {
        assertTrue(RenderPipeline.fellBack("es2,sw", null, false));
        assertTrue(RenderPipeline.fellBack("d3d,es2,sw", "win", false));
        assertTrue(RenderPipeline.fellBack(" mtl , es2, sw", "mac", false));
        assertTrue(RenderPipeline.fellBack(null, "gtk", false), "JavaFX's default order starts with a GPU pipeline");
        assertTrue(RenderPipeline.fellBack("", null, false));
    }

    @Test
    void theGpuRunningIsNoFallback() {
        assertFalse(RenderPipeline.fellBack("es2,sw", null, true));
        assertFalse(RenderPipeline.fellBack(null, null, true));
    }

    @Test
    void softwareAskedForFirstIsNoFallback() {
        assertFalse(RenderPipeline.fellBack("sw", null, false), "the tests and the screenshot harness ask for sw");
        assertFalse(RenderPipeline.fellBack("SW,es2", null, false));
        assertFalse(RenderPipeline.fellBack("j2d", null, false));
    }

    @Test
    void headlessAndMonocleGlassHaveNoGpuToFallBackFrom() {
        assertFalse(RenderPipeline.fellBack("es2,sw", "Headless", false));
        assertFalse(RenderPipeline.fellBack(null, "Monocle", false));
    }
}
