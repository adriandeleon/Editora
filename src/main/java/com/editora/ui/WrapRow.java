package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * A row that wraps instead of squeezing. Children sit side by side while they fit; when the row gets
 * narrow the trailing ones move to a further line. A child marked {@link #setGrow growing} (a text
 * field, or a label + field group) shares the spare width of its line and may shrink to its minimum
 * width before anything wraps; every other child always keeps its preferred width, so a button or a
 * label is never ellipsized to "…".
 *
 * <p>An {@code HBox} cannot do this (it shrinks everything, labels first) and a {@code FlowPane} cannot
 * stretch a field — the Find bar and the Find in Files panel need both.
 */
final class WrapRow extends Pane {

    private static final Object GROW = new Object();

    private final double hgap;
    private final double vgap;

    WrapRow(double hgap, double vgap, Node... children) {
        this.hgap = hgap;
        this.vgap = vgap;
        getChildren().addAll(children);
    }

    /** Marks {@code child} as one that fills spare width and may shrink to its min width. */
    static <T extends Node> T setGrow(T child) {
        child.getProperties().put(GROW, Boolean.TRUE);
        return child;
    }

    private static boolean grows(Node child) {
        return child.getProperties().containsKey(GROW);
    }

    /**
     * Breaks items into lines: each takes as many consecutive items as fit in {@code width} at their
     * narrowest ({@code basis}) with {@code gap} between them; an item wider than the row gets a line to
     * itself. Returns the index one past the last item of each line. Pure.
     */
    static int[] lineEnds(double[] basis, double width, double gap) {
        int[] ends = new int[basis.length];
        int lines = 0;
        double used = 0;
        for (int i = 0; i < basis.length; i++) {
            double next = used == 0 ? basis[i] : used + gap + basis[i];
            if (used > 0 && next > width + 0.5) {
                ends[lines++] = i;
                used = basis[i];
            } else {
                used = next;
            }
        }
        if (basis.length > 0) {
            ends[lines++] = basis.length;
        }
        return java.util.Arrays.copyOf(ends, lines);
    }

    private List<Node> items() {
        List<Node> items = new ArrayList<>(getChildren().size());
        for (Node child : getChildren()) {
            if (child.isManaged()) {
                items.add(child);
            }
        }
        return items;
    }

    private static double basis(Node child) {
        if (grows(child)) {
            return child.minWidth(-1);
        }
        double pref = child.prefWidth(-1);
        return child.isResizable() ? Math.min(pref, child.maxWidth(-1)) : pref; // prefWidth ignores a max width
    }

    private static double[] bases(List<Node> items) {
        double[] basis = new double[items.size()];
        for (int i = 0; i < basis.length; i++) {
            basis[i] = basis(items.get(i));
        }
        return basis;
    }

    private double contentWidth(double width) {
        Insets in = getInsets();
        return Math.max(0, width - in.getLeft() - in.getRight());
    }

    /** Number of lines the row occupies at its current width (for tests and callers that care). */
    int lineCount() {
        List<Node> items = items();
        return lineEnds(bases(items), contentWidth(getWidth()), hgap).length;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        double widest = 0;
        for (Node child : items()) {
            widest = Math.max(widest, basis(child));
        }
        Insets in = getInsets();
        return in.getLeft() + in.getRight() + widest;
    }

    @Override
    protected double computePrefWidth(double height) {
        double total = 0;
        List<Node> items = items();
        for (Node child : items) {
            total += Math.max(child.prefWidth(-1), basis(child));
        }
        Insets in = getInsets();
        return in.getLeft() + in.getRight() + total + hgap * Math.max(0, items.size() - 1);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        List<Node> items = items();
        Insets in = getInsets();
        double available = width < 0 ? Double.MAX_VALUE : contentWidth(width);
        int[] ends = lineEnds(bases(items), available, hgap);
        double height = 0;
        int start = 0;
        for (int end : ends) {
            double line = 0;
            for (int i = start; i < end; i++) {
                line = Math.max(line, items.get(i).prefHeight(-1));
            }
            height += line;
            start = end;
        }
        return in.getTop() + in.getBottom() + height + vgap * Math.max(0, ends.length - 1);
    }

    @Override
    protected void layoutChildren() {
        List<Node> items = items();
        Insets in = getInsets();
        double available = contentWidth(getWidth());
        double[] basis = bases(items);
        int[] ends = lineEnds(basis, available, hgap);
        double y = in.getTop();
        int start = 0;
        for (int end : ends) {
            double used = hgap * (end - start - 1);
            int growing = 0;
            double lineHeight = 0;
            for (int i = start; i < end; i++) {
                used += basis[i];
                growing += grows(items.get(i)) ? 1 : 0;
                lineHeight = Math.max(lineHeight, items.get(i).prefHeight(-1));
            }
            double share = growing == 0 ? 0 : Math.max(0, available - used) / growing;
            double x = in.getLeft();
            for (int i = start; i < end; i++) {
                Node child = items.get(i);
                double w = Math.min(available, basis[i] + (grows(child) ? share : 0));
                layoutInArea(child, x, y, w, lineHeight, 0, HPos.LEFT, VPos.CENTER);
                x += w + hgap;
            }
            y += lineHeight + vgap;
            start = end;
        }
    }
}
