package com.editora.pdf;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure (no-FX) coverage of the image→PDF backend used by the SVG/Markwhen/tree preview PDF export. */
class ImagePdfWriterTest {

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

    private static List<PDImageXObject> images(PDDocument doc, int page) throws Exception {
        List<PDImageXObject> out = new java.util.ArrayList<>();
        var res = doc.getPage(page).getResources();
        for (var name : res.getXObjectNames()) {
            if (res.getXObject(name) instanceof PDImageXObject img) {
                out.add(img);
            }
        }
        return out;
    }

    /** A white image with a black bar per row, leaving the rows listed in {@code gaps} (and 4 below) blank. */
    private static BufferedImage bars(int w, int h, int... gaps) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(10, 0, w - 20, h);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 10, h);
        g.fillRect(w - 10, 0, 10, h);
        for (int gap : gaps) {
            g.fillRect(0, gap, w, 5);
        }
        g.dispose();
        return img;
    }

    @Test
    void aTallImageIsCutAtABlankRowNearThePageEndNotThroughContent(@TempDir Path dir) throws Exception {
        // Page end at pixel row 720; rows 700..704 are blank, so the first page ends at 705, not 720.
        Path out = dir.resolve("gap.pdf");
        ImagePdfWriter.write(List.of(bars(100, 1000, 700)), "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            assertEquals(705, images(doc, 0).get(0).getHeight());
            assertEquals(295, images(doc, 1).get(0).getHeight());
        }
        // No blank row within reach: the hard cut at the page end, as before.
        ImagePdfWriter.write(List.of(bars(100, 1000, 300)), "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(720, images(doc, 0).get(0).getHeight());
        }
    }

    @Test
    void aDenseImageIsDrawnAtItsLogicalSizeWithAllItsPixels(@TempDir Path dir) throws Exception {
        // 800×400 px at 2× is 400×200 logical: it fits the page at full size and keeps every pixel.
        Path out = dir.resolve("dense.pdf");
        BufferedImage img = solid(800, 400);
        ImagePdfWriter.write(List.of(ImagePdfWriter.plain(img, 2)), i -> img, "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(800, images(doc, 0).get(0).getWidth(), "not downsampled");
            assertEquals(612f, doc.getPage(0).getMediaBox().getWidth(), "portrait: it is not too wide");
            assertEquals(400.0, drawnWidth(doc, 0), 0.01, "2 px per point: 144 dpi");
        }
    }

    /** The width, in points, the first image of a page is drawn at (the {@code cm} before its {@code Do}). */
    private static double drawnWidth(PDDocument doc, int page) throws Exception {
        var parser = new org.apache.pdfbox.pdfparser.PDFStreamParser(doc.getPage(page));
        java.util.List<Object> tokens = parser.parse();
        for (int i = 6; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof org.apache.pdfbox.contentstream.operator.Operator op
                    && "cm".equals(op.getName())) {
                return ((org.apache.pdfbox.cos.COSNumber) tokens.get(i - 6)).floatValue();
            }
        }
        throw new AssertionError("no image drawn on page " + page);
    }

    @Test
    void aWideImageTurnsThePageToLandscape(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("wide.pdf");
        ImagePdfWriter.write(List.of(solid(1100, 600)), "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(792f, doc.getPage(0).getMediaBox().getWidth());
            assertEquals(612f, doc.getPage(0).getMediaBox().getHeight());
            assertEquals(720.0, drawnWidth(doc, 0), 0.01, "fitted to the long side");
        }
    }

    @Test
    void aVeryWideImageIsTiledAcrossPages(@TempDir Path dir) throws Exception {
        // 6000×2000 on landscape Letter (720×540 pt) at half size: 5 columns × 2 bands.
        Path out = dir.resolve("map.pdf");
        ImagePdfWriter.write(List.of(solid(6000, 2000)), "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(10, doc.getNumberOfPages());
            assertEquals(1440, images(doc, 0).get(0).getWidth(), "one page-wide tile");
            assertEquals(1080, images(doc, 0).get(0).getHeight());
            assertEquals(6000 - 4 * 1440, images(doc, 4).get(0).getWidth(), "the last column");
        }
    }

    @Test
    void pngChunksOfARowListShareAPageAndAreNotAllDecodedAtOnce(@TempDir Path dir) throws Exception {
        java.io.ByteArrayOutputStream a = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(solid(200, 300), "png", a);
        byte[] png = a.toByteArray();
        assertEquals(200, PageImage.of(png).source().pixelWidth(), "read from the PNG header");
        assertEquals(300, PageImage.of(png).source().pixelHeight());
        assertEquals(0, PageImage.of(new byte[] {1, 2, 3}).source().pixelWidth(), "not a PNG");
        Path out = dir.resolve("rows.pdf");
        ImagePdfWriter.writePng(
                List.of(
                        PageImage.rows(png, 1, new int[] {100, 200}, false),
                        PageImage.rows(png, 1, new int[] {100, 200}, true)),
                "letter",
                out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages(), "the second chunk continues under the first");
            assertEquals(2, images(doc, 0).size());
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
