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
    public record ReplaceResult(String text, int count) {}

    private MultiFileSearch() {}

    /** Every match in {@code text}, line by line (1-based line/col), each carrying its line's text. */
    public static List<LineMatch> matchesInText(String text, SearchQuery q) {
        List<LineMatch> out = new ArrayList<>();
        if (text == null
                || text.isEmpty()
                || q == null
                || q.text() == null
                || q.text().isEmpty()) {
            return out;
        }
        int line = 1;
        int start = 0;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i; // drop a trailing CR
                String lineText = text.substring(start, end);
                for (int[] m : SearchMatcher.matches(lineText, q.text(), q.caseSensitive(), q.regex(), q.wholeWord())) {
                    out.add(new LineMatch(line, m[0] + 1, m[1] - m[0], lineText));
                }
                line++;
                start = i + 1;
            }
        }
        return out;
    }

    /**
     * Replaces every match of {@code q} in {@code text}. In regex mode the replacement supports
     * capture-group references ({@code $1}/{@code ${name}}, with {@code \} escapes); in literal mode
     * {@code $}/{@code \} are inserted verbatim. A bad group reference is a graceful no-op (text unchanged).
     *
     * <p><b>Line-oriented</b>, exactly like {@link #matchesInText} — the matcher runs per line (a trailing
     * {@code \r} excluded, EOLs preserved), so what Replace All changes is precisely what the search preview
     * showed. (A whole-text regex replace instead diverged from the per-line preview: {@code ;$} matched every
     * line in the panel but only once at end-of-file, and a cross-line regex like {@code foo\nbar} matched
     * nothing in the panel yet rewrote across the newline.)
     */
    public static ReplaceResult replaceAll(String text, SearchQuery q, String replacement) {
        if (text == null) {
            return new ReplaceResult("", 0);
        }
        if (q == null || q.text() == null || q.text().isEmpty()) {
            return new ReplaceResult(text, 0);
        }
        String repl = replacement == null ? "" : replacement;
        java.util.regex.Pattern regex =
                q.regex() ? SearchMatcher.compileRegex(q.text(), q.caseSensitive(), q.wholeWord()) : null;
        if (q.regex() && regex == null) {
            return new ReplaceResult(text, 0); // bad pattern → leave the text untouched
        }
        StringBuilder out = new StringBuilder(text.length());
        int count = 0;
        int n = text.length();
        int i = 0;
        try {
            while (i <= n) {
                int nl = text.indexOf('\n', i);
                int lineEnd = nl < 0 ? n : nl;
                int contentEnd = lineEnd > i && text.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
                String content = text.substring(i, contentEnd);
                List<int[]> ms = SearchMatcher.matches(content, q.text(), q.caseSensitive(), q.regex(), q.wholeWord());
                if (ms.isEmpty()) {
                    out.append(content);
                } else if (regex != null) {
                    out.append(regex.matcher(content).replaceAll(repl)); // honors $1/${name} on this line
                    count += ms.size();
                } else {
                    int last = 0;
                    for (int[] m : ms) {
                        out.append(content, last, m[0]).append(repl);
                        last = m[1];
                    }
                    out.append(content, last, content.length());
                    count += ms.size();
                }
                out.append(text, contentEnd, nl < 0 ? n : nl + 1); // preserve this line's \r?\n terminator
                if (nl < 0) {
                    break;
                }
                i = nl + 1;
            }
        } catch (RuntimeException badReplacementRef) {
            return new ReplaceResult(text, 0); // invalid $-group reference → leave the text untouched
        }
        return new ReplaceResult(out.toString(), count);
    }
}
