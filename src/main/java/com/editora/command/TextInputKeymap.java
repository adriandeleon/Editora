package com.editora.command;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;

/**
 * Brings the user's configured keybindings (Emacs today) to a plain {@link TextInputControl} — the note
 * editor's {@code TextArea}, and any other dialog text field. Such controls live in their own dialog scene
 * where the global {@link KeyDispatcher} isn't installed, so without this only JavaFX's built-in bindings
 * work (and chords like {@code C-a} mean "select all" rather than Emacs "line start").
 *
 * <p>A {@code KEY_PRESSED} filter builds the same chord token the dispatcher uses, resolves it through the
 * {@link KeymapManager}, and runs the matching caret-movement / basic-edit action (the {@code nav.*} and a
 * few {@code edit.*} command ids), consuming the event so the default binding doesn't also fire. Unmapped
 * keys fall through, so ordinary typing and Tab/Enter/Esc keep working. The caret math is pure and
 * unit-tested ({@link #lineStart}/{@link #lineEnd}/{@link #lineUp}/{@link #lineDown}/{@link #backToIndentation}).
 */
public final class TextInputKeymap {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private static final Map<String, Consumer<TextInputControl>> ACTIONS = actions();

    private TextInputKeymap() {
    }

    /** Installs configured-keymap caret movement + basic editing on {@code control}. */
    public static void install(TextInputControl control, KeymapManager keymap) {
        if (control == null || keymap == null) {
            return;
        }
        boolean[] consumed = {false};
        control.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            consumed[0] = false;
            String token = KeyDispatcher.chord(e);
            if (token == null) {
                return;
            }
            Consumer<TextInputControl> action = ACTIONS.get(keymap.commandFor(token));
            if (action != null) {
                action.accept(control);
                e.consume();
                consumed[0] = true;
            }
        });
        // Swallow the character that pairs with a handled press (e.g. macOS Option-f → "ƒ").
        control.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (consumed[0] || (IS_MAC && e.isAltDown())) {
                consumed[0] = false;
                e.consume();
            }
        });
    }

    private static Map<String, Consumer<TextInputControl>> actions() {
        Map<String, Consumer<TextInputControl>> m = new HashMap<>();
        m.put("nav.charForward", c -> c.positionCaret(Math.min(c.getLength(), c.getCaretPosition() + 1)));
        m.put("nav.charBackward", c -> c.positionCaret(Math.max(0, c.getCaretPosition() - 1)));
        m.put("nav.lineStart", c -> c.positionCaret(lineStart(c.getText(), c.getCaretPosition())));
        m.put("nav.lineEnd", c -> c.positionCaret(lineEnd(c.getText(), c.getCaretPosition())));
        m.put("nav.lineDown", c -> c.positionCaret(lineDown(c.getText(), c.getCaretPosition())));
        m.put("nav.lineUp", c -> c.positionCaret(lineUp(c.getText(), c.getCaretPosition())));
        m.put("nav.backToIndentation", c -> c.positionCaret(backToIndentation(c.getText(), c.getCaretPosition())));
        m.put("nav.wordForward", TextInputControl::endOfNextWord);
        m.put("nav.wordBackward", TextInputControl::previousWord);
        m.put("nav.docStart", c -> c.positionCaret(0));
        m.put("nav.docEnd", c -> c.positionCaret(c.getLength()));
        m.put("edit.deleteChar", TextInputKeymap::deleteChar);
        m.put("edit.killLine", TextInputKeymap::killLine);
        m.put("edit.killWord", c -> {
            c.selectEndOfNextWord();
            c.replaceSelection("");
        });
        m.put("edit.cut", TextInputControl::cut);
        m.put("edit.copy", TextInputControl::copy);
        m.put("edit.paste", TextInputControl::paste);
        m.put("edit.undo", c -> {
            if (c.isUndoable()) {
                c.undo();
            }
        });
        m.put("edit.redo", c -> {
            if (c.isRedoable()) {
                c.redo();
            }
        });
        return m;
    }

    private static void deleteChar(TextInputControl c) {
        if (c.getSelection().getLength() > 0) {
            c.replaceSelection("");
        } else if (c.getCaretPosition() < c.getLength()) {
            c.deleteText(c.getCaretPosition(), c.getCaretPosition() + 1);
        }
    }

    private static void killLine(TextInputControl c) {
        String t = c.getText();
        int p = c.getCaretPosition();
        int le = lineEnd(t, p);
        if (le > p) {
            c.deleteText(p, le); // kill to end of line
        } else if (p < t.length()) {
            c.deleteText(p, p + 1); // already at line end → delete the line break
        }
    }

    // ---- pure caret math (unit-tested) ----

    /** Offset of the first character of the line containing {@code caret}. */
    public static int lineStart(String text, int caret) {
        int c = clamp(caret, text);
        return text.lastIndexOf('\n', c - 1) + 1;
    }

    /** Offset just before the line break ending {@code caret}'s line (or end of text). */
    public static int lineEnd(String text, int caret) {
        int i = text.indexOf('\n', clamp(caret, text));
        return i < 0 ? text.length() : i;
    }

    /** Offset of the first non-whitespace character of {@code caret}'s line (Emacs {@code M-m}). */
    public static int backToIndentation(String text, int caret) {
        int i = lineStart(text, caret);
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Same column on the next line (clamped to that line's length); end of text on the last line. */
    public static int lineDown(String text, int caret) {
        int c = clamp(caret, text);
        int col = c - lineStart(text, c);
        int le = lineEnd(text, c);
        if (le >= text.length()) {
            return text.length();
        }
        int nextStart = le + 1;
        return Math.min(nextStart + col, lineEnd(text, nextStart));
    }

    /** Same column on the previous line (clamped); start of text on the first line. */
    public static int lineUp(String text, int caret) {
        int c = clamp(caret, text);
        int ls = lineStart(text, c);
        if (ls == 0) {
            return 0;
        }
        int col = c - ls;
        int prevEnd = ls - 1;
        return Math.min(lineStart(text, prevEnd) + col, prevEnd);
    }

    private static int clamp(int caret, String text) {
        return Math.max(0, Math.min(caret, text.length()));
    }
}
