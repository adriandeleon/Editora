package com.editora.io;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentWriteSequencerTest {

    @Test
    void aNewerTicketSuppressesAnOlderQueuedWrite() throws Exception {
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        Path file = Path.of("file.txt");
        try (var old = sequencer.begin(file);
                var current = sequencer.begin(file)) {
            assertFalse(old.runIfCurrent(() -> true).executed());
            assertTrue(current.runIfCurrent(() -> true).executed());
        }
    }

    @Test
    void ticketsForDifferentPathsDoNotSupersedeEachOther() throws Exception {
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        try (var first = sequencer.begin(Path.of("one.txt"));
                var second = sequencer.begin(Path.of("two.txt"))) {
            assertTrue(first.runIfCurrent(() -> true).executed());
            assertTrue(second.runIfCurrent(() -> true).executed());
        }
    }

    @Test
    void explicitSupersedeInvalidatesOutstandingTicket() throws Exception {
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        Path file = Path.of("file.txt");
        try (var ticket = sequencer.begin(file)) {
            sequencer.supersede(file);
            assertFalse(ticket.runIfCurrent(() -> true).executed());
        }
    }

    @Test
    void aNewerTicketPreventsAnOlderStagedCommit(@TempDir Path dir) throws Exception {
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        Path file = Files.writeString(dir.resolve("file.txt"), "baseline");
        java.util.concurrent.CountDownLatch staged = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch checkCommit = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                var old = sequencer.begin(file)) {
            var oldWrite =
                    executor.submit(() -> old.runIfCurrent(() -> AtomicFileWrite.writeIf(file, "old".getBytes(), () -> {
                        staged.countDown();
                        try {
                            checkCommit.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        return old.isCurrent();
                    })));
            assertTrue(staged.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try (var current = sequencer.begin(file)) {
                checkCommit.countDown();
                var oldOutcome = oldWrite.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(oldOutcome.executed());
                assertFalse(oldOutcome.value());

                var currentOutcome = current.runIfCurrent(() -> {
                    AtomicFileWrite.write(file, "current".getBytes());
                    return true;
                });
                assertTrue(currentOutcome.executed());
            }
        } finally {
            checkCommit.countDown();
        }
        assertTrue("current".equals(Files.readString(file)));
    }
}
