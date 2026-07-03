package com.editora.editops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure, unit-tested identifier case-style conversions (the String-Manipulation-plugin family):
 * {@code camelCase} / {@code PascalCase} / {@code snake_case} / {@code SCREAMING_SNAKE_CASE} /
 * {@code kebab-case} / {@code dot.case}, plus {@link #cycle} (repeated invocation steps a token
 * through the styles), {@link #swapCase}, and {@link #tokenAt} (the word-at-caret fallback used
 * when there is no selection). No toolkit dependency; the controller applies the result to the
 * active {@code CodeArea} (mirrors {@link LineOps}).
 */
public final class StringCase {

    /** The recognized identifier case styles. */
    public enum Style {
        CAMEL,
        PASCAL,
        SNAKE,
        SCREAMING_SNAKE,
        KEBAB,
        DOT
    }

    private StringCase() {}

    /**
     * Splits a token into its constituent words: separator characters ({@code _ - . space}) and
     * camel humps both delimit, and an acronym run keeps its trailing upper-case letter with the
     * next word ({@code HTTPServer} → {@code [HTTP, Server]}). Digits stay attached to the word
     * they follow. Original casing is preserved (the converters re-case).
     */
    public static List<String> words(String token) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = token.length();
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            if (c == '_' || c == '-' || c == '.' || Character.isWhitespace(c)) {
                flush(out, cur);
                continue;
            }
            if (Character.isUpperCase(c) && !cur.isEmpty()) {
                char prev = cur.charAt(cur.length() - 1);
                boolean humpAfterLower = Character.isLowerCase(prev) || Character.isDigit(prev);
                // Acronym end: "HTTPServer" — split before the upper that starts the next word.
                boolean acronymEnd =
                        Character.isUpperCase(prev) && i + 1 < n && Character.isLowerCase(token.charAt(i + 1));
                if (humpAfterLower || acronymEnd) {
                    flush(out, cur);
                }
            }
            cur.append(c);
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (!cur.isEmpty()) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /** Converts {@code token} to the given {@code style}; an empty/word-less token is returned as-is. */
    public static String to(Style style, String token) {
        List<String> words = words(token);
        if (words.isEmpty()) {
            return token;
        }
        return switch (style) {
            case CAMEL -> {
                StringBuilder sb = new StringBuilder(lower(words.get(0)));
                for (int i = 1; i < words.size(); i++) {
                    sb.append(capitalize(words.get(i)));
                }
                yield sb.toString();
            }
            case PASCAL -> {
                StringBuilder sb = new StringBuilder();
                for (String w : words) {
                    sb.append(capitalize(w));
                }
                yield sb.toString();
            }
            case SNAKE -> join(words, '_', false);
            case SCREAMING_SNAKE -> join(words, '_', true);
            case KEBAB -> join(words, '-', false);
            case DOT -> join(words, '.', false);
        };
    }

    private static String join(List<String> words, char sep, boolean upper) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) {
                sb.append(sep);
            }
            sb.append(upper ? w.toUpperCase(Locale.ROOT) : lower(w));
        }
        return sb.toString();
    }

    private static String lower(String w) {
        return w.toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + lower(w.substring(1));
    }

    /**
     * Detects the token's current case style by its separators, else its first letter's case.
     * Separator checks run underscore → dash → dot, so a mixed token classifies by its first
     * matching separator; a separator-less token is {@code PASCAL} or {@code CAMEL}.
     */
    public static Style detect(String token) {
        if (token.indexOf('_') >= 0) {
            return hasLowerLetter(token) ? Style.SNAKE : Style.SCREAMING_SNAKE;
        }
        if (token.indexOf('-') >= 0) {
            return Style.KEBAB;
        }
        if (token.indexOf('.') >= 0) {
            return Style.DOT;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                return Character.isUpperCase(c) ? Style.PASCAL : Style.CAMEL;
            }
        }
        return Style.CAMEL;
    }

    private static boolean hasLowerLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLowerCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The "toggle case" gesture: converts {@code token} to the style after its detected one in the
     * cycle {@code camelCase → snake_case → SCREAMING_SNAKE_CASE → kebab-case → PascalCase → camelCase}
     * ({@code dot.case} re-enters the cycle at {@code camelCase} — it is reachable only directly).
     */
    public static String cycle(String token) {
        Style next =
                switch (detect(token)) {
                    case CAMEL -> Style.SNAKE;
                    case SNAKE -> Style.SCREAMING_SNAKE;
                    case SCREAMING_SNAKE -> Style.KEBAB;
                    case KEBAB -> Style.PASCAL;
                    case PASCAL, DOT -> Style.CAMEL;
                };
        return to(next, token);
    }

    /** Inverts every letter's case ({@code fooBar} → {@code FOObAR}); non-letters pass through. */
    public static String swapCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append(Character.toLowerCase(c));
            } else if (Character.isLowerCase(c)) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The identifier-like token around {@code caret} — a run of letters, digits, {@code _} or
     * {@code -} (dashes included so a kebab-case token round-trips through {@link #cycle}; dots are
     * deliberately excluded — too ambiguous with member access). Returns {@code {start, end}} or
     * {@code null} when the caret touches no such character.
     */
    public static int[] tokenAt(String text, int caret) {
        int n = text.length();
        int start = Math.clamp(caret, 0, n);
        int end = start;
        while (start > 0 && isTokenChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < n && isTokenChar(text.charAt(end))) {
            end++;
        }
        return start == end ? null : new int[] {start, end};
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
