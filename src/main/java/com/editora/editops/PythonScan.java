package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

/**
 * A small line-level reader of Python source — pure, no toolkit — for the features that need to know where
 * Python's statements begin and end without a parser: Enter/closer indentation ({@link PythonIndent}) and
 * indentation-based folding ({@code editor/FoldRegions}).
 *
 * <p>It understands just enough lexical structure to tell code from data: comments, single- and
 * triple-quoted strings (any prefix, escapes, a triple-quoted string spanning lines), bracket depth, and a
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
        List<Line> out = new ArrayList<>();
        int depth = 0;
        char triple = 0; // the quote char of an open triple-quoted string
        boolean continued = false;
        int pos = 0;
        while (pos <= n) {
            int end = pos;
            while (end < n && text.charAt(end) != '\n') {
                end++;
            }
            boolean startsInString = triple != 0;
            boolean logical = !startsInString && depth == 0 && !continued;
            int indentEnd = pos;
            while (indentEnd < end && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) {
                indentEnd++;
            }
            boolean blank = !startsInString && indentEnd == end;
            boolean commentOnly = !startsInString && indentEnd < end && text.charAt(indentEnd) == '#';
            char lastCode = 0;
            boolean lineContinued = false;
            int i = pos;
            while (i < end) {
                char c = text.charAt(i);
                if (triple != 0) {
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == triple && i + 2 < end + 1 && startsWith(text, i, triple, 3, end)) {
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
                    int j = i + 1;
                    while (j < end && text.charAt(j) != c) {
                        j += text.charAt(j) == '\\' ? 2 : 1;
                    }
                    lastCode = c;
                    i = Math.min(j, end) + 1; // an unterminated string ends with its line
                    continue;
                }
                if (c == '\\' && i + 1 >= end) {
                    lineContinued = true;
                    i++;
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                    depth--;
                }
                if (!Character.isWhitespace(c)) {
                    lastCode = c;
                }
                i++;
            }
            out.add(new Line(
                    pos, end, indentEnd, logical, blank, commentOnly, lastCode, depth, triple != 0, lineContinued));
            continued = lineContinued && triple == 0;
            if (end >= n) {
                break;
            }
            pos = end + 1;
        }
        return out;
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
