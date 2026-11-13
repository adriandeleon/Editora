package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows Recycle Bin. What it decides (which drives it takes, what it hands the shell, how it reads the
 * shell's answer) is tested on every OS with the shell faked; the shell call itself runs on Windows only.
 */
class WindowsRecycleBinTest {

    @TempDir
    Path dir;

    private final List<String> recycled = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();

    private WindowsRecycleBin bin(int shellAnswers, int driveType) {
        return new WindowsRecycleBin(
                path -> {
                    recycled.add(path);
                    return shellAnswers;
                },
                root -> {
                    asked.add(root);
                    return driveType;
                });
    }

    @Test
    void aFileOnAFixedDriveGoesToTheShellByItsAbsolutePath() throws Exception {
        Path file = Files.writeString(dir.resolve("f.txt"), "x");
        WindowsRecycleBin bin = bin(0, WindowsRecycleBin.DRIVE_FIXED);
        assertTrue(bin.accepts(file));
        bin.trash(file);
        assertEquals(List.of(file.toAbsolutePath().normalize().toString()), recycled);
    }

    @Test
    void onlyAFixedDriveHasARecycleBin() throws Exception {
        Path file = Files.writeString(dir.resolve("f.txt"), "x");
        for (int other : new int[] {0, 1, 2, 4, 5, 6}) { // unknown, no root, removable, network, CD, RAM disk
            WindowsRecycleBin bin = bin(0, other);
            assertFalse(bin.accepts(file), "drive type " + other);
            assertThrows(IOException.class, () -> bin.trash(file));
        }
        assertTrue(recycled.isEmpty(), "a file the bin cannot take never reaches the shell");
        assertTrue(Files.exists(file), "and stays where it was");
    }

    @Test
    void theDriveIsAskedOncePerRoot() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "x");
        Path b = Files.writeString(dir.resolve("b.txt"), "x");
        WindowsRecycleBin bin = bin(0, WindowsRecycleBin.DRIVE_FIXED);
        assertTrue(bin.accepts(a));
        assertTrue(bin.accepts(b));
        assertTrue(bin.accepts(a));
        assertEquals(1, asked.size(), "accepts runs before every confirmation: it must stay cheap");
    }

    @Test
    void aRefusalOrAnAnswerOfNoIsAnErrorNeverASilentDelete() throws Exception {
        Path file = Files.writeString(dir.resolve("f.txt"), "x");
        IOException refused = assertThrows(
                IOException.class, () -> bin(120, WindowsRecycleBin.DRIVE_FIXED).trash(file));
        assertTrue(refused.getMessage().contains("120"), refused.getMessage());
        assertThrows(
                IOException.class, () -> bin(-1, WindowsRecycleBin.DRIVE_FIXED).trash(file));
        assertTrue(Files.exists(file));
    }

    @Test
    void aFileThatIsNotThereIsNotAccepted() {
        Path missing = dir.resolve("missing.txt");
        WindowsRecycleBin bin = bin(0, WindowsRecycleBin.DRIVE_FIXED);
        assertFalse(bin.accepts(missing));
        assertThrows(IOException.class, () -> bin.trash(missing));
        assertTrue(recycled.isEmpty());
    }

    @Test
    void theShellIsAskedToRecycleAndToWarnBeforeARealDelete() {
        assertEquals(WindowsRecycleBin.FOF_ALLOWUNDO, WindowsRecycleBin.FLAGS & WindowsRecycleBin.FOF_ALLOWUNDO);
        assertEquals(
                WindowsRecycleBin.FOF_WANTNUKEWARNING,
                WindowsRecycleBin.FLAGS & WindowsRecycleBin.FOF_WANTNUKEWARNING,
                "too large for the bin, or the bin switched off: Windows asks, it does not destroy quietly");
    }

    @Test
    void thePathIsAListOfOneEndedByTwoNuls() {
        Memory memory = WindowsRecycleBin.doubleNulTerminated("C:\\a.txt");
        assertEquals((8 + 2L) * Native.WCHAR_SIZE, memory.size());
        assertEquals("C:\\a.txt", memory.getWideString(0));
        for (long i = 8L * Native.WCHAR_SIZE; i < memory.size(); i++) {
            assertEquals(0, memory.getByte(i), "byte " + i);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void offWindowsThereIsNoShellToReach() {
        assertNull(WindowsRecycleBin.system());
    }

    /** The real thing, on a Windows machine: the file leaves its folder for the Recycle Bin. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAFileAndAFolderReallyGoToTheRecycleBin() throws Exception {
        WindowsRecycleBin bin = WindowsRecycleBin.system();
        assertNotNull(bin, "shell32 and kernel32 load on Windows");
        Path file = Files.writeString(dir.resolve("editora-recycle-test.txt"), "x");
        Path folder = Files.createDirectory(dir.resolve("editora-recycle-test"));
        Files.writeString(folder.resolve("inside.txt"), "x");
        // A CI runner's temp folder is on a fixed drive; a developer's might be a RAM disk or a share.
        org.junit.jupiter.api.Assumptions.assumeTrue(bin.accepts(file), "the temp folder is on a fixed drive");
        bin.trash(file);
        assertFalse(Files.exists(file), "the file left its folder");
        bin.trash(folder);
        assertFalse(Files.exists(folder), "and so did the folder, with what was in it");
    }
}
