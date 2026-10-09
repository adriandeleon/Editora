package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The File Information panel's disk facts, read on a worker thread: what a file reports, and what a gone one does. */
class FileFactsTest {

    private static final String DASH = "–";

    @Test
    void aFileReportsItsSizeTimesAndPermissions(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "fifteen bytes!\n");
        FileInformationPanel.FileFacts facts = FileInformationPanel.FileFacts.read(file);
        assertEquals("15 bytes", facts.size());
        assertTrue(facts.modified().matches(".*\\d.*"), "a real time: " + facts.modified());
        assertFalse(DASH.equals(facts.created()));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertFalse(DASH.equals(facts.owner()));
            assertTrue(facts.permissions().matches("\\d{3} \\(-[rwx-]{9}\\)"), facts.permissions());
        }
    }

    @Test
    void posixPermissionsAreShownInOctalAndSymbols(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("script.sh"), "#!/bin/sh\n");
        Assumptions.assumeTrue(
                Files.getFileStore(file).supportsFileAttributeView("posix"), "a POSIX filesystem is needed");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x--x"));
        assertEquals(
                "751 (-rwxr-x--x)", FileInformationPanel.FileFacts.read(file).permissions());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(
                "644 (-rw-r--r--)", FileInformationPanel.FileFacts.read(file).permissions());
    }

    @Test
    void aFileThatIsGoneHasNothingToShow(@TempDir Path dir) {
        FileInformationPanel.FileFacts facts = FileInformationPanel.FileFacts.read(dir.resolve("deleted.txt"));
        assertEquals(FileInformationPanel.FileFacts.NONE, facts);
        assertEquals(DASH, facts.size());
        assertEquals(DASH, facts.owner());
    }
}
