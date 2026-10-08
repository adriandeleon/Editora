package com.editora.pdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.MarkdownRenderer;
import com.editora.editor.MathImages;
import com.editora.markdown.CsvTableDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tables in the Markdown PDF: styled cells, alignment, content-sized columns, repeated headers. */
class MarkdownPdfTableTest {

    private static final float LEFT = MarkdownPdfWriter.MARGIN;
    private static final float RIGHT = 612f - MarkdownPdfWriter.MARGIN;

    private static Path write(Path dir, String md) throws Exception {
        Path out = dir.resolve("table.pdf");
        MarkdownPdfWriter.write(MarkdownRenderer.parseToDocument(md), dir, "letter", null, out, List.of());
        return out;
    }

    /** The x of every vertical table border on page 1, left to right (the outer box is a rectangle). */
    private static List<Float> columnEdges(Path pdf) throws Exception {
        return PdfProbe.segments(pdf).stream()
                .filter(s -> s.page() == 1 && s.vertical())
                .map(PdfProbe.Segment::x1)
                .distinct()
                .sorted()
                .toList();
    }

    @Test
    void cellsKeepTheirInlineStyling(@TempDir Path dir) throws Exception {
        Path out = write(
                dir,
                "| Name | Notes |\n|---|---|\n| plain | has **bold**, `code`, ~~gone~~ and a [site](https://example.com/t) |\n");
        String text = PdfProbe.text(out);
        assertTrue(text.contains("has bold, code, gone and a site"), "the words and their spacing survive: " + text);
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        // Struck text has its line through the middle of the word.
        List<PdfProbe.Glyph> gone = PdfProbe.glyphsOf(glyphs, "gone");
        float base = gone.get(0).baseline();
        assertTrue(
                PdfProbe.segments(out).stream()
                        .anyMatch(s -> s.horizontal()
                                && s.y1() > base + 1f
                                && s.y1() < base + 6f
                                && s.x1() <= gone.get(0).x() + 0.5f
                                && s.x2() >= gone.get(3).right() - 0.5f),
                "~~gone~~ is struck through in a cell");
        // The link is underlined and clickable.
        List<PdfProbe.Glyph> site = PdfProbe.glyphsOf(glyphs, "site");
        assertTrue(
                PdfProbe.segments(out).stream()
                        .anyMatch(s -> s.horizontal()
                                && s.y1() < base
                                && s.y1() > base - 3f
                                && Math.abs(s.x1() - site.get(0).x()) < 0.5f),
                "the link is underlined");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getPage(0).getAnnotations().size());
            PDAnnotationLink link =
                    (PDAnnotationLink) doc.getPage(0).getAnnotations().get(0);
            assertEquals("https://example.com/t", ((PDActionURI) link.getAction()).getURI());
            assertTrue(link.getRectangle().getLowerLeftX() <= site.get(0).x() + 0.5f);
            // Bold and code are different fonts from the body's.
            String fonts = fontsOf(doc);
            assertTrue(fonts.contains("Inter-Bold") && fonts.contains("JetBrainsMono"), fonts);
        }
    }

    private static String fontsOf(PDDocument doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (org.apache.pdfbox.cos.COSName name : doc.getPage(0).getResources().getFontNames()) {
            sb.append(doc.getPage(0).getResources().getFont(name).getName()).append(' ');
        }
        return sb.toString();
    }

    @Test
    void columnsAreAlignedAsTheDelimiterRowSays(@TempDir Path dir) throws Exception {
        Path out = write(
                dir,
                "| Left side | Centre col | Right side |\n|:---|:---:|---:|\n| l | c | 7 |\n| lll | ccc | 1234.50 |\n");
        List<Float> edges = columnEdges(out);
        assertEquals(2, edges.size(), "two inner borders: " + edges);
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float pad = MarkdownPdfWriter.CELL_PAD;
        // Left: at the cell's left padding.
        assertEquals(LEFT + pad, PdfProbe.glyphsOf(glyphs, "lll").get(0).x(), 0.6f);
        // Right: both numbers end at the same x, the cell's right padding.
        List<PdfProbe.Glyph> big = PdfProbe.glyphsOf(glyphs, "1234.50");
        assertEquals(RIGHT - pad, big.get(big.size() - 1).right(), 0.6f, "right-aligned");
        PdfProbe.Glyph seven =
                glyphs.stream().filter(g -> g.text().equals("7")).findFirst().orElseThrow();
        assertEquals(RIGHT - pad, seven.right(), 0.6f, "the short number too");
        // Centre: the cell's text is centred between its borders.
        List<PdfProbe.Glyph> ccc = PdfProbe.glyphsOf(glyphs, "ccc");
        float mid = (ccc.get(0).x() + ccc.get(2).right()) / 2f;
        assertEquals((edges.get(0) + edges.get(1)) / 2f, mid, 0.6f, "centred");
        // …and so is the header of a centred column.
        List<PdfProbe.Glyph> head = PdfProbe.glyphsOf(glyphs, "Centrecol");
        assertEquals(
                (edges.get(0) + edges.get(1)) / 2f,
                (head.get(0).x() + head.get(head.size() - 1).right()) / 2f,
                0.6f);
    }

    @Test
    void columnWidthsFollowTheContent() {
        // Everything fits: proportional to the natural widths, filling the table.
        assertArrayEquals(
                new float[] {100f, 300f},
                MarkdownPdfWriter.columnWidths(new float[] {50, 150}, new float[] {20, 30}, 400),
                0.01f);
        // Too wide: each column keeps its widest word, and the rest goes to the column that wants more.
        float[] w = MarkdownPdfWriter.columnWidths(new float[] {40, 40, 900}, new float[] {40, 40, 60}, 400);
        assertEquals(40f, w[0], 0.01f, "a column of short words is not squeezed");
        assertEquals(40f, w[1], 0.01f);
        assertEquals(320f, w[2], 0.01f, "the long cell takes what is left and wraps");
        // Two long columns share what is left in proportion to what they still want.
        w = MarkdownPdfWriter.columnWidths(new float[] {50, 500, 1000}, new float[] {50, 50, 50}, 450);
        assertEquals(50f, w[0], 0.01f);
        assertEquals(450f, w[0] + w[1] + w[2], 0.01f);
        assertEquals(2.111f, (w[2] - 50f) / (w[1] - 50f), 0.01f, "(1000-50) : (500-50)");
        // One unbreakable monster (a URL) cannot starve the others: its floor is capped.
        w = MarkdownPdfWriter.columnWidths(new float[] {80, 2000}, new float[] {80, 2000}, 400);
        assertEquals(400f, w[0] + w[1], 0.01f);
        assertTrue(w[0] >= 80f - 0.01f, "the short column keeps its word: " + w[0]);
        // Even the words do not fit: only the widest columns give way, the narrow ones stay whole.
        w = MarkdownPdfWriter.columnWidths(new float[] {30, 30, 90, 90}, new float[] {30, 30, 90, 90}, 200);
        assertArrayEquals(new float[] {30f, 30f, 70f, 70f}, w, 0.01f);
        // Degenerate input still yields a usable table.
        assertArrayEquals(
                new float[] {50f, 50f},
                MarkdownPdfWriter.columnWidths(new float[] {0, 0}, new float[] {0, 0}, 100),
                0.01f);
    }

    @Test
    void aFourteenColumnTableDoesNotBreakShortWords(@TempDir Path dir) throws Exception {
        String[] words = {
            "alpha",
            "bravo",
            "charlie",
            "delta",
            "echo",
            "foxtrot",
            "golf",
            "hotel",
            "india",
            "juliet",
            "kilo",
            "lima",
            "mike",
            "november"
        };
        StringBuilder md = new StringBuilder("|");
        for (int i = 1; i <= 14; i++) {
            md.append(" c").append(i).append(" |");
        }
        md.append("\n|").append("---|".repeat(14)).append("\n|");
        for (String w : words) {
            md.append(' ').append(w).append(" |");
        }
        Path out = write(dir, md + "\n");
        List<String> lines = PdfProbe.text(out).lines().map(String::strip).toList();
        for (String w : words) {
            assertTrue(
                    lines.stream().anyMatch(l -> l.matches("(.*\\s)?" + w + "(\\s.*)?")),
                    w + " is on one line, not broken mid-word: " + lines);
        }
        assertTrue(PdfProbe.rightmostInk(out) <= RIGHT + 0.5f, "the table stays inside the margin");
        List<Float> edges = columnEdges(out);
        assertEquals(13, edges.size());
        assertTrue(
                edges.get(12) - edges.get(11) < (RIGHT - edges.get(12)), "\"november\" gets more room than \"mike\"");
    }

    @Test
    void aLongCellWrapsWhileShortColumnsStayNarrow(@TempDir Path dir) throws Exception {
        Path out = write(
                dir,
                "| Id | Description |\n|---|---|\n| 1 | " + "a long description that keeps going ".repeat(8) + "|\n");
        List<Float> edges = columnEdges(out);
        assertEquals(1, edges.size());
        assertTrue(edges.get(0) - LEFT < 60f, "the Id column is as narrow as its content: " + edges);
        assertTrue(PdfProbe.text(out).lines().count() >= 4, "the description wraps");
        assertTrue(PdfProbe.rightmostInk(out) <= RIGHT - MarkdownPdfWriter.CELL_PAD + 0.5f);
    }

    @Test
    void theHeaderRowIsRepeatedOnEveryPageTheTableReaches(@TempDir Path dir) throws Exception {
        StringBuilder md = new StringBuilder("| Key | Value |\n|---|---|\n");
        for (int i = 1; i <= 130; i++) {
            md.append("| k").append(i).append(" | value ").append(i).append(" |\n");
        }
        Path out = write(dir, md.toString());
        int pages = PdfProbe.pages(out);
        assertTrue(pages >= 3, "the table spans pages: " + pages);
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        for (int p = 1; p <= pages; p++) {
            int page = p;
            List<PdfProbe.Glyph> onPage =
                    glyphs.stream().filter(g -> g.page() == page).toList();
            List<PdfProbe.Glyph> key = PdfProbe.glyphsOf(onPage, "Key");
            float top = (float)
                    onPage.stream().mapToDouble(PdfProbe.Glyph::baseline).max().orElseThrow();
            assertEquals(top, key.get(0).baseline(), 0.01f, "the header is the first row of page " + p);
            assertTrue(
                    PdfProbe.boxes(out).stream().anyMatch(b -> b.page() == page && b.y() > top - 10f),
                    "…with its shaded band");
        }
        String text = PdfProbe.squeezed(out);
        for (int i = 1; i <= 130; i++) {
            assertTrue(text.contains("k" + i + "value" + i), "row " + i + " is lost");
        }
        assertEquals(pages, text.split("KeyValue", -1).length - 1, "once per page, not once per row");
    }

    @Test
    void theHeaderIsRepeatedAboveEachBandOfARowTallerThanAPage(@TempDir Path dir) throws Exception {
        Path out = write(
                dir,
                "| Key | Value |\n|---|---|\n| big | " + "many words in one enormous cell ".repeat(260)
                        + "|\n| after | y |\n");
        int pages = PdfProbe.pages(out);
        assertTrue(pages >= 3, "the row spans pages: " + pages);
        String text = PdfProbe.squeezed(out);
        assertEquals(pages, text.split("KeyValue", -1).length - 1, "the header leads every page");
        assertEquals(
                260,
                text.replace("KeyValue", "").split("manywordsinoneenormouscell", -1).length - 1,
                "no text is lost or repeated");
        assertTrue(text.contains("aftery"));
        for (PdfProbe.Glyph g : PdfProbe.glyphs(out)) {
            assertTrue(g.baseline() >= MarkdownPdfWriter.MARGIN, "drawn below the bottom margin: " + g);
        }
    }

    @Test
    void aHeaderIsNotLeftAloneAtTheFootOfAPage(@TempDir Path dir) throws Exception {
        // 43 body lines fill the page to within two lines of the bottom: the header fits, a row would not.
        for (int filler = 40; filler <= 46; filler++) {
            Path out = write(dir, "line  \n".repeat(filler) + "end\n\n| Key | Value |\n|---|---|\n| a | b |\n");
            List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
            PdfProbe.Glyph key = PdfProbe.glyphsOf(glyphs, "Key").get(0);
            PdfProbe.Glyph row = PdfProbe.glyphsOf(glyphs, "b").get(0);
            assertEquals(key.page(), row.page(), "header and first row share a page (" + filler + " lines above)");
            assertEquals(1, PdfProbe.squeezed(out).split("KeyValue", -1).length - 1);
        }
    }

    @Test
    void csvCellsAreNeverReadAsMathOrMarkup(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("csv.pdf");
        MathImages.configure(true, false);
        try {
            MarkdownPdfWriter.writeData(
                    CsvTableDocument.fromCsv("item,price\n**tea**,$5 to $9\n"),
                    null,
                    PdfPageSpec.of("letter"),
                    new PdfDocMeta("prices.csv", null, null),
                    null,
                    out);
        } finally {
            MathImages.configure(false, false);
        }
        String text = PdfProbe.text(out);
        assertTrue(text.contains("**tea**"), text);
        assertTrue(text.contains("$5 to $9"), "a price range is text, not a formula: " + text);
        assertEquals(0, PdfProbe.images(out).size());
        assertTrue(PdfProbe.artifactText(out).contains("prices.csv"), "a CSV PDF has the footer too");
        assertFalse(Files.size(out) == 0);
    }
}
