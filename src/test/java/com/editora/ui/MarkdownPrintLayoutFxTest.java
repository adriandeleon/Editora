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
}
