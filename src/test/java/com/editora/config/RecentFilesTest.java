package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RecentFiles}: most-recent-first ordering, de-dup, the {@link RecentFiles#MAX_ENTRIES}
 * cap, removal/clear, and on-disk persistence across instances (each op writes {@code recent-files.json}).
 * Uses an {@link javafx.collections.ObservableList} backing store but no FX toolkit, so it stays a pure test.
 */
class RecentFilesTest {

    @Test
    void addMovesToTopAndDeduplicates(@TempDir Path dir) {
        RecentFiles rf = new RecentFiles(dir);
        rf.add(dir.resolve("a.txt"));
        rf.add(dir.resolve("b.txt"));
        rf.add(dir.resolve("a.txt")); // re-adding a.txt moves it to the front, no duplicate
        assertEquals(2, rf.getList().size());
        assertEquals(dir.resolve("a.txt"), rf.getList().get(0));
        assertEquals(dir.resolve("b.txt"), rf.getList().get(1));
    }

    @Test
    void nullAddAndRemoveAreNoOps(@TempDir Path dir) {
        RecentFiles rf = new RecentFiles(dir);
        rf.add(null);
        rf.remove(null);
        assertTrue(rf.getList().isEmpty());
    }

    @Test
    void listIsCappedAtMaxEntries(@TempDir Path dir) {
        RecentFiles rf = new RecentFiles(dir);
        for (int i = 0; i < RecentFiles.MAX_ENTRIES + 5; i++) {
            rf.add(dir.resolve("f" + i + ".txt"));
        }
        assertEquals(RecentFiles.MAX_ENTRIES, rf.getList().size());
        // The most recently added survives; the oldest were evicted.
        assertEquals(
                dir.resolve("f" + (RecentFiles.MAX_ENTRIES + 4) + ".txt"),
                rf.getList().get(0));
    }

    @Test
    void removeAndClear(@TempDir Path dir) {
        RecentFiles rf = new RecentFiles(dir);
        rf.add(dir.resolve("x.txt"));
        rf.add(dir.resolve("y.txt"));
        rf.remove(dir.resolve("x.txt"));
        assertEquals(1, rf.getList().size());
        rf.clear();
        assertTrue(rf.getList().isEmpty());
        rf.clear(); // clearing an empty list is a harmless no-op
        assertTrue(rf.getList().isEmpty());
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        RecentFiles first = new RecentFiles(dir);
        first.add(dir.resolve("one.txt"));
        first.add(dir.resolve("two.txt"));

        // A fresh instance over the same config dir reloads the saved list (most-recent-first).
        RecentFiles reloaded = new RecentFiles(dir);
        assertEquals(2, reloaded.getList().size());
        assertEquals(dir.resolve("two.txt"), reloaded.getList().get(0));
        assertEquals(dir.resolve("one.txt"), reloaded.getList().get(1));
    }
}
