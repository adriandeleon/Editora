package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;

/** The container of a split's second view: its scroll pane, what is drawn over the text, and its minimap. */
final class SecondaryPane {

    private SecondaryPane() {}

    /**
     * Docks {@code minimap} on the right edge and lets {@code scroll} fill the rest ({@code EditorBuffer}
     * sets its right anchor to make room for the minimap). {@code overText} is placed by its owner, which
     * tracks the scroll pane's bounds; it sits above the scroll pane and below the minimap.
     */
    static AnchorPane assemble(Node scroll, Node overText, Node minimap) {
        AnchorPane pane = new AnchorPane(scroll, overText, minimap);
        AnchorPane.setTopAnchor(scroll, 0d);
        AnchorPane.setBottomAnchor(scroll, 0d);
        AnchorPane.setLeftAnchor(scroll, 0d);
        AnchorPane.setTopAnchor(minimap, 0d);
        AnchorPane.setBottomAnchor(minimap, 0d);
        AnchorPane.setRightAnchor(minimap, 0d);
        return pane;
    }
}
