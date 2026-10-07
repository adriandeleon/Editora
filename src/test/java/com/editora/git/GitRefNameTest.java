package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tag-name validation, held to what the installed git itself accepts. */
class GitRefNameTest {

    private static final List<String> VALID = List.of(
            "v1.0",
            "release/2026-10",
            "a",
            "ünïcödé",
            "v1.0-rc.1",
            "feature/x/y",
            "@v1",
            "a@b",
            "x.locked",
            "a.b",
            "1");

    private static final List<String> INVALID = List.of(
            "",
            "@",
            "-v1",
            "--force",
            "/v1",
            "v1/",
            "a//b",
            "a..b",
            "v1.",
            ".hidden",
            "a/.hidden",
            "v1.lock",
            "a.lock/b",
            "a b",
            "a~1",
            "a^",
            "a:b",
            "a?",
            "a*",
            "a[1]",
            "a\\b",
            "a@{1}",
            "tab\there",
            "ctrl\u0001",
            "del\u007f");

    @Test
    void validNamesAreAcceptedAndInvalidOnesRefused() {
        for (String name : VALID) {
            assertTrue(GitRefName.isValid(name), "valid: " + name);
        }
        for (String name : INVALID) {
            assertFalse(GitRefName.isValid(name), "invalid: " + name);
        }
        assertFalse(GitRefName.isValid(null));
    }

    @Test
    void itAgreesWithGitCheckRefFormat(@TempDir Path dir) throws Exception {
        assumeTrue(gitRuns(dir), "git is not installed");
        for (List<String> names : List.of(VALID, INVALID)) {
            for (String name : names) {
                if (name.isEmpty() || name.indexOf('\0') >= 0) {
                    continue;
                }
                // A name beginning with "-" is refused by `git tag` itself (it would be an option), not by
                // check-ref-format, which only judges the ref's spelling.
                // A lone "@" is a legal tag ref, but as a name it means HEAD: Editora does not create it.
                boolean git = !name.startsWith("-")
                        && !name.equals("@")
                        && run(dir, "git", "check-ref-format", "refs/tags/" + name) == 0;
                assertEquals(git, GitRefName.isValid(name), "git check-ref-format refs/tags/" + name);
            }
        }
    }

    private static boolean gitRuns(Path dir) {
        try {
            return run(dir, "git", "--version") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(Files.createDirectories(dir).toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }
}
