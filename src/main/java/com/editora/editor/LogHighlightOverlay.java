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

    /** Where a filtered view's levels come from; see {@link #LogHighlightOverlay(CodeArea, LevelSource)}. */
    interface LevelSource {
        /** Whether the view is a filtered subset, so a line's level cannot be read off the lines above it. */
        boolean filtered();

        /** The level of visible paragraph {@code paragraph} in the complete log (null for none). */
        LogLevel levelAt(int paragraph);
    }

    /** Resolved from the editor background on a theme change, never per paint. */
    private OverlayPalette.LogTints tints = OverlayPalette.logTints(Color.WHITE);

    private static final double BAR_WIDTH = 3.0;
    /** FATAL is told from ERROR by weight as well as by its wash. */
    private static final double FATAL_BAR_WIDTH = 6.0;
    /** How far above the first visible line we scan to establish the inherited level (bounded → cheap). */
    private static final int INHERIT_SCAN = 400;

    private final CodeArea area;
    private final LevelSource levels;
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

    /**
     * @param levels the levels of a filtered view. There a stack-trace line sits under whatever line the filter
     *     left above it, so inheriting from the visible text tinted the trace of an ERROR as the WARN before it.
     */
    LogHighlightOverlay(CodeArea area, LevelSource levels) {
        this.area = area;
        this.levels = levels;
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
        tints = OverlayPalette.logTints(area);
        area.backgroundProperty().addListener((o, a, b) -> {
            tints = OverlayPalette.logTints(area);
            scheduleRedraw();
        });
    }

    /** The same level tints for a split's second {@code view}, kept in step with this one. */
    @Override
    public LogHighlightOverlay follower(CodeArea view) {
        LogHighlightOverlay second = new LogHighlightOverlay(view, levels);
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
            boolean filtered = levels.filtered();
            LogLevel carry = filtered ? null : inheritedLevelAt(first);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                LogLevel eff;
                if (filtered) {
                    eff = levels.levelAt(p);
                } else {
                    LogLevel own = levelOf(area.getParagraph(p).getText());
                    eff = own != null ? own : carry; // a line without a level belongs to the record above
                    carry = eff;
                }
                if (eff == null || area.getParagraphLength(p) == 0) {
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
        // The paragraph's bounds, not its first character's: a wrapped line is several rows tall and all of
        // them belong to the record.
        Bounds b = toLocal(area.getParagraphBoundsOnScreen(paragraph).orElse(null));
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
        g.fillRect(0, y, level == LogLevel.FATAL ? FATAL_BAR_WIDTH : BAR_WIDTH, height);
    }

    private Color barFor(LogLevel level) {
        return switch (level) {
            case FATAL -> tints.fatal();
            case ERROR -> tints.error();
            case WARN -> tints.warn();
            case INFO -> tints.info();
            case DEBUG -> tints.debug();
            case TRACE -> tints.trace();
        };
    }

    private Color washFor(LogLevel level) {
        return switch (level) {
            case FATAL -> tints.fatalWash();
            case ERROR -> tints.errorWash();
            case WARN -> tints.warnWash();
            default -> null;
        };
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
