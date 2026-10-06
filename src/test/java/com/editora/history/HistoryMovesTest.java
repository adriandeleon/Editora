package com.editora.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryMovesTest {

    private static HistoryRevision rev(String path, long timestamp, String sha) {
        return new HistoryRevision(path, timestamp, 10, sha, HistoryRevision.REASON_SAVE);
    }

    private static Map<String, Map<String, List<HistoryRevision>>> index() {
        Map<String, List<HistoryRevision>> project = new LinkedHashMap<>();
        project.put(
                "/p/src/a.txt", new ArrayList<>(List.of(rev("/p/src/a.txt", 30, "a3"), rev("/p/src/a.txt", 10, "a1"))));
        project.put("/p/src/deep/b.txt", new ArrayList<>(List.of(rev("/p/src/deep/b.txt", 20, "b2"))));
        project.put("/p/src2/c.txt", new ArrayList<>(List.of(rev("/p/src2/c.txt", 5, "c1"))));
        Map<String, List<HistoryRevision>> other = new LinkedHashMap<>();
        other.put("/p/src/a.txt", new ArrayList<>(List.of(rev("/p/src/a.txt", 40, "a4"))));
        Map<String, Map<String, List<HistoryRevision>>> index = new LinkedHashMap<>();
        index.put("project", project);
        index.put("other", other);
        return index;
    }

    @Test
    void aRenamedFileTakesItsRevisionsAlongInEveryProject() {
        var index = index();
        Set<String> before = HistoryRetention.liveHashes(index);

        assertTrue(HistoryMoves.rename(index, "/p/src/a.txt", "/p/src/z.txt", "/"));

        assertNull(index.get("project").get("/p/src/a.txt"));
        assertEquals(
                List.of(rev("/p/src/z.txt", 30, "a3"), rev("/p/src/z.txt", 10, "a1")),
                index.get("project").get("/p/src/z.txt"),
                "re-keyed, and each revision names the file's new path");
        assertEquals(List.of(rev("/p/src/z.txt", 40, "a4")), index.get("other").get("/p/src/z.txt"));
        assertEquals(before, HistoryRetention.liveHashes(index), "no revision — so no stored body — is dropped");
    }

    @Test
    void aRenamedFolderTakesTheHistoryOfEveryFileBelowIt() {
        var index = index();
        Set<String> before = HistoryRetention.liveHashes(index);

        assertTrue(HistoryMoves.rename(index, "/p/src", "/p/lib", "/"));

        Map<String, List<HistoryRevision>> project = index.get("project");
        assertEquals(Set.of("/p/lib/a.txt", "/p/lib/deep/b.txt", "/p/src2/c.txt"), project.keySet());
        assertEquals(
                "/p/lib/deep/b.txt", project.get("/p/lib/deep/b.txt").get(0).path());
        assertEquals("/p/src2/c.txt", project.get("/p/src2/c.txt").get(0).path(), "src2 is not below src");
        assertEquals(before, HistoryRetention.liveHashes(index));
    }

    @Test
    void historyAlreadyAtTheNewPathIsMergedNotReplaced() {
        var index = index();
        index.get("project")
                .put(
                        "/p/src/z.txt",
                        new ArrayList<>(List.of(rev("/p/src/z.txt", 20, "z2"), rev("/p/src/z.txt", 10, "a1"))));
        Set<String> before = HistoryRetention.liveHashes(index);

        assertTrue(HistoryMoves.rename(index, "/p/src/a.txt", "/p/src/z.txt", "/"));

        assertEquals(
                List.of(rev("/p/src/z.txt", 30, "a3"), rev("/p/src/z.txt", 20, "z2"), rev("/p/src/z.txt", 10, "a1")),
                index.get("project").get("/p/src/z.txt"),
                "newest first; the revision both had is listed once");
        assertEquals(before, HistoryRetention.liveHashes(index));
    }

    @Test
    void nothingMovesForAPathWithoutHistory() {
        var index = index();
        assertFalse(HistoryMoves.rename(index, "/p/none.txt", "/p/other.txt", "/"));
        assertFalse(HistoryMoves.rename(index, "/p/src/a.txt", "/p/src/a.txt", "/"));
        assertFalse(HistoryMoves.rename(null, "/a", "/b", "/"));
        assertEquals(index(), index);
    }

    @Test
    void saveAsGivesTheNewFileThePastOfTheOneItWasSavedFrom() {
        Map<String, List<HistoryRevision>> bucket = index().get("project");

        assertTrue(HistoryMoves.copy(bucket, "/p/src/a.txt", "/p/copy.txt"));

        assertEquals(List.of(rev("/p/copy.txt", 30, "a3"), rev("/p/copy.txt", 10, "a1")), bucket.get("/p/copy.txt"));
        assertEquals(
                List.of(rev("/p/src/a.txt", 30, "a3"), rev("/p/src/a.txt", 10, "a1")),
                bucket.get("/p/src/a.txt"),
                "the original keeps its own");
        assertFalse(HistoryMoves.copy(bucket, "/p/src/a.txt", "/p/copy.txt"), "copying again adds nothing");
        assertFalse(HistoryMoves.copy(bucket, "/p/none.txt", "/p/copy.txt"));
    }

    @Test
    void aKeyIsOnlyRenamedWhenItIsThePathOrBelowIt() {
        assertEquals("/p/lib", HistoryMoves.renamed("/p/src", "/p/src", "/p/lib", "/"));
        assertEquals("/p/lib/a.txt", HistoryMoves.renamed("/p/src/a.txt", "/p/src", "/p/lib", "/"));
        assertNull(HistoryMoves.renamed("/p/src2/a.txt", "/p/src", "/p/lib", "/"));
        assertNull(HistoryMoves.renamed("/p/srcfile", "/p/src", "/p/lib", "/"));
        assertEquals("C:\\lib\\a.txt", HistoryMoves.renamed("C:\\src\\a.txt", "C:\\src", "C:\\lib", "\\"));
    }
}
