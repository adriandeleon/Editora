package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.git.DiffParser;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind the second Git review's Commit window fixes. */
class GitReview2DecisionsTest {

    private static GitStatus status(FileEntry... files) {
        return new GitStatus(true, "main", "", 0, 0, List.of(files));
    }

    // --- U1, U11: files in the index but in no commit -----------------------------------------------------

    @Test
    void anIntentToAddFileIsAddedNotRestoredFromTheIndex() {
        GitDiscardPlan plan = GitDiscardPlan.of(status(new FileEntry("ita.txt", '.', 'A', null)), "ita.txt");
        assertEquals(GitDiscardPlan.Kind.ADDED, plan.kind(), "checkout -- would empty it");
        assertEquals(List.of("ita.txt"), plan.added());
        assertTrue(GitDiscardPlan.intentToAdd(new FileEntry("x", '.', 'A', null)));
        assertFalse(GitDiscardPlan.intentToAdd(new FileEntry("x", 'A', '.', null)));
    }

    @Test
    void aStagedNewFileLeavesTheIndexWithoutHead() {
        GitDiscardPlan plan = GitDiscardPlan.of(status(new FileEntry("new.txt", 'A', 'M', null)), "new.txt");
        assertEquals(GitDiscardPlan.Kind.ADDED, plan.kind(), "restore --source=HEAD fails with no commit");
        assertEquals(List.of("new.txt"), plan.added());

        GitDiscardPlan folder = GitDiscardPlan.of(
                status(new FileEntry("src/a.txt", 'A', '.', null), new FileEntry("src/b.txt", '.', 'A', null)), "src");
        assertEquals(GitDiscardPlan.Kind.ADDED, folder.kind());
        assertEquals(List.of("src/b.txt", "src/a.txt"), folder.added());
    }

    @Test
    void anIntentToAddFileInAFolderLeavesTheIndexBeforeTheFolderIsRestored() {
        GitStatus status =
                status(new FileEntry("src/m.txt", '.', 'M', null), new FileEntry("src/i.txt", '.', 'A', null));
        GitDiscardPlan plan = GitDiscardPlan.of(status, "src");
        assertEquals(GitDiscardPlan.Kind.WORKTREE, plan.kind());
        assertEquals(List.of("src"), plan.specs());
        assertEquals(List.of("src/i.txt"), plan.added());

        GitStatus staged = status(
                new FileEntry("src/m.txt", 'M', '.', null),
                new FileEntry("src/new.txt", 'A', '.', null),
                new FileEntry("src/i.txt", '.', 'A', null));
        GitDiscardPlan head = GitDiscardPlan.of(staged, "src");
        assertEquals(GitDiscardPlan.Kind.HEAD, head.kind(), "with a commit to go back to, HEAD restores the add too");
        assertEquals(List.of("src/i.txt"), head.added());
    }

    @Test
    void discardAllSortsAddedFilesApart() {
        GitCoordinator.DiscardAll all = GitCoordinator.DiscardAll.of(status(
                new FileEntry("ita.txt", '.', 'A', null),
                new FileEntry("new.txt", 'A', '.', null),
                new FileEntry("m.txt", '.', 'M', null)));
        assertEquals(List.of("ita.txt", "new.txt"), all.added());
        assertEquals(List.of("m.txt"), all.worktree());
        assertEquals(List.of(), all.head());
        assertEquals(3, all.tracked());
    }

    // --- U8, U12: untracked rows ------------------------------------------------------------------------

    @Test
    void theHistoryNoteIsSaidOnlyPastTheLimitWithHistoryOn() {
        int max = GitUntrackedDelete.MAX_HISTORY_CAPTURES;
        assertEquals("", GitUntrackedDelete.historyNote(max, true));
        assertEquals("", GitUntrackedDelete.historyNote(max + 1, false));
        assertEquals("\n\n" + tr("dialog.discard.historyLimit", max), GitUntrackedDelete.historyNote(max + 1, true));
    }

    @Test
    void aNestedRepositoryRowIsFound(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("nested/.git"));
        Files.createDirectories(root.resolve("plain"));
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/.git"), "gitdir: ../.git/modules/sub\n");
        Files.writeString(root.resolve("file.txt"), "x");

        GitUntrackedDelete.Look look = GitUntrackedDelete.look(root, List.of("nested/", "plain/", "sub", "file.txt"));
        assertEquals(3, look.folders());
        assertEquals(List.of("nested/", "sub"), look.nested());
    }

    // --- U6, U9, U13 ----------------------------------------------------------------------------------

    @Test
    void aCommitSavesTheStagedPathsOnly() {
        GitStatus status = status(
                new FileEntry("staged.txt", 'M', '.', null),
                new FileEntry("both.txt", 'M', 'M', null),
                new FileEntry("unstaged.txt", '.', 'M', null),
                new FileEntry("new.txt", '?', '?', null));
        assertEquals(List.of("staged.txt", "both.txt"), GitCommitCoordinator.stagedPaths(status));
        assertEquals(List.of(), GitCommitCoordinator.stagedPaths(null));
    }

    @Test
    void aMovedHeadIsToldFromOtherFailures() {
        String expected = "0123456789abcdef0123456789abcdef01234567";
        ProcessRunner.Result moved = new ProcessRunner.Result(
                128,
                "",
                "fatal: update_ref failed for ref 'HEAD': cannot lock ref 'HEAD': is at fedcba but expected "
                        + expected);
        assertTrue(GitCommitCoordinator.movedFrom(moved, expected));
        assertFalse(GitCommitCoordinator.movedFrom(new ProcessRunner.Result(128, "", "fatal: other"), expected));
        assertFalse(GitCommitCoordinator.movedFrom(new ProcessRunner.Result(0, "", ""), expected));
    }

    @Test
    void aHunkIsFoundAgainByTheLinesItAdds() {
        DiffParser.Hunk a = new DiffParser.Hunk(1, 1, 1, 1, List.of("old"), List.of("new"), false, false);
        DiffParser.Hunk b = new DiffParser.Hunk(9, 0, 10, 2, List.of(), List.of("x", "y"), false, false);
        assertSame(b, GitHunkCoordinator.hunkAdding(List.of(a, b), List.of("x", "y")));
        assertNull(GitHunkCoordinator.hunkAdding(List.of(a, b), List.of("x")), "rewritten by the save: not guessed");
        assertNull(GitHunkCoordinator.hunkAdding(List.of(a, a), List.of("new")), "two candidates: not guessed");
        assertNull(GitHunkCoordinator.hunkAdding(List.of(a), List.of()), "a deletion has no lines to find it by");
    }

    // --- U2: the git folder of a work tree ------------------------------------------------------------

    @Test
    void theGitFoldersOfALinkedWorktreeAreFound(@TempDir Path dir) throws Exception {
        Path main = Files.createDirectories(dir.resolve("main/.git/worktrees/wt"));
        Files.writeString(main.resolve("commondir"), "../..\n");
        Path wt = Files.createDirectories(dir.resolve("wt"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + main + "\n");

        GitMetadataWatcher.GitDirs dirs = GitMetadataWatcher.dirsOf(wt);
        assertEquals(main.normalize(), dirs.gitDir());
        assertEquals(dir.resolve("main/.git").normalize(), dirs.commonDir());

        GitMetadataWatcher.GitDirs plain = GitMetadataWatcher.dirsOf(dir.resolve("main"));
        assertEquals(dir.resolve("main/.git"), plain.gitDir());
        assertNull(GitMetadataWatcher.dirsOf(dir), "no .git: nothing to watch");
        assertEquals("../x", GitMetadataWatcher.gitDirLine("gitdir: ../x\n"));
        assertNull(GitMetadataWatcher.gitDirLine("nonsense"));
    }

    @Test
    void onlyChangesThatMoveTheStatusAreHeard() {
        assertTrue(GitMetadataWatcher.relevant("index", GitMetadataWatcher.Kind.GIT_DIR));
        assertTrue(GitMetadataWatcher.relevant("HEAD", GitMetadataWatcher.Kind.GIT_DIR));
        assertTrue(GitMetadataWatcher.relevant("main", GitMetadataWatcher.Kind.REFS), "a branch moved");
        assertFalse(GitMetadataWatcher.relevant("index.lock", GitMetadataWatcher.Kind.GIT_DIR));
        assertFalse(GitMetadataWatcher.relevant("main.lock", GitMetadataWatcher.Kind.REFS));
        assertFalse(
                GitMetadataWatcher.relevant("FETCH_HEAD", GitMetadataWatcher.Kind.GIT_DIR), "a fetch refreshes itself");
        assertFalse(GitMetadataWatcher.relevant("objects", GitMetadataWatcher.Kind.GIT_DIR));
    }
}
