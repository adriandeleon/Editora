package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.editor.MarkdownPrintAssets;
import com.editora.editor.MarkdownRenderer;
import com.editora.print.MarkdownPrintLayout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the print-preview block measurement: a GFM table (a percent-width {@code GridPane} of wrapping
 * cells) must measure to its real, compact height at the printable width — not collapse to a few chars and
 * over-measure, which previously bumped a small table to its own page (print preview didn't match the
 * on-screen preview). See {@link MarkdownPrintLayout#measureBlockHeights}.
 *
 * <p>Also covers pagination itself — what is split and how, always-light pages, final sizes on the first
 * pass. None of it needs a printer: the page is given as a width and a height.
 */
@Tag("fx")
class MarkdownPrintLayoutFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void tableMeasuresCompactAtPrintableWidth() throws Exception {
        String md = "# Pagos\n\n| Deuda | Cantidad | Estatus |\n|---|---|---|\n"
                + "| Didi Prestamos | 1,960 | Done |\n| TDC Banorte #1 | 3,172 | Done |\n"
                + "| TDC Banorte #2 | 1,826 | Done |\n| TDC MercadoLibre | 5,400 | Done |\n"
                + "| TDC MercadoLibre | 9,900 | Done |\n| TDC Plata | 1,050 | Done |\n"
                + "| Bravo RTD | 9,000 | Done |\n";
        double pw = 540; // ~ letter printable width (points)
        double ph = 700; // ~ letter printable height (points)

        List<Double> heights = FxTestSupport.callOnFx(() -> {
            Node wrap = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(md), null);
            VBox content = (VBox) ((StackPane) wrap).getChildren().get(0);
            return MarkdownPrintLayout.measureBlockHeights(content, pw, ph);
        });

        assertEquals(2, heights.size(), "heading + table");
        double heading = heights.get(0);
        double table = heights.get(1);
        // Before the fix the columns collapsed and the 8-row table measured ~930px (> a page) — its own page.
        assertTrue(table < 400, "table height " + table + "px too large — columns collapsed?");
        assertTrue(heading + table < ph, "heading+table (" + (heading + table) + ") must fit one page");

        // …and it must therefore pack onto a single page with the heading.
        assertEquals(1, MarkdownPrintLayout.packBlocks(heights, ph).size(), "should be one page");
    }

    // --- over-tall blocks are split, not scaled ------------------------------------------------------

    /**
     * A long list is <b>one</b> top-level Markdown block, so the old "give an over-tall block its own page
     * and scale it to fit" rule crushed a whole document onto one sheet. Measured on this repo's CLAUDE.md
     * before the fix: a 296-page list at 0.3% scale, the file printing as fourteen mostly-blank pages.
     *
     * <p>Asserted as "no page is scaled", not as a page count: how many pages a list needs is the layout's
     * business, but shrinking text to fit is never the answer for text.
     */
    @Test
    void aLongListSplitsAcrossPagesRatherThanBeingScaledOntoOne() throws Exception {
        StringBuilder md = new StringBuilder("# Title\n\n");
        for (int i = 0; i < 200; i++) {
            md.append("- item ").append(i).append(" with enough words on it to take a whole line\n");
        }
        List<Node> pages = paginate(md.toString());
        assertTrue(pages.size() > 3, "a 200-item list should need several pages, got " + pages.size());
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
    }

    /** The same for a single paragraph longer than a page: it splits at its inline runs. */
    @Test
    void aParagraphLongerThanAPageSplitsRatherThanBeingScaled() throws Exception {
        StringBuilder md = new StringBuilder("# Title\n\n");
        for (int i = 0; i < 400; i++) {
            md.append("word").append(i).append(' ');
        }
        md.append("\n\nwordy short paragraph, which is never split.\n");
        List<Node> pages = paginate(md.toString());
        assertTrue(pages.size() > 1, "400 words do not fit one page");
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        // A split piece must print at the size it was measured at. The pieces used to carry a font set from
        // code, which fell back to the toolkit default (13px) once moved onto the page.
        java.util.Set<Double> sizes = new java.util.TreeSet<>();
        for (Node page : pages) {
            for (javafx.scene.text.Text t : texts(page)) {
                if (t.getText().startsWith("word")) {
                    sizes.add(t.getFont().getSize());
                }
            }
        }
        assertEquals(1, sizes.size(), "split pieces are the size of text that was never split, got " + sizes);
    }

    /**
     * A deeply nested list — the shape that actually defeated the first three attempts at this.
     *
     * <p>Each bullet level is three containers (list → item row → content box), and a list item's content
     * shares its row with the bullet; both had to be modelled or pieces came out marginally over the page.
     */
    @Test
    void aDeeplyNestedListStillSplitsCleanly() throws Exception {
        StringBuilder md = new StringBuilder("# Title\n\n");
        for (int i = 0; i < 12; i++) {
            md.append("- outer ").append(i).append('\n');
            for (int j = 0; j < 6; j++) {
                md.append("    - inner ").append(j).append(" with a good few words of text on it\n");
                md.append("        - deepest ").append(j).append(" also carrying a sentence of its own\n");
            }
        }
        List<Node> pages = paginate(md.toString());
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
    }

    /** Short input still produces exactly one page — the split path must not fragment what already fits. */
    @Test
    void aShortDocumentIsStillOnePage() throws Exception {
        List<Node> pages = paginate("# Title\n\nA short paragraph.\n");
        assertEquals(1, pages.size());
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
    }

    /** US Letter's printable area at default margins, in points — no printer is asked for it. */
    private static final double PAGE_W = 504;

    private static final double PAGE_H = 684;

    private static List<Node> paginate(String md) throws Exception {
        return paginate(MarkdownRenderer.parseToDocument(md), null);
    }

    /** Resolves the document's assets off the FX thread, as {@code PrintService} does, then paginates. */
    private static List<Node> paginate(org.commonmark.node.Node ast, java.nio.file.Path baseDir) throws Exception {
        MarkdownPrintAssets assets = MarkdownPrintAssets.resolve(ast, baseDir);
        return FxTestSupport.callOnFx(() -> MarkdownPrintLayout.paginate(ast, baseDir, assets, PAGE_W, PAGE_H));
    }

    // --- always light ---------------------------------------------------------------------------------

    /**
     * A printed page is white with dark text whatever the app theme is.
     *
     * <p>The page root carried {@code md-light} alone, which matches no rule — the palette is redefined by
     * {@code .markdown-preview-pane.md-light} — so under a dark theme the page came out as a dark sheet with
     * light text.
     */
    @Test
    void aPageIsLightUnderADarkAppTheme() throws Exception {
        String md = "# Title\n\nSome body text, long enough to leave plenty of ink on the page.\n\n"
                + "| A | B |\n|---|---|\n| 1 | 2 |\n\n```\ncode\n```\n";
        org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument(md);
        javafx.scene.image.WritableImage shot = FxTestSupport.callOnFx(() -> {
            String before = javafx.application.Application.getUserAgentStylesheet();
            javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor("Primer Dark"));
            try {
                Node page =
                        MarkdownPrintLayout.paginate(ast, null, PAGE_W, PAGE_H).get(0);
                return page.snapshot(new javafx.scene.SnapshotParameters(), null);
            } finally {
                javafx.application.Application.setUserAgentStylesheet(before);
            }
        });
        javafx.scene.image.PixelReader px = shot.getPixelReader();
        assertEquals(javafx.scene.paint.Color.WHITE, px.getColor(2, 2), "the page's corner is paper");
        int dark = 0;
        int light = 0;
        for (int y = 0; y < (int) shot.getHeight(); y += 2) {
            for (int x = 0; x < (int) shot.getWidth(); x += 2) {
                double b = px.getColor(x, y).getBrightness();
                if (b < 0.35) {
                    dark++;
                } else if (b > 0.9) {
                    light++;
                }
            }
        }
        assertTrue(dark > 50, "the text is dark ink, found " + dark + " dark samples");
        assertTrue(light > 20 * dark, "the page is mostly paper: " + light + " light vs " + dark + " dark samples");
    }

    /** Math is rasterised dark-on-white for print even when the app (and so the preview) is dark. */
    @Test
    void mathPrintsDarkOnWhiteUnderADarkAppTheme() throws Exception {
        org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument("Inline $x^2 + y^2$ here.\n");
        double brightness = FxTestSupport.callOnFx(() -> {
            com.editora.editor.MathImages.configure(true, true);
            try {
                Node page =
                        MarkdownPrintLayout.paginate(ast, null, PAGE_W, PAGE_H).get(0);
                javafx.scene.image.ImageView formula = (javafx.scene.image.ImageView) page.lookup(".md-math-inline");
                javafx.scene.image.Image img = formula.getImage();
                double darkest = 1;
                for (int y = 0; y < (int) img.getHeight(); y++) {
                    for (int x = 0; x < (int) img.getWidth(); x++) {
                        javafx.scene.paint.Color c = img.getPixelReader().getColor(x, y);
                        if (c.getOpacity() > 0.9) {
                            darkest = Math.min(darkest, c.getBrightness());
                        }
                    }
                }
                return darkest;
            } finally {
                com.editora.editor.MathImages.configure(false, false);
            }
        });
        assertTrue(brightness < 0.3, "the formula's ink must be dark, its solid pixels are " + brightness);
    }

    // --- tables ---------------------------------------------------------------------------------------

    /**
     * A table taller than a page is split between rows: every piece is still a table with all its columns
     * and starts with the header row, and no row is lost or reordered.
     *
     * <p>It used to be regrouped as a generic pane, which stacked every cell in a row of its own — 17 pages
     * of "#, Name, Value, 1, name 1, value 1, …" for this very table.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {40, 120}) // laid out whole, and measured in chunks
    void aTableTallerThanAPageSplitsBetweenRowsAndRepeatsItsHeader(int count) throws Exception {
        StringBuilder md = new StringBuilder("| # | Name | Value |\n|---|---|---|\n");
        for (int i = 1; i <= count; i++) {
            md.append("| ")
                    .append(i)
                    .append(" | name ")
                    .append(i)
                    .append(" | value ")
                    .append(i)
                    .append(" |\n");
        }
        List<Node> pages = paginate(md.toString());
        assertTrue(pages.size() >= 2 && pages.size() <= 8, count + " short rows are a few pages: " + pages.size());
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        List<String> firstColumn = new java.util.ArrayList<>();
        for (Node page : pages) {
            GridPane table = (GridPane) page.lookup(".md-table");
            assertEquals(3, table.getColumnConstraints().size(), "a piece keeps the table's columns");
            List<List<String>> rows = rowTexts(table);
            assertEquals(List.of("#", "Name", "Value"), rows.get(0), "every piece starts with the header row");
            for (List<String> row : rows.subList(1, rows.size())) {
                assertEquals(3, row.size(), "a row keeps its three cells side by side");
                firstColumn.add(row.get(0));
            }
        }
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            expected.add(String.valueOf(i));
        }
        assertEquals(expected, firstColumn, "every row prints once, in order");
    }

    /** Ten rows of twenty columns is one small table, not pages of single cells. */
    @Test
    void aTableWithManyColumnsStaysATable() throws Exception {
        List<Node> pages = paginate(com.editora.markdown.CsvTableDocument.fromCsv(csv(10, 20)), null);
        assertEquals(1, pages.size(), "ten rows fit one page once the cells stop wrapping a letter a line");
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        GridPane table = (GridPane) pages.get(0).lookup(".md-table");
        List<List<String>> rows = rowTexts(table);
        assertEquals(11, rows.size(), "header + ten rows");
        for (List<String> row : rows) {
            assertEquals(20, row.size(), "all twenty columns side by side");
        }
        assertTrue(table.getLayoutBounds().getWidth() <= PAGE_W, "and within the page width");
    }

    /** One row taller than a page is continued on the next page; none of its text is dropped or scaled. */
    @Test
    void aRowTallerThanAPageIsContinuedWithoutLosingText() throws Exception {
        String csv = "id,note\n1," + "longcell ".repeat(400).strip() + "\n2,short\n";
        List<Node> pages = paginate(com.editora.markdown.CsvTableDocument.fromCsv(csv), null);
        assertTrue(pages.size() >= 2, "400 words in one cell need more than a page");
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        int words = 0;
        boolean sawLastRow = false;
        for (Node page : pages) {
            List<List<String>> rows = rowTexts((GridPane) page.lookup(".md-table"));
            assertEquals(List.of("id", "note"), rows.get(0), "each page of the row is still a table with its header");
            rows.forEach(row -> assertEquals(2, row.size(), "both columns side by side"));
            for (javafx.scene.text.Text t : texts(page)) {
                words += t.getText().split("longcell", -1).length - 1;
                sawLastRow |= t.getText().equals("short");
            }
        }
        assertEquals(400, words, "every word of the long cell is printed");
        assertTrue(sawLastRow, "and the row after it");
    }

    /**
     * A long CSV paginates in time proportional to its length.
     *
     * <p>It was quadratic twice over: the splitter binary-searched each page with a full layout per step,
     * and a {@code GridPane} lays itself out in rows × cells. 5,000 rows took 156s on the FX thread. The
     * bound here is deliberately loose (it runs in about a second) — it only has to tell seconds from minutes.
     */
    @Test
    void aLongCsvPaginatesInLinearTime() throws Exception {
        org.commonmark.node.Node doc = com.editora.markdown.CsvTableDocument.fromCsv(csv(3000, 5));
        long start = System.nanoTime();
        List<Node> pages = paginate(doc, null);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 20_000, "3,000 rows took " + millis + "ms to paginate");
        assertTrue(pages.size() > 50, "3,000 rows are many pages, got " + pages.size());
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        int rows = 0;
        for (Node page : pages) {
            List<List<String>> piece = rowTexts((GridPane) page.lookup(".md-table"));
            assertEquals("Header0", piece.get(0).get(0), "every page repeats the header");
            rows += piece.size() - 1;
        }
        assertEquals(3000, rows, "every row prints once");
    }

    private static String csv(int rows, int cols) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c < cols; c++) {
                sb.append(c > 0 ? "," : "").append(r == 0 ? "Header" + c : "r" + r + "c" + c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** A table's cell texts, row by row, in column order. */
    private static List<List<String>> rowTexts(GridPane table) {
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, String>> rows = new java.util.TreeMap<>();
        for (Node cell : table.getChildren()) {
            Integer r = GridPane.getRowIndex(cell);
            Integer c = GridPane.getColumnIndex(cell);
            StringBuilder text = new StringBuilder();
            texts(cell).forEach(t -> text.append(t.getText()));
            rows.computeIfAbsent(r == null ? 0 : r, k -> new java.util.TreeMap<>())
                    .put(c == null ? 0 : c, text.toString());
        }
        List<List<String>> out = new java.util.ArrayList<>();
        rows.values().forEach(r -> out.add(new java.util.ArrayList<>(r.values())));
        return out;
    }

    /** Every {@code Text} under {@code node}, in document order. */
    private static List<javafx.scene.text.Text> texts(Node node) {
        List<javafx.scene.text.Text> out = new java.util.ArrayList<>();
        collectTexts(node, out);
        return out;
    }

    private static void collectTexts(Node node, List<javafx.scene.text.Text> out) {
        if (node instanceof javafx.scene.text.Text t) {
            out.add(t);
        } else if (node instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collectTexts(c, out));
        }
    }

    // --- code blocks ----------------------------------------------------------------------------------

    /**
     * A plain code block taller than a page prints every line.
     *
     * <p>It was one wrapping {@code Label}, measured in a page-high scene: it reported a page of height, was
     * never split, and printed "plain line 30..." with lines 31–150 gone.
     */
    @Test
    void aPlainCodeBlockTallerThanAPagePrintsEveryLine() throws Exception {
        StringBuilder md = new StringBuilder("```\n");
        for (int i = 1; i <= 150; i++) {
            md.append("plain line ").append(i).append('\n');
        }
        List<Node> pages = paginate(md.append("```\n").toString());
        assertTrue(pages.size() >= 3, "150 lines are several pages, got " + pages.size());
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        List<String> lines = new java.util.ArrayList<>();
        for (Node page : pages) {
            assertTrue(page.lookupAll(".label").isEmpty(), "no ellipsizing label on a printed code page");
            lines.addAll(codeLines(page));
        }
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            expected.add("plain line " + i);
        }
        assertEquals(expected, lines, "every line prints once, in order, whole");
    }

    /**
     * A highlighted fence taller than a page is cut between lines, stays monospaced, and carries its syntax
     * colours on the pages as they are handed over — not a pulse later, which is after they were printed.
     */
    @Test
    void aHighlightedFenceSplitsBetweenLinesInColourAndMonospace() throws Exception {
        StringBuilder md = new StringBuilder("```java\n");
        for (int i = 1; i <= 150; i++) {
            md.append("int line").append(i).append(" = ").append(i).append("; // code line number ");
            md.append(i).append('\n');
        }
        List<Node> pages = paginate(md.append("```\n").toString()); // nothing below waits for a pulse
        assertNoPageIsScaled(pages);
        assertNoPageOverflows(pages);
        List<String> lines = new java.util.ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            Node page = pages.get(p);
            lines.addAll(codeLines(page));
            Boolean[] seen = FxTestSupport.callOnFx(() -> {
                boolean keyword = false;
                boolean mono = true;
                javafx.scene.paint.Paint keywordFill = null;
                javafx.scene.paint.Paint plainFill = null;
                for (javafx.scene.text.Text t : texts(page)) {
                    if (t.getStyleClass().contains("keyword")) {
                        keyword = true;
                        keywordFill = t.getFill();
                    } else if (t.getStyleClass().size() == 1) {
                        plainFill = t.getFill();
                    }
                    javafx.scene.text.Text narrow = new javafx.scene.text.Text("iiii");
                    javafx.scene.text.Text wide = new javafx.scene.text.Text("MMMM");
                    narrow.setFont(t.getFont());
                    wide.setFont(t.getFont());
                    mono &= Math.abs(narrow.getLayoutBounds().getWidth()
                                    - wide.getLayoutBounds().getWidth())
                            < 0.01;
                }
                return new Boolean[] {keyword, mono, keywordFill != null && !keywordFill.equals(plainFill)};
            });
            assertTrue(seen[0], "page " + (p + 1) + " has highlighted keywords");
            assertTrue(seen[1], "page " + (p + 1) + " is set in a monospaced font");
            assertTrue(seen[2], "page " + (p + 1) + ": a keyword is a different colour from plain code");
        }
        assertEquals(150, lines.size(), "150 lines in, 150 lines out — none cut in two at a page boundary");
        for (int i = 1; i <= 150; i++) {
            assertEquals("int line" + i + " = " + i + "; // code line number " + i, lines.get(i - 1), "line " + i);
        }
    }

    /** The text of each code line on {@code page}: one {@code TextFlow} per line inside the code box. */
    private static List<String> codeLines(Node page) {
        List<String> out = new java.util.ArrayList<>();
        for (Node box : page.lookupAll(".md-code-lines")) {
            for (Node line : ((javafx.scene.Parent) box).getChildrenUnmodifiable()) {
                StringBuilder sb = new StringBuilder();
                texts(line).forEach(t -> sb.append(t.getText()));
                out.add(sb.toString());
            }
        }
        return out;
    }

    // --- images ---------------------------------------------------------------------------------------

    /**
     * Images have their final size the first time the document is paginated, with nothing cached.
     *
     * <p>They used to load in the background: a block measured 0px, the page was packed as if it were not
     * there, and the image then grew and pushed the rest of its page off the sheet (content down to y=1472
     * on a 684pt page). The preview, opened a moment later with a warm cache, showed a different pagination
     * from the one that printed.
     */
    @Test
    void imagesAreMeasuredAtTheirFinalSizeOnTheFirstPass(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        writePng(dir.resolve("mid.png"), 600, 400);
        writePng(dir.resolve("huge.png"), 1500, 2000);
        String lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor. ".repeat(12);
        String md = "# Images\n\n" + (lorem + "\n\n").repeat(3) + "![mid](mid.png)\n\nText after mid image.\n\n"
                + "Missing: ![missing alt](nope.png) end.\n\n![huge](huge.png)\n\nText after huge.\n";
        org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument(md);

        List<Node> pages = paginate(ast, dir);
        double[] lowest = FxTestSupport.callOnFx(() -> {
            double[] bottom = new double[pages.size()];
            for (int i = 0; i < pages.size(); i++) {
                for (Node view : pages.get(i).lookupAll(".image-view")) {
                    assertTrue(((javafx.scene.image.ImageView) view).getImage() != null, "the image is loaded");
                    bottom[i] = Math.max(
                            bottom[i],
                            view.localToScene(view.getBoundsInLocal()).getMaxY());
                }
            }
            return bottom;
        });
        int images = 0;
        for (int i = 0; i < pages.size(); i++) {
            images += pages.get(i).lookupAll(".image-view").size();
            assertTrue(lowest[i] <= PAGE_H + 1, "page " + (i + 1) + " has an image reaching y=" + lowest[i]);
        }
        assertEquals(2, images, "both images print");
        assertNoPageOverflows(pages);
        boolean alt = false;
        for (Node page : pages) {
            for (Node label : page.lookupAll(".md-inline-code")) {
                alt |= ((javafx.scene.control.Label) label).getText().equals("[image: missing alt]");
            }
        }
        assertTrue(alt, "an image that cannot be loaded prints its alt text");
        assertEquals(pages.size(), paginate(ast, dir).size(), "paginating again changes nothing");
    }

    private static void writePng(java.nio.file.Path file, int w, int h) throws java.io.IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.LIGHT_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", file.toFile());
    }

    /**
     * No page's content carries a scale transform.
     *
     * <p>This is the assertion that matters: a scaled page passes every other check — it is present, it
     * holds the right blocks, the count looks plausible — while being unreadable, which is exactly how the
     * bug shipped.
     */
    private static void assertNoPageIsScaled(List<Node> pages) {
        for (int i = 0; i < pages.size(); i++) {
            Node body = ((StackPane) pages.get(i)).getChildren().get(0);
            Node content =
                    (body instanceof javafx.scene.Group g) ? g.getChildren().get(0) : body;
            assertTrue(
                    content.getScaleY() > 0.999,
                    "page " + (i + 1) + " of " + pages.size() + " was shrunk to " + content.getScaleY()
                            + " instead of being split across pages");
        }
    }

    /**
     * No page's content is taller than the page.
     *
     * <p>The complement of {@link #assertNoPageIsScaled}: scaling is one way a too-tall block can reach a
     * page, overflowing is the other, and only both together say the split actually worked. It also guards
     * the arithmetic fast path — a {@code VBox}'s group height is predicted from its children's rather than
     * laid out, and a prediction that ran slightly optimistic would show up here and nowhere else.
     */
    private static void assertNoPageOverflows(List<Node> pages) {
        for (int i = 0; i < pages.size(); i++) {
            StackPane page = (StackPane) pages.get(i);
            double limit = page.getPrefHeight();
            double content = page.getChildren().get(0).getLayoutBounds().getHeight();
            assertTrue(
                    content <= limit + 1.0,
                    "page " + (i + 1) + " of " + pages.size() + " holds " + content + "px of content on a " + limit
                            + "px page");
        }
    }

    /**
     * The arithmetic path predicts a {@code VBox} group's height exactly.
     *
     * <p>Pagination is 94% layout, so a group's height is computed from its children's measured heights
     * rather than laid out — valid because a VBox stacks children at their preferred heights and the clone
     * is pinned to the printable width, so a child measured alone is the height it will be in a group. That
     * reasoning is what this checks, against a real layout, rather than trusting it.
     *
     * <p>Padding and spacing are read back <em>after</em> CSS rather than assumed: {@code .markdown-preview}
     * sets both, and overrides whatever the code set. Writing this test with the values it had passed in was
     * wrong by 60px.
     */
    @Test
    void aVBoxGroupIsAsTallAsItsChildrenStacked() throws Exception {
        double[] measured = FxTestSupport.callOnFx(() -> {
            List<javafx.scene.text.Text> kids = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                kids.add(new javafx.scene.text.Text("child " + i + " with a line of text on it"));
            }
            VBox group = styledBox();
            group.getChildren().addAll(kids);
            double whole = MarkdownPrintLayout.measureOne(group, 400, 800);
            double padding = group.getPadding().getTop() + group.getPadding().getBottom();
            double spacing = group.getSpacing();
            group.getChildren().clear();

            double sum = 0;
            for (javafx.scene.text.Text kid : kids) {
                VBox solo = styledBox();
                solo.getChildren().add(kid);
                sum += MarkdownPrintLayout.measureOne(solo, 400, 800) - padding;
                solo.getChildren().clear();
            }
            return new double[] {whole, padding + sum + spacing * (kids.size() - 1)};
        });
        assertEquals(
                measured[0],
                measured[1],
                0.5,
                "a VBox's height must be its children stacked, or the split's fast path mispredicts");
    }

    private static VBox styledBox() {
        VBox box = new VBox();
        box.getStyleClass().add("markdown-preview");
        return box;
    }
}
