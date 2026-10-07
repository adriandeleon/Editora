package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.editora.git.GitOperation.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W2: the operation a repository is in the middle of, read from its git directory's state files. */
class GitOperationTest {

    /** A git directory as a map: a present key is an existing entry, its value the file's text. */
    private static GitOperation detect(Map<String, String> entries) {
        return GitOperation.detect(new GitOperation.Probe() {
            @Override
            public boolean exists(String name) {
                return entries.containsKey(name);
            }

            @Override
            public String read(String name) {
                return entries.get(name);
            }
        });
    }

    private static Map<String, String> dir(String... namesAndTexts) {
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < namesAndTexts.length; i += 2) {
            entries.put(namesAndTexts[i], namesAndTexts[i + 1]);
        }
        return entries;
    }

    @Test
    void aCleanGitDirectoryHasNoOperation() {
        assertSame(GitOperation.NONE, detect(dir("HEAD", "ref: refs/heads/main\n", "ORIG_HEAD", "abc\n")));
        assertSame(GitOperation.NONE, GitOperation.detect((GitOperation.Probe) null));
        assertSame(GitOperation.NONE, GitOperation.detect((Path) null));
        assertFalse(GitOperation.NONE.inProgress());
        assertFalse(GitOperation.NONE.canContinue());
        assertEquals(0, GitOperation.NONE.abortArgs().length);
    }

    @Test
    void aMergeIsRecognisedAndBringsItsPreparedMessageWithoutTheCommentLines() {
        GitOperation merge = detect(
                dir("MERGE_HEAD", "1234\n", "MERGE_MSG", "Merge branch 'feature'\n\n# Conflicts:\n#\tstory.txt\n"));
        assertEquals(Kind.MERGE, merge.kind());
        assertEquals("Merge branch 'feature'", merge.message(), "the # Conflicts: block is for an editor to strip");
        assertTrue(merge.canContinue());
        assertTrue(merge.canAbort());
        assertFalse(merge.canSkip(), "a merge is one step: git has no merge --skip");
        assertArrayEquals(
                new String[] {"commit", "--no-edit", "--cleanup=strip"},
                merge.continueArgs(),
                "a merge is concluded by committing");
        assertArrayEquals(new String[] {"merge", "--abort"}, merge.abortArgs());
        assertEquals(0, merge.skipArgs().length);
    }

    @Test
    void aMergeWithoutAReadableMessageIsStillAMerge() {
        assertEquals("", detect(dir("MERGE_HEAD", "1234\n")).message());
        assertEquals(
                Kind.MERGE,
                detect(dir("MERGE_HEAD", "1234\n", "MERGE_MSG", null)).kind());
    }

    @Test
    void bothRebaseBackendsAreRecognisedWithTheirProgress() {
        GitOperation merging =
                detect(dir("rebase-merge", null, "rebase-merge/msgnum", "2\n", "rebase-merge/end", "5\n"));
        assertEquals(Kind.REBASE, merging.kind());
        assertEquals(2, merging.step());
        assertEquals(5, merging.total());
        assertArrayEquals(new String[] {"rebase", "--continue"}, merging.continueArgs());
        assertArrayEquals(new String[] {"rebase", "--skip"}, merging.skipArgs());
        assertArrayEquals(new String[] {"rebase", "--abort"}, merging.abortArgs());

        GitOperation applying = detect(dir("rebase-apply", null, "rebase-apply/next", "1", "rebase-apply/last", "3"));
        assertEquals(Kind.REBASE, applying.kind());
        assertEquals(1, applying.step());
        assertEquals(3, applying.total());

        GitOperation unreadable = detect(dir("rebase-merge", null, "rebase-merge/msgnum", "soon"));
        assertEquals(Kind.REBASE, unreadable.kind(), "the progress files are a nicety, not the evidence");
        assertEquals(0, unreadable.step());
    }

    @Test
    void aRebaseWinsOverTheCherryPickAndMergeHeadsItLeavesWhileReplaying() {
        // While a rebase replays a commit git also writes CHERRY_PICK_HEAD (and MERGE_HEAD under
        // --rebase-merges). The command that moves on is still rebase --continue.
        GitOperation op = detect(dir("rebase-merge", null, "CHERRY_PICK_HEAD", "1\n", "MERGE_HEAD", "2\n"));
        assertEquals(Kind.REBASE, op.kind());
    }

    @Test
    void anApplyMailboxSessionIsNotARebase() {
        assertSame(GitOperation.NONE, detect(dir("rebase-apply", null, "rebase-apply/applying", "")));
    }

    @Test
    void cherryPickAndRevertAreRecognisedByTheirHeads() {
        GitOperation pick = detect(dir("CHERRY_PICK_HEAD", "1\n"));
        assertEquals(Kind.CHERRY_PICK, pick.kind());
        assertArrayEquals(new String[] {"cherry-pick", "--continue"}, pick.continueArgs());
        assertArrayEquals(new String[] {"cherry-pick", "--skip"}, pick.skipArgs());
        assertArrayEquals(new String[] {"cherry-pick", "--abort"}, pick.abortArgs());

        GitOperation revert = detect(dir("REVERT_HEAD", "1\n"));
        assertEquals(Kind.REVERT, revert.kind());
        assertArrayEquals(new String[] {"revert", "--abort"}, revert.abortArgs());
    }

    @Test
    void aMultiCommitPickBetweenTwoCommitsIsKnownFromTheSequencer() {
        // After the conflicted commit of `cherry-pick A B C` is committed by hand there is no CHERRY_PICK_HEAD,
        // but B and C are still queued: the sequencer's todo list says which command is running.
        assertEquals(
                Kind.CHERRY_PICK,
                detect(dir("sequencer/todo", "pick 1a2b3c4 second\npick 5d6e7f8 third\n"))
                        .kind());
        assertEquals(
                Kind.REVERT,
                detect(dir("sequencer/todo", "# comment\n\nrevert 1a2b3c4 second\n"))
                        .kind());
        assertSame(GitOperation.NONE, detect(dir("sequencer/todo", "")));
        assertSame(GitOperation.NONE, detect(dir("sequencer/todo", "exec make\n")));
    }

    @Test
    void aBisectIsShownButHasNothingToDrive() {
        GitOperation bisect = detect(dir("BISECT_LOG", "git bisect start\n"));
        assertEquals(Kind.BISECT, bisect.kind());
        assertTrue(bisect.inProgress());
        assertFalse(bisect.canContinue());
        assertFalse(bisect.canAbort());
        assertFalse(bisect.canSkip());
        assertEquals(0, bisect.continueArgs().length);
        // A merge started during a bisect is the thing to finish first.
        assertEquals(
                Kind.MERGE, detect(dir("BISECT_LOG", "x", "MERGE_HEAD", "1")).kind());
    }

    @Test
    void theRealDirectoryProbeReadsFilesAndFolders(@TempDir Path gitDir) throws Exception {
        assertSame(GitOperation.NONE, GitOperation.detect(gitDir));
        Files.writeString(gitDir.resolve("MERGE_HEAD"), "1234\n");
        Files.writeString(gitDir.resolve("MERGE_MSG"), "Merge branch 'x'\n# Conflicts:\n#\ta\n");
        GitOperation merge = GitOperation.detect(gitDir);
        assertEquals(Kind.MERGE, merge.kind());
        assertEquals("Merge branch 'x'", merge.message());

        Files.createDirectory(gitDir.resolve("rebase-merge"));
        Files.writeString(gitDir.resolve("rebase-merge/msgnum"), "3\n");
        Files.writeString(gitDir.resolve("rebase-merge/end"), "4\n");
        GitOperation rebase = GitOperation.detect(gitDir);
        assertEquals(Kind.REBASE, rebase.kind());
        assertEquals(3, rebase.step());
        assertEquals(4, rebase.total());
    }

    @Test
    void anOversizedMessageFileIsNotRead(@TempDir Path gitDir) throws Exception {
        Files.writeString(gitDir.resolve("MERGE_HEAD"), "1234\n");
        Files.writeString(gitDir.resolve("MERGE_MSG"), "x".repeat(GitOperation.MAX_MESSAGE_BYTES + 1));
        GitOperation merge = GitOperation.detect(gitDir);
        assertEquals(Kind.MERGE, merge.kind());
        assertEquals("", merge.message());
    }
}
