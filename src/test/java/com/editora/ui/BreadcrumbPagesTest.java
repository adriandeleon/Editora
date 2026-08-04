package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreadcrumbPagesTest {

    @Test
    void aShortListingIsNotPagedAtAll() {
        assertTrue(BreadcrumbPages.pages(0, 30).isEmpty());
        assertTrue(BreadcrumbPages.pages(1, 30).isEmpty());
        assertTrue(BreadcrumbPages.pages(30, 30).isEmpty(), "exactly a page still fits flat");
    }

    @Test
    void aLongListingSplitsIntoContiguousPagesCoveringEveryEntry() {
        List<int[]> pages = BreadcrumbPages.pages(95, 30);
        assertEquals(0, pages.get(0)[0]);
        assertEquals(95, pages.get(pages.size() - 1)[1], "the last page must reach the end");
        for (int i = 1; i < pages.size(); i++) {
            assertEquals(pages.get(i - 1)[1], pages.get(i)[0], "pages must not overlap or leave a gap");
        }
        for (int[] p : pages) {
            assertTrue(p[1] - p[0] <= 30 + BreadcrumbPages.MIN_TAIL, "no page grows far beyond the page size");
        }
    }

    @Test
    void aStubTrailingPageIsFoldedIntoTheOneBeforeIt() {
        // 31 entries would otherwise be a page of 30 and a page of 1.
        List<int[]> pages = BreadcrumbPages.pages(31, 30);
        assertEquals(1, pages.size());
        assertEquals(0, pages.get(0)[0]);
        assertEquals(31, pages.get(0)[1]);
    }

    @Test
    void aFullTrailingPageIsKept() {
        List<int[]> pages = BreadcrumbPages.pages(60, 30);
        assertEquals(2, pages.size());
        assertEquals(30, pages.get(1)[0]);
        assertEquals(60, pages.get(1)[1]);
    }

    @Test
    void aNonPositivePageSizeNeverLoops() {
        assertTrue(BreadcrumbPages.pages(100, 0).isEmpty());
        assertTrue(BreadcrumbPages.pages(100, -5).isEmpty());
    }

    @Test
    void theRangeLabelNamesBothEndsAndElidesLongOnes() {
        assertEquals("android … cargo", BreadcrumbPages.label("android", "cargo"));
        String label = BreadcrumbPages.label("a-very-long-directory-name", "b");
        assertTrue(label.contains("…"), "a long name is elided: " + label);
        assertTrue(label.endsWith(" b"));
    }

    @Test
    void aNullEndpointDoesNotBlowUp() {
        assertEquals(" … b", BreadcrumbPages.label(null, "b"));
    }
}
