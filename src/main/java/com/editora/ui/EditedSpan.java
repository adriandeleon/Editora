package com.editora.ui;

import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

/**
 * Where a document may differ from a baseline it was equal to, as the edits since then say (PERF-08, Pauta 6.0.1).
 * The characters before {@link #prefix()} and the last {@link #suffix()} characters were not touched by any edit,
 * so whether the document is back to its baseline is a comparison of the span between them, read from the
 * document, never of the whole text. Pure; the owner calls {@link #edited} for each change and {@link #reset} when
 * the baseline is taken from the document again.
 */
final class EditedSpan {

    /** Untouched characters at the start; {@link Integer#MAX_VALUE} while nothing was edited. */
    private int prefix = Integer.MAX_VALUE;

    /** Untouched characters at the end; {@link Integer#MAX_VALUE} while nothing was edited. */
    private int suffix = Integer.MAX_VALUE;

    /** The document is its baseline again: nothing is edited. */
    void reset() {
        prefix = Integer.MAX_VALUE;
        suffix = Integer.MAX_VALUE;
    }

    /**
     * An edit put {@code inserted} characters at {@code position} (whatever it removed), leaving the document
     * {@code lengthAfter} long.
     */
    void edited(int position, int inserted, int lengthAfter) {
        prefix = Math.min(prefix, Math.max(0, position));
        suffix = Math.min(suffix, Math.max(0, lengthAfter - position - inserted));
    }

    /** Whether no edit was seen since the last {@link #reset}. */
    boolean untouched() {
        return prefix == Integer.MAX_VALUE;
    }

    int prefix() {
        return prefix;
    }

    int suffix() {
        return suffix;
    }

    /**
     * Whether the document, {@code length} characters long, equals {@code baseline}. A different length answers at
     * once; otherwise only the edited span is read, through {@code slice} ({@code start}, {@code end}).
     */
    boolean equalsBaseline(@Nullable String baseline, int length, BiFunction<Integer, Integer, String> slice) {
        if (baseline == null || baseline.length() != length) {
            return false;
        }
        if (untouched()) {
            return true;
        }
        int from = Math.min(prefix, length);
        int to = Math.max(from, length - Math.min(suffix, length));
        String span = slice.apply(from, to);
        return span.length() == to - from && baseline.regionMatches(from, span, 0, span.length());
    }
}
