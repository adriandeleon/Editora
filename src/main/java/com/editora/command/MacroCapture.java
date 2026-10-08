package com.editora.command;

import javafx.event.EventTarget;
import javafx.scene.input.KeyEvent;

/**
 * What the {@link KeyDispatcher} asks of the keyboard-macro machinery. The dispatcher is a scene filter, so
 * it is the one place that sees every key in the window; this is how it reports the ones a macro must
 * capture and how it learns to stand aside for the ones a replay sends.
 *
 * <p>{@link #mode} is the only call on the idle path (once per key event) and must not allocate.
 */
public interface MacroCapture {

    /** Nothing to do. */
    int IDLE = 0;
    /** A macro is being recorded: report keys and text. */
    int RECORDING = 1;
    /**
     * The event being dispatched was fired by a replay. The dispatcher must not look at it at all: treating
     * it as a chord could run a command, and even examining it cleared the flag that swallows the replay
     * chord's own typed character ({@code C-x e} then typed a stray "e").
     */
    int SYNTHETIC = 2;
    /** A long replay is between two slices: real keys are swallowed, and the cancel key stops it. */
    int REPLAYING = 3;

    int mode();

    /** {@link #RECORDING}: a real key press arrived (called before anything is done with it). */
    void keySeen();

    /** {@link #RECORDING}: text was typed at {@code target}. */
    void text(String chars, EventTarget target);

    /**
     * {@link #RECORDING}: a key press the dispatcher did not turn into a command is on its way to
     * {@code target} — an editing or navigation key, or a bound chord left to the focused control.
     */
    void key(KeyEvent event, EventTarget target);

    /** {@link #REPLAYING}: the user pressed Escape or the keymap's cancel chord. */
    void cancelReplay();
}
