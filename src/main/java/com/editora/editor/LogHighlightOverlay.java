package com.editora.editor;

import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogPatterns;
import org.fxmisc.richtext.CodeArea;

/**
 * A transparent overlay that tints each visible log line by its severity: a solid colored bar at the
 * left edge for every leveled line, plus a faint full-row wash for WARN/ERROR/FATAL. Unlike TextMate
 * highlighting (disabled on files ≥ 5 MB) this is <em>size-independent</em>, so a multi-GB log opened at
 * its tail still shows levels.
 *
 * <p>Modeled on {@link SpellCheckOverlay}/{@link SearchHighlightOverlay}: a mouse-transparent
 * {@link Canvas} redrawn coalesced (one per pulse) on scroll/edit/resize, only over the visible
 * paragraphs. A continuation line (a stack-trace frame, a wrapped message) inherits the preceding
 * record's level, so an exception's whole trace tints red. Per-line level detection is cached by line
 * text (pure) so a scroll pulse re-tints without re-scanning.
 */
final class LogHighlightOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    /** Translucent so they read on any editor theme without per-theme overrides (the search-wash convention). */
    private static final Color ERROR_BAR = Color.web("#e5484d", 0.95);

    private static final Color WARN_BAR = Color.web("#e0a800", 0.95);
    private static final Color INFO_BAR = Color.web("#2da44e", 0.85);
    private static final Color DEBUG_BAR = Color.web("#8b949e", 0.7);
    private static final Color TRACE_BAR = Color.web("#8b949e", 0.4);
    private static final Color ERROR_WASH = Color.web("#e5484d", 0.07);
    private static final Color WARN_WASH = Color.web("#e0a800", 0.06);

    private static final double BAR_WIDTH = 3.0;
    /** How far above the first visible line we scan to establish the inherited level (bounded → cheap). */
    private static final int INHERIT_SCAN = 400;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private boolean active;
    private boolean redrawPending;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private LogHighlightOverlay follower;

    private final Map<String, LogLevel> levelCache = lru(8000);

    /** Document edits seen, and the inherited level last computed for (first visible line, edits). */
    private int edits;

    private int inheritedFirst = -1;
    private int inheritedEdits = -1;
    private LogLevel inherited;

    private static <K, V> Map<K, V> lru(int max) {
        return new java.util.LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    LogHighlightOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("log-highlight-overlay");
        setMouseTransparent(true);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> {
            edits++;
            scheduleRedraw();
        });
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** The same level tints for a split's second {@code view}, kept in step with this one. */
    @Override
    public LogHighlightOverlay follower(CodeArea view) {
        LogHighlightOverlay second = new LogHighlightOverlay(view);
        second.setRenderingActive(rendering);
        second.setActive(active);
        follower = second;
        return second;
    }

    void setActive(boolean active) {
        if (follower != null) {
            follower.setActive(active);
        }
        if (this.active == active) {
            return;
        }
        this.active = active;
        setVisible(active);
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas); // drop the full-viewport texture while hidden
        }
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

    boolean isActive() {
        return active;
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active || !rendering) {
            return; // stay 1x1 / no texture while off or backgrounded
        }
        CanvasGuards.fit(this, canvas);
        scheduleRedraw();
    }

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

    /** Pattern scans run since construction — the test seam for "a repaint of unchanged lines scans none". */
    private int levelScans;

    int levelScansForTest() {
        return levelScans;
    }

    private LogLevel levelOf(String line) {
        // Not computeIfAbsent: it records no mapping for a null result, and "no level" is the answer for
        // every stack-trace and continuation line — exactly the lines that would then be re-scanned on
        // each repaint.
        LogLevel cached = levelCache.get(line);
        if (cached != null || levelCache.containsKey(line)) {
            return cached;
        }
        levelScans++;
        LogLevel level = LogPatterns.levelOf(line);
        levelCache.put(line, level);
        return level;
    }

    private void redraw() {
        if (!active || !rendering || !CanvasGuards.paintable(getWidth(), getHeight())) {
            CanvasGuards.release(canvas);
            return;
        }
        CanvasGuards.fit(this, canvas); // grown here as well: activation can arrive without a layout pass
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
            LogLevel carry = inheritedLevelAt(first);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                LogLevel own = levelOf(line);
                LogLevel eff = own != null ? own : carry; // LogFilter.effectiveLevel, through the cache
                carry = eff;
                if (eff == null || line.isEmpty()) {
                    continue;
                }
                paintLine(g, p, eff, w);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event redraws.
        }
    }

    /** The inherited level entering {@code first}: the nearest leveled line in the bounded window above it. */
    private LogLevel inheritedLevelAt(int first) {
        if (first == inheritedFirst && edits == inheritedEdits) {
            return inherited; // a horizontal scroll, a resize or a caret blink repaints the same lines
        }
        int from = Math.max(0, first - INHERIT_SCAN);
        LogLevel carry = null;
        for (int p = first - 1; p >= from && carry == null; p--) {
            carry = levelOf(area.getParagraph(p).getText());
        }
        inheritedFirst = first;
        inheritedEdits = edits;
        inherited = carry;
        return carry;
    }

    private void paintLine(GraphicsContext g, int paragraph, LogLevel level, double w) {
        int base = area.getAbsolutePosition(paragraph, 0);
        Bounds b = toLocal(area.getCharacterBoundsOnScreen(base, base + 1).orElse(null));
        if (b == null) {
            return;
        }
        double y = b.getMinY();
        double height = b.getHeight();
        if (y + height < 0 || y > canvas.getHeight()) {
            return; // off-screen
        }
        Color wash = washFor(level);
        if (wash != null) {
            g.setFill(wash);
            g.fillRect(0, y, w, height);
        }
        g.setFill(barFor(level));
        g.fillRect(0, y, BAR_WIDTH, height);
    }

    private static Color barFor(LogLevel level) {
        return switch (level) {
            case FATAL, ERROR -> ERROR_BAR;
            case WARN -> WARN_BAR;
            case INFO -> INFO_BAR;
            case DEBUG -> DEBUG_BAR;
            case TRACE -> TRACE_BAR;
        };
    }

    private static Color washFor(LogLevel level) {
        return switch (level) {
            case FATAL, ERROR -> ERROR_WASH;
            case WARN -> WARN_WASH;
            default -> null;
        };
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
