package com.editora.editops;

import java.util.Locale;
import java.util.Map;

/**
 * A simple abbreviation expander: the text immediately before a point, looked up (case-insensitively) in a
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

    /**
     * Whether inserting {@code inserted} ends the word before it the way one typed terminator does. Besides
     * a single non-word character that is what the editor's own typing assists insert for one keystroke:
     * Enter with auto-indent (a newline followed by indentation) and an auto-closed pair ({@code ()},
     * {@code ""}). Requiring exactly one character missed both, so an abbreviation stayed unexpanded before
     * Enter on any indented line and before every bracket or quote. A paste is neither, and still never
     * expands.
     */
    public static boolean terminates(String inserted) {
        if (inserted == null || inserted.isEmpty() || isWordChar(inserted.charAt(0))) {
            return false;
        }
        if (inserted.length() == 1) {
            return true;
        }
        if (inserted.charAt(0) == '\n') {
            return inserted.chars().skip(1).allMatch(c -> c == ' ' || c == '\t');
        }
        return inserted.length() == 2 && PAIRS.contains(inserted);
    }

    /** The opener + closer pairs auto-close inserts as one change. */
    private static final java.util.Set<String> PAIRS = java.util.Set.of("()", "[]", "{}", "<>", "\"\"", "''", "``");

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
     * The expansion for the abbreviation ending at {@code point}, or {@code null} when the text there is not
     * an abbreviation (or the expansion equals it). {@code table} is keyed by <b>lower-cased</b>
     * abbreviation — the caller lower-cases the keys once when building it.
     *
     * <p>An abbreviation need not be a single word: {@code adl-fn}, {@code ;sig} and {@code e.g} are looked
     * up too. Only letters and digits were scanned, so such an entry could be defined and never expanded.
     * Every start between the preceding whitespace and {@code point} that begins a word or a run of
     * punctuation is tried, longest first, so {@code adl-fn} wins over a separate {@code fn}.
     */
    public static Edit expand(String text, int point, Map<String, String> table) {
        if (text == null || table == null || table.isEmpty()) {
            return null;
        }
        int end = Math.max(0, Math.min(point, text.length()));
        int tokenStart = end;
        while (tokenStart > 0 && !Character.isWhitespace(text.charAt(tokenStart - 1))) {
            tokenStart--;
        }
        for (int start = tokenStart; start < end; start++) {
            if (start > tokenStart && isWordChar(text.charAt(start - 1)) && isWordChar(text.charAt(start))) {
                continue; // inside a word: an abbreviation never starts there
            }
            String word = text.substring(start, end);
            String expansion = table.get(word.toLowerCase(Locale.ROOT));
            if (expansion == null || expansion.isEmpty()) {
                continue;
            }
            String replacement = adaptCase(word, expansion);
            // A no-op (the abbrev maps to itself) expands nothing, rather than falling back to a shorter one.
            return replacement.equals(word) ? null : new Edit(start, end, replacement);
        }
        return null;
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
