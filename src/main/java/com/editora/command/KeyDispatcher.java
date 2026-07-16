package com.editora.command;

import java.util.Locale;
import java.util.function.Consumer;

import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Translates key presses into chord tokens and dispatches them to commands via the keymap.
 * Supports multi-key Emacs chords (e.g. {@code C-x C-s}) using a pending-prefix buffer.
 */
public class KeyDispatcher {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final Consumer<String> statusListener;
    /** Optional hook fed each literally-typed character that reaches the editor (for the macro recorder). */
    private Consumer<Character> typedListener;

    private String pending = "";
    /** True when the last KEY_PRESSED was consumed, so its paired KEY_TYPED is swallowed too. */
    private boolean consumedPress;
    /** Optional first-look hook: given the chord token + event target, returns true if it handled the
     *  key (then the event is consumed and dispatch stops). Used e.g. to let {@code M-g} close a
     *  focused tool window. Only consulted when no multi-key prefix is pending. */
    private java.util.function.BiPredicate<String, EventTarget> preDispatch;

    public KeyDispatcher(CommandRegistry registry, KeymapManager keymap, Consumer<String> statusListener) {
        this.registry = registry;
        this.keymap = keymap;
        this.statusListener = statusListener != null ? statusListener : s -> {};
    }

    /** Installs a first-look hook (see {@link #preDispatch}); may be null to clear. */
    public void setPreDispatch(java.util.function.BiPredicate<String, EventTarget> hook) {
        this.preDispatch = hook;
    }

    /**
     * Installs a hook fed each literally-typed character that reaches the editor (i.e. a {@code KEY_TYPED}
     * not swallowed as part of a command chord). Used by the macro recorder to capture typed text
     * interleaved with command invocations. May be null to clear.
     */
    public void setTypedListener(Consumer<Character> listener) {
        this.typedListener = listener;
    }

    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handle);
        scene.addEventFilter(KeyEvent.KEY_TYPED, this::handleTyped);
        if (!IS_MAC) {
            // Windows/Linux: a bare Alt (or an unbound Alt+<key>) is treated by the OS as menu/mnemonic
            // activation, which puts the native window into "menu mode" — that freezes KEY_TYPED and
            // breaks every Alt-based (M-) chord (the keymap is full of them: M-x, M-g, M-1…M-9, …). The
            // user-visible symptom is "most keybindings stopped working and the keyboard locks up until
            // restart". We consume bare Alt (press in handle(), release here) and any unbound plain-Alt
            // key (in handle()) so the toolkit never enters menu mode. AltGr (reported as Ctrl+Alt) is
            // never consumed, so international layouts keep composing characters.
            scene.addEventFilter(KeyEvent.KEY_RELEASED, KeyDispatcher::suppressMenuAlt);
        }
    }

    /** Consumes a bare {@code Alt} release so Windows doesn't enter system-menu mode on an Alt tap. */
    private static void suppressMenuAlt(KeyEvent event) {
        if (event.getCode() == KeyCode.ALT) {
            event.consume();
        }
    }

    /**
     * Whether <em>plain</em> Alt (Left-Alt as Meta, not AltGr) is active on a non-macOS platform — the
     * case where an otherwise-unhandled key must be consumed to avoid Windows menu activation. AltGr is
     * reported as Ctrl+Alt, so requiring Alt down <em>and</em> Ctrl up excludes it (international AltGr
     * typing and Ctrl+Alt chords are unaffected). macOS is never affected (Option = Meta). Pure — tested.
     */
    static boolean plainAltActive(boolean isMac, boolean altDown, boolean controlDown) {
        return !isMac && altDown && !controlDown;
    }

    /**
     * Swallows the character event that pairs with a key press we already handled, so a bound key
     * never also types a character. This matters when the command opened a modal dialog
     * ({@code showAndWait}): the press is consumed, but its KEY_TYPED is queued and delivered to the
     * editor after the dialog closes (e.g. the trailing {@code g} of {@code M-g g}). A bound Option/Meta
     * chord on macOS is already covered: {@code handle()} consumes it and sets {@code consumedPress}, so its
     * glyph ({@code M-f} => "ƒ") is swallowed here too. We must NOT swallow an <em>unbound</em> Option
     * character, though — that would break macOS Option-based accented/symbol input (é, ç, ∞, dead keys).
     */
    void handleTyped(KeyEvent event) {
        if (consumedPress) {
            consumedPress = false;
            event.consume();
            return;
        }
        // A genuine character reaching the editor — feed it to the macro recorder (if any).
        if (typedListener != null) {
            String s = event.getCharacter();
            if (s != null) {
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (isRecordableChar(c)) {
                        typedListener.accept(c);
                    }
                }
            }
        }
    }

    /** A typed char worth recording in a macro: printable, or one of tab / newline / carriage-return. */
    private static boolean isRecordableChar(char c) {
        return (c >= 0x20 && c != 0x7F) || c == '\t' || c == '\n' || c == '\r';
    }

    void handle(KeyEvent event) {
        consumedPress = false;
        // Bare Alt: consume so Windows can't enter menu mode (which freezes the keyboard). Plain Alt
        // only — AltGr (Ctrl+Alt) is left alone (see plainAltActive).
        if (event.getCode() == KeyCode.ALT && plainAltActive(IS_MAC, event.isAltDown(), event.isControlDown())) {
            event.consume();
            return;
        }
        String token = chord(event);
        if (token == null) {
            return; // a modifier key on its own
        }
        if (pending.isEmpty() && preDispatch != null && preDispatch.test(token, event.getTarget())) {
            event.consume();
            consumedPress = true; // swallow the paired KEY_TYPED
            return;
        }
        String sequence = pending.isEmpty() ? token : pending + " " + token;

        String commandId = keymap.commandFor(sequence);
        boolean prefix = keymap.isPrefix(sequence);

        // A window that owns its keys (e.g. a tool window) handles only the editor navigation/edit
        // chords it repurposes for local navigation (C-n/C-p, C-f/C-b, …) — those are left to it.
        // Everything else stays global so jump/window commands (M-x, M-1, M-g …) and prefixes (C-x …)
        // work even while a tool window is focused.
        if (pending.isEmpty() && !prefix && isEditorContext(commandId) && ownsKeys(event.getTarget())) {
            return; // let the focused window handle this editor-context key
        }

        if (commandId != null) {
            event.consume();
            consumedPress = true; // set before run(): a modal command defers the paired KEY_TYPED
            reset();
            registry.run(commandId);
            return;
        }

        if (prefix) {
            event.consume();
            consumedPress = true;
            pending = sequence;
            statusListener.accept(sequence + " -");
            return;
        }

        if (!pending.isEmpty()) {
            // Mid-chord but no continuation matched: cancel the chord and swallow the key.
            event.consume();
            consumedPress = true;
            statusListener.accept(sequence + " is undefined");
            reset();
            return;
        }
        // Windows/Linux: an UNBOUND plain-Alt chord (e.g. M-n with no binding) must still be consumed.
        // If it falls through, Windows treats the Alt+<key> as a menu mnemonic, enters native menu mode,
        // and the keyboard freezes app-wide (mouse still works) until restart — the reported bug. AltGr
        // (Ctrl+Alt) is excluded by plainAltActive, so international layouts keep composing characters.
        if (plainAltActive(IS_MAC, event.isAltDown(), event.isControlDown())) {
            event.consume();
            consumedPress = true;
            return;
        }
        // A lone, unbound key: let it fall through so normal text input works.
    }

    /**
     * True if the event's target (or any ancestor) opts out of global key dispatch via the
     * {@code editora.ownsKeys} node property. Such components (e.g. the Structure tool window)
     * implement their own keyboard handling and must receive raw key events, including bound chords.
     */
    /**
     * Editor-context commands — caret movement and text manipulation — are the only bound chords a
     * focused key-owning window swallows (it reuses them for its own navigation). Jump/window/view
     * commands stay global. Keyed by id prefix: {@code nav.*} (caret) and {@code edit.*} (text).
     */
    static boolean isEditorContext(String commandId) {
        return commandId != null && (commandId.startsWith("nav.") || commandId.startsWith("edit."));
    }

    private static boolean ownsKeys(EventTarget target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (Boolean.TRUE.equals(node.getProperties().get("editora.ownsKeys"))) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void reset() {
        if (!pending.isEmpty()) {
            pending = "";
        }
    }

    /** Builds a chord token like {@code C-x}, {@code M-w}, {@code C-S-p}; null for modifier-only events. */
    public static String chord(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (event.isControlDown()) {
            sb.append("C-");
        }
        if (event.isAltDown()) {
            sb.append("M-");
        }
        if (event.isMetaDown()) {
            sb.append("Cmd-");
        }
        if (event.isShiftDown()) {
            sb.append("S-");
        }
        sb.append(keyName(code));
        return sb.toString();
    }

    private static String keyName(KeyCode code) {
        if (code.isLetterKey()) {
            return code.getName().toLowerCase(Locale.ROOT);
        }
        if (code.isDigitKey()) {
            // Main-row digits: getName() is "0".."9". Numpad digits: "Numpad 6" — take the trailing digit so
            // it maps to "6" (matches the M-1…M-9 chords) instead of an unmatchable, space-containing token.
            String name = code.getName();
            return name.length() == 1 ? name : name.substring(name.length() - 1);
        }
        return switch (code) {
            case SPACE -> "space";
            case SLASH -> "/";
            case BACK_SLASH -> "\\";
            case PERIOD -> ".";
            case COMMA -> ",";
            case SEMICOLON -> ";";
            case MINUS -> "-";
            case EQUALS -> "=";
            case OPEN_BRACKET -> "[";
            case CLOSE_BRACKET -> "]";
            case ENTER -> "enter";
            case TAB -> "tab";
            case BACK_SPACE -> "backspace";
            case DELETE -> "delete";
            case ESCAPE -> "escape";
            case LEFT -> "left";
            case RIGHT -> "right";
            case UP -> "up";
            case DOWN -> "down";
            case HOME -> "home";
            case END -> "end";
            case PAGE_UP -> "pageup";
            case PAGE_DOWN -> "pagedown";
            default -> code.getName().toLowerCase(Locale.ROOT).replace(' ', '-');
        };
    }
}
