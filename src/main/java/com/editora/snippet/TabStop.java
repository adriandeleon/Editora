package com.editora.snippet;

import java.util.Collections;
import java.util.List;

/**
 * One tab stop in a parsed snippet: its number (0 is the final caret, {@code $0}), every offset range
 * it occupies in the expanded text (more than one = mirrors), and the initial placeholder text.
 *
 * <p>Ranges are {@code int[]{start, end}} offsets <em>relative to the snippet's inserted text</em>;
 * {@link SnippetSession} converts them to absolute document offsets and keeps them in sync as the user
 * edits. The {@link #primaryIndex()} range is the editable "field"; the others mirror it.
 *
 * <p>{@link #transforms()} runs parallel to {@link #ranges()}: a non-null entry is a regex
 * <em>transform</em> occurrence ({@code ${1/re/fmt/flags}}) whose text is derived from the field's value
 * rather than copied verbatim. The primary occurrence's entry is always null. A stop with no transforms
 * uses an all-null list, so nothing outside a transform-bearing snippet changes.
 *
 * <p>{@code primaryIndex} exists because the value-defining occurrence need not be first in the document —
 * in {@code foreach (${1/.../} in ${1:collection})} the transform occurrence is emitted before the
 * placeholder that gives the field its value, so the editable field is {@code ranges().get(1)}.
 */
public record TabStop(
        int number,
        List<int[]> ranges,
        String placeholder,
        List<String> choices,
        List<SnippetTransform> transforms,
        int primaryIndex) {

    /** Full stop with no transforms and the first range editable. */
    public TabStop(int number, List<int[]> ranges, String placeholder, List<String> choices) {
        this(number, ranges, placeholder, choices, nulls(ranges.size()), 0);
    }

    /** Convenience for a stop with no choice list and no transforms. */
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

    /** The transform for occurrence {@code i}, or null when that occurrence copies the value verbatim. */
    public SnippetTransform transformAt(int i) {
        return transforms == null || i >= transforms.size() ? null : transforms.get(i);
    }

    /** True when any occurrence is a regex transform (so the session must derive, not just mirror). */
    public boolean hasTransforms() {
        if (transforms == null) {
            return false;
        }
        for (SnippetTransform t : transforms) {
            if (t != null) {
                return true;
            }
        }
        return false;
    }

    private static List<SnippetTransform> nulls(int n) {
        return Collections.nCopies(n, null);
    }
}
