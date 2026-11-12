package com.editora.editor.completion;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;

import com.editora.editor.markdown.MarkdownRenderer;
import org.jspecify.annotations.Nullable;

/**
 * IntelliJ's "quick documentation" panel for completion: a focus-less {@link Popup} shown <b>beside</b> the
 * completion list (to its right, flipping left at the screen edge). It renders the selected item's
 * declaration (a wrapping monospace header) plus its documentation (markdown via {@link MarkdownRenderer})
 * when the server provides any. Its lifecycle is tied to the completion popup — the editor shows/hides it as
 * the selection changes and tears it down with the list. Focus-less matters: a popup gets its owner window's
 * keys first, so the content comes from {@link MarkdownRenderer#renderPopup}, where nothing is a Tab stop.
 * Lives in {@code editor/} (like {@link CompletionPopup}) so the package stays independent of {@code ui}.
 */
final class CompletionDocPopup {

    private static final double WIDTH = 420;
    /** Widest the panel grows for a long signature: a 66-character Pyright overload line, uncut. */
    static final double MAX_WIDTH = 680;
    /** Average advance of the panel's code font, per character, and the code block's padding and scroll bar. */
    private static final double CODE_CHAR_WIDTH = 9.0;

    private static final double CODE_CHROME = 72;

    /** The panel's width for the item now shown ({@link #widthFor}). */
    private double width = WIDTH;

    static final double MAX_HEIGHT = 320;
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
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        card.setMaxHeight(MAX_HEIGHT);
        // A Popup has its own scene that doesn't inherit the editor's stylesheets — attach both so the
        // markdown-preview + token rules resolve (the note-tooltip / hover-popup idiom).
        addCss("/com/editora/styles/app.css");
        addCss("/com/editora/styles/syntax.css");
        popup.getContent().add(card);
        popup.setAutoHide(false); // the editor owns its lifecycle (tied to the completion list)
        popup.setAutoFix(false);
        // Escape belongs to the editor's key filter, which closes the list and this panel together. Left at
        // the Popup default the panel hid itself and swallowed the key, so the list needed a second Escape.
        popup.setHideOnEscape(false);
    }

    private void addCss(String resource) {
        java.net.URL url = CompletionDocPopup.class.getResource(resource);
        if (url != null) {
            card.getStylesheets().add(com.editora.editor.UiZoom.sheet(url.toExternalForm()));
        }
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /**
     * Pure: the panel's width for documentation {@code markdown} — wide enough for its longest code line, as the hover
     * card is, between the usual {@link #WIDTH} and {@link #MAX_WIDTH}. Pyright's overload signatures were cut behind a
     * sideways scroll bar at 420 px (Python review 2, C106).
     */
    static double widthFor(@Nullable String markdown) {
        int longest = com.editora.editor.markdown.CodeFences.longestLine(markdown == null ? "" : markdown);
        return Math.max(WIDTH, Math.min(MAX_WIDTH, longest * CODE_CHAR_WIDTH + CODE_CHROME));
    }

    /**
     * Pure: the width to show a panel that wants {@code wanted}, with {@code right} and {@code left} free beside the
     * list: as wanted where it fits (right first, as before), else narrower beside the list rather than over it, down
     * to the usual {@link #WIDTH}; with no room for even that, the usual width, placed as before.
     */
    static double fitWidth(double wanted, double right, double left) {
        if (wanted <= right) {
            return wanted;
        }
        if (right >= WIDTH) {
            return right;
        }
        if (wanted <= left) {
            return wanted;
        }
        return left >= WIDTH ? left : Math.min(wanted, WIDTH);
    }

    /** As {@link #show(Window, Bounds, String, Node, String)} for documentation with no code block. */
    void show(Window owner, Bounds listBounds, String signature, @Nullable Node docNode) {
        show(owner, listBounds, signature, docNode, "");
    }

    /**
     * Shows the declaration {@code signature} (wrapping monospace header) and optional rendered
     * {@code docNode} beside the completion popup ({@code listBounds} in screen coordinates): to its
     * right, or flipped to its left when it would overflow the screen's visible bounds. Hides when there
     * is nothing to show. {@code markdown} (the documentation's source) sets its width.
     */
    void show(Window owner, Bounds listBounds, String signature, @Nullable Node docNode, @Nullable String markdown) {
        boolean hasSig = signature != null && !signature.isBlank();
        if (owner == null || listBounds == null || (!hasSig && docNode == null)) {
            hide();
            return;
        }
        com.editora.editor.PopupSheets.followOwnerTheme(card, owner); // dark code in a dark theme (B107)
        Rectangle2D screen = screenFor(listBounds);
        width = fitWidth(
                widthFor(markdown),
                screen.getMaxX() - listBounds.getMaxX() - GAP,
                listBounds.getMinX() - GAP - screen.getMinX());
        card.setPrefWidth(width);
        card.setMaxWidth(width);
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
        scroll.setVvalue(0); // each item's docs from their first line, not where the last item's were scrolled
        fitHeight(content);
        Rectangle2D vis = screenFor(listBounds);
        double x = listBounds.getMaxX() + GAP;
        if (x + width > vis.getMaxX()) {
            x = listBounds.getMinX() - GAP - width; // not enough room on the right → flip left
        }
        x = Math.max(vis.getMinX(), Math.min(x, vis.getMaxX() - width));
        double y = Math.max(vis.getMinY(), Math.min(listBounds.getMinY(), vis.getMaxY() - MAX_HEIGHT));
        if (popup.isShowing()) {
            popup.setAnchorX(x);
            popup.setAnchorY(y);
        } else {
            popup.show(owner, x, y);
        }
    }

    /**
     * Sizes the panel to {@code content} as laid out at the panel's own width, up to {@link #MAX_HEIGHT}. Left to
     * itself the scroll pane took the content's height at no particular width (one long unwrapped line per
     * paragraph), so the panel showed two or three lines of a wrapped description and cut the next one in half
     * (HTML review 2 C112).
     */
    private void fitHeight(VBox content) {
        card.applyCss();
        scroll.applyCss();
        content.applyCss();
        Insets cardInsets = card.getInsets();
        Insets scrollInsets = scroll.getInsets();
        double inner =
                width - cardInsets.getLeft() - cardInsets.getRight() - scrollInsets.getLeft() - scrollInsets.getRight();
        double chrome = cardInsets.getTop() + cardInsets.getBottom() + scrollInsets.getTop() + scrollInsets.getBottom();
        double wanted = Math.ceil(content.prefHeight(inner)); // past the maximum, it scrolls
        scroll.setPrefViewportHeight(Math.max(1, Math.min(wanted, MAX_HEIGHT - chrome)));
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
