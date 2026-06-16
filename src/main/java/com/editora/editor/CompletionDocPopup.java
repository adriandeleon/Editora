package com.editora.editor;

import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;

/**
 * IntelliJ's "quick documentation" panel for completion: a focus-less {@link Popup} shown <b>beside</b> the
 * completion list (to its right, flipping left at the screen edge). It renders the selected item's
 * declaration (a wrapping monospace header) plus its documentation (markdown via {@link MarkdownRenderer})
 * when the server provides any. Its lifecycle is tied to the completion popup — the editor shows/hides it as
 * the selection changes and tears it down with the list. Lives in {@code editor/} (like
 * {@link CompletionPopup}) so the package stays independent of {@code ui}.
 */
final class CompletionDocPopup {

    private static final double WIDTH = 420;
    private static final double MAX_HEIGHT = 320;
    private static final double GAP = 4;

    private final Popup popup = new Popup();
    private final ScrollPane scroll = new ScrollPane();
    private final StackPane card = new StackPane(scroll);

    CompletionDocPopup() {
        scroll.getStyleClass().add("completion-doc-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Size to the content (so short docs get a small popup, not a fixed empty box), capped at a max.
        card.getStyleClass().add("completion-doc-popup");
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        card.setMaxHeight(MAX_HEIGHT);
        // A Popup has its own scene that doesn't inherit the editor's stylesheets — attach both so the
        // markdown-preview + token rules resolve (the note-tooltip / hover-popup idiom).
        addCss("/com/editora/styles/app.css");
        addCss("/com/editora/styles/syntax.css");
        popup.getContent().add(card);
        popup.setAutoHide(false); // the editor owns its lifecycle (tied to the completion list)
        popup.setAutoFix(false);
    }

    private void addCss(String resource) {
        java.net.URL url = CompletionDocPopup.class.getResource(resource);
        if (url != null) {
            card.getStylesheets().add(url.toExternalForm());
        }
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /**
     * Shows the declaration {@code signature} (wrapping monospace header) and optional rendered
     * {@code docNode} beside the completion popup ({@code listBounds} in screen coordinates): to its
     * right, or flipped to its left when it would overflow the screen's visible bounds. Hides when there
     * is nothing to show.
     */
    void show(Window owner, Bounds listBounds, String signature, Node docNode) {
        boolean hasSig = signature != null && !signature.isBlank();
        if (owner == null || listBounds == null || (!hasSig && docNode == null)) {
            hide();
            return;
        }
        VBox content = new VBox(6);
        content.getStyleClass().add("completion-doc-content");
        if (hasSig) {
            Label sig = new Label(signature.strip());
            sig.getStyleClass().add("completion-doc-signature");
            sig.setWrapText(true); // long signatures wrap instead of clipping
            sig.setMaxWidth(Double.MAX_VALUE);
            content.getChildren().add(sig);
        }
        if (docNode != null) {
            content.getChildren().add(docNode);
        }
        scroll.setContent(content);
        Rectangle2D vis = screenFor(listBounds);
        double x = listBounds.getMaxX() + GAP;
        if (x + WIDTH > vis.getMaxX()) {
            x = listBounds.getMinX() - GAP - WIDTH; // not enough room on the right → flip left
        }
        x = Math.max(vis.getMinX(), Math.min(x, vis.getMaxX() - WIDTH));
        double y = Math.max(vis.getMinY(), Math.min(listBounds.getMinY(), vis.getMaxY() - MAX_HEIGHT));
        if (popup.isShowing()) {
            popup.setAnchorX(x);
            popup.setAnchorY(y);
        } else {
            popup.show(owner, x, y);
        }
    }

    void hide() {
        if (popup.isShowing()) {
            popup.hide();
        }
        scroll.setContent(null);
    }

    /** The visible bounds of the screen the completion list sits on (multi-monitor aware). */
    private static Rectangle2D screenFor(Bounds b) {
        for (Screen s : Screen.getScreens()) {
            Rectangle2D vb = s.getVisualBounds();
            if (vb.intersects(b.getMinX(), b.getMinY(), Math.max(1, b.getWidth()), Math.max(1, b.getHeight()))) {
                return vb;
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }
}
