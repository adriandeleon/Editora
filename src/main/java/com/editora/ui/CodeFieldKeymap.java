package com.editora.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editops.EmacsEdits;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.jspecify.annotations.Nullable;

/**
 * The user's configured keybindings on an <em>editable</em> {@link CodeArea} used as an input field — the debugger's
 * expression fields. {@link TextInputKeymap} does this for a {@code TextInputControl} and {@link ConsoleNav} for a
 * read-only console; an expression field is a RichTextFX area (so it can be syntax-highlighted) that is edited, so
 * it needs the editing actions too: under the Emacs keymap {@code C-a}/{@code C-e}, {@code C-f}/{@code C-b},
 * {@code M-f}/{@code M-b}, {@code C-d}, {@code C-k}, {@code M-d}, {@code M-DEL}, {@code C-w}/{@code M-w}/{@code
 * C-y}, {@code C-/}; under CUA the same command ids carry their usual chords.
 *
 * <p>A {@code KEY_PRESSED} filter resolves the chord through the keymap read at each press (a keymap switch applies
 * at once) and runs the action, consuming the press and the character it would type. Unmapped keys fall through to
 * RichTextFX. {@code nav.lineUp}/{@code nav.lineDown} on the first/last line go to {@link Hooks}: the field's
 * history, as in a shell. A killed region goes to the clipboard, so {@code C-k} then {@code C-y} moves text.
 */
final class CodeFieldKeymap {

    /** What the field does where the area's own motion stops. */
    interface Hooks {
        /** {@code nav.lineUp} on the first line (the previous history entry). */
        void lineUpAtTop();

        /** {@code nav.lineDown} on the last line (the next history entry). */
        void lineDownAtBottom();
    }

    private static final Map<String, BiConsumer<CodeArea, Hooks>> ACTIONS = actions();

    private CodeFieldKeymap() {}

    /** Installs {@code keymap}'s actions on {@code area}; the keymap is read at each press (null: none yet). */
    static void install(CodeArea area, Hooks hooks, java.util.function.Supplier<@Nullable KeymapManager> keymap) {
        boolean[] consumed = {false};
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            consumed[0] = false;
            if (e.isConsumed()) {
                consumed[0] = true; // an earlier filter (completion, Enter) had it: its character goes too
                return;
            }
            KeymapManager km = keymap.get();
            String token = km == null ? null : KeyDispatcher.chord(e);
            if (km == null || token == null) {
                return;
            }
            BiConsumer<CodeArea, Hooks> action = ACTIONS.get(km.commandFor(token));
            if (action != null && area.isEditable()) {
                action.accept(area, hooks);
                area.requestFollowCaret();
                e.consume();
                consumed[0] = true;
            }
        });
        area.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (TextInputKeymap.swallowTyped(consumed[0], KeymapManager.isMac(), e.isMetaDown(), e.isControlDown())) {
                e.consume();
            }
            consumed[0] = false;
        });
        area.addEventFilter(KeyEvent.KEY_RELEASED, e -> consumed[0] = false);
    }

    /** The command ids this keymap acts on (for tests and for the chords a field claims). */
    static java.util.Set<String> commands() {
        return ACTIONS.keySet();
    }

    private static Map<String, BiConsumer<CodeArea, Hooks>> actions() {
        Map<String, BiConsumer<CodeArea, Hooks>> m = new HashMap<>();
        m.put("nav.charForward", (a, h) -> a.nextChar(SelectionPolicy.CLEAR));
        m.put("nav.charBackward", (a, h) -> a.previousChar(SelectionPolicy.CLEAR));
        m.put("nav.lineStart", (a, h) -> a.moveTo(TextInputKeymap.lineStart(a.getText(), a.getCaretPosition())));
        m.put("nav.lineEnd", (a, h) -> a.moveTo(TextInputKeymap.lineEnd(a.getText(), a.getCaretPosition())));
        m.put(
                "nav.backToIndentation",
                (a, h) -> a.moveTo(TextInputKeymap.backToIndentation(a.getText(), a.getCaretPosition())));
        m.put("nav.lineUp", (a, h) -> {
            if (a.getCurrentParagraph() == 0) {
                h.lineUpAtTop();
            } else {
                a.moveTo(TextInputKeymap.lineUp(a.getText(), a.getCaretPosition()));
            }
        });
        m.put("nav.lineDown", (a, h) -> {
            if (a.getCurrentParagraph() >= a.getParagraphs().size() - 1) {
                h.lineDownAtBottom();
            } else {
                a.moveTo(TextInputKeymap.lineDown(a.getText(), a.getCaretPosition()));
            }
        });
        m.put("nav.wordForward", (a, h) -> a.moveTo(wordForward(a.getText(), a.getCaretPosition())));
        m.put("nav.wordBackward", (a, h) -> a.moveTo(wordBackward(a.getText(), a.getCaretPosition())));
        m.put("nav.docStart", (a, h) -> a.moveTo(0));
        m.put("nav.docEnd", (a, h) -> a.moveTo(a.getLength()));
        m.put("edit.deleteChar", (a, h) -> {
            if (a.getSelection().getLength() > 0) {
                a.replaceSelection("");
            } else if (a.getCaretPosition() < a.getLength()) {
                a.deleteNextChar();
            }
        });
        m.put("edit.killLine", (a, h) -> {
            int p = a.getCaretPosition();
            int end = killLineEnd(a.getText(), p);
            if (end > p) {
                kill(a, p, end);
            }
        });
        m.put("edit.killWord", (a, h) -> {
            int p = a.getCaretPosition();
            int end = wordForward(a.getText(), p);
            if (end > p) {
                kill(a, p, end);
            }
        });
        m.put("edit.backwardKillWord", (a, h) -> {
            EmacsEdits.Edit edit = EmacsEdits.backwardKillWord(a.getText(), a.getCaretPosition());
            if (edit != null) {
                kill(a, edit.from(), edit.to());
            }
        });
        m.put("edit.cut", (a, h) -> a.cut());
        m.put("edit.copy", (a, h) -> a.copy());
        m.put("edit.paste", (a, h) -> a.paste());
        m.put("edit.selectAll", (a, h) -> a.selectAll());
        m.put("edit.undo", (a, h) -> a.undo());
        m.put("edit.redo", (a, h) -> a.redo());
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Deletes {@code [from, to)} of {@code a}, putting the text on the clipboard for a later yank. */
    private static void kill(CodeArea a, int from, int to) {
        ClipboardContent content = new ClipboardContent();
        content.putString(a.getText(from, to));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        a.deleteText(from, to);
    }

    // ---- pure caret math (unit-tested) ----

    /** Where Emacs {@code C-k} at {@code caret} kills to: the line's end, or past its break when already there. */
    static int killLineEnd(String text, int caret) {
        int p = Math.clamp(caret, 0, text.length());
        int end = TextInputKeymap.lineEnd(text, p);
        return end > p ? end : Math.min(text.length(), p + 1);
    }

    /** Emacs {@code forward-word}: past any non-word characters, then to the end of the word after them. */
    static int wordForward(String text, int caret) {
        int i = Math.clamp(caret, 0, text.length());
        while (i < text.length() && !isWord(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && isWord(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Emacs {@code backward-word}: back over non-word characters, then to the start of the word before them. */
    static int wordBackward(String text, int caret) {
        int i = Math.clamp(caret, 0, text.length());
        while (i > 0 && !isWord(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && isWord(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
