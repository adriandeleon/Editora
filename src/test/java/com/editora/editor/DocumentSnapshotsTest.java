package com.editora.editor;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class DocumentSnapshotsTest {

    @Test
    void materializesAtMostOncePerDocumentVersion() {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        AtomicInteger reads = new AtomicInteger();

        DocumentSnapshots.Snapshot first = snapshots.get(7, () -> "text-" + reads.incrementAndGet());
        DocumentSnapshots.Snapshot reused = snapshots.get(7, () -> "text-" + reads.incrementAndGet());

        assertSame(first, reused);
        assertEquals("text-1", reused.text());
        assertEquals(1, reads.get());
        assertEquals(1, snapshots.materializations());
    }

    @Test
    void invalidationDropsTheCachedReferenceEvenBeforeTheVersionMoves() {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        DocumentSnapshots.Snapshot before = snapshots.get(3, () -> "before");

        snapshots.invalidate();
        DocumentSnapshots.Snapshot after = snapshots.get(3, () -> "after");

        assertNotSame(before, after);
        assertEquals("after", after.text());
        assertEquals(2, snapshots.materializations());
    }

    @Test
    void aNewVersionCannotReuseAnOlderSnapshot() {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        snapshots.get(10, () -> "old");

        DocumentSnapshots.Snapshot current = snapshots.get(11, () -> "new");

        assertEquals(11, current.version());
        assertEquals("new", current.text());
        assertEquals(2, snapshots.materializations());
    }
}
