package com.editora.dap;

import org.jspecify.annotations.Nullable;

/**
 * Which lines hold no code a debugger could stop on: blank lines and lines that are only a comment. A breakpoint
 * there was accepted, and java-debug even reported it verified, yet it never stopped (B8) — IntelliJ and NetBeans
 * do not let one be set there at all. Decided from the text alone (no language server), for the comment syntax
 * of the languages Editora debugs: {@code //} and {@code /* … *}{@code /} for Java, JavaScript, Go, Rust, C, C++, C# and
 * PHP, {@code #} for Python and Ruby. Other languages: only blank lines. Errs towards "code": a line it cannot place is left to the adapter.
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
            case "python", "ruby" -> own.startsWith("#");
            // PHP's `#[…]` is an attribute, so only its C-style comments count.
            case "java", "javascript", "typescript", "go", "rust", "c", "cpp", "csharp", "php" ->
                cStyleCommentOnly(lines, line, lang);
            default -> false;
        };
    }

    /** No string open at a line's end. */
    private static final int NONE = 0;

    /** A Java (or C#) text block, {@code """…"""}, open at a line's end. */
    private static final int TEXT_BLOCK = 1;

    /** A JavaScript or TypeScript template literal, {@code `…`}, open at a line's end. */
    private static final int BACKTICK = 2;

    /** A Go raw string, {@code `…`}, in which a backslash is only a backslash, open at a line's end. */
    private static final int UNESCAPED_RAW = 4;

    /** A Rust string, which may span lines, open at a line's end. */
    private static final int MULTI_LINE_STRING = 3;

    /** Whether {@code line} of a C-style source is only comment, given the block comments opened above it. */
    private static boolean cStyleCommentOnly(String[] lines, int line, String lang) {
        boolean inBlock = false;
        int open = NONE;
        for (int i = 0; i < line; i++) {
            int[] state = scan(lines[i], inBlock, open, lang, null);
            inBlock = state[0] == 1;
            open = state[1];
        }
        if (open != NONE) {
            return false; // a line of a multi-line string belongs to the statement that holds it: leave it to the
            // adapter
        }
        boolean[] code = {false};
        scan(lines[line], inBlock, NONE, lang, code);
        return !code[0];
    }

    /**
     * Scans one line from the given state; returns {inBlockComment, the multi-line string open} at its end. When
     * {@code code} is given, sets it if any character outside a comment is not whitespace.
     */
    private static int[] scan(String s, boolean inBlock, int open, String lang, boolean @Nullable [] code) {
        boolean js = lang.equals("javascript") || lang.equals("typescript");
        int i = 0;
        int n = s.length();
        char previous = 0; // the last code character before i on this line, 0 at its start
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
            if (open != NONE) {
                int end = closeOf(s, i, open);
                if (end < 0) {
                    break; // still open at the line's end
                }
                open = NONE;
                i = end;
                previous = '"';
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
                open = TEXT_BLOCK;
                i += 3;
                continue;
            }
            if (c == '`' && (js || lang.equals("go"))) {
                open = js ? BACKTICK : UNESCAPED_RAW;
                i++;
                continue;
            }
            if (c == '"' && lang.equals("rust")) {
                open = MULTI_LINE_STRING;
                i++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(s, i, c);
                previous = c;
                continue;
            }
            if (c == '/' && js && regexMayStart(s, i, previous)) {
                int end = regexEnd(s, i);
                if (end > 0) {
                    i = end;
                    previous = '/';
                    continue;
                }
            }
            if (!Character.isWhitespace(c)) {
                previous = c;
            }
            i++;
        }
        return new int[] {inBlock ? 1 : 0, open};
    }

    /** The index after the end of the {@code open} string in {@code s} from {@code from}, or -1 when it stays open. */
    private static int closeOf(String s, int from, int open) {
        String close = open == TEXT_BLOCK ? "\"\"\"" : open == UNESCAPED_RAW || open == BACKTICK ? "`" : "\"";
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && open != UNESCAPED_RAW) {
                i++;
            } else if (s.startsWith(close, i)) {
                return i + close.length();
            }
        }
        return -1;
    }

    /**
     * Whether a {@code /} at {@code i} of JavaScript may open a regular expression rather than divide: at the line's
     * start, after an operator or opening bracket ({@code previous}), or after {@code return}, {@code typeof} and the
     * like.
     */
    private static boolean regexMayStart(String s, int i, char previous) {
        if (previous == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(previous) >= 0) {
            return true;
        }
        int end = i;
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(s.charAt(start - 1))) {
            start--;
        }
        String word = s.substring(start, end);
        return switch (word) {
            case "return",
                    "typeof",
                    "case",
                    "do",
                    "else",
                    "in",
                    "of",
                    "new",
                    "delete",
                    "void",
                    "throw",
                    "yield",
                    "await" -> true;
            default -> false;
        };
    }

    /**
     * The index after the regular expression opened at {@code start} — its closing {@code /} unescaped and outside a
     * {@code […]} class, then its flags — or -1 when the line has no such end (then it was a division after all).
     */
    private static int regexEnd(String s, int start) {
        boolean inClass = false;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                if (i == start + 1) {
                    return -1; // "//" is a comment, not an empty regular expression
                }
                int end = i + 1;
                while (end < s.length() && Character.isLetter(s.charAt(end))) {
                    end++;
                }
                return end;
            }
        }
        return -1;
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
