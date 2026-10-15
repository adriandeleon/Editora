package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WorkspaceFileSnapshot}: the disk as it was when taken, for the paths it took; the live disk otherwise. */
class WorkspaceFileSnapshotTest {

    @TempDir
    Path dir;

    @Test
    void whatWasCapturedIsAnsweredAsItWasThen() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "abc");
        Path folder = Files.createDirectories(dir.resolve("sub"));
        Path missing = dir.resolve("missing.txt");
        LspCoordinator.WorkspaceFileOperations live = LspCoordinator.WorkspaceFileOperations.SYSTEM;

        WorkspaceFileSnapshot snapshot = WorkspaceFileSnapshot.of(live, List.of(file, folder, missing));
        LspCoordinator.WorkspaceFileIdentity before = snapshot.identity(file);
        Files.writeString(file, "a much longer content than before");
        Files.writeString(missing, "made after the snapshot");

        assertTrue(snapshot.exists(file));
        assertTrue(snapshot.isRegularFile(file));
        assertTrue(snapshot.isDirectory(folder));
        assertEquals(3, snapshot.size(file), "as it was when taken");
        assertEquals(before, snapshot.identity(file));
        assertFalse(snapshot.exists(missing), "it did not exist then");
        assertThrows(NoSuchFileException.class, () -> snapshot.identity(missing));
    }

    @Test
    void aPathNotCapturedIsAskedOfTheLiveDisk() throws Exception {
        Path other = Files.writeString(dir.resolve("other.txt"), "hello");

        WorkspaceFileSnapshot snapshot =
                WorkspaceFileSnapshot.of(LspCoordinator.WorkspaceFileOperations.SYSTEM, List.of());

        assertTrue(snapshot.exists(other));
        assertEquals(5, snapshot.size(other));
        assertFalse(WorkspaceFileSnapshot.anyRemote(List.of(other)), "a local path needs no snapshot");
    }
}
