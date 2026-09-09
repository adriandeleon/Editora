package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.config.HistoryRevision;
import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryRetention.RetentionPolicy;
import com.editora.history.HistoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #556: a failure in the off-thread history record (e.g. the blob disk write) must still fire the
 * {@code onRecorded} callback. The caller ({@code HistoryCoordinator}) increments an in-flight counter before the
 * call and decrements it in the callback, GC-ing local history only when the counter hits zero — so a stranded
 * callback (the throw was swallowed into the unobserved {@code submit()} Future) silently stopped local-history GC
 * for the whole session. The callback now fires with {@code null} on failure so the counter always balances.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HistoryServiceCallbackFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aBlobWriteFailureStillFiresTheRecordCallback(@TempDir Path dir) throws Exception {
        // A blob store whose "blobs dir" is actually a regular file → put() can't create its subdirs → it throws.
        Path blobsDirIsAFile = dir.resolve("blobs-is-a-file");
        Files.writeString(blobsDirIsAFile, "not a directory");
        HistoryService svc = new HistoryService(new HistoryBlobStore(blobsDirIsAFile));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HistoryRevision> got = new AtomicReference<>();
        RetentionPolicy policy = new RetentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE);

        // force = true so the record isn't short-circuited as a duplicate; the blob write then throws.
        FxTestSupport.runOnFx(() -> svc.snapshot(
                Path.of("/x/Foo.java"), "some content", "save", null, true, List.of(), policy, 1000L, rev -> {
                    got.set(rev);
                    latch.countDown();
                }));

        assertTrue(
                latch.await(5, TimeUnit.SECONDS),
                "onRecorded must fire even when the blob write throws (else the caller's in-flight counter sticks)");
        assertNull(got.get(), "a failed record reports no revision (null) so the in-flight counter still balances");
        svc.shutdown();
    }

    @Test
    void gcWaitsUntilAnInFlightRevisionIsPublished(@TempDir Path dir) throws Exception {
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("blobs"));
        HistoryService svc = new HistoryService(blobs);
        String content = "pending revision";
        String sha = HistoryBlobStore.sha256(content);
        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);

        Platform.runLater(() -> {
            fxBlocked.countDown();
            try {
                releaseFx.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(fxBlocked.await(5, TimeUnit.SECONDS));

        RetentionPolicy policy = new RetentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE);
        svc.snapshot(Path.of("/x/Foo.java"), content, "save", null, true, List.of(), policy, 1000L, rev -> {
            svc.gc(Set.of(rev.sha256()));
            published.countDown();
        });
        long blobDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (blobs.get(sha) == null && System.nanoTime() < blobDeadline) {
            Thread.sleep(10);
        }
        assertTrue(blobs.get(sha) != null, "the blob must have reached disk before testing the publish gap");

        svc.gc(Set.of());
        releaseFx.countDown();
        assertTrue(published.await(5, TimeUnit.SECONDS));
        long gcDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (blobs.get(sha) == null && System.nanoTime() < gcDeadline) {
            Thread.sleep(10);
        }
        assertEquals(content, blobs.get(sha), "the post-publication live set must replace the stale GC request");
        svc.shutdown();
    }
}
