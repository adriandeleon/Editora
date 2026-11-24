package com.editora.git;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The Git Log's "did anything move" fingerprint: only a real answer is a fingerprint (R3-E1, R3-E6). */
class GitRefsFingerprintTest {

    private static final ProcessRunner.Result HEAD = new ProcessRunner.Result(0, "refs/heads/main\n", "");
    private static final ProcessRunner.Result REFS = new ProcessRunner.Result(0, "abc HEAD\nabc refs/heads/main\n", "");

    @Test
    void aRealAnswerIsAFingerprintAndMovesWithTheRefs() {
        String one = GitService.refsFingerprint(HEAD, REFS, "-");
        assertFalse(one.isEmpty());
        assertEquals(one, GitService.refsFingerprint(HEAD, REFS, "-"));
        ProcessRunner.Result moved = new ProcessRunner.Result(0, "def HEAD\ndef refs/heads/main\n", "");
        assertNotEquals(one, GitService.refsFingerprint(HEAD, moved, "-"));
        // A detached HEAD: symbolic-ref -q exits 1 without a word, which is an answer.
        assertFalse(GitService.refsFingerprint(new ProcessRunner.Result(1, "", ""), REFS, "-")
                .isEmpty());
    }

    @Test
    void anUnbornRepositoryHasAFingerprint() {
        assertFalse(GitService.refsFingerprint(HEAD, new ProcessRunner.Result(1, "", ""), "-")
                .isEmpty());
    }

    @Test
    void aFailedReadIsUnknownSoTheLogReloads() {
        // Output too large (completeOrFailed's exit 1, empty stdout), a timeout, an error, a truncated capture.
        assertEquals(
                "",
                GitService.refsFingerprint(
                        HEAD, new ProcessRunner.Result(1, "", GitService.OUTPUT_TOO_LARGE, true, false), "-"));
        assertEquals("", GitService.refsFingerprint(HEAD, new ProcessRunner.Result(-1, "", "command timed out"), "-"));
        assertEquals("", GitService.refsFingerprint(HEAD, new ProcessRunner.Result(128, "", "fatal: bad"), "-"));
        assertEquals("", GitService.refsFingerprint(HEAD, new ProcessRunner.Result(0, "abc", "", true, false), "-"));
        assertEquals("", GitService.refsFingerprint(new ProcessRunner.Result(-1, "", "command timed out"), REFS, "-"));
    }

    @Test
    void deepeningAShallowCloneChangesTheFingerprint() {
        assertNotEquals(GitService.refsFingerprint(HEAD, REFS, "aaa"), GitService.refsFingerprint(HEAD, REFS, "bbb"));
        assertNotEquals(GitService.refsFingerprint(HEAD, REFS, "aaa"), GitService.refsFingerprint(HEAD, REFS, "-"));
    }
}
