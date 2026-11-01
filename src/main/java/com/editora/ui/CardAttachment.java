package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;

import org.jspecify.annotations.Nullable;

/**
 * Something stuck on a Project Map code card — its sticky notes ({@link ProjectMapStickyNotes}), its Structure column
 * ({@link ProjectMapCardStructure}) — that belongs to the card: it follows the card as it moves, resizes and zooms,
 * rises, hides and closes with it, and takes nothing from the open-card limit (review 3, D6). {@link MapCards} treats
 * every attachment alike for use, raising, focus, placement, connectors and closing; what is particular to each kind
 * stays on its class.
 *
 * <p>An attachment follows its card by itself (it listens to the card's place, size, zoom and visibility). When it has
 * moved it tells its owner, which redraws the connectors once for the pulse rather than once per change.
 */
interface CardAttachment {

    /** Which side of its card an attachment stands on. */
    enum Side {
        /** Beside the card's right edge, outside it. */
        RIGHT,
        /** Beside its left edge, outside it. */
        LEFT,
        /** Neither side has room in the panel: on the card itself, against its right edge. */
        OVER
    }

    /**
     * The side of a card, drawn from {@code cardLeft} to {@code cardRight} in a panel {@code panelWidth} wide, that has
     * room for something {@code width} wide beside it: {@code preferLeft} picks which side is tried first; on the card
     * when neither has room. Pure.
     */
    static Side side(double cardLeft, double cardRight, double width, double panelWidth, boolean preferLeft) {
        boolean right = panelWidth - cardRight >= width;
        boolean left = cardLeft >= width;
        if (preferLeft ? left : right) {
            return preferLeft ? Side.LEFT : Side.RIGHT;
        }
        if (preferLeft ? right : left) {
            return preferLeft ? Side.RIGHT : Side.LEFT;
        }
        return Side.OVER;
    }

    /** The card it is stuck on. */
    ProjectMapPreview card();

    /**
     * What it takes beside the card on {@code side}, in the card's unzoomed units: what else stands there goes past it.
     * Zero on the other sides, and while it stands over the card.
     */
    double reserved(Side side);

    /** Puts it above its card, which was just raised above the others. */
    void raise();

    /** Whether {@code node} (the keyboard's, usually) is inside it. */
    boolean contains(@Nullable Node node);

    /**
     * What it covers as drawn, {@code {x, y, w, h}} in the map area's pixels — the union of its parts — or null while
     * nothing of it is in sight. Card placement keeps clear of it as it does of a card.
     */
    double @Nullable [] bounds();

    /** Draws its own connectors (to its card) on the map's connector canvas, in the canvas's current stroke. */
    void drawConnectors(GraphicsContext g);

    /** Takes it off the card for good. */
    void dispose();
}
