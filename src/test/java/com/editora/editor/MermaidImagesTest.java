package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidImagesTest {

    @Test
    void detectsTheHeadlessChromeMissingFailure() {
        assertTrue(MermaidImages.looksLikeChromeMissing("Error: Could not find Chrome (ver. 148.0.7778.97)."));
        assertTrue(MermaidImages.looksLikeChromeMissing("…/node_modules/puppeteer-core/lib/.../BrowserLauncher.js"));
        assertTrue(MermaidImages.looksLikeChromeMissing("run `npx puppeteer browsers install chrome-headless-shell`"));
    }

    @Test
    void leavesOtherFailuresAlone() {
        assertFalse(MermaidImages.looksLikeChromeMissing("Parse error on line 2: unexpected token"));
        assertFalse(MermaidImages.looksLikeChromeMissing(""));
        assertFalse(MermaidImages.looksLikeChromeMissing(null));
    }

    @Test
    void aRenderIsSupersededWhenANewerGenerationExistsForItsSurface() {
        // #458: gen 1 was queued, then gen 2 arrived for the same surface → gen 1 must skip its ~4 s spawn.
        assertTrue(MermaidImages.superseded("file.mmd", 1L, 2L));
        // The latest generation still renders.
        assertFalse(MermaidImages.superseded("file.mmd", 2L, 2L));
        // No recorded generation (nothing newer) → render.
        assertFalse(MermaidImages.superseded("file.mmd", 1L, null));
    }

    @Test
    void aNullSurfaceIsNeverSuperseded() {
        // Markdown diagram blocks pass no surface — distinct concurrent diagrams, never coalesced.
        assertFalse(MermaidImages.superseded(null, 1L, 5L));
    }
}
