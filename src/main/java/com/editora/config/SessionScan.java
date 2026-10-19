package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.editora.vfs.Vfs;

/**
 * Which of a saved session's open-file entries can be reopened right now, and which are kept although they
 * cannot be. The decision needs a stat per entry (three for one that cannot be read), each of which can stall
 * on a network mount, so a launch takes it on the config-prefetch thread and hands the result to the window
 * through {@link ConfigManager#setStartupSessionScan}; the restore itself then stats nothing on the FX
 * thread. A window built outside a launch scans inline, with the same rules.
 *
 * @param scanned the list that was scanned — the result is for exactly these entries (identity, so a reloaded
 *     session is never matched by a scan of the previous one)
 * @param openable the entries whose file is readable, in session order
 * @param missing the entries that cannot be read but are not known to be gone (see {@link #keepUnreadable}),
 *     in session order; they stay in the session so one launch without a volume does not erase its tabs
 */
public record SessionScan(
        List<WorkspaceState.OpenFile> scanned,
        List<WorkspaceState.OpenFile> openable,
        List<WorkspaceState.OpenFile> missing) {

    public SessionScan {
        openable = List.copyOf(openable);
        missing = List.copyOf(missing);
    }

    /** Scans {@code files} against the real filesystem; {@code resolve} turns a stored path into a path. */
    public static SessionScan of(List<WorkspaceState.OpenFile> files, Function<String, Path> resolve) {
        return of(files, resolve, Files::isReadable, Files::isDirectory, Files::notExists);
    }

    /**
     * The pure form. {@code resolve} may return {@code null} for an entry that cannot be resolved at all (a
     * remote file whose connection is not open): a remote one is kept (see {@link #unreachableRemote}); anything
     * else that does not resolve is neither openable nor kept.
     */
    public static SessionScan of(
            List<WorkspaceState.OpenFile> files,
            Function<String, Path> resolve,
            Predicate<Path> isReadable,
            Predicate<Path> isDirectory,
            Predicate<Path> notExists) {
        List<WorkspaceState.OpenFile> openable = new ArrayList<>();
        List<WorkspaceState.OpenFile> missing = new ArrayList<>();
        for (WorkspaceState.OpenFile f : files) {
            String stored = f.getPath();
            Path path = stored == null || stored.isBlank() ? null : resolve.apply(stored);
            if (path != null && path.getFileName() == null) {
                continue; // a root is never a tab (a hand-edited session); restoring one aborted the restore
            }
            if (path != null && isReadable.test(path)) {
                openable.add(f);
            } else if (keepUnreadable(path, isDirectory, notExists) || unreachableRemote(stored, path)) {
                missing.add(f);
            }
        }
        return new SessionScan(files, openable, missing);
    }

    /**
     * A remote file whose host is not connected: nothing is known about it either, so it is kept, like a file
     * on a volume that is not mounted. Whether the window wants it back is the window's decision.
     */
    static boolean unreachableRemote(String stored, Path resolved) {
        return resolved == null && stored != null && Vfs.isRemoteUri(stored);
    }

    /** Whether this scan is of exactly {@code files} (the list object, not an equal list). */
    public boolean covers(List<WorkspaceState.OpenFile> files) {
        return scanned == files;
    }

    /**
     * Whether a saved entry whose file cannot be read right now stays in the session.
     *
     * <p>It is dropped only when the file is known to be gone: its folder is there and the file is not —
     * someone deleted or renamed it, and keeping the entry would resurrect a tab nobody wants. When the
     * folder itself is missing or cannot be examined (an unmounted volume, a network share that is down, a
     * permission that was revoked) nothing has been learned about the file, so the entry is kept.
     *
     * @param file the resolved path, or {@code null} when the entry could not be resolved at all — those are
     *     not restorable and are dropped
     */
    public static boolean keepUnreadable(Path file, Predicate<Path> isDirectory, Predicate<Path> notExists) {
        if (file == null) {
            return false;
        }
        Path parent = file.toAbsolutePath().getParent();
        boolean knownDeleted = parent != null && isDirectory.test(parent) && notExists.test(file);
        return !knownDeleted;
    }
}
