package com.editora.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

/**
 * The response view's header row: Run, the status pill, the timing and size, then (right-aligned) the History
 * picker. In a narrow panel the parts give way in the order {@link HttpPanelLayout#header} decides, and History
 * moves to a row of its own rather than squeeze the status into "…".
 */
final class HttpHeaderRow extends Region {

    private final Region run;
    private final Label status;
    private final Label meta;
    private final Region history;

    /** Whether the status may be cut (a running request's long label), and the least it keeps then. */
    private boolean statusShrinks;

    private static final double RUNNING_STATUS_MIN = 120;

    /** The last fit, for tests. */
    private HttpPanelLayout.HeaderFit fit;

    HttpHeaderRow(Region run, Label status, Label meta, Region history) {
        this.run = run;
        this.status = status;
        this.meta = meta;
        this.history = history;
        getStyleClass().add("http-header");
        getChildren().addAll(run, status, meta, history);
    }

    /** While a request runs the status is a long "Running …" label that may be cut in the middle. */
    void setStatusShrinks(boolean shrinks) {
        if (statusShrinks != shrinks) {
            statusShrinks = shrinks;
            requestLayout();
        }
    }

    HttpPanelLayout.HeaderFit fitForTest() {
        return fit;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    private HttpPanelLayout.HeaderFit fit(double room) {
        double statusPref = status.prefWidth(-1);
        double metaPref = meta.getText() == null || meta.getText().isEmpty() ? 0 : meta.prefWidth(-1);
        return HttpPanelLayout.header(
                room,
                run.prefWidth(-1),
                statusPref,
                statusShrinks ? Math.min(statusPref, RUNNING_STATUS_MIN) : statusPref,
                metaPref);
    }

    private double rowHeight() {
        return Math.max(run.prefHeight(-1), Math.max(status.prefHeight(-1), history.prefHeight(-1)));
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        double w = run.prefWidth(-1) + status.prefWidth(-1) + meta.prefWidth(-1) + HttpPanelLayout.HISTORY_WIDTH;
        return in.getLeft() + w + 3 * HttpPanelLayout.GAP + in.getRight();
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + run.minWidth(-1) + in.getRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double w = width > 0 ? width : getWidth() > 0 ? getWidth() : prefWidth(-1);
        int rows = fit(w - in.getLeft() - in.getRight()).rows();
        return in.getTop() + rows * rowHeight() + (rows - 1) * HttpPanelLayout.GAP / 2 + in.getBottom();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double left = in.getLeft();
        double right = getWidth() - in.getRight();
        double room = right - left;
        fit = fit(room);
        double rowH = rowHeight();
        double y = in.getTop();
        double gap = HttpPanelLayout.GAP;
        double runW = run.prefWidth(-1);
        place(run, left, y, runW, rowH);
        double x = left + runW + gap;
        place(status, x, y, fit.status(), rowH);
        x += fit.status() + gap;
        meta.setVisible(fit.meta());
        if (fit.meta()) {
            place(meta, x, y, meta.prefWidth(-1), rowH);
        }
        if (fit.rows() == 1) {
            place(history, right - fit.history(), y, fit.history(), rowH);
        } else {
            place(history, left, y + rowH + gap / 2, fit.history(), rowH);
        }
    }

    private void place(Region node, double x, double y, double w, double rowH) {
        layoutInArea(node, x, y, Math.max(0, w), rowH, 0, Insets.EMPTY, true, false, HPos.LEFT, VPos.CENTER);
    }
}
