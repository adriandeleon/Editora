package com.editora.git;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine side of the automatic fetch and of the clone options: what environment a command nobody asked
 * for runs in, and what a clone's argv looks like.
 */
class GitBackgroundFetchTest {

    @Test
    void theAutomaticFetchCanAskForNothing() {
        Map<String, String> env = GitSafety.autoFetchEnv(Map.of(), false);
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("", env.get("GIT_ASKPASS"), "empty is 'no askpass program' to git");
        assertEquals("", env.get("SSH_ASKPASS"));
        assertEquals("never", env.get("SSH_ASKPASS_REQUIRE"));
        assertEquals("never", env.get("GCM_INTERACTIVE"));
        assertEquals("ssh -o BatchMode=yes", env.get("GIT_SSH_COMMAND"));
        assertTrue(env.containsKey("GIT_DIR") && env.get("GIT_DIR") == null, "repository-binding variables removed");
        assertEquals(List.of("-c", "core.askPass=", "-c", "credential.interactive=false"), GitSafety.AUTO_FETCH_CONFIG);
    }

    /** GIT_SSH_COMMAND outranks core.sshCommand: setting it would replace the ssh command the user chose. */
    @Test
    void aUsersOwnSshCommandIsNeverReplaced() {
        assertFalse(GitSafety.autoFetchEnv(Map.of(), true).containsKey("GIT_SSH_COMMAND"), "core.sshCommand is set");
        assertFalse(
                GitSafety.autoFetchEnv(Map.of("GIT_SSH_COMMAND", "ssh -i ~/.ssh/work"), false)
                        .containsKey("GIT_SSH_COMMAND"),
                "exported by the user: inherited as it is");
        assertFalse(GitSafety.autoFetchEnv(Map.of("GIT_SSH", "/usr/bin/plink"), false)
                .containsKey("GIT_SSH_COMMAND"));
        assertTrue(GitSafety.autoFetchEnv(Map.of("GIT_SSH_COMMAND", " "), false).containsKey("GIT_SSH_COMMAND"));
        assertTrue(GitSafety.autoFetchEnv(null, false).containsKey("GIT_SSH_COMMAND"));
    }

    @Test
    void cloneOptionsBecomeArgumentsBeforeTheDoubleDash() {
        assertEquals(
                List.of("clone", "--progress", "--", "url", "/dest"),
                List.of(GitService.cloneArgs("url", "/dest", GitService.CloneOptions.NONE)));
        assertEquals(
                List.of("clone", "--progress", "--depth=1", "--branch=topic", "--recurse-submodules", "--", "u", "/d"),
                List.of(GitService.cloneArgs("u", "/d", new GitService.CloneOptions(1, " topic ", true))));
        // A branch typed as an option stays the value of --branch: one argument, never an option of its own.
        assertEquals(
                List.of("clone", "--progress", "--branch=--upload-pack=evil", "--", "u", "/d"),
                List.of(GitService.cloneArgs("u", "/d", new GitService.CloneOptions(0, "--upload-pack=evil", false))));
        assertEquals("", new GitService.CloneOptions(-3, null, false).branch());
    }
}
