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

    @Test
    void aContainerOrDistributionChipSaysItsName() {
        assertEquals("web", TerminalCoordinator.labelOf("container:web"));
        assertEquals("Ubuntu-24.04", TerminalCoordinator.labelOf("wsl:Ubuntu-24.04"));
        assertEquals("plain", TerminalCoordinator.labelOf("plain"));
    }

    @Test
    void aRemoteShellsChildrenAreReadFromPs() {
        String ps = "    1 init\n  812 bash\n  900 /usr/bin/vim\n  900 sleep\n  901 ps\n 9000 other\n";
        assertEquals(java.util.List.of("vim", "sleep"), TerminalCoordinator.childrenOf(ps, 900));
        assertEquals(java.util.List.of(), TerminalCoordinator.childrenOf(ps, 42));
    }
}
