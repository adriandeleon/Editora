package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.git.DiffParser;
import com.editora.git.GitOperation;
import com.editora.git.GitService;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions behind the third Git review's Commit window and watcher fixes. */
class GitReview3DecisionsTest {

    private static final long SECOND = 1_000_000_000L;

    // --- U3: a hunk of a CRLF file is found by its lines -------------------------------------------------

    @Test
    void aCrlfFilesHunkIsFoundByTheBuffersLines() {
        DiffParser.Hunk crlf = new DiffParser.Hunk(1, 1, 1, 2, List.of("old\r"), List.of("x\r", "y\r"), false, false);
        assertSame(crlf, GitHunkCoordinator.hunkAdding(List.of(crlf), List.of("x", "y")), "the \\r is the file's");
        assertNull(GitHunkCoordinator.hunkAdding(List.of(crlf), List.of("x", "z")));
        assertFalse(GitHunkCoordinator.sameLines(List.of("x\r"), List.of("x", "y")));
        assertTrue(GitHunkCoordinator.sameLines(List.of("x"), List.of("x")));
    }

    // --- U2, U10: what the watcher hears -----------------------------------------------------------------

    @Test
    void eachFolderHearsOnlyItsOwnNames() {
        assertTrue(GitMetadataWatcher.relevant("index", GitMetadataWatcher.Kind.GIT_DIR));
        assertTrue(GitMetadataWatcher.relevant("packed-refs", GitMetadataWatcher.Kind.GIT_DIR));
        assertFalse(GitMetadataWatcher.relevant("index", GitMetadataWatcher.Kind.COMMON_DIR), "another tree's index");
        assertFalse(GitMetadataWatcher.relevant("HEAD", GitMetadataWatcher.Kind.COMMON_DIR), "another tree's HEAD");
        assertTrue(GitMetadataWatcher.relevant("packed-refs", GitMetadataWatcher.Kind.COMMON_DIR));
        assertTrue(GitMetadataWatcher.relevant("HEAD", GitMetadataWatcher.Kind.LOGS), "HEAD's reflog");
        assertFalse(GitMetadataWatcher.relevant("refs", GitMetadataWatcher.Kind.LOGS));
        assertTrue(GitMetadataWatcher.relevant("x", GitMetadataWatcher.Kind.REFS));
        assertFalse(GitMetadataWatcher.relevant("x.lock", GitMetadataWatcher.Kind.REFS));
        assertTrue(GitMetadataWatcher.relevant("tables.list", GitMetadataWatcher.Kind.REFTABLE));
        assertFalse(GitMetadataWatcher.relevant(null, GitMetadataWatcher.Kind.REFS));
    }

    @Test
    void aLinkedWorktreeWatchesItsOwnGitFolderAndTheCommonRefs(@TempDir Path dir) throws Exception {
        Path common = Files.createDirectories(dir.resolve("main/.git"));
        Path own = Files.createDirectories(common.resolve("worktrees/wt"));
        Files.writeString(own.resolve("commondir"), "../..\n");
        Files.createDirectories(own.resolve("logs"));
        Files.createDirectories(common.resolve("refs/heads/feature/deep"));
        Files.createDirectories(common.resolve("reftable"));
        Path wt = Files.createDirectories(dir.resolve("wt"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + own + "\n");

        Map<Path, GitMetadataWatcher.Kind> watched =
                GitMetadataWatcher.dirsOf(wt).watched();

        assertEquals(GitMetadataWatcher.Kind.GIT_DIR, watched.get(own.normalize()));
        assertEquals(GitMetadataWatcher.Kind.COMMON_DIR, watched.get(common.normalize()), "not its index or HEAD");
        assertEquals(
                GitMetadataWatcher.Kind.LOGS, watched.get(own.resolve("logs").normalize()));
        assertEquals(
                GitMetadataWatcher.Kind.REFS,
                watched.get(common.resolve("refs/heads").normalize()));
        assertEquals(
                GitMetadataWatcher.Kind.REFS,
                watched.get(common.resolve("refs/heads/feature").normalize()));
        assertEquals(
                GitMetadataWatcher.Kind.REFS,
                watched.get(common.resolve("refs/heads/feature/deep").normalize()));
        assertEquals(
                GitMetadataWatcher.Kind.REFTABLE,
                watched.get(common.resolve("reftable").normalize()));

        Map<Path, GitMetadataWatcher.Kind> plain =
                GitMetadataWatcher.dirsOf(dir.resolve("main")).watched();
        assertEquals(GitMetadataWatcher.Kind.GIT_DIR, plain.get(dir.resolve("main/.git")), "its own git folder");
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = "inotify: the JDK polls on macOS, every ten seconds")
    void aBranchUnderAFolderMovedFromOutsideIsHeard(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("a.txt", "a\n");
        repo.commitAll("base");
        repo.git("branch", "feature/x");
        repo.write("a.txt", "b\n");
        repo.commitAll("second");
        Path featureFolder = repo.root.resolve(".git/refs/heads/feature");

        GitMetadataWatcher watcher = new GitMetadataWatcher();
        AtomicInteger heard = new AtomicInteger();
        try (GitMetadataWatcher.Handle handle = watcher.watch(repo.root, heard::incrementAndGet)) {
            awaitTrue("the branch folder watched", () -> watcher.watchingForTest(featureFolder));
            heard.set(0);

            repo.git("branch", "-f", "feature/x", "HEAD"); // as a terminal's commit --amend on that branch would

            awaitTrue("the move heard", () -> heard.get() > 0);
            assertFalse(handle.stale());
        }
        assertEquals(0, watcher.registeredForTest(), "closing unregisters every folder");
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = "inotify drops a deleted folder's watch; Windows locks it")
    void aGitFolderMadeAgainMakesTheWatchStale(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("a.txt", "a\n");
        repo.commitAll("base");
        Path gitDir = repo.root.resolve(".git");

        GitMetadataWatcher watcher = new GitMetadataWatcher();
        try (GitMetadataWatcher.Handle handle = watcher.watch(repo.root, () -> {})) {
            awaitTrue("the git folder watched", () -> watcher.watchingForTest(gitDir));

            deleteTree(gitDir);
            repo.git("init", "-q");

            awaitTrue("the dead watch noticed", handle::stale);
            assertFalse(watcher.watchingForTest(gitDir), "dropped, so the next watch registers it again");
        }
        try (GitMetadataWatcher.Handle again = watcher.watch(repo.root, () -> {})) {
            awaitTrue("watched again", () -> watcher.watchingForTest(gitDir));
            assertFalse(again.stale());
        }
    }

    // --- U4: an outside change in the echo of this window's command ----------------------------------------

    @Test
    void anOutsideChangeAfterOwnCommandIsDeferredNotDropped() {
        long mutation = 100 * SECOND;
        assertEquals(0, GitCoordinator.externalRefreshDelay(mutation + 2 * SECOND, mutation, mutation + SECOND));
        assertEquals(
                -1,
                GitCoordinator.externalRefreshDelay(mutation + SECOND / 2, mutation, mutation - SECOND / 10),
                "heard before the command finished: its own refresh saw it");
        assertEquals(
                SECOND / 2,
                GitCoordinator.externalRefreshDelay(mutation + SECOND / 2, mutation, mutation + SECOND / 5),
                "heard after it: refreshed once the echo window is over");
    }

    // --- B1: --autostash given back at the end of a merge or rebase -----------------------------------------

    @Test
    void aConflictedAutostashIsReadFromGitsOwnLinesOnly() {
        ProcessRunner.Result kept = new ProcessRunner.Result(
                0,
                "[main 1234567] Merge branch 'feature'\n",
                "Applying autostash resulted in conflicts.\n" + "Your changes are safe in the stash.\n");
        assertTrue(GitCoordinator.autostashKept(kept, GitOperation.Kind.MERGE));
        assertTrue(GitCoordinator.autostashKept(kept, GitOperation.Kind.REBASE));
        assertFalse(GitCoordinator.autostashKept(kept, GitOperation.Kind.NONE), "an ordinary commit");
        assertFalse(GitCoordinator.autostashKept(kept, GitOperation.Kind.CHERRY_PICK));
        ProcessRunner.Result subject = new ProcessRunner.Result(0, "[main 1234567] keep it safe in the stash\n", "");
        assertFalse(GitCoordinator.autostashKept(subject, GitOperation.Kind.MERGE), "a subject is not git's word");
        assertFalse(GitCoordinator.autostashKept(
                new ProcessRunner.Result(0, "", "Applied autostash.\n"), GitOperation.Kind.MERGE));
        assertFalse(GitCoordinator.autostashKept(null, GitOperation.Kind.MERGE));
    }

    @Test
    void anAutostashOfTheOperationIsFoundInTheGitFolder(@TempDir Path dir) throws Exception {
        Path gitDir = Files.createDirectories(dir.resolve(".git"));
        assertFalse(GitCoordinator.holdsAutostash(dir, GitOperation.Kind.MERGE));
        Files.writeString(gitDir.resolve("MERGE_AUTOSTASH"), "abc\n");
        assertTrue(GitCoordinator.holdsAutostash(dir, GitOperation.Kind.MERGE));
        assertFalse(GitCoordinator.holdsAutostash(dir, GitOperation.Kind.REBASE));
        Files.writeString(
                Files.createDirectories(gitDir.resolve("rebase-merge")).resolve("autostash"), "abc\n");
        assertTrue(GitCoordinator.holdsAutostash(dir, GitOperation.Kind.REBASE));
        assertFalse(GitCoordinator.holdsAutostash(dir.resolve("nowhere"), GitOperation.Kind.MERGE));
    }

    // --- U7: Undo Last Commit moves the branch it was asked about ------------------------------------------

    @Test
    void undoMovesTheBranchHeadIsOnOrHeadWhenDetached() {
        assertEquals(
                "refs/heads/feature/x",
                GitCommitCoordinator.undoRef(new ProcessRunner.Result(0, "refs/heads/feature/x\n", "")));
        assertEquals("HEAD", GitCommitCoordinator.undoRef(new ProcessRunner.Result(1, "", "")), "detached");
        assertEquals("HEAD", GitCommitCoordinator.undoRef(null));
        GitService.HeadCommit head = new GitService.HeadCommit("c0ffee", "c0f", "s", List.of("p4r3nt"), "s", "", false);
        assertArrayEquals(
                new String[] {"update-ref", "-m", "reset: moving to c0f~1", "refs/heads/main", "p4r3nt", "c0ffee"},
                GitCommitCoordinator.undoArgs("refs/heads/main", head));
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition) throws Exception {
        com.editora.testkit.Await.until(condition, what);
    }

    private static void deleteTree(Path top) throws Exception {
        try (var walk = Files.walk(top)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
