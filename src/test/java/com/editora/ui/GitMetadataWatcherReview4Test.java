package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the fourth Git review found in the metadata watcher: per-folder watches under {@code refs/heads} that hold
 * the folders open on Windows, a folder made again that was not watched again, and no bound on how many folders a
 * repository with many branch folders takes.
 */
class GitMetadataWatcherReview4Test {

    // --- R4-U2: no watches under refs/heads on Windows ---------------------------------------------------

    @Test
    void windowsWatchesRefsHeadsButNotTheFoldersUnderIt(@TempDir Path dir) throws Exception {
        assertFalse(GitMetadataWatcher.watchesRefSubfolders(true), "Windows: a watch holds the folder open");
        assertTrue(GitMetadataWatcher.watchesRefSubfolders(false));

        Path git = Files.createDirectories(dir.resolve(".git"));
        Files.createDirectories(git.resolve("logs"));
        Path heads = Files.createDirectories(git.resolve("refs/heads"));
        Files.createDirectories(heads.resolve("feature/deep"));

        Map<Path, GitMetadataWatcher.Kind> watched =
                GitMetadataWatcher.dirsOf(dir).watched(false, GitMetadataWatcher.MAX_REF_FOLDERS);

        assertEquals(GitMetadataWatcher.Kind.REFS, watched.get(heads), "refs/heads itself is never deleted");
        assertNull(watched.get(heads.resolve("feature")), "git deletes and makes it again");
        assertNull(watched.get(heads.resolve("feature/deep")));
        assertEquals(GitMetadataWatcher.Kind.LOGS, watched.get(git.resolve("logs")), "HEAD moves are heard there");
        assertEquals(GitMetadataWatcher.Kind.GIT_DIR, watched.get(git), "and packed-refs in the git folder");
    }

    // --- R4-U4: a bound on the branch folders watched ---------------------------------------------------

    @Test
    void atMostSoManyBranchFoldersAreWatched(@TempDir Path dir) throws Exception {
        Path git = Files.createDirectories(dir.resolve(".git"));
        Path heads = Files.createDirectories(git.resolve("refs/heads"));
        for (int i = 0; i < 5; i++) {
            Files.createDirectories(heads.resolve("team" + i));
        }

        Map<Path, GitMetadataWatcher.Kind> watched =
                GitMetadataWatcher.dirsOf(dir).watched(true, 3);

        long refs = watched.values().stream()
                .filter(kind -> kind == GitMetadataWatcher.Kind.REFS)
                .count();
        assertEquals(3, refs);
        assertEquals(GitMetadataWatcher.Kind.REFS, watched.get(heads), "refs/heads is the first kept");
        assertEquals(GitMetadataWatcher.Kind.GIT_DIR, watched.get(git), "packed-refs is still heard");
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = "inotify: the JDK polls on macOS, every ten seconds")
    void aBranchFolderMadeLaterCountsTowardsTheBound(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("a.txt", "a\n");
        repo.commitAll("base");
        Path heads = repo.root.resolve(".git/refs/heads");

        GitMetadataWatcher watcher = new GitMetadataWatcher(true, 2);
        java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
        try (GitMetadataWatcher.Handle handle = watcher.watch(repo.root, heard::incrementAndGet)) {
            awaitTrue("refs/heads watched", () -> watcher.watchingForTest(heads));
            repo.git("branch", "one/x");
            awaitTrue("the first branch folder watched", () -> watcher.watchingForTest(heads.resolve("one")));
            heard.set(0);
            repo.git("branch", "two/x");

            awaitTrue("the second folder heard", () -> heard.get() > 0); // told after its folder was looked at
            assertEquals(2, watcher.refFoldersForTest(handle));
            assertFalse(watcher.watchingForTest(heads.resolve("two")), "beyond the bound");
        }
    }

    // --- R4-U3: a branch folder deleted and made again is watched again ------------------------------------

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = "inotify drops a deleted folder's watch; Windows locks it")
    void aBranchFolderMadeAgainIsWatchedAgain(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("a.txt", "a\n");
        repo.commitAll("base");
        Path feature = repo.root.resolve(".git/refs/heads/feature");

        GitMetadataWatcher watcher = new GitMetadataWatcher(true, GitMetadataWatcher.MAX_REF_FOLDERS);
        java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
        try (GitMetadataWatcher.Handle handle = watcher.watch(repo.root, heard::incrementAndGet)) {
            awaitTrue("refs/heads watched", () -> watcher.watchingForTest(repo.root.resolve(".git/refs/heads")));
            repo.git("branch", "feature/x"); // made after the watch began: not one of its first folders
            awaitTrue("the branch folder watched", () -> watcher.watchingForTest(feature));

            for (int round = 0; round < 5; round++) {
                repo.git("branch", "-D", "-q", "feature/x"); // git deletes the emptied folder
                repo.git("branch", "feature/x"); // and makes it again
            }
            awaitTrue("the folder watched again", () -> watcher.watchingForTest(feature));
            heard.set(0);
            repo.git("branch", "feature/y"); // heard through the watch of the folder made again

            awaitTrue("a branch in the folder made again heard", () -> heard.get() > 0);
            assertEquals(2, watcher.refFoldersForTest(handle), "refs/heads and feature, each counted once");
            assertFalse(handle.stale(), "a folder made after the watch began is not one it must have");
        }
    }

    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition) throws Exception {
        com.editora.testkit.Await.until(condition, what);
    }
}
