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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.io.AtomicFileWrite;

/**
 * Performs config-file writes off the JavaFX thread. Callers hand it immutable bytes or an immutable
 * snapshot supplier; one daemon thread performs deferred serialization and disk I/O.
 *
 * <p>Writes to the same file coalesce — the latest bytes win — and a single writer thread keeps them
 * ordered, so an async (non-blocking) write can never land after and clobber a later durable one.
 * {@link #enqueue} is the non-blocking, coalesced path (a frequent in-session save); {@link #flush} blocks
 * until everything queued has been written (a durable save, an export, or app exit). Every write is a
 * temp-file-plus-atomic-move, so a crash mid-write never leaves a half-written config file.
 */
public final class ConfigWriter {

    @FunctionalInterface
    interface BytesSupplier {
        byte[] get() throws IOException;
    }

    enum WriteOutcome {
        WRITTEN,
        SUPERSEDED,
        FAILED
    }

    private record PendingWrite(BytesSupplier bytes, Consumer<WriteOutcome> completion) {}

    private static final Logger LOG = Logger.getLogger(ConfigWriter.class.getName());

    private static final AtomicFileWrite.FileOperations FILES = AtomicFileWrite.systemFileOperations();

    /** Config files can hold credentials + private content, so they are owner-only (0600). */
    private static final java.util.Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final ExecutorService io;
    /** Serializes the executor drain with the post-shutdown synchronous fallback. */
    private final Object writerLock = new Object();

    private final AtomicBoolean failureSinceFlush = new AtomicBoolean();

    public ConfigWriter() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "config-writer");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Test seam: supply the executor that runs the drains. Production uses the no-arg constructor's single
     * daemon thread. A test can inject a controllable executor to make the {@code enqueue}→{@code cancel}
     * ordering deterministic — otherwise the real writer thread may drain a queued write <em>before</em> a
     * following {@code cancel} runs (a race that flaked {@code ConfigDurabilityTest} on loaded CI runners).
     */
    ConfigWriter(ExecutorService io) {
        this.io = io;
    }

    /** Sets the write-failure handler (see {@link #onWriteError}); {@code null} restores the no-op. */
    public void setOnWriteError(java.util.function.BiConsumer<Path, IOException> handler) {
        this.onWriteError = handler == null ? (f, e) -> {} : handler;
    }

    private final Object lock = new Object();
    private final Map<Path, PendingWrite> pending = new LinkedHashMap<>();

    /**
     * Notified (on the writer thread) when an atomic config-file write fails. Lets a durable save on quit /
     * Settings-apply <em>surface</em> the failure instead of it being silently swallowed — a full disk or a
     * read-only {@code ~/.editora} otherwise loses the setting/session with no sign (#418). The handler must
     * marshal to the FX thread itself. Default no-op (writes just log at SEVERE, as before).
     */
    private volatile java.util.function.BiConsumer<Path, IOException> onWriteError = (f, e) -> {};

    /** Files whose write is revoked: dropped from {@code pending} AND suppressed if a drain already claimed
     *  the bytes but hasn't written them yet. Cleared for a file by a fresh {@link #enqueue}. */
    private final java.util.Set<Path> cancelled = new java.util.HashSet<>();

    /** Test seam: run right after {@link #drain} claims a batch (pending cleared), before it writes — lets a
     *  test slip a {@link #cancel} in between to exercise the "claimed but not yet written" race (#491). */
    volatile Runnable afterBatchClaimedForTest;

    /**
     * Drops any queued bytes for {@code file} and suppresses a write a drain has <b>already claimed</b> but
     * not yet performed. Used when the file is being <b>deleted</b> — a coalesced write still in the queue (or
     * mid-drain) would otherwise land afterwards and re-create it. {@code drain} clears {@code pending} under
     * the lock and writes outside it, so removing from {@code pending} alone couldn't stop a claimed write;
     * the {@code cancelled} set, re-checked per file just before each write, closes that race (#491).
     */
    public void cancel(Path file) {
        PendingWrite removed;
        synchronized (lock) {
            removed = pending.remove(file);
            cancelled.add(file);
        }
        complete(removed, WriteOutcome.SUPERSEDED);
    }

    /** Queues {@code bytes} to be written to {@code file} off-thread; a newer write to the same file wins. */
    public void enqueue(Path file, byte[] bytes) {
        enqueue(file, () -> bytes);
    }

    /** Queues off-thread serialization plus writing of an immutable snapshot. */
    void enqueue(Path file, BytesSupplier bytes) {
        enqueue(file, bytes, ignored -> {});
    }

    /** Queues an immutable snapshot and reports whether this exact snapshot became durable. */
    void enqueue(Path file, BytesSupplier bytes, Consumer<WriteOutcome> completion) {
        PendingWrite requested = new PendingWrite(bytes, completion);
        PendingWrite superseded;
        synchronized (lock) {
            superseded = pending.put(file, requested);
            cancelled.remove(file); // a fresh, legitimate write un-cancels the file
        }
        complete(superseded, WriteOutcome.SUPERSEDED);
        try {
            io.execute(this::drain);
        } catch (RejectedExecutionException shuttingDown) {
            PendingWrite rejected = null;
            synchronized (lock) {
                if (pending.get(file) == requested) {
                    rejected = pending.remove(file);
                }
            }
            if (rejected != null) {
                failureSinceFlush.set(true);
                complete(rejected, WriteOutcome.FAILED);
            }
        }
    }

    /** Blocks until every queued write has been performed (a durable save, an export, or app exit). */
    public boolean flush() {
        try {
            io.submit(() -> {}).get(flushTimeoutMillis, TimeUnit.MILLISECONDS); // wait for all queued drains
            return !failureSinceFlush.getAndSet(false);
        } catch (RejectedExecutionException shuttingDown) {
            try {
                if (!io.awaitTermination(flushTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    return false;
                }
                synchronized (lock) {
                    return pending.isEmpty() && !failureSinceFlush.getAndSet(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // The writer may still own a claimed batch. A synchronous drain here would introduce a second
            // writer and allow that older batch to land after a newer one. Leave the single owner intact.
            return false;
        }
    }

    /** Production durability wait; package-private and mutable only so timeout ordering is testable. */
    volatile long flushTimeoutMillis = TimeUnit.SECONDS.toMillis(10);

    private void drain() {
        synchronized (writerLock) {
            drainOwned();
        }
    }

    private void drainOwned() {
        Map<Path, PendingWrite> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        Runnable hook = afterBatchClaimedForTest;
        if (hook != null) {
            hook.run(); // test-only: a window for a racing cancel() (#491); null in production
        }
        batch.forEach((file, write) -> {
            synchronized (lock) {
                if (cancelled.contains(file)) {
                    complete(write, WriteOutcome.SUPERSEDED);
                    return; // cancelled after this drain claimed the bytes — don't write (#491)
                }
            }
            try {
                writeAtomicOrThrow(file, write.bytes().get());
                complete(write, WriteOutcome.WRITTEN);
            } catch (IOException | RuntimeException e) {
                IOException failure = e instanceof IOException ioFailure
                        ? ioFailure
                        : new IOException("Failed to serialize configuration snapshot", e);
                LOG.log(Level.SEVERE, "Failed to write config file " + file, failure);
                failureSinceFlush.set(true);
                complete(write, WriteOutcome.FAILED);
                try {
                    onWriteError.accept(file, failure); // surface it (#418) — no longer a silent swallow
                } catch (RuntimeException handlerFailure) {
                    LOG.log(Level.WARNING, "Config write failure handler failed", handlerFailure);
                }
            }
        });
    }

    private static void complete(PendingWrite write, WriteOutcome outcome) {
        if (write == null) {
            return;
        }
        try {
            write.completion().accept(outcome);
        } catch (RuntimeException completionFailure) {
            LOG.log(Level.WARNING, "Config write completion handler failed", completionFailure);
        }
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
        writeAtomicOrThrow(file, bytes, FILES);
    }

    /** Atomic config replacement with an injectable filesystem boundary. */
    static void writeAtomic(Path file, byte[] bytes, AtomicFileWrite.FileOperations files) throws IOException {
        writeAtomicOrThrow(file, bytes, files);
    }

    private static void writeAtomicOrThrow(Path file, byte[] bytes, AtomicFileWrite.FileOperations files)
            throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            files.createDirectories(parent);
        }
        // Remove the fixed-name temporary file used by older Editora versions. New writes use a unique
        // owner-only file below, so independent atomic writers cannot truncate or move each other's temp.
        files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
        Path tmp = createOwnerOnlyTemp(file, files);
        boolean replaced = false;
        try {
            files.write(tmp, bytes);
            try {
                files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            replaced = true;
        } finally {
            if (!replaced) {
                files.deleteIfExists(tmp);
            }
        }
    }

    private static Path createOwnerOnlyTemp(Path file, AtomicFileWrite.FileOperations files) throws IOException {
        Path parent = file.getParent();
        String prefix = "." + file.getFileName() + "-";
        if (parent != null
                && parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                return files.createTempFile(parent, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (UnsupportedOperationException ignored) {
                // No POSIX attributes after all — create with the filesystem's default mode.
            }
        }
        return parent == null ? files.createTempFile(prefix, ".tmp") : files.createTempFile(parent, prefix, ".tmp");
    }

    /**
     * (Re)creates {@code file} empty and readable only by its owner, ready to be written by any API whose
     * default options are CREATE+TRUNCATE_EXISTING ({@link Files#write}, {@link Files#newOutputStream}) —
     * those keep the mode of the file they find.
     *
     * <p><b>Use this for anything derived from the config dir.</b> Config data is not public:
     * {@code settings.json} holds the AI provider's <em>API key</em> — a billable credential — and
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

    /**
     * Flushes pending writes and stops accepting executor work. Returns whether the bounded durability
     * barrier completed; on timeout the existing single writer is allowed to finish rather than being
     * interrupted or raced by a caller-thread drain.
     */
    public boolean shutdown() {
        io.shutdown();
        return flush();
    }
}
