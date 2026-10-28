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
        if (markdown == null) {
            return 0;
        }
        int longest = 0;
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            String t = line.strip();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                longest = Math.max(longest, line.length());
            }
        }
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
}
