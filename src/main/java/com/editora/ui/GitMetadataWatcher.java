package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.io.WatchServices;
import org.jspecify.annotations.Nullable;

/**
 * Hears a repository's index, {@code HEAD} and branch refs change on disk, whoever changed them: a {@code git commit}
 * in the integrated terminal, another editor, a script. The Project tree does not watch {@code .git}, and focus never
 * left the window, so the Commit window used to go on listing what had just been committed.
 *
 * <p>One watcher for the whole application (one inotify instance, of a user's 128), shared by every window; each
 * watched folder is registered once however many windows ask for it. A window's listener is told, on the watcher's
 * thread, that something relevant changed; it coalesces and refreshes on the FX thread itself.
 *
 * <p>What is watched, for a work tree at {@code root}: its git folder ({@code .git}, or the folder a {@code .git}
 * file names for a linked worktree or a submodule) — {@code index}, {@code HEAD}, the operation markers — and the
 * common folder's {@code packed-refs} and {@code refs/heads}, where a commit moves the branch.
 */
final class GitMetadataWatcher {

    private static final Logger LOG = Logger.getLogger(GitMetadataWatcher.class.getName());

    /** The application's watcher. */
    static final GitMetadataWatcher SHARED = new GitMetadataWatcher();

    /** The names in a git folder whose change can change what {@code git status} answers. */
    private static final Set<String> GIT_DIR_NAMES = Set.of(
            "index",
            "HEAD",
            "ORIG_HEAD",
            "MERGE_HEAD",
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "REBASE_HEAD",
            "packed-refs",
            "rebase-merge",
            "rebase-apply",
            "sequencer");

    /** Where a work tree keeps its git data: its own folder, and the one its refs live in. */
    record GitDirs(Path gitDir, Path commonDir) {
        /** The folders to watch, without repeats; {@code refs/heads} when it exists. */
        List<Path> watched() {
            Set<Path> dirs = new LinkedHashSet<>();
            dirs.add(gitDir);
            dirs.add(commonDir);
            Path heads = commonDir.resolve("refs").resolve("heads");
            if (Files.isDirectory(heads)) {
                dirs.add(heads);
            }
            return new ArrayList<>(dirs);
        }
    }

    /**
     * The git folders of the work tree at {@code root}, read from the disk (off the FX thread): {@code root/.git} when
     * it is a folder; the folder named by a {@code .git} file's {@code gitdir:} line (a linked worktree, a
     * submodule), with its {@code commondir} file followed. Null when there is neither.
     */
    static @Nullable GitDirs dirsOf(Path root) {
        Path dotGit = root.resolve(".git");
        Path gitDir;
        try {
            if (Files.isDirectory(dotGit)) {
                gitDir = dotGit;
            } else if (Files.isRegularFile(dotGit)) {
                String pointer = gitDirLine(Files.readString(dotGit, StandardCharsets.UTF_8));
                if (pointer == null) {
                    return null;
                }
                gitDir = root.resolve(pointer).normalize();
                if (!Files.isDirectory(gitDir)) {
                    return null;
                }
            } else {
                return null;
            }
            Path common = gitDir;
            Path commondir = gitDir.resolve("commondir");
            if (Files.isRegularFile(commondir)) {
                String line =
                        Files.readString(commondir, StandardCharsets.UTF_8).strip();
                if (!line.isEmpty()) {
                    Path resolved = gitDir.resolve(line).normalize();
                    if (Files.isDirectory(resolved)) {
                        common = resolved;
                    }
                }
            }
            return new GitDirs(gitDir, common);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The path of a {@code .git} file's {@code gitdir: <path>} line, or null. Pure. */
    static @Nullable String gitDirLine(String text) {
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("gitdir:")) {
                String path = stripped.substring("gitdir:".length()).strip();
                return path.isEmpty() ? null : path;
            }
        }
        return null;
    }

    /**
     * Whether a change to {@code name} in a watched folder can change the status: in a git folder one of
     * {@link #GIT_DIR_NAMES}, under {@code refs/heads} any branch; never a {@code .lock} file (git writes
     * {@code index.lock} and renames it over {@code index}, which is heard on its own). Pure.
     */
    static boolean relevant(String name, boolean refsFolder) {
        if (name == null || name.endsWith(".lock")) {
            return false;
        }
        return refsFolder || GIT_DIR_NAMES.contains(name);
    }

    /** A watched folder: its key, whether it is a refs folder, and who is told about it. */
    private static final class Registration {
        final WatchKey key;
        final boolean refs;
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        Registration(WatchKey key, boolean refs) {
            this.key = key;
            this.refs = refs;
        }
    }

    private final Object lock = new Object();
    private @Nullable WatchService service;
    private final Map<Path, Registration> registrations = new HashMap<>();

    /**
     * Tells {@code onChange} — on the watcher's thread, possibly several times for one git command — whenever the
     * repository at {@code root} changes its index, HEAD or branches. The folders are found and registered off the
     * calling thread. Closing the returned handle stops it; a handle closed before its registration is done
     * registers nothing.
     */
    AutoCloseable watch(Path root, Runnable onChange) {
        Handle handle = new Handle(onChange);
        Thread.ofVirtual().name("git-metadata-register").start(() -> register(root, handle));
        return handle;
    }

    /** One {@link #watch}: what it registered, and whether it has been closed. */
    private final class Handle implements AutoCloseable {
        final Runnable listener;
        final List<Path> dirs = new ArrayList<>();
        boolean closed;

        Handle(Runnable listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                for (Path dir : dirs) {
                    Registration registration = registrations.get(dir);
                    if (registration != null) {
                        registration.listeners.remove(listener);
                        if (registration.listeners.isEmpty()) {
                            registration.key.cancel();
                            registrations.remove(dir);
                        }
                    }
                }
                dirs.clear();
            }
        }
    }

    private void register(Path root, Handle handle) {
        GitDirs dirs = dirsOf(root);
        if (dirs == null) {
            return; // GIT_DIR elsewhere, or a layout this does not know: focus regain still refreshes
        }
        Path heads = dirs.commonDir().resolve("refs").resolve("heads");
        synchronized (lock) {
            if (handle.closed) {
                return;
            }
            for (Path dir : dirs.watched()) {
                Path key = dir.toAbsolutePath().normalize();
                Registration registration = registrations.get(key);
                if (registration == null) {
                    try {
                        WatchKey watchKey = WatchServices.register(
                                key,
                                serviceLocked(),
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        registration = new Registration(
                                watchKey, key.equals(heads.toAbsolutePath().normalize()));
                        registrations.put(key, registration);
                    } catch (IOException | RuntimeException unwatchable) {
                        LOG.log(Level.FINE, "Not watching " + key, unwatchable);
                        continue;
                    }
                }
                registration.listeners.add(handle.listener);
                handle.dirs.add(key);
            }
        }
    }

    /** The watch service, opened with its drain thread on first use. Holds {@link #lock}. */
    private WatchService serviceLocked() {
        WatchService ws = service;
        if (ws == null) {
            ws = WatchServices.open();
            service = ws;
            WatchService drained = ws;
            Thread thread = new Thread(() -> drain(drained), "git-metadata-watch");
            thread.setDaemon(true);
            thread.start();
        }
        return ws;
    }

    private void drain(WatchService ws) {
        while (true) {
            WatchKey key;
            try {
                key = ws.take();
            } catch (InterruptedException | ClosedWatchServiceException stopped) {
                return;
            }
            boolean relevant = false;
            List<Runnable> listeners = List.of();
            synchronized (lock) {
                Object watchable = key.watchable();
                Registration registration = watchable instanceof Path dir ? registrations.get(dir) : null;
                if (registration != null && registration.key == key) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW
                                || relevant(String.valueOf(event.context()), registration.refs)) {
                            relevant = true;
                        }
                    }
                    listeners = List.copyOf(registration.listeners);
                } else {
                    key.pollEvents();
                }
            }
            key.reset();
            if (relevant) {
                for (Runnable listener : listeners) {
                    try {
                        listener.run();
                    } catch (RuntimeException failed) {
                        LOG.log(Level.FINE, "git metadata listener failed", failed);
                    }
                }
            }
        }
    }

    /** How many folders are registered (tests). */
    int registeredForTest() {
        synchronized (lock) {
            return registrations.size();
        }
    }
}
