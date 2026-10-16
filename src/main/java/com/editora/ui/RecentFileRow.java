package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

/**
 * One row of the toolbar's Recent Files dropdown: file name, project pill, remove button.
 *
 * <p>The rows of one dropdown share a {@link Columns}, so the pills line up: the name column is as wide as
 * the widest name and the pill column as wide as the widest pill, each capped so one very long name cannot
 * stretch the menu across the window (an over-long name or project ellipsizes; the row's tooltip has the
 * full path). The widths come from the labels' own preferred widths during layout, after CSS has resolved
 * their fonts, so nothing is measured ahead of time.
 */
final class RecentFileRow extends Region {

    /** Space between the name, pill and remove-button columns. */
    static final double GAP = 10;
    /** Widest the name column grows; longer names ellipsize. */
    static final double MAX_NAME_WIDTH = 380;
    /** Widest the pill column grows; longer project names ellipsize. */
    static final double MAX_PILL_WIDTH = 200;

    /** The column widths shared by every row of one dropdown. */
    static final class Columns {
        private final List<RecentFileRow> rows = new ArrayList<>();

        double nameWidth() {
            double width = 0;
            for (RecentFileRow row : rows) {
                width = Math.max(width, row.name.prefWidth(-1));
            }
            return Math.min(Math.ceil(width), MAX_NAME_WIDTH);
        }

        /** Zero when no row has a pill, so a dropdown without projects has no empty column. */
        double pillWidth() {
            double width = 0;
            for (RecentFileRow row : rows) {
                if (row.pill != null) {
                    width = Math.max(width, row.pill.prefWidth(-1));
                }
            }
            return Math.min(Math.ceil(width), MAX_PILL_WIDTH);
        }
    }

    private final Columns columns;
    private final Label name;
    private final Label pill;
    private final Node remove;

    /** {@code pill} is null for a file outside every known project. */
    RecentFileRow(Columns columns, Label name, Label pill, Node remove) {
        this.columns = columns;
        this.name = name;
        this.pill = pill;
        this.remove = remove;
        columns.rows.add(this);
        getChildren().add(name);
        if (pill != null) {
            getChildren().add(pill);
        }
        getChildren().add(remove);
    }

    @Override
    protected double computePrefWidth(double height) {
        double pillWidth = columns.pillWidth();
        return snappedLeftInset()
                + columns.nameWidth()
                + (pillWidth > 0 ? GAP + pillWidth : 0)
                + GAP
                + remove.prefWidth(-1)
                + snappedRightInset();
    }

    @Override
    protected double computeMinWidth(double height) {
        return computePrefWidth(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        double height = Math.max(name.prefHeight(-1), remove.prefHeight(-1));
        if (pill != null) {
            height = Math.max(height, pill.prefHeight(-1));
        }
        return snappedTopInset() + height + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double top = snappedTopInset();
        double contentHeight = getHeight() - top - snappedBottomInset();
        double x = snappedLeftInset();
        double nameWidth = columns.nameWidth();
        place(name, x, top, Math.min(nameWidth, name.prefWidth(-1)), contentHeight);
        if (pill != null) {
            place(pill, x + nameWidth + GAP, top, Math.min(columns.pillWidth(), pill.prefWidth(-1)), contentHeight);
        }
        double removeWidth = remove.prefWidth(-1);
        place(remove, getWidth() - snappedRightInset() - removeWidth, top, removeWidth, contentHeight);
    }

    /** Sizes {@code node} to its preferred height and centres it vertically in the row. */
    private static void place(Node node, double x, double top, double width, double contentHeight) {
        double height = Math.min(node.prefHeight(width), contentHeight);
        node.resizeRelocate(Math.round(x), Math.round(top + (contentHeight - height) / 2), width, height);
    }
}
