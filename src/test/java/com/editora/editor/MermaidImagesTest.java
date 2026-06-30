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
}
