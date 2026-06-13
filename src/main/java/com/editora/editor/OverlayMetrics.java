package com.editora.editor;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;

import java.util.HashMap;
import java.util.Map;

import org.fxmisc.richtext.CodeArea;

/**
 * Per-redraw geometry cache shared by the editor's Canvas overlays (search highlight, spell, LSP
 * diagnostics, notes, ...). Each of those overlays redraws once per scroll/edit pulse and, for every
 * visible item, asked RichTextFX for {@link CodeArea#getCharacterBoundsOnScreen} — a ~28&micro;s
 * synchronous text-layout query (measured). A dense pulse (e.g. find-all of a common word, many
 * diagnostics) therefore issued one costly query per visible match.
 *
 * <p>For the editor's <b>monospace, non-wrapped</b> surface the horizontal position of a column is just
 * {@code contentLeftX + column * charWidth}, where both constants are derived once per pulse from a single
 * real query; each visible line's vertical extent is queried at most once and cached. So an overlay's
 * cost drops from O(visible items) to O(visible lines with items). This generalizes the arithmetic-advance
 * trick {@link WhitespaceOverlay} already uses in production.</p>
 *
 * <p><b>Correctness:</b> a line containing a tab before the target column falls back to the exact query
 * (tab stops are not a fixed advance), as does any empty/unavailable result — so output is identical to
 * the old per-item query, only cheaper. One instance per overlay redraw. FX thread only; not thread-safe.</p>
 */
final class OverlayMetrics {

    private final CodeArea area;
    private final Node local; // the overlay canvas, for screenToLocal

    private boolean horizInit;
    private boolean horizOk;
    private double contentLeftX;
    private double charWidth;

    /** par -> {minY, height} in local coords; absent = not yet queried, NaN-marked = queried but unavailable. */
    private final Map<Integer, double[]> lineVert = new HashMap<>();

    OverlayMetrics(CodeArea area, Node local) {
        this.area = area;
        this.local = local;
    }

    /**
     * Local-coordinate bounds of columns {@code [c0, c1)} on paragraph {@code par}, equivalent to
     * {@code screenToLocal(getCharacterBoundsOnScreen(abs(c0), abs(c1)))} but using the arithmetic fast
     * path when safe. Returns {@code null} when bounds are unavailable (caller skips, exactly as before).
     */
    Bounds rangeLocal(int par, int c0, int c1) {
        if (c1 <= c0) {
            return null;
        }
        String line = area.getParagraph(par).getText();
        if (!hasTabBefore(line, c1) && ensureHoriz()) {
            double[] v = vert(par);
            if (v != null) {
                return rangeBounds(contentLeftX, charWidth, v[0], v[1], c0, c1);
            }
        }
        return exact(par, c0, c1);
    }

    /* ---- pure helpers (unit-tested) ---- */

    /** Whether {@code line} contains a tab anywhere in {@code [0, endCol)} (where arithmetic X breaks). */
    static boolean hasTabBefore(String line, int endCol) {
        int n = Math.min(endCol, line.length());
        for (int i = 0; i < n; i++) {
            if (line.charAt(i) == '\t') {
                return true;
            }
        }
        return false;
    }

    /** The monospace arithmetic box for columns {@code [c0, c1)} given a line's derived metrics. */
    static Bounds rangeBounds(double contentLeftX, double charWidth, double minY, double height, int c0, int c1) {
        return new BoundingBox(contentLeftX + c0 * charWidth, minY, (c1 - c0) * charWidth, height);
    }

    /* ---- query + cache ---- */

    /** Derive {@link #contentLeftX} + {@link #charWidth} once, from char 0 of a non-tab, non-empty visible line. */
    private boolean ensureHoriz() {
        if (horizInit) {
            return horizOk;
        }
        horizInit = true;
        int total = area.getParagraphs().size();
        int first = Math.max(0, area.firstVisibleParToAllParIndex());
        int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p)) {
                continue;
            }
            String line = area.getParagraph(p).getText();
            if (line.isEmpty() || line.charAt(0) == '\t') {
                continue;
            }
            int abs = area.getAbsolutePosition(p, 0);
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
            if (b != null && b.getWidth() > 0) {
                contentLeftX = b.getMinX();
                charWidth = b.getWidth();
                lineVert.put(p, new double[]{b.getMinY(), b.getHeight()}); // anchor line's vertical is free
                horizOk = true;
                return true;
            }
        }
        return false;
    }

    /** A visible line's {minY, height} in local coords (one char-0 query per line, cached). */
    private double[] vert(int par) {
        double[] cached = lineVert.get(par);
        if (cached != null) {
            return Double.isNaN(cached[0]) ? null : cached;
        }
        int abs = area.getAbsolutePosition(par, 0);
        Bounds b = toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
        if (b == null) {
            lineVert.put(par, new double[]{Double.NaN, 0});
            return null;
        }
        double[] v = {b.getMinY(), b.getHeight()};
        lineVert.put(par, v);
        return v;
    }

    private Bounds exact(int par, int c0, int c1) {
        int abs0 = area.getAbsolutePosition(par, c0);
        int abs1 = area.getAbsolutePosition(par, c1);
        return toLocal(area.getCharacterBoundsOnScreen(abs0, abs1).orElse(null));
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : local.screenToLocal(screen);
    }
}
