package com.editora.snippet;

import java.util.List;

/**
 * One tab stop in a parsed snippet: its number (0 is the final caret, {@code $0}), every offset range
 * it occupies in the expanded text (more than one = mirrors), and the initial placeholder text.
 *
 * <p>Ranges are {@code int[]{start, end}} offsets <em>relative to the snippet's inserted text</em>;
 * {@link SnippetSession} converts them to absolute document offsets and keeps them in sync as the user
 * edits. The first range is the "primary" field; the others mirror it.
 */
public record TabStop(int number, List<int[]> ranges, String placeholder, List<String> choices) {

    /** Convenience for a stop with no choice list. */
    public TabStop(int number, List<int[]> ranges, String placeholder) {
        this(number, ranges, placeholder, List.of());
    }

    /** The {@code $0} final-caret stop, which has no placeholder and ends the session. */
    public boolean isFinal() {
        return number == 0;
    }

    /** True for a {@code ${1|a,b,c|}} choice stop (shows a dropdown of {@link #choices()}). */
    public boolean hasChoices() {
        return choices != null && !choices.isEmpty();
    }
}
