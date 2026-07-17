package com.editora.sshconfig;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for SSH config parsing + decoding. */
class SshConfigTest {

    @Test
    void parsesBlocksAndGlobal() {
        String text = """
                # global defaults
                ForwardAgent no
                ServerAliveInterval 60

                Host web prod
                    HostName example.com
                    Port 2222
                    User deploy
                    IdentityFile ~/.ssh/id_ed25519
                    ProxyJump bastion

                Host *.internal
                    User admin
                """;
        List<SshConfig.Block> blocks = SshConfig.parse(text);
        assertEquals(3, blocks.size());
        assertEquals("global", blocks.get(0).type());
        assertEquals("no", blocks.get(0).first("ForwardAgent"));

        SshConfig.Block web = blocks.get(1);
        assertEquals("Host", web.type());
        assertEquals("web prod", web.argument());
        assertEquals(
                "Connects to example.com on port 2222 as deploy, key ~/.ssh/id_ed25519, via jump host bastion",
                SshConfigDescribe.summary(web));
    }

    @Test
    void handlesEqualsSeparatorAndQuotes() {
        List<SshConfig.Block> blocks =
                SshConfig.parse("Host x\n  HostName=host.example\n  ProxyCommand=\"nc %h %p\"\n");
        SshConfig.Block b = blocks.get(0);
        assertEquals("host.example", b.first("HostName"));
        assertEquals("nc %h %p", b.first("ProxyCommand"));
    }

    @Test
    void glossesNotableOptions() {
        assertEquals("SSH agent forwarding on", SshConfigDescribe.gloss("ForwardAgent", "yes"));
        assertEquals("agent forwarding off", SshConfigDescribe.gloss("ForwardAgent", "no"));
        assertEquals("send a keepalive every 60s", SshConfigDescribe.gloss("ServerAliveInterval", "60"));
        assertEquals("connect via jump host: bastion", SshConfigDescribe.gloss("ProxyJump", "bastion"));
        assertNull(SshConfigDescribe.gloss("SomeUnknownOption", "x"));
    }

    @Test
    void globalBlockOmittedWhenEmpty() {
        List<SshConfig.Block> blocks = SshConfig.parse("Host a\n  User u\n");
        assertEquals(1, blocks.size());
        assertEquals("Host", blocks.get(0).type());
        assertTrue(SshConfigDescribe.summary(blocks.get(0)).startsWith("Connects to a"));
    }

    // --- cross-block first-value-wins precedence (#476), verified against `ssh -G` ------------------

    @Test
    void anEarlierWildcardBlockOverridesAConcreteBlocksUserAndPort() {
        // ssh -G myserver: user=globaluser, hostname=example.com, port=2222 (Host * comes first, wins).
        List<SshConfig.Block> blocks = SshConfig.parse("""
                Host *
                  User globaluser
                  Port 2222
                Host myserver
                  HostName example.com
                  User realuser
                  Port 22
                """);
        SshConfig.Block myserver = blocks.get(blocks.size() - 1);
        assertEquals("myserver", myserver.argument());
        assertEquals("Connects to example.com on port 2222 as globaluser", SshConfigDescribe.summary(blocks, myserver));
        // The resolver itself, key by key.
        assertEquals("2222", SshConfig.effective(blocks, "myserver", "Port"));
        assertEquals("globaluser", SshConfig.effective(blocks, "myserver", "User"));
        assertEquals("example.com", SshConfig.effective(blocks, "myserver", "HostName"));
    }

    @Test
    void anEarlierWildcardDomainBlockOverridesAConcreteBlocksUser() {
        // ssh -G web.example.com: user=wilduser, hostname=10.0.0.5.
        List<SshConfig.Block> blocks = SshConfig.parse("""
                Host *.example.com
                  User wilduser
                Host web.example.com
                  HostName 10.0.0.5
                  User webuser
                """);
        SshConfig.Block web = blocks.get(blocks.size() - 1);
        assertEquals("Connects to 10.0.0.5 as wilduser", SshConfigDescribe.summary(blocks, web));
    }

    @Test
    void aConcreteBlocksOwnValuesWinWhenNoEarlierBlockSetsThem() {
        List<SshConfig.Block> blocks = SshConfig.parse("""
                Host myserver
                  HostName example.com
                  User realuser
                  Port 22
                Host *
                  User globaluser
                """);
        // The concrete block comes first, so its own User wins (the later Host * can't override).
        SshConfig.Block myserver = blocks.get(0);
        assertEquals("Connects to example.com on port 22 as realuser", SshConfigDescribe.summary(blocks, myserver));
    }

    @Test
    void aWildcardBlockShowsItsOwnDeclaredValues() {
        // A pattern block describes a class of hosts, not one connection — show its own values, not a resolve.
        List<SshConfig.Block> blocks = SshConfig.parse("""
                Host *
                  User globaluser
                Host *.internal
                  User admin
                """);
        SshConfig.Block internal = blocks.get(blocks.size() - 1);
        assertTrue(SshConfig.isConcreteHost("myserver"));
        assertTrue(!SshConfig.isConcreteHost("*.internal"));
        assertEquals("Connects to *.internal as admin", SshConfigDescribe.summary(blocks, internal));
    }
}
