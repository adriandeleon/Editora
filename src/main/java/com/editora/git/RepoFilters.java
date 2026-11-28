package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.config.TrustStore;
import com.editora.process.ProcessRunner;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * The filter drivers a repository's own config defines, emptied in Git's background commands while its folder is
 * not trusted (see {@link GitSafety}). A {@code .git/config} that came with an extracted archive or a USB stick can
 * name any program as {@code filter.<name>.process}, and {@code .gitattributes} selects it: the gutter diff of a
 * changed file ran it (HN dry run, 2026-11-27). The user's global filters (Git LFS) are not touched, nor is a
 * trusted folder's.
 *
 * <p>The names are read with {@code git config} (which runs nothing) once per repository and kept until its
 * {@code .git/config} changes; a repository whose {@code .git} is a file (a linked worktree, a submodule) is read
 * again after {@link #UNSTAMPED_FOR}.
 */
final class RepoFilters {

    private RepoFilters() {}

    /** How long the names of a repository whose config file is not found are kept. */
    static final Duration UNSTAMPED_FOR = Duration.ofSeconds(5);

    private record Entry(String stamp, long readAt, Set<String> names) {}

    private static final Map<Path, Entry> CACHE = new ConcurrentHashMap<>();

    /** The {@code -c} pairs for a background command run in {@code dir}: none in a trusted or remote folder. */
    static List<String> overrides(@Nullable Path dir) {
        if (dir == null || !Vfs.isLocal(dir) || TrustStore.trustedNow(dir)) {
            return List.of();
        }
        Set<String> names = names(dir.toAbsolutePath().normalize());
        return names.isEmpty() ? List.of() : GitSafety.filterOverrides(names);
    }

    private static Set<String> names(Path dir) {
        String stamp = stamp(dir);
        long now = System.nanoTime();
        Entry e = CACHE.get(dir);
        if (e != null && e.stamp().equals(stamp) && (!stamp.isEmpty() || now - e.readAt() < UNSTAMPED_FOR.toNanos())) {
            return e.names();
        }
        Set<String> names = read(dir);
        CACHE.put(dir, new Entry(stamp, now, names));
        return names;
    }

    /** The repository's filter names, as {@code git config} reports them; none when it cannot be asked. */
    private static Set<String> read(Path dir) {
        List<String> argv = GitSafety.backgroundArgv(
                GitService.commandFor(dir), "config", "--show-scope", "--name-only", "--get-regexp", "^filter\\.");
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(10), argv, GitSafety.BACKGROUND_ENV);
        // Exit 1 is "no such key": no filter at all. Git before 2.26 has no --show-scope: then every filter it
        // finds is taken for the repository's (emptied in an untrusted folder, which costs that folder LFS).
        if (r.ok()) {
            return Set.copyOf(GitSafety.localFilterNames(r.out()));
        }
        if (r.out().isEmpty() && r.err().contains("show-scope")) {
            ProcessRunner.Result old = ProcessRunner.run(
                    dir,
                    Duration.ofSeconds(10),
                    GitSafety.backgroundArgv(
                            GitService.commandFor(dir), "config", "--name-only", "--get-regexp", "^filter\\."),
                    GitSafety.BACKGROUND_ENV);
            return old.ok()
                    ? Set.copyOf(GitSafety.localFilterNames(old.out()
                            .lines()
                            .map(l -> "local\t" + l)
                            .collect(java.util.stream.Collectors.joining("\n"))))
                    : Set.of();
        }
        return Set.of();
    }

    /** The modified time and size of {@code dir}'s repository config, or "" when its {@code .git} is not a folder. */
    private static String stamp(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path git = d.resolve(".git");
            if (Files.isDirectory(git)) {
                try {
                    BasicFileAttributes a = Files.readAttributes(git.resolve("config"), BasicFileAttributes.class);
                    Path worktree = git.resolve("config.worktree");
                    String wt = Files.exists(worktree)
                            ? "/" + Files.getLastModifiedTime(worktree).toMillis()
                            : "";
                    return a.lastModifiedTime().toMillis() + ":" + a.size() + wt;
                } catch (java.io.IOException e) {
                    return "";
                }
            }
            if (Files.exists(git)) {
                return ""; // a worktree or submodule: its config is elsewhere
            }
        }
        return "";
    }

    /** Forgets what was read (a test changes a repository's config within one clock tick). */
    static void clearForTest() {
        CACHE.clear();
    }
}
