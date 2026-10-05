package com.editora.editops;

/**
 * Decides, without a grammar, whether a quote character opens a string — shared by the scans that must
 * skip string contents ({@link SexpNav}, {@link Indenter}). A {@code "} or {@code '} string ends on its own
 * line, so a quote with no partner before the newline is an ordinary character: the apostrophe of
 * {@code // don't}, or one of a Python {@code """}. Two more shapes of {@code '} are never a string opener:
 * an apostrophe inside a word ({@code don't}, {@code it's} — but not a string prefix such as {@code r'…'}),
 * and a Rust lifetime ({@code &'a}, {@code <'a>}). A backtick string may span lines.
 */
final class Quotes {

    private Quotes() {}

    /**
     * Index of the quote that closes the string opened at {@code open}, or -1 when the character there does
     * not open a string. {@code limit} bounds the search (exclusive).
     */
    static int closing(CharSequence text, int open, int limit) {
        char q = text.charAt(open);
        if (q == '\'' && (isApostrophe(text, open) || isLifetime(text, open, limit))) {
            return -1;
        }
        for (int j = open + 1; j < limit; j++) {
            char d = text.charAt(j);
            if (d == '\n' && q != '`') {
                return -1; // unterminated on its line
            }
            if (d == '\\') {
                j++; // skip the escaped char
            } else if (d == q) {
                return j;
            }
        }
        return -1;
    }

    /**
     * Index of the quote that opens the string closed at {@code close}, or -1 when the character there does
     * not close one. Pairs the quotes of the line from its start, so the answer agrees with
     * {@link #closing}; a backtick falls back to the nearest earlier backtick.
     */
    static int opening(CharSequence text, int close) {
        char q = text.charAt(close);
        if (q == '`') {
            for (int j = close - 1; j >= 0; j--) {
                if (text.charAt(j) == q && (j == 0 || text.charAt(j - 1) != '\\')) {
                    return j;
                }
            }
            return -1;
        }
        int ls = close;
        while (ls > 0 && text.charAt(ls - 1) != '\n') {
            ls--;
        }
        int limit = Math.min(text.length(), close + 1);
        for (int i = ls; i < close; i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int end = closing(text, i, limit);
                if (end == close) {
                    return i;
                }
                if (end > i) {
                    i = end;
                }
            }
        }
        return -1;
    }

    /** A {@code '} directly after a word character, unless that word is a string prefix ({@code r}, {@code rb}, …). */
    private static boolean isApostrophe(CharSequence text, int at) {
        int start = at;
        while (start > 0 && isWord(text.charAt(start - 1))) {
            start--;
        }
        int len = at - start;
        if (len == 0) {
            return false;
        }
        if (len > 2) {
            return true;
        }
        for (int i = start; i < at; i++) {
            if ("rbfuRBFU".indexOf(text.charAt(i)) < 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code &'a} / {@code <'a}: a name follows the quote and no quote closes it (unlike {@code '<'} or {@code 'a'}). */
    private static boolean isLifetime(CharSequence text, int at, int limit) {
        if (at == 0 || (text.charAt(at - 1) != '&' && text.charAt(at - 1) != '<')) {
            return false;
        }
        int end = at + 1;
        while (end < limit && isWord(text.charAt(end))) {
            end++;
        }
        return end > at + 1 && (end >= limit || text.charAt(end) != '\'');
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
