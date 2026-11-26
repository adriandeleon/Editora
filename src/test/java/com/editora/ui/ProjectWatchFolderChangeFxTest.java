package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Windows reports a watched folder's sub-folder as modified whenever something inside it changes. Passed on as a
 * change, the Markdown link graph and the symbol index took it for a folder whose contents were never reported
 * and walked the whole project again: on Windows a save in {@code .editora/} rebuilt both
 * (MarkdownBacklinksFxTest counted two builds there, one on Linux).
 */
@Tag("fx")
@Tag("windows-fx")
class ProjectWatchFolderChangeFxTest {

    @Test
    void aFoldersOwnModifiedEventIsNoChangeButANewFolderIs(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("docs"));
        Path file = Files.writeString(dir.resolve("a.md"), "a\n");

        assertNull(ProjectPanel.fsChange(folder, StandardWatchEventKinds.ENTRY_MODIFY));
        assertEquals(
                new ProjectPanel.FsChange(file, ProjectPanel.FsKind.CHANGED),
                ProjectPanel.fsChange(file, StandardWatchEventKinds.ENTRY_MODIFY));
        assertEquals(
                new ProjectPanel.FsChange(folder, ProjectPanel.FsKind.CREATED),
                ProjectPanel.fsChange(folder, StandardWatchEventKinds.ENTRY_CREATE));
        assertEquals(
                new ProjectPanel.FsChange(dir.resolve("gone"), ProjectPanel.FsKind.DELETED),
                ProjectPanel.fsChange(dir.resolve("gone"), StandardWatchEventKinds.ENTRY_DELETE));
    }
}
