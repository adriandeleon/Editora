package com.editora.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An advisory, process-lifetime claim on a config directory, so that two Editora processes sharing one
 * directory can tell which of them may do the things only one process can safely do.
 *
 * <p>A second process on the same directory is normal, not an error: a launch with no file argument,
 * {@code --project}, {@code --new-file}, {@code --new-instance} and {@code --diff-ui} are deliberately not
 * forwarded to the running editor. The stores under the directory are nevertheless written whole from each
 * process's own in-memory copy, so the processes need a way to know about each other.
 *
 * <p>The lock file carries two byte-range locks, both released by the operating system when their holder
 * dies — so, unlike a pid written to a file, neither a crash nor a reused process id can leave a stale claim:
 *
 * <ul>
 *   <li><b>byte 0, exclusive — the primary.</b> Whoever takes it first holds it for life and is the
 *       <em>primary</em> instance; everyone later is a <em>secondary</em>. There is no promotion: a secondary
 *       that outlives the primary stays a secondary, because its in-memory stores were loaded while another
 *       process was already changing the files.
 *   <li><b>byte 1, shared — presence.</b> Every secondary holds a shared lock here for life. The primary can
 *       therefore ask "is anyone else here right now?" by trying to take the same byte exclusively (see
 *       {@link #othersPresent()}), without any process having to register or deregister.
 * </ul>
 *
 * <p>Advisory locking is a courtesy between Editora processes, not protection: on a filesystem that refuses
 * locks altogether, or when the file cannot be created, the claim degrades to "primary, alone" — exactly the
 * behaviour before this class existed, and the right one for the overwhelmingly common single process.
 */
final class InstanceLock implements AutoCloseable {

    /** The lock file, directly under the config directory. Its content is never read or written. */
    static final String FILE_NAME = "instance.lock";

    private static final Logger LOG = Logger.getLogger(InstanceLock.class.getName());

    private static final long PRIMARY_BYTE = 0;
    private static final long PRESENCE_BYTE = 1;

    /** A secondary's presence lock can collide with the primary's momentary probe; retry briefly. */
    private static final int PRESENCE_ATTEMPTS = 5;

    private static final long PRESENCE_RETRY_NANOS = 5_000_000L;

    /** {@code null} when the lock file could not be opened (then {@link #primary} is assumed). */
    private final FileChannel channel;
    /** Held for life; never read, only kept reachable so the lock is not released by a GC'd channel. */
    @SuppressWarnings("unused")
    private final FileLock primaryLock;

    private final boolean primary;
    private FileLock presenceLock;

    private InstanceLock(FileChannel channel, FileLock primaryLock, boolean primary) {
        this.channel = channel;
        this.primaryLock = primaryLock;
        this.primary = primary;
    }

    /** What one non-blocking lock attempt found. */
    private enum Attempt {
        ACQUIRED,
        HELD_ELSEWHERE,
        FAILED
    }

    private record Tried(Attempt outcome, FileLock lock) {}

    /** Claims {@code configDir} for this process. Never throws and never blocks on another process. */
    static InstanceLock claim(Path configDir) {
        FileChannel channel;
        try {
            Files.createDirectories(configDir);
            channel = FileChannel.open(
                    configDir.resolve(FILE_NAME),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "Could not open the instance lock in " + configDir + "; assuming a single instance", e);
            return new InstanceLock(null, null, true);
        }
        Tried tried = tryLock(channel, PRIMARY_BYTE, false);
        switch (tried.outcome()) {
            case ACQUIRED -> {
                return new InstanceLock(channel, tried.lock(), true);
            }
            case HELD_ELSEWHERE -> {
                InstanceLock secondary = new InstanceLock(channel, null, false);
                secondary.ensurePresence();
                return secondary;
            }
            default -> {
                // The filesystem refused the lock (some network mounts do). Nothing can be learned about other
                // processes here, so behave as before locking existed rather than cripple the only instance.
                closeQuietly(channel);
                return new InstanceLock(null, null, true);
            }
        }
    }

    /** Whether this process took the directory first. Fixed for the life of the process. */
    boolean primary() {
        return primary;
    }

    /**
     * Whether another Editora process is using the directory <em>right now</em>. Always true for a secondary
     * (the primary lock it lost is itself the evidence, and a secondary never does primary-only work). For
     * the primary it is a live probe, so it turns false again once every secondary has exited. When the
     * probe itself fails the answer is {@code true}: the callers gate destructive work on "nobody else is
     * here", and not knowing is not the same as nobody.
     */
    synchronized boolean othersPresent() {
        if (!primary) {
            ensurePresence(); // a presence lock that lost its first race is retried whenever we are asked
            return true;
        }
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        Tried tried = tryLock(channel, PRESENCE_BYTE, false);
        if (tried.outcome() != Attempt.ACQUIRED) {
            return true;
        }
        try {
            tried.lock().release();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not release the instance presence probe", e);
        }
        return false;
    }

    /** Takes (or re-takes) a secondary's shared presence lock. Returns whether it is held. */
    private synchronized boolean ensurePresence() {
        if (primary || channel == null || !channel.isOpen()) {
            return false;
        }
        if (presenceLock != null && presenceLock.isValid()) {
            return true;
        }
        for (int attempt = 0; attempt < PRESENCE_ATTEMPTS; attempt++) {
            Tried tried = tryLock(channel, PRESENCE_BYTE, true);
            if (tried.outcome() == Attempt.ACQUIRED) {
                presenceLock = tried.lock();
                return true;
            }
            if (tried.outcome() == Attempt.FAILED) {
                return false;
            }
            // Held exclusively for an instant by the primary's probe (or the platform has no shared locks
            // and another secondary already marks our presence): wait a moment and look again.
            LockSupport.parkNanos(PRESENCE_RETRY_NANOS);
        }
        return false;
    }

    private static Tried tryLock(FileChannel channel, long position, boolean shared) {
        try {
            FileLock lock = channel.tryLock(position, 1, shared);
            return lock == null ? new Tried(Attempt.HELD_ELSEWHERE, null) : new Tried(Attempt.ACQUIRED, lock);
        } catch (OverlappingFileLockException e) {
            // Another channel in this very JVM holds the range — a second config on the same directory (the
            // tests' way of standing in for a second process). The file-lock table is per JVM, so the answer
            // is the same one another process would have got from the operating system.
            return new Tried(Attempt.HELD_ELSEWHERE, null);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Instance lock attempt failed", e);
            return new Tried(Attempt.FAILED, null);
        }
    }

    /** Releases every lock by closing the channel. Idempotent. */
    @Override
    public synchronized void close() {
        presenceLock = null;
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not close the instance lock", e);
        }
    }
}
