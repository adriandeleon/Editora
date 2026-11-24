package com.editora.editor.logviewer;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.editor.AfterLayoutPaint;
import com.editora.editor.CanvasGuards;
import com.editora.editor.OverlayPalette;
import com.editora.editor.SecondaryPane;
import com.editora.editor.TabSurface;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogMatcher;
import com.editora.logviewer.LogPatterns;
import com.editora.logviewer.LogSources;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * A transparent overlay that tints each visible log line by its severity: a solid colored bar at the
 * left edge for every leveled line, plus a faint full-row wash for WARN/ERROR/FATAL. Unlike TextMate
 * highlighting (disabled in large-file mode) this is <em>size-independent</em>, so a multi-GB log opened at
 * its tail still shows levels.
 *
 * <p>Modeled on {@code SpellCheckOverlay}/{@code SearchHighlightOverlay}: a mouse-transparent
 * {@link Canvas} redrawn coalesced (one per pulse) on scroll/edit/resize, only over the visible
 * paragraphs. A continuation line (a stack-trace frame, a wrapped message) inherits the preceding
 * record's level, so an exception's whole trace tints red. Per-line level detection is cached by line
 * text (pure) so a scroll pulse re-tints without re-scanning.
 */
public final class LogHighlightOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    /** Where a filtered view's levels come from; see {@link #LogHighlightOverlay(CodeArea, LevelSource)}. */
    public interface LevelSource {
        /** Whether the view is a filtered subset, so a line's level cannot be read off the lines above it. */
        boolean filtered();

        /** The level of visible paragraph {@code paragraph} in the complete log (null for none). */
        @Nullable
        LogLevel levelAt(int paragraph);

        /** The pattern whose matches are marked in the visible lines — the filter's — or null for none. */
        default @Nullable Pattern highlight() {
            return null;
        }

        /** The 0-based line, in the complete text, that visible paragraph {@code paragraph} shows. */
        default int lineAt(int paragraph) {
            return paragraph;
        }
    }

    /** A merged log's sources (see {@link #setSources}), or null for a log of one file. */
    private @Nullable LogSources sources;

    private Color[] sourceColors = new Color[0];
    /** Each source name's width in the chip font, measured once per merge. */
    private double[] sourceWidths = new double[0];

    private static final double SOURCE_STRIP_WIDTH = 3.0;
    private static final double CHIP_PAD = 4.0;

    /**
     * Tags each line of a merged log with the log it came from: a strip in that log's colour at the right edge
     * (the left one is the level's, and the text starts just after it), and its name beside the strip. Null
     * takes the tags away.
     */
    public void setSources(@Nullable LogSources sources) {
        if (follower != null) {
            follower.setSources(sources);
        }
        this.sources = sources;
        List<String> names = sources == null ? List.of() : sources.names();
        int n = names.size();
        sourceColors = new Color[n];
        sourceWidths = new double[n];
        javafx.scene.text.Text probe = new javafx.scene.text.Text();
        probe.setFont(chipFont());
        for (int i = 0; i < n; i++) {
            sourceColors[i] = sourceColor(i);
            probe.setText(names.get(i));
            sourceWidths[i] = probe.getLayoutBounds().getWidth();
        }
        scheduleRedraw();
    }

    /** Source {@code i}'s colour: hues spread by the golden angle, so neighbours differ however many logs. */
    static Color sourceColor(int i) {
        return Color.hsb((205 + i * 137.508) % 360, 0.62, 0.78);
    }

    private static javafx.scene.text.Font chipFont() {
        return javafx.scene.text.Font.font(com.editora.editor.UiZoom.px(10));
    }

    /** Resolved from the editor background on a theme change, never per paint. */
    private OverlayPalette.LogTints tints = OverlayPalette.logTints(Color.WHITE);

    private static final double BAR_WIDTH = 3.0;
    /** FATAL is told from ERROR by weight as well as by its wash. */
    private static final double FATAL_BAR_WIDTH = 6.0;
    /** Most matches marked on one line: a pattern like {@code .} would otherwise box every character. */
    private static final int MAX_MARKS_PER_LINE = 64;

    /** How far above the first visible line we scan to establish the inherited level (bounded → cheap). */
    private static final int INHERIT_SCAN = 400;

    private final CodeArea area;
    private final LevelSource levels;
    private Canvas canvas = new Canvas(1, 1);
    private boolean active;
    private boolean redrawPending;
    /** Runs the repaint after the layout pass, and looks again after it (see {@link AfterLayoutPaint}). */
    private final AfterLayoutPaint afterLayout;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private @Nullable LogHighlightOverlay follower;

    private final Map<String, LogLevel> levelCache = lru(8000);

    /** Matches the filter's pattern in the visible lines; rebuilt when the pattern changes, null for none. */
    private @Nullable LogMatcher marker;

    /** Document edits seen, and the inherited level last computed for (first visible line, edits). */
    private int edits;

    private int inheritedFirst = -1;
    private int inheritedEdits = -1;
    private @Nullable LogLevel inherited;

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
    public LogHighlightOverlay(CodeArea area, LevelSource levels) {
        this.area = area;
        this.afterLayout = new AfterLayoutPaint(
                this,
                area,
                () -> {
                    redrawPending = false;
                    redraw();
                },
                () -> active && rendering);
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
        OverlayPalette.trackLogTints(
                area,
                getChildren(),
                next -> { // the theme's, re-read on a theme change
                    tints = next;
                    scheduleRedraw();
                });
    }

    /** The same level tints for a split's second {@code view}, kept in step with this one. */
    @Override
    public LogHighlightOverlay follower(CodeArea view) {
        LogHighlightOverlay second = new LogHighlightOverlay(view, levels);
        second.setRenderingActive(rendering);
        second.setActive(active);
        second.setSources(sources);
        follower = second;
        return second;
    }

    public void setActive(boolean active) {
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
            canvas = CanvasGuards.released(canvas, getChildren()); // drop the full-viewport texture while hidden
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
            canvas = CanvasGuards.released(canvas, getChildren());
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
        afterLayout.request();
    }

    /** Pattern scans run since construction — the test seam for "a repaint of unchanged lines scans none". */
    private int levelScans;

    int levelScansForTest() {
        return levelScans;
    }

    private @Nullable LogLevel levelOf(String line) {
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
            CanvasGuards.shrink(canvas);
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
            LogMatcher marks = filtered ? markerFor(levels.highlight()) : null;
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
                if (area.getParagraphLength(p) == 0) {
                    continue;
                }
                if (eff != null) {
                    paintLine(g, p, eff, w);
                }
                if (marks != null) {
                    paintMatches(g, p, marks, w);
                }
                LogSources tagged = sources;
                if (tagged != null) {
                    paintSource(g, p, tagged, w);
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event redraws.
        }
    }

    /** The matcher for {@code pattern}, made again only when the filter's pattern changes. */
    private @Nullable LogMatcher markerFor(@Nullable Pattern pattern) {
        if (pattern == null) {
            marker = null;
        } else if (marker == null || marker.pattern() != pattern) {
            marker = new LogMatcher(pattern);
        }
        return marker;
    }

    /** Boxes the filter's matches in paragraph {@code paragraph}, as Find marks its matches. */
    private void paintMatches(GraphicsContext g, int paragraph, LogMatcher marks, double w) {
        String text = area.getParagraph(paragraph).getText();
        int base = area.getAbsolutePosition(paragraph, 0);
        g.setFill(tints.match());
        marks.ranges(text, MAX_MARKS_PER_LINE, (start, end) -> {
            Bounds b = toLocal(
                    area.getCharacterBoundsOnScreen(base + start, base + end).orElse(null));
            if (b != null && b.getMaxX() >= 0 && b.getMinX() <= w) {
                g.fillRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
            }
        });
    }

    /** The inherited level entering {@code first}: the nearest leveled line in the bounded window above it. */
    private @Nullable LogLevel inheritedLevelAt(int first) {
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

    /** Line {@code paragraph}'s source tag: a strip at the right edge, and the log's name beside it. */
    private void paintSource(GraphicsContext g, int paragraph, LogSources sources, double w) {
        int source = sources.sourceOf(levels.lineAt(paragraph));
        if (source < 0 || source >= sourceColors.length) {
            return;
        }
        Bounds b = toLocal(area.getParagraphBoundsOnScreen(paragraph).orElse(null));
        if (b == null || b.getMaxY() < 0 || b.getMinY() > canvas.getHeight()) {
            return;
        }
        Color color = sourceColors[source];
        g.setFill(color);
        g.fillRect(w - SOURCE_STRIP_WIDTH, b.getMinY(), SOURCE_STRIP_WIDTH, b.getHeight());
        double rowHeight = Math.min(b.getHeight(), com.editora.editor.UiZoom.px(16));
        double chipW = sourceWidths[source] + 2 * CHIP_PAD;
        double x = w - SOURCE_STRIP_WIDTH - chipW - CHIP_PAD;
        if (x < w / 2) {
            return; // a narrow view: the strip alone
        }
        g.setFill(color.deriveColor(0, 1, 1, 0.16));
        g.fillRoundRect(x, b.getMinY() + 1, chipW, rowHeight - 2, 6, 6);
        g.setFill(color);
        g.setFont(chipFont());
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        g.fillText(sources.names().get(source), x + CHIP_PAD, b.getMinY() + rowHeight / 2);
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

    private @Nullable Color washFor(LogLevel level) {
        return switch (level) {
            case FATAL -> tints.fatalWash();
            case ERROR -> tints.errorWash();
            case WARN -> tints.warnWash();
            default -> null;
        };
    }

    private @Nullable Bounds toLocal(@Nullable Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
