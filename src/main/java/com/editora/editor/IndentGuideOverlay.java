package com.editora.editor;

import java.util.function.IntSupplier;

import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.editops.IndentLevels;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Faint vertical lines at each indentation level of the visible lines (VS Code's indent guides), painted on a
 * {@link Canvas} over the text.
 *
 * <p>Placed from the text as it is laid out: one character-bounds query per redraw finds column 0, and a
 * repaint measures the width of one column of indentation on the most deeply indented visible line (the font's
 * own metrics, {@link ColumnAdvance}, are only the fallback when no visible line is indented — they measure a
 * digit, and in a proportional font a space is narrower). Each visible line costs one paragraph-bounds query for
 * its height, so a wrapped line's guides run its full height, and a guide is stroked once per run of lines that
 * carry it. A blank line takes the indentation of the block around it ({@link IndentLevels#widths}), so guides
 * do not break at every empty line. Folded lines are skipped.
 *
 * <p>Redraws are coalesced to one per pulse and run <em>after</em> that pulse's layout (see
 * {@link #scheduleRedraw}). The canvas stays 1×1 while the guides are off, the tab is in the background, or the
 * buffer is in large-file mode.
 */
final class IndentGuideOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    /** How far past the viewport a blank run at its edge looks for the block it belongs to. */
    private static final int LOOKAROUND = 200;

    /** Redraws that painted, app-wide: a test seam for the per-edit budget. */
    static final java.util.concurrent.atomic.AtomicInteger REDRAWS_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Test seam: when set, the guides of the last frame painted are recorded here as {@code {x, top, bottom}}. */
    static java.util.@Nullable List<double[]> guidesForTest;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private final ColumnAdvance advance = new ColumnAdvance();
    /** The indent unit in columns ({@code tabSize} for a tab) and the tab size, read at each redraw. */
    private final IntSupplier unitColumns;

    private final IntSupplier tabSize;

    private Color color = OverlayPalette.of(Color.WHITE).indentGuide();
    private @Nullable String fontFamily;
    private double fontSize = 14;
    private boolean requested;
    private boolean suppressed;
    private boolean active;
    private boolean rendering = true;
    private boolean redrawPending;
    /** Runs the repaint after the layout pass, and looks again after it (see {@link AfterLayoutPaint}). */
    private final AfterLayoutPaint afterLayout;

    private @Nullable IndentGuideOverlay follower;

    IndentGuideOverlay(CodeArea area, IntSupplier unitColumns, IntSupplier tabSize) {
        this.area = area;
        this.afterLayout = new AfterLayoutPaint(
                this,
                area,
                () -> {
                    redrawPending = false;
                    redraw();
                },
                this::drawable);
        this.unitColumns = unitColumns;
        this.tabSize = tabSize;
        getStyleClass().add("indent-guide-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        OverlayPalette.track(area, palette -> {
            color = palette.indentGuide();
            scheduleRedraw();
        });
    }

    /** The same guides for a split's second {@code view}, switched and re-fonted with these. */
    @Override
    public IndentGuideOverlay follower(CodeArea view) {
        IndentGuideOverlay second = new IndentGuideOverlay(view, unitColumns, tabSize);
        second.fontFamily = fontFamily;
        second.fontSize = fontSize;
        second.suppressed = suppressed;
        second.setRenderingActive(rendering);
        second.setActive(requested);
        follower = second;
        return second;
    }

    /** Turns the guides on or off (they stay off while {@link #setSuppressed suppressed}). */
    void setActive(boolean on) {
        if (follower != null) {
            follower.setActive(on);
        }
        requested = on;
        apply();
    }

    /** Keeps the guides off in large-file mode, whatever the setting says. */
    void setSuppressed(boolean on) {
        if (follower != null) {
            follower.setSuppressed(on);
        }
        suppressed = on;
        apply();
    }

    /** The editor font, whose column width places the guides. */
    void setFont(String family, double size) {
        if (follower != null) {
            follower.setFont(family, size);
        }
        fontFamily = family;
        fontSize = size;
        painted = 0; // a new column width: measured again by the next paint
        scheduleRedraw();
    }

    /** Asks for a redraw: the indent unit or tab size may have changed. */
    void refresh() {
        if (follower != null) {
            follower.refresh();
        }
        painted = 0; // asked for: paint even if nothing it can see changed
        scheduleRedraw();
    }

    private void apply() {
        boolean want = requested && !suppressed;
        if (active == want) {
            return;
        }
        active = want;
        setVisible(want);
        if (want) {
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas);
            painted = 0;
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!drawable()) {
            return;
        }
        CanvasGuards.fit(this, canvas);
        scheduleRedraw();
    }

    @Override
    public void setRenderingActive(boolean on) {
        if (follower != null) {
            follower.setRenderingActive(on);
        }
        if (rendering == on) {
            return;
        }
        rendering = on;
        painted = 0;
        if (on) {
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas);
        }
    }

    private boolean drawable() {
        return active && rendering;
    }

    /**
     * Asks for a repaint after the next layout pass (see {@link AfterLayoutPaint}). Where the text starts is part of the frame key as well, so a
     * repaint that finds nothing moved costs no layout query beyond that one.
     */
    private void scheduleRedraw() {
        if (!drawable() || redrawPending) {
            return;
        }
        redrawPending = true;
        afterLayout.request();
    }

    /** Repaints when anything the guides depend on changed; returns whether it painted a new frame. */
    private boolean redraw() {
        if (!drawable() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            CanvasGuards.release(canvas);
            painted = 0;
            return false;
        }
        CanvasGuards.fit(this, canvas);
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        try {
            int total = area.getParagraphs().size();
            int unit = Math.max(1, unitColumns.getAsInt());
            int tab = tabSize.getAsInt();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            if (total == 0 || last < first) {
                g.clearRect(0, 0, w, h);
                painted = 0;
                return false;
            }
            int[] widths =
                    IndentLevels.widths(p -> area.getParagraph(p).getText(), total, first, last, tab, LOOKAROUND);
            double zero = columnZeroX(first, last);
            // Most keystrokes change no line's indentation and move nothing: the picture is the same, so the
            // per-line layout queries below are skipped. Everything the picture depends on is in the key —
            // where the text starts included, which moves without a scroll or an edit when the gutter changes.
            long key = frameKey(first, last, widths, unit, w, h, zero);
            if (key == painted) {
                return false;
            }
            g.clearRect(0, 0, w, h);
            painted = 0;
            if (Double.isNaN(zero)) {
                return false;
            }
            REDRAWS_FOR_TEST.incrementAndGet();
            if (guidesForTest != null) {
                guidesForTest.clear();
            }
            painted = key;
            double cell = columnWidth(first, last, tab, zero);
            // Scrolled sideways, the text slides under the gutter, which stays put: a guide whose column went
            // under it is not drawn over the line numbers. Column 0 at no scroll is where the text area begins.
            double textLeft = Math.floor(zero + area.getEstimatedScrollX());
            g.setStroke(color);
            g.setLineWidth(1);
            int deepest = 0;
            for (int width : widths) {
                deepest = Math.max(deepest, IndentLevels.guides(width, unit));
            }
            // Each guide column is stroked once per run of consecutive lines that carry it, not once per line.
            double[] runTop = new double[deepest];
            double[] runBottom = new double[deepest];
            java.util.Arrays.fill(runTop, Double.NaN);
            for (int p = first; p <= last; p++) {
                int guides = IndentLevels.guides(widths[p - first], unit);
                Bounds box = guides == 0 || area.isFolded(p)
                        ? null
                        : area.getParagraphBoundsOnScreen(p)
                                .map(canvas::screenToLocal)
                                .orElse(null);
                for (int k = 0; k < deepest; k++) {
                    boolean carries = k < guides;
                    if (box != null
                            && carries
                            && !Double.isNaN(runTop[k])
                            && Math.abs(box.getMinY() - runBottom[k]) <= 1) {
                        runBottom[k] = box.getMaxY(); // the run goes on
                        continue;
                    }
                    if (!Double.isNaN(runTop[k])) {
                        stroke(g, guideX(zero, cell, k, unit), runTop[k], runBottom[k], textLeft, w);
                        runTop[k] = Double.NaN;
                    }
                    if (box != null && carries) {
                        runTop[k] = box.getMinY();
                        runBottom[k] = box.getMaxY();
                    }
                }
            }
            for (int k = 0; k < deepest; k++) {
                if (!Double.isNaN(runTop[k])) {
                    stroke(g, guideX(zero, cell, k, unit), runTop[k], runBottom[k], textLeft, w);
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            // Viewport mid-layout: skip this frame, a later event redraws.
            return false;
        }
    }

    /** A crisp one-pixel line at the left edge of guide {@code k}'s column. */
    private static double guideX(double zero, double cell, int k, int unit) {
        return Math.floor(zero + k * unit * cell) + 0.5;
    }

    private void stroke(GraphicsContext g, double x, double top, double bottom, double textLeft, double w) {
        if (x < textLeft || x > w) {
            return;
        }
        g.strokeLine(x, top, x, bottom);
        if (guidesForTest != null) {
            guidesForTest.add(new double[] {x, top, bottom});
        }
    }

    /** The last frame painted (see {@link #frameKey}); 0 = none, so the next redraw paints. */
    private long painted;

    /**
     * What the guides depend on: where column 0 is, the visible lines and their indentation, the unit, the scroll
     * position, the document's height (a line that wraps anew, an inlay row), the colour and font, the canvas
     * size, and which lines are folded.
     */
    private long frameKey(int first, int last, int[] widths, int unit, double w, double h, double zero) {
        long key = 17;
        key = 31 * key + Double.hashCode(zero);
        key = 31 * key + first;
        key = 31 * key + last;
        key = 31 * key + java.util.Arrays.hashCode(widths);
        key = 31 * key + unit;
        key = 31 * key + Double.hashCode(area.getEstimatedScrollX());
        key = 31 * key + Double.hashCode(area.getEstimatedScrollY());
        key = 31 * key + Double.hashCode(area.getTotalHeightEstimate());
        key = 31 * key + Double.hashCode(w);
        key = 31 * key + Double.hashCode(h);
        key = 31 * key + color.hashCode();
        key = 31 * key + java.util.Objects.hash(fontFamily, fontSize, area.isWrapText());
        for (int p = first; p <= last; p++) {
            key = 31 * key + (area.isFolded(p) ? 1 : 0);
            if (area.isWrapText()) {
                key = 31 * key + area.getParagraphLength(p); // a longer line may wrap onto another row
            }
        }
        return key == 0 ? 1 : key;
    }

    /** Canvas-local x of column 0, from the first character of a visible line; NaN when none has one. */
    private double columnZeroX(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p) || area.getParagraphLength(p) == 0) {
                continue;
            }
            Bounds b = bounds(p, 0);
            if (b != null) {
                return b.getMinX();
            }
        }
        return Double.NaN;
    }

    /**
     * The width of one column of indentation, read off the laid-out text: on the most deeply indented visible
     * line, the first character after the indentation sits {@link IndentLevels#width} columns past column 0 (a
     * tab reaching its tab stop, as the text lays it out). With no indented line on screen, or one whose
     * indentation wraps, the font's metrics stand in.
     */
    private double columnWidth(int first, int last, int tab, double zero) {
        int best = -1;
        int bestWidth = 0;
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p)) {
                continue;
            }
            int width = IndentLevels.width(area.getParagraph(p).getText(), tab);
            if (width > bestWidth) {
                best = p;
                bestWidth = width;
            }
        }
        if (best >= 0) {
            String text = area.getParagraph(best).getText();
            int lead = 0;
            while (text.charAt(lead) == ' ' || text.charAt(lead) == '\t') {
                lead++;
            }
            Bounds after = bounds(best, lead);
            // Unwrapped, the indentation is on the line's only row; wrapped, it may have run onto the next one.
            Bounds start = area.isWrapText() ? bounds(best, 0) : after;
            if (start != null && after != null && Math.abs(after.getMinY() - start.getMinY()) < 1) {
                double cell = (after.getMinX() - zero) / bestWidth;
                if (cell > 0) {
                    return cell;
                }
            }
        }
        return advance.of(fontFamily, fontSize);
    }

    /** The canvas-local bounds of character {@code column} of {@code paragraph}, or null. */
    private @Nullable Bounds bounds(int paragraph, int column) {
        int abs = area.getAbsolutePosition(paragraph, column);
        return area.getCharacterBoundsOnScreen(abs, abs + 1)
                .map(canvas::screenToLocal)
                .orElse(null);
    }
}
