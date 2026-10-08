package com.editora.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Writes one or more raster images into a PDF, each at its logical size (a 2× snapshot is drawn at half its
 * pixel size, so the extra pixels are resolution), scaled down to the printable width when wider and — when
 * taller than a page — continued on the following pages, cut between rows of content rather than through
 * them. A page turns to landscape for an image that is wider than tall, and a very wide image is tiled
 * rather than shrunk to a strip. The snapshot-based backend for the preview → PDF export of image/tree
 * previews (SVG, Markwhen, the JSON/YAML/TOML/XML trees, the Project Map). PDFBox only; no FX. The geometry
 * is {@link ImagePaging}, shared with the print paginator and unit-tested there.
 */
public final class ImagePdfWriter {

    private static final float MARGIN = 36f; // 0.5"

    private ImagePdfWriter() {}

    /** Writes already-decoded images, one image pixel per point (their density is unknown). */
    public static void write(List<BufferedImage> images, String pageSizeKey, Path out) throws IOException {
        List<ImagePaging.Source> sources = images.stream()
                .map(img -> img == null ? ImagePaging.Source.plain(0, 0) : plain(img, 1))
                .toList();
        write(sources, images::get, pageSizeKey, out);
    }

    /** The layout source of a decoded image at {@code pixelScale} image pixels per logical pixel. */
    public static ImagePaging.Source plain(BufferedImage img, double pixelScale) {
        return new ImagePaging.Source(img.getWidth(), img.getHeight(), pixelScale, null, false);
    }

    /** Writes PNG images, decoding one at a time (see {@link PageImage}). */
    public static void writePng(List<PageImage> images, String pageSizeKey, Path out) throws IOException {
        List<ImagePaging.Source> sources =
                images.stream().map(PageImage::source).toList();
        write(
                sources,
                i -> {
                    try {
                        return javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(images.get(i).png()));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                },
                pageSizeKey,
                out);
    }

    /**
     * Writes the images described by {@code sources}; {@code loader} decodes image {@code i} when a page
     * needs its pixels. Only the last decoded image is kept, so the peak is one image, not all of them.
     */
    public static void write(
            List<ImagePaging.Source> sources, IntFunction<BufferedImage> loader, String pageSizeKey, Path out)
            throws IOException {
        PDRectangle rect = CodePdfWriter.pageRectangle(pageSizeKey);
        float availW = rect.getWidth() - 2 * MARGIN;
        float availH = rect.getHeight() - 2 * MARGIN;
        BufferedImage[] held = new BufferedImage[1];
        int[] heldIndex = {-1};
        IntFunction<BufferedImage> pixels = i -> {
            if (heldIndex[0] != i) {
                held[0] = null; // let the previous image go before the next one is decoded
                held[0] = loader.apply(i);
                heldIndex[0] = i;
            }
            return held[0];
        };
        try (PDDocument doc = new PDDocument()) {
            List<ImagePaging.Page> pages =
                    ImagePaging.layout(sources, availW, availH, true, (i, row) -> blankRow(pixels.apply(i), row));
            for (ImagePaging.Page p : pages) {
                PDRectangle box = p.landscape() ? new PDRectangle(rect.getHeight(), rect.getWidth()) : rect;
                PDPage page = new PDPage(box);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (ImagePaging.Slice s : p.slices()) {
                        BufferedImage img = pixels.apply(s.image());
                        if (img == null) {
                            continue;
                        }
                        // A header that disagrees with the decoded image must not throw out of getSubimage.
                        int w = Math.min(s.srcWidth(), img.getWidth() - s.srcX());
                        int h = Math.min(s.srcHeight(), img.getHeight() - s.srcY());
                        if (w < 1 || h < 1) {
                            continue;
                        }
                        PDImageXObject xo =
                                LosslessFactory.createFromImage(doc, img.getSubimage(s.srcX(), s.srcY(), w, h));
                        // Draw from the top margin down (PDF origin is bottom-left).
                        cs.drawImage(
                                xo,
                                MARGIN + (float) s.x(),
                                box.getHeight() - MARGIN - (float) (s.y() + s.height()),
                                (float) s.width(),
                                (float) s.height());
                    }
                }
            }
            if (doc.getNumberOfPages() == 0) {
                doc.addPage(new PDPage(rect)); // never write a zero-page PDF (PDFBox would fail to save)
            }
            doc.save(out.toFile());
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Whether pixel row {@code row} of {@code img} is one colour from edge to edge — a place to cut. */
    static boolean blankRow(BufferedImage img, int row) {
        if (img == null || row < 0 || row >= img.getHeight()) {
            return false;
        }
        int[] rgb = img.getRGB(0, row, img.getWidth(), 1, null, 0, img.getWidth());
        for (int v : rgb) {
            if (v != rgb[0]) {
                return false;
            }
        }
        return true;
    }
}
