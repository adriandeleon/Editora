package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitService.Availability;
import com.editora.git.GitService.RepoState;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind the engine's caches and failure reporting. */
class GitServiceDecisionsTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aTruncatedReadIsAFailureNotASmallerAnswer() {
        // E1: no text-output caller looked at the flag, so 10 MB of a larger blame / status / diff / file
        // list was parsed and shown (and, for blame, cached) as if it were all of it.
        ProcessRunner.Result cut = new ProcessRunner.Result(0, "partial", "", true, false);
        ProcessRunner.Result failed = GitService.completeOrFailed(cut);
        assertFalse(failed.ok());
        assertEquals("", failed.out(), "the partial output must not reach a parser");
        assertEquals(GitService.OUTPUT_TOO_LARGE, failed.message());
        assertTrue(failed.outTruncated());
        assertFalse(failed.timedOut());

        ProcessRunner.Result whole = new ProcessRunner.Result(0, "all of it", "");
        assertSame(whole, GitService.completeOrFailed(whole));
        // A failure stays the failure it was (its message is git's), truncated or not.
        ProcessRunner.Result error = new ProcessRunner.Result(128, "x", "fatal: bad object", true, false);
        assertSame(error, GitService.completeOrFailed(error));
        // Only stdout is parsed: a cut-off stderr does not fail a read.
        ProcessRunner.Result noisy = new ProcessRunner.Result(0, "ok", "warnings…", false, true);
        assertSame(noisy, GitService.completeOrFailed(noisy));
    }

    @Test
    void gitsRefusalIsItsFirstErrorLine() {
        // E5
        assertEquals(
                "detected dubious ownership in repository at '/mnt/c/work'",
                GitService.refusalReason(new ProcessRunner.Result(
                        128,
                        "",
                        "fatal: detected dubious ownership in repository at '/mnt/c/work'\n"
                                + "To add an exception for this directory, call:\n\n"
                                + "\tgit config --global --add safe.directory /mnt/c/work\n")));
        assertEquals(
                "this operation must be run in a work tree",
                GitService.refusalReason(
                        new ProcessRunner.Result(128, "", "\nfatal: this operation must be run in a work tree\n")));
        assertEquals(
                "index file corrupt",
                GitService.refusalReason(new ProcessRunner.Result(1, "", "error: index file corrupt")));
        // Killed, not started, interrupted, or silent: git said nothing about the folder.
        assertEquals("", GitService.refusalReason(new ProcessRunner.Result(-1, "", "command timed out")));
        assertEquals("", GitService.refusalReason(new ProcessRunner.Result(-1, "", "Cannot run program")));
        assertEquals("", GitService.refusalReason(new ProcessRunner.Result(128, "", "  \n")));
        assertEquals("", GitService.refusalReason(new ProcessRunner.Result(0, "", "warning: x")));
        assertTrue(GitService.refusalReason(new ProcessRunner.Result(1, "", "fatal: " + "x".repeat(5_000)))
                        .length()
                < 400);
    }

    @Test
    void aRefusedStateIsNotARepositoryButSaysWhy() {
        RepoState refused = RepoState.refused("detected dubious ownership in repository at '/x'");
        assertFalse(refused.isRepo());
        assertTrue(refused.refused());
        assertEquals("detected dubious ownership in repository at '/x'", refused.refusal());
        assertFalse(RepoState.NONE.refused());
        assertEquals("", RepoState.NONE.refusal());
        // The constructors existing callers use still exist and mean "no refusal".
        RepoState plain = new RepoState(
                Path.of("/r"),
                new GitStatus(true, "main", "", 0, 0, List.of()),
                java.util.Map.of(),
                java.util.Map.of());
        assertTrue(plain.isRepo());
        assertFalse(plain.refused());
        assertTrue(RepoState.refused(null).refused(), "a refusal always has some text");
    }

    @Test
    void gitUnavailableIsBelievedOnlyForAWhile() {
        // E6: one failed (or merely slow) `git --version` disabled Git until restart.
        List<String> git = List.of("git");
        assertFalse(GitService.availabilityCurrent(null, git, 0));
        Availability found = new Availability(git, true, 0, 0);
        assertTrue(GitService.availabilityCurrent(found, git, 365L * 24 * 3600 * SECOND), "a success does not expire");
        assertFalse(GitService.availabilityCurrent(found, List.of("/opt/git"), SECOND), "another command: ask again");

        Availability missing = new Availability(git, false, 100 * SECOND, 1);
        assertTrue(GitService.availabilityCurrent(missing, git, 100 * SECOND + 4 * SECOND));
        assertFalse(GitService.availabilityCurrent(missing, git, 100 * SECOND + 5 * SECOND));
        // Each further failure doubles the wait, to a ceiling: a machine without git is not probed per refresh.
        Availability third = new Availability(git, false, 0, 3);
        assertTrue(GitService.availabilityCurrent(third, git, 19 * SECOND));
        assertFalse(GitService.availabilityCurrent(third, git, 20 * SECOND));
        Availability hundredth = new Availability(git, false, 0, 100);
        assertTrue(GitService.availabilityCurrent(hundredth, git, 59 * SECOND));
        assertFalse(GitService.availabilityCurrent(hundredth, git, 60 * SECOND));
    }

    @Test
    void aNewEntryTakesTheWorkingFilesMode() {
        // E3
        assertEquals("100644", GitService.newEntryMode(false, false, true));
        assertEquals("100755", GitService.newEntryMode(false, true, true));
        assertEquals("100644", GitService.newEntryMode(false, true, false), "core.fileMode=false: bits mean nothing");
        assertEquals("120000", GitService.newEntryMode(true, false, true));
        assertEquals("120000", GitService.newEntryMode(true, true, false));
    }

    @Test
    void theMarkerFingerprintChangesWhenARepositoryAppearsOrGoes(@TempDir Path tmp) throws Exception {
        // E4: what a cached root is revalidated with, instead of a process per refresh.
        Path root = Files.createDirectories(tmp.resolve("outer")).toRealPath();
        Files.createDirectory(root.resolve(".git"));
        Path deep = Files.createDirectories(root.resolve("a/b"));

        List<Boolean> before = GitService.gitMarkers(deep, root);
        assertEquals(List.of(false, false, true), before);
        assertEquals(before, GitService.gitMarkers(deep, root), "stable while nothing changes");

        Files.createDirectory(root.resolve("a/.git")); // git init in a parent folder below the root
        assertNotEquals(before, GitService.gitMarkers(deep, root));
        Files.delete(root.resolve("a/.git"));
        assertEquals(before, GitService.gitMarkers(deep, root));

        Files.writeString(deep.resolve(".git"), "gitdir: elsewhere\n"); // a work tree's .git is a file
        assertNotEquals(before, GitService.gitMarkers(deep, root));
        Files.delete(deep.resolve(".git"));

        Files.delete(root.resolve(".git")); // the repository itself is gone
        assertNotEquals(before, GitService.gitMarkers(deep, root));

        assertEquals(List.of(false), GitService.gitMarkers(root, root));
        // A folder outside the root (a separate work tree): its own entry and the root's.
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        assertEquals(2, GitService.gitMarkers(elsewhere, root).size());
    }
}
