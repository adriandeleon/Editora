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
import org.jspecify.annotations.Nullable;

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
        return new ImagePaging.Source(img.getWidth(), img.getHeight(), pixelScale, ImagePaging.NO_CUTS, false);
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
        @Nullable BufferedImage[] held = new BufferedImage[1];
        int[] heldIndex = {-1};
        IntFunction<BufferedImage> pixels = i -> {
            BufferedImage image = heldIndex[0] == i ? held[0] : null;
            if (image == null) {
                held[0] = null; // let the previous image go before the next one is decoded
                image = loader.apply(i);
                held[0] = image;
                heldIndex[0] = i;
            }
            return image;
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

    /** The printable area of a page in points ({@code {width, height}}), turned on its side for landscape. */
    public static double[] printableSize(String pageSizeKey, boolean landscape) {
        PDRectangle rect = pageRectangle(pageSizeKey, landscape);
        return new double[] {rect.getWidth() - 2 * MARGIN, rect.getHeight() - 2 * MARGIN};
    }

    private static PDRectangle pageRectangle(String pageSizeKey, boolean landscape) {
        PDRectangle rect = CodePdfWriter.pageRectangle(pageSizeKey);
        return landscape ? new PDRectangle(rect.getHeight(), rect.getWidth()) : rect;
    }

    /**
     * Writes images that are already cut to pages, one per page and all at the same
     * {@code pointsPerPixel}: the caller chose the scale and the cuts (see the Project Map output plan), so
     * nothing is refitted or sliced here. Each image is drawn from the top-left margin; one that is larger
     * than the printable area is shrunk to it rather than clipped.
     */
    public static void writePages(
            List<BufferedImage> pages, double pointsPerPixel, String pageSizeKey, boolean landscape, Path out)
            throws IOException {
        writePages(pages, pointsPerPixel, pageSizeKey, landscape, List.of(), out);
    }

    /**
     * A name an image draws, in that image's pixels ({@code x, y} the top-left of its line, {@code height} its
     * font size): written over the picture as invisible text, so the PDF can be searched and its names copied.
     */
    public record TextBox(String text, double x, double y, double width, double height) {}

    /**
     * As {@link #writePages(List, double, String, boolean, Path)}, with {@code texts.get(i)} laid over page
     * {@code i} as invisible text (PDF text rendering mode 3, as scanned documents carry their OCR text): the
     * page still looks like the picture, and a viewer's Find, selection and copy reach the names (Project Map
     * export, Map explore position 16). Each name is stretched to the width the picture gives it. Characters the
     * bundled Inter lacks come from the system fallback fonts, as in the other PDF exports.
     */
    public static void writePages(
            List<BufferedImage> pages,
            double pointsPerPixel,
            String pageSizeKey,
            boolean landscape,
            List<List<TextBox>> texts,
            Path out)
            throws IOException {
        PDRectangle rect = pageRectangle(pageSizeKey, landscape);
        float availW = rect.getWidth() - 2 * MARGIN;
        float availH = rect.getHeight() - 2 * MARGIN;
        boolean anyText = texts.stream().anyMatch(page -> page != null && !page.isEmpty());
        try (PDDocument doc = new PDDocument();
                PdfGlyphs glyphs = new PdfGlyphs(doc, anyText ? SystemFontFiles.get() : List.of())) {
            org.apache.pdfbox.pdmodel.font.PDFont font = anyText ? textFont(doc) : null;
            int index = -1;
            for (BufferedImage img : pages) {
                index++;
                if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
                    continue;
                }
                double scale = Math.min(
                        pointsPerPixel > 0 ? pointsPerPixel : 1.0,
                        Math.min(availW / (double) img.getWidth(), availH / (double) img.getHeight()));
                float drawW = (float) (img.getWidth() * scale);
                float drawH = (float) (img.getHeight() * scale);
                PDPage page = new PDPage(rect);
                doc.addPage(page);
                PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(xo, MARGIN, rect.getHeight() - MARGIN - drawH, drawW, drawH);
                    List<TextBox> onPage = index < texts.size() ? texts.get(index) : null;
                    if (font != null && onPage != null && !onPage.isEmpty()) {
                        writeInvisibleText(cs, glyphs, font, onPage, scale, rect.getHeight() - MARGIN);
                    }
                }
            }
            if (doc.getNumberOfPages() == 0) {
                doc.addPage(new PDPage(rect));
            }
            doc.save(out.toFile());
        }
    }

    /** The bundled Inter, embedded as a subset: the Map's own face, and what its names are searched in. */
    private static org.apache.pdfbox.pdmodel.font.PDFont textFont(PDDocument doc) throws IOException {
        try (java.io.InputStream in =
                ImagePdfWriter.class.getResourceAsStream("/com/editora/fonts/inter/Inter-Regular.ttf")) {
            if (in == null) {
                throw new IOException("Bundled font not found: Inter-Regular");
            }
            return org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, in);
        }
    }

    /**
     * Lays {@code boxes} (image pixels, drawn at {@code scale} points per pixel from the top-left margin, whose
     * PDF y is {@code top}) over the page as text that is neither filled nor stroked.
     */
    private static void writeInvisibleText(
            PDPageContentStream cs,
            PdfGlyphs glyphs,
            org.apache.pdfbox.pdmodel.font.PDFont primary,
            List<TextBox> boxes,
            double scale,
            float top)
            throws IOException {
        for (TextBox box : boxes) {
            if (box == null || box.text() == null || box.text().isBlank() || box.height() <= 0) {
                continue;
            }
            float size = (float) (box.height() * scale);
            List<PdfGlyphs.Run> runs = glyphs.runs(primary, box.text(), true);
            double natural = 0;
            for (PdfGlyphs.Run run : runs) {
                natural += run.font().getStringWidth(run.text()) / 1000.0 * size;
            }
            if (natural <= 0) {
                continue;
            }
            double target = Math.max(1, box.width() * scale);
            float stretch = (float) Math.max(1, Math.min(1_000, target / natural * 100));
            cs.beginText();
            cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
            cs.setHorizontalScaling(stretch);
            cs.newLineAtOffset(MARGIN + (float) (box.x() * scale), top - (float) ((box.y() + box.height()) * scale));
            for (PdfGlyphs.Run run : runs) {
                cs.setFont(run.font(), size);
                cs.showText(run.text());
            }
            cs.endText();
        }
    }
}
