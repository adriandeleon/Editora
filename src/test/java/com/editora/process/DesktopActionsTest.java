package com.editora.process;

import java.nio.file.Path;
import java.util.List;

import com.editora.process.DesktopActions.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        // mac/linux exec their launcher directly (no shell), so the path is safe as an argv element.
        assertEquals(
                new DesktopActions.Command(List.of("open", "-a", "Terminal", DIR.toString()), null),
                DesktopActions.terminalCommand(Os.MAC, DIR));
        assertEquals(
                new DesktopActions.Command(List.of("x-terminal-emulator", "--working-directory=" + DIR), null),
                DesktopActions.terminalCommand(Os.LINUX, DIR));
        // Windows opens through cmd.exe, so the path is delivered as the working directory, not in the argv.
        assertEquals(
                new DesktopActions.Command(List.of("cmd", "/c", "start", "", "cmd", "/k"), DIR),
                DesktopActions.terminalCommand(Os.WINDOWS, DIR));
    }

    @Test
    void windowsTerminalNeverPutsTheFolderPathInAnyCmdArgument() {
        // A folder named with cmd metacharacters — all legal in a Windows folder name — must not be able to
        // run a command when the user picks "Open Terminal Here". The old `cmd /k "cd /d " + dir` interpolated
        // it into the cmd command line; the fix routes it through the working directory, which no shell parses.
        Path malicious = Path.of("C:\\repo & calc.exe & rem ");
        DesktopActions.Command cmd = DesktopActions.terminalCommand(Os.WINDOWS, malicious);

        assertEquals(malicious, cmd.workingDir(), "the folder is the child's working directory, verbatim");
        for (String arg : cmd.argv()) {
            assertFalse(
                    arg.contains("calc.exe") || arg.contains("&") || arg.contains(malicious.toString()),
                    "no cmd argument may carry any part of the folder path: " + arg);
        }
    }

    @Test
    void nonWindowsTerminalSetsNoWorkingDirectory() {
        // The path rides in the argv there (exec'd directly), so nothing needs the directory() treatment.
        assertNull(DesktopActions.terminalCommand(Os.MAC, DIR).workingDir());
        assertNull(DesktopActions.terminalCommand(Os.LINUX, DIR).workingDir());
    }

    @Test
    void containingDirIsParentForFilesAndSelfForDirs() {
        assertEquals(PARENT, DesktopActions.containingDir(FILE, false));
        assertEquals(DIR, DesktopActions.containingDir(DIR, true));
    }
}
