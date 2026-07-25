package com.editora.editops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Semantic "expand selection" — the growing-selection primitive of VS Code ({@code Shift+Alt+Right}),
 * IntelliJ ({@code Ctrl+W}) and Emacs {@code expand-region}. Pure (no toolkit), so it is unit-tested.
 *
 * <p>{@link #expand} returns the next range out from the current selection: word → the contents of an
 * enclosing bracket or quote pair → those delimiters included → the current line (trimmed, then whole) →
 * the enclosing definition → the paragraph → the whole document. There is no matching {@code shrink} here:
 * the caller keeps a stack of the selections it has set and pops it, so shrink retraces expand exactly.
 *
 * <p>Rather than a fixed ladder, it collects every candidate range that <em>strictly</em> contains the
 * current selection and returns the smallest — which is robust to the candidates' relative sizes (a short
 * line inside large brackets, say) in a way a hand-ordered ladder is not. Bracket/quote matching is the
 * same best-effort as {@link BraceMatcher}: it does not exclude delimiters that appear inside a string or
 * comment, since telling those apart needs the grammar. A syntax-accurate version (an LSP
 * {@code selectionRange} provider) can layer on top later; this is the always-available fallback.
 */
public final class SmartSelect {

    /** Bounds the outward bracket/quote scan so a pathological input can't make one expand O(document). */
    static final int MAX_SCAN = 50_000;

    private SmartSelect() {}

    /**
     * The next range out from {@code [selStart, selEnd)} (which may be empty, i.e. a bare caret), or
     * {@code null} when the selection already spans the whole document. The result always
     * <em>strictly</em> contains the input.
     */
    public static int[] expand(String text, int selStart, int selEnd) {
        int n = text.length();
        int s = clamp(selStart, n);
        int e = clamp(selEnd, n);
        if (s > e) {
            int t = s;
            s = e;
            e = t;
        }
        if (s == 0 && e == n) {
            return null; // nothing larger than the whole document
        }

        List<int[]> cands = new ArrayList<>();
        addWord(text, s, e, cands);
        addBrackets(text, s, e, cands);
        addQuotes(text, s, e, cands);
        addLine(text, s, e, cands);
        addDefun(text, s, e, cands);
        addParagraph(text, s, e, cands);
        cands.add(new int[] {0, n}); // the document is always the last resort

        int[] best = null;
        for (int[] c : cands) {
            if (c[0] <= s && c[1] >= e && (c[0] < s || c[1] > e)) { // contains, and strictly larger
                if (best == null
                        || span(c) < span(best)
                        || (span(c) == span(best) && c[0] > best[0])) { // tie → the closer (later start) one
                    best = c;
                }
            }
        }
        return best;
    }

    private static int span(int[] r) {
        return r[1] - r[0];
    }

    // --- candidate builders ---

    /** The word/identifier run covering the selection (only when the selection lies within word chars). */
    private static void addWord(String text, int s, int e, List<int[]> out) {
        int a = s;
        while (a > 0 && isWord(text.charAt(a - 1))) {
            a--;
        }
        int b = e;
        int n = text.length();
        while (b < n && isWord(text.charAt(b))) {
            b++;
        }
        if (a < b) {
            out.add(new int[] {a, b});
        }
    }

    /** Contents and full extent of every enclosing bracket pair, within a bounded window. */
    private static void addBrackets(String text, int s, int e, List<int[]> out) {
        int n = text.length();
        int lo = Math.max(0, s - MAX_SCAN);
        int hi = Math.min(n, e + MAX_SCAN);
        Deque<int[]> stack = new ArrayDeque<>(); // {index, openerChar}
        for (int i = lo; i < hi; i++) {
            char c = text.charAt(i);
            if (isOpen(c)) {
                stack.push(new int[] {i, c});
            } else if (isClose(c)) {
                // Recover from a mismatched closer by popping until a matching opener (best-effort).
                while (!stack.isEmpty() && !matches((char) stack.peek()[1], c)) {
                    stack.pop();
                }
                if (stack.isEmpty()) {
                    continue;
                }
                int oi = stack.pop()[0];
                int ci = i;
                if (oi + 1 <= s && ci >= e) { // this pair encloses the selection
                    out.add(new int[] {oi + 1, ci}); // contents
                    out.add(new int[] {oi, ci + 1}); // including the delimiters
                }
            }
        }
    }

    /** Contents and full extent of an enclosing quote pair on the selection's own line. */
    private static void addQuotes(String text, int s, int e, List<int[]> out) {
        int lineStart = lineStart(text, s);
        int lineEnd = lineEnd(text, e);
        // Pair same-type quotes left-to-right across the line; keep the pair that encloses the selection.
        char open = 0;
        int openIdx = -1;
        for (int i = lineStart; i < lineEnd; i++) {
            char c = text.charAt(i);
            if (c != '"' && c != '\'' && c != '`') {
                continue;
            }
            if (open == 0) {
                open = c;
                openIdx = i;
            } else if (c == open) {
                if (openIdx + 1 <= s && i >= e) { // encloses
                    out.add(new int[] {openIdx + 1, i}); // contents
                    out.add(new int[] {openIdx, i + 1}); // including the quotes
                }
                open = 0;
                openIdx = -1;
            }
        }
    }

    /** The selection's line(s): trimmed content first, then the full line span (no trailing newline). */
    private static void addLine(String text, int s, int e, List<int[]> out) {
        int ls = lineStart(text, s);
        int le = lineEnd(text, e);
        if (ls >= le) {
            return;
        }
        int firstNonWs = ls;
        while (firstNonWs < le && isHorizWs(text.charAt(firstNonWs))) {
            firstNonWs++;
        }
        int lastNonWs = le;
        while (lastNonWs > firstNonWs && isHorizWs(text.charAt(lastNonWs - 1))) {
            lastNonWs--;
        }
        if (firstNonWs < lastNonWs) {
            out.add(new int[] {firstNonWs, lastNonWs}); // trimmed content
        }
        out.add(new int[] {ls, le}); // whole line
    }

    private static void addDefun(String text, int s, int e, List<int[]> out) {
        int a = SexpNav.beginningOfDefun(text, s);
        int b = SexpNav.endOfDefun(text, e);
        if (a <= s && b >= e) {
            out.add(new int[] {a, b});
        }
    }

    private static void addParagraph(String text, int s, int e, List<int[]> out) {
        int[] p = SexpNav.paragraphBounds(text, s);
        if (p[0] <= s && p[1] >= e) {
            out.add(p);
        }
    }

    // --- char helpers ---

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isOpen(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    private static boolean isClose(char c) {
        return c == ')' || c == ']' || c == '}';
    }

    private static boolean matches(char open, char close) {
        return (open == '(' && close == ')') || (open == '[' && close == ']') || (open == '{' && close == '}');
    }

    private static boolean isHorizWs(char c) {
        return c == ' ' || c == '\t';
    }

    private static int lineStart(String text, int pos) {
        int i = Math.min(pos, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    private static int lineEnd(String text, int pos) {
        int n = text.length();
        int i = Math.min(pos, n);
        while (i < n && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(v, n));
    }
}
