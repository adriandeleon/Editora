package com.editora.print;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
