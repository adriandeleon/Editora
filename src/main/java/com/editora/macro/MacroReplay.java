package com.editora.macro;

/**
 * The decisions a macro replay makes that do not need the toolkit: how often to repeat, and whether the
 * window is ready for the next step. Pure and unit-tested; the loop that acts on them lives with the window.
 */
public final class MacroReplay {

    private MacroReplay() {}

    /** Whether the next step can be delivered now, or what the replay is waiting for. */
    public enum Readiness {
        READY,
        /** A step recorded in a prompt, and the keyboard focus is not in one yet. */
        WAIT_FOR_PROMPT,
        /** A step recorded in the document, and an overlay is still in front of it. */
        WAIT_FOR_EDITOR
    }

    /**
     * Whether a text or key step can be delivered.
     *
     * <p>A <b>prompt</b> step goes to the control that has the focus, so the focus must have left the
     * document — and, when an overlay card is up, must have arrived inside it: a card takes the focus one
     * event-loop turn after it is shown, and until then the focus owner is still whatever opened it.
     * Delivering early would type the prompt's text into the document, which is the bug this replaces.
     *
     * <p>A <b>document</b> step goes to the editor whatever has the focus (the find bar may keep it), but
     * not while an overlay covers the editor: the user could not have typed it there.
     */
    public static Readiness readiness(
            boolean promptStep,
            boolean hasFocusOwner,
            boolean focusInEditor,
            boolean overlayShowing,
            boolean focusInOverlay) {
        if (promptStep) {
            if (!hasFocusOwner || focusInEditor || (overlayShowing && !focusInOverlay)) {
                return Readiness.WAIT_FOR_PROMPT;
            }
            return Readiness.READY;
        }
        return overlayShowing ? Readiness.WAIT_FOR_EDITOR : Readiness.READY;
    }

    /** {@code value} held to {@code 1..max}. */
    public static int clampTimes(long value, int max) {
        return (int) Math.max(1, Math.min(value, max));
    }

    /** The repeat count typed into the prompt: a number held to {@code 1..max}; anything else is 1. */
    public static int parseTimes(String s, int max) {
        try {
            return clampTimes(Long.parseLong(s == null ? "" : s.trim()), max);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
