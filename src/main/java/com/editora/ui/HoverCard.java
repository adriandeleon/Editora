package com.editora.ui;

import javafx.event.EventHandler;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.fxmisc.richtext.CodeArea;

/**
 * The LSP hover card's size and keys ({@link LspCoordinator}'s Show Documentation): as wide as its code needs, no
 * taller than part of the window, and scrolled from the keyboard. bash-language-server answers a command's hover
 * with its whole man page (8 KB for {@code ls}), which could be read only with the mouse wheel, its lines cut at
 * the card's right edge (Bash review C7).
 */
final class HoverCard {

    private HoverCard() {}

    /** Average advance of the hover's code font, per character (a slight over-estimate is harmless). */
    static final double CODE_CHAR_WIDTH = 8.4;

    /** Code-block chrome inside the hover: block padding, the scroll pane's inner padding, the scroll bar. */
    static final double CODE_CHROME = 72;

    /** Widest a hover popup's text column gets. */
    static final double MAX_WIDTH = 560;

    /** A man page's width: man lays its text out for 80 columns. */
    static final int MAN_COLUMNS = 80;

    /** Widest a card holding a man page gets: its 80 columns, so no line is cut at the right. */
    static final double MAN_MAX_WIDTH = MAN_COLUMNS * CODE_CHAR_WIDTH + CODE_CHROME;

    /**
     * Pure: the text-column width a hover needs to show its fenced code without a sideways scroll — the longest code
     * line in characters — clamped to {@link #maxWidth}; 0 without a code block.
     */
    static double codeWidth(String markdown) {
        int longest = com.editora.editor.markdown.CodeFences.longestLine(markdown);
        return longest == 0 ? 0 : Math.min(maxWidth(markdown), longest * CODE_CHAR_WIDTH + CODE_CHROME);
    }

    /**
     * Pure: the widest the card for {@code markdown} gets: {@link #MAX_WIDTH}, or a man page's 80 columns when it holds
     * one (a {@code ```man} fence, as bash-language-server sends a command's documentation).
     */
    static double maxWidth(String markdown) {
        return markdown != null && markdown.matches("(?s).*(^|\\R)\\s*```\\s*man\\s*(\\R.*|$)")
                ? MAN_MAX_WIDTH
                : MAX_WIDTH;
    }

    /** The tallest a hover popup gets in a window {@code windowHeight} tall: part of it, within fixed bounds. */
    static double maxHeight(double windowHeight) {
        double share = windowHeight > 0 ? windowHeight * 0.45 : 400;
        return Math.max(160, Math.min(480, share));
    }

    static double maxHeight(CodeArea area) {
        javafx.stage.Window w = area.getScene() == null ? null : area.getScene().getWindow();
        return maxHeight(w == null ? 0 : w.getHeight());
    }

    /**
     * The chords the card takes from the keymap while it is up ({@code KeyDispatcher.OWNED_CHORDS}): Emacs's page
     * keys scroll the card instead of the editor. Only while the keymap binds them to paging, so IntelliJ's
     * {@code C-v} stays Paste (and, moving the caret, closes the card).
     */
    static final java.util.Map<String, String> SCROLL_CHORDS =
            java.util.Map.of("C-v", "nav.pageDown", "M-v", "nav.pageUp");

    /** What one press does to the card: close it, scroll it a page, or nothing (the key is the editor's). */
    enum Key {
        CLOSE,
        PAGE_DOWN,
        PAGE_UP,
        NONE
    }

    /** Pure: what {@code code} with these modifiers does while the card is up. */
    static Key keyFor(KeyCode code, boolean control, boolean alt, boolean meta, boolean shift) {
        boolean plain = !control && !alt && !meta && !shift;
        if (code == KeyCode.ESCAPE && plain) {
            return Key.CLOSE;
        }
        if (code == KeyCode.PAGE_DOWN && plain || code == KeyCode.V && control && !alt && !meta && !shift) {
            return Key.PAGE_DOWN;
        }
        if (code == KeyCode.PAGE_UP && plain || code == KeyCode.V && alt && !control && !meta && !shift) {
            return Key.PAGE_UP;
        }
        return Key.NONE;
    }

    /**
     * The key filter for {@code area} while the card shows: Escape closes it ({@code close}), Page Down/Up (and
     * Emacs's {@code C-v}/{@code M-v}, handed over through {@link #SCROLL_CHORDS}) scroll {@code scroll} by a page.
     */
    static EventHandler<KeyEvent> keys(ScrollPane scroll, Runnable close) {
        return ev -> {
            switch (keyFor(ev.getCode(), ev.isControlDown(), ev.isAltDown(), ev.isMetaDown(), ev.isShiftDown())) {
                case CLOSE -> close.run();
                case PAGE_DOWN -> page(scroll, 1);
                case PAGE_UP -> page(scroll, -1);
                case NONE -> {
                    return;
                }
            }
            ev.consume();
        };
    }

    /** Scrolls {@code scroll} by most of its visible height, {@code direction} 1 down, -1 up. */
    static void page(ScrollPane scroll, int direction) {
        double content = scroll.getContent() == null
                ? 0
                : scroll.getContent().getLayoutBounds().getHeight();
        double view = scroll.getViewportBounds().getHeight();
        double range = content - view;
        if (range <= 0) {
            return;
        }
        double step = (view * 0.9) / range * (scroll.getVmax() - scroll.getVmin());
        scroll.setVvalue(Math.max(scroll.getVmin(), Math.min(scroll.getVmax(), scroll.getVvalue() + direction * step)));
    }

    /**
     * Shows {@code content} — the hover's rendered {@code markdown} — in a dismissable popup at {@code area}'s caret:
     * in a scroll pane no taller than part of the window, as wide as its code needs, closed on Escape, a click
     * elsewhere, caret movement or scrolling, paged with Page Down/Up. {@code onHidden} runs once it closes. The
     * editor's Show Documentation and a Project Map card's Quick Documentation both show it.
     */
    static javafx.stage.Popup show(CodeArea area, javafx.scene.Node content, String markdown, Runnable onHidden) {
        // In a scroll pane no taller than part of the window: a command's hover from bash-language-server is
        // its whole man page (8 KB for `ls`), which made a popup thousands of pixels tall with most of it
        // off screen and out of reach.
        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("lsp-hover-scroll");
        scroll.setFitToWidth(true); // prose wraps at the popup's width; code blocks scroll sideways themselves
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setFocusTraversable(false);
        scroll.setMaxWidth(maxWidth(markdown)); // a man page's 80 columns uncut (Bash review C7)
        scroll.setMaxHeight(maxHeight(area));
        // Wide enough for the signature: the popup sized itself to the docstring's prose, so Pyright's
        // `def helper(` block scrolled sideways under its own language tag (Python review B12).
        scroll.setMinWidth(codeWidth(markdown));
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(scroll);
        box.getStyleClass().add("lsp-hover-popup");
        box.setMaxWidth(maxWidth(markdown));
        box.getStylesheets()
                .addAll(
                        HoverCard.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm(),
                        HoverCard.class
                                .getResource("/com/editora/styles/syntax.css")
                                .toExternalForm());
        com.editora.editor.PopupSheets.followOwnerTheme(
                box, area.getScene() == null ? null : area.getScene().getWindow());

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true); // click outside / focus loss dismisses it
        popup.setConsumeAutoHidingEvents(false);
        // Escape is the key filter's below, which closes the card and consumes the key: the popup's own hide on
        // Escape let the key go on, so a Map card under it closed with it.
        popup.setHideOnEscape(false);
        popup.getContent().add(box);

        // Dismiss on Escape, caret movement, or scroll, page with Page Down/Up (and Emacs's C-v/M-v, which the
        // keymap hands over while it shows: C7) — all detached again when the popup hides.
        EventHandler<KeyEvent> esc = keys(scroll, popup::hide);
        javafx.beans.value.ChangeListener<Object> dismiss = (o, a, b) -> popup.hide();
        boolean ownChords =
                area.getProperties().putIfAbsent(com.editora.command.KeyDispatcher.OWNED_CHORDS, SCROLL_CHORDS) == null;
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(dismiss);
        area.estimatedScrollYProperty().addListener(dismiss);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            if (ownChords) {
                area.getProperties().remove(com.editora.command.KeyDispatcher.OWNED_CHORDS, SCROLL_CHORDS);
            }
            area.caretPositionProperty().removeListener(dismiss);
            area.estimatedScrollYProperty().removeListener(dismiss);
            onHidden.run();
        });

        LspPopups.showAtCaret(popup, area);
        scroll.setVvalue(0); // from its first line, whatever layout scrolled to (HTML review B8)
        return popup;
    }
}
