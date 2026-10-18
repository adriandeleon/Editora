package com.editora.index;

import com.editora.editops.Commenter.CommentStyle;
import com.editora.editops.ShellScan;

/**
 * Replaces the contents of comments and string literals with spaces, preserving every offset.
 *
 * <p>Without this a declaration scanner matches inside prose and data — the word "class" in a Javadoc
 * paragraph, a {@code def} in a docstring, a SQL string containing {@code create function}. Those are the
 * false positives that make a heuristic index feel untrustworthy, and they are cheap to remove.
 *
 * <p><b>Length-preserving on purpose.</b> Every offset in the blanked text is the offset in the original,
 * so a scanner can report positions directly without an offset map. This is the same rule
 * {@code editor/CompactSource} and {@code test/JavaTestScanner} follow, and the same hazard
 * {@code completion/MatchHighlighter} documents for lowercased copies: a transformation that changes
 * length silently drifts every index computed from it.
 *
 * <p>Newlines are kept as newlines so line numbering survives, including inside a block comment or a
 * multi-line string.
 */
final class SourceBlanker {

    private SourceBlanker() {}

    /** {@code text} with comment and string-literal contents replaced by spaces, same length. */
    static String blank(String text, CommentStyle style) {
        return blank(text, style, null);
    }

    /**
     * As {@link #blank(String, CommentStyle)}, reading quotes as {@code language} does. In Clojure {@code '} and
     * a backtick are the quote reader macros ({@code 'sym}, {@code `(list)}), {@code \c} is a character literal
     * ({@code \'}, {@code \"}, {@code \;}), a single {@code ;} starts a comment and a string may span lines; in
     * Scala a {@code '} opens a character literal only ({@code 'a'}, {@code '\n'}), never Scala 2's
     * {@code 'symbol}, and a backtick quotes an identifier ({@code `type`}), which stays code. Read as strings,
     * each of those blanked the code after it up to the next quote — and a Clojure {@code \"}, opening a string
     * that spans lines, everything after it.
     */
    static String blank(String text, CommentStyle style, String language) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        boolean clojure = "clojure".equals(language);
        boolean scala = "scala".equals(language);
        char[] out = text.toCharArray();
        String lineComment = clojure ? ";" : style != null && style.hasLine() ? style.line() : null;
        String blockStart = style != null && style.hasBlock() ? style.blockStart() : null;
        String blockEnd = style != null && style.hasBlock() ? style.blockEnd() : null;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (clojure && (c == '\\' || c == '\'' || c == '`')) {
                i += c == '\\' ? 2 : 1; // a character literal skips the character it names
                continue;
            }
            if (scala && ((c == '\'' && !isScalaCharLiteral(text, i)) || c == '`')) {
                i++;
                continue;
            }
            if (blockStart != null && text.startsWith(blockStart, i)) {
                int end = text.indexOf(blockEnd, i + blockStart.length());
                int stop = end < 0 ? n : end + blockEnd.length();
                blankRange(out, i, stop);
                i = stop;
                continue;
            }
            if (lineComment != null && text.startsWith(lineComment, i)) {
                int end = text.indexOf('\n', i);
                int stop = end < 0 ? n : end;
                blankRange(out, i, stop);
                i = stop;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = blankString(text, out, i, c, clojure); // every Clojure string may span lines
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** Whether the {@code '} at {@code i} opens a Scala character literal: {@code 'a'} or an escape {@code '\n'}. */
    private static boolean isScalaCharLiteral(String text, int i) {
        int n = text.length();
        if (i + 1 >= n || text.charAt(i + 1) == '\n') {
            return false;
        }
        if (text.charAt(i + 1) == '\\') {
            return true;
        }
        int next = i + 1 + Character.charCount(text.codePointAt(i + 1));
        return next < n && text.charAt(next) == '\'';
    }

    /**
     * Blanks the string literal opening at {@code start} and returns the index just past it. Handles a
     * triple-quoted literal (Python's docstring, Java's text block) by looking for the same delimiter
     * tripled — cheap, and harmless for languages that have no such form, since three quotes in a row
     * would otherwise be read as an empty string followed by a stray opening quote.
     */
    private static int blankString(String text, char[] out, int start, char quote, boolean multiLine) {
        int n = text.length();
        String triple = String.valueOf(quote).repeat(3);
        boolean isTriple = text.startsWith(triple, start);
        int delimLen = isTriple ? 3 : 1;
        int i = start + delimLen;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2; // an escape consumes the next character, whatever it is
                continue;
            }
            if (isTriple ? text.startsWith(triple, i) : c == quote) {
                int stop = i + delimLen;
                blankRange(out, start, stop);
                return stop;
            }
            // An unterminated single-quoted literal must not swallow the rest of the file; a newline ends
            // it. A triple-quoted one legitimately spans lines, so it keeps going — and so does a backtick
            // literal (a JS template, a Go raw string), whose body was otherwise scanned as code.
            if (c == '\n' && !isTriple && quote != '`' && !multiLine) {
                blankRange(out, start, i);
                return i;
            }
            i++;
        }
        blankRange(out, start, n);
        return n;
    }

    /**
     * {@link #blank} for shell, which also blanks what a line-at-a-time scan cannot see is data: the lines of
     * a here-document and of a quoted string that spans lines (a usage text that mentions {@code deploy()}),
     * found with {@link ShellScan}.
     */
    static String blankShell(String text, CommentStyle style) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        ShellScan.Result r = ShellScan.scan(text);
        char[] out = text.toCharArray();
        for (int line = 0; line < r.lineCount(); line++) {
            if (r.startState(line) != ShellScan.CODE) {
                int from = r.lineStart(line);
                int to = line + 1 < r.lineCount() ? r.lineStart(line + 1) - 1 : text.length();
                blankRange(out, from, to);
            }
        }
        return blank(new String(out), style);
    }

    /** Spaces out {@code [from,to)}, leaving newlines so line numbering is unaffected. */
    private static void blankRange(char[] out, int from, int to) {
        for (int i = from; i < Math.min(to, out.length); i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
    }
}
