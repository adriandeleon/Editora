package com.editora.ui;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.editora.config.HistoryRevision;
import com.editora.ui.HistoryCoordinator.RestoreResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions of {@link HistoryCoordinator}: what a restore reports, what a picker row says, how buckets merge. */
class HistoryCoordinatorTextTest {

    private static HistoryRevision rev(String path, long time, String sha) {
        return new HistoryRevision(path, time, 10, sha, HistoryRevision.REASON_SAVE);
    }

    @Test
    void everyRestoreOutcomeHasItsOwnMessageAndOnlyAnUnreadableSnapshotSaysSo() {
        assertEquals("status.history.restored", HistoryCoordinator.restoreMessageKey(RestoreResult.RESTORED));
        assertNull(HistoryCoordinator.restoreMessageKey(RestoreResult.CANCELLED), "the user knows they cancelled");
        assertNull(HistoryCoordinator.restoreMessageKey(RestoreResult.INVALID_REQUEST));
        Set<RestoreResult> saysUnreadable = EnumSet.allOf(RestoreResult.class).stream()
                .filter(result -> "status.history.restoreFailed".equals(HistoryCoordinator.restoreMessageKey(result)))
                .collect(Collectors.toSet());
        assertEquals(Set.of(RestoreResult.CONTENT_UNAVAILABLE), saysUnreadable);
        assertEquals(
                "status.history.restoreWriteFailed", HistoryCoordinator.restoreMessageKey(RestoreResult.WRITE_FAILED));
        assertEquals(
                "status.history.restoreReadOnly", HistoryCoordinator.restoreMessageKey(RestoreResult.APPLY_FAILED));
        assertEquals(
                "status.history.restoreNotPreserved",
                HistoryCoordinator.restoreMessageKey(RestoreResult.NOT_PRESERVED));
        for (RestoreResult changed :
                List.of(RestoreResult.TARGET_CHANGED, RestoreResult.SUPERSEDED, RestoreResult.BUFFER_CHANGED)) {
            assertEquals("status.history.restoreChanged", HistoryCoordinator.restoreMessageKey(changed));
        }
    }

    @Test
    void aRecentChangesRowLeadsWithTheTimeAndNamesTheFileRelativeToTheProject() {
        assertEquals("16:40  ·  Saved", HistoryCoordinator.recentRowText("16:40", "", "Saved"));
        assertEquals("16:40  ·  Saved", HistoryCoordinator.recentRowText("16:40", null, "Saved"));
        assertEquals(
                "16:40  ·  before refactor", HistoryCoordinator.recentRowText("16:40", "before refactor", "Label"));

        Path root = Path.of("/work/app").toAbsolutePath();
        String inside = root.resolve("src").resolve("index.ts").toString();
        assertEquals(Path.of("src", "index.ts").toString(), HistoryCoordinator.recentDetail(inside, root, null));
        assertEquals(
                Path.of("src", "index.ts") + "  ·  deleted", HistoryCoordinator.recentDetail(inside, root, "deleted"));
        String outside = Path.of("/elsewhere/notes.txt").toAbsolutePath().toString();
        assertEquals(outside, HistoryCoordinator.recentDetail(outside, root, null), "outside the project: its path");
        assertEquals(inside, HistoryCoordinator.recentDetail(inside, null, null), "no project: its path");
        assertEquals(root.toString(), HistoryCoordinator.recentDetail(root.toString(), root, null));
        assertEquals("\0bad", HistoryCoordinator.recentDetail("\0bad", root, null), "shown as recorded");
    }

    @Test
    void aFilesRevisionsFromSeveralBucketsAreOneNewestFirstListWithoutRepeats() {
        HistoryRevision a = rev("/w/f.txt", 300, "a");
        HistoryRevision b = rev("/w/f.txt", 200, "b");
        HistoryRevision c = rev("/w/f.txt", 100, "c");
        Map<String, List<HistoryRevision>> own = new LinkedHashMap<>();
        Map<String, List<HistoryRevision>> other = new LinkedHashMap<>();
        Map<String, List<HistoryRevision>> third = new LinkedHashMap<>();
        own.put("/w/f.txt", List.of(a, c));
        other.put("/w/f.txt", List.of(b, c));
        other.put("/w/g.txt", List.of(rev("/w/g.txt", 50, "g")));
        third.put("/w/f.txt", List.of());

        assertEquals(List.of(a, b, c), HistoryCoordinator.mergedRevisions(List.of(own, other, third), "/w/f.txt"));
        assertSame(
                own.get("/w/f.txt"),
                HistoryCoordinator.mergedRevisions(List.of(own, third), "/w/f.txt"),
                "one bucket knows the file: its list, not a copy");
        assertEquals(List.of(), HistoryCoordinator.mergedRevisions(List.of(own, other), "/w/none.txt"));
        assertTrue(HistoryCoordinator.mergedRevisions(List.of(), "/w/f.txt").isEmpty());
    }
}
