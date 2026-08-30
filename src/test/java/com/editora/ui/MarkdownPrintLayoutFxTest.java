package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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

    /** A page small enough that ordinary Markdown overflows it, so the split path is the one under test. */
    private static final double PW = 400;

    private static final double PH = 200;

    /** Paginates {@code md} and returns each page's laid-out content height. */
    private static List<Double> pageContentHeights(String md) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            javafx.print.PageLayout layout = null;
            javafx.print.Printer printer = javafx.print.Printer.getDefaultPrinter();
            if (printer != null) {
                layout = printer.createPageLayout(
                        javafx.print.Paper.NA_LETTER,
                        javafx.print.PageOrientation.PORTRAIT,
                        javafx.print.Printer.MarginType.DEFAULT);
            }
            if (layout == null) {
                return null; // no printer on this machine — the caller skips
            }
            List<Node> pages = MarkdownPrintLayout.paginate(MarkdownRenderer.parseToDocument(md), null, layout);
            List<Double> out = new java.util.ArrayList<>();
            for (Node page : pages) {
                out.add(((StackPane) page)
                        .getChildren()
                        .get(0)
                        .getLayoutBounds()
                        .getHeight());
            }
            return out;
        });
    }

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
        org.junit.jupiter.api.Assumptions.assumeTrue(pages != null, "no printer available");
        assertTrue(pages.size() > 3, "a 200-item list should need several pages, got " + pages.size());
        assertNoPageIsScaled(pages);
    }

    /** The same for a single paragraph longer than a page: it splits at its inline runs. */
    @Test
    void aParagraphLongerThanAPageSplitsRatherThanBeingScaled() throws Exception {
        StringBuilder md = new StringBuilder("# Title\n\n");
        for (int i = 0; i < 400; i++) {
            md.append("word").append(i).append(' ');
        }
        List<Node> pages = paginate(md.toString());
        org.junit.jupiter.api.Assumptions.assumeTrue(pages != null, "no printer available");
        assertNoPageIsScaled(pages);
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
        org.junit.jupiter.api.Assumptions.assumeTrue(pages != null, "no printer available");
        assertNoPageIsScaled(pages);
    }

    /** Short input still produces exactly one page — the split path must not fragment what already fits. */
    @Test
    void aShortDocumentIsStillOnePage() throws Exception {
        List<Node> pages = paginate("# Title\n\nA short paragraph.\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(pages != null, "no printer available");
        assertEquals(1, pages.size());
        assertNoPageIsScaled(pages);
    }

    private static List<Node> paginate(String md) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            javafx.print.Printer printer = javafx.print.Printer.getDefaultPrinter();
            if (printer == null) {
                return null;
            }
            javafx.print.PageLayout layout = printer.createPageLayout(
                    javafx.print.Paper.NA_LETTER,
                    javafx.print.PageOrientation.PORTRAIT,
                    javafx.print.Printer.MarginType.DEFAULT);
            return MarkdownPrintLayout.paginate(MarkdownRenderer.parseToDocument(md), null, layout);
        });
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
}
