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

        DocumentSnapshots.Snapshot first = snapshots.get(7, () -> () -> "text-" + reads.incrementAndGet());
        DocumentSnapshots.Snapshot reused = snapshots.get(7, () -> () -> "text-" + reads.incrementAndGet());

        assertSame(first, reused);
        assertEquals("text-1", reused.text());
        assertEquals(1, reads.get());
        assertEquals(1, snapshots.materializations());
    }

    @Test
    void invalidationDropsTheCachedReferenceEvenBeforeTheVersionMoves() {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        DocumentSnapshots.Snapshot before = snapshots.get(3, () -> () -> "before");

        snapshots.invalidate();
        DocumentSnapshots.Snapshot after = snapshots.get(3, () -> () -> "after");

        assertEquals("before", before.text());
        assertNotSame(before, after);
        assertEquals("after", after.text());
        assertEquals(2, snapshots.materializations());
    }

    @Test
    void aNewVersionCannotReuseAnOlderSnapshot() {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        snapshots.get(10, () -> () -> "old").text();

        DocumentSnapshots.Snapshot current = snapshots.get(11, () -> () -> "new");

        assertEquals(11, current.version());
        assertEquals("new", current.text());
        assertEquals(2, snapshots.materializations());
    }

    @Test
    void takingASnapshotBuildsNothingUntilItsTextIsAskedForThenOnceOnWhicheverThreadAsks() throws Exception {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        AtomicInteger reads = new AtomicInteger();

        DocumentSnapshots.Snapshot taken = snapshots.get(5, () -> () -> "text-" + reads.incrementAndGet());
        assertEquals(0, reads.get(), "taking the snapshot is O(1): nothing is built yet");
        assertEquals(0, snapshots.materializations());

        String[] onWorker = new String[1];
        Thread worker = Thread.ofVirtual().start(() -> onWorker[0] = taken.text());
        worker.join();
        assertEquals("text-1", onWorker[0]);
        assertSame(
                onWorker[0], snapshots.get(5, () -> () -> "never").text(), "the FX thread reuses the worker's String");
        assertEquals(1, reads.get());
        assertEquals(1, snapshots.materializations());
    }

    @Test
    void anUnbuiltSnapshotIsNotReleasedABuiltOneIs() {
        DocumentSnapshots snapshots = new DocumentSnapshots();
        DocumentSnapshots.Snapshot taken = snapshots.get(1, () -> () -> "text");
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.releaseUnless(null), "holds no String: nothing to free");
        taken.text();
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.releaseUnless(null));
    }
}
