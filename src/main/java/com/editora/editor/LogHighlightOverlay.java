package com.editora.editor;

import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.logviewer.LogFilter;
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
final class LogHighlightOverlay extends Region {

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

    private final Map<String, LogLevel> levelCache = lru(8000);

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
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        setVisible(active);
        if (active) {
            scheduleRedraw();
        } else {
            clear();
        }
    }

    boolean isActive() {
        return active;
    }

    @Override
    protected void layoutChildren() {
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
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

    private LogLevel levelOf(String line) {
        return levelCache.computeIfAbsent(line, LogPatterns::levelOf);
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
            LogLevel carry = inheritedLevelAt(first);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                LogLevel eff = LogFilter.effectiveLevel(line, carry);
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
        int from = Math.max(0, first - INHERIT_SCAN);
        LogLevel carry = null;
        for (int p = from; p < first; p++) {
            LogLevel own = levelOf(area.getParagraph(p).getText());
            if (own != null) {
                carry = own;
            }
        }
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
