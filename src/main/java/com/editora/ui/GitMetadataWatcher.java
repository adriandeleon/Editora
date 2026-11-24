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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

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
 * file names for a linked worktree or a submodule) — {@code index}, {@code HEAD}, the operation markers — and its
 * {@code logs/HEAD}, the reflog git appends to whenever {@code HEAD} moves (the most reliable "HEAD moved" signal);
 * the common folder's {@code packed-refs}, every folder under {@code refs/heads} (a {@code feature/x} branch lives in
 * {@code refs/heads/feature}), and {@code reftable} for a reftable repository. A linked worktree never hears the main
 * work tree's {@code index} or {@code HEAD}, which share its common folder. A folder whose watch dies (the git folder
 * deleted and made again, the watch limit) is dropped, and the handle says it is {@link Handle#stale()} so the owner
 * watches again.
 */
final class GitMetadataWatcher {

    private static final Logger LOG = Logger.getLogger(GitMetadataWatcher.class.getName());

    /** Whether a folder that could not be watched has been logged at INFO (once: the watch limit is the usual cause). */
    private static final AtomicBoolean LOGGED_UNWATCHABLE = new AtomicBoolean();

    /** Whether a repository with more branch folders than {@link #MAX_REF_FOLDERS} has been logged at INFO (once). */
    private static final AtomicBoolean LOGGED_REF_CAP = new AtomicBoolean();

    /**
     * At most this many folders under {@code refs/heads} ({@code refs/heads} itself included) are watched per
     * repository: each is a watch of the user's few thousand (inotify {@code max_user_watches}), shared by every
     * application. Beyond it, a branch moved from outside in a folder not watched is still heard through
     * {@code logs/HEAD} when it is the checked-out one, and through {@code packed-refs} after a pack.
     */
    static final int MAX_REF_FOLDERS = 256;

    /**
     * Whether folders under {@code refs/heads} are watched one by one. Not on Windows: a watched folder there is held
     * open, and git cannot delete {@code refs/heads/feature/} when its last branch goes, or make it again, while the
     * watch holds it. {@code refs/heads} itself (never deleted) is still watched, and a move of the checked-out branch
     * is heard through {@code logs/HEAD}; other {@code feature/} branches are seen at the next refresh (focus, a
     * command of this window).
     */
    static boolean watchesRefSubfolders(boolean windows) {
        return !windows;
    }

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

    /** What a watched folder is to a work tree, which decides the names in it that matter. */
    enum Kind {
        /** The work tree's own git folder: {@code index}, {@code HEAD}, the operation markers. */
        GIT_DIR,
        /** A linked worktree's common folder: only {@code packed-refs} (its index and HEAD are another tree's). */
        COMMON_DIR,
        /** {@code refs/heads} or a folder under it: any branch. */
        REFS,
        /** The git folder's {@code logs}: {@code HEAD}'s reflog. */
        LOGS,
        /** A reftable repository's {@code reftable} folder: any table. */
        REFTABLE
    }

    /** Where a work tree keeps its git data: its own folder, and the one its refs live in. */
    record GitDirs(Path gitDir, Path commonDir) {
        /**
         * The folders to watch and what each is, without repeats (a folder that is both the git folder and the
         * common one is the git folder); every folder under {@code refs/heads}, {@code logs} and {@code reftable}
         * when they exist. Reads the disk.
         */
        Map<Path, Kind> watched() {
            return watched(watchesRefSubfolders(com.editora.Os.isWindows()), MAX_REF_FOLDERS);
        }

        /**
         * As {@link #watched()}, with the folders under {@code refs/heads} watched one by one or not
         * ({@code refSubfolders}, false on Windows) and at most {@code maxRefFolders} of them. Reads the disk.
         */
        Map<Path, Kind> watched(boolean refSubfolders, int maxRefFolders) {
            Map<Path, Kind> dirs = new LinkedHashMap<>();
            dirs.put(gitDir, Kind.GIT_DIR);
            dirs.putIfAbsent(commonDir, Kind.COMMON_DIR);
            Path logs = gitDir.resolve("logs");
            if (Files.isDirectory(logs)) {
                dirs.putIfAbsent(logs, Kind.LOGS);
            }
            Path heads = commonDir.resolve("refs").resolve("heads");
            List<Path> refFolders =
                    refSubfolders ? foldersUnder(heads) : (Files.isDirectory(heads) ? List.of(heads) : List.<Path>of());
            if (refFolders.size() > maxRefFolders) {
                logRefCap(commonDir, refFolders.size(), maxRefFolders);
                refFolders = refFolders.subList(0, maxRefFolders); // the walk lists refs/heads first
            }
            for (Path folder : refFolders) {
                dirs.putIfAbsent(folder, Kind.REFS);
            }
            Path reftable = commonDir.resolve("reftable");
            if (Files.isDirectory(reftable)) {
                dirs.putIfAbsent(reftable, Kind.REFTABLE);
            }
            return dirs;
        }
    }

    /** Says once, at INFO, that a repository has more branch folders than are watched. */
    private static void logRefCap(Path commonDir, int folders, int cap) {
        if (LOGGED_REF_CAP.compareAndSet(false, true)) {
            LOG.info("Watching " + cap + " of the " + folders + " folders under refs/heads of " + commonDir
                    + "; branches in the others are heard through logs/HEAD and packed-refs");
        }
    }

    /** {@code top} and every folder under it, or nothing when it is not a folder. Reads the disk. */
    static List<Path> foldersUnder(Path top) {
        if (!Files.isDirectory(top)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(top)) {
            return walk.filter(Files::isDirectory).toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of(top);
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
     * Whether a change to {@code name} in a watched folder of {@code kind} can change the status: in a git folder one
     * of {@link #GIT_DIR_NAMES}, in a linked worktree's common folder {@code packed-refs}, under {@code refs/heads}
     * any branch, in {@code logs} {@code HEAD}'s reflog, in {@code reftable} any table; never a {@code .lock} file
     * (git writes {@code index.lock} and renames it over {@code index}, which is heard on its own). Pure.
     */
    static boolean relevant(@Nullable String name, Kind kind) {
        if (name == null || name.endsWith(".lock")) {
            return false;
        }
        return switch (kind) {
            case GIT_DIR -> GIT_DIR_NAMES.contains(name);
            case COMMON_DIR -> "packed-refs".equals(name);
            case LOGS -> "HEAD".equals(name);
            case REFS, REFTABLE -> true;
        };
    }

    /** One handle's interest in a watched folder. */
    private record Entry(Handle handle, Kind kind) {}

    /** A watched folder: its key and who is told about it, each with what the folder is to them. */
    private static final class Registration {
        final WatchKey key;
        final List<Entry> entries = new CopyOnWriteArrayList<>();

        Registration(WatchKey key) {
            this.key = key;
        }
    }

    private final Object lock = new Object();
    private @Nullable WatchService service;
    private final Map<Path, Registration> registrations = new HashMap<>();
    /** Whether folders under {@code refs/heads} are watched one by one ({@link #watchesRefSubfolders}). */
    private final boolean refSubfolders;
    /** At most this many folders under {@code refs/heads} per handle ({@link #MAX_REF_FOLDERS}). */
    private final int maxRefFolders;

    GitMetadataWatcher() {
        this(watchesRefSubfolders(com.editora.Os.isWindows()), MAX_REF_FOLDERS);
    }

    /** A watcher that watches the folders under {@code refs/heads} or not, and at most so many of them (tests). */
    GitMetadataWatcher(boolean refSubfolders, int maxRefFolders) {
        this.refSubfolders = refSubfolders;
        this.maxRefFolders = maxRefFolders;
    }

    /**
     * Tells {@code onChange} — on the watcher's thread, possibly several times for one git command — whenever the
     * repository at {@code root} changes its index, HEAD or branches. The folders are found and registered off the
     * calling thread. Closing the returned handle stops it; a handle closed before its registration is done
     * registers nothing.
     */
    Handle watch(Path root, Runnable onChange) {
        Handle handle = new Handle(onChange);
        Thread.ofVirtual().name("git-metadata-register").start(() -> register(root, handle));
        return handle;
    }

    /** One {@link #watch}: what it registered, and whether it has been closed or lost a folder. */
    final class Handle implements AutoCloseable {
        final Runnable listener;
        final List<Path> dirs = new ArrayList<>();
        /** The folders found at registration; losing one of them makes the handle stale. */
        final Set<Path> roots = new HashSet<>();
        /** How many folders under {@code refs/heads} this handle watches (at most the watcher's cap). */
        int refFolders;

        boolean closed;
        private volatile boolean stale;

        Handle(Runnable listener) {
            this.listener = listener;
        }

        /**
         * Whether one of the folders this handle watched stopped being watched (deleted, its git folder made again,
         * the watch cancelled by the system): the owner closes it and watches again to hear the repository.
         */
        boolean stale() {
            return stale;
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
                        registration.entries.removeIf(entry -> entry.handle() == this);
                        if (registration.entries.isEmpty()) {
                            registration.key.cancel();
                            registrations.remove(dir);
                        }
                    }
                }
                dirs.clear();
                refFolders = 0;
            }
        }
    }

    private void register(Path root, Handle handle) {
        GitDirs dirs = dirsOf(root);
        if (dirs == null) {
            return; // GIT_DIR elsewhere, or a layout this does not know: focus regain still refreshes
        }
        Map<Path, Kind> watched = dirs.watched(refSubfolders, maxRefFolders);
        // Registered under the lock: a close() or another handle sharing a folder must not see half of it. Each
        // registration is one system call and MAX_REF_FOLDERS bounds how many there are, so the drain thread waits
        // that long at most.
        synchronized (lock) {
            if (handle.closed) {
                return;
            }
            watched.forEach((dir, kind) -> {
                Path key = dir.toAbsolutePath().normalize();
                if (registerLocked(key, new Entry(handle, kind))) {
                    handle.roots.add(key);
                }
            });
        }
    }

    /** Registers {@code entry}'s interest in {@code dir}; false when the folder cannot be watched. Holds {@link #lock}. */
    private boolean registerLocked(Path dir, Entry entry) {
        Handle handle = entry.handle();
        if (handle.closed) {
            return false;
        }
        Registration registration = registrations.get(dir);
        boolean live = registration != null && registration.key.isValid();
        if (handle.dirs.contains(dir)) {
            if (live) {
                return true;
            }
            // Listed, but its watch is dead: the folder deleted and made again (a branch folder emptied, then a
            // branch put back in it) before the drain thread heard the old key go. Watched again, not skipped.
            forgetLocked(handle, dir, entry.kind());
            if (registration != null) {
                registration.entries.removeIf(e -> e.handle() == handle);
            }
        }
        if (registration == null || !live) {
            try {
                WatchKey watchKey = WatchServices.register(
                        dir,
                        serviceLocked(),
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                Registration fresh = new Registration(watchKey);
                if (registration != null) {
                    fresh.entries.addAll(registration.entries); // a dead key's listeners carry over
                }
                registration = fresh;
                registrations.put(dir, registration);
            } catch (IOException | RuntimeException unwatchable) {
                // Usually the system's watch limit (inotify max_user_watches): said once, so it can be found.
                Level level = LOGGED_UNWATCHABLE.compareAndSet(false, true) ? Level.INFO : Level.FINE;
                LOG.log(level, "Not watching " + dir + " for git changes: " + unwatchable, unwatchable);
                return false;
            }
        }
        registration.entries.add(entry);
        handle.dirs.add(dir);
        if (entry.kind() == Kind.REFS) {
            handle.refFolders++;
        }
        return true;
    }

    /** {@code handle} no longer watches {@code dir}, a folder of {@code kind}. Holds {@link #lock}. */
    private static void forgetLocked(Handle handle, Path dir, Kind kind) {
        if (handle.dirs.remove(dir) && kind == Kind.REFS) {
            handle.refFolders--;
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
            Set<Runnable> told = new LinkedHashSet<>();
            synchronized (lock) {
                Object watchable = key.watchable();
                Path dir = watchable instanceof Path path ? path : null;
                Registration registration = dir != null ? registrations.get(dir) : null;
                if (dir != null && registration != null && registration.key == key) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        heardLocked(dir, registration, event, told);
                    }
                    if (!key.reset()) {
                        droppedLocked(dir, registration);
                    }
                } else {
                    key.pollEvents();
                    key.reset();
                }
            }
            for (Runnable listener : told) {
                try {
                    listener.run();
                } catch (RuntimeException failed) {
                    LOG.log(Level.FINE, "git metadata listener failed", failed);
                }
            }
        }
    }

    /**
     * One event in {@code dir}: adds the listeners it matters to to {@code told}; a folder made under
     * {@code refs/heads} (a {@code feature/} branch) or a {@code logs} folder made in a git folder is watched too, for
     * the handles that watch its parent that way. Holds {@link #lock}.
     */
    private void heardLocked(Path dir, Registration registration, WatchEvent<?> event, Set<Runnable> told) {
        boolean overflow = event.kind() == StandardWatchEventKinds.OVERFLOW;
        String name = overflow ? null : String.valueOf(event.context());
        boolean created = event.kind() == StandardWatchEventKinds.ENTRY_CREATE;
        for (Entry entry : registration.entries) {
            if (overflow || relevant(name, entry.kind())) {
                told.add(entry.handle().listener);
            }
            if (!created || name == null) {
                continue;
            }
            Path child = dir.resolve(name);
            if (entry.kind() == Kind.REFS && Files.isDirectory(child)) {
                if (refSubfolders) {
                    for (Path folder : foldersUnder(child)) { // mkdir -p may have made more than one already
                        if (entry.handle().refFolders >= maxRefFolders) {
                            logRefCap(dir, entry.handle().refFolders + 1, maxRefFolders);
                            break;
                        }
                        registerLocked(folder, new Entry(entry.handle(), Kind.REFS));
                    }
                }
                told.add(entry.handle().listener); // a branch may have landed in it before its watch
            } else if (entry.kind() == Kind.GIT_DIR && "logs".equals(name) && Files.isDirectory(child)) {
                registerLocked(child, new Entry(entry.handle(), Kind.LOGS));
            }
        }
    }

    /**
     * {@code dir}'s watch is dead (the folder deleted, the git folder made again, the system cancelled it): it is
     * forgotten, so a later {@link #watch} registers it again, and a handle that lost one of its first folders is
     * {@link Handle#stale()}. Holds {@link #lock}.
     */
    private void droppedLocked(Path dir, Registration registration) {
        registrations.remove(dir);
        for (Entry entry : registration.entries) {
            Handle handle = entry.handle();
            forgetLocked(handle, dir, entry.kind());
            if (handle.roots.contains(dir)) {
                handle.stale = true;
            }
        }
    }

    /** Whether {@code dir} is being watched (tests). */
    boolean watchingForTest(Path dir) {
        synchronized (lock) {
            Registration registration = registrations.get(dir.toAbsolutePath().normalize());
            return registration != null && registration.key.isValid();
        }
    }

    /** How many folders under {@code refs/heads} {@code handle} watches (tests). */
    int refFoldersForTest(Handle handle) {
        synchronized (lock) {
            return handle.refFolders;
        }
    }

    /** How many folders are registered (tests). */
    int registeredForTest() {
        synchronized (lock) {
            return registrations.size();
        }
    }
}
