package com.editora.macro;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the interleaved stream of invoked commands, literal typed characters and bare
 * editing/navigation key presses while a macro is being recorded. Three hooks feed it — a command-execution
 * listener (→ {@link #recordCommand}), a typed-character hook (→ {@link #recordChar}) and a key hook
 * (→ {@link #recordKey}) — all firing on the FX thread in event order, so the recorded sequence preserves
 * the order in which the user performed the actions.
 *
 * <p>Consecutive characters coalesce into a single {@link MacroStep#TEXT} step (so a run of typing is one
 * step, not one per keystroke). Pure — no toolkit dependency — and unit-tested.
 */
public final class MacroRecorder {

    private boolean recording;
    private final List<MacroStep> steps = new ArrayList<>();

    public boolean isRecording() {
        return recording;
    }

    /** Begins a fresh recording, discarding any previously buffered steps. */
    public void start() {
        steps.clear();
        recording = true;
    }

    /** Records an invoked command. No-op when not recording. */
    public void recordCommand(String commandId) {
        if (!recording || commandId == null) {
            return;
        }
        steps.add(MacroStep.command(commandId));
    }

    /**
     * Records a bare key press by {@code KeyCode} name (Backspace, Delete, an arrow, Home/End, …). No-op
     * when not recording. Breaks the text run, so {@code x Backspace y} is three steps, in order.
     */
    public void recordKey(String keyCodeName) {
        if (!recording || keyCodeName == null || keyCodeName.isBlank()) {
            return;
        }
        steps.add(MacroStep.key(keyCodeName));
    }

    /** Records a literally-typed character, coalescing it into the trailing text step. No-op when not recording. */
    public void recordChar(char c) {
        if (!recording) {
            return;
        }
        if (!steps.isEmpty()) {
            MacroStep last = steps.get(steps.size() - 1);
            if (last.isText()) {
                steps.set(steps.size() - 1, MacroStep.text(last.value() + c));
                return;
            }
        }
        steps.add(MacroStep.text(String.valueOf(c)));
    }

    /** An immutable snapshot of what has been recorded so far. */
    public List<MacroStep> steps() {
        return List.copyOf(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** Stops recording and returns the captured steps (the buffer is retained until the next {@link #start}). */
    public List<MacroStep> stop() {
        recording = false;
        return List.copyOf(steps);
    }
}
