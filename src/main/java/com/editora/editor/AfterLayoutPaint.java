package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
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
 * there, as nothing it listens to asked again. So the paint is a post-layout pulse listener, and on the
 * {@link #CHECKS_AFTER_PAINT} pulses after it this compares where the first visible character is with where it
 * was when the overlay painted, painting again in the same pulse if it moved.
 *
 * <p>The overlays over one {@link CodeArea} share one {@link Origin}: one post-layout listener, and one look at
 * where the text is per pulse, which every overlay's check and record read. The look changes nothing: RichTextFX
 * answers {@code getCharacterBoundsOnScreen} by adding a shape to the paragraph's text flow and taking it off
 * again, which dirties the scene, so a check made that way caused the next pulse and its frame, and the three
 * checks after every paint chained three more (about 1.8 extra frames per keystroke). The look reads the text
 * column's place on screen and the first visible paragraph's top, plain transforms. The checks ride on the pulses
 * that happen anyway and never ask for one: text moves only in a layout pass, and a layout pass comes with a
 * pulse, whose post-layout listener this is. A paint that is asked for still asks for its pulse.
 *
 * <p>One per overlay; FX thread only. The shared listener is on the scene only while a paint or a check is due.
 */
public final class AfterLayoutPaint {

    /** How many pulses after a paint the text's position is checked. */
    static final int CHECKS_AFTER_PAINT = 3;

    private final Node owner;
    private final Origin origin;
    private final Runnable paint;
    private final BooleanSupplier drawable;

    private boolean pending;
    private int checksLeft;
    /** Where the text was when the overlay last painted (see {@link Origin#look}; NaN: unknown). */
    private double paintedX = Double.NaN;

    private double paintedY = Double.NaN;

    /**
     * @param owner the overlay, whose scene's pulses are listened to
     * @param area the text the overlay is drawn over
     * @param paint the overlay's repaint
     * @param drawable whether the overlay has anything to draw: the checks after a paint stop when it has not
     */
    public AfterLayoutPaint(Node owner, CodeArea area, Runnable paint, BooleanSupplier drawable) {
        this.owner = owner;
        this.origin = Origin.of(area);
        this.paint = paint;
        this.drawable = drawable;
    }

    /** Asks for {@code paint} after the next layout pass (several requests before it are one paint). */
    public void request() {
        pending = true;
        arm();
    }

    /**
     * The screen bounds of the first character of the first visible line that has one, or null. Measured once per
     * pulse for every overlay over the area, so a paint that needs where the text starts asks here. This is a
     * RichTextFX range query, which dirties the scene: ask it when painting, not to decide whether to.
     */
    public @Nullable Bounds firstCharacterOnScreen() {
        return origin.firstOnScreen();
    }

    /**
     * The screen x of the text column (the paragraphs' text flow, past the gutter), NaN when none is laid out: a
     * look that changes nothing, for a frame key that must change when the text moves sideways.
     */
    public double textColumnOnScreen() {
        return origin.lookX();
    }

    /**
     * {@link #textColumnOnScreen()} for {@code area}, for code that paints over it without an
     * {@code AfterLayoutPaint} of its own: the look the area's overlays share this pulse, or a fresh one.
     */
    public static double textColumnOnScreen(CodeArea area) {
        return Origin.of(area).lookX();
    }

    /** Whether a paint was asked for and will run after the layout of {@code scene}'s next pulse (tests). */
    boolean paintsAfterLayoutOf(Scene scene) {
        return pending && origin.armed == scene;
    }

    /** Whether this overlay still has a paint or a check due (tests: none once the checks are done). */
    boolean armedForTest() {
        return origin.armed != null && origin.due.contains(this);
    }

    /** Whether the area's shared listener is on a scene (tests: it comes off once every check is done). */
    boolean listeningForTest() {
        return origin.armed != null;
    }

    /** The area's looks at where the text is so far (tests). */
    int measuresForTest() {
        return origin.measures;
    }

    /** Joins the area's listener for the next post-layout pulse, and asks for one. */
    private void arm() {
        origin.join(this, owner.getScene());
        Platform.requestNextPulse();
    }

    /** This overlay's part of a pulse: paints if asked to, else checks the last paint still fits. */
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
        if (!drawable.getAsBoolean() || owner.getScene() != origin.armed) {
            checksLeft = 0;
        }
    }

    /** Whether a paint or a check is still due. */
    private boolean due() {
        return pending || checksLeft > 0;
    }

    private void paintAndRecord() {
        paint.run();
        paintedX = origin.lookX();
        paintedY = origin.lookY();
        checksLeft = CHECKS_AFTER_PAINT;
    }

    /** Whether the text is no longer where it was when the overlay last painted. */
    private boolean textMoved() {
        double x = origin.lookX();
        double y = origin.lookY();
        if (Double.isNaN(x) && Double.isNaN(y)) {
            return false; // nothing laid out to compare against this pulse
        }
        return Double.compare(x, paintedX) != 0 || Double.compare(y, paintedY) != 0;
    }

    /**
     * What the overlays over one area share: the post-layout listener, and the first visible character's place,
     * measured at most once per pulse.
     */
    static final class Origin {

        private static final Object KEY = new Object();

        private final CodeArea area;
        private final Runnable afterLayout = this::afterLayoutPulse;
        /** The overlays with a paint or a check due, in the order they joined. */
        private final List<AfterLayoutPaint> due = new ArrayList<>();
        /** The scene whose post-layout pulses {@link #afterLayout} is listening to, or null. */
        private @Nullable Scene armed;

        private boolean inPulse;
        private boolean looked;
        private double lookX = Double.NaN;
        private double lookY = Double.NaN;
        private boolean measured;
        private @Nullable Bounds firstOnScreen;
        /** A text flow of the area's paragraphs, kept while it stays in the scene (they all start at one x). */
        private @Nullable Node textFlow;
        /** Looks taken (tests: one per pulse, however many overlays read it). */
        private int measures;

        private Origin(CodeArea area) {
            this.area = area;
        }

        static Origin of(CodeArea area) {
            return (Origin) area.getProperties().computeIfAbsent(KEY, k -> new Origin(area));
        }

        private void join(AfterLayoutPaint member, @Nullable Scene scene) {
            if (!due.contains(member)) {
                due.add(member);
            }
            if (scene == null) {
                Platform.runLater(afterLayout); // not in a window: there is no pulse to wait for
                return;
            }
            if (armed != scene) {
                disarm();
                scene.addPostLayoutPulseListener(afterLayout);
                armed = scene;
            }
        }

        private void disarm() {
            if (armed != null) {
                armed.removePostLayoutPulseListener(afterLayout);
                armed = null;
            }
        }

        /** Runs once the scene has been laid out: each overlay due paints or checks, from one measure. */
        private void afterLayoutPulse() {
            inPulse = true;
            looked = false;
            measured = false;
            try {
                // A paint that asks for another overlay's paint: that one joins, and runs on the next pulse.
                for (AfterLayoutPaint member : due.toArray(new AfterLayoutPaint[0])) {
                    member.afterLayoutPulse();
                }
            } finally {
                inPulse = false;
                looked = false;
                measured = false;
                firstOnScreen = null;
            }
            due.removeIf(member -> !member.due());
            if (due.isEmpty()) {
                disarm();
            }
        }

        private double lookX() {
            look();
            return lookX;
        }

        private double lookY() {
            look();
            return lookY;
        }

        /**
         * Where the text is, with nothing changed to find out: the screen x of the paragraphs' text column (it moves
         * when the gutter widens, the padding changes or the text is scrolled sideways) and the screen y of the
         * first visible paragraph's top. Once per pulse; every time when asked between pulses.
         */
        private void look() {
            if (inPulse && looked) {
                return;
            }
            looked = true;
            measures++;
            lookX = Double.NaN;
            lookY = Double.NaN;
            try {
                Node flow = textFlow;
                if (flow == null || flow.getScene() == null) {
                    flow = area.lookup(".paragraph-text");
                    textFlow = flow;
                }
                Point2D at = flow == null ? null : flow.localToScreen(0, 0);
                if (at != null) {
                    lookX = at.getX();
                }
                if (!area.getParagraphs().isEmpty()) {
                    Bounds top = area.getParagraphBoundsOnScreen(Math.max(0, area.firstVisibleParToAllParIndex()))
                            .orElse(null);
                    if (top != null) {
                        lookY = top.getMinY();
                    }
                }
            } catch (RuntimeException midLayout) {
                // Viewport mid-layout: nothing to compare against this pulse.
            }
        }

        private @Nullable Bounds firstOnScreen() {
            if (inPulse && measured) {
                return firstOnScreen;
            }
            measured = true;
            firstOnScreen = firstCharacterOnScreen(area);
            return firstOnScreen;
        }
    }

    /** The screen bounds of the first character of the first visible line that has one, or null. */
    private static @Nullable Bounds firstCharacterOnScreen(CodeArea area) {
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
                    return b;
                }
            }
        } catch (RuntimeException midLayout) {
            // Viewport mid-layout: nothing to compare against this pulse.
        }
        return null;
    }
}
