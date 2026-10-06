package com.editora.editor;

import java.util.function.Supplier;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/**
 * A tooltip that is not built until the pointer first reaches its node.
 *
 * <p>A {@link Tooltip} is a {@code PopupControl} with its own style manager entry, and
 * {@link Tooltip#install} adds five event handlers to the node. Gutter rows and sticky-scroll rows are
 * created by the hundred per scroll and almost none is ever hovered, so they register this single handler
 * instead. Installing on {@code MOUSE_ENTERED} is early enough: the tooltip's own timer starts on the
 * {@code MOUSE_MOVED} events that follow.
 */
final class LazyTooltip {

    /** Tooltips built so far — the test seam for "scrolling creates none". */
    static int created;

    private LazyTooltip() {}

    static void install(Node node, Supplier<String> text) {
        install(node, text, null, -1);
    }

    /**
     * As {@link #install(Node, Supplier)}, with a style class for the tooltip (may be {@code null}) and its
     * show delay in milliseconds (negative keeps the platform default).
     */
    static void install(Node node, Supplier<String> text, String styleClass, double showDelayMillis) {
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, new EventHandler<>() {
            @Override
            public void handle(MouseEvent e) {
                node.removeEventHandler(MouseEvent.MOUSE_ENTERED, this);
                String value = text.get();
                if (value == null || value.isBlank()) {
                    return;
                }
                Tooltip tip = new Tooltip(value);
                if (styleClass != null) {
                    tip.getStyleClass().add(styleClass);
                }
                if (showDelayMillis >= 0) {
                    tip.setShowDelay(Duration.millis(showDelayMillis));
                }
                created++;
                Tooltip.install(node, tip);
            }
        });
    }
}
