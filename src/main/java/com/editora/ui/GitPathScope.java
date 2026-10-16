package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.git.GitService;
import com.editora.vfs.Vfs;

/**
 * Which repository a path picked in the Project tree belongs to, relative to the window's active one.
 *
 * <p>The Git UI follows the active tab's repository, but a project folder can hold others: a nested
 * repository, a submodule, a second worktree. Their files are <em>inside</em> the active repository's folder
 * and yet not part of it, so computing their path against the active root produced a pathspec git rejects —
 * and the tree's status colouring, which is the active repository's, says nothing about them.
 *
 * <p>The answer comes from the nearest {@code .git} entry above the path (a folder for a repository, a file
 * for a submodule or worktree): a few {@code stat} calls, cheap enough for a menu that is about to open.
 *
 * <p>On a remote host each of those would be an SFTP round trip on the FX thread. So for a remote project
 * the host is asked once, off the FX thread, where its {@code .git} entries are ({@link #learnRemote}: one
 * {@code find}), and a path's nearest repository is looked up in that answer. Until it has come, a remote
 * path is answered as it always was: in the active repository when under its root, else in none.
 */
public enum GitPathScope {
    /** Not inside any repository. */
    NONE,
    /** In the window's active repository: the last status and the tree colouring describe it. */
    ACTIVE,
    /** In some other repository; its status is not known until git is asked. */
    OTHER;

    /** The nearest ancestor of {@code path} (or {@code path} itself) holding a {@code .git} entry, or null. */
    static Path nearestRepository(Path path) {
        if (path == null) {
            return null;
        }
        if (!Vfs.isLocal(path)) {
            return nearestRemoteRepository(path);
        }
        try {
            for (Path dir = path.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
                if (Files.exists(dir.resolve(".git"))) {
                    return dir;
                }
            }
        } catch (RuntimeException unreadable) {
            // an unusable path is simply in no repository
        }
        return null;
    }

    /** What a remote project's host said about it: the folders holding a {@code .git}, relative to its root. */
    private record RemoteRepositories(String root, java.util.Set<String> folders, long scannedNanos) {}

    /** By the root's storable name ({@code sftp://user@host:port/path}), which outlives a reconnect. */
    private static final java.util.Map<String, RemoteRepositories> REMOTE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Set<String> SCANNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A remote project is asked again at most this often when nothing says it changed. */
    static final java.time.Duration RESCAN_AFTER = java.time.Duration.ofMinutes(1);

    /** How deep below the root a repository is looked for, and what is not looked in. */
    static final int SCAN_DEPTH = 8;

    static final java.util.List<String> SCAN_SKIPS =
            java.util.List.of("node_modules", ".venv", "venv", "__pycache__", ".cache", ".gradle");

    /**
     * The command that lists a remote root's {@code .git} entries, run in the root: {@code ./a/b/.git} for a
     * repository at {@code a/b}, {@code ./.git} for the root itself. A {@code .git} is not descended into.
     */
    static java.util.List<String> scanArgv() {
        java.util.List<String> argv =
                new java.util.ArrayList<>(java.util.List.of("find", ".", "-maxdepth", String.valueOf(SCAN_DEPTH), "("));
        for (int i = 0; i < SCAN_SKIPS.size(); i++) {
            if (i > 0) {
                argv.add("-o");
            }
            argv.addAll(java.util.List.of("-name", SCAN_SKIPS.get(i)));
        }
        argv.addAll(java.util.List.of(")", "-prune", "-o", "-name", ".git", "-print", "-prune"));
        return argv;
    }

    /** The repository folders, relative to the root ({@code ""} for the root), in {@link #scanArgv}'s output. Pure. */
    static java.util.Set<String> parseScan(String output) {
        java.util.Set<String> folders = new java.util.HashSet<>();
        for (String line : output.split("\n")) {
            String entry = line.strip();
            if (!entry.equals("./.git") && !entry.endsWith("/.git")) {
                continue;
            }
            String folder = entry.substring(0, entry.length() - ".git".length());
            folder = folder.equals("./") ? "" : folder.substring(2, folder.length() - 1);
            folders.add(folder);
        }
        return java.util.Set.copyOf(folders);
    }

    /**
     * Asks the host of the remote project {@code root}, off the FX thread, where its repositories are: at
     * once with {@code force}, else when it has not been asked in the last {@link #RESCAN_AFTER}. A local root,
     * or a host that cannot run commands, is left alone.
     */
    static void learnRemote(Path root, boolean force) {
        if (root == null || Vfs.isLocal(root)) {
            return;
        }
        String key = Vfs.toStorableString(root);
        RemoteRepositories known = REMOTE.get(key);
        if (!force && known != null && System.nanoTime() - known.scannedNanos() < RESCAN_AFTER.toNanos()) {
            return;
        }
        if (!SCANNING.add(key)) {
            return;
        }
        Thread.ofVirtual().name("remote-repositories").start(() -> {
            try {
                com.editora.process.ProcessRunner.Result r =
                        com.editora.process.ProcessRunner.run(root, java.time.Duration.ofSeconds(30), scanArgv());
                if (r.exit() != -1) { // -1: not run (no commands on that host); a permission error still lists
                    REMOTE.put(key, new RemoteRepositories(key, parseScan(r.out()), System.nanoTime()));
                }
            } finally {
                SCANNING.remove(key);
            }
        });
    }

    /** Whether the host of {@code root} has said where its repositories are (tests). */
    static boolean knowsRemote(Path root) {
        return root != null && REMOTE.containsKey(Vfs.toStorableString(root));
    }

    /** The nearest folder at or above remote {@code path} holding a {@code .git}, from what its host said. */
    private static Path nearestRemoteRepository(Path path) {
        String name = Vfs.toStorableString(path.toAbsolutePath().normalize());
        for (RemoteRepositories known : REMOTE.values()) {
            String root = known.root();
            if (!name.equals(root) && !name.startsWith(root.endsWith("/") ? root : root + "/")) {
                continue;
            }
            String relative = name.length() <= root.length()
                    ? ""
                    : name.substring(root.length()).replaceFirst("^/", "");
            Path dir = path.toAbsolutePath().normalize();
            while (true) {
                if (known.folders().contains(relative)) {
                    return dir;
                }
                if (relative.isEmpty() || dir.getParent() == null) {
                    return null;
                }
                int slash = relative.lastIndexOf('/');
                relative = slash < 0 ? "" : relative.substring(0, slash);
                dir = dir.getParent();
            }
        }
        return null;
    }

    static GitPathScope of(Path path, Path activeRoot) {
        Path nearest = nearestRepository(path);
        if (nearest == null) {
            // No marker found (GIT_DIR, an unusual layout): trust the active repository if the path is in it.
            return activeRoot != null && GitService.repoRelative(activeRoot, path) != null ? ACTIVE : NONE;
        }
        return activeRoot != null
                        && GitService.repoRelative(activeRoot, nearest) != null
                        && GitService.repoRelative(nearest, activeRoot) != null
                ? ACTIVE
                : OTHER;
    }
}
