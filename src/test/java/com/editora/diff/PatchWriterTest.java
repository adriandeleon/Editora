package com.editora.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void finalNewlineOnlyDifferenceProducesApplicablePatch() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "same\n", "same");
        assertTrue(patch.contains("-same"), patch);
        assertTrue(patch.contains("+same"), patch);
        assertTrue(patch.contains("\\ No newline at end of file"), patch);
        PatchParser.FilePatch parsed = PatchParser.parse(patch).get(0);
        assertTrue(parsed.oldFinalNewline());
        assertTrue(!parsed.newFinalNewline());
    }

    @Test
    void writesFinalNewlineMarkersAlongsideContentChanges() {
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "first\nold\nlast", "first\nnew\nlast\n");

        assertTrue(patch.contains("-old"), patch);
        assertTrue(patch.contains("+new"), patch);
        assertTrue(patch.contains("\\ No newline at end of file"), patch);
    }

    @Test
    void appendsAnEofHunkWhenContentChangeIsFarFromFinalNewlineChange() {
        String middle = "unchanged\n".repeat(12);
        String patch = PatchWriter.unifiedDiff("a/f", "b/f", "old\n" + middle + "last", "new\n" + middle + "last\n");

        assertTrue(patch.indexOf("@@") != patch.lastIndexOf("@@"), patch);
        assertTrue(patch.contains("\\ No newline at end of file"), patch);
    }
}
