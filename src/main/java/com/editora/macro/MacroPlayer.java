package com.editora.macro;

import java.util.ArrayDeque;

/**
 * The replay cursor: which step of which macro comes next. A replay is a stack of frames — the macro the
 * user asked for at the bottom, and above it every saved macro a step of the one below invoked — so a macro
 * that runs another macro replays it in place and then carries on. The caller owns the loop
 * ({@link #peek}, deliver, {@link #advance}), which is what lets it pause between steps for a prompt to take
 * the focus or to give the window a turn.
 *
 * <p>Nesting used to be refused outright by a single "already playing" flag, so a recorded
 * {@code macro.run.<id>} step was silently skipped on replay. It is now bounded instead: {@link #push}
 * refuses a macro that is already on the stack (it would never end) and anything deeper than
 * {@link #MAX_DEPTH}. Pure — no toolkit dependency — and unit-tested.
 */
public final class MacroPlayer {

    /** How deep macros may invoke macros. Far beyond any hand-built composition; a cycle is caught sooner. */
    public static final int MAX_DEPTH = 16;

    /** The outcome of asking to run a macro from inside a replay. */
    public enum Push {
        OK,
        /** Nothing to play: no macro, no steps, or a count below one. */
        EMPTY,
        /** The macro is already running further down the stack — it runs itself, directly or not. */
        CYCLE,
        TOO_DEEP
    }

    private static final class Frame {
        final String key;
        final Macro macro;
        final int times;
        int iteration;
        int index;

        Frame(String key, Macro macro, int times) {
            this.key = key;
            this.macro = macro;
            this.times = times;
        }
    }

    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private boolean playing;
    private int rootTimes;
    private int rootIteration;

    /** True from {@link #begin} until {@link #end} — including while the caller is between steps. */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * Starts a replay of {@code macro}, {@code times} times. {@code key} identifies the macro for the cycle
     * check (its id; any fixed string for the unsaved last recording). Returns false, changing nothing, when
     * a replay is already running or there is nothing to play.
     */
    public boolean begin(String key, Macro macro, int times) {
        if (playing || macro == null || macro.isEmpty() || times < 1) {
            return false;
        }
        playing = true;
        rootTimes = times;
        rootIteration = 0;
        stack.push(new Frame(key, macro, times));
        return true;
    }

    /** Runs {@code macro} in place, as a step of the replay in progress. */
    public Push push(String key, Macro macro, int times) {
        if (!playing || macro == null || macro.isEmpty() || times < 1) {
            return Push.EMPTY;
        }
        for (Frame f : stack) {
            if (key != null && key.equals(f.key)) {
                return Push.CYCLE;
            }
        }
        if (stack.size() >= MAX_DEPTH) {
            return Push.TOO_DEEP;
        }
        stack.push(new Frame(key, macro, times));
        return Push.OK;
    }

    /** The step {@link #advance} will consume next, or null when the replay has run out. */
    public MacroStep peek() {
        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            if (f.index < f.macro.steps().size()) {
                return f.macro.steps().get(f.index);
            }
            f.iteration++;
            if (stack.size() == 1) {
                rootIteration = f.iteration;
            }
            if (f.iteration < f.times) {
                f.index = 0;
            } else {
                stack.pop();
            }
        }
        return null;
    }

    /** Consumes the step {@link #peek} returned. */
    public void advance() {
        Frame f = stack.peek();
        if (f != null) {
            f.index++;
        }
    }

    /** Ends the replay, finished or not. */
    public void end() {
        stack.clear();
        playing = false;
    }

    /** How many times the macro the user asked for is being played. */
    public int rootTimes() {
        return rootTimes;
    }

    /** How many of those passes are complete. */
    public int rootIteration() {
        return rootIteration;
    }
}
