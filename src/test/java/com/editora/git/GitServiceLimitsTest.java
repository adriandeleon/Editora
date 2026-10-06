package com.editora.git;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kill timers: short for background reads, never short for anything that rewrites the tree. */
class GitServiceLimitsTest {

    @Test
    void mutationsAndNetworkCommandsAreNotOnTheStatusTimer() {
        assertEquals(Duration.ofSeconds(10), GitService.QUICK, "background reads stay bounded");
        // A commit with pre-commit hooks or a pinentry, or a checkout of a large tree, was SIGTERMed after
        // the 10 s read timer and left half-updated.
        assertTrue(GitService.MUTATION.compareTo(Duration.ofMinutes(10)) >= 0, GitService.MUTATION.toString());
        assertTrue(GitService.NETWORK.compareTo(GitService.MUTATION) >= 0, GitService.NETWORK.toString());
    }

    @Test
    void cloneNeverReadsThePastedUrlAsAnOption() {
        // `git clone --upload-pack=<program> <repo>` runs the program; after "--" it is only a (bad) URL.
        assertEquals(
                java.util.List.of("clone", "--", "--upload-pack=touch /tmp/x", "/dest"),
                java.util.List.of(GitService.cloneArgs("--upload-pack=touch /tmp/x", "/dest")));
    }

    @Test
    void aGitPathWithASpaceIsOneArgument(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // What Settings' Browse… button writes: C:\Program Files\Git\cmd\git.exe, /Users/John Smith/bin/git.
        java.nio.file.Path tool =
                java.nio.file.Files.createDirectories(dir.resolve("My Tools")).resolve("git");
        java.nio.file.Files.writeString(tool, "");
        assertEquals(java.util.List.of(tool.toString()), GitService.commandTokens(tool.toString()));
        assertEquals(java.util.List.of(tool.toString()), GitService.commandTokens("  " + tool + " "));
        // A path that does not exist can still be quoted by hand, and a wrapper keeps its arguments.
        assertEquals(
                java.util.List.of("/no such dir/git", "--no-pager"),
                GitService.commandTokens("\"/no such dir/git\" --no-pager"));
        assertEquals(
                java.util.List.of("flatpak-spawn", "--host", "git"),
                GitService.commandTokens("flatpak-spawn --host git"));
        assertEquals(java.util.List.of("git"), GitService.commandTokens("  "));
        assertEquals(java.util.List.of("git"), GitService.commandTokens(null));
    }

    @Test
    void onlyGitsOwnAnswerCountsAsNotARepository() {
        assertTrue(GitService.isNotARepository(new com.editora.process.ProcessRunner.Result(
                128, "", "fatal: not a git repository (or any of the parent directories): .git")));
        // A killed (timed-out) rev-parse or one that could not start says nothing about the folder.
        assertTrue(!GitService.isNotARepository(new com.editora.process.ProcessRunner.Result(-1, "", "timed out")));
        assertTrue(!GitService.isNotARepository(
                new com.editora.process.ProcessRunner.Result(128, "", "fatal: bad config")));
    }
}
