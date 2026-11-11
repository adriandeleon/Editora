package com.editora.editor.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure: the functions that start on one line of JavaScript or TypeScript — arrow functions ({@code x => x * 2},
 * {@code (a, b) => {…}}, {@code async () => …}, with a TypeScript return type) and function expressions passed or
 * assigned inline ({@code setTimeout(function () {…})}) — where a breakpoint can stop inside them each time they run,
 * which a line breakpoint does not: it stops once, when the line itself runs. A function declaration that begins the
 * line is not one: a breakpoint on its line already stops in it.
 *
 * <p>V8 (through js-debug) puts a column breakpoint on the first place it can stop at or after that column, so each
 * function's stop column is the start of its body: the expression after {@code =>}, else the first statement after
 * its {@code {} — or, when the body goes on to the next lines, the end of this one, which V8 moves on into it.
 * Strings, template literals and comments are skipped; a regular expression with {@code =>} in it is not told apart.
 */
public final class JsFunctions {

    private JsFunctions() {}

    /** Every function starting on {@code line}, left to right. */
    public static List<LineFunctions.Function> on(String line) {
        List<LineFunctions.Function> out = new ArrayList<>();
        if (line == null || (line.indexOf("=>") < 0 && line.indexOf("function") < 0)) {
            return out;
        }
        char[] c = blankNonCode(line);
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '=' && i + 1 < c.length && c[i + 1] == '>' && (i == 0 || "=!<>".indexOf(c[i - 1]) < 0)) {
                int start = arrowStart(c, i);
                if (start >= 0) {
                    out.add(new LineFunctions.Function(start, bodyStart(c, i + 2), preview(line, start)));
                }
                i++;
            } else if (isWord(c, i, "function") && !beginsLine(c, i)) {
                int open = c.length;
                int paren = indexOf(c, '(', i + 8);
                if (paren >= 0) {
                    int close = matchForward(c, paren);
                    open = close < 0 ? c.length : indexOf(c, '{', close);
                }
                int start = asyncBefore(c, i);
                int stop = open < 0 || open >= c.length ? c.length : bodyStart(c, open);
                out.add(new LineFunctions.Function(start, stop, preview(line, start)));
                i += 7;
            }
        }
        return out;
    }

    /**
     * Where the arrow function whose {@code =>} is at {@code arrow} starts: its single parameter, or the {@code (}
     * of its parameter list — before a TypeScript return type, and with an {@code async} before it; -1 when neither.
     */
    private static int arrowStart(char[] c, int arrow) {
        int k = skipSpacesBack(c, arrow - 1);
        if (k < 0) {
            return -1;
        }
        int start = -1;
        if (c[k] == ')') {
            start = matchBack(c, k);
        } else {
            // A TypeScript return type, "(…): T =>": the parameters are the list before the colon.
            int paren = k;
            while (paren >= 0 && c[paren] != ')') {
                paren--;
            }
            if (paren >= 0
                    && new String(c, paren + 1, arrow - paren - 1).strip().startsWith(":")) {
                start = matchBack(c, paren);
            } else if (Character.isJavaIdentifierPart(c[k])) {
                start = k;
                while (start > 0 && Character.isJavaIdentifierPart(c[start - 1])) {
                    start--;
                }
            }
        }
        return start < 0 ? -1 : asyncBefore(c, start);
    }

    /** {@code start}, or the start of an {@code async} just before it. */
    private static int asyncBefore(char[] c, int start) {
        int k = skipSpacesBack(c, start - 1);
        if (k >= 4 && isWord(c, k - 4, "async")) {
            return k - 4;
        }
        return start;
    }

    /** The first non-blank column at or after {@code from}, past a {@code {} there; the line's end when none. */
    private static int bodyStart(char[] c, int from) {
        int k = from;
        while (k < c.length && c[k] == ' ') {
            k++;
        }
        if (k < c.length && c[k] == '{') {
            k++;
            while (k < c.length && c[k] == ' ') {
                k++;
            }
        }
        return Math.min(k, c.length);
    }

    /** Whether the {@code function} at {@code i} begins the line (after {@code export}, {@code default}, {@code async}). */
    private static boolean beginsLine(char[] c, int i) {
        String before = new String(c, 0, i).strip();
        while (!before.isEmpty()) {
            String next = before;
            for (String word : new String[] {"export", "default", "async"}) {
                if (next.endsWith(word)) {
                    next = next.substring(0, next.length() - word.length()).strip();
                }
            }
            if (next.equals(before)) {
                return false;
            }
            before = next;
        }
        return true;
    }

    /** Whether {@code word} stands at {@code i} as a word of its own. */
    private static boolean isWord(char[] c, int i, String word) {
        if (i < 0 || i + word.length() > c.length) {
            return false;
        }
        for (int k = 0; k < word.length(); k++) {
            if (c[i + k] != word.charAt(k)) {
                return false;
            }
        }
        boolean startsWord = i == 0 || !Character.isJavaIdentifierPart(c[i - 1]);
        boolean endsWord = i + word.length() == c.length || !Character.isJavaIdentifierPart(c[i + word.length()]);
        return startsWord && endsWord;
    }

    private static int skipSpacesBack(char[] c, int k) {
        while (k >= 0 && c[k] == ' ') {
            k--;
        }
        return k;
    }

    private static int indexOf(char[] c, char ch, int from) {
        for (int k = Math.max(0, from); k < c.length; k++) {
            if (c[k] == ch) {
                return k;
            }
        }
        return -1;
    }

    /** The {@code (} that the {@code )} at {@code close} closes, or -1. */
    private static int matchBack(char[] c, int close) {
        int depth = 0;
        for (int k = close; k >= 0; k--) {
            if (c[k] == ')') {
                depth++;
            } else if (c[k] == '(' && --depth == 0) {
                return k;
            }
        }
        return -1;
    }

    /** The {@code )} that closes the {@code (} at {@code open}, or -1. */
    private static int matchForward(char[] c, int open) {
        int depth = 0;
        for (int k = open; k < c.length; k++) {
            if (c[k] == '(') {
                depth++;
            } else if (c[k] == ')' && --depth == 0) {
                return k;
            }
        }
        return -1;
    }

    /**
     * {@code line} with the contents of strings, template literals and comments (and their quotes) turned into spaces.
     * A template literal's {@code ${…}} is blanked too: a function in it is not found.
     */
    static char[] blankNonCode(String line) {
        char[] c = line.toCharArray();
        int i = 0;
        while (i < c.length) {
            char ch = c[i];
            if (ch == '/' && i + 1 < c.length && c[i + 1] == '/') {
                java.util.Arrays.fill(c, i, c.length, ' ');
                break;
            }
            if (ch == '/' && i + 1 < c.length && c[i + 1] == '*') {
                int end = line.indexOf("*/", i + 2);
                int stop = end < 0 ? c.length : end + 2;
                java.util.Arrays.fill(c, i, stop, ' ');
                i = stop;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                int k = i + 1;
                while (k < c.length && c[k] != ch) {
                    k += c[k] == '\\' ? 2 : 1;
                }
                int stop = Math.min(c.length, k + 1);
                java.util.Arrays.fill(c, i, stop, ' ');
                i = stop;
                continue;
            }
            i++;
        }
        return c;
    }

    private static String preview(String line, int start) {
        String rest = line.substring(start).strip();
        return rest.length() > JavaLambdas.MAX_PREVIEW ? rest.substring(0, JavaLambdas.MAX_PREVIEW) + "…" : rest;
    }
}
