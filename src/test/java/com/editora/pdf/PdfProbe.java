package com.editora.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Test helper: reads a produced PDF back. Export tests used to assert only {@code %PDF} + a size, which a
 * file full of {@code ?} or clipped lines passes; these read what a reader of the PDF would actually get.
 */
final class PdfProbe {

    private PdfProbe() {}

    /** The text of {@code pdf} as extracted in reading order (lines separated by {@code \n}). */
    static String text(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            return stripper.getText(doc);
        }
    }

    /** {@link #text} with every whitespace run removed — for content that wraps across lines. */
    static String squeezed(Path pdf) throws IOException {
        return text(pdf).replaceAll("\\s+", "");
    }

    /** The right edge (in points) of the right-most glyph drawn anywhere in {@code pdf}. */
    static float rightmostInk(Path pdf) throws IOException {
        float[] max = {0f};
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition p : positions) {
                        if (!p.getUnicode().isBlank()) {
                            max[0] = Math.max(max[0], p.getXDirAdj() + p.getWidthDirAdj());
                        }
                    }
                    super.writeString(text, positions);
                }
            };
            stripper.getText(doc);
        }
        return max[0];
    }

    /** The number of pages in {@code pdf}. */
    static int pages(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }
}
