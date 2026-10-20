package com.editora.editor;

import org.jspecify.annotations.Nullable;

/**
 * Which lines hold no code a debugger could stop on: blank lines and lines that are only a comment. A breakpoint
 * there was accepted, and java-debug even reported it verified, yet it never stopped (B8) — IntelliJ and NetBeans
 * do not let one be set there at all. Decided from the text alone (no language server), for the comment syntax
 * of the languages Editora debugs: {@code //} and {@code /* … *}{@code /} for Java and JavaScript, {@code #} for
 * Python. Other languages: only blank lines. Errs towards "code": a line it cannot place is left to the adapter.
 * Pure — tested.
 */
public final class CodeLessLines {

    private CodeLessLines() {}

    /** Whether 0-based {@code line} of {@code text} is blank or only a comment, in {@code language}. */
    public static boolean isCodeLess(String text, int line, String language) {
        if (text == null || line < 0) {
            return false;
        }
        String[] lines = text.split("\n", -1);
        if (line >= lines.length) {
            return false;
        }
        String own = lines[line].strip();
        if (own.isEmpty()) {
            return true;
        }
        String lang = language == null ? "" : language;
        return switch (lang) {
            case "python" -> own.startsWith("#");
            case "java", "javascript", "typescript" -> cStyleCommentOnly(lines, line);
            default -> false;
        };
    }

    /** Whether {@code line} of a C-style source is only comment, given the block comments opened above it. */
    private static boolean cStyleCommentOnly(String[] lines, int line) {
        boolean inBlock = false;
        boolean inTextBlock = false;
        for (int i = 0; i < line; i++) {
            int[] state = scan(lines[i], inBlock, inTextBlock, null);
            inBlock = state[0] == 1;
            inTextBlock = state[1] == 1;
        }
        if (inTextBlock) {
            return false; // a line of a text block belongs to the statement that holds it: leave it to the adapter
        }
        boolean[] code = {false};
        scan(lines[line], inBlock, false, code);
        return !code[0];
    }

    /**
     * Scans one line from the given state; returns {inBlockComment, inTextBlock} at its end. When {@code code}
     * is given, sets it if any character outside a comment is not whitespace.
     */
    private static int[] scan(String s, boolean inBlock, boolean inTextBlock, boolean @Nullable [] code) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (inBlock) {
                if (c == '*' && i + 1 < n && s.charAt(i + 1) == '/') {
                    inBlock = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (inTextBlock) {
                if (s.startsWith("\"\"\"", i)) {
                    inTextBlock = false;
                    i += 3;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                break; // the rest of the line is a comment
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                inBlock = true;
                i += 2;
                continue;
            }
            if (!Character.isWhitespace(c) && code != null) {
                code[0] = true;
            }
            if (s.startsWith("\"\"\"", i)) {
                inTextBlock = true;
                i += 3;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(s, i, c);
                continue;
            }
            i++;
        }
        return new int[] {inBlock ? 1 : 0, inTextBlock ? 1 : 0};
    }

    /** The index after the string or char literal opened at {@code start} (or the line's end). */
    private static int skipQuoted(String s, int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return i;
    }
}
