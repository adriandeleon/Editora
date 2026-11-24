package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** G1/M2: the host behind an SSH remote's alias or SSH-only endpoint. */
class SshHostsTest {

    @Test
    void sshOnlyEndpointsAreTheirSites() {
        assertEquals("github.com", SshHosts.canonical("SSH.GitHub.com"));
        assertEquals("gitlab.com", SshHosts.canonical("altssh.gitlab.com"));
        assertEquals("bitbucket.org", SshHosts.canonical("altssh.bitbucket.org"));
        assertEquals("git.corp.example", SshHosts.canonical("git.corp.example"));
    }

    @Test
    void theHostnameLineOfSshG() {
        String out = "user git\nhostname ssh.github.com\nport 443\nidentityfile ~/.ssh/work\n";
        assertEquals("ssh.github.com", SshHosts.hostnameOf(out));
        assertEquals("github.com", SshHosts.hostnameOf("HostName GitHub.com\r\nport 22"));
        assertNull(SshHosts.hostnameOf("port 22\n"));
        assertNull(SshHosts.hostnameOf(null));
    }

    @Test
    void theConfigIsReadWhenSshCannotBeRun() {
        String config = """
                # work account
                Host github-work github.com-work
                    HostName github.com
                    IdentityFile ~/.ssh/work

                Host *.corp !build.corp
                  HostName=%h.example.com

                Host *
                  HostName fallback.example
                """;
        assertEquals("github.com", SshHosts.fromConfig(config, "github-work"));
        assertEquals("github.com", SshHosts.fromConfig(config, "github.com-work"));
        assertEquals("git.corp.example.com", SshHosts.fromConfig(config, "git.corp"));
        assertEquals("fallback.example", SshHosts.fromConfig(config, "build.corp"), "a negated pattern does not match");
        assertNull(SshHosts.fromConfig("Host other\n  HostName x\n", "github-work"));
    }

    @Test
    void onlySshRemotesHaveTheirHostResolved() {
        java.util.function.UnaryOperator<String> work = h -> h.equals("github-work") ? "github.com" : h;
        assertEquals("git@github.com:o/r.git", SshHosts.resolveUrl("git@github-work:o/r.git", work));
        assertEquals("ssh://git@github.com:22/o/r.git", SshHosts.resolveUrl("ssh://git@github-work:22/o/r.git", work));
        assertEquals("https://github-work/o/r.git", SshHosts.resolveUrl("https://github-work/o/r.git", work));
        assertEquals("/srv/repo.git", SshHosts.resolveUrl("/srv/repo.git", work));
        assertEquals("C:\\repo", SshHosts.resolveUrl("C:\\repo", work));
    }

    /** R3-G3: ssh matches Host patterns case-sensitively, so the alias is matched as it is written. */
    @Test
    void theConfigIsMatchedWithTheAliasAsWritten() {
        String config = """
                Host GitHub-Work
                  HostName GitHub.com
                  Port 2222
                """;
        assertEquals("github.com", SshHosts.fromConfig(config, "GitHub-Work"), "the value is lower-cased");
        assertNull(SshHosts.fromConfig(config, "github-work"), "another case is another alias");
        assertEquals("2222", SshHosts.fromConfig(config, "GitHub-Work", "port"));
    }

    /** R3-G7: ssh -G's port line, for a Bitbucket Server whose 7999 is only in ~/.ssh/config. */
    @Test
    void thePortLineOfSshG() {
        assertEquals("7999", SshHosts.portOf("hostname git.corp.example\nport 7999\n"));
        assertNull(SshHosts.portOf("hostname x\n"));
        assertNull(SshHosts.portOf(null));
    }

    /** R3-G3/G5: a name that is no alias is never handed to ssh, and comes back canonical. */
    @Test
    void aNameSshWouldReadAsAnOptionIsNotLookedUp() {
        assertEquals("-oproxycommand=x", SshHosts.resolve("-oProxyCommand=x"));
        assertEquals("", SshHosts.lookup(null, " ").host());
    }

    /** R4-G5: the paths of Include lines, several to a line, quoted or not. */
    @Test
    void includeLinesAreRead() {
        assertEquals(
                java.util.List.of("config.d/*", "~/work config", "other"),
                SshHosts.includes(
                        "Host a\n  HostName b\nInclude config.d/*\ninclude \"~/work config\" other\n#Include x\n"));
    }

    /** R4-G5: a change to a file pulled in by Include (here through a glob) is a change of the config. */
    @Test
    void aChangedIncludedFileChangesTheFingerprint(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
            throws Exception {
        java.nio.file.Path ssh = java.nio.file.Files.createDirectories(home.resolve(".ssh/config.d"))
                .getParent();
        java.nio.file.Files.writeString(ssh.resolve("config"), "Include config.d/*\nHost x\n");
        java.nio.file.Path work = java.nio.file.Files.writeString(ssh.resolve("config.d/work"), "Host w\n");
        String before = SshHosts.configFingerprint(home);
        assertEquals(before, SshHosts.configFingerprint(home), "nothing changed");

        java.nio.file.Files.setLastModifiedTime(
                work,
                java.nio.file.attribute.FileTime.fromMillis(
                        java.nio.file.Files.getLastModifiedTime(work).toMillis() + 5_000));
        String touched = SshHosts.configFingerprint(home);
        org.junit.jupiter.api.Assertions.assertNotEquals(before, touched, "an included file changed");

        java.nio.file.Files.writeString(ssh.resolve("config.d/more"), "Host m\n");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                touched, SshHosts.configFingerprint(home), "a file the glob now matches");
    }
}
