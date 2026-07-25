package com.editora.lsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.editora.editor.FoldRegions;
import org.eclipse.lsp4j.FoldingRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LspFolding} — reconciling a server's folding ranges with the editor's own region model (#738). */
class LspFoldingTest {

    private static FoldingRange range(int start, int end) {
        FoldingRange r = new FoldingRange();
        r.setStartLine(start);
        r.setEndLine(end);
        return r;
    }

    private static List<FoldRegions.Region> map(FoldingRange... ranges) {
        return LspFolding.toRegions(Arrays.asList(ranges));
    }

    @Test
    void aRangeBecomesARegionWithTheSameLines() {
        assertEquals(List.of(new FoldRegions.Region(2, 9)), map(range(2, 9)));
    }

    @Test
    void nullAndEmptyAnswerWithNoRegions() {
        assertTrue(LspFolding.toRegions(null).isEmpty());
        assertTrue(LspFolding.toRegions(List.of()).isEmpty());
    }

    /**
     * A single-line range is dropped, not clamped: folding it would hide nothing while still drawing a
     * chevron in the gutter, which reads as a broken fold rather than an absent one.
     */
    @Test
    void aRangeThatCannotHideALineIsDropped() {
        assertTrue(map(range(4, 4)).isEmpty(), "start == end");
        assertTrue(map(range(7, 3)).isEmpty(), "end before start");
    }

    @Test
    void aMalformedRangeIsDroppedRatherThanThrowing() {
        assertTrue(map(range(-1, 5)).isEmpty(), "negative start");
        assertEquals(1, LspFolding.toRegions(Arrays.asList(null, range(1, 3))).size(), "a null entry is skipped");
    }

    /**
     * The order is the built-in detector's, innermost-first — {@code FoldManager.foldRecursivelyAtCaret}
     * collapses deepest-first and relies on it. Servers are free to answer outermost-first, and several do.
     */
    @Test
    void nestedRegionsComeOutInnermostFirst() {
        List<FoldRegions.Region> out = map(range(0, 20), range(2, 8), range(3, 5));

        assertEquals(
                List.of(new FoldRegions.Region(3, 5), new FoldRegions.Region(2, 8), new FoldRegions.Region(0, 20)),
                out);
    }

    /** Siblings keep document order — the brace scanner emits them as it reaches each closing delimiter. */
    @Test
    void siblingRegionsKeepDocumentOrder() {
        assertEquals(
                List.of(new FoldRegions.Region(1, 3), new FoldRegions.Region(5, 7)), map(range(5, 7), range(1, 3)));
    }

    /**
     * Two constructs can legitimately span the same lines (a class whose body holds a single method), and a
     * duplicated region would put two chevrons on one line.
     */
    @Test
    void duplicateRangesCollapseToOneRegion() {
        assertEquals(List.of(new FoldRegions.Region(1, 9)), map(range(1, 9), range(1, 9)));
    }

    /** The character fields are ignored: the gutter folds whole lines, which is why we declare lineFoldingOnly. */
    @Test
    void characterPrecisionIsIgnored() {
        FoldingRange r = range(1, 4);
        r.setStartCharacter(11);
        r.setEndCharacter(3);

        assertEquals(List.of(new FoldRegions.Region(1, 4)), LspFolding.toRegions(List.of(r)));
    }

    /** A big answer must not degrade into anything quadratic-looking or reordered. */
    @Test
    void aLargeAnswerStaysOrderedAndComplete() {
        List<FoldingRange> ranges = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            ranges.add(range(i * 3, i * 3 + 2));
        }

        List<FoldRegions.Region> out = LspFolding.toRegions(ranges);

        assertEquals(2000, out.size());
        assertEquals(new FoldRegions.Region(0, 2), out.get(0));
        assertEquals(new FoldRegions.Region(5997, 5999), out.get(1999));
    }
}
