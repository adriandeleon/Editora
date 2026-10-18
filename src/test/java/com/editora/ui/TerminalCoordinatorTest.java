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

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void withoutPsTheProgramsAreReadFromProc() throws Exception {
        Process child = new ProcessBuilder("sleep", "30").start();
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", TerminalCoordinator.PROGRAMS_SCRIPT);
            pb.environment().put("PATH", "/nonexistent"); // no ps
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            org.junit.jupiter.api.Assertions.assertTrue(
                    TerminalCoordinator.childrenOf(out, ProcessHandle.current().pid()).contains("sleep"), out);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void theTerminalIsInTheMenusAndTheDefaultKeymapHasAKeyForIt() {
        // A default install (Emacs keymap) offered only the stripe and the palette.
        var ids = MenuBarModel.allCommandIds();
        org.junit.jupiter.api.Assertions.assertTrue(
                ids.containsAll(java.util.List.of("tool.terminal", "terminal.new", "file.openTerminal")), ids.toString());
        org.junit.jupiter.api.Assertions.assertEquals(
                "tool.terminal", com.editora.command.KeymapManager.defaults("emacs", false).get("M-0"));
        org.junit.jupiter.api.Assertions.assertTrue(com.editora.terminal.TerminalKeys.reachesEditora(
                "M-0", "tool.terminal", com.editora.terminal.TerminalKeys.DEFAULT_IDE_COMMANDS, false),
                "and it works from inside the terminal, to hide it again");
    }
}
