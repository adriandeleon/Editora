package com.editora.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import com.jediterm.terminal.util.CharUtils;

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

    /** A search stops collecting past this many hits, keeping the newest; the count then reads "1000+". */
    public static final int MAX_MATCHES = 1000;

    /**
     * The newest {@link #MAX_MATCHES} occurrences of {@code query} in rows {@code firstRow} to {@code lastRow}
     * inclusive, returned oldest first. Rows are scanned from the bottom up, so a long scrollback full of hits
     * still finds what was printed last. Occurrences do not overlap. An empty query finds nothing.
     */
    public static List<Match> find(
            IntFunction<String> rowText, int firstRow, int lastRow, String query, boolean matchCase) {
        List<Match> newestFirst = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return newestFirst;
        }
        for (int row = lastRow; row >= firstRow && newestFirst.size() < MAX_MATCHES; row--) {
            List<Match> inRow = inRow(row, rowText.apply(row), query, matchCase);
            for (int i = inRow.size() - 1; i >= 0 && newestFirst.size() < MAX_MATCHES; i--) {
                newestFirst.add(inRow.get(i));
            }
        }
        return newestFirst.reversed();
    }

    /**
     * The occurrences of {@code query} in one row's text, left to right. The text is the buffer's: a wide character
     * is followed by {@link CharUtils#DWC}, a placeholder that is not part of the text, so it is skipped while
     * matching and a hit's slots still index the row (a hit ending on a wide character covers its placeholder).
     * Case is folded per character, so no row is copied to lower case.
     */
    static List<Match> inRow(int row, String text, String query, boolean matchCase) {
        if (text == null || text.isEmpty() || query.isEmpty()) {
            return List.of();
        }
        int dwc = text.indexOf(CharUtils.DWC);
        String hay = text;
        int[] slotOf = null;
        if (dwc >= 0) {
            StringBuilder plain = new StringBuilder(text.length());
            slotOf = new int[text.length() + 1];
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != CharUtils.DWC) {
                    slotOf[plain.length()] = i;
                    plain.append(text.charAt(i));
                }
            }
            slotOf[plain.length()] = text.length();
            hay = plain.toString();
        }
        int length = query.length();
        if (hay.length() < length) {
            return List.of();
        }
        List<Match> found = new ArrayList<>();
        char first = query.charAt(0);
        char upper = Character.toUpperCase(first);
        char lower = Character.toLowerCase(first);
        for (int at = 0; at <= hay.length() - length; at++) {
            char c = hay.charAt(at);
            boolean candidate = matchCase ? c == first : c == first || c == upper || c == lower;
            if (candidate && hay.regionMatches(!matchCase, at, query, 0, length)) {
                if (slotOf == null) {
                    found.add(new Match(row, at, at + length));
                } else {
                    int to = slotOf[at + length - 1] + 1;
                    if (to < text.length() && text.charAt(to) == CharUtils.DWC) {
                        to++;
                    }
                    found.add(new Match(row, slotOf[at], to));
                }
                at += length - 1;
            }
        }
        return found;
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
