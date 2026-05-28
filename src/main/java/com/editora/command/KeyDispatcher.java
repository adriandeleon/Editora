package com.editora.command;

import java.util.Locale;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Translates key presses into chord tokens and dispatches them to commands via the keymap.
 * Supports multi-key Emacs chords (e.g. {@code C-x C-s}) using a pending-prefix buffer.
 */
public class KeyDispatcher {

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final Consumer<String> statusListener;

    private String pending = "";

    public KeyDispatcher(CommandRegistry registry, KeymapManager keymap, Consumer<String> statusListener) {
        this.registry = registry;
        this.keymap = keymap;
        this.statusListener = statusListener != null ? statusListener : s -> {
        };
    }

    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handle);
    }

    void handle(KeyEvent event) {
        String token = chord(event);
        if (token == null) {
            return; // a modifier key on its own
        }
        String sequence = pending.isEmpty() ? token : pending + " " + token;

        String commandId = keymap.commandFor(sequence);
        if (commandId != null) {
            event.consume();
            reset();
            registry.run(commandId);
            return;
        }

        if (keymap.isPrefix(sequence)) {
            event.consume();
            pending = sequence;
            statusListener.accept(sequence + " -");
            return;
        }

        if (!pending.isEmpty()) {
            // Mid-chord but no continuation matched: cancel the chord and swallow the key.
            event.consume();
            statusListener.accept(sequence + " is undefined");
            reset();
            return;
        }
        // A lone, unbound key: let it fall through so normal text input works.
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
