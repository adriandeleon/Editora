package com.editora.editor;

/**
 * Pure, unit-tested implementation of the Emacs transpose commands — character (`C-t`), word (`M-t`),
 * and line (`C-x C-t`) — computing a minimal {@link Edit} (a replacement span + the resulting caret)
 * from the current text + caret, or {@code null} for a no-op. No toolkit dependency; the controller
 * applies the {@code Edit} to the active {@code CodeArea}.
 */
public final class Transposer {

    /** Replace {@code [from, to)} with {@code replacement}, then place the caret at {@code caret}. */
    public record Edit(int from, int to, String replacement, int caret) { }

    private Transposer() {
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Start of the line containing {@code pos} (index just after the previous newline, or 0). */
    private static int lineStart(String text, int pos) {
        int i = pos;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** End of the line containing {@code pos} (index of the next newline at/after {@code pos}, or length). */
    private static int lineEnd(String text, int pos) {
        int i = pos;
        int n = text.length();
        while (i < n && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /**
     * Emacs {@code transpose-chars} (`C-t`): swap the characters around the caret and move forward one.
     * At end of line/buffer, swap the two preceding characters (leaving the caret in place). Never
     * transposes across a newline; no-op at the start of a line/buffer.
     */
    public static Edit transposeChars(String text, int caret) {
        int n = text.length();
        boolean atEnd = caret >= n || text.charAt(caret) == '\n';
        if (atEnd) {
            if (caret >= 2 && text.charAt(caret - 1) != '\n' && text.charAt(caret - 2) != '\n') {
                String repl = "" + text.charAt(caret - 1) + text.charAt(caret - 2);
                return new Edit(caret - 2, caret, repl, caret);
            }
            return null;
        }
        if (caret >= 1 && text.charAt(caret - 1) != '\n') {
            String repl = "" + text.charAt(caret) + text.charAt(caret - 1);
            return new Edit(caret - 1, caret + 1, repl, caret + 1);
        }
        return null;
    }

    /**
     * Emacs {@code transpose-words} (`M-t`): interchange the word before the caret with the word after
     * it (preserving the separators between them), leaving the caret at the end of the pair. A caret
     * inside a word is first advanced to that word's end; at the end of the buffer the last two words
     * are swapped; at the start, the first two. No-op when fewer than two words are involved.
     */
    public static Edit transposeWords(String text, int caret) {
        int n = text.length();
        // Inside a word → advance to its end, so whole words are transposed.
        if (caret > 0 && caret < n && isWord(text.charAt(caret - 1)) && isWord(text.charAt(caret))) {
            while (caret < n && isWord(text.charAt(caret))) {
                caret++;
            }
        }
        // Word before the caret: [a1, a2)
        int a2 = caret;
        while (a2 > 0 && !isWord(text.charAt(a2 - 1))) {
            a2--;
        }
        int a1 = a2;
        while (a1 > 0 && isWord(text.charAt(a1 - 1))) {
            a1--;
        }
        boolean haveA = a1 < a2;
        // Word after the caret: [b1, b2)
        int b1 = caret;
        while (b1 < n && !isWord(text.charAt(b1))) {
            b1++;
        }
        int b2 = b1;
        while (b2 < n && isWord(text.charAt(b2))) {
            b2++;
        }
        boolean haveB = b1 < b2;

        if (haveA && !haveB) {
            // End of buffer: transpose the two words before the caret (B := A, A := the prior word).
            b1 = a1;
            b2 = a2;
            int x2 = a1;
            while (x2 > 0 && !isWord(text.charAt(x2 - 1))) {
                x2--;
            }
            int x1 = x2;
            while (x1 > 0 && isWord(text.charAt(x1 - 1))) {
                x1--;
            }
            if (x1 == x2) {
                return null;
            }
            a1 = x1;
            a2 = x2;
        } else if (!haveA && haveB) {
            // Start of buffer: transpose the two words after the caret (A := B, B := the next word).
            a1 = b1;
            a2 = b2;
            int y1 = b2;
            while (y1 < n && !isWord(text.charAt(y1))) {
                y1++;
            }
            int y2 = y1;
            while (y2 < n && isWord(text.charAt(y2))) {
                y2++;
            }
            if (y1 == y2) {
                return null;
            }
            b1 = y1;
            b2 = y2;
        } else if (!haveA) {
            return null;
        }
        if (!(a1 < a2 && a2 <= b1 && b1 < b2)) {
            return null;
        }
        String wa = text.substring(a1, a2);
        String gap = text.substring(a2, b1);
        String wb = text.substring(b1, b2);
        return new Edit(a1, b2, wb + gap + wa, b2);
    }

    /**
     * Emacs {@code transpose-lines} (`C-x C-t`): exchange the current line with the previous one,
     * leaving the caret at the start of the line below the pair. No-op on the first line.
     */
    public static Edit transposeLines(String text, int caret) {
        int n = text.length();
        int curStart = lineStart(text, caret);
        if (curStart == 0) {
            return null; // first line: nothing above to transpose with
        }
        int prevStart = lineStart(text, curStart - 1);
        int curEnd = lineEnd(text, caret);
        String prevLine = text.substring(prevStart, curStart - 1); // excludes its trailing newline
        String curLine = text.substring(curStart, curEnd);
        String repl = curLine + "\n" + prevLine; // same length as [prevStart, curEnd)
        int afterBlock = prevStart + repl.length(); // == curEnd
        int newCaret = afterBlock < n ? afterBlock + 1 : afterBlock; // start of the line below, or EOF
        return new Edit(prevStart, curEnd, repl, newCaret);
    }
}
