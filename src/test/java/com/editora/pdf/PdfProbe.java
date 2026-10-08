package com.editora.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
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

    /**
     * One drawn glyph: its 1-based page, text, left and right edge, and baseline — all in points, in PDF
     * coordinates (y grows upward from the bottom of the page). Invisible text is included.
     */
    record Glyph(int page, String text, float x, float right, float baseline) {}

    /** Every non-blank glyph of {@code pdf}, in drawing order. */
    static List<Glyph> glyphs(Path pdf) throws IOException {
        List<Glyph> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition p : positions) {
                        if (!p.getUnicode().isBlank()) {
                            out.add(new Glyph(
                                    getCurrentPageNo(),
                                    p.getUnicode(),
                                    p.getXDirAdj(),
                                    p.getXDirAdj() + p.getWidthDirAdj(),
                                    p.getPageHeight() - p.getYDirAdj()));
                        }
                    }
                    super.writeString(text, positions);
                }
            };
            stripper.getText(doc);
        }
        return out;
    }

    /** The glyphs of the first occurrence of {@code word} (drawn as one run on one line); fails if absent. */
    static List<Glyph> glyphsOf(List<Glyph> glyphs, String word) {
        StringBuilder all = new StringBuilder();
        List<Integer> owner = new ArrayList<>();
        for (int i = 0; i < glyphs.size(); i++) {
            for (int k = 0; k < glyphs.get(i).text().length(); k++) {
                owner.add(i);
            }
            all.append(glyphs.get(i).text());
        }
        int at = all.indexOf(word);
        if (at < 0) {
            throw new AssertionError("not drawn: " + word + " in " + all);
        }
        return glyphs.subList(owner.get(at), owner.get(at + word.length() - 1) + 1);
    }

    /** A straight stroke segment ({@code m} … {@code l}) on a 1-based page, in PDF coordinates. */
    record Segment(int page, float x1, float y1, float x2, float y2) {
        boolean horizontal() {
            return Math.abs(y1 - y2) < 0.01f;
        }

        boolean vertical() {
            return Math.abs(x1 - x2) < 0.01f;
        }
    }

    /** A rectangle path ({@code re}) on a 1-based page, in PDF coordinates. */
    record Box(int page, float x, float y, float width, float height) {}

    /** Every line segment drawn in {@code pdf} (rules, underlines, quote bars, ticks). */
    static List<Segment> segments(Path pdf) throws IOException {
        List<Segment> out = new ArrayList<>();
        walkPaths(pdf, out, new ArrayList<>(), new ArrayList<>());
        return out;
    }

    /** Every rectangle drawn in {@code pdf} (table cells, code strips, checkboxes). */
    static List<Box> boxes(Path pdf) throws IOException {
        List<Box> out = new ArrayList<>();
        walkPaths(pdf, new ArrayList<>(), out, new ArrayList<>());
        return out;
    }

    /** Where every image is placed in {@code pdf} (its {@code cm} matrix: origin and drawn size). */
    static List<Box> images(Path pdf) throws IOException {
        List<Box> out = new ArrayList<>();
        walkPaths(pdf, new ArrayList<>(), new ArrayList<>(), out);
        return out;
    }

    private static void walkPaths(Path pdf, List<Segment> segments, List<Box> boxes, List<Box> images)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (int pi = 0; pi < doc.getNumberOfPages(); pi++) {
                List<Float> operands = new ArrayList<>();
                Box placed = null; // the last cm: PDFBox writes "q, w 0 0 h x y cm, /Im Do, Q" per image
                float cx = 0;
                float cy = 0;
                for (Object token : new PDFStreamParser(doc.getPage(pi)).parse()) {
                    if (token instanceof COSNumber n) {
                        operands.add(n.floatValue());
                        continue;
                    }
                    if (token instanceof Operator op) {
                        int n = operands.size();
                        if (op.getName().equals("m") && n >= 2) {
                            cx = operands.get(n - 2);
                            cy = operands.get(n - 1);
                        } else if (op.getName().equals("l") && n >= 2) {
                            segments.add(new Segment(pi + 1, cx, cy, operands.get(n - 2), operands.get(n - 1)));
                            cx = operands.get(n - 2);
                            cy = operands.get(n - 1);
                        } else if (op.getName().equals("cm") && n >= 6) {
                            placed = new Box(
                                    pi + 1, operands.get(n - 2), operands.get(n - 1), operands.get(0), operands.get(3));
                        } else if (op.getName().equals("Do") && placed != null) {
                            images.add(placed);
                            placed = null;
                        } else if (op.getName().equals("re") && n >= 4) {
                            boxes.add(new Box(
                                    pi + 1,
                                    operands.get(n - 4),
                                    operands.get(n - 3),
                                    operands.get(n - 2),
                                    operands.get(n - 1)));
                        }
                    }
                    operands.clear();
                }
            }
        }
    }

    /** The number of pages in {@code pdf}. */
    static int pages(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }
}
