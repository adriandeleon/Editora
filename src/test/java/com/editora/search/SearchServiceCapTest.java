package com.editora.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review E2: Find in Files said the result was complete when the match budget filled exactly at a file
 * boundary — 5,000 one-match files shown of 5,051, "not truncated".
 */
class SearchServiceCapTest {

    private static List<FileResult> oneMatchFiles(int n) {
        List<FileResult> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new FileResult(Path.of("f" + i + ".js"), List.of(new LineMatch(1, 1, 6, "needle"))));
        }
        return out;
    }

    @Test
    void aBudgetFilledAtAFileBoundaryWithMoreFilesIsTruncated() {
        SearchService.Outcome o = SearchService.cap(oneMatchFiles(6), 5, false);
        assertEquals(5, o.totalMatches());
        assertEquals(5, o.fileCount());
        assertTrue(o.truncated(), "the sixth file's match was left out");
    }

    @Test
    void exactlyTheBudgetIsComplete() {
        SearchService.Outcome o = SearchService.cap(oneMatchFiles(5), 5, false);
        assertEquals(5, o.totalMatches());
        assertFalse(o.truncated());
    }

    @Test
    void aFileCutInTheMiddleIsTruncated() {
        List<FileResult> files = new ArrayList<>(oneMatchFiles(3));
        files.add(new FileResult(
                Path.of("many.js"),
                List.of(new LineMatch(1, 1, 1, "a"), new LineMatch(2, 1, 1, "b"), new LineMatch(3, 1, 1, "c"))));
        SearchService.Outcome o = SearchService.cap(files, 5, false);
        assertEquals(5, o.totalMatches());
        assertEquals(4, o.fileCount());
        assertTrue(o.truncated());
    }

    @Test
    void emptyFilesAfterTheBudgetDoNotCount() {
        List<FileResult> files = new ArrayList<>(oneMatchFiles(5));
        files.add(new FileResult(Path.of("empty.js"), List.of()));
        assertFalse(SearchService.cap(files, 5, false).truncated());
        assertTrue(SearchService.cap(oneMatchFiles(2), 5, true).truncated(), "a truncated source stays truncated");
    }
}
