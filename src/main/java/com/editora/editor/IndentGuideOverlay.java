package com.editora.editor;

import java.util.function.IntSupplier;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.editops.IndentLevels;
import org.fxmisc.richtext.CodeArea;

/**
 * Faint vertical lines at each indentation level of the visible lines (VS Code's indent guides), painted on a
 * {@link Canvas} over the text.
 *
 * <p>Cheap by construction: one character-bounds query per redraw finds column 0, the column width comes from
 * the font ({@link ColumnAdvance}), and each visible line costs one paragraph-bounds query for its height — so
 * a wrapped line's guides run its full height. A blank line takes the indentation of the block around it
 * ({@link IndentLevels#widths}), so guides do not break at every empty line. Folded lines are skipped; redraws
 * are coalesced to one per pulse; the canvas stays 1×1 while the guides are off, the tab is in the background,
 * or the buffer is in large-file mode.
 */
final class IndentGuideOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    /** How far past the viewport a blank run at its edge looks for the block it belongs to. */
    private static final int LOOKAROUND = 200;

    /** Redraws that painted, app-wide: a test seam for the per-edit budget. */
    static final java.util.concurrent.atomic.AtomicInteger REDRAWS_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Test seam: when set, every guide painted is recorded here as {@code {x, top, bottom}}. */
    static java.util.List<double[]> guidesForTest;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private final ColumnAdvance advance = new ColumnAdvance();
    /** The indent unit in columns ({@code tabSize} for a tab) and the tab size, read at each redraw. */
    private final IntSupplier unitColumns;

    private final IntSupplier tabSize;

    private Color color = OverlayPalette.of(Color.WHITE).indentGuide();
    private String fontFamily;
    private double fontSize = 14;
    private boolean requested;
    private boolean suppressed;
    private boolean active;
    private boolean rendering = true;
    private boolean redrawPending;
    private IndentGuideOverlay follower;

    IndentGuideOverlay(CodeArea area, IntSupplier unitColumns, IntSupplier tabSize) {
        this.area = area;
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

    private void scheduleRedraw() {
        if (!drawable() || redrawPending) {
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
            painted = 0;
            return;
        }
        CanvasGuards.fit(this, canvas);
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        try {
            int total = area.getParagraphs().size();
            int unit = Math.max(1, unitColumns.getAsInt());
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            if (total == 0 || last < first) {
                g.clearRect(0, 0, w, h);
                painted = 0;
                return;
            }
            int[] widths = IndentLevels.widths(
                    p -> area.getParagraph(p).getText(), total, first, last, tabSize.getAsInt(), LOOKAROUND);
            // Most keystrokes change no line's indentation and move nothing: the picture is the same, so the
            // layout queries below are skipped. Everything the picture depends on is in the key.
            long key = frameKey(first, last, widths, unit, w, h);
            if (key == painted) {
                return;
            }
            g.clearRect(0, 0, w, h);
            painted = 0;
            Double zero = columnZeroX(first, last);
            if (zero == null) {
                return;
            }
            REDRAWS_FOR_TEST.incrementAndGet();
            painted = key;
            double cell = advance.of(fontFamily, fontSize);
            g.setStroke(color);
            g.setLineWidth(1);
            for (int p = first; p <= last; p++) {
                int guides = IndentLevels.guides(widths[p - first], unit);
                if (guides == 0 || area.isFolded(p)) {
                    continue;
                }
                Bounds box = area.getParagraphBoundsOnScreen(p)
                        .map(canvas::screenToLocal)
                        .orElse(null);
                if (box == null) {
                    continue;
                }
                for (int k = 0; k < guides; k++) {
                    double x = Math.floor(zero + k * unit * cell) + 0.5; // a crisp one-pixel line
                    if (x < 0 || x > w) {
                        continue;
                    }
                    g.strokeLine(x, box.getMinY(), x, box.getMaxY());
                    if (guidesForTest != null) {
                        guidesForTest.add(new double[] {x, box.getMinY(), box.getMaxY()});
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout: skip this frame, a later event redraws.
        }
    }

    /** The last frame painted (see {@link #frameKey}); 0 = none, so the next redraw paints. */
    private long painted;

    /**
     * What the guides depend on: the visible lines and their indentation, the unit, the scroll position, the
     * document's height (a line that wraps anew, an inlay row), the colour and font, the canvas size, and which
     * lines are folded.
     */
    private long frameKey(int first, int last, int[] widths, int unit, double w, double h) {
        long key = 17;
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

    /** Canvas-local x of column 0, from the first character of a visible line; null when none has one. */
    private Double columnZeroX(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p) || area.getParagraphLength(p) == 0) {
                continue;
            }
            int abs = area.getAbsolutePosition(p, 0);
            Bounds b = area.getCharacterBoundsOnScreen(abs, abs + 1)
                    .map(canvas::screenToLocal)
                    .orElse(null);
            if (b != null) {
                return b.getMinX();
            }
        }
        return null;
    }
}
