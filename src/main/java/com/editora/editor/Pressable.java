package com.editora.editor;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * A node the app builds from a label or a pane that acts as a button. ACC-02 (Pauta 6.0.1): it takes the
 * {@code BUTTON} role and a screen reader presses it through {@link AccessibleAction#FIRE}, which runs the same
 * action as a primary click, and as Enter or Space once it has focus. {@link PressableLabel}, {@link PressableBox}
 * and {@link PressablePane} are the three shapes; each overrides {@code executeAccessibleAction} and calls
 * {@link #wire} from its constructor.
 *
 * <p>A pressable that is a Tab stop is reached from the keyboard (KBD-15); one that is not (a gutter mark, whose
 * action a command does from the keyboard) is still pressed by a screen reader.
 */
public final class Pressable {

    private Pressable() {}

    /** The node's action; {@link #press} runs it unless the node is disabled. */
    public interface Owner {
        Runnable action();
    }

    /** Whether a key press presses the node: an unmodified Enter or Space. Pure. */
    public static boolean isActivationKey(KeyCode code, boolean modified) {
        return !modified && (code == KeyCode.ENTER || code == KeyCode.SPACE);
    }

    /** Gives {@code node} the button role, the focus traversal asked for, and its click and key handlers. */
    static <N extends Node & Owner> void wire(N node, boolean tabStop) {
        node.setAccessibleRole(AccessibleRole.BUTTON);
        node.setFocusTraversable(tabStop);
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) { // not the end of a drag
                press(node);
                e.consume();
            }
        });
        node.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (isActivationKey(e.getCode(), e.isShortcutDown() || e.isAltDown() || e.isShiftDown())) {
                e.consume();
                press(node);
            }
        });
    }

    /** Runs the node's action, as a click, a key or a screen reader's press does. Nothing while disabled. */
    public static <N extends Node & Owner> void press(N node) {
        if (!node.isDisabled()) {
            node.action().run();
        }
    }

    /** The {@code executeAccessibleAction} of a pressable: {@code FIRE} presses it. Returns whether it handled. */
    static <N extends Node & Owner> boolean handled(N node, AccessibleAction action) {
        if (action == AccessibleAction.FIRE) {
            press(node);
            return true;
        }
        return false;
    }
}
