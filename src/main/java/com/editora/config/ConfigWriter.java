package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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

    /** Config files can hold credentials + private content, so they are owner-only (0600). */
    private static final java.util.Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "config-writer");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private final Map<Path, byte[]> pending = new LinkedHashMap<>();

    /**
     * Drops any queued (not yet written) bytes for {@code file}. Used when the file is being <b>deleted</b> —
     * a coalesced write still sitting in the queue would otherwise land afterwards and re-create it.
     */
    public void cancel(Path file) {
        synchronized (lock) {
            pending.remove(file);
        }
    }

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
            writeAtomicOrThrow(file, bytes);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write config file " + file, e);
        }
    }

    /**
     * Serializes {@code value} with {@code mapper} and writes it to {@code file} atomically — <b>use this
     * instead of {@code mapper.writeValue(file.toFile(), value)}</b> for any config store.
     *
     * <p>Jackson's {@code writeValue(File, …)} <b>truncates the target first</b> and streams into it. A crash,
     * a full disk, or an I/O error partway through therefore leaves a <em>torn</em> file — and the read path
     * ({@code ConfigMigrations.readVersioned}) treats an unparseable file as "absent" and loads an
     * <em>empty</em> store, which the next save then writes back over the remains. One bad write and every
     * bookmark, note, breakpoint, or the whole project index is gone, with nothing to recover from.
     *
     * <p>Temp-file-plus-atomic-move means the target is only ever replaced by a complete file.
     */
    public static void writeAtomic(Path file, com.fasterxml.jackson.databind.ObjectMapper mapper, Object value)
            throws IOException {
        writeAtomicOrThrow(file, mapper.writeValueAsBytes(value));
    }

    private static void writeAtomicOrThrow(Path file, byte[] bytes) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        writeOwnerOnly(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes {@code bytes} to {@code tmp}, readable only by its owner where the filesystem supports it.
     * The subsequent atomic move preserves the mode, which also re-tightens a file left 0644 by an older
     * version.
     */
    private static void writeOwnerOnly(Path tmp, byte[] bytes) throws IOException {
        createOwnerOnly(tmp);
        Files.write(tmp, bytes); // CREATE+TRUNCATE_EXISTING: keeps the mode of the file just created
    }

    /**
     * (Re)creates {@code file} empty and readable only by its owner, ready to be written by any API whose
     * default options are CREATE+TRUNCATE_EXISTING ({@link Files#write}, {@link Files#newOutputStream}) —
     * those keep the mode of the file they find.
     *
     * <p><b>Use this for anything derived from the config dir.</b> Config data is not public:
     * {@code settings.toml} holds the AI provider's <em>API key</em> — a billable credential — and
     * {@code notes.json} holds the user's private notes. The default umask leaves a new file world-readable
     * (0644) in a directory that is itself world-traversable (0755), so any other account on the machine can
     * simply read the key out. That applies just as much to a <em>copy</em>: the config export writes a zip of
     * the whole directory into the user's home, so it must be owner-only too — locking down the original and
     * not the export protects nothing.
     *
     * <p>The mode is applied as a <em>creation</em> attribute rather than set afterwards, so the file is never
     * briefly readable by anyone else, and it costs no extra syscall. A no-op on filesystems without POSIX
     * permissions (Windows).
     */
    public static void createOwnerOnly(Path file) throws IOException {
        Files.deleteIfExists(file); // a leftover file would keep its old, laxer mode
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (UnsupportedOperationException ignored) {
                // No POSIX attributes after all — fall through and write with the default mode.
            }
        }
    }

    /** Flushes any pending writes, then stops the writer thread (final app shutdown). */
    public void shutdown() {
        flush();
        io.shutdownNow();
    }
}
