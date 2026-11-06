package com.editora.editor;

import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;

import com.editora.config.RulerColumns;
import org.jspecify.annotations.Nullable;

/**
 * Where a buffer's column rulers stand: the columns in force (Settings, per language, {@code .editorconfig}), and
 * the deferred measurement of column 0's on-screen x that places them — coalesced to one per pulse, skipped for a
 * background tab, re-confirmed two frames after a changed gutter, font or wrap. Character bounds are never queried
 * inside a layout pass.
 */
final class BufferRuler {

    private final EditorBuffer b;

    BufferRuler(EditorBuffer b) {
        this.b = b;
    }

    private @Nullable Integer
            rulerColumnOverride; // .editorconfig max_line_length: null = none; EditorConfigProperties.OFF = hide
    /** The ruler columns from Settings (see {@link RulerColumns#resolve}): the global list and per-language lists. */
    private List<Integer> rulerColumns = List.of(80);

    java.util.Map<String, List<Integer>> languageRulers = java.util.Map.of();
    /** Whether the user enabled the 80-column ruler. The line is only actually shown when a visible
     *  line reaches column 80 (see {@link #measureAndPlaceRuler}). */
    private boolean rulerVisible;

    /** Coalesces ruler re-measurement onto a later pulse (see {@link #scheduleRulerMeasure}). */
    private boolean rulerMeasurePending;
    /** Viewport width at the last scheduled ruler measure, so a viewport-dirty event that did not
     *  resize the area (a vertical scroll, an edit) doesn't pay for a re-measure. */
    double lastRulerViewportWidth = -1;
    /** Set when something the ruler's x depends on changed other than the viewport width or the
     *  horizontal scroll — the gutter, the font, wrap, the ruler column. Cleared by the measure. */
    boolean rulerInputsDirty = true;

    private final ColumnAdvance columnAdvance = new ColumnAdvance();

    /** Show/hide the column-80 ruler overlay. */
    void setColumnRulerVisible(boolean visible) {
        if (visible == rulerVisible) {
            return; // unchanged: measuring the ruler forces a VirtualFlow layout, so don't re-do it for nothing
        }
        this.rulerVisible = visible;
        if (visible) {
            markRulerInputsDirty();
        } else {
            b.columnRuler.hide();
        }
    }

    /**
     * The ruler columns Settings asks for: {@code global}, or the list {@code perLanguage} has for this buffer's
     * language. An {@code .editorconfig} {@code max_line_length} still replaces the first (see
     * {@link RulerColumns#resolve}). Re-measures only when they changed.
     */
    void setRulerColumns(List<Integer> global, java.util.Map<String, List<Integer>> perLanguage) {
        List<Integer> g = global == null ? List.of() : List.copyOf(global);
        java.util.Map<String, List<Integer>> l =
                perLanguage == null ? java.util.Map.of() : java.util.Map.copyOf(perLanguage);
        if (g.equals(rulerColumns) && l.equals(languageRulers)) {
            return; // runs for every buffer on every settings apply
        }
        rulerColumns = g;
        languageRulers = l;
        markRulerInputsDirty();
    }

    /** The columns the rulers stand at now (see {@link RulerColumns#resolve}). */
    List<Integer> rulerColumnsInForce() {
        return RulerColumns.resolve(rulerColumns, languageRulers, b.language, rulerColumnOverride);
    }

    /**
     * Records that an input to the ruler's position changed other than the viewport width or the horizontal
     * scroll (the gutter, the font, wrap, the ruler column), and schedules the re-measure. The flag survives
     * until a measure actually runs, so a change made before the area has been laid out is not lost.
     */
    void markRulerInputsDirty() {
        rulerInputsDirty = true;
        scheduleRulerMeasure();
    }

    /**
     * Coalesces a ruler re-measurement onto the next pulse. Measuring queries character bounds, which
     * must not happen synchronously inside a layout/viewport event (it re-enters layout and blanks the
     * editor), so we always defer it via {@link Platform#runLater}.
     */
    void scheduleRulerMeasure() {
        // Measuring queries the viewport (a forced VirtualFlow layout) + character bounds. A background tab
        // is not on screen, so doing that for it is pure cost — and a settings apply dirties every open
        // buffer's viewport at once, which is what made a Simple-mode toggle scale with the number of tabs.
        // setRenderingActive re-schedules when the tab is shown again, so nothing is left stale.
        if (!rulerVisible || rulerMeasurePending || !b.renderingActive) {
            return;
        }
        rulerMeasurePending = true;
        Platform.runLater(() -> {
            rulerMeasurePending = false;
            measureAndPlaceRuler();
        });
    }

    /**
     * How many times the ruler has been measured, app-wide. A test seam, not state the editor reads: how
     * often this runs is a measured property (each measure is two forced {@code VirtualFlow} layouts plus
     * character-bounds queries), and without a counter a regression to the old measure-on-every-viewport-event
     * subscription would be invisible — the ruler would still land in the right place, just far more often.
     * See {@code ColumnRulerFxTest}.
     */
    static final java.util.concurrent.atomic.AtomicInteger RULER_MEASURES_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Positions the rulers at their columns (80 unless Settings or {@code .editorconfig} say otherwise), drawn
     * whether or not any text reaches them. Each is column 0's on-screen x plus that many advances of the
     * editor font (see {@link #columnZeroX}), so it is exact regardless of which glyphs are present; one
     * measure serves them all. A ruler is hidden when its column falls outside the visible text width (the
     * window is too narrow, or the text is scrolled past it).
     */
    private void measureAndPlaceRuler() {
        RULER_MEASURES_FOR_TEST.incrementAndGet();
        lastRulerViewportWidth = b.area.getWidth();
        List<Integer> columns = rulerColumnsInForce();
        if (!rulerVisible || columns.isEmpty()) {
            b.columnRuler.hide();
            rulerInputsDirty = false; // nothing to place; a later show() re-marks via setColumnRulerVisible
            return;
        }
        boolean inputsChanged = rulerInputsDirty;
        Double x = columnZeroX();
        // Clear the pending flag only on a measure that actually produced a position. Before the area's
        // first layout the geometry is unmeasurable and columnZeroX returns null; clearing the flag there
        // would leave the gate waiting for a viewport WIDTH change that may never come, and the ruler would
        // stay hidden for the life of the buffer.
        if (x != null) {
            rulerInputsDirty = false;
            if (inputsChanged) {
                confirmRulerAfterLayout();
            }
        }
        b.columnRuler.place(x, columnAdvance.of(b.fontFamily, b.fontSize), columns, b.scrollPane.getWidth());
    }

    /**
     * Measures once more two frames after a measure that followed a changed input (gutter, font, wrap). That
     * first measure runs from {@code runLater}, which can land before the pulse that lays the new gutter out;
     * it then reads column 0 at its old x and nothing else would ever correct it. One-shot, and the second
     * measure does not re-arm it. A changed input while one is waiting starts the two frames again: the
     * confirm that was about to fire would otherwise also run before the new layout, and be the last word.
     */
    private void confirmRulerAfterLayout() {
        rulerConfirmFrames = 0;
        if (rulerConfirm != null) {
            return;
        }
        rulerConfirm = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (++rulerConfirmFrames < 2) {
                    return;
                }
                stop();
                rulerConfirm = null;
                if (rulerVisible && b.renderingActive) {
                    measureAndPlaceRuler();
                }
            }
        };
        rulerConfirm.start();
    }

    private javafx.animation.@Nullable AnimationTimer rulerConfirm;
    private int rulerConfirmFrames;

    /** Whether a ruler measure is still to come (the deferred one or its confirmation); for tests to wait on. */
    boolean rulerMeasurePending() {
        return rulerMeasurePending || rulerConfirm != null;
    }

    /**
     * Root-local x of column 0: the left edge of the first character of any visible line — a property of the
     * gutter and the horizontal scroll, not of the text. A ruler column is this plus that many advances of the
     * editor font (see {@link ColumnAdvance}; never derived from the visible text, which a short line, a tab or
     * a wide glyph skewed). Returns {@code null} when no visible line has a character.
     */
    private @Nullable Double columnZeroX() {
        try {
            int first = Math.max(0, b.area.firstVisibleParToAllParIndex());
            int last = Math.min(b.area.getParagraphs().size() - 1, b.area.lastVisibleParToAllParIndex());
            for (int p = first; p <= last; p++) {
                if (b.area.isFolded(p) || b.area.getParagraphLength(p) == 0) {
                    continue; // collapsed or empty: no character to take column 0 from
                }
                Bounds start = caretBounds(p, 0);
                if (start != null) {
                    return start.getMinX();
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout; a later event will re-measure.
        }
        return null;
    }

    /**
     * Root-local bounds of the caret at {@code column} in paragraph {@code p}, or {@code null}.
     *
     * <p><b>Never query the zero-width {@code getCharacterBoundsOnScreen(abs, abs)} form here.</b> For an
     * empty range {@code GenericStyledArea} allocates a throwaway {@link org.fxmisc.richtext.CaretNode} to
     * measure with, and a {@code CaretNode} starts a 500 ms {@code restartableTicks} blink timer that
     * nothing ever stops — so every call permanently registers a running JavaFX {@code Timeline} as a pulse
     * receiver. This method runs 2–3 times per ruler measurement and the ruler re-measures on
     * {@code viewportDirtyEvents}, i.e. on every edit: measured at <b>+2 leaked timers per keystroke</b>,
     * with typing latency degrading 5.6 ms → 28 ms over 2000 keystrokes and never recovering (the leak
     * outlives the window). Measuring a <em>one-character</em> range takes a different path in RichTextFX
     * and allocates nothing; at end-of-paragraph there is no character to the right, so we measure the last
     * character and take its right edge, which is the same x the caret would sit at.
     */
    private @Nullable Bounds caretBounds(int p, int column) {
        int abs = b.area.getAbsolutePosition(p, column);
        int len = b.area.getParagraphLength(p);
        Bounds screen;
        if (column < len) {
            screen = b.area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null);
        } else if (len > 0) {
            Bounds last = b.area.getCharacterBoundsOnScreen(abs - 1, abs).orElse(null);
            screen = last == null
                    ? null
                    : new javafx.geometry.BoundingBox(last.getMaxX(), last.getMinY(), 0, last.getHeight());
        } else {
            return null; // empty paragraph: nothing to measure an advance from
        }
        return screen == null ? null : b.root.screenToLocal(screen);
    }

    /** The ruler column (EditorConfig {@code max_line_length}); null = default, OFF = hide; re-measures. */
    void setRulerColumn(@Nullable Integer column) {
        if (java.util.Objects.equals(column, rulerColumnOverride)) {
            return; // unchanged: this runs for every buffer on every settings apply (applyEditorConfig)
        }
        this.rulerColumnOverride = column;
        markRulerInputsDirty(); // deferred, not a synchronous measure: character bounds must never be
        // queried inside a layout pass, and the caller may be mid-apply
    }
}
