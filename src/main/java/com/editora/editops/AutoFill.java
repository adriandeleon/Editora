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
        if (lineText == null || fillColumn < 1 || lineText.length() <= fillColumn) {
            return null;
        }
        int brk = -1;
        for (int i = Math.min(fillColumn, lineText.length() - 1); i >= 0; i--) {
            if (isSpaceOrTab(lineText.charAt(i))) {
                brk = i;
                break;
            }
        }
        if (brk < 0) {
            for (int i = fillColumn + 1; i < lineText.length(); i++) {
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
        return new Break(start, end - start, "\n" + (fillPrefix == null ? "" : fillPrefix));
    }
}
