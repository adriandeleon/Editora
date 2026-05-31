package com.editora.editor;

import java.time.Duration;

import org.fxmisc.richtext.CodeArea;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * A lightweight document overview ("minimap") drawn beside the editor. Each paragraph is rendered
 * as scaled-down blocks representing its non-whitespace runs, with a translucent rectangle marking
 * the currently visible viewport. Clicking or dragging scrolls the editor to that position.
 *
 * <p>The (relatively expensive) content rendering is cached as an image and only regenerated when
 * the text or size changes; scrolling just re-blits the cached image plus the viewport rectangle.
 */
final class Minimap extends Region {

    /** Fixed width of the minimap column, in pixels. */
    static final double WIDTH = 90;

    /** Horizontal scale: assume ~110 columns map across the full width. */
    private static final double CHAR_SCALE = WIDTH / 110.0;
    /** Max vertical pixels per document line. Caps short files so they fill from the top rather than
     * stretching one line across a huge slice; long files compress below this to fit the column. */
    private static final double MAX_ROW_HEIGHT = 3.0;

    /** Block and viewport-overlay colors; theme-aware (see {@link #setColors}). */
    private Color textColor = Color.web("#9aa5b1");
    private Color viewportColor = Color.web("#0969da", 0.14);

    private final CodeArea area;
    private final Canvas canvas = new Canvas(WIDTH, 0);
    private WritableImage contentImage;
    /** Visual width of a tab character, in columns. */
    private int tabSize = 4;

    Minimap(CodeArea area) {
        this.area = area;
        getStyleClass().add("minimap");
        getChildren().add(canvas);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);

        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(200))
                .subscribe(ignore -> renderContent());
        area.estimatedScrollYProperty().addListener((o, a, b) -> redraw());

        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::scrollToEvent);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::scrollToEvent);
    }

    /** Sets the visual tab width (columns) and re-renders if it changed. */
    void setTabSize(int tabSize) {
        if (tabSize > 0 && tabSize != this.tabSize) {
            this.tabSize = tabSize;
            renderContent();
        }
    }

    /** Sets the document-block and viewport-overlay colors (theme-aware) and re-renders. */
    void setColors(Color text, Color viewport) {
        this.textColor = text;
        this.viewportColor = viewport;
        renderContent();
    }

    /** Forces a re-render (e.g. after layout/theme settle at startup, when the first render may have
     *  run before the canvas was sized). */
    void refresh() {
        renderContent();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            renderContent();
        }
        canvas.relocate(0, 0);
    }

    private void scrollToEvent(MouseEvent e) {
        int total = area.getParagraphs().size();
        if (total == 0 || getHeight() <= 0) {
            return;
        }
        double fraction = Math.max(0, Math.min(1, e.getY() / getHeight()));
        area.showParagraphAtTop((int) Math.round(fraction * (total - 1)));
    }

    /** Renders the document content into the canvas, caches it, then draws the viewport on top. */
    private void renderContent() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        contentImage = null;
        if (!isVisible() || w <= 0 || h <= 0) {
            return;
        }
        int total = area.getParagraphs().size();
        if (total == 0) {
            return;
        }
        double rowHeight = rowHeight(h, total);
        double blockH = Math.max(0.75, Math.min(rowHeight * 0.8, 2.0));
        g.setFill(textColor);
        for (int i = 0; i < total; i++) {
            String text = area.getParagraph(i).getText();
            if (!text.isEmpty()) {
                drawRuns(g, text, i * rowHeight, blockH, w);
            }
        }
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        try {
            contentImage = canvas.snapshot(sp, null);
        } catch (RuntimeException ignored) {
            // snapshot() forces a synchronous full-scene layout pass; during early startup the
            // CodeArea's VirtualFlow may have no visible cell yet, and Flowless throws
            // "Cell 0 is not visible". Leave the cache empty — a later render (layout settle,
            // refresh(), or the next edit) re-caches once a cell is laid out.
            contentImage = null;
        }
        drawViewport(g, w, h, total, rowHeight);
    }

    /** Cheap redraw on scroll: re-blit the cached content image and draw the viewport rectangle. */
    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (!isVisible() || w <= 0 || h <= 0) {
            return;
        }
        int total = area.getParagraphs().size();
        if (total == 0) {
            return;
        }
        if (contentImage != null) {
            g.drawImage(contentImage, 0, 0);
        }
        drawViewport(g, w, h, total, rowHeight(h, total));
    }

    /** Vertical pixels per line: a fixed size, but compressed to fit when the document is long. */
    private static double rowHeight(double h, int total) {
        return Math.min(MAX_ROW_HEIGHT, h / total);
    }

    private void drawViewport(GraphicsContext g, double w, double h, int total, double rowHeight) {
        try {
            int first = clamp(area.firstVisibleParToAllParIndex(), total);
            int last = clamp(area.lastVisibleParToAllParIndex(), total);
            double vy = first * rowHeight;
            double vh = Math.max(rowHeight, (last - first + 1) * rowHeight);
            g.setFill(viewportColor);
            g.fillRect(0, vy, w, vh);
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet (e.g. before first render) — skip the indicator.
        }
    }

    /** Draws a block for each contiguous run of non-whitespace characters in {@code text}. */
    private void drawRuns(GraphicsContext g, String text, double y, double blockH, double w) {
        int n = text.length();
        int col = 0;
        int runStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean whitespace = i == n || Character.isWhitespace(text.charAt(i));
            if (!whitespace && runStart < 0) {
                runStart = col;
            } else if (whitespace && runStart >= 0) {
                double x = runStart * CHAR_SCALE;
                double width = Math.min(w - x, (col - runStart) * CHAR_SCALE);
                if (x < w && width > 0) {
                    g.fillRect(x, y, width, blockH);
                }
                runStart = -1;
            }
            if (i < n) {
                col += text.charAt(i) == '\t' ? tabSize : 1;
            }
        }
    }

    private static int clamp(int idx, int total) {
        if (idx < 0) {
            return 0;
        }
        return Math.min(idx, total - 1);
    }
}
