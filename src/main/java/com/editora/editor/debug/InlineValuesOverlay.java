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

    /** The 0-based line the frame is stopped on (its function is the only one annotated); -1 = unknown. */
    private int frameLine = -1;

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
        return values != null && rendering;
    }

    /** Sets the suspended frame's variables (null or empty clears and releases the canvas). */
    /**
     * The lines that may carry a value at all (a {@code .http} file: its script lines — the identifiers of a request
     * body are not the script's); null for every line.
     */
    private java.util.function.@Nullable IntPredicate lineFilter;

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
        boolean active = values != null;
        setVisible(active);
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas); // release the full-viewport texture while inactive
        }
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
        if (vals == null || !drawable() || !CanvasGuards.paintable(getWidth(), getHeight())) {
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
            int frameHeader =
                    frameLine < 0 ? InlineValueScope.UNKNOWN : InlineValueScope.headerOf(this::codeLine, frameLine);
            for (int p = first; p <= last; p++) {
                if (frameLine >= 0 && p > frameLine) {
                    break; // a line below the stop has not run yet: a value there reads as that line's (review C3)
                }
                if (area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                if (line.isEmpty()) {
                    continue;
                }
                int par = p;
                List<String> names = namesOn(
                        line,
                        p,
                        frameLine,
                        frameHeader,
                        vals.keySet(),
                        col -> !isCode(area.getStyleAtPosition(par, col + 1)),
                        this::codeLine);
                if (names.isEmpty() || (lineFilter != null && !lineFilter.test(p))) {
                    continue;
                }
                int lastChar = line.length() - 1;
                int abs = area.getAbsolutePosition(p, lastChar);
                Bounds b = area.getCharacterBoundsOnScreen(abs, abs + 1)
                        .map(canvas::screenToLocal)
                        .orElse(null);
                if (b == null) {
                    continue;
                }
                double x = b.getMaxX() + 16;
                if (x < 0 || x > w) {
                    continue;
                }
                g.fillText(annotation(names, vals), x, b.getMinY() + b.getHeight() / 2);
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
