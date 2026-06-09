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
        backdrop.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> hide());
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
        show(content, null, onShown, onHidden);
    }

    /**
     * Shows {@code content} as an overlay. When {@code anchor} is non-null the card is positioned just
     * <em>above</em> that node (e.g. a status-bar segment, so the dropdown "drops" from it); otherwise it
     * is centered near the top like the command palette.
     */
    public void show(Node content, Node anchor, Runnable onShown, Runnable onHidden) {
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
            StackPane.setAlignment(content, Pos.TOP_CENTER);
            StackPane.setMargin(content, new Insets(90, 0, 0, 0));
        } else {
            StackPane.setAlignment(content, Pos.TOP_LEFT);
            StackPane.setMargin(content, Insets.EMPTY);
        }
        overlayRoot.getChildren().add(content);
        overlayRoot.setVisible(true);
        overlayRoot.toFront();
        showing.set(true);
        if (anchor != null) {
            Platform.runLater(() -> positionAbove(content, anchor)); // needs the card's measured height
        }
        if (onShown != null) {
            Platform.runLater(onShown); // after layout, so getCharacterBoundsOnScreen / focus work
        }
    }

    /** Positions {@code content}'s bottom-left just above {@code anchor} (scene coordinates), clamped on-screen. */
    private void positionAbove(Node content, Node anchor) {
        if (anchor.getScene() == null) {
            return;
        }
        javafx.geometry.Bounds a = anchor.localToScene(anchor.getBoundsInLocal());
        double cardH = content.getLayoutBounds().getHeight();
        double x = Math.max(4, a.getMinX());
        double y = Math.max(4, a.getMinY() - cardH - 4);
        content.setTranslateX(x);
        content.setTranslateY(y);
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
