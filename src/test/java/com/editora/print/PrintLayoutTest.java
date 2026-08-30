package com.editora.print;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrintLayoutTest {

    @Test
    void columnsFloorsToFitAndNeverBelowOne() {
        assertEquals(10, CodePrintLayout.columns(100, 10));
        assertEquals(10, CodePrintLayout.columns(105, 10)); // floor
        assertEquals(1, CodePrintLayout.columns(5, 10)); // narrower than one char → 1
        assertEquals(1, CodePrintLayout.columns(100, 0)); // bad char width → 1
        assertEquals(1, CodePrintLayout.columns(100, Double.NaN));
    }

    @Test
    void linesPerPageFloorsToFitAndNeverBelowOne() {
        assertEquals(8, CodePrintLayout.linesPerPage(100, 12)); // floor 8.33
        assertEquals(1, CodePrintLayout.linesPerPage(10, 12)); // shorter than one line → 1
        assertEquals(1, CodePrintLayout.linesPerPage(100, 0)); // bad line height → 1
    }

    @Test
    void packBlocksGreedilyPacksWholeBlocks() {
        assertEquals(List.of(List.of(0, 1), List.of(2)), MarkdownPrintLayout.packBlocks(List.of(10.0, 10.0, 10.0), 25));
        assertEquals(List.of(List.of(0, 1, 2)), MarkdownPrintLayout.packBlocks(List.of(10.0, 10.0, 10.0), 100));
    }

    @Test
    void packBlocksGivesAnOverTallBlockItsOwnPage() {
        // block 1 (30) is taller than the page (20) → its own page; neighbors keep their own pages.
        assertEquals(
                List.of(List.of(0), List.of(1), List.of(2)),
                MarkdownPrintLayout.packBlocks(List.of(10.0, 30.0, 10.0), 20));
        assertEquals(List.of(List.of(0)), MarkdownPrintLayout.packBlocks(List.of(30.0), 20));
    }

    @Test
    void packBlocksAlwaysReturnsAtLeastOnePage() {
        assertEquals(List.of(List.of()), MarkdownPrintLayout.packBlocks(List.of(), 100));
    }

    /**
     * Packing charges the gap between blocks.
     *
     * <p>The page's container is a {@code VBox} with CSS spacing, and packing used to ignore it: blocks
     * summing to exactly the page height then overflowed it by the gaps between them. Measured on a
     * 200-item list, six of eight pages were over the page, the worst by 31px — invisible to a "was
     * anything scaled?" check and invisible on screen, because the preview clips.
     */
    @Test
    void packBlocksChargesTheSpacingBetweenBlocks() {
        // Three 30px blocks fit a 100px page only if the two 10px gaps between them are free; they are not.
        assertEquals(
                List.of(List.of(0, 1), List.of(2)), MarkdownPrintLayout.packBlocks(List.of(30.0, 30.0, 30.0), 100, 10));
        // The same blocks with no spacing still share one page.
        assertEquals(
                List.of(List.of(0, 1, 2)), MarkdownPrintLayout.packBlocks(List.of(30.0, 30.0, 30.0), 100, 10 - 10));
    }

    /** The first block on a page pays no leading gap — otherwise every page would lose one gap of room. */
    @Test
    void theFirstBlockOnAPageIsNotChargedAGap() {
        assertEquals(List.of(List.of(0)), MarkdownPrintLayout.packBlocks(List.of(100.0), 100, 25));
    }

    /** Negative or absent spacing is treated as none, so the two-argument form keeps its old behaviour. */
    @Test
    void spacingIsClampedAndTheTwoArgumentFormIsUnchanged() {
        assertEquals(
                MarkdownPrintLayout.packBlocks(List.of(40.0, 40.0), 100),
                MarkdownPrintLayout.packBlocks(List.of(40.0, 40.0), 100, -5));
    }
}
