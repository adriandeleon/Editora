package com.editora.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HistoryService#snapshotBlocking} is what a caller uses before a change it cannot take back: the
 * content must be on disk when the callback runs, the callback runs on the caller's thread, and a failed
 * write is never reported as a revision.
 */
class HistoryServiceBlockingSnapshotTest {

    @Test
    void theContentIsOnDiskWhenTheCallbackRunsOnTheCallingThread(@TempDir Path dir) {
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("blobs"));
        HistoryService service = new HistoryService(blobs);
        try {
            Path file = dir.resolve("big.log");
            AtomicReference<HistoryService.SnapshotOutcome> seen = new AtomicReference<>();
            AtomicReference<Thread> thread = new AtomicReference<>();
            AtomicReference<String> onDisk = new AtomicReference<>();
            service.snapshotBlocking(
                    file, "before the edit\n", HistoryRevision.REASON_LABEL, "Before X", 42L, 10_000, o -> {
                        seen.set(o);
                        thread.set(Thread.currentThread());
                        onDisk.set(
                                o.revision() == null
                                        ? null
                                        : blobs.get(o.revision().sha256()));
                    });
            assertSame(Thread.currentThread(), thread.get());
            assertTrue(seen.get().successful());
            HistoryRevision revision = seen.get().revision();
            assertNotNull(revision);
            assertEquals("before the edit\n", onDisk.get(), "written before the caller is told");
            assertEquals(file.toString(), revision.path());
            assertEquals("Before X", revision.label());
            assertEquals(HistoryRevision.REASON_LABEL, revision.reason());
            assertEquals(42L, revision.timestamp());
            assertEquals(16, revision.sizeBytes());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void unchangedContentIsStillRecordedSoTheCallerHasARevisionToName(@TempDir Path dir) {
        HistoryService service = new HistoryService(new HistoryBlobStore(dir.resolve("blobs")));
        try {
            AtomicReference<HistoryService.SnapshotOutcome> first = new AtomicReference<>();
            AtomicReference<HistoryService.SnapshotOutcome> second = new AtomicReference<>();
            service.snapshotBlocking(dir.resolve("f"), "same", "LABEL", "a", 1L, 10_000, first::set);
            service.snapshotBlocking(dir.resolve("f"), "same", "LABEL", "b", 2L, 10_000, second::set);
            assertNotNull(second.get().revision());
            assertEquals(
                    first.get().revision().sha256(), second.get().revision().sha256());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aWriteThatFailsIsReportedAsNoRevision(@TempDir Path dir) throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("blobs"), "a file where the store should be");
        HistoryService service = new HistoryService(new HistoryBlobStore(notADirectory));
        try {
            AtomicReference<HistoryService.SnapshotOutcome> seen = new AtomicReference<>();
            service.snapshotBlocking(dir.resolve("f"), "text", "LABEL", "x", 1L, 10_000, seen::set);
            assertFalse(seen.get().successful());
            assertNull(seen.get().revision());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aServiceThatIsShutDownReportsNoRevisionInsteadOfThrowing(@TempDir Path dir) {
        HistoryService service = new HistoryService(new HistoryBlobStore(dir.resolve("blobs")));
        service.shutdown();
        AtomicReference<HistoryService.SnapshotOutcome> seen = new AtomicReference<>();
        service.snapshotBlocking(dir.resolve("f"), "text", "LABEL", "x", 1L, 10_000, seen::set);
        assertFalse(seen.get().successful());
        assertNull(seen.get().revision());
    }
}
