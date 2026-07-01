package com.editora.process;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatedSaveTest {

    @Test
    void supportedOnlyOnLinux() {
        assertTrue(ElevatedSave.supportedOnOs("Linux"));
        assertTrue(ElevatedSave.supportedOnOs("GNU/Linux 6.1"));
        assertFalse(ElevatedSave.supportedOnOs("Mac OS X"));
        assertFalse(ElevatedSave.supportedOnOs("Windows 11"));
        assertFalse(ElevatedSave.supportedOnOs(null));
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
