package com.editora.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.editora.git.QuietGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Which GitHub CLI account sync signs in as, with the real git and a stand-in gh. */
class SyncAccountTest {

    /** An https URL that the clone's own configuration rewrites to a local repository. */
    private static final String URL = "https://sync.test/me/editora-sync.git";

    @TempDir
    Path tmp;

    private Path clone;
    private QuietGit git;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(QuietGit.available(), "git is not installed");
        Path remote = tmp.resolve("remote.git");
        git(tmp, null, "init", "-q", "--bare", remote.toString());
        clone = Files.createDirectories(tmp.resolve("repo"));
        git(clone, null, "init", "-q");
        git(clone, null, "remote", "add", "origin", URL);
        git(clone, null, "config", "url." + remote.toUri() + ".insteadOf", URL);
        git = new QuietGit(clone, false);
    }

    private static String git(Path dir, String stdin, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        Process p = pb.start();
        if (stdin != null) {
            p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return out;
    }

    private List<String> logins(String host, String... logins) {
        asked.add(host);
        return List.of(logins);
    }

    private String stored() throws Exception {
        return git(clone, null, "config", "--local", "--get", SyncAccount.KEY).strip();
    }

    @Test
    void onlyAnHttpsRepositoryHasAHost() {
        assertEquals("github.com", SyncAccount.httpsHost(" https://GitHub.com/me/editora-sync.git "));
        assertEquals("ghe.corp.example", SyncAccount.httpsHost("https://me@ghe.corp.example:8443/me/sync"));
        assertEquals("", SyncAccount.httpsHost("git@github.com:me/editora-sync.git"));
        assertEquals("", SyncAccount.httpsHost("ssh://git@github.com/me/editora-sync.git"));
        assertEquals("", SyncAccount.httpsHost("/srv/git/sync.git"));
        assertEquals("", SyncAccount.httpsHost("https://exa mple.com/x"));
        assertEquals("", SyncAccount.httpsHost(null));
    }

    @Test
    void theHelperReplacesTheUsersOwnForThatHostOnly() {
        List<String> config = SyncAccount.credentialConfig(List.of("/opt/git hub/gh"), "github.com", "me");

        assertEquals(4, config.size());
        assertEquals(List.of("-c", "credential.https://github.com.helper="), config.subList(0, 2));
        assertTrue(config.get(3).startsWith("credential.https://github.com.helper=!"), config.get(3));
        assertTrue(
                config.get(3).contains("'/opt/git hub/gh' auth token --hostname github.com --user me)"), config.get(3));
        assertFalse(config.get(3).contains("\""), "a double quote would not survive a Windows command line");
    }

    @Test
    void nothingThatCouldEscapeTheShellSnippetIsWrittenIntoIt() {
        assertEquals(List.of(), SyncAccount.credentialConfig(List.of("gh"), "github.com", "me; rm -rf ~"));
        assertEquals(List.of(), SyncAccount.credentialConfig(List.of("gh"), "github.com", "-me"));
        assertEquals(List.of(), SyncAccount.credentialConfig(List.of("gh"), "github.com", ""));
        assertEquals(List.of(), SyncAccount.credentialConfig(List.of("gh"), "git hub.com", "me"));
        assertEquals(List.of(), SyncAccount.credentialConfig(List.of("it's/gh"), "github.com", "me"));
        assertEquals(List.of(), SyncAccount.credentialConfig(List.of(), "github.com", "me"));
    }

    /** The point of it all: git gets the named account's token, not the active account's. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void gitIsHandedTheNamedAccountsTokenWhicheverAccountIsActive() throws Exception {
        Path gh = tmp.resolve("fake bin").resolve("gh");
        Files.createDirectories(gh.getParent());
        Files.writeString(gh, """
                #!/bin/sh
                # gh auth token --hostname <host> --user <login>
                if [ "$1 $2 $6" = "auth token me" ]; then echo token-of-me; exit 0; fi
                echo "no oauth token found for $4 account $6" >&2
                exit 1
                """);
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwxr-xr-x"));
        String ask = "protocol=https\nhost=github.com\n\n";

        List<String> me = new ArrayList<>(SyncAccount.credentialConfig(List.of(gh.toString()), "github.com", "me"));
        me.addAll(List.of(
                "-c", "credential.helper=!echo username=active; echo password=token-of-active", "credential", "fill"));
        String filled = git(clone, ask, me.toArray(String[]::new));
        assertTrue(filled.contains("username=me") && filled.contains("password=token-of-me"), filled);

        List<String> gone = new ArrayList<>(SyncAccount.credentialConfig(List.of(gh.toString()), "github.com", "gone"));
        gone.addAll(List.of("credential", "fill"));
        String refused = git(clone, ask, gone.toArray(String[]::new));
        assertFalse(refused.contains("password="), refused);

        List<String> elsewhere =
                new ArrayList<>(SyncAccount.credentialConfig(List.of(gh.toString()), "github.com", "me"));
        elsewhere.addAll(List.of("credential", "fill"));
        String other = git(clone, "protocol=https\nhost=example.com\n\n", elsewhere.toArray(String[]::new));
        assertFalse(other.contains("token-of-me"), "the token goes to its own host only: " + other);
    }

    @Test
    void theFirstAccountThatCanReadTheRepositoryIsRemembered() throws Exception {
        String login =
                SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host, "me-at-work", "me"), false, true);

        assertEquals("me-at-work", login, "the active account is tried first");
        assertEquals("me-at-work", stored());
        assertEquals(List.of("sync.test"), asked);
        assertEquals("me-at-work", SyncAccount.stored(git, URL));
    }

    @Test
    void aRememberedAccountIsUsedWithoutAskingAgain() throws Exception {
        git(clone, null, "config", "--local", SyncAccount.KEY, "me");

        assertEquals("me", SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host, "work"), false, true));
        assertEquals(List.of(), asked, "no gh call, no network");
    }

    @Test
    void afterAFailureTheRememberedAccountIsTriedBeforeTheActiveOne() throws Exception {
        git(clone, null, "config", "--local", SyncAccount.KEY, "me");

        assertEquals("me", SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host, "work", "me"), true, true));
        assertEquals("me", stored());
    }

    @Test
    void anAccountGhNoLongerHasIsReplaced() throws Exception {
        git(clone, null, "config", "--local", SyncAccount.KEY, "left-the-company");

        assertEquals("me", SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host, "me"), true, true));
        assertEquals("me", stored());
    }

    @Test
    void anUnreachableRepositoryChangesNothing() throws Exception {
        git(clone, null, "config", "--local", SyncAccount.KEY, "me");
        git(clone, null, "config", "--unset", "url." + tmp.resolve("remote.git").toUri() + ".insteadOf");
        git(clone, null, "config", "url." + tmp.resolve("nowhere.git").toUri() + ".insteadOf", URL);

        assertEquals("me", SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host, "work", "me"), true, true));
        assertEquals("me", stored());
        assertEquals(
                "",
                SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host, "work"), true, true),
                "gh lost it");
        assertEquals("", stored());
    }

    @Test
    void withoutGhOrOffHttpsSignInIsLeftToGit() throws Exception {
        assertEquals("", SyncAccount.choose(git, List.of("gh"), URL, host -> logins(host), false, true));
        assertEquals("", stored());

        String ssh = "git@github.com:me/editora-sync.git";
        assertEquals("", SyncAccount.choose(git, List.of("gh"), ssh, host -> logins(host, "me"), false, true));
        assertEquals(List.of("sync.test"), asked, "an SSH repository never asks gh");
    }

    @Test
    void anAccountRememberedForAnotherRepositoryIsNotUsed() throws Exception {
        git(clone, null, "config", "--local", SyncAccount.KEY, "me");

        assertEquals("", SyncAccount.stored(git, "https://sync.test/me/another.git"));
        assertEquals("", SyncAccount.stored(new QuietGit(tmp.resolve("no-clone-yet"), false), URL));
    }
}
