package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchFilesTest {

    @Test
    void recognizesPatchAndDiffExtensionsCaseInsensitively() {
        assertTrue(PatchFiles.isPatchFile("fix-login.patch"));
        assertTrue(PatchFiles.isPatchFile("changes.DIFF"));
        assertTrue(PatchFiles.isPatchFile("/abs/path/to/my.Patch"));
    }

    @Test
    void rejectsOtherExtensions() {
        assertFalse(PatchFiles.isPatchFile("Main.java"));
        assertFalse(PatchFiles.isPatchFile("notes.txt"));
        assertFalse(PatchFiles.isPatchFile("README"));
        assertFalse(PatchFiles.isPatchFile(""));
        assertFalse(PatchFiles.isPatchFile(null));
    }
}
