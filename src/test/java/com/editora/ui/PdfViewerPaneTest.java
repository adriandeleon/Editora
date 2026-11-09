package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the PDF viewer: {@code .pdf} detection and the wheel-zoom speed. */
class PdfViewerPaneTest {

    @Test
    void detectsPdfByExtension() {
        assertTrue(PdfViewerPane.isPdf("report.pdf"));
        assertTrue(PdfViewerPane.isPdf("/home/a/b/Report.PDF")); // case-insensitive, with a path
        assertTrue(PdfViewerPane.isPdf("weird.name.pdf"));
        assertFalse(PdfViewerPane.isPdf("notes.txt"));
        assertFalse(PdfViewerPane.isPdf("archive.pdf.zip")); // only the final extension counts
        assertFalse(PdfViewerPane.isPdf("pdf")); // no extension
        assertFalse(PdfViewerPane.isPdf(null));
    }

    @Test
    void wheelZoomIsProportionalCappedAndFasterWithTheShortcut() {
        assertEquals(0.1, PdfViewerPane.wheelZoomExponent(40, false), 1e-9); // one wheel notch
        assertEquals(-0.1, PdfViewerPane.wheelZoomExponent(-40, false), 1e-9);
        assertEquals(0.16, PdfViewerPane.wheelZoomExponent(40, true), 1e-9);
        assertEquals(0.0025, PdfViewerPane.wheelZoomExponent(1, false), 1e-9); // a touchpad's small step
        assertEquals(0.45, PdfViewerPane.wheelZoomExponent(10_000, false), 1e-9); // capped
        assertEquals(-0.45, PdfViewerPane.wheelZoomExponent(-10_000, true), 1e-9);
    }
}
