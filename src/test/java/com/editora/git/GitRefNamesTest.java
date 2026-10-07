package com.editora.git;

import java.util.List;

import com.editora.git.GitRefNames.Problem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
