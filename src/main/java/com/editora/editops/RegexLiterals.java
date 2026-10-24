package com.editora.editops;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * JavaScript/TypeScript regular-expression literals, for the light scanners that are not the tokenizer (the
 * fold walker, the declaration index). A {@code /} starts one where an operand is expected — at the start, after
 * an operator or an opening bracket, or after {@code return}, {@code typeof}, {@code case} and the like — and
 * divides after an operand. Without this, a regex holding a backtick ({@code s.split(/`/)}) read as the start
 * of a template literal that ran to the next backtick, and one holding an escaped brace opened a block that never
 * closed. Pure.
 */
public final class RegexLiterals {

    private RegexLiterals() {}

    /** Words after which a {@code /} starts a regular expression rather than dividing. */
    private static final Set<String> REGEX_AFTER_WORD = Set.of(
            "return",
            "typeof",
            "instanceof",
            "in",
            "of",
            "new",
            "delete",
            "void",
            "throw",
            "case",
            "do",
            "else",
            "yield",
            "await");

    /** Whether {@code language} has regex literals ({@code javascript*}, {@code typescript*}). */
    public static boolean in(@Nullable String language) {
        return language != null && (language.startsWith("javascript") || language.startsWith("typescript"));
    }

    /**
     * Index of the last character of the regex literal opened by the {@code /} at {@code i}, or {@code i} itself
     * when that slash divides. The caller has already ruled out {@code //} and {@code /*}. A literal not closed on
     * its line is taken as a division.
     */
    public static int end(String text, int i) {
        if (!operandExpected(text, i)) {
            return i;
        }
        boolean inClass = false;
        for (int j = i + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '\n') {
                return i;
            } else if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                return j;
            }
        }
        return i;
    }

    /**
     * Whether {@code lineBeforeCaret} ends inside a regex literal still being typed ({@code const r = /['}): one
     * opened where an operand is expected and not closed yet. The highlighter cannot say so, since an unclosed
     * regex is not a regex to it. Line and block comments end the search; strings are not tracked.
     */
    public static boolean openAtEnd(String lineBeforeCaret) {
        for (int i = 0; i < lineBeforeCaret.length(); i++) {
            if (lineBeforeCaret.charAt(i) != '/') {
                continue;
            }
            if (lineBeforeCaret.startsWith("//", i) || lineBeforeCaret.startsWith("/*", i)) {
                return false;
            }
            if (operandExpected(lineBeforeCaret, i)) {
                int end = end(lineBeforeCaret, i);
                if (end == i) {
                    return true; // opened and not closed before the caret
                }
                i = end;
            }
        }
        return false;
    }

    /** Whether the code before {@code i} ends where an operand (so a regex) may begin. */
    private static boolean operandExpected(String text, int i) {
        int k = i - 1;
        while (k >= 0 && Character.isWhitespace(text.charAt(k))) {
            k--;
        }
        if (k < 0) {
            return true;
        }
        char p = text.charAt(k);
        if (isWordPart(p)) {
            int start = k;
            while (start > 0 && isWordPart(text.charAt(start - 1))) {
                start--;
            }
            return REGEX_AFTER_WORD.contains(text.substring(start, k + 1));
        }
        return "(,=:[!&|?{};+-*%<>~^".indexOf(p) >= 0;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
