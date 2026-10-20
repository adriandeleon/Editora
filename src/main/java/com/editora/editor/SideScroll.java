package com.editora.editor;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

import org.jspecify.annotations.Nullable;

/**
 * Hosts one block that must keep its own width — a table with many columns, a code listing — and scrolls it
 * sideways when the Markdown column is narrower, instead of squeezing it.
 *
 * <p>The preview used to force both to the column width: a 16-column table got columns two characters wide and
 * broke every word, and a long line of code wrapped, which changes what line-oriented text (a diff, a log, a
 * Python block) says. Here the child is laid out at the wider of the available width and its own minimum
 * width, and a thin horizontal scrollbar appears under it only when it does not fit.
 *
 * <p>Like {@link ShrinkToFit}, the region's own minimum width is zero, so one wide block never widens the
 * column. It is not a {@code ScrollPane}: a nested scroll pane swallows the vertical wheel and has no height
 * that follows its width, which wrapping table cells need.
 */
final class SideScroll extends Region {

    private final Region child;
    /** Created the first time the child does not fit: most blocks never need one, and a scrollbar is a
     *  control with a skin of its own — thousands of them were a visible share of a long document's build. */
    private @Nullable ScrollBar bar;

    private final Rectangle clip = new Rectangle();

    SideScroll(Region child) {
        this.child = child;
        getStyleClass().add("md-side-scroll");
        getChildren().add(child);
        setClip(clip);
        addEventHandler(ScrollEvent.SCROLL, e -> {
            double dx = e.getDeltaX() != 0 ? e.getDeltaX() : (e.isShiftDown() ? e.getDeltaY() : 0);
            ScrollBar b = bar;
            if (dx != 0 && b != null && overflows()) {
                b.setValue(Math.max(0, Math.min(b.getMax(), b.getValue() - dx)));
                e.consume(); // a vertical wheel is not consumed: it scrolls the document
            }
        });
    }

    private ScrollBar bar() {
        if (bar == null) {
            bar = new ScrollBar();
            bar.setOrientation(Orientation.HORIZONTAL);
            bar.getStyleClass().add("md-side-scroll-bar");
            bar.setManaged(false);
            bar.valueProperty().addListener(o -> requestLayout());
            getChildren().add(bar);
        }
        return bar;
    }

    /** The scrollbar's height once it is needed; before one exists, the height a new one would ask for. */
    private double barHeight() {
        return bar == null ? BAR_HEIGHT : Math.max(BAR_HEIGHT, bar.prefHeight(-1));
    }

    /** Room kept under a block that scrolls, in px (a themed scrollbar asks for about this). */
    private static final double BAR_HEIGHT = 12;

    /** The child this region scrolls. */
    Region content() {
        return child;
    }

    /** Whether the child is wider than the region (its scrollbar is showing). */
    boolean overflows() {
        return bar != null && bar.isVisible();
    }

    /** The width the child is laid out at when {@code available} is on offer: never under its minimum. Pure. */
    static double contentWidth(double available, double childMin) {
        return Math.max(Math.max(0, available), childMin);
    }

    private double available(double width) {
        Insets in = getInsets();
        return Math.max(0, width - in.getLeft() - in.getRight());
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + in.getRight();
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + in.getRight() + child.prefWidth(-1);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double min = child.minWidth(-1);
        double avail = width < 0 ? child.prefWidth(-1) : available(width);
        double w = contentWidth(avail, min);
        double barHeight = w > avail + 0.5 ? barHeight() : 0;
        return in.getTop() + in.getBottom() + child.prefHeight(w) + barHeight;
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double avail = available(getWidth());
        double w = contentWidth(avail, child.minWidth(-1));
        double h = child.prefHeight(w);
        boolean overflow = w > avail + 0.5;
        double offset = 0;
        if (overflow) {
            ScrollBar b = bar();
            double range = w - avail;
            b.setMax(range);
            b.setVisibleAmount(range * avail / w);
            b.setBlockIncrement(avail * 0.9);
            b.setUnitIncrement(40);
            if (b.getValue() > range) {
                b.setValue(range);
            }
            b.setVisible(true);
            b.resizeRelocate(in.getLeft(), in.getTop() + h, avail, barHeight());
            offset = b.getValue();
        } else if (bar != null) {
            bar.setValue(0);
            bar.setVisible(false);
        }
        child.resizeRelocate(in.getLeft() - offset, in.getTop(), w, h);
        clip.setWidth(getWidth());
        clip.setHeight(getHeight());
    }
}
