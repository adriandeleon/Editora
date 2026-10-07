package com.editora.git;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outcomes of a git command that are not "it failed", from git's real (C-locale) output. */
class GitOutcomeTest {

    @Test
    void exitZeroIsOk() {
        assertEquals(GitOutcome.OK, GitOutcome.of(0, "Already up to date.\n", ""));
        // Even when the text mentions a conflict: git said it succeeded.
        assertEquals(GitOutcome.OK, GitOutcome.of(0, "CONFLICT (content): resolved by rerere", ""));
    }

    @Test
    void aMergeThatStopsOnConflictsIsNotAFailure() {
        String out = "Auto-merging file.txt\nCONFLICT (content): Merge conflict in file.txt\n"
                + "Automatic merge failed; fix conflicts and then commit the result.\n";
        assertEquals(GitOutcome.CONFLICT, GitOutcome.of(1, out, ""));
    }

    @Test
    void aRebaseThatStopsOnConflictsIsNotAFailure() {
        String out = "Auto-merging file.txt\nCONFLICT (content): Merge conflict in file.txt\n";
        String err = "error: could not apply 1a2b3c4... change\n"
                + "hint: Resolve all conflicts manually, mark them as resolved with\n"
                + "hint: \"git add/rm <conflicted_files>\", then run \"git rebase --continue\".\n";
        assertEquals(GitOutcome.CONFLICT, GitOutcome.of(1, out, err));
        // Older gits print only the stderr half.
        assertEquals(GitOutcome.CONFLICT, GitOutcome.of(1, "", err));
    }

    @Test
    void aPushTheRemoteIsAheadOfIsNonFastForward() {
        String behind = "To ../remote.git\n ! [rejected]        main -> main (non-fast-forward)\n"
                + "error: failed to push some refs to '../remote.git'\n";
        assertEquals(GitOutcome.NON_FAST_FORWARD, GitOutcome.of(1, "", behind));
        String unfetched = "To ../remote.git\n ! [rejected]        main -> main (fetch first)\n"
                + "error: failed to push some refs to '../remote.git'\n";
        assertEquals(GitOutcome.NON_FAST_FORWARD, GitOutcome.of(1, "", unfetched));
    }

    /** A lease that no longer holds is a real refusal: offering "force with lease" again would loop. */
    @Test
    void aStaleLeaseAndAHookRejectionAreFailures() {
        String stale = " ! [rejected]        main -> main (stale info)\nerror: failed to push some refs\n";
        assertEquals(GitOutcome.FAILED, GitOutcome.of(1, "", stale));
        String hook = " ! [remote rejected] main -> main (pre-receive hook declined)\n";
        assertEquals(GitOutcome.FAILED, GitOutcome.of(1, "", hook));
    }

    @Test
    void anUnmergedBranchAndADirtyWorktreeAreRecognised() {
        assertEquals(
                GitOutcome.NOT_FULLY_MERGED,
                GitOutcome.of(1, "", "error: the branch 'topic' is not fully merged\nhint: ...\n"));
        assertEquals(
                GitOutcome.NOT_FULLY_MERGED, GitOutcome.of(1, "", "error: The branch 'topic' is not fully merged.\n"));
        assertEquals(
                GitOutcome.DIRTY_WORKTREE,
                GitOutcome.of(
                        128, "", "fatal: '../wt' contains modified or untracked files, use --force to delete it\n"));
    }

    @Test
    void everythingElseIsAFailure() {
        assertEquals(GitOutcome.FAILED, GitOutcome.of(128, "", "fatal: not a git repository"));
        assertEquals(GitOutcome.FAILED, GitOutcome.of(1, "", "error: Your local changes would be overwritten"));
        // A command that never ran (timeout, missing git) said nothing: not a conflict, whatever its text.
        assertEquals(GitOutcome.FAILED, GitOutcome.of(-1, "CONFLICT (content)", ""));
        assertEquals(GitOutcome.FAILED, GitOutcome.of(null));
    }

    @Test
    void aCancelledCommandIsCancelled() {
        assertEquals(GitOutcome.CANCELLED, GitOutcome.of(new ProcessRunner.Result(-1, "", ProcessRunner.CANCELLED)));
        assertEquals(GitOutcome.CONFLICT, GitOutcome.of(new ProcessRunner.Result(1, "CONFLICT (content): x", "")));
    }

    /** The text shown for a conflict stop is git's account of it, without the terminal-command hints. */
    @Test
    void theTranscriptKeepsGitsMessageAndDropsItsHints() {
        String text = GitOutcome.transcript(new ProcessRunner.Result(
                1,
                "Auto-merging a.txt\nCONFLICT (content): Merge conflict in a.txt\n\n",
                "error: could not apply 1a2b3c4... change\nhint: Resolve all conflicts manually\n"));
        assertEquals(
                "Auto-merging a.txt\nCONFLICT (content): Merge conflict in a.txt\nerror: could not apply 1a2b3c4... change",
                text);
        assertFalse(text.contains("hint:"));
        assertTrue(GitOutcome.transcript(null).isEmpty());
    }
}
