package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;

/** The container of a split's second view: its scroll pane, what is drawn over the text, and its minimap. */
final class SecondaryPane {

    private SecondaryPane() {}

    /**
     * Docks {@code minimap} on the right edge and lets {@code scroll} fill the rest ({@code EditorBuffer}
     * sets its right anchor to make room for the minimap). Each of {@code overText} is placed by its owner
     * or by {@link #over}, tracking the scroll pane's bounds; they sit above the scroll pane, in the order
     * given, and below the minimap.
     */
    static AnchorPane assemble(Node scroll, Node minimap, Node... overText) {
        AnchorPane pane = new AnchorPane(scroll);
        pane.getChildren().addAll(overText);
        pane.getChildren().add(minimap);
        AnchorPane.setTopAnchor(scroll, 0d);
        AnchorPane.setBottomAnchor(scroll, 0d);
        AnchorPane.setLeftAnchor(scroll, 0d);
        AnchorPane.setTopAnchor(minimap, 0d);
        AnchorPane.setBottomAnchor(minimap, 0d);
        AnchorPane.setRightAnchor(minimap, 0d);
        return pane;
    }

    /** Keeps {@code overlay} lying exactly over {@code text} (the scroll pane, wherever a minimap leaves it). */
    static <T extends Region> T over(Region text, T overlay) {
        overlay.setManaged(false);
        text.boundsInParentProperty()
                .addListener(
                        (obs, old, b) -> overlay.resizeRelocate(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
        return overlay;
    }
}
