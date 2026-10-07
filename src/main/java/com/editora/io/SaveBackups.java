package com.editora.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.sshd.sftp.client.fs.SftpFileSystem;

/**
 * The backup files an in-place document write ({@link AtomicFileWrite.Outcome#IN_PLACE}) leaves behind when
 * it does not finish: the process was killed, the machine lost power, or the previous bytes could not be
 * written back.
 *
 * <p>Such a write truncates the user's file, so until it completes the backup is the only copy of what the
 * file held. A backup that outlives its write used to be found by nobody: the folder was never read. Each
 * backup therefore has a small note beside it naming the file it belongs to, and {@link #scan} lists the
 * pairs so the next launch can offer to put the bytes back.
 */
public final class SaveBackups {

    /** The suffix of a backup file. */
    public static final String SUFFIX = ".editora-backup";

    /** The suffix, after a backup's whole name, of the note that names its file. */
    static final String NOTE_SUFFIX = ".target";

    /** A backup younger than this may belong to a write that another running editor has not finished. */
    private static final Duration SETTLE = Duration.ofMinutes(10);

    private SaveBackups() {}

    /**
     * A backup found on disk.
     *
     * @param backup the file holding the previous bytes
     * @param target the absolute local path it was taken from, a URI for a remote file, or null when unknown
     * @param modified when the backup was written
     */
    public record Leftover(Path backup, String target, Instant modified) {

        /** Whether the file is on a remote filesystem (named by URI), which a launch cannot reach by itself. */
        public boolean remote() {
            return target != null && target.contains("://");
        }
    }

    /** How a backup compares with the file it was taken from. */
    public enum State {
        /** The file holds exactly the backed-up bytes: nothing was lost and the backup is redundant. */
        SAME,
        /** The file differs — it is torn, or it is the completed newer version. Only the user can tell. */
        DIFFERENT,
        /** The file no longer exists. */
        TARGET_MISSING,
        /** The file is on a remote server. */
        REMOTE,
        /** Nothing says which file the backup belongs to (it predates the note, or the note is unreadable). */
        UNKNOWN_TARGET
    }

    /** Records, beside {@code backup}, which file it holds the previous bytes of. Best effort. */
    static void noteTarget(Path backup, Path target) {
        try {
            String name = target.getFileSystem() instanceof SftpFileSystem
                    ? target.toUri().toString()
                    : target.toAbsolutePath().toString();
            Files.writeString(note(backup), name, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unrecorded) {
            // The backup itself still exists and is still named in any error; only the offer loses its target.
        }
    }

    /** Removes the note of a backup that is being deleted. */
    static void forget(Path backup) {
        try {
            Files.deleteIfExists(note(backup));
        } catch (IOException | RuntimeException leftBehind) {
            // A stray note without its backup is ignored by scan().
        }
    }

    private static Path note(Path backup) {
        return backup.resolveSibling(backup.getFileName() + NOTE_SUFFIX);
    }

    /** Every backup in {@code dir}, oldest first; empty when the folder is missing or unreadable. */
    public static List<Leftover> scan(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Leftover> found = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path backup : entries) {
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                found.add(new Leftover(
                        backup,
                        readNote(backup),
                        Files.getLastModifiedTime(backup).toInstant()));
            }
        } catch (IOException | RuntimeException unreadable) {
            return List.copyOf(found);
        }
        found.sort(Comparator.comparing(Leftover::modified).thenComparing(Leftover::backup));
        return List.copyOf(found);
    }

    private static String readNote(Path backup) {
        try {
            String name = Files.readString(note(backup), StandardCharsets.UTF_8).strip();
            return name.isEmpty() ? null : name;
        } catch (IOException | RuntimeException missing) {
            return null;
        }
    }

    /** The local file {@code leftover} belongs to, or null when it is unknown or remote. */
    public static Path targetPath(Leftover leftover) {
        if (leftover.target() == null || leftover.remote()) {
            return null;
        }
        try {
            return Path.of(leftover.target());
        } catch (InvalidPathException unusable) {
            return null;
        }
    }

    /** Compares {@code leftover} with its file. Reads both, so call it away from the UI thread. */
    public static State state(Leftover leftover) {
        if (leftover.target() == null) {
            return State.UNKNOWN_TARGET;
        }
        if (leftover.remote()) {
            return State.REMOTE;
        }
        Path target = targetPath(leftover);
        if (target == null) {
            return State.UNKNOWN_TARGET;
        }
        if (!Files.exists(target)) {
            return State.TARGET_MISSING;
        }
        try {
            return Files.size(target) == Files.size(leftover.backup())
                            && Arrays.equals(Files.readAllBytes(target), Files.readAllBytes(leftover.backup()))
                    ? State.SAME
                    : State.DIFFERENT;
        } catch (IOException | RuntimeException unreadable) {
            return State.DIFFERENT; // cannot be shown to be redundant: keep it and ask
        }
    }

    /**
     * Whether {@code leftover} is old enough that no running save can still own it. A redundant backup is
     * only deleted unasked once this holds: deleting the backup of a write that is about to truncate its
     * file would remove the one thing protecting it.
     */
    public static boolean settled(Leftover leftover, Instant now) {
        return leftover.modified().plus(SETTLE).isBefore(now);
    }

    /**
     * Writes the backed-up bytes over the file they came from, then removes the backup. The write is an
     * ordinary document save, so the file's current (torn or newer) bytes are themselves backed up while it
     * runs.
     *
     * @throws IOException when the target is unknown or remote, the backup is gone, or the write fails — the
     *     backup is kept in every such case
     */
    public static void restore(Leftover leftover, Path backupDir) throws IOException {
        Path target = targetPath(leftover);
        if (target == null) {
            throw new IOException("No local file is recorded for " + leftover.backup());
        }
        byte[] previous = Files.readAllBytes(leftover.backup());
        AtomicFileWrite.writeDocument(target, previous, () -> true, backupDir);
        discard(leftover);
    }

    /** Deletes the backup and its note. */
    public static void discard(Leftover leftover) {
        try {
            Files.deleteIfExists(leftover.backup());
        } catch (IOException | RuntimeException kept) {
            return; // still listed next time, with its note
        }
        forget(leftover.backup());
    }
}
