package com.editora.editor;

import java.util.regex.Pattern;

/**
 * Pure, unit-tested heuristic for detecting a <strong>Java 25 compact source file</strong> (JEP 512):
 * a {@code .java} file whose members live directly at the top level inside an implicitly-declared class,
 * rather than inside an explicit {@code class}/{@code interface}/etc. The distinguishing, launchable
 * signal we look for is a {@code void main(...)} method declared at <em>brace depth 0</em> — in a normal
 * Java file every method sits inside a type (depth ≥ 1), so a {@code main} at depth 0 can only be the
 * top-level entry point of a compact source file.
 *
 * <p>String/char/text-block literals and comments are blanked out first (replaced with spaces, preserving
 * newlines) so a {@code "void main("} inside a string or a {@code //} comment never triggers a match and
 * stray braces inside literals don't skew the depth count. No real parser is used — this only gates a UI
 * affordance (the toolbar Run button), so a cheap, conservative text scan is the right tradeoff.
 */
public final class CompactSource {

    /** A {@code void main(} method header (any modifiers/return-type spacing), used to find the entry point. */
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");

    private CompactSource() {
    }

    /**
     * True if {@code fileName} is a {@code .java} file and {@code source} is a launchable compact source
     * file (a top-level {@code void main(...)} outside any explicit type).
     */
    public static boolean isLaunchable(String fileName, String source) {
        if (fileName == null || source == null || !fileName.endsWith(".java")) {
            return false;
        }
        return hasTopLevelMain(source);
    }

    /** True if a {@code void main(...)} method appears at brace depth 0 (a compact source entry point). */
    static boolean hasTopLevelMain(String source) {
        return mainLine(source) >= 0;
    }

    /**
     * The 0-based line of the top-level {@code void main(...)} entry point (for the gutter Run glyph), or
     * {@code -1} if there isn't one. {@code stripCommentsAndLiterals} preserves length and newline
     * positions, so a match offset in the cleaned text maps to the same offset (and line) in the source.
     */
    public static int mainLine(String source) {
        if (source == null) {
            return -1;
        }
        String clean = stripCommentsAndLiterals(source);
        var m = MAIN.matcher(clean);
        while (m.find()) {
            if (braceDepthAt(clean, m.start()) == 0) {
                return lineOf(source, m.start());
            }
        }
        return -1;
    }

    /** The 0-based line containing {@code offset} (newlines counted up to it). */
    static int lineOf(String s, int offset) {
        int line = 0;
        int end = Math.min(offset, s.length());
        for (int i = 0; i < end; i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Net {@code '{'} minus {@code '}'} over {@code clean[0, end)} (clamped at 0). */
    static int braceDepthAt(String clean, int end) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
        }
        return depth;
    }

    /**
     * Replaces the contents of line/block comments and string/char/text-block literals with spaces
     * (newlines preserved) so the brace/keyword scan only sees actual code. Approximate but sufficient for
     * a heuristic: unterminated tokens simply blank to end-of-input.
     */
    static String stripCommentsAndLiterals(String s) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < n && !(s.charAt(i) == '"' && i + 2 < n
                        && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
            } else if (c == '"') {
                i = blankQuoted(s, n, '"', out, i);
            } else if (c == '\'') {
                i = blankQuoted(s, n, '\'', out, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Blanks a {@code quote}-delimited literal starting at the opening quote {@code i}; returns the new index. */
    private static int blankQuoted(String s, int n, char quote, StringBuilder out, int i) {
        out.append(' ');
        i++;
        while (i < n && s.charAt(i) != quote) {
            if (s.charAt(i) == '\\' && i + 1 < n) {
                out.append("  ");
                i += 2;
            } else {
                out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                i++;
            }
        }
        if (i < n) {
            out.append(' ');
            i++;
        }
        return i;
    }
}
