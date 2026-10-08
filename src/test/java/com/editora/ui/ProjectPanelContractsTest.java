package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.editora.editor.NoteDraft;
import com.editora.ui.ProjectPanel.DeleteApproval;
import com.editora.ui.ProjectPanel.DeleteOperations;
import com.editora.ui.ProjectPanel.MarkerActions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small contracts the Project panel hands to its window: what their defaults promise. No toolkit. */
class ProjectPanelContractsTest {

    @Test
    void deleteOperationsWithoutATrashNeverClaimOneAndNeverDeleteInItsName() {
        List<Path> deleted = new ArrayList<>();
        DeleteOperations plain = new DeleteOperations() {
            @Override
            public byte[] readAllBytes(Path file) {
                return new byte[0];
            }

            @Override
            public void delete(Path file) {
                deleted.add(file);
            }
        };
        Path file = Path.of("notes.txt");

        assertFalse(plain.movesToTrash(file));
        IOException refused = assertThrows(IOException.class, () -> plain.moveToTrash(file));
        assertTrue(refused.getMessage().contains("notes.txt"), refused.getMessage());
        assertTrue(deleted.isEmpty(), "asking for the trash must never fall back to deleting");
    }

    @Test
    void aDeleteApprovalKeepsItsOwnCopyOfTheCapturedBytesAndDropsEmptyEntries() {
        Path file = Path.of("a.txt");
        byte[] bytes = {1, 2, 3};
        Map<Path, byte[]> captured = new HashMap<>();
        captured.put(file, bytes);
        captured.put(Path.of("gone.txt"), null);
        captured.put(null, new byte[] {9});

        DeleteApproval approval = new DeleteApproval(true, captured);
        bytes[0] = 99;
        captured.clear();

        assertEquals(java.util.Set.of(file), approval.expectedBytes().keySet());
        assertArrayEquals(new byte[] {1, 2, 3}, approval.expectedBytes().get(file));
        assertThrows(
                UnsupportedOperationException.class,
                () -> approval.expectedBytes().put(file, new byte[0]));
        assertTrue(new DeleteApproval(false, null).expectedBytes().isEmpty());
        assertTrue(DeleteApproval.approved().permitted());
        assertFalse(DeleteApproval.denied().permitted());
    }

    @Test
    void markerActionsFallBackToTheFileLevelFormsAndReportNothingExtra() {
        List<String> calls = new ArrayList<>();
        MarkerActions actions = new MarkerActions() {
            @Override
            public boolean personalNotesEnabled() {
                return true;
            }

            @Override
            public boolean hasBookmarks(Path file) {
                return false;
            }

            @Override
            public boolean hasPersonalNotes(Path file) {
                return false;
            }

            @Override
            public void addBookmark(Path file) {
                calls.add("bookmark " + file);
            }

            @Override
            public void addPersonalNote(Path file) {
                calls.add("note " + file);
            }
        };
        Path file = Path.of("Main.java");

        actions.addBookmark(file, 12);
        actions.addPersonalNote(file, new NoteDraft(null, null));
        actions.updatePersonalNote(file, null, "ignored by a store that keeps no per-note bodies");

        assertEquals(List.of("bookmark Main.java", "note Main.java"), calls);
        assertEquals("", actions.personalNotesTooltip(file));
        assertEquals(List.of(), actions.personalNotes(file));
        assertTrue(actions.markedPaths().isEmpty());
    }

    @Test
    void storedPathKeysAreParsedAndUnparseableOnesDropped() {
        List<Path> paths = ProjectPanel.pathsOf(List.of("a/b.txt", "bad\0key"), List.of("c.txt"));

        assertEquals(List.of(Path.of("a/b.txt"), Path.of("c.txt")), paths);
        assertEquals(List.of(), ProjectPanel.pathsOf(List.of(), List.of()));
    }
}
