package com.editora.editor;

import java.util.List;

import com.editora.editor.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StickyScrollTest {

    private static Region r(int start, int end) {
        return new Region(start, end);
    }

    /** class 0..99 { method 10..40 { if 20..30 { … } } } — a typical nesting. */
    private static final List<Region> NESTED = List.of(r(0, 99), r(10, 40), r(20, 30));

    @Test
    void pinsTheEnclosingChainOutermostFirst() {
        assertEquals(List.of(0, 10, 20), StickyScroll.headerLines(NESTED, 25, 5));
    }

    @Test
    void onlyPinsScopesThatActuallyContainTheLine() {
        // Line 45 is past the method's end, so only the class encloses it.
        assertEquals(List.of(0), StickyScroll.headerLines(NESTED, 45, 5));
    }

    @Test
    void doesNotPinAHeaderThatIsItselfOnScreen() {
        // Viewport starts exactly on the method header: pinning it would cover the real line with a copy.
        assertEquals(List.of(0), StickyScroll.headerLines(NESTED, 10, 5));
    }

    @Test
    void atTheTopOfTheFileNothingIsPinned() {
        assertEquals(List.of(), StickyScroll.headerLines(NESTED, 0, 5));
    }

    @Test
    void aLinePastEveryRegionPinsNothing() {
        assertEquals(List.of(), StickyScroll.headerLines(NESTED, 250, 5));
    }

    @Test
    void deeperThanTheCapKeepsTheOutermost() {
        // The innermost scope is the one you can infer from the code in front of you; the outermost has
        // been off screen longest, so it is the one worth the space.
        List<Region> deep = List.of(r(0, 99), r(5, 90), r(10, 80), r(15, 70), r(20, 60), r(25, 50));
        assertEquals(List.of(0, 5), StickyScroll.headerLines(deep, 30, 2));
    }

    @Test
    void twoRegionsOpeningOnOneLineArePinnedOnce() {
        // A detector can report a brace and a bracket starting together; rendering it twice looks broken.
        assertEquals(List.of(3), StickyScroll.headerLines(List.of(r(3, 50), r(3, 20)), 10, 5));
    }

    @Test
    void unsortedRegionsStillComeBackOutermostFirst() {
        assertEquals(List.of(0, 10, 20), StickyScroll.headerLines(List.of(r(20, 30), r(0, 99), r(10, 40)), 25, 5));
    }

    @Test
    void degenerateInputIsSafe() {
        assertEquals(List.of(), StickyScroll.headerLines(null, 10, 5));
        assertEquals(List.of(), StickyScroll.headerLines(List.of(), 10, 5));
        assertEquals(List.of(), StickyScroll.headerLines(NESTED, 25, 0));
        assertEquals(List.of(), StickyScroll.headerLines(NESTED, -3, 5));
    }

    @Test
    void theDefaultCapIsApplied() {
        List<Region> deep = List.of(r(0, 99), r(1, 98), r(2, 97), r(3, 96), r(4, 95), r(5, 94), r(6, 93));
        assertEquals(
                StickyScroll.DEFAULT_MAX, StickyScroll.headerLines(deep, 50).size());
    }

    @Test
    void navigationLeavesOneRowForEachPinnedHeader() {
        assertEquals(22, StickyScroll.navigationFirstVisible(NESTED, 25, 5));
    }

    @Test
    void navigationRecomputesClearanceWhenItRevealsANestedHeader() {
        // Moving up from line 21 initially needs three rows, but that exposes the if header. The class and
        // method remain pinned, so line 19 is the stable viewport start and line 21 stays visible below them.
        assertEquals(19, StickyScroll.navigationFirstVisible(NESTED, 21, 5));
    }

    @Test
    void navigationNeedsNoClearanceWithoutPinnedHeaders() {
        assertEquals(250, StickyScroll.navigationFirstVisible(NESTED, 250, 5));
        assertEquals(0, StickyScroll.navigationFirstVisible(NESTED, -4, 5));
    }

    @Test
    void theIndexAnswersExactlyAsTheLinearScanDoes() {
        java.util.Random random = new java.util.Random(20261005);
        for (int round = 0; round < 300; round++) {
            // Arbitrary intervals: nested, adjacent, sharing a start line, and overlapping without nesting
            // (the "} else {" shape, where one region ends on the line the next starts on).
            List<Region> regions = new java.util.ArrayList<>();
            int lines = 20 + random.nextInt(200);
            for (int i = random.nextInt(40); i > 0; i--) {
                int start = random.nextInt(lines);
                regions.add(r(start, start + random.nextInt(lines - start + 1)));
            }
            StickyScroll.Index index = StickyScroll.Index.of(regions);
            for (int first = 0; first <= lines + 1; first++) {
                for (int max : new int[] {1, 3, 5, 50}) {
                    assertEquals(
                            StickyScroll.headerLines(regions, first, max),
                            index.headerLines(first, max),
                            "regions " + regions + " first " + first + " max " + max);
                }
            }
        }
    }

    @Test
    void theIndexIsReusedOnlyForTheListItWasBuiltFrom() {
        StickyScroll.Index index = StickyScroll.Index.of(NESTED);
        org.junit.jupiter.api.Assertions.assertTrue(index.isFor(NESTED));
        org.junit.jupiter.api.Assertions.assertFalse(index.isFor(List.copyOf(new java.util.ArrayList<>(NESTED))));
        assertEquals(List.of(), StickyScroll.Index.EMPTY.headerLines(25, 5));
        assertEquals(List.of(), StickyScroll.Index.of(null).headerLines(25, 5));
    }

    @Test
    void aQueryWalksTheNestingDepthNotTheFile() {
        // 50,000 sibling methods inside one class: only the class and one method enclose any line.
        List<Region> regions = new java.util.ArrayList<>();
        regions.add(r(0, 500_001));
        for (int i = 0; i < 50_000; i++) {
            regions.add(r(1 + i * 10, 9 + i * 10));
        }
        StickyScroll.Index index = StickyScroll.Index.of(regions);
        assertEquals(List.of(0, 250_001), index.headerLines(250_005, 5));
        assertEquals(List.of(0), index.headerLines(250_010, 5));
        assertEquals(StickyScroll.headerLines(regions, 499_995, 5), index.headerLines(499_995, 5));
    }
}
