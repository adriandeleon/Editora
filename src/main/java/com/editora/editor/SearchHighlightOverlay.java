package com.editora.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * A transparent overlay that highlights <b>all</b> find matches behind the editor text (the active
 * match more strongly), without touching the document's style spans so it never fights the syntax
 * highlighter.
 *
 * <p>Modeled on {@link SpellCheckOverlay}: a mouse-transparent {@link Canvas} sized to the viewport,
 * redrawn coalesced (one per pulse) on scroll / edit / resize / fold, and only for the currently
 * visible paragraphs. Driven by {@link #setMatches}: it activates when there are matches and clears
 * when empty.
 */
final class SearchHighlightOverlay extends Region implements SecondaryPane.Followed {

    /** Amber wash for all matches, a deeper one + border for the current match (see OverlayPalette). */
    private OverlayPalette.Colors colors = OverlayPalette.of(Color.WHITE);

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private SearchMatches matches = SearchMatches.EMPTY;
    /** How many matches the last redraw looked at (tests): the visible ones, however many there are. */
    private int visited;

    private int activeIndex = -1;
    private boolean active;
    private boolean redrawPending;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private SearchHighlightOverlay follower;

    SearchHighlightOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("search-overlay");
        setMouseTransparent(true);
        // Inactive until a search has matches: stay hidden so the renderer composites nothing and the
        // 1x1 canvas holds no full-viewport GPU texture (no matches highlighted most of the time).
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        OverlayPalette.track(area, palette -> {
            colors = palette; // resolved on a theme change, not per paint
            scheduleRedraw();
        });
    }

    /** The same highlights for a split's second {@code view}, kept in step with this one. */
    @Override
    public SearchHighlightOverlay follower(CodeArea view) {
        SearchHighlightOverlay second = new SearchHighlightOverlay(view);
        second.setMatches(matches, activeIndex);
        follower = second;
        return second;
    }

    /** Sets the matches to highlight and which is the current one (-1 for none). */
    void setMatches(SearchMatches matches, int activeIndex) {
        if (follower != null) {
            follower.setMatches(matches, activeIndex);
        }
        this.matches = matches == null ? SearchMatches.EMPTY : matches;
        this.activeIndex = activeIndex;
        boolean show = !this.matches.isEmpty();
        if (active != show) {
            active = show;
            setVisible(show);
        }
        if (show) {
            requestLayout(); // size the canvas up to the viewport, then draw
            scheduleRedraw();
        } else {
            clear();
            canvas.setWidth(1); // release the full-viewport texture while hidden
            canvas.setHeight(1);
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active) {
            return; // stay 1x1 / no texture while inactive (the common case)
        }
        double w = CanvasGuards.clampWidth(this, getWidth());
        double h = CanvasGuards.clampHeight(this, getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        scheduleRedraw();
    }

    private void scheduleRedraw() {
        if (!active || redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    private void clear() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (!active || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            // Only the matches that touch the visible offsets are looked at, found by binary search: a
            // "find all" can hold a hundred thousand matches with a handful visible, and this runs on every
            // scroll/edit pulse — walking the list from its start made each pulse cost its whole length.
            int firstOffset = area.getAbsolutePosition(first, 0);
            int lastOffset = area.getAbsolutePosition(
                    last, area.getParagraph(last).getText().length());
            visited = 0;
            for (int i = matches.firstEndingAtOrAfter(firstOffset);
                    i < matches.size() && matches.start(i) <= lastOffset;
                    i++) {
                visited++;
                paintMatch(g, matches.start(i), matches.end(i), i == activeIndex, first, last, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /** Paints one match's box, clipped to the visible paragraph range; multi-line matches paint per line. */
    private void paintMatch(
            GraphicsContext g, int from, int to, boolean isActive, int first, int last, double w, double h) {
        var startPos = area.offsetToPosition(from, Bias.Forward);
        var endPos = area.offsetToPosition(Math.max(to, from), Bias.Backward);
        int startLine = startPos.getMajor();
        int endLine = endPos.getMajor();
        if (endLine < first || startLine > last) {
            return;
        }
        for (int line = Math.max(startLine, first); line <= Math.min(endLine, last); line++) {
            if (area.isFolded(line)) {
                continue;
            }
            int lineLen = area.getParagraph(line).getText().length();
            int c0 = line == startLine ? startPos.getMinor() : 0;
            int c1 = line == endLine ? endPos.getMinor() : lineLen;
            if (c1 <= c0) {
                continue; // nothing visible on this line
            }
            int absStart = area.getAbsolutePosition(line, c0);
            int absEnd = area.getAbsolutePosition(line, c1);
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(absStart, absEnd).orElse(null));
            if (b == null || b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
                continue;
            }
            g.setFill(isActive ? colors.searchActive() : colors.searchMatch());
            g.fillRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
            if (isActive) {
                g.setStroke(colors.searchActiveBorder());
                g.setLineWidth(1);
                g.strokeRect(b.getMinX() + 0.5, b.getMinY() + 0.5, b.getWidth() - 1, b.getHeight() - 1);
            }
        }
    }

    /** How many matches the last redraw looked at (tests). */
    int visitedInLastRedraw() {
        return visited;
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
