package com.editora.editor.logviewer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import com.editora.editor.CanvasGuards;
import com.editora.editor.ControlNames;
import com.editora.editor.OverlayPalette;
import com.editora.editor.SecondaryPane;
import com.editora.editor.UiZoom;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogMarks;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * A log's overview stripe over the vertical scrollbar: an amber tick for each WARN line and a red one for each
 * ERROR or FATAL line, the whole log mapped to the stripe's height, so a glance shows where the trouble is.
 * Hovering a tick shows its line, clicking it goes there. It marks the lines in view: a filtered log shows the
 * marks of what the filter kept.
 *
 * <p>The levels are found by {@link LogMarks#scan} a moment after the text stops changing (a followed log
 * grows in bursts), on a background thread for a large log; a scan whose text has changed since is dropped.
 * Painting reduces the marks to one per pixel row, the most severe, so a log of 200 000 lines paints in about
 * the same time as one of 200.
 */
public final class LogStripe extends Region implements SecondaryPane.Followed {

    public static final double WIDTH = 6;
    private static final double HIT_PAD = 3;
    /** Wait after the last change before the levels are looked for again. */
    private static final Duration SETTLE = Duration.millis(250);
    /** Text up to this long is scanned on the FX thread: a few milliseconds, and no flicker. */
    static final int INLINE_SCAN_CHARS = 256 * 1024;
    /** Characters of a line a tooltip shows. */
    private static final int TOOLTIP_CHARS = 160;

    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "log-stripe-scan");
        t.setDaemon(true);
        return t;
    });

    private Color warning = OverlayPalette.of(Color.WHITE).warning();
    private Color error = OverlayPalette.of(Color.WHITE).error();

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private final PauseTransition settle = new PauseTransition(SETTLE);
    private LogMarks marks = LogMarks.NONE;
    private boolean active;
    private boolean redrawPending;
    /** Bumped for every scan asked for, so a slower, older scan cannot overwrite a newer one. */
    private int generation;

    private @Nullable LogStripe follower;
    private @Nullable Tooltip tooltip;
    private @Nullable String shownText;
    private IntConsumer onActivate = line -> {};
    private ObjIntConsumer<CodeArea> onActivateIn = SecondaryPane::jumpToLine;
    /** A split's copy takes its marks from the original instead of scanning the same text again. */
    private final boolean scans;

    public LogStripe(CodeArea area) {
        this(area, true);
    }

    private LogStripe(CodeArea area, boolean scans) {
        this.area = area;
        this.scans = scans;
        getStyleClass().add("log-stripe");
        setMouseTransparent(true);
        setVisible(false);
        UiZoom.minWidth(this, WIDTH);
        UiZoom.prefWidth(this, WIDTH);
        UiZoom.maxWidth(this, WIDTH);
        getChildren().add(canvas);
        settle.setOnFinished(e -> scan());
        if (scans) {
            area.multiPlainChanges().subscribe(ignore -> {
                if (active) {
                    settle.playFromStart();
                }
            });
        }
        area.totalHeightEstimateProperty().addListener((obs, was, now) -> scheduleRedraw());
        OverlayPalette.track(area, getChildren(), palette -> {
            warning = palette.warning();
            error = palette.error();
            scheduleRedraw();
        });
        addEventHandler(MouseEvent.MOUSE_MOVED, this::onHover);
        addEventHandler(MouseEvent.MOUSE_EXITED, e -> hideTooltip());
        addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClick);
    }

    @Override
    public LogStripe follower(CodeArea view) {
        LogStripe second = new LogStripe(view, false);
        second.onActivate = line -> onActivateIn.accept(view, line);
        second.setMarks(marks);
        second.setActive(active);
        follower = second;
        return second;
    }

    /** Shows or hides the stripe: on for a log while the log viewer is. */
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
            scan();
            requestLayout();
        } else {
            settle.stop();
            generation++; // a scan under way is no longer wanted
            hideTooltip();
            setMarks(LogMarks.NONE);
            releaseTexture();
        }
    }

    public boolean isActive() {
        return active;
    }

    /** The marks shown now. */
    public LogMarks marks() {
        return marks;
    }

    /** The click-to-jump action for this stripe and its split copies, with the view clicked beside. */
    public void setOnActivateIn(ObjIntConsumer<CodeArea> onActivateIn) {
        this.onActivateIn = onActivateIn;
        this.onActivate = line -> onActivateIn.accept(area, line);
    }

    /** Looks for the levels in the text now (the settle delay has passed, or the stripe was just shown). */
    void scan() {
        settle.stop();
        if (!active || !scans) {
            return;
        }
        int asked = ++generation;
        String text = area.getText();
        if (text.length() <= INLINE_SCAN_CHARS) {
            LogMarks found = LogMarks.scan(text, () -> false);
            if (found != null) {
                setMarks(found);
            }
            return;
        }
        SCANNER.execute(() -> {
            LogMarks found = LogMarks.scan(text, () -> generation != asked);
            if (found != null) {
                Platform.runLater(() -> {
                    if (active && generation == asked) {
                        setMarks(found);
                    }
                });
            }
        });
    }

    private void setMarks(LogMarks marks) {
        if (follower != null) {
            follower.setMarks(marks);
        }
        if (marks.equals(this.marks)) {
            return; // an append without a new warning: nothing to paint
        }
        this.marks = marks;
        setMouseTransparent(!active || marks.size() == 0);
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active || marks.size() == 0) {
            releaseTexture();
            return;
        }
        double w = CanvasGuards.clampWidth(this, getWidth());
        double h = CanvasGuards.clampHeight(this, getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        scheduleRedraw();
    }

    private void releaseTexture() {
        if (canvas.getWidth() != 1 || canvas.getHeight() != 1) {
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
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

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        int total = area.getParagraphs().size();
        if (!active || marks.size() == 0 || total == 0 || !CanvasGuards.paintable(getWidth(), getHeight())) {
            if (marks.size() == 0) {
                requestLayout(); // gives the texture back
            }
            return;
        }
        int rows = Math.max(1, (int) Math.ceil(h));
        byte[] worst = new byte[rows]; // 0 = nothing, else rank + 1
        double markH = markHeight(h, total);
        int tall = Math.max(1, (int) Math.ceil(markH));
        for (int i = 0; i < marks.size(); i++) {
            int top = (int) Math.min(yForLine(marks.lines()[i], h, total), Math.max(0, h - markH));
            byte rank = (byte) (marks.ranks()[i] + 1);
            for (int r = top; r < Math.min(rows, top + tall); r++) {
                if (worst[r] < rank) {
                    worst[r] = rank;
                }
            }
        }
        int r = 0;
        while (r < rows) {
            if (worst[r] == 0) {
                r++;
                continue;
            }
            int start = r;
            byte rank = worst[r];
            while (r < rows && worst[r] == rank) {
                r++;
            }
            g.setFill(rank - 1 >= LogLevel.ERROR.rank() ? error : warning);
            g.fillRect(0, start, w, r - start);
        }
    }

    private double contentHeight() {
        Double estimate = area.totalHeightEstimateProperty().getValue();
        return estimate == null ? 0 : estimate;
    }

    private double yForLine(int line, double h, int total) {
        int clamped = Math.max(0, Math.min(line, total - 1));
        double contentH = contentHeight();
        if (contentH > 0) {
            return clamped * (contentH / total) * (h / Math.max(contentH, h));
        }
        return (clamped / (double) total) * h;
    }

    private double markHeight(double h, int total) {
        double contentH = contentHeight();
        if (contentH > 0) {
            return Math.max(2.0, (contentH / total) * (h / Math.max(contentH, h)));
        }
        return Math.max(2.0, h / total);
    }

    /** The mark under {@code localY}, or -1. */
    int hitAt(double localY) {
        int total = area.getParagraphs().size();
        double h = canvas.getHeight();
        if (total == 0 || marks.size() == 0 || h <= 1) {
            return -1;
        }
        double markH = markHeight(h, total);
        double contentH = contentHeight();
        double perLine = contentH > 0 ? (contentH / total) * (h / Math.max(contentH, h)) : h / total;
        int line = (int) Math.min(total - 1, Math.max(0, localY / perLine));
        int i = marks.nearest(line);
        double y = Math.min(yForLine(marks.lines()[i], h, total), h - markH);
        double pad = UiZoom.px(HIT_PAD);
        return localY >= y - pad && localY <= y + markH + pad ? i : -1;
    }

    private void onHover(MouseEvent e) {
        int hit = active ? hitAt(e.getY()) : -1;
        if (hit < 0) {
            setCursor(Cursor.DEFAULT);
            hideTooltip();
            return;
        }
        setCursor(Cursor.HAND);
        String text = tooltipText(hit);
        if (tooltip != null && tooltip.isShowing() && text.equals(shownText)) {
            return;
        }
        if (tooltip == null) {
            tooltip = ControlNames.tooltip();
            tooltip.getStyleClass().add("todo-stripe-tooltip");
            tooltip.setWrapText(true);
            Tooltip made = tooltip;
            UiZoom.follow(made, () -> made.setMaxWidth(UiZoom.px(480)));
        }
        tooltip.setText(text);
        shownText = text;
        tooltip.show(this, e.getScreenX() - UiZoom.px(12) - tooltip.getWidth(), e.getScreenY() + UiZoom.px(14));
    }

    /** The marked line itself, cut short. */
    String tooltipText(int mark) {
        int line = marks.lines()[mark];
        String text = line < area.getParagraphs().size()
                ? area.getParagraph(line).getText().strip()
                : "";
        return text.length() > TOOLTIP_CHARS ? text.substring(0, TOOLTIP_CHARS) + "…" : text;
    }

    private void onClick(MouseEvent e) {
        int hit = active ? hitAt(e.getY()) : -1;
        if (hit >= 0) {
            onActivate.accept(marks.lines()[hit]);
        }
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.hide();
        }
        shownText = null;
    }
}
