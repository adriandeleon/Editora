package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertEquals(
                    List.of(
                            "--no-ext-diff",
                            "--no-textconv",
                            "--no-color",
                            "--src-prefix=a/",
                            "--dst-prefix=b/",
                            "HEAD"),
                    argv.subList(at + 1, argv.size()),
                    command);
        }
        List<String> blame = GitSafety.backgroundArgv(List.of("git"), "blame", "--line-porcelain", "--", "f");
        assertEquals("--no-textconv", blame.get(blame.indexOf("blame") + 1));
        // A global option before the subcommand does not hide it, and an unrelated command gains nothing.
        List<String> listing = GitSafety.backgroundArgv(List.of("git"), "--literal-pathspecs", "ls-files", "-s");
        assertFalse(listing.contains("--no-ext-diff"));
        List<String> literal = GitSafety.backgroundArgv(List.of("git"), "--literal-pathspecs", "diff", "--cached");
        assertEquals("--no-ext-diff", literal.get(literal.indexOf("diff") + 1));
    }

    /**
     * A {@code -c key=value} before the subcommand is a global option and its value: the value used to be taken
     * for the subcommand, so the Changes card's {@code -c core.quotePath=false diff} ran without
     * {@code --no-textconv} and a repository's textconv program ran.
     */
    @Test
    void theValueOfAGlobalOptionIsNotTheSubcommand() {
        List<String> argv = GitSafety.backgroundArgv(
                List.of("git"), "--literal-pathspecs", "-c", "core.quotePath=false", "diff", "--no-color", "HEAD");
        int diff = argv.indexOf("diff");
        assertEquals(
                List.of("--no-ext-diff", "--no-textconv", "--no-color", "--src-prefix=a/", "--dst-prefix=b/"),
                argv.subList(diff + 1, diff + 6));
        assertEquals("core.quotePath=false", argv.get(diff - 1));
        List<String> log = GitSafety.backgroundArgv(List.of("git"), "-C", "sub", "log", "--name-status");
        assertEquals("--no-ext-diff", log.get(log.indexOf("log") + 1));
        assertEquals(3, GitSafety.subcommandIndex("-c", "a=b", "--literal-pathspecs", "show"));
        assertEquals(2, GitSafety.subcommandIndex("--git-dir", "x", "status"));
        assertEquals(-1, GitSafety.subcommandIndex("-c", "a=b"));
    }

    /** A clone, fetch, pull or push the user asked for cannot stop on an ssh question nobody can answer. */
    @Test
    void userNetworkCommandsPutSshInBatchModeUnlessTheUserHasTheirOwnSsh() {
        java.util.Map<String, String> base = GitSafety.userEnv(java.util.Map.of());
        assertEquals(
                "ssh -o BatchMode=yes",
                GitSafety.userNetworkEnv(base, java.util.Map.of(), false).get("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.userNetworkEnv(base, java.util.Map.of(), true).containsKey("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.userNetworkEnv(base, java.util.Map.of("GIT_SSH_COMMAND", "my-ssh"), false)
                .containsKey("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.userNetworkEnv(base, java.util.Map.of("GIT_SSH", "plink"), false)
                .containsKey("GIT_SSH_COMMAND"));
        // The user's settings stay: no editor, no terminal prompt, and gpg is not pointed at a terminal.
        java.util.Map<String, String> env = GitSafety.userNetworkEnv(base, java.util.Map.of(), false);
        assertEquals(":", env.get("GIT_EDITOR"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertTrue(env.containsKey("GPG_TTY") && env.get("GPG_TTY") == null, "GPG_TTY is removed");
    }

    /** R3-E4: batch mode would also switch off a graphical askpass, so where one can ask, ssh uses it. */
    @Test
    void aUsableGraphicalAskpassReplacesBatchMode() {
        java.util.Map<String, String> base = GitSafety.userEnv(java.util.Map.of());
        java.util.Map<String, String> x11 = java.util.Map.of("SSH_ASKPASS", "/usr/bin/ksshaskpass", "DISPLAY", ":0");
        java.util.Map<String, String> env = GitSafety.userNetworkEnv(base, x11, false, GitSafety.BATCH_SSH, true);
        assertFalse(env.containsKey("GIT_SSH_COMMAND"));
        assertEquals("force", env.get("SSH_ASKPASS_REQUIRE"));
        assertTrue(GitSafety.guiAskpassUsable(
                java.util.Map.of("SSH_ASKPASS", "/usr/bin/ksshaskpass", "WAYLAND_DISPLAY", "wayland-0"), true));
        // Linux without a display, a blank askpass, or one the user forbade: batch mode as before.
        java.util.Map<String, String> headless = java.util.Map.of("SSH_ASKPASS", "/usr/bin/ksshaskpass");
        assertFalse(GitSafety.guiAskpassUsable(headless, true));
        assertEquals(
                GitSafety.BATCH_SSH,
                GitSafety.userNetworkEnv(base, headless, false, GitSafety.BATCH_SSH, true)
                        .get("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.guiAskpassUsable(java.util.Map.of("SSH_ASKPASS", " ", "DISPLAY", ":0"), true));
        assertFalse(GitSafety.guiAskpassUsable(
                java.util.Map.of("SSH_ASKPASS", "/a", "DISPLAY", ":0", "SSH_ASKPASS_REQUIRE", "never"), true));
        assertFalse(GitSafety.guiAskpassUsable(java.util.Map.of("DISPLAY", ":0"), true));
        // macOS and Windows: a set SSH_ASKPASS is enough; unset keeps batch mode.
        assertTrue(GitSafety.guiAskpassUsable(headless, false));
        assertEquals(
                GitSafety.BATCH_SSH,
                GitSafety.userNetworkEnv(base, java.util.Map.of(), false, GitSafety.BATCH_SSH, false)
                        .get("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.userNetworkEnv(base, java.util.Map.of(), false, GitSafety.BATCH_SSH, false)
                .containsKey("SSH_ASKPASS_REQUIRE"));
    }

    /** R3-E5: on Windows only Git for Windows' own ssh is put in batch mode, by its path; else none. */
    @Test
    void onWindowsBatchModeNamesGitsOwnSsh() {
        assertEquals(GitSafety.BATCH_SSH, GitSafety.batchSshCommand(false, null, path -> false));
        java.util.Set<String> files = java.util.Set.of("C:/Program Files/Git/usr/bin/ssh.exe");
        assertEquals(
                "\"C:/Program Files/Git/usr/bin/ssh.exe\" -o BatchMode=yes",
                GitSafety.batchSshCommand(
                        true, "C:\\Program Files\\Git\\mingw64\\libexec\\git-core\n", files::contains));
        assertEquals(
                "\"C:/Program Files/Git/usr/bin/ssh.exe\" -o BatchMode=yes",
                GitSafety.batchSshCommand(true, "C:/Program Files/Git/libexec/git-core/", files::contains));
        assertNull(GitSafety.batchSshCommand(true, "C:/Other/git/cmd", files::contains), "not found: none");
        assertNull(GitSafety.batchSshCommand(true, null, files::contains));
        java.util.Map<String, String> base = GitSafety.userEnv(java.util.Map.of());
        assertFalse(GitSafety.userNetworkEnv(base, java.util.Map.of(), false, null, false)
                .containsKey("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.autoFetchEnv(java.util.Map.of(), false, null).containsKey("GIT_SSH_COMMAND"));
        assertEquals(
                "never", GitSafety.autoFetchEnv(java.util.Map.of(), false, null).get("SSH_ASKPASS_REQUIRE"));
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
        // No terminal, so no editor: rebase --continue and a merge commit keep git's prepared message
        // instead of starting $EDITOR and waiting on it for the whole mutation ceiling.
        assertEquals(":", plain.get("GIT_EDITOR"));

        // An inherited LC_ALL would override LC_MESSAGES: it is blanked and its charset kept in LC_CTYPE.
        java.util.Map<String, String> all = GitSafety.userEnv(java.util.Map.of("LC_ALL", "de_DE.UTF-8"));
        assertEquals("", all.get("LC_ALL"));
        assertEquals("de_DE.UTF-8", all.get("LC_CTYPE"));
        assertEquals("C", all.get("LC_MESSAGES"));
    }

    @Test
    void everyGitChildLosesTheRepositoryBindingVariables() {
        // E10: started from a shell that exports GIT_DIR / GIT_WORK_TREE (bare-repository dotfiles, a hook's
        // shell), every folder resolved to that one repository. A null value tells the process runner to
        // remove the variable; both environments carry one for each name git itself calls repository-local.
        java.util.Map<String, String> user =
                GitSafety.userEnv(java.util.Map.of("GIT_DIR", "/elsewhere/.git", "LANG", "C"));
        for (java.util.Map<String, String> env : List.of(GitSafety.BACKGROUND_ENV, user)) {
            for (String name : List.of(
                    "GIT_DIR",
                    "GIT_WORK_TREE",
                    "GIT_INDEX_FILE",
                    "GIT_OBJECT_DIRECTORY",
                    "GIT_COMMON_DIR",
                    "GIT_CONFIG_PARAMETERS",
                    "GIT_CONFIG_COUNT",
                    "GIT_PREFIX")) {
                assertTrue(env.containsKey(name), name);
                assertEquals(null, env.get(name), name);
            }
            assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        }
        // What the user set on purpose for their git is not Editora's to remove.
        assertFalse(GitSafety.BACKGROUND_ENV.containsKey("GIT_SSH_COMMAND"));
        assertFalse(GitSafety.BACKGROUND_ENV.containsKey("GIT_AUTHOR_NAME"));
    }
}
