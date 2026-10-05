package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The line-oriented search must treat an empty line as a line: {@code ^}, {@code ^$} and {@code ^\\s*$}
 * match it, in both regex dialects, exactly as ripgrep reports for the same file while it is closed.
 */
class MultiFileSearchBlankLineTest {

    private static final String TEXT = "first\n\nthird\n\n\nsixth";

    private static List<Integer> lines(String regex, boolean unicodeClasses) {
        SearchQuery q = new SearchQuery(regex, true, true, false);
        return MultiFileSearch.matchesInText(TEXT, q, Integer.MAX_VALUE, unicodeClasses).stream()
                .map(LineMatch::line)
                .toList();
    }

    @Test
    void blankLinePatternsFindTheEmptyLines() {
        for (boolean unicode : new boolean[] {false, true}) {
            assertEquals(List.of(2, 4, 5), lines("^$", unicode));
            assertEquals(List.of(2, 4, 5), lines("^\\s*$", unicode));
        }
    }

    @Test
    void replacingLineStartPrefixesEmptyLinesToo() {
        SearchQuery q = new SearchQuery("^", true, true, false);
        for (boolean unicode : new boolean[] {false, true}) {
            MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("a\n\nb", q, "> ", unicode);
            assertEquals("> a\n> \n> b", r.text());
        }
    }
}
