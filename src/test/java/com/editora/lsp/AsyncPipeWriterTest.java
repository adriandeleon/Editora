package com.editora.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writer that stands between LSP4J and a language server's stdin. What it has to guarantee is that the
 * caller (usually the FX thread) never waits on the pipe, and that nothing about wire order changes.
 */
class AsyncPipeWriterTest {

    /** A pipe whose reader has stopped: every write blocks until {@link #release} or the first one fails. */
    private static final class StuckPipe extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException("pipe closed", e);
            }
            synchronized (written) {
                written.write(bytes, offset, length);
            }
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesReturnWhileThePipeIsBlockedAndArriveInOrder() throws Exception {
        StuckPipe pipe = new StuckPipe();
        AsyncPipeWriter writer = new AsyncPipeWriter(pipe, "test-writer", () -> {});
        try {
            // Before the fix these calls ran on the caller's thread straight into the blocked pipe.
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                writer.write(bytes("first "));
                assertTrue(pipe.entered.await(5, TimeUnit.SECONDS), "the writer thread never reached the pipe");
                writer.write(bytes("second "));
                writer.write(bytes("third"));
            });
            assertTrue(writer.queuedBytes() > 0, "the blocked pipe must leave a backlog, not a blocked caller");

            pipe.release.countDown();
            assertTrue(writer.awaitDrained(5, TimeUnit.SECONDS));
            assertEquals("first second third", pipe.written.toString(StandardCharsets.UTF_8));
            assertEquals(0, writer.queuedBytes());
        } finally {
            pipe.release.countDown();
            writer.close();
        }
    }

    @Test
    void aServerThatStopsReadingIsReportedOnceAndLaterWritesFailFast() throws Exception {
        StuckPipe pipe = new StuckPipe();
        AtomicInteger stalls = new AtomicInteger();
        AsyncPipeWriter writer = new AsyncPipeWriter(pipe, "test-writer", 8, stalls::incrementAndGet);
        try {
            writer.write(bytes("12345678")); // reaches the limit; one write is always accepted below it
            assertTrue(pipe.entered.await(5, TimeUnit.SECONDS));

            assertThrows(IOException.class, () -> writer.write(bytes("x")));
            assertThrows(IOException.class, () -> writer.write(bytes("y")));
            assertEquals(1, stalls.get(), "the stall is reported once, not per refused write");
        } finally {
            pipe.release.countDown();
            writer.close();
        }
    }

    @Test
    void aSingleLargeMessageIsNeverRefusedOnItsOwn() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicInteger stalls = new AtomicInteger();
        AsyncPipeWriter writer = new AsyncPipeWriter(sink, "test-writer", 4, stalls::incrementAndGet);
        try {
            writer.write(new byte[1024]); // far over the limit, but nothing was waiting
            assertTrue(writer.awaitDrained(5, TimeUnit.SECONDS));
            assertEquals(1024, sink.size());
            assertEquals(0, stalls.get());
        } finally {
            writer.close();
        }
    }

    @Test
    void closeReturnsWhileTheWriterThreadIsStillBlockedInThePipe() throws Exception {
        StuckPipe pipe = new StuckPipe();
        AsyncPipeWriter writer = new AsyncPipeWriter(pipe, "test-writer", () -> {});
        writer.write(bytes("stuck"));
        assertTrue(pipe.entered.await(5, TimeUnit.SECONDS));

        // dispose() calls this on the FX thread; it must not wait for the write it cannot finish.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), writer::close);
        assertThrows(IOException.class, () -> writer.write(bytes("after close")));
        assertEquals(0, writer.queuedBytes());
        pipe.release.countDown();
    }

    @Test
    void aBrokenPipeFailsLaterWritesInsteadOfQueueingForever() throws Exception {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
        };
        AsyncPipeWriter writer = new AsyncPipeWriter(broken, "test-writer", () -> {});
        try {
            writer.write(bytes("to a dead server"));
            assertTrue(!writer.awaitDrained(5, TimeUnit.SECONDS), "a failed pipe is not a drained one");
            assertThrows(IOException.class, () -> writer.write(bytes("more")));
        } finally {
            writer.close();
        }
    }

    // --- entries encoded on the writer thread ---------------------------------------------------------

    /** The point of a deferred entry: its bytes are produced off the calling thread, and still in order. */
    @Test
    void aDeferredEntryIsProducedOnTheWriterThreadInItsPlaceInTheQueue() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AsyncPipeWriter writer = new AsyncPipeWriter(sink, "test-writer", () -> {});
        var producedOn = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            writer.write(bytes("before "));
            writer.writeDeferred(8, () -> {
                producedOn.set(Thread.currentThread().getName());
                return bytes("deferred ");
            });
            writer.write(bytes("after"));

            assertTrue(writer.awaitDrained(5, TimeUnit.SECONDS));
            assertEquals("before deferred after", sink.toString(StandardCharsets.UTF_8));
            assertEquals("test-writer", producedOn.get(), "not the thread that queued it");
            assertEquals(0, writer.queuedBytes(), "the estimate is released like real bytes");
        } finally {
            writer.close();
        }
    }

    /** While the pipe is blocked nothing behind it is encoded yet: the caller only paid for a queue entry. */
    @Test
    void aDeferredEntryCountsItsEstimateAgainstTheBacklogWhileItWaits() throws Exception {
        StuckPipe pipe = new StuckPipe();
        AtomicInteger produced = new AtomicInteger();
        AsyncPipeWriter writer = new AsyncPipeWriter(pipe, "test-writer", () -> {});
        try {
            writer.write(bytes("x"));
            assertTrue(pipe.entered.await(5, TimeUnit.SECONDS));
            writer.writeDeferred(1_000, () -> {
                produced.incrementAndGet();
                return bytes("y");
            });

            assertEquals(1_001, writer.queuedBytes());
            assertEquals(0, produced.get(), "not encoded until its turn");

            pipe.release.countDown();
            assertTrue(writer.awaitDrained(5, TimeUnit.SECONDS));
            assertEquals("xy", pipe.written.toString(StandardCharsets.UTF_8));
            assertEquals(1, produced.get());
            assertEquals(0, writer.queuedBytes());
        } finally {
            pipe.release.countDown();
            writer.close();
        }
    }

    @Test
    void aDeferredEntryThatCannotBeEncodedIsSkippedAndTheRestStillArrives() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AsyncPipeWriter writer = new AsyncPipeWriter(sink, "test-writer", () -> {});
        try {
            writer.write(bytes("a"));
            writer.writeDeferred(4, () -> {
                throw new IllegalStateException("cannot encode");
            });
            writer.write(bytes("b"));

            assertTrue(writer.awaitDrained(5, TimeUnit.SECONDS));
            assertEquals("ab", sink.toString(StandardCharsets.UTF_8));
            assertEquals(0, writer.queuedBytes());
        } finally {
            writer.close();
        }
    }

    @Test
    void aClosedWriterRefusesDeferredEntriesToo() {
        AsyncPipeWriter writer = new AsyncPipeWriter(new ByteArrayOutputStream(), "test-writer", () -> {});
        writer.close();

        assertThrows(IOException.class, () -> writer.writeDeferred(1, () -> bytes("late")));
    }
}
