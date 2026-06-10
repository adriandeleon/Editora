package com.editora.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * A single, shared in-scene overlay host that shows one "card" node at a time, centered near the top of
 * the main window over a dim, click-to-dismiss backdrop. This replaces the per-component
 * {@link javafx.stage.Popup}s used by the command palette, file finder, switcher, branch popup, quick-open
 * pickers, etc. — a {@code Popup} is a separate native window that on Windows doesn't reliably take OS
 * keyboard focus ({@code requestFocus()} orphans focus and the keyboard goes dead). Living in the one main
 * scene, focus works on every platform (the find bar does the same).
 *
 * <p>One instance is owned by {@code MainController} and installed into the scene-root {@code StackPane}.
 * Each component supplies its content node + an {@code onShown} hook (to focus its input) and an
 * {@code onHidden} hook (to flip its own "showing" flag); the host owns the backdrop, focus capture and
 * restore, {@code Esc}/{@code C-g} dismissal, and a {@link #justHidden()} guard for click-to-toggle.
 */
public final class OverlayHost {

    private final Region backdrop = new Region();
    private final StackPane overlayRoot = new StackPane(backdrop);
    private final BooleanProperty showing = new SimpleBooleanProperty(false);

    private Node previousFocus;
    private Runnable onHidden;
    private long lastHiddenAt;

    public OverlayHost() {
        backdrop.getStyleClass().add("overlay-backdrop");
        // Pick across the full bounds even when the fill is (near-)transparent. A Region with a transparent
        // background is otherwise not pickable, so an outside click would pass straight through instead of
        // being caught here to dismiss the overlay. (Only pickable while the overlay is visible — it's
        // setVisible(false) when hidden, so the editor stays fully interactive.)
        backdrop.setPickOnBounds(true);
        // Dismiss on PRESS (not CLICK) and consume it: a click is press+release, so consuming the press
        // means the gesture that closes the overlay can't also reach the node behind it. In particular,
        // re-pressing the same status-bar segment that opened an anchored popup closes it cleanly — the
        // press is swallowed here, so the segment's own toggle handler never fires to reopen it.
        backdrop.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            hide();
            e.consume();
        });
        overlayRoot.setVisible(false); // hidden ⇒ not painted and not pickable, so the editor stays usable
        // Esc / C-g dismiss the overlay (the card's own handlers run after this capturing filter for
        // their action keys: Enter, arrows, C-n/C-p). C-g reaches us because the card sets
        // editora.ownsKeys, so the global KeyDispatcher leaves edit.cancel to the focused overlay.
        overlayRoot.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE
                    || (e.getCode() == KeyCode.G && (e.isControlDown() || e.isAltDown()))) {
                hide();
                e.consume();
            }
        });
    }

    /** Adds the overlay to the scene-root StackPane (hidden). Call once, after the scene exists. */
    public void install(StackPane sceneRoot) {
        sceneRoot.getChildren().add(overlayRoot);
    }

    /**
     * Shows {@code content} centered near the top over the backdrop.
     *
     * @param content  the card node (should set the {@code editora.ownsKeys} property so its nav chords
     *                 aren't hijacked by the global dispatcher)
     * @param onShown  run after the overlay is laid out — typically {@code input.requestFocus()}
     * @param onHidden run when the overlay is dismissed — typically the component clearing its own flag
     */
    /** Shows {@code content} centered near the top (no anchor). */
    public void show(Node content, Runnable onShown, Runnable onHidden) {
        show(content, null, false, onShown, onHidden);
    }

    /** Shows {@code content} with no anchor, either centered near the top (default) or in the middle of
     *  the overlay ({@code centered}). */
    public void show(Node content, boolean centered, Runnable onShown, Runnable onHidden) {
        show(content, null, centered, onShown, onHidden);
    }

    /** Anchored variant (positioned above {@code anchor}); never centered. */
    public void show(Node content, Node anchor, Runnable onShown, Runnable onHidden) {
        show(content, anchor, false, onShown, onHidden);
    }

    /**
     * Shows {@code content} as an overlay. When {@code anchor} is non-null the card is positioned just
     * <em>above</em> that node (e.g. a status-bar segment, so the dropdown "drops" from it); otherwise it
     * is centered near the top like the command palette, or in the vertical middle when {@code centered}.
     */
    private void show(Node content, Node anchor, boolean centered, Runnable onShown, Runnable onHidden) {
        // Replacing one overlay with another (e.g. palette → file finder): clear the previous component's
        // flag, but keep the original focus owner so dismissal returns to the editor, not the prior card.
        if (showing.get()) {
            runHidden();
        } else {
            previousFocus = overlayRoot.getScene() == null ? null : overlayRoot.getScene().getFocusOwner();
        }
        this.onHidden = onHidden;
        if (overlayRoot.getChildren().size() > 1) {
            overlayRoot.getChildren().remove(1, overlayRoot.getChildren().size());
        }
        content.setTranslateX(0);
        content.setTranslateY(0);
        if (anchor == null) {
            StackPane.setAlignment(content, centered ? Pos.CENTER : Pos.TOP_CENTER);
            StackPane.setMargin(content, centered ? Insets.EMPTY : new Insets(90, 0, 0, 0));
        }
        overlayRoot.getChildren().add(content);
        overlayRoot.setVisible(true);
        overlayRoot.toFront();
        showing.set(true);
        if (anchor != null) {
            // Force a CSS + layout pass so overlayRoot has its real height now (the card may be added this
            // pulse), then anchor the card. We pin the card's *bottom* via bottom-alignment + a bottom
            // margin rather than computing its top from a measured height: the message-log ListView sizes
            // its cells across several pulses, so a height-based position would land short on the first
            // show and clip the footer. With the bottom pinned, the layout engine grows the card upward
            // from a fixed bottom edge at its natural height — correct on the first show, no measuring.
            overlayRoot.applyCss();
            overlayRoot.layout();
            positionAbove(content, anchor);
            Platform.runLater(() -> positionAbove(content, anchor)); // re-pin after anchor bounds settle
        }
        if (onShown != null) {
            Platform.runLater(onShown); // after layout, so getCharacterBoundsOnScreen / focus work
        }
    }

    /** Pins {@code content}'s bottom-left just above {@code anchor} (scene coordinates) using StackPane
     *  bottom-alignment + margins, so the card's measured height is irrelevant. */
    private void positionAbove(Node content, Node anchor) {
        if (anchor.getScene() == null) {
            return;
        }
        javafx.geometry.Bounds a = anchor.localToScene(anchor.getBoundsInLocal());
        double gap = 4;
        double left = Math.max(4, a.getMinX());
        // BOTTOM_LEFT: a bottom margin of M places the card's bottom edge M above the overlay's bottom.
        // We want the card's bottom at the anchor's top minus a small gap, so M = overlayH - anchorTop + gap.
        double bottomMargin = Math.max(4, overlayRoot.getHeight() - a.getMinY() + gap);
        StackPane.setAlignment(content, Pos.BOTTOM_LEFT);
        StackPane.setMargin(content, new Insets(0, 0, bottomMargin, left));
    }

    public void hide() {
        if (!showing.get()) {
            return;
        }
        overlayRoot.setVisible(false);
        if (overlayRoot.getChildren().size() > 1) {
            overlayRoot.getChildren().remove(1, overlayRoot.getChildren().size());
        }
        showing.set(false);
        lastHiddenAt = System.currentTimeMillis();
        runHidden();
        if (previousFocus != null) {
            previousFocus.requestFocus(); // return typing to the editor
            previousFocus = null;
        }
    }

    private void runHidden() {
        Runnable h = onHidden;
        onHidden = null;
        if (h != null) {
            h.run();
        }
    }

    public boolean isShowing() {
        return showing.get();
    }

    /** True if the overlay hid within the last 250 ms — lets a status-bar click that auto-... er, that
     *  closed it act as a clean toggle (the click that dismisses via backdrop also re-fires the opener). */
    public boolean justHidden() {
        return System.currentTimeMillis() - lastHiddenAt < 250;
    }

    public BooleanProperty showingProperty() {
        return showing;
    }
}
