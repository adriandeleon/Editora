package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.HistoryPurgeHold;
import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the Local History record/restore fix pass (R9, R17, E4, F10). */
class HistoryCoordinatorPureTest {

    /** R17: a closed file's current side is read as the editor reads it, not as raw strict UTF-8. */
    @Test
    void aClosedFilesTextIsInTheEditorsForm(@TempDir Path temp) throws Exception {
        Path latin = Files.write(temp.resolve("latin.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n'});
        assertEquals("café\n", HistoryCoordinator.closedFileText(latin, "latin1"));
        assertFalse(HistoryCoordinator.closedFileText(latin, null).isEmpty(), "not shown as an empty file");

        Path crlf = Files.write(temp.resolve("crlf.txt"), "﻿one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("one\ntwo\n", HistoryCoordinator.closedFileText(crlf, null), "line feeds, no byte-order mark");
    }

    /** F10: Recent Changes lists the window's project only; a No-Project window lists everything. */
    @Test
    void recentChangesKeepTheProjectsFiles(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("proj");
        String inside = root.resolve("src/a.txt").toString();
        String sibling = temp.resolve("proj-other/b.txt").toString(); // a name that starts the same, outside
        String outside = temp.resolve("elsewhere/c.txt").toString();
        Map<String, List<HistoryRevision>> index = new LinkedHashMap<>();
        for (String key : List.of(inside, sibling, outside)) {
            index.put(key, List.of(new HistoryRevision(key, 1L, 1, "s", HistoryRevision.REASON_SAVE)));
        }
        assertEquals(
                List.of(inside),
                List.copyOf(HistoryCoordinator.projectFiles(index, root).keySet()));
        assertEquals(index, HistoryCoordinator.projectFiles(index, null));
    }

    /** E4: the status after a purge names what keeps the bodies on disk, each reason its own words. */
    @Test
    void thePurgeStatusNamesTheRealReason() {
        assertEquals("status.history.purged", HistoryCoordinator.purgedStatusKey(HistoryPurgeHold.NONE));
        java.util.Set<String> said = new java.util.HashSet<>();
        for (HistoryPurgeHold hold : HistoryPurgeHold.values()) {
            String key = HistoryCoordinator.purgedStatusKey(hold);
            String text = tr(key, 3);
            assertNotEquals(key, text, "a catalog entry for " + key);
            assertTrue(text.contains("3"));
            said.add(text);
        }
        assertEquals(HistoryPurgeHold.values().length, said.size(), "no two reasons say the same");
    }

    /** B10 / R9: when snapshots are shared, the question offers to remove them everywhere. */
    @Test
    void aSharedPurgeOffersToRemoveTheSharedSnapshotsToo() {
        Confirm.Choice choice = HistoryCoordinator.purgeSharedFileChoice(4, "notes.md", 2);
        assertEquals(
                List.of(tr("dialog.history.purge.button"), tr("dialog.history.purge.everywhere")), choice.answers());
        assertTrue(String.valueOf(choice.message()).contains(tr("dialog.history.purgeFile.sharedChoice", 2)));
        assertTrue(String.valueOf(choice.message()).contains("notes.md"));
    }

    /** R13: a restore over a BOM-less UTF-16 file writes UTF-16 without a mark, as the file was; a marked one keeps it. */
    @Test
    void aRestoreKeepsTheFilesByteOrderMarkOrItsAbsence() {
        byte[] bare = "now\n".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(
                java.util.Arrays.toString("then\n".getBytes(StandardCharsets.UTF_16LE)),
                java.util.Arrays.toString(HistoryCoordinator.restoredBytes("then\n", bare, "utf-16le")));
        byte[] marked = {(byte) 0xFF, (byte) 0xFE, 'n', 0, '\n', 0};
        byte[] restored = HistoryCoordinator.restoredBytes("t\n", marked, null);
        assertEquals(
                java.util.Arrays.toString(new byte[] {(byte) 0xFF, (byte) 0xFE, 't', 0, '\n', 0}),
                java.util.Arrays.toString(restored));
    }
}
