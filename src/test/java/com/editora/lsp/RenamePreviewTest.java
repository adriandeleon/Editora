package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenamePreviewTest {

    private static final Path A = Path.of("/w/A.java");
    private static final Path B = Path.of("/w/B.java");
    private static final Path C = Path.of("/w/C.java");

    private static LspTextEdit edit() {
        return new LspTextEdit(0, 0, 0, 1, "x");
    }

    private static WorkspaceEditMapper.FileEdit fileEdit(Path p, int n) {
        return new WorkspaceEditMapper.FileEdit(p, java.util.Collections.nCopies(n, edit()));
    }

    private static WorkspaceEditMapper.Mapped mapped(
            List<WorkspaceEditMapper.FileEdit> edits, List<WorkspaceEditMapper.FileRename> renames) {
        return new WorkspaceEditMapper.Mapped(edits, renames);
    }

    @Test
    void everyEditedFileBecomesARowWithItsCount() {
        var m = mapped(List.of(fileEdit(A, 3), fileEdit(B, 1)), List.of());

        List<RenamePreview.FileChange> rows = RenamePreview.summarise(m);

        assertEquals(2, rows.size());
        assertEquals(A, rows.get(0).file());
        assertEquals(3, rows.get(0).edits());
        assertEquals(1, rows.get(1).edits());
        assertEquals(4, RenamePreview.totalEdits(m));
    }

    /**
     * jdtls moves a .java file whose public class was renamed, and that file may receive no text edits of its
     * own. Folding renames only into existing rows would understate what the rename is about to do.
     */
    @Test
    void aFileRenamedWithoutEditsStillAppears() {
        var m = mapped(List.of(fileEdit(A, 2)), List.of(new WorkspaceEditMapper.FileRename(B, C, false)));

        List<RenamePreview.FileChange> rows = RenamePreview.summarise(m);

        assertEquals(2, rows.size(), "the moved file has its own row");
        assertEquals(B, rows.get(1).file());
        assertEquals(0, rows.get(1).edits());
        assertEquals(C, rows.get(1).renamedTo(), "and shows where it goes");
    }

    /** A file both edited and renamed is one row, not two. */
    @Test
    void anEditedAndRenamedFileIsASingleRow() {
        var m = mapped(List.of(fileEdit(A, 2)), List.of(new WorkspaceEditMapper.FileRename(A, C, false)));

        List<RenamePreview.FileChange> rows = RenamePreview.summarise(m);

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).edits());
        assertEquals(C, rows.get(0).renamedTo());
    }

    @Test
    void filteringKeepsOnlyTheChosenFiles() {
        var m = mapped(List.of(fileEdit(A, 2), fileEdit(B, 1)), List.of());

        var kept = RenamePreview.filter(m, Set.of(A));

        assertEquals(1, kept.edits().size());
        assertEquals(A, kept.edits().get(0).file());
    }

    /** Creates and deletes are the refactoring's scaffolding, not rows: the filter used to drop them. */
    @Test
    void filteringKeepsCreatesAndDeletes() {
        var m = new WorkspaceEditMapper.Mapped(
                List.of(fileEdit(A, 1)),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(B, false, true)),
                List.of(new WorkspaceEditMapper.FileDelete(B, false, false)));

        var kept = RenamePreview.filter(m, Set.of(A));

        assertEquals(m.creates(), kept.creates());
        assertEquals(m.deletes(), kept.deletes());
    }

    /**
     * The important filtering rule: moving a file whose edits were excluded would leave the rename
     * half-applied — a file moved but not updated — which is worse than leaving it alone entirely.
     */
    @Test
    void deselectingAFileAlsoDropsItsRename() {
        var m = mapped(
                List.of(fileEdit(A, 1), fileEdit(B, 1)), List.of(new WorkspaceEditMapper.FileRename(B, C, false)));

        var kept = RenamePreview.filter(m, Set.of(A));

        assertEquals(1, kept.edits().size());
        assertTrue(kept.renames().isEmpty(), "B's rename went with B's edits");
    }

    /**
     * A single-file rename is visible in the editor and one undo away, so interposing a confirmation adds
     * friction with nothing to confirm. The preview is for the edit you cannot see.
     */
    @Test
    void onlyRenamesReachingBeyondOneFileAreWorthPreviewing() {
        assertFalse(RenamePreview.worthPreviewing(mapped(List.of(fileEdit(A, 5)), List.of())), "one file");
        assertTrue(RenamePreview.worthPreviewing(mapped(List.of(fileEdit(A, 1), fileEdit(B, 1)), List.of())));
        assertTrue(
                RenamePreview.worthPreviewing(
                        mapped(List.of(fileEdit(A, 1)), List.of(new WorkspaceEditMapper.FileRename(A, C, false)))),
                "a file move is never invisible-safe");
    }

    @Test
    void nullAndEmptyAreHandled() {
        assertEquals(List.of(), RenamePreview.summarise(null));
        assertFalse(RenamePreview.worthPreviewing(null));
        assertEquals(0, RenamePreview.totalEdits(null));
        assertEquals(List.of(), RenamePreview.summarise(mapped(List.of(), List.of())));
    }

    // --- deletes and overwrites are rows the user can see and untick ---------------------------------------

    private static final Path DIR = Path.of("/w/p");
    private static final Path IN_DIR = Path.of("/w/p/B.java");

    /** The probe's edit: move two files out of a package, replace an existing file, delete the package. */
    private static WorkspaceEditMapper.Mapped destructive() {
        return new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(
                        new WorkspaceEditMapper.FileRename(Path.of("/w/p/A.java"), Path.of("/w/q/A.java"), false),
                        new WorkspaceEditMapper.FileRename(IN_DIR, Path.of("/w/q/B.java"), false)),
                List.of(new WorkspaceEditMapper.FileCreate(C, true, false)),
                List.of(new WorkspaceEditMapper.FileDelete(DIR, true, false)));
    }

    private static final WorkspaceEditHazards.Disk DISK = new WorkspaceEditHazards.Disk() {
        @Override
        public boolean exists(Path path) {
            return !path.startsWith("/w/q");
        }

        @Override
        public boolean isDirectory(Path path) {
            return path.equals(DIR);
        }
    };

    @Test
    void deletesAndOverwritingCreatesAreRows() {
        List<RenamePreview.FileChange> rows = RenamePreview.summarise(destructive(), DISK);

        assertEquals(4, rows.size());
        assertEquals(new RenamePreview.FileChange(C, 0, null, null, true), rows.get(3));
        assertEquals(
                new RenamePreview.FileChange(DIR, 0, null, WorkspaceEditHazards.Kind.DELETE_DIRECTORY, false),
                rows.get(2));
    }

    @Test
    void anOverwritingRenameIsMarkedOnTheRowOfTheFileThatMoves() {
        var m = mapped(List.of(), List.of(new WorkspaceEditMapper.FileRename(A, B, true)));

        assertEquals(List.of(new RenamePreview.FileChange(A, 0, B, null, true)), RenamePreview.summarise(m, DISK));
    }

    @Test
    void anEditedFileThatIsThenDeletedIsOneRow() {
        var m = new WorkspaceEditMapper.Mapped(
                List.of(fileEdit(A, 2)),
                List.of(),
                List.of(),
                List.of(new WorkspaceEditMapper.FileDelete(A, false, false)));

        assertEquals(
                List.of(new RenamePreview.FileChange(A, 2, null, WorkspaceEditHazards.Kind.DELETE_FILE, false)),
                RenamePreview.summarise(m, DISK));
    }

    @Test
    void anUntickedDeleteOrOverwriteIsDropped() {
        var m = destructive();
        Set<Path> listed = new java.util.LinkedHashSet<>();
        RenamePreview.summarise(m, DISK).forEach(row -> listed.add(row.file()));

        var kept = RenamePreview.filter(m, Set.of(Path.of("/w/p/A.java"), IN_DIR), listed);

        assertEquals(2, kept.renames().size());
        assertTrue(kept.creates().isEmpty(), "the unticked overwrite must not truncate the file");
        assertTrue(kept.deletes().isEmpty(), "the unticked delete must not run");
    }

    @Test
    void aTickedDeleteThatCoversAnUntickedFileIsReported() {
        var m = destructive();
        Set<Path> listed = new java.util.LinkedHashSet<>();
        RenamePreview.summarise(m, DISK).forEach(row -> listed.add(row.file()));
        Set<Path> keep = Set.of(Path.of("/w/p/A.java"), C, DIR); // B.java unticked: it stays in /w/p

        var kept = RenamePreview.filter(m, keep, listed);

        assertEquals(List.of(IN_DIR), RenamePreview.excludedButDeleted(kept, keep, listed));
        assertEquals(List.of(), RenamePreview.excludedButDeleted(kept, listed, listed), "everything ticked");
    }

    @Test
    void aSingleFileRenameThatDestroysSomethingIsWorthPreviewing() {
        var plain = mapped(List.of(fileEdit(A, 1)), List.of());
        var deleting = new WorkspaceEditMapper.Mapped(
                List.of(fileEdit(A, 1)),
                List.of(),
                List.of(),
                List.of(new WorkspaceEditMapper.FileDelete(B, false, false)));

        assertFalse(RenamePreview.worthPreviewing(plain, DISK));
        assertTrue(RenamePreview.worthPreviewing(deleting, DISK));
    }
}
