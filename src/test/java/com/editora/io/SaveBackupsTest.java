package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A backup that outlives its save is the only copy of a file's previous bytes: it must be found again. */
class SaveBackupsTest {

    @TempDir
    Path dir;

    @TempDir
    Path backups;

    /** A backup as a killed in-place write leaves it: the previous bytes, and the note naming the file. */
    private SaveBackups.Leftover leftover(Path target, String previous) throws IOException {
        Path backup = Files.createTempFile(backups, target.getFileName() + ".", SaveBackups.SUFFIX);
        Files.writeString(backup, previous);
        SaveBackups.noteTarget(backup, target);
        List<SaveBackups.Leftover> found = SaveBackups.scan(backups);
        return found.stream().filter(l -> l.backup().equals(backup)).findFirst().orElseThrow();
    }

    @Test
    void aMissingFolderHoldsNoBackups() {
        assertEquals(List.of(), SaveBackups.scan(backups.resolve("nope")));
        assertEquals(List.of(), SaveBackups.scan(null));
    }

    @Test
    void aTornFileIsReportedWithTheBackupThatCanRepairIt() throws IOException {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, ""); // truncated by the write that never finished
        SaveBackups.Leftover left = leftover(file, "previous contents\n");

        assertEquals(file.toString(), left.target());
        assertEquals(SaveBackups.State.DIFFERENT, SaveBackups.state(left));

        SaveBackups.restore(left, backups);

        assertEquals("previous contents\n", Files.readString(file));
        assertEquals(List.of(), SaveBackups.scan(backups), "a restored backup is not offered again");
        try (var rest = Files.list(backups)) {
            assertEquals(List.of(), rest.toList());
        }
    }

    @Test
    void aBackupEqualToItsFileIsNotWorthAsking() throws IOException {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "same\n");
        SaveBackups.Leftover left = leftover(file, "same\n");
        assertEquals(SaveBackups.State.SAME, SaveBackups.state(left));
    }

    @Test
    void aDeletedFileCanStillBeRestored() throws IOException {
        Path file = dir.resolve("gone.txt");
        SaveBackups.Leftover left = leftover(file, "previous contents\n");
        assertEquals(SaveBackups.State.TARGET_MISSING, SaveBackups.state(left));

        SaveBackups.restore(left, backups);

        assertEquals("previous contents\n", Files.readString(file));
    }

    @Test
    void aBackupWithoutItsNoteIsKeptAndReportedAsUnknown() throws IOException {
        Path backup = Files.createTempFile(backups, "old.txt.", SaveBackups.SUFFIX);
        Files.writeString(backup, "x");

        SaveBackups.Leftover left = SaveBackups.scan(backups).get(0);

        assertNull(left.target());
        assertEquals(SaveBackups.State.UNKNOWN_TARGET, SaveBackups.state(left));
        assertTrue(Files.exists(backup));
    }

    @Test
    void discardingRemovesTheBackupAndItsNote() throws IOException {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "new\n");
        SaveBackups.Leftover left = leftover(file, "old\n");

        SaveBackups.discard(left);

        try (var rest = Files.list(backups)) {
            assertEquals(List.of(), rest.toList());
        }
        assertEquals("new\n", Files.readString(file));
    }

    @Test
    void onlySettledBackupsAreCleanedUpUnasked() throws IOException {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "same\n");
        SaveBackups.Leftover fresh = leftover(file, "same\n");
        Instant now = Instant.now();
        // Another running editor may be half-way through this very save: its backup is seconds old.
        assertFalse(SaveBackups.settled(fresh, now));

        Files.setLastModifiedTime(fresh.backup(), FileTime.from(now.minus(Duration.ofHours(1))));
        SaveBackups.Leftover old = SaveBackups.scan(backups).get(0);
        assertTrue(SaveBackups.settled(old, now));
    }

    @Test
    void otherFilesInTheFolderAreNotBackups() throws IOException {
        Files.writeString(backups.resolve("remote-staging-leftovers.txt"), "x");
        assertEquals(List.of(), SaveBackups.scan(backups));
    }
}
