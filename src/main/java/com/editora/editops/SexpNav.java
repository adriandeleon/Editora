package com.editora.editops;

/**
 * Pure, unit-tested structural navigation for the Emacs balanced-expression and defun commands:
 * {@code forward-sexp} (C-M-f), {@code backward-sexp} (C-M-b), {@code beginning-of-defun} (C-M-a),
 * {@code end-of-defun} (C-M-e), plus blank-line paragraph bounds for {@code mark-paragraph} (M-h).
 * Each returns an absolute offset (or a span), or the input position for a no-op. No toolkit
 * dependency; the controller moves/selects the active {@code CodeArea}.
 *
 * <p>"Sexp" here is bracket/string/symbol aware ({@code () [] {}} nesting, {@code " ' `} strings,
 * runs of symbol characters). Defun detection is necessarily heuristic in a language-agnostic editor:
 * a defun starts at a column-0 line whose first character opens a definition (a letter, {@code _},
 * {@code @}, or an opening bracket) — good for brace/paren languages, approximate elsewhere.
 */
public final class SexpNav {

    private SexpNav() {}

    private static boolean isSymbol(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$' || c == '.';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isOpen(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    private static boolean isClose(char c) {
        return c == ')' || c == ']' || c == '}';
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '`';
    }

    /**
     * Emacs {@code forward-sexp}: skip leading whitespace, then move over one balanced expression — a
     * bracketed group (to just past its matching close), a quoted string, or a run of symbol chars.
     * Returns {@code pos} unchanged when there is nothing ahead or the caret is before a closing bracket.
     */
    public static int forward(String text, int pos) {
        int n = text.length();
        int i = clamp(pos, n);
        while (i < n && isSpace(text.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return pos;
        }
        char c = text.charAt(i);
        if (isClose(c)) {
            return pos; // can't move forward over a closer
        }
        if (isOpen(c)) {
            int depth = 0;
            for (int j = i; j < n; j++) {
                char d = text.charAt(j);
                if (isQuote(d)) {
                    j = skipStringForward(text, j);
                    continue;
                }
                if (isOpen(d)) {
                    depth++;
                } else if (isClose(d)) {
                    depth--;
                    if (depth == 0) {
                        return j + 1;
                    }
                }
            }
            return n; // unbalanced: go to end
        }
        if (isQuote(c)) {
            return skipStringForward(text, i) + 1;
        }
        // symbol run
        while (i < n && isSymbol(text.charAt(i))) {
            i++;
        }
        return i > pos ? i : pos;
    }

    /**
     * Emacs {@code backward-sexp}: the mirror of {@link #forward} — skip trailing whitespace, then move
     * back over one balanced expression. Returns {@code pos} unchanged for a no-op.
     */
    public static int backward(String text, int pos) {
        int i = clamp(pos, text.length());
        while (i > 0 && isSpace(text.charAt(i - 1))) {
            i--;
        }
        if (i <= 0) {
            return pos;
        }
        char c = text.charAt(i - 1);
        if (isOpen(c)) {
            return pos; // can't move back over an opener
        }
        if (isClose(c)) {
            int depth = 0;
            for (int j = i - 1; j >= 0; j--) {
                char d = text.charAt(j);
                if (isQuote(d)) {
                    j = skipStringBackward(text, j);
                    continue;
                }
                if (isClose(d)) {
                    depth++;
                } else if (isOpen(d)) {
                    depth--;
                    if (depth == 0) {
                        return j;
                    }
                }
            }
            return 0; // unbalanced: go to start
        }
        if (isQuote(c)) {
            return skipStringBackward(text, i - 1);
        }
        while (i > 0 && isSymbol(text.charAt(i - 1))) {
            i--;
        }
        return i < pos ? i : pos;
    }

    /** Index of the closing quote that matches the opening quote at {@code open} (or end of text). */
    private static int skipStringForward(String text, int open) {
        char q = text.charAt(open);
        int n = text.length();
        for (int j = open + 1; j < n; j++) {
            char d = text.charAt(j);
            if (d == '\\') {
                j++; // skip the escaped char
                continue;
            }
            if (d == q) {
                return j;
            }
        }
        return n - 1;
    }

    /** Index of the opening quote that matches the closing quote at {@code close} (or 0). */
    private static int skipStringBackward(String text, int close) {
        char q = text.charAt(close);
        for (int j = close - 1; j >= 0; j--) {
            if (text.charAt(j) == q && (j == 0 || text.charAt(j - 1) != '\\')) {
                return j;
            }
        }
        return 0;
    }

    private static int lineStartOf(String text, int pos) {
        int i = clamp(pos, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    private static int lineEndOf(String text, int pos) {
        int i = clamp(pos, text.length());
        int n = text.length();
        while (i < n && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** Whether the line starting at {@code ls} begins a defun (heuristic; see the class doc). */
    private static boolean isDefunStart(String text, int ls) {
        if (ls >= text.length()) {
            return false;
        }
        char c = text.charAt(ls);
        return Character.isLetter(c) || c == '_' || c == '@' || c == '(' || c == '{' || c == '[';
    }

    /**
     * Emacs {@code beginning-of-defun} (`C-M-a`): move to the start of the current or preceding
     * top-level definition (a column-0 defun-start line, see the class doc). Returns 0 when none is found.
     */
    public static int beginningOfDefun(String text, int pos) {
        int cur = lineStartOf(text, pos);
        // If we're already at the very start of a defun line, search from the line above.
        int from = (pos == cur && isDefunStart(text, cur)) ? cur - 1 : pos - 1;
        int p = from;
        while (p >= 0) {
            int ls = lineStartOf(text, p);
            if (isDefunStart(text, ls)) {
                return ls;
            }
            p = ls - 1;
        }
        return 0;
    }

    /**
     * Emacs {@code end-of-defun} (`C-M-e`): move to the end of the current defun — approximated as the
     * start of the next top-level defun line, or the end of the buffer when none follows.
     */
    public static int endOfDefun(String text, int pos) {
        int n = text.length();
        // Skip the current defun-start line, then find the next defun start.
        int p = lineEndOf(text, pos) + 1;
        while (p < n) {
            int ls = lineStartOf(text, p);
            if (isDefunStart(text, ls)) {
                return ls;
            }
            p = lineEndOf(text, p) + 1;
        }
        return n;
    }

    /**
     * Bounds of the paragraph (blank-line delimited) containing {@code pos}, for {@code mark-paragraph}
     * (M-h). Returns {@code {start, end}} offsets; an empty/blank region yields {@code {pos, pos}}.
     */
    public static int[] paragraphBounds(String text, int pos) {
        int n = text.length();
        int p = clamp(pos, n);
        // Move into a non-blank region if currently on blank lines: search forward.
        // Start: walk up while the previous line is non-blank.
        int start = lineStartOf(text, p);
        while (start > 0) {
            int prevStart = lineStartOf(text, start - 1);
            if (isBlank(text, prevStart, start - 1)) {
                break;
            }
            start = prevStart;
        }
        // End: walk down while the current/next line is non-blank.
        int end = lineEndOf(text, p);
        while (end < n) {
            int ns = end + 1;
            int ne = lineEndOf(text, ns);
            if (isBlank(text, ns, ne)) {
                break;
            }
            end = ne;
        }
        return new int[] {start, end};
    }

    private static boolean isBlank(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }
}
