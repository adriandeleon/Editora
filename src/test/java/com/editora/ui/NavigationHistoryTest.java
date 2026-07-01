package com.editora.ui;

import java.nio.file.Path;

import com.editora.ui.NavigationHistory.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationHistoryTest {

    private static Location loc(String p, int line) {
        return new Location(Path.of(p), line, 0);
    }

    @Test
    void backAndForwardWalkTheTrail() {
        NavigationHistory h = new NavigationHistory();
        h.record(loc("a", 10));
        h.record(loc("b", 100));
        h.record(loc("c", 5));
        assertTrue(h.canBack());
        assertFalse(h.canForward());
        assertEquals(loc("b", 100), h.back());
        assertEquals(loc("a", 10), h.back());
        assertNull(h.back()); // at the oldest
        assertEquals(loc("b", 100), h.forward());
        assertEquals(loc("c", 5), h.forward());
        assertNull(h.forward()); // at the newest
    }

    @Test
    void recordingAfterBackTruncatesForwardHistory() {
        NavigationHistory h = new NavigationHistory();
        h.record(loc("a", 1));
        h.record(loc("b", 2));
        h.record(loc("c", 3));
        h.back(); // now at b
        h.record(loc("d", 4)); // forks: c is dropped
        assertFalse(h.canForward());
        assertEquals(loc("b", 2), h.back());
        assertEquals(loc("d", 4), h.forward());
    }

    @Test
    void dedupsConsecutiveSameFileAndLine() {
        NavigationHistory h = new NavigationHistory();
        h.record(loc("a", 10));
        h.record(new Location(Path.of("a"), 10, 40)); // same file+line, diff column → ignored
        assertEquals(1, h.size());
        h.record(loc("a", 11)); // different line → kept
        assertEquals(2, h.size());
    }

    @Test
    void capsRetainedEntries() {
        NavigationHistory h = new NavigationHistory();
        for (int i = 0; i < NavigationHistory.MAX + 20; i++) {
            h.record(loc("f", i));
        }
        assertEquals(NavigationHistory.MAX, h.size());
    }

    @Test
    void nullAndEmptyAreSafe() {
        NavigationHistory h = new NavigationHistory();
        h.record(null);
        assertNull(h.back());
        assertNull(h.forward());
        assertFalse(h.canBack());
        assertFalse(h.canForward());
    }
}
