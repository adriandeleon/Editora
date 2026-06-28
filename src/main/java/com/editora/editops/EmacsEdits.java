package com.editora.editops;

import java.util.Locale;

/**
 * Pure, unit-tested implementations of the Emacs word/whitespace/line editing commands that produce a
 * minimal {@link Edit} (a replacement span + the resulting caret) from the current text + caret, or
 * {@code null} for a no-op. No toolkit dependency; the controller applies the {@code Edit} to the
 * active {@code CodeArea} (mirroring {@link Transposer} / {@link LineOps}).
 *
 * <p>Covers: {@code backward-kill-word} (M-DEL), {@code upcase/downcase/capitalize-word}
 * (M-u/M-l/M-c) + the region variants {@code upcase/downcase-region} (C-x C-u/C-x C-l),
 * {@code delete-indentation} (M-^, join-line), {@code delete-horizontal-space} (M-\),
 * {@code just-one-space} (M-SPC), {@code delete-blank-lines} (C-x C-o), {@code open-line} (C-o),
 * {@code kill-whole-line} (C-S-DEL), and {@code zap-to-char} (M-z).
 */
public final class EmacsEdits {

    /** Replace {@code [from, to)} with {@code replacement}, then place the caret at {@code caret}. */
    public record Edit(int from, int to, String replacement, int caret) {}

    private EmacsEdits() {}

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isHorizWs(char c) {
        return c == ' ' || c == '\t';
    }

    /** Start of the line containing {@code pos} (index just after the previous newline, or 0). */
    static int lineStart(String text, int pos) {
        int i = Math.min(pos, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** End of the line containing {@code pos} (index of the next newline at/after {@code pos}, or length). */
    static int lineEnd(String text, int pos) {
        int i = Math.max(0, pos);
        int n = text.length();
        while (i < n && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** Whether the line {@code [start, end)} (newline excluded) is empty or whitespace-only. */
    private static boolean isBlankLine(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!isHorizWs(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Emacs {@code backward-kill-word} (`M-DEL`): delete the word before the caret (skipping any
     * non-word characters between the caret and that word, like {@code backward-word}). No-op at the
     * start of the buffer.
     */
    public static Edit backwardKillWord(String text, int caret) {
        int end = clamp(caret, text.length());
        int i = end;
        while (i > 0 && !isWord(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && isWord(text.charAt(i - 1))) {
            i--;
        }
        if (i == end) {
            return null;
        }
        return new Edit(i, end, "", i);
    }

    /**
     * Emacs {@code upcase-word} (`M-u`): upper-case from the caret to the end of the following word,
     * leaving the caret after it. No-op when there is no word ahead.
     */
    public static Edit upcaseWord(String text, int caret) {
        return caseWord(text, caret, CaseOp.UPPER);
    }

    /** Emacs {@code downcase-word} (`M-l`): like {@link #upcaseWord} but lower-cases. */
    public static Edit downcaseWord(String text, int caret) {
        return caseWord(text, caret, CaseOp.LOWER);
    }

    /**
     * Emacs {@code capitalize-word} (`M-c`): capitalize the following word — the first letter
     * upper-case, the rest lower-case — leaving the caret after it. No-op when there is no word ahead.
     */
    public static Edit capitalizeWord(String text, int caret) {
        return caseWord(text, caret, CaseOp.CAPITALIZE);
    }

    private enum CaseOp {
        UPPER,
        LOWER,
        CAPITALIZE
    }

    private static Edit caseWord(String text, int caret, CaseOp op) {
        int n = text.length();
        int from = clamp(caret, n);
        int i = from;
        while (i < n && !isWord(text.charAt(i))) {
            i++;
        }
        int wordStart = i;
        while (i < n && isWord(text.charAt(i))) {
            i++;
        }
        int end = i;
        if (wordStart == end) {
            return null; // no word ahead
        }
        // The leading non-word run [from, wordStart) is preserved verbatim; only the word changes.
        StringBuilder sb = new StringBuilder(end - from);
        sb.append(text, from, wordStart);
        boolean seenLetter = false;
        for (int k = wordStart; k < end; k++) {
            char c = text.charAt(k);
            switch (op) {
                case UPPER -> sb.append(Character.toUpperCase(c));
                case LOWER -> sb.append(Character.toLowerCase(c));
                case CAPITALIZE -> {
                    if (Character.isLetter(c)) {
                        sb.append(seenLetter ? Character.toLowerCase(c) : Character.toUpperCase(c));
                        seenLetter = true;
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return new Edit(from, end, sb.toString(), end);
    }

    /** Emacs {@code upcase-region} (`C-x C-u`): upper-case the selection {@code [start, end)}. */
    public static Edit upcaseRegion(String text, int start, int end) {
        return caseRegion(text, start, end, true);
    }

    /** Emacs {@code downcase-region} (`C-x C-l`): lower-case the selection {@code [start, end)}. */
    public static Edit downcaseRegion(String text, int start, int end) {
        return caseRegion(text, start, end, false);
    }

    private static Edit caseRegion(String text, int start, int end, boolean upper) {
        int n = text.length();
        int a = clamp(start, n);
        int b = clamp(end, n);
        if (a >= b) {
            return null;
        }
        String region = text.substring(a, b);
        String out = upper ? region.toUpperCase(Locale.ROOT) : region.toLowerCase(Locale.ROOT);
        if (out.equals(region)) {
            return null;
        }
        return new Edit(a, b, out, b);
    }

    /**
     * Emacs {@code delete-indentation} (`M-^`): join the current line to the previous one, collapsing
     * the trailing whitespace of the previous line and the leading whitespace of the current line into
     * a single space (or none when either side is empty). No-op on the first line.
     */
    public static Edit deleteIndentation(String text, int caret) {
        int cs = lineStart(text, caret);
        if (cs == 0) {
            return null; // first line: nothing above to join to
        }
        int prevNewline = cs - 1; // the '\n' that ends the previous line
        int prevTrim = prevNewline;
        while (prevTrim > 0 && isHorizWs(text.charAt(prevTrim - 1))) {
            prevTrim--;
        }
        int contentStart = cs;
        int n = text.length();
        while (contentStart < n && isHorizWs(text.charAt(contentStart))) {
            contentStart++;
        }
        int prevLineStart = lineStart(text, prevNewline);
        boolean prevEmpty = prevTrim == prevLineStart;
        boolean curEmpty = contentStart >= lineEnd(text, cs);
        String sep = (prevEmpty || curEmpty) ? "" : " ";
        return new Edit(prevTrim, contentStart, sep, prevTrim + sep.length());
    }

    /**
     * Emacs {@code delete-horizontal-space} (`M-\`): delete all spaces and tabs around the caret.
     * No-op when there is no surrounding horizontal whitespace.
     */
    public static Edit deleteHorizontalSpace(String text, int caret) {
        int n = text.length();
        int a = clamp(caret, n);
        int b = a;
        while (a > 0 && isHorizWs(text.charAt(a - 1))) {
            a--;
        }
        while (b < n && isHorizWs(text.charAt(b))) {
            b++;
        }
        if (a == b) {
            return null;
        }
        return new Edit(a, b, "", a);
    }

    /**
     * Emacs {@code just-one-space} (`M-SPC`): replace all spaces and tabs around the caret with a
     * single space (inserting one when there is none).
     */
    public static Edit justOneSpace(String text, int caret) {
        int n = text.length();
        int a = clamp(caret, n);
        int b = a;
        while (a > 0 && isHorizWs(text.charAt(a - 1))) {
            a--;
        }
        while (b < n && isHorizWs(text.charAt(b))) {
            b++;
        }
        if (b - a == 1 && text.charAt(a) == ' ') {
            return null; // already exactly one space
        }
        return new Edit(a, b, " ", a + 1);
    }

    /**
     * Emacs {@code open-line} (`C-o`): insert a newline at the caret, leaving the caret before it.
     */
    public static Edit openLine(String text, int caret) {
        int c = clamp(caret, text.length());
        return new Edit(c, c, "\n", c);
    }

    /**
     * Emacs {@code kill-whole-line} (`C-S-DEL`): delete the entire current line including its trailing
     * newline, leaving the caret at the start of the next line. For a last line with no trailing
     * newline, the preceding newline is removed instead. No-op in an empty buffer.
     */
    public static Edit killWholeLine(String text, int caret) {
        int n = text.length();
        if (n == 0) {
            return null;
        }
        int cs = lineStart(text, caret);
        int ce = lineEnd(text, caret);
        int from = cs;
        int to;
        if (ce < n) {
            to = ce + 1; // include the trailing newline
        } else {
            to = ce; // last line, no trailing newline
            if (cs > 0) {
                from = cs - 1; // remove the preceding newline so the line truly disappears
            }
        }
        if (from == to) {
            return null;
        }
        return new Edit(from, to, "", from);
    }

    /**
     * Emacs {@code zap-to-char} (`M-z` then a char): delete from the caret up to and including the next
     * occurrence of {@code target} at/after the caret. No-op when {@code target} is not found ahead.
     * (Without a kill ring the text is simply deleted, like {@code kill-line}.)
     */
    public static Edit zapToChar(String text, int caret, char target) {
        int from = clamp(caret, text.length());
        int idx = text.indexOf(target, from);
        if (idx < 0) {
            return null;
        }
        return new Edit(from, idx + 1, "", from);
    }

    /**
     * Emacs {@code delete-blank-lines} (`C-x C-o`): on a blank line, collapse a run of blank lines to a
     * single one (or delete it when isolated); on a non-blank line, delete any blank lines immediately
     * following it. Returns {@code null} when there is nothing to delete.
     */
    public static Edit deleteBlankLines(String text, int caret) {
        int n = text.length();
        int cs = lineStart(text, caret);
        int ce = lineEnd(text, caret);
        boolean curBlank = isBlankLine(text, cs, ce);

        if (curBlank) {
            // Extend the run of blank lines upward and downward.
            int runStart = cs;
            while (runStart > 0) {
                int ps = lineStart(text, runStart - 1);
                if (isBlankLine(text, ps, runStart - 1)) {
                    runStart = ps;
                } else {
                    break;
                }
            }
            int runEnd = ce; // end (newline-excluded) of the last blank line in the run
            int firstEnd = lineEnd(text, runStart); // end of the first blank line in the run
            while (runEnd < n) {
                int ns = runEnd + 1; // start of the next line (past the newline)
                int ne = lineEnd(text, ns);
                if (ns <= n && isBlankLine(text, ns, ne)) {
                    runEnd = ne;
                } else {
                    break;
                }
            }
            if (runEnd > firstEnd) {
                // More than one blank line: keep the first, delete the rest (with their newlines).
                return new Edit(firstEnd, runEnd, "", firstEnd);
            }
            // Isolated single blank line: delete it (with one bounding newline).
            if (ce < n) {
                return new Edit(cs, ce + 1, "", cs);
            }
            if (cs > 0) {
                return new Edit(cs - 1, ce, "", cs - 1);
            }
            return null; // a lone empty buffer
        }

        // Non-blank line: delete the run of blank lines immediately following it.
        if (ce >= n) {
            return null; // no following line
        }
        int ns = ce + 1;
        int ne = lineEnd(text, ns);
        if (!isBlankLine(text, ns, ne)) {
            return null; // next line is not blank
        }
        int runEnd = ne;
        while (runEnd < n) {
            int s = runEnd + 1;
            int e = lineEnd(text, s);
            if (s <= n && isBlankLine(text, s, e)) {
                runEnd = e;
            } else {
                break;
            }
        }
        // Delete the blank lines themselves (from the start of the first one through the newline that
        // ends the run), keeping the newline that terminates the current line — so one separator remains.
        int to = runEnd < n ? runEnd + 1 : runEnd;
        return new Edit(ns, to, "", ce);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }
}
