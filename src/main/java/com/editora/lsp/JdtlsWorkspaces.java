package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The jdtls {@code -data} workspaces of this process: which are claimed by a live session in any window (#668), the
 * repair of one a failed handshake left behind, the remote folder a jdtls on another host keeps its index in, and
 * the {@code lsp.clearJavaIndexCaches} sweep. Process-wide state, so it is static.
 */
final class JdtlsWorkspaces {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(LspManager.class.getName());

    private JdtlsWorkspaces() {}

    /**
     * A remote jdtls's {@code -data}: {@code jdtls-workspaces/<root hash>} in the host's cache folder, so its
     * index outlives the session as a local one's does. Not claimed across windows like a local workspace (a
     * remote project has one window); a folder that cannot be made leaves jdtls its own default.
     */
    static LspServerRegistry.ServerSpec withRemoteJdtlsWorkspace(LspServerRegistry.ServerSpec spec, Path root) {
        try {
            com.editora.process.TargetCache cache =
                    com.editora.process.ExecTargets.forPath(root).cache();
            if (cache == null) {
                return spec;
            }
            return LspServerRegistry.withDataDir(
                    spec, cache.makeDirectories("jdtls-workspaces/" + LspServerRegistry.workspaceDirName(root)));
        } catch (java.io.IOException | RuntimeException e) {
            return spec;
        }
    }

    /**
     * jdtls {@code -data} dirs currently claimed by a live session in <b>any</b> window. {@code LspManager} is
     * per-window, so two windows opening files under the same Java root would otherwise fork two jdtls against
     * <b>one</b> Eclipse workspace — whose {@code .metadata/.lock} admits a single process, wedging the second
     * at {@code initialize} until its 60 s timeout, and every retry the same way (#668). Static so the claim
     * spans windows; released when the owning session drops. In-process only — a second Editora <i>process</i>
     * can still collide (rare, pre-existing).
     */
    static final java.util.Set<String> claimedJdtlsWorkspaces = ConcurrentHashMap.newKeySet();

    /**
     * Session → the jdtls workspace dir name claimed for it, released when it is dropped or shut down. Kept per
     * process, not per window: a server two windows share (C12) holds its claim until the window that stops it
     * releases it, whichever window started it.
     */
    static final Map<LanguageServerSession, String> jdtlsWorkspaceBySession = new ConcurrentHashMap<>();

    /** Claims the first free workspace dir name for {@code baseName}: the canonical hash dir when free (so
     *  the persisted index is reused), else {@code <base>-2}, {@code -3}, … A suffixed dir costs a second
     *  jdtls index but unwedges the second window. Package-visible for tests. */
    static String claimJdtlsWorkspaceName(String baseName) {
        for (int i = 1; i <= 20; i++) {
            String candidate = workspaceCandidate(baseName, i);
            if (claimedJdtlsWorkspaces.add(candidate)) {
                return candidate;
            }
        }
        // 20 live same-root claims cannot happen in practice; hand out a unique last resort anyway.
        String unique = baseName + "-x" + Long.toHexString(System.nanoTime());
        claimedJdtlsWorkspaces.add(unique);
        return unique;
    }

    /**
     * Off-thread production claim. Sidecar markers identify caches whose previous session never completed
     * initialize and could not be safely removed (usually another process still held the Eclipse lock).
     *
     * <p>A marker is re-tested here rather than obeyed forever: once the lock is free the rebuildable cache
     * and its marker are removed and the name is reused. Nothing else ever deleted a marker, so one timed-out
     * handshake orphaned the canonical index for good and every later start burned another suffix.
     */
    static String claimUsableJdtlsWorkspaceName(Path base, String baseName) {
        for (int i = 1; i <= 20; i++) {
            String candidate = workspaceCandidate(baseName, i);
            if (!claimedJdtlsWorkspaces.add(candidate)) {
                continue;
            }
            Path marker = failedWorkspaceMarker(base, candidate);
            if (!Files.exists(marker)) {
                return candidate;
            }
            if (deleteUnlockedJdtlsWorkspace(base.resolve(candidate))) {
                try {
                    Files.deleteIfExists(marker);
                    return candidate;
                } catch (java.io.IOException e) {
                    LOG.log(java.util.logging.Level.FINE, "Could not clear " + marker, e);
                }
            }
            claimedJdtlsWorkspaces.remove(candidate); // still locked by someone else: leave it alone
        }
        String unique = baseName + "-x" + Long.toHexString(System.nanoTime());
        claimedJdtlsWorkspaces.add(unique);
        return unique;
    }

    static Path failedWorkspaceMarker(Path base, @Nullable String name) {
        return base.resolve("." + name + ".initialize-failed");
    }

    /** The {@code attempt}-th candidate dir name for {@code baseName} (1 = the canonical name). Pure. */
    static String workspaceCandidate(String baseName, int attempt) {
        return attempt <= 1 ? baseName : baseName + "-" + attempt;
    }

    /** Releases a claim taken by {@link #claimJdtlsWorkspaceName}. Package-visible for tests. */
    static void releaseJdtlsWorkspaceName(@Nullable String name) {
        if (name != null) {
            claimedJdtlsWorkspaces.remove(name);
        }
    }

    /** A jdtls data directory contains only rebuildable Eclipse indexes. After a failed handshake, remove it
     *  if its OS lock is free; otherwise mark it so this and future Editora processes choose a fresh suffix. */
    static void repairFailedJdtlsWorkspace(@Nullable Path jdtlsWorkspaceBase, @Nullable String workspaceName) {
        Path base = jdtlsWorkspaceBase;
        if (base == null) {
            return;
        }
        Path workspace = base.resolve(workspaceName);
        Path marker = failedWorkspaceMarker(base, workspaceName);
        if (deleteUnlockedJdtlsWorkspace(workspace)) {
            try {
                Files.deleteIfExists(marker);
            } catch (java.io.IOException ignored) {
                // A stale marker only costs a fresh suffix on the next launch.
            }
            return;
        }
        try {
            Files.createDirectories(base);
            Files.writeString(marker, "initialize failed " + java.time.Instant.now());
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.FINE, "Could not mark failed jdtls workspace " + workspace, e);
        }
    }

    static boolean deleteUnlockedJdtlsWorkspace(Path workspace) {
        if (!Files.exists(workspace)) {
            return true;
        }
        Path lockFile = workspace.resolve(".metadata/.lock");
        if (Files.exists(lockFile)) {
            try (var channel = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                if (lock == null) {
                    return false;
                }
            } catch (java.nio.channels.OverlappingFileLockException | java.io.IOException e) {
                return false;
            }
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return !Files.exists(workspace);
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /**
     * Deletes every jdtls workspace under {@code base} that no server is using ({@code lsp.clearJavaIndexCaches}).
     * Nothing else ever removes one: there is a workspace per Java folder ever opened, each ~40 MB before the
     * project's own classes (jdtls indexes the JDK into every one), so the folder only grows. jdtls rebuilds a
     * deleted one on its next start for that project.
     *
     * <p>A workspace is kept when a session in this process has claimed it, or another process (a second
     * Editora) holds its Eclipse lock. The claim is taken here for the duration of the delete, so no session can
     * start on a half-deleted cache. Failure markers go with their workspace. Blocking I/O: call it off the FX
     * thread.
     */
    public static LspManager.JdtlsCacheClearResult clearJdtlsWorkspaces(Path base) {
        if (base == null || !Files.isDirectory(base)) {
            return new LspManager.JdtlsCacheClearResult(0, 0, 0);
        }
        List<Path> entries;
        try (var list = Files.list(base)) {
            entries = list.sorted().toList();
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.FINE, "Could not list " + base, e);
            return new LspManager.JdtlsCacheClearResult(0, 0, 0);
        }
        int deleted = 0;
        int kept = 0;
        long freed = 0;
        for (Path dir : entries) {
            if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String name = dir.getFileName().toString();
            if (!claimedJdtlsWorkspaces.add(name)) {
                kept++; // a live session in some window of this process
                continue;
            }
            try {
                long size = treeSize(dir);
                if (deleteUnlockedJdtlsWorkspace(dir)) {
                    deleted++;
                    freed += size;
                } else {
                    kept++;
                }
            } finally {
                claimedJdtlsWorkspaces.remove(name);
            }
        }
        deleteOrphanedFailureMarkers(base);
        return new LspManager.JdtlsCacheClearResult(deleted, freed, kept);
    }

    /** Deletes the {@code .<name>.initialize-failed} markers whose workspace no longer exists. */
    private static void deleteOrphanedFailureMarkers(Path base) {
        String suffix = ".initialize-failed";
        try (var list = Files.list(base)) {
            for (Path marker : list.toList()) {
                String file = marker.getFileName().toString();
                if (file.startsWith(".") && file.endsWith(suffix) && file.length() > suffix.length() + 1) {
                    String name = file.substring(1, file.length() - suffix.length());
                    if (!Files.exists(base.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(marker);
                    }
                }
            }
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.FINE, "Could not clear failure markers in " + base, e);
        }
    }

    /** Total size of the regular files under {@code dir}, without following links; 0 when unreadable. */
    private static long treeSize(Path dir) {
        try (var paths = Files.walk(dir)) {
            return paths.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (java.io.IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }
}
