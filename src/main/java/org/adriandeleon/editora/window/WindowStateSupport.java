package org.adriandeleon.editora.window;

import javafx.geometry.Rectangle2D;

import java.util.List;

public final class WindowStateSupport {
    private static final double DEFAULT_WIDTH = 1440;
    private static final double DEFAULT_HEIGHT = 920;

    private WindowStateSupport() {
    }

    public static Position resolveVisiblePosition(double width,
                                                  double height,
                                                  double savedX,
                                                  double savedY,
                                                  List<Rectangle2D> visualBounds) {
        double resolvedWidth = width > 0 ? width : DEFAULT_WIDTH;
        double resolvedHeight = height > 0 ? height : DEFAULT_HEIGHT;
        Rectangle2D fallbackBounds = primaryBounds(visualBounds);

        if (!Double.isNaN(savedX)
                && !Double.isNaN(savedY)
                && isVisible(savedX, savedY, resolvedWidth, resolvedHeight, visualBounds)) {
            return new Position(savedX, savedY);
        }

        return centerWithin(fallbackBounds, resolvedWidth, resolvedHeight);
    }

    static boolean isVisible(double x,
                             double y,
                             double width,
                             double height,
                             List<Rectangle2D> visualBounds) {
        if (Double.isNaN(x) || Double.isNaN(y) || width <= 0 || height <= 0) {
            return false;
        }

        double maxX = x + width;
        double maxY = y + height;
        return visualBounds.stream().anyMatch(bounds ->
                maxX > bounds.getMinX()
                        && x < bounds.getMaxX()
                        && maxY > bounds.getMinY()
                        && y < bounds.getMaxY());
    }

    private static Rectangle2D primaryBounds(List<Rectangle2D> visualBounds) {
        if (visualBounds == null || visualBounds.isEmpty()) {
            return new Rectangle2D(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }
        return visualBounds.getFirst();
    }

    private static Position centerWithin(Rectangle2D bounds, double width, double height) {
        double x;
        if (width >= bounds.getWidth()) {
            x = bounds.getMinX();
        } else {
            x = bounds.getMinX() + ((bounds.getWidth() - width) / 2.0d);
        }

        double y;
        if (height >= bounds.getHeight()) {
            y = bounds.getMinY();
        } else {
            y = bounds.getMinY() + ((bounds.getHeight() - height) / 2.0d);
        }

        return new Position(x, y);
    }

    public record Position(double x, double y) {
    }
}

