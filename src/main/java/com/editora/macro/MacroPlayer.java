package com.editora.macro;

import java.util.function.Consumer;

/**
 * Replays a {@link Macro} by dispatching each step, in order, a given number of times. Command steps go to
 * the {@code runCommand} callback (wired to {@code CommandRegistry::run}), text steps to the
 * {@code typeText} callback (wired to the active buffer's typing path), and key steps to {@code pressKey}
 * (wired to the active buffer's key-press path).
 *
 * <p>Holds a single re-entrancy guard: {@link #play} no-ops while a replay is already in progress, so a
 * macro that contains (or is bound to a key that triggers) its own replay runs once instead of recursing
 * forever — and reports that it did nothing, so the caller doesn't claim success. Pure — no toolkit
 * dependency — and unit-tested.
 */
public final class MacroPlayer {

    private boolean playing;

    public boolean isPlaying() {
        return playing;
    }

    /** Replays {@code macro} {@code times} times. Returns false when it did nothing (nested replay / no macro). */
    public boolean play(
            Macro macro, int times, Consumer<String> runCommand, Consumer<String> typeText, Consumer<String> pressKey) {
        if (playing || macro == null || times < 1) {
            return false;
        }
        playing = true;
        try {
            for (int i = 0; i < times; i++) {
                for (MacroStep step : macro.steps()) {
                    if (step.isCommand()) {
                        runCommand.accept(step.value());
                    } else if (step.isKey()) {
                        pressKey.accept(step.value());
                    } else {
                        typeText.accept(step.value());
                    }
                }
            }
        } finally {
            playing = false;
        }
        return true;
    }
}
