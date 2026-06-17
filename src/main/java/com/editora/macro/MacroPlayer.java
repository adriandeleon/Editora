package com.editora.macro;

import java.util.function.Consumer;

/**
 * Replays a {@link Macro} by dispatching each step, in order, a given number of times. Command steps go to
 * the {@code runCommand} callback (wired to {@code CommandRegistry::run}) and text steps to the
 * {@code typeText} callback (wired to the active buffer's typing path).
 *
 * <p>Holds a single re-entrancy guard: {@link #play} no-ops while a replay is already in progress, so a
 * macro that contains (or is bound to a key that triggers) its own replay runs once instead of recursing
 * forever. Pure — no toolkit dependency — and unit-tested.
 */
public final class MacroPlayer {

    private boolean playing;

    public boolean isPlaying() {
        return playing;
    }

    public void play(Macro macro, int times, Consumer<String> runCommand, Consumer<String> typeText) {
        if (playing || macro == null || times < 1) {
            return;
        }
        playing = true;
        try {
            for (int i = 0; i < times; i++) {
                for (MacroStep step : macro.steps()) {
                    if (step.isCommand()) {
                        runCommand.accept(step.value());
                    } else {
                        typeText.accept(step.value());
                    }
                }
            }
        } finally {
            playing = false;
        }
    }
}
