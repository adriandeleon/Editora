package com.editora.todo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Pure scanner: finds every configured-pattern hit in a text, line by line, returning {@link TodoMatch}es
 * with both absolute offsets (for the in-editor highlight) and 1-based line/col + the line's text (for the
 * tool-window list). Mirrors {@code MultiFileSearch.matchesInText}; no JavaFX / no IO, so it is unit-tested.
 */
public final class TodoScanner {

    private TodoScanner() {}

    /** Every match of {@code patterns} in {@code text}, in document order (by start offset). */
    public static List<TodoMatch> scan(String text, List<TodoPatterns.Compiled> patterns) {
        List<TodoMatch> out = new ArrayList<>();
        if (text == null || text.isEmpty() || patterns == null || patterns.isEmpty()) {
            return out;
        }
        int line = 1;
        int start = 0;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i; // drop a trailing CR
                scanLine(text.substring(start, end), start, line, patterns, out);
                line++;
                start = i + 1;
            }
        }
        out.sort(Comparator.comparingInt(TodoMatch::start));
        return out;
    }

    private static void scanLine(
            String lineText, int lineStart, int line, List<TodoPatterns.Compiled> patterns, List<TodoMatch> out) {
        if (lineText.isEmpty()) {
            return;
        }
        for (TodoPatterns.Compiled c : patterns) {
            Matcher m = c.pattern().matcher(lineText);
            int from = 0;
            while (from <= lineText.length() && m.find(from)) {
                int s = m.start();
                int e = m.end();
                if (e == s) {
                    from = s + 1; // zero-length match — step past it so we can't loop forever
                    continue;
                }
                TodoComment parsed = TodoComment.parse(lineText, s, e);
                out.add(new TodoMatch(
                        lineStart + s, lineStart + e, line, s + 1, lineText, c.name(), c.color(), parsed));
                from = e;
            }
        }
    }
}
