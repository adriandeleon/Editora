package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import com.editora.io.SaveBackups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which leftover save backups a launch asks about, removes, or leaves alone. */
class SaveBackupRecoveryTest {

    @TempDir
    Path dir;

    @TempDir
    Path backups;

    private Path backupOf(Path target, String previous, Instant written) throws IOException {
        Path backup = Files.createTempFile(backups, target.getFileName() + ".", SaveBackups.SUFFIX);
        Files.writeString(backup, previous);
        Files.writeString(backup.resolveSibling(backup.getFileName() + ".target"), target.toString());
        Files.setLastModifiedTime(backup, FileTime.from(written));
        return backup;
    }

    @Test
    void theDecisionFollowsTheStateOfTheFile() {
        assertEquals(
                SaveBackupRecovery.Action.OFFER_RESTORE,
                SaveBackupRecovery.actionFor(SaveBackups.State.DIFFERENT, false));
        assertEquals(
                SaveBackupRecovery.Action.OFFER_RESTORE,
                SaveBackupRecovery.actionFor(SaveBackups.State.TARGET_MISSING, true));
        assertEquals(SaveBackupRecovery.Action.DISCARD, SaveBackupRecovery.actionFor(SaveBackups.State.SAME, true));
        assertEquals(SaveBackupRecovery.Action.LEAVE, SaveBackupRecovery.actionFor(SaveBackups.State.SAME, false));
        assertEquals(SaveBackupRecovery.Action.REPORT, SaveBackupRecovery.actionFor(SaveBackups.State.REMOTE, true));
        assertEquals(
                SaveBackupRecovery.Action.REPORT, SaveBackupRecovery.actionFor(SaveBackups.State.UNKNOWN_TARGET, true));
    }

    @Test
    void onlyAFileThatStillExistsCanBeKeptInsteadOfItsBackup() {
        assertEquals("dialog.saveBackup.keep", SaveBackupRecovery.discardLabelKey(SaveBackups.State.DIFFERENT));
        for (SaveBackups.State state : SaveBackups.State.values()) {
            if (state != SaveBackups.State.DIFFERENT) {
                assertEquals(
                        "dialog.saveBackup.delete",
                        SaveBackupRecovery.discardLabelKey(state),
                        state + ": there is no file to keep, the button deletes the backup");
            }
        }
    }

    @Test
    void aTornFileIsOfferedAndARedundantOldBackupIsRemoved() throws IOException {
        Instant now = Instant.now();
        Path torn = Files.writeString(dir.resolve("torn.txt"), "");
        Path tornBackup = backupOf(torn, "previous\n", now.minus(Duration.ofDays(2)));
        Path fine = Files.writeString(dir.resolve("fine.txt"), "same\n");
        Path redundant = backupOf(fine, "same\n", now.minus(Duration.ofDays(2)));
        Path inFlight = backupOf(fine, "same\n", now);

        var offers = SaveBackupRecovery.pending(backups, now);

        assertEquals(1, offers.size(), "only the torn file needs the user");
        assertTrue(Files.exists(tornBackup), "and its backup is untouched until they answer");
        assertFalse(Files.exists(redundant), "a settled backup equal to its file is cleaned up");
        assertTrue(Files.exists(inFlight), "a fresh one may belong to a save that is still running");
        assertEquals("", Files.readString(torn), "nothing is restored without being asked");
    }
}
