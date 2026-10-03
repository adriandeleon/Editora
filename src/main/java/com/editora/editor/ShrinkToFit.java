package com.editora.editor;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

/**
 * Hosts one block that cannot get narrower by itself — a rendered diagram, an image, a display formula —
 * and scales it down when the space it is given is narrower than the block.
 *
 * <p>Such a block reports its full width as its <em>minimum</em> width, and a minimum width propagates up
 * to the whole Markdown column: with one 860px diagram in a 500px split pane, the fit-to-width preview
 * could not shrink below 860px, so every paragraph ran off the pane behind a horizontal scrollbar. This
 * region's own minimum width is zero, so the column — and its prose — always wraps to the pane, and only
 * the wide block itself is reduced (uniformly, so a diagram keeps its proportions).
 */
final class ShrinkToFit extends Region {

    private final Node child;
    private final Scale scale = new Scale(1, 1, 0, 0);

    ShrinkToFit(Node child) {
        this.child = child;
        child.getTransforms().add(scale);
        getChildren().add(child);
    }

    /** The factor that fits a block {@code needed} wide into {@code available}: never enlarges. Pure. */
    static double factor(double needed, double available) {
        return needed <= 0 || available >= needed ? 1 : Math.max(0, available) / needed;
    }

    private double contentWidth(double width) {
        Insets in = getInsets();
        return Math.max(0, width - in.getLeft() - in.getRight());
    }

    /** The width the child is laid out at: the available width if it can shrink that far, else its minimum. */
    private double childWidth(double available) {
        if (!child.isResizable()) {
            return child.getLayoutBounds().getWidth();
        }
        return Math.max(child.minWidth(-1), Math.min(available, child.maxWidth(-1)));
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
        double available = width < 0 ? child.prefWidth(-1) : contentWidth(width);
        double w = childWidth(available);
        return in.getTop() + in.getBottom() + child.prefHeight(w) * factor(w, available);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double available = contentWidth(getWidth());
        double w = childWidth(available);
        double f = factor(w, available);
        scale.setX(f);
        scale.setY(f);
        double h = child.prefHeight(w);
        if (f < 1) {
            // Lay the block out at its own size; the transform (pivot at its origin) brings it into view.
            if (child.isResizable()) {
                child.resize(w, h);
            }
            child.relocate(
                    in.getLeft() - child.getLayoutBounds().getMinX() * f,
                    in.getTop() - child.getLayoutBounds().getMinY() * f);
        } else {
            layoutInArea(child, in.getLeft(), in.getTop(), available, h, 0, HPos.LEFT, VPos.TOP);
        }
    }
}
