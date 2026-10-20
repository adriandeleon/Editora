package com.editora.editor;

import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Runs an overlay's paint <em>after the layout pass</em> of the pulse that asked for it, and looks again on the
 * few pulses after.
 *
 * <p>An overlay places what it draws from the laid-out position of the text. A paint from
 * {@code Platform.runLater} runs between pulses: when the pulse after it moves the text — the line numbers
 * widening on the fold recompute's debounce after the file reaches 1,000 lines, a gutter sized a pulse after a
 * file is opened — that pulse renders the old paint over the moved text, and on macOS the overlay could stay
 * there, as nothing it listens to asked again. So the paint is a post-layout pulse listener, and for
 * {@link #CHECKS_AFTER_PAINT} pulses after it this compares where the first visible character is with where it was
 * when the overlay painted, painting again in the same pulse if it moved.
 *
 * <p>One per overlay; FX thread only. The listener is on the scene only while a paint or a check is due.
 */
final class AfterLayoutPaint {

    /** How many pulses after a paint the text's position is checked. */
    static final int CHECKS_AFTER_PAINT = 3;

    private final Node owner;
    private final CodeArea area;
    private final Runnable paint;
    private final BooleanSupplier drawable;
    private final Runnable afterLayout = this::afterLayoutPulse;
    /** The scene whose post-layout pulses {@link #afterLayout} is listening to, or null. */
    private @Nullable Scene armed;

    private boolean pending;
    private int checksLeft;
    /** Where the first visible character was when the overlay last painted (NaN: unknown). */
    private double paintedX = Double.NaN;

    private double paintedY = Double.NaN;

    /**
     * @param owner the overlay, whose scene's pulses are listened to
     * @param area the text the overlay is drawn over
     * @param paint the overlay's repaint
     * @param drawable whether the overlay has anything to draw: the checks after a paint stop when it has not
     */
    AfterLayoutPaint(Node owner, CodeArea area, Runnable paint, BooleanSupplier drawable) {
        this.owner = owner;
        this.area = area;
        this.paint = paint;
        this.drawable = drawable;
    }

    /** Asks for {@code paint} after the next layout pass (several requests before it are one paint). */
    void request() {
        pending = true;
        arm();
    }

    /** Whether a paint was asked for and will run after the layout of {@code scene}'s next pulse (tests). */
    boolean paintsAfterLayoutOf(Scene scene) {
        return pending && armed == scene;
    }

    /** Whether the listener is on a scene (tests: it comes off once the checks are done). */
    boolean armedForTest() {
        return armed != null;
    }

    /** Listens for the next post-layout pulse, and asks for one. */
    private void arm() {
        Scene scene = owner.getScene();
        if (scene == null) {
            Platform.runLater(afterLayout); // not in a window: there is no pulse to wait for
            return;
        }
        if (armed != scene) {
            disarm();
            scene.addPostLayoutPulseListener(afterLayout);
            armed = scene;
        }
        Platform.requestNextPulse();
    }

    private void disarm() {
        if (armed != null) {
            armed.removePostLayoutPulseListener(afterLayout);
            armed = null;
        }
    }

    /** Runs once the scene has been laid out: paints if asked to, else checks the last paint still fits. */
    private void afterLayoutPulse() {
        if (pending) {
            pending = false;
            paintAndRecord();
        } else if (checksLeft > 0) {
            checksLeft--;
            if (drawable.getAsBoolean() && textMoved()) {
                paintAndRecord();
            }
        }
        if (checksLeft > 0 && drawable.getAsBoolean() && armed != null && owner.getScene() == armed) {
            Platform.requestNextPulse();
        } else {
            checksLeft = 0;
            disarm();
        }
    }

    private void paintAndRecord() {
        paint.run();
        Bounds origin = firstCharacter();
        paintedX = origin == null ? Double.NaN : origin.getMinX();
        paintedY = origin == null ? Double.NaN : origin.getMinY();
        checksLeft = CHECKS_AFTER_PAINT;
    }

    /** Whether the first visible character is no longer where it was when the overlay last painted. */
    private boolean textMoved() {
        Bounds now = firstCharacter();
        return now != null && (now.getMinX() != paintedX || now.getMinY() != paintedY);
    }

    /** The area-local bounds of the first character of the first visible line that has one, or null. */
    private @Nullable Bounds firstCharacter() {
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            for (int p = first; p <= last; p++) {
                if (area.getParagraphLength(p) == 0 || area.isFolded(p)) {
                    continue;
                }
                int abs = area.getAbsolutePosition(p, 0);
                Bounds b = area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null);
                if (b != null) {
                    return area.screenToLocal(b);
                }
            }
        } catch (RuntimeException midLayout) {
            // Viewport mid-layout: nothing to compare against this pulse.
        }
        return null;
    }
}
