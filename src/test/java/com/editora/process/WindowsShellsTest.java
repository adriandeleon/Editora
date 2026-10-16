package com.editora.process;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run on Windows finds Git Bash, never the WSL launcher in System32. */
class WindowsShellsTest {

    private static final String WSL = "C:\\Windows\\System32\\bash.exe";

    @Test
    void gitBashBesideGitOnThePathWinsOverTheWslLauncher() {
        Set<String> files =
                Set.of(WSL, "C:\\Program Files\\Git\\cmd\\git.exe", "C:\\Program Files\\Git\\bin\\bash.exe");
        String found = WindowsShells.find(
                "bash", List.of("C:\\Windows\\System32", "C:\\Program Files\\Git\\cmd"), List.of(), files::contains);
        assertEquals("C:\\Program Files\\Git\\bin\\bash.exe", found);
    }

    @Test
    void aStandardInstallIsFoundWithNothingOfGitOnThePath() {
        Set<String> files = Set.of(WSL, "D:\\Apps\\Git\\usr\\bin\\sh.exe");
        assertEquals(
                "D:\\Apps\\Git\\usr\\bin\\sh.exe",
                WindowsShells.find(
                        "sh",
                        List.of("C:\\Windows\\System32"),
                        List.of("C:\\Program Files", "D:\\Apps"),
                        files::contains));
    }

    @Test
    void theWslLauncherIsNeverTaken() {
        assertNull(WindowsShells.find(
                "bash.exe",
                List.of("C:\\Windows\\System32\\", "C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps"),
                List.of(),
                Set.of(WSL, "C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\bash.exe")::contains));
        assertEquals(
                "C:\\msys64\\usr\\bin\\bash.exe",
                WindowsShells.find(
                        "bash",
                        List.of("C:\\Windows\\System32", "C:\\msys64\\usr\\bin"),
                        List.of(),
                        Set.of(WSL, "C:\\msys64\\usr\\bin\\bash.exe")::contains),
                "another bash on the PATH (MSYS2) is fine");
    }

    @Test
    void installRootsComeFromTheEnvironment() {
        Map<String, String> env = Map.of(
                "ProgramFiles",
                "C:\\Program Files",
                "ProgramW6432",
                "C:\\Program Files",
                "ProgramFiles(x86)",
                "C:\\Program Files (x86)",
                "LOCALAPPDATA",
                "C:\\Users\\u\\AppData\\Local");
        assertEquals(
                List.of("C:\\Program Files", "C:\\Program Files (x86)", "C:\\Users\\u\\AppData\\Local\\Programs"),
                WindowsShells.installRoots(env::get));
        assertTrue(WindowsShells.isPosixShell("BASH.EXE"));
        assertFalse(WindowsShells.isPosixShell("python"));
    }
}
