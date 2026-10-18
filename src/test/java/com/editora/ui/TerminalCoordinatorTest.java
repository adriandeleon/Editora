package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a remote terminal's chip calls its host. */
class TerminalCoordinatorTest {

    @Test
    void theChipNamesTheHostWithoutUserOrPort() {
        assertEquals("build.example.com", TerminalCoordinator.hostOf("ana@build.example.com:22"));
        assertEquals("box", TerminalCoordinator.hostOf("box:2222"));
        assertEquals("box", TerminalCoordinator.hostOf("box"));
        assertEquals("::1", TerminalCoordinator.hostOf("ana@[::1]:2222"));
        assertEquals("fe80::1", TerminalCoordinator.hostOf("fe80::1"), "a bare IPv6 address has no port to cut");
    }
}
