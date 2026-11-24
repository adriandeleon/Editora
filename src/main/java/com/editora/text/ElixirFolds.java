package com.editora.text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.editora.text.FoldRegions.Region;

/**
 * Elixir's foldable regions: every {@code do … end} and {@code fn … end} block, multi-line {@code [ ]} and
 * {@code &#123; &#125;} (maps, keyword lists, tuples), and heredocs ({@code @moduledoc """ … """}). Strings,
 * charlists, sigils ({@code ~r/…/}, {@code ~s(…)}, {@code ~H"""…"""}), character literals ({@code ?"}) and
 * {@code #} comments are skipped, and so are the atom and keyword forms of the block words ({@code :do},
 * {@code do:}, {@code if x, do: y}), which open nothing. Pure; one pass.
 */
final class ElixirFolds {

    private ElixirFolds() {}

    /** An opener on the stack: its line, and its kind ({@code [}, {@code &#123;}, or {@code d}: closed by {@code end}). */
    private record Open(int line, char kind) {}

    static List<Region> detect(String text) {
        List<Region> out = new ArrayList<>();
        Deque<Open> stack = new ArrayDeque<>();
        int n = text.length();
        int line = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '#') {
                while (i + 1 < n && text.charAt(i + 1) != '\n') {
                    i++;
                }
            } else if (c == '?' && i + 1 < n && !isWordChar(prev(text, i))) {
                // a character literal: ?a, ?", ?\n, ?\\ — never a string or a comment
                i += text.charAt(i + 1) == '\\' && i + 2 < n ? 2 : 1;
                if (text.charAt(i) == '\n') {
                    line++;
                }
            } else if (c == '"' || c == '\'') {
                int start = line;
                int end = stringEnd(text, i, c, true);
                line += newlines(text, i, end);
                if (text.startsWith(String.valueOf(c).repeat(3), i) && line > start) {
                    out.add(new Region(start, line)); // a heredoc: @doc """ … """
                }
                i = end;
            } else if (c == '~' && i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
                int start = line;
                int delimiter = i + 1;
                while (delimiter < n && Character.isLetterOrDigit(text.charAt(delimiter))) {
                    delimiter++;
                }
                boolean heredoc = text.startsWith("\"\"\"", delimiter) || text.startsWith("'''", delimiter);
                int end = sigilEnd(text, i);
                line += newlines(text, i, end);
                if (heredoc && line > start) {
                    out.add(new Region(start, line)); // a heredoc sigil: ~H""" … """
                }
                i = end;
            } else if (c == '[' || c == '{') {
                stack.push(new Open(line, c));
            } else if (c == ']' || c == '}') {
                popTo(stack, c == ']' ? '[' : '{', line, out);
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && isWordChar(text.charAt(j))) {
                    j++;
                }
                if (j < n && (text.charAt(j) == '?' || text.charAt(j) == '!')) {
                    j++;
                }
                String word = text.substring(i, j);
                char before = prev(text, i);
                boolean keyword = j < n && text.charAt(j) == ':' && !(j + 1 < n && text.charAt(j + 1) == ':');
                boolean plain = before != ':' && before != '.' && before != '@' && !keyword;
                if (plain && (word.equals("do") || word.equals("fn"))) {
                    stack.push(new Open(line, 'd'));
                } else if (plain && word.equals("end")) {
                    popTo(stack, 'd', line, out);
                }
                i = j - 1;
            }
        }
        return FoldRegions.canonicalOrder(out);
    }

    /**
     * Closes the innermost opener of {@code kind}, dropping any unclosed opener of another kind above it (a
     * stray bracket must not swallow the {@code end} that closes the block around it).
     */
    private static void popTo(Deque<Open> stack, char kind, int line, List<Region> out) {
        boolean present = false;
        for (Open o : stack) {
            if (o.kind() == kind) {
                present = true;
                break;
            }
        }
        if (!present) {
            return;
        }
        while (!stack.isEmpty()) {
            Open o = stack.pop();
            if (o.kind() == kind) {
                if (line > o.line()) {
                    out.add(new Region(o.line(), line));
                }
                return;
            }
        }
    }

    private static int newlines(String text, int from, int to) {
        int count = 0;
        for (int k = from + 1; k <= to && k < text.length(); k++) {
            if (text.charAt(k) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static char prev(String text, int i) {
        return i > 0 ? text.charAt(i - 1) : '\0';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The index of the last character of the string or charlist opening at {@code i} (a heredoc when the quote
     * is tripled), or the text's last index when it never closes. {@code escapes}: a backslash escapes the next
     * character (not in an upper-case sigil). Interpolation ({@code #{…}}) may hold quotes of its own.
     */
    private static int stringEnd(String text, int i, char quote, boolean escapes) {
        int n = text.length();
        String triple = String.valueOf(quote).repeat(3);
        if (text.startsWith(triple, i)) {
            int close = text.indexOf(triple, i + 3);
            return close < 0 ? n - 1 : close + 2;
        }
        for (int j = i + 1; j < n; j++) {
            char c = text.charAt(j);
            if (c == '\\' && escapes) {
                j++;
            } else if (c == '#' && escapes && j + 1 < n && text.charAt(j + 1) == '{') {
                j = interpolationEnd(text, j + 1);
            } else if (c == quote) {
                return j;
            }
        }
        return n - 1;
    }

    /** The index of the {@code }} that closes the interpolation whose {@code {} is at {@code open}. */
    private static int interpolationEnd(String text, int open) {
        int n = text.length();
        int depth = 0;
        for (int j = open; j < n; j++) {
            char c = text.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return j;
            } else if (c == '"' || c == '\'') {
                j = stringEnd(text, j, c, true);
            }
        }
        return n - 1;
    }

    /** The index of the last character of the sigil starting with the {@code ~} at {@code i}. */
    private static int sigilEnd(String text, int i) {
        int n = text.length();
        int j = i + 1;
        boolean upper = Character.isUpperCase(text.charAt(j));
        while (j < n && Character.isLetterOrDigit(text.charAt(j))) {
            j++; // ~r, ~H, and multi-letter upper-case sigils (~HTML)
        }
        if (j >= n) {
            return n - 1;
        }
        char open = text.charAt(j);
        int end;
        if (open == '"' || open == '\'') {
            end = stringEnd(text, j, open, !upper);
        } else {
            char close = switch (open) {
                case '(' -> ')';
                case '[' -> ']';
                case '{' -> '}';
                case '<' -> '>';
                case '/', '|' -> open;
                default -> '\0';
            };
            if (close == '\0') {
                return j - 1; // not a sigil after all
            }
            end = n - 1;
            for (int k = j + 1; k < n; k++) {
                char c = text.charAt(k);
                if (c == '\\' && !upper) {
                    k++;
                } else if (c == close) {
                    end = k;
                    break;
                }
            }
        }
        // modifiers: ~r/x/iu
        while (end + 1 < n && Character.isLetter(text.charAt(end + 1))) {
            end++;
        }
        return end;
    }
}
