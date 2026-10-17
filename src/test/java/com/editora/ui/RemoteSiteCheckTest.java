package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteSiteCheckTest {

    @Test
    void hosts() {
        assertNull(RemoteSiteCheck.host(""));
        assertNull(RemoteSiteCheck.host("example.com"));
        assertNull(RemoteSiteCheck.host("10.0.0.7"));
        assertNull(RemoteSiteCheck.host("fe80::1"));
        assertEquals("settings.remote.check.hostSpaces", RemoteSiteCheck.host("my host"));
        assertEquals("settings.remote.check.hostUser", RemoteSiteCheck.host("me@example.com"));
        assertEquals("settings.remote.check.hostPort", RemoteSiteCheck.host("example.com:2222"));
        assertEquals("settings.remote.check.hostName", RemoteSiteCheck.host("sftp://example.com"));
        assertEquals("settings.remote.check.hostName", RemoteSiteCheck.host("example.com/srv"));
    }

    @Test
    void portsAndUsers() {
        assertNull(RemoteSiteCheck.port(""));
        assertNull(RemoteSiteCheck.port("22"));
        assertNull(RemoteSiteCheck.port("65535"));
        assertEquals("settings.remote.check.port", RemoteSiteCheck.port("0"));
        assertEquals("settings.remote.check.port", RemoteSiteCheck.port("70000"));
        assertEquals("settings.remote.check.port", RemoteSiteCheck.port("abc"));
        assertEquals("settings.remote.check.port", RemoteSiteCheck.port("-1"));
        assertNull(RemoteSiteCheck.user(""));
        assertNull(RemoteSiteCheck.user("deploy"));
        assertEquals("settings.remote.check.user", RemoteSiteCheck.user("me@example.com"));
        assertEquals("settings.remote.check.user", RemoteSiteCheck.user("my name"));
    }

    @Test
    void aLeadingTildeIsTheHomeFolder() {
        assertEquals("/home/me/.ssh/id_ed25519", RemoteSiteCheck.expandHome("~/.ssh/id_ed25519", "/home/me"));
        assertEquals("/home/me", RemoteSiteCheck.expandHome("~", "/home/me"));
        assertEquals("/keys/~id", RemoteSiteCheck.expandHome(" /keys/~id ", "/home/me"));
        assertEquals("", RemoteSiteCheck.expandHome(null, "/home/me"));
    }
}
