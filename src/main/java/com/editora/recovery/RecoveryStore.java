package com.editora.recovery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The recovery directory: {@code <config>/recovery/<session>/<buffer>.rec}, one sub-directory per Editora
 * process and one file per unsaved buffer.
 *
 * <p><b>Whose records are whose.</b> Each process writes only inside its own session directory and holds an
 * exclusive operating-system lock on that directory's {@code session.lock} for as long as it lives. The lock
 * is released by the operating system when the process ends, however it ends, so "the lock can be taken" is
 * the one reliable sign that a session's owner is gone — unlike a recorded process id, it cannot go stale or
 * be reused. A second Editora on the same configuration directory therefore never lists, restores or deletes
 * the records of a running one. A process that finds a dead session keeps that session's lock itself (it
 * <em>adopts</em> it), so two later launches do not both offer the same records.
 *
 * <p><b>What a record on disk means.</b> A record is written to a temporary file, forced to the device and
 * then renamed into place, so a {@code .rec} file is always a complete record or the complete previous one.
 * A file that nevertheless does not read back whole is never offered and never deleted: it is reported and
 * left where it is.
 *
 * <p>When the file system refuses locks, a session cannot be proven dead; it is then treated as alive, and
 * its records stay on disk without being offered.
 *
 * <p>All methods do blocking file I/O. {@link RecoveryService} calls them from its one worker thread.
 */
public final class RecoveryStore implements AutoCloseable {

    /** The directory under the config directory. */
    public static final String DIR_NAME = "recovery";

    static final String LOCK_NAME = "session.lock";
    static final String RECORD_SUFFIX = ".rec";
    static final String TEMP_SUFFIX = ".tmp";

    /** A file larger than this is compared by size and modified time only, not by its bytes. */
    private static final long MAX_HASHED_BYTES = 64L * 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(RecoveryStore.class.getName());
    private static final int SESSION_ATTEMPTS = 3;

    /** How the file a record belongs to compares with what the edits were based on. */
    public enum DiskState {
        /** An untitled buffer: there is no file. */
        NO_FILE,
        /** The file is what the edits were made against. */
        UNCHANGED,
        /** The file was changed after the edits were made. */
        CHANGED,
        /** The file is gone. */
        MISSING,
        /** A remote file: not asked, because that needs a connection. */
        REMOTE,
        /** Nothing was recorded to compare with. */
        UNKNOWN
    }

    /** A record left by a session whose process is gone; {@code record} carries no text. */
    public record Entry(Path file, RecoveryRecord record, DiskState disk) {}

    /** What dead sessions left: the records that read back whole, and the files that did not. */
    public record Orphans(List<Entry> entries, List<Path> unreadable) {
        public static final Orphans NONE = new Orphans(List.of(), List.of());
    }

    private final Path root;
    private Path sessionDir;
    private FileChannel sessionLock;
    /** Dead sessions whose lock this process now holds, by directory. */
    private final Map<Path, FileChannel> adopted = new LinkedHashMap<>();

    public RecoveryStore(Path configDir) {
        this.root = configDir.resolve(DIR_NAME);
    }

    /** The recovery directory itself. */
    public Path root() {
        return root;
    }

    /** This process's session directory, or {@code null} while it has written nothing. */
    public synchronized Path sessionDir() {
        return sessionDir;
    }

    // --- this session's records ---

    /** Stores {@code record}, replacing the buffer's previous record. Durable when it returns. */
    public synchronized void write(RecoveryRecord record) throws IOException {
        Path dir = ensureSession();
        Path target = dir.resolve(fileName(record.bufferId()));
        Path temp = dir.resolve(fileName(record.bufferId()) + TEMP_SUFFIX);
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(RecoveryCodec.encode(record));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                deleteQuietly(temp);
            }
        }
        forceDirectory(dir);
    }

    /** Deletes the buffer's record, if it has one. */
    public synchronized void remove(String bufferId) throws IOException {
        if (sessionDir != null) {
            Files.deleteIfExists(sessionDir.resolve(fileName(bufferId)));
        }
    }

    /** The buffer ids that currently have a record in this session (tests, diagnostics). */
    public synchronized List<String> ownRecordIds() {
        List<String> ids = new ArrayList<>();
        if (sessionDir != null) {
            for (Path file : list(sessionDir)) {
                String name = file.getFileName().toString();
                if (name.endsWith(RECORD_SUFFIX)) {
                    ids.add(name.substring(0, name.length() - RECORD_SUFFIX.length()));
                }
            }
        }
        return ids;
    }

    private Path ensureSession() throws IOException {
        if (sessionDir != null) {
            return sessionDir;
        }
        createPrivateDirectory(root);
        IOException last = null;
        for (int attempt = 0; attempt < SESSION_ATTEMPTS; attempt++) {
            Path dir = root.resolve(newSessionId());
            try {
                createPrivateDirectory(dir);
                FileChannel channel =
                        FileChannel.open(dir.resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                Boolean locked = tryLock(channel);
                if (Boolean.FALSE.equals(locked)) {
                    // Another process adopted the directory between its creation and this lock: it is theirs.
                    closeQuietly(channel);
                    continue;
                }
                if (locked == null) {
                    LOG.warning("The file system refused a lock on " + dir
                            + "; these recovery records cannot be told from a running editor's");
                }
                sessionDir = dir;
                sessionLock = channel;
                return dir;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Could not claim a recovery session directory in " + root);
    }

    private static String newSessionId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + "-"
                + ProcessHandle.current().pid()
                + "-"
                + Long.toString(new SecureRandom().nextLong() & Long.MAX_VALUE, 36);
    }

    private static String fileName(String bufferId) {
        if (bufferId == null || !bufferId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("not a recovery buffer id: " + bufferId);
        }
        return bufferId + RECORD_SUFFIX;
    }

    // --- records left by sessions whose process is gone ---

    /**
     * Lists the records of every session whose owner is no longer running, taking over those sessions so no
     * other process lists them too. Sessions already taken over are listed again; a running editor's session
     * is never touched. Empty leftovers of dead sessions are removed.
     */
    public synchronized Orphans claimOrphans() {
        if (!Files.isDirectory(root)) {
            return Orphans.NONE;
        }
        for (Path dir : list(root)) {
            if (!Files.isDirectory(dir) || dir.equals(sessionDir) || adopted.containsKey(dir)) {
                continue;
            }
            try {
                FileChannel channel =
                        FileChannel.open(dir.resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                if (Boolean.TRUE.equals(tryLock(channel))) {
                    adopted.put(dir, channel);
                } else {
                    closeQuietly(channel); // its owner is running, or cannot be shown not to be
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Could not examine the recovery session " + dir, e);
            }
        }
        List<Entry> entries = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        for (Path dir : List.copyOf(adopted.keySet())) {
            int kept = 0;
            for (Path file : list(dir)) {
                String name = file.getFileName().toString();
                if (name.endsWith(TEMP_SUFFIX)) {
                    deleteQuietly(file); // a write that never completed; the record it would replace is intact
                } else if (name.endsWith(RECORD_SUFFIX)) {
                    kept++;
                    try {
                        RecoveryRecord record = RecoveryCodec.decode(Files.readAllBytes(file), false);
                        entries.add(new Entry(file, record, diskState(record)));
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        LOG.log(Level.WARNING, "Unreadable recovery record left in place: " + file, e);
                        unreadable.add(file);
                    }
                } else if (!name.equals(LOCK_NAME)) {
                    kept++; // not ours to judge
                }
            }
            if (kept == 0) {
                releaseAdopted(dir, true);
            }
        }
        entries.sort(Comparator.comparingLong((Entry e) -> e.record().savedAtMillis())
                .reversed()
                .thenComparing(e -> e.file().toString()));
        return new Orphans(List.copyOf(entries), List.copyOf(unreadable));
    }

    /** Reads an orphaned record with its text. */
    public synchronized RecoveryRecord read(Entry entry) throws IOException {
        return RecoveryCodec.decode(Files.readAllBytes(entry.file()), true);
    }

    /** Deletes an orphaned record — the user's explicit choice — and its session directory once empty. */
    public synchronized void discard(Entry entry) throws IOException {
        Path dir = entry.file().getParent();
        if (!adopted.containsKey(dir)) {
            throw new IOException("Not a record of a session this process took over: " + entry.file());
        }
        Files.deleteIfExists(entry.file());
        if (onlyLockLeft(dir)) {
            releaseAdopted(dir, true);
        }
    }

    private void releaseAdopted(Path dir, boolean delete) {
        FileChannel channel = adopted.remove(dir);
        closeQuietly(channel); // before the delete: Windows does not delete an open file
        if (delete) {
            deleteQuietly(dir.resolve(LOCK_NAME));
            deleteQuietly(dir);
        }
    }

    private static boolean onlyLockLeft(Path dir) {
        for (Path file : list(dir)) {
            if (!file.getFileName().toString().equals(LOCK_NAME)) {
                return false;
            }
        }
        return true;
    }

    /** How the record's file compares with what its edits were based on. Never touches a remote file. */
    static DiskState diskState(RecoveryRecord record) {
        if (record.untitled()) {
            return DiskState.NO_FILE;
        }
        if (com.editora.vfs.Vfs.isRemoteUri(record.path())) {
            return DiskState.REMOTE;
        }
        if (record.baseModifiedMillis() < 0) {
            return DiskState.UNKNOWN;
        }
        try {
            Path file = Path.of(record.path());
            boolean wasNew = record.baseModifiedMillis() == 0 && record.baseSize() == 0;
            if (!Files.exists(file)) {
                return wasNew ? DiskState.UNCHANGED : DiskState.MISSING;
            }
            long size = Files.size(file);
            if (size != record.baseSize()) {
                return DiskState.CHANGED;
            }
            if (record.baseFingerprint() != null && size <= MAX_HASHED_BYTES) {
                // The bytes decide when they were recorded: a file that was only touched, or rewritten with
                // the same content (a build step, a checkout), is not a change — and one rewritten at the
                // same size within the clock's resolution is.
                byte[] digest =
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
                return java.util.HexFormat.of().formatHex(digest).equals(record.baseFingerprint())
                        ? DiskState.UNCHANGED
                        : DiskState.CHANGED;
            }
            if (Files.getLastModifiedTime(file).toMillis() == record.baseModifiedMillis()) {
                return DiskState.UNCHANGED;
            }
            return DiskState.CHANGED;
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException e) {
            return DiskState.UNKNOWN;
        }
    }

    // --- lifetime ---

    /**
     * Ends this process's use of the directory in the ordinary way: releases every lock and removes the
     * directories that hold no record. Records that are still there stay, and are offered by the next launch.
     */
    @Override
    public synchronized void close() {
        for (Path dir : List.copyOf(adopted.keySet())) {
            releaseAdopted(dir, onlyLockLeft(dir));
        }
        if (sessionDir != null) {
            boolean empty = onlyLockLeft(sessionDir);
            closeQuietly(sessionLock);
            if (empty) {
                deleteQuietly(sessionDir.resolve(LOCK_NAME));
                deleteQuietly(sessionDir);
            }
            sessionLock = null;
            sessionDir = null;
        }
        deleteQuietly(root); // only succeeds when nothing is left in it
    }

    /**
     * Releases every lock and deletes nothing — what the operating system does when the process dies. For
     * tests that stand in for a crash.
     */
    public synchronized void abandon() {
        adopted.values().forEach(RecoveryStore::closeQuietly);
        adopted.clear();
        closeQuietly(sessionLock);
        sessionLock = null;
        sessionDir = null;
    }

    // --- helpers ---

    /** TRUE = locked by us now; FALSE = held by someone else; null = the file system would not say. */
    private static Boolean tryLock(FileChannel channel) {
        try {
            FileLock lock = channel.tryLock();
            return lock != null;
        } catch (OverlappingFileLockException e) {
            return Boolean.FALSE; // held through another channel of this JVM (a second store on one directory)
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Recovery session lock attempt failed", e);
            return null;
        }
    }

    /** Recovery copies hold document text, which may come from a file only its owner can read. */
    private static void createPrivateDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(
                    dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createDirectories(dir);
        }
    }

    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true); // makes the rename itself durable where the platform allows it
        } catch (IOException | RuntimeException notSupported) {
            // Windows cannot open a directory this way; the file's own bytes were forced above.
        }
    }

    private static List<Path> list(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(out::add);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not list " + dir, e);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not close a recovery session lock", e);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not delete " + path, e);
        }
    }
}
