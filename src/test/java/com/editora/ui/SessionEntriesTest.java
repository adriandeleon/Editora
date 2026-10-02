package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionEntriesTest {

    @Test
    void aFileDeletedFromAFolderThatIsStillThereIsDropped(@TempDir Path dir) {
        Path deleted = dir.resolve("deleted.txt");
        assertFalse(SessionEntries.keepUnreadable(deleted, Files::isDirectory, Files::notExists));
    }

    @Test
    void aFileWhoseFolderIsMissingIsKept(@TempDir Path dir) {
        // What an unmounted volume looks like: the whole tree is gone, which says nothing about the file.
        Path onMissingVolume = dir.resolve("not-mounted").resolve("project").resolve("Main.java");
        assertTrue(SessionEntries.keepUnreadable(onMissingVolume, Files::isDirectory, Files::notExists));
    }

    @Test
    void aFileThatCannotBeExaminedIsKept() {
        Path file = Path.of("/somewhere/secret.txt");
        // The folder is there, but neither exists nor notExists can be established (permission revoked).
        assertTrue(SessionEntries.keepUnreadable(file, p -> true, p -> false));
    }

    @Test
    void anUnresolvableEntryIsDroppedAsBefore() {
        assertFalse(SessionEntries.keepUnreadable(null, p -> true, p -> true));
    }

    @Test
    void retainedEntriesFollowTheOpenTabsWithoutDuplicates() {
        WorkspaceState.OpenFile a = new WorkspaceState.OpenFile("/a", 3, false);
        WorkspaceState.OpenFile b = new WorkspaceState.OpenFile("/b", 0, true);
        WorkspaceState.OpenFile missing = new WorkspaceState.OpenFile("/vol/x", 42, true, 1);
        WorkspaceState.OpenFile reopened = new WorkspaceState.OpenFile("/a", 99, false);

        List<WorkspaceState.OpenFile> merged = SessionEntries.withMissing(List.of(a, b), List.of(missing, reopened));

        assertEquals(List.of(a, b, missing), merged, "tab order first; an entry with a live tab is not repeated");
        assertEquals(42, merged.get(2).getCaret(), "the retained entry keeps its caret, pin and group");
        assertTrue(merged.get(2).isPinned());
        assertEquals(1, merged.get(2).getGroup());
    }

    @Test
    void nothingRetainedMeansTheListIsUntouched() {
        List<WorkspaceState.OpenFile> open = List.of(new WorkspaceState.OpenFile("/a", 0, false));
        assertSame(open, SessionEntries.withMissing(open, List.of()));
    }
}
