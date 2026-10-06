package com.editora.history;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate on local-history garbage collection.
 *
 * <p>GC deletes every blob outside the caller's live set — which is this process's index. When two Editora
 * processes share a config dir, the other one's revisions are not in that index, so each history save here
 * deleted the other editor's revision bodies. The config layer now vetoes the collection in that case.
 */
class HistoryServiceGcGateTest {

    /**
     * Runs one gated GC with an empty live set and returns once the worker has finished it. The service has
     * one worker thread, so the moment it asks the gate about a <em>second</em> request, the first is done.
     */
    private static void collectEverything(HistoryBlobStore blobs, boolean allowed) throws Exception {
        CountDownLatch asked = new CountDownLatch(2);
        HistoryService service = new HistoryService(blobs, () -> {
            asked.countDown();
            return allowed;
        });
        try {
            service.gc(Set.of());
            service.gc(Set.of());
            assertTrue(asked.await(10, TimeUnit.SECONDS), "the gate is consulted for every collection");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aVetoedCollectionDeletesNothing(@TempDir Path dir) throws Exception {
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("blobs"));
        String sha = blobs.put("a revision recorded by the other editor");

        collectEverything(blobs, false);

        assertEquals("a revision recorded by the other editor", blobs.get(sha));
    }

    @Test
    void anAllowedCollectionStillRemovesUnreferencedBlobs(@TempDir Path dir) throws Exception {
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("blobs"));
        String sha = blobs.put("nobody references this any more");

        collectEverything(blobs, true);

        assertNull(blobs.get(sha), "the gate must not disable collection for the ordinary single process");
    }
}
