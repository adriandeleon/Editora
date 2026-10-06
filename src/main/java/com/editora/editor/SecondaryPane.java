package com.editora.editor;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javafx.collections.MapChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;

import org.fxmisc.richtext.CodeArea;

/**
 * The container of a split's second view: its scroll pane, its minimap, and a twin of everything the
 * primary pane draws over or beside its text (see {@link Followed}).
 */
final class SecondaryPane {

    /**
     * Something the primary pane draws for its view (an overlay over the text, a stripe beside it) that can
     * draw the same for a split's second view.
     */
    interface Followed {
        /**
         * A twin bound to {@code view}. From then on the original hands it every setting and all data it is
         * given itself, so the two panes cannot drift apart; the twin repaints on its own view's scrolling.
         */
        Node follower(CodeArea view);
    }

    /** Marks a node of the primary pane that lies exactly over its text (as opposed to docked at an edge). */
    static final String OVER_TEXT = "editora.overText";

    private final CodeArea view;
    private final Region scroll;
    private final AnchorPane pane;
    /** Primary node → its counterpart here; the two scroll panes and minimaps anchor the stacking order. */
    private final Map<Node, Node> twins = new IdentityHashMap<>();

    /**
     * Docks {@code minimap} on the right edge and lets {@code scroll} fill the rest ({@code EditorBuffer}
     * sets its right anchor to make room for the minimap). {@code primaryScroll} and {@code primaryMinimap}
     * are their counterparts in the primary pane.
     */
    SecondaryPane(CodeArea view, Region scroll, Node minimap, Node primaryScroll, Node primaryMinimap) {
        this.view = view;
        this.scroll = scroll;
        pane = new AnchorPane(scroll, minimap);
        AnchorPane.setTopAnchor(scroll, 0d);
        AnchorPane.setBottomAnchor(scroll, 0d);
        AnchorPane.setLeftAnchor(scroll, 0d);
        AnchorPane.setTopAnchor(minimap, 0d);
        AnchorPane.setBottomAnchor(minimap, 0d);
        AnchorPane.setRightAnchor(minimap, 0d);
        twins.put(primaryScroll, scroll);
        twins.put(primaryMinimap, minimap);
    }

    AnchorPane root() {
        return pane;
    }

    /**
     * Gives every {@link Followed} node of the primary pane that has none yet a twin here, stacked in the
     * same order. Called when the pane is built and again whenever the primary pane gains an overlay.
     */
    void follow(List<Node> primaryChildren) {
        Node below = scroll;
        for (Node node : primaryChildren) {
            Node twin = twins.get(node);
            if (twin == null && node instanceof Followed followed) {
                Node made = followed.follower(view);
                if (made instanceof Region overText && node.getProperties().containsKey(OVER_TEXT)) {
                    over(scroll, overText);
                } else {
                    dockLike(node, made);
                }
                pane.getChildren().add(pane.getChildren().indexOf(below) + 1, made);
                twins.put(node, made);
                twin = made;
            }
            if (twin != null) {
                below = twin;
            }
        }
    }

    /** Keeps {@code overlay} lying exactly over {@code text} (the scroll pane, wherever a minimap leaves it). */
    private static void over(Region text, Region overlay) {
        overlay.setManaged(false);
        text.boundsInParentProperty().addListener((obs, old, b) -> place(overlay, b));
        place(overlay, text.getBoundsInParent());
    }

    private static void place(Region overlay, Bounds b) {
        overlay.resizeRelocate(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }

    /**
     * Docks {@code twin} at the edge its {@code primary} is docked at, and moves it when the primary moves
     * (the stripes shift left as the minimap or a neighbouring stripe appears; both panes show the same ones).
     */
    private static void dockLike(Node primary, Node twin) {
        Runnable copy = () -> {
            AnchorPane.setTopAnchor(twin, AnchorPane.getTopAnchor(primary));
            AnchorPane.setBottomAnchor(twin, AnchorPane.getBottomAnchor(primary));
            AnchorPane.setLeftAnchor(twin, AnchorPane.getLeftAnchor(primary));
            AnchorPane.setRightAnchor(twin, AnchorPane.getRightAnchor(primary));
        };
        primary.getProperties().addListener((MapChangeListener<Object, Object>) change -> copy.run());
        copy.run();
    }

    /** Moves {@code view}'s caret to the start of {@code line} (0-based), scrolls there and focuses it. */
    static void jumpToLine(CodeArea view, int line) {
        int total = view.getParagraphs().size();
        if (total > 0) {
            view.moveTo(Math.max(0, Math.min(line, total - 1)), 0);
            view.requestFollowCaret();
            view.requestFocus();
        }
    }
}
