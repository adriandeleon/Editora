package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs config-file writes off the JavaFX thread. Callers serialize a consistent snapshot to bytes on
 * their own thread (the FX thread is single-threaded, so no locking is needed to read the config POJOs)
 * and hand the immutable bytes here; one daemon thread does the actual disk I/O.
 *
 * <p>Writes to the same file coalesce — the latest bytes win — and a single writer thread keeps them
 * ordered, so an async (non-blocking) write can never land after and clobber a later durable one.
 * {@link #enqueue} is the non-blocking, coalesced path (a frequent in-session save); {@link #flush} blocks
 * until everything queued has been written (a durable save, an export, or app exit). Every write is a
 * temp-file-plus-atomic-move, so a crash mid-write never leaves a half-written config file.
 */
public final class ConfigWriter {

    private static final Logger LOG = Logger.getLogger(ConfigWriter.class.getName());

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "config-writer");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private final Map<Path, byte[]> pending = new LinkedHashMap<>();

    /** Queues {@code bytes} to be written to {@code file} off-thread; a newer write to the same file wins. */
    public void enqueue(Path file, byte[] bytes) {
        synchronized (lock) {
            pending.put(file, bytes);
        }
        try {
            io.execute(this::drain);
        } catch (RejectedExecutionException shuttingDown) {
            drain(); // executor already stopped (exit) — write synchronously on the caller
        }
    }

    /** Blocks until every queued write has been performed (a durable save, an export, or app exit). */
    public void flush() {
        try {
            io.submit(() -> {}).get(10, TimeUnit.SECONDS); // wait for all queued drains to finish
        } catch (RejectedExecutionException shuttingDown) {
            drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drain();
        } catch (Exception e) {
            drain(); // timeout / executor error — best-effort synchronous write
        }
    }

    private void drain() {
        Map<Path, byte[]> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        batch.forEach(ConfigWriter::writeAtomic);
    }

    /** Writes {@code bytes} to {@code file} via a temp file + atomic move (a crash never leaves a partial file). */
    static void writeAtomic(Path file, byte[] bytes) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write config file " + file, e);
        }
    }

    /** Flushes any pending writes, then stops the writer thread (final app shutdown). */
    public void shutdown() {
        flush();
        io.shutdownNow();
    }
}
