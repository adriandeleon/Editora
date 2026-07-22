package com.editora.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.input.KeyEvent;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

/**
 * Brings the user's configured keybindings to a read-only console {@link CodeArea} — the Build Output,
 * Run, External Tools and Debug consoles. Those panels mark themselves {@code editora.ownsKeys}, so the
 * scene-level {@link KeyDispatcher} deliberately leaves every {@code nav.*}/{@code edit.*} chord to the
 * focused panel (see {@code KeyDispatcher.isEditorContext}) — but a console had nothing to hand them to,
 * so the Emacs scroll chords ({@code C-n}/{@code C-p}, {@code C-v}/{@code M-v}, {@code M-<}/{@code M->})
 * were silently swallowed and only the raw arrows/PageUp/PageDown RichTextFX handles natively worked.
 *
 * <p>The {@link CodeArea} analogue of {@link TextInputKeymap} (which does the same for a dialog's
 * {@code TextInputControl}): a {@code KEY_PRESSED} filter — running before RichTextFX's own InputMap —
 * builds the same chord token the dispatcher uses, resolves it through the {@link KeymapManager}, and
 * runs the matching motion, then scrolls to follow the caret. Only single-key chords resolve (a console
 * has no use for the {@code C-x …} prefixes), and an unmapped key falls through untouched, so the native
 * arrows/PageUp/PageDown and mouse selection keep working. Motions are caret moves rather than raw
 * scrolling so text selection + copy stay coherent; consoles that hide the caret still scroll, since
 * {@link CodeArea#requestFollowCaret()} moves the viewport either way.
 */
public final class ConsoleNav {

    private static final Map<String, Consumer<CodeArea>> ACTIONS = actions();

    private ConsoleNav() {}

    /** Installs the app-wide configured keymap's console navigation (no-op before the keymap is set). */
    public static void installShared(CodeArea console) {
        install(console, TextInputKeymap.sharedKeymap());
    }

    /** Installs {@code keymap}'s console navigation on {@code console}. */
    public static void install(CodeArea console, KeymapManager keymap) {
        if (console == null || keymap == null) {
            return;
        }
        boolean[] consumed = {false};
        console.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            consumed[0] = false;
            if (e.isConsumed()) {
                return;
            }
            String token = KeyDispatcher.chord(e);
            if (token == null) {
                return; // a modifier on its own
            }
            Consumer<CodeArea> action = ACTIONS.get(keymap.commandFor(token));
            if (action == null) {
                return; // unmapped: let RichTextFX's native handling run
            }
            action.accept(console);
            console.requestFollowCaret();
            e.consume();
            consumed[0] = true;
        });
        // Swallow the character that pairs with a handled press (e.g. macOS Option-v → "√").
        console.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (consumed[0]) {
                consumed[0] = false;
                e.consume();
            }
        });
    }

    private static Map<String, Consumer<CodeArea>> actions() {
        Map<String, Consumer<CodeArea>> m = new HashMap<>();
        m.put("nav.lineDown", a -> moveLine(a, 1));
        m.put("nav.lineUp", a -> moveLine(a, -1));
        m.put("nav.pageDown", a -> a.nextPage(SelectionPolicy.CLEAR));
        m.put("nav.pageUp", a -> a.prevPage(SelectionPolicy.CLEAR));
        m.put("nav.docStart", a -> a.start(SelectionPolicy.CLEAR));
        m.put("nav.docEnd", a -> a.end(SelectionPolicy.CLEAR));
        m.put("nav.lineStart", a -> a.lineStart(SelectionPolicy.CLEAR));
        m.put("nav.lineEnd", a -> a.lineEnd(SelectionPolicy.CLEAR));
        m.put("nav.charForward", a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1)));
        m.put("nav.charBackward", a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1)));
        m.put("edit.copy", CodeArea::copy);
        m.put("edit.selectAll", CodeArea::selectAll);
        m.put("edit.cancel", a -> a.deselect());
        return m;
    }

    /**
     * The tail of a console's append: trims to {@code maxChars}, then either scrolls to the new end or —
     * when the user had scrolled back ({@code follow} false) — puts the caret back where they left it.
     *
     * <p>The caret is the console's scroll anchor, and RichTextFX's {@code appendText} always leaves it at
     * the end of the inserted text, so a panel must decide <em>before</em> appending (was the caret at the
     * end?) and restore afterwards. Without this, scrolling back through a still-running build was useless:
     * the very next output line yanked the view to the tail again.
     *
     * @param caretBefore the caret offset captured before the append
     * @param follow      whether the console was tracking the tail (caret at the end) before the append
     */
    static void afterAppend(CodeArea console, int caretBefore, boolean follow, int maxChars) {
        int trimmed = 0;
        int len = console.getLength();
        if (len > maxChars) {
            trimmed = len - maxChars;
            console.deleteText(0, trimmed);
        }
        if (follow) {
            console.moveTo(console.getLength());
            console.requestFollowCaret();
        } else {
            console.moveTo(Math.max(0, Math.min(caretBefore - trimmed, console.getLength())));
        }
    }

    /** Same column on the line {@code delta} away, clamped to that line's length; a no-op at either end. */
    private static void moveLine(CodeArea a, int delta) {
        int target = a.getCurrentParagraph() + delta;
        if (target < 0 || target >= a.getParagraphs().size()) {
            return;
        }
        a.moveTo(target, Math.min(a.getCaretColumn(), a.getParagraphLength(target)));
    }
}
