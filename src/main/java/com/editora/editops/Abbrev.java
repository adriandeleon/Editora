package com.editora.editops;

import java.util.Locale;
import java.util.Map;

/**
 * A simple abbreviation expander: the word immediately before a point, looked up (case-insensitively) in a
 * user dictionary and replaced with its expansion. Pure and toolkit-free (mirroring {@link EmacsEdits}); the
 * buffer applies the returned {@link Edit}.
 *
 * <p>A lighter take on Emacs {@code abbrev-mode} — a flat text-replacement table, not the full mode-local
 * abbrev machinery. The typed word's case is carried onto the expansion via {@link PreserveCase} (so
 * {@code btw}→{@code by the way}, {@code Btw}→{@code By the way}, {@code BTW}→{@code BY THE WAY}).
 */
public final class Abbrev {

    /** Replace {@code [from, to)} with {@code replacement}. */
    public record Edit(int from, int to, String replacement) {}

    private Abbrev() {}

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** Start of the word (letters/digits) ending at {@code end}; equal to {@code end} when there is none. */
    public static int wordStart(String text, int end) {
        int i = Math.max(0, Math.min(end, text.length()));
        while (i > 0 && isWordChar(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * The expansion for the word ending at {@code point}, or {@code null} when that word is not an
     * abbreviation (or the expansion equals the word). {@code table} is keyed by <b>lower-cased</b>
     * abbreviation — the caller lower-cases the keys once when building it.
     */
    public static Edit expand(String text, int point, Map<String, String> table) {
        if (text == null || table == null || table.isEmpty()) {
            return null;
        }
        int end = Math.max(0, Math.min(point, text.length()));
        int start = wordStart(text, end);
        if (start == end) {
            return null;
        }
        String word = text.substring(start, end);
        String expansion = table.get(word.toLowerCase(Locale.ROOT));
        if (expansion == null || expansion.isEmpty()) {
            return null;
        }
        String replacement = adaptCase(word, expansion);
        if (replacement.equals(word)) {
            return null; // no-op (e.g. the abbrev maps to itself)
        }
        return new Edit(start, end, replacement);
    }

    /**
     * Abbrev case adaptation (Emacs-style): a lower-cased abbrev inserts the expansion <b>verbatim</b> (so a
     * stored capital survives — "as far as I know"); an all-caps abbrev upper-cases it; a Capitalized abbrev
     * capitalizes the expansion's first letter. Deliberately not {@link PreserveCase}, which would lower-case
     * the whole expansion for a lower-case match.
     */
    static String adaptCase(String word, String expansion) {
        boolean hasLetter = false;
        boolean allUpper = true;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) {
                    allUpper = false;
                }
            }
        }
        if (hasLetter && allUpper && word.length() > 1) {
            return expansion.toUpperCase(Locale.ROOT);
        }
        if (hasLetter && Character.isUpperCase(word.charAt(0)) && !allUpper) {
            return capitalizeFirst(expansion);
        }
        return expansion; // lower-case, single upper letter, or no case signal → verbatim
    }

    private static String capitalizeFirst(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return s.substring(0, i) + Character.toUpperCase(s.charAt(i)) + s.substring(i + 1);
            }
        }
        return s;
    }
}
