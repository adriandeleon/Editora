package com.editora.editops;

/**
 * Emacs {@code auto-fill-mode}: break a line at a word boundary as it grows past the fill column while you
 * type. Pure and toolkit-free (mirroring {@link Filler}); the buffer calls {@link #compute} after each
 * insertion and applies the returned break as a single edit.
 *
 * <p>This is the break-as-you-type companion to {@link Filler#fillParagraph} ({@code M-q}): it inserts one
 * break per keystroke, never re-flowing the whole paragraph. The continuation prefix (leading indent, plus
 * a comment/quote marker in a comment) is reused from {@link Filler#fillPrefix}.
 */
public final class AutoFill {

    /** Replace the whitespace run {@code [at, at+removeLen)} of the line with {@code insert}. */
    public record Break(int at, int removeLen, String insert) {}

    private AutoFill() {}

    private static boolean isSpaceOrTab(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * The break for {@code lineText} (a single line, no newline), or {@code null} when it need not or cannot
     * be broken. Prefers the last whitespace at or before {@code fillColumn}; failing that, the first
     * whitespace after it (so an over-long first word still breaks eventually). Never breaks inside the
     * leading indentation — a break there would only produce an empty first line.
     */
    public static Break compute(String lineText, int fillColumn, String fillPrefix) {
        return compute(lineText, fillColumn, fillPrefix, fillPrefix);
    }

    /**
     * {@link #compute(String, int, String)} for a prose buffer: a list item ({@code * item}, {@code - item},
     * {@code 1. item}) wraps <em>under its text</em> — repeating the marker would turn the wrapped tail into
     * a second item.
     */
    public static Break computeProse(String lineText, int fillColumn, String lineComment) {
        if (lineText == null) {
            return null;
        }
        String[] prefixes = Filler.proseBreakPrefixes(lineText, lineComment);
        return compute(lineText, fillColumn, prefixes[0], prefixes[1]);
    }

    /** As {@link #compute(String, int, String)}, starting the wrapped tail with {@code continuation}. */
    static Break compute(String lineText, int fillColumn, String fillPrefix, String continuation) {
        if (lineText == null || fillColumn < 1 || lineText.length() <= fillColumn) {
            return null;
        }
        // Never break inside the fill prefix: the space after a "> " or "// " marker is not a word gap, and
        // breaking there would re-break the same line on every keystroke.
        int floor = fillPrefix != null && lineText.startsWith(fillPrefix) ? fillPrefix.length() : 0;
        int brk = -1;
        for (int i = Math.min(fillColumn, lineText.length() - 1); i >= floor; i--) {
            if (isSpaceOrTab(lineText.charAt(i))) {
                brk = i;
                break;
            }
        }
        if (brk < 0) {
            for (int i = Math.max(fillColumn + 1, floor); i < lineText.length(); i++) {
                if (isSpaceOrTab(lineText.charAt(i))) {
                    brk = i;
                    break;
                }
            }
        }
        if (brk < 0) {
            return null; // an unbreakable run (e.g. a long URL) — leave the line over-long, as Emacs does
        }
        int start = brk;
        while (start > 0 && isSpaceOrTab(lineText.charAt(start - 1))) {
            start--;
        }
        int end = brk + 1;
        while (end < lineText.length() && isSpaceOrTab(lineText.charAt(end))) {
            end++;
        }
        if (lineText.substring(0, start).isBlank()) {
            return null; // only whitespace before the break — nothing to keep on the first line
        }
        return new Break(start, end - start, "\n" + (continuation == null ? "" : continuation));
    }
}
