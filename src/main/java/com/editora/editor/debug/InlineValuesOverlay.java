package com.editora.editor.debug;

import java.util.List;
import java.util.Map;

import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.TextAlignment;

import com.editora.editor.AfterLayoutPaint;
import com.editora.editor.CanvasGuards;
import com.editora.editor.OverlayPalette;
import com.editora.editor.SecondaryPane;
import com.editora.editor.TabSurface;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * IntelliJ-style inline debugger values: while execution is suspended, grey italic
 * {@code name: value} annotations are painted after the text of each visible line that mentions a
 * variable of the suspended frame — in code (not in a comment or a string literal), and within the function
 * the frame is stopped in ({@link InlineValueScope}): the same name elsewhere is another variable. A mouse-transparent {@link Canvas} mirroring
 * {@code WhitespaceOverlay}: only visible paragraphs are scanned (via the pure
 * {@link DebugIdentifiers#matchesIn}), redraws coalesce to one per pulse, and when no values are set
 * the canvas is 1×1 and invisible — zero cost outside a paused debug session.
 */
public final class InlineValuesOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private OverlayPalette.Colors colors = OverlayPalette.of(Color.WHITE);
    private static final int MAX_PER_LINE = 3;
    private static final int MAX_VALUE_CHARS = 60;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    /** Variable name → rendered value for the suspended frame; null = inactive. */
    private @Nullable Map<String, String> values;

    /** Line → its pinned values ({@code expr = value}), painted after the line's own values; null = none. */
    private @Nullable Map<Integer, String> pinned;

    /** The 0-based line the frame is stopped on (its function is the only one annotated); -1 = unknown. */
    private int frameLine = -1;

    /** How long the last step or Continue ran (Visual Studio's PerfTip), painted last on {@link #elapsedLine}. */
    private @Nullable String elapsed;

    private int elapsedLine = -1;

    private boolean redrawPending;

    /** Runs the repaint after the layout pass, and looks again after it (see {@link AfterLayoutPaint}). */
    private final AfterLayoutPaint afterLayout;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private @Nullable InlineValuesOverlay follower;

    private Font font = Font.font("monospace", FontPosture.ITALIC, 14);

    public InlineValuesOverlay(CodeArea area) {
        this.area = area;
        this.afterLayout = new AfterLayoutPaint(
                this,
                area,
                () -> {
                    redrawPending = false;
                    redraw();
                },
                this::drawable);
        getStyleClass().add("inline-values-overlay");
        setMouseTransparent(true);
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

    /** The same inline values for a split's second {@code view}, kept in step with this one. */
    @Override
    public InlineValuesOverlay follower(CodeArea view) {
        InlineValuesOverlay second = new InlineValuesOverlay(view);
        second.font = font;
        second.setRenderingActive(rendering);
        second.setValues(values, frameLine);
        second.setPinned(pinned);
        second.setElapsed(elapsedLine, elapsed);
        follower = second;
        return second;
    }

    /**
     * Releases the canvas while this overlay's tab is in the background and repaints when it is shown again
     * (driven by {@code EditorBuffer.setRenderingActive}): a session suspended in a hidden tab would otherwise
     * keep a viewport-sized texture alive for as long as the tab stays open.
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
        return active() && rendering;
    }

    private boolean active() {
        return values != null || pinned != null || elapsed != null;
    }

    /** Paints {@code text} (how long the program ran to this stop) last on 0-based {@code line}; null clears it. */
    public void setElapsed(int line, @Nullable String text) {
        if (follower != null) {
            follower.setElapsed(line, text);
        }
        this.elapsed = line < 0 || text == null || text.isBlank() ? null : text;
        this.elapsedLine = this.elapsed == null ? -1 : line;
        activate();
    }

    /** Sets the pinned values to paint, by line (null or empty clears them). */
    public void setPinned(@Nullable Map<Integer, String> byLine) {
        if (follower != null) {
            follower.setPinned(byLine);
        }
        this.pinned = byLine == null || byLine.isEmpty() ? null : Map.copyOf(byLine);
        activate();
    }

    private void activate() {
        boolean active = active();
        setVisible(active);
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas); // release the full-viewport texture while inactive
        }
    }

    /**
     * The lines that may carry a value at all (a {@code .http} file: its script lines — the identifiers of a request
     * body are not the script's); null for every line.
     */
    private java.util.function.@Nullable IntPredicate lineFilter;

    /** Sets the suspended frame's variables (null or empty clears and releases the canvas). */
    public void setValues(
            @Nullable Map<String, String> v, int frameLine, java.util.function.@Nullable IntPredicate lines) {
        this.lineFilter = lines;
        setValues(v, frameLine);
    }

    void setValues(@Nullable Map<String, String> v, int frameLine) {
        if (follower != null) {
            follower.setValues(v, frameLine, lineFilter);
        }
        this.values = (v == null || v.isEmpty()) ? null : Map.copyOf(v);
        this.frameLine = frameLine;
        activate();
    }

    /** Keeps the annotation font in step with the editor font (italic variant). */
    public void setFont(String family, int size) {
        if (follower != null) {
            follower.setFont(family, size);
        }
        this.font = Font.font(family, FontPosture.ITALIC, size);
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!drawable()) {
            return; // stay 1x1 / no texture while inactive or backgrounded
        }
        CanvasGuards.fit(this, canvas);
        scheduleRedraw();
    }

    private void scheduleRedraw() {
        if (!drawable() || redrawPending) {
            return;
        }
        redrawPending = true;
        afterLayout.request();
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        Map<String, String> vals = values;
        Map<String, String> known = vals == null ? Map.of() : vals;
        Map<Integer, String> pins = pinned;
        String took = elapsed;
        int tookLine = elapsedLine;
        if (!drawable() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setFill(colors.inlineValue());
            g.setFont(font);
            g.setTextBaseline(VPos.CENTER);
            g.setTextAlign(TextAlignment.LEFT);
            // The frame's function header: a lambda opened on another line declares its own parameters there.
            int frameHeader = vals == null || frameLine < 0
                    ? InlineValueScope.UNKNOWN
                    : InlineValueScope.headerOf(this::codeLine, frameLine);
            for (int p = first; p <= last; p++) {
                String pin = pins == null ? null : pins.get(p);
                String tookHere = p == tookLine ? took : null;
                boolean valuesHere = vals != null && !(frameLine >= 0 && p > frameLine); // below the stop: not yet run
                if ((!valuesHere && pin == null && tookHere == null) || area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                int par = p;
                List<String> names = !valuesHere || line.isEmpty()
                        ? List.of()
                        : namesOn(
                                line,
                                p,
                                frameLine,
                                frameHeader,
                                known.keySet(),
                                col -> !isCode(area.getStyleAtPosition(par, col + 1)),
                                this::codeLine);
                if (lineFilter != null && !lineFilter.test(p)) {
                    names = List.of();
                }
                if (names.isEmpty() && pin == null && tookHere == null) {
                    continue;
                }
                double y;
                double x;
                if (line.isEmpty()) {
                    Bounds para = area.getParagraphBoundsOnScreen(p)
                            .map(canvas::screenToLocal)
                            .orElse(null);
                    if (para == null) {
                        continue;
                    }
                    x = para.getMinX() + 16;
                    y = para.getMinY() + para.getHeight() / 2;
                } else {
                    int abs = area.getAbsolutePosition(p, line.length() - 1);
                    Bounds b = area.getCharacterBoundsOnScreen(abs, abs + 1)
                            .map(canvas::screenToLocal)
                            .orElse(null);
                    if (b == null) {
                        continue;
                    }
                    x = b.getMaxX() + 16;
                    y = b.getMinY() + b.getHeight() / 2;
                }
                if (x < 0 || x > w) {
                    continue;
                }
                if (!names.isEmpty()) {
                    String text = annotation(names, known);
                    g.setFill(colors.inlineValue());
                    g.fillText(text, x, y);
                    x += textWidth(text) + 16;
                }
                if (pin != null) {
                    g.setFill(colors.info()); // a pinned value: asked for, not inferred
                    g.fillText(PIN_MARK + pin, x, y);
                    x += textWidth(PIN_MARK + pin) + 16;
                }
                if (tookHere != null) {
                    g.setFill(colors.inlineValue());
                    g.setGlobalAlpha(0.7); // fainter than the values: about the run, not the code
                    g.fillText(tookHere, x, y);
                    g.setGlobalAlpha(1);
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event redraws.
        }
    }

    /**
     * Pure: the frame's variables to annotate on line {@code p} ({@code text}) — none below the stop line (it has
     * not run yet) or outside the frame's function, and not a lambda's own parameter on the line that declares it
     * (review C3: {@code names.forEach(n -> …)} showed the loop variable {@code n}'s value).
     */
    static List<String> namesOn(
            String text,
            int p,
            int frameLine,
            int frameHeader,
            java.util.Set<String> names,
            java.util.function.IntPredicate notCode,
            java.util.function.IntFunction<String> lineAt) {
        if (frameLine >= 0 && p > frameLine) {
            return List.of();
        }
        List<String> found = DebugIdentifiers.matchesIn(text, names, notCode);
        if (p != frameHeader && !found.isEmpty()) {
            found.removeAll(DebugIdentifiers.lambdaParameters(text));
        }
        return found.isEmpty() || !InlineValueScope.sameScope(lineAt, frameLine, p) ? List.of() : found;
    }

    private static boolean isCode(java.util.Collection<String> style) {
        return style == null || !(style.contains("comment") || style.contains("string"));
    }

    /** A line's text for the scope walk; {@code ""} for one that starts inside a comment or a string. */
    private String codeLine(int p) {
        if (p < 0 || p >= area.getParagraphs().size()) {
            return "";
        }
        String text = area.getParagraph(p).getText();
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) {
            first++;
        }
        return first == text.length() || !isCode(area.getStyleAtPosition(p, first + 1)) ? "" : text;
    }

    /** Leads a pinned value's text, telling it from the values the editor infers. */
    static final String PIN_MARK = "◆ ";

    private final javafx.scene.text.Text measure = new javafx.scene.text.Text();

    private double textWidth(String text) {
        measure.setFont(font);
        measure.setText(text);
        return measure.getLayoutBounds().getWidth();
    }

    /** {@code name: value  name2: value2} for up to {@link #MAX_PER_LINE} names, values capped. */
    private static String annotation(List<String> names, Map<String, String> vals) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown == MAX_PER_LINE) {
                sb.append("  …");
                break;
            }
            String v = vals.get(name);
            if (v == null) {
                continue;
            }
            v = v.replace('\n', ' ');
            if (v.length() > MAX_VALUE_CHARS) {
                v = v.substring(0, MAX_VALUE_CHARS) + "…";
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(name).append(": ").append(v);
            shown++;
        }
        return sb.toString();
    }
}
