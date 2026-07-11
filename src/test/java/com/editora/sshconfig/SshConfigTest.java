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
}
