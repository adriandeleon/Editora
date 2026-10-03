package com.editora.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * A Settings card row: the title/description column on the left and the control on the right — unless
 * that would squeeze the text. A wide control (a combo with long items, a path field with a Browse
 * button) used to keep its full width and leave the description a ~90px column wrapping to five lines;
 * here the control drops <em>under</em> the text whenever the text column would fall below
 * {@link #TEXT_MIN_WIDTH}, so the description always reads as a sentence.
 */
final class SettingRowPane extends Pane {

    /** Narrowest text column that still sits beside its control. */
    static final double TEXT_MIN_WIDTH = 260;

    static final double GAP = 16;
    static final double STACK_GAP = 8;

    private final Node text;
    private final Node control;

    SettingRowPane(Node text, Node control) {
        this.text = text;
        this.control = control;
        getChildren().add(text);
        if (control != null) {
            getChildren().add(control);
        }
    }

    /**
     * Whether the control goes below the text: when, beside it, the text column would be narrower than
     * both {@link #TEXT_MIN_WIDTH} and the text's own single-line width (a short title needs no more than
     * its own width, so a short row never stacks). Pure.
     */
    static boolean stacks(double contentWidth, double textPrefWidth, double controlPrefWidth) {
        double textColumn = contentWidth - GAP - controlPrefWidth;
        return textColumn < Math.min(TEXT_MIN_WIDTH, textPrefWidth);
    }

    boolean isStacked() {
        return control != null && stacks(contentWidth(getWidth()), text.prefWidth(-1), controlWidth());
    }

    /**
     * The width the control wants, within its own min/max: {@code prefWidth} alone ignores a max width, and
     * a wrapping status label capped at 440px would then be measured for one line but laid out for two.
     */
    private double controlWidth() {
        double pref = control.prefWidth(-1);
        return control.isResizable() ? Math.max(control.minWidth(-1), Math.min(pref, control.maxWidth(-1))) : pref;
    }

    private double contentWidth(double width) {
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
        return in.getLeft() + in.getRight() + Math.min(TEXT_MIN_WIDTH, text.prefWidth(-1));
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        double w = text.prefWidth(-1);
        if (control != null) {
            w = Math.min(w, 2 * TEXT_MIN_WIDTH) + GAP + controlWidth();
        }
        return in.getLeft() + in.getRight() + w;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double cw = width < 0 ? computePrefWidth(-1) - in.getLeft() - in.getRight() : contentWidth(width);
        double h;
        if (control == null) {
            h = text.prefHeight(cw);
        } else {
            double controlWidth = controlWidth();
            if (stacks(cw, text.prefWidth(-1), controlWidth)) {
                h = text.prefHeight(cw) + STACK_GAP + control.prefHeight(Math.min(cw, controlWidth));
            } else {
                h = Math.max(text.prefHeight(Math.max(0, cw - GAP - controlWidth)), control.prefHeight(controlWidth));
            }
        }
        return in.getTop() + in.getBottom() + h;
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double x = in.getLeft();
        double y = in.getTop();
        double cw = contentWidth(getWidth());
        double ch = Math.max(0, getHeight() - in.getTop() - in.getBottom());
        if (control == null) {
            layoutInArea(text, x, y, cw, ch, 0, HPos.LEFT, VPos.CENTER);
            return;
        }
        double controlWidth = controlWidth();
        if (stacks(cw, text.prefWidth(-1), controlWidth)) {
            double textHeight = text.prefHeight(cw);
            layoutInArea(text, x, y, cw, textHeight, 0, HPos.LEFT, VPos.TOP);
            double top = y + textHeight + STACK_GAP;
            layoutInArea(control, x, top, cw, Math.max(0, in.getTop() + ch - top), 0, HPos.LEFT, VPos.TOP);
        } else {
            double textWidth = Math.max(0, cw - GAP - controlWidth);
            layoutInArea(text, x, y, textWidth, ch, 0, HPos.LEFT, VPos.CENTER);
            layoutInArea(control, x + textWidth + GAP, y, controlWidth, ch, 0, HPos.RIGHT, VPos.CENTER);
        }
    }
}
