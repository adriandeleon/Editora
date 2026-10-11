package com.editora.editor;

import javafx.scene.Node;

/**
 * Where each child of an {@link EditorBuffer}'s code pane sits in its stack, lowest first. A lazily attached
 * overlay or stripe goes in just below the first child with a higher layer, so the order is the same
 * whichever features a buffer turned on, and in whatever sequence. Nodes without a layer (the corner
 * controls, the sticky-scroll bar) are appended later and count as topmost.
 */
final class OverlayLayer {
    static final int TEXT = 0;
    static final int NOTE = 10;
    static final int SNIPPET = 20;
    static final int SEARCH = 30;
    static final int OCCURRENCE = 40;
    static final int TODO = 50;
    static final int LOG = 60;
    static final int WHITESPACE = 70;
    static final int SPELL = 80;
    static final int MERMAID_LINT = 90;
    static final int MARKDOWN_LINT = 100;
    static final int LSP = 110;
    static final int INLINE_VALUES = 120;
    static final int ACE_JUMP = 125;
    static final int MINIMAP = 130;
    static final int DIAGNOSTIC_STRIPE = 140;
    static final int MARKDOWN_LINT_STRIPE = 150;
    static final int TODO_STRIPE = 160;
    static final int RULER = 170;

    /** The property the layer is kept in on the node, so the stack can be rebuilt from the children. */
    static final String PROPERTY = "editora.overlayLayer";

    private OverlayLayer() {}

    static int of(Node node) {
        Object layer = node.hasProperties() ? node.getProperties().get(PROPERTY) : null;
        return layer instanceof Integer i ? i : Integer.MAX_VALUE;
    }

    /** Tags {@code node} with its layer and returns it. */
    static <T extends Node> T layered(T node, int layer) {
        node.getProperties().put(PROPERTY, layer);
        return node;
    }
}
