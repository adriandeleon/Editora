package com.editora.pdf;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure (no-FX) coverage of the image→PDF backend used by the SVG/Markwhen/tree preview PDF export. */
class ImagePdfWriterTest {

    @Test
    void fitScaleNeverUpscalesAndClampsToOne() {
        assertEquals(0.5, ImagePdfWriter.fitScale(1000, 500f), 1e-9); // wider than the page → shrink
        assertEquals(1.0, ImagePdfWriter.fitScale(100, 500f), 1e-9); // narrower → don't upscale
        assertEquals(1.0, ImagePdfWriter.fitScale(0, 500f), 1e-9); // degenerate width → safe default
    }

    @Test
    void rowsPerPageIsFlooredAndAtLeastOne() {
        assertEquals(1440, ImagePdfWriter.rowsPerPage(720f, 0.5));
        assertEquals(720, ImagePdfWriter.rowsPerPage(720f, 1.0));
        assertEquals(1, ImagePdfWriter.rowsPerPage(1f, 100.0)); // never zero
    }

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void tallImageIsSlicedAcrossPages(@TempDir Path dir) throws Exception {
        // Letter printable height is 792 - 72 = 720pt. A narrow 100px image isn't upscaled (scale 1.0), so
        // 720 source rows fit per page → a 2000px-tall image spans ceil(2000/720) = 3 pages.
        Path out = dir.resolve("tall.pdf");
        ImagePdfWriter.write(List.of(solid(100, 2000)), "letter", out);
        assertTrue(Files.size(out) > 0);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void multipleImagesEachStartFreshAndShortOnesAreOnePage(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("multi.pdf");
        ImagePdfWriter.write(List.of(solid(100, 50), solid(100, 60)), "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages()); // one short image each → one page each
        }
    }

    @Test
    void emptyInputStillProducesAValidOnePagePdf(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("empty.pdf");
        ImagePdfWriter.write(List.of(), "a4", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages()); // never a zero-page PDF (PDFBox can't save one)
        }
    }
}
