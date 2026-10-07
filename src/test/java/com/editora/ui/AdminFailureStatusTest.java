package com.editora.ui;

import java.nio.file.Path;

import com.editora.i18n.Messages;
import com.editora.process.ElevatedSave;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed elevated save must say what became of the file, not just pass the script's stderr on. */
class AdminFailureStatusTest {

    private static final Path TARGET = Path.of("/etc/fstab");

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static String status(int exit, String stderr) {
        return FileWorkflowCoordinator.adminFailureStatus(
                TARGET, new FileWorkflowCoordinator.AdminResult(exit, stderr, -1, -1));
    }

    @Test
    void aKeptBackupIsNamed() {
        String status = status(73, "cat: write error: No space left on device\neditora-admin-save:backup-kept\n");
        assertTrue(status.contains(ElevatedSave.backupOf(TARGET).toString()), status);
        assertTrue(status.contains("No space left on device"), status);
        assertFalse(status.contains("editora-admin-save:"), "the marker is not shown: " + status);
        assertEquals(MessageLog.Severity.ERROR, StatusSeverity.of(status));
    }

    @Test
    void theOtherOutcomesEachHaveTheirOwnMessage() {
        assertTrue(status(72, "editora-admin-save:restored").contains("put back"));
        assertTrue(status(71, "cp: cannot create\neditora-admin-save:no-backup").contains("not changed"));
        assertTrue(status(74, "editora-admin-save:stale-backup")
                .contains(ElevatedSave.backupOf(TARGET).toString()));
        assertEquals("Administrator save failed: boom", status(1, "boom"));
        assertEquals("Administrator save failed: 3", status(3, ""));
    }
}
