package com.editora.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PatchWriterTest {

    @Test
    void identicalTextYieldsEmptyPatch() {
        assertEquals("", PatchWriter.unifiedDiff("a/f", "b/f", "x\ny\n", "x\ny\n"));
    }

    @Test
    void emitsUnifiedDiffWithHeadersAndHunk() {
        String patch = PatchWriter.unifiedDiff("a/file.txt", "b/file.txt", "x\ny\nz\n", "x\nY\nz\n");
        assertTrue(patch.startsWith("--- a/file.txt\n+++ b/file.txt\n"), patch);
        assertTrue(patch.contains("@@"), patch);
        assertTrue(patch.contains("-y"), patch);
        assertTrue(patch.contains("+Y"), patch);
        assertTrue(patch.endsWith("\n"));
    }
}
