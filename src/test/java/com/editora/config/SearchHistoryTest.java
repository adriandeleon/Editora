package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchHistoryTest {

    @Test
    void addMovesToTopDedupedAndSkipsBlank(@TempDir Path dir) {
        SearchHistory h = new SearchHistory(dir);
        h.add("foo");
        h.add("bar");
        h.add("foo"); // dedupe → moves to top
        h.add("");
        h.add(null);
        assertEquals(java.util.List.of("foo", "bar"), h.getList());
    }

    @Test
    void cappedAtMaxEntries(@TempDir Path dir) {
        SearchHistory h = new SearchHistory(dir);
        for (int i = 0; i < SearchHistory.MAX_ENTRIES + 10; i++) {
            h.add("q" + i);
        }
        assertEquals(SearchHistory.MAX_ENTRIES, h.getList().size());
        assertEquals("q" + (SearchHistory.MAX_ENTRIES + 9), h.getList().get(0)); // newest first
    }

    @Test
    void persistsAndReloads(@TempDir Path dir) {
        SearchHistory a = new SearchHistory(dir);
        a.add("alpha");
        a.add("beta");
        SearchHistory b = new SearchHistory(dir); // fresh instance reads the same file
        assertEquals(java.util.List.of("beta", "alpha"), b.getList());
    }

    @Test
    void clearEmptiesAndPersists(@TempDir Path dir) {
        SearchHistory a = new SearchHistory(dir);
        a.add("x");
        a.clear();
        assertTrue(a.getList().isEmpty());
        assertTrue(new SearchHistory(dir).getList().isEmpty());
    }
}
