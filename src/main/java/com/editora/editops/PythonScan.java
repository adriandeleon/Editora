package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A small line-level reader of Python source — pure, no toolkit — for the features that need to know where
 * Python's statements begin and end without a parser: Enter/closer indentation ({@link PythonIndent}) and
 * indentation-based folding ({@code text/FoldRegions}).
 *
 * <p>It understands just enough lexical structure to tell code from data: comments, single- and
 * triple-quoted strings (any prefix, escapes, a triple-quoted string spanning lines, and the replacement fields
 * of a one-line f/t-string, where PEP 701 lets the same quote nest: {@code f"{d["k("]}"}), bracket depth, and a
 * trailing backslash continuation. From that each line gets: whether it starts a <em>logical</em> line (not
 * inside a bracket, a triple-quoted string or a backslash continuation), its indent, whether it is blank or
 * only a comment, its first word, and the last code character on it.
 */
public final class PythonScan {

    private PythonScan() {}

    /** Bound on the characters one scan reads, so a huge file cannot make a keystroke or a fold pass crawl. */
    public static final int MAX_CHARS = 4_000_000;

    /**
     * One physical line.
     *
     * @param start offset of the line's first character
     * @param end offset just past its last character (the {@code \n} excluded)
     * @param indentEnd offset of its first non-blank character ({@code end} on a blank line)
     * @param logicalStart the line begins a new statement: not inside brackets, a string or a continuation
     * @param blank only whitespace (and not inside a string)
     * @param commentOnly only a comment after the indent
     * @param lastCode the last code character on the line (a closing quote counts), or {@code 0} when none
     * @param depthAtEnd bracket depth after the line
     * @param stringAtEnd the line ends inside a triple-quoted string
     * @param continued the line ends with a backslash continuation
     */
    public record Line(
            int start,
            int end,
            int indentEnd,
            boolean logicalStart,
            boolean blank,
            boolean commentOnly,
            char lastCode,
            int depthAtEnd,
            boolean stringAtEnd,
            boolean continued) {

        /** The line's leading whitespace in {@code text}. */
        public String indent(CharSequence text) {
            return text.subSequence(start, indentEnd).toString();
        }

        /** Whether this line is a code line that begins a statement (not blank, not only a comment). */
        public boolean statement() {
            return logicalStart && !blank && !commentOnly;
        }
    }

    /** Reads every line of {@code text} (up to {@link #MAX_CHARS}). */
    public static List<Line> scan(CharSequence text) {
        int n = Math.min(text.length(), MAX_CHARS);
        Lexer lexer = new Lexer(text, null);
        List<Line> out = new ArrayList<>();
        boolean continued = false;
        int pos = 0;
        while (pos <= n) {
            int end = pos;
            while (end < n && text.charAt(end) != '\n') {
                end++;
            }
            boolean startsInString = lexer.triple != 0;
            boolean logical = !startsInString && lexer.depth == 0 && !continued;
            int indentEnd = pos;
            while (indentEnd < end && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) {
                indentEnd++;
            }
            boolean blank = !startsInString && indentEnd == end;
            boolean commentOnly = !startsInString && indentEnd < end && text.charAt(indentEnd) == '#';
            lexer.line(pos, end);
            out.add(new Line(
                    pos,
                    end,
                    indentEnd,
                    logical,
                    blank,
                    commentOnly,
                    lexer.lastCode,
                    lexer.depth,
                    lexer.triple != 0,
                    lexer.continued));
            continued = lexer.continued && lexer.triple == 0;
            if (end >= n) {
                break;
            }
            pos = end + 1;
        }
        return out;
    }

    /**
     * Offset of the innermost bracket still open at the end of {@code text}, reading from {@code from} (the
     * start of the statement), or -1. The same lexer as {@link #scan}, so the two never disagree about what is
     * code: brackets in strings, comments and f-string literal text do not count.
     */
    public static int innermostOpenBracket(CharSequence text, int from) {
        int n = Math.min(text.length(), MAX_CHARS);
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        Lexer lexer = new Lexer(text, stack);
        int pos = Math.max(0, from);
        while (pos <= n) {
            int end = pos;
            while (end < n && text.charAt(end) != '\n') {
                end++;
            }
            lexer.line(pos, end);
            if (end >= n) {
                break;
            }
            pos = end + 1;
        }
        Integer top = stack.peek();
        return top == null ? -1 : top;
    }

    /** The lexical state carried from line to line: bracket depth and an open triple-quoted string. */
    private static final class Lexer {
        private final CharSequence text;
        /** Offsets of the open brackets, innermost first; null when only the depth is wanted. */
        private final java.util.@Nullable Deque<Integer> stack;

        int depth;
        /** The quote char of an open triple-quoted string, or 0. */
        char triple;
        /** Results of the last {@link #line}: its last code character and whether it ends with a {@code \\}. */
        char lastCode;

        boolean continued;

        Lexer(CharSequence text, java.util.@Nullable Deque<Integer> stack) {
            this.text = text;
            this.stack = stack;
        }

        /** Reads the line {@code [pos, end)}. */
        void line(int pos, int end) {
            lastCode = 0;
            continued = false;
            int i = pos;
            while (i < end) {
                char c = text.charAt(i);
                if (triple != 0) {
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == triple && startsWith(text, i, triple, 3, end)) {
                        triple = 0;
                        lastCode = c;
                        i += 3;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (c == '#') {
                    break; // the rest of the line is a comment
                }
                if (c == '"' || c == '\'') {
                    if (startsWith(text, i, c, 3, end)) {
                        triple = c;
                        lastCode = c;
                        i += 3;
                        continue;
                    }
                    i = skipString(text, i, end, c, isFormatString(text, pos, i)); // unterminated: ends with its line
                    lastCode = c;
                    continue;
                }
                if (c == '\\' && i + 1 >= end) {
                    continued = true;
                    i++;
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                    if (stack != null) {
                        stack.push(i);
                    }
                } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                    depth--;
                    if (stack != null && !stack.isEmpty()) {
                        stack.pop();
                    }
                }
                if (!Character.isWhitespace(c)) {
                    lastCode = c;
                }
                i++;
            }
        }
    }

    /**
     * Index just past the one-line string whose opening {@code quote} is at {@code i} ({@code end} when it is not
     * closed on its line). In an f/t-string ({@code format}) a replacement field is code: its own strings, even in
     * the same quote (PEP 701), and its brackets are read as such, so they do not end the string early.
     */
    private static int skipString(CharSequence text, int i, int end, char quote, boolean format) {
        int j = i + 1;
        while (j < end) {
            char ch = text.charAt(j);
            if (ch == '\\') {
                j += 2;
            } else if (ch == quote) {
                return j + 1;
            } else if (format && ch == '{') {
                j = j + 1 < end && text.charAt(j + 1) == '{' ? j + 2 : skipField(text, j + 1, end, i);
            } else {
                j++;
            }
        }
        return end;
    }

    /** Index just past the {@code }} closing the replacement field whose code starts at {@code j}. */
    private static int skipField(CharSequence text, int j, int end, int lineFloor) {
        int depth = 0;
        while (j < end) {
            char ch = text.charAt(j);
            if ((ch == '"' || ch == '\'') && !startsWith(text, j, ch, 3, end)) {
                j = skipString(text, j, end, ch, isFormatString(text, lineFloor, j));
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == '}') {
                if (depth == 0) {
                    return j + 1;
                }
                depth--;
            }
            j++;
        }
        return end;
    }

    /** Whether the quote at {@code quote} opens an f- or t-string: a prefix of string-prefix letters with f or t. */
    static boolean isFormatString(CharSequence text, int floor, int quote) {
        int k = quote;
        boolean format = false;
        while (k > floor && quote - k < 3 && "fFtTrRbBuU".indexOf(text.charAt(k - 1)) >= 0) {
            char p = Character.toLowerCase(text.charAt(k - 1));
            format |= p == 'f' || p == 't';
            k--;
        }
        if (k == quote || quote - k > 2) {
            return false;
        }
        return format && (k == floor || !(Character.isLetterOrDigit(text.charAt(k - 1)) || text.charAt(k - 1) == '_'));
    }

    /** The first word of the code at {@code line}'s indent ({@code ""} when it does not start with a letter). */
    public static String firstWord(CharSequence text, Line line) {
        int i = line.indentEnd();
        int j = i;
        while (j < line.end() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        return text.subSequence(i, j).toString();
    }

    /** The first word, skipping a leading {@code async} ({@code async for} → {@code for}). */
    public static String keyword(CharSequence text, Line line) {
        String w = firstWord(text, line);
        if (!w.equals("async")) {
            return w;
        }
        int i = line.indentEnd() + w.length();
        while (i < line.end() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        int j = i;
        while (j < line.end() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        return text.subSequence(i, j).toString();
    }

    /** Index of the line holding {@code offset} in {@code lines} (the last line when past the end). */
    public static int lineAt(List<Line> lines, int offset) {
        int lo = 0;
        int hi = lines.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lines.get(mid).start() <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Index of the line that begins the statement {@code index} belongs to. */
    public static int statementStart(List<Line> lines, int index) {
        int i = index;
        while (i > 0 && !lines.get(i).logicalStart()) {
            i--;
        }
        return i;
    }

    /** Index of the last line of the statement that begins at {@code index}. */
    public static int statementEnd(List<Line> lines, int index) {
        int i = index;
        while (i + 1 < lines.size() && !lines.get(i + 1).logicalStart()) {
            i++;
        }
        return i;
    }

    private static boolean startsWith(CharSequence text, int i, char c, int count, int limit) {
        if (i + count > limit) {
            return false;
        }
        for (int k = 0; k < count; k++) {
            if (text.charAt(i + k) != c) {
                return false;
            }
        }
        return true;
    }
}
