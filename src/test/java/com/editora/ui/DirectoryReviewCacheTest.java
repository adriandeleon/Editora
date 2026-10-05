package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The folder review's pane cache: bounded, least-recently-used first, never evicting a pinned (draft) pane. */
class DirectoryReviewCacheTest {

    @Test
    void aPinnedEldestEntryDoesNotStopEviction() {
        LinkedHashMap<Integer, String> cache = new LinkedHashMap<>(16, 0.75f, true);
        cache.put(0, "draft");
        for (int i = 1; i <= 10; i++) {
            cache.put(i, "pane" + i);
            DirectoryReviewPane.trim(cache, 4, "draft"::equals);
            assertTrue(cache.size() <= 4, "the cache stays at its cap while the eldest entry is pinned");
        }
        assertEquals(List.of(0, 8, 9, 10), List.copyOf(cache.keySet()));
    }

    @Test
    void theCacheComesBackDownToItsCapOnceNothingIsPinned() {
        LinkedHashMap<Integer, String> cache = new LinkedHashMap<>(16, 0.75f, true);
        for (int i = 0; i < 10; i++) {
            cache.put(i, "pane" + i);
        }
        assertEquals(10, DirectoryReviewPane.trim(cache, 4, value -> true).size() + cache.size());
        assertEquals(10, cache.size(), "everything pinned: nothing can go");

        List<String> evicted = DirectoryReviewPane.trim(cache, 4, value -> false);

        assertEquals(List.of("pane0", "pane1", "pane2", "pane3", "pane4", "pane5"), evicted);
        assertEquals(List.of(6, 7, 8, 9), List.copyOf(cache.keySet()));
    }
}
