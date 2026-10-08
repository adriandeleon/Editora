package com.editora.pdf;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The page geometry print and PDF share for raster images: scale, cuts, landscape, tiling. Pure. */
class ImagePagingTest {

    private static final double W = 540; // Letter with half-inch margins
    private static final double H = 720;

    private static ImagePaging.Source rows(int width, int rowCount, int rowHeight, double scale, boolean continues) {
        int[] cuts = new int[rowCount - 1];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = (i + 1) * rowHeight;
        }
        return new ImagePaging.Source(width, rowCount * rowHeight, scale, cuts, continues);
    }

    @Test
    void fitScaleNeverUpscalesAndClampsToOne() {
        assertEquals(0.5, ImagePaging.fitScale(1000, 500), 1e-9); // wider than the page → shrink
        assertEquals(1.0, ImagePaging.fitScale(100, 500), 1e-9); // narrower → don't upscale
        assertEquals(1.0, ImagePaging.fitScale(0, 500), 1e-9); // degenerate width → safe default
    }

    @Test
    void fitWithinShrinksForEitherSideAndNeverEnlarges() {
        assertEquals(1.0, ImagePaging.fitWithin(200, 100, W, H), 1e-9, "a small diagram keeps its size");
        assertEquals(0.5, ImagePaging.fitWithin(1080, 100, W, H), 1e-9);
        assertEquals(0.5, ImagePaging.fitWithin(100, 1440, W, H), 1e-9);
    }

    @Test
    void rowsPerPageIsFlooredAndAtLeastOne() {
        assertEquals(1440, ImagePaging.rowsPerPage(720, 0.5));
        assertEquals(720, ImagePaging.rowsPerPage(720, 1.0));
        assertEquals(1, ImagePaging.rowsPerPage(1, 100.0)); // never zero
    }

    @Test
    void aCutPrefersTheLastRowBoundaryThatFits() {
        int[] cuts = {100, 200, 300, 400};
        assertEquals(300, ImagePaging.chooseCut(0, 350, 350, true, 500, cuts, row -> false));
        assertEquals(500, ImagePaging.chooseCut(300, 350, 350, true, 500, cuts, row -> false), "the rest fits");
        // Half a page is left and the next row (100 px) does not fit in the 60 that remain: move it whole.
        assertEquals(200, ImagePaging.chooseCut(200, 60, 350, false, 500, cuts, row -> false));
    }

    @Test
    void withoutRowBoundariesACutLooksBackForABlankRowAndElseCutsHard() {
        int[] none = {};
        // Page end at 720; rows 690..699 are blank. The cut lands just below the nearest blank row.
        assertEquals(700, ImagePaging.chooseCut(0, 720, 720, true, 3000, none, row -> row >= 690 && row < 700));
        // A blank row further up than a quarter page is not worth the white space.
        assertEquals(720, ImagePaging.chooseCut(0, 720, 720, true, 3000, none, row -> row == 100));
        assertEquals(720, ImagePaging.chooseCut(0, 720, 720, true, 3000, none, row -> false), "hard cut");
        // A row taller than the page cannot be kept whole: blank row, else hard cut — never an empty slice.
        assertEquals(720, ImagePaging.chooseCut(0, 720, 720, true, 3000, new int[] {900}, row -> false));
    }

    @Test
    void aRowListFlowsAcrossItsChunksAndBreaksOnlyBetweenRows() {
        // Two 2× chunks of 30 rows, 44 px (22 pt) each: 60 rows, 32 to a 720 pt page (704 pt).
        List<ImagePaging.Source> chunks = List.of(rows(800, 30, 44, 2, false), rows(800, 30, 44, 2, true));
        List<ImagePaging.Page> pages = ImagePaging.layout(chunks, W, H, true, ImagePaging.BlankRows.NONE);
        assertEquals(2, pages.size(), "60 rows at 32 a page");
        List<ImagePaging.Slice> first = pages.get(0).slices();
        assertEquals(2, first.size(), "the second chunk continues on the first page");
        assertEquals(30 * 44, first.get(0).srcHeight());
        assertEquals(2 * 44, first.get(1).srcHeight(), "two more whole rows fit under the first chunk");
        assertEquals(660, first.get(1).y(), 1e-6, "directly under it");
        assertEquals(400, first.get(0).width(), 1e-6, "drawn at the logical width: 2 px per point");
        ImagePaging.Slice rest = pages.get(1).slices().get(0);
        assertEquals(2 * 44, rest.srcY());
        assertEquals(0, rest.y(), 1e-6);
        for (ImagePaging.Page p : pages) {
            assertFalse(p.landscape());
            for (ImagePaging.Slice s : p.slices()) {
                assertEquals(0, s.srcY() % 44, "starts on a row");
                assertEquals(0, s.srcHeight() % 44, "holds whole rows");
            }
        }
    }

    @Test
    void chunksOfOneListShareOneScale() {
        // The second chunk is wider than the page; both must shrink together or the rows change size mid-list.
        List<ImagePaging.Source> chunks = List.of(rows(400, 4, 20, 1, false), rows(1080, 4, 20, 1, true));
        List<ImagePaging.Slice> slices = ImagePaging.layout(chunks, W, H, false, ImagePaging.BlankRows.NONE)
                .get(0)
                .slices();
        assertEquals(200, slices.get(0).width(), 1e-6);
        assertEquals(540, slices.get(1).width(), 1e-6);
        assertEquals(40, slices.get(0).height(), 1e-6);
    }

    @Test
    void imagesThatDoNotContinueStartTheirOwnPage() {
        List<ImagePaging.Source> two = List.of(ImagePaging.Source.plain(100, 50), ImagePaging.Source.plain(100, 60));
        assertEquals(
                2,
                ImagePaging.layout(two, W, H, true, ImagePaging.BlankRows.NONE).size());
    }

    @Test
    void landscapeIsChosenOnlyForAnImageThatIsWiderThanTallAndGains() {
        assertTrue(ImagePaging.landscapeFitsBetter(1100, 600, W, H));
        assertFalse(ImagePaging.landscapeFitsBetter(1100, 3000, W, H), "taller than wide");
        assertFalse(ImagePaging.landscapeFitsBetter(400, 300, W, H), "already at full size on portrait");

        ImagePaging.Page page = ImagePaging.layout(
                        List.of(ImagePaging.Source.plain(1100, 600)), W, H, true, ImagePaging.BlankRows.NONE)
                .get(0);
        assertTrue(page.landscape());
        assertEquals(720, page.slices().get(0).width(), 1e-6, "the long side of the paper");
        page = ImagePaging.layout(List.of(ImagePaging.Source.plain(1100, 600)), W, H, false, ImagePaging.BlankRows.NONE)
                .get(0);
        assertFalse(page.landscape(), "print keeps the orientation the user chose");
        assertEquals(540, page.slices().get(0).width(), 1e-6);
    }

    @Test
    void theFixedWidthPreviewsStayOnOneColumn() {
        // 1100 logical px on a 468 pt printable width (one-inch margins) is 0.43: shrunk, not tiled.
        List<ImagePaging.Page> pages = ImagePaging.layout(
                List.of(new ImagePaging.Source(2200, 800, 2, null, false)),
                468,
                648,
                false,
                ImagePaging.BlankRows.NONE);
        assertEquals(1, pages.size());
        assertEquals(468, pages.get(0).slices().get(0).width(), 1e-6);
    }

    @Test
    void aVeryWideImageIsTiledLeftToRightThenDownInsteadOfShrunkToAStrip() {
        // A 6000×2000 map on portrait Letter would be 9% of its size. At half size it is 3000×1000 pt:
        // 6 columns of 540 pt (the last 300 pt wide), and 2 bands of 720 pt (the second 280 pt high).
        List<ImagePaging.Page> pages = ImagePaging.layout(
                List.of(ImagePaging.Source.plain(6000, 2000)), W, H, false, ImagePaging.BlankRows.NONE);
        assertEquals(6, ImagePaging.tileColumns(6000, ImagePaging.TILE_SCALE, W));
        assertEquals(12, pages.size());
        for (int i = 0; i < pages.size(); i++) {
            ImagePaging.Slice s = pages.get(i).slices().get(0);
            assertEquals((i % 6) * 1080, s.srcX(), "column " + i);
            assertEquals(i < 6 ? 0 : 1440, s.srcY(), "band of page " + i);
            assertEquals(i % 6 == 5 ? 300 : 540, s.width(), 1e-6);
            assertEquals(i < 6 ? 720 : 280, s.height(), 1e-6);
        }
        // For a PDF the page turns first: 720 pt columns, and the 1000 pt height still needs two bands.
        pages = ImagePaging.layout(
                List.of(ImagePaging.Source.plain(6000, 2000)), W, H, true, ImagePaging.BlankRows.NONE);
        assertTrue(pages.get(0).landscape());
        assertEquals(5 * 2, pages.size());
    }

    @Test
    void contentSlightlyTallerThanAPageIsShrunkOntoItInsteadOfSpillingASliver() {
        // An A4 Typst page (595×842 pt, rendered at 192 ppi) on Letter: fitted to the width it is 764 pt tall.
        ImagePaging.Source a4 = new ImagePaging.Source(1587, 2245, 192 / 72.0, null, false);
        List<ImagePaging.Page> pages = ImagePaging.layout(List.of(a4), W, H, false, ImagePaging.BlankRows.NONE);
        assertEquals(1, pages.size(), "it used to print its bottom margin on a second page");
        assertEquals(720, pages.get(0).slices().get(0).height(), 0.5);
        assertEquals(2245, pages.get(0).slices().get(0).srcHeight());
        // Well over a page is a real second page, at the full width.
        ImagePaging.Source tall = ImagePaging.Source.plain(540, 900);
        pages = ImagePaging.layout(List.of(tall), W, H, false, ImagePaging.BlankRows.NONE);
        assertEquals(2, pages.size());
        assertEquals(540, pages.get(0).slices().get(0).width(), 1e-6);
    }

    @Test
    void emptyImagesAreSkipped() {
        assertTrue(ImagePaging.layout(List.of(ImagePaging.Source.plain(0, 0)), W, H, true, ImagePaging.BlankRows.NONE)
                .isEmpty());
    }
}
