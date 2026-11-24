package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.SemanticTokensEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link SemanticTokensSplice}: delta edits splice onto the cached array; stale deltas are refused (#679). */
class SemanticTokensSpliceTest {

    private static SemanticTokensEdit edit(int start, int delete, Integer... data) {
        return new SemanticTokensEdit(start, delete, List.of(data));
    }

    private static int[] ints(int... values) {
        return values;
    }

    @Test
    void replaceInsertAndDeleteSplices() {
        int[] prev = ints(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        // Replace [2..4) with {20,21,22}.
        assertArrayEquals(
                ints(0, 1, 20, 21, 22, 4, 5, 6, 7, 8, 9),
                SemanticTokensSplice.apply(prev, List.of(edit(2, 2, 20, 21, 22))));
        // Pure insert at 0.
        assertArrayEquals(
                ints(99, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), SemanticTokensSplice.apply(prev, List.of(edit(0, 0, 99))));
        // Pure delete of the tail.
        assertArrayEquals(
                ints(0, 1, 2, 3, 4), SemanticTokensSplice.apply(prev, List.of(new SemanticTokensEdit(5, 5, null))));
        // Insert at the very end.
        assertArrayEquals(
                ints(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), SemanticTokensSplice.apply(prev, List.of(edit(10, 0, 10))));
        assertArrayEquals(ints(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), prev, "the cached array is never written to");
    }

    @Test
    void multipleEditsApplyAgainstTheOldIndicesRegardlessOfOrder() {
        int[] prev = ints(0, 1, 2, 3, 4, 5);
        // Two edits — both index the OLD array, in whatever order they come.
        assertArrayEquals(
                ints(0, 10, 2, 3, 40, 5), SemanticTokensSplice.apply(prev, List.of(edit(1, 1, 10), edit(4, 1, 40))));
        assertArrayEquals(
                ints(0, 10, 2, 3, 40, 5), SemanticTokensSplice.apply(prev, List.of(edit(4, 1, 40), edit(1, 1, 10))));
    }

    @Test
    void emptyDeltaIsTheUnchangedArray() {
        int[] prev = ints(1, 2, 3);
        assertSame(prev, SemanticTokensSplice.apply(prev, List.of()));
        assertSame(prev, SemanticTokensSplice.apply(prev, null));
    }

    @Test
    void anOutOfRangeOrOverlappingEditRefusesTheDelta() {
        // A stale/garbled delta must not decode into a corrupted token array — null tells the caller to
        // re-request full.
        assertNull(SemanticTokensSplice.apply(ints(1, 2, 3), List.of(edit(2, 5))));
        assertNull(SemanticTokensSplice.apply(ints(1, 2, 3), List.of(edit(-1, 0, 9))));
        assertNull(SemanticTokensSplice.apply(null, List.of(edit(0, 0, 9))));
        assertNull(SemanticTokensSplice.apply(ints(1, 2, 3, 4), List.of(edit(0, 2, 9), edit(1, 1, 8))));
    }

    @Test
    void toArrayUnboxesAndReadsNullAsZero() {
        assertArrayEquals(ints(1, 0, 3), SemanticTokensSplice.toArray(java.util.Arrays.asList(1, null, 3)));
        assertArrayEquals(ints(), SemanticTokensSplice.toArray(null));
    }
}
