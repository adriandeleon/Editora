package com.editora.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.editora.config.migration.NewerThanSupportedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes config stores so that this process never puts back data it did not itself change.
 *
 * <p>Every store used to be written whole from memory. Two Editora processes on one config directory (or one
 * process and a sync tool, or a hand edit) therefore reverted each other: the copy loaded at start was written
 * back over whatever had changed on disk since, on the next incidental save. For each file this class
 * remembers what this process last knew it to hold and what it last saw on disk, and a write then does one of
 * three things:
 *
 * <ol>
 *   <li><b>nothing</b>, when the store in memory is still what this process last read or wrote — it has no
 *       change to persist, so whatever is on disk stays. (A file that was migrated, re-stamped or replaced
 *       by defaults when it was loaded counts as changed by that load, and is written once.)
 *   <li><b>a plain write</b>, when the file on disk is still the one this process last read or wrote;
 *   <li><b>a merge</b> otherwise: the file is re-read and this process's own changes are applied to it
 *       ({@link StoreMerge}), keeping the other writer's version of everything this process did not touch.
 * </ol>
 *
 * <p>The other writer's changes are kept on disk; they are not loaded into this process's memory (it shows
 * them after a restart). Thread-safe: the queued stores are written on the config-writer thread, the others on
 * the FX thread.
 */
final class StoreSync {

    private static final Logger LOG = Logger.getLogger(StoreSync.class.getName());

    /** Beside the stores; held only for the read-merge-write of one file. Its content is never used. */
    static final String LOCK_FILE_NAME = "stores.lock";

    /** How long a write waits for another process's read-merge-write before going ahead regardless. */
    private static final long LOCK_WAIT_NANOS = 1_000_000_000L;

    private static final long LOCK_RETRY_NANOS = 2_000_000L;

    /**
     * One JVM takes the file lock from one thread at a time: the lock table is per JVM, so a second channel
     * in the same JVM (two configs on one directory — how the tests stand in for two processes) would get an
     * {@link OverlappingFileLockException} instead of waiting.
     */
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();

    /** What this process knows about one file. Guarded by {@link #JVM_LOCK}. */
    private static final class State {
        /** The store as this process last read or wrote it, serialized; {@code null} = never loaded here. */
        byte[] base;
        /** Hash of the file as this process last saw it; {@code null} = it did not exist. */
        byte[] diskHash;
        /** Whether the file, as last seen, held nothing but {@link #base} (so a plain write loses nothing). */
        boolean pure;
        /**
         * Whether the file, as loaded, is not byte for byte what this build writes for {@link #base}: an older
         * schema, a missing version stamp, values a migration changed, content that could not be read. Loading
         * it was itself a change this process made, so the next save writes it even if nothing else changed.
         */
        boolean normalize;
    }

    private final Path lockFile;
    private final ObjectMapper json;
    private final Map<Path, State> states = new ConcurrentHashMap<>();

    StoreSync(Path configDir, ObjectMapper json) {
        this.lockFile = configDir.resolve(LOCK_FILE_NAME);
        this.json = json;
    }

    /**
     * Records that {@code file} was just loaded and the store built from it serializes to {@code mine}. From
     * here on a write of those same bytes is "no change", and what the file holds now is the common ancestor
     * for a later merge.
     */
    void loaded(Path file, byte[] mine) {
        State state = new State();
        byte[] disk;
        try {
            disk = readIfPresent(file);
        } catch (IOException unreadable) {
            disk = new byte[0]; // whatever the load made of it, it is not what is on disk
        }
        state.base = mine;
        state.diskHash = hash(disk);
        state.pure = true;
        state.normalize = disk != null && !Arrays.equals(disk, mine);
        JVM_LOCK.lock();
        try {
            states.put(file, state);
        } finally {
            JVM_LOCK.unlock();
        }
    }

    /** Drops what is known about {@code file} (it is being deleted); a later write starts from nothing. */
    void forget(Path file) {
        JVM_LOCK.lock();
        try {
            states.remove(file);
        } finally {
            JVM_LOCK.unlock();
        }
    }

    /**
     * Makes {@code file} hold this process's store {@code mine} without undoing anyone else's changes.
     *
     * @param schema the store's schema, for migrating and merging what another writer left; {@code null} for
     *     a store with no schema (merged key by key, arrays as whole values)
     * @param onMerged told the tree that was written when it is not exactly {@code mine}; may be {@code null}
     * @return whether the file was written ({@code false}: this process had nothing to persist)
     * @throws IOException when the write failed, or the file on disk is now from a newer build
     */
    boolean write(Path file, byte[] mine, ConfigSchema schema, Consumer<JsonNode> onMerged) throws IOException {
        JVM_LOCK.lock();
        try (Held ignored = lockAcrossProcesses()) {
            State state = states.get(file);
            if (state != null
                    && state.base != null
                    && !state.normalize
                    && Arrays.equals(mine, state.base)
                    && Files.exists(file)) {
                return false; // nothing changed here; whatever is on disk stays
            }
            byte[] disk = readIfPresent(file);
            byte[] out = mine;
            boolean plain = disk == null
                    || (state != null
                            && state.pure
                            && state.diskHash != null
                            && Arrays.equals(hash(disk), state.diskHash));
            if (!plain) {
                JsonNode merged = merge(file, state == null ? null : state.base, mine, disk, schema);
                if (merged != null) {
                    out = json.writeValueAsBytes(merged);
                    if (onMerged != null) {
                        onMerged.accept(merged);
                    }
                }
            }
            ConfigWriter.writeAtomicChecked(file, out);
            State next = new State();
            next.base = mine;
            next.diskHash = hash(out);
            next.pure = out == mine;
            states.put(file, next);
            return true;
        } finally {
            JVM_LOCK.unlock();
        }
    }

    /**
     * The tree to write in place of {@code mine}, or {@code null} when {@code mine} itself is right: the other
     * writer changed nothing this process cares about, or left something that cannot be read (kept aside).
     */
    private JsonNode merge(Path file, byte[] base, byte[] mine, byte[] disk, ConfigSchema schema) throws IOException {
        JsonNode theirs;
        try {
            theirs = json.readTree(ConfigMigrations.decodeText(disk));
            if (theirs == null || !theirs.isObject()) {
                throw new IOException("not a JSON object");
            }
            if (schema != null) {
                theirs = ConfigMigrations.upgrade(schema, theirs, json);
            }
        } catch (NewerThanSupportedException newer) {
            // A newer build is writing this file now. Replacing it would drop what only that build knows.
            throw new IOException(
                    file.getFileName() + " was written by a newer Editora (schema " + newer.storedVersion()
                            + "); not overwriting it",
                    newer);
        } catch (IOException | RuntimeException unreadable) {
            // Damaged since this process last saw it: nothing to merge with. Keep the bytes, write ours.
            Path kept = ConfigMigrations.keepCopy(file);
            LOG.log(
                    Level.WARNING,
                    "Config file {0} changed on disk and cannot be read; kept a copy at {1}",
                    new Object[] {file, kept});
            return null;
        }
        JsonNode mineTree = json.readTree(mine);
        JsonNode baseTree = base == null ? json.createObjectNode() : json.readTree(base);
        if (theirs.equals(baseTree) || theirs.equals(mineTree)) {
            return null;
        }
        JsonNode merged = StoreMerge.merge(baseTree, mineTree, theirs, StoreMerge.keysFor(schema));
        return merged == null || merged.equals(mineTree) ? null : merged;
    }

    /** The file's bytes; {@code null} when it does not exist. */
    private static byte[] readIfPresent(Path file) throws IOException {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException unreadable) {
            if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw unreadable; // something is there that cannot be read: replacing it blind could lose it
            }
            return null;
        }
    }

    private static byte[] hash(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The cross-process lock for one read-merge-write; closing releases it. Never {@code null}. */
    private Held lockAcrossProcesses() {
        FileChannel channel = null;
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + LOCK_WAIT_NANOS;
            while (true) {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return new Held(channel);
                }
                if (System.nanoTime() - deadline >= 0) {
                    break; // a stuck holder must not stop this process from saving
                }
                LockSupport.parkNanos(LOCK_RETRY_NANOS);
            }
        } catch (IOException | RuntimeException noLock) {
            // A read-only directory or a filesystem without locks: write as before, unserialized.
            LOG.log(Level.FINE, "Could not lock " + lockFile, noLock);
        }
        closeQuietly(channel);
        return new Held(null);
    }

    private record Held(FileChannel channel) implements AutoCloseable {
        @Override
        public void close() {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not close the store lock", e);
            }
        }
    }
}
