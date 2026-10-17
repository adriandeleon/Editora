package com.editora.vfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code ~/.ssh/config} with what MINA alone gets wrong: {@code Match} sections and {@code Include} lines. */
class SshConfigFileTest {

    @TempDir
    Path home;

    private Path ssh() throws Exception {
        return Files.createDirectories(home.resolve(".ssh"));
    }

    private static String hostName(SshConfigFile file, String host) throws Exception {
        HostConfigEntry e = file.resolveEffectiveHost(host, 0, null, "me", null, null);
        return e == null ? null : e.getHostName();
    }

    @Test
    void aMatchSectionIsSkippedAndTheRestStillApplies() throws Exception {
        Path config = Files.writeString(
                ssh().resolve("config"),
                "Host a\n  HostName 10.0.0.1\nMatch host b exec \"true\"\n  HostName 10.0.0.2\n"
                        + "Host c\n  HostName 10.0.0.3\n");

        SshConfigFile file = new SshConfigFile(config);

        // MINA's own resolver throws "Currently not able to process Match sections" for every host here.
        assertEquals("10.0.0.1", hostName(file, "a"));
        assertEquals(null, hostName(file, "b"), "nothing the Match section said applies: no entry for b");
        assertEquals("10.0.0.3", hostName(file, "c"), "a Host after the Match section is read again");
    }

    @Test
    void includedFilesAreReadInPlaceRelativeToSshAndFromHomeAndByGlob() throws Exception {
        Path conf = Files.createDirectories(ssh().resolve("conf.d"));
        Files.writeString(conf.resolve("10-work"), "Host work\n  HostName 10.1.0.1\n");
        Files.writeString(conf.resolve("20-play"), "Host play\n  HostName 10.2.0.1\n");
        Files.writeString(home.resolve("extra"), "Host extra\n  HostName 10.3.0.1\n");
        Path config = Files.writeString(
                ssh().resolve("config"), "Include conf.d/*\nInclude ~/extra missing\nHost x\n  HostName 1.1.1.1\n");

        SshConfigFile file = new SshConfigFile(config);

        assertEquals("10.1.0.1", hostName(file, "work"));
        assertEquals("10.2.0.1", hostName(file, "play"));
        assertEquals("10.3.0.1", hostName(file, "extra"));
        assertEquals("1.1.1.1", hostName(file, "x"));
    }

    @Test
    void aGlobInAnAbsoluteIncludeAndInAHomeFolderIsMatchedToo() throws Exception {
        Path conf = Files.createDirectories(home.resolve("hosts"));
        Files.writeString(conf.resolve("a.conf"), "Host a\n  HostName 10.4.0.1\n");
        Files.writeString(conf.resolve("b.conf"), "Host b\n  HostName 10.4.0.2\n");
        Path config = Files.writeString(
                ssh().resolve("config"), "Include " + conf.toAbsolutePath() + "/a.*\nInclude ~/hosts/b.co?f\n");

        SshConfigFile file = new SshConfigFile(config);

        assertEquals("10.4.0.1", hostName(file, "a"));
        assertEquals("10.4.0.2", hostName(file, "b"));
    }

    @Test
    void anIncludeThatNamesNoPathIsSkippedAndTheRestStillApplies() throws Exception {
        // NUL is no path anywhere; on Windows a glob character in a folder name is not one either. Before, the
        // InvalidPathException escaped and no host in the file resolved.
        Path config = Files.writeString(
                ssh().resolve("config"), "Include bad\u0000name\nInclude bad*/x\nHost x\n  HostName 1.1.1.1\n");

        assertEquals("1.1.1.1", hostName(new SshConfigFile(config), "x"));
    }

    @Test
    void anIncludedFileThatChangesIsReadAgain() throws Exception {
        Path part = Files.writeString(ssh().resolve("part"), "Host box\n  HostName 10.0.0.1\n");
        Path config = Files.writeString(ssh().resolve("config"), "Include part\n");
        SshConfigFile file = new SshConfigFile(config);
        assertEquals("10.0.0.1", hostName(file, "box"));

        Files.writeString(part, "Host box\n  HostName 10.9.9.9\n");
        Files.setLastModifiedTime(
                part, FileTime.fromMillis(Files.getLastModifiedTime(part).toMillis() + 5_000));

        assertEquals("10.9.9.9", hostName(file, "box"));
    }

    @Test
    void aFileThatIncludesItselfStopsInsteadOfLoopingForever() throws Exception {
        Path config = Files.writeString(ssh().resolve("config"), "Host box\n  HostName 10.0.0.1\nInclude config\n");

        assertEquals("10.0.0.1", hostName(new SshConfigFile(config), "box"));
    }

    @Test
    void noConfigAtAllResolvesNothing() throws Exception {
        assertEquals(null, hostName(new SshConfigFile(ssh().resolve("config")), "box"));
    }

    @Test
    void matchSectionsRunToTheNextHostLine() {
        assertEquals(
                "Host a\n  User x\nHost c\n  User z\n\n",
                SshConfigFile.withoutMatchSections("Host a\n  User x\nMatch all\n  User y\nHost c\n  User z\n"));
    }
}
