package com.editora.editops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recases a replacement string to match the text it replaces — the "preserve case" (AB) toggle in the
 * find bar. Pure and toolkit-free, so it is unit-tested.
 *
 * <p>Mirrors VS Code's {@code buildReplaceStringWithCasePreserved}: the <em>matched</em> text is
 * inspected in order for all-upper, all-lower, then a leading upper/lower letter, and the replacement is
 * cased to suit; anything else is left alone. A separator shared by both sides ({@code _} or {@code -})
 * with equal segment counts is handled per segment, so {@code snake_case} and {@code kebab-case} keep
 * their shape rather than being flattened by the whole-string rule.
 *
 * <p>There is deliberately no camelCase/PascalCase detection beyond the first-character rule — that is
 * also where VS Code stops, and guessing further produces surprising rewrites.
 *
 * <p>All case mapping uses {@link Locale#ROOT} so a Turkish locale cannot turn {@code i} into {@code İ},
 * matching {@code editor.CaseFold}.
 */
public final class PreserveCase {

    /** Separators whose segments are cased independently when both sides share one. */
    private static final char[] SEPARATORS = {'_', '-'};

    private PreserveCase() {}

    /**
     * {@code replacement} recased after {@code matched}. Returns {@code replacement} unchanged when either
     * side is null/empty or {@code matched} carries no usable case signal.
     */
    public static String apply(String matched, String replacement) {
        if (matched == null || matched.isEmpty() || replacement == null || replacement.isEmpty()) {
            return replacement;
        }
        int sep = sharedSeparator(matched, replacement);
        if (sep >= 0) {
            List<String> m = split(matched, (char) sep);
            List<String> r = split(replacement, (char) sep);
            if (m.size() == r.size()) {
                StringBuilder sb = new StringBuilder(replacement.length());
                for (int i = 0; i < r.size(); i++) {
                    if (i > 0) {
                        sb.append((char) sep);
                    }
                    sb.append(applyOne(m.get(i), r.get(i)));
                }
                return sb.toString();
            }
        }
        return applyOne(matched, replacement);
    }

    /** The whole-string rule, also used per segment by {@link #apply}. */
    private static String applyOne(String matched, String replacement) {
        if (matched.isEmpty() || replacement.isEmpty()) {
            return replacement;
        }
        if (isAllUpper(matched)) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (isAllLower(matched)) {
            return replacement.toLowerCase(Locale.ROOT);
        }
        int first = matched.codePointAt(0);
        if (Character.isUpperCase(first)) {
            return mapFirst(replacement, true);
        }
        if (Character.isLowerCase(first)) {
            return mapFirst(replacement, false);
        }
        return replacement;
    }

    /** The first separator present in <b>both</b> strings, or -1. */
    private static int sharedSeparator(String matched, String replacement) {
        for (char c : SEPARATORS) {
            if (matched.indexOf(c) >= 0 && replacement.indexOf(c) >= 0) {
                return c;
            }
        }
        return -1;
    }

    /** Splits on {@code sep} keeping empty segments, so segment counts stay comparable. */
    private static List<String> split(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /**
     * True when {@code s} has at least one letter and every letter is uppercase. An <em>uncased</em> letter
     * (CJK, and the handful of title-case-only forms) makes this false rather than true — such text carries
     * no case signal, so the caller should leave the replacement alone.
     */
    private static boolean isAllUpper(String s) {
        boolean sawLetter = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                if (!Character.isUpperCase(cp)) {
                    return false;
                }
                sawLetter = true;
            }
        }
        return sawLetter;
    }

    /** True when {@code s} has at least one letter and every letter is lowercase. */
    private static boolean isAllLower(String s) {
        boolean sawLetter = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                if (!Character.isLowerCase(cp)) {
                    return false;
                }
                sawLetter = true;
            }
        }
        return sawLetter;
    }

    /**
     * {@code s} with its first code point upper- or lower-cased, rest untouched. Code-point aware so a
     * surrogate pair is not split. Uses the single-code-point mapping, so a length-changing fold
     * ({@code ß}→{@code SS}) is deliberately not applied here — only the first character changes.
     */
    private static String mapFirst(String s, boolean upper) {
        int cp = s.codePointAt(0);
        int mapped = upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
        if (mapped == cp) {
            return s;
        }
        return new String(Character.toChars(mapped)) + s.substring(Character.charCount(cp));
    }
}
