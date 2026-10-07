package com.editora.git;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W1: the pull modes, and telling "the branches diverged" apart from every other failed pull. */
class GitPullModeTest {

    @Test
    void eachModeHasItsArguments() {
        assertArrayEquals(new String[] {"pull", "--ff-only"}, GitPullMode.FF_ONLY.args());
        assertArrayEquals(new String[] {"pull", "--rebase"}, GitPullMode.REBASE.args());
        assertArrayEquals(
                new String[] {"pull", "--no-rebase", "--no-edit"},
                GitPullMode.MERGE.args(),
                "--no-rebase overrides pull.rebase=true; --no-edit because there is no terminal for an editor");
    }

    @Test
    void aStoredValueMapsToItsModeAndAnythingElseIsFastForwardOnly() {
        for (GitPullMode mode : GitPullMode.values()) {
            assertEquals(mode, GitPullMode.of(mode.id()));
        }
        assertEquals(GitPullMode.REBASE, GitPullMode.of(" Rebase "));
        assertEquals(GitPullMode.FF_ONLY, GitPullMode.of(null), "an existing settings file has no value");
        assertEquals(GitPullMode.FF_ONLY, GitPullMode.of(""));
        assertEquals(GitPullMode.FF_ONLY, GitPullMode.of("octopus"));
    }

    @Test
    void aDivergedFastForwardIsRecognisedFromGitsReply() {
        // git 2.38+ (verbatim from `git pull --ff-only` on a diverged branch)
        assertTrue(GitPullMode.diverged(new ProcessRunner.Result(
                128,
                "",
                "From /tmp/origin\n   4f1c2aa..9b0d3ce  main       -> origin/main\n"
                        + "hint: Diverging branches can't be fast-forwarded, you need to either:\n"
                        + "hint:\n"
                        + "hint: \tgit merge --no-ff\n"
                        + "hint:\n"
                        + "hint: or:\n"
                        + "hint:\n"
                        + "hint: \tgit rebase\n"
                        + "hint:\n"
                        + "fatal: Not possible to fast-forward, aborting.\n")));
        // Older git: only the fatal line.
        assertTrue(GitPullMode.diverged(
                new ProcessRunner.Result(128, "", "fatal: Not possible to fast-forward, aborting.")));
    }

    @Test
    void otherFailedPullsAreNotDivergence() {
        assertFalse(GitPullMode.diverged(new ProcessRunner.Result(0, "Already up to date.", "")));
        assertFalse(GitPullMode.diverged(null));
        assertFalse(GitPullMode.diverged(new ProcessRunner.Result(
                1, "", "fatal: unable to access 'https://example.invalid/x.git/': Could not resolve host")));
        assertFalse(
                GitPullMode.diverged(
                        new ProcessRunner.Result(
                                1,
                                "",
                                "There is no tracking information for the current branch.\nPlease specify which branch you want to merge with.")));
        assertFalse(GitPullMode.diverged(new ProcessRunner.Result(
                1,
                "",
                "error: Your local changes to the following files would be overwritten by merge:\n\ta.txt\nAborting")));
        assertFalse(GitPullMode.diverged(new ProcessRunner.Result(-1, "", ProcessRunner.CANCELLED)));
    }
}
