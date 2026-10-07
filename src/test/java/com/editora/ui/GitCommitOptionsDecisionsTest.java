package com.editora.ui;

import java.util.List;

import com.editora.git.GitOperation;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import com.editora.process.CommandLog;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind the Commit window's options, the bulk actions and the quieter Git console. */
class GitCommitOptionsDecisionsTest {

    @Test
    void aCommitsArgvCarriesItsOptionsAndTheMessageAsOneArgument() {
        assertEquals(
                List.of("commit", "-m", "Subject\n\nBody"),
                List.of(GitCommitCoordinator.commitArgs(
                        new GitPanel.CommitRequest("Subject\n\nBody", false, false, false), false)));
        assertEquals(
                List.of("commit", "--amend", "-s", "-m", "x"),
                List.of(GitCommitCoordinator.commitArgs(new GitPanel.CommitRequest("x", true, true, true), false)),
                "pushing is not git commit's business");
        // A message beginning with "-" is the value of -m, and "#42" stays a subject without a template.
        assertEquals(
                List.of("commit", "-m", "#42 --amend"),
                List.of(GitCommitCoordinator.commitArgs(
                        new GitPanel.CommitRequest("#42 --amend", false, false, false), false)));
        assertEquals(
                List.of("commit", "--cleanup=strip", "-m", "# hint\nSubject"),
                List.of(GitCommitCoordinator.commitArgs(
                        new GitPanel.CommitRequest("# hint\nSubject", false, false, false), true)),
                "with a commit template its comment lines are stripped by git");
    }

    @Test
    void theLastCommitIsNotUndoneWhenItHasNoParentOrIsAMerge() {
        assertEquals("status.git.undoCommit.none", GitCommitCoordinator.undoRefusal(null));
        assertEquals("status.git.undoCommit.root", GitCommitCoordinator.undoRefusal(head(List.of())));
        assertEquals("status.git.undoCommit.merge", GitCommitCoordinator.undoRefusal(head(List.of("a", "b"))));
        assertNull(GitCommitCoordinator.undoRefusal(head(List.of("a"))));
    }

    private static GitService.HeadCommit head(List<String> parents) {
        return new GitService.HeadCommit("abc1234def", "abc1234", "subject", parents, "subject", "", false);
    }

    /** During a rebase git's "ours" is the branch being rebased onto: the labels say what each side is. */
    @Test
    void acceptSideIsNamedForTheOperationInProgress() {
        assertEquals("gitpanel.menu.acceptOurs", GitCoordinator.acceptSideKey(GitOperation.Kind.MERGE, true));
        assertEquals("gitpanel.menu.acceptTheirs", GitCoordinator.acceptSideKey(GitOperation.Kind.MERGE, false));
        assertEquals("gitpanel.menu.acceptOurs", GitCoordinator.acceptSideKey(GitOperation.Kind.NONE, true));
        assertEquals("gitpanel.menu.acceptOurs.rebase", GitCoordinator.acceptSideKey(GitOperation.Kind.REBASE, true));
        assertEquals(
                "gitpanel.menu.acceptTheirs.rebase", GitCoordinator.acceptSideKey(GitOperation.Kind.REBASE, false));
        assertEquals(
                "gitpanel.menu.acceptTheirs.commit",
                GitCoordinator.acceptSideKey(GitOperation.Kind.CHERRY_PICK, false));
        assertEquals(
                "gitpanel.menu.acceptTheirs.commit", GitCoordinator.acceptSideKey(GitOperation.Kind.REVERT, false));
    }

    @Test
    void theCloneDepthIsAPositiveNumberOrNothing() {
        assertEquals(0, GitCoordinator.cloneDepth(""));
        assertEquals(0, GitCoordinator.cloneDepth(null));
        assertEquals(0, GitCoordinator.cloneDepth("  "));
        assertEquals(1, GitCoordinator.cloneDepth(" 1 "));
        assertEquals(50, GitCoordinator.cloneDepth("50"));
        assertEquals(-1, GitCoordinator.cloneDepth("0"));
        assertEquals(-1, GitCoordinator.cloneDepth("-2"));
        assertEquals(-1, GitCoordinator.cloneDepth("many"));
        assertEquals(-1, GitCoordinator.cloneDepth("99999999999"));
    }

    /** Discard All sorts every changed path by what its status needs, and stops at a conflict. */
    @Test
    void discardAllPlansEachPathByItsStatus() {
        GitStatus status = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(
                        new FileEntry("edited.txt", '.', 'M', null),
                        new FileEntry("staged.txt", 'M', '.', null),
                        new FileEntry("both.txt", 'M', 'M', null),
                        new FileEntry("new-name.txt", 'R', '.', "old-name.txt"),
                        new FileEntry("scratch.txt", '?', '?', null),
                        new FileEntry("build/", '?', '?', null)));
        GitCoordinator.DiscardAll plan = GitCoordinator.DiscardAll.of(status);
        assertEquals(List.of("edited.txt"), plan.worktree());
        assertEquals(
                List.of("staged.txt", "both.txt", "new-name.txt", "old-name.txt"),
                plan.head(),
                "a staged rename brings its old name back");
        assertEquals(List.of("scratch.txt", "build/"), plan.untracked());
        assertEquals(5, plan.tracked());
        assertNull(plan.conflict());

        GitStatus conflicted = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(new FileEntry("a.txt", '.', 'M', null), new FileEntry("c.txt", 'U', 'U', null)));
        assertEquals("c.txt", GitCoordinator.DiscardAll.of(conflicted).conflict());
        assertEquals(0, GitCoordinator.DiscardAll.of(null).tracked());
    }

    /** Only a failure brings the Git console forward; a local command that worked leaves the Git Log alone. */
    @Test
    void onlyAFailedCommandRaisesTheGitConsole() {
        assertFalse(GitConsoleLog.raisesConsole(entry(0, "")), "a tag created from the Git Log");
        assertTrue(GitConsoleLog.raisesConsole(entry(1, "error: pathspec")));
        assertTrue(GitConsoleLog.raisesConsole(entry(128, "fatal: not a git repository")));
        assertFalse(GitConsoleLog.raisesConsole(entry(-1, ProcessRunner.CANCELLED)), "the user stopped it");
    }

    private static CommandLog.Entry entry(int exit, String err) {
        return new CommandLog.Entry(List.of("git", "tag", "v1"), exit, "", err, 12);
    }

    @Test
    void theAutomaticFetchIntervalIsNeverUnderAMinute() {
        assertEquals(1, GitAutoFetch.minutes(0));
        assertEquals(1, GitAutoFetch.minutes(-5));
        assertEquals(10, GitAutoFetch.minutes(10));
    }
}
