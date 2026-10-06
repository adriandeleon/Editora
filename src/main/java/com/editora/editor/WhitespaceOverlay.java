package com.editora.editor;

import javafx.application.Platform;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import org.fxmisc.richtext.CodeArea;

/**
 * A transparent overlay that draws "hidden character" markers — {@code ·} for spaces, {@code →} for
 * tabs, {@code ¶} for line ends — on top of the editor without touching the document text.
 *
 * <p>RichTextFX has no native whitespace rendering, so markers are painted on a {@link Canvas} sized
 * to the text viewport. Each marker is positioned from {@link CodeArea#getCharacterBoundsOnScreen} so
 * it stays aligned across tabs and any font. Only the currently visible paragraphs are drawn, and
 * redraws (on scroll / edit / resize / fold) are coalesced to one per pulse — when inactive the
 * overlay does nothing, so it is free unless the user turns it on.
 */
final class WhitespaceOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private OverlayPalette.Colors colors = OverlayPalette.of(Color.WHITE);
    private static final String SPACE = "·";
    private static final String TAB = "→";
    private static final String EOL = "¶";

    /**
     * Test seam: {@code false} sends every line down the per-run measured path, so a test can render the
     * same viewport both ways and require the two pictures to agree.
     */
    static boolean uniformPath = true;

    /** Test seam: when set, every marker painted is also recorded here as {@code {glyph, x, y}}. */
    static java.util.List<double[]> markersForTest;

    /** How many columns past its first one a line is measured at to derive the cell advance. */
    private static final int UNIFORM_SPAN = 512;

    /** A line at least this long is culled to the visible columns before any marker is measured. */
    private static final int CULL_MIN_LENGTH = 256;
    /** How far (px) a predicted marker may sit from its measured place before a line is measured per run. */
    private static final double ADVANCE_TOLERANCE = 0.5;

    /**
     * Layout queries (character bounds and hit tests) issued by all whitespace overlays — the test seam that
     * pins the per-viewport budget, like {@code EditorBuffer.RULER_MEASURES_FOR_TEST}.
     */
    static final java.util.concurrent.atomic.AtomicInteger LAYOUT_QUERIES_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private boolean active;
    /** What the setting asks for; {@link #active} is this minus the large-file suppression. */
    private boolean requested;

    private boolean suppressed;
    private boolean redrawPending;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;

    private Font font = Font.font("monospace", 14);
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private WhitespaceOverlay follower;

    WhitespaceOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("whitespace-overlay");
        setMouseTransparent(true);
        // Inactive until "show whitespace" is on: stay hidden so the renderer composites nothing and the
        // 1x1 canvas holds no full-viewport GPU texture (whitespace markers are off by default).
        setVisible(false);
        getChildren().add(canvas);
        // Anything that changes what's on screen (scroll, resize, layout, fold) or the text itself.
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        OverlayPalette.track(area, palette -> {
            colors = palette; // resolved on a theme change, not per paint
            scheduleRedraw();
        });
    }

    /**
     * The same markers for a split's second {@code view}: an overlay to put in that view's pane, from then
     * on switched and re-fonted together with this one.
     */
    @Override
    public WhitespaceOverlay follower(CodeArea view) {
        var second = new WhitespaceOverlay(view);
        second.font = font;
        second.suppressed = suppressed;
        second.setRenderingActive(rendering);
        second.setActive(requested);
        follower = second;
        return second;
    }

    /** Turns the markers on or off (they stay off regardless while {@link #setSuppressed suppressed}). */
    void setActive(boolean on) {
        if (follower != null) {
            follower.setActive(on);
        }
        requested = on;
        apply();
    }

    /**
     * Keeps the markers off in large-file mode, whatever the setting says. The 64 KiB long-line profile is
     * part of that mode, and a marker per whitespace character of such a paragraph is exactly the
     * per-viewport work the mode exists to avoid; the setting is remembered and applies again on leaving it.
     */
    void setSuppressed(boolean on) {
        if (follower != null) {
            follower.setSuppressed(on);
        }
        suppressed = on;
        apply();
    }

    private void apply() {
        boolean want = requested && !suppressed;
        if (active == want) {
            return;
        }
        active = want;
        setVisible(want);
        if (want) {
            requestLayout(); // size the canvas up to the viewport, then draw
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas); // drop the full-viewport texture while hidden
        }
    }

    /** Keeps the marker glyphs in step with the editor font. */
    void setFont(String family, int size) {
        this.font = Font.font(family, size);
        scheduleRedraw();
        if (follower != null) {
            follower.setFont(family, size);
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!drawable()) {
            return; // stay 1x1 / no texture while off, backgrounded, or with nothing to draw
        }
        CanvasGuards.fit(this, canvas);
        scheduleRedraw();
    }

    /**
     * Releases the canvas while this overlay's tab is in the background and repaints when it is shown again
     * (driven by {@code EditorBuffer.setRenderingActive}). A hidden tab would otherwise keep a
     * viewport-sized texture alive for as long as it stays open.
     */
    @Override
    public void setRenderingActive(boolean on) {
        if (follower != null) {
            follower.setRenderingActive(on);
        }
        if (rendering == on) {
            return;
        }
        rendering = on;
        if (on) {
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas);
        }
    }

    /** Whether there is anything to paint; the canvas is 1x1 (no viewport texture) whenever there is not. */
    private boolean drawable() {
        return active && rendering;
    }

    /** Coalesces a burst of viewport events into a single redraw on the next pulse. */
    private void scheduleRedraw() {
        if (!active || !rendering || redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    private void redraw() {
        if (!drawable() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            CanvasGuards.release(canvas);
            return;
        }
        CanvasGuards.fit(this, canvas); // grown here as well: content can arrive without a layout pass
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            confirmedCell = Double.NaN;
            g.setFill(colors.whitespace());
            g.setFont(font);
            g.setTextBaseline(VPos.CENTER);
            // The text area's left edge (past the line-number gutter). Empty lines have no character
            // to anchor their ¶ to, so we reuse this x derived from a real character on any line.
            double contentLeftX = contentLeftX(first, last);
            for (int p = first; p <= last; p++) {
                // Skip collapsed (folded) paragraphs: they aren't rendered, and their character
                // bounds collapse onto the fold-header row, piling stray markers there.
                if (area.isFolded(p)) {
                    continue;
                }
                drawParagraph(g, p, total, w, contentLeftX);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /**
     * The local x of the text area's left edge, taken from the first character of the first
     * non-empty visible paragraph. Returns {@code -1} if no visible line has any text.
     */
    private double contentLeftX(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p) || area.getParagraph(p).getText().isEmpty()) {
                continue;
            }
            Bounds b = charBounds(p, 0);
            if (b != null) {
                return b.getMinX();
            }
        }
        return -1;
    }

    private void drawParagraph(GraphicsContext g, int paragraph, int total, double w, double contentLeftX) {
        String line = area.getParagraph(paragraph).getText();
        int len = line.length();
        boolean eol = paragraph < total - 1; // the document's final paragraph has no line break to mark
        g.setTextAlign(TextAlignment.CENTER);
        if (uniformPath && len > 0 && !area.isWrapText()) {
            int lead = uniformFrom(line);
            if (lead >= 0 && drawUniform(g, paragraph, line, lead, w, eol)) {
                return;
            }
        }
        int[] columns = visibleColumns(paragraph, len);
        // getCharacterBoundsOnScreen is a synchronous layout query; a space-indented line would issue one
        // per leading space. In a monospace font adjacent non-tab glyphs advance by the same amount, so
        // within a contiguous run of spaces we advance arithmetically from real queries. Tabs (variable
        // width → tab stops) are always queried, and any non-whitespace gap forces the next marker to
        // re-query — so positions stay exact.
        Bounds prev = null; // bounds of the previous whitespace marker, for arithmetic advance
        int prevIndex = -2; // its index (contiguity check); prevTab guards advancing past a tab box
        boolean prevTab = false;
        // The advance between two adjacent cells of this line, measured from the first pair of adjacent
        // spaces. Not a box's width: the box RichTextFX reports is the selection shape's outline, which is
        // wider than the advance, so stepping by it pushed each further marker of a run off its space.
        double advance = Double.NaN;
        for (int i = columns[0]; i <= columns[1]; i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                continue; // a non-whitespace gap; the next marker re-queries (contiguity broken)
            }
            boolean adjacent = c == ' ' && prev != null && i == prevIndex + 1 && !prevTab;
            Bounds local;
            if (adjacent && !Double.isNaN(advance)) {
                local = new BoundingBox(prev.getMinX() + advance, prev.getMinY(), prev.getWidth(), prev.getHeight());
            } else {
                local = charBounds(paragraph, i);
                if (adjacent && local != null && local.getMinY() == prev.getMinY()) {
                    advance = local.getMinX() - prev.getMinX();
                }
            }
            if (local == null) {
                prev = null;
                prevIndex = -2;
                continue;
            }
            prev = local; // keep tracking even when off-screen so contiguity (and advance) holds
            prevIndex = i;
            prevTab = c == '\t';
            double cx = local.getMinX() + local.getWidth() / 2;
            if (cx < 0 || cx > w) {
                continue; // off-screen horizontally
            }
            mark(g, c == ' ' ? SPACE : TAB, cx, local.getMinY() + local.getHeight() / 2);
        }
        // End-of-line marker. Skipped without a query when the columns were culled short of the line end:
        // the last character is then outside the viewport and so is its marker.
        if (eol && columns[1] >= len - 1) {
            Bounds end = len > 0
                    ? charBounds(paragraph, len - 1)
                    : toLocal(area.getParagraphBoundsOnScreen(paragraph).orElse(null));
            // For an empty line, anchor the ¶ at the text-area left edge, not the line's
            // on-screen minX (which is the gutter); skip if no reference x is available.
            if (end != null && (len > 0 || contentLeftX >= 0)) {
                drawEol(g, len > 0 ? end.getMaxX() + 2 : contentLeftX, end, w);
            }
        }
    }

    private void mark(GraphicsContext g, String glyph, double x, double y) {
        g.fillText(glyph, x, y);
        if (markersForTest != null) {
            markersForTest.add(new double[] {glyph.charAt(0), x, y});
        }
    }

    private void drawEol(GraphicsContext g, double x, Bounds row, double w) {
        if (x >= 0 && x <= w) {
            g.setTextAlign(TextAlignment.LEFT);
            mark(g, EOL, x, row.getMinY() + row.getHeight() / 2);
            g.setTextAlign(TextAlignment.CENTER);
        }
    }

    /**
     * Where {@code line}'s uniform part starts — the column after its leading tabs — when everything from
     * there on is printable ASCII (no further tab stop, no wide or fallback-font glyph); {@code -1} when
     * the line has none, or nothing after its indentation.
     */
    static int uniformFrom(String line) {
        int lead = 0;
        while (lead < line.length() && line.charAt(lead) == '\t') {
            lead++;
        }
        for (int i = lead; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < ' ' || c > '~') {
                return -1;
            }
        }
        return lead < line.length() ? lead : -1;
    }

    /** The cell advance confirmed for an earlier line of this repaint (NaN before the first). */
    private double confirmedCell = Double.NaN;

    /**
     * Draws an unwrapped line whose text after the leading tabs is plain ASCII from a handful of layout
     * queries, whatever its length. Two characters are measured and every marker between them is placed a
     * whole number of cells along; the cell they imply must match the one already confirmed for this
     * repaint, or a third measurement in between must land where the arithmetic says. Returns {@code false}
     * having drawn nothing when it does not (a proportional font, a span styled at another size), and the
     * caller then measures that line run by run.
     */
    private boolean drawUniform(GraphicsContext g, int paragraph, String line, int lead, double w, boolean eol) {
        int len = line.length();
        Bounds a = charBounds(paragraph, lead);
        if (a == null) {
            return false;
        }
        int anchor = lead;
        int far = Math.min(len - 1, lead + UNIFORM_SPAN);
        Bounds end = a; // the measured box nearest the line end
        double cell = Double.isNaN(confirmedCell) ? a.getWidth() : confirmedCell;
        if (far > lead) {
            Bounds f = charBounds(paragraph, far);
            if (f == null || f.getMinY() != a.getMinY()) {
                return false;
            }
            cell = (f.getMinX() - a.getMinX()) / (far - lead);
            if (cell <= 0 || !cellHolds(paragraph, a, lead, far, cell)) {
                return false;
            }
            end = f;
        }
        if (!(cell > 0)) {
            return false;
        }
        int lo = Math.max(lead, lead + (int) Math.floor(-a.getMinX() / cell) - 1);
        int hi = Math.min(len - 1, lead + (int) Math.ceil((w - a.getMinX()) / cell) + 1);
        if (hi > far) {
            // The viewport reaches past the stretch just confirmed (a long line, scrolled or not): measure
            // again at its two visible ends, so no marker is ever placed more than a screenful of cells
            // from a real measurement.
            anchor = Math.min(lo, len - 1);
            a = charBounds(paragraph, anchor);
            end = charBounds(paragraph, hi);
            if (a == null
                    || end == null
                    || Math.abs(end.getMinX() - (a.getMinX() + (hi - anchor) * cell)) > ADVANCE_TOLERANCE) {
                return false;
            }
            if (hi > anchor) {
                // Far along a long line the layout's own single-precision positions round each step, so the
                // advance here is a hair off the one measured near column 0; take it from these two ends.
                cell = (end.getMinX() - a.getMinX()) / (hi - anchor);
            }
        }
        double y = a.getMinY() + a.getHeight() / 2;
        for (int i = lo; i <= hi; i++) {
            if (line.charAt(i) == ' ') {
                double cx = a.getMinX() + (i - anchor) * cell + a.getWidth() / 2;
                if (cx >= 0 && cx <= w) {
                    mark(g, SPACE, cx, y);
                }
            }
        }
        for (int i = 0; i < lead && lo == lead; i++) { // the indentation, unless it is scrolled out of view
            Bounds tab = charBounds(paragraph, i);
            double cx = tab == null ? -1 : tab.getMinX() + tab.getWidth() / 2;
            if (cx >= 0 && cx <= w) {
                mark(g, TAB, cx, y);
            }
        }
        if (eol && hi >= len - 1) {
            drawEol(g, end.getMaxX() + 2, a, w);
        }
        return true;
    }

    /**
     * Whether the cell implied by the boxes at {@code from} and {@code to} is the real advance of every
     * column between them. The first line of a repaint proves it with a measurement at the midpoint; later
     * lines only have to imply the same cell, and one that does not (a line in another font size) proves
     * itself the same way without disturbing the confirmed value.
     */
    private boolean cellHolds(int paragraph, Bounds a, int from, int to, double cell) {
        if (Math.abs(cell - confirmedCell) * (to - from) <= ADVANCE_TOLERANCE) {
            return true;
        }
        int mid = (from + to) >>> 1;
        if (mid > from) {
            Bounds m = charBounds(paragraph, mid);
            if (m == null || Math.abs(m.getMinX() - (a.getMinX() + (mid - from) * cell)) > ADVANCE_TOLERANCE) {
                return false;
            }
        }
        if (Double.isNaN(confirmedCell)) {
            confirmedCell = cell;
        }
        return true;
    }

    /**
     * The inclusive column range of {@code paragraph} that can be on screen. A long unwrapped line is asked
     * for the characters under the viewport's left and right edges (two hit tests) instead of having every
     * whitespace run in it measured only to find it off-screen; a short or wrapped one is taken whole.
     */
    private int[] visibleColumns(int paragraph, int len) {
        int[] all = {0, len - 1};
        if (len < CULL_MIN_LENGTH || area.isWrapText()) {
            return all;
        }
        Bounds row = area.getParagraphBoundsOnScreen(paragraph).orElse(null);
        Bounds view = area.localToScreen(area.getLayoutBounds());
        if (row == null || view == null) {
            return all;
        }
        LAYOUT_QUERIES_FOR_TEST.addAndGet(2);
        double y = area.screenToLocal(view.getMinX(), (row.getMinY() + row.getMaxY()) / 2)
                .getY();
        int base = area.getAbsolutePosition(paragraph, 0);
        int left = area.hit(0, y).getInsertionIndex() - base;
        int right = area.hit(area.getWidth(), y).getInsertionIndex() - base;
        if (left > right || right < 0 || left > len) {
            return all; // the row was not the one hit (mid-layout) — measure the line the slow, exact way
        }
        return new int[] {Math.max(0, left - 2), Math.min(len - 1, right + 2)};
    }

    private Bounds charBounds(int paragraph, int column) {
        LAYOUT_QUERIES_FOR_TEST.incrementAndGet();
        int abs = area.getAbsolutePosition(paragraph, column);
        return toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
