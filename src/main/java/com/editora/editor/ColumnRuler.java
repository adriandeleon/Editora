package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The vertical lines at the ruler columns of one view. {@code EditorBuffer} measures where column 0 is in the
 * primary view and how wide a column is (see {@code measureAndPlaceRuler}) and hands both to {@link #place};
 * this class shows a line at each ruler column, hiding the ones outside the visible text. One measure serves
 * every ruler: column {@code n} is column 0 plus {@code n} advances.
 *
 * <p>The split's second view gets a twin (see {@link #follower}) that needs no measuring of its own: both
 * views lay the same text out behind the same gutter, so its columns are the primary's shifted by the
 * difference between the two views' horizontal scroll.
 */
final class ColumnRuler extends Group implements SecondaryPane.Followed {

    private final CodeArea view;
    /** How far down the lines reach: the pane's height. */
    private final DoubleProperty height = new SimpleDoubleProperty();

    private final List<Line> lines = new ArrayList<>();
    /** Column 0's x with the view scrolled fully left; null while the rulers cannot or need not be shown. */
    private @Nullable Double unscrolledZero;

    private double advance;
    private List<Integer> columns = List.of();

    private @Nullable ColumnRuler follower;

    ColumnRuler(CodeArea view) {
        this.view = view;
        getStyleClass().add("column-rulers");
        setManaged(false);
        setMouseTransparent(true);
        setVisible(false);
    }

    /** Ties the lines' length to the pane's height. */
    DoubleProperty heightProperty() {
        return height;
    }

    /**
     * Puts a line at each of {@code columns}, column 0 being at {@code zeroX} (in the pane's coordinates, as
     * the view is scrolled right now) and each column {@code advance} wide. Hides them all when {@code zeroX}
     * is null; a line outside {@code [0, viewportWidth]} is hidden on its own.
     */
    void place(@Nullable Double zeroX, double advance, List<Integer> columns, double viewportWidth) {
        unscrolledZero = zeroX == null ? null : zeroX + view.getEstimatedScrollX();
        this.advance = advance;
        this.columns = columns;
        show(zeroX, viewportWidth);
        if (follower != null) {
            follower.unscrolledZero = unscrolledZero;
            follower.advance = advance;
            follower.columns = columns;
            follower.update();
        }
    }

    /** Hides every line. */
    void hide() {
        place(null, 0, List.of(), 0);
    }

    /** The x of each line shown, in column order: a test seam. */
    List<Double> shownForTest() {
        List<Double> out = new ArrayList<>();
        for (Line line : lines) {
            if (line.isVisible() && isVisible()) {
                out.add(line.getStartX());
            }
        }
        return out;
    }

    private void show(@Nullable Double zeroX, double viewportWidth) {
        if (zeroX != null) {
            while (lines.size() < columns.size()) {
                Line line = new Line();
                line.getStyleClass().add("column-ruler");
                line.setStartY(0);
                line.endYProperty().bind(height);
                lines.add(line);
                getChildren().add(line);
            }
        }
        boolean any = false;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            double x = zeroX == null || i >= columns.size() ? -1 : zeroX + columns.get(i) * advance;
            boolean show = x >= 0 && x <= viewportWidth;
            line.setVisible(show);
            if (show) {
                line.setStartX(x);
                line.setEndX(x);
                any = true;
            }
        }
        setVisible(any);
    }

    /** Re-derives a twin's positions from its own view's scroll and width. */
    private void update() {
        double width = view.getParent() instanceof Region text ? text.getWidth() : view.getWidth();
        show(unscrolledZero == null ? null : unscrolledZero - view.getEstimatedScrollX(), width);
    }

    /** The same rulers for a split's second {@code view}, placed whenever these are. */
    @Override
    public Node follower(CodeArea second) {
        ColumnRuler twin = new ColumnRuler(second);
        twin.unscrolledZero = unscrolledZero;
        twin.advance = advance;
        twin.columns = columns;
        second.estimatedScrollXProperty().addListener((obs, old, now) -> twin.update());
        second.widthProperty().addListener((obs, old, now) -> twin.update());
        twin.parentProperty().addListener((obs, old, parent) -> {
            twin.height.unbind();
            if (parent instanceof Region pane) {
                twin.height.bind(pane.heightProperty());
            }
        });
        follower = twin;
        return twin;
    }
}
