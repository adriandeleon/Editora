package com.editora.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ripgrep as a file pre-filter: what Find in Files lists for a closed file is what the Java matcher finds in
 * it — the same spans {@link MultiFileSearch#replaceAll} rewrites — not what ripgrep's own dialect matched.
 */
class SearchServiceRematchTest {

    private static final Path POSIX = Path.of("posix.txt");
    private static final String POSIX_TEXT = "item 123 digit 45\nedit 7\n";

    /** What rg reports for {@code [[:digit:]]+} over {@link #POSIX_TEXT}: the digits. */
    private static List<FileResult> ripgrepDigits() {
        return new ArrayList<>(List.of(new FileResult(
                POSIX,
                List.of(
                        new LineMatch(1, 6, 3, "item 123 digit 45"),
                        new LineMatch(1, 16, 2, "item 123 digit 45"),
                        new LineMatch(2, 6, 1, "edit 7")))));
    }

    private static int replaced(String text, SearchQuery q) {
        return MultiFileSearch.replaceAll(text, q, "N", MultiFileSearch.UNICODE_CLASSES)
                .count();
    }

    @Test
    void aPosixClassIsListedTheWayReplaceAllWillReadIt() {
        SearchQuery q = new SearchQuery("[[:digit:]]+", true, true, false);
        List<FileResult> files = ripgrepDigits();

        assertFalse(SearchService.rematch(files, q, Map.of(POSIX, POSIX_TEXT)::get, 100, () -> false));

        // java.util.regex reads [[:digit:]] as the set {: d i g t}: "it", "digit", "dit" - and no digit.
        List<LineMatch> listed = files.get(0).matches();
        assertEquals(List.of("1:1+2", "1:10+5", "2:2+3"), spans(listed));
        assertEquals(listed.size(), replaced(POSIX_TEXT, q), "Replace All rewrites exactly the listed matches");
    }

    @Test
    void aFileOnlyRipgrepsDialectMatchesIsDropped() {
        SearchQuery q = new SearchQuery("\\<foo\\>", true, true, false);
        Path words = Path.of("words.txt");
        Path tags = Path.of("tags.txt");
        List<FileResult> files = new ArrayList<>(List.of(
                new FileResult(words, List.of(new LineMatch(1, 3, 3, "a foo b"))),
                new FileResult(
                        tags,
                        List.of(
                                new LineMatch(1, 3, 3, "a foo b <foo> c"),
                                new LineMatch(1, 10, 3, "a foo b <foo> c")))));

        SearchService.rematch(files, q, Map.of(words, "a foo b\n", tags, "a foo b <foo> c\n")::get, 100, () -> false);

        assertEquals(1, files.size(), "Replace All would change nothing in words.txt, so it is not listed");
        assertEquals(tags, files.get(0).file());
        assertEquals(List.of("1:9+5"), spans(files.get(0).matches()));
        assertEquals(0, replaced("a foo b\n", q));
        assertEquals(1, replaced("a foo b <foo> c\n", q));
    }

    @Test
    void wholeWordWithAPunctuationEdgeListsWhatReplaceAllChanges() {
        // rg -w matches the standalone "@Override"; the Java \\b...\\b reading matches only "x@Override".
        SearchQuery q = new SearchQuery("@Override", true, false, true);
        Path file = Path.of("Anno.java");
        String text = "class A {\n    @Override\n    int x@Override;\n}\n";
        List<FileResult> files =
                new ArrayList<>(List.of(new FileResult(file, List.of(new LineMatch(2, 5, 9, "    @Override")))));

        SearchService.rematch(files, q, Map.of(file, text)::get, 100, () -> false);

        assertEquals(List.of("3:10+9"), spans(files.get(0).matches()));
        assertEquals(1, replaced(text, q));
    }

    @Test
    void anUnreadableFileKeepsRipgrepsMatchesAndTheLimitTruncates() {
        SearchQuery q = new SearchQuery("[[:digit:]]+", true, true, false);
        List<FileResult> unreadable = ripgrepDigits();
        assertFalse(SearchService.rematch(unreadable, q, path -> null, 100, () -> false));
        assertEquals(3, unreadable.get(0).matches().size());

        Path second = Path.of("second.txt");
        List<FileResult> files = ripgrepDigits();
        files.add(new FileResult(second, List.of(new LineMatch(1, 1, 1, "7"))));
        assertTrue(SearchService.rematch(files, q, Map.of(POSIX, POSIX_TEXT, second, "it")::get, 2, () -> false));
        assertEquals(1, files.size());
        assertEquals(2, files.get(0).matches().size());
    }

    @Test
    void onlyACaseSensitiveLiteralIsLeftToRipgrep() {
        assertTrue(SearchService.sameInBothEngines(new SearchQuery("foo", true, false, false)));
        assertFalse(SearchService.sameInBothEngines(new SearchQuery("foo", false, false, false)));
        assertFalse(SearchService.sameInBothEngines(new SearchQuery("foo", true, true, false)));
        assertFalse(SearchService.sameInBothEngines(new SearchQuery("foo", true, false, true)));
    }

    private static List<String> spans(List<LineMatch> matches) {
        return matches.stream()
                .map(m -> m.line() + ":" + m.col() + "+" + m.length())
                .toList();
    }
}
