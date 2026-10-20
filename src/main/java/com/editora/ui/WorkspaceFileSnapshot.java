package com.editora.ui;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What the workspace-edit checks read from disk, captured off the FX thread just before the FX step that
 * reads it.
 *
 * <p>The checks that decide whether an edit may land (a file still where staging left it, a file the server
 * did not have open unchanged since the request) run on the FX thread, in the same turn as the edit, so no
 * keystroke or save of this window slips in between. For a local file a stat is cheap and they read the disk
 * directly; for a file on a remote host each one is a network round trip, and a rename touching a few dozen
 * files froze the window. So for those the stats are taken on the workspace worker into this snapshot, and
 * the FX step reads the snapshot. A path the snapshot did not capture is asked of the live disk.
 *
 * <p>Read-only: every write goes to the live operations.
 */
final class WorkspaceFileSnapshot implements LspCoordinator.WorkspaceFileOperations {

    private record Entry(
            boolean exists,
            boolean directory,
            boolean regularFile,
            LspCoordinator.@Nullable WorkspaceFileIdentity identity) {}

    private final LspCoordinator.WorkspaceFileOperations live;
    private final Map<Path, Entry> entries;

    private WorkspaceFileSnapshot(LspCoordinator.WorkspaceFileOperations live, Map<Path, Entry> entries) {
        this.live = live;
        this.entries = entries;
    }

    /** Stats {@code paths} through {@code live} now — call it off the FX thread. */
    static WorkspaceFileSnapshot of(LspCoordinator.WorkspaceFileOperations live, Collection<Path> paths) {
        Map<Path, Entry> entries = new HashMap<>();
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            Path key = key(path);
            if (entries.containsKey(key)) {
                continue;
            }
            try {
                LspCoordinator.WorkspaceFileIdentity identity = live.identity(key);
                entries.put(key, new Entry(true, live.isDirectory(key), live.isRegularFile(key), identity));
            } catch (IOException | RuntimeException missing) {
                entries.put(key, new Entry(false, false, false, null));
            }
        }
        return new WorkspaceFileSnapshot(live, Map.copyOf(entries));
    }

    /** Whether any of {@code paths} is on a remote host — where a snapshot is worth the extra worker turn. */
    static boolean anyRemote(Collection<Path> paths) {
        return paths.stream().anyMatch(p -> p != null && com.editora.vfs.Vfs.isRemote(p));
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private @Nullable Entry entry(Path path) {
        return path == null ? null : entries.get(key(path));
    }

    @Override
    public boolean exists(Path path) {
        Entry e = entry(path);
        return e != null ? e.exists() : live.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        Entry e = entry(path);
        return e != null ? e.directory() : live.isDirectory(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        Entry e = entry(path);
        return e != null ? e.regularFile() : live.isRegularFile(path);
    }

    @Override
    public long size(Path path) throws IOException {
        Entry e = entry(path);
        if (e == null) {
            return live.size(path);
        }
        if (!e.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        LspCoordinator.WorkspaceFileIdentity identity = e.identity(); // read for every file that exists
        return identity == null ? live.size(path) : identity.size();
    }

    @Override
    public LspCoordinator.@Nullable WorkspaceFileIdentity identity(Path path) throws IOException {
        Entry e = entry(path);
        if (e == null) {
            return live.identity(path);
        }
        if (!e.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return e.identity();
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        live.createDirectories(path);
    }

    @Override
    public void createFile(Path path) throws IOException {
        live.createFile(path);
    }

    @Override
    public void move(Path from, Path to, CopyOption... options) throws IOException {
        live.move(from, to, options);
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
        return live.createTempFile(directory, prefix, suffix);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return live.deleteIfExists(path);
    }

    @Override
    public List<Path> list(Path path) throws IOException {
        return live.list(path);
    }

    @Override
    public List<Path> walk(Path path) throws IOException {
        return live.walk(path);
    }
}
