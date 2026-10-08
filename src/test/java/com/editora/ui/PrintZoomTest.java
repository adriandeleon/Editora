package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Print Preview's zoom arithmetic and page-number parsing (pure). */
class PrintZoomTest {

    private static final double LETTER_W = 612;
    private static final double LETTER_H = 792;
    private static final double CHROME = 32;

    private static double factor(PrintZoom.Setting setting, double viewW, double viewH) {
        return PrintZoom.factor(setting, LETTER_W, LETTER_H, viewW, viewH, CHROME);
    }

    @Test
    void fitPageShowsTheWholeSheet() {
        // 760×812 viewport: the height is the tighter side for a portrait page.
        double f = factor(PrintZoom.Setting.FIT_PAGE, 760, 812);
        assertEquals((812 - 64) / LETTER_H, f, 1e-9);
        // A wide, short viewport is limited by its height too; a tall, narrow one by its width.
        assertEquals((400 - 64) / LETTER_H, factor(PrintZoom.Setting.FIT_PAGE, 2000, 400), 1e-9);
        assertEquals((400 - 64) / LETTER_W, factor(PrintZoom.Setting.FIT_PAGE, 400, 2000), 1e-9);
    }

    @Test
    void fitWidthIgnoresTheHeight() {
        assertEquals((760 - 64) / LETTER_W, factor(PrintZoom.Setting.FIT_WIDTH, 760, 100), 1e-9);
    }

    /** The finding: in a 1700×1000 window the sheet stayed 504 px wide, because the fit was capped at 1.0. */
    @Test
    void aFitIsNotCappedAtActualSize() {
        assertEquals((1700 - 64) / LETTER_W, factor(PrintZoom.Setting.FIT_WIDTH, 1700, 1000), 1e-9);
        assertEquals((1000 - 64) / LETTER_H, factor(PrintZoom.Setting.FIT_PAGE, 1700, 1000), 1e-9);
        assertEquals(
                PrintZoom.MAX, factor(PrintZoom.Setting.FIT_WIDTH, 20_000, 20_000), 1e-9, "but stops at the maximum");
    }

    @Test
    void aFitSurvivesAViewportWithNoSizeYet() {
        assertEquals(1, factor(PrintZoom.Setting.FIT_PAGE, 0, 0), 1e-9);
        assertEquals(1, factor(PrintZoom.Setting.FIT_WIDTH, 40, 40), 1e-9, "less than the chrome");
        assertEquals(1, PrintZoom.factor(PrintZoom.Setting.FIT_PAGE, 0, 0, 800, 800, CHROME), 1e-9, "no sheet");
        double tiny = factor(PrintZoom.Setting.FIT_PAGE, 66, 66);
        assertEquals(0.05, tiny, 1e-9, "never zero");
    }

    @Test
    void aPercentageIsTheFactorWhateverTheViewport() {
        assertEquals(1.5, factor(PrintZoom.Setting.percent(1.5), 100, 100), 1e-9);
        assertEquals(PrintZoom.MIN, factor(PrintZoom.Setting.percent(0.01), 100, 100), 1e-9);
        assertEquals(PrintZoom.MAX, factor(PrintZoom.Setting.percent(99), 100, 100), 1e-9);
        assertEquals(1, PrintZoom.Setting.percent(Double.NaN).percent(), 1e-9);
    }

    @Test
    void theStepsWalkFromFiftyToFourHundredPercent() {
        assertArrayEquals(new double[] {0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0}, PrintZoom.steps(), 1e-9);
        assertEquals(1.25, PrintZoom.stepUp(1.0), 1e-9);
        assertEquals(1.0, PrintZoom.stepUp(0.94), 1e-9, "from a fit, the next step above the scale in effect");
        assertEquals(0.75, PrintZoom.stepDown(0.94), 1e-9);
        assertEquals(0.75, PrintZoom.stepDown(1.0), 1e-9);
        assertEquals(PrintZoom.MAX, PrintZoom.stepUp(4.0), 1e-9);
        assertEquals(PrintZoom.MIN, PrintZoom.stepDown(0.5), 1e-9);
        assertEquals(PrintZoom.MIN, PrintZoom.stepDown(0.3), 1e-9, "a fit below the smallest step");
        assertEquals(125, PrintZoom.percentOf(1.25));
        assertEquals(94, PrintZoom.percentOf(0.9419));
    }

    @Test
    void aTypedPageNumberMustBeAPageOfTheDocument() {
        assertEquals(0, PrintZoom.pageIndex("1", 15));
        assertEquals(14, PrintZoom.pageIndex("15", 15));
        assertEquals(2, PrintZoom.pageIndex("  3 ", 15));
        assertEquals(6, PrintZoom.pageIndex("007", 15));
        for (String bad : new String[] {"0", "16", "-1", "+2", "2.5", "abc", "", " ", "99999999999999999999", null}) {
            assertEquals(-1, PrintZoom.pageIndex(bad, 15), String.valueOf(bad));
        }
        assertEquals(-1, PrintZoom.pageIndex("1", 0), "an empty document has no page 1");
    }
}
