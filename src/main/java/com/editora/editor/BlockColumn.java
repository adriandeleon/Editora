package com.editora.editor;

import java.util.Map;
import java.util.WeakHashMap;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * The live preview's column of blocks: a {@link VBox} that remembers each child's height.
 *
 * <p>A {@code VBox} asks every child for its height on every layout pass. For text that wraps the answer
 * depends on the width, so JavaFX does not cache it: with one block of a 20,000-line document replaced, laying
 * out the column measured the other 4,500 again — re-wrapping every paragraph and re-solving every table —
 * which was most of what a keystroke still cost after the nodes themselves were being reused.
 *
 * <p>A child's height at a given width can only change when the child asks for layout (that is the JavaFX
 * contract: {@code requestLayout} is what clears a size), and such a request marks the child
 * {@link Parent#isNeedsLayout() needing layout} until it has been laid out. So a child that is not marked, at
 * a width that has not changed, still has the height measured last time; it is not measured, and not even
 * touched when it also stays where it was.
 */
final class BlockColumn extends VBox {

    /**
     * Each child's height, per content width. More than one width is live at a time: a scroll pane asks for
     * the height with and without room for its scrollbar on every pass, so remembering only the last width
     * asked for would measure everything twice per pass. Weak: a removed block is forgotten.
     */
    private final java.util.LinkedHashMap<Long, Map<Node, Double>> heights =
            new java.util.LinkedHashMap<>(4, 0.75f, true);

    /** Most widths remembered at once: a scroll pane and the wrappers around the column ask at a handful
     *  (viewport with and without its scrollbar, the column's capped and preferred widths). */
    private static final int WIDTHS = 8;

    /** Where each child was last put: {@code {y, width, height}}. */
    private final Map<Node, double[]> placed = new WeakHashMap<>();

    private long measured;

    /** How many times a child has been measured so far (the others kept their remembered height). */
    long measured() {
        return measured;
    }

    private static boolean dirty(Node child) {
        return !(child instanceof Parent p) || p.isNeedsLayout();
    }

    private double contentWidth(double width) {
        Insets in = getInsets();
        return Math.max(0, width - snapSpaceX(in.getLeft()) - snapSpaceX(in.getRight()));
    }

    private Map<Node, Double> heightsAt(double w) {
        long key = Math.round(w * 64);
        Map<Node, Double> at = heights.get(key);
        if (at == null) {
            at = new WeakHashMap<>();
            heights.put(key, at);
            if (heights.size() > WIDTHS) {
                var eldest = heights.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }
        return at;
    }

    /** The height of {@code child} at content width {@code w}: remembered unless it asked for layout. */
    private double heightOf(Node child, double w, Map<Node, Double> at) {
        if (dirty(child)) {
            for (Map<Node, Double> m : heights.values()) {
                m.remove(child); // whatever it measured before, at any width, is out of date
            }
        }
        Double h = at.get(child);
        if (h == null) {
            h = snapSizeY(child.prefHeight(w));
            at.put(child, h);
            measured++;
        }
        return h;
    }

    @Override
    protected double computePrefHeight(double width) {
        if (width < 0) {
            return super.computePrefHeight(width);
        }
        double w = contentWidth(width);
        Map<Node, Double> at = heightsAt(w);
        Insets in = getInsets();
        double total = snapSpaceY(in.getTop()) + snapSpaceY(in.getBottom());
        int managed = 0;
        for (Node child : getChildren()) {
            if (child.isManaged()) {
                total += heightOf(child, w, at);
                managed++;
            }
        }
        return total + Math.max(0, managed - 1) * snapSpaceY(getSpacing());
    }

    @Override
    protected double computeMinHeight(double width) {
        return width < 0 ? super.computeMinHeight(width) : computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double x = snapSpaceX(in.getLeft());
        double w = contentWidth(getWidth());
        Map<Node, Double> at = heightsAt(w);
        double spacing = snapSpaceY(getSpacing());
        double y = snapSpaceY(in.getTop());
        for (Node child : getChildren()) {
            if (!child.isManaged()) {
                continue;
            }
            boolean wasDirty = dirty(child);
            double h = heightOf(child, w, at);
            double[] p = placed.get(child);
            if (wasDirty || p == null || p[1] != w || p[2] != h) {
                layoutInArea(child, x, y, w, h, 0, HPos.LEFT, VPos.TOP);
                placed.put(child, new double[] {y, w, h});
            } else if (p[0] != y) {
                child.setLayoutY(y - child.getLayoutBounds().getMinY()); // only moved: nothing to measure
                p[0] = y;
            }
            y += h + spacing;
        }
    }
}
