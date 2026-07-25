package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SelectionRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LspSelection} — flattening a server's selection-range chain into document offsets (#739). */
class LspSelectionTest {

    /** Line starts for a document whose lines are each 10 characters plus a newline. */
    private static final int[] LINE_STARTS = {0, 11, 22, 33, 44};

    private static SelectionRange sel(int sl, int sc, int el, int ec, SelectionRange parent) {
        SelectionRange r = new SelectionRange();
        r.setRange(new Range(new Position(sl, sc), new Position(el, ec)));
        r.setParent(parent);
        return r;
    }

    @Test
    void aChainFlattensInnermostFirst() {
        SelectionRange outer = sel(0, 0, 4, 5, null);
        SelectionRange middle = sel(1, 2, 3, 4, outer);
        SelectionRange inner = sel(2, 1, 2, 6, middle);

        List<int[]> chain = LspSelection.toOffsetChain(List.of(inner), LINE_STARTS);

        assertEquals(3, chain.size());
        assertArrayEquals(new int[] {23, 28}, chain.get(0), "inner: line 2 cols 1..6");
        assertArrayEquals(new int[] {13, 37}, chain.get(1), "middle");
        assertArrayEquals(new int[] {0, 49}, chain.get(2), "outer");
    }

    @Test
    void emptyInputsAnswerWithAnEmptyChain() {
        assertTrue(LspSelection.toOffsetChain(null, LINE_STARTS).isEmpty());
        assertTrue(LspSelection.toOffsetChain(List.of(), LINE_STARTS).isEmpty());
        assertTrue(
                LspSelection.toOffsetChain(List.of(sel(0, 0, 1, 1, null)), null).isEmpty());
        assertTrue(LspSelection.toOffsetChain(List.of(sel(0, 0, 1, 1, null)), new int[0])
                .isEmpty());
    }

    /**
     * A range naming a line the document doesn't have means the response is stale relative to the buffer.
     * Clamping it to the end would silently select the wrong text, so the chain stops there instead.
     */
    @Test
    void aRangeBeyondTheDocumentEndsTheChain() {
        SelectionRange outOfRange = sel(0, 0, 99, 0, null);
        SelectionRange inner = sel(1, 0, 1, 4, outOfRange);

        List<int[]> chain = LspSelection.toOffsetChain(List.of(inner), LINE_STARTS);

        assertEquals(1, chain.size(), "the valid inner range survives; its unusable parent does not");
        assertArrayEquals(new int[] {11, 15}, chain.get(0));
    }

    /**
     * Some servers repeat a range as its own parent. Kept, it would be an expand press that changes nothing,
     * so the duplicate is skipped while the rest of the chain continues.
     */
    @Test
    void aRepeatedRangeIsSkippedButTheChainContinues() {
        SelectionRange outer = sel(0, 0, 4, 5, null);
        SelectionRange duplicate = sel(1, 2, 3, 4, outer);
        SelectionRange inner = sel(1, 2, 3, 4, duplicate);

        List<int[]> chain = LspSelection.toOffsetChain(List.of(inner), LINE_STARTS);

        assertEquals(2, chain.size());
        assertArrayEquals(new int[] {13, 37}, chain.get(0));
        assertArrayEquals(new int[] {0, 49}, chain.get(1));
    }

    /** A parent that doesn't contain its child isn't a nesting chain — stop rather than expand backwards. */
    @Test
    void aNonContainingParentEndsTheChain() {
        SelectionRange bogus = sel(3, 0, 3, 2, null);
        SelectionRange inner = sel(1, 0, 1, 5, bogus);

        List<int[]> chain = LspSelection.toOffsetChain(List.of(inner), LINE_STARTS);

        assertEquals(1, chain.size());
        assertArrayEquals(new int[] {11, 16}, chain.get(0));
    }

    /** A self-referential parent chain is malformed, but it must not spin the request thread. */
    @Test
    void aCyclicParentChainTerminates() {
        SelectionRange a = sel(1, 0, 1, 5, null);
        a.setParent(a);

        List<int[]> chain = LspSelection.toOffsetChain(List.of(a), LINE_STARTS);

        assertEquals(1, chain.size(), "the repeat guard drops the self-parent");
    }

    @Test
    void onlyTheFirstPositionsChainIsUsed() {
        SelectionRange first = sel(0, 0, 0, 3, null);
        SelectionRange second = sel(2, 0, 2, 3, null);

        List<int[]> chain = LspSelection.toOffsetChain(List.of(first, second), LINE_STARTS);

        assertEquals(1, chain.size());
        assertArrayEquals(new int[] {0, 3}, chain.get(0));
    }

    @Test
    void aRangelessEntryIsDroppedRatherThanThrowing() {
        assertTrue(LspSelection.toOffsetChain(List.of(new SelectionRange()), LINE_STARTS)
                .isEmpty());
    }
}
