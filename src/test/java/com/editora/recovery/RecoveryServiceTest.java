package com.editora.recovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryServiceTest {

    private static final long WAIT = 10_000;

    @TempDir
    Path config;

    private static RecoveryRecord record(String id, String text) {
        return RecoveryCodecTest.record(id, null, text);
    }

    private RecoveryService service() {
        return new RecoveryService(config, Runnable::run);
    }

    @Test
    void aSaveReachesTheDiskAndARemoveTakesItAway() throws Exception {
        try (RecoveryService service = service()) {
            service.save(record("a", "first"));
            assertTrue(service.awaitIdle(WAIT));
            Path file = service.store().sessionDir().resolve("a.rec");
            assertEquals(
                    "first",
                    RecoveryCodec.decode(Files.readAllBytes(file), true).text());

            service.save(record("a", "second"));
            service.remove("a");
            assertTrue(service.awaitIdle(WAIT));
            assertFalse(Files.exists(file), "the removal was requested last, so it wins");
        }
        assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)), "a normal end leaves nothing");
    }

    @Test
    void removingABufferThatNeverHadACopyDoesNothing() {
        try (RecoveryService service = service()) {
            service.remove("never");
            assertTrue(service.awaitIdle(WAIT));
            assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)), "not even the directory");
        }
    }

    /** Only the newest text queued for a buffer is written. */
    @Test
    void queuedSavesOfOneBufferCoalesce() throws Exception {
        try (RecoveryService service = service()) {
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch held = new CountDownLatch(1);
            // Occupy the worker so the saves below queue up behind it.
            service.submit(() -> {
                held.countDown();
                try {
                    hold.await(WAIT, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(held.await(WAIT, TimeUnit.MILLISECONDS));
            for (int i = 0; i < 50; i++) {
                service.save(record("a", "version " + i));
            }
            service.save(record("b", "other buffer"));
            hold.countDown();
            assertTrue(service.awaitIdle(WAIT));
            Path dir = service.store().sessionDir();
            assertEquals(
                    "version 49",
                    RecoveryCodec.decode(Files.readAllBytes(dir.resolve("a.rec")), true)
                            .text());
            assertEquals(
                    "other buffer",
                    RecoveryCodec.decode(Files.readAllBytes(dir.resolve("b.rec")), true)
                            .text());
        }
    }

    @Test
    void aRestoredRecordIsDeletedOnlyOnceItsReplacementIsOnDisk() throws Exception {
        RecoveryService dead = service();
        dead.save(record("old", "recovered text"));
        dead.abandon();

        try (RecoveryService next = service()) {
            RecoveryStore.Orphans found = next.orphans().get(WAIT, TimeUnit.MILLISECONDS);
            RecoveryStore.Entry entry = found.entries().get(0);
            assertEquals(
                    "recovered text",
                    next.read(entry).get(WAIT, TimeUnit.MILLISECONDS).text());

            assertFalse(next.discardOnceSaved(entry, "new").get(WAIT, TimeUnit.MILLISECONDS), "no replacement yet");
            assertTrue(Files.exists(entry.file()));

            next.save(record("new", "recovered text"));
            assertTrue(next.discardOnceSaved(entry, "new").get(WAIT, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(entry.file()));
            assertEquals(List.of("new"), next.store().ownRecordIds());
        }
    }

    @Test
    void anAbandonedSessionIsFoundByTheNextServiceButNotByALiveNeighbour() throws Exception {
        RecoveryService crashed = service();
        crashed.save(record("a", "lost?"));
        try (RecoveryService neighbour = service()) {
            neighbour.save(record("n", "neighbour's"));
            assertTrue(neighbour.awaitIdle(WAIT));
            assertTrue(crashed.awaitIdle(WAIT));
            assertTrue(
                    neighbour
                            .orphans()
                            .get(WAIT, TimeUnit.MILLISECONDS)
                            .entries()
                            .isEmpty(),
                    "the other editor is still running");
            crashed.abandon();
            try (RecoveryService next = service()) {
                List<RecoveryStore.Entry> found =
                        next.orphans().get(WAIT, TimeUnit.MILLISECONDS).entries();
                assertEquals(1, found.size(), "the dead session's record, not the live neighbour's");
                assertEquals("a", found.get(0).record().bufferId());
                assertTrue(next.discard(found.get(0)).get(WAIT, TimeUnit.MILLISECONDS));
            }
            assertEquals(List.of("n"), neighbour.store().ownRecordIds());
        }
    }

    @Test
    void onlyTheFirstWindowClaimsTheOffer() {
        try (RecoveryService service = service()) {
            assertTrue(service.claimOffer());
            assertFalse(service.claimOffer());
        }
    }

    @Test
    void aWriteFailureIsReportedOnceAndLaterWritesStillWork() throws Exception {
        try (RecoveryService service = service()) {
            AtomicInteger reported = new AtomicInteger();
            service.addWriteErrorListener(e -> reported.incrementAndGet());
            Files.writeString(config.resolve(RecoveryStore.DIR_NAME), "a file where the directory should be");
            service.save(record("a", "x"));
            assertTrue(service.awaitIdle(WAIT));
            service.save(record("a", "y"));
            assertTrue(service.awaitIdle(WAIT));
            assertEquals(1, reported.get(), "one report for a run of failures");

            Files.delete(config.resolve(RecoveryStore.DIR_NAME));
            service.save(record("a", "z"));
            assertTrue(service.awaitIdle(WAIT));
            assertEquals(List.of("a"), service.store().ownRecordIds());
        }
    }

    @Test
    void aTickerRunsWhileRegisteredAndStopsWhenRemoved() throws Exception {
        try (RecoveryService service = service()) {
            CountDownLatch ticked = new CountDownLatch(2);
            Runnable ticker = ticked::countDown;
            service.addTicker(ticker);
            assertTrue(ticked.await(WAIT, TimeUnit.MILLISECONDS));
            service.removeTicker(ticker);
        }
    }

    /** A plain kill: the windows are still open, so the hook asks them for one last copy and writes it. */
    @Test
    void theShutdownHookTakesALastCopyAndGetsItOntoTheDisk() throws Exception {
        RecoveryService service = service();
        service.save(record("a", "older"));
        assertTrue(service.awaitIdle(WAIT));
        Path dir = service.store().sessionDir();
        service.addFinalizer(() -> service.save(record("a", "typed just before the signal")));
        service.onShutdown();
        assertEquals(
                "typed just before the signal",
                RecoveryCodec.decode(Files.readAllBytes(dir.resolve("a.rec")), true)
                        .text());
        service.abandon();
    }

    /** A normal quit: every window already dropped its copies, and the hook leaves nothing behind. */
    @Test
    void theShutdownHookAfterANormalQuitRemovesTheEmptySession() {
        RecoveryService service = service();
        service.save(record("a", "x"));
        service.remove("a");
        service.onShutdown();
        assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)));
        service.close();
    }

    @Test
    void nothingHappensAfterClose() throws IOException {
        RecoveryService service = service();
        service.close();
        service.save(record("a", "x"));
        service.remove("a");
        assertTrue(service.awaitIdle(WAIT));
        assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)));
        service.close();
    }
}
