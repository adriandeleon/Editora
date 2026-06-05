package com.editora.print;

import java.util.ArrayList;
import java.util.List;

import com.editora.pdf.PdfText;
import com.editora.pdf.PdfTheme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Builds printable JavaFX page nodes for source code — the {@code javafx.print} analog of
 * {@code com.editora.pdf.CodePdfWriter}. Reuses the pure, font-agnostic layout core
 * {@link PdfText} (line/run flattening + monospace column wrapping) and the light {@link PdfTheme}
 * palette (its {@code java.awt.Color}s are converted to JavaFX {@link Color}). Each page is a
 * {@code VBox} of one row per visual line (optional right-aligned line-number gutter + a
 * {@code TextFlow} of colored runs), paginated by whole lines so nothing is split across a page edge.
 *
 * <p>{@link #columns} and {@link #linesPerPage} are pure and unit-tested; {@link #paginate} needs the
 * JavaFX toolkit (fonts/text) and runs on the FX thread at print time.
 */
public final class CodePrintLayout {

    /** Print font size (pt), matching the PDF code export. */
    public static final double FONT_SIZE = 9;
    /** Line height as a multiple of the font size. */
    private static final double LINE_SPACING = 1.3;
    /** Gap between the line-number gutter and the code, in px. */
    private static final double GUTTER_GAP = 8;

    private CodePrintLayout() {
    }

    /** Monospace columns that fit in {@code printableWidth} at {@code charWidth} px/char (min 1). */
    public static int columns(double printableWidth, double charWidth) {
        if (charWidth <= 0 || !Double.isFinite(charWidth) || !Double.isFinite(printableWidth)) {
            return 1;
        }
        return Math.max(1, (int) Math.floor(printableWidth / charWidth));
    }

    /** Text rows that fit in {@code printableHeight} at {@code lineHeight} px/row (min 1). */
    public static int linesPerPage(double printableHeight, double lineHeight) {
        if (lineHeight <= 0 || !Double.isFinite(lineHeight) || !Double.isFinite(printableHeight)) {
            return 1;
        }
        return Math.max(1, (int) Math.floor(printableHeight / lineHeight));
    }

    /**
     * Lays {@code lines} (per-source-line runs from {@link PdfText#splitIntoLineRuns}) out into one
     * {@code VBox} per printed page for {@code layout}, wrapping long lines to the printable width and
     * packing whole visual lines per page. {@code lineNumbers} adds a muted right-aligned gutter.
     */
    public static List<Node> paginate(List<List<PdfText.Run>> lines, PageLayout layout,
            boolean lineNumbers, Font mono) {
        double charW = charWidth(mono);
        double lineH = Math.ceil(mono.getSize() * LINE_SPACING);
        int total = lines.size();
        int digits = Integer.toString(Math.max(1, total)).length();
        double gutterW = lineNumbers ? digits * charW + GUTTER_GAP : 0;
        int cols = columns(layout.getPrintableWidth() - gutterW, charW);
        int perPage = linesPerPage(layout.getPrintableHeight(), lineH);

        List<Node> pages = new ArrayList<>();
        VBox page = new VBox();
        int onPage = 0;
        for (int i = 0; i < total; i++) {
            List<List<PdfText.Run>> visual = PdfText.wrap(lines.get(i), cols);
            for (int v = 0; v < visual.size(); v++) {
                if (onPage == perPage) {
                    pages.add(page);
                    page = new VBox();
                    onPage = 0;
                }
                int lineNo = v == 0 ? i + 1 : 0; // 0 → blank gutter on a wrap continuation
                page.getChildren().add(row(visual.get(v), lineNo, lineNumbers, gutterW, mono, lineH));
                onPage++;
            }
        }
        if (onPage > 0 || pages.isEmpty()) {
            pages.add(page);
        }
        return pages;
    }

    private static HBox row(List<PdfText.Run> runs, int lineNo, boolean lineNumbers, double gutterW,
            Font mono, double lineH) {
        HBox rowBox = new HBox();
        rowBox.setMinHeight(lineH);
        rowBox.setPrefHeight(lineH);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        if (lineNumbers) {
            Text num = new Text(lineNo == 0 ? "" : Integer.toString(lineNo));
            num.setFont(mono);
            num.setFill(fx(PdfTheme.LINE_NUMBER));
            HBox gutter = new HBox(num);
            gutter.setAlignment(Pos.CENTER_RIGHT);
            gutter.setMinWidth(gutterW);
            gutter.setPrefWidth(gutterW);
            gutter.setMaxWidth(gutterW);
            gutter.setPadding(new Insets(0, GUTTER_GAP, 0, 0));
            rowBox.getChildren().add(gutter);
        }
        TextFlow flow = new TextFlow();
        flow.setMaxHeight(lineH);
        for (PdfText.Run r : runs) {
            Text t = new Text(r.text());
            t.setFont(variant(mono, r.bold(), r.italic()));
            t.setFill(fx(r.color()));
            flow.getChildren().add(t);
        }
        rowBox.getChildren().add(flow);
        return rowBox;
    }

    /** Width of one monospace character, measured from the font (no scene required). */
    static double charWidth(Font mono) {
        Text probe = new Text("M");
        probe.setFont(mono);
        return probe.getLayoutBounds().getWidth();
    }

    private static Font variant(Font base, boolean bold, boolean italic) {
        return Font.font(base.getFamily(),
                bold ? FontWeight.BOLD : FontWeight.NORMAL,
                italic ? FontPosture.ITALIC : FontPosture.REGULAR,
                base.getSize());
    }

    /** Converts a {@link PdfTheme} {@code java.awt.Color} to a JavaFX {@link Color}. */
    static Color fx(java.awt.Color c) {
        return Color.rgb(c.getRed(), c.getGreen(), c.getBlue());
    }
}
