package com.editora.text;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Pure: whether a file opens in View mode because of its shape, and why. Three shapes are slow or risky to type
 * into: a very large file and one with a 64K-character line both run without undo, and a minified or generated
 * bundle (many long lines) lays out a long paragraph again on every keystroke. Each opens for reading, with
 * Enable Editing one click away, so a stray key in {@code dist/bundle.min.js} neither stalls the window nor
 * lands where it cannot be undone (JS/TS review E104).
 */
public final class LongLineFiles {

    /** Why the file opened in View mode. */
    public enum Reason {
        LARGE,
        LONG_LINE,
        MINIFIED
    }

    /** A file must be at least this long to count as minified: a small one with a few long lines types fine. */
    static final long MINIFIED_MIN_CHARS = 100_000;

    /** ...and have lines this long on average: hand-written code averages under 50, a bundle thousands. */
    static final int MINIFIED_MIN_AVERAGE = 500;

    /** Prose and data whose long lines are paragraphs or records, not generated code. */
    private static final Set<String> PROSE =
            Set.of("markdown", "typst", "plaintext", "log", "csv", "tsv", "markwhen", "diff");

    private LongLineFiles() {}

    /** Whether a {@code chars}-character file of {@code lines} lines in {@code language} looks minified. */
    public static boolean looksMinified(long chars, int lines, String language) {
        return chars >= MINIFIED_MIN_CHARS
                && chars / Math.max(1, lines) >= MINIFIED_MIN_AVERAGE
                && !PROSE.contains(language);
    }

    /**
     * Why a freshly opened file starts in View mode, or {@code null} when it opens for editing. A truncated or
     * log load is excluded: the first is read-only for good, the second has its own viewer.
     */
    public static @Nullable Reason viewFirst(
            boolean large, boolean longLine, boolean truncated, boolean log, long chars, int lines, String language) {
        if (truncated || log) {
            return null;
        }
        if (large) {
            return Reason.LARGE;
        }
        if (longLine) {
            return Reason.LONG_LINE;
        }
        return looksMinified(chars, lines, language) ? Reason.MINIFIED : null;
    }
}
