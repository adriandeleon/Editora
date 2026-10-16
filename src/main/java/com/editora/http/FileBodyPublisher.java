package com.editora.http;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A request body streamed from a file, like {@link HttpRequest.BodyPublishers#ofFile}, that can be {@link #close
 * closed}: closing it closes every file it has open and fails the uploads still reading. The JDK's own file
 * publisher keeps its stream open when the exchange is torn down by a cancel, so a cancelled upload held the file
 * open until the garbage collector found it — on Windows that locks the file against being saved, renamed or
 * deleted. The length is taken when the publisher is made, as {@code ofFile} does, so a file that changes size
 * before it is sent fails the request instead of sending a body that does not match its {@code Content-Length}.
 *
 * <p>Each subscription opens the file anew, so the same publisher can be sent twice (a Digest challenge answered
 * with the body again).
 */
public final class FileBodyPublisher implements HttpRequest.BodyPublisher, Closeable {

    private static final int CHUNK = 64 * 1024;

    private final Path file;
    private final long length;
    private final Set<SeekableByteChannel> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** A publisher of {@code file}'s bytes; {@link FileNotFoundException} when it cannot be read, as {@code ofFile}. */
    public FileBodyPublisher(Path file) throws FileNotFoundException {
        this.file = file;
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new FileNotFoundException(file + " not found");
            }
            this.length = Files.size(file);
        } catch (IOException unreadable) {
            FileNotFoundException e = new FileNotFoundException(file + " not found");
            e.initCause(unreadable);
            throw e;
        }
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Upload upload = new Upload(subscriber);
        subscriber.onSubscribe(upload);
    }

    /** Closes the files of every upload still running; later uploads fail. Idempotent. */
    @Override
    public void close() {
        closed = true;
        for (SeekableByteChannel ch : open) {
            closeQuietly(ch);
        }
        open.clear();
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closing to release it: nothing left to do
        }
    }

    /** One send of the file: reads a chunk per unit of demand, serialized by {@code wip}. */
    private final class Upload implements Flow.Subscription {
        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicInteger wip = new AtomicInteger();
        private volatile SeekableByteChannel channel;
        private volatile boolean done;

        Upload(Flow.Subscriber<? super ByteBuffer> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                fail(new IllegalArgumentException("non-positive request: " + n));
                return;
            }
            demand.accumulateAndGet(n, (a, b) -> a + b < 0 ? Long.MAX_VALUE : a + b);
            drain();
        }

        @Override
        public void cancel() {
            done = true;
            release();
        }

        private void drain() {
            if (wip.getAndIncrement() != 0) {
                return; // the thread already draining picks up the new demand
            }
            int missed = 1;
            do {
                while (!done && demand.get() > 0) {
                    try {
                        if (closed) {
                            throw new IOException("the upload of " + file.getFileName() + " was stopped");
                        }
                        if (channel == null) {
                            channel = Files.newByteChannel(file);
                            open.add(channel);
                            if (closed) { // close() ran between the check and the add
                                release();
                                continue;
                            }
                        }
                        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
                        int read = channel.read(buf);
                        if (read < 0) {
                            done = true;
                            release();
                            subscriber.onComplete();
                            return;
                        }
                        buf.flip();
                        demand.decrementAndGet();
                        subscriber.onNext(buf);
                    } catch (IOException | RuntimeException failure) {
                        fail(failure);
                        return;
                    }
                }
                missed = wip.addAndGet(-missed);
            } while (missed != 0);
        }

        private void fail(Throwable failure) {
            if (!done) {
                done = true;
                release();
                subscriber.onError(failure);
            }
        }

        private void release() {
            SeekableByteChannel ch = channel;
            if (ch != null) {
                open.remove(ch);
                closeQuietly(ch);
            }
        }
    }
}
