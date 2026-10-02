package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.config.InstanceLockTestHooks;
import com.editora.config.SharedConfig;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Editora processes on one config dir. A launch that is not a plain "open these files" (no file argument,
 * {@code --project}, {@code --new-instance}, {@code --diff-ui}) is not forwarded to the running editor, so it
 * becomes a second process sharing {@code ~/.editora}.
 *
 * <p>The other process is played by {@link InstanceLockTestHooks}, which holds the locks a real one would.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecondInstanceFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<MessageLog.Entry> messages(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<MessageLog>field(status, "messageLog").entries();
        });
    }

    /**
     * Both processes rewrite settings, notes, bookmarks, breakpoints, projects and recents whole from their
     * own snapshot. The second one used to start without a word; it now says so, once, and the message stays
     * flagged in the log after routine startup messages have replaced the echo line.
     */
    @Test
    void theSecondProcessTellsTheUserThatAnotherEditorSharesThisConfiguration() throws Exception {
        Path dir = Files.createTempDirectory("editora-second-instance");
        try (var first = InstanceLockTestHooks.otherProcessIsPrimary(dir);
                FxWindowFixture fx = FxWindowFixture.create(dir, SharedConfig::claimInstance)) {
            FxTestSupport.drainFx();

            assertFalse(fx.shared.isPrimaryInstance());
            List<MessageLog.Entry> warnings = messages(fx).stream()
                    .filter(e -> e.text().equals(tr("status.config.secondaryInstance")))
                    .toList();
            assertEquals(1, warnings.size(), "shown once: " + messages(fx));
            assertEquals(MessageLog.Severity.ERROR, warnings.get(0).severity(), "so it stays flagged as unread");
        }
    }

    @Test
    void theOnlyProcessOnAConfigDirIsNotWarned() throws Exception {
        Path dir = Files.createTempDirectory("editora-only-instance");
        try (FxWindowFixture fx = FxWindowFixture.create(dir, SharedConfig::claimInstance)) {
            FxTestSupport.drainFx();

            assertTrue(fx.shared.isPrimaryInstance());
            assertTrue(messages(fx).stream().noneMatch(e -> e.text().equals(tr("status.config.secondaryInstance"))));
        }
    }

    // --- local history: one process must not garbage-collect the other's revision bodies ---

    /**
     * Saves the history index and returns once the garbage collection that follows it has run (or been
     * skipped). The GC is queued on the history worker before the durable callback fires, and the worker has
     * one thread — so a later {@code content} request completing means the GC is behind us.
     */
    private static void saveHistoryAndAwaitCollection(SharedConfig shared) throws Exception {
        CountDownLatch durable = new CountDownLatch(1);
        shared.saveHistory(written -> durable.countDown());
        assertTrue(durable.await(20, TimeUnit.SECONDS), "the history index should have been written");
        CountDownLatch behindTheCollection = new CountDownLatch(1);
        shared.historyService().content(null, text -> behindTheCollection.countDown());
        assertTrue(behindTheCollection.await(20, TimeUnit.SECONDS));
    }

    /**
     * Blob GC deletes everything outside <em>this</em> process's index, on every history save. The other
     * editor's revisions are not in that index, so its History view ended up listing revisions whose bodies
     * were gone. A secondary now never collects.
     */
    @Test
    void aSecondProcessNeverDeletesHistoryBlobsItDoesNotKnow(@TempDir Path dir) throws Exception {
        try (var first = InstanceLockTestHooks.otherProcessIsPrimary(dir)) {
            SharedConfig shared = new SharedConfig(dir, false);
            try {
                shared.load();
                assertFalse(shared.claimInstance());
                HistoryBlobStore blobs = new HistoryBlobStore(shared.getHistoryBlobsDir());
                String theirs = blobs.put("a revision the first editor recorded after this one started");

                saveHistoryAndAwaitCollection(shared);

                assertEquals("a revision the first editor recorded after this one started", blobs.get(theirs));
            } finally {
                shared.shutdown();
            }
        }
    }

    /** …and the primary holds off while a second process is alive, then collects again once it has gone. */
    @Test
    void thePrimaryDoesNotCollectWhileASecondProcessIsRunning(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            shared.load();
            assertTrue(shared.claimInstance());
            HistoryBlobStore blobs = new HistoryBlobStore(shared.getHistoryBlobsDir());
            String theirs = blobs.put("a revision the second editor just recorded");

            try (var second = InstanceLockTestHooks.otherProcessIsSecondary(dir)) {
                saveHistoryAndAwaitCollection(shared);
                assertEquals("a revision the second editor just recorded", blobs.get(theirs));
            }

            // The second editor has exited; nothing references the blob any more, so it is collected — which
            // is also what proves the saves above really do reach the collector.
            saveHistoryAndAwaitCollection(shared);
            assertNull(blobs.get(theirs));
        } finally {
            shared.shutdown();
        }
    }
}
