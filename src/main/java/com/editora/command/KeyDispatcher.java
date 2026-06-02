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

    private String pending = "";
    /** True when the last KEY_PRESSED was consumed, so its paired KEY_TYPED is swallowed too. */
    private boolean consumedPress;

    public KeyDispatcher(CommandRegistry registry, KeymapManager keymap, Consumer<String> statusListener) {
        this.registry = registry;
        this.keymap = keymap;
        this.statusListener = statusListener != null ? statusListener : s -> {
        };
    }

    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handle);
        scene.addEventFilter(KeyEvent.KEY_TYPED, this::handleTyped);
    }

    /**
     * Swallows the character event that pairs with a key press we already handled, so a bound key
     * never also types a character. This matters when the command opened a modal dialog
     * ({@code showAndWait}): the press is consumed, but its KEY_TYPED is queued and delivered to the
     * editor after the dialog closes (e.g. the trailing {@code g} of {@code M-g g}). On macOS we also
     * swallow any Option-produced character, since Option is the Meta key (e.g. {@code M-f} => "ƒ").
     */
    private void handleTyped(KeyEvent event) {
        if (consumedPress || (IS_MAC && event.isAltDown())) {
            consumedPress = false;
            event.consume();
        }
    }

    void handle(KeyEvent event) {
        consumedPress = false;
        String token = chord(event);
        if (token == null) {
            return; // a modifier key on its own
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
    static String chord(KeyEvent event) {
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
            return code.getName();
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
