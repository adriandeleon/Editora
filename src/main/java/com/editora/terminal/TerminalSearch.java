package com.editora.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Finds text in a terminal's scrollback and screen. Pure: it reads rows through a function, so the view can feed
 * it the buffer under its lock and a test can feed it strings.
 *
 * <p>Rows are the buffer's own axis: 0 is the top of the live screen, negative reaches into history. A match is
 * found within one row; text a soft wrap split across two rows is two rows here, as in most terminals' search.
 */
public final class TerminalSearch {

    private TerminalSearch() {}

    /** One hit: the row, and the slots (indices into the row's text) it covers, end exclusive. */
    public record Match(int row, int from, int to) {

        /** The same match {@code delta} rows further up, as history grows under it. */
        Match shifted(int delta) {
            return new Match(row - delta, from, to);
        }
    }

    /** A search stops collecting past this many hits; the count then reads "1000+". */
    public static final int MAX_MATCHES = 1000;

    /**
     * Every occurrence of {@code query} in rows {@code firstRow} to {@code lastRow} inclusive, oldest first.
     * Occurrences do not overlap. An empty query finds nothing.
     */
    public static List<Match> find(
            IntFunction<String> rowText, int firstRow, int lastRow, String query, boolean matchCase) {
        List<Match> matches = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return matches;
        }
        String needle = matchCase ? query : query.toLowerCase(Locale.ROOT);
        for (int row = firstRow; row <= lastRow && matches.size() < MAX_MATCHES; row++) {
            String text = rowText.apply(row);
            if (text == null || text.length() < needle.length()) {
                continue;
            }
            String hay = matchCase ? text : text.toLowerCase(Locale.ROOT);
            if (hay.length() != text.length()) {
                hay = text; // a case mapping that changes length would misplace every hit; match exactly instead
            }
            int at = hay.indexOf(needle);
            while (at >= 0 && matches.size() < MAX_MATCHES) {
                matches.add(new Match(row, at, at + needle.length()));
                at = hay.indexOf(needle, at + needle.length());
            }
        }
        return matches;
    }

    /**
     * Where a search starts: the last match at or above {@code row} (the bottom of the view), so the first Enter
     * finds what was printed most recently, as a terminal is read bottom-up. The last match when none is.
     */
    public static int startIndex(List<Match> matches, int row) {
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i).row() <= row) {
                return i;
            }
        }
        return matches.isEmpty() ? -1 : matches.size() - 1;
    }
}
