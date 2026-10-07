package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceEditJournal}: a workspace-edit transaction that died between staging and commit can be
 * found again and put back, without overwriting or deleting anything on the way.
 *
 * <p>{@code abandon()} stands in for the process dying: it leaves exactly what a crash leaves on disk (the
 * journal and the staged files) and drops the in-process claim a new process would not have.
 */
class WorkspaceEditJournalTest {

    @TempDir
    Path root;

    private Path journals() {
        return root.resolve("journals");
    }

    private Path project() throws Exception {
        return Files.createDirectories(root.resolve("project"));
    }

    @Test
    void aFinishedTransactionLeavesNothingBehind() throws Exception {
        Path file = Files.writeString(project().resolve("A.txt"), "a");
        Path stage = project().resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);
        journal.committed();
        Files.delete(stage);
        journal.close();

        assertEquals(List.of(), WorkspaceEditJournal.pending(journals()));
        try (var left = Files.list(journals())) {
            assertEquals(0, left.count(), "the journal is removed with the transaction");
        }
    }

    @Test
    void aRunningTransactionIsNotOfferedForRecovery() throws Exception {
        Path file = Files.writeString(project().resolve("A.txt"), "a");
        Path stage = project().resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);

        assertEquals(List.of(), WorkspaceEditJournal.pending(journals()), "another window's live transaction");
        journal.close();
    }

    @Test
    void anInterruptedDeleteIsFoundAndRestored() throws Exception {
        Path file = Files.writeString(project().resolve("A.txt"), "precious");
        Path stage = project().resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);
        journal.abandon(); // the process dies here

        List<WorkspaceEditJournal.Interrupted> pending = WorkspaceEditJournal.pending(journals());
        assertEquals(1, pending.size());
        var interrupted = pending.get(0);
        assertFalse(interrupted.committed());
        assertEquals(List.of(stage), interrupted.leftovers());
        assertEquals(List.of(file), interrupted.originals());
        assertTrue(interrupted.touches(project()));
        assertFalse(interrupted.touches(root.resolve("elsewhere")));
        assertEquals(List.of(), WorkspaceEditJournal.pending(journals()), "offered once per process");

        var outcome = interrupted.restore();

        assertEquals(List.of(file), outcome.restored());
        assertEquals(List.of(), outcome.leftInPlace());
        assertEquals("precious", Files.readString(file));
        assertFalse(Files.exists(stage));
        try (var left = Files.list(journals())) {
            assertEquals(0, left.count(), "a fully restored transaction is forgotten");
        }
    }

    @Test
    void restoreNeverOverwritesAFileThatAppearedMeanwhile() throws Exception {
        Path file = Files.writeString(project().resolve("A.txt"), "old");
        Path stage = project().resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);
        journal.abandon();
        Files.writeString(file, "written after the crash");

        var interrupted = WorkspaceEditJournal.pending(journals()).get(0);
        var outcome = interrupted.restore();

        assertEquals(List.of(), outcome.restored());
        assertEquals(List.of(stage), outcome.leftInPlace());
        assertEquals("written after the crash", Files.readString(file));
        assertEquals("old", Files.readString(stage), "both copies survive");
        WorkspaceEditJournal.release(interrupted);
        assertEquals(1, WorkspaceEditJournal.pending(journals()).size(), "and it is offered again next time");
    }

    @Test
    void anInterruptedRenameAndOverwriteUnwindInOrder() throws Exception {
        Path from = Files.writeString(project().resolve("From.txt"), "moving");
        Path to = Files.writeString(project().resolve("To.txt"), "replaced");
        Path existing = Files.writeString(project().resolve("Existing.txt"), "truncated by a create");
        Path stage = project().resolve(".editora-lsp-1.source");
        Path backup = project().resolve(".editora-lsp-2.destination");
        Path createBackup = project().resolve(".editora-lsp-3.created-overwrite");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        // Staging order: creates, then renames.
        journal.creating(existing, createBackup);
        Files.move(existing, createBackup);
        Files.createFile(existing);
        journal.renaming(from, stage, to);
        Files.move(from, stage);
        journal.replacing(to, backup);
        Files.move(to, backup);
        Files.move(stage, to);
        journal.abandon();

        var interrupted = WorkspaceEditJournal.pending(journals()).get(0);
        var outcome = interrupted.restore();

        assertEquals(List.of(), outcome.leftInPlace());
        assertEquals("moving", Files.readString(from));
        assertEquals("replaced", Files.readString(to));
        assertEquals("truncated by a create", Files.readString(existing));
        try (var files = Files.list(project())) {
            assertEquals(3, files.count(), "no staging file is left");
        }
    }

    @Test
    void aMoveAnnouncedButNeverStartedIsNotUndone() throws Exception {
        Path from = Files.writeString(project().resolve("From.txt"), "still here");
        Path to = Files.writeString(project().resolve("To.txt"), "someone else's file");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.renaming(from, project().resolve(".editora-lsp-1.source"), to);
        journal.abandon(); // died between writing the line and moving the file

        assertEquals(List.of(), WorkspaceEditJournal.pending(journals()), "nothing is staged, so nothing to offer");
        assertEquals("still here", Files.readString(from));
        assertEquals("someone else's file", Files.readString(to));
    }

    @Test
    void aCommittedTransactionIsNotRestoredAndItsOldCopiesGoOnlyWhenAsked() throws Exception {
        Path file = Files.writeString(project().resolve("A.txt"), "deleted on purpose");
        Path stage = project().resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);
        journal.committed();
        journal.abandon(); // died before the old copy was removed

        var interrupted = WorkspaceEditJournal.pending(journals()).get(0);
        assertTrue(interrupted.committed());
        assertEquals(List.of(), interrupted.restore().restored(), "a decided edit is not undone");
        assertTrue(Files.exists(stage), "and nothing is removed without being asked");
        assertFalse(Files.exists(file));

        assertEquals(List.of(), interrupted.discardLeftovers());
        assertFalse(Files.exists(stage));
    }

    @Test
    void aTornLastLineIsIgnored() throws Exception {
        Path file = Files.writeString(project().resolve("A.txt"), "a");
        Path stage = project().resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);
        journal.abandon();
        Path journalFile;
        try (var files = Files.list(journals())) {
            journalFile = files.findFirst().orElseThrow();
        }
        Files.writeString(journalFile, Files.readString(journalFile) + "RENAME\t%2Fhalf");

        var interrupted = WorkspaceEditJournal.pending(journals()).get(0);
        assertEquals(List.of(file), interrupted.restore().restored());
    }

    @Test
    void aJournalWithoutADirectoryRecordsNothing() {
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(null);
        journal.deleting(root.resolve("a"), root.resolve("b"));
        journal.committed();
        journal.close();
        assertEquals(List.of(), WorkspaceEditJournal.pending(null));
    }
}
