package com.editora.process;

import java.nio.file.Path;
import java.util.List;

import com.editora.process.DesktopActions.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopActionsTest {

    private static final Path FILE = Path.of("/home/me/project/src/Main.java");
    private static final Path DIR = Path.of("/home/me/project/src");
    private static final Path PARENT = Path.of("/home/me/project/src");

    @Test
    void revealFileSelectsInFinderAndExplorer() {
        assertEquals(List.of("open", "-R", FILE.toString()), DesktopActions.revealArgv(Os.MAC, FILE, false, PARENT));
        assertEquals(
                List.of("explorer", "/select," + FILE), DesktopActions.revealArgv(Os.WINDOWS, FILE, false, PARENT));
        // Linux can't select a file — it opens the containing folder.
        assertEquals(List.of("xdg-open", PARENT.toString()), DesktopActions.revealArgv(Os.LINUX, FILE, false, PARENT));
    }

    @Test
    void revealDirectoryOpensTheDirectory() {
        assertEquals(List.of("open", "-R", DIR.toString()), DesktopActions.revealArgv(Os.MAC, DIR, true, DIR));
        assertEquals(List.of("explorer", DIR.toString()), DesktopActions.revealArgv(Os.WINDOWS, DIR, true, DIR));
        assertEquals(List.of("xdg-open", DIR.toString()), DesktopActions.revealArgv(Os.LINUX, DIR, true, DIR));
    }

    @Test
    void terminalOpensAtDirectory() {
        assertEquals(List.of("open", "-a", "Terminal", DIR.toString()), DesktopActions.terminalArgv(Os.MAC, DIR));
        assertEquals(
                List.of("cmd", "/c", "start", "cmd", "/k", "cd /d " + DIR),
                DesktopActions.terminalArgv(Os.WINDOWS, DIR));
        assertEquals(
                List.of("x-terminal-emulator", "--working-directory=" + DIR),
                DesktopActions.terminalArgv(Os.LINUX, DIR));
    }

    @Test
    void containingDirIsParentForFilesAndSelfForDirs() {
        assertEquals(PARENT, DesktopActions.containingDir(FILE, false));
        assertEquals(DIR, DesktopActions.containingDir(DIR, true));
    }
}
