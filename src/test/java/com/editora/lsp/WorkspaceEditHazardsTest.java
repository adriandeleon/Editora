package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.lsp.WorkspaceEditHazards.Hazard;
import com.editora.lsp.WorkspaceEditHazards.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link WorkspaceEditHazards}: which operations of a workspace edit destroy something already on disk. */
class WorkspaceEditHazardsTest {

    private static final Path DIR = Path.of("/w/p");
    private static final Path A = Path.of("/w/p/A.java");
    private static final Path B = Path.of("/w/B.java");
    private static final Path NEW = Path.of("/w/New.java");

    /** A disk holding exactly these files and folders. */
    private static WorkspaceEditHazards.Disk disk(Set<Path> files, Set<Path> folders) {
        return new WorkspaceEditHazards.Disk() {
            @Override
            public boolean exists(Path path) {
                return files.contains(path) || folders.contains(path);
            }

            @Override
            public boolean isDirectory(Path path) {
                return folders.contains(path);
            }
        };
    }

    private static WorkspaceEditMapper.Mapped mapped(
            List<WorkspaceEditMapper.FileRename> renames,
            List<WorkspaceEditMapper.FileCreate> creates,
            List<WorkspaceEditMapper.FileDelete> deletes) {
        return new WorkspaceEditMapper.Mapped(List.of(), renames, creates, deletes);
    }

    @Test
    void anExistingFileOrFolderDeleteIsAHazard() {
        var m = mapped(
                List.of(),
                List.of(),
                List.of(
                        new WorkspaceEditMapper.FileDelete(B, false, false),
                        new WorkspaceEditMapper.FileDelete(DIR, true, false)));

        assertEquals(
                List.of(new Hazard(B, Kind.DELETE_FILE, null), new Hazard(DIR, Kind.DELETE_DIRECTORY, null)),
                WorkspaceEditHazards.of(m, disk(Set.of(A, B), Set.of(DIR))));
    }

    @Test
    void theEditsOwnScaffoldingIsNotAHazard() {
        // jdtls's package rename: create a placeholder, delete it again.
        var m = mapped(
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(NEW, false, true)),
                List.of(
                        new WorkspaceEditMapper.FileDelete(NEW, false, false),
                        new WorkspaceEditMapper.FileDelete(Path.of("/w/Gone.java"), false, true),
                        new WorkspaceEditMapper.FileDelete(DIR, false, false))); // non-recursive: empty or refused

        assertEquals(List.of(), WorkspaceEditHazards.of(m, disk(Set.of(B), Set.of(DIR))));
        assertEquals(
                List.of(),
                WorkspaceEditHazards.of(m, WorkspaceEditHazards.Disk.ASSUME_FILES).stream()
                        .filter(h -> h.path().equals(NEW))
                        .toList());
    }

    @Test
    void onlyACreateThatReplacesAnExistingFileIsAHazard() {
        var m = mapped(
                List.of(),
                List.of(
                        new WorkspaceEditMapper.FileCreate(B, true, false),
                        new WorkspaceEditMapper.FileCreate(A, true, true), // ignoreIfExists wins in staging
                        new WorkspaceEditMapper.FileCreate(NEW, true, false)), // nothing there to replace
                List.of());

        assertEquals(
                List.of(new Hazard(B, Kind.OVERWRITE, null)),
                WorkspaceEditHazards.of(m, disk(Set.of(A, B), Set.of(DIR))));
    }

    @Test
    void anOverwritingRenameOntoAnExistingFileIsAHazardOfItsDestination() {
        var m = mapped(
                List.of(
                        new WorkspaceEditMapper.FileRename(A, B, true),
                        new WorkspaceEditMapper.FileRename(NEW, Path.of("/w/Free.java"), true)),
                List.of(),
                List.of());

        assertEquals(
                List.of(new Hazard(B, Kind.OVERWRITE, A)),
                WorkspaceEditHazards.of(m, disk(Set.of(A, B, NEW), Set.of(DIR))));
    }

    @Test
    void aSingleFileDeleteDoesNotNeedConfirmationButAFolderOrAnOverwriteDoes() {
        var m = mapped(
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(B, true, false)),
                List.of(
                        new WorkspaceEditMapper.FileDelete(A, false, false),
                        new WorkspaceEditMapper.FileDelete(DIR, true, false)));

        assertEquals(
                List.of(new Hazard(DIR, Kind.DELETE_DIRECTORY, null), new Hazard(B, Kind.OVERWRITE, null)),
                WorkspaceEditHazards.needingConfirmation(m, disk(Set.of(A, B), Set.of(DIR))));
    }
}
