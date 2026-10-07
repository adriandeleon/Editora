package com.editora.git;

import java.util.List;

import com.editora.git.GitStatus.FileEntry;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W2: taking one side of a conflict, and recognising a command that stopped on conflicts. */
class GitConflictsTest {

    private static FileEntry entry(String path, String xy) {
        return new FileEntry(path, xy.charAt(0), xy.charAt(1), null);
    }

    @Test
    void onlyUnmergedEntriesAreConflicts() {
        GitStatus status = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(entry("a.txt", "UU"), entry("b.txt", "M."), entry("c.txt", "??"), entry("d.txt", "AA")));
        assertEquals(
                List.of("a.txt", "d.txt"),
                GitConflicts.unmerged(status).stream().map(FileEntry::path).toList());
        assertTrue(GitConflicts.unmerged(null).isEmpty());
        assertTrue(GitConflicts.unmerged(GitStatus.NOT_A_REPO).isEmpty());
    }

    @Test
    void aSideThatHasTheFileIsCheckedOutAndStaged() {
        List<String[]> ours = GitConflicts.acceptSide(List.of(entry("a.txt", "UU"), entry("b.txt", "AA")), true);
        assertEquals(2, ours.size());
        assertArrayEquals(
                new String[] {"--literal-pathspecs", "checkout", "--ours", "--", "a.txt", "b.txt"}, ours.get(0));
        assertArrayEquals(new String[] {"--literal-pathspecs", "add", "--", "a.txt", "b.txt"}, ours.get(1));

        List<String[]> theirs = GitConflicts.acceptSide(List.of(entry("a.txt", "UU")), false);
        assertArrayEquals(new String[] {"--literal-pathspecs", "checkout", "--theirs", "--", "a.txt"}, theirs.get(0));
    }

    @Test
    void takingASideThatDeletedOrNeverHadTheFileRemovesIt() {
        // `git checkout --ours` on a path our side deleted fails with "does not have our version".
        // X = ours, Y = theirs: DU deleted by us, UD deleted by them, AU added by us, UA added by them.
        assertTrue(GitConflicts.sideHasFile(entry("f", "UD"), true), "deleted by them: ours still has it");
        assertFalse(GitConflicts.sideHasFile(entry("f", "UD"), false));
        assertFalse(GitConflicts.sideHasFile(entry("f", "DU"), true), "deleted by us");
        assertTrue(GitConflicts.sideHasFile(entry("f", "DU"), false));
        assertTrue(GitConflicts.sideHasFile(entry("f", "AU"), true), "added by us");
        assertFalse(GitConflicts.sideHasFile(entry("f", "AU"), false), "they never had it");
        assertFalse(GitConflicts.sideHasFile(entry("f", "UA"), true));
        assertTrue(GitConflicts.sideHasFile(entry("f", "UA"), false));
        assertFalse(GitConflicts.sideHasFile(entry("f", "DD"), true));
        assertFalse(GitConflicts.sideHasFile(entry("f", "DD"), false));

        List<String[]> ours = GitConflicts.acceptSide(List.of(entry("gone.txt", "DU"), entry("kept.txt", "UD")), true);
        assertEquals(3, ours.size());
        assertArrayEquals(new String[] {"--literal-pathspecs", "checkout", "--ours", "--", "kept.txt"}, ours.get(0));
        assertArrayEquals(new String[] {"--literal-pathspecs", "add", "--", "kept.txt"}, ours.get(1));
        assertArrayEquals(
                new String[] {"--literal-pathspecs", "rm", "-q", "--ignore-unmatch", "--", "gone.txt"}, ours.get(2));
    }

    @Test
    void pathsThatAreNotConflictedAreLeftOut() {
        assertTrue(GitConflicts.acceptSide(List.of(entry("a.txt", "M."), entry("b.txt", "??")), true)
                .isEmpty());
    }

    @Test
    void aCommandThatStoppedOnConflictsIsNotAnError() {
        // git merge / pull --no-rebase
        assertTrue(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(
                1,
                "Auto-merging story.txt\nCONFLICT (content): Merge conflict in story.txt\n"
                        + "Automatic merge failed; fix conflicts and then commit the result.\n",
                "")));
        // git rebase / pull --rebase / cherry-pick / revert
        assertTrue(
                GitConflicts.stoppedOnConflict(
                        new ProcessRunner.Result(
                                1,
                                "Auto-merging story.txt\nCONFLICT (content): Merge conflict in story.txt\n",
                                "error: could not apply 9b0d3ce... local\nhint: Resolve all conflicts manually, mark them as resolved with\n")));
        assertTrue(
                GitConflicts.stoppedOnConflict(
                        new ProcessRunner.Result(
                                1,
                                "",
                                "error: could not revert 1a2b3c4... change\nhint: after resolving the conflicts, mark the corrected paths\n")));
        // git stash pop
        assertTrue(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(
                1,
                "Auto-merging story.txt\nCONFLICT (content): Merge conflict in story.txt\n"
                        + "The stash entry is kept in case you need it again.\n",
                "")));
        // a modify/delete conflict
        assertTrue(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(
                1, "CONFLICT (modify/delete): a.txt deleted in HEAD and modified in feature.\n", "")));
    }

    @Test
    void aCommandThatRefusedToStartIsStillAnError() {
        assertFalse(
                GitConflicts.stoppedOnConflict(new ProcessRunner.Result(0, "CONFLICT (content): x", "")), "success");
        assertFalse(GitConflicts.stoppedOnConflict(null));
        assertFalse(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(
                1,
                "",
                "error: Your local changes to the following files would be overwritten by merge:\n\ta.txt\n"
                        + "Please commit your changes or stash them before you merge.\nAborting\n")));
        assertFalse(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(128, "", "fatal: bad revision 'nope'")));
        assertFalse(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(
                128, "", "error: Committing is not possible because you have unmerged files.")));
        assertFalse(GitConflicts.stoppedOnConflict(new ProcessRunner.Result(-1, "", ProcessRunner.CANCELLED)));
    }
}
