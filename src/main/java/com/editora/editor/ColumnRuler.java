package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;

import org.fxmisc.richtext.CodeArea;

/**
 * The vertical line at the ruler column of one view. {@code EditorBuffer} measures where the column is in
 * the primary view (see {@code measureAndPlaceRuler}) and hands the result to {@link #place}; this class
 * shows the line there, or hides it when the column is outside the visible text.
 *
 * <p>The split's second view gets a twin (see {@link #follower}) that needs no measuring of its own: both
 * views lay the same text out behind the same gutter, so its column is the primary's shifted by the
 * difference between the two views' horizontal scroll.
 */
final class ColumnRuler extends Line implements SecondaryPane.Followed {

    private final CodeArea view;
    /** Where the column would be with the view scrolled fully left; null while it cannot or need not be shown. */
    private Double unscrolledX;

    private ColumnRuler follower;

    ColumnRuler(CodeArea view) {
        this.view = view;
        getStyleClass().add("column-ruler");
        setManaged(false);
        setMouseTransparent(true);
        setStartY(0);
        setVisible(false);
    }

    /**
     * Puts the line at {@code x} (in the pane's coordinates, as the view is scrolled right now), or hides it
     * when {@code x} is null or outside {@code [0, viewportWidth]}.
     */
    void place(Double x, double viewportWidth) {
        unscrolledX = x == null ? null : x + view.getEstimatedScrollX();
        show(x, viewportWidth);
        if (follower != null) {
            follower.unscrolledX = unscrolledX;
            follower.update();
        }
    }

    private void show(Double x, double viewportWidth) {
        boolean show = x != null && x >= 0 && x <= viewportWidth;
        setVisible(show);
        if (show) {
            setStartX(x);
            setEndX(x);
        }
    }

    /** Re-derives a twin's position from its own view's scroll and width. */
    private void update() {
        double width = view.getParent() instanceof Region text ? text.getWidth() : view.getWidth();
        show(unscrolledX == null ? null : unscrolledX - view.getEstimatedScrollX(), width);
    }

    /** The same ruler for a split's second {@code view}, placed whenever this one is. */
    @Override
    public Node follower(CodeArea second) {
        ColumnRuler twin = new ColumnRuler(second);
        twin.unscrolledX = unscrolledX;
        second.estimatedScrollXProperty().addListener((obs, old, now) -> twin.update());
        second.widthProperty().addListener((obs, old, now) -> twin.update());
        twin.parentProperty().addListener((obs, old, parent) -> {
            twin.endYProperty().unbind();
            if (parent instanceof Region pane) {
                twin.endYProperty().bind(pane.heightProperty());
            }
        });
        follower = twin;
        return twin;
    }
}
