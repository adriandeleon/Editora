package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git keeps the parse-stable environment. Splitting {@code LC_ALL=C} off the user-facing children (runs,
 * builds, language servers) must not take it away from git, whose porcelain output Editora parses and which
 * would otherwise answer in the user's language.
 *
 * <p>A shell script stands in for {@code git}, so Windows sits this out.
 */
@DisabledOnOs(OS.WINDOWS)
class GitEnvironmentProcessTest {

    @Test
    void gitStillRunsInTheCLocale(@TempDir Path dir) throws Exception {
        // Succeeds only when started with LC_ALL=C — the exit code is all gitAvailable() looks at.
        Path fakeGit = dir.resolve("fake-git");
        Files.writeString(fakeGit, "#!/bin/sh\n[ \"$LC_ALL\" = \"C\" ]\n");
        Files.setPosixFilePermissions(fakeGit, PosixFilePermissions.fromString("rwx------"));
        GitService git = new GitService();
        try {
            git.setCommand(fakeGit.toString());

            assertTrue(git.gitAvailable(), "git must be started with LC_ALL=C so its output parses in every locale");
        } finally {
            git.setCommand(""); // the command is app-wide static state
            git.shutdown();
        }
    }
}
