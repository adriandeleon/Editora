package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules behind hardened background git commands and repository-supplied revision names. */
class GitSafetyTest {

    @Test
    void backgroundCommandsCarryTheConfigOverridesBeforeTheSubcommand() {
        List<String> argv = GitSafety.backgroundArgv(List.of("git"), "status", "--porcelain=v2", "--branch");

        assertEquals("git", argv.get(0));
        int status = argv.indexOf("status");
        List<String> overrides = argv.subList(1, status);
        assertTrue(overrides.contains("core.fsmonitor=false"), overrides.toString());
        assertTrue(overrides.stream().anyMatch(o -> o.startsWith("core.hooksPath=")), overrides.toString());
        assertTrue(overrides.contains("core.pager=cat"), overrides.toString());
        assertTrue(overrides.contains("log.showSignature=false"), overrides.toString());
        assertTrue(overrides.contains("protocol.ext.allow=never"), overrides.toString());
        // Every override is a "-c key=value" pair, and nothing touches the user's credential helper.
        for (int i = 0; i < overrides.size(); i += 2) {
            assertEquals("-c", overrides.get(i));
        }
        assertFalse(argv.stream().anyMatch(a -> a.startsWith("credential.")), argv.toString());
        // An empty diff.external is itself "a program to run" for Git; the flag is used instead.
        assertFalse(argv.stream().anyMatch(a -> a.startsWith("diff.external")), argv.toString());
        assertEquals(List.of("status", "--porcelain=v2", "--branch"), argv.subList(status, argv.size()));
    }

    @Test
    void diffProducingCommandsDisableExternalDriversAndTextconv() {
        for (String command : List.of("diff", "show", "log", "diff-tree")) {
            List<String> argv = GitSafety.backgroundArgv(List.of("git"), command, "HEAD");
            int at = argv.indexOf(command);
            assertEquals(List.of("--no-ext-diff", "--no-textconv", "HEAD"), argv.subList(at + 1, argv.size()), command);
        }
        List<String> blame = GitSafety.backgroundArgv(List.of("git"), "blame", "--line-porcelain", "--", "f");
        assertEquals("--no-textconv", blame.get(blame.indexOf("blame") + 1));
        // A global option before the subcommand does not hide it, and an unrelated command gains nothing.
        List<String> listing = GitSafety.backgroundArgv(List.of("git"), "--literal-pathspecs", "ls-files", "-s");
        assertFalse(listing.contains("--no-ext-diff"));
        List<String> literal = GitSafety.backgroundArgv(List.of("git"), "--literal-pathspecs", "diff", "--cached");
        assertEquals("--no-ext-diff", literal.get(literal.indexOf("diff") + 1));
    }

    @Test
    void theNullDeviceFollowsThePlatform() {
        assertTrue(GitSafety.backgroundConfig(false).contains("core.hooksPath=/dev/null"));
        assertTrue(GitSafety.backgroundConfig(true).contains("core.hooksPath=NUL"));
    }

    @Test
    void aConfiguredGitCommandWithArgumentsKeepsItsTokensFirst() {
        List<String> argv = GitSafety.backgroundArgv(List.of("flatpak-spawn", "--host", "git"), "status");
        assertEquals(List.of("flatpak-spawn", "--host", "git", "-c"), argv.subList(0, 4));
    }

    @Test
    void revisionsThatWouldBeReadAsOptionsAreRejected() {
        assertTrue(GitSafety.isSafeRevision("main"));
        assertTrue(GitSafety.isSafeRevision("v1.0"));
        assertTrue(GitSafety.isSafeRevision("origin/feature/x"));
        assertTrue(GitSafety.isSafeRevision("HEAD:src/App.java"));
        assertTrue(GitSafety.isSafeRevision(":2:-dash.txt")); // a path starting with "-" sits after the colon
        assertTrue(GitSafety.isSafeRevision("stash@{0}"));

        assertFalse(GitSafety.isSafeRevision("--output=/home/user/.bashrc"));
        assertFalse(GitSafety.isSafeRevision("-f"));
        assertFalse(GitSafety.isSafeRevision("--output=x:README.md"));
        assertFalse(GitSafety.isSafeRevision(""));
        assertFalse(GitSafety.isSafeRevision("  "));
        assertFalse(GitSafety.isSafeRevision(null));
        assertFalse(GitSafety.isSafeRevision("tag\nname"));
        assertFalse(GitSafety.isSafeRevision("tag\0name"));
    }

    @Test
    void endOfOptionsIsUsedOnlyWhereGitUnderstandsIt() {
        assertTrue(GitSafety.supportsEndOfOptions("git version 2.47.3"));
        assertTrue(GitSafety.supportsEndOfOptions("git version 2.24.0"));
        assertTrue(GitSafety.supportsEndOfOptions("git version 2.39.5 (Apple Git-154)"));
        assertTrue(GitSafety.supportsEndOfOptions("git version 2.45.1.windows.1"));
        assertTrue(GitSafety.supportsEndOfOptions("git version 3.0.0"));
        assertFalse(GitSafety.supportsEndOfOptions("git version 2.23.4"));
        assertFalse(GitSafety.supportsEndOfOptions("git version 1.8.3.1"));
        assertFalse(GitSafety.supportsEndOfOptions("not git"));
        assertFalse(GitSafety.supportsEndOfOptions(null));

        assertEquals(List.of("--end-of-options", "v1.0"), GitSafety.revisionArgs(true, "v1.0"));
        assertEquals(List.of("v1.0"), GitSafety.revisionArgs(false, "v1.0"));
    }

    @Test
    void aBlobSpecAppliesTheRefRulesOnlyToItsRevisionHalf() {
        // A TAB (or any control character) is legal in a file name; refusing it reported a tracked file as
        // "blob not found".
        assertTrue(GitSafety.isSafeBlobSpec("HEAD:tab\tname.txt"));
        assertTrue(GitSafety.isSafeBlobSpec(":tab\tname.txt"));
        assertTrue(GitSafety.isSafeBlobSpec(":2:line\nbreak.txt"));
        assertTrue(GitSafety.isSafeBlobSpec("HEAD:src/App.java"));
        assertTrue(GitSafety.isSafeBlobSpec(":2:-dash.txt"));
        assertTrue(GitSafety.isSafeBlobSpec("v1.0"));

        assertFalse(GitSafety.isSafeBlobSpec("--output=x:README.md"));
        assertFalse(GitSafety.isSafeBlobSpec("-f"));
        assertFalse(GitSafety.isSafeBlobSpec("ta\tg:README.md"), "the revision half is still a ref name");
        assertFalse(GitSafety.isSafeBlobSpec("HEAD:nul\0name"), "NUL cannot be passed in an argument");
        assertFalse(GitSafety.isSafeBlobSpec("tag\nname"));
        assertFalse(GitSafety.isSafeBlobSpec(""));
        assertFalse(GitSafety.isSafeBlobSpec(null));
    }

    @Test
    void userCommandsKeepTheUsersLocaleAndPinOnlyTheMessageLanguage() {
        java.util.Map<String, String> plain = GitSafety.userEnv(java.util.Map.of("LANG", "es_MX.UTF-8"));
        assertEquals("C", plain.get("LC_MESSAGES"));
        assertEquals("C", plain.get("LANGUAGE"));
        assertFalse(plain.containsKey("LC_ALL"), "LC_ALL=C is what broke JVM hooks on non-ASCII paths");
        assertFalse(plain.containsKey("LC_CTYPE"));
        assertEquals("0", plain.get("GIT_TERMINAL_PROMPT"));

        // An inherited LC_ALL would override LC_MESSAGES: it is blanked and its charset kept in LC_CTYPE.
        java.util.Map<String, String> all = GitSafety.userEnv(java.util.Map.of("LC_ALL", "de_DE.UTF-8"));
        assertEquals("", all.get("LC_ALL"));
        assertEquals("de_DE.UTF-8", all.get("LC_CTYPE"));
        assertEquals("C", all.get("LC_MESSAGES"));
    }
}
