package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LspCoordinator.paddedWindow} — the over-scan window for inlay-hint / semantic-token requests,
 * clamped to the document (#724).
 *
 * <p>Unclamped, {@code visible[1] + 200} names a line that does not exist for any file shorter than the
 * viewport plus the pad, and {@code LspManager} adds another {@code +1} for the exclusive range end. Every
 * response path ends in {@code .exceptionally(t -> List.of())}, so a server that rejects an out-of-range
 * range fails silently and indistinguishably from "no results".
 */
class LspWindowClampTest {

    private static final int PAD = 200;

    @Test
    void aShortFileNeverAsksBeyondItsLastLine() {
        // The #724 case: a 27-line file, whole document visible. Unclamped this asked for line 226 (+1 in
        // LspManager ⇒ Position(227,0)); clamped it asks for 26 (+1 ⇒ Position(27,0) — the document end).
        assertArrayEquals(new int[] {0, 26}, LspCoordinator.paddedWindow(new int[] {0, 26}, PAD, 27));
    }

    @Test
    void aLongFileKeepsTheFullOverScanOnBothSides() {
        assertArrayEquals(new int[] {300, 900}, LspCoordinator.paddedWindow(new int[] {500, 700}, PAD, 5000));
    }

    @Test
    void theTopOfALongFileClampsOnlyTheStart() {
        assertArrayEquals(new int[] {0, 210}, LspCoordinator.paddedWindow(new int[] {0, 10}, PAD, 5000));
    }

    @Test
    void theBottomOfALongFileClampsOnlyTheEnd() {
        assertArrayEquals(new int[] {4700, 4999}, LspCoordinator.paddedWindow(new int[] {4900, 4999}, PAD, 5000));
    }

    @Test
    void aSingleLineAndAnEmptyDocumentStayInRange() {
        assertArrayEquals(new int[] {0, 0}, LspCoordinator.paddedWindow(new int[] {0, 0}, PAD, 1));
        // lineCount 0 shouldn't happen (RichTextFX always has one paragraph) but must not go negative.
        assertArrayEquals(new int[] {0, 0}, LspCoordinator.paddedWindow(new int[] {0, 0}, PAD, 0));
    }

    /** Whatever the viewport reports, the result must be a valid, in-document, non-inverted range. */
    @Test
    void theResultIsAlwaysAWellFormedInDocumentRange() {
        int[][] visibles = {{0, 0}, {0, 26}, {5, 3}, {900, 1200}, {4999, 4999}, {-4, 12}};
        int[] lineCounts = {1, 27, 200, 5000};
        for (int[] visible : visibles) {
            for (int lineCount : lineCounts) {
                int[] w = LspCoordinator.paddedWindow(visible, PAD, lineCount);
                String at = java.util.Arrays.toString(visible) + " in " + lineCount + " lines → "
                        + java.util.Arrays.toString(w);
                assertTrue(w[0] >= 0, "start negative: " + at);
                assertTrue(w[1] >= w[0], "inverted range: " + at);
                assertTrue(w[1] <= Math.max(0, lineCount - 1), "end past the last line: " + at);
            }
        }
    }
}
