package com.editora.lsp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single ordered writer for one language server's stdin.
 *
 * <p>LSP4J serializes a message and writes it to the output stream on the calling thread. With the process
 * pipe as that stream, a server that stops reading its stdin (wedged, paused in a debugger, swapping) fills
 * the OS pipe buffer and the next {@code write()} blocks — and most calls come from the JavaFX thread, so
 * the editor freezes with it. This stream is what LSP4J writes to instead: {@link #write} only appends the
 * bytes to an in-memory queue and returns, and one daemon thread per session performs the real, possibly
 * blocking, pipe writes.
 *
 * <p>Wire order is exactly the order of the {@code write} calls: LSP4J emits each message's header and body
 * under its own output lock, the queue is FIFO, and there is one consumer. Document-sync ordering
 * ({@code didOpen} before {@code didChange}, monotonic versions) is therefore decided where it always was,
 * by the order the session issues the calls.
 *
 * <p>The backlog is bounded. A server that has stopped reading is not going to recover by being sent more,
 * so once {@code capacityBytes} are waiting the stream reports the stall once and fails further writes
 * rather than growing without limit. One write is always accepted while the backlog is under the limit, so
 * a single large document can never trip it on its own.
 */
final class AsyncPipeWriter extends OutputStream {

    private static final Logger LOG = Logger.getLogger(AsyncPipeWriter.class.getName());

    /**
     * Default backlog limit. Far above any healthy burst — a busy server can fall seconds behind a user
     * typing in a large full-sync document without being wedged — and small beside the packaged heap.
     */
    static final long DEFAULT_CAPACITY_BYTES = 64L * 1024 * 1024;

    private final OutputStream target;
    private final long capacityBytes;
    private final Runnable onStalled;
    private final Thread thread;

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean writing;
    private boolean closed;
    private boolean failed;
    private boolean stalled;

    AsyncPipeWriter(OutputStream target, String threadName, Runnable onStalled) {
        this(target, threadName, DEFAULT_CAPACITY_BYTES, onStalled);
    }

    AsyncPipeWriter(OutputStream target, String threadName, long capacityBytes, Runnable onStalled) {
        this.target = target;
        this.capacityBytes = Math.max(1, capacityBytes);
        this.onStalled = onStalled == null ? () -> {} : onStalled;
        this.thread = new Thread(this::drain, threadName);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (length <= 0) {
            return;
        }
        boolean reportStall = false;
        synchronized (lock) {
            if (closed || failed || stalled) {
                // The type LSP4J recognises as "the stream is gone", so it logs these quietly instead of
                // warning once per message sent to a server that has already exited.
                throw new java.nio.channels.ClosedChannelException();
            }
            if (queuedBytes >= capacityBytes) {
                stalled = true;
                reportStall = true;
            } else {
                byte[] copy = new byte[length];
                System.arraycopy(bytes, offset, copy, 0, length);
                queue.add(copy);
                queuedBytes += length;
                lock.notifyAll();
            }
        }
        if (reportStall) {
            onStalled.run(); // outside the lock: the handler kills the process, which unblocks the writer
            throw new IOException("language server is not reading its input");
        }
    }

    /** A no-op: the writer thread flushes the pipe whenever it has emptied the queue. */
    @Override
    public void flush() {}

    /** Bytes accepted but not yet written to the pipe. */
    long queuedBytes() {
        synchronized (lock) {
            return queuedBytes;
        }
    }

    /**
     * Waits until everything accepted so far has reached the pipe, or the timeout passes. Used by the
     * graceful shutdown so {@code exit} is really delivered before the process tree is killed.
     */
    boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lock) {
            while ((!queue.isEmpty() || writing) && !failed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return !failed;
        }
    }

    /**
     * Stops accepting writes and discards the backlog. Never touches the pipe itself, so it is safe to call
     * while the writer thread is blocked inside a write: closing a stream another thread is blocked on would
     * wait for that very write. Killing the process is what releases the blocked thread.
     */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            queue.clear();
            queuedBytes = 0;
            lock.notifyAll();
        }
        thread.interrupt();
    }

    private void drain() {
        while (true) {
            byte[] chunk;
            synchronized (lock) {
                while (queue.isEmpty() && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        if (closed) {
                            return;
                        }
                    }
                }
                if (closed) {
                    return;
                }
                chunk = queue.poll();
                writing = true;
            }
            boolean ok = true;
            try {
                target.write(chunk);
                boolean idle;
                synchronized (lock) {
                    if (!closed) {
                        queuedBytes -= chunk.length; // close() already zeroed the backlog
                    }
                    idle = queue.isEmpty();
                }
                if (idle) {
                    target.flush();
                }
            } catch (IOException | RuntimeException e) {
                ok = false;
                LOG.log(Level.FINE, "language server input closed", e);
            }
            synchronized (lock) {
                writing = false;
                if (!ok) {
                    failed = true; // the pipe is gone (server exited); later writes fail fast
                    queue.clear();
                    queuedBytes = 0;
                }
                lock.notifyAll();
            }
            if (!ok) {
                return;
            }
        }
    }
}
