package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.SemanticTokensEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link SemanticTokensSplice}: delta edits splice onto the cached array; stale deltas are refused (#679). */
class SemanticTokensSpliceTest {

    private static SemanticTokensEdit edit(int start, int delete, Integer... data) {
        SemanticTokensEdit e = new SemanticTokensEdit(start, delete, List.of(data));
        return e;
    }

    @Test
    void replaceInsertAndDeleteSplices() {
        List<Integer> prev = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        // Replace [2..4) with {20,21,22}.
        assertEquals(
                List.of(0, 1, 20, 21, 22, 4, 5, 6, 7, 8, 9),
                SemanticTokensSplice.apply(prev, List.of(edit(2, 2, 20, 21, 22))));
        // Pure insert at 0.
        assertEquals(
                List.of(99, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), SemanticTokensSplice.apply(prev, List.of(edit(0, 0, 99))));
        // Pure delete of the tail.
        assertEquals(
                List.of(0, 1, 2, 3, 4), SemanticTokensSplice.apply(prev, List.of(new SemanticTokensEdit(5, 5, null))));
    }

    @Test
    void multipleEditsApplyAgainstTheOldIndicesRegardlessOfOrder() {
        List<Integer> prev = List.of(0, 1, 2, 3, 4, 5);
        // Two edits given ascending — both index the OLD array; descending-start application keeps them valid.
        List<Integer> out = SemanticTokensSplice.apply(prev, List.of(edit(1, 1, 10), edit(4, 1, 40)));
        assertEquals(List.of(0, 10, 2, 3, 40, 5), out);
    }

    @Test
    void emptyDeltaIsTheUnchangedArray() {
        assertEquals(List.of(1, 2, 3), SemanticTokensSplice.apply(List.of(1, 2, 3), List.of()));
        assertEquals(List.of(1, 2, 3), SemanticTokensSplice.apply(List.of(1, 2, 3), null));
    }

    @Test
    void anOutOfRangeEditRefusesTheDelta() {
        // A stale/garbled delta must not decode into a corrupted token array — null tells the caller to
        // re-request full.
        assertNull(SemanticTokensSplice.apply(List.of(1, 2, 3), List.of(edit(2, 5))));
        assertNull(SemanticTokensSplice.apply(List.of(1, 2, 3), List.of(edit(-1, 0, 9))));
        assertNull(SemanticTokensSplice.apply(null, List.of(edit(0, 0, 9))));
    }
}
