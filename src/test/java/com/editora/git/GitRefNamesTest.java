package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitRefNames.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The branch-name rules of {@code git check-ref-format --branch}, one case per rule. */
class GitRefNamesTest {

    @Test
    void ordinaryNamesAreValid() {
        for (String name :
                List.of("main", "feature/login", "fix-123", "release/2.0.x", "user@host", "a.b", "ünïcode")) {
            assertNull(GitRefNames.problem(name), name);
            assertTrue(GitRefNames.isValidBranch(name), name);
        }
    }

    @Test
    void blankNamesAreRefused() {
        assertEquals(Problem.EMPTY, GitRefNames.problem(null));
        assertEquals(Problem.EMPTY, GitRefNames.problem(""));
        assertEquals(Problem.EMPTY, GitRefNames.problem("   "));
    }

    /** A name git would parse as an option never reaches an argv. */
    @Test
    void aLeadingDashIsRefused() {
        assertEquals(Problem.LEADING_DASH, GitRefNames.problem("-f"));
        assertEquals(Problem.LEADING_DASH, GitRefNames.problem("--force"));
        assertNull(GitRefNames.problem("a-b"), "a dash elsewhere is fine");
    }

    @Test
    void forbiddenCharactersAreRefused() {
        for (String name : List.of("a b", "a~1", "a^", "a:b", "a?", "a*", "a[b", "a\\b", "a\tb", "a\u007fb", "a\nb")) {
            assertEquals(Problem.FORBIDDEN_CHARACTER, GitRefNames.problem(name), name);
        }
    }

    @Test
    void revisionSyntaxIsRefused() {
        assertEquals(Problem.FORBIDDEN_SEQUENCE, GitRefNames.problem("a..b"));
        assertEquals(Problem.FORBIDDEN_SEQUENCE, GitRefNames.problem("main@{1}"));
        assertEquals(Problem.FORBIDDEN_SEQUENCE, GitRefNames.problem("@"));
    }

    @Test
    void emptyPathComponentsAreRefused() {
        assertEquals(Problem.EMPTY_COMPONENT, GitRefNames.problem("/a"));
        assertEquals(Problem.EMPTY_COMPONENT, GitRefNames.problem("a/"));
        assertEquals(Problem.EMPTY_COMPONENT, GitRefNames.problem("a//b"));
    }

    @Test
    void hiddenAndLockComponentsAreRefused() {
        assertEquals(Problem.BAD_COMPONENT, GitRefNames.problem(".hidden"));
        assertEquals(Problem.BAD_COMPONENT, GitRefNames.problem("a/.b"));
        assertEquals(Problem.BAD_COMPONENT, GitRefNames.problem("a.lock"));
        assertEquals(Problem.BAD_COMPONENT, GitRefNames.problem("a.lock/b"));
    }

    @Test
    void aTrailingDotAndHeadAreRefused() {
        assertEquals(Problem.TRAILING_DOT, GitRefNames.problem("a."));
        assertEquals(Problem.RESERVED, GitRefNames.problem("HEAD"));
        assertNull(GitRefNames.problem("HEAD2"));
    }

    /** {@code <remote>/<branch>} must split at the first slash, so a remote's name has none. */
    @Test
    void aRemoteNameHasNoSlash() {
        assertTrue(GitRefNames.isValidRemote("origin"));
        assertTrue(GitRefNames.isValidRemote("my-fork"));
        assertFalse(GitRefNames.isValidRemote("a/b"));
        assertFalse(GitRefNames.isValidRemote("-o"));
        assertFalse(GitRefNames.isValidRemote(""));
    }

    @Test
    void aRemoteUrlIsOneLineThatIsNotAnOption() {
        assertTrue(GitRefNames.isUsableUrl("https://example.com/a.git"));
        assertTrue(GitRefNames.isUsableUrl("git@example.com:a/b.git"));
        assertTrue(GitRefNames.isUsableUrl("../sibling.git"));
        assertFalse(GitRefNames.isUsableUrl("--upload-pack=touch /tmp/x"));
        assertFalse(GitRefNames.isUsableUrl("  -u"));
        assertFalse(GitRefNames.isUsableUrl("a\nb"));
        assertFalse(GitRefNames.isUsableUrl(" "));
        assertFalse(GitRefNames.isUsableUrl(null));
    }

    // --- tag names: the table once kept for a second validator, held to the installed git ---------

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
    void tagNamesFollowTheSameRules() {
        for (String name : VALID) {
            assertTrue(GitRefNames.isValidTag(name), "valid: " + name);
        }
        for (String name : INVALID) {
            assertFalse(GitRefNames.isValidTag(name), "invalid: " + name);
        }
        assertFalse(GitRefNames.isValidTag(null));
        assertFalse(GitRefNames.isValidTag("HEAD"), "as a revision HEAD is the checked-out commit");
        for (String name : VALID) {
            assertTrue(GitRefNames.isValidBranch(name), "one validator: a valid tag name is a valid branch name");
        }
    }

    @Test
    void tagNamesAgreeWithGitCheckRefFormat(@TempDir Path dir) throws Exception {
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
                assertEquals(git, GitRefNames.isValidTag(name), "git check-ref-format refs/tags/" + name);
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
