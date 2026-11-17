package com.editora.editor;

import javafx.scene.AccessibleAction;

/** {@link Pressable} as a label ({@code Label}): a button to the keyboard and to a screen reader. */
public class PressableLabel extends javafx.scene.control.Label implements Pressable.Owner {

    private Runnable action;

    /** A pressable that runs {@code action}; {@code tabStop} false leaves it out of the Tab order. */
    public PressableLabel(Runnable action, boolean tabStop) {
        this.action = action;
        Pressable.wire(this, tabStop);
    }

    /** A Tab stop that runs {@code action}. */
    public PressableLabel(Runnable action) {
        this(action, true);
    }

    @Override
    public Runnable action() {
        return action;
    }

    /** Replaces what pressing it does. */
    public void setAction(Runnable action) {
        this.action = action;
    }

    /** Runs its action, as a click, Enter or Space, or a screen reader's press does. Nothing while disabled. */
    public void fire() {
        Pressable.press(this);
    }

    @Override
    public void executeAccessibleAction(AccessibleAction accessibleAction, Object... parameters) {
        if (!Pressable.handled(this, accessibleAction)) {
            super.executeAccessibleAction(accessibleAction, parameters);
        }
    }
}
