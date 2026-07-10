package com.editora.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Writes one or more raster images into a PDF, one after another, each scaled to the printable width and —
 * when taller than a page — sliced across successive pages (a long "screenshot" printed top-to-bottom). The
 * snapshot-based backend for the preview → PDF export of image/tree previews (SVG, Markwhen, and the
 * JSON/YAML/TOML/XML trees, whose full render is captured in bounded row-chunks by the caller — each chunk
 * is one image here). PDFBox only; no FX. The pure {@link #layout} maths are unit-tested.
 */
public final class ImagePdfWriter {

    private static final float MARGIN = 36f; // 0.5"

    private ImagePdfWriter() {}

    /** A slice's placement on its page: how many source rows it spans and the drawn width/height in points. */
    record Placement(int srcRows, float drawWidth, float drawHeight) {}

    /**
     * Pure geometry for one image on a page of {@code availW × availH} printable points: the scale that fits
     * the image to the width (never upscaling beyond 1 pt/px), how many source pixel rows fit on one page at
     * that scale, and the drawn width. Unit-tested.
     */
    public static double fitScale(int imgWidth, double availW) {
        if (imgWidth <= 0) {
            return 1.0;
        }
        return Math.min(availW / (double) imgWidth, 1.0);
    }

    public static int rowsPerPage(double availH, double scale) {
        return Math.max(1, (int) Math.floor(availH / scale));
    }

    public static void write(List<BufferedImage> images, String pageSizeKey, Path out) throws IOException {
        PDRectangle rect = CodePdfWriter.pageRectangle(pageSizeKey);
        float availW = rect.getWidth() - 2 * MARGIN;
        float availH = rect.getHeight() - 2 * MARGIN;
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage img : images) {
                if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
                    continue;
                }
                double scale = fitScale(img.getWidth(), availW);
                int srcPageRows = rowsPerPage(availH, scale);
                float drawW = (float) (img.getWidth() * scale);
                for (int y = 0; y < img.getHeight(); y += srcPageRows) {
                    int h = Math.min(srcPageRows, img.getHeight() - y);
                    BufferedImage slice = img.getSubimage(0, y, img.getWidth(), h);
                    float drawH = (float) (h * scale);
                    PDPage page = new PDPage(rect);
                    doc.addPage(page);
                    PDImageXObject xo = LosslessFactory.createFromImage(doc, slice);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        // Draw from the top margin down (PDF origin is bottom-left).
                        cs.drawImage(xo, MARGIN, rect.getHeight() - MARGIN - drawH, drawW, drawH);
                    }
                }
            }
            if (doc.getNumberOfPages() == 0) {
                doc.addPage(new PDPage(rect)); // never write a zero-page PDF (PDFBox would fail to save)
            }
            doc.save(out.toFile());
        }
    }
}
