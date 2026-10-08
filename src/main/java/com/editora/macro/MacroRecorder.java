package com.editora.macro;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the interleaved stream of invoked commands, typed text and key presses while a macro is being
 * recorded. Three hooks feed it — a command-execution listener (→ {@link #recordCommand}), a typed-text hook
 * (→ {@link #recordText}) and a key hook (→ {@link #recordKey}) — all firing on the FX thread in event
 * order, so the recorded sequence preserves the order in which the user performed the actions.
 *
 * <p>Consecutive text for the same target coalesces into a single {@link MacroStep#TEXT} step (so a run of
 * typing is one step, not one per keystroke). Pure — no toolkit dependency — and unit-tested.
 */
public final class MacroRecorder {

    private boolean recording;
    private final List<MacroStep> steps = new ArrayList<>();
    /** The trailing text step's characters while it is still growing (avoids re-copying it per keystroke). */
    private StringBuilder tail;

    private boolean tailPrompt;

    public boolean isRecording() {
        return recording;
    }

    /** Begins a fresh recording, discarding any previously buffered steps. */
    public void start() {
        steps.clear();
        tail = null;
        recording = true;
    }

    /** Records an invoked command. No-op when not recording. */
    public void recordCommand(String commandId) {
        if (!recording || commandId == null) {
            return;
        }
        flush();
        steps.add(MacroStep.command(commandId));
    }

    /**
     * Records a key press as a {@link MacroKey} token. No-op when not recording. Breaks the text run, so
     * {@code x Backspace y} is three steps, in order.
     */
    public void recordKey(String keyToken, boolean prompt) {
        if (!recording || keyToken == null || keyToken.isBlank()) {
            return;
        }
        flush();
        steps.add(MacroStep.key(keyToken, prompt));
    }

    /** Records typed text, coalescing it into the trailing text step for the same target. */
    public void recordText(String chars, boolean prompt) {
        if (!recording || chars == null || chars.isEmpty()) {
            return;
        }
        if (tail != null && tailPrompt != prompt) {
            flush();
        }
        if (tail == null) {
            tail = new StringBuilder();
            tailPrompt = prompt;
        }
        tail.append(chars);
    }

    private void flush() {
        if (tail != null) {
            steps.add(MacroStep.text(tail.toString(), tailPrompt));
            tail = null;
        }
    }

    /** An immutable snapshot of what has been recorded so far. */
    public List<MacroStep> steps() {
        flush();
        return List.copyOf(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty() && tail == null;
    }

    /** Stops recording and returns the captured steps (the buffer is retained until the next {@link #start}). */
    public List<MacroStep> stop() {
        recording = false;
        return steps();
    }

    /** Stops recording and throws away what was captured. */
    public void cancel() {
        recording = false;
        steps.clear();
        tail = null;
    }
}
