package com.editora.editops;

import java.util.List;

/**
 * Pure helpers for "select all occurrences" — turning the word/selection under the caret into a set of
 * ranges that the caller places multiple carets at. No toolkit, so it is unit-tested; the actual caret
 * placement (a fork API) and the match finding ({@code editor/SearchMatcher}) live elsewhere.
 */
public final class SelectOccurrences {

    private SelectOccurrences() {}

    /**
     * The word run ({@code [start, end)}) covering or adjacent to {@code caret}, or {@code null} when the
     * caret is not on a word — used to seed the query when there's no selection. A word char is a letter,
     * digit or underscore, matching {@code SearchMatcher}'s whole-word test.
     */
    public static int[] wordAt(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) {
            return null;
        }
        int a = caret;
        while (a > 0 && isWord(text.charAt(a - 1))) {
            a--;
        }
        int b = caret;
        while (b < text.length() && isWord(text.charAt(b))) {
            b++;
        }
        return a < b ? new int[] {a, b} : null;
    }

    /**
     * Which match should stay the primary caret: the one at or containing {@code anchorStart} (so the caret
     * stays where the user was), else the first match at/after it, else the last. Returns 0 for an empty
     * list — the caller guards against that.
     */
    public static int primaryIndex(List<int[]> matches, int anchorStart) {
        if (matches.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < matches.size(); i++) {
            int[] m = matches.get(i);
            if (anchorStart >= m[0] && anchorStart <= m[1]) {
                return i; // the anchor sits inside this match
            }
        }
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i)[0] >= anchorStart) {
                return i; // first match after the anchor
            }
        }
        return matches.size() - 1;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
