package com.editora.process;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatedSaveTest {

    @Test
    void supportedOnLinuxAndMac() {
        assertTrue(ElevatedSave.supportedOnOs("Linux"));
        assertTrue(ElevatedSave.supportedOnOs("GNU/Linux 6.1"));
        assertTrue(ElevatedSave.supportedOnOs("Mac OS X"));
        assertFalse(ElevatedSave.supportedOnOs("Windows 11"));
        assertFalse(ElevatedSave.supportedOnOs(null));
    }

    @Test
    void osDetection() {
        assertTrue(ElevatedSave.isLinux("Linux"));
        assertFalse(ElevatedSave.isLinux("Mac OS X"));
        assertTrue(ElevatedSave.isMac("Mac OS X"));
        assertTrue(ElevatedSave.isMac("Darwin"));
        assertFalse(ElevatedSave.isMac("Windows 11"));
        assertFalse(ElevatedSave.isMac(null));
    }

    @Test
    void elevatedArgvPicksTheToolPerOs() {
        assertEquals(
                "pkexec",
                ElevatedSave.elevatedArgv("Linux", ElevatedSave.PKEXEC, Path.of("/a"), Path.of("/b"))
                        .get(0));
        assertEquals(
                "osascript",
                ElevatedSave.elevatedArgv("Mac OS X", ElevatedSave.PKEXEC, Path.of("/a"), Path.of("/b"))
                        .get(0));
        assertNull(ElevatedSave.elevatedArgv("Windows 11", ElevatedSave.PKEXEC, Path.of("/a"), Path.of("/b")));
    }

    @Test
    void osascriptArgvUsesAdminPrivilegesAndQuotedForm() {
        List<String> argv = ElevatedSave.osascriptArgv(Path.of("/tmp/x.tmp"), Path.of("/etc/hosts"));
        assertEquals("osascript", argv.get(0));
        // The AppleScript runs `do shell script … with administrator privileges` and shell-escapes the
        // paths via `quoted form of` (never interpolating them into the script).
        assertTrue(
                argv.stream().anyMatch(s -> s.contains("with administrator privileges")), "elevates via AppleScript");
        assertTrue(argv.stream().anyMatch(s -> s.contains("quoted form of")), "paths shell-escaped");
        // The two paths are passed as osascript argv (item 1/2 of argv), the last two entries.
        assertEquals("/tmp/x.tmp", argv.get(argv.size() - 2));
        assertEquals("/etc/hosts", argv.get(argv.size() - 1));
    }

    @Test
    void cancellationDetection() {
        // Linux: pkexec dismiss/not-authorized is exit 126.
        assertTrue(ElevatedSave.isCancellation("Linux", 126, ""));
        assertFalse(ElevatedSave.isCancellation("Linux", 1, "some error"));
        // macOS: osascript reports -128 / "User canceled" in stderr on cancel.
        assertTrue(ElevatedSave.isCancellation("Mac OS X", 1, "execution error: User canceled. (-128)"));
        assertFalse(ElevatedSave.isCancellation("Mac OS X", 1, "some other failure"));
    }

    @Test
    void argvCopiesSourceIntoTargetInPlaceAsRoot() {
        List<String> argv = ElevatedSave.pkexecArgv(ElevatedSave.PKEXEC, Path.of("/tmp/x.tmp"), Path.of("/etc/hosts"));
        assertEquals("pkexec", argv.get(0));
        assertEquals("/bin/sh", argv.get(1));
        assertEquals("-c", argv.get(2));
        assertEquals("cat \"$1\" > \"$2\"", argv.get(3), "in-place rewrite preserves owner/mode");
        // The two paths are the last two positional args ($1 and $2), never interpolated into the script.
        assertEquals("/tmp/x.tmp", argv.get(argv.size() - 2));
        assertEquals("/etc/hosts", argv.get(argv.size() - 1));
    }

    @Test
    void blankPkexecFallsBackToTheDefault() {
        assertEquals(
                "pkexec",
                ElevatedSave.pkexecArgv("", Path.of("/a"), Path.of("/b")).get(0));
        assertEquals(
                "pkexec",
                ElevatedSave.pkexecArgv(null, Path.of("/a"), Path.of("/b")).get(0));
        assertEquals(
                "/usr/bin/pkexec",
                ElevatedSave.pkexecArgv("/usr/bin/pkexec", Path.of("/a"), Path.of("/b"))
                        .get(0));
    }
}
