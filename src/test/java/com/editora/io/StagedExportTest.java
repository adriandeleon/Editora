package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An export that fails partway must not cost the user the file it was replacing: exports used to truncate
 * the destination first and write into it, so a full disk or a failing tool left neither version.
 */
class StagedExportTest {

    @TempDir
    Path dir;

    private List<Path> entries() throws IOException {
        try (var files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }

    @Test
    void aFailedExportLeavesThePreviousFileWholeAndNoStagingBehind() throws Exception {
        Path dest = Files.writeString(dir.resolve("report.html"), "last good export");

        IOException failure = assertThrows(
                IOException.class,
                () -> StagedExport.writeVia(dest, staging -> {
                    Files.writeString(staging, "half of the new ex"); // the write got this far…
                    throw new IOException("No space left on device");
                }));

        assertEquals("No space left on device", failure.getMessage());
        assertEquals("last good export", Files.readString(dest));
        assertEquals(List.of(dest), entries());
    }

    @Test
    void aCompleteExportReplacesTheFile() throws Exception {
        Path dest = Files.writeString(dir.resolve("data.csv"), "old");

        StagedExport.writeString(dest, "a,b\n1,2\n");

        assertEquals("a,b\n1,2\n", Files.readString(dest));
        assertEquals(List.of(dest), entries());
    }

    @Test
    void aNewFileIsCreatedIncludingItsFolder() throws Exception {
        Path dest = dir.resolve("out").resolve("new.json");

        StagedExport.write(dest, new byte[] {1, 2, 3});

        assertEquals(3, Files.size(dest));
        try (var files = Files.list(dest.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void theStagingFileKeepsTheDestinationNameSoToolsStillSeeTheExtension() throws Exception {
        Path dest = dir.resolve("diagram.pdf");
        try (StagedExport export = StagedExport.begin(dest)) {
            assertEquals("diagram.pdf", export.path().getFileName().toString());
            assertEquals(dir, export.path().getParent().getParent(), "staged beside the destination");
            assertFalse(Files.exists(dest));
        }
        assertTrue(entries().isEmpty(), "closing without a commit discards the staging directory");
    }

    @Test
    void committingWithNothingWrittenFailsAndKeepsTheDestination() throws Exception {
        Path dest = Files.writeString(dir.resolve("kept.pdf"), "kept");
        StagedExport export = StagedExport.begin(dest);

        assertThrows(IOException.class, export::commit, "a tool that exits 0 without output wrote nothing");

        assertEquals("kept", Files.readString(dest));
        assertEquals(List.of(dest), entries());
        assertThrows(IOException.class, export::commit, "an export is finished once");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aReplacedFileKeepsItsPermissions() throws Exception {
        Path dest = Files.writeString(dir.resolve("private.txt"), "old");
        Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("rw-------"));

        StagedExport.writeString(dest, "new");

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dest)));
    }
}
