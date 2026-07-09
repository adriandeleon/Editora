package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure test for the {@code .pdf} extension detection that routes a file into the PDF viewer. */
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
}
