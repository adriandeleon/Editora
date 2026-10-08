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

    private static javafx.print.PageRange range(int from, int to) {
        return new javafx.print.PageRange(from, to);
    }

    @Test
    void noPageRangeMeansEveryPage() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 1, 2}, PrintService.pageIndices(null, 3));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {0, 1, 2}, PrintService.pageIndices(new javafx.print.PageRange[0], 3));
        assertEquals(0, PrintService.pageIndices(null, 0).length);
    }

    /**
     * The dialog's ranges are 1-based and inclusive; the helper answers 0-based page indices.
     *
     * <p>"Pages 3–5" used to submit all ten pages. The platform job asks only for pages 3, 4 and 5 and
     * JavaFX answers each request with the next node submitted, so the document's pages 1–3 came out and
     * the fourth submission failed the job.
     */
    @Test
    void aPageRangeSelectsOnlyItsPages() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {2, 3, 4}, PrintService.pageIndices(new javafx.print.PageRange[] {range(3, 5)}, 10));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {6}, PrintService.pageIndices(new javafx.print.PageRange[] {range(7, 7)}, 10));
    }

    @Test
    void severalRangesAreMergedInPageOrderWithoutRepeats() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {0, 1, 4, 5, 6, 7},
                PrintService.pageIndices(
                        new javafx.print.PageRange[] {range(5, 7), range(1, 2), range(6, 8), null}, 10));
    }

    @Test
    void aRangeIsClampedToTheDocument() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {8, 9}, PrintService.pageIndices(new javafx.print.PageRange[] {range(9, 500)}, 10));
        assertEquals(0, PrintService.pageIndices(new javafx.print.PageRange[] {range(20, 30)}, 10).length);
    }

    /** A recording {@link PrintService.PageSink}: {@code printPages} is exercised without any printer job. */
    private static final class Recorder implements PrintService.PageSink {
        final java.util.List<javafx.scene.Node> printed = new java.util.ArrayList<>();
        int failAt = -1;
        int ended;

        @Override
        public boolean printPage(javafx.print.PageLayout layout, javafx.scene.Node page) {
            if (printed.size() == failAt) {
                return false;
            }
            printed.add(page);
            return true;
        }

        @Override
        public boolean endJob() {
            ended++;
            return true;
        }
    }

    @Test
    void printPagesSubmitsExactlyTheChosenPagesAndEndsTheJob() {
        List<javafx.scene.Node> nodes =
                List.of(new javafx.scene.Group(), new javafx.scene.Group(), new javafx.scene.Group());
        Recorder sink = new Recorder();
        PrintService.Result result =
                PrintService.printPages(PrintService.Pages.of(nodes), new int[] {1, 2}, null, sink);
        assertEquals(true, result.ok());
        assertEquals(List.of(nodes.get(1), nodes.get(2)), sink.printed);
        assertEquals(1, sink.ended);
    }

    @Test
    void printPagesStopsAtTheFirstRefusedPageAndStillEndsTheJob() {
        List<javafx.scene.Node> nodes =
                List.of(new javafx.scene.Group(), new javafx.scene.Group(), new javafx.scene.Group());
        Recorder sink = new Recorder();
        sink.failAt = 1;
        PrintService.Result result =
                PrintService.printPages(PrintService.Pages.of(nodes), new int[] {0, 1, 2}, null, sink);
        assertEquals(false, result.ok());
        assertEquals(List.of(nodes.get(0)), sink.printed);
        assertEquals(1, sink.ended);
    }

    private static List<com.editora.pdf.PdfText.Run> line(String text) {
        return List.of(new com.editora.pdf.PdfText.Run(text, java.awt.Color.BLACK, false, false));
    }

    /** Page breaks are found without building a page: {source line, visual line} per page. */
    @Test
    void codePageStartsCountWrappedLinesAndAlwaysGiveOnePage() {
        List<List<com.editora.pdf.PdfText.Run>> lines = List.of(line("aaaa"), line("bbbbbbbbbb"), line("cc"));
        // 4 columns, 2 rows per page: "aaaa" | "bbbb" / "bbbb" | "bb" / "cc"
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[][] {{0, 0}, {1, 1}, {2, 0}}, CodePrintLayout.pageStarts(lines, 4, 2));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[][] {{0, 0}}, CodePrintLayout.pageStarts(List.of(), 80, 50));
    }

    // --- keep a heading with what follows it ----------------------------------------------------------

    private static List<List<Integer>> pack(List<Double> heights, double page, Integer... headings) {
        java.util.Set<Integer> kept = java.util.Set.of(headings);
        return MarkdownPrintLayout.packBlocks(heights, page, 0, kept::contains);
    }

    /** A heading that would be the last thing on a page goes over with the block that did not fit. */
    @Test
    void aHeadingIsNotLeftAtTheFootOfAPage() {
        // 40 + 40 + heading 10 = 90 of 100; the next block (30) opens page 2 — and takes the heading along.
        assertEquals(List.of(List.of(0, 1), List.of(2, 3)), pack(List.of(40.0, 40.0, 10.0, 30.0), 100, 2));
        // without the rule it is stranded
        assertEquals(
                List.of(List.of(0, 1, 2), List.of(3)),
                MarkdownPrintLayout.packBlocks(List.of(40.0, 40.0, 10.0, 30.0), 100, 0));
        // a run of headings (h1 then h2) moves together
        assertEquals(List.of(List.of(0), List.of(1, 2, 3)), pack(List.of(70.0, 10.0, 10.0, 30.0), 100, 1, 2));
    }

    @Test
    void aHeadingStaysWhereMovingItWouldNotHelp() {
        // the page holds nothing but the heading: moving it would leave an empty page behind
        assertEquals(List.of(List.of(0), List.of(1)), pack(List.of(10.0, 95.0), 100, 0));
        // heading and block do not fit one page together either
        assertEquals(List.of(List.of(0, 1), List.of(2)), pack(List.of(50.0, 10.0, 95.0), 100, 1));
        // an over-tall block gets its page to itself (it is scaled): the heading stays behind
        assertEquals(List.of(List.of(0, 1), List.of(2)), pack(List.of(50.0, 10.0, 300.0), 100, 1));
    }

    /** The running form the splitter sizes pieces against: room left, room on a new page, a forced break. */
    @Test
    void thePackerReportsTheRoomLeftAndTakesBackWhatItWasTold() {
        MarkdownPrintLayout.Packer packer = new MarkdownPrintLayout.Packer(100, 10);
        assertEquals(100, packer.room());
        org.junit.jupiter.api.Assertions.assertFalse(packer.canBreak(), "nothing on the page yet");
        packer.add(30, false);
        assertEquals(60, packer.room(), "100 - 30 - the gap before the next block");
        packer.add(20, true); // a heading
        assertEquals(30, packer.room());
        assertEquals(70, packer.freshRoom(), "a new page would start with the heading: 100 - 20 - gap");
        org.junit.jupiter.api.Assertions.assertTrue(packer.canBreak());

        MarkdownPrintLayout.Packer.Mark mark = packer.mark();
        packer.breakPage();
        assertEquals(70, packer.room(), "after a forced break the room is the new page's");
        packer.add(25, false); // would have fitted the old page; goes to the new one, heading and all
        assertEquals(List.of(List.of(0), List.of(1, 2)), packer.pages());
        org.junit.jupiter.api.Assertions.assertTrue(packer.brokeSince(mark));

        packer.rewind(mark);
        assertEquals(List.of(List.of(0, 1)), packer.pages());
        assertEquals(30, packer.room());
        packer.add(25, false);
        assertEquals(List.of(List.of(0, 1, 2)), packer.pages(), "with no break asked for it fits where it was");
    }

    // --- printed code ---------------------------------------------------------------------------------

    /** A file that ends in a newline does not print a numbered line with nothing on it. */
    @Test
    void theEmptyLineAfterAFinalNewlineIsNotPrinted() {
        List<List<com.editora.pdf.PdfText.Run>> lines = com.editora.pdf.PdfText.splitIntoLineRuns("a\nb\n", null, 4);
        assertEquals(3, lines.size(), "the splitter reports the caret's line after the last newline");
        assertEquals(2, CodePrintLayout.withoutTrailingEmptyLine(lines).size());
        // …which is what used to cost a last sheet holding only a line number: 2 rows per page, 2 real lines
        assertEquals(2, CodePrintLayout.pageStarts(lines, 80, 2).length);
        assertEquals(1, CodePrintLayout.pageStarts(CodePrintLayout.withoutTrailingEmptyLine(lines), 80, 2).length);

        List<List<com.editora.pdf.PdfText.Run>> noNewline = com.editora.pdf.PdfText.splitIntoLineRuns("a\nb", null, 4);
        assertEquals(noNewline, CodePrintLayout.withoutTrailingEmptyLine(noNewline), "nothing to drop");
        List<List<com.editora.pdf.PdfText.Run>> blankLines =
                com.editora.pdf.PdfText.splitIntoLineRuns("a\n\n\n", null, 4);
        assertEquals(3, CodePrintLayout.withoutTrailingEmptyLine(blankLines).size(), "only the last one goes");
        List<List<com.editora.pdf.PdfText.Run>> empty = com.editora.pdf.PdfText.splitIntoLineRuns("", null, 4);
        assertEquals(empty, CodePrintLayout.withoutTrailingEmptyLine(empty), "an empty file keeps its one line");
    }
}
