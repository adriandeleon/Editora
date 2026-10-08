package com.editora.sshconfig;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The option glosses and block summaries of the SSH config preview, and the parser's edges. */
class SshConfigDescribeTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "HostName|example.com|actual hostname: example.com",
                "Port|2222|port 2222",
                "User|deploy|log in as deploy",
                "IdentityFile|~/.ssh/id|private key: ~/.ssh/id",
                "ProxyJump|bastion|connect via jump host: bastion",
                "ProxyCommand|nc %h %p|connect via command: nc %h %p",
                "LocalForward|8080 localhost:80|local port forward: 8080 localhost:80",
                "RemoteForward|9090 localhost:90|remote port forward: 9090 localhost:90",
                "DynamicForward|1080|SOCKS proxy on: 1080",
                "ServerAliveInterval|30|send a keepalive every 30s",
                "ServerAliveCountMax|3|disconnect after 3 missed keepalives",
                "StrictHostKeyChecking|accept-new|strict host-key checking: accept-new",
                "UserKnownHostsFile|/dev/null|known-hosts file: /dev/null",
                "PreferredAuthentications|publickey|authentication order: publickey",
                "ControlMaster|auto|connection sharing: auto",
                "ControlPath|~/.ssh/cm-%C|control socket: ~/.ssh/cm-%C",
                "ControlPersist|10m|keep the master connection: 10m",
                "ConnectTimeout|5|5s connect timeout",
                "AddKeysToAgent|yes|add keys to the agent: yes",
                "LogLevel|VERBOSE|log verbosity: VERBOSE",
                "RequestTTY|force|TTY allocation: force",
                "SendEnv|LANG LC_*|send environment variables: LANG LC_*",
                "SetEnv|FOO=bar|set environment variables: FOO=bar",
                "Ciphers|aes256-ctr|ciphers: aes256-ctr",
                "MACs|hmac-sha2-256|MAC algorithms: hmac-sha2-256",
                "KexAlgorithms|curve25519-sha256|key-exchange algorithms: curve25519-sha256",
                "hostname|h|actual hostname: h",
            })
    void glossesValueOptions(String key, String value, String gloss) {
        assertEquals(gloss, SshConfigDescribe.gloss(key, value));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "IdentitiesOnly|only use the configured key(s)|may try other keys",
                "ForwardAgent|SSH agent forwarding on|agent forwarding off",
                "ForwardX11|X11 forwarding on|X11 forwarding off",
                "Compression|compression on|compression off",
                "PubkeyAuthentication|public-key auth on|public-key auth off",
                "PasswordAuthentication|password auth on|password auth off",
            })
    void glossesSwitchesBothWays(String key, String on, String off) {
        for (String yes : List.of("yes", "true", "on", "1", " Yes ", "TRUE")) {
            assertEquals(on, SshConfigDescribe.gloss(key, yes), yes);
        }
        for (String no : List.of("no", "false", "off", "0", "ask", "")) {
            assertEquals(off, SshConfigDescribe.gloss(key, no), no);
        }
    }

    @Test
    void anUnknownOptionHasNoGloss() {
        assertNull(SshConfigDescribe.gloss("GSSAPIAuthentication", "yes"));
    }

    @Test
    void summarisesTheGlobalAndMatchBlocks() {
        List<SshConfig.Block> blocks = SshConfig.parse("""
                ServerAliveInterval 60
                Match host *.corp exec "test -f /x"
                  User corp
                Host a
                  Port 22
                """);
        assertEquals(3, blocks.size());
        assertEquals("Applies to all hosts", SshConfigDescribe.summary(blocks, blocks.get(0)));
        assertEquals(
                "For connections matching host *.corp exec \"test -f /x\"",
                SshConfigDescribe.summary(blocks, blocks.get(1)));
        // The global block applies to host "a"; the Match block's User does not (criteria are not modelled).
        assertEquals("Connects to a on port 22", SshConfigDescribe.summary(blocks, blocks.get(2)));
        assertEquals("60", SshConfig.effective(blocks, "a", "ServerAliveInterval"));
        assertNull(SshConfig.effective(blocks, "a", "User"));
    }

    @Test
    void aWildcardBlockShowsItsOwnValuesOnly() {
        List<SshConfig.Block> blocks = SshConfig.parse("""
                Host *
                  Port 2200
                Host *.example.com !bad.example.com
                  User ops
                  IdentityFile ~/.ssh/ops
                """);
        // A class of hosts, not one connection: the earlier Host * port is not folded in.
        assertEquals(
                "Connects to *.example.com !bad.example.com as ops, key ~/.ssh/ops",
                SshConfigDescribe.summary(blocks, blocks.get(1)));
        assertEquals("Connects to * on port 2200", SshConfigDescribe.summary(blocks.get(0)));
    }

    @Test
    void aConcreteHostIsOneNameWithoutPatternCharacters() {
        assertTrue(SshConfig.isConcreteHost("web"));
        assertTrue(SshConfig.isConcreteHost("  web.example.com  "));
        assertFalse(SshConfig.isConcreteHost(null));
        assertFalse(SshConfig.isConcreteHost("   "));
        assertFalse(SshConfig.isConcreteHost("a b"));
        assertFalse(SshConfig.isConcreteHost("a\tb"));
        assertFalse(SshConfig.isConcreteHost("*.example.com"));
        assertFalse(SshConfig.isConcreteHost("web?"));
        assertFalse(SshConfig.isConcreteHost("!web"));
    }

    @Test
    void effectiveNeedsBlocksAndAHost() {
        List<SshConfig.Block> blocks = SshConfig.parse("Host a\n  User u\n");
        assertNull(SshConfig.effective(null, "a", "User"));
        assertNull(SshConfig.effective(blocks, null, "User"));
        assertEquals("u", SshConfig.effective(blocks, "a", "User"));
        assertNull(SshConfig.effective(blocks, "b", "User"));
    }

    @Test
    void parsingToleratesNothingCommentsAndOddLines() {
        assertEquals(List.of(), SshConfig.parse(null));
        assertEquals(List.of(), SshConfig.parse("# nothing here\n\n"));
        // A line that starts with a separator has no keyword and is dropped; a bare keyword has an empty value.
        List<SshConfig.Block> blocks =
                SshConfig.parse("match all\n  =orphan\n  Compression\n  User\t\"a b\"\n  Port \"\n");
        assertEquals(1, blocks.size());
        SshConfig.Block b = blocks.get(0);
        assertEquals("Match", b.type());
        assertEquals("all", b.argument());
        assertEquals(3, b.options().size());
        assertEquals("", b.first("Compression"));
        assertEquals("a b", b.first("user"));
        // A lone quote is not a quoted value.
        assertEquals("\"", b.first("Port"));
        assertEquals(5, b.options().get(2).line());
    }
}
