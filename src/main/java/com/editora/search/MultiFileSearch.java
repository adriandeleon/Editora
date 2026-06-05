package com.editora.search;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.SearchMatcher;

/**
 * Pure helpers for multi-file search: find per-line matches in a text, and apply a replacement to every
 * match. Reuses {@link SearchMatcher}. No JavaFX / no IO, so it is unit-tested.
 */
public final class MultiFileSearch {

    /** Outcome of a whole-text replace: the new text and how many matches were replaced. */
    public record ReplaceResult(String text, int count) {
    }

    private MultiFileSearch() {
    }

    /** Every match in {@code text}, line by line (1-based line/col), each carrying its line's text. */
    public static List<LineMatch> matchesInText(String text, SearchQuery q) {
        List<LineMatch> out = new ArrayList<>();
        if (text == null || text.isEmpty() || q == null || q.text() == null || q.text().isEmpty()) {
            return out;
        }
        int line = 1;
        int start = 0;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i; // drop a trailing CR
                String lineText = text.substring(start, end);
                for (int[] m : SearchMatcher.matches(lineText, q.text(), q.caseSensitive(), q.regex(),
                        q.wholeWord())) {
                    out.add(new LineMatch(line, m[0] + 1, m[1] - m[0], lineText));
                }
                line++;
                start = i + 1;
            }
        }
        return out;
    }

    /** Replaces every match of {@code q} in {@code text} with {@code replacement} (literal insertion). */
    public static ReplaceResult replaceAll(String text, SearchQuery q, String replacement) {
        if (text == null) {
            return new ReplaceResult("", 0);
        }
        List<int[]> matches = SearchMatcher.matches(text, q.text(), q.caseSensitive(), q.regex(),
                q.wholeWord());
        if (matches.isEmpty()) {
            return new ReplaceResult(text, 0);
        }
        String repl = replacement == null ? "" : replacement;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        for (int[] m : matches) {
            sb.append(text, i, m[0]).append(repl);
            i = m[1];
        }
        sb.append(text, i, text.length());
        return new ReplaceResult(sb.toString(), matches.size());
    }
}
